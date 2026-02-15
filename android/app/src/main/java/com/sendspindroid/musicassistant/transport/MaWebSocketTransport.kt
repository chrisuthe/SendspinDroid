package com.sendspindroid.musicassistant.transport

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Persistent WebSocket transport for the Music Assistant API.
 *
 * Used for LOCAL and PROXY connection modes. Opens a single WebSocket to
 * `ws://host:8095/ws` (or `wss://` for proxy), authenticates once, then
 * multiplexes all commands over the persistent connection.
 *
 * This replaces the previous fire-and-forget pattern where each API call
 * opened its own WebSocket, authenticated, sent one command, and closed.
 *
 * ## Connection Lifecycle
 * ```
 * connect(token) -> WS open -> receive ServerInfo -> send auth -> auth response -> Connected
 *                                                                                      |
 *                  sendCommand() / sendCommand() / sendCommand() / ...                  |
 *                                                                                      |
 * disconnect() -> WS close -> Disconnected
 * ```
 *
 * @param apiUrl WebSocket URL (e.g., "ws://192.168.1.100:8095/ws")
 */
class MaWebSocketTransport(
    private val apiUrl: String
) : MaApiTransport {

    companion object {
        private const val TAG = "MaWebSocketTransport"
        private const val AUTH_TIMEOUT_MS = 10000L
    }

    private val _state = MutableStateFlow<MaApiTransport.State>(MaApiTransport.State.Disconnected)
    override val state: StateFlow<MaApiTransport.State> = _state.asStateFlow()

    override var serverVersion: String? = null
        private set

    override var maServerId: String? = null
        private set

    private val multiplexer = MaCommandMultiplexer()
    private var webSocket: WebSocket? = null

    // Auth handshake deferred - completed when auth response received
    private var authDeferred: CompletableDeferred<JSONObject>? = null
    private var isLoginMode = false

    // OkHttp client configured for WebSocket (no read timeout, ping keepalive)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    // ========================================================================
    // MaApiTransport implementation
    // ========================================================================

    override suspend fun connect(token: String) {
        if (_state.value is MaApiTransport.State.Connected) {
            Log.d(TAG, "Already connected")
            return
        }

        _state.value = MaApiTransport.State.Connecting

        // Prepare the auth deferred before opening the WebSocket
        val deferred = CompletableDeferred<JSONObject>()
        authDeferred = deferred

        // Convert URL to WebSocket if needed
        val wsUrl = convertToWebSocketUrl(apiUrl)
        Log.d(TAG, "Connecting to MA API: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = httpClient.newWebSocket(request, MaWebSocketListener(token))

        // Wait for auth to complete (or fail)
        try {
            withTimeout(AUTH_TIMEOUT_MS) {
                deferred.await()
            }
            // If we get here, auth succeeded
        } catch (e: Exception) {
            // Clean up on failure
            disconnect()
            throw e
        }
    }

    override suspend fun connectWithCredentials(
        username: String,
        password: String
    ): MaApiTransport.LoginResult {
        if (_state.value is MaApiTransport.State.Connected) {
            throw IOException("Already connected - disconnect first")
        }

        _state.value = MaApiTransport.State.Connecting
        isLoginMode = true

        // Prepare the auth deferred before opening the WebSocket
        val deferred = CompletableDeferred<JSONObject>()
        authDeferred = deferred

        val wsUrl = convertToWebSocketUrl(apiUrl)
        Log.d(TAG, "Connecting to MA API for login: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = httpClient.newWebSocket(request, MaWebSocketListener(
            token = "",  // Not used in login mode
            loginUsername = username,
            loginPassword = password
        ))

        try {
            val response = withTimeout(AUTH_TIMEOUT_MS) {
                deferred.await()
            }

            // Extract token from the login response
            val resultObj = response.optJSONObject("result")
                ?: throw MaApiTransport.AuthenticationException("Invalid server response: no result")

            val token = resultObj.optString("access_token", "")
                .ifEmpty { resultObj.optString("token", "") }

            if (token.isBlank()) {
                throw MaApiTransport.AuthenticationException("Server response missing access token")
            }

            val userObj = resultObj.optJSONObject("user")
            val userId = userObj?.optString("user_id", "") ?: ""
            val userName = userObj?.optString("display_name", "")
                ?: userObj?.optString("username", username)
                ?: username

            Log.i(TAG, "Login successful for user: $userName via WebSocket")
            return MaApiTransport.LoginResult(
                accessToken = token,
                userId = userId,
                userName = userName
            )
        } catch (e: MaApiTransport.AuthenticationException) {
            disconnect()
            throw e
        } catch (e: Exception) {
            disconnect()
            throw e
        } finally {
            isLoginMode = false
        }
    }

    override suspend fun sendCommand(
        command: String,
        args: Map<String, Any>,
        timeoutMs: Long
    ): JSONObject {
        val ws = webSocket ?: throw IOException("MA API transport not connected")
        if (_state.value !is MaApiTransport.State.Connected) {
            throw IOException("MA API transport not in Connected state: ${_state.value}")
        }

        val (messageId, deferred) = multiplexer.registerCommand()

        // Build and send the command message
        val cmdMsg = JSONObject().apply {
            put("message_id", messageId)
            put("command", command)
            if (args.isNotEmpty()) {
                put("args", JSONObject(args))
            }
        }

        Log.d(TAG, "Sending command: $command (id=$messageId)")
        val sent = ws.send(cmdMsg.toString())
        if (!sent) {
            deferred.completeExceptionally(IOException("Failed to send command"))
            throw IOException("Failed to send command: WebSocket send returned false")
        }

        return withTimeout(timeoutMs) {
            deferred.await()
        }
    }

    override suspend fun httpProxy(
        method: String,
        path: String,
        headers: Map<String, String>,
        timeoutMs: Long
    ): MaApiTransport.HttpProxyResponse {
        // WebSocket transport can make direct HTTP requests,
        // so HTTP proxy is not needed. But we implement it for interface compliance.
        throw UnsupportedOperationException(
            "HTTP proxy not needed for WebSocket transport - use direct HTTP requests"
        )
    }

    override fun setEventListener(listener: MaApiTransport.EventListener?) {
        multiplexer.eventListener = listener
    }

    override fun disconnect() {
        Log.d(TAG, "Disconnecting")

        multiplexer.cancelAll("Transport disconnecting")
        authDeferred?.completeExceptionally(IOException("Transport disconnecting"))
        authDeferred = null

        webSocket?.close(1000, "Client disconnect")
        webSocket = null

        serverVersion = null
        maServerId = null
        isLoginMode = false

        _state.value = MaApiTransport.State.Disconnected
    }

    // ========================================================================
    // WebSocket Listener
    // ========================================================================

    private inner class MaWebSocketListener(
        private val token: String,
        private val loginUsername: String? = null,
        private val loginPassword: String? = null
    ) : WebSocketListener() {

        private var serverInfoReceived = false
        private var authMessageId: String? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected, waiting for server info...")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)

                // Phase 1: Receive ServerInfoMessage (first message from server)
                if (!serverInfoReceived && json.has("server_id") && json.has("server_version")) {
                    serverInfoReceived = true
                    serverVersion = json.optString("server_version")
                    maServerId = json.optString("server_id")

                    Log.d(TAG, "Server info received: v$serverVersion (id=$maServerId)")
                    _state.value = MaApiTransport.State.Authenticating

                    // Send auth or login command
                    val msgId = java.util.UUID.randomUUID().toString()
                    authMessageId = msgId

                    val authMsg = if (isLoginMode && loginUsername != null && loginPassword != null) {
                        JSONObject().apply {
                            put("message_id", msgId)
                            put("command", "auth/login")
                            put("args", JSONObject().apply {
                                put("username", loginUsername)
                                put("password", loginPassword)
                                put("device_name", "SendSpinDroid")
                            })
                        }
                    } else {
                        JSONObject().apply {
                            put("message_id", msgId)
                            put("command", "auth")
                            put("args", JSONObject().apply {
                                put("token", token)
                            })
                        }
                    }
                    webSocket.send(authMsg.toString())
                    return
                }

                // Phase 2: Auth response
                val msgId = json.optString("message_id", "")
                if (msgId == authMessageId && authMessageId != null) {
                    authMessageId = null

                    if (json.has("error_code")) {
                        val errorCode = json.optString("error_code", "unknown")
                        val details = json.optString("details", "Authentication failed")
                        Log.e(TAG, "Auth failed: $errorCode - $details")
                        _state.value = MaApiTransport.State.Error(details, isAuthError = true)
                        authDeferred?.completeExceptionally(
                            MaApiTransport.AuthenticationException(details)
                        )
                    } else {
                        Log.i(TAG, "Authenticated successfully")
                        _state.value = MaApiTransport.State.Connected
                        authDeferred?.complete(json)
                    }
                    return
                }

                // Phase 3: Normal operation - delegate to multiplexer
                multiplexer.onMessage(text)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            this@MaWebSocketTransport.webSocket = null

            if (_state.value is MaApiTransport.State.Connected) {
                // Unexpected disconnect
                multiplexer.cancelAll("WebSocket closed: $reason")
                _state.value = MaApiTransport.State.Error("Connection lost: $reason")
                multiplexer.eventListener?.onDisconnected("WebSocket closed: $code $reason")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            this@MaWebSocketTransport.webSocket = null

            val message = t.message ?: "Connection failed"
            multiplexer.cancelAll(message)

            if (_state.value is MaApiTransport.State.Connecting ||
                _state.value is MaApiTransport.State.Authenticating
            ) {
                _state.value = MaApiTransport.State.Error(message)
                authDeferred?.completeExceptionally(IOException(message))
            } else {
                _state.value = MaApiTransport.State.Error(message)
                multiplexer.eventListener?.onDisconnected(message)
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Ensure URL uses ws:// or wss:// scheme.
     */
    private fun convertToWebSocketUrl(url: String): String {
        return when {
            url.startsWith("ws://") || url.startsWith("wss://") -> url
            url.startsWith("https://") -> url.replaceFirst("https://", "wss://")
            url.startsWith("http://") -> url.replaceFirst("http://", "ws://")
            else -> "ws://$url"
        }
    }
}
