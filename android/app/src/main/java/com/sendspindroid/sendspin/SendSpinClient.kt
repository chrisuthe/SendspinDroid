package com.sendspindroid.sendspin

import android.content.Context
import android.os.Build
import android.util.Log
import com.sendspindroid.UserSettings
import com.sendspindroid.logging.AppLog
import com.sendspindroid.remote.WebRTCTransport
import com.sendspindroid.sendspin.transport.ProxyWebSocketTransport
import com.sendspindroid.sendspin.protocol.GroupInfo
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.SendSpinProtocolHandler
import com.sendspindroid.sendspin.protocol.StreamConfig
import com.sendspindroid.sendspin.protocol.TrackMetadata
import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.TransportState
import com.sendspindroid.sendspin.transport.WebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException

/**
 * Native Kotlin SendSpin client.
 *
 * Implements the Sendspin Protocol for synchronized multi-room audio streaming.
 * Protocol spec: https://www.sendspin-audio.com/spec/
 *
 * ## Protocol Overview
 * 1. Connect via WebSocket (local) or WebRTC DataChannel (remote)
 * 2. Send client/hello with capabilities
 * 3. Receive server/hello with active roles
 * 4. Send client/time messages continuously for clock sync
 * 5. Receive binary audio chunks (type 4) with microsecond timestamps
 * 6. Play audio at computed client time using Kalman-filtered offset
 *
 * ## Connection Modes
 * - **Local**: Direct WebSocket to server on local network (ws://host:port/sendspin)
 * - **Remote**: WebRTC DataChannel via Music Assistant Remote Access (26-char Remote ID)
 *
 * This class extends SendSpinProtocolHandler for shared protocol logic
 * and implements client-specific concerns:
 * - Transport abstraction (WebSocket or WebRTC)
 * - Connection state machine (Disconnected/Connecting/Connected/Error)
 * - Reconnection with exponential backoff
 * - Time filter freeze/thaw during reconnection
 */
