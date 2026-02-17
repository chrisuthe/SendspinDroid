package com.sendspindroid.musicassistant.transport

import com.sendspindroid.shared.log.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Persistent WebSocket transport for the Music Assistant API using Ktor.
 *
 * Used for LOCAL and PROXY connection modes. Opens a single WebSocket to
 * `ws://host:8095/ws` (or `wss://` for proxy), authenticates once, then
 * multiplexes all commands over the persistent connection.
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
 * @param pingIntervalSeconds Ping interval in seconds (default: 30)
 * @param connectTimeoutMs Connect timeout in milliseconds (default: 15000)
 * @param httpClient Optional Ktor HttpClient (creates one if not provided)
 */
class MaWebSocketTransport(
    private val apiUrl: String,
    pingIntervalSeconds: Long = 30,
    connectTimeoutMs: Long = 15000,
    private val httpClient: HttpClient = createDefaultClient(pingIntervalSeconds, connectTimeoutMs)
) : MaApiTransport {

    companion object {
        private const val TAG = "MaWebSocketTransport"
        private const val AUTH_TIMEOUT_MS = 10000L

        /**
         * Create a default Ktor HttpClient configured for MA WebSocket connections.
         */
        fun createDefaultClient(
            pingIntervalSeconds: Long = 30,
            connectTimeoutMs: Long = 15000
        ): HttpClient = HttpClient {
            install(WebSockets) {
                pingIntervalMillis = pingIntervalSeconds * 1000
            }
            install(io.ktor.client.plugins.HttpTimeout) {
                connectTimeoutMillis = connectTimeoutMs
                socketTimeoutMillis = Long.MAX_VALUE
            }
        }
    }

    private val _state = MutableStateFlow<MaApiTransport.State>(MaApiTransport.State.Disconnected)
    override val state: StateFlow<MaApiTransport.State> = _state.asStateFlow()

    override var serverVersion: String? = null
        private set

    override var maServerId: String? = null
        private set

    private val multiplexer = MaCommandMultiplexer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null

    // Channel for outgoing text messages
    private var sendChannel: Channel<String>? = null

    // Auth handshake state
    @Volatile
    private var authToken: String? = null
    @Volatile
    private var loginUsername: String? = null
    @Volatile
    private var loginPassword: String? = null
    @Volatile
    private var isLoginMode = false

    // Deferred for auth completion — used to signal connect()/connectWithCredentials() callers
    @Volatile
    private var authResult: kotlinx.coroutines.CompletableDeferred<JsonObject>? = null

    // ========================================================================
    // MaApiTransport implementation
    // ========================================================================

    override suspend fun connect(token: String) {
        if (_state.value is MaApiTransport.State.Connected) {
            Log.d(TAG, "Already connected")
            return
        }

        _state.value = MaApiTransport.State.Connecting
        authToken = token
        isLoginMode = false

        val deferred = kotlinx.coroutines.CompletableDeferred<JsonObject>()
        authResult = deferred

        startConnection()

        try {
            withTimeout(AUTH_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (e: Exception) {
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
        loginUsername = username
        loginPassword = password

        val deferred = kotlinx.coroutines.CompletableDeferred<JsonObject>()
        authResult = deferred

        startConnection()

        try {
            val response = withTimeout(AUTH_TIMEOUT_MS) {
                deferred.await()
            }

            // Extract token from the login response
            val resultObj = response.optJsonObject("result")
                ?: throw MaApiTransport.AuthenticationException("Invalid server response: no result")

            val token = resultObj.optString("access_token")
                .ifEmpty { resultObj.optString("token") }

            if (token.isBlank()) {
                throw MaApiTransport.AuthenticationException("Server response missing access token")
            }

            val userObj = resultObj.optJsonObject("user")
            val userId = userObj?.optString("user_id") ?: ""
            val userName = userObj?.optString("display_name")
                ?.ifEmpty { userObj.optString("username") }
                ?.ifEmpty { username }
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
            loginUsername = null
            loginPassword = null
        }
    }

    override suspend fun sendCommand(
        command: String,
        args: Map<String, Any>,
        timeoutMs: Long
    ): JsonObject {
        if (_state.value !is MaApiTransport.State.Connected) {
            throw IOException("MA API transport not in Connected state: ${_state.value}")
        }

        val (messageId, deferred) = multiplexer.registerCommand()

        val cmdMsg = buildJsonObject {
            put("message_id", messageId)
            put("command", command)
            if (args.isNotEmpty()) {
                put("args", mapToJsonObject(args))
            }
        }

        Log.d(TAG, "Sending command: $command (id=$messageId)")
        val sent = trySend(Json.encodeToString(JsonObject.serializer(), cmdMsg))
        if (!sent) {
            deferred.completeExceptionally(IOException("Failed to send command"))
            throw IOException("Failed to send command: send channel closed")
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
        authResult?.completeExceptionally(IOException("Transport disconnecting"))
        authResult = null

        connectionJob?.cancel()
        connectionJob = null

        sendChannel?.close()
        sendChannel = null

        serverVersion = null
        maServerId = null
        authToken = null
        loginUsername = null
        loginPassword = null
        isLoginMode = false

        _state.value = MaApiTransport.State.Disconnected
    }

    // ========================================================================
    // Connection management
    // ========================================================================

    private fun startConnection() {
        val wsUrl = convertToWebSocketUrl(apiUrl)
        Log.d(TAG, "Connecting to MA API: $wsUrl")

        val channel = Channel<String>(Channel.BUFFERED)
        sendChannel = channel

        connectionJob = scope.launch {
            try {
                httpClient.webSocket(urlString = wsUrl) {
                    Log.d(TAG, "WebSocket connected, waiting for server info...")

                    // Launch sender coroutine
                    val senderJob = launch {
                        try {
                            for (text in channel) {
                                send(Frame.Text(text))
                            }
                        } catch (_: ClosedSendChannelException) {
                            // Normal shutdown
                        }
                    }

                    // Auth state tracking
                    var serverInfoReceived = false
                    var authMessageId: String? = null

                    // Receiver loop
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()

                            try {
                                val json = Json.parseToJsonElement(text).jsonObject

                                // Phase 1: ServerInfoMessage
                                if (!serverInfoReceived && json.has("server_id") && json.has("server_version")) {
                                    serverInfoReceived = true
                                    serverVersion = json.optString("server_version")
                                    maServerId = json.optString("server_id")
                                    Log.d(TAG, "Server info received: v$serverVersion (id=$maServerId)")
                                    _state.value = MaApiTransport.State.Authenticating

                                    // Send auth or login command
                                    @OptIn(ExperimentalUuidApi::class)
                                    val msgId = Uuid.random().toString()
                                    authMessageId = msgId

                                    val authMsg = buildAuthMessage(msgId)
                                    send(Frame.Text(Json.encodeToString(JsonObject.serializer(), authMsg)))
                                    continue
                                }

                                // Phase 2: Auth response
                                val msgId = json.optString("message_id")
                                if (msgId == authMessageId && authMessageId != null) {
                                    authMessageId = null

                                    if (json.has("error_code")) {
                                        val errorCode = json.optString("error_code", "unknown")
                                        val details = json.optString("details", "Authentication failed")
                                        Log.e(TAG, "Auth failed: $errorCode - $details")
                                        _state.value = MaApiTransport.State.Error(details, isAuthError = true)
                                        authResult?.completeExceptionally(
                                            MaApiTransport.AuthenticationException(details)
                                        )
                                    } else {
                                        Log.i(TAG, "Authenticated successfully")
                                        _state.value = MaApiTransport.State.Connected
                                        authResult?.complete(json)
                                    }
                                    continue
                                }

                                // Phase 3: Normal operation - delegate to multiplexer
                                multiplexer.onMessage(text)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing message", e)
                            }
                        }
                    } finally {
                        senderJob.cancel()
                    }
                }

                // WebSocket session ended normally
                Log.d(TAG, "WebSocket session ended")
                handleDisconnect("WebSocket closed")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal cancellation from disconnect()
                Log.d(TAG, "Connection cancelled")
            } catch (e: Exception) {
                val message = e.message ?: "Connection failed"
                Log.e(TAG, "WebSocket error: $message", e)
                handleDisconnect(message)
            }
        }
    }

    private fun handleDisconnect(reason: String) {
        multiplexer.cancelAll(reason)

        val wasConnected = _state.value is MaApiTransport.State.Connected
        _state.value = MaApiTransport.State.Error(reason)

        authResult?.completeExceptionally(IOException(reason))
        authResult = null

        if (wasConnected) {
            multiplexer.eventListener?.onDisconnected(reason)
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun buildAuthMessage(messageId: String): JsonObject {
        return if (isLoginMode && loginUsername != null && loginPassword != null) {
            buildJsonObject {
                put("message_id", messageId)
                put("command", "auth/login")
                put("args", buildJsonObject {
                    put("username", loginUsername!!)
                    put("password", loginPassword!!)
                    put("device_name", "SendSpinDroid")
                })
            }
        } else {
            buildJsonObject {
                put("message_id", messageId)
                put("command", "auth")
                put("args", buildJsonObject {
                    put("token", authToken ?: "")
                })
            }
        }
    }

    private fun trySend(text: String): Boolean {
        return sendChannel?.trySend(text)?.isSuccess == true
    }

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

/**
 * Convert a Map<String, Any> to a JsonObject for command args.
 * Handles String, Number, Boolean, and nested maps.
 */
internal fun mapToJsonObject(map: Map<String, Any>): JsonObject = buildJsonObject {
    for ((key, value) in map) {
        when (value) {
            is String -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is Double -> put(key, value)
            is Float -> put(key, value.toDouble())
            is Boolean -> put(key, value)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                put(key, mapToJsonObject(value as Map<String, Any>))
            }
            is List<*> -> {
                put(key, kotlinx.serialization.json.buildJsonArray {
                    for (item in value) {
                        when (item) {
                            is String -> add(kotlinx.serialization.json.JsonPrimitive(item))
                            is Number -> add(kotlinx.serialization.json.JsonPrimitive(item))
                            is Boolean -> add(kotlinx.serialization.json.JsonPrimitive(item))
                            else -> add(kotlinx.serialization.json.JsonPrimitive(item.toString()))
                        }
                    }
                })
            }
            else -> put(key, value.toString())
        }
    }
}
