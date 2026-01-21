package com.sendspindroid.sendspin

import android.util.Log
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.protocol.GroupInfo
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.SendSpinProtocolHandler
import com.sendspindroid.sendspin.protocol.StreamConfig
import com.sendspindroid.sendspin.protocol.TrackMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
 * This class extends SendSpinProtocolHandler for shared protocol logic
 * and implements client-specific concerns:
 * - OkHttp WebSocket transport
 * - Connection state machine (Disconnected/Connecting/Connected/Error)
 * - Reconnection with exponential backoff
 * - Time filter freeze/thaw during reconnection
 */
class SendSpinClient(
    private val deviceName: String,
    private val callback: Callback
) : SendSpinProtocolHandler(TAG) {

    companion object {
        private const val TAG = "SendSpinClient"

        // Reconnection configuration
        // Short initial delay (500ms) to maximize reconnect attempts during buffer drain
        // Sequence: 500ms, 1s, 2s, 4s, 8s - gives ~5 attempts in first 15 seconds
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val INITIAL_RECONNECT_DELAY_MS = 500L // 500ms (was 1s)
        private const val MAX_RECONNECT_DELAY_MS = 10000L // 10 seconds (was 30s)

        /**
         * Gets the appropriate buffer capacity based on low memory mode setting.
         */
        fun getBufferCapacity(): Int {
            return if (UserSettings.lowMemoryMode) {
                SendSpinProtocol.Buffer.CAPACITY_LOW_MEM
            } else {
                SendSpinProtocol.Buffer.CAPACITY_NORMAL
            }
        }
    }

    /**
     * Callback interface for SendSpin events.
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
        fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?)
        fun onStreamClear()
        fun onAudioChunk(serverTimeMicros: Long, pcmData: ByteArray)
        fun onVolumeChanged(volume: Int)
        fun onMutedChanged(muted: Boolean)
        fun onSyncOffsetApplied(offsetMs: Double, source: String)
        fun onNetworkChanged()
        fun onReconnecting(attempt: Int, serverName: String)
        fun onReconnected()
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
        // Short connect timeout (5s) to fail fast during reconnection
        // When DRAINING, we typically have <5s of buffer, so waiting 15s for a
        // connection attempt to timeout would exhaust the buffer
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Must be 0 for WebSocket
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var serverAddress: String? = null
    private var serverPath: String? = null
    private var serverName: String? = null

    // Client identity
    private val clientId = UUID.randomUUID().toString()

    // Time synchronization (Kalman filter)
    private val timeFilter = SendspinTimeFilter()

    // Reconnection state
    private val userInitiatedDisconnect = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val reconnecting = AtomicBoolean(false)
    private var reconnectJob: Job? = null  // Pending reconnect coroutine - cancelled on disconnect

    val isConnected: Boolean
        get() = _connectionState.value is ConnectionState.Connected

    init {
        // Initialize time sync manager with our time filter
        initTimeSyncManager(timeFilter)
    }

    // ========== SendSpinProtocolHandler Implementation ==========

    override fun sendTextMessage(text: String) {
        val ws = webSocket
        if (ws == null) {
            Log.e(TAG, "Cannot send message - WebSocket is null")
            return
        }
        val success = ws.send(text)
        if (!success) {
            Log.w(TAG, "Failed to send message")
        }
    }

    override fun getCoroutineScope(): CoroutineScope = scope

    override fun getTimeFilter(): SendspinTimeFilter = timeFilter

    override fun getBufferCapacity(): Int = Companion.getBufferCapacity()

    override fun getClientId(): String = clientId

    override fun getDeviceName(): String = deviceName

    override fun onHandshakeComplete(serverName: String, serverId: String) {
        this.serverName = serverName

        // Check if this is a reconnection
        val wasReconnecting = timeFilter.isFrozen || reconnecting.get()

        if (timeFilter.isFrozen) {
            timeFilter.thaw()
            Log.i(TAG, "Time filter thawed after reconnection - sync preserved")
        }

        reconnecting.set(false)
        reconnectAttempts.set(0)
        _connectionState.value = ConnectionState.Connected(serverName)

        if (wasReconnecting) {
            callback.onReconnected()
            Log.i(TAG, "Reconnection successful")
        }

        callback.onConnected(serverName)
    }

    override fun onMetadataUpdate(metadata: TrackMetadata) {
        callback.onMetadataUpdate(
            metadata.title,
            metadata.artist,
            metadata.album,
            metadata.artworkUrl,
            metadata.durationMs,
            metadata.positionMs
        )
    }

    override fun onPlaybackStateChanged(state: String) {
        callback.onStateChanged(state)
    }

    override fun onVolumeCommand(volume: Int) {
        callback.onVolumeChanged(volume)
    }

    override fun onMuteCommand(muted: Boolean) {
        callback.onMutedChanged(muted)
    }

    override fun onGroupUpdate(info: GroupInfo) {
        callback.onGroupUpdate(info.groupId, info.groupName, info.playbackState)
    }

    override fun onStreamStart(config: StreamConfig) {
        val preferredCodec = UserSettings.getPreferredCodec()
        Log.i(TAG, "Stream started: server chose codec=${config.codec} (we preferred=$preferredCodec)")
        callback.onStreamStart(
            config.codec,
            config.sampleRate,
            config.channels,
            config.bitDepth,
            config.codecHeader
        )
    }

    override fun onStreamClear() {
        callback.onStreamClear()
    }

    override fun onAudioChunk(timestampMicros: Long, payload: ByteArray) {
        callback.onAudioChunk(timestampMicros, payload)
    }

    override fun onArtwork(channel: Int, payload: ByteArray) {
        callback.onArtwork(payload)
    }

    override fun onSyncOffsetApplied(offsetMs: Double, source: String) {
        callback.onSyncOffsetApplied(offsetMs, source)
    }

    // ========== Public API ==========

    /**
     * Get the connected server's name.
     */
    fun getServerName(): String? = serverName

    /**
     * Get the connected server's address.
     */
    fun getServerAddress(): String? = serverAddress

    /**
     * Get milliseconds since the last time sync measurement.
     */
    fun getLastTimeSyncAgeMs(): Long {
        val lastUpdate = timeFilter.lastUpdateTimeUs
        if (lastUpdate <= 0) return -1
        val nowUs = System.nanoTime() / 1000
        return (nowUs - lastUpdate) / 1000
    }

    /**
     * Called when the network changes.
     * During reconnection, we preserve the frozen sync state to maintain playback continuity.
     */
    fun onNetworkChanged() {
        if (!isConnected) return

        // If we're actively reconnecting, preserve the frozen sync state
        // This allows playback to continue from buffer without losing clock sync
        if (reconnecting.get() || timeFilter.isFrozen) {
            Log.i(TAG, "Network changed during reconnection - preserving frozen sync state")
            return
        }

        Log.i(TAG, "Network changed - resetting time filter for re-sync")
        timeFilter.reset()
        callback.onNetworkChanged()
    }

    /**
     * Called when network becomes available.
     * If we're actively reconnecting, cancel any pending backoff and immediately retry.
     * This minimizes buffer exhaustion by reconnecting as fast as possible.
     */
    fun onNetworkAvailable() {
        if (!reconnecting.get()) return

        val savedAddress = serverAddress ?: return
        val savedPath = serverPath ?: return

        Log.i(TAG, "Network available during reconnection - attempting immediate reconnect")

        // Cancel any pending backoff delay
        reconnectJob?.cancel()
        reconnectJob = null

        // Reset backoff counter for faster retry if this fails too
        // (Keep it at least 1 so we don't re-freeze the time filter)
        reconnectAttempts.set(1)

        // Immediately try to reconnect
        scope.launch {
            if (userInitiatedDisconnect.get() || !reconnecting.get()) {
                Log.d(TAG, "Reconnection cancelled before immediate retry")
                return@launch
            }

            Log.d(TAG, "Immediate reconnecting to: $savedAddress path=$savedPath")

            handshakeComplete = false
            stopTimeSync()

            val wsUrl = "ws://$savedAddress$savedPath"
            val request = Request.Builder()
                .url(wsUrl)
                .build()

            webSocket = okHttpClient.newWebSocket(request, WebSocketEventListener())
        }
    }

    /**
     * Connect to a SendSpin server.
     *
     * @param address Server address in "host:port" format
     * @param path WebSocket path (from mDNS TXT or default /sendspin)
     */
    fun connect(address: String, path: String = SendSpinProtocol.ENDPOINT_PATH) {
        if (isConnected) {
            Log.w(TAG, "Already connected, disconnecting first")
            disconnect()
        }

        val normalizedPath = normalizePath(path)

        Log.d(TAG, "Connecting to: $address path=$normalizedPath")
        _connectionState.value = ConnectionState.Connecting
        serverAddress = address
        serverPath = normalizedPath
        handshakeComplete = false
        timeFilter.reset()

        // Cancel any pending reconnect from previous connection attempt
        reconnectJob?.cancel()
        reconnectJob = null

        userInitiatedDisconnect.set(false)
        reconnectAttempts.set(0)
        reconnecting.set(false)

        val wsUrl = "ws://$address$normalizedPath"
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

        // Cancel any pending reconnect coroutine to prevent race condition
        reconnectJob?.cancel()
        reconnectJob = null

        stopTimeSync()
        reconnecting.set(false)
        sendGoodbye("user_request")
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        handshakeComplete = false
        _connectionState.value = ConnectionState.Disconnected
        callback.onDisconnected()
    }

    fun play() = sendCommand("play")
    fun pause() = sendCommand("pause")
    fun next() = sendCommand("next")
    fun previous() = sendCommand("previous")
    fun switchGroup() = sendCommand("switch")

    /**
     * Clean up resources.
     */
    fun destroy() {
        stopTimeSync()
        userInitiatedDisconnect.set(true)

        // Cancel any pending reconnect coroutine
        reconnectJob?.cancel()
        reconnectJob = null

        reconnecting.set(false)
        disconnect()
    }

    // ========== Private Methods ==========

    /**
     * Normalize and validate the WebSocket path parameter.
     */
    private fun normalizePath(path: String): String {
        if (path.isEmpty()) {
            Log.d(TAG, "Empty path provided, using default: ${SendSpinProtocol.ENDPOINT_PATH}")
            return SendSpinProtocol.ENDPOINT_PATH
        }

        val pathWithoutQuery = path.substringBefore("?")
        if (pathWithoutQuery != path) {
            Log.d(TAG, "Removed query string from path: '$path' -> '$pathWithoutQuery'")
        }

        if (pathWithoutQuery.isEmpty()) {
            Log.d(TAG, "Path empty after removing query string, using default: ${SendSpinProtocol.ENDPOINT_PATH}")
            return SendSpinProtocol.ENDPOINT_PATH
        }

        val normalizedPath = if (!pathWithoutQuery.startsWith("/")) {
            Log.d(TAG, "Path missing leading slash, prepending: '/$pathWithoutQuery'")
            "/$pathWithoutQuery"
        } else {
            pathWithoutQuery
        }

        return normalizedPath
    }

    /**
     * Attempt reconnection with exponential backoff.
     */
    private fun attemptReconnect() {
        val address = serverAddress
        val path = serverPath ?: SendSpinProtocol.ENDPOINT_PATH
        val savedServerName = serverName ?: address ?: "Unknown"

        if (address == null) {
            Log.w(TAG, "Cannot reconnect: no server address saved")
            return
        }

        if (userInitiatedDisconnect.get()) {
            Log.d(TAG, "Not reconnecting: user-initiated disconnect")
            return
        }

        val attempts = reconnectAttempts.incrementAndGet()

        // On first reconnection attempt, freeze the time filter
        if (attempts == 1) {
            timeFilter.freeze()
            Log.i(TAG, "Time filter frozen for reconnection (had ${timeFilter.measurementCountValue} measurements)")
        }

        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnection attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up")
            reconnecting.set(false)
            timeFilter.resetAndDiscard()
            _connectionState.value = ConnectionState.Error("Connection lost. Please reconnect manually.")
            callback.onError("Connection lost after $MAX_RECONNECT_ATTEMPTS reconnection attempts")
            callback.onDisconnected()
            return
        }

        val delayMs = (INITIAL_RECONNECT_DELAY_MS * (1 shl (attempts - 1)))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)

        Log.i(TAG, "Attempting reconnection $attempts/$MAX_RECONNECT_ATTEMPTS in ${delayMs}ms")
        reconnecting.set(true)
        _connectionState.value = ConnectionState.Connecting

        callback.onReconnecting(attempts, savedServerName)

        // Store the job so it can be cancelled if user disconnects during the delay
        reconnectJob = scope.launch {
            delay(delayMs)

            if (userInitiatedDisconnect.get() || !reconnecting.get()) {
                Log.d(TAG, "Reconnection cancelled")
                return@launch
            }

            Log.d(TAG, "Reconnecting to: $address path=$path (attempt $attempts)")

            handshakeComplete = false
            stopTimeSync()

            val wsUrl = "ws://$address$path"
            val request = Request.Builder()
                .url(wsUrl)
                .build()

            webSocket = okHttpClient.newWebSocket(request, WebSocketEventListener())
        }
    }

    /**
     * Check if an error is recoverable (should trigger reconnection).
     */
    private fun isRecoverableError(t: Throwable): Boolean {
        val cause = t.cause ?: t
        val message = t.message?.lowercase() ?: ""

        return when {
            cause is SocketException -> true
            cause is java.io.EOFException -> true
            message.contains("reset") -> true
            message.contains("abort") -> true
            message.contains("broken pipe") -> true
            message.contains("connection closed") -> true
            cause is SocketTimeoutException -> true
            cause is UnknownHostException -> false
            cause is SSLHandshakeException -> false
            message.contains("refused") -> false
            else -> true
        }
    }

    /**
     * Map exception to user-friendly error message.
     */
    private fun getSpecificErrorMessage(t: Throwable): String {
        val cause = t.cause ?: t

        return when (cause) {
            is ConnectException -> "Server refused connection. Check if SendSpin is running."
            is UnknownHostException -> "Server not found. Check the address."
            is SocketTimeoutException -> "Connection timeout. Server not responding."
            is NoRouteToHostException -> "Network unreachable. Check WiFi connection."
            is SSLHandshakeException -> "Secure connection failed."
            is SocketException -> "Connection lost. Check your network."
            else -> {
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

    // ========== WebSocket Event Listener ==========

    private inner class WebSocketEventListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected, sending client/hello")
            sendClientHello()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleTextMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleBinaryMessage(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")

            // Code 1000 = Normal Closure - server intentionally ended the session
            // This is NOT an error that should trigger reconnection
            val isNormalClosure = code == 1000

            if (!userInitiatedDisconnect.get() && handshakeComplete && !isNormalClosure) {
                // Abnormal closure (not code 1000) - attempt reconnection
                Log.i(TAG, "Abnormal closure (code=$code), attempting reconnection")
                attemptReconnect()
            } else {
                // Either user-initiated, pre-handshake, or server's normal closure
                if (isNormalClosure && !userInitiatedDisconnect.get()) {
                    Log.i(TAG, "Server closed connection normally (code 1000) - session ended")
                }
                reconnecting.set(false)
                _connectionState.value = ConnectionState.Disconnected
                callback.onDisconnected()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)

            val shouldReconnect = !userInitiatedDisconnect.get() &&
                    serverAddress != null &&
                    isRecoverableError(t)

            if (shouldReconnect) {
                Log.i(TAG, "Recoverable error, attempting reconnection: ${t.message}")
                attemptReconnect()
            } else {
                val errorMessage = getSpecificErrorMessage(t)
                reconnecting.set(false)
                _connectionState.value = ConnectionState.Error(errorMessage)
                callback.onError(errorMessage)
            }
        }
    }
}
