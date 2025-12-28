package com.sendspindroid.sendspin

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

/**
 * Native Kotlin SendSpin client.
 *
 * Implements the Sendspin Protocol for synchronized multi-room audio streaming.
 * Protocol spec: https://www.sendspin-audio.com/spec/
 *
 * ## Protocol Overview
 * 1. WebSocket connect to ws://host:port/sendspin
 * 2. Send client/hello with capabilities
 * 3. Receive server/hello with active roles
 * 4. Send client/time messages continuously for clock sync
 * 5. Receive binary audio chunks (type 4) with microsecond timestamps
 * 6. Play audio at computed client time using Kalman-filtered offset
 *
 * ## Message Types
 * - client/hello: Initial handshake with supported roles/formats
 * - server/hello: Server acknowledgment and role activation
 * - client/time: Clock sync request (client_transmitted μs)
 * - server/time: Clock sync response (client_transmitted, server_received, server_transmitted)
 * - server/state: Metadata and playback state updates
 * - Binary type 4: Audio chunk (8-byte timestamp + PCM data)
 */
class SendSpinClient(
    private val deviceName: String,
    private val callback: Callback
) {
    companion object {
        private const val TAG = "SendSpinClient"
        private const val PROTOCOL_VERSION = 1
        private const val ENDPOINT_PATH = "/sendspin"

        // Binary message types
        private const val MSG_TYPE_AUDIO = 4
        private const val MSG_TYPE_ARTWORK_BASE = 8 // 8-11 for channels 0-3

        // Time sync configuration
        private const val TIME_SYNC_INTERVAL_MS = 1000L // Send time sync every second
        private const val INITIAL_TIME_SYNC_COUNT = 5 // Send 5 rapid syncs initially
        private const val INITIAL_TIME_SYNC_DELAY_MS = 100L

        // Reconnection configuration
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L // 1 second
        private const val MAX_RECONNECT_DELAY_MS = 30000L // 30 seconds

        // Audio configuration we support
        private const val AUDIO_CODEC = "pcm"
        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_CHANNELS = 2
        private const val AUDIO_BIT_DEPTH = 16
        private const val BUFFER_CAPACITY = 32_000_000 // 32MB buffer
    }

    /**
     * Callback interface for SendSpin events.
     * Mirrors the Go PlayerCallback interface.
     */
    interface Callback {
        fun onServerDiscovered(name: String, address: String)
        fun onConnected(serverName: String)
        fun onDisconnected()
        fun onStateChanged(state: String)
        fun onGroupUpdate(groupId: String, groupName: String, playbackState: String)
        fun onMetadataUpdate(
            title: String,
            artist: String,
            album: String,
            artworkUrl: String,
            durationMs: Long,
            positionMs: Long
        )
        fun onArtwork(imageData: ByteArray)
        fun onError(message: String)

        // Audio streaming callbacks
        fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int)
        fun onStreamClear()
        fun onAudioChunk(serverTimeMicros: Long, pcmData: ByteArray)

        // Volume callback - called when server sends volume update
        fun onVolumeChanged(volume: Int)
    }

    // Connection state
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val serverName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS) // Fail fast if server is unreachable
        .writeTimeout(15, TimeUnit.SECONDS) // Timeout for sending data
        // readTimeout must be 0 for WebSocket: OkHttp uses this for reading frames,
        // and WebSocket connections are long-lived with infrequent messages
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS) // Keep connection alive through NAT/routers
        .build()

    private var webSocket: WebSocket? = null
    private var serverAddress: String? = null
    private var serverPath: String? = null
    private var serverName: String? = null

    // Protocol state
    private val clientId = UUID.randomUUID().toString()
    private var handshakeComplete = false
    private var timeSyncRunning = false

    // Reconnection state
    private val userInitiatedDisconnect = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private var reconnecting = false

    // Player state (for client/state messages)
    private var currentVolume: Int = 100
    private var currentMuted: Boolean = false

    // Time synchronization (Kalman filter)
    private val timeFilter = SendspinTimeFilter()

    val isConnected: Boolean
        get() = _connectionState.value is ConnectionState.Connected

    /**
     * Access to the time filter for audio synchronization.
     * The audio player uses this to convert server timestamps to client time.
     */
    fun getTimeFilter(): SendspinTimeFilter = timeFilter

    /**
     * Connect to a SendSpin server.
     *
     * @param address Server address in "host:port" format
     * @param path WebSocket path (from mDNS TXT or default /sendspin)
     */
    fun connect(address: String, path: String = ENDPOINT_PATH) {
        if (isConnected) {
            Log.w(TAG, "Already connected, disconnecting first")
            disconnect()
        }

        Log.d(TAG, "Connecting to: $address path=$path")
        _connectionState.value = ConnectionState.Connecting
        serverAddress = address
        serverPath = path
        handshakeComplete = false
        timeSyncRunning = false
        timeFilter.reset()

        // Reset reconnection state for new connection
        userInitiatedDisconnect.set(false)
        reconnectAttempts.set(0)
        reconnecting = false

        // Construct WebSocket URL using provided path
        val wsUrl = "ws://$address$path"
        Log.d(TAG, "WebSocket URL: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, WebSocketEventListener())
    }

    /**
     * Disconnect from the current server.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting (user-initiated)")
        userInitiatedDisconnect.set(true)
        timeSyncRunning = false
        reconnecting = false
        sendGoodbye("user_disconnect")
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        handshakeComplete = false
        _connectionState.value = ConnectionState.Disconnected
        callback.onDisconnected()
    }

    /**
     * Start audio playback.
     */
    fun play() {
        sendCommand("play")
    }

    /**
     * Pause audio playback.
     */
    fun pause() {
        sendCommand("pause")
    }

    /**
     * Skip to next track.
     */
    fun next() {
        sendCommand("next")
    }

    /**
     * Go to previous track.
     */
    fun previous() {
        sendCommand("previous")
    }

    /**
     * Set playback volume.
     *
     * @param volume Volume level from 0.0 to 1.0
     */
    fun setVolume(volume: Double) {
        val volumePercent = (volume * 100).toInt().coerceIn(0, 100)
        currentVolume = volumePercent
        Log.d(TAG, "setVolume: $volumePercent%")
        // Use client/state format per SendSpin protocol
        sendPlayerStateUpdate(volumePercent, currentMuted)
    }

    /**
     * Set muted state and send to server.
     */
    fun setMuted(muted: Boolean) {
        currentMuted = muted
        Log.d(TAG, "setMuted: $muted")
        sendPlayerStateUpdate(currentVolume, muted)
    }

    /**
     * Send the current player state (volume/muted) to the server.
     * Format: {"type": "client/state", "payload": {"state": "synchronized", "volume": 75, "muted": false}}
     */
    private fun sendPlayerStateUpdate(volume: Int, muted: Boolean) {
        Log.i(TAG, ">>> Sending player state: volume=$volume%, muted=$muted")
        val message = JSONObject().apply {
            put("type", "client/state")
            put("payload", JSONObject().apply {
                put("state", "synchronized")
                put("volume", volume)
                put("muted", muted)
            })
        }
        Log.i(TAG, ">>> Player state message: $message")
        sendMessage(message)
    }

    /**
     * Send a media command to the server (play, pause, next, previous, etc).
     *
     * Commands are sent as client/command messages with controller object.
     * Protocol format: {"type": "client/command", "payload": {"controller": {"command": "pause"}}}
     */
    fun sendCommand(command: String) {
        Log.i(TAG, ">>> Sending controller command: $command")

        val payload = JSONObject().apply {
            put("controller", JSONObject().apply {
                put("command", command)
            })
        }

        val message = JSONObject().apply {
            put("type", "client/command")
            put("payload", payload)
        }

        Log.i(TAG, ">>> Command message: $message")
        sendMessage(message)
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        timeSyncRunning = false
        userInitiatedDisconnect.set(true) // Prevent reconnection during destroy
        reconnecting = false
        disconnect()
    }

    /**
     * Attempt to reconnect to the last server.
     * Uses exponential backoff with a maximum number of attempts.
     */
    private fun attemptReconnect() {
        val address = serverAddress
        val path = serverPath ?: ENDPOINT_PATH

        if (address == null) {
            Log.w(TAG, "Cannot reconnect: no server address saved")
            return
        }

        if (userInitiatedDisconnect.get()) {
            Log.d(TAG, "Not reconnecting: user-initiated disconnect")
            return
        }

        val attempts = reconnectAttempts.incrementAndGet()
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnection attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up")
            reconnecting = false
            _connectionState.value = ConnectionState.Error("Connection lost. Please reconnect manually.")
            callback.onError("Connection lost after $MAX_RECONNECT_ATTEMPTS reconnection attempts")
            return
        }

        // Exponential backoff: 1s, 2s, 4s, 8s, 16s (capped at 30s)
        val delayMs = (INITIAL_RECONNECT_DELAY_MS * (1 shl (attempts - 1)))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)

        Log.i(TAG, "Attempting reconnection $attempts/$MAX_RECONNECT_ATTEMPTS in ${delayMs}ms")
        reconnecting = true
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            delay(delayMs)

            if (userInitiatedDisconnect.get() || !reconnecting) {
                Log.d(TAG, "Reconnection cancelled")
                return@launch
            }

            Log.d(TAG, "Reconnecting to: $address path=$path (attempt $attempts)")

            // Reset state for new connection attempt
            handshakeComplete = false
            timeSyncRunning = false
            timeFilter.reset()

            val wsUrl = "ws://$address$path"
            val request = Request.Builder()
                .url(wsUrl)
                .build()

            webSocket = okHttpClient.newWebSocket(request, WebSocketEventListener())
        }
    }

    // ========== Protocol Messages ==========

    /**
     * Send client/hello message.
     *
     * This is the first message after WebSocket opens.
     * Declares our capabilities and supported audio formats.
     */
    private fun sendClientHello() {
        val deviceInfo = JSONObject().apply {
            put("product_name", "SendSpinDroid")
            put("manufacturer", Build.MANUFACTURER)
            put("software_version", "1.0.0")
        }

        val supportedFormat = JSONObject().apply {
            put("codec", AUDIO_CODEC)
            put("sample_rate", AUDIO_SAMPLE_RATE)
            put("channels", AUDIO_CHANNELS)
            put("bit_depth", AUDIO_BIT_DEPTH)
        }

        // Also support mono
        val monoFormat = JSONObject().apply {
            put("codec", AUDIO_CODEC)
            put("sample_rate", AUDIO_SAMPLE_RATE)
            put("channels", 1)
            put("bit_depth", AUDIO_BIT_DEPTH)
        }

        val playerSupport = JSONObject().apply {
            put("supported_formats", JSONArray().apply {
                put(supportedFormat)
                put(monoFormat)
            })
            put("buffer_capacity", BUFFER_CAPACITY)
            put("supported_commands", JSONArray().apply {
                put("volume")
                put("mute")
            })
        }

        val payload = JSONObject().apply {
            put("client_id", clientId)
            put("name", deviceName)
            put("version", PROTOCOL_VERSION)
            put("supported_roles", JSONArray().apply {
                put("player@v1")
                put("controller@v1")  // Needed to send play/pause/next/previous commands
                put("metadata@v1")    // Needed to receive track metadata
            })
            put("device_info", deviceInfo)
            // Note: aiosendspin uses "player_support" not "player@v1_support"
            put("player_support", playerSupport)
        }

        val message = JSONObject().apply {
            put("type", "client/hello")
            put("payload", payload)
        }

        sendMessage(message)
        Log.d(TAG, "Sent client/hello: client_id=$clientId")
    }

    /**
     * Send goodbye message before disconnecting.
     */
    private fun sendGoodbye(reason: String) {
        if (webSocket == null || !handshakeComplete) return

        val message = JSONObject().apply {
            put("type", "client/goodbye")
            put("payload", JSONObject().apply {
                put("reason", reason)
            })
        }
        sendMessage(message)
    }

    /**
     * Send client/time for clock synchronization.
     *
     * The server will respond with server/time containing:
     * - client_transmitted: our timestamp echoed back
     * - server_received: when server got our message
     * - server_transmitted: when server sent its response
     */
    private fun sendClientTime() {
        val clientTransmitted = System.nanoTime() / 1000 // Convert to microseconds

        val message = JSONObject().apply {
            put("type", "client/time")
            put("payload", JSONObject().apply {
                put("client_transmitted", clientTransmitted)
            })
        }
        sendMessage(message)
    }

    /**
     * Start the continuous time sync loop.
     * Sends rapid initial syncs for fast convergence, then periodic updates.
     */
    private fun startTimeSyncLoop() {
        if (timeSyncRunning) return
        timeSyncRunning = true

        scope.launch {
            // Send initial rapid syncs for fast clock convergence
            repeat(INITIAL_TIME_SYNC_COUNT) {
                if (!timeSyncRunning || !isActive) return@launch
                sendClientTime()
                delay(INITIAL_TIME_SYNC_DELAY_MS)
            }

            // Then periodic syncs to maintain accuracy
            while (timeSyncRunning && isActive) {
                delay(TIME_SYNC_INTERVAL_MS)
                if (timeSyncRunning) {
                    sendClientTime()
                }
            }
        }
    }

    /**
     * Send initial player state after handshake.
     */
    private fun sendPlayerState() {
        // Send default player state (100% volume, not muted)
        sendPlayerStateUpdate(100, false)
    }

    /**
     * Send a JSON message over the WebSocket.
     */
    private fun sendMessage(message: JSONObject) {
        // Android's JSONObject escapes forward slashes as \/, which some servers don't like
        // Replace \/ with / for compatibility
        var text = message.toString()
        text = text.replace("\\/", "/")

        val ws = webSocket
        if (ws == null) {
            Log.e(TAG, "Cannot send message - WebSocket is null")
            return
        }
        val success = ws.send(text)
        Log.d(TAG, "sendMessage: type=${message.optString("type")}, success=$success, length=${text.length}")
        if (!success) {
            Log.w(TAG, "Failed to send message: ${message.optString("type")}")
        }
    }

    /**
     * WebSocket event listener.
     */
    private inner class WebSocketEventListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected, sending client/hello")
            // Don't mark as connected yet - wait for server/hello handshake
            sendClientHello()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.i(TAG, ">>> Received TEXT message (${text.length} chars): ${text.take(200)}")
            handleTextMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.i(TAG, ">>> Received BINARY message: ${bytes.size} bytes")
            handleBinaryMessage(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")

            // If not user-initiated and was previously connected, try to reconnect
            if (!userInitiatedDisconnect.get() && handshakeComplete) {
                Log.i(TAG, "Unexpected closure, attempting reconnection")
                attemptReconnect()
            } else {
                _connectionState.value = ConnectionState.Disconnected
                callback.onDisconnected()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)

            // Check if we should attempt reconnection
            val shouldReconnect = !userInitiatedDisconnect.get() &&
                                  serverAddress != null &&
                                  isRecoverableError(t)

            if (shouldReconnect) {
                Log.i(TAG, "Recoverable error, attempting reconnection: ${t.message}")
                attemptReconnect()
            } else {
                val errorMessage = getSpecificErrorMessage(t)
                reconnecting = false
                _connectionState.value = ConnectionState.Error(errorMessage)
                callback.onError(errorMessage)
            }
        }
    }

    /**
     * Checks if an error is recoverable (should trigger reconnection).
     * Errors like network drops, socket resets, etc. are recoverable.
     * Errors like authentication failures or invalid URLs are not.
     */
    private fun isRecoverableError(t: Throwable): Boolean {
        val cause = t.cause ?: t
        val message = t.message?.lowercase() ?: ""

        return when {
            // Socket errors are generally recoverable
            cause is SocketException -> true
            cause is java.io.EOFException -> true

            // Connection reset/abort are recoverable
            message.contains("reset") -> true
            message.contains("abort") -> true
            message.contains("broken pipe") -> true
            message.contains("connection closed") -> true

            // Timeouts are recoverable
            cause is SocketTimeoutException -> true

            // Specific non-recoverable errors
            cause is UnknownHostException -> false
            cause is SSLHandshakeException -> false
            message.contains("refused") -> false

            // Default: try to recover from unknown errors
            else -> true
        }
    }

    /**
     * Maps exception types to user-friendly error messages.
     */
    private fun getSpecificErrorMessage(t: Throwable): String {
        // Check the root cause for wrapped exceptions
        val cause = t.cause ?: t

        return when (cause) {
            is ConnectException -> "Server refused connection. Check if SendSpin is running."
            is UnknownHostException -> "Server not found. Check the address."
            is SocketTimeoutException -> "Connection timeout. Server not responding."
            is NoRouteToHostException -> "Network unreachable. Check WiFi connection."
            is SSLHandshakeException -> "Secure connection failed."
            is SocketException -> "Connection lost. Check your network."
            else -> {
                // Check message for additional hints
                val message = t.message?.lowercase() ?: ""
                when {
                    message.contains("refused") -> "Server refused connection. Check if SendSpin is running."
                    message.contains("timeout") -> "Connection timeout. Server not responding."
                    message.contains("unreachable") -> "Network unreachable. Check WiFi connection."
                    message.contains("host") -> "Server not found. Check the address."
                    message.contains("abort") -> "Connection dropped. Reconnecting..."
                    message.contains("reset") -> "Connection reset. Reconnecting..."
                    message.contains("broken pipe") -> "Connection lost. Reconnecting..."
                    else -> t.message ?: "Connection failed"
                }
            }
        }
    }

    // ========== Message Handlers ==========

    /**
     * Handle text (JSON) messages from server.
     *
     * Message types:
     * - server/hello: Handshake response
     * - server/time: Clock sync response
     * - server/state: Metadata and playback state
     * - stream/start: Audio stream starting
     * - stream/clear: Clear audio buffers (seek)
     */
    private fun handleTextMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.getString("type")
            val payload = json.optJSONObject("payload")

            // Log all incoming messages for debugging (truncate long messages)
            val logText = if (text.length > 200) text.take(200) + "..." else text
            Log.v(TAG, "<<< Received: $logText")

            when (type) {
                "server/hello" -> handleServerHello(payload)
                "server/time" -> handleServerTime(payload)
                "server/state" -> handleServerState(payload)
                "server/command" -> handleServerCommand(payload)
                "group/update" -> handleGroupUpdate(payload)
                "stream/start" -> handleStreamStart(payload)
                "stream/clear" -> handleStreamClear()
                else -> Log.d(TAG, "Unhandled message type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${text.take(100)}", e)
        }
    }

    /**
     * Handle server/hello - handshake complete.
     */
    private fun handleServerHello(payload: JSONObject?) {
        if (payload == null) {
            Log.e(TAG, "server/hello missing payload")
            return
        }

        serverName = payload.optString("name", serverAddress ?: "Unknown")
        val serverId = payload.optString("server_id", "")
        val activeRoles = payload.optJSONArray("active_roles")
        val connectionReason = payload.optString("connection_reason", "discovery")

        Log.i(TAG, "server/hello received: name=$serverName, id=$serverId, reason=$connectionReason")
        Log.d(TAG, "Active roles: $activeRoles")

        handshakeComplete = true
        reconnecting = false
        reconnectAttempts.set(0) // Reset reconnection counter on successful connection
        _connectionState.value = ConnectionState.Connected(serverName!!)
        callback.onConnected(serverName!!)

        // Start time synchronization and send initial state
        sendPlayerState()
        startTimeSyncLoop()
    }

    /**
     * Handle server/time - clock sync response.
     *
     * Uses NTP-style calculation:
     * offset = ((T2 - T1) + (T3 - T4)) / 2
     * where:
     *   T1 = client_transmitted (we sent)
     *   T2 = server_received
     *   T3 = server_transmitted
     *   T4 = now (when we received)
     */
    private fun handleServerTime(payload: JSONObject?) {
        if (payload == null) return

        val clientTransmitted = payload.optLong("client_transmitted", 0)
        val serverReceived = payload.optLong("server_received", 0)
        val serverTransmitted = payload.optLong("server_transmitted", 0)
        val clientReceived = System.nanoTime() / 1000 // Current time in microseconds

        if (clientTransmitted == 0L || serverReceived == 0L || serverTransmitted == 0L) {
            Log.w(TAG, "Invalid server/time payload")
            return
        }

        // NTP-style offset calculation
        // offset = ((server_received - client_transmitted) + (server_transmitted - client_received)) / 2
        val offset = ((serverReceived - clientTransmitted) + (serverTransmitted - clientReceived)) / 2

        // Round-trip time / 2 gives us the uncertainty
        val rtt = (clientReceived - clientTransmitted) - (serverTransmitted - serverReceived)
        val maxError = rtt / 2

        // Update the Kalman filter
        timeFilter.addMeasurement(offset, maxError, clientReceived)

        if (timeFilter.isReady) {
            Log.d(TAG, "Time sync: offset=${timeFilter.offsetMicros}μs, error=${timeFilter.errorMicros}μs")
        }
    }

    /**
     * Handle server/state - metadata and playback updates.
     */
    private fun handleServerState(payload: JSONObject?) {
        if (payload == null) return

        // Extract metadata if present
        val metadata = payload.optJSONObject("metadata")
        if (metadata != null) {
            val title = metadata.optString("title", "")
            val artist = metadata.optString("artist", "")
            val album = metadata.optString("album", "")
            val artworkUrl = metadata.optString("artwork_url", "")
            val durationMs = metadata.optLong("duration_ms", 0)
            val positionMs = metadata.optLong("position_ms", 0)

            callback.onMetadataUpdate(title, artist, album, artworkUrl, durationMs, positionMs)
        }

        // Extract playback state
        val state = payload.optString("state", "")
        if (state.isNotEmpty()) {
            callback.onStateChanged(state)
        }

        // Extract volume if present (0-100)
        if (payload.has("volume")) {
            val volume = payload.optInt("volume", -1)
            if (volume in 0..100) {
                Log.d(TAG, "Server volume update: $volume%")
                callback.onVolumeChanged(volume)
            }
        }
    }

    /**
     * Handle server/command - player volume/mute control from server.
     *
     * Example payload:
     * {"player": {"command": "volume", "volume": 75}}
     * {"player": {"command": "mute", "mute": true}}
     */
    private fun handleServerCommand(payload: JSONObject?) {
        if (payload == null) return

        val player = payload.optJSONObject("player") ?: return
        val command = player.optString("command", "")

        when (command) {
            "volume" -> {
                val volume = player.optInt("volume", -1)
                if (volume in 0..100) {
                    Log.d(TAG, "Server command: set volume to $volume%")
                    currentVolume = volume
                    callback.onVolumeChanged(volume)
                    // Send state update back to server per spec
                    sendPlayerStateUpdate(currentVolume, currentMuted)
                }
            }
            "mute" -> {
                val mute = player.optBoolean("mute", false)
                Log.d(TAG, "Server command: set mute to $mute")
                currentMuted = mute
                // TODO: Add onMutedChanged callback if needed
                // For now, send volume update with muted state
                sendPlayerStateUpdate(currentVolume, currentMuted)
            }
            else -> Log.d(TAG, "Unknown player command: $command")
        }
    }

    /**
     * Handle group/update - playback group state changes.
     *
     * This message is sent when the player's group assignment changes or
     * when the playback state of the group changes.
     *
     * Example payload:
     * {"playback_state":"stopped","group_id":"761348d6-9fce-43f7-b7bc-4f1313e1d523"}
     */
    private fun handleGroupUpdate(payload: JSONObject?) {
        if (payload == null) return

        val groupId = payload.optString("group_id", "")
        val groupName = payload.optString("group_name", "") // May not always be present
        val playbackState = payload.optString("playback_state", "")

        Log.d(TAG, "group/update: id=$groupId, name=$groupName, state=$playbackState")
        callback.onGroupUpdate(groupId, groupName, playbackState)

        // Check for volume in group/update as well
        if (payload.has("volume")) {
            val volume = payload.optInt("volume", -1)
            if (volume in 0..100) {
                Log.d(TAG, "Group volume update: $volume%")
                callback.onVolumeChanged(volume)
            }
        }
    }

    /**
     * Handle stream/start - audio stream configuration.
     *
     * This is sent when the server starts streaming audio.
     * Includes codec and format information for the audio player.
     */
    private fun handleStreamStart(payload: JSONObject?) {
        if (payload == null) return

        val player = payload.optJSONObject("player")
        if (player != null) {
            val codec = player.optString("codec", AUDIO_CODEC)
            val sampleRate = player.optInt("sample_rate", AUDIO_SAMPLE_RATE)
            val channels = player.optInt("channels", AUDIO_CHANNELS)
            val bitDepth = player.optInt("bit_depth", AUDIO_BIT_DEPTH)

            Log.i(TAG, "Stream started: codec=$codec, rate=$sampleRate, ch=$channels, bits=$bitDepth")
            callback.onStreamStart(codec, sampleRate, channels, bitDepth)
        }
    }

    /**
     * Handle stream/clear - flush audio buffers (e.g., seek).
     *
     * This is sent when the server wants us to clear our buffers,
     * typically before a seek or track change.
     */
    private fun handleStreamClear() {
        Log.d(TAG, "Stream clear - flushing audio buffers")
        callback.onStreamClear()
    }

    /**
     * Handle binary messages from server.
     *
     * Binary protocol:
     * - Byte 0: message type
     *   - 4: Audio chunk
     *   - 8-11: Artwork for channels 0-3
     *   - 16: Visualization data
     * - Bytes 1-8: timestamp (int64, big-endian, microseconds)
     * - Remaining: payload data
     */
    private fun handleBinaryMessage(bytes: ByteString) {
        if (bytes.size < 9) {
            Log.w(TAG, "Binary message too short: ${bytes.size} bytes")
            return
        }

        val msgType = bytes[0].toInt() and 0xFF

        // Extract timestamp (big-endian int64)
        val timestampBytes = bytes.substring(1, 9).toByteArray()
        val buffer = ByteBuffer.wrap(timestampBytes).order(ByteOrder.BIG_ENDIAN)
        val serverTimestampMicros = buffer.getLong()

        // Get payload
        val payload = bytes.substring(9)

        when (msgType) {
            MSG_TYPE_AUDIO -> handleAudioChunk(serverTimestampMicros, payload)
            in MSG_TYPE_ARTWORK_BASE..(MSG_TYPE_ARTWORK_BASE + 3) -> {
                val channel = msgType - MSG_TYPE_ARTWORK_BASE
                handleArtwork(channel, payload)
            }
            else -> Log.d(TAG, "Unknown binary message type: $msgType")
        }
    }

    /**
     * Handle audio chunk with server timestamp.
     *
     * The timestamp indicates when the first sample should play in server time.
     * We forward this to the audio player via callback for synchronized playback.
     */
    private fun handleAudioChunk(serverTimestampMicros: Long, payload: ByteString) {
        // Forward to audio player - it will handle time conversion and sync
        callback.onAudioChunk(serverTimestampMicros, payload.toByteArray())
    }

    /**
     * Handle artwork binary data.
     */
    private fun handleArtwork(channel: Int, payload: ByteString) {
        Log.d(TAG, "Received artwork channel $channel: ${payload.size} bytes")
        callback.onArtwork(payload.toByteArray())
    }
}
