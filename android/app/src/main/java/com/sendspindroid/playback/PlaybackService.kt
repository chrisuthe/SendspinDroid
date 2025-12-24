package com.sendspindroid.playback

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Background playback service for SendSpinDroid.
 *
 * Extends MediaSessionService to provide:
 * - Background audio playback (screen off, app minimized)
 * - System media integration (notifications, lock screen controls)
 * - Audio focus handling (pause for phone calls, etc.)
 * - Bluetooth/headset button support
 *
 * ## Architecture
 * ```
 * MainActivity ──MediaController──► PlaybackService
 *                                        │
 *                                   ┌────┴────┐
 *                                   │ ExoPlayer │
 *                                   │ GoPlayer  │
 *                                   │ MediaSession │
 *                                   └─────────────┘
 * ```
 *
 * ## Lifecycle
 * - Created when first controller connects or startService() called
 * - Runs as foreground service while playing
 * - Destroyed when all controllers disconnect and not playing
 *
 * @see GoPlayerDataSource for audio data bridge
 * @see GoPlayerMediaSourceFactory for ExoPlayer integration
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var goPlayer: player.Player_? = null

    // Handler for posting callbacks to main thread (Go callbacks come from Go runtime threads)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Connection state exposed as StateFlow for observers
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    companion object {
        private const val TAG = "PlaybackService"

        // Custom session commands
        const val COMMAND_CONNECT = "com.sendspindroid.CONNECT"
        const val COMMAND_DISCONNECT = "com.sendspindroid.DISCONNECT"
        const val COMMAND_SET_VOLUME = "com.sendspindroid.SET_VOLUME"

        // Command arguments
        const val ARG_SERVER_ADDRESS = "server_address"
        const val ARG_VOLUME = "volume"
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

    /**
     * Called when service is created.
     *
     * Initializes:
     * 1. Notification channel (required for foreground service)
     * 2. ExoPlayer (Task 3.2)
     * 3. MediaSession (Task 3.3)
     * 4. Go player (Task 3.5)
     */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlaybackService created")

        // Create notification channel for foreground service
        // Must be done before any foreground notification is shown
        NotificationHelper.createNotificationChannel(this)

        // Initialize ExoPlayer with proper audio configuration
        initializePlayer()

        // Create MediaSession wrapping ExoPlayer
        initializeMediaSession()

        // Initialize Go player for audio streaming
        initializeGoPlayer()
    }

    /**
     * Initializes the Go player with callbacks.
     *
     * The Go player handles:
     * - WebSocket connection to SendSpin server
     * - Audio data reception and decoding
     * - Metadata updates
     *
     * Callbacks are posted to main thread via mainHandler since
     * they're called from Go runtime threads.
     */
    private fun initializeGoPlayer() {
        try {
            goPlayer = player.Player.newPlayer(
                android.os.Build.MODEL,
                GoPlayerCallback()
            )
            Log.d(TAG, "Go player initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Go player", e)
            _connectionState.value = ConnectionState.Error("Failed to initialize: ${e.message}")
        }
    }

    /**
     * Callback for Go player events.
     *
     * All callbacks are posted to main thread for thread safety.
     * Do NOT call Go player methods from within callbacks.
     */
    private inner class GoPlayerCallback : player.PlayerCallback {

        override fun onServerDiscovered(name: String, address: String) {
            // Server discovery is handled by MainActivity, not the service
            Log.d(TAG, "Server discovered (ignored in service): $name at $address")
        }

        override fun onConnected(serverName: String) {
            mainHandler.post {
                Log.d(TAG, "Connected to: $serverName")
                _connectionState.value = ConnectionState.Connected(serverName)

                // Start ExoPlayer playback with Go player audio
                startExoPlayerPlayback()
            }
        }

        override fun onDisconnected() {
            mainHandler.post {
                Log.d(TAG, "Disconnected from server")
                _connectionState.value = ConnectionState.Disconnected

                // Stop ExoPlayer playback
                stopExoPlayerPlayback()
            }
        }

        override fun onStateChanged(state: String) {
            mainHandler.post {
                Log.d(TAG, "State changed: $state")
                // Could update MediaSession playback state here if needed
            }
        }

        override fun onMetadata(title: String, artist: String, album: String) {
            mainHandler.post {
                Log.d(TAG, "Metadata: $title / $artist / $album")
                updateMediaMetadata(title, artist, album)
            }
        }

        override fun onError(message: String) {
            mainHandler.post {
                Log.e(TAG, "Go player error: $message")
                _connectionState.value = ConnectionState.Error(message)
            }
        }
    }

    /**
     * Starts ExoPlayer playback using GoPlayerMediaSource.
     *
     * Called when Go player connects to a server.
     * This is where we test if ExoPlayer works with our custom raw PCM source.
     */
    private fun startExoPlayerPlayback() {
        val currentPlayer = exoPlayer ?: run {
            Log.e(TAG, "Cannot start playback: exoPlayer is null")
            return
        }

        val currentGoPlayer = goPlayer ?: run {
            Log.e(TAG, "Cannot start playback: goPlayer is null")
            return
        }

        try {
            Log.d(TAG, "Starting ExoPlayer with GoPlayerMediaSource...")

            // Create MediaSource from our Go player bridge
            val mediaSource = GoPlayerMediaSourceFactory.create(currentGoPlayer)

            // Set the media source and prepare ExoPlayer
            currentPlayer.setMediaSource(mediaSource)
            currentPlayer.prepare()
            currentPlayer.play()

            Log.d(TAG, "ExoPlayer playback started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ExoPlayer playback", e)
            _connectionState.value = ConnectionState.Error("Playback failed: ${e.message}")
        }
    }

    /**
     * Stops ExoPlayer playback.
     *
     * Called when Go player disconnects from server.
     */
    private fun stopExoPlayerPlayback() {
        exoPlayer?.apply {
            stop()
            clearMediaItems()
        }
        Log.d(TAG, "ExoPlayer playback stopped")
    }

    /**
     * Updates media metadata for notifications and lock screen.
     */
    private fun updateMediaMetadata(title: String, artist: String, album: String) {
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title.ifEmpty { "SendSpinDroid" })
            .setArtist(artist.ifEmpty { null })
            .setAlbumTitle(album.ifEmpty { null })
            .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()

        // Create/update media item with metadata
        val mediaItem = androidx.media3.common.MediaItem.Builder()
            .setMediaMetadata(metadata)
            .build()

        // Update the exoPlayer's current media item
        exoPlayer?.let {
            if (it.mediaItemCount > 0) {
                it.replaceMediaItem(0, mediaItem)
            }
        }

        Log.d(TAG, "Updated metadata: $title / $artist / $album")
    }

    /**
     * Connects to a SendSpin server.
     *
     * @param address Server address in host:port format
     */
    fun connectToServer(address: String) {
        Log.d(TAG, "Connecting to server: $address")
        _connectionState.value = ConnectionState.Connecting
        goPlayer?.connect(address)
    }

    /**
     * Disconnects from the current server.
     */
    fun disconnectFromServer() {
        Log.d(TAG, "Disconnecting from server")
        goPlayer?.disconnect()
    }

    /**
     * Sets the playback volume.
     *
     * @param volume Volume level from 0.0 to 1.0
     */
    fun setVolume(volume: Float) {
        Log.d(TAG, "Setting volume: $volume")
        goPlayer?.setVolume(volume.toDouble())
        exoPlayer?.volume = volume
    }

    /**
     * Initializes MediaSession wrapping ExoPlayer.
     *
     * MediaSession provides:
     * - System media integration (notifications, lock screen)
     * - MediaController API for remote control
     * - Media button handling (Bluetooth, wired headset)
     *
     * MediaSessionService automatically handles foreground notification
     * based on the player's state.
     */
    private fun initializeMediaSession() {
        // Ensure exoPlayer is initialized
        val player = exoPlayer ?: run {
            Log.e(TAG, "Cannot create MediaSession: exoPlayer is null")
            return
        }

        // Create MediaSession with callback for handling events
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(MediaSessionCallback())
            .build()

        Log.d(TAG, "MediaSession initialized")
    }

    /**
     * Callback for MediaSession events.
     *
     * Handles:
     * - Controller connections/disconnections
     * - Custom commands (connect, disconnect, set volume)
     * - Playback actions from notifications/lock screen
     */
    private inner class MediaSessionCallback : MediaSession.Callback {

        /**
         * Called when a MediaController wants to connect.
         *
         * We accept all connections from our own package.
         * Could add package verification for additional security.
         *
         * Exposes custom commands for app-specific operations:
         * - COMMAND_CONNECT: Connect to a SendSpin server
         * - COMMAND_DISCONNECT: Disconnect from current server
         * - COMMAND_SET_VOLUME: Set playback volume
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.d(TAG, "Controller connecting: ${controller.packageName}")

            // Build set of available custom commands
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_CONNECT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_DISCONNECT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_VOLUME, Bundle.EMPTY))
                .build()

            // Accept connection with default player commands + our custom commands
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        /**
         * Called when a controller disconnects.
         */
        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            Log.d(TAG, "Controller disconnected: ${controller.packageName}")
        }

        /**
         * Handles custom commands from MediaController.
         *
         * Custom commands allow MainActivity to control the service:
         * - COMMAND_CONNECT: Connect to a SendSpin server
         * - COMMAND_DISCONNECT: Disconnect from current server
         * - COMMAND_SET_VOLUME: Set playback volume
         *
         * @param session The MediaSession receiving the command
         * @param controller Info about the sending controller
         * @param customCommand The command name and extras
         * @param args Additional arguments (command-specific)
         * @return Future with SessionResult indicating success/failure
         */
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
                    if (address != null) {
                        connectToServer(address)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Log.e(TAG, "CONNECT command missing server_address")
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
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
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Log.e(TAG, "SET_VOLUME command has invalid volume: $volume")
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                    }
                }

                else -> {
                    Log.w(TAG, "Unknown custom command: ${customCommand.customAction}")
                    super.onCustomCommand(session, controller, customCommand, args)
                }
            }
        }
    }

    /**
     * Initializes ExoPlayer with audio playback configuration.
     *
     * Configuration:
     * - Audio attributes set for music playback
     * - Audio focus handling enabled (pauses for phone calls, etc.)
     * - "Becoming noisy" handling (pauses when headphones unplugged)
     */
    private fun initializePlayer() {
        // Configure audio attributes for music streaming
        // This tells Android how to handle audio focus and routing
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Create ExoPlayer with:
        // - Audio attributes for proper focus handling
        // - handleAudioFocus = true: Automatically pause for phone calls, etc.
        // - handleAudioBecomingNoisy = true: Pause when headphones unplugged
        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        Log.d(TAG, "ExoPlayer initialized")
    }

    /**
     * Returns the MediaSession for connecting controllers.
     *
     * Called by the framework when a MediaController wants to connect.
     * Returns null if session not yet created (shouldn't happen in normal flow).
     *
     * @param controllerInfo Information about the connecting controller
     * @return The MediaSession, or null if not available
     */
    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? {
        Log.d(TAG, "onGetSession called by: ${controllerInfo.packageName}")
        return mediaSession
    }

    /**
     * Called when the service receives a start command.
     *
     * MediaSessionService handles most intents automatically.
     * We use START_NOT_STICKY so the service doesn't restart automatically
     * if killed by the system - user must explicitly start playback again.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        // Let parent handle MediaSession lifecycle and media button intents
        super.onStartCommand(intent, flags, startId)

        // Don't restart automatically if killed
        return START_NOT_STICKY
    }

    /**
     * Called when app task is removed (user swipes away from recents).
     *
     * If not currently playing, stop the service to free resources.
     * If playing, continue in background (this is the whole point!).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved")

        // Check if exoPlayer is currently playing
        val isPlaying = exoPlayer?.isPlaying == true

        if (!isPlaying) {
            Log.d(TAG, "Not playing, stopping service")
            stopSelf()
        } else {
            Log.d(TAG, "Currently playing, continuing in background")
        }

        super.onTaskRemoved(rootIntent)
    }

    /**
     * Called when service is being destroyed.
     *
     * Clean up all resources:
     * 1. Release MediaSession
     * 2. Release ExoPlayer
     * 3. Cleanup Go player
     */
    override fun onDestroy() {
        Log.d(TAG, "PlaybackService destroyed")

        // Release MediaSession first (it holds reference to exoPlayer)
        mediaSession?.run {
            // Don't release exoPlayer here - we'll do it separately
            release()
        }
        mediaSession = null

        // Release ExoPlayer
        exoPlayer?.release()
        exoPlayer = null

        // Disconnect and cleanup Go player
        // Disconnect first to stop any pending network operations
        goPlayer?.disconnect()
        goPlayer = null

        super.onDestroy()
    }
}
