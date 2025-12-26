package com.sendspindroid.playback

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
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
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var exoPlayer: ExoPlayer? = null
    private var forwardingPlayer: MetadataForwardingPlayer? = null
    private var sendSpinClient: SendSpinClient? = null

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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlaybackService created")

        // Create notification channel for foreground service
        NotificationHelper.createNotificationChannel(this)

        // Initialize Coil ImageLoader for artwork fetching
        imageLoader = ImageLoader.Builder(this)
            .crossfade(true)
            .build()

        // Initialize ExoPlayer (temporary - will be replaced by AAudio)
        initializePlayer()

        // Create MediaSession wrapping ExoPlayer
        initializeMediaSession()

        // Initialize native Kotlin SendSpin client
        initializeSendSpinClient()
    }

    /**
     * Initializes the native Kotlin SendSpin client.
     */
    private fun initializeSendSpinClient() {
        try {
            sendSpinClient = SendSpinClient(
                deviceName = android.os.Build.MODEL,
                callback = SendSpinClientCallback()
            )
            Log.d(TAG, "SendSpinClient initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SendSpinClient", e)
            _connectionState.value = ConnectionState.Error("Failed to initialize: ${e.message}")
        }
    }

    /**
     * Callback for SendSpinClient events.
     */
    private inner class SendSpinClientCallback : SendSpinClient.Callback {

        override fun onServerDiscovered(name: String, address: String) {
            Log.d(TAG, "Server discovered (ignored in service): $name at $address")
        }

        override fun onConnected(serverName: String) {
            mainHandler.post {
                Log.d(TAG, "Connected to: $serverName")
                _connectionState.value = ConnectionState.Connected(serverName)

                // Start playback
                sendSpinClient?.play()

                // TODO: Start audio playback when AAudio is implemented
                // For now, just update state
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
            }
        }

        override fun onStateChanged(state: String) {
            mainHandler.post {
                Log.d(TAG, "State changed: $state")
                val newState = PlaybackStateType.fromString(state)
                _playbackState.value = _playbackState.value.copy(playbackState = newState)
            }
        }

        override fun onGroupUpdate(groupId: String, groupName: String, playbackState: String) {
            mainHandler.post {
                Log.d(TAG, "Group update: id=$groupId name=$groupName state=$playbackState")

                val currentState = _playbackState.value
                val isGroupChange = groupId.isNotEmpty() && groupId != currentState.groupId

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
            }
        }

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
            }
        }
    }

    /**
     * Fetches artwork from a URL using Coil.
     */
    private fun fetchArtwork(url: String) {
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

    private fun isValidArtworkUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun updateMediaSessionArtwork(bitmap: Bitmap) {
        val state = _playbackState.value
        Log.d(TAG, "Artwork updated: ${bitmap.width}x${bitmap.height} for ${state.title}")

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
        Log.d(TAG, "Metadata received: $title / $artist / $album")

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
        Log.d(TAG, "Broadcast metadata to controllers: $title / $artist")
    }

    /**
     * Connects to a SendSpin server.
     */
    fun connectToServer(address: String) {
        Log.d(TAG, "Connecting to server: $address")
        _connectionState.value = ConnectionState.Connecting

        try {
            if (sendSpinClient?.isConnected == true) {
                Log.d(TAG, "Already connected, disconnecting first...")
                sendSpinClient?.disconnect()
            }

            sendSpinClient?.connect(address)
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
        sendSpinClient?.disconnect()
    }

    /**
     * Sets the playback volume.
     */
    fun setVolume(volume: Float) {
        Log.d(TAG, "Setting volume: $volume")
        sendSpinClient?.setVolume(volume.toDouble())
        exoPlayer?.volume = volume
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initializeMediaSession() {
        val player = exoPlayer ?: run {
            Log.e(TAG, "Cannot create MediaSession: exoPlayer is null")
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
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
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
                    sendSpinClient?.next()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_PREVIOUS -> {
                    Log.d(TAG, "Previous track command received")
                    sendSpinClient?.previous()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                else -> {
                    Log.w(TAG, "Unknown custom command: ${customCommand.customAction}")
                    super.onCustomCommand(session, controller, customCommand, args)
                }
            }
        }
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

    private fun initializePlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        Log.d(TAG, "ExoPlayer initialized (temporary - will be replaced by AAudio)")
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

        val isPlaying = exoPlayer?.isPlaying == true

        if (!isPlaying) {
            Log.d(TAG, "Not playing, stopping service")
            stopSelf()
        } else {
            Log.d(TAG, "Currently playing, continuing in background")
        }

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "PlaybackService destroyed")

        serviceScope.cancel()
        imageLoader.shutdown()

        mediaSession?.run {
            release()
        }
        mediaSession = null

        forwardingPlayer = null

        exoPlayer?.release()
        exoPlayer = null

        sendSpinClient?.destroy()
        sendSpinClient = null

        super.onDestroy()
    }
}
