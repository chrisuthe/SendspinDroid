package com.sendspindroid.playback

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sendspindroid.ServerRepository
import com.sendspindroid.model.PlaybackState
import com.sendspindroid.model.PlaybackStateType
import com.sendspindroid.sendspin.SendSpinClient
import com.sendspindroid.sendspin.SyncAudioPlayer
import com.sendspindroid.sendspin.SyncAudioPlayerCallback
import com.sendspindroid.sendspin.PlaybackState as SyncPlaybackState
import androidx.media3.session.DefaultMediaNotificationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Background playback service for SendSpinDroid.
 *
 * Extends MediaLibraryService to provide:
 * - Background audio playback (screen off, app minimized)
 * - System media integration (notifications, lock screen controls)
 * - Audio focus handling (pause for phone calls, etc.)
 * - Bluetooth/headset button support
 * - Android Auto browse tree support
 *
 * ## Architecture (Native Kotlin)
 * ```
 * MainActivity ──MediaController──► PlaybackService
 *                                        │
 *                                   ┌────┴────┐
 *                                   │ SendSpinClient │
 *                                   │ AAudio (TODO)  │
 *                                   │ MediaSession   │
 *                                   └────────────────┘
 * ```
 *
 * ## TODO: Implementation phases
 * 1. WebSocket connection and protocol (SendSpinClient)
 * 2. Clock synchronization
 * 3. AAudio/Oboe playback with sync correction
 * 4. Remove ExoPlayer dependency
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var sendSpinPlayer: SendSpinPlayer? = null
    private var forwardingPlayer: MetadataForwardingPlayer? = null
    private var sendSpinClient: SendSpinClient? = null
    private var syncAudioPlayer: SyncAudioPlayer? = null

    // Handler for posting callbacks to main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    // Connection state exposed as StateFlow for observers
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Playback state exposed as StateFlow (like Python CLI's AppState)
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Artwork state
    private var lastArtworkUrl: String? = null
    private var currentArtwork: Bitmap? = null
    private lateinit var imageLoader: ImageLoader

    // Coroutine scope for background tasks (artwork loading)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Wake lock to prevent CPU sleep during playback
    private var wakeLock: PowerManager.WakeLock? = null

    // WiFi lock to prevent WiFi from going to sleep during playback
    private var wifiLock: WifiManager.WifiLock? = null

    // Handler for periodic wake lock refresh
    private val wakeLockHandler = Handler(Looper.getMainLooper())

    // Wake lock refresh runnable - refreshes wake lock periodically during active playback
    private val wakeLockRefreshRunnable = object : Runnable {
        override fun run() {
            refreshWakeLock()
            // Schedule next refresh if still playing
            if (isActivelyPlaying()) {
                wakeLockHandler.postDelayed(this, WAKE_LOCK_REFRESH_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val TAG = "PlaybackService"

        // Wake lock refresh strategy:
        // - Use a 30-minute timeout so if release fails (crash, etc.), max battery drain is 30 min
        // - Refresh every 20 minutes during active playback to keep it alive
        // - This is safer than a 10-hour timeout with no refresh
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L  // 30 minutes
        private const val WAKE_LOCK_REFRESH_INTERVAL_MS = 20 * 60 * 1000L  // 20 minutes

        // Custom session commands
        const val COMMAND_CONNECT = "com.sendspindroid.CONNECT"
        const val COMMAND_DISCONNECT = "com.sendspindroid.DISCONNECT"
        const val COMMAND_SET_VOLUME = "com.sendspindroid.SET_VOLUME"
        const val COMMAND_NEXT = "com.sendspindroid.NEXT"
        const val COMMAND_PREVIOUS = "com.sendspindroid.PREVIOUS"
        const val COMMAND_GET_STATS = "com.sendspindroid.GET_STATS"

        // Command arguments
        const val ARG_SERVER_ADDRESS = "server_address"
        const val ARG_SERVER_PATH = "server_path"
        const val ARG_VOLUME = "volume"

        // Session extras keys for metadata (service → controller)
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_ARTWORK_URL = "artwork_url"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_ARTWORK_DATA = "artwork_data"

        // Session extras keys for connection state
        const val EXTRA_CONNECTION_STATE = "connection_state"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        // Session extras keys for volume (server → controller)
        const val EXTRA_VOLUME = "volume"

        // Connection state values
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_ERROR = "error"

        // Android Auto browse tree media IDs
        private const val MEDIA_ID_ROOT = "root"
        private const val MEDIA_ID_DISCOVERED = "discovered_servers"
        private const val MEDIA_ID_RECENT = "recent"
        private const val MEDIA_ID_MANUAL = "manual_servers"
        private const val MEDIA_ID_SERVER_PREFIX = "server_"
    }

    /**
     * Connection state for the service.
     */
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val serverName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlaybackService created")

        // Create notification channel for foreground service
        NotificationHelper.createNotificationChannel(this)

        // Configure media notification provider to use our channel
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NotificationHelper.CHANNEL_ID)
                .build()
        )

        // Initialize Coil ImageLoader for artwork fetching
        imageLoader = ImageLoader.Builder(this)
            .crossfade(true)
            .build()

        // Initialize UserSettings for player name preference
        com.sendspindroid.UserSettings.initialize(this)

        // Initialize SendSpinPlayer
        initializePlayer()

        // Create MediaSession wrapping SendSpinPlayer
        initializeMediaSession()

        // Initialize native Kotlin SendSpin client
        initializeSendSpinClient()
    }

    /**
     * Initializes the native Kotlin SendSpin client.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initializeSendSpinClient() {
        try {
            // Use user-configured player name, falls back to device model
            val playerName = com.sendspindroid.UserSettings.getPlayerName()
            sendSpinClient = SendSpinClient(
                deviceName = playerName,
                callback = SendSpinClientCallback()
            )
            sendSpinPlayer?.setSendSpinClient(sendSpinClient)
            Log.d(TAG, "SendSpinClient initialized with name: $playerName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SendSpinClient", e)
            _connectionState.value = ConnectionState.Error("Failed to initialize: ${e.message}")
        }
    }

    /**
     * Callback for SyncAudioPlayer state changes.
     * Updates SendSpinPlayer when playback state changes so MediaSession notifies controllers.
     */
    private inner class SyncAudioPlayerStateCallback : SyncAudioPlayerCallback {
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onPlaybackStateChanged(state: SyncPlaybackState) {
            mainHandler.post {
                Log.d(TAG, "SyncAudioPlayer state changed: $state")
                // Update SendSpinPlayer so it notifies MediaSession listeners
                sendSpinPlayer?.updateStateFromPlayer()
            }
        }
    }

    /**
     * Callback for SendSpinClient events.
     */
    private inner class SendSpinClientCallback : SendSpinClient.Callback {

        override fun onServerDiscovered(name: String, address: String) {
            Log.d(TAG, "Server discovered (ignored in service): $name at $address")
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onConnected(serverName: String) {
            mainHandler.post {
                Log.d(TAG, "Connected to: $serverName")
                _connectionState.value = ConnectionState.Connected(serverName)
                sendSpinPlayer?.updateConnectionState(true, serverName)

                // Acquire wake lock immediately on connect to prevent being killed
                // before stream starts
                acquireWakeLock()

                // Broadcast connection state to controllers (MainActivity)
                broadcastConnectionState(STATE_CONNECTED, serverName)

                // Note: Don't auto-start playback - let user control or server push state
            }
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onDisconnected() {
            mainHandler.post {
                Log.d(TAG, "Disconnected from server")

                // Stop audio playback and release wake lock
                syncAudioPlayer?.stop()
                syncAudioPlayer?.release()
                syncAudioPlayer = null
                sendSpinPlayer?.setSyncAudioPlayer(null)
                sendSpinPlayer?.updateConnectionState(false, null)
                releaseWakeLock()

                _connectionState.value = ConnectionState.Disconnected

                // Broadcast disconnection to controllers (MainActivity)
                broadcastConnectionState(STATE_DISCONNECTED)

                // Clear playback state on disconnect
                _playbackState.value = PlaybackState()
                lastArtworkUrl = null
                currentArtwork = null

                // Clear lock screen metadata
                forwardingPlayer?.clearMetadata()
            }
        }

        override fun onStateChanged(state: String) {
            mainHandler.post {
                Log.d(TAG, "State changed: $state")
                val newState = PlaybackStateType.fromString(state)

                // Stop audio immediately when server stops/pauses playback
                if (newState == PlaybackStateType.STOPPED || newState == PlaybackStateType.PAUSED) {
                    Log.d(TAG, "State is stopped/paused - clearing audio buffer")
                    syncAudioPlayer?.clearBuffer()
                    syncAudioPlayer?.pause()
                }

                _playbackState.value = _playbackState.value.copy(playbackState = newState)
            }
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onGroupUpdate(groupId: String, groupName: String, playbackState: String) {
            mainHandler.post {
                Log.d(TAG, "Group update: id=$groupId name=$groupName state=$playbackState")

                val currentState = _playbackState.value
                val isGroupChange = groupId.isNotEmpty() && groupId != currentState.groupId
                val newPlaybackState = PlaybackStateType.fromString(playbackState)

                // Handle playback state changes for audio player and notifications
                if (playbackState.isNotEmpty()) {
                    when (newPlaybackState) {
                        PlaybackStateType.STOPPED, PlaybackStateType.PAUSED -> {
                            Log.d(TAG, "Playback stopped/paused - clearing audio buffer")
                            syncAudioPlayer?.clearBuffer()
                            syncAudioPlayer?.pause()
                        }
                        PlaybackStateType.PLAYING -> {
                            Log.d(TAG, "Playback playing - updating player state")
                            // Player state will update from SyncAudioPlayer via setSyncAudioPlayer
                            sendSpinPlayer?.setSyncAudioPlayer(syncAudioPlayer)
                        }
                        else -> { /* No action needed */ }
                    }
                }

                val newState = if (isGroupChange) {
                    currentState.withClearedMetadata().copy(
                        groupId = groupId,
                        groupName = groupName.ifEmpty { null },
                        playbackState = newPlaybackState
                    )
                } else {
                    currentState.copy(
                        groupId = groupId.ifEmpty { currentState.groupId },
                        groupName = groupName.ifEmpty { currentState.groupName },
                        playbackState = if (playbackState.isNotEmpty())
                            newPlaybackState
                        else currentState.playbackState
                    )
                }
                _playbackState.value = newState
            }
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onMetadataUpdate(
            title: String,
            artist: String,
            album: String,
            artworkUrl: String,
            durationMs: Long,
            positionMs: Long
        ) {
            mainHandler.post {
                Log.d(TAG, "Metadata update: $title / $artist / $album")

                _playbackState.value = _playbackState.value.withMetadata(
                    title = title.ifEmpty { null },
                    artist = artist.ifEmpty { null },
                    album = album.ifEmpty { null },
                    artworkUrl = artworkUrl.ifEmpty { null },
                    durationMs = durationMs,
                    positionMs = positionMs
                )

                // Update the player's media item for lock screen/notification
                sendSpinPlayer?.updateMediaItem(
                    title = title.ifEmpty { null },
                    artist = artist.ifEmpty { null },
                    album = album.ifEmpty { null },
                    durationMs = durationMs
                )

                updateMediaMetadata(title, artist, album)

                if (artworkUrl.isNotEmpty() && artworkUrl != lastArtworkUrl) {
                    lastArtworkUrl = artworkUrl
                    fetchArtwork(artworkUrl)
                }
            }
        }

        override fun onArtwork(imageData: ByteArray) {
            mainHandler.post {
                Log.d(TAG, "Artwork received: ${imageData.size} bytes")
                try {
                    val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                    if (bitmap != null) {
                        currentArtwork = bitmap
                        updateMediaSessionArtwork(bitmap)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode artwork", e)
                }
            }
        }

        override fun onError(message: String) {
            mainHandler.post {
                Log.e(TAG, "SendSpinClient error: $message")
                _connectionState.value = ConnectionState.Error(message)

                // Broadcast error to controllers (MainActivity)
                broadcastConnectionState(STATE_ERROR, errorMessage = message)
            }
        }

        override fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int) {
            mainHandler.post {
                Log.d(TAG, "Stream started: codec=$codec, rate=$sampleRate, channels=$channels, bits=$bitDepth")

                // Stop existing player if any
                syncAudioPlayer?.release()

                // Get the time filter from SendSpinClient
                val timeFilter = sendSpinClient?.getTimeFilter()
                if (timeFilter == null) {
                    Log.e(TAG, "Cannot start audio: time filter not available")
                    return@post
                }

                // Acquire wake lock to prevent CPU sleep during playback
                acquireWakeLock()

                // Create and start the audio player
                syncAudioPlayer = SyncAudioPlayer(
                    timeFilter = timeFilter,
                    sampleRate = sampleRate,
                    channels = channels,
                    bitDepth = bitDepth
                ).apply {
                    // Set callback to update SendSpinPlayer when playback state changes
                    setStateCallback(SyncAudioPlayerStateCallback())
                    initialize()
                    start()
                }
                sendSpinPlayer?.setSyncAudioPlayer(syncAudioPlayer)

                Log.i(TAG, "SyncAudioPlayer started: ${sampleRate}Hz, ${channels}ch, ${bitDepth}bit")
            }
        }

        override fun onStreamClear() {
            mainHandler.post {
                Log.d(TAG, "Stream clear - flushing audio buffers")
                syncAudioPlayer?.clearBuffer()
            }
        }

        override fun onAudioChunk(serverTimeMicros: Long, pcmData: ByteArray) {
            // Queue chunk directly - SyncAudioPlayer handles threading internally
            syncAudioPlayer?.queueChunk(serverTimeMicros, pcmData)
        }

        override fun onVolumeChanged(volume: Int) {
            mainHandler.post {
                // Convert from 0-100 to 0.0-1.0 and apply to local audio player
                val volumeFloat = volume / 100f
                syncAudioPlayer?.setVolume(volumeFloat)
                // Broadcast volume to UI controllers
                val extras = Bundle().apply {
                    putInt(EXTRA_VOLUME, volume)
                }
                mediaSession?.setSessionExtras(extras)
            }
        }

        override fun onSyncOffsetApplied(offsetMs: Double, source: String) {
            android.util.Log.i(TAG, "Sync offset applied: ${offsetMs}ms from $source")
            // Optionally broadcast to UI for display
            mainHandler.post {
                val extras = Bundle().apply {
                    putDouble("sync_offset_ms", offsetMs)
                    putString("sync_offset_source", source)
                }
                mediaSession?.setSessionExtras(extras)
            }
        }
    }

    /**
     * Fetches artwork from a URL using Coil.
     */
    private fun fetchArtwork(url: String) {
        if (!isValidArtworkUrl(url)) {
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(this@PlaybackService)
                    .data(url)
                    .build()

                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = result.drawable.toBitmap()
                    mainHandler.post {
                        currentArtwork = bitmap
                        updateMediaSessionArtwork(bitmap)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch artwork", e)
            }
        }
    }

    private fun isValidArtworkUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun updateMediaSessionArtwork(bitmap: Bitmap) {
        val state = _playbackState.value

        forwardingPlayer?.updateMetadata(
            title = state.title,
            artist = state.artist,
            album = state.album,
            artwork = bitmap,
            artworkUri = state.artworkUrl?.let { android.net.Uri.parse(it) }
        )

        broadcastMetadataToControllers(
            title = state.title ?: "",
            artist = state.artist ?: "",
            album = state.album ?: "",
            artworkUrl = state.artworkUrl,
            durationMs = state.durationMs,
            positionMs = state.positionMs
        )
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun updateMediaMetadata(title: String, artist: String, album: String) {
        val state = _playbackState.value

        forwardingPlayer?.updateMetadata(
            title = state.title,
            artist = state.artist,
            album = state.album,
            artwork = currentArtwork,
            artworkUri = state.artworkUrl?.let { android.net.Uri.parse(it) }
        )

        broadcastMetadataToControllers(
            title = title,
            artist = artist,
            album = album,
            artworkUrl = state.artworkUrl,
            durationMs = state.durationMs,
            positionMs = state.positionMs
        )
    }

    private fun broadcastMetadataToControllers(
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?,
        durationMs: Long,
        positionMs: Long
    ) {
        val extras = Bundle().apply {
            putString(EXTRA_TITLE, title)
            putString(EXTRA_ARTIST, artist)
            putString(EXTRA_ALBUM, album)
            putString(EXTRA_ARTWORK_URL, artworkUrl ?: "")
            putLong(EXTRA_DURATION_MS, durationMs)
            putLong(EXTRA_POSITION_MS, positionMs)
        }
        mediaSession?.setSessionExtras(extras)
    }

    /**
     * Connects to a SendSpin server.
     *
     * @param address Server address in "host:port" format
     * @param path WebSocket path (default: /sendspin)
     */
    fun connectToServer(address: String, path: String = "/sendspin") {
        Log.d(TAG, "Connecting to server: $address path=$path")
        _connectionState.value = ConnectionState.Connecting

        // Broadcast connecting state to controllers (MainActivity)
        broadcastConnectionState(STATE_CONNECTING)

        try {
            if (sendSpinClient?.isConnected == true) {
                Log.d(TAG, "Already connected, disconnecting first...")
                sendSpinClient?.disconnect()
            }

            sendSpinClient?.connect(address, path)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to server", e)
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
            broadcastConnectionState(STATE_ERROR, errorMessage = "Connection failed: ${e.message}")
        }
    }

    /**
     * Broadcasts connection state to all connected MediaControllers via session extras.
     *
     * This allows MainActivity (and Android Auto) to react to connection state changes
     * without needing to observe PlaybackService's StateFlow directly across process boundaries.
     *
     * @param state One of STATE_DISCONNECTED, STATE_CONNECTING, STATE_CONNECTED, STATE_ERROR
     * @param serverName Server name (only relevant for STATE_CONNECTED)
     * @param errorMessage Error message (only relevant for STATE_ERROR)
     */
    private fun broadcastConnectionState(
        state: String,
        serverName: String? = null,
        errorMessage: String? = null
    ) {
        val extras = Bundle().apply {
            putString(EXTRA_CONNECTION_STATE, state)
            serverName?.let { putString(EXTRA_SERVER_NAME, it) }
            errorMessage?.let { putString(EXTRA_ERROR_MESSAGE, it) }
        }
        mediaSession?.setSessionExtras(extras)
    }

    /**
     * Disconnects from the current server.
     */
    fun disconnectFromServer() {
        Log.d(TAG, "Disconnecting from server")
        sendSpinClient?.disconnect()
    }

    /**
     * Sets the playback volume.
     * Volume is controlled locally on the audio player, not sent to the server.
     */
    fun setVolume(volume: Float) {
        Log.d(TAG, "Setting volume: $volume")
        syncAudioPlayer?.setVolume(volume)
    }

    /**
     * Acquires wake lock and WiFi lock to keep CPU and WiFi running during audio playback.
     * Also starts the foreground service to prevent Android from killing us.
     * This prevents the system from killing our audio or dropping the connection when the screen turns off.
     *
     * Wake lock strategy for battery safety:
     * - Uses a 30-minute timeout instead of indefinite or very long timeout
     * - Refreshes the wake lock every 20 minutes during active playback
     * - If the app crashes without releasing, max battery drain is limited to 30 minutes
     * - The refresh mechanism ensures continuous playback isn't interrupted
     */
    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        // Start as foreground service with notification
        startForegroundServiceWithNotification()

        // CPU wake lock with 30-minute timeout for battery safety
        // Refreshed periodically during active playback
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SendSpinDroid::AudioPlayback"
            )
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "Wake lock acquired with ${WAKE_LOCK_TIMEOUT_MS / 60000}min timeout")

            // Start periodic refresh to keep wake lock alive during long playback sessions
            wakeLockHandler.removeCallbacks(wakeLockRefreshRunnable)
            wakeLockHandler.postDelayed(wakeLockRefreshRunnable, WAKE_LOCK_REFRESH_INTERVAL_MS)
        }

        // WiFi lock - keeps WiFi active even when screen is off
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "SendSpinDroid::AudioStreaming"
            )
        }
        if (wifiLock?.isHeld == false) {
            wifiLock?.acquire()
            Log.d(TAG, "WiFi lock acquired")
        }
    }

    /**
     * Refreshes the wake lock by releasing and re-acquiring with a fresh timeout.
     * Called periodically during active playback to prevent the wake lock from expiring.
     */
    private fun refreshWakeLock() {
        if (wakeLock?.isHeld == true) {
            // Release and re-acquire with fresh timeout
            wakeLock?.release()
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "Wake lock refreshed with ${WAKE_LOCK_TIMEOUT_MS / 60000}min timeout")
        } else {
            Log.d(TAG, "Wake lock refresh skipped - not held")
        }
    }

    /**
     * Checks if the audio player is actively playing or waiting to start.
     * Used to determine whether to continue refreshing the wake lock.
     */
    private fun isActivelyPlaying(): Boolean {
        val state = syncAudioPlayer?.getPlaybackState()
        return state == com.sendspindroid.sendspin.PlaybackState.PLAYING ||
               state == com.sendspindroid.sendspin.PlaybackState.WAITING_FOR_START
    }

    /**
     * Starts the service in foreground mode with a notification.
     * This is required on Android 8+ to keep the service alive when the app is in the background.
     * On Android 14+, we must specify the foreground service type.
     */
    private fun startForegroundServiceWithNotification() {
        try {
            val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
                .setContentTitle("SendSpin")
                .setContentText("Streaming audio...")
                .setSmallIcon(com.sendspindroid.R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()

            // Use ServiceCompat for backward compatibility
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    /**
     * Releases wake lock and WiFi lock when playback stops.
     * Also stops the foreground service and cancels the wake lock refresh handler.
     */
    private fun releaseWakeLock() {
        // Stop the periodic wake lock refresh first
        wakeLockHandler.removeCallbacks(wakeLockRefreshRunnable)

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "Wake lock released")
        }
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
            Log.d(TAG, "WiFi lock released")
        }

        // Stop foreground service but keep the service running
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            Log.d(TAG, "Foreground service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground service", e)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initializeMediaSession() {
        val player = sendSpinPlayer ?: run {
            Log.e(TAG, "Cannot create MediaSession: sendSpinPlayer is null")
            return
        }

        forwardingPlayer = MetadataForwardingPlayer(player)

        mediaSession = MediaLibrarySession.Builder(this, forwardingPlayer!!, LibraryCallback())
            .build()

        Log.d(TAG, "MediaLibrarySession initialized with browse tree support")
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d(TAG, "onGetLibraryRoot called by: ${browser.packageName}")

            val rootItem = MediaItem.Builder()
                .setMediaId(MEDIA_ID_ROOT)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("SendSpinDroid")
                        .setIsPlayable(false)
                        .setIsBrowsable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.d(TAG, "onGetChildren: parentId=$parentId, page=$page")

            val children: List<MediaItem> = when (parentId) {
                MEDIA_ID_ROOT -> getRootChildren()
                MEDIA_ID_DISCOVERED -> getDiscoveredServers()
                MEDIA_ID_RECENT -> getRecentServers()
                MEDIA_ID_MANUAL -> getManualServers()
                else -> emptyList()
            }

            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d(TAG, "onGetItem: mediaId=$mediaId")

            val item = findItemById(mediaId)
            return if (item != null) {
                Futures.immediateFuture(LibraryResult.ofItem(item, null))
            } else {
                Futures.immediateFuture(
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                )
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            Log.d(TAG, "onAddMediaItems: ${mediaItems.size} items")

            val updatedItems = mediaItems.map { item ->
                val mediaId = item.mediaId

                if (mediaId.startsWith(MEDIA_ID_SERVER_PREFIX)) {
                    val serverAddress = mediaId.removePrefix(MEDIA_ID_SERVER_PREFIX)
                    Log.d(TAG, "User selected server: $serverAddress")

                    connectToServer(serverAddress)

                    val server = ServerRepository.getServer(serverAddress)
                    if (server != null) {
                        ServerRepository.addToRecent(server)
                    }

                    item.buildUpon()
                        .setUri("sendspin://$serverAddress")
                        .build()
                } else {
                    item
                }
            }

            return Futures.immediateFuture(updatedItems)
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.d(TAG, "Controller connecting: ${controller.packageName}")

            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_CONNECT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_DISCONNECT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_VOLUME, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_NEXT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_PREVIOUS, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_GET_STATS, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            Log.d(TAG, "Controller disconnected: ${controller.packageName}")
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            Log.d(TAG, "Custom command: ${customCommand.customAction}")

            return when (customCommand.customAction) {
                COMMAND_CONNECT -> {
                    val address = args.getString(ARG_SERVER_ADDRESS)
                    val path = args.getString(ARG_SERVER_PATH) ?: "/sendspin"
                    if (address != null) {
                        connectToServer(address, path)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Log.e(TAG, "CONNECT command missing server_address")
                        Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                }

                COMMAND_DISCONNECT -> {
                    disconnectFromServer()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_SET_VOLUME -> {
                    val volume = args.getFloat(ARG_VOLUME, -1f)
                    if (volume in 0f..1f) {
                        setVolume(volume)
                        // Also send volume command to server
                        sendSpinClient?.setVolume(volume.toDouble())
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Log.e(TAG, "SET_VOLUME command has invalid volume: $volume")
                        Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                }

                COMMAND_NEXT -> {
                    Log.d(TAG, "Next track command received")
                    sendSpinClient?.next()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_PREVIOUS -> {
                    Log.d(TAG, "Previous track command received")
                    sendSpinClient?.previous()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_GET_STATS -> {
                    val statsBundle = getStats()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, statsBundle))
                }

                else -> {
                    Log.w(TAG, "Unknown custom command: ${customCommand.customAction}")
                    super.onCustomCommand(session, controller, customCommand, args)
                }
            }
        }
    }

    /**
     * Collects current stats from SyncAudioPlayer and SendSpinClient.
     * Returns a Bundle containing all stats for Stats for Nerds display.
     */
    private fun getStats(): Bundle {
        val bundle = Bundle()

        // Get stats from SyncAudioPlayer
        val audioStats = syncAudioPlayer?.getStats()
        if (audioStats != null) {
            // Playback state
            bundle.putString("playback_state", audioStats.playbackState.name)
            bundle.putBoolean("is_playing", audioStats.isPlaying)

            // Sync status
            bundle.putLong("sync_error_us", audioStats.smoothedSyncErrorUs)
            bundle.putLong("true_sync_error_us", audioStats.trueSyncErrorUs)

            // Buffer
            bundle.putLong("queued_samples", audioStats.queuedSamples)
            bundle.putLong("chunks_received", audioStats.chunksReceived)
            bundle.putLong("chunks_played", audioStats.chunksPlayed)
            bundle.putLong("chunks_dropped", audioStats.chunksDropped)
            bundle.putLong("gaps_filled", audioStats.gapsFilled)
            bundle.putLong("gap_silence_ms", audioStats.gapSilenceMs)
            bundle.putLong("overlaps_trimmed", audioStats.overlapsTrimmed)
            bundle.putLong("overlap_trimmed_ms", audioStats.overlapTrimmedMs)

            // Sync correction
            bundle.putInt("insert_every_n_frames", audioStats.insertEveryNFrames)
            bundle.putInt("drop_every_n_frames", audioStats.dropEveryNFrames)
            bundle.putLong("frames_inserted", audioStats.framesInserted)
            bundle.putLong("frames_dropped", audioStats.framesDropped)
            bundle.putLong("sync_corrections", audioStats.syncCorrections)
            bundle.putLong("correction_error_us", audioStats.correctionErrorUs)

            // DAC calibration
            bundle.putInt("dac_calibration_count", audioStats.dacCalibrationCount)
            bundle.putLong("total_frames_written", audioStats.totalFramesWritten)
            bundle.putLong("last_known_playback_position_us", audioStats.lastKnownPlaybackPositionUs)
            bundle.putLong("server_timeline_cursor_us", audioStats.serverTimelineCursorUs)

            // Timing
            audioStats.scheduledStartLoopTimeUs?.let {
                bundle.putLong("scheduled_start_loop_time_us", it)
            }
            audioStats.firstServerTimestampUs?.let {
                bundle.putLong("first_server_timestamp_us", it)
            }
        } else {
            // No audio player - provide default values
            bundle.putString("playback_state", "NO_AUDIO")
            bundle.putBoolean("is_playing", false)
        }

        // Get stats from SendSpinClient (clock sync)
        sendSpinClient?.let { client ->
            val timeFilter = client.getTimeFilter()
            bundle.putBoolean("clock_ready", timeFilter.isReady)
            bundle.putLong("clock_offset_us", timeFilter.offsetMicros)
            bundle.putLong("clock_error_us", timeFilter.errorMicros)
            bundle.putInt("measurement_count", timeFilter.measurementCountValue)
        }

        return bundle
    }

    private fun getRootChildren(): List<MediaItem> {
        return listOf(
            createBrowsableItem(
                mediaId = MEDIA_ID_DISCOVERED,
                title = "Discovered Servers",
                subtitle = "Auto-discovered on your network"
            ),
            createBrowsableItem(
                mediaId = MEDIA_ID_RECENT,
                title = "Recent",
                subtitle = "Recently connected servers"
            ),
            createBrowsableItem(
                mediaId = MEDIA_ID_MANUAL,
                title = "Manual Servers",
                subtitle = "Manually added servers"
            )
        )
    }

    private fun getDiscoveredServers(): List<MediaItem> {
        return ServerRepository.discoveredServers.value.map { server ->
            createPlayableServerItem(server.name, server.address)
        }
    }

    private fun getRecentServers(): List<MediaItem> {
        return ServerRepository.recentServers.value.map { recent ->
            MediaItem.Builder()
                .setMediaId("$MEDIA_ID_SERVER_PREFIX${recent.address}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(recent.name)
                        .setSubtitle(recent.formattedTime)
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
                )
                .build()
        }
    }

    private fun getManualServers(): List<MediaItem> {
        return ServerRepository.manualServers.value.map { server ->
            createPlayableServerItem(server.name, server.address)
        }
    }

    private fun createBrowsableItem(
        mediaId: String,
        title: String,
        subtitle: String? = null
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()
    }

    private fun createPlayableServerItem(name: String, address: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId("$MEDIA_ID_SERVER_PREFIX$address")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setSubtitle(address)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    private fun findItemById(mediaId: String): MediaItem? {
        return when {
            mediaId == MEDIA_ID_ROOT -> {
                MediaItem.Builder()
                    .setMediaId(MEDIA_ID_ROOT)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("SendSpinDroid")
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .build()
                    )
                    .build()
            }
            mediaId == MEDIA_ID_DISCOVERED ||
            mediaId == MEDIA_ID_RECENT ||
            mediaId == MEDIA_ID_MANUAL -> {
                getRootChildren().find { it.mediaId == mediaId }
            }
            mediaId.startsWith(MEDIA_ID_SERVER_PREFIX) -> {
                val address = mediaId.removePrefix(MEDIA_ID_SERVER_PREFIX)
                val server = ServerRepository.getServer(address)
                server?.let { createPlayableServerItem(it.name, it.address) }
            }
            else -> null
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initializePlayer() {
        sendSpinPlayer = SendSpinPlayer()
        Log.d(TAG, "SendSpinPlayer initialized")
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession? {
        Log.d(TAG, "onGetSession called by: ${controllerInfo.packageName}")
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved")

        // Check if SyncAudioPlayer is actively playing (not ExoPlayer)
        val audioPlayerState = syncAudioPlayer?.getPlaybackState()
        val isPlaying = audioPlayerState == com.sendspindroid.sendspin.PlaybackState.PLAYING ||
                        audioPlayerState == com.sendspindroid.sendspin.PlaybackState.WAITING_FOR_START

        if (!isPlaying) {
            Log.d(TAG, "Not playing (state=$audioPlayerState), stopping service")
            stopSelf()
        } else {
            Log.d(TAG, "Currently playing (state=$audioPlayerState), continuing in background")
        }

        super.onTaskRemoved(rootIntent)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onDestroy() {
        Log.d(TAG, "PlaybackService destroyed")

        serviceScope.cancel()
        imageLoader.shutdown()

        // Release audio player and wake lock
        syncAudioPlayer?.release()
        syncAudioPlayer = null
        releaseWakeLock()

        mediaSession?.run {
            release()
        }
        mediaSession = null

        forwardingPlayer = null

        sendSpinPlayer?.release()
        sendSpinPlayer = null

        sendSpinClient?.destroy()
        sendSpinClient = null

        super.onDestroy()
    }
}
