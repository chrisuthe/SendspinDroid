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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
import com.sendspindroid.AppSendSpinHost
import com.sendspindroid.MainActivity

import com.sendspindroid.SyncOffsetPreference
import com.sendspindroid.ui.adaptive.isTvDevice
import com.sendspindroid.ui.settings.SettingsViewModel
import com.sendspindroid.coordinator.ConnectionCoordinator
import com.sendspindroid.coordinator.ConnectionMode
import com.sendspindroid.coordinator.FailureReason
import com.sendspindroid.coordinator.InboundConnections
import com.sendspindroid.coordinator.ReconnectStatus
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.diagnostics.HandoffEpisodeRecorder
import com.sendspindroid.diagnostics.Telemetry
import com.sendspindroid.logging.AppLog
import com.sendspindroid.logging.LogLevel
import com.sendspindroid.model.PlaybackState
import com.sendspindroid.model.PlaybackStateType
import com.sendspindroid.model.SyncStats
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.pairing.PairedServers
import com.sendspindroid.sendspin.pairing.PairingCodeFormat
import com.sendspindroid.sendspin.protocol.AdmissionState
import com.sendspindroid.sendspin.protocol.ColorState
import com.sendspindroid.sendspin.protocol.TrackMetadata
import com.sendspindroid.sendspin.SendSpinEndpoint
import com.sendspindroid.discovery.DiscoveryGate
import com.sendspindroid.discovery.NsdAdvertiser
import com.sendspindroid.discovery.NsdDiscoveryManager
import com.sendspindroid.sendspin.transport.SendspinWebSocketServer
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.SyncAudioPlayer
import com.sendspindroid.sendspin.PlaybackState as SyncPlaybackState
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.network.ConnectionSelector
import com.sendspindroid.network.NetworkEvaluator
import com.sendspindroid.network.NetworkState
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Background playback service for SendSpinDroid.
 *
 * Extends MediaLibraryService to provide:
 * - Background audio playback (screen off, app minimized)
 * - System media integration (notifications, lock screen controls)
 * - Audio focus handling (pause the group for calls, mute for other audio)
 * - Bluetooth/headset button support
 * - Android Auto browse tree support
 *
 * ## Architecture
 * ```
 * MainActivity --MediaController--> PlaybackService
 *                                        |
 *                                   +----+----+
 *                                   | SendSpin        |
 *                                   | SyncAudioPlayer |
 *                                   | MediaSession    |
 *                                   +-----------------+
 * ```
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var sendSpinPlayer: SendSpinPlayer? = null
    private var forwardingPlayer: MetadataForwardingPlayer? = null

    // The client being played from: [dialClient], or the client of a server
    // that connected to us and was admitted. Swapped only by useClient().
    private var sendSpinClient: SendSpin? = null

    // The client every connection this service dials is made on.
    private var dialClient: SendSpin? = null
    // Decoding and the audio output, fed by the client being played from.
    private val engine = SendSpinEngine(
        listener = object : SendSpinEngine.Listener {
            override fun onStreamStarted() {
                // Acquire playback locks (CPU/WiFi) to prevent sleep during streaming
                // The foreground service is already running (started on connect)
                acquirePlaybackLocks()

                // Update notification to show we're now streaming
                startForegroundServiceWithNotification()
            }

            override fun onPlayerChanged(player: SyncAudioPlayer?) {
                sendSpinPlayer?.setSyncAudioPlayer(player)
            }

            @OptIn(UnstableApi::class)
            override fun onPlayerStateChanged(state: SyncPlaybackState) {
                // Update SendSpinPlayer so it notifies MediaSession listeners
                sendSpinPlayer?.updateStateFromPlayer()
            }
        },
        lowMemoryMode = { UserSettings.lowMemoryMode },
        playerMuted = { UserSettings.getPlayerMuted() },
        // Synchronous messages, as mainHandler posts: Dispatchers.Main posts
        // asynchronous ones, which overtake mainHandler's (onStateChanged's
        // pause, say) while a frame barrier is pending.
        mainDispatcher = Handler(Looper.getMainLooper()).asCoroutineDispatcher(),
    )

    // Active server as a flow, consumed by ConnectionCoordinator.
    private val _currentServerFlow = MutableStateFlow<UnifiedServer?>(null)

    private lateinit var coordinator: ConnectionCoordinator

    // Records network-handoff episodes (drop + reconnect attempts + outcome) from
    // the coordinator's reconnect status, surfaced in getStats() / bug reports and
    // submitted to opt-in telemetry as each episode closes.
    private val handoffRecorder = HandoffEpisodeRecorder().apply {
        onEpisodeClosed = { episode -> Telemetry.submit(episode) }
    }

    // mDNS discovery for Android Auto browse tree
    private var browseDiscoveryManager: NsdDiscoveryManager? = null

    // mDNS discovery while the reconnect loop runs: a server that announces
    // itself again is retried at once instead of at the end of a backoff.
    private var reconnectDiscoveryManager: NsdDiscoveryManager? = null

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
            sendSpinClient?.let { client ->
                client.setUserSyncOffsetMs(offsetMs.toDouble())
                Log.i(TAG, "Applied sync offset from settings change: ${offsetMs}ms")
            }
        }
    }

    // BroadcastReceiver for log level changes from settings
    private val logLevelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val levelStr = intent.getStringExtra(SettingsViewModel.EXTRA_LOG_LEVEL) ?: return
            val level = runCatching { LogLevel.valueOf(levelStr) }.getOrDefault(LogLevel.OFF)
            Log.i(TAG, "Log level changed: $level")

            if (level != LogLevel.OFF && isConnected()) {
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

    // BroadcastReceiver for preferred codec changes from settings: report the
    // new preference as `format` in client/state instead of waiting for the
    // next connect. The server replies with stream/start, which flows through
    // the normal format-change reconfiguration path.
    private val preferredCodecReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val codec = intent.getStringExtra(SettingsViewModel.EXTRA_PREFERRED_CODEC) ?: return
            if (!AudioDecoderFactory.isCodecSupported(codec)) {
                Log.w(TAG, "Preferred codec changed to unsupported '$codec' - not requesting")
                return
            }
            Log.i(TAG, "Preferred codec changed: $codec - reporting the new format preference")
            sendSpinClient?.setPreferredCodec(codec)
        }
    }

    // The player name changed in settings. The server only learns a name from
    // client/hello ("the client/hello value is authoritative", connection.md),
    // so the dial client and the mDNS announcement take the new one, and a
    // live connection is made again ("restart": the server reconnects too).
    // Server-initiated connections read the name when they are made.
    private val playerNameReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val name = UserSettings.getPlayerName()
            if (name == appliedPlayerName) return
            appliedPlayerName = name
            Log.i(TAG, "Player name changed to '$name'")
            dialClient?.setDeviceName(name)
            val port = _advertisedPort.value
            val advertising = advertiser
            if (port != null && advertising != null) {
                advertising.start(name, port, SendspinWebSocketServer.DEFAULT_PATH)
            }
            if (sendSpinClient?.connectionState?.value is TransportState.Ready) {
                sendSpinClient?.disconnectForReselection()
            }
        }
    }

    // The player name the clients and the announcement were last given
    private var appliedPlayerName: String? = null

    // Flag to prevent callbacks from executing after service is destroyed
    @Volatile
    private var isDestroyed = false

    // Set by COMMAND_EXIT: the service is on its way out and must not take
    // the foreground again. Main thread only.
    private var exiting = false

    // Playback state exposed as StateFlow (like Python CLI's AppState)
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Sync offset state (included in broadcastSessionExtras to avoid bare-bundle overwrites)
    private var lastSyncOffsetMs: Double = 0.0
    private var lastSyncOffsetSource: String = ""

    // Artwork state. Two independent sources are maintained so the lock-screen
    // widget and app UI don't diverge when the SendSpin server sends a binary
    // artwork payload whose contents don't match the `artwork_url` in the
    // accompanying `server/state` metadata (observed with MA in playlist
    // contexts; see docs/architecture/sendspin-ma-metadata-flow.md §7 Q4/Q5).
    //
    // Policy: while the track's metadata carries an `artwork_url`, only the
    // image fetched from that URL is shown, matching what the app's own
    // mini-player already does via Coil on the `artworkUrl` state. Binary
    // artwork is shown only while the metadata carries no URL.
    //
    // The two are never mixed, and each has one owner. urlArtwork belongs to
    // lastArtworkUrl and is dropped when that changes. binaryArtwork is the
    // artwork stream's current image (roles/artwork/v1.md): the server sends
    // an image once and clears it explicitly, so it is replaced or cleared
    // only by onArtwork/onArtworkCleared, never by a metadata update.
    //
    // effectiveArtwork is recomputed on every write and passed to MediaSession.
    private var lastArtworkUrl: String? = null
    private var lastTrackTitle: String? = null
    private var urlArtwork: Bitmap? = null
    private var binaryArtwork: Bitmap? = null
    // Bumped whenever binaryArtwork is superseded, so a decode still running
    // for an older image cannot bring it back. Main thread only.
    private var binaryArtworkGeneration = 0
    private val effectiveArtwork: Bitmap?
        get() = if (lastArtworkUrl != null) urlArtwork else binaryArtwork
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

    // Keeps the CPU awake while the loop makes a connection at boot or after
    // a restart, when nothing else does. Not reference counted, and acquired
    // with a timeout: it cannot be left held.
    private var connectWakeLock: PowerManager.WakeLock? = null

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
            if (AppLog.level != LogLevel.OFF && isConnected()) {
                logCurrentStats()
                debugLogHandler.postDelayed(this, DEBUG_LOG_INTERVAL_MS)
            }
        }
    }

    private var networkEvaluator: NetworkEvaluator? = null

    // AudioManager for device volume control (Spotify-style hybrid approach)
    private var audioManager: AudioManager? = null
    private var volumeObserver: ContentObserver? = null
    private var volumeObserverRegistered: Boolean = false  // Track registration state to prevent leaks
    private var lastKnownVolume: Int = -1  // Track to detect external volume changes
    private var lastOutputType: Int? = null  // The output lastKnownVolume was set or synced on
    private val fullVolumeOutputs = mutableSetOf<Int>()  // Outputs setVolume found holding the index at max, kept in UserSettings

    // Audio focus management - required for Android Auto to hand over audio output
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false
    // Another app took the output for good, and it has not been taken back:
    // a fact about the device, which every client played from reports as
    // available: false. Main thread.
    private var otherAppHoldsOutput = false

    // What another app taking the output has done to us (main thread only).
    // See AudioInterruptionPolicy.
    private var interruptionMuted = false   // output muted for a focus loss or output disconnect
    private var pausedForCall = false       // we sent 'pause' for a call and nobody has changed playback since
    private var transientFocusLoss: AudioInterruption? = null  // transient loss still in force

    // A call can take focus slightly before the audio mode says it is a call
    // (and a VoIP app may only switch mode when answered), so while a transient
    // loss is being handled as plain audio the mode is polled, and the loss is
    // decided again once the mode says it is a call.
    private val callModeRecheckRunnable = object : Runnable {
        override fun run() {
            val loss = transientFocusLoss ?: return
            if (AudioInterruptionPolicy.isCallMode(audioManager?.mode ?: AudioManager.MODE_NORMAL)) {
                handleAudioInterruption(loss)
            } else {
                mainHandler.postDelayed(this, CALL_MODE_RECHECK_MS)
            }
        }
    }

    // Receiver for ACTION_AUDIO_BECOMING_NOISY: fired when the audio output is
    // rerouting to the built-in speaker because an external output disconnected
    // (wired headphones unplugged, Bluetooth/Android Auto disconnected). We pause
    // the group so we don't abruptly blast the phone speaker.
    private var becomingNoisyReceiver: BroadcastReceiver? = null

    companion object {
        // The artist image the server sends on its own artwork channel, for
        // the second page of the album art card. In-process: it is a few
        // tens of kilobytes that only the activity wants, not session
        // metadata.
        private val _artistArtwork = MutableStateFlow<ByteArray?>(null)
        val artistArtwork: StateFlow<ByteArray?> = _artistArtwork.asStateFlow()

        // The colors the server sends for the current audio (color@v1).
        // In-process, like the artist image: only the activity reads them.
        private val _serverColors = MutableStateFlow<ColorState?>(null)
        val serverColors: StateFlow<ColorState?> = _serverColors.asStateFlow()

        private const val TAG = "PlaybackService"

        // How long after an output is connected or removed its volume is read
        private const val OUTPUT_CHANGE_SETTLE_MS = 300L

        // Every focus change, the audio mode at that moment and the action
        // taken are logged at INFO under this tag: adb logcat -s AudioFocus
        private const val FOCUS_TAG = "AudioFocus"
        private const val CALL_MODE_RECHECK_MS = 1000L

        // Wake lock refresh strategy:
        // - Use a 30-minute timeout so if release fails (crash, etc.), max battery drain is 30 min
        // - Refresh every 20 minutes during active playback to keep it alive
        // - This is safer than a 10-hour timeout with no refresh
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L  // 30 minutes
        private const val WAKE_LOCK_REFRESH_INTERVAL_MS = 20 * 60 * 1000L  // 20 minutes

        // Debug logging interval (1 sample per second)
        private const val DEBUG_LOG_INTERVAL_MS = 1000L

        // Timeout for awaiting a terminal connection state in connectViaSelectedConnection.
        private const val CONNECT_TIMEOUT_MS = 15_000L

        // How long the playback locks (CPU, Wi-Fi, audio focus) held when a
        // connection dropped are kept for the reconnect loop, and the wake
        // lock of a connection made at boot or on a restart is held. Long
        // enough for a server restart or a network blip with the screen off;
        // after it the loop carries on whenever the device is awake.
        private const val RECONNECT_LOCK_HOLD_MS = 10 * 60 * 1000L

        // How long COMMAND_EXIT waits for the goodbye to go out before it
        // stops the service anyway.
        private const val EXIT_FLUSH_TIMEOUT_MS = 2_000L

        // Custom session commands
        const val COMMAND_CONNECT = "com.sendspindroid.CONNECT"
        const val COMMAND_DISCONNECT = "com.sendspindroid.DISCONNECT"
        const val COMMAND_EXIT = "com.sendspindroid.EXIT"
        const val COMMAND_SET_VOLUME = "com.sendspindroid.SET_VOLUME"
        /** Mute or un-mute this player ([ARG_MUTED]), as the server's `mute` command does. */
        const val COMMAND_SET_MUTED = "com.sendspindroid.SET_MUTED"
        const val COMMAND_NEXT = "com.sendspindroid.NEXT"
        const val COMMAND_PREVIOUS = "com.sendspindroid.PREVIOUS"
        const val COMMAND_SWITCH_GROUP = "com.sendspindroid.SWITCH_GROUP"
        const val COMMAND_GET_STATS = "com.sendspindroid.GET_STATS"
        const val COMMAND_ALLOW_PAIRING = "com.sendspindroid.ALLOW_PAIRING"

        /** Search for servers ([ARG_SEARCH_FOR_SERVERS] true) or advertise and wait for one. */
        const val COMMAND_SET_SEARCH_FOR_SERVERS = "com.sendspindroid.SET_SEARCH_FOR_SERVERS"
        const val ARG_SEARCH_FOR_SERVERS = "search_for_servers"

        // Intent actions for service start (used by BootReceiver)
        const val ACTION_AUTO_CONNECT = "com.sendspindroid.ACTION_AUTO_CONNECT"
        const val EXTRA_SERVER_ID = "server_id_auto_connect"

        // Command arguments
        const val ARG_SERVER_ADDRESS = "server_address"
        const val ARG_SERVER_PATH = "server_path"
        const val ARG_VOLUME = "volume"
        const val ARG_MUTED = "muted"
        const val ARG_SERVER_ID = "server_id"

        // Session extras keys for metadata (service → controller)
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_ARTWORK_URL = "artwork_url"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_POSITION_UPDATED_AT = "position_updated_at"
        const val EXTRA_PLAYBACK_SPEED = "playback_speed"
        const val EXTRA_ARTWORK_DATA = "artwork_data"
        // Present only when the track has one: no "" or 0 stands in for none
        const val EXTRA_ALBUM_ARTIST = "album_artist"
        const val EXTRA_YEAR = "year"

        // Session extras keys for connection state
        const val EXTRA_CONNECTION_STATE = "connection_state"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        /**
         * Why a connected session cannot play, as an [AdmissionState] name.
         * Only present while EXTRA_CONNECTION_STATE is STATE_CONNECTED - it
         * describes an accepted activation, which no other state has.
         */
        const val EXTRA_ADMISSION_STATE = "admission_state"

        /**
         * The current dynamic pairing code, present only while one is being
         * shown to the operator. Rides the same STATE_CONNECTED branch as
         * EXTRA_ADMISSION_STATE, for the same reason.
         */
        const val EXTRA_PAIRING_CODE = "pairing_code"

        /**
         * The emission format of EXTRA_PAIRING_CODE, as its wire name: in
         * `qr_code` the code is a pairing token to draw as a QR code. Sent
         * with the code and only with it.
         */
        const val EXTRA_PAIRING_CODE_FORMAT = "pairing_code_format"

        /**
         * Whether a dynamic pairing attempt is gesture-gated and waiting on
         * the "Allow pairing" gesture. Absent value reads as false.
         */
        const val EXTRA_PAIRING_GESTURE_REQUESTED = "pairing_gesture_requested"

        // Session extras keys for volume (server → controller)
        const val EXTRA_VOLUME = "volume"
        /** The player's `muted` state, independent of [EXTRA_VOLUME]. */
        const val EXTRA_MUTED = "muted"

        // Session extras keys for group info
        const val EXTRA_GROUP_NAME = "group_name"

        // Which attempt the reconnect loop is on (with STATE_RECONNECTING)
        const val EXTRA_RECONNECT_ATTEMPT = "reconnect_attempt"

        // Connection state values
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_RECONNECTING = "reconnecting"
        const val STATE_ERROR = "error"

        // Android Auto browse tree media IDs
        // Max artwork bitmap dimension (px) for MediaMetadata / notifications.
        // Notifications only display small thumbnails; 300px is more than
        // sufficient and avoids wasting memory on full-resolution bitmaps.
        // The UI loads artwork independently via URL so this does not affect
        // in-app display quality.
        private const val MAX_ARTWORK_SIZE = 300

        // Browse tree media IDs and content style hints live in AutoBrowseTree
        // (the testable list builder); aliased here so the rest of the service
        // reads unchanged.
        private const val MEDIA_ID_ROOT = AutoBrowseTree.MEDIA_ID_ROOT
        private const val MEDIA_ID_DISCOVERED = AutoBrowseTree.MEDIA_ID_DISCOVERED
        private const val MEDIA_ID_SERVER_PREFIX = AutoBrowseTree.MEDIA_ID_SERVER_PREFIX
        private const val MEDIA_ID_SAVED_SERVER_PREFIX = AutoBrowseTree.MEDIA_ID_SAVED_SERVER_PREFIX

        // Android Auto content style hint keys
        private const val CONTENT_STYLE_BROWSABLE = AutoBrowseTree.CONTENT_STYLE_BROWSABLE
        private const val CONTENT_STYLE_PLAYABLE = AutoBrowseTree.CONTENT_STYLE_PLAYABLE
        private const val CONTENT_STYLE_LIST = AutoBrowseTree.CONTENT_STYLE_LIST

        // How long the "Connect" browse node waits for the first mDNS result
        // before showing the "No servers found" guidance row. Keeps the first
        // browse on Android Auto from racing discovery and rendering empty.
        private const val BROWSE_DISCOVERY_WAIT_MS = 3_000L

        /**
         * Whether an mDNS announcement is for [server]: by its friendly name
         * (which is what a saved server is named after), by the raw service
         * name, or by the address it was last reached at.
         */
        internal fun isSameServer(server: UnifiedServer, name: String, friendlyName: String, address: String): Boolean =
            friendlyName == server.name || name == server.name || address == server.local?.address

        // Exposes the coordinator's network state for in-process observers (e.g. MainActivity).
        // Updated by the service's existing coordinator.networkState collector.
        private val _networkState = MutableStateFlow(NetworkState())
        val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

        // Exposes the coordinator's reconnect status for in-process observers (e.g. MainActivity),
        // which show it and never act on it.
        // Updated by the service's existing coordinator.reconnectStatus collector.
        private val _reconnectStatusRelay = MutableStateFlow<ReconnectStatus>(ReconnectStatus.Idle)
        val reconnectStatus: StateFlow<ReconnectStatus> = _reconnectStatusRelay.asStateFlow()

        // The port servers can connect to, while the service is advertising
        // itself and waiting for one; null otherwise. For in-process observers.
        private val _advertisedPort = MutableStateFlow<Int?>(null)
        val advertisedPort: StateFlow<Int?> = _advertisedPort.asStateFlow()

        // True while the service is meant to be advertising but servers
        // cannot find or reach it: no port could be bound, or the mDNS
        // registration failed.
        private val _advertisingFailed = MutableStateFlow(false)
        val advertisingFailed: StateFlow<Boolean> = _advertisingFailed.asStateFlow()
    }


    @OptIn(UnstableApi::class)
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

        // Initialize UnifiedServerRepository for server lookups
        UnifiedServerRepository.initialize(this)
        UserSettings.chooseConnectionModeOnce(UnifiedServerRepository.getDefaultServer() != null)

        // Register receiver for sync offset changes from settings
        LocalBroadcastManager.getInstance(this).registerReceiver(
            syncOffsetReceiver,
            IntentFilter(SyncOffsetPreference.ACTION_SYNC_OFFSET_CHANGED)
        )

        // Register receiver for log level changes from settings
        LocalBroadcastManager.getInstance(this).registerReceiver(
            logLevelReceiver,
            IntentFilter(SettingsViewModel.ACTION_LOG_LEVEL_CHANGED)
        )

        // Register receiver for High Power Mode toggle changes from settings
        LocalBroadcastManager.getInstance(this).registerReceiver(
            highPowerModeReceiver,
            IntentFilter(SettingsViewModel.ACTION_HIGH_POWER_MODE_CHANGED)
        )

        // Register receiver for preferred codec changes from settings
        LocalBroadcastManager.getInstance(this).registerReceiver(
            preferredCodecReceiver,
            IntentFilter(SettingsViewModel.ACTION_PREFERRED_CODEC_CHANGED)
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            playerNameReceiver,
            IntentFilter(SettingsViewModel.ACTION_PLAYER_NAME_CHANGED)
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

        coordinator = ConnectionCoordinator(
            currentServerFlow = _currentServerFlow,
            sendSpinStateFlow = clientState,
            scope = serviceScope,
            connectAttempt = { lost, method ->
                // Read again for each attempt: mDNS may have found the server
                // at a new address since the loop started.
                val server = UnifiedServerRepository.getServer(lost.id) ?: lost
                // Local is the only connection method left; other ConnectionType
                // values can no longer be satisfied (no remote/proxy transport).
                val selected = when (method) {
                    com.sendspindroid.model.ConnectionType.LOCAL -> server.local?.let {
                        ConnectionSelector.SelectedConnection.Local(it.address, it.path)
                    }
                    else -> null
                } ?: return@ConnectionCoordinator false
                connectViaSelectedConnection(server, selected)
            },
            context = applicationContext,
        )

        // Mirrors the Coordinator's NetworkState into the companion flow for
        // in-process observers (MainActivity).
        serviceScope.launch {
            coordinator.networkState.collect { state -> _networkState.value = state }
        }

        // Reconnect status: mirrored into the companion flow for in-process
        // observers (MainActivity) and broadcast to MediaController consumers.
        serviceScope.launch {
            var wasAttempting = false
            coordinator.reconnectStatus.collect { status ->
                _reconnectStatusRelay.value = status
                recordHandoff(status)
                broadcastSessionExtras()
                val attempting = status is ReconnectStatus.Attempting
                if (wasAttempting && !attempting) {
                    // The loop is over, one way or the other.
                    stopReconnectDiscovery()
                    mainHandler.removeCallbacks(reconnectLockRelease)
                    releaseConnectWakeLock()
                    releaseIfIdle()
                    noteDialEnded()
                }
                wasAttempting = attempting
            }
        }

        // A pairing record was removed in Settings: whichever connection it
        // admitted ends, dialled or opened by the server.
        serviceScope.launch {
            PairedServers.forgotten.collect { pskId ->
                dialClient?.leaveIfAdmittedBy(pskId)
                inboundConnections.leaveIfAdmittedBy(pskId)
            }
        }

        // NetworkEvent observer: dispatches Android-specific events (identity/link-address changes)
        // that cannot be encoded in the platform-neutral NetworkState.
        serviceScope.launch {
            coordinator.networkEvents.collect { event ->
                when (event) {
                    is com.sendspindroid.coordinator.NetworkEvent.IdentityChanged -> {
                        Log.i(TAG, "networkEvent: IdentityChanged handle=${event.networkHandle}")
                        sendSpinClient?.onNetworkChanged()
                        val connState = sendSpinClient?.connectionState?.value
                        val shouldReselect =
                            connState is TransportState.Ready ||
                            connState is TransportState.Connecting
                        if (shouldReselect) {
                            Log.i(TAG, "Triggering connection reselection for new network")
                            sendSpinClient?.disconnectForReselection()
                        }
                        advertiseAgainAfterNetworkChange()
                    }
                    is com.sendspindroid.coordinator.NetworkEvent.LinkAddressesChanged -> {
                        Log.i(TAG, "networkEvent: LinkAddressesChanged")
                        // The connection survives an address change on its
                        // network, and so does its clock estimate: resetting
                        // it here silenced a playing group for seconds and
                        // dropped the queued audio. A change that does break
                        // the path closes the socket, and the reconnect
                        // starts the clock over.
                        browseDiscoveryManager?.refreshMulticastLockIfActive()
                        advertiseAgainAfterNetworkChange()
                    }
                }
            }
        }

        sendSpinClient?.let { observeClient(it) }

        // Initialize network evaluator for passive network monitoring
        networkEvaluator = NetworkEvaluator(this)

        // Perform initial network evaluation
        networkEvaluator?.evaluateCurrentNetwork()

        // Initialize AudioManager for device volume control
        initializeVolumeControl()

        // Stop the sound when the audio output device disconnects.
        registerBecomingNoisyReceiver()

        // Last, with everything a connection needs in place: unless the user
        // chose to search for servers, start waiting for one to connect.
        connectionMode.start(searchForServers = UserSettings.searchForServers)
    }

    // What the connection-state collector last saw of the client being played from.
    private var prevSendSpinState: TransportState = TransportState.Idle

    // The same, for the coordinator, which outlives any one client.
    private val clientState = MutableStateFlow<TransportState>(TransportState.Idle)

    private var clientObserver: Job? = null

    /** Follow [client]'s connection, controller state and colors. One client at a time. */
    private fun observeClient(client: SendSpin) {
        clientObserver?.cancel()
        clientObserver = serviceScope.launch {
            // The media session offers only what the server's controller state allows.
            launch {
                client.controllerState.collect { state ->
                    sendSpinPlayer?.updateControllerState(state)
                }
            }
            launch { client.colorState.collect { _serverColors.value = it } }
            client.connectionState.collect { state -> onClientState(state) }
        }
    }

    /**
     * Play from [client] from now on: the dialling client, or the client of
     * a server that connected to us and was admitted. Whatever the previous
     * one was playing ends here, as if its connection had.
     */
    private fun useClient(client: SendSpin) {
        if (sendSpinClient === client) return
        clientObserver?.cancel()
        if (prevSendSpinState !is TransportState.Idle) onClientState(TransportState.Idle)
        sendSpinClient = client
        engine.client = client
        client.setExternalSource(otherAppHoldsOutput)
        sendSpinPlayer?.setSendSpinClient(client)
        admissionState = AdmissionState.READY
        pairingCode = null
        pairingGestureRequested = false
        observeClient(client)
    }

    /** Reacts to the connection state of the client being played from. */
    private fun onClientState(state: TransportState) {
        clientState.value = state
        if (state !is TransportState.Ready) engine.endStream()
        when {
            state is TransportState.Ready && prevSendSpinState !is TransportState.Ready -> {
                val serverName = sendSpinClient?.getServerName() ?: ""
                Log.d(TAG, "Connected to: $serverName")
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
                val serverAddr = sendSpinClient?.getServerName() ?: ""
                AppLog.session.start(serverName, serverAddr)
                startDebugLogging()

                // Broadcast connection state to controllers (MainActivity)
                broadcastConnectionState(STATE_CONNECTED, serverName)
            }
            state is TransportState.Idle && prevSendSpinState !is TransportState.Idle -> {
                Log.d(TAG, "Disconnected from server")

                // Stop debug logging session
                stopDebugLogging()
                AppLog.session.end()

                // Stop audio playback
                engine.releasePlayer()
                sendSpinPlayer?.updateConnectionState(
                    connected = false,
                    reconnecting = coordinator.reconnectStatus.value is ReconnectStatus.Attempting,
                )
                releaseIfIdle()

                // Refresh browse tree root so "Connect" reappears
                mediaSession?.notifyChildrenChanged(MEDIA_ID_ROOT, 0, null)

                // Broadcast disconnection to controllers (MainActivity)
                broadcastConnectionState(STATE_DISCONNECTED)

                // Clear playback state on disconnect
                _playbackState.value = _playbackState.value.withConnectionEnded()
                _artistArtwork.value = null
                _serverColors.value = null
                lastArtworkUrl = null
                lastTrackTitle = null
                urlArtwork = null
                binaryArtwork = null
                binaryArtworkGeneration++

                // Clear lock screen metadata
                forwardingPlayer?.clearMetadata()
            }
            state is TransportState.Failed -> {
                val message = failureReasonToMessage(state.reason)
                Log.e(TAG, "SendSpin error: $message")

                // Show error on Android Auto
                sendSpinPlayer?.setError(message)
                releaseIfIdle()

                // Broadcast error to controllers (MainActivity)
                broadcastConnectionState(STATE_ERROR, errorMessage = message)
            }
            state is TransportState.Connecting && prevSendSpinState !is TransportState.Connecting -> {
                // Announce the attempt from here, where the transition
                // has already happened. The connect* methods used to do
                // it on their first line, before anything had left Idle,
                // and the publisher reports observed state rather than
                // the argument it is handed - so that call announced
                // DISCONNECTED at the very moment a connection began.
                broadcastConnectionState(STATE_CONNECTING)
            }
        }
        prevSendSpinState = state
        noteDialEnded()
    }

    // ========== Server-initiated connections ==========

    /**
     * Whether the service advertises itself and waits for a server, searches
     * for servers, or is dialled out to one. See [ConnectionMode].
     */
    private val connectionMode = ConnectionMode(
        object : ConnectionMode.Advertising {
            override fun start() = startAdvertising()
            override fun stop(onStopped: () -> Unit) = stopAdvertising(onStopped)
        },
        DiscoveryGate,
    )

    private val inboundConnections = InboundConnections(
        scope = serviceScope,
        newClient = ::newInboundClient,
        onAdmitted = { client -> mainHandler.post { onServerAdmitted(client) } },
        lastPlaybackServerId = { UserSettings.lastPlaybackServerId },
        storeLastPlaybackServerId = { UserSettings.lastPlaybackServerId = it },
    )

    private var listener: SendspinWebSocketServer? = null
    private var advertiser: NsdAdvertiser? = null

    // Bumped by every start and stop, so a bind that finishes after its
    // advertising was stopped knows to undo itself.
    private var advertisingGeneration = 0

    /**
     * Listen for servers and announce the listener on mDNS. The service
     * holds the foreground while it waits, so a server can connect with the
     * app in the background.
     */
    private fun startAdvertising() {
        val generation = ++advertisingGeneration
        val name = UserSettings.getPlayerName()
        val server = SendspinWebSocketServer(
            onConnection = { inboundConnections.onConnection(it, it.remoteAddress) }
        )
        listener = server
        inboundConnections.open()
        startForegroundServiceWithNotification()
        serviceScope.launch {
            // Binding the socket is blocking I/O.
            val port = withContext(Dispatchers.IO) { runCatching { server.start() } }
                .getOrElse {
                    Log.e(TAG, "Cannot listen for servers", it)
                    if (generation == advertisingGeneration) setAdvertisingFailed(true)
                    return@launch
                }
            if (generation != advertisingGeneration) {
                withContext(Dispatchers.IO) { server.stop() }
                return@launch
            }
            // "Advertise the port actually bound": 8928 unless it was taken.
            advertiser = NsdAdvertiser(this@PlaybackService) { registered ->
                mainHandler.post {
                    if (generation == advertisingGeneration) setAdvertisingFailed(!registered)
                }
            }.also {
                it.start(name, port, SendspinWebSocketServer.DEFAULT_PATH)
            }
            _advertisedPort.value = port
            Log.i(TAG, "Advertising as '$name' on port $port, waiting for a server")
        }
    }

    /**
     * Servers cannot find or reach the app although it is meant to be
     * waiting for one: the listener could not be bound, or the mDNS
     * registration failed. Said on the default screen and in the
     * notification, so that "waiting" is never shown over nothing.
     */
    private fun setAdvertisingFailed(failed: Boolean) {
        if (_advertisingFailed.value == failed) return
        _advertisingFailed.value = failed
        if (connectionMode.isAdvertising && !isConnectedOrReconnecting()) {
            startForegroundServiceWithNotification()
        }
    }

    /**
     * The network changed while advertising: announce again, and if the
     * listener never came up, try that again too. No timer retries either;
     * a change of network is when the outcome can be different.
     */
    private fun advertiseAgainAfterNetworkChange() {
        if (!connectionMode.isAdvertising) return
        if (_advertisingFailed.value && _advertisedPort.value == null) {
            startAdvertising()
        } else {
            advertiser?.refresh()
        }
    }

    /**
     * Withdraw the announcement, end every connection a server opened to us
     * and close the listener.
     *
     * Closing the listener waits for those connections to get their goodbye
     * out, so it is done off the main thread; [onStopped] is called back on
     * it once the port is released. Nothing is dialled before that.
     */
    private fun stopAdvertising(onStopped: () -> Unit) {
        advertisingGeneration++
        advertiser?.stop()
        advertiser = null
        _advertisedPort.value = null
        _advertisingFailed.value = false
        // A server being played from is left with the rest.
        if (!isDestroyed) dialClient?.let { useClient(it) }
        inboundConnections.close()
        val server = listener
        listener = null
        when {
            server == null -> onStopped()
            // The service is going and its scope with it. Nothing waits for
            // the port, so the listener closes on a thread of its own.
            isDestroyed -> {
                thread(name = "SendspinListenerStop") { server.stop() }
                onStopped()
            }
            else -> serviceScope.launch {
                withContext(Dispatchers.IO) { server.stop() }
                Log.i(TAG, "No longer advertising")
                onStopped()
            }
        }
    }

    /** A client for one connection a server opens to us. Not on the main thread. */
    private fun newInboundClient(): SendSpin =
        SendSpin(UserSettings.getPlayerName(), SendSpinClientCallback(), AppSendSpinHost).also { client ->
            val am = audioManager ?: return@also
            client.setInitialVolume(currentVolumePercent(am), UserSettings.getPlayerMuted())
        }

    /** A server that connected to us was admitted: play from its connection. */
    private fun onServerAdmitted(client: SendSpin) {
        if (isDestroyed || !connectionMode.isAdvertising) return
        // There is no saved server behind this connection and nothing to
        // reconnect to: the server comes back to us.
        _currentServerFlow.value = null
        useClient(client)
    }

    /**
     * Tell [connectionMode] once the dialled connection and its reconnect
     * loop are both gone, so the app returns to the mode that is selected.
     */
    private fun noteDialEnded() {
        val state = dialClient?.connectionState?.value
        val alive = state is TransportState.Ready || state is TransportState.Connecting ||
            coordinator.reconnectStatus.value is ReconnectStatus.Attempting
        if (!alive) connectionMode.dialEnded()
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
        fullVolumeOutputs += UserSettings.getFullVolumeOutputs()

        // Track current volume to detect external changes
        lastKnownVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        lastOutputType = audioManager?.let { mediaOutputType(it) }

        // Initialize playback state with actual device volume (not default 100%),
        // or, where the index cannot hold one, the volume last set on the output
        val restored = audioManager?.let { restoredVolume(it, lastKnownVolume, mediaOutputType(it)) }
        restored?.let {
            Log.i(TAG, "Volume $it restored as track gain on output type $lastOutputType")
            engine.setTrackGain(VolumeLaw.amplitude(it))
        }
        val volumePercent = restored ?: audioManager?.let { deviceVolumePercent(it, lastKnownVolume) } ?: 0
        _playbackState.value = _playbackState.value.copy(volume = volumePercent)

        // Create observer to detect volume changes from hardware buttons
        volumeObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return

                // Only sync to server if volume actually changed (not our own change)
                if (currentVolume != lastKnownVolume) {
                    Log.d(TAG, "Device volume changed via hardware buttons: index $currentVolume")
                    syncDeviceVolumeToServer(currentVolume)
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

        // A new output brings its own stored index and its own volume curve,
        // without any setting being written: report what plays now.
        audioManager?.registerAudioDeviceCallback(outputDeviceCallback, mainHandler)

        Log.d(TAG, "Volume control initialized - using device STREAM_MUSIC")
    }

    /**
     * Initializes the native Kotlin SendSpin client.
     */
    @OptIn(UnstableApi::class)
    /**
     * Latest admission state for the live connection.
     *
     * Reset per connection: a stale value from a previous session would
     * otherwise be published in the window between reconnecting and the first
     * `server/activate` of the new session.
     */
    @Volatile
    private var admissionState: AdmissionState = AdmissionState.READY

    /** The dynamic pairing code currently shown to the operator, or null. */
    @Volatile
    private var pairingCode: String? = null

    /** The emission format of [pairingCode]; meaningful only while that is set. */
    @Volatile
    private var pairingCodeFormat: PairingCodeFormat = PairingCodeFormat.DIGITS

    /** Whether a dynamic pairing attempt is waiting on the "Allow pairing" gesture. */
    @Volatile
    private var pairingGestureRequested: Boolean = false

    private fun initializeSendSpinClient() {
        try {
            admissionState = AdmissionState.READY
            pairingCode = null
            pairingGestureRequested = false
            // Use user-configured player name, falls back to device model
            val playerName = com.sendspindroid.UserSettings.getPlayerName()
            appliedPlayerName = playerName
            dialClient = SendSpin(
                deviceName = playerName,
                callback = SendSpinClientCallback(),
                host = AppSendSpinHost,
            )
            sendSpinClient = dialClient
            engine.client = dialClient
            sendSpinPlayer?.setSendSpinClient(sendSpinClient)
            Log.d(TAG, "SendSpin initialized with name: $playerName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SendSpin", e)
        }
    }

    /**
     * Callback for SendSpin events.
     */
    private inner class SendSpinClientCallback : SendSpin.Callback {

        override fun onDisconnected(reconnect: Boolean) {
            mainHandler.post { onConnectionEnded(reconnect) }
        }

        override fun onAdmissionStateChanged(state: AdmissionState) {
            mainHandler.post {
                if (admissionState == state) return@post
                Log.i(TAG, "Admission state: $admissionState -> $state")
                admissionState = state
                broadcastSessionExtras()
            }
        }

        override fun onDynamicPairingCodeEmitted(code: String, format: PairingCodeFormat) {
            mainHandler.post {
                pairingCode = code
                pairingCodeFormat = format
                broadcastSessionExtras()
            }
        }

        override fun onDynamicPairingCodeCleared() {
            mainHandler.post {
                // StopEmittingCode fires on every terminal path (success, abort,
                // timeout, superseding activation) - including one where the
                // attempt never got past AWAITING_GESTURE and no code was ever
                // emitted. A terminal ends both the code display and any
                // pending gesture request, so both clear here together;
                // leaving the gesture flag set would strand the "Allow
                // pairing" button on screen with no live attempt behind it.
                pairingCode = null
                pairingGestureRequested = false
                broadcastSessionExtras()
            }
        }

        override fun onDynamicPairingGestureRequested() {
            mainHandler.post {
                pairingGestureRequested = true
                broadcastSessionExtras()
            }
        }

        override fun onStateChanged(state: String) {
            mainHandler.post {
                Log.d(TAG, "State changed: $state")
                val newState = PlaybackStateType.fromString(state)
                noteServerPlaybackState(newState)

                // Handle playback state transitions per SendSpin spec
                if (newState == PlaybackStateType.STOPPED) {
                    // Stop: "reset position to beginning" - clear buffer
                    Log.d(TAG, "State is stopped - clearing audio buffer and releasing playback locks")
                    sendSpinPlayer?.updatePlayWhenReadyFromServer(false)
                    engine.applyServerPlayback(newState)
                    releasePlaybackLocks()
                } else if (newState == PlaybackStateType.PAUSED) {
                    // Pause: "maintains current position for later resumption" - keep buffer
                    Log.d(TAG, "State is paused - pausing audio (keeping buffer)")
                    sendSpinPlayer?.updatePlayWhenReadyFromServer(false)
                    engine.applyServerPlayback(newState)
                    releasePlaybackLocks()
                } else if (newState == PlaybackStateType.PLAYING) {
                    // Playing: resume playback if paused
                    Log.d(TAG, "State is playing - resuming audio and acquiring playback locks")
                    sendSpinPlayer?.updatePlayWhenReadyFromServer(true)
                    engine.applyServerPlayback(newState)
                    acquirePlaybackLocks()
                }

                _playbackState.value = _playbackState.value.copy(playbackState = newState)
            }
        }

        override fun onPlayerRoleChanged(active: Boolean) {
            if (active) return
            mainHandler.post {
                // No longer a player: nothing of ours will play on this connection
                Log.i(TAG, "Player role removed - releasing the output and its locks")
                engine.releasePlayer()
                releasePlaybackLocks()
            }
        }

        @OptIn(UnstableApi::class)
        override fun onGroupUpdate(groupId: String, groupName: String, playbackState: String) {
            mainHandler.post {
                Log.d(TAG, "Group update: id=$groupId name=$groupName state=$playbackState")

                val currentState = _playbackState.value
                val newPlaybackState = PlaybackStateType.fromString(playbackState)
                if (playbackState.isNotEmpty()) noteServerPlaybackState(newPlaybackState)

                // Handle playback state transitions per SendSpin spec
                if (playbackState.isNotEmpty()) {
                    when (newPlaybackState) {
                        PlaybackStateType.STOPPED -> {
                            // Stop: "reset position to beginning" - clear buffer
                            Log.d(TAG, "Playback stopped - clearing audio buffer and releasing playback locks")
                            sendSpinPlayer?.updatePlayWhenReadyFromServer(false)
                            engine.applyServerPlayback(newPlaybackState)
                            releasePlaybackLocks()
                        }
                        PlaybackStateType.PAUSED -> {
                            // Pause: "maintains current position for later resumption" - keep buffer
                            Log.d(TAG, "Playback paused - pausing audio (keeping buffer)")
                            sendSpinPlayer?.updatePlayWhenReadyFromServer(false)
                            engine.applyServerPlayback(newPlaybackState)
                            releasePlaybackLocks()
                        }
                        PlaybackStateType.PLAYING -> {
                            // Playing: resume playback if paused
                            Log.d(TAG, "Playback playing - resuming audio and acquiring playback locks")
                            sendSpinPlayer?.updatePlayWhenReadyFromServer(true)
                            engine.applyServerPlayback(newPlaybackState)
                            sendSpinPlayer?.setSyncAudioPlayer(engine.hostPlayer)
                            acquirePlaybackLocks()
                        }
                        else -> { /* No action needed */ }
                    }
                }

                // The track metadata is left alone, also when the group id is
                // new: it belongs to server/state, which the server sends
                // before the first group/update and does not repeat after it.
                _playbackState.value = currentState.withGroupUpdate(
                    groupId = groupId.ifEmpty { currentState.groupId },
                    groupName = groupName.ifEmpty { currentState.groupName },
                    playbackState = if (playbackState.isNotEmpty())
                        newPlaybackState
                    else currentState.playbackState
                )

                // Broadcast all state including group name to controllers (MainActivity)
                broadcastSessionExtras()
            }
        }

        @OptIn(UnstableApi::class)
        override fun onMetadataUpdate(metadata: TrackMetadata, positionMs: Long) {
            // Null means the server has no value for the field. The text
            // fields carry that on as "".
            val title = metadata.title.orEmpty()
            val artist = metadata.artist.orEmpty()
            val album = metadata.album.orEmpty()
            val artworkUrl = metadata.artworkUrl.orEmpty()
            val durationMs = metadata.durationMs
            // No progress object means no position, so nothing to advance.
            val playbackSpeed = metadata.progress?.playbackSpeed ?: 0
            mainHandler.post {
                Log.d(TAG, "Metadata update: $title / $artist / $album, album artist " +
                    "${metadata.albumArtist}, year ${metadata.year}, track ${metadata.track}")

                // server/state carries the role's full state, so "" here
                // means the track has no such field. It is passed on as "",
                // which withMetadata clears; null would keep the last track's.
                _playbackState.value = _playbackState.value.withMetadata(
                    title = title,
                    artist = artist,
                    album = album,
                    artworkUrl = artworkUrl,
                    durationMs = durationMs,
                    positionMs = positionMs,
                    playbackSpeed = playbackSpeed,
                    albumArtist = metadata.albumArtist,
                    year = metadata.year,
                    track = metadata.track
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
                    durationMs = durationMs,
                    playbackSpeed = playbackSpeed
                )

                // A title or URL change invalidates the URL artwork so the
                // notification doesn't briefly show the prior track's image
                // alongside the new track's title. The URL fetch kicked off
                // below repopulates it. Coil caches by URL so a re-fetch on
                // the same album is essentially free. Binary artwork is left
                // alone: the artwork stream replaces or clears it itself.
                val newTitle = title.ifEmpty { null }
                val titleChanged = newTitle != lastTrackTitle
                lastTrackTitle = newTitle

                if (artworkUrl.isEmpty()) {
                    lastArtworkUrl = null
                    urlArtwork = null
                } else if (artworkUrl != lastArtworkUrl || titleChanged) {
                    lastArtworkUrl = artworkUrl
                    urlArtwork = null
                    fetchArtwork(artworkUrl)
                }

                updateMediaMetadata(title, artist, album)
            }
        }

        override fun onArtwork(imageData: ByteArray) {
            // Skip artwork processing in low memory mode
            if (com.sendspindroid.UserSettings.lowMemoryMode) {
                return
            }

            // Posted like onArtworkCleared, so an image and a clear take effect
            // in the order the server sent them.
            mainHandler.post {
                Log.d(TAG, "Artwork received: ${imageData.size} bytes")
                val generation = ++binaryArtworkGeneration
                serviceScope.launch {
                    val scaled = try {
                        withContext(Dispatchers.IO) {
                            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                            bitmap?.let { scaleArtwork(it) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode artwork", e)
                        null
                    }
                    if (generation != binaryArtworkGeneration) return@launch
                    // An image that does not decode still replaces the one
                    // before it; effectiveArtwork decides whether it is shown.
                    binaryArtwork = scaled
                    updateMediaMetadata(
                        _playbackState.value.title ?: "",
                        _playbackState.value.artist ?: "",
                        _playbackState.value.album ?: ""
                    )
                }
            }
        }

        override fun onArtistArtwork(imageData: ByteArray?) {
            if (com.sendspindroid.UserSettings.lowMemoryMode) return
            Log.d(TAG, "Artist image: ${imageData?.size ?: 0} bytes")
            _artistArtwork.value = imageData
        }

        override fun onArtworkCleared() {
            mainHandler.post {
                Log.d(TAG, "Artwork cleared by server (empty payload)")
                binaryArtworkGeneration++
                binaryArtwork = null
                updateMediaMetadata(
                    _playbackState.value.title ?: "",
                    _playbackState.value.artist ?: "",
                    _playbackState.value.album ?: ""
                )
            }
        }

        override fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) =
            engine.onStreamStart(codec, sampleRate, channels, bitDepth, codecHeader)

        override fun onStreamClear() = engine.onStreamClear()

        override fun onStreamEnd() = engine.onStreamEnd()

        override fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray) =
            engine.onAudioChunk(serverTimeMicros, audioData)

        override fun onVolumeChanged(volume: Int) {
            Log.i(TAG, "[cmd-trace] T2 onVolumeChanged ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name} vol=$volume")
            mainHandler.post {
                Log.i(TAG, "[cmd-trace] T3 onVolumeChanged.post ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name} vol=$volume")
                // Convert from 0-100 to 0.0-1.0 and apply to device volume
                val volumeFloat = volume / 100f
                setVolume(volumeFloat)  // Sets device STREAM_MUSIC volume and the cached volume
                // Broadcast all state including volume to UI controllers
                broadcastSessionExtras()
            }
        }

        override fun onMutedChanged(muted: Boolean) {
            mainHandler.post { applyPlayerMuted(muted) }
        }

        override fun onSyncOffsetApplied(offsetMs: Double, source: String) {
            android.util.Log.i(TAG, "Sync offset applied: ${offsetMs}ms from $source")
            mainHandler.post {
                lastSyncOffsetMs = offsetMs
                lastSyncOffsetSource = source
                broadcastSessionExtras()
            }
        }

        override fun onSyncMuteChanged(muted: Boolean) = engine.onSyncMuteChanged(muted)

        override fun onNetworkChanged() = engine.onNetworkChanged()

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
                        // The track moved on while this was loading.
                        if (url != lastArtworkUrl) return@post
                        urlArtwork = scaled
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

    @OptIn(UnstableApi::class)
    private fun updateMediaSessionArtwork(bitmap: Bitmap) {
        val state = _playbackState.value

        // The state holds the metadata role's full state (messaging.md,
        // server/state), so a null text field is one the track has none of.
        // It goes on as "", which clears it; null would keep the last track's.
        forwardingPlayer?.updateMetadata(
            title = state.title.orEmpty(),
            artist = state.artist.orEmpty(),
            album = state.album.orEmpty(),
            artwork = bitmap,
            artworkUri = state.artworkUrl?.let { Uri.parse(it) },
            albumArtist = state.albumArtist,
            year = state.year,
            trackNumber = state.track
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

    @OptIn(UnstableApi::class)
    private fun updateMediaMetadata(title: String, artist: String, album: String) {
        val state = _playbackState.value

        // "" clears a text field the track has none of, as in
        // updateMediaSessionArtwork.
        forwardingPlayer?.updateMetadata(
            title = state.title.orEmpty(),
            artist = state.artist.orEmpty(),
            album = state.album.orEmpty(),
            artwork = effectiveArtwork,
            artworkUri = state.artworkUrl?.let { Uri.parse(it) },
            albumArtist = state.albumArtist,
            year = state.year,
            trackNumber = state.track
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
        val sessionState = coordinator.sessionState.value
        val reconnectStatus = coordinator.reconnectStatus.value

        // coordinator.sessionState is a combine() of several upstream flows, so it
        // settles a beat after sendSpinClient.connectionState - the flow every
        // collector here reacts to. Deriving the published state from the lagging
        // copy made a broadcast triggered by a client transition announce the
        // PREVIOUS state.
        val sendSpinState = sendSpinClient?.connectionState?.value ?: sessionState.sendSpin

        // A reconnect in progress outranks what the attempt in flight looks
        // like: the loop goes through Connecting, Idle and Failed on its way.
        val connectionStateString = when {
            sendSpinState is TransportState.Ready -> STATE_CONNECTED
            reconnectStatus is ReconnectStatus.Attempting -> STATE_RECONNECTING
            sendSpinState is TransportState.Failed -> STATE_ERROR
            sendSpinState is TransportState.Connecting -> STATE_CONNECTING
            else -> STATE_DISCONNECTED
        }

        val serverName: String? = sessionState.server?.name
            ?: sendSpinClient?.takeIf { it.isServerInitiated }?.getServerName()

        // Same source as connectionStateString above, or STATE_ERROR could be
        // published with a null message when the two copies disagree.
        val errorMessage: String? = when (sendSpinState) {
            is TransportState.Failed -> failureReasonToMessage(sendSpinState.reason)
            else -> null
        }

        val extras = Bundle().apply {
            // Connection state
            when (connectionStateString) {
                STATE_DISCONNECTED -> putString(EXTRA_CONNECTION_STATE, STATE_DISCONNECTED)
                STATE_CONNECTING -> putString(EXTRA_CONNECTION_STATE, STATE_CONNECTING)
                STATE_CONNECTED -> {
                    putString(EXTRA_CONNECTION_STATE, STATE_CONNECTED)
                    serverName?.let { putString(EXTRA_SERVER_NAME, it) }
                    putString(EXTRA_ADMISSION_STATE, admissionState.name)
                    pairingCode?.let {
                        putString(EXTRA_PAIRING_CODE, it)
                        putString(EXTRA_PAIRING_CODE_FORMAT, pairingCodeFormat.wire)
                    }
                    putBoolean(EXTRA_PAIRING_GESTURE_REQUESTED, pairingGestureRequested)
                }
                STATE_RECONNECTING -> {
                    putString(EXTRA_CONNECTION_STATE, STATE_RECONNECTING)
                    serverName?.let { putString(EXTRA_SERVER_NAME, it) }
                    (reconnectStatus as? ReconnectStatus.Attempting)?.let {
                        putInt(EXTRA_RECONNECT_ATTEMPT, it.attempt)
                    }
                }
                STATE_ERROR -> {
                    putString(EXTRA_CONNECTION_STATE, STATE_ERROR)
                    errorMessage?.let { putString(EXTRA_ERROR_MESSAGE, it) }
                }
            }

            // Metadata
            putString(EXTRA_TITLE, playbackState.title ?: "")
            putString(EXTRA_ARTIST, playbackState.artist ?: "")
            putString(EXTRA_ALBUM, playbackState.album ?: "")
            putString(EXTRA_ARTWORK_URL, playbackState.artworkUrl ?: "")
            putLong(EXTRA_DURATION_MS, playbackState.durationMs)
            putLong(EXTRA_POSITION_MS, playbackState.positionMs)
            putLong(EXTRA_POSITION_UPDATED_AT, playbackState.positionUpdatedAt)
            putInt(EXTRA_PLAYBACK_SPEED, playbackState.playbackSpeed)
            playbackState.albumArtist?.let { putString(EXTRA_ALBUM_ARTIST, it) }
            playbackState.year?.let { putInt(EXTRA_YEAR, it) }

            // Group info
            playbackState.groupName?.let { putString(EXTRA_GROUP_NAME, it) }

            // Volume
            putInt(EXTRA_VOLUME, playbackState.volume)
            putBoolean(EXTRA_MUTED, playbackState.muted)

            // Sync offset (included here to avoid bare-bundle overwrites that
            // would clobber volume, metadata, and connection state)
            if (lastSyncOffsetMs != 0.0) {
                putDouble("sync_offset_ms", lastSyncOffsetMs)
                putString("sync_offset_source", lastSyncOffsetSource)
            }
        }

        mediaSession?.setSessionExtras(extras)
    }

    private fun failureReasonToMessage(reason: FailureReason): String = when (reason) {
        is FailureReason.AuthRejected -> "Authentication failed"
        is FailureReason.HandshakeFailed -> "Could not establish connection"
        is FailureReason.TransientNetwork -> "Network error"
        is FailureReason.ProtocolError -> "Protocol error"
        is FailureReason.ServerLacksEncryption ->
            "This server does not support encrypted connections. " +
                "Music Assistant 2.9 or newer is required."
    }

    /**
     * Connects to a SendSpin server because someone chose it: the user or
     * Android Auto. Takes over from a reconnect loop still trying for the
     * previous server.
     *
     * @param address Server address in "host:port" format
     * @param path WebSocket path (default: /sendspin)
     */
    fun connectToServer(address: String, path: String = "/sendspin") {
        coordinator.cancelReconnect()
        openConnection(address, path)
    }

    /**
     * Every connection this service dials is opened here, once the
     * advertisement and the listener are gone: at once when nothing is
     * advertised, which is always so in search mode and in the reconnect
     * loop, and otherwise as soon as the listener has closed. See
     * [ConnectionMode].
     */
    private fun openConnection(address: String, path: String) {
        connectionMode.dial {
            dialServer(address, path)
            // An attempt that failed before it began has ended already.
            mainHandler.post { noteDialEnded() }
        }
    }

    private fun dialServer(address: String, path: String) {
        Log.d(TAG, "Connecting to server: $address path=$path")

        // No broadcast here: the coordinator is still Idle at this point, and
        // the publisher reports whatever state the coordinator is in. The
        // Connecting branch of the connectionState collector announces it.

        try {
            // No disconnect() first: connect() leaves the current server
            // itself, with the goodbye reason a server switch requires.

            // Read current device volume and set as initial volume for server and UI
            val am = audioManager
            if (am != null) {
                val volumePercent = currentVolumePercent(am)
                Log.d(TAG, "Setting initial volume from device: $volumePercent%")
                // Mute is restored from settings: it is the app's own state,
                // where volume is the device's.
                val muted = com.sendspindroid.UserSettings.getPlayerMuted()
                sendSpinClient?.setInitialVolume(volumePercent, muted)
                // Also update playback state so UI shows correct volume from the start
                _playbackState.value = _playbackState.value.copy(volume = volumePercent, muted = muted)
            }

            sendSpinClient?.connect(SendSpinEndpoint.Local(address, path))
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to server", e)
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
        // Use unified broadcast to avoid overwriting other extras
        broadcastSessionExtras()
    }

    /**
     * Disconnects from the current server.
     */
    fun disconnectFromServer(onFlushed: () -> Unit = {}) {
        Log.d(TAG, "Disconnecting from server")
        val client = sendSpinClient
        if (client == null) onFlushed() else client.disconnect(onFlushed)
    }

    /**
     * An established connection has ended. The coordinator's loop takes it
     * back, for the server the service was connected to, unless the client
     * chose to leave (user disconnect, server switch, server/unpair, rejected
     * activation). No activity needs to be alive.
     *
     * Runs before the connection-state collector sees the connection gone, so
     * that it finds the loop already running and leaves the service in the
     * foreground.
     */
    private fun onConnectionEnded(reconnect: Boolean) {
        if (isDestroyed) return
        val server = _currentServerFlow.value
        if (!reconnect || server == null) {
            coordinator.cancelReconnect()
            return
        }
        Log.i(TAG, "Connection to ${server.name} lost - reconnecting")
        startReconnectLoop(server)
    }

    /**
     * Start the coordinator's loop for [server], which keeps trying until it
     * connects or is cancelled. Every connection the service makes on its own
     * starts here, so none is tried only once: after a drop, at boot, and
     * when the system restarts the service.
     */
    private fun startReconnectLoop(server: UnifiedServer) {
        startForegroundServiceWithNotification(server.name, reconnecting = true)
        mainHandler.removeCallbacks(reconnectLockRelease)
        mainHandler.postDelayed(reconnectLockRelease, RECONNECT_LOCK_HOLD_MS)
        startReconnectDiscovery(server)
        coordinator.connect(server)
    }

    // Playback locks are kept across a drop so the loop can run with the
    // screen off, but not for as long as a server may stay away. High Power
    // Mode's own locks are the user's choice and stay.
    private val reconnectLockRelease = Runnable {
        if (coordinator.reconnectStatus.value is ReconnectStatus.Attempting) {
            Log.i(TAG, "Still reconnecting after ${RECONNECT_LOCK_HOLD_MS / 60000}min - releasing playback locks")
            releasePlaybackLocks()
        }
    }

    private fun isConnectedOrReconnecting(): Boolean {
        val state = sendSpinClient?.connectionState?.value
        return state is TransportState.Ready || state is TransportState.Connecting ||
            coordinator.reconnectStatus.value is ReconnectStatus.Attempting
    }

    /**
     * Media3 takes the service out of the foreground on every notification
     * update while nothing is playing, and removes the notification when
     * there is nothing to show. A connected player that is idle or paused
     * would then be an ordinary background app: its network is cut within
     * seconds of the activity leaving the screen and the server loses the
     * player. The foreground is held for as long as there is a connection.
     */
    @OptIn(UnstableApi::class)
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // Media3 asks for the foreground while the player still reports
        // playWhenReady, and for some time after, and starts the service to
        // get it. On exit nothing may start it again (#327).
        if (exiting) {
            super.onUpdateNotification(session, false)
            return
        }
        // Waiting for a server to connect holds it too: the listener has to
        // stay reachable with the app in the background.
        val hold = isConnectedOrReconnecting() || connectionMode.isAdvertising
        super.onUpdateNotification(session, startInForegroundRequired || hold)
        // With nothing to show, Media3 has just removed the notification and
        // the foreground with it. Otherwise its media notification holds it.
        val player = session.player
        if (hold && (player.currentTimeline.isEmpty || player.playbackState == Player.STATE_IDLE)) {
            startForegroundServiceWithNotification(
                connectedServerName(),
                reconnecting = coordinator.reconnectStatus.value is ReconnectStatus.Attempting,
            )
        }
    }

    /**
     * The server to name as connected or reconnecting: the saved server that
     * was dialled, or the server that connected to us.
     */
    private fun connectedServerName(): String? =
        _currentServerFlow.value?.name
            ?: sendSpinClient?.takeIf { it.isServerInitiated && it.isConnected }?.getServerName()

    /**
     * Give up the foreground and every lock once nothing is connected and
     * nothing is trying to be. While the reconnect loop runs the service is
     * still a media playback service with a session to restore, so it keeps
     * both.
     */
    private fun releaseIfIdle() {
        if (isConnectedOrReconnecting()) return
        sendSpinPlayer?.updateConnectionState(false)
        releasePlaybackLocks()
        releaseHighPowerLocks()
        if (connectionMode.isAdvertising) {
            // Still waiting for a server: say so, and stay reachable.
            startForegroundServiceWithNotification()
        } else {
            stopForegroundNotification()
        }
    }

    /**
     * Watch mDNS for [server] while the reconnect loop runs. Seeing it
     * announce itself retries immediately, and refreshes the saved address
     * the next attempt reads.
     */
    private fun startReconnectDiscovery(server: UnifiedServer) {
        stopReconnectDiscovery()
        reconnectDiscoveryManager = NsdDiscoveryManager(this, object : NsdDiscoveryManager.DiscoveryListener {
            override fun onServerDiscovered(name: String, address: String, path: String, friendlyName: String) {
                UnifiedServerRepository.addDiscoveredServer(friendlyName, address, path)
                if (isSameServer(server, name, friendlyName, address)) {
                    // addDiscoveredServer refreshes a saved server by its
                    // friendly name only, and this one may be saved under
                    // its service name.
                    UnifiedServerRepository.refreshSavedServerAddressById(server.id, address)
                    Log.i(TAG, "${server.name} seen on mDNS at $address - retrying now")
                    coordinator.retryNow()
                }
            }
            override fun onServerLost(name: String) {}
            override fun onDiscoveryStarted() {}
            override fun onDiscoveryStopped() {}
            override fun onDiscoveryError(error: String) {
                Log.w(TAG, "Reconnect: mDNS discovery error: $error")
            }
        }).also { it.startDiscovery() }
    }

    private fun stopReconnectDiscovery() {
        // cleanup() (not stopDiscovery()) so the multicast lock is released
        // even if onDiscoveryStarted never fired.
        reconnectDiscoveryManager?.cleanup()
        reconnectDiscoveryManager = null
    }

    /**
     * Sets the current server ID.
     * Call this before connecting when the server ID is known.
     *
     * @param serverId The UnifiedServer.id
     */
    fun setCurrentServer(serverId: String?) {
        Log.d(TAG, "Set current server: $serverId")

        _currentServerFlow.value = serverId?.let { UnifiedServerRepository.getServer(it) }
        // Kept for a restart by the system, which starts a new process.
        UserSettings.dialledServerId = serverId
    }

    /**
     * One attempt of the reconnect loop: opens the connection, then awaits
     * the SendSpin.connectionState transition out of Connecting.
     *
     * Returns true on Ready, false on anything else or on timeout.
     */
    private suspend fun connectViaSelectedConnection(
        server: com.sendspindroid.model.UnifiedServer,
        selectedConnection: ConnectionSelector.SelectedConnection,
    ): Boolean {
        // Short-circuit if SendSpin client construction failed in onCreate.
        val client = sendSpinClient ?: return false

        // Set the active server first so observers see context immediately.
        setCurrentServer(server.id)

        // A connection someone started while the loop was waiting is the one
        // to wait for, not one to replace.
        val busy = client.connectionState.value.let {
            it is TransportState.Connecting || it is TransportState.Ready
        }
        if (!busy) {
            when (selectedConnection) {
                is ConnectionSelector.SelectedConnection.Local ->
                    openConnection(selectedConnection.address, selectedConnection.path)
            }
        }

        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            client.connectionState.first { it !is TransportState.Connecting } is TransportState.Ready
        } ?: run {
            Log.w(TAG, "connectViaSelectedConnection timed out after ${CONNECT_TIMEOUT_MS}ms; cancelling transport")
            disconnectFromServer()
            false
        }
    }

    /**
     * The user mutes or un-mutes this player. Reported to the server when
     * connected; otherwise the next connection reports the persisted state.
     *
     * Main thread only.
     */
    fun setPlayerMuted(muted: Boolean) {
        sendSpinClient?.setMuted(muted)
        applyPlayerMuted(muted)
    }

    /**
     * Mute or un-mute this player, from the server's `mute` command or the
     * user. Mute silences the output and leaves the volume alone: the two are
     * independent, and a later volume change must not make a muted player
     * audible. Persisted, as roles/player/v1.md recommends.
     *
     * Main thread only.
     */
    private fun applyPlayerMuted(muted: Boolean) {
        engine.setMuted(SyncAudioPlayer.MuteReason.PLAYER, muted)
        UserSettings.setPlayerMuted(muted)
        _playbackState.value = _playbackState.value.copy(muted = muted)
        broadcastSessionExtras()
    }

    /**
     * Sets the playback volume, mostly via device STREAM_MUSIC (Spotify-style).
     *
     * Volume stays on the device's media stream so the hardware keys work.
     * The spec asks for `amplitude = (volume / 100)^1.5` (roles/player/v1.md),
     * so the index is picked from the device's own curve and the rest is gain
     * on the track ([VolumeLaw]). On a fixed-volume or full-volume output the
     * whole of it is track gain, and it is kept for that output
     * ([UserSettings.getOutputVolume]), as Android keeps none there.
     *
     * Main thread only.
     *
     * @param volume Normalized volume from 0.0 (mute) to 1.0 (full)
     */
    fun setVolume(volume: Float) {
        val am = audioManager ?: return
        val percent = (volume * 100).roundToInt().coerceIn(0, 100)

        // Every session-extras broadcast resends this value to the activity, so
        // it has to follow each volume set here or the slider snaps back to it.
        _playbackState.value = _playbackState.value.copy(volume = percent)

        if (am.isVolumeFixed) {
            Log.d(TAG, "Fixed-volume output: volume $percent as track gain")
            engine.setTrackGain(VolumeLaw.amplitude(percent))
            UserSettings.setOutputVolume(mediaOutputType(am), percent)
            return
        }

        val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val output = mediaOutputType(am)
        val curve = deviceVolumeCurve(am, output)
        val newVolume = if (curve != null) {
            VolumeLaw.indexFor(percent, maxVolume, curve)
        } else {
            // No curve to go by: a linear index, and never the silent one
            // for a volume above 0.
            (volume * maxVolume).roundToInt().coerceIn(if (percent > 0) 1 else 0, maxVolume)
        }

        Log.d(TAG, "Setting device volume: $newVolume/$maxVolume (volume $percent)")

        // Update tracking to prevent echo in observer
        lastKnownVolume = newVolume
        lastOutputType = output

        // Set device volume (no flags = silent, no UI popup)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
        Log.i(TAG, "[cmd-trace] T4 setStreamVolume ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name} new=$newVolume max=$maxVolume")

        // The track gain goes with the index that plays, so it is set once
        // the index is read back.
        val applied = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        when {
            VolumeLaw.isFullVolumeSet(output in fullVolumeOutputs, newVolume, applied, maxVolume) -> {
                // The index stays at max: the whole volume is track gain, as
                // on a fixed-volume output, and the volume asked for plays.
                Log.d(TAG, "Full-volume output: volume $percent as track gain")
                lastKnownVolume = applied
                if (fullVolumeOutputs.add(output)) UserSettings.setFullVolumeOutputs(fullVolumeOutputs)
                engine.setTrackGain(VolumeLaw.amplitude(percent))
                UserSettings.setOutputVolume(output, percent)
            }
            applied == newVolume ->
                engine.setTrackGain(curve?.let { VolumeLaw.trackGainFor(percent, newVolume, it) } ?: 1f)
            else -> {
                // The platform holds the index below what was asked (the safe
                // media volume warning): report what actually plays.
                Log.w(TAG, "Device volume held at $applied, asked for $newVolume")
                syncDeviceVolumeToServer(applied)
            }
        }
    }

    /** Media attributes, as the player's AudioTrack uses them, to ask where media is routed. */
    private val mediaAttributes = AndroidAudioAttributes.Builder()
        .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
        .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /**
     * The type of the output media plays on: the routed one from API 33,
     * below that the one AudioPolicy picks for media of those connected
     * ([VolumeLaw.mediaOutputType]).
     */
    private fun mediaOutputType(am: AudioManager): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            am.getAudioDevicesForAttributes(mediaAttributes).firstOrNull()?.type
                ?: android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            VolumeLaw.mediaOutputType(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type })
        }

    /**
     * The device's own gain in dB at each STREAM_MUSIC index, on the output
     * media plays on now; null where the platform cannot say (below API 28).
     * On an absolute-volume output (most Bluetooth A2DP and LE audio, HDMI
     * with CEC) the sink applies its own curve to the index, and this one
     * only stands in for it.
     */
    private fun deviceVolumeCurve(am: AudioManager, deviceType: Int = mediaOutputType(am)): ((Int) -> Double)? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val min = am.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val table = (0..max).map { index ->
                if (index < min) Double.NEGATIVE_INFINITY
                else am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, deviceType).toDouble()
            }
            if (VolumeLaw.isUsableCurve(max, table::get)) {
                table::get
            } else {
                Log.w(TAG, "Unusable dB volume curve for output type $deviceType; using native steps")
                null
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "No volume curve for output type $deviceType", e)
            null
        }
    }

    /**
     * The volume playing now: the one last set from here while the device is
     * still on the index set for it (the track gain is part of it), else what
     * the device's index stands for.
     */
    private fun currentVolumePercent(am: AudioManager): Int {
        val index = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (index == lastKnownVolume) _playbackState.value.volume else deviceVolumePercent(am, index)
    }

    /**
     * Media plays on an output setVolume found to be full-volume (its type
     * from the same mediaOutputType, so below API 33 too), with the index
     * still at max.
     */
    private fun isFullVolume(am: AudioManager, output: Int, index: Int): Boolean =
        output in fullVolumeOutputs && index == am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    /**
     * The volume last set on [output], where media plays on it at STREAM_MUSIC
     * [index] and that index cannot hold one ([VolumeLaw.restoredVolume]).
     */
    private fun restoredVolume(am: AudioManager, index: Int, output: Int): Int? =
        VolumeLaw.restoredVolume(am.isVolumeFixed, isFullVolume(am, output, index), UserSettings.getOutputVolume(output))

    /** The protocol volume (0-100) that STREAM_MUSIC [index] stands for on the current output. */
    private fun deviceVolumePercent(am: AudioManager, index: Int): Int {
        val curve = deviceVolumeCurve(am)
        if (curve != null && !am.isVolumeFixed) return VolumeLaw.volumeFor(index, curve)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return ((index.toFloat() / max) * 100).roundToInt()
    }

    /**
     * The device volume changed outside the app (hardware keys, another
     * output): drop the track gain, which belonged to a volume set from here,
     * and report the volume [index] stands for.
     */
    private fun syncDeviceVolumeToServer(index: Int) {
        val am = audioManager ?: return
        lastKnownVolume = index
        engine.setTrackGain(1f)
        val volumePercent = deviceVolumePercent(am, index)
        Log.d(TAG, "Device volume index $index is volume $volumePercent")

        // Sync to server (for multi-client coordination)
        sendSpinClient?.setVolume(volumePercent / 100.0)

        // Update playback state for UI sync
        _playbackState.value = _playbackState.value.copy(volume = volumePercent)
        broadcastSessionExtras()
    }

    /** Media may have moved to another output, with its own index and curve. */
    private val outputDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
            if (addedDevices.any { it.isSink }) scheduleOutputVolumeSync()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) {
            if (removedDevices.any { it.isSink }) scheduleOutputVolumeSync()
        }
    }

    // Routing settles a moment after the device callback. Any sink counts
    // there, so the volume is touched only when what plays changed.
    private val outputVolumeSync = Runnable {
        val am = audioManager ?: return@Runnable
        val index = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val output = mediaOutputType(am)
        val fullVolume = isFullVolume(am, output, index)
        when (VolumeLaw.outputChange(am.isVolumeFixed, fullVolume, index, lastKnownVolume, output, lastOutputType)) {
            VolumeLaw.OutputChange.KEEP_VOLUME -> {
                lastKnownVolume = index
                lastOutputType = output
                // The volume last set on this output, else the one playing
                val playing = _playbackState.value.volume
                val volume = restoredVolume(am, index, output) ?: playing
                setVolume(volume / 100f)
                if (volume != playing) {
                    Log.i(TAG, "Volume $volume restored as track gain on output type $output")
                    sendSpinClient?.setVolume(volume / 100.0)
                    broadcastSessionExtras()
                }
            }
            VolumeLaw.OutputChange.NONE -> Unit
            VolumeLaw.OutputChange.DEVICE_VOLUME -> {
                lastOutputType = output
                syncDeviceVolumeToServer(index)
            }
        }
    }

    private fun scheduleOutputVolumeSync() {
        mainHandler.removeCallbacks(outputVolumeSync)
        mainHandler.postDelayed(outputVolumeSync, OUTPUT_CHANGE_SETTLE_MS)
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
        val client = sendSpinClient
        if (!AudioInterruptionPolicy.mayTakeFocus(
                userRequest = false,
                unavailable = otherAppHoldsOutput,
                playerRoleActive = client?.playerRoleActive == true,
            )
        ) {
            // Nothing will play: what the server still sends is discarded
            Log.i(FOCUS_TAG, "not taking the output: " +
                if (otherAppHoldsOutput) "another app owns it" else "not a player")
            return
        }

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
        val state = engine.player?.getPlaybackState()
        return state == com.sendspindroid.sendspin.PlaybackState.PLAYING ||
               state == com.sendspindroid.sendspin.PlaybackState.WAITING_FOR_START
    }

    /**
     * Checks if we are currently connected to a server.
     */
    private fun isConnected(): Boolean {
        return coordinator.sessionState.value.sendSpin is TransportState.Ready
    }

    /**
     * Logs the current stats to AppLog if logging is enabled.
     * Called periodically when debug mode is active.
     */
    private fun logCurrentStats() {
        val audioStats = engine.player?.getStats() ?: return
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
            convergenceTimeMs = timeFilter.convergenceTimeMillis
        )

        AppLog.Audio.d("Stats: " +
            "state=${syncStats.playbackState.name}, " +
            "syncErr=${syncStats.syncErrorUs}us, " +
            "queue=${syncStats.queuedSamples}, " +
            "offset=${syncStats.clockOffsetUs}us, " +
            "insertN=${syncStats.insertEveryNFrames}, dropN=${syncStats.dropEveryNFrames}, " +
            "framesIns=${syncStats.framesInserted}, framesDrop=${syncStats.framesDropped}")
    }

    /**
     * Starts the debug logging loop if debug mode is enabled.
     */
    private fun startDebugLogging() {
        if (AppLog.level != LogLevel.OFF) {
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
     * @param reconnecting Shows "Reconnecting to [serverName]..." instead.
     */
    private fun startForegroundServiceWithNotification(serverName: String? = null, reconnecting: Boolean = false) {
        // Started as well as bound: a service that is only bound is destroyed
        // when the activity lets go of its controller, foreground or not.
        // Refused from the background, where it is already started.
        runCatching { startService(Intent(this, PlaybackService::class.java)) }
        try {
            val contentText = when {
                reconnecting -> "Reconnecting to $serverName..."
                serverName != null -> "Connected to $serverName"
                connectionMode.isAdvertising && sendSpinClient?.isConnected != true -> getString(
                    if (_advertisingFailed.value) com.sendspindroid.R.string.notification_cannot_be_found
                    else com.sendspindroid.R.string.notification_waiting_for_server
                )
                else -> "Streaming audio..."
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
                // Deliver "can duck" losses to the listener instead of letting
                // the system duck us: ducking one synced player sounds wrong
                // next to the others, so it is muted like any other transient loss.
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    mainHandler.post {
                        handleAudioFocusChange(focusChange)
                    }
                }
                .build()
        }

        val result = am.requestAudioFocus(audioFocusRequest!!)
        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        Log.i(FOCUS_TAG, "focus requested: ${if (hasAudioFocus) "granted" else "denied"}")
        if (hasAudioFocus) {
            // We own the output again: clear any 'external_source' report
            // and any mute left by an interruption.
            otherAppHoldsOutput = false
            sendSpinClient?.setExternalSource(false)
            setInterruptionMuted(false)
        }
    }

    /**
     * The user asked this device to play. If another app took the output for
     * good, take it back: that makes the client available again, before the
     * play command reaches the server. False when it cannot be taken back
     * (a phone call): nothing could play, so the command is not sent.
     */
    private fun onUserPlay(): Boolean {
        val client = sendSpinClient ?: return true
        if (!otherAppHoldsOutput) return true
        if (!AudioInterruptionPolicy.mayTakeFocus(
                userRequest = true, unavailable = true, playerRoleActive = client.playerRoleActive)
        ) return true
        Log.i(FOCUS_TAG, "play asked while another app owns the output - taking it back")
        requestAudioFocus()
        return !otherAppHoldsOutput
    }

    /**
     * Abandons audio focus when playback stops.
     */
    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        // Abandoning now would lose the focus-gain callback that ends the
        // interruption (un-mute, resume after a call). The gain abandons instead.
        if (transientFocusLoss != null) return

        audioFocusRequest?.let { request ->
            audioManager?.abandonAudioFocusRequest(request)
            Log.i(FOCUS_TAG, "focus abandoned")
        }
        hasAudioFocus = false
    }

    /**
     * Handles audio focus changes from the system.
     */
    private fun handleAudioFocusChange(focusChange: Int) {
        val event = AudioInterruption.fromFocusChange(focusChange)
        if (event == null) {
            Log.i(FOCUS_TAG, "focus change $focusChange ignored")
            return
        }
        handleAudioInterruption(event)
    }

    /**
     * Another app wants the output, or has given it back: ask
     * [AudioInterruptionPolicy] what to do, log it and do it.
     *
     * A local interruption never pauses the output. It is either a pause
     * on the server (which then stops sending, and the server-state handling
     * stops the player) or a mute, under which audio keeps draining in sync.
     */
    private fun handleAudioInterruption(event: AudioInterruption) {
        val audioMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        val serverState = _playbackState.value.playbackState
        val canSendPause = sendSpinClient?.let { it.isConnected && it.canSendCommand("pause") } == true
        val action = AudioInterruptionPolicy.decide(
            event, audioMode, serverState, canSendPause, pausedForCall, isTv = isTvDevice()
        )
        Log.i(
            FOCUS_TAG,
            "$event mode=${AudioInterruptionPolicy.modeName(audioMode)} server=$serverState " +
                "canSendPause=$canSendPause pausedForCall=$pausedForCall -> $action"
        )

        mainHandler.removeCallbacks(callModeRecheckRunnable)
        if (event != AudioInterruption.BECOMING_NOISY) {
            transientFocusLoss = event.takeIf { it.isTransientLoss }
        }

        when (action) {
            InterruptionAction.PAUSE_ON_SERVER -> {
                // Muted first: the pause takes a round trip to stop the audio.
                setInterruptionMuted(true)
                // Only a call is resumed afterwards, never an output disconnect.
                pausedForCall = event.isTransientLoss
                sendSpinClient?.pause()
            }
            InterruptionAction.RESUME_ON_SERVER -> {
                pausedForCall = false
                setInterruptionMuted(false)
                sendSpinClient?.play()
            }
            InterruptionAction.MUTE_LOCALLY -> setInterruptionMuted(true)
            InterruptionAction.UNMUTE_LOCALLY -> setInterruptionMuted(false)
            InterruptionAction.BECOME_UNAVAILABLE -> {
                // Another app took focus permanently: report 'external_source'
                // per spec. The server parks this client in a solo group and
                // ends its streams; playing again re-requests focus, which
                // clears the state and the mute.
                hasAudioFocus = false
                pausedForCall = false
                setInterruptionMuted(true)
                otherAppHoldsOutput = true
                sendSpinClient?.setExternalSource(true)
            }
            InterruptionAction.NONE -> {}
        }

        if (transientFocusLoss != null && !AudioInterruptionPolicy.isCallMode(audioMode)) {
            mainHandler.postDelayed(callModeRecheckRunnable, CALL_MODE_RECHECK_MS)
        }
        // Focus was kept through the interruption (see abandonAudioFocus);
        // give it up now if nothing is playing.
        if (event == AudioInterruption.FOCUS_GAIN && AudioInterruptionPolicy.abandonFocusOnGain(
                action, serverState, playerRoleActive = sendSpinClient?.playerRoleActive == true)
        ) {
            abandonAudioFocus()
        }
    }

    private fun setInterruptionMuted(muted: Boolean) {
        if (interruptionMuted == muted) return
        interruptionMuted = muted
        Log.i(FOCUS_TAG, if (muted) "output muted" else "output un-muted")
        engine.setMuted(SyncAudioPlayer.MuteReason.INTERRUPTION, muted)
    }

    /**
     * Called with every playback state the server reports, before it is stored:
     * forget our pause-for-a-call once someone else has changed playback, so the
     * end of the call does not resume over their choice.
     */
    private fun noteServerPlaybackState(newState: PlaybackStateType) {
        val previous = _playbackState.value.playbackState
        if (pausedForCall && AudioInterruptionPolicy.callPauseSuperseded(previous, newState)) {
            pausedForCall = false
            Log.i(FOCUS_TAG, "call pause superseded (server $previous -> $newState): will not resume")
        }
    }

    /**
     * Register a receiver for ACTION_AUDIO_BECOMING_NOISY so the sound stops when
     * the audio output device disconnects. Idempotent.
     */
    private fun registerBecomingNoisyReceiver() {
        if (becomingNoisyReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    handleBecomingNoisy()
                }
            }
        }
        becomingNoisyReceiver = receiver
        // System-protected broadcast; not exported (constant value is inert on
        // API < 33, the 3-arg overload exists since API 26).
        registerReceiver(
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            Context.RECEIVER_NOT_EXPORTED
        )
        Log.d(TAG, "Registered ACTION_AUDIO_BECOMING_NOISY receiver")
    }

    /**
     * Handles ACTION_AUDIO_BECOMING_NOISY: the audio output is rerouting to the
     * built-in speaker because an external output disconnected (wired headphones
     * unplugged, Bluetooth/Android Auto disconnected). Follow the Android
     * convention and stop the sound: pause the group and do not resume. If the
     * pause cannot be sent the output is muted instead, until playback is next
     * started.
     *
     * ACTION_AUDIO_BECOMING_NOISY is fired once by the system per transition, so
     * it is inherently single-fire -- unlike AudioDeviceCallback.onAudioDevicesRemoved,
     * which can fire several times for one Bluetooth device (A2DP + SCO) and
     * would need debouncing.
     */
    private fun handleBecomingNoisy() {
        handleAudioInterruption(AudioInterruption.BECOMING_NOISY)
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
     * Keeps the CPU awake while the loop makes a connection at boot or after
     * a restart. Released when the loop ends, or by its timeout of
     * [RECONNECT_LOCK_HOLD_MS] if that comes first. No audio focus is asked
     * for: nothing plays yet, and the user's other audio carries on.
     */
    private fun acquireConnectWakeLock() {
        if (connectWakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            connectWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SendSpinDroid::Connect"
            ).apply { setReferenceCounted(false) }
        }
        connectWakeLock?.acquire(RECONNECT_LOCK_HOLD_MS)
        Log.d(TAG, "Connect wake lock acquired with ${RECONNECT_LOCK_HOLD_MS / 60000}min timeout")
    }

    private fun releaseConnectWakeLock() {
        if (connectWakeLock?.isHeld == true) {
            connectWakeLock?.release()
            Log.d(TAG, "Connect wake lock released")
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
            // Bound clients, if any, keep it alive from here.
            stopSelf()
            Log.d(TAG, "Foreground notification removed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground service", e)
        }
    }

    @OptIn(UnstableApi::class)
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
    }

    internal inner class LibraryCallback : MediaLibrarySession.Callback {

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

            // Build LibraryParams with content style defaults. Search is not
            // advertised: SendSpin defines no library to search.
            val extras = Bundle().apply {
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

            // Sync path: root tabs
            if (parentId == MEDIA_ID_ROOT) {
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(getRootChildren()), params)
                )
            }

            // Async path: server discovery (bounded mDNS wait)
            return suspendToFuture {
                val items = when (parentId) {
                    MEDIA_ID_DISCOVERED -> getDiscoveredServers()
                    else -> {
                        // Never hand Android Auto an empty list here -- see the
                        // invariant documented on AutoBrowseTree.unknownParentItem.
                        Log.w(TAG, "Unknown parentId for onGetChildren: $parentId")
                        listOf(AutoBrowseTree.unknownParentItem(parentId))
                    }
                }
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
                            val selected = ConnectionSelector.selectConnection(server)

                            when (selected) {
                                is ConnectionSelector.SelectedConnection.Local -> {
                                    setCurrentServer(server.id)
                                    connectToServer(selected.address, selected.path)
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

                        // Look up UnifiedServer by local address
                        val unifiedServer = UnifiedServerRepository.allServers.value.find {
                            it.local?.address == serverAddress
                        }
                        if (unifiedServer != null) {
                            setCurrentServer(unifiedServer.id)
                        } else {
                            Log.w(TAG, "No UnifiedServer found for address: $serverAddress")
                        }

                        connectToServer(serverAddress)

                        // Update last-connected timestamp (replaces legacy addToRecent)
                        if (unifiedServer != null) {
                            UnifiedServerRepository.updateLastConnected(unifiedServer.id)
                        }

                        item.buildUpon()
                            .setUri("sendspin://$serverAddress")
                            .build()
                    }
                    else -> item
                }
            }

            return Futures.immediateFuture(updatedItems)
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
                .add(SessionCommand(COMMAND_DISCONNECT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_EXIT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_VOLUME, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_MUTED, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_NEXT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_PREVIOUS, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SWITCH_GROUP, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_GET_STATS, Bundle.EMPTY))
                .apply { if (isOwnApp(controller)) add(SessionCommand(COMMAND_ALLOW_PAIRING, Bundle.EMPTY)) }
                .add(SessionCommand(COMMAND_SET_SEARCH_FOR_SERVERS, Bundle.EMPTY))
                .build()

            // Player commands must include SET_MEDIA_ITEM so the legacy compat bridge
            // can translate playFromMediaId -> onAddMediaItems for Android Auto.
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SET_MEDIA_ITEM)
                .add(Player.COMMAND_PREPARE)
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

        /**
         * Whether [controller] is this app's own UI, the only one allowed to
         * send [COMMAND_ALLOW_PAIRING]. That command lifts the round limit's
         * hold-back and resets the count, which `pairing.md#rounds` asks to
         * be "a deliberate, manufacturer-defined operator action". This
         * service is exported, so any other app could otherwise press it and
         * guess pairing codes without limit.
         */
        private fun isOwnApp(controller: MediaSession.ControllerInfo): Boolean =
            controller.uid == Process.myUid()

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
                        // Set server info before connecting
                        setCurrentServer(serverId)
                        connectToServer(address, path)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Log.e(TAG, "CONNECT command missing server_address")
                        Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                }

                COMMAND_DISCONNECT -> {
                    // The user is done with this server: stop trying for it,
                    // and forget it so a drop racing this cannot start again.
                    coordinator.cancelReconnect()
                    setCurrentServer(null)
                    disconnectFromServer()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_EXIT -> {
                    // As DISCONNECT, and stop waiting for a server too. While
                    // advertising, the disconnect completing would otherwise
                    // put the service back in the foreground and start it
                    // again, undoing the activity's stopService (#327).
                    exiting = true
                    coordinator.cancelReconnect()
                    setCurrentServer(null)
                    // The rest once the goodbye is out: taking the listener
                    // down first could cut the socket it is going out on. The
                    // result says the same, so a caller may end the process
                    // after it (#330).
                    val result = SettableFuture.create<SessionResult>()
                    var done = false
                    val finish = Runnable {
                        if (done) return@Runnable
                        done = true
                        connectionMode.stop()
                        stopForegroundNotification()
                        result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    mainHandler.postDelayed(finish, EXIT_FLUSH_TIMEOUT_MS)
                    disconnectFromServer { mainHandler.post(finish) }
                    result
                }

                COMMAND_SET_SEARCH_FOR_SERVERS -> {
                    val search = args.getBoolean(ARG_SEARCH_FOR_SERVERS)
                    Log.i(TAG, if (search) "Mode: search for servers" else "Mode: wait for a server to connect")
                    UserSettings.searchForServers = search
                    connectionMode.setSearching(search)
                    // Searching holds no foreground of its own.
                    releaseIfIdle()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                COMMAND_SET_VOLUME -> {
                    val volume = args.getFloat(ARG_VOLUME, -1f)
                    if (volume in 0f..1f) {
                        // Reported first: setVolume reports again if the
                        // device holds the volume below what was asked.
                        sendSpinClient?.setVolume(volume.toDouble())
                        setVolume(volume)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Log.e(TAG, "SET_VOLUME command has invalid volume: $volume")
                        Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                }

                COMMAND_SET_MUTED -> {
                    if (args.containsKey(ARG_MUTED)) {
                        setPlayerMuted(args.getBoolean(ARG_MUTED))
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Log.e(TAG, "SET_MUTED command has no muted argument")
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

                COMMAND_ALLOW_PAIRING -> {
                    // Never offered to another app (onConnect), and refused
                    // here too should one send it anyway.
                    if (!isOwnApp(controller)) {
                        Log.w(TAG, "Allow pairing refused from ${controller.packageName} (uid=${controller.uid})")
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    }
                    Log.d(TAG, "Allow pairing gesture confirmed")
                    // Cleared here, at the operator's tap, rather than waiting on a
                    // round trip through the flow: the gate is a local UI concern,
                    // and the button must disappear the moment it is pressed.
                    pairingGestureRequested = false
                    sendSpinClient?.confirmDynamicPairingGesture()
                    broadcastSessionExtras()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                else -> {
                    Log.w(TAG, "Unknown custom command: ${customCommand.customAction}")
                    super.onCustomCommand(session, controller, customCommand, args)
                }
            }
        }
    }

    /**
     * Collects current stats from SyncAudioPlayer and SendSpin.
     * Returns a Bundle containing all stats for Stats for Nerds display.
     */
    /** Translate a coordinator reconnect status into a handoff-episode event. */
    private fun recordHandoff(status: ReconnectStatus) {
        val transport = coordinator.networkState.value.transportType.name
        val methods = coordinator.sessionState.value.server?.configuredMethods?.map { it.name } ?: emptyList()
        val playing = engine.player?.getStats()?.isPlaying ?: false
        val phase = when (status) {
            is ReconnectStatus.Attempting -> HandoffEpisodeRecorder.Phase.ATTEMPTING
            is ReconnectStatus.Succeeded -> HandoffEpisodeRecorder.Phase.SUCCEEDED
            ReconnectStatus.Idle -> HandoffEpisodeRecorder.Phase.IDLE
        }
        val method = (status as? ReconnectStatus.Attempting)?.method?.name
        handoffRecorder.onReconnect(phase, method, transport, methods, playing)
    }

    private fun getStats(): Bundle {
        val bundle = Bundle()

        // Get connection info from SendSpin. An if/else, not `?.let {} ?: run {}`:
        // that form takes the fallback whenever the block's last expression
        // is null, which reported a connected, idle player as disconnected.
        val client = sendSpinClient
        if (client != null) {
            bundle.putString("server_name", client.getServerName())
            bundle.putString("server_address", client.getServerAddress())
            // TransportState variants are plain objects; toString() would show
            // "TransportState$Ready@b7f7b20" on the stats screen.
            val stateLabel = when (client.connectionState.value) {
                is TransportState.Ready -> "Connected"
                is TransportState.Connecting -> "Connecting"
                is TransportState.Idle -> "Disconnected"
                is TransportState.Failed -> "Failed"
            }
            bundle.putString("connection_state", stateLabel)
            bundle.putBoolean("server_initiated", client.isServerInitiated)
            bundle.putString("audio_codec", engine.codec.uppercase())

            // What the session negotiated
            val protocol = client.protocolStats()
            bundle.putString("active_roles", protocol.activeRoles.joinToString(","))
            protocol.pskCategory?.let { bundle.putString("psk_category", it.name) }
            bundle.putInt("min_buffer_ms", protocol.minBufferMs)
            bundle.putInt("required_lead_time_ms", protocol.requiredLeadTimeMs)
            engine.activeStream?.let { stream ->
                bundle.putInt("stream_sample_rate", stream.sampleRate)
                bundle.putInt("stream_bit_depth", stream.bitDepth)
                bundle.putInt("stream_channels", stream.channels)
            }
        } else {
            bundle.putString("connection_state", "Disconnected")
            bundle.putString("audio_codec", "--")
        }

        // Network-handoff episodes (drop + reconnect outcomes) for bug reports.
        bundle.putString("handoff_episodes", handoffRecorder.summary())

        // Get stats from SyncAudioPlayer
        val audioStats = engine.player?.getStats()
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

        // Get stats from SendSpin (clock sync)
        sendSpinClient?.let { client ->
            val timeFilter = client.getTimeFilter()
            bundle.putBoolean("clock_ready", timeFilter.isReady)
            bundle.putBoolean("clock_converged", timeFilter.isConverged)
            bundle.putLong("clock_offset_us", timeFilter.offsetMicros)
            bundle.putDouble("clock_drift_ppm", timeFilter.driftPpm)
            bundle.putLong("clock_error_us", timeFilter.errorMicros)
            bundle.putInt("measurement_count", timeFilter.measurementCountValue)
            bundle.putLong("last_time_sync_age_ms", client.getLastTimeSyncAgeMs())
            bundle.putDouble("static_delay_ms", timeFilter.staticDelayMs)

            // Connection health telemetry (issue #128). Keys left absent when
            // the underlying value is null so StatsViewModel can distinguish
            // "no disconnect yet" from "last disconnect was code=0".
            bundle.putLong("last_byte_received_ago_ms", client.getLastByteReceivedAgoMs())
            bundle.putBoolean("stall_watchdog_armed", client.isStallWatchdogArmed())
            client.getLastDisconnectCode()?.let { bundle.putInt("last_disconnect_code", it) }
            client.getLastDisconnectReason()?.let { bundle.putString("last_disconnect_reason", it) }
            bundle.putLong("time_filter_convergence_ms", timeFilter.convergenceTimeMillis)
        }

        // Refresh network state before reading it: no NetworkEvaluator.Listener is
        // registered, so without this the diagnostics bundle would stay frozen at
        // whatever the network looked like at service start instead of reflecting
        // the current network (e.g. a Wi-Fi to cellular handover).
        networkEvaluator?.evaluateCurrentNetwork()

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
     *
     * A setting of 0 is applied too: the client may hold an older value,
     * set while another client was in use. A restore is not the user
     * changing the offset, so it leaves an offset the server has already
     * pushed in force.
     */
    private fun applySyncOffsetFromSettings() {
        val offsetMs = com.sendspindroid.UserSettings.getSyncOffsetMs()
        sendSpinClient?.let { client ->
            client.setUserSyncOffsetMs(offsetMs.toDouble(), overrideServer = false)
            Log.i(TAG, "Applied manual sync offset from settings: ${offsetMs}ms")
        }
    }

    /**
     * Updates the sync offset and applies it immediately if connected.
     * Called when the user changes the offset in settings.
     */
    fun updateSyncOffset(offsetMs: Int) {
        com.sendspindroid.UserSettings.setSyncOffsetMs(offsetMs)
        sendSpinClient?.let { client ->
            client.setUserSyncOffsetMs(offsetMs.toDouble())
            Log.i(TAG, "Updated sync offset to: ${offsetMs}ms")
        }
    }

    private fun getRootChildren(): List<MediaItem> {
        return AutoBrowseTree.rootChildren(isConnected())
    }

    private suspend fun getDiscoveredServers(): List<MediaItem> {
        // Trigger mDNS scan so servers populate for Android Auto / external browsers
        ensureBrowseDiscoveryRunning()

        // AutoBrowseTree waits briefly for the first mDNS result when nothing
        // is known yet, and guarantees a non-empty list (guidance row when no
        // servers are available) so Android Auto never shows a blank screen.
        return AutoBrowseTree.serverListChildren(
            savedServers = UnifiedServerRepository.savedServers.value,
            discoveredServersFlow = UnifiedServerRepository.filteredDiscoveredServers,
            discoveryWaitMs = BROWSE_DISCOVERY_WAIT_MS,
        )
    }

    /**
     * Starts mDNS discovery if not already running.
     * Used when an external client (Android Auto) browses the server list,
     * since the main Activity may not be open to trigger discovery.
     */
    private fun ensureBrowseDiscoveryRunning() {
        if (browseDiscoveryManager != null) return  // Already initialized
        // Advertising or discovering, never both: saved servers are still
        // listed, and choosing one stops the advertising first.
        if (connectionMode.isAdvertising) return

        Log.i(TAG, "Starting mDNS discovery for browse tree")
        browseDiscoveryManager = NsdDiscoveryManager(this, object : NsdDiscoveryManager.DiscoveryListener {
            override fun onServerDiscovered(name: String, address: String, path: String, friendlyName: String) {
                Log.d(TAG, "Browse discovery: found $name at $address (path=$path friendlyName=$friendlyName)")
                UnifiedServerRepository.addDiscoveredServer(friendlyName, address, path)
                // Notify subscribed browsers that children changed
                mediaSession?.notifyChildrenChanged(MEDIA_ID_DISCOVERED, 0, null)
            }

            override fun onServerLost(name: String) {
                Log.d(TAG, "Browse discovery: lost $name")
                // Find the server by name to get its address for removal
                val server = UnifiedServerRepository.discoveredServers.value.find { it.name == name }
                server?.local?.address?.let { address ->
                    UnifiedServerRepository.removeDiscoveredServer(address)
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
                AutoBrowseTree.browsableItem(MEDIA_ID_DISCOVERED, "Connect", "Choose a server")
            }
            mediaId.startsWith(MEDIA_ID_SAVED_SERVER_PREFIX) -> {
                val serverId = mediaId.removePrefix(MEDIA_ID_SAVED_SERVER_PREFIX)
                UnifiedServerRepository.savedServers.value
                    .find { it.id == serverId }
                    ?.let { AutoBrowseTree.savedServerItem(it) }
            }
            mediaId.startsWith(MEDIA_ID_SERVER_PREFIX) -> {
                val address = mediaId.removePrefix(MEDIA_ID_SERVER_PREFIX)
                val server = UnifiedServerRepository.getServerByAddress(address)
                server?.let { AutoBrowseTree.playableServerItem(it.name, it.local?.address ?: address) }
            }
            else -> null
        }
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        sendSpinPlayer = SendSpinPlayer().apply { onPlayRequested = { onUserPlay() } }
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

        when {
            intent?.action == ACTION_AUTO_CONNECT -> handleAutoConnect(intent)
            // START_STICKY: the system restarted the service it had killed.
            intent == null -> handleRestart()
        }

        return START_STICKY
    }

    /**
     * Handle auto-connect from BootReceiver: connect to the server it names,
     * the default server.
     */
    private fun handleAutoConnect(intent: Intent) {
        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
        if (serverId == null) {
            Log.w(TAG, "Auto-connect: missing server ID")
            takeForegroundAndLetGo()
            return
        }

        val server = UnifiedServerRepository.getServer(serverId)
        if (server == null) {
            Log.w(TAG, "Auto-connect: server $serverId not found")
            takeForegroundAndLetGo()
            return
        }

        Log.i(TAG, "Auto-connect on boot: ${server.name} (${server.id})")
        connectOnStart(server)
    }

    /**
     * The service was killed and the system has started it again. While
     * advertising, onCreate has put the listener back and the server
     * reconnects to it. While searching nothing would: connect to the server
     * it was on, which [setCurrentServer] recorded. The service is left
     * started only while it is connected or trying to be, so that is the
     * server it was killed on. With no server recorded yet (the first restart
     * after an update), the default server, as at boot. One recorded but not
     * saved (found on mDNS) cannot be looked up again, and is not swapped for
     * another server.
     */
    private fun handleRestart() {
        if (connectionMode.isAdvertising) return
        val dialled = UserSettings.dialledServerId
        val server = if (dialled != null) {
            UnifiedServerRepository.getServer(dialled)
        } else {
            UnifiedServerRepository.getDefaultServer()
        }
        if (server == null) {
            Log.i(TAG, "Restarted with no server to connect to")
            return
        }
        Log.i(TAG, "Restarted: connecting to ${server.name} (${server.id})")
        connectOnStart(server)
    }

    /**
     * Connect to [server] when the service starts on its own, at boot or
     * after a restart, with nobody there to try again: through the reconnect
     * loop, so that a server or a network not up yet is retried (#158), and
     * with the CPU kept awake while the loop runs. A connection already up or
     * being made is left alone.
     */
    private fun connectOnStart(server: UnifiedServer) {
        if (isConnectedOrReconnecting()) {
            Log.i(TAG, "Not connecting to ${server.name}: already connected or connecting")
            // A start by startForegroundService still has to take the
            // foreground, which a connection being made has not taken yet.
            // If that connection fails, releaseIfIdle lets it go.
            startForegroundServiceWithNotification(
                connectedServerName(),
                reconnecting = coordinator.reconnectStatus.value is ReconnectStatus.Attempting,
            )
            return
        }
        if (server.local == null) {
            // The loop would wait forever with nothing to dial.
            Log.w(TAG, "Auto-connect: server ${server.name} has no configured connection methods")
            takeForegroundAndLetGo()
            return
        }
        // The server this service is connected to, which a later drop
        // reconnects to.
        setCurrentServer(server.id)
        acquireConnectWakeLock()
        // Takes the foreground at once, as a start by startForegroundService must.
        startReconnectLoop(server)
    }

    /**
     * A start by startForegroundService has to take the foreground even when
     * there is nothing to connect to; it is let go at once.
     */
    private fun takeForegroundAndLetGo() {
        startForegroundServiceWithNotification()
        releaseIfIdle()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Check if SyncAudioPlayer is actively playing
        val audioPlayerState = engine.player?.getPlaybackState()
        val isPlaying = audioPlayerState == com.sendspindroid.sendspin.PlaybackState.PLAYING ||
                        audioPlayerState == com.sendspindroid.sendspin.PlaybackState.WAITING_FOR_START

        // Keep alive if auto-start is enabled and we're connected (even if
        // idle) or working on getting the connection back
        val autoStartKeepAlive = UserSettings.autoStartOnBoot && (
            coordinator.sessionState.value.sendSpin is TransportState.Ready ||
                coordinator.reconnectStatus.value is ReconnectStatus.Attempting ||
                connectionMode.isAdvertising
            )

        Log.d(TAG, "onTaskRemoved (playing=$isPlaying, autoStart=$autoStartKeepAlive, state=$audioPlayerState)")

        if (!isPlaying && !autoStartKeepAlive) {
            Log.d(TAG, "Not playing and no auto-start keep-alive, stopping service")
            stopSelf()
        } else {
            Log.d(TAG, "Continuing in background (playing=$isPlaying, autoStart=$autoStartKeepAlive)")
        }

        super.onTaskRemoved(rootIntent)
    }

    @OptIn(UnstableApi::class)
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

        // Unregister sync offset receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(syncOffsetReceiver)

        // Unregister log level receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logLevelReceiver)

        // Unregister High Power Mode receiver and release locks
        LocalBroadcastManager.getInstance(this).unregisterReceiver(highPowerModeReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(preferredCodecReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(playerNameReceiver)
        releaseHighPowerLocks()

        // Let the final releasePlaybackLocks() abandon focus even mid-interruption
        transientFocusLoss = null

        // Unregister the becoming-noisy receiver (system broadcast)
        becomingNoisyReceiver?.let {
            runCatching { unregisterReceiver(it) }
            becomingNoisyReceiver = null
        }

        // Unregister volume observer (only if it was registered)
        if (volumeObserverRegistered) {
            volumeObserver?.let { contentResolver.unregisterContentObserver(it) }
            volumeObserverRegistered = false
            audioManager?.unregisterAudioDeviceCallback(outputDeviceCallback)
        }
        volumeObserver = null
        mainHandler.removeCallbacks(outputVolumeSync)

        // Stop browse discovery if running
        browseDiscoveryManager?.cleanup()
        browseDiscoveryManager = null
        stopReconnectDiscovery()

        // A server that connected to us is told the player is shutting down;
        // then the advertisement and the listener go.
        sendSpinClient?.takeIf { it !== dialClient }?.destroy()
        connectionMode.stop()

        // Waits up to 500 ms for the decode worker to drain, and releases
        // the output.
        engine.release()

        if (::coordinator.isInitialized) coordinator.close()
        serviceScope.cancel()
        imageLoader?.shutdown()

        // Cancel playback locks and foreground notification.
        releasePlaybackLocks()
        releaseConnectWakeLock()
        stopForegroundNotification()

        mediaSession?.run {
            release()
        }
        mediaSession = null

        forwardingPlayer = null

        sendSpinPlayer?.release()
        sendSpinPlayer = null

        dialClient?.destroy()
        dialClient = null
        sendSpinClient = null

        super.onDestroy()
    }
}

