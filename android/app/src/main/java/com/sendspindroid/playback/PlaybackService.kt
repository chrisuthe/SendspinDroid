package com.sendspindroid.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.net.Uri
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.sendspindroid.R
import com.sendspindroid.MainActivity
import com.sendspindroid.ServerRepository
import com.sendspindroid.SyncOffsetPreference
import com.sendspindroid.ui.settings.SettingsViewModel
import com.sendspindroid.debug.DebugLogger
import com.sendspindroid.model.PlaybackState
import com.sendspindroid.model.PlaybackStateType
import com.sendspindroid.model.SyncStats
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaQueueItem
import com.sendspindroid.musicassistant.MaRadio
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.sendspin.SendSpinClient
import com.sendspindroid.discovery.NsdDiscoveryManager
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.UserSettings.ConnectionMode
import com.sendspindroid.sendspin.SyncAudioPlayer
import com.sendspindroid.sendspin.SyncAudioPlayerCallback
import com.sendspindroid.sendspin.PlaybackState as SyncPlaybackState
import com.sendspindroid.sendspin.decoder.AudioDecoder
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.network.ConnectionSelector
import com.sendspindroid.network.NetworkEvaluator
import com.sendspindroid.network.NetworkState
import com.sendspindroid.network.TransportType
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
    private var audioDecoder: AudioDecoder? = null
    private var currentCodec: String = "pcm"  // Track current stream codec for stats

    // Current server connection info (for MA integration)
    private var currentServerId: String? = null
    private var currentConnectionMode: ConnectionMode = ConnectionMode.LOCAL

    // mDNS discovery for Android Auto browse tree
    private var browseDiscoveryManager: NsdDiscoveryManager? = null

    // ========================================================================
    // Music Assistant Browse Tree - Cache & Helpers
    // ========================================================================

    /** Simple time-based cache entry. */
    private data class CacheEntry<T>(val data: T, val time: Long = System.currentTimeMillis()) {
        fun expired(ttl: Long) = System.currentTimeMillis() - time > ttl
    }

    // MA list caches
    private var maPlaylistsCache: CacheEntry<List<MediaItem>>? = null
    private var maAlbumsCache: CacheEntry<List<MediaItem>>? = null
    private var maArtistsCache: CacheEntry<List<MediaItem>>? = null
    private var maRadioCache: CacheEntry<List<MediaItem>>? = null

    // MA drill-down caches (keyed by item ID)
    private val maPlaylistTracksCache = mutableMapOf<String, CacheEntry<List<MediaItem>>>()
    private val maAlbumTracksCache = mutableMapOf<String, CacheEntry<List<MediaItem>>>()
    private val maArtistAlbumsCache = mutableMapOf<String, CacheEntry<List<MediaItem>>>()

    // MA search result cache
    private var maSearchResultsCache: List<MediaItem>? = null

    // Generation counter for populatePlayerQueue() to discard stale async results
    private var queuePopulateGeneration = 0L

    /** Clears all MA caches (called on MA disconnect). */
    private fun clearMaCaches() {
        maPlaylistsCache = null
        maAlbumsCache = null
        maArtistsCache = null
        maRadioCache = null
        maPlaylistTracksCache.clear()
        maAlbumTracksCache.clear()
        maArtistAlbumsCache.clear()
        maSearchResultsCache = null
    }

    /** Encodes a URI to Base64 URL-safe string for use in media IDs. */
    private fun encodeMediaUri(uri: String): String {
        return android.util.Base64.encodeToString(
            uri.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }

    /** Decodes a Base64 URL-safe media ID back to a URI. */
    private fun decodeMediaUri(encoded: String): String {
        return String(
            android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING),
            Charsets.UTF_8
        )
    }

    /** Bridges a suspend function into a ListenableFuture for MediaLibrarySession callbacks. */
    private fun <T> suspendToFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        serviceScope.launch {
            try {
                future.set(block())
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    // Handler for posting callbacks to main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    // BroadcastReceiver for sync offset changes from settings
    private val syncOffsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val offsetMs = intent.getIntExtra(SyncOffsetPreference.EXTRA_OFFSET_MS, 0)
            sendSpinClient?.getTimeFilter()?.let { timeFilter ->
                timeFilter.staticDelayMs = offsetMs.toDouble()
                Log.i(TAG, "Applied sync offset from settings change: ${offsetMs}ms")
            }
        }
    }

    // BroadcastReceiver for debug logging toggle changes from settings
    private val debugLoggingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val enabled = intent.getBooleanExtra(
                SettingsViewModel.EXTRA_DEBUG_LOGGING_ENABLED, false
            )
            Log.i(TAG, "Debug logging changed: $enabled")

            if (enabled && isConnected()) {
                // Start collecting stats if connected
                startDebugLogging()
            } else {
                stopDebugLogging()
            }
        }
    }

    // BroadcastReceiver for High Power Mode toggle changes from settings
    private val highPowerModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val enabled = intent.getBooleanExtra(
                SettingsViewModel.EXTRA_HIGH_POWER_MODE_ENABLED, false
            )
            Log.i(TAG, "High Power Mode changed: $enabled")
            onHighPowerModeChanged(enabled)
        }
    }

    // Flag to prevent callbacks from executing after service is destroyed
    @Volatile
    private var isDestroyed = false

    // Guards against race condition: onAudioChunk runs on WebSocket thread but
    // decoder creation is posted to mainHandler. Chunks arriving before the new
    // decoder is ready would hit the old (released) decoder and throw.
    @Volatile
    private var decoderReady = false

    // Connection state exposed as StateFlow for observers
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Playback state exposed as StateFlow (like Python CLI's AppState)
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Artwork state
    private var lastArtworkUrl: String? = null
    private var currentArtwork: Bitmap? = null
    // ImageLoader is null when low memory mode is enabled
    private var imageLoader: ImageLoader? = null

    // Coroutine scope for background tasks (artwork loading)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Wake lock to prevent CPU sleep during playback
    private var wakeLock: PowerManager.WakeLock? = null

    // WiFi lock to prevent WiFi from going to sleep during playback
    private var wifiLock: WifiManager.WifiLock? = null

    // High Power Mode locks - separate from streaming locks to avoid lifecycle interference
    // These span the entire connection (not just streaming) when High Power Mode is enabled
    private var highPowerWakeLock: PowerManager.WakeLock? = null
    private var highPowerWifiLock: WifiManager.WifiLock? = null

    // Handler for periodic wake lock refresh
    private val wakeLockHandler = Handler(Looper.getMainLooper())

    // Wake lock refresh runnable - refreshes wake lock periodically during active playback
    private val wakeLockRefreshRunnable = object : Runnable {
        override fun run() {
            // Prevent callback execution after service destruction
            if (isDestroyed) return
            refreshWakeLock()
            // Schedule next refresh if still playing and not destroyed
            if (!isDestroyed && isActivelyPlaying()) {
                wakeLockHandler.postDelayed(this, WAKE_LOCK_REFRESH_INTERVAL_MS)
            }
        }
    }

    // High Power wake lock refresh runnable - keeps wake lock alive for entire connection
    // Unlike streaming refresh, this runs as long as connected (not just while playing)
    private val highPowerWakeLockRefreshRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            if (highPowerWakeLock?.isHeld == true) {
                highPowerWakeLock?.release()
                highPowerWakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                Log.d(TAG, "High Power wake lock refreshed with ${WAKE_LOCK_TIMEOUT_MS / 60000}min timeout")
            }
            if (!isDestroyed && highPowerWakeLock?.isHeld == true) {
                wakeLockHandler.postDelayed(this, WAKE_LOCK_REFRESH_INTERVAL_MS)
            }
        }
    }

    // Debug logging handler - logs stats periodically when debug mode is enabled
    private val debugLogHandler = Handler(Looper.getMainLooper())
    private val debugLogRunnable = object : Runnable {
        override fun run() {
            // Prevent callback execution after service destruction
            if (isDestroyed) return
            if (DebugLogger.isEnabled && isConnected()) {
                logCurrentStats()
                debugLogHandler.postDelayed(this, DEBUG_LOG_INTERVAL_MS)
            }
        }
    }

    // Network change detection - resets time filter when network changes
    private var connectivityManager: ConnectivityManager? = null
    private var lastNetworkId: Int = -1
    private var networkEvaluator: NetworkEvaluator? = null

    // AudioManager for device volume control (Spotify-style hybrid approach)
    private var audioManager: AudioManager? = null
    private var volumeObserver: ContentObserver? = null
    private var volumeObserverRegistered: Boolean = false  // Track registration state to prevent leaks
    private var lastKnownVolume: Int = -1  // Track to detect external volume changes

    // Audio focus management - required for Android Auto to hand over audio output
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val networkId = network.hashCode()
            Log.d(TAG, "Network available: id=$networkId (last=$lastNetworkId)")

            // Evaluate network conditions
            networkEvaluator?.evaluateCurrentNetwork(network)

            // Notify client that network is available - this handles both:
            // 1. Resuming paused reconnection (waitingForNetwork)
            // 2. Cancelling backoff for immediate retry (existing onNetworkAvailable behavior)
            sendSpinClient?.setNetworkAvailable(true)

            // Only trigger time filter reset if we had a previous network and it changed
            if (lastNetworkId != -1 && lastNetworkId != networkId) {
                Log.i(TAG, "Network changed from $lastNetworkId to $networkId")
                sendSpinClient?.onNetworkChanged()
            }
            lastNetworkId = networkId
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost: id=${network.hashCode()}")
            // Don't reset lastNetworkId here - we want to detect when a new network comes up
            // Update network evaluator to reflect disconnected state
            networkEvaluator?.evaluateCurrentNetwork(null)
            // Notify client so reconnection pauses instead of wasting attempts
            sendSpinClient?.setNetworkAvailable(false)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Re-evaluate network conditions when capabilities change (e.g., signal strength)
            Log.d(TAG, "Network capabilities changed: id=${network.hashCode()}")
            networkEvaluator?.evaluateCurrentNetwork(network)
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

        // Debug logging interval (1 sample per second)
        private const val DEBUG_LOG_INTERVAL_MS = 1000L

        // Custom session commands
        const val COMMAND_CONNECT = "com.sendspindroid.CONNECT"
        const val COMMAND_DISCONNECT = "com.sendspindroid.DISCONNECT"
        const val COMMAND_SET_VOLUME = "com.sendspindroid.SET_VOLUME"
        const val COMMAND_NEXT = "com.sendspindroid.NEXT"
        const val COMMAND_PREVIOUS = "com.sendspindroid.PREVIOUS"
        const val COMMAND_SWITCH_GROUP = "com.sendspindroid.SWITCH_GROUP"
        const val COMMAND_GET_STATS = "com.sendspindroid.GET_STATS"
        const val COMMAND_CONNECT_REMOTE = "com.sendspindroid.CONNECT_REMOTE"
        const val COMMAND_CONNECT_PROXY = "com.sendspindroid.CONNECT_PROXY"

        // Command arguments
        const val ARG_SERVER_ADDRESS = "server_address"
        const val ARG_SERVER_PATH = "server_path"
        const val ARG_VOLUME = "volume"
        const val ARG_REMOTE_ID = "remote_id"
        const val ARG_PROXY_URL = "proxy_url"
        const val ARG_AUTH_TOKEN = "auth_token"
        const val ARG_SERVER_ID = "server_id"  // For MA integration

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
        const val EXTRA_WAS_USER_INITIATED = "was_user_initiated"
        const val EXTRA_WAS_RECONNECT_EXHAUSTED = "was_reconnect_exhausted"

        // Session extras keys for volume (server → controller)
        const val EXTRA_VOLUME = "volume"

        // Session extras keys for group info
        const val EXTRA_GROUP_NAME = "group_name"

        // Connection state values
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_RECONNECTING = "reconnecting"
        const val STATE_ERROR = "error"

        // Android Auto browse tree media IDs
        // Max artwork bitmap dimension (px) for MediaMetadata.
        // Framework enforces 320dp; at tvdpi (~1.33x) that's ~426px.
        // 480px covers xhdpi (2x) and looks sharp on TV.
        private const val MAX_ARTWORK_SIZE = 480

        private const val MEDIA_ID_ROOT = "root"
        private const val MEDIA_ID_DISCOVERED = "discovered_servers"
        private const val MEDIA_ID_SERVER_PREFIX = "server_"
        private const val MEDIA_ID_SAVED_SERVER_PREFIX = "saved_server_"

        // Android Auto content style hint keys
        private const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_SINGLE_ITEM = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT"
        private const val CONTENT_STYLE_GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
        private const val CONTENT_STYLE_LIST = 1
        private const val CONTENT_STYLE_GRID = 2

        // Music Assistant browse tree media IDs
        private const val MEDIA_ID_MA_PLAYLISTS = "ma_playlists"
        private const val MEDIA_ID_MA_ALBUMS = "ma_albums"
        private const val MEDIA_ID_MA_ARTISTS = "ma_artists"
        private const val MEDIA_ID_MA_RADIO = "ma_radio"

        // MA item prefixes (for drill-down into children)
        private const val MEDIA_ID_MA_PLAYLIST_PREFIX = "ma_playlist_"
        private const val MEDIA_ID_MA_ALBUM_PREFIX = "ma_album_"
        private const val MEDIA_ID_MA_ARTIST_PREFIX = "ma_artist_"

        // MA leaf item prefixes (playable, URI encoded as Base64)
        private const val MEDIA_ID_MA_TRACK_PREFIX = "ma_track_"
        private const val MEDIA_ID_MA_RADIO_ITEM_PREFIX = "ma_radio_item_"

        // MA Queue item prefix (for native Now Playing queue)
        private const val MEDIA_ID_MA_QUEUE_ITEM_PREFIX = "ma_qi_"

        // MA cache TTLs
        private const val MA_LIST_CACHE_TTL_MS = 5 * 60 * 1000L   // 5 minutes
        private const val MA_DETAIL_CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes
    }

    /**
     * Connection state for the service.
     */
    sealed class ConnectionState {
        /**
         * Disconnected from server.
         * @param wasUserInitiated true if user explicitly requested disconnect
         * @param wasReconnectExhausted true if internal reconnect attempts were exhausted
         */
        data class Disconnected(
            val wasUserInitiated: Boolean = false,
            val wasReconnectExhausted: Boolean = false
        ) : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val serverName: String) : ConnectionState()
        /** Connection lost, reconnecting - playback continues from buffer */
        data class Reconnecting(val serverName: String, val attempt: Int) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PlaybackService.onCreate() started")

        // Create notification channel for foreground service
        NotificationHelper.createNotificationChannel(this)

        // Configure media notification provider to use our channel
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NotificationHelper.CHANNEL_ID)
                .build()
        )

        // Initialize UserSettings for player name preference (must be before lowMemoryMode check)
        com.sendspindroid.UserSettings.initialize(this)

        // Initialize MusicAssistantManager for MA API integration
        MusicAssistantManager.initialize(this)

        // Initialize UnifiedServerRepository for server lookups
        UnifiedServerRepository.initialize(this)

        // Register receiver for sync offset changes from settings
        LocalBroadcastManager.getInstance(this).registerReceiver(
            syncOffsetReceiver,
            IntentFilter(SyncOffsetPreference.ACTION_SYNC_OFFSET_CHANGED)
        )

        // Register receiver for debug logging toggle changes from settings
        LocalBroadcastManager.getInstance(this).registerReceiver(
            debugLoggingReceiver,
            IntentFilter(SettingsViewModel.ACTION_DEBUG_LOGGING_CHANGED)
        )

        // Register receiver for High Power Mode toggle changes from settings
        LocalBroadcastManager.getInstance(this).registerReceiver(
            highPowerModeReceiver,
            IntentFilter(SettingsViewModel.ACTION_HIGH_POWER_MODE_CHANGED)
        )

        // Initialize Coil ImageLoader for artwork fetching (skip in low memory mode)
        if (!com.sendspindroid.UserSettings.lowMemoryMode) {
            imageLoader = ImageLoader.Builder(this)
                .crossfade(true)
                .build()
        } else {
            Log.i(TAG, "Low Memory Mode: Skipping ImageLoader initialization")
        }

        // Initialize SendSpinPlayer
        initializePlayer()

        // Create MediaSession wrapping SendSpinPlayer
        initializeMediaSession()
        Log.i(TAG, "PlaybackService.onCreate() session ready: mediaSession=${mediaSession != null}")

        // Initialize native Kotlin SendSpin client
        initializeSendSpinClient()

        // Initialize network evaluator for passive network monitoring
        networkEvaluator = NetworkEvaluator(this)

        // Register network callback to detect network changes
        registerNetworkCallback()

        // Perform initial network evaluation
        networkEvaluator?.evaluateCurrentNetwork()

        // Initialize AudioManager for device volume control
        initializeVolumeControl()
    }

    /**
     * Initializes device volume control (Spotify-style hybrid approach).
     *
     * This sets up:
     * - AudioManager for reading/writing device STREAM_MUSIC volume
     * - ContentObserver to detect hardware volume button presses and sync to server
     *
     * Hardware volume buttons now control playback volume, and changes are
     * synced to the SendSpin server for multi-client coordination.
     */
    private fun initializeVolumeControl() {
        // Prevent double registration - unregister existing observer first
        if (volumeObserverRegistered) {
            Log.w(TAG, "Volume observer already registered, skipping initialization")
            return
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Track current volume to detect external changes
        lastKnownVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0

        // Initialize playback state with actual device volume (not default 100%)
        val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        val volumePercent = ((lastKnownVolume.toFloat() / maxVolume) * 100).toInt()
        _playbackState.value = _playbackState.value.copy(volume = volumePercent)

        // Create observer to detect volume changes from hardware buttons
        volumeObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return

                // Only sync to server if volume actually changed (not our own change)
                if (currentVolume != lastKnownVolume) {
                    lastKnownVolume = currentVolume

                    // Convert to normalized 0.0-1.0 and sync to server
                    val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                    val normalizedVolume = currentVolume.toFloat() / maxVolume
                    Log.d(TAG, "Device volume changed via hardware buttons: $currentVolume/$maxVolume ($normalizedVolume)")

                    // Sync to server (for multi-client coordination)
                    sendSpinClient?.setVolume(normalizedVolume.toDouble())

                    // Update playback state for UI sync
                    val volumePercent = (normalizedVolume * 100).toInt()
                    _playbackState.value = _playbackState.value.copy(volume = volumePercent)
                    broadcastSessionExtras()
                }
            }
        }

        // Register the volume observer
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver!!
        )
        volumeObserverRegistered = true

        Log.d(TAG, "Volume control initialized - using device STREAM_MUSIC")
    }

    /**
     * Registers a network callback to detect when the network changes.
     * When the network changes (e.g., WiFi AP handoff, WiFi→mobile), we reset
     * the time filter to force re-synchronization since latency may have changed.
     */
    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            Log.d(TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Unregisters the network callback.
     */
    private fun unregisterNetworkCallback() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "Network callback unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
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
                context = applicationContext,
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

        override fun onBufferLow(remainingMs: Long) {
            Log.w(TAG, "Buffer low during reconnection: ${remainingMs}ms remaining")
            // Update session extras so UI can show buffer status
            mainHandler.post {
                broadcastSessionExtras()
            }
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onBufferExhausted() {
            Log.e(TAG, "Buffer exhausted during reconnection - stopping playback")
            mainHandler.post {
                // Stop audio playback and release playback locks
                syncAudioPlayer?.stop()
                releasePlaybackLocks()

                // Update connection state to error
                _connectionState.value = ConnectionState.Error("Connection lost. Buffer exhausted.")
                broadcastConnectionState(STATE_ERROR, errorMessage = "Connection lost")

                // Clear playback state
                _playbackState.value = _playbackState.value.copy(
                    playbackState = PlaybackStateType.STOPPED
                )
                sendSpinPlayer?.updatePlayWhenReadyFromServer(false)

                // Stop foreground notification since playback failed
                stopForegroundNotification()
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
                sendSpinPlayer?.clearError()

                // Refresh browse tree root so "Connect" disappears
                mediaSession?.notifyChildrenChanged(MEDIA_ID_ROOT, 0, null)

                // Apply saved sync offset from settings
                applySyncOffsetFromSettings()

                // Start foreground service to prevent process from being killed
                // but DON'T acquire wake/WiFi locks yet - those drain battery and are
                // only needed during active audio streaming (acquired in onStreamStart)
                startForegroundServiceWithNotification(serverName)

                // In High Power Mode, acquire WiFi + CPU locks immediately on connect
                // to prevent Android from sleeping the connection between streams
                if (com.sendspindroid.UserSettings.highPowerMode) {
                    acquireHighPowerLocks()
                }

                // Start debug logging session if enabled
                val serverAddr = sendSpinClient?.let {
                    // Get address from connection state or use empty string
                    (it.connectionState.value as? SendSpinClient.ConnectionState.Connected)?.serverName ?: ""
                } ?: ""
                DebugLogger.startSession(serverName, serverAddr)
                startDebugLogging()

                // Broadcast connection state to controllers (MainActivity)
                broadcastConnectionState(STATE_CONNECTED, serverName)

                // Notify MusicAssistantManager of connection
                // This triggers MA API availability check and token auth if applicable
                notifyMusicAssistantConnected()

                // Note: Don't auto-start playback - let user control or server push state
            }
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onDisconnected(wasUserInitiated: Boolean, wasReconnectExhausted: Boolean) {
            mainHandler.post {
                Log.d(TAG, "Disconnected from server (userInitiated=$wasUserInitiated, reconnectExhausted=$wasReconnectExhausted)")

                // Stop debug logging session
                stopDebugLogging()
                DebugLogger.endSession()

                // Check if we're in DRAINING state (reconnection in progress)
                // If so, keep the audio player alive to continue playback from buffer
                val isDraining = syncAudioPlayer?.getPlaybackState() == SyncPlaybackState.DRAINING
                if (isDraining && !wasUserInitiated) {
                    Log.i(TAG, "Disconnected during DRAINING - keeping audio player alive for auto-reconnect")
                    // Don't stop/release - let DRAINING continue playing from buffer
                    // Keep foreground service running for reconnection
                } else {
                    // Stop audio playback and release playback locks (CPU/WiFi)
                    syncAudioPlayer?.stop()
                    syncAudioPlayer?.release()
                    syncAudioPlayer = null
                    sendSpinPlayer?.setSyncAudioPlayer(null)
                    releasePlaybackLocks()
                    releaseHighPowerLocks()
                    // Stop the foreground notification since we're fully disconnecting
                    stopForegroundNotification()
                }
                sendSpinPlayer?.updateConnectionState(false, null)

                // Show error on Android Auto if reconnect attempts were exhausted
                if (wasReconnectExhausted) {
                    sendSpinPlayer?.setError("Connection lost")
                }

                _connectionState.value = ConnectionState.Disconnected(
                    wasUserInitiated = wasUserInitiated,
                    wasReconnectExhausted = wasReconnectExhausted
                )

                // Refresh browse tree root so "Connect" reappears
                mediaSession?.notifyChildrenChanged(MEDIA_ID_ROOT, 0, null)

                // Broadcast disconnection to controllers (MainActivity)
                broadcastConnectionState(STATE_DISCONNECTED)

                // Clear playback state on disconnect
                _playbackState.value = PlaybackState()
                lastArtworkUrl = null
                currentArtwork = null

                // Clear lock screen metadata
                forwardingPlayer?.clearMetadata()

                // Notify MusicAssistantManager of disconnection (only on full disconnect)
                if (!isDraining || wasUserInitiated) {
                    MusicAssistantManager.onServerDisconnected()
                    currentServerId = null
                }
            }
        }

        override fun onStateChanged(state: String) {
            mainHandler.post {
                Log.d(TAG, "State changed: $state")
                val newState = PlaybackStateType.fromString(state)

                // Update playWhenReady from server state (without sending command back)
                sendSpinPlayer?.updatePlayWhenReadyFromServer(newState == PlaybackStateType.PLAYING)

                // Handle playback state transitions per SendSpin spec
                if (newState == PlaybackStateType.STOPPED) {
                    // Stop: "reset position to beginning" - clear buffer
                    Log.d(TAG, "State is stopped - clearing audio buffer and releasing playback locks")
                    syncAudioPlayer?.clearBuffer()
                    syncAudioPlayer?.pause()
                    releasePlaybackLocks()
                } else if (newState == PlaybackStateType.PAUSED) {
                    // Pause: "maintains current position for later resumption" - keep buffer
                    Log.d(TAG, "State is paused - pausing audio (keeping buffer)")
                    syncAudioPlayer?.pause()
                    releasePlaybackLocks()
                } else if (newState == PlaybackStateType.PLAYING) {
                    // Playing: resume playback if paused
                    Log.d(TAG, "State is playing - resuming audio and acquiring playback locks")
                    syncAudioPlayer?.resume()
                    acquirePlaybackLocks()
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

                // Update playWhenReady from server state (without sending command back)
                if (playbackState.isNotEmpty()) {
                    sendSpinPlayer?.updatePlayWhenReadyFromServer(newPlaybackState == PlaybackStateType.PLAYING)
                }

                // Handle playback state transitions per SendSpin spec
                if (playbackState.isNotEmpty()) {
                    when (newPlaybackState) {
                        PlaybackStateType.STOPPED -> {
                            // Check if we're in DRAINING state (actively playing from buffer during reconnection)
                            // Only auto-resume if we have buffered audio - this means we were playing when disconnected
                            val isDraining = syncAudioPlayer?.getPlaybackState() == SyncPlaybackState.DRAINING

                            if (isDraining) {
                                // We're reconnecting with active buffer - request server to resume playback
                                // This handles the case where we were playing, got disconnected briefly,
                                // and the server reports stopped state on reconnect
                                Log.i(TAG, "Received stop while DRAINING - sending play command to resume")
                                sendSpinClient?.play()
                                // Don't clear buffer or pause - keep playing from existing buffer
                            } else {
                                // Stop: "reset position to beginning" - clear buffer
                                // Server genuinely wants to stop - honor it
                                Log.d(TAG, "Playback stopped - clearing audio buffer and releasing playback locks")
                                syncAudioPlayer?.clearBuffer()
                                syncAudioPlayer?.pause()
                                releasePlaybackLocks()
                            }
                        }
                        PlaybackStateType.PAUSED -> {
                            // Pause: "maintains current position for later resumption" - keep buffer
                            Log.d(TAG, "Playback paused - pausing audio (keeping buffer)")
                            syncAudioPlayer?.pause()
                            releasePlaybackLocks()
                        }
                        PlaybackStateType.PLAYING -> {
                            // Playing: resume playback if paused
                            Log.d(TAG, "Playback playing - resuming audio and acquiring playback locks")
                            syncAudioPlayer?.resume()
                            sendSpinPlayer?.setSyncAudioPlayer(syncAudioPlayer)
                            acquirePlaybackLocks()
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

                // Broadcast all state including group name to controllers (MainActivity)
                broadcastSessionExtras()
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

                // Update the player's position so MediaSession reports it
                // to Android Auto, Bluetooth (AVRCP), and lock screen
                sendSpinPlayer?.updatePlaybackState(
                    syncState = null,
                    positionMs = positionMs,
                    durationMs = durationMs
                )

                // Populate the player's timeline with queue items for native queue UI
                populatePlayerQueue()

                // Handle artwork URL changes
                if (artworkUrl.isEmpty()) {
                    lastArtworkUrl = null
                } else if (artworkUrl != lastArtworkUrl) {
                    lastArtworkUrl = artworkUrl
                    fetchArtwork(artworkUrl)
                }

                // Keep existing artwork as a bridge until new artwork arrives.
                // Clearing eagerly causes a race: MediaSession gets updated with no
                // artwork, Android Auto caches that state (grey box), then the binary
                // artwork arrives too late. The old artwork will be replaced as soon
                // as onArtwork() or fetchArtwork() delivers the new one.
                updateMediaMetadata(title, artist, album)
            }
        }

        override fun onArtwork(imageData: ByteArray) {
            // Skip artwork processing in low memory mode
            if (com.sendspindroid.UserSettings.lowMemoryMode) {
                return
            }

            mainHandler.post {
                Log.d(TAG, "Artwork received: ${imageData.size} bytes")
                try {
                    val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                    if (bitmap != null) {
                        val scaled = scaleArtwork(bitmap)
                        currentArtwork = scaled
                        updateMediaSessionArtwork(scaled)
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

                // Show error on Android Auto
                sendSpinPlayer?.setError(message)

                // Broadcast error to controllers (MainActivity)
                broadcastConnectionState(STATE_ERROR, errorMessage = message)
            }
        }

        override fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) {
            // Mark decoder as not ready IMMEDIATELY (on WebSocket thread) before posting.
            // This prevents onAudioChunk from using the old (about-to-be-released) decoder.
            decoderReady = false
            mainHandler.post {
                Log.d(TAG, "Stream started: codec=$codec, rate=$sampleRate, channels=$channels, bits=$bitDepth, header=${codecHeader?.size ?: 0} bytes")
                currentCodec = codec

                // Stop existing player if any
                syncAudioPlayer?.release()

                // Release existing decoder and create new one for this stream
                audioDecoder?.release()
                audioDecoder = null
                try {
                    audioDecoder = AudioDecoderFactory.create(codec)
                    audioDecoder?.configure(sampleRate, channels, bitDepth, codecHeader)
                    Log.i(TAG, "Audio decoder created: $codec")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create decoder for $codec, falling back to PCM", e)
                    audioDecoder = AudioDecoderFactory.create("pcm")
                }
                // Signal that the new decoder is ready for use by onAudioChunk
                decoderReady = true

                // Get the time filter from SendSpinClient
                val timeFilter = sendSpinClient?.getTimeFilter()
                if (timeFilter == null) {
                    Log.e(TAG, "Cannot start audio: time filter not available")
                    return@post
                }

                // Acquire playback locks (CPU/WiFi) to prevent sleep during streaming
                // The foreground service is already running (started on connect)
                acquirePlaybackLocks()

                // Update notification to show we're now streaming
                startForegroundServiceWithNotification()

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
                Log.d(TAG, "Stream clear - flushing audio and decoder buffers")
                audioDecoder?.flush()
                syncAudioPlayer?.clearBuffer()
            }
        }

        override fun onStreamEnd() {
            mainHandler.post {
                Log.i(TAG, "Stream end - server terminated playback")
                // Stop the audio player gracefully when server ends the stream
                syncAudioPlayer?.stop()
            }
        }

        override fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray) {
            // Guard: don't try to decode if the decoder is being replaced (race with onStreamStart)
            if (!decoderReady) return

            // Decode compressed data to PCM (pass-through for PCM codec)
            val pcmData = try {
                audioDecoder?.decode(audioData) ?: audioData
            } catch (e: Exception) {
                Log.e(TAG, "Decode error, dropping chunk", e)
                return
            }
            // Queue decoded PCM - SyncAudioPlayer handles threading internally
            syncAudioPlayer?.queueChunk(serverTimeMicros, pcmData)
        }

        override fun onVolumeChanged(volume: Int) {
            mainHandler.post {
                // Convert from 0-100 to 0.0-1.0 and apply to device volume
                val volumeFloat = volume / 100f
                setVolume(volumeFloat)  // Sets device STREAM_MUSIC volume
                // Update playback state with new volume
                _playbackState.value = _playbackState.value.copy(volume = volume)
                // Broadcast all state including volume to UI controllers
                broadcastSessionExtras()
            }
        }

        override fun onMutedChanged(muted: Boolean) {
            mainHandler.post {
                // Apply mute by setting volume to 0, or restore previous volume
                val currentState = _playbackState.value
                if (muted) {
                    setVolume(0f)
                } else {
                    // Restore volume from state
                    setVolume(currentState.volume / 100f)
                }
                // Update playback state with new mute status
                _playbackState.value = currentState.copy(muted = muted)
                // Broadcast all state including mute to UI controllers
                broadcastSessionExtras()
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

        override fun onNetworkChanged() {
            mainHandler.post {
                // Only clear buffer if NOT in DRAINING state
                // During reconnection, keep playing from buffer for continuity
                val player = syncAudioPlayer
                if (player != null && player.getPlaybackState() == SyncPlaybackState.DRAINING) {
                    android.util.Log.i(TAG, "Network changed during reconnection - preserving buffer")
                } else {
                    android.util.Log.i(TAG, "Network changed - triggering audio player reanchor")
                    player?.clearBuffer()
                }
            }
        }

        override fun onReconnecting(attempt: Int, serverName: String) {
            android.util.Log.i(TAG, "Reconnecting to $serverName (attempt $attempt) - entering DRAINING mode")
            // Enter draining mode SYNCHRONOUSLY before any mainHandler posts
            // This ensures disconnect handlers see DRAINING state when they check
            // DRAINING state allows playback to continue from buffer during reconnection
            syncAudioPlayer?.enterDraining()
            mainHandler.post {

                // Update connection state for UI (shows "Reconnecting..." indicator)
                _connectionState.value = ConnectionState.Reconnecting(serverName, attempt)

                // Broadcast to UI
                broadcastConnectionState(STATE_RECONNECTING, serverName)
            }
        }

        override fun onReconnected() {
            android.util.Log.i(TAG, "Reconnected successfully - exiting DRAINING mode")
            mainHandler.post {
                // Exit draining mode - new stream will arrive shortly
                syncAudioPlayer?.exitDraining()

                // Connection state will be updated by onConnected() callback which follows
            }
        }
    }

    /**
     * Fetches artwork from a URL using Coil.
     * Skipped in low memory mode.
     */
    private fun fetchArtwork(url: String) {
        // Skip artwork loading in low memory mode
        if (com.sendspindroid.UserSettings.lowMemoryMode) {
            return
        }

        val loader = imageLoader ?: return

        if (!isValidArtworkUrl(url)) {
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(this@PlaybackService)
                    .data(url)
                    .build()

                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = result.drawable.toBitmap()
                    val scaled = scaleArtwork(bitmap)
                    mainHandler.post {
                        currentArtwork = scaled
                        updateMediaSessionArtwork(scaled)
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

    /**
     * Pre-scales artwork bitmaps to fit within the MediaMetadata max size.
     *
     * The Android framework enforces a max of 320dp for MediaMetadata bitmaps
     * (config_mediaMetadataBitmapMaxSize). Oversized bitmaps get silently
     * downscaled and trigger StrictMode violations on Android 16+.
     * Pre-scaling avoids this overhead and keeps memory usage predictable.
     */
    private fun scaleArtwork(bitmap: Bitmap, maxSize: Int = MAX_ARTWORK_SIZE): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxSize && height <= maxSize) return bitmap

        val scale = maxSize.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun updateMediaSessionArtwork(bitmap: Bitmap) {
        val state = _playbackState.value
        val scaled = scaleArtwork(bitmap)

        forwardingPlayer?.updateMetadata(
            title = state.title,
            artist = state.artist,
            album = state.album,
            artwork = scaled,
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
        // Use unified broadcast to avoid overwriting other extras
        broadcastSessionExtras()
    }

    /**
     * Broadcasts all session state to MediaControllers via session extras.
     *
     * This unified method ensures all state (connection, metadata, group, volume)
     * is broadcast together, preventing individual setSessionExtras calls from
     * overwriting each other.
     *
     * Call this method whenever any state changes that needs to be reflected in the UI.
     */
    private fun broadcastSessionExtras() {
        val playbackState = _playbackState.value
        val connState = _connectionState.value

        val extras = Bundle().apply {
            // Connection state
            when (connState) {
                is ConnectionState.Disconnected -> {
                    putString(EXTRA_CONNECTION_STATE, STATE_DISCONNECTED)
                    putBoolean(EXTRA_WAS_USER_INITIATED, connState.wasUserInitiated)
                    putBoolean(EXTRA_WAS_RECONNECT_EXHAUSTED, connState.wasReconnectExhausted)
                }
                is ConnectionState.Connecting -> putString(EXTRA_CONNECTION_STATE, STATE_CONNECTING)
                is ConnectionState.Connected -> {
                    putString(EXTRA_CONNECTION_STATE, STATE_CONNECTED)
                    putString(EXTRA_SERVER_NAME, connState.serverName)
                }
                is ConnectionState.Reconnecting -> {
                    putString(EXTRA_CONNECTION_STATE, STATE_RECONNECTING)
                    putString(EXTRA_SERVER_NAME, connState.serverName)
                    putInt("reconnect_attempt", connState.attempt)
                    // Include buffer info if available
                    syncAudioPlayer?.getBufferedDurationMs()?.let {
                        putLong("buffer_remaining_ms", it)
                    }
                }
                is ConnectionState.Error -> {
                    putString(EXTRA_CONNECTION_STATE, STATE_ERROR)
                    putString(EXTRA_ERROR_MESSAGE, connState.message)
                }
            }

            // Metadata
            putString(EXTRA_TITLE, playbackState.title ?: "")
            putString(EXTRA_ARTIST, playbackState.artist ?: "")
            putString(EXTRA_ALBUM, playbackState.album ?: "")
            putString(EXTRA_ARTWORK_URL, playbackState.artworkUrl ?: "")
            putLong(EXTRA_DURATION_MS, playbackState.durationMs)
            putLong(EXTRA_POSITION_MS, playbackState.positionMs)

            // Group info
            playbackState.groupName?.let { putString(EXTRA_GROUP_NAME, it) }

            // Volume
            putInt(EXTRA_VOLUME, playbackState.volume)
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

            // Read current device volume and set as initial volume for server and UI
            val am = audioManager
            if (am != null) {
                val currentDeviceVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val volumePercent = ((currentDeviceVolume.toFloat() / maxVolume) * 100).toInt()
                Log.d(TAG, "Setting initial volume from device: $currentDeviceVolume/$maxVolume = $volumePercent%")
                sendSpinClient?.setInitialVolume(volumePercent)
                // Also update playback state so UI shows correct volume from the start
                _playbackState.value = _playbackState.value.copy(volume = volumePercent)
            }

            sendSpinClient?.connect(address, path)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to server", e)
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
            broadcastConnectionState(STATE_ERROR, errorMessage = "Connection failed: ${e.message}")
        }
    }

    /**
     * Connects to a SendSpin server via Music Assistant Remote Access.
     *
     * @param remoteId The 26-character Remote ID from Music Assistant settings
     */
    fun connectToRemoteServer(remoteId: String) {
        Log.d(TAG, "Connecting to remote server via Remote ID: $remoteId")
        _connectionState.value = ConnectionState.Connecting

        // Broadcast connecting state to controllers (MainActivity)
        broadcastConnectionState(STATE_CONNECTING)

        try {
            if (sendSpinClient?.isConnected == true) {
                Log.d(TAG, "Already connected, disconnecting first...")
                sendSpinClient?.disconnect()
            }

            // Read current device volume and set as initial volume for server and UI
            val am = audioManager
            if (am != null) {
                val currentDeviceVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val volumePercent = ((currentDeviceVolume.toFloat() / maxVolume) * 100).toInt()
                Log.d(TAG, "Setting initial volume from device: $currentDeviceVolume/$maxVolume = $volumePercent%")
                sendSpinClient?.setInitialVolume(volumePercent)
                _playbackState.value = _playbackState.value.copy(volume = volumePercent)
            }

            sendSpinClient?.connectRemote(remoteId)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to remote server", e)
            _connectionState.value = ConnectionState.Error("Remote connection failed: ${e.message}")
            broadcastConnectionState(STATE_ERROR, errorMessage = "Remote connection failed: ${e.message}")
        }
    }

    /**
     * Connect to a SendSpin server via authenticated reverse proxy.
     *
     * This is for users who have Music Assistant exposed through Nginx Proxy Manager,
     * Traefik, Caddy, or similar reverse proxies with token authentication.
     *
     * @param url The proxy URL (e.g., "https://ma.example.com/sendspin")
     * @param authToken The long-lived authentication token from Music Assistant
     */
    fun connectToProxyServer(url: String, authToken: String) {
        Log.d(TAG, "Connecting to proxy server: $url")
        _connectionState.value = ConnectionState.Connecting

        // Broadcast connecting state to controllers (MainActivity)
        broadcastConnectionState(STATE_CONNECTING)

        try {
            if (sendSpinClient?.isConnected == true) {
                Log.d(TAG, "Already connected, disconnecting first...")
                sendSpinClient?.disconnect()
            }

            // Read current device volume and set as initial volume for server and UI
            val am = audioManager
            if (am != null) {
                val currentDeviceVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val volumePercent = ((currentDeviceVolume.toFloat() / maxVolume) * 100).toInt()
                Log.d(TAG, "Setting initial volume from device: $currentDeviceVolume/$maxVolume = $volumePercent%")
                sendSpinClient?.setInitialVolume(volumePercent)
                _playbackState.value = _playbackState.value.copy(volume = volumePercent)
            }

            sendSpinClient?.connectProxy(url, authToken)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to proxy server", e)
            _connectionState.value = ConnectionState.Error("Proxy connection failed: ${e.message}")
            broadcastConnectionState(STATE_ERROR, errorMessage = "Proxy connection failed: ${e.message}")
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
        // Use unified broadcast to avoid overwriting other extras
        broadcastSessionExtras()
    }

    /**
     * Disconnects from the current server.
     */
    fun disconnectFromServer() {
        Log.d(TAG, "Disconnecting from server")
        sendSpinClient?.disconnect()
    }

    /**
     * Sets the current server ID for MA integration.
     * Call this before connecting when the server ID is known.
     *
     * @param serverId The UnifiedServer.id
     * @param connectionMode The connection mode being used
     */
    fun setCurrentServer(serverId: String?, connectionMode: ConnectionMode) {
        currentServerId = serverId
        currentConnectionMode = connectionMode
        Log.d(TAG, "Set current server: $serverId, mode=$connectionMode")
    }

    /**
     * Notifies MusicAssistantManager that a server connection was established.
     * Looks up the server by ID and triggers MA availability check.
     */
    private fun notifyMusicAssistantConnected() {
        val serverId = currentServerId
        if (serverId == null) {
            Log.d(TAG, "No server ID set - skipping MA notification")
            return
        }

        val server = UnifiedServerRepository.getServer(serverId)
        if (server == null) {
            Log.w(TAG, "Server not found in repository: $serverId")
            return
        }

        Log.d(TAG, "Notifying MusicAssistantManager: server=${server.name}, isMusicAssistant=${server.isMusicAssistant}")
        MusicAssistantManager.onServerConnected(server, currentConnectionMode)
    }

    /**
     * Sets the playback volume via device STREAM_MUSIC (Spotify-style).
     *
     * Volume is controlled via the device's media stream, not per-app gain.
     * This enables hardware volume button support and follows best practices
     * used by Spotify, Plexamp, and other major media apps.
     *
     * @param volume Normalized volume from 0.0 (mute) to 1.0 (full)
     */
    fun setVolume(volume: Float) {
        val am = audioManager ?: return
        val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val newVolume = (volume * maxVolume).toInt().coerceIn(0, maxVolume)

        Log.d(TAG, "Setting device volume: $newVolume/$maxVolume (normalized: $volume)")

        // Update tracking to prevent echo in observer
        lastKnownVolume = newVolume

        // Set device volume (no flags = silent, no UI popup)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
    }

    /**
     * Acquires wake lock and WiFi lock to keep CPU and WiFi running during audio playback.
     * This prevents the system from putting CPU/WiFi to sleep while streaming.
     *
     * NOTE: This does NOT start the foreground service - that's done separately in onConnected()
     * via startForegroundServiceWithNotification(). The foreground service protects the process
     * from being killed, while these locks protect against CPU/WiFi sleep during active streaming.
     *
     * Wake lock strategy for battery safety:
     * - Uses a 30-minute timeout instead of indefinite or very long timeout
     * - Refreshes the wake lock every 20 minutes during active playback
     * - If the app crashes without releasing, max battery drain is limited to 30 minutes
     * - The refresh mechanism ensures continuous playback isn't interrupted
     */
    @Suppress("DEPRECATION")
    private fun acquirePlaybackLocks() {
        // Request audio focus first - required for Android Auto to route audio to us
        requestAudioFocus()

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
     * Checks if we are currently connected to a server.
     */
    private fun isConnected(): Boolean {
        return _connectionState.value is ConnectionState.Connected
    }

    /**
     * Logs the current stats to DebugLogger if enabled.
     * Called periodically when debug mode is active.
     */
    private fun logCurrentStats() {
        val audioStats = syncAudioPlayer?.getStats() ?: return
        val timeFilter = sendSpinClient?.getTimeFilter() ?: return

        val syncStats = SyncStats(
            playbackState = audioStats.playbackState,
            isPlaying = audioStats.isPlaying,
            syncErrorUs = audioStats.syncErrorUs,
            smoothedSyncErrorUs = audioStats.smoothedSyncErrorUs,
            startTimeCalibrated = audioStats.startTimeCalibrated,
            samplesReadSinceStart = audioStats.samplesReadSinceStart,
            queuedSamples = audioStats.queuedSamples,
            chunksReceived = audioStats.chunksReceived,
            chunksPlayed = audioStats.chunksPlayed,
            chunksDropped = audioStats.chunksDropped,
            gapsFilled = audioStats.gapsFilled,
            gapSilenceMs = audioStats.gapSilenceMs,
            overlapsTrimmed = audioStats.overlapsTrimmed,
            overlapTrimmedMs = audioStats.overlapTrimmedMs,
            insertEveryNFrames = audioStats.insertEveryNFrames,
            dropEveryNFrames = audioStats.dropEveryNFrames,
            framesInserted = audioStats.framesInserted,
            framesDropped = audioStats.framesDropped,
            syncCorrections = audioStats.syncCorrections,
            clockReady = timeFilter.isReady,
            clockConverged = timeFilter.isConverged,
            clockOffsetUs = timeFilter.offsetMicros,
            clockDriftPpm = timeFilter.driftPpm,
            clockErrorUs = timeFilter.errorMicros,
            measurementCount = timeFilter.measurementCountValue,
            totalFramesWritten = audioStats.totalFramesWritten,
            serverTimelineCursorUs = audioStats.serverTimelineCursorUs,
            scheduledStartLoopTimeUs = audioStats.scheduledStartLoopTimeUs,
            firstServerTimestampUs = audioStats.firstServerTimestampUs,
            convergenceTimeMs = timeFilter.convergenceTimeMillis,
            stabilityScore = timeFilter.stability
        )

        DebugLogger.logStats(syncStats)
    }

    /**
     * Starts the debug logging loop if debug mode is enabled.
     */
    private fun startDebugLogging() {
        if (DebugLogger.isEnabled) {
            debugLogHandler.removeCallbacks(debugLogRunnable)
            debugLogHandler.postDelayed(debugLogRunnable, DEBUG_LOG_INTERVAL_MS)
            Log.d(TAG, "Debug logging started")
        }
    }

    /**
     * Stops the debug logging loop.
     */
    private fun stopDebugLogging() {
        debugLogHandler.removeCallbacks(debugLogRunnable)
        Log.d(TAG, "Debug logging stopped")
    }

    /**
     * Starts the service in foreground mode with a notification.
     * This is required on Android 8+ to keep the service alive when the app is in the background.
     * On Android 14+, we must specify the foreground service type.
     *
     * @param serverName Optional server name for context-aware notification text.
     *                   If provided, shows "Connected to [serverName]".
     *                   If null, shows "Streaming audio..." (during active playback).
     */
    private fun startForegroundServiceWithNotification(serverName: String? = null) {
        try {
            val contentText = if (serverName != null) {
                "Connected to $serverName"
            } else {
                "Streaming audio..."
            }

            val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
                .setContentTitle("SendSpin")
                .setContentText(contentText)
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
            Log.d(TAG, "Foreground service started with text: $contentText")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    /**
     * Releases wake lock and WiFi lock when playback stops.
     * Cancels the wake lock refresh handler.
     *
     * NOTE: This does NOT stop the foreground service notification - that's done separately
     * in stopForegroundNotification(). This allows the service to stay alive for reconnection
     * while not draining battery with CPU/WiFi locks during idle periods.
     */
    private fun releasePlaybackLocks() {
        // Abandon audio focus - tells the system we're done producing audio
        abandonAudioFocus()

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
    }

    /**
     * Requests audio focus for music playback.
     *
     * This is required for Android Auto (and car Bluetooth) to hand over the audio
     * output channel from whatever is currently playing (FM radio, other apps, etc.).
     * Without this call, the car infotainment system doesn't know our app wants to
     * produce audio and won't route output to us.
     */
    private fun requestAudioFocus() {
        if (hasAudioFocus) return

        val am = audioManager ?: return

        if (audioFocusRequest == null) {
            val audioAttributes = AndroidAudioAttributes.Builder()
                .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    mainHandler.post {
                        handleAudioFocusChange(focusChange)
                    }
                }
                .build()
        }

        val result = am.requestAudioFocus(audioFocusRequest!!)
        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        Log.d(TAG, "Audio focus requested: ${if (hasAudioFocus) "granted" else "denied"}")
    }

    /**
     * Abandons audio focus when playback stops.
     */
    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return

        audioFocusRequest?.let { request ->
            audioManager?.abandonAudioFocusRequest(request)
            Log.d(TAG, "Audio focus abandoned")
        }
        hasAudioFocus = false
    }

    /**
     * Handles audio focus changes from the system.
     *
     * On Android Auto, focus loss typically means another app (navigation, phone call)
     * needs audio. We pause/duck accordingly and resume when focus returns.
     */
    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                // Focus returned - resume if we were playing before losing focus
                syncAudioPlayer?.resume()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio focus lost permanently")
                // Another app took focus permanently - pause playback
                hasAudioFocus = false
                syncAudioPlayer?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost transiently")
                // Temporary loss (phone call, navigation announcement) - pause
                syncAudioPlayer?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus lost transiently (can duck)")
                // Could duck volume here, but for synced playback just let it play
                // since ducking would desync volume with other clients
            }
        }
    }

    /**
     * Acquires High Power Mode locks (WiFi + CPU) for the entire connection lifetime.
     * These are separate from streaming locks and use low-latency WiFi mode.
     * Called when High Power Mode is enabled and the client is connected.
     */
    @Suppress("DEPRECATION")
    private fun acquireHighPowerLocks() {
        // WiFi lock with low-latency mode (API 29+) for faster ping detection
        if (highPowerWifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            highPowerWifiLock = wifiManager.createWifiLock(wifiMode, "SendSpinDroid::HighPower")
        }
        if (highPowerWifiLock?.isHeld == false) {
            highPowerWifiLock?.acquire()
            Log.d(TAG, "High Power WiFi lock acquired")
        }

        // CPU wake lock to prevent deep sleep between tracks
        if (highPowerWakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            highPowerWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SendSpinDroid::HighPower"
            )
        }
        if (highPowerWakeLock?.isHeld == false) {
            highPowerWakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "High Power wake lock acquired with ${WAKE_LOCK_TIMEOUT_MS / 60000}min timeout")

            // Start periodic refresh to keep wake lock alive indefinitely
            wakeLockHandler.removeCallbacks(highPowerWakeLockRefreshRunnable)
            wakeLockHandler.postDelayed(highPowerWakeLockRefreshRunnable, WAKE_LOCK_REFRESH_INTERVAL_MS)
        }
    }

    /**
     * Releases High Power Mode locks.
     * Called on disconnect or when High Power Mode is disabled.
     */
    private fun releaseHighPowerLocks() {
        wakeLockHandler.removeCallbacks(highPowerWakeLockRefreshRunnable)
        if (highPowerWakeLock?.isHeld == true) {
            highPowerWakeLock?.release()
            Log.d(TAG, "High Power wake lock released")
        }
        if (highPowerWifiLock?.isHeld == true) {
            highPowerWifiLock?.release()
            Log.d(TAG, "High Power WiFi lock released")
        }
    }

    /**
     * Called when High Power Mode is toggled in settings.
     * Acquires or releases locks based on the new state and current connection.
     */
    private fun onHighPowerModeChanged(enabled: Boolean) {
        if (enabled && isConnected()) {
            acquireHighPowerLocks()
        } else {
            releaseHighPowerLocks()
        }
    }

    /**
     * Stops the foreground service notification.
     * Called when fully disconnecting from a server.
     */
    private fun stopForegroundNotification() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            Log.d(TAG, "Foreground notification removed")
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

        // Create PendingIntent for notification tap - opens MainActivity
        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaLibrarySession.Builder(this, forwardingPlayer!!, LibraryCallback())
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        Log.d(TAG, "MediaLibrarySession initialized with browse tree support")

        // Watch MA connection state to refresh browse tree root when Library appears/disappears
        serviceScope.launch {
            var wasAvailable = MusicAssistantManager.connectionState.value.isAvailable
            MusicAssistantManager.connectionState.collect { state ->
                val isNowAvailable = state.isAvailable
                if (isNowAvailable != wasAvailable) {
                    Log.i(TAG, "MA availability changed: $wasAvailable -> $isNowAvailable")
                    wasAvailable = isNowAvailable
                    if (!isNowAvailable) {
                        clearMaCaches()
                    }
                    // Notify that root children changed (Library folder appears/disappears)
                    mediaSession?.notifyChildrenChanged(MEDIA_ID_ROOT, 0, null)
                }
            }
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Log incoming root hints for debugging Android Auto behavior
            val rootHints = params?.extras
            val tabLimit = rootHints?.getInt(
                "androidx.media.MediaBrowserServiceCompat.BrowserRoot.Extras.KEY_ROOT_CHILDREN_LIMIT", -1
            ) ?: -1
            Log.i(TAG, "onGetLibraryRoot called by: ${browser.packageName}" +
                    " (uid=${browser.uid})" +
                    ", tabLimit=$tabLimit, params=$params")

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

            // Build LibraryParams with search support and content style defaults
            val extras = Bundle().apply {
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                putInt(CONTENT_STYLE_BROWSABLE, CONTENT_STYLE_LIST)
                putInt(CONTENT_STYLE_PLAYABLE, CONTENT_STYLE_LIST)
            }
            val libraryParams = LibraryParams.Builder().setExtras(extras).build()

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, libraryParams))
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

            // Sync path: existing browse tree nodes
            val syncChildren: List<MediaItem>? = when (parentId) {
                MEDIA_ID_ROOT -> getRootChildren()
                MEDIA_ID_DISCOVERED -> getDiscoveredServers()
                else -> null  // Not a sync node, check async path
            }

            if (syncChildren != null) {
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(syncChildren), params)
                )
            }

            // Async path: MA data fetches
            return suspendToFuture {
                val items = getMaChildren(parentId)
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
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

            // Voice search (VC-1): "OK Google, play X on SendSpinDroid"
            // arrives with requestMetadata.searchQuery set
            val searchQuery = mediaItems.firstOrNull()?.requestMetadata?.searchQuery
            if (searchQuery != null) {
                Log.d(TAG, "Voice search detected: query='$searchQuery'")
                return handleVoiceSearch(searchQuery, mediaItems)
            }

            val updatedItems = mediaItems.map { item ->
                val mediaId = item.mediaId

                when {
                    // Saved server (looked up by UUID, auto-selects connection method)
                    mediaId.startsWith(MEDIA_ID_SAVED_SERVER_PREFIX) -> {
                        val serverId = mediaId.removePrefix(MEDIA_ID_SAVED_SERVER_PREFIX)
                        Log.d(TAG, "User selected saved server: $serverId")

                        val server = UnifiedServerRepository.savedServers.value
                            .find { it.id == serverId }
                        if (server != null) {
                            // Use ConnectionSelector to pick the best method
                            networkEvaluator?.evaluateCurrentNetwork()
                            val netState = networkEvaluator?.networkState?.value
                                ?: NetworkState(TransportType.UNKNOWN)
                            val selected = ConnectionSelector.selectConnection(server, netState)

                            when (selected) {
                                is ConnectionSelector.SelectedConnection.Local -> {
                                    setCurrentServer(server.id, ConnectionMode.LOCAL)
                                    connectToServer(selected.address, selected.path)
                                }
                                is ConnectionSelector.SelectedConnection.Remote -> {
                                    setCurrentServer(server.id, ConnectionMode.REMOTE)
                                    connectToRemoteServer(selected.remoteId)
                                }
                                is ConnectionSelector.SelectedConnection.Proxy -> {
                                    setCurrentServer(server.id, ConnectionMode.PROXY)
                                    connectToProxyServer(selected.url, selected.authToken)
                                }
                                null -> {
                                    Log.w(TAG, "No connection method for saved server: ${server.name}")
                                }
                            }
                            UnifiedServerRepository.updateLastConnected(server.id)
                        } else {
                            Log.w(TAG, "Saved server not found: $serverId")
                        }

                        item.buildUpon()
                            .setUri("sendspin://saved/$serverId")
                            .build()
                    }

                    // Discovered server (by address, local connection only)
                    mediaId.startsWith(MEDIA_ID_SERVER_PREFIX) -> {
                        val serverAddress = mediaId.removePrefix(MEDIA_ID_SERVER_PREFIX)
                        Log.d(TAG, "User selected server: $serverAddress")

                        // Look up UnifiedServer by local address for MA integration
                        val unifiedServer = UnifiedServerRepository.allServers.value.find {
                            it.local?.address == serverAddress
                        }
                        if (unifiedServer != null) {
                            setCurrentServer(unifiedServer.id, ConnectionMode.LOCAL)
                        } else {
                            Log.w(TAG, "No UnifiedServer found for address: $serverAddress")
                        }

                        connectToServer(serverAddress)

                        val server = ServerRepository.getServer(serverAddress)
                        if (server != null) {
                            ServerRepository.addToRecent(server)
                        }

                        item.buildUpon()
                            .setUri("sendspin://$serverAddress")
                            .build()
                    }
                    // MA media items (tracks, playlists, albums, radio)
                    mediaId.startsWith("ma_") -> {
                        Log.d(TAG, "MA media item selected: $mediaId")
                        handleMaMediaItem(mediaId)
                    }
                    else -> item
                }
            }

            return Futures.immediateFuture(updatedItems)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            Log.d(TAG, "onSearch: query='$query'")

            if (!MusicAssistantManager.connectionState.value.isAvailable) {
                return Futures.immediateFuture(
                    LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
                )
            }

            // Execute search async, cache results, notify when done
            serviceScope.launch {
                try {
                    val result = MusicAssistantManager.search(
                        query = query,
                        limit = 25,
                        libraryOnly = false
                    )
                    val searchResults = result.getOrNull()
                    if (searchResults != null) {
                        // Build flat list grouped by type (contiguous blocks with group titles)
                        val items = mutableListOf<MediaItem>()
                        searchResults.tracks.forEach { items.add(withGroupTitle(createMaTrackItem(it), "Songs")) }
                        searchResults.albums.forEach { items.add(withGroupTitle(createMaAlbumItem(it), "Albums")) }
                        searchResults.artists.forEach { items.add(withGroupTitle(createMaArtistItem(it), "Artists")) }
                        searchResults.playlists.forEach { items.add(withGroupTitle(createMaPlaylistItem(it), "Playlists")) }
                        searchResults.radios.forEach { items.add(withGroupTitle(createMaRadioItem(it), "Radio")) }
                        maSearchResultsCache = items.filter { it != MediaItem.EMPTY }
                        Log.d(TAG, "Search returned ${maSearchResultsCache?.size} results")
                    } else {
                        maSearchResultsCache = emptyList()
                        Log.d(TAG, "Search returned no results")
                    }
                    // Notify browser that search results are ready
                    session.notifySearchResultChanged(browser, query,
                        maSearchResultsCache?.size ?: 0, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Search failed", e)
                    maSearchResultsCache = emptyList()
                    session.notifySearchResultChanged(browser, query, 0, params)
                }
            }

            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.d(TAG, "onGetSearchResult: query='$query', page=$page, pageSize=$pageSize")

            val results = maSearchResultsCache ?: emptyList()
            // Paginate
            val startIndex = page * pageSize
            val endIndex = minOf(startIndex + pageSize, results.size)
            val pageResults = if (startIndex < results.size) {
                results.subList(startIndex, endIndex)
            } else {
                emptyList()
            }

            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(pageResults), params)
            )
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.i(TAG, "Controller connecting: ${controller.packageName}" +
                    " (uid=${controller.uid})" +
                    ", session=${session.id}")

            // Must use DEFAULT_SESSION_AND_LIBRARY_COMMANDS (not DEFAULT_SESSION_COMMANDS)
            // so that the legacy MediaBrowserServiceCompat compat bridge includes browse/root
            // commands needed by Android Auto and other MediaBrowserCompat clients.
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_CONNECT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_CONNECT_REMOTE, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_CONNECT_PROXY, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_DISCONNECT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_VOLUME, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_NEXT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_PREVIOUS, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SWITCH_GROUP, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_GET_STATS, Bundle.EMPTY))
                .build()

            // Player commands must include SET_MEDIA_ITEM so the legacy compat bridge
            // can translate playFromMediaId -> onAddMediaItems for Android Auto.
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM)
                .add(androidx.media3.common.Player.COMMAND_PREPARE)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            Log.i(TAG, "Controller disconnected: ${controller.packageName} (uid=${controller.uid})")
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
                    val serverId = args.getString(ARG_SERVER_ID)
                    if (address != null) {
                        // Set server info for MA integration before connecting
                        setCurrentServer(serverId, ConnectionMode.LOCAL)
                        connectToServer(address, path)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Log.e(TAG, "CONNECT command missing server_address")
                        Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                }

                COMMAND_CONNECT_REMOTE -> {
                    val remoteId = args.getString(ARG_REMOTE_ID)
                    val serverId = args.getString(ARG_SERVER_ID)
                    if (remoteId != null) {
                        // Set server info for MA integration before connecting
                        setCurrentServer(serverId, ConnectionMode.REMOTE)
                        connectToRemoteServer(remoteId)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Log.e(TAG, "CONNECT_REMOTE command missing remote_id")
                        Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                }

                COMMAND_CONNECT_PROXY -> {
                    val url = args.getString(ARG_PROXY_URL)
                    val token = args.getString(ARG_AUTH_TOKEN)
                    val serverId = args.getString(ARG_SERVER_ID)
                    if (url != null && token != null) {
                        // Set server info for MA integration before connecting
                        setCurrentServer(serverId, ConnectionMode.PROXY)
                        connectToProxyServer(url, token)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Log.e(TAG, "CONNECT_PROXY command missing proxy_url or auth_token")
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

                COMMAND_SWITCH_GROUP -> {
                    Log.d(TAG, "Switch group command received")
                    sendSpinClient?.switchGroup()
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

        // Get connection info from SendSpinClient
        sendSpinClient?.let { client ->
            bundle.putString("server_name", client.getServerName())
            bundle.putString("server_address", client.getServerAddress())
            bundle.putString("connection_state", client.connectionState.value.toString())
            bundle.putString("audio_codec", currentCodec.uppercase())
        } ?: run {
            bundle.putString("connection_state", "Disconnected")
            bundle.putString("audio_codec", "--")
        }

        // Get stats from SyncAudioPlayer
        val audioStats = syncAudioPlayer?.getStats()
        if (audioStats != null) {
            // Playback state
            bundle.putString("playback_state", audioStats.playbackState.name)
            bundle.putBoolean("is_playing", audioStats.isPlaying)

            // Sync error
            bundle.putLong("sync_error_us", audioStats.syncErrorUs)
            bundle.putLong("smoothed_sync_error_us", audioStats.smoothedSyncErrorUs)
            bundle.putDouble("sync_error_drift", audioStats.syncErrorDrift)
            bundle.putLong("grace_period_remaining_us", audioStats.gracePeriodRemainingUs)

            // DAC/Audio
            bundle.putBoolean("start_time_calibrated", audioStats.startTimeCalibrated)
            bundle.putInt("dac_calibration_count", audioStats.dacCalibrationCount)
            bundle.putBoolean("dac_timestamps_stable", audioStats.dacTimestampsStable)
            bundle.putLong("samples_read_since_start", audioStats.samplesReadSinceStart)
            bundle.putLong("total_frames_written", audioStats.totalFramesWritten)
            bundle.putLong("buffer_underrun_count", audioStats.bufferUnderrunCount)

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
            bundle.putLong("reanchor_count", audioStats.reanchorCount)

            // Playback tracking
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
            bundle.putBoolean("clock_converged", timeFilter.isConverged)
            bundle.putLong("clock_offset_us", timeFilter.offsetMicros)
            bundle.putDouble("clock_drift_ppm", timeFilter.driftPpm)
            bundle.putLong("clock_error_us", timeFilter.errorMicros)
            bundle.putInt("measurement_count", timeFilter.measurementCountValue)
            bundle.putLong("last_time_sync_age_ms", client.getLastTimeSyncAgeMs())
            bundle.putInt("reconnect_attempts", client.getReconnectAttempts())
            bundle.putBoolean("clock_frozen", timeFilter.isFrozen)
            bundle.putDouble("static_delay_ms", timeFilter.staticDelayMs)
        }

        // Get network stats from NetworkEvaluator
        networkEvaluator?.networkState?.value?.let { netState ->
            bundle.putString("network_type", netState.transportType.name)
            bundle.putString("network_quality", netState.quality.name)
            bundle.putBoolean("network_metered", netState.isMetered)
            bundle.putBoolean("network_connected", netState.isConnected)
            netState.wifiRssi?.let { bundle.putInt("wifi_rssi", it) }
            netState.wifiLinkSpeedMbps?.let { bundle.putInt("wifi_link_speed", it) }
            netState.wifiFrequencyMhz?.let { bundle.putInt("wifi_frequency", it) }
            netState.cellularType?.let { bundle.putString("cellular_type", it.name) }
            netState.downstreamBandwidthKbps?.let { bundle.putInt("bandwidth_down_kbps", it) }
        }

        return bundle
    }

    /**
     * Applies the manual sync offset from UserSettings to the TimeFilter.
     * Called when connecting to a server to apply the saved offset.
     */
    private fun applySyncOffsetFromSettings() {
        val offsetMs = com.sendspindroid.UserSettings.getSyncOffsetMs()
        if (offsetMs != 0) {
            sendSpinClient?.getTimeFilter()?.let { timeFilter ->
                timeFilter.staticDelayMs = offsetMs.toDouble()
                Log.i(TAG, "Applied manual sync offset from settings: ${offsetMs}ms")
            }
        }
    }

    /**
     * Updates the sync offset and applies it immediately if connected.
     * Called when the user changes the offset in settings.
     */
    fun updateSyncOffset(offsetMs: Int) {
        com.sendspindroid.UserSettings.setSyncOffsetMs(offsetMs)
        sendSpinClient?.getTimeFilter()?.let { timeFilter ->
            timeFilter.staticDelayMs = offsetMs.toDouble()
            Log.i(TAG, "Updated sync offset to: ${offsetMs}ms")
        }
    }

    private fun getRootChildren(): List<MediaItem> {
        val children = mutableListOf<MediaItem>()
        val maAvailable = MusicAssistantManager.connectionState.value.isAvailable

        // Show "Connect" until MA is available (avoids empty root during MA handshake)
        if (!maAvailable) {
            children.add(
                createBrowsableItem(
                    mediaId = MEDIA_ID_DISCOVERED,
                    title = "Connect",
                    subtitle = if (isConnected()) "Connected" else null
                )
            )
        }

        // Show library categories directly as root tabs when MA is connected
        if (maAvailable) {
            children.add(
                createBrowsableItem(
                    mediaId = MEDIA_ID_MA_PLAYLISTS,
                    title = "Playlists",
                    iconRes = R.drawable.ic_auto_playlists
                )
            )
            children.add(
                createBrowsableItem(
                    mediaId = MEDIA_ID_MA_ALBUMS,
                    title = "Albums",
                    extras = Bundle().apply {
                        putInt(CONTENT_STYLE_PLAYABLE, CONTENT_STYLE_GRID)
                    },
                    iconRes = R.drawable.ic_auto_albums
                )
            )
            children.add(
                createBrowsableItem(
                    mediaId = MEDIA_ID_MA_ARTISTS,
                    title = "Artists",
                    extras = Bundle().apply {
                        putInt(CONTENT_STYLE_BROWSABLE, CONTENT_STYLE_GRID)
                    },
                    iconRes = R.drawable.ic_auto_artists
                )
            )
            children.add(
                createBrowsableItem(
                    mediaId = MEDIA_ID_MA_RADIO,
                    title = "Radio",
                    iconRes = R.drawable.ic_auto_radio
                )
            )
        }

        return children
    }

    private fun getDiscoveredServers(): List<MediaItem> {
        // Trigger mDNS scan so servers populate for Android Auto / external browsers
        ensureBrowseDiscoveryRunning()

        // Saved servers first, sorted by most recently connected
        val savedItems = UnifiedServerRepository.savedServers.value
            .sortedByDescending { it.lastConnectedMs }
            .map { createSavedServerItem(it) }

        // Discovered servers that aren't already in the saved list
        val discoveredItems = UnifiedServerRepository.filteredDiscoveredServers.value.mapNotNull { server ->
            val address = server.local?.address ?: return@mapNotNull null
            createPlayableServerItem(server.name, address)
        }

        return savedItems + discoveredItems
    }

    /**
     * Starts mDNS discovery if not already running.
     * Used when an external client (Android Auto) browses the server list,
     * since the main Activity may not be open to trigger discovery.
     */
    private fun ensureBrowseDiscoveryRunning() {
        if (browseDiscoveryManager != null) return  // Already initialized

        Log.i(TAG, "Starting mDNS discovery for browse tree")
        browseDiscoveryManager = NsdDiscoveryManager(this, object : NsdDiscoveryManager.DiscoveryListener {
            override fun onServerDiscovered(name: String, address: String, path: String) {
                Log.d(TAG, "Browse discovery: found $name at $address (path=$path)")
                val server = com.sendspindroid.ServerInfo(
                    name = name,
                    address = address,
                    path = path
                )
                ServerRepository.addDiscoveredServer(server)
                UnifiedServerRepository.addDiscoveredServer(name, address, path)
                // Notify subscribed browsers that children changed
                mediaSession?.notifyChildrenChanged(MEDIA_ID_DISCOVERED, 0, null)
            }

            override fun onServerLost(name: String) {
                Log.d(TAG, "Browse discovery: lost $name")
                // Find the server by name to get its address for removal
                val server = ServerRepository.discoveredServers.value.find { it.name == name }
                server?.let {
                    ServerRepository.removeDiscoveredServer(it.address)
                    UnifiedServerRepository.removeDiscoveredServer(it.address)
                    mediaSession?.notifyChildrenChanged(MEDIA_ID_DISCOVERED, 0, null)
                }
            }

            override fun onDiscoveryStarted() {
                Log.d(TAG, "Browse discovery started")
            }

            override fun onDiscoveryStopped() {
                Log.d(TAG, "Browse discovery stopped")
            }

            override fun onDiscoveryError(error: String) {
                Log.e(TAG, "Browse discovery error: $error")
            }
        })
        browseDiscoveryManager?.startDiscovery()
    }

    /**
     * Async routing for MA browse tree nodes.
     * Called from onGetChildren for any parentId not handled by the sync path.
     */
    private suspend fun getMaChildren(parentId: String): List<MediaItem> {
        return when (parentId) {
            MEDIA_ID_MA_PLAYLISTS -> getMaPlaylists()
            MEDIA_ID_MA_ALBUMS -> getMaAlbums()
            MEDIA_ID_MA_ARTISTS -> getMaArtists()
            MEDIA_ID_MA_RADIO -> getMaRadioStations()
            else -> when {
                parentId.startsWith(MEDIA_ID_MA_PLAYLIST_PREFIX) -> {
                    val playlistId = parentId.removePrefix(MEDIA_ID_MA_PLAYLIST_PREFIX)
                    getMaPlaylistTracks(playlistId)
                }
                parentId.startsWith(MEDIA_ID_MA_ALBUM_PREFIX) -> {
                    val albumId = parentId.removePrefix(MEDIA_ID_MA_ALBUM_PREFIX)
                    getMaAlbumTracks(albumId)
                }
                parentId.startsWith(MEDIA_ID_MA_ARTIST_PREFIX) -> {
                    val artistId = parentId.removePrefix(MEDIA_ID_MA_ARTIST_PREFIX)
                    getMaArtistAlbums(artistId)
                }
                else -> {
                    Log.w(TAG, "Unknown parentId for MA children: $parentId")
                    emptyList()
                }
            }
        }
    }

    private fun createBrowsableItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        extras: Bundle? = null,
        iconRes: Int = 0
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
                    .apply {
                        if (extras != null) setExtras(extras)
                        if (iconRes != 0) {
                            setArtworkUri(Uri.parse("android.resource://com.sendspindroid/$iconRes"))
                        }
                    }
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

    private fun createSavedServerItem(server: UnifiedServer): MediaItem {
        val subtitle = when {
            server.local != null -> server.local.address
            server.proxy != null -> "Proxy"
            server.remote != null -> "Remote Access"
            else -> ""
        }
        return MediaItem.Builder()
            .setMediaId("$MEDIA_ID_SAVED_SERVER_PREFIX${server.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(server.name)
                    .setSubtitle(subtitle)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    // ========================================================================
    // Music Assistant Item Builders
    // ========================================================================

    private fun createMaTrackItem(track: MaTrack): MediaItem {
        val uri = track.uri ?: return MediaItem.EMPTY
        return MediaItem.Builder()
            .setMediaId("$MEDIA_ID_MA_TRACK_PREFIX${encodeMediaUri(uri)}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setSubtitle(track.artist)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .apply {
                        track.imageUri?.let { setArtworkUri(Uri.parse(it)) }
                    }
                    .build()
            )
            .build()
    }

    private fun createMaPlaylistItem(playlist: MaPlaylist): MediaItem {
        val subtitle = if (playlist.trackCount > 0) "${playlist.trackCount} tracks" else null
        return MediaItem.Builder()
            .setMediaId("$MEDIA_ID_MA_PLAYLIST_PREFIX${playlist.playlistId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(playlist.name)
                    .setSubtitle(subtitle)
                    .setIsPlayable(true)   // Tap to play entire playlist
                    .setIsBrowsable(true)  // Drill into tracks
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .apply {
                        playlist.imageUri?.let { setArtworkUri(Uri.parse(it)) }
                    }
                    .build()
            )
            .build()
    }

    private fun createMaAlbumItem(album: MaAlbum): MediaItem {
        val subtitle = buildString {
            album.artist?.let { append(it) }
            album.year?.let {
                if (isNotEmpty()) append(" - ")
                append(it)
            }
        }.ifEmpty { null }
        return MediaItem.Builder()
            .setMediaId("$MEDIA_ID_MA_ALBUM_PREFIX${album.albumId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(album.name)
                    .setSubtitle(subtitle)
                    .setArtist(album.artist)
                    .setIsPlayable(true)   // Tap to play entire album
                    .setIsBrowsable(true)  // Drill into tracks
                    .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                    .setExtras(Bundle().apply {
                        putInt(CONTENT_STYLE_SINGLE_ITEM, CONTENT_STYLE_GRID)
                    })
                    .apply {
                        album.imageUri?.let { setArtworkUri(Uri.parse(it)) }
                    }
                    .build()
            )
            .build()
    }

    private fun createMaArtistItem(artist: MaArtist): MediaItem {
        return MediaItem.Builder()
            .setMediaId("$MEDIA_ID_MA_ARTIST_PREFIX${artist.artistId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(artist.name)
                    .setIsPlayable(false)  // Browse only (shows albums)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                    .setExtras(Bundle().apply {
                        putInt(CONTENT_STYLE_SINGLE_ITEM, CONTENT_STYLE_GRID)
                    })
                    .apply {
                        artist.imageUri?.let { setArtworkUri(Uri.parse(it)) }
                    }
                    .build()
            )
            .build()
    }

    private fun createMaRadioItem(radio: MaRadio): MediaItem {
        val uri = radio.uri ?: return MediaItem.EMPTY
        return MediaItem.Builder()
            .setMediaId("$MEDIA_ID_MA_RADIO_ITEM_PREFIX${encodeMediaUri(uri)}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(radio.name)
                    .setSubtitle(radio.provider)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .apply {
                        radio.imageUri?.let { setArtworkUri(Uri.parse(it)) }
                    }
                    .build()
            )
            .build()
    }

    /**
     * Wraps a MediaItem with a group title extra for Android Auto search result grouping.
     * Items with the same group title are displayed together under a section header.
     */
    private fun withGroupTitle(item: MediaItem, title: String): MediaItem {
        val existingExtras = item.mediaMetadata.extras
        val extras = Bundle().apply {
            if (existingExtras != null) putAll(existingExtras)
            putString(CONTENT_STYLE_GROUP_TITLE, title)
        }
        return item.buildUpon()
            .setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    // ========================================================================
    // Music Assistant Category Listing Methods (suspend)
    // ========================================================================

    private suspend fun getMaPlaylists(): List<MediaItem> {
        maPlaylistsCache?.takeUnless { it.expired(MA_LIST_CACHE_TTL_MS) }?.let { return it.data }

        val result = MusicAssistantManager.getPlaylists(limit = 100)
        val items = result.getOrNull()?.map { createMaPlaylistItem(it) } ?: emptyList()
        maPlaylistsCache = CacheEntry(items)
        return items
    }

    private suspend fun getMaAlbums(): List<MediaItem> {
        maAlbumsCache?.takeUnless { it.expired(MA_LIST_CACHE_TTL_MS) }?.let { return it.data }

        val result = MusicAssistantManager.getAlbums(limit = 100)
        val items = result.getOrNull()?.map { createMaAlbumItem(it) } ?: emptyList()
        maAlbumsCache = CacheEntry(items)
        return items
    }

    private suspend fun getMaArtists(): List<MediaItem> {
        maArtistsCache?.takeUnless { it.expired(MA_LIST_CACHE_TTL_MS) }?.let { return it.data }

        val result = MusicAssistantManager.getArtists(limit = 100)
        val items = result.getOrNull()?.map { createMaArtistItem(it) } ?: emptyList()
        maArtistsCache = CacheEntry(items)
        return items
    }

    private suspend fun getMaRadioStations(): List<MediaItem> {
        maRadioCache?.takeUnless { it.expired(MA_LIST_CACHE_TTL_MS) }?.let { return it.data }

        val result = MusicAssistantManager.getRadioStations(limit = 100)
        val items = result.getOrNull()?.map { createMaRadioItem(it) } ?: emptyList()
        maRadioCache = CacheEntry(items)
        return items
    }

    // ========================================================================
    // Music Assistant Drill-Down Methods (suspend)
    // ========================================================================

    private suspend fun getMaPlaylistTracks(playlistId: String): List<MediaItem> {
        maPlaylistTracksCache[playlistId]
            ?.takeUnless { it.expired(MA_DETAIL_CACHE_TTL_MS) }
            ?.let { return it.data }

        val result = MusicAssistantManager.getPlaylistTracks(playlistId)
        val items = result.getOrNull()?.map { createMaTrackItem(it) } ?: emptyList()
        maPlaylistTracksCache[playlistId] = CacheEntry(items)
        return items
    }

    private suspend fun getMaAlbumTracks(albumId: String): List<MediaItem> {
        maAlbumTracksCache[albumId]
            ?.takeUnless { it.expired(MA_DETAIL_CACHE_TTL_MS) }
            ?.let { return it.data }

        val result = MusicAssistantManager.getAlbumTracks(albumId)
        val items = result.getOrNull()?.map { createMaTrackItem(it) } ?: emptyList()
        maAlbumTracksCache[albumId] = CacheEntry(items)
        return items
    }

    private suspend fun getMaArtistAlbums(artistId: String): List<MediaItem> {
        maArtistAlbumsCache[artistId]
            ?.takeUnless { it.expired(MA_DETAIL_CACHE_TTL_MS) }
            ?.let { return it.data }

        val result = MusicAssistantManager.getArtistDetails(artistId)
        val items = result.getOrNull()?.albums?.map { createMaAlbumItem(it) } ?: emptyList()
        maArtistAlbumsCache[artistId] = CacheEntry(items)
        return items
    }

    /**
     * Creates a playable MediaItem for a queue entry.
     * Uses the queue item ID as the media ID (not the track URI),
     * so tapping it calls playQueueItem() to jump to that position.
     *
     * The currently playing item gets a "[Now Playing]" prefix in its title
     * for visual differentiation in Android Auto.
     */
    private fun createMaQueueMediaItem(item: MaQueueItem, isCurrent: Boolean): MediaItem {
        val displayTitle = if (isCurrent) "[Now Playing] ${item.name}" else item.name
        val mediaId = "$MEDIA_ID_MA_QUEUE_ITEM_PREFIX${item.queueItemId}"

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(displayTitle)
                    .setSubtitle(item.artist)
                    .setArtist(item.artist)
                    .setAlbumTitle(item.album)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .apply {
                        item.imageUri?.let { setArtworkUri(Uri.parse(it)) }
                    }
                    .build()
            )
            .build()
    }

    /**
     * Fetches the MA queue in the background and populates the player's timeline.
     * This makes the native queue button in Android Auto show all queue items.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun populatePlayerQueue() {
        if (!MusicAssistantManager.connectionState.value.isAvailable) return

        val generation = ++queuePopulateGeneration

        serviceScope.launch {
            try {
                val result = MusicAssistantManager.getQueueItems()
                val queueState = result.getOrNull() ?: return@launch

                val items = queueState.items.map { queueItem ->
                    createMaQueueMediaItem(queueItem, isCurrent = false)
                }

                mainHandler.post {
                    // Discard result if a newer populatePlayerQueue() was launched
                    if (generation != queuePopulateGeneration) {
                        Log.d(TAG, "Discarding stale queue populate (gen=$generation, current=$queuePopulateGeneration)")
                        return@post
                    }
                    sendSpinPlayer?.updateQueueItems(items, queueState.currentIndex)
                }
                Log.d(TAG, "Populated player queue: ${items.size} items, current=${queueState.currentIndex}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to populate player queue", e)
            }
        }
    }

    // ========================================================================
    // Voice Search (VC-1 requirement for Android Auto)
    // ========================================================================

    /**
     * Handles voice search from Android Auto ("OK Google, play X on SendSpinDroid").
     * In Media3, voice search arrives via onAddMediaItems with requestMetadata.searchQuery set.
     *
     * - Empty/blank query ("play music"): plays recently played tracks
     * - Non-empty query: searches MA library and plays first track result
     */
    private fun handleVoiceSearch(
        query: String,
        originalItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        if (!MusicAssistantManager.connectionState.value.isAvailable) {
            Log.w(TAG, "Voice search: MA not available, returning items as-is")
            return Futures.immediateFuture(originalItems)
        }

        return suspendToFuture {
            try {
                if (query.isBlank()) {
                    // "Play music on SendSpinDroid" - play recently played
                    Log.d(TAG, "Voice search: empty query, playing recent")
                    val recent = MusicAssistantManager.getRecentlyPlayed(limit = 1)
                    val firstTrack = recent.getOrNull()?.firstOrNull()
                    if (firstTrack?.uri != null) {
                        MusicAssistantManager.playMedia(firstTrack.uri, mediaType = "track")
                    } else {
                        Log.w(TAG, "Voice search: no recent tracks to play")
                    }
                } else {
                    // "Play Beatles on SendSpinDroid" - search and play first result
                    Log.d(TAG, "Voice search: searching for '$query'")
                    val result = MusicAssistantManager.search(
                        query = query,
                        limit = 5,
                        libraryOnly = false
                    )
                    val searchResults = result.getOrNull()
                    val firstTrack = searchResults?.tracks?.firstOrNull()
                    if (firstTrack?.uri != null) {
                        Log.d(TAG, "Voice search: playing track '${firstTrack.name}'")
                        MusicAssistantManager.playMedia(firstTrack.uri, mediaType = "track")
                    } else {
                        // Try playing first playlist or album if no tracks found
                        val firstPlaylist = searchResults?.playlists?.firstOrNull()
                        val firstAlbum = searchResults?.albums?.firstOrNull()
                        when {
                            firstPlaylist != null -> {
                                Log.d(TAG, "Voice search: playing playlist '${firstPlaylist.name}'")
                                MusicAssistantManager.playMedia(
                                    firstPlaylist.playlistId,
                                    mediaType = "playlist"
                                )
                            }
                            firstAlbum != null -> {
                                Log.d(TAG, "Voice search: playing album '${firstAlbum.name}'")
                                MusicAssistantManager.playMedia(
                                    firstAlbum.albumId,
                                    mediaType = "album"
                                )
                            }
                            else -> {
                                Log.w(TAG, "Voice search: no results for '$query'")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice search failed", e)
            }
            // Return original items with a dummy URI so media3 framework doesn't error
            originalItems.map { item ->
                item.buildUpon()
                    .setUri("sendspin://voice-search")
                    .build()
            }
        }
    }

    // Music Assistant MA Playback Dispatch
    // ========================================================================

    /**
     * Handles playback for MA media IDs from the browse tree.
     * Called from onAddMediaItems when a ma_* media ID is tapped.
     */
    private fun handleMaMediaItem(mediaId: String): MediaItem {
        serviceScope.launch {
            try {
                when {
                    mediaId.startsWith(MEDIA_ID_MA_QUEUE_ITEM_PREFIX) -> {
                        val queueItemId = mediaId.removePrefix(MEDIA_ID_MA_QUEUE_ITEM_PREFIX)
                        Log.d(TAG, "MA: Playing queue item id=$queueItemId")
                        MusicAssistantManager.playQueueItem(queueItemId)
                    }
                    mediaId.startsWith(MEDIA_ID_MA_TRACK_PREFIX) -> {
                        val encoded = mediaId.removePrefix(MEDIA_ID_MA_TRACK_PREFIX)
                        val uri = decodeMediaUri(encoded)
                        Log.d(TAG, "MA: Playing track uri=$uri")
                        MusicAssistantManager.playMedia(uri, mediaType = "track")
                    }
                    mediaId.startsWith(MEDIA_ID_MA_RADIO_ITEM_PREFIX) -> {
                        val encoded = mediaId.removePrefix(MEDIA_ID_MA_RADIO_ITEM_PREFIX)
                        val uri = decodeMediaUri(encoded)
                        Log.d(TAG, "MA: Playing radio uri=$uri")
                        MusicAssistantManager.playMedia(uri, mediaType = "radio")
                    }
                    mediaId.startsWith(MEDIA_ID_MA_PLAYLIST_PREFIX) -> {
                        val playlistId = mediaId.removePrefix(MEDIA_ID_MA_PLAYLIST_PREFIX)
                        val uri = "library://playlist/$playlistId"
                        Log.d(TAG, "MA: Playing playlist uri=$uri")
                        MusicAssistantManager.playMedia(uri, mediaType = "playlist")
                    }
                    mediaId.startsWith(MEDIA_ID_MA_ALBUM_PREFIX) -> {
                        val albumId = mediaId.removePrefix(MEDIA_ID_MA_ALBUM_PREFIX)
                        val uri = "library://album/$albumId"
                        Log.d(TAG, "MA: Playing album uri=$uri")
                        MusicAssistantManager.playMedia(uri, mediaType = "album")
                    }
                    else -> {
                        Log.w(TAG, "MA: Unknown media ID for playback: $mediaId")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MA: Failed to play media $mediaId", e)
            }
        }

        // Return a MediaItem with a dummy URI so media3 doesn't complain
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri("sendspin://ma-playback")
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
            mediaId == MEDIA_ID_DISCOVERED -> {
                createBrowsableItem(MEDIA_ID_DISCOVERED, "Connect", "Choose a server")
            }
            mediaId.startsWith(MEDIA_ID_SAVED_SERVER_PREFIX) -> {
                val serverId = mediaId.removePrefix(MEDIA_ID_SAVED_SERVER_PREFIX)
                UnifiedServerRepository.savedServers.value
                    .find { it.id == serverId }
                    ?.let { createSavedServerItem(it) }
            }
            mediaId.startsWith(MEDIA_ID_SERVER_PREFIX) -> {
                val address = mediaId.removePrefix(MEDIA_ID_SERVER_PREFIX)
                val server = ServerRepository.getServer(address)
                server?.let { createPlayableServerItem(it.name, it.address) }
            }
            // MA category folders (root-level tabs)
            mediaId == MEDIA_ID_MA_PLAYLISTS -> {
                createBrowsableItem(MEDIA_ID_MA_PLAYLISTS, "Playlists")
            }
            mediaId == MEDIA_ID_MA_ALBUMS -> {
                createBrowsableItem(MEDIA_ID_MA_ALBUMS, "Albums")
            }
            mediaId == MEDIA_ID_MA_ARTISTS -> {
                createBrowsableItem(MEDIA_ID_MA_ARTISTS, "Artists")
            }
            mediaId == MEDIA_ID_MA_RADIO -> {
                createBrowsableItem(MEDIA_ID_MA_RADIO, "Radio")
            }
            // MA items - search through caches
            mediaId.startsWith("ma_") -> {
                findMaItemInCaches(mediaId)
            }
            else -> null
        }
    }

    /**
     * Searches all MA caches for an item by media ID.
     * Used by onGetItem to resolve individual MA items.
     */
    private fun findMaItemInCaches(mediaId: String): MediaItem? {
        // Search list caches
        val allCaches = listOfNotNull(
            maPlaylistsCache?.data,
            maAlbumsCache?.data,
            maArtistsCache?.data,
            maRadioCache?.data,
            maSearchResultsCache
        )
        for (cache in allCaches) {
            cache.find { it.mediaId == mediaId }?.let { return it }
        }
        // Search drill-down caches
        for ((_, entry) in maPlaylistTracksCache) {
            entry.data.find { it.mediaId == mediaId }?.let { return it }
        }
        for ((_, entry) in maAlbumTracksCache) {
            entry.data.find { it.mediaId == mediaId }?.let { return it }
        }
        for ((_, entry) in maArtistAlbumsCache) {
            entry.data.find { it.mediaId == mediaId }?.let { return it }
        }
        return null
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initializePlayer() {
        sendSpinPlayer = SendSpinPlayer()
        sendSpinPlayer?.onQueueItemSelected = { mediaId ->
            // Handle queue item selection from Android Auto's native queue UI
            val queueItemId = mediaId.removePrefix(MEDIA_ID_MA_QUEUE_ITEM_PREFIX)
            Log.d(TAG, "Native queue item selected: $queueItemId")
            serviceScope.launch {
                MusicAssistantManager.playQueueItem(queueItemId)
            }
        }
        Log.d(TAG, "SendSpinPlayer initialized")
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession? {
        Log.i(TAG, "onGetSession called by: ${controllerInfo.packageName}" +
                " (uid=${controllerInfo.uid})" +
                ", mediaSession=${mediaSession != null}" +
                ", player=${sendSpinPlayer != null}" +
                ", forwardingPlayer=${forwardingPlayer != null}" +
                ", isDestroyed=$isDestroyed")

        // Defensive: ensure MediaSession exists for external callers like Android Auto
        if (mediaSession == null) {
            Log.w(TAG, "MediaSession is null when onGetSession called - attempting recovery")
            if (sendSpinPlayer == null) {
                Log.d(TAG, "Creating SendSpinPlayer for external caller")
                initializePlayer()
            }
            if (sendSpinPlayer != null) {
                Log.d(TAG, "Creating MediaSession for external caller")
                initializeMediaSession()
            }
            Log.i(TAG, "Recovery result: mediaSession=${mediaSession != null}")
        }

        if (mediaSession == null) {
            Log.e(TAG, "Failed to create MediaSession for ${controllerInfo.packageName}")
        }

        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, flags=$flags")
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Check if SyncAudioPlayer is actively playing
        val audioPlayerState = syncAudioPlayer?.getPlaybackState()
        val isPlaying = audioPlayerState == com.sendspindroid.sendspin.PlaybackState.PLAYING ||
                        audioPlayerState == com.sendspindroid.sendspin.PlaybackState.WAITING_FOR_START

        Log.d(TAG, "onTaskRemoved (playing=$isPlaying, state=$audioPlayerState)")

        // Keep service alive only if actively playing
        if (!isPlaying) {
            Log.d(TAG, "Not playing, stopping service")
            stopSelf()
        } else {
            Log.d(TAG, "Continuing playback in background")
        }

        super.onTaskRemoved(rootIntent)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onDestroy() {
        Log.d(TAG, "PlaybackService destroyed")

        // Set destroyed flag first to prevent any pending callbacks from executing
        isDestroyed = true

        // Remove all pending callbacks from all handlers
        mainHandler.removeCallbacksAndMessages(null)
        wakeLockHandler.removeCallbacks(wakeLockRefreshRunnable)
        wakeLockHandler.removeCallbacks(highPowerWakeLockRefreshRunnable)
        debugLogHandler.removeCallbacks(debugLogRunnable)

        // Stop debug logging (also removes callbacks, but flag is set above)
        stopDebugLogging()

        // Unregister network callback
        unregisterNetworkCallback()

        // Unregister sync offset receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(syncOffsetReceiver)

        // Unregister debug logging receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(debugLoggingReceiver)

        // Unregister High Power Mode receiver and release locks
        LocalBroadcastManager.getInstance(this).unregisterReceiver(highPowerModeReceiver)
        releaseHighPowerLocks()

        // Unregister volume observer (only if it was registered)
        if (volumeObserverRegistered) {
            volumeObserver?.let { contentResolver.unregisterContentObserver(it) }
            volumeObserverRegistered = false
        }
        volumeObserver = null

        // Stop browse discovery if running
        browseDiscoveryManager?.cleanup()
        browseDiscoveryManager = null

        serviceScope.cancel()
        imageLoader?.shutdown()

        // Release audio decoder and player, then playback locks and foreground notification
        audioDecoder?.release()
        audioDecoder = null
        syncAudioPlayer?.release()
        syncAudioPlayer = null
        releasePlaybackLocks()
        stopForegroundNotification()

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
