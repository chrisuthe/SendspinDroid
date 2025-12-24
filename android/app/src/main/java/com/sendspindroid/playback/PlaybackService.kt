package com.sendspindroid.playback

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sendspindroid.model.PlaybackState
import com.sendspindroid.model.PlaybackStateType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

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
    private var forwardingPlayer: MetadataForwardingPlayer? = null
    private var goPlayer: player.Player_? = null

    // Handler for posting callbacks to main thread (Go callbacks come from Go runtime threads)
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

    companion object {
        private const val TAG = "PlaybackService"

        // Custom session commands
        const val COMMAND_CONNECT = "com.sendspindroid.CONNECT"
        const val COMMAND_DISCONNECT = "com.sendspindroid.DISCONNECT"
        const val COMMAND_SET_VOLUME = "com.sendspindroid.SET_VOLUME"
        const val COMMAND_NEXT = "com.sendspindroid.NEXT"
        const val COMMAND_PREVIOUS = "com.sendspindroid.PREVIOUS"

        // Command arguments
        const val ARG_SERVER_ADDRESS = "server_address"
        const val ARG_VOLUME = "volume"

        // Session extras keys for metadata (service → controller)
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_ARTWORK_URL = "artwork_url"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_ARTWORK_DATA = "artwork_data"
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

        // Initialize Coil ImageLoader for artwork fetching
        imageLoader = ImageLoader.Builder(this)
            .crossfade(true)
            .build()

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

                // Start Go player streaming FIRST (must happen before ExoPlayer reads)
                // We call play() here instead of in connectToServer() because
                // connect() is async - the connection isn't established yet there
                try {
                    goPlayer?.play()
                    Log.d(TAG, "Go player play() called after connection established")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start Go player playback", e)
                }

                // Start ExoPlayer playback with Go player audio
                startExoPlayerPlayback()
            }
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onDisconnected() {
            mainHandler.post {
                Log.d(TAG, "Disconnected from server")
                _connectionState.value = ConnectionState.Disconnected

                // Clear playback state on disconnect
                _playbackState.value = PlaybackState()
                lastArtworkUrl = null
                currentArtwork = null

                // Clear lock screen metadata
                forwardingPlayer?.clearMetadata()

                // Stop ExoPlayer playback
                stopExoPlayerPlayback()
            }
        }

        override fun onStateChanged(state: String) {
            mainHandler.post {
                Log.d(TAG, "State changed: $state")
                val newState = PlaybackStateType.fromString(state)
                _playbackState.value = _playbackState.value.copy(playbackState = newState)
            }
        }

        /**
         * Called when group/update message is received.
         * Updates group-level state (like Python CLI's _handle_group_update).
         * Also syncs ExoPlayer state with server state.
         */
        override fun onGroupUpdate(groupId: String, groupName: String, playbackState: String) {
            mainHandler.post {
                Log.d(TAG, "Group update: id=$groupId name=$groupName state=$playbackState")

                val currentState = _playbackState.value
                val isGroupChange = groupId.isNotEmpty() && groupId != currentState.groupId

                // Clear metadata on group change (like Python CLI does)
                val newState = if (isGroupChange) {
                    currentState.withClearedMetadata().copy(
                        groupId = groupId,
                        groupName = groupName.ifEmpty { null },
                        playbackState = PlaybackStateType.fromString(playbackState)
                    )
                } else {
                    currentState.copy(
                        groupId = groupId.ifEmpty { currentState.groupId },
                        groupName = groupName.ifEmpty { currentState.groupName },
                        playbackState = if (playbackState.isNotEmpty())
                            PlaybackStateType.fromString(playbackState)
                        else currentState.playbackState
                    )
                }
                _playbackState.value = newState

                // Sync ExoPlayer state with server state
                // This ensures the phone follows when server starts/stops playback
                syncExoPlayerWithServerState(playbackState)
            }
        }

        /**
         * Called when metadata update is received (from server/state or group/update).
         * Updates track-level metadata and triggers artwork fetch if URL changed.
         */
        override fun onMetadataUpdate(
            title: String,
            artist: String,
            album: String,
            artworkUrl: String,
            durationMs: Long,
            positionMs: Long
        ) {
            mainHandler.post {
                Log.d(TAG, "Metadata update: $title / $artist / $album (artwork: $artworkUrl)")

                // Update playback state with new metadata
                _playbackState.value = _playbackState.value.withMetadata(
                    title = title.ifEmpty { null },
                    artist = artist.ifEmpty { null },
                    album = album.ifEmpty { null },
                    artworkUrl = artworkUrl.ifEmpty { null },
                    durationMs = durationMs,
                    positionMs = positionMs
                )

                // Update MediaSession metadata
                updateMediaMetadata(title, artist, album)

                // Fetch artwork if URL changed (like C# does)
                if (artworkUrl.isNotEmpty() && artworkUrl != lastArtworkUrl) {
                    lastArtworkUrl = artworkUrl
                    fetchArtwork(artworkUrl)
                }
            }
        }

        /**
         * Called when binary artwork data is received (message types 8-11).
         */
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
                Log.e(TAG, "Go player error: $message")
                _connectionState.value = ConnectionState.Error(message)
            }
        }
    }

    /**
     * Fetches artwork from a URL using Coil.
     * Like C#'s FetchArtworkAsync in MainViewModel.cs.
     */
    private fun fetchArtwork(url: String) {
        // Validate URL (like C# does - block localhost, etc.)
        if (!isValidArtworkUrl(url)) {
            Log.w(TAG, "Invalid artwork URL: $url")
            return
        }

        Log.d(TAG, "Fetching artwork from: $url")
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

    /**
     * Validates artwork URL for security (like C# implementation).
     */
    private fun isValidArtworkUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

    /**
     * Updates MediaSession with artwork for lock screen and notifications.
     *
     * Updates the ForwardingPlayer with new artwork so MediaSession
     * can display it on lock screen and in notifications.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun updateMediaSessionArtwork(bitmap: Bitmap) {
        val state = _playbackState.value
        Log.d(TAG, "Artwork updated: ${bitmap.width}x${bitmap.height} for ${state.title}")

        // Update the ForwardingPlayer with artwork
        forwardingPlayer?.updateMetadata(
            title = state.title,
            artist = state.artist,
            album = state.album,
            artwork = bitmap,
            artworkUri = state.artworkUrl?.let { android.net.Uri.parse(it) }
        )

        // Also broadcast to controllers via session extras
        broadcastMetadataToControllers(
            title = state.title ?: "",
            artist = state.artist ?: "",
            album = state.album ?: "",
            artworkUrl = state.artworkUrl,
            durationMs = state.durationMs,
            positionMs = state.positionMs
        )
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
     * Syncs ExoPlayer's play/pause state with the server's state.
     *
     * When the server changes playback state (e.g., user hits play on server),
     * we need to tell ExoPlayer to follow. This prevents the audio channel
     * from filling up when ExoPlayer is paused but server is playing.
     *
     * Note: We track whether we're syncing to avoid feedback loops
     * (ExoPlayer state change → send command → server state → sync → etc.)
     */
    /** Thread-safe flag to prevent feedback loops during state synchronization */
    private val isSyncingWithServer = AtomicBoolean(false)

    private fun syncExoPlayerWithServerState(serverState: String) {
        // Use compareAndSet for thread-safe check-and-set
        if (!isSyncingWithServer.compareAndSet(false, true)) return

        try {
            val player = exoPlayer ?: return

            when (serverState.lowercase()) {
                "playing" -> {
                    if (!player.isPlaying && player.playbackState == Player.STATE_READY) {
                        Log.d(TAG, "Server is playing, resuming ExoPlayer")
                        player.play()
                    }
                }
                "paused", "stopped" -> {
                    if (player.isPlaying) {
                        Log.d(TAG, "Server is $serverState, pausing ExoPlayer")
                        player.pause()
                    }
                }
            }
        } finally {
            isSyncingWithServer.set(false)
        }
    }

    /**
     * Updates media metadata for notifications and lock screen.
     *
     * Uses MetadataForwardingPlayer to provide dynamic metadata to MediaSession.
     * This ensures lock screen and notifications show current track info.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun updateMediaMetadata(title: String, artist: String, album: String) {
        Log.d(TAG, "Metadata received: $title / $artist / $album")

        // Use current playback state to get all metadata
        val state = _playbackState.value

        // Update the ForwardingPlayer with current metadata
        // This will trigger MediaSession to update lock screen/notifications
        forwardingPlayer?.updateMetadata(
            title = state.title,
            artist = state.artist,
            album = state.album,
            artwork = currentArtwork,
            artworkUri = state.artworkUrl?.let { android.net.Uri.parse(it) }
        )

        // Also broadcast to controllers via session extras (for our app's UI)
        broadcastMetadataToControllers(
            title = title,
            artist = artist,
            album = album,
            artworkUrl = state.artworkUrl,
            durationMs = state.durationMs,
            positionMs = state.positionMs
        )
    }

    /**
     * Broadcasts metadata to all connected MediaControllers via session extras.
     * Controllers receive updates via Controller.Listener.onExtrasChanged().
     */
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
        Log.d(TAG, "Broadcast metadata to controllers: $title / $artist")
    }

    /**
     * Connects to a SendSpin server.
     *
     * @param address Server address in host:port format
     */
    fun connectToServer(address: String) {
        Log.d(TAG, "Connecting to server: $address")
        _connectionState.value = ConnectionState.Connecting

        try {
            // If already connected, disconnect first
            if (goPlayer?.isConnected == true) {
                Log.d(TAG, "Already connected, disconnecting first...")
                goPlayer?.disconnect()
            }

            // Just initiate connection - play() is called in onConnected callback
            // because connect() is async and play() would be ignored if called here
            goPlayer?.connect(address)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to server", e)
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
        }
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
     * Initializes MediaSession wrapping ExoPlayer via ForwardingPlayer.
     *
     * We wrap ExoPlayer in MetadataForwardingPlayer to provide dynamic
     * metadata for lock screen and notifications. The ForwardingPlayer
     * intercepts getMediaMetadata() calls and returns our current track info.
     *
     * MediaSession provides:
     * - System media integration (notifications, lock screen)
     * - MediaController API for remote control
     * - Media button handling (Bluetooth, wired headset)
     *
     * MediaSessionService automatically handles foreground notification
     * based on the player's state.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initializeMediaSession() {
        // Ensure exoPlayer is initialized
        val player = exoPlayer ?: run {
            Log.e(TAG, "Cannot create MediaSession: exoPlayer is null")
            return
        }

        // Wrap ExoPlayer in MetadataForwardingPlayer for dynamic metadata
        // This allows lock screen to show current track instead of "no title"
        forwardingPlayer = MetadataForwardingPlayer(player)

        // Create MediaSession with the forwarding player (not raw ExoPlayer)
        mediaSession = MediaSession.Builder(this, forwardingPlayer!!)
            .setCallback(MediaSessionCallback())
            .build()

        Log.d(TAG, "MediaSession initialized with MetadataForwardingPlayer")
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
                .add(SessionCommand(COMMAND_NEXT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_PREVIOUS, Bundle.EMPTY))
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

                COMMAND_NEXT -> {
                    Log.d(TAG, "Next track command received")
                    try {
                        goPlayer?.next()
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to skip to next track", e)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
                    }
                }

                COMMAND_PREVIOUS -> {
                    Log.d(TAG, "Previous track command received")
                    try {
                        goPlayer?.previous()
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to go to previous track", e)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
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
     * - Player listener to forward play/pause to Go player (server control)
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

        // Add listener to forward play/pause commands to the server
        // This ensures the server knows when the user pauses/resumes playback
        exoPlayer?.addListener(ExoPlayerStateListener())

        Log.d(TAG, "ExoPlayer initialized")
    }

    /**
     * Listener for ExoPlayer state changes.
     *
     * Forwards play/pause commands to the Go player, which sends them
     * to the SendSpin server. This is how the C# client works - when you
     * pause locally, it tells the server to pause as well.
     */
    private inner class ExoPlayerStateListener : Player.Listener {

        // Track previous state to detect actual changes
        private var wasPlaying = false

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Only forward commands when connected to a server
            val gp = goPlayer ?: return
            if (!gp.isConnected) return

            Log.d(TAG, "onPlayWhenReadyChanged: playWhenReady=$playWhenReady, reason=$reason")

            // Determine if this is a user-initiated action vs system (audio focus, etc.)
            // We want to send commands to server for all cases to keep state synchronized
            try {
                if (playWhenReady && !wasPlaying) {
                    // Transitioning to playing state
                    Log.d(TAG, "Sending play command to server")
                    gp.sendCommand("play")
                } else if (!playWhenReady && wasPlaying) {
                    // Transitioning to paused state
                    Log.d(TAG, "Sending pause command to server")
                    gp.sendCommand("pause")
                }
                wasPlaying = playWhenReady
            } catch (e: Exception) {
                Log.e(TAG, "Error sending command to server", e)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "ExoPlayer state: $playbackState")
            // Could handle STATE_ENDED to send stop command if needed
        }
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

        // Cancel background coroutines
        serviceScope.cancel()

        // Shutdown ImageLoader to release thread pools and caches
        imageLoader.shutdown()

        // Release MediaSession first (it holds reference to forwardingPlayer)
        mediaSession?.run {
            release()
        }
        mediaSession = null

        // Clear forwardingPlayer reference (it wraps exoPlayer)
        forwardingPlayer = null

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
