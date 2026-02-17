package com.sendspindroid.remote

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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * WebSocket client for Music Assistant signaling server using Ktor.
 *
 * The signaling server (wss://signaling.music-assistant.io/ws) facilitates WebRTC
 * connection establishment by exchanging:
 * - SDP offers and answers (session descriptions)
 * - ICE candidates (network path information)
 *
 * ## Protocol Flow
 * ```
 * Client                    Signaling Server                  MA Server
 *   │                              │                              │
 *   │──{type:"connect-request",   │                              │
 *   │   remoteId:"XXXX..."}──────►│──────────────────────────────►│
 *   │                              │◄─{type:"session-ready",...}──│
 *   │◄─{type:"connected",         │                              │
 *   │   sessionId:"...",           │                              │
 *   │   iceServers:[...]}─────────│                              │
 *   │                              │                              │
 *   │──{type:"offer",             │                              │
 *   │   remoteId:"...",            │                              │
 *   │   sessionId:"...",           │                              │
 *   │   data:{sdp:"...",           │                              │
 *   │         type:"offer"}}──────►│──────────────────────────────►│
 *   │                              │                              │
 *   │◄─{type:"answer",            │◄──────────────────────────────│
 *   │   data:{sdp:"...",           │                              │
 *   │         type:"answer"}}─────│                              │
 *   │                              │                              │
 *   │──{type:"ice-candidate",     │                              │
 *   │   remoteId:"...",            │                              │
 *   │   sessionId:"...",           │                              │
 *   │   data:{...}}───────────────►│──────────────────────────────►│
 *   │◄─{type:"ice-candidate",     │◄──────────────────────────────│
 *   │   data:{...}}───────────────│                              │
 * ```
 *
 * @param remoteId The 26-character Remote ID from Music Assistant settings
 * @param signalingUrl URL of the signaling server
 * @param httpClient Optional Ktor HttpClient (creates one if not provided)
 */
class SignalingClient(
    private val remoteId: String,
    private val signalingUrl: String = DEFAULT_SIGNALING_URL,
    private val httpClient: HttpClient = createDefaultClient()
) {

    companion object {
        private const val TAG = "SignalingClient"
        const val DEFAULT_SIGNALING_URL = "wss://signaling.music-assistant.io/ws"

        // Default STUN servers (free, public)
        val DEFAULT_ICE_SERVERS = listOf(
            IceServerConfig("stun:stun.l.google.com:19302"),
            IceServerConfig("stun:stun1.l.google.com:19302"),
            IceServerConfig("stun:stun.cloudflare.com:3478"),
            IceServerConfig("stun:stun.home-assistant.io:3478")
        )

        fun createDefaultClient(): HttpClient = HttpClient {
            install(WebSockets) {
                pingIntervalMillis = 30_000
            }
            install(io.ktor.client.plugins.HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = Long.MAX_VALUE
            }
        }
    }

    /**
     * Signaling connection state.
     */
    sealed class State {
        object Disconnected : State()
        object Connecting : State()
        object WaitingForServer : State()  // Connected to signaling, waiting for MA server
        data class ServerConnected(val iceServers: List<IceServerConfig>) : State()
        data class Error(val message: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private var connectionJob: Job? = null
    private var sendChannel: Channel<String>? = null
    private var listener: Listener? = null

    /** Session ID assigned by the signaling server after connect-request. */
    private var sessionId: String? = null

    /**
     * Listener for signaling events.
     */
    interface Listener {
        /** Called when the MA server accepts our connection request */
        fun onServerConnected(iceServers: List<IceServerConfig>)

        /** Called when we receive an SDP answer from the server */
        fun onAnswer(sdp: String)

        /** Called when we receive an ICE candidate from the server */
        fun onIceCandidate(candidate: IceCandidateInfo)

        /** Called when the connection fails */
        fun onError(message: String)

        /** Called when the signaling connection is closed */
        fun onDisconnected()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * Connect to the signaling server and request connection to the Remote ID.
     */
    fun connect() {
        if (_state.value !is State.Disconnected && _state.value !is State.Error) {
            Log.w(TAG, "Already connecting or connected")
            return
        }

        // Validate Remote ID format (26 uppercase alphanumeric characters)
        if (!isValidRemoteId(remoteId)) {
            _state.value = State.Error("Invalid Remote ID format. Expected 26 uppercase letters/numbers.")
            listener?.onError("Invalid Remote ID format")
            return
        }

        Log.d(TAG, "Connecting to signaling server: $signalingUrl")
        _state.value = State.Connecting

        val channel = Channel<String>(Channel.BUFFERED)
        sendChannel = channel

        connectionJob = scope.launch {
            try {
                httpClient.webSocket(urlString = signalingUrl) {
                    Log.d(TAG, "Connected to signaling server")
                    _state.value = State.WaitingForServer

                    // Send connect-request immediately
                    val connectMessage = buildJsonObject {
                        put("type", "connect-request")
                        put("remoteId", remoteId)
                    }
                    send(Frame.Text(Json.encodeToString(JsonObject.serializer(), connectMessage)))

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

                    // Receiver loop
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()
                            handleMessage(text)
                        }
                    } finally {
                        senderJob.cancel()
                    }
                }

                // WebSocket session ended normally
                Log.d(TAG, "Signaling session ended")
                handleClosed()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal cancellation from disconnect()
                Log.d(TAG, "Signaling connection cancelled")
            } catch (e: Exception) {
                val message = e.message ?: "Connection failed"
                Log.e(TAG, "Signaling failure: $message", e)
                _state.value = State.Error(message)
                listener?.onError(message)
            }
        }
    }

    /**
     * Send an SDP offer to the server.
     * The SDP is wrapped in a `data` object per the signaling protocol.
     */
    fun sendOffer(sdp: String) {
        val message = buildJsonObject {
            put("type", "offer")
            put("remoteId", remoteId)
            sessionId?.let { put("sessionId", it) }
            put("data", buildJsonObject {
                put("sdp", sdp)
                put("type", "offer")
            })
        }
        trySend(Json.encodeToString(JsonObject.serializer(), message))
    }

    /**
     * Send an ICE candidate to the server.
     * The candidate is wrapped in a `data` object per the signaling protocol.
     */
    fun sendIceCandidate(candidate: IceCandidateInfo) {
        val message = buildJsonObject {
            put("type", "ice-candidate")
            put("remoteId", remoteId)
            sessionId?.let { put("sessionId", it) }
            put("data", buildJsonObject {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        }
        trySend(Json.encodeToString(JsonObject.serializer(), message))
    }

    /**
     * Disconnect from the signaling server.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting from signaling server")
        connectionJob?.cancel()
        connectionJob = null
        sendChannel?.close()
        sendChannel = null
        sessionId = null
        _state.value = State.Disconnected
    }

    fun destroy() {
        disconnect()
    }

    // ========================================================================
    // Internal message handling
    // ========================================================================

    private fun trySend(text: String) {
        val sent = sendChannel?.trySend(text)?.isSuccess == true
        if (!sent) {
            Log.w(TAG, "Cannot send: channel closed or null")
        } else {
            Log.d(TAG, "Sending: $text")
        }
    }

    private fun isValidRemoteId(id: String): Boolean {
        // Remote ID is 26 uppercase alphanumeric characters
        return id.length == 26 && id.all { it.isDigit() || (it.isLetter() && it.isUpperCase()) }
    }

    private fun handleMessage(text: String) {
        Log.d(TAG, "Received: $text")

        try {
            val json = Json.parseToJsonElement(text).jsonObject
            val type = (json["type"] as? JsonPrimitive)?.content ?: ""

            when (type) {
                "connected" -> {
                    // Signaling server confirmed connection to MA server.
                    sessionId = (json["sessionId"] as? JsonPrimitive)?.content
                        ?.takeIf { it.isNotEmpty() }
                    val iceServersJson = json["iceServers"] as? JsonArray
                    val iceServers = if (iceServersJson != null) {
                        parseIceServers(iceServersJson)
                    } else {
                        DEFAULT_ICE_SERVERS.toList()
                    }

                    Log.i(TAG, "Server connected (sessionId=$sessionId), ${iceServers.size} ICE servers")
                    _state.value = State.ServerConnected(iceServers)
                    listener?.onServerConnected(iceServers)
                }

                "answer" -> {
                    val data = json["data"]?.jsonObject
                    val sdp = (data?.get("sdp") as? JsonPrimitive)?.content
                    if (sdp != null) {
                        Log.d(TAG, "Received SDP answer")
                        listener?.onAnswer(sdp)
                    } else {
                        Log.w(TAG, "Answer message missing SDP")
                    }
                }

                "ice-candidate" -> {
                    val data = json["data"]?.jsonObject
                    if (data != null) {
                        val candidate = IceCandidateInfo(
                            sdp = (data["candidate"] as? JsonPrimitive)?.content ?: "",
                            sdpMid = (data["sdpMid"] as? JsonPrimitive)?.content ?: "",
                            sdpMLineIndex = (data["sdpMLineIndex"] as? JsonPrimitive)
                                ?.int ?: 0
                        )
                        Log.d(TAG, "Received ICE candidate: ${candidate.sdp.take(50)}...")
                        listener?.onIceCandidate(candidate)
                    }
                }

                "peer-disconnected" -> {
                    Log.w(TAG, "Peer (MA server) disconnected")
                    listener?.onDisconnected()
                }

                "error" -> {
                    val message = (json["error"] as? JsonPrimitive)?.content
                        ?: (json["message"] as? JsonPrimitive)?.content
                        ?: "Unknown error"
                    Log.e(TAG, "Signaling error: $message")
                    _state.value = State.Error(message)
                    listener?.onError(message)
                }

                "ping", "pong" -> {
                    // JSON-level keepalive messages - ignore
                }

                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing signaling message", e)
        }
    }

    private fun parseIceServers(jsonArray: JsonArray): List<IceServerConfig> {
        val servers = mutableListOf<IceServerConfig>()

        for (i in 0 until jsonArray.size) {
            val serverJson = jsonArray[i] as? JsonObject ?: continue

            // Parse URLs (can be single string or array)
            val urls = mutableListOf<String>()
            when (val urlsValue = serverJson["urls"]) {
                is JsonPrimitive -> urls.add(urlsValue.content)
                is JsonArray -> {
                    for (j in 0 until urlsValue.size) {
                        (urlsValue[j] as? JsonPrimitive)?.content?.let { urls.add(it) }
                    }
                }
                else -> { /* null or JsonObject — skip */ }
            }

            // Parse optional credentials
            val username = (serverJson["username"] as? JsonPrimitive)?.content
                ?.takeIf { it.isNotEmpty() }
            val credential = (serverJson["credential"] as? JsonPrimitive)?.content
                ?.takeIf { it.isNotEmpty() }

            urls.forEach { url ->
                servers.add(IceServerConfig(url, username, credential))
            }
        }

        // Always include default STUN servers as fallback
        val allUrls = servers.map { it.url }.toSet()
        DEFAULT_ICE_SERVERS.forEach { defaultServer ->
            if (defaultServer.url !in allUrls) {
                servers.add(defaultServer)
            }
        }

        return servers
    }

    private fun handleClosed() {
        sessionId = null
        _state.value = State.Disconnected
        listener?.onDisconnected()
    }
}