class SendSpinClient(
    private val context: Context,
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
        private const val HIGH_POWER_RECONNECT_DELAY_MS = 30_000L // 30s steady-state for high power mode

        // Stall watchdog: while connected+handshake-complete, if no bytes arrive for
        // this long, force-close the transport so the existing reconnect path kicks in.
        // Shorter than Ktor's 30s ping-timeout to beat buffer drain.
        private const val STALL_TIMEOUT_MS = 7_000L
        private const val STALL_CHECK_INTERVAL_MS = 3_000L

        // Idle-mode stall threshold. Larger than the streaming threshold because during
        // idle the only regular server->client traffic is server/time responses to our
        // TimeSyncManager bursts. Burst cadence is 500ms-3s once converged, so ~9s is
        // the worst-case natural silence; 20s gives 2x headroom while still catching
        // server death with headroom for reconnect + resync inside the ~30s audio buffer.
        // Issue #127.
        private const val IDLE_STALL_TIMEOUT_MS = 20_000L

        // After this many consecutive LOCAL-mode reconnect failures in a row, switch
        // internally to PROXY if a fallback was configured via setProxyFallback().
        // Prevents indefinite retry of a dead LAN address when the server is no longer
        // reachable on this network. Issue #126.
        private const val LOCAL_RECONNECT_FALLBACK_THRESHOLD = 3

    }

    /**
     * Callback interface for SendSpin events.
     */
    interface Callback {
        fun onServerDiscovered(name: String, address: String)
        fun onConnected(serverName: String)
        /**
         * Called when disconnected from the server.
         * @param wasUserInitiated true if the user explicitly requested disconnect
         * @param wasReconnectExhausted true if internal reconnect attempts were exhausted
         */
        fun onDisconnected(wasUserInitiated: Boolean = false, wasReconnectExhausted: Boolean = false)
        fun onStateChanged(state: String)
        fun onGroupUpdate(groupId: String, groupName: String, playbackState: String)
        fun onMetadataUpdate(
            title: String,
            artist: String,
            album: String,
            artworkUrl: String,
            durationMs: Long,
            positionMs: Long,
            playbackSpeed: Int = 1000
        )
        fun onArtwork(imageData: ByteArray)
        fun onArtworkCleared()
        fun onError(message: String)
        fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?)
        fun onStreamClear()
        fun onStreamEnd()
        fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray)
        fun onVolumeChanged(volume: Int)
        fun onMutedChanged(muted: Boolean)
        fun onSyncOffsetApplied(offsetMs: Double, source: String)
        fun onNetworkChanged()
        fun onReconnecting(attempt: Int, serverName: String)
        fun onReconnected()
    }

    /**
     * Connection mode for the client.
     */
    enum class ConnectionMode {
        LOCAL,   // Direct WebSocket on local network
        REMOTE,  // WebRTC via Music Assistant Remote Access
        PROXY    // WebSocket via authenticated reverse proxy
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

    // Transport abstraction - can be WebSocket (local) or WebRTC (remote)
    private var transport: SendSpinTransport? = null
    private var connectionMode: ConnectionMode = ConnectionMode.LOCAL

    // Connection info (stored for reconnection)
    private var serverAddress: String? = null
    private var serverPath: String? = null
    private var remoteId: String? = null
    private var serverName: String? = null

    // Proxy authentication state
    private var authToken: String? = null
    private var awaitingAuthResponse = false

    // Optional PROXY fallback config. When set and the client is reconnecting in
    // LOCAL mode after [LOCAL_RECONNECT_FALLBACK_THRESHOLD] consecutive failures,
    // the client switches internally to PROXY using these values instead of
    // continuing to retry a dead LAN address. See setProxyFallback(). Issue #126.
    private var proxyFallbackUrl: String? = null
    private var proxyFallbackAuthToken: String? = null

    // Client identity - persisted across app launches
    private val clientId = UserSettings.getPlayerId()

    // Time synchronization (Kalman filter)
    private val timeFilter = SendspinTimeFilter()

    // Reconnection state
    private val userInitiatedDisconnect = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val reconnecting = AtomicBoolean(false)
    private var reconnectJob: Job? = null  // Pending reconnect coroutine - cancelled on disconnect

    // Network awareness for smart reconnection
    // When network is unavailable, reconnect attempts are paused (not wasted)
    private val networkAvailable = AtomicBoolean(true)
    private val waitingForNetwork = AtomicBoolean(false)

    // Stall watchdog state. lastByteReceivedAtMs is updated on EVERY text/binary
    // message from the transport. stallWatchdogJob is the polling coroutine.
    private val lastByteReceivedAtMs = AtomicLong(System.currentTimeMillis())
    @Volatile
    private var stallWatchdogJob: Job? = null

    // True while a server-announced audio stream is active. The stall watchdog
    // only trips while streaming - during idle (no stream) the server may send
    // nothing for long periods, which would cause false-positive stalls.
    private val streamActive = AtomicBoolean(false)

    // -- Connection health telemetry (issue #128). All observational: updated on
    // event paths that already touch state (handshake-complete, onClosed,
    // onFailure, attemptReconnect); read by the stats poll and the structured
    // [disconnect]/[reconnect-ok] log lines. No hot-path cost.
    private val reconnectAttemptsTotal = AtomicInteger(0)
    @Volatile private var connectedAtMs: Long? = null
    @Volatile private var lastDisconnectAtMs: Long? = null
    @Volatile private var lastDisconnectCode: Int? = null
    @Volatile private var lastDisconnectReason: String? = null
    @Volatile private var lastDisconnectMode: ConnectionMode? = null

    val isConnected: Boolean
        get() = _connectionState.value is ConnectionState.Connected

    /**
     * Get the number of reconnection attempts since last successful connect.
     */
    fun getReconnectAttempts(): Int = reconnectAttempts.get()

    // -- Connection health accessors (issue #128) --

    /** Milliseconds since the transport last delivered a text or binary frame. */
    fun getLastByteReceivedAgoMs(): Long =
        System.currentTimeMillis() - lastByteReceivedAtMs.get()

    /**
     * True when the stall watchdog would actually evaluate: handshake is
     * complete, the client isn't mid-reconnect, and the user hasn't asked to
     * disconnect. Note: this does NOT check `streamActive` -- the watchdog
     * fires in both streaming (7 s threshold) and idle (20 s threshold) states
     * per #127, so "armed" means "the watchdog is running and will trip if
     * the appropriate silence threshold is exceeded."
     */
    fun isStallWatchdogArmed(): Boolean =
        handshakeComplete && !userInitiatedDisconnect.get() && !reconnecting.get()

    /** Lifetime reconnect attempts (survives across sessions within the process). */
    fun getReconnectAttemptsTotal(): Int = reconnectAttemptsTotal.get()

    /** Most recent close code seen on an abnormal disconnect; null if none. */
    fun getLastDisconnectCode(): Int? = lastDisconnectCode

    /** Most recent close reason / error message. null if no disconnect yet. */
    fun getLastDisconnectReason(): String? = lastDisconnectReason

    /** When the current session handshake completed (null if not connected). */
    fun getConnectedAtMs(): Long? = connectedAtMs

    init {
        // Initialize time sync manager with our time filter
        initTimeSyncManager(timeFilter)
    }

    // ========== SendSpinProtocolHandler Implementation ==========

    override fun sendTextMessage(text: String) {
        val t = transport ?: return  // Silently drop if transport is gone (e.g. post-disconnect race)
        val success = t.send(text)
        if (!success) {
            Log.w(TAG, "Failed to send message")
        }
    }

    override fun getCoroutineScope(): CoroutineScope = scope

    override fun getTimeFilter(): SendspinTimeFilter = timeFilter

    override fun isLowMemoryMode(): Boolean = UserSettings.lowMemoryMode

    override fun getClientId(): String = clientId

    override fun getDeviceName(): String = deviceName

    override fun getManufacturer(): String = Build.MANUFACTURER ?: "Unknown"

    override fun getSupportedFormats(): List<MessageBuilder.FormatEntry> {
        val bitDepths = if (isLowMemoryMode()) {
            listOf(16)
        } else {
            AudioDecoderFactory.getSupportedPcmBitDepths()
        }
        return MessageBuilder.buildSupportedFormats(
            preferredCodec = UserSettings.getPreferredCodec(),
            isCodecSupported = { AudioDecoderFactory.isCodecSupported(it) },
            supportedBitDepths = bitDepths
        )
    }

    override fun onHandshakeComplete(serverName: String, serverId: String) {
        this.serverName = serverName

        // Check if this is a reconnection
        val wasReconnecting = timeFilter.isFrozen || reconnecting.get()

        if (timeFilter.isFrozen) {
            timeFilter.thaw()
            Log.i(TAG, "Time filter thawed after reconnection - re-syncing with increased covariance")
        }

        // Capture telemetry for the structured [reconnect-ok] line before resetting
        // current-cycle counters. Issue #128.
        val attemptsThisCycle = reconnectAttempts.get()
        val disconnectAtMs = lastDisconnectAtMs

        reconnecting.set(false)
        reconnectAttempts.set(0)
        waitingForNetwork.set(false)
        _connectionState.value = ConnectionState.Connected(serverName)

        // Mark session start for uptime calculation and clear the disconnect marker.
        // Issue #128.
        connectedAtMs = System.currentTimeMillis()
        lastDisconnectAtMs = null

        if (wasReconnecting) {
            // Emit a structured recovery log line so shared on-device logs show
            // end-to-end reconnect outcomes (disconnect -> handshake complete).
            // Issue #128.
            if (disconnectAtMs != null) {
                val tookSeconds = (System.currentTimeMillis() - disconnectAtMs) / 1000.0
                AppLog.Network.i(
                    "[reconnect-ok] took_s=%.1f attempts_this_cycle=%d attempts_total=%d".format(
                        tookSeconds,
                        attemptsThisCycle,
                        reconnectAttemptsTotal.get(),
                    )
                )
            }
            callback.onReconnected()
            Log.i(TAG, "Reconnection successful")
        }

        callback.onConnected(serverName)
        streamActive.set(false)  // fresh handshake - wait for server to announce stream state
        startStallWatchdog()  // (re)start watchdog now that we have a live handshake-complete session
    }

    override fun onMetadataUpdate(metadata: TrackMetadata) {
        callback.onMetadataUpdate(
            metadata.title,
            metadata.artist,
            metadata.album,
            metadata.artworkUrl,
            metadata.durationMs,
            metadata.positionMs,
            metadata.progress.playbackSpeed
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
        streamActive.set(true)
        // Reset so we don't false-trip from any stale timestamp accumulated while
        // the stream was inactive (we were not expecting data then).
        lastByteReceivedAtMs.set(System.currentTimeMillis())

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
        streamActive.set(false)
        callback.onStreamClear()
    }

    override fun onStreamEnd() {
        streamActive.set(false)
        callback.onStreamEnd()
    }

    override fun onAudioChunk(timestampMicros: Long, audioData: ByteArray) {
        callback.onAudioChunk(timestampMicros, audioData)
    }

    override fun onArtwork(channel: Int, payload: ByteArray) {
        if (payload.isEmpty()) {
            callback.onArtworkCleared()
        } else {
            callback.onArtwork(payload)
        }
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

        Log.i(TAG, "Network available during reconnection - attempting immediate reconnect")

        // Cancel any pending backoff delay
        reconnectJob?.cancel()
        reconnectJob = null

        // Reset backoff counter for faster retry if this fails too
        // (Keep it at least 1 so we don't re-freeze the time filter)
        reconnectAttempts.set(1)

        // Immediately try to reconnect using the appropriate mode
        scope.launch {
            if (userInitiatedDisconnect.get() || !reconnecting.get()) {
                Log.d(TAG, "Reconnection cancelled before immediate retry")
                return@launch
            }

            handshakeComplete = false
            stopTimeSync()

            when (connectionMode) {
                ConnectionMode.LOCAL -> {
                    val savedAddress = serverAddress ?: return@launch
                    val savedPath = serverPath ?: return@launch
                    Log.d(TAG, "Immediate reconnecting to: $savedAddress path=$savedPath")
                    createLocalTransport(savedAddress, savedPath)
                }
                ConnectionMode.REMOTE -> {
                    val savedRemoteId = remoteId ?: return@launch
                    Log.d(TAG, "Immediate reconnecting via Remote ID: $savedRemoteId")
                    createRemoteTransport(savedRemoteId)
                }
                ConnectionMode.PROXY -> {
                    val savedUrl = serverAddress ?: return@launch
                    Log.d(TAG, "Immediate reconnecting via proxy: $savedUrl")
                    createProxyTransport(savedUrl)
                }
            }
        }
    }

    /**
     * Called by PlaybackService when network availability changes.
     * When network is lost during reconnection, pauses attempts without wasting them.
     * When network returns, resumes immediately via onNetworkAvailable().
     */
    fun setNetworkAvailable(available: Boolean) {
        networkAvailable.set(available)
        if (available && waitingForNetwork.getAndSet(false)) {
            Log.i(TAG, "Network restored - resuming paused reconnection")
            onNetworkAvailable()
        }
    }

    /**
     * Connect to a SendSpin server on the local network.
     *
     * @param address Server address in "host:port" format
     * @param path WebSocket path (from mDNS TXT or default /sendspin)
     */
    fun connect(address: String, path: String = SendSpinProtocol.ENDPOINT_PATH) {
        connectLocal(address, path)
    }

    /**
     * Configure a PROXY fallback for LOCAL mode. When set, the client will switch
     * internally to PROXY after [LOCAL_RECONNECT_FALLBACK_THRESHOLD] consecutive
     * LOCAL-mode reconnect failures, instead of retrying the dead LAN address
     * indefinitely. Closes the "moved off LAN but saved LOCAL address is still
     * being tried" gap from issue #126.
     *
     * Call with (null, null) to clear (e.g., when switching servers, or when
     * connecting to a server that has no PROXY configured).
     *
     * Safe to call at any time; takes effect on the next reconnect cycle.
     */
    fun setProxyFallback(url: String?, authToken: String?) {
        proxyFallbackUrl = url
        proxyFallbackAuthToken = authToken
    }

    /**
     * Connect to a SendSpin server on the local network.
     *
     * @param address Server address in "host:port" format
     * @param path WebSocket path (from mDNS TXT or default /sendspin)
     */
    fun connectLocal(address: String, path: String = SendSpinProtocol.ENDPOINT_PATH) {
        if (isConnected) {
            Log.w(TAG, "Already connected, disconnecting first")
            disconnect()
        }

        val normalizedPath = normalizePath(path)

        Log.d(TAG, "Connecting locally to: $address path=$normalizedPath")
        prepareForConnection()

        connectionMode = ConnectionMode.LOCAL
        serverAddress = address
        serverPath = normalizedPath
        remoteId = null

        createLocalTransport(address, normalizedPath)
    }

    /**
     * Connect to a SendSpin server via Music Assistant Remote Access.
     *
     * @param remoteId The 26-character Remote ID from Music Assistant settings
     */
    fun connectRemote(remoteId: String) {
        if (isConnected) {
            Log.w(TAG, "Already connected, disconnecting first")
            disconnect()
        }

        Log.d(TAG, "Connecting remotely via Remote ID: $remoteId")
        prepareForConnection()

        connectionMode = ConnectionMode.REMOTE
        this.remoteId = remoteId
        serverAddress = null
        serverPath = null

        createRemoteTransport(remoteId)
    }

    /**
     * Connect to a SendSpin server via authenticated reverse proxy.
     *
     * The connection flow is:
     * 1. Connect WebSocket to the proxy URL (wss://domain.com/sendspin)
     * 2. Send auth message with token and client_id
     * 3. Wait for auth_ok response
     * 4. Proceed with normal client/hello handshake
     *
     * @param url The proxy URL (e.g., "https://ma.example.com/sendspin")
     * @param authToken The long-lived authentication token from Music Assistant
     */
    fun connectProxy(url: String, authToken: String) {
        if (isConnected) {
            Log.w(TAG, "Already connected, disconnecting first")
            disconnect()
        }

        Log.d(TAG, "Connecting via proxy to: $url")
        prepareForConnection()

        connectionMode = ConnectionMode.PROXY
        this.authToken = authToken
        this.serverAddress = url  // Store full URL for reconnection
        this.serverPath = null    // Path is included in URL
        this.remoteId = null

        createProxyTransport(url)
    }

    /**
     * Common preparation for both local and remote connections.
     */
    private fun prepareForConnection() {
        _connectionState.value = ConnectionState.Connecting
        handshakeComplete = false
        awaitingAuthResponse = false
        timeFilter.reset()

        // Cancel any pending reconnect from previous connection attempt
        reconnectJob?.cancel()
        reconnectJob = null

        userInitiatedDisconnect.set(false)
        reconnectAttempts.set(0)
        reconnecting.set(false)
        waitingForNetwork.set(false)

        // Clean up any existing transport.
        // Clear the listener first to prevent stale callbacks (e.g., onOpen from
        // a previous OkHttp WebSocket) from firing on the new transport's listener.
        transport?.setListener(null)
        transport?.destroy()
        transport = null
    }

    /**
     * Get the WebSocket ping interval based on High Power Mode setting.
     * High Power Mode uses 15s for faster drop detection, normal uses 30s.
     */
    private fun getPingIntervalSeconds(): Long =
        if (UserSettings.highPowerMode) 15L else 30L

    /**
     * Create and connect a local WebSocket transport.
     */
    private fun createLocalTransport(address: String, path: String) {
        val wsTransport = WebSocketTransport(address, path, pingIntervalSeconds = getPingIntervalSeconds())
        transport = wsTransport
        wsTransport.setListener(TransportEventListener())
        wsTransport.connect()
    }

    /**
     * Create and connect a remote WebRTC transport.
     */
    private fun createRemoteTransport(remoteId: String) {
        val rtcTransport = WebRTCTransport(context, remoteId)
        transport = rtcTransport
        rtcTransport.setListener(TransportEventListener())
        rtcTransport.connect()
    }

    /**
     * Create and connect a proxy WebSocket transport.
     * Auth token is passed to the transport for inclusion in the HTTP upgrade request header.
     */
    private fun createProxyTransport(url: String) {
        val proxyTransport = ProxyWebSocketTransport(
            url = url,
            authToken = authToken,
            pingIntervalSeconds = getPingIntervalSeconds()
        )
        transport = proxyTransport
        proxyTransport.setListener(TransportEventListener())
        proxyTransport.connect()
    }

    /**
     * Get the current connection mode.
     */
    fun getConnectionMode(): ConnectionMode = connectionMode

    /**
     * Get the Remote ID if connected via remote access.
     */
    fun getRemoteId(): String? = remoteId

    /**
     * Get the MA API DataChannel from the WebRTC transport.
     *
     * Only available when connected in REMOTE mode and the "ma-api"
     * DataChannel has been established. Returns null in LOCAL/PROXY modes
     * or if the channel is not yet open.
     */
    fun getMaApiDataChannel(): org.webrtc.DataChannel? {
        val t = transport
        return if (t is WebRTCTransport) t.getMaApiDataChannel() else null
    }

    /**
     * Drain any MA API messages buffered by WebRTCTransport before the
     * MaDataChannelTransport observer was registered.
     */
    fun drainMaApiMessageBuffer(): List<String> {
        val t = transport
        return if (t is WebRTCTransport) t.drainMaApiMessageBuffer() else emptyList()
    }

    /**
     * Disconnect from the current server for reasons that should trigger an
     * upward auto-reconnect, such as the underlying network transport type
     * changing (WiFi -> Cellular). Unlike [disconnect], this does NOT set
     * [userInitiatedDisconnect]. Fires `onDisconnected(wasUserInitiated=false,
     * wasReconnectExhausted=false)`, which MainActivity's STATE_DISCONNECTED
     * handler interprets as "start AutoReconnectManager" -- the outer reconnect
     * loop re-runs `ConnectionSelector` fresh and picks the right mode for
     * whatever network we are on now.
     *
     * The reason for existing: [disconnect] is a user action (tap 'Switch Server'
     * etc.) and explicitly suppresses auto-reconnect. We want the opposite here:
     * the user did nothing wrong, the network changed out from under us, and the
     * inner reconnect loop is going to spin forever on the wrong mode. Yield
     * cleanly and let the outer loop re-select.
     */
    fun disconnectForReselection() {
        stopStallWatchdog()
        Log.i(TAG, "Disconnecting for reselection (transport-type change)")

        // Cancel any pending reconnect coroutine to prevent races
        reconnectJob?.cancel()
        reconnectJob = null

        stopTimeSync()
        reconnecting.set(false)
        waitingForNetwork.set(false)
        sendGoodbye("network_type_changed")
        // Clear the transport listener BEFORE closing to prevent the async onClosed
        // callback from firing a second onDisconnected after we fire one synchronously below.
        transport?.setListener(null)
        transport?.close(1000, "Reselection")
        transport = null
        handshakeComplete = false
        _connectionState.value = ConnectionState.Disconnected
        callback.onDisconnected(wasUserInitiated = false, wasReconnectExhausted = false)
    }

    /**
     * Disconnect from the current server.
     */
    fun disconnect() {
        stopStallWatchdog()
        Log.d(TAG, "Disconnecting (user-initiated)")
        userInitiatedDisconnect.set(true)

        // Cancel any pending reconnect coroutine to prevent race condition
        reconnectJob?.cancel()
        reconnectJob = null

        stopTimeSync()
        reconnecting.set(false)
        waitingForNetwork.set(false)
        sendGoodbye("user_request")
        // Clear the transport listener BEFORE closing to prevent the async onClosed
        // callback from firing a second onDisconnected after we fire one synchronously below.
        transport?.setListener(null)
        transport?.close(1000, "User disconnect")
        transport = null
        handshakeComplete = false
        _connectionState.value = ConnectionState.Disconnected
        callback.onDisconnected(wasUserInitiated = true, wasReconnectExhausted = false)
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
        stopStallWatchdog()
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
     * Start the stall watchdog. Called when the connection reaches a state where
     * we expect data to be flowing. Cancels any previous instance.
     */
    private fun startStallWatchdog() {
        stallWatchdogJob?.cancel()
        // Reset so we don't false-trip using a stale pre-handshake timestamp
        lastByteReceivedAtMs.set(System.currentTimeMillis())
        stallWatchdogJob = scope.launch {
            while (true) {
                delay(STALL_CHECK_INTERVAL_MS)
                checkStall()
            }
        }
    }

    /**
     * Stop the stall watchdog. Called on disconnect or during reconnect attempts.
     */
    private fun stopStallWatchdog() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null
    }

    /**
     * Check whether the transport has gone silent for too long and force-close it
     * if so. Only acts when the client is connected, handshake is complete, and we
     * are not already in a reconnect cycle.
     *
     * Uses a two-tier threshold: [STALL_TIMEOUT_MS] while a stream is active (audio
     * frames should arrive continuously), and [IDLE_STALL_TIMEOUT_MS] when idle
     * (only regular traffic is server/time responses to our TimeSyncManager bursts).
     * The idle threshold catches server death during kiosk/dashboard-style
     * deployments where music is rarely flowing -- without it, detection relied on
     * OkHttp's 30s ping timeout alone which consumes the entire audio buffer.
     * Issue #127.
     *
     * Private for production; reached via reflection from SendSpinClientStallWatchdogTest.
     */
    private fun checkStall() {
        if (userInitiatedDisconnect.get()) return
        if (reconnecting.get()) return
        if (!handshakeComplete) return
        val t = transport ?: return
        if (!t.isConnected) return

        val streaming = streamActive.get()
        val threshold = if (streaming) STALL_TIMEOUT_MS else IDLE_STALL_TIMEOUT_MS
        val sinceLastByte = System.currentTimeMillis() - lastByteReceivedAtMs.get()
        if (sinceLastByte > threshold) {
            val mode = if (streaming) "streaming" else "idle"
            Log.w(TAG, "Stall watchdog: no data received in ${sinceLastByte}ms ($mode threshold ${threshold}ms) - forcing transport close")
            // 1001 "Going Away" is non-1000 so onClosed path triggers reconnection
            t.close(1001, "stall watchdog ($mode)")
        }
    }

    /**
     * Record disconnect state and emit a structured `[disconnect]` log line
     * consumable by anyone reading the on-device log file shared via Settings.
     *
     * Invariants (issue #128):
     *   * Fires on every disconnect path -- normal, abnormal, pre-handshake, or
     *     user-initiated. The stats screen's "last disconnect" is informational
     *     regardless of whether reconnect is scheduled.
     *   * Uptime is derived from [connectedAtMs] (null -> "preconnect" when
     *     handshake had not yet completed).
     *   * Logs at INFO so `AppLog.level = WARN or above` suppresses emission
     *     without any formatting cost.
     */
    private fun recordDisconnectTelemetry(
        code: Int?,
        reasonText: String,
        isNormalClosure: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val connectedAt = connectedAtMs

        // Persist for the stats screen. Keep the "abnormal" flag for Part B's
        // reconnect-result correlation: lastDisconnectAtMs only gates the
        // [reconnect-ok] emission on a subsequent handshake complete, not the
        // user-facing stats, so only set it on abnormal closures that will
        // actually trigger a retry cycle.
        lastDisconnectCode = code
        lastDisconnectReason = reasonText
        lastDisconnectMode = connectionMode
        if (!isNormalClosure && !userInitiatedDisconnect.get()) {
            lastDisconnectAtMs = now
        }

        // Session ended for uptime purposes regardless of whether we reconnect.
        connectedAtMs = null

        val codeField = code?.toString() ?: "none"
        val uptimeField = if (connectedAt != null) {
            "%.1f".format((now - connectedAt) / 1000.0)
        } else {
            "preconnect"
        }
        AppLog.Network.i(
            "[disconnect] code=$codeField reason=${reasonText.ifBlank { "unknown" }} " +
                "mode=$connectionMode uptime_s=$uptimeField " +
                "attempts_total=${reconnectAttemptsTotal.get()}"
        )
    }

    /**
     * Attempt reconnection with exponential backoff.
     *
     * Exponential backoff for the first 5 attempts (500ms -> 8s), then 30s
     * steady-state retries forever. Applies in both normal and high-power mode.
     * If network is unavailable, pauses without consuming an attempt.
     */
    private fun attemptReconnect() {
        val savedServerName = serverName ?: serverAddress ?: remoteId ?: "Unknown"

        // Verify we have connection info for the current mode
        val canReconnect = when (connectionMode) {
            ConnectionMode.LOCAL -> serverAddress != null
            ConnectionMode.REMOTE -> remoteId != null
            ConnectionMode.PROXY -> serverAddress != null && !authToken.isNullOrBlank()
        }

        if (!canReconnect) {
            Log.w(TAG, "Cannot reconnect: no connection info saved for mode $connectionMode")
            return
        }

        if (userInitiatedDisconnect.get()) {
            Log.d(TAG, "Not reconnecting: user-initiated disconnect")
            return
        }

        val attempts = reconnectAttempts.incrementAndGet()
        // Lifetime counter survives across reconnect cycles. Issue #128.
        reconnectAttemptsTotal.incrementAndGet()

        // LOCAL -> PROXY internal fallback: if LOCAL reconnect has failed
        // [LOCAL_RECONNECT_FALLBACK_THRESHOLD] times in a row and a PROXY fallback
        // is configured, switch modes internally instead of retrying a LAN address
        // that is apparently unreachable on this network. Issue #126.
        //
        // Note: we intentionally do NOT call disconnectForReselection() here.
        // That would trigger MainActivity.startReconnecting -> AutoReconnectManager,
        // but AutoReconnectManager's performAutoReconnect() returns optimistically
        // and would re-select LOCAL first, creating an infinite ping-pong. Doing
        // the mode switch internally keeps this fix self-contained.
        val fbUrl = proxyFallbackUrl
        val fbToken = proxyFallbackAuthToken
        if (connectionMode == ConnectionMode.LOCAL &&
            attempts > LOCAL_RECONNECT_FALLBACK_THRESHOLD &&
            !fbUrl.isNullOrBlank() &&
            !fbToken.isNullOrBlank()) {
            Log.i(TAG, "LOCAL reconnect failed $attempts times; switching internally to PROXY fallback")
            connectionMode = ConnectionMode.PROXY
            serverAddress = fbUrl
            serverPath = null  // PROXY URL already carries the path; matches connectProxy()
            authToken = fbToken
            // Reset so PROXY attempt counting starts fresh (backoff from attempt 1).
            reconnectAttempts.set(0)
            // Re-invoke attemptReconnect so the PROXY path goes through the same
            // freeze/backoff/transport-creation flow. This also re-checks
            // canReconnect and userInitiatedDisconnect cleanly.
            attemptReconnect()
            return
        }

        // On first reconnection attempt, freeze the time filter
        if (attempts == 1) {
            timeFilter.freeze()
            Log.i(TAG, "Time filter frozen for reconnection (had ${timeFilter.measurementCountValue} measurements)")
        }
        stopStallWatchdog()  // watchdog restarts on next successful handshake via onHandshakeComplete

        // If network is unavailable, pause without wasting an attempt
        // setNetworkAvailable(true) will resume via onNetworkAvailable()
        if (!networkAvailable.get()) {
            Log.i(TAG, "Network unavailable - pausing reconnection (attempt $attempts saved)")
            reconnectAttempts.decrementAndGet()
            waitingForNetwork.set(true)
            reconnecting.set(true)
            _connectionState.value = ConnectionState.Connecting
            callback.onReconnecting(attempts.coerceAtLeast(1), savedServerName)
            return
        }

        // Exponential backoff for first 5 attempts, then 30s steady-state forever.
        // Applies in both normal and high power mode - the user can always disconnect
        // manually if they're done listening.
        val delayMs = if (attempts > MAX_RECONNECT_ATTEMPTS) {
            HIGH_POWER_RECONNECT_DELAY_MS
        } else {
            (INITIAL_RECONNECT_DELAY_MS * (1 shl (attempts - 1)))
                .coerceAtMost(MAX_RECONNECT_DELAY_MS)
        }

        Log.i(TAG, "Attempting reconnection $attempts in ${delayMs}ms")
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

            handshakeComplete = false
            stopTimeSync()

            // Clean up old transport
            transport?.destroy()
            transport = null

            // Reconnect using the appropriate mode
            when (connectionMode) {
                ConnectionMode.LOCAL -> {
                    val address = serverAddress ?: return@launch
                    val path = serverPath ?: SendSpinProtocol.ENDPOINT_PATH
                    Log.d(TAG, "Reconnecting to: $address path=$path (attempt $attempts)")
                    createLocalTransport(address, path)
                }
                ConnectionMode.REMOTE -> {
                    val id = remoteId ?: return@launch
                    Log.d(TAG, "Reconnecting via Remote ID: $id (attempt $attempts)")
                    createRemoteTransport(id)
                }
                ConnectionMode.PROXY -> {
                    val url = serverAddress ?: return@launch
                    Log.d(TAG, "Reconnecting via proxy: $url (attempt $attempts)")
                    createProxyTransport(url)
                }
            }
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

    // ========== Transport Event Listener ==========

    /**
     * Unified event listener for both WebSocket and WebRTC transports.
     *
     * ## Reconnect-gate policy (issue #129)
     *
     * Both [onClosed] and [onFailure] trigger [attemptReconnect] under the same
     * core conditions:
     *   * Not a user-initiated disconnect (`!userInitiatedDisconnect`).
     *   * Have connection info for the current mode (address + token for PROXY,
     *     remoteId for REMOTE, address for LOCAL).
     *   * The failure is transient / unexpected (non-1000 close code for
     *     [onClosed]; `isRecoverable` exception class for [onFailure]).
     *
     * Notably, `handshakeComplete` is NOT part of the gate. A server that
     * accepts the WebSocket upgrade and then closes abnormally before
     * `server/hello` arrives is retried -- backoff with 30 s steady-state
     * after 5 attempts handles the "server is broken" case without spinning,
     * and the existing `!isNormalClosure` / `isRecoverable` filters prevent
     * reconnect storms on deterministic rejections (code 1000 from an
     * accept-then-reject server, DNS, SSL, auth failures).
     */
    private inner class TransportEventListener : SendSpinTransport.Listener {

        override fun onConnected() {
            Log.d(TAG, "Transport connected")

            if (connectionMode == ConnectionMode.PROXY && !authToken.isNullOrBlank()) {
                // Proxy mode: send auth message first, then wait for auth_ok before hello.
                // The SendSpin server protocol requires a JSON auth message as the first
                // WebSocket message.
                Log.d(TAG, "Sending proxy auth message (token ${authToken!!.length} chars)")
                awaitingAuthResponse = true
                val authMsg = buildJsonObject {
                    put("type", JsonPrimitive("auth"))
                    put("token", JsonPrimitive(authToken))
                    put("client_id", JsonPrimitive(clientId))
                }
                val sent = transport?.send(authMsg.toString())
                Log.d(TAG, "Auth message send result: $sent")
            } else if (connectionMode == ConnectionMode.PROXY && authToken.isNullOrBlank()) {
                // Proxy mode but no token available - auth will fail
                Log.e(TAG, "Proxy connection has no auth token - server will reject")
                callback.onError("No auth token available. Please re-configure the server with valid credentials.")
                disconnect()
            } else {
                // Local/Remote mode: proceed directly with hello
                sendClientHello()
            }
        }

        override fun onMessage(text: String) {
            lastByteReceivedAtMs.set(System.currentTimeMillis())
            // Check for auth failure (server may send error if token is invalid)
            if (connectionMode == ConnectionMode.PROXY && !handshakeComplete) {
                try {
                    val json = Json.parseToJsonElement(text).jsonObject
                    val msgType = json["type"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (msgType == "auth_failed" || msgType == "error") {
                        val msg = json["message"]?.jsonPrimitive?.contentOrNull ?: "Authentication failed"
                        Log.e(TAG, "Proxy auth failed: $msg")
                        awaitingAuthResponse = false
                        callback.onError("Authentication failed: $msg")
                        disconnect()
                        return
                    }
                } catch (e: Exception) {
                    // Not a JSON message or doesn't have type field - continue normally
                }
            }

            // After receiving first message post-auth, send client/hello
            if (awaitingAuthResponse) {
                Log.d(TAG, "Received auth-ack, sending client/hello")
                awaitingAuthResponse = false
                sendClientHello()
                // Consume the auth-ack message; do NOT forward it to the protocol handler.
                // If the auth-ack were forwarded, it could be misinterpreted as a protocol
                // message (e.g., a server/hello arriving before client/hello is sent).
                return
            }

            handleTextMessage(text)
        }

        override fun onMessage(bytes: ByteArray) {
            lastByteReceivedAtMs.set(System.currentTimeMillis())
            handleBinaryMessage(bytes)
        }

        override fun onClosing(code: Int, reason: String) {
            Log.d(TAG, "Transport closing: $code $reason")
        }

        override fun onClosed(code: Int, reason: String) {
            Log.d(TAG, "Transport closed: $code $reason")

            // Code 1000 = Normal Closure - server intentionally ended the session
            // This is NOT an error that should trigger reconnection
            val isNormalClosure = code == 1000

            // Record telemetry for the stats screen + emit the structured [disconnect]
            // log line. Issue #128. Safe for all paths (normal, abnormal,
            // user-initiated): the stats screen shows "last disconnect" which is
            // informational regardless of whether we reconnect.
            recordDisconnectTelemetry(
                code = code,
                reasonText = reason.ifEmpty { "code=$code" },
                isNormalClosure = isNormalClosure,
            )

            val hasConnectionInfo = when (connectionMode) {
                ConnectionMode.LOCAL -> serverAddress != null
                ConnectionMode.REMOTE -> remoteId != null
                ConnectionMode.PROXY -> serverAddress != null && !authToken.isNullOrBlank()
            }

            if (!userInitiatedDisconnect.get() && !isNormalClosure && hasConnectionInfo) {
                // Abnormal closure (not code 1000) - attempt reconnection. We no
                // longer gate on handshakeComplete here; see class-level doc for
                // the unified reconnect-gate policy (#129). Logging keeps the
                // handshake state so field triage can still distinguish
                // "pre-handshake drop" from "post-handshake drop".
                Log.i(TAG, "Abnormal closure (code=$code, handshakeComplete=$handshakeComplete), attempting reconnection")
                attemptReconnect()
            } else {
                // Either user-initiated, pre-handshake, or server's normal closure
                if (isNormalClosure && !userInitiatedDisconnect.get()) {
                    Log.i(TAG, "Server closed connection normally (code 1000) - session ended")
                }
                reconnecting.set(false)
                _connectionState.value = ConnectionState.Disconnected
                callback.onDisconnected(
                    wasUserInitiated = userInitiatedDisconnect.get(),
                    wasReconnectExhausted = false
                )
            }
        }

        override fun onFailure(error: Throwable, isRecoverable: Boolean) {
            Log.e(TAG, "Transport failure", error)

            // Record telemetry for the stats screen + emit the structured [disconnect]
            // log line. onFailure has no WebSocket close code -- use `null` code and
            // the error class/message as the reason. Issue #128.
            recordDisconnectTelemetry(
                code = null,
                reasonText = error.message ?: error::class.java.simpleName,
                isNormalClosure = false,
            )

            val hasConnectionInfo = when (connectionMode) {
                ConnectionMode.LOCAL -> serverAddress != null
                ConnectionMode.REMOTE -> remoteId != null
                ConnectionMode.PROXY -> serverAddress != null && !authToken.isNullOrBlank()
            }

            val shouldReconnect = !userInitiatedDisconnect.get() &&
                    hasConnectionInfo &&
                    isRecoverable

            if (shouldReconnect) {
                Log.i(TAG, "Recoverable error, attempting reconnection: ${error.message}")
                attemptReconnect()
            } else {
                val errorMessage = getSpecificErrorMessage(error)
                reconnecting.set(false)
                _connectionState.value = ConnectionState.Error(errorMessage)
                callback.onError(errorMessage)
            }
        }
    }
}
