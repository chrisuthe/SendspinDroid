package com.sendspindroid.remote

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WebSocket client for Music Assistant signaling server.
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
 */
class SignalingClient(
    private val remoteId: String,
    private val signalingUrl: String = DEFAULT_SIGNALING_URL
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

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket requires no read timeout
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
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

        val request = Request.Builder()
            .url(signalingUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, SignalingWebSocketListener())
    }

    /**
     * Send an SDP offer to the server.
     * The SDP is wrapped in a `data` object per the signaling protocol.
     */
    fun sendOffer(sdp: String) {
        val message = JSONObject().apply {
            put("type", "offer")
            put("remoteId", remoteId)
            sessionId?.let { put("sessionId", it) }
            put("data", JSONObject().apply {
                put("sdp", sdp)
                put("type", "offer")
            })
        }
        sendJson(message)
    }

    /**
     * Send an ICE candidate to the server.
     * The candidate is wrapped in a `data` object per the signaling protocol.
     */
    fun sendIceCandidate(candidate: IceCandidateInfo) {
        val message = JSONObject().apply {
            put("type", "ice-candidate")
            put("remoteId", remoteId)
            sessionId?.let { put("sessionId", it) }
            put("data", JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        }
        sendJson(message)
    }

    /**
     * Disconnect from the signaling server.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting from signaling server")
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        sessionId = null
        _state.value = State.Disconnected
    }

    fun destroy() {
        disconnect()
    }

    private fun sendJson(json: JSONObject) {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "Cannot send: WebSocket is null")
            return
        }

        val text = json.toString()
        Log.d(TAG, "Sending: $text")
        ws.send(text)
    }

    private fun isValidRemoteId(id: String): Boolean {
        // Remote ID is 26 uppercase alphanumeric characters
        // Digits are valid but not "uppercase", so check letter case separately
        return id.length == 26 && id.all { it.isDigit() || (it.isLetter() && it.isUpperCase()) }
    }

    private fun parseIceServers(jsonArray: JSONArray): List<IceServerConfig> {
        val servers = mutableListOf<IceServerConfig>()

        for (i in 0 until jsonArray.length()) {
            val serverJson = jsonArray.getJSONObject(i)

            // Parse URLs (can be single string or array)
            val urls = mutableListOf<String>()
            when (val urlsValue = serverJson.opt("urls")) {
                is String -> urls.add(urlsValue)
                is JSONArray -> {
                    for (j in 0 until urlsValue.length()) {
                        urls.add(urlsValue.getString(j))
                    }
                }
            }

            // Parse optional credentials
            val username = serverJson.optString("username", "").takeIf { it.isNotEmpty() }
            val credential = serverJson.optString("credential", "").takeIf { it.isNotEmpty() }

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

    private inner class SignalingWebSocketListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Connected to signaling server")
            _state.value = State.WaitingForServer

            // Request connection to the Remote ID
            val connectMessage = JSONObject().apply {
                put("type", "connect-request")
                put("remoteId", remoteId)
            }
            sendJson(connectMessage)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received: $text")

            scope.launch {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")

                    when (type) {
                        "connected" -> {
                            // Signaling server confirmed connection to MA server.
                            // Store the session ID for subsequent messages.
                            sessionId = json.optString("sessionId", ""
                            ).takeIf { it.isNotEmpty() }
                            val iceServersJson = json.optJSONArray("iceServers") ?: JSONArray()
                            val iceServers = parseIceServers(iceServersJson)

                            Log.i(TAG, "Server connected (sessionId=$sessionId), ${iceServers.size} ICE servers")
                            _state.value = State.ServerConnected(iceServers)
                            listener?.onServerConnected(iceServers)
                        }

                        "answer" -> {
                            // SDP answer is wrapped in "data" object
                            val data = json.getJSONObject("data")
                            val sdp = data.getString("sdp")
                            Log.d(TAG, "Received SDP answer")
                            listener?.onAnswer(sdp)
                        }

                        "ice-candidate" -> {
                            // ICE candidate is wrapped in "data" object
                            val data = json.getJSONObject("data")
                            val candidate = IceCandidateInfo(
                                sdp = data.getString("candidate"),
                                sdpMid = data.optString("sdpMid", ""),
                                sdpMLineIndex = data.optInt("sdpMLineIndex", 0)
                            )
                            Log.d(TAG, "Received ICE candidate: ${candidate.sdp.take(50)}...")
                            listener?.onIceCandidate(candidate)
                        }

                        "peer-disconnected" -> {
                            Log.w(TAG, "Peer (MA server) disconnected")
                            listener?.onDisconnected()
                        }

                        "error" -> {
                            val message = json.optString("error",
                                json.optString("message", "Unknown error"))
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
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Signaling closing: $code $reason")
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Signaling closed: $code $reason")
            this@SignalingClient.webSocket = null
            sessionId = null
            _state.value = State.Disconnected
            listener?.onDisconnected()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Signaling failure", t)
            this@SignalingClient.webSocket = null
            sessionId = null
            val message = t.message ?: "Connection failed"
            _state.value = State.Error(message)
            listener?.onError(message)
        }
    }
}

/**
 * ICE server configuration for WebRTC.
 */
data class IceServerConfig(
    val url: String,
    val username: String? = null,
    val credential: String? = null
) {
    val isTurn: Boolean get() = url.startsWith("turn:")
    val isStun: Boolean get() = url.startsWith("stun:")
}

/**
 * ICE candidate information exchanged via signaling.
 */
data class IceCandidateInfo(
    val sdp: String,
    val sdpMid: String,
    val sdpMLineIndex: Int
)
