package com.sendspindroid.musicassistant.transport

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import org.webrtc.DataChannel
import java.io.IOException
import java.nio.ByteBuffer

/**
 * WebRTC DataChannel transport for the Music Assistant API.
 *
 * Used for REMOTE connection mode. Sends/receives MA API commands as JSON
 * text messages over a WebRTC DataChannel named "ma-api" on the same
 * PeerConnection that carries the Sendspin audio channel.
 *
 * The MA server's WebRTC gateway bridges this DataChannel transparently to
 * the local `ws://localhost:8095/ws` WebSocket API. Authentication and
 * command format are identical to a direct WebSocket connection.
 *
 * ## Also supports HTTP proxy
 * For images, previews, and other HTTP resources that can't be accessed
 * directly over WebRTC, the MA gateway supports a special message protocol:
 * - Client sends: `{type: "http-proxy-request", id, method, path, headers}`
 * - Server responds: `{type: "http-proxy-response", id, status, headers, body}`
 * where body is hex-encoded bytes.
 *
 * @param dataChannel The open WebRTC DataChannel (label "ma-api")
 */
class MaDataChannelTransport(
    private val dataChannel: DataChannel
) : MaApiTransport {

    companion object {
        private const val TAG = "MaDataChannelTransport"
        private const val AUTH_TIMEOUT_MS = 10000L
    }

    private val _state = MutableStateFlow<MaApiTransport.State>(MaApiTransport.State.Disconnected)
    override val state: StateFlow<MaApiTransport.State> = _state.asStateFlow()

    override var serverVersion: String? = null
        private set

    override var maServerId: String? = null
        private set

    override var baseUrl: String? = null
        private set

    private val multiplexer = MaCommandMultiplexer()

    // Auth handshake state
    private var authDeferred: CompletableDeferred<JSONObject>? = null
    private var authMessageId: String? = null
    private var serverInfoReceived = false

    // Token stored during connect() for use in the auth handshake callback
    @Volatile
    private var pendingAuthToken: String? = null

    // Credentials stored during connectWithCredentials() for the login handshake
    @Volatile
    private var pendingUsername: String? = null
    @Volatile
    private var pendingPassword: String? = null
    private var isLoginMode = false
    // Saved login response (for two-step auth: login → get token → auth with token)
    private var loginResponse: JSONObject? = null

    // Eagerly register observer to capture ServerInfoMessage even before connect() is called.
    // The MA gateway sends ServerInfo immediately when the DataChannel is bridged to the
    // local WebSocket. If the user takes time (e.g., typing credentials in a dialog),
    // we must not miss this message.
    init {
        dataChannel.registerObserver(MaApiChannelObserver())
        Log.d(TAG, "Observer registered eagerly on DataChannel")
    }

    // ========================================================================
    // MaApiTransport implementation
    // ========================================================================

    override suspend fun connect(token: String) {
        if (_state.value is MaApiTransport.State.Connected) {
            Log.d(TAG, "Already connected")
            return
        }

        if (dataChannel.state() != DataChannel.State.OPEN) {
            throw IOException("DataChannel is not open (state: ${dataChannel.state()})")
        }

        _state.value = MaApiTransport.State.Connecting
        pendingAuthToken = token

        // Prepare auth deferred
        val deferred = CompletableDeferred<JSONObject>()
        authDeferred = deferred

        // If ServerInfo was already received (buffered by eager observer),
        // send auth immediately. Otherwise wait for it.
        if (serverInfoReceived) {
            Log.d(TAG, "ServerInfo already received, sending auth immediately")
            val msgId = java.util.UUID.randomUUID().toString()
            authMessageId = msgId
            _state.value = MaApiTransport.State.Authenticating
            sendAuthCommand(msgId)
        } else {
            Log.d(TAG, "Waiting for ServerInfoMessage on DataChannel...")
        }

        try {
            withTimeout(AUTH_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (e: Exception) {
            _state.value = MaApiTransport.State.Error(e.message ?: "Auth failed")
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

        if (dataChannel.state() != DataChannel.State.OPEN) {
            throw IOException("DataChannel is not open (state: ${dataChannel.state()})")
        }

        _state.value = MaApiTransport.State.Connecting
        isLoginMode = true
        pendingUsername = username
        pendingPassword = password

        // Prepare auth deferred
        val deferred = CompletableDeferred<JSONObject>()
        authDeferred = deferred

        // If ServerInfo was already received (buffered by eager observer),
        // send login immediately. Otherwise wait for it.
        if (serverInfoReceived) {
            Log.d(TAG, "ServerInfo already received, sending login immediately")
            val msgId = java.util.UUID.randomUUID().toString()
            authMessageId = msgId
            _state.value = MaApiTransport.State.Authenticating
            sendAuthCommand(msgId)
        } else {
            Log.d(TAG, "Waiting for ServerInfoMessage on DataChannel (login mode)...")
        }

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

            Log.i(TAG, "Login successful for user: $userName via DataChannel")
            return MaApiTransport.LoginResult(
                accessToken = token,
                userId = userId,
                userName = userName
            )
        } catch (e: MaApiTransport.AuthenticationException) {
            throw e
        } catch (e: Exception) {
            _state.value = MaApiTransport.State.Error(e.message ?: "Login failed")
            throw e
        } finally {
            pendingUsername = null
            pendingPassword = null
        }
    }

    override suspend fun sendCommand(
        command: String,
        args: Map<String, Any>,
        timeoutMs: Long
    ): JsonObject {
        if (_state.value !is MaApiTransport.State.Connected) {
            throw IOException("MA API DataChannel transport not in Connected state: ${_state.value}")
        }
        if (dataChannel.state() != DataChannel.State.OPEN) {
            throw IOException("DataChannel is not open")
        }

        val (messageId, deferred) = multiplexer.registerCommand()

        val cmdMsg = JSONObject().apply {
            put("message_id", messageId)
            put("command", command)
            if (args.isNotEmpty()) {
                put("args", JSONObject(args))
            }
        }

        Log.d(TAG, "Sending command: $command (id=$messageId)")
        sendText(cmdMsg.toString())

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
        if (dataChannel.state() != DataChannel.State.OPEN) {
            throw IOException("DataChannel is not open")
        }

        val (requestId, deferred) = multiplexer.registerProxyRequest()

        val proxyMsg = JSONObject().apply {
            put("type", "http-proxy-request")
            put("id", requestId)
            put("method", method)
            put("path", path)
            if (headers.isNotEmpty()) {
                put("headers", JSONObject(headers))
            }
        }

        Log.d(TAG, "Sending HTTP proxy: $method $path (id=$requestId)")
        sendText(proxyMsg.toString())

        return withTimeout(timeoutMs) {
            deferred.await()
        }
    }

    override fun setEventListener(listener: MaApiTransport.EventListener?) {
        multiplexer.eventListener = listener
    }

    override fun disconnect() {
        Log.d(TAG, "Disconnecting DataChannel transport")

        multiplexer.cancelAll("Transport disconnecting")
        authDeferred?.completeExceptionally(IOException("Transport disconnecting"))
        authDeferred = null

        serverVersion = null
        maServerId = null
        baseUrl = null
        serverInfoReceived = false
        authMessageId = null
        pendingAuthToken = null
        pendingUsername = null
        pendingPassword = null
        isLoginMode = false
        loginResponse = null

        _state.value = MaApiTransport.State.Disconnected
        // Note: We don't close the DataChannel itself - that's managed by WebRTCTransport
    }

    // ========================================================================
    // DataChannel observer
    // ========================================================================

    private inner class MaApiChannelObserver : DataChannel.Observer {

        override fun onStateChange() {
            val dc = dataChannel
            Log.d(TAG, "DataChannel state changed: ${dc.state()}")

            if (dc.state() == DataChannel.State.CLOSED) {
                multiplexer.cancelAll("DataChannel closed")
                _state.value = MaApiTransport.State.Error("DataChannel closed")
                multiplexer.eventListener?.onDisconnected("DataChannel closed")
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) {
                Log.w(TAG, "Ignoring binary message on MA API channel")
                return
            }

            val data = buffer.data
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            val text = String(bytes, Charsets.UTF_8)

            // WebRTC callbacks come on internal threads - handle inline
            // (all our operations are non-blocking: JSON parse + deferred completion)
            handleMessage(text)
        }

        override fun onBufferedAmountChange(previousAmount: Long) {}
    }

    // ========================================================================
    // Message handling
    // ========================================================================

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            // Phase 1: ServerInfoMessage (first message from server)
            if (!serverInfoReceived && json.has("server_id") && json.has("server_version")) {
                serverInfoReceived = true
                serverVersion = json.optString("server_version")
                maServerId = json.optString("server_id")
                baseUrl = json.optString("base_url", "").ifEmpty { null }
                Log.d(TAG, "Server info received: v$serverVersion (id=$maServerId)")

                // If connect()/connectWithCredentials() has already been called
                // (authDeferred is set), send auth now. Otherwise just buffer the
                // ServerInfo — connect() will detect it and send auth immediately.
                if (authDeferred != null) {
                    _state.value = MaApiTransport.State.Authenticating
                    val msgId = java.util.UUID.randomUUID().toString()
                    authMessageId = msgId
                    sendAuthCommand(msgId)
                } else {
                    Log.d(TAG, "ServerInfo buffered (no pending auth yet)")
                }
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
                } else if (isLoginMode && pendingAuthToken == null) {
                    // Login mode step 1: auth/login succeeded, we got a token.
                    // Now we must send "auth" with the token to actually
                    // authenticate this session (auth/login only returns a token).
                    val resultObj = json.optJSONObject("result")
                    val token = resultObj?.optString("access_token", "")
                        ?.ifEmpty { resultObj.optString("token", "") }

                    if (token.isNullOrBlank()) {
                        Log.e(TAG, "Login response missing access token")
                        _state.value = MaApiTransport.State.Error("No token in login response", isAuthError = true)
                        authDeferred?.completeExceptionally(
                            MaApiTransport.AuthenticationException("Server response missing access token")
                        )
                    } else {
                        Log.d(TAG, "Login returned token, now authenticating session...")
                        // Store the token and response, then send auth command
                        pendingAuthToken = token
                        loginResponse = json  // Save for later completion
                        val newMsgId = java.util.UUID.randomUUID().toString()
                        authMessageId = newMsgId
                        // Temporarily switch out of login mode for sendAuthCommand
                        val wasLoginMode = isLoginMode
                        isLoginMode = false
                        sendAuthCommand(newMsgId)
                        isLoginMode = wasLoginMode
                    }
                } else {
                    Log.i(TAG, "Authenticated successfully via DataChannel")
                    _state.value = MaApiTransport.State.Connected
                    // In login mode, complete with the original login response
                    // (which contains the token and user info)
                    val response = loginResponse ?: json
                    loginResponse = null
                    authDeferred?.complete(response)
                }
                return
            }

            // Phase 3: Normal operation - delegate to multiplexer
            multiplexer.onMessage(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing DataChannel message", e)
        }
    }

    /**
     * Build and send the auth command.
     *
     * In token mode: sends `{command: "auth", args: {token: "..."}}`
     * In login mode: sends `{command: "auth/login", args: {username, password, device_name}}`
     */
    private fun sendAuthCommand(messageId: String) {
        val authMsg = if (isLoginMode) {
            val username = pendingUsername
            val password = pendingPassword
            if (username == null || password == null) {
                Log.e(TAG, "No pending credentials for login command")
                authDeferred?.completeExceptionally(
                    MaApiTransport.AuthenticationException("No credentials available")
                )
                return
            }

            JSONObject().apply {
                put("message_id", messageId)
                put("command", "auth/login")
                put("args", JSONObject().apply {
                    put("username", username)
                    put("password", password)
                    put("device_name", "SendSpinDroid")
                })
            }
        } else {
            val token = pendingAuthToken
            if (token == null) {
                Log.e(TAG, "No pending auth token for auth command")
                authDeferred?.completeExceptionally(
                    MaApiTransport.AuthenticationException("No auth token available")
                )
                return
            }

            JSONObject().apply {
                put("message_id", messageId)
                put("command", "auth")
                put("args", JSONObject().apply {
                    put("token", token)
                })
            }
        }

        val mode = if (isLoginMode) "login" else "token"
        Log.d(TAG, "Sending $mode auth command (id=$messageId)")
        sendText(authMsg.toString())
    }

    // ========================================================================
    // Message replay (for messages buffered by WebRTCTransport)
    // ========================================================================

    /**
     * Replay messages that were buffered by WebRTCTransport before this
     * transport's observer was registered.
     *
     * The MA gateway sends ServerInfo immediately when the DataChannel is
     * bridged. That message arrives at WebRTCTransport's observer (which
     * buffers it) before PlaybackService creates this transport. Call this
     * method right after construction to process those early messages.
     */
    fun replayBufferedMessages(messages: List<String>) {
        for (msg in messages) {
            Log.d(TAG, "Replaying buffered message (${msg.length} chars)")
            handleMessage(msg)
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun sendText(text: String): Boolean {
        val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
        val dataBuffer = DataChannel.Buffer(buffer, false) // false = text
        return dataChannel.send(dataBuffer)
    }
}
