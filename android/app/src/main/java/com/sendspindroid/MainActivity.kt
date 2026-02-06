package com.sendspindroid

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import android.database.ContentObserver
import android.media.AudioManager
import android.provider.Settings
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sendspindroid.debug.DebugLogger
import com.sendspindroid.debug.FileLogger
import android.app.UiModeManager
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.animation.AnimationUtils
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.palette.graphics.Palette
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.sendspindroid.databinding.ActivityMainBinding
import com.sendspindroid.discovery.NsdDiscoveryManager
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.playback.PlaybackService
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.model.ConnectionType
import com.sendspindroid.network.AutoReconnectManager
import com.sendspindroid.network.ConnectionSelector
import com.sendspindroid.network.DefaultServerPinger
import com.sendspindroid.network.NetworkEvaluator
import com.sendspindroid.ui.remote.ProxyConnectDialog
import com.sendspindroid.ui.remote.RemoteConnectDialog
import com.sendspindroid.ui.server.AddServerWizardActivity
import com.sendspindroid.ui.server.SectionedServerAdapter
import com.sendspindroid.ui.server.UnifiedServerAdapter
import com.sendspindroid.ui.server.UnifiedServerConnector
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaConnectionState
import com.sendspindroid.ui.navigation.HomeFragment
import com.sendspindroid.ui.navigation.SearchFragment
import com.sendspindroid.ui.navigation.LibraryFragment
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.main.PlaybackState
import com.sendspindroid.ui.main.ArtworkSource
import com.sendspindroid.ui.main.NavTab

/**
 * Main activity for the SendSpinDroid audio streaming client.
 * Handles server discovery, connection management, and audio playback control.
 *
 * Architecture note: This activity currently handles too many responsibilities.
 * For v2, consider refactoring to MVVM pattern with ViewModel for better separation of concerns.
 */
class MainActivity : AppCompatActivity() {

    // ViewBinding provides type-safe access to views (best practice vs findViewById)
    private lateinit var binding: ActivityMainBinding
    private lateinit var serverAdapter: ServerAdapter

    // ViewModel for managing UI state (survives configuration changes)
    private val viewModel: MainActivityViewModel by viewModels()

    // Unified server support - sectioned adapter with saved + discovered servers
    private var sectionedServerAdapter: SectionedServerAdapter? = null
    private var unifiedServerAdapter: UnifiedServerAdapter? = null
    private var unifiedServerConnector: UnifiedServerConnector? = null

    // List backing the RecyclerView - Consider moving to ViewModel with StateFlow for v2
    private val servers = mutableListOf<ServerInfo>()

    // Connection state machine - starts with ServerList (shows immediately on startup)
    private var connectionState: AppConnectionState = AppConnectionState.ServerList

    // Track the currently connected server ID for editing
    private var currentConnectedServerId: String? = null

    // Track last artwork to prevent duplicate background updates
    private var lastArtworkSource: String? = null

    // Store the last applied background color for restoring after navigation
    private var lastBackgroundColor: Int? = null

    // NsdManager-based discovery (Android native - more reliable than Go's hashicorp/mdns)
    private var discoveryManager: NsdDiscoveryManager? = null

    // MediaController for communicating with PlaybackService
    // Provides playback control and state observation
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    // Handler for UI operations
    private val handler = Handler(Looper.getMainLooper())

    // User manually disconnected flag - when true, blocks auto-connect to default server
    // Set when user taps "Switch Server" or manually selects a different server while connected
    // Cleared when user manually connects to the default server
    private var userManuallyDisconnected = false

    // Reconnecting indicator - persists while reconnection is in progress
    private var reconnectingSnackbar: Snackbar? = null

    // Auto-reconnect manager for persistent reconnection from ServerList UI
    private var autoReconnectManager: AutoReconnectManager? = null

    // Server being reconnected to (for tracking during auto-reconnect)
    private var reconnectingToServer: UnifiedServer? = null

    // Default server pinger for remote/proxy auto-connect when mDNS unavailable
    private var defaultServerPinger: DefaultServerPinger? = null
    private var networkEvaluator: NetworkEvaluator? = null

    // Charging state receiver for adaptive ping intervals
    private var chargingReceiver: BroadcastReceiver? = null

    // Network state monitoring
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Volume control - uses device STREAM_MUSIC (Spotify-style)
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var volumeObserver: ContentObserver? = null

    // Android TV detection - used for D-pad navigation and remote control handling
    private val isTvDevice: Boolean by lazy {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    // Permission request launcher for POST_NOTIFICATIONS (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted")
        } else {
            Log.w(TAG, "Notification permission denied - playback notifications will not appear")
        }
    }

    // Add Server Wizard Activity launcher
    // Uses ActivityResult to receive the new/updated server ID when the wizard completes
    private val addServerWizardLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val serverId = result.data?.getStringExtra(AddServerWizardActivity.RESULT_SERVER_ID)
            if (serverId != null) {
                val server = UnifiedServerRepository.getServer(serverId)
                if (server != null) {
                    Log.d(TAG, "Server wizard completed: ${server.name}")
                    showSuccessSnackbar(getString(R.string.server_added, server.name))
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        // Delay before auto-connecting to default server (allows UI to render)
        private const val DEFAULT_SERVER_AUTO_CONNECT_DELAY_MS = 1_000L
        // SharedPreferences keys
        private const val PREFS_NAME = "sendspindroid_prefs"
        private const val PREF_ONBOARDING_SHOWN = "onboarding_shown"
    }

    // SharedPreferences for app state
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ============================================================================
    // Accessibility Support
    // ============================================================================

    /**
     * Announces an important message to screen readers.
     * Only announces if accessibility services are enabled.
     *
     * @param message The message to announce
     */
    private fun announceForAccessibility(message: String) {
        val accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (accessibilityManager?.isEnabled == true) {
            binding.root.announceForAccessibility(message)
        }
    }

    /**
     * Updates the server list content description with current count.
     * Uses filtered discovered servers to match what the user sees.
     */
    private fun updateServerListAccessibility() {
        val savedCount = UnifiedServerRepository.savedServers.value.size
        val discoveredCount = UnifiedServerRepository.filteredDiscoveredServers.value.size
        val totalCount = savedCount + discoveredCount
        binding.serverListRecyclerView?.contentDescription =
            getString(R.string.accessibility_server_list, totalCount)
    }

    /**
     * Updates volume slider content description with current percentage.
     */
    private fun updateVolumeAccessibility(volumePercent: Int) {
        binding.volumeSlider.contentDescription =
            getString(R.string.accessibility_volume_percent, volumePercent)
    }

    /**
     * Snackbar error types for different error scenarios.
     * Used to determine appropriate duration, colors, and actions.
     */
    private enum class ErrorType {
        NETWORK,          // Network connectivity errors
        CONNECTION,       // Server connection failures
        DISCOVERY,        // Server discovery failures
        PLAYBACK,         // Audio playback errors
        VALIDATION,       // Input validation errors
        GENERAL          // General errors
    }

    /**
     * Shows an error Snackbar with appropriate styling and optional retry action.
     *
     * @param message The error message to display
     * @param errorType The type of error (determines styling and duration)
     * @param retryAction Optional action to execute when user taps "Retry"
     */
    private fun showErrorSnackbar(
        message: String,
        errorType: ErrorType = ErrorType.GENERAL,
        retryAction: (() -> Unit)? = null
    ) {
        // Announce error to screen readers for accessibility
        announceForAccessibility("Error: $message")

        val snackbar = Snackbar.make(
            binding.coordinatorLayout,
            message,
            if (errorType == ErrorType.VALIDATION) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG
        )

        // Set error background color
        snackbar.view.setBackgroundColor(
            ContextCompat.getColor(this, com.google.android.material.R.color.design_default_color_error)
        )

        // Add retry action if provided
        retryAction?.let { action ->
            snackbar.setAction(getString(R.string.action_retry)) {
                action()
            }
            snackbar.setActionTextColor(
                ContextCompat.getColor(this, android.R.color.white)
            )
        }

        snackbar.show()
    }

    /**
     * Shows a success Snackbar with appropriate styling.
     *
     * @param message The success message to display
     */
    private fun showSuccessSnackbar(message: String) {
        val snackbar = Snackbar.make(
            binding.coordinatorLayout,
            message,
            Snackbar.LENGTH_SHORT
        )

        // Set success background color (using primary color)
        snackbar.view.setBackgroundColor(
            ContextCompat.getColor(this, com.google.android.material.R.color.design_default_color_primary_dark)
        )

        snackbar.show()
    }

    /**
     * Shows an info Snackbar with default styling.
     *
     * @param message The info message to display
     */
    private fun showInfoSnackbar(message: String) {
        Snackbar.make(
            binding.coordinatorLayout,
            message,
            Snackbar.LENGTH_SHORT
        ).show()
    }

    /**
     * Shows an indicator that we're reconnecting to the server.
     * Playback continues from buffer during this time.
     *
     * @param attempt Current reconnection attempt number
     * @param bufferMs Remaining audio buffer in milliseconds
     */
    private fun showReconnectingIndicator(attempt: Int, bufferMs: Long) {
        // Don't show UI if activity is finishing or destroyed
        if (isFinishing || isDestroyed) {
            return
        }

        // Dismiss any existing reconnecting snackbar
        reconnectingSnackbar?.dismiss()

        val bufferSec = bufferMs / 1000
        val message = if (bufferSec > 0) {
            "Reconnecting (attempt $attempt)... ${bufferSec}s buffer"
        } else {
            "Reconnecting (attempt $attempt)..."
        }

        try {
            reconnectingSnackbar = Snackbar.make(
                binding.coordinatorLayout,
                message,
                Snackbar.LENGTH_INDEFINITE
            ).apply {
                // Use warning/info color instead of error
                view.setBackgroundColor(
                    ContextCompat.getColor(this@MainActivity, com.google.android.material.R.color.design_default_color_primary)
                )
                show()
            }

            // Announce for accessibility
            announceForAccessibility("Reconnecting to server. Playback continuing from buffer.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show reconnecting indicator", e)
        }
    }

    /**
     * Hides the reconnecting indicator (on successful reconnection or error).
     */
    private fun hideReconnectingIndicator() {
        reconnectingSnackbar?.dismiss()
        reconnectingSnackbar = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize file-based debug logger (for devices where logcat is disabled)
        FileLogger.init(this)

        // Restore debug logging enabled state from preferences (so startup logs are captured)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        DebugLogger.isEnabled = prefs.getBoolean("debug_logging_enabled", false)

        FileLogger.i(TAG, "MainActivity onCreate (debug logging: ${DebugLogger.isEnabled})")

        // Initialize ServerRepository for sharing server state with PlaybackService
        // This enables Android Auto to see discovered servers
        ServerRepository.initialize(this)

        // Initialize UnifiedServerRepository for unified server management
        UnifiedServerRepository.initialize(this)

        // Initialize UserSettings for accessing user preferences
        UserSettings.initialize(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle window insets for edge-to-edge display
        setupWindowInsets()
        applyFullScreenMode()

        // Set up the toolbar as the action bar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowHomeEnabled(false)

        // Initialize discovery manager BEFORE setupUI, because setupUI calls
        // showSearchingView() which starts auto-discovery
        initializeDiscoveryManager()
        initializeAutoReconnectManager()
        initializeDefaultServerPinger()
        initializeMediaController()
        setupUI()

        // Setup back press handling for navigation content
        setupBackPressHandler()

        // Show onboarding dialog for first-time users
        showOnboardingIfNeeded()

        // Request notification permission for Android 13+
        requestNotificationPermission()
    }

    /**
     * Handle configuration changes (rotation) manually to prevent Activity recreation.
     * This avoids the false network change detection that causes disconnects on rotation.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed: orientation=${newConfig.orientation}")

        // Re-inflate the layout to get the correct orientation-specific layout
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Re-apply window insets handling
        setupWindowInsets()
        applyFullScreenMode()

        // Re-setup toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowHomeEnabled(false)

        // Re-setup all UI bindings
        setupUI()

        // Restore navigation state if we were showing navigation content
        if (isNavigationContentVisible) {
            // Re-show the navigation content with the current tab
            val fragment = when (currentNavTab) {
                R.id.nav_home -> HomeFragment.newInstance()
                R.id.nav_search -> SearchFragment.newInstance()
                R.id.nav_library -> LibraryFragment.newInstance()
                else -> HomeFragment.newInstance()
            }
            // Temporarily set to false so showNavigationContent will show it
            isNavigationContentVisible = false
            showNavigationContent(fragment)
            binding.bottomNavigation?.selectedItemId = currentNavTab
            return
        }

        // Restore UI state based on current connection state
        when (val state = connectionState) {
            is AppConnectionState.ServerList -> showServerListView()
            is AppConnectionState.Connecting -> {
                showConnectionLoading(state.serverName)
            }
            is AppConnectionState.Connected -> {
                showNowPlayingView(state.serverName)
                enablePlaybackControls(true)
                // Re-apply any cached metadata/artwork from media controller
                mediaController?.let { controller ->
                    controller.mediaMetadata?.let { metadata ->
                        updateMetadata(
                            metadata.title?.toString() ?: "",
                            metadata.artist?.toString() ?: "",
                            metadata.albumTitle?.toString() ?: ""
                        )
                        updateAlbumArt(metadata)
                    }
                    // Restore play/pause button state
                    updatePlayPauseButton(controller.isPlaying)
                }
            }
            is AppConnectionState.Reconnecting -> {
                showNowPlayingView(state.serverName)
                enablePlaybackControls(true)
                showReconnectingIndicator(state.attempt, 0L)
                // Restore play/pause button state
                mediaController?.let { controller ->
                    updatePlayPauseButton(controller.isPlaying)
                }
            }
            is AppConnectionState.Error -> {
                showServerListView()
                showErrorSnackbar(state.message)
            }
        }
    }

    /**
     * Requests POST_NOTIFICATIONS permission on Android 13+ (API 33+).
     * This is required for playback notifications to appear.
     * If denied, playback will still work but without notification controls.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Notification permission already granted")
                }
                else -> {
                    Log.d(TAG, "Requesting notification permission")
                    notificationPermissionLauncher.launch(permission)
                }
            }
        }
    }

    /**
     * Shows the onboarding dialog if this is the user's first launch.
     * Uses SharedPreferences to track if the dialog has been shown.
     */
    private fun showOnboardingIfNeeded() {
        if (!prefs.getBoolean(PREF_ONBOARDING_SHOWN, false)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.onboarding_title)
                .setMessage(R.string.onboarding_message)
                .setPositiveButton(R.string.onboarding_got_it) { dialog, _ ->
                    // Mark onboarding as shown
                    prefs.edit().putBoolean(PREF_ONBOARDING_SHOWN, true).apply()
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()
        }
    }

    /**
     * Set up window insets handling for edge-to-edge display.
     * Applies system bar padding to the main content area so content doesn't
     * overlap with status bar, navigation bar, or display cutouts.
     */
    private fun setupWindowInsets() {
        val contentArea = binding.contentArea ?: return

        ViewCompat.setOnApplyWindowInsetsListener(contentArea) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // Apply insets as padding, plus some extra spacing for aesthetics
            view.updatePadding(
                left = insets.left + 24.dpToPx(),
                top = insets.top + 16.dpToPx(),
                right = insets.right + 24.dpToPx(),
                bottom = insets.bottom + 16.dpToPx()
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    /** Convert dp to pixels */
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    /**
     * Apply full screen (immersive) mode based on user setting.
     * Uses WindowInsetsControllerCompat for backward compatibility to API 21.
     */
    private fun applyFullScreenMode() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (UserSettings.fullScreenMode) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun updateKeepScreenOn(isPlaying: Boolean) {
        if (UserSettings.keepScreenOn && isPlaying) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setupUI() {
        // Setup legacy RecyclerView for servers (kept for compatibility)
        serverAdapter = ServerAdapter { server ->
            onServerSelected(server)
        }

        // Load previously saved manual servers (legacy)
        loadPersistedServers()

        // Setup sectioned server adapter for the new unified server list
        setupSectionedServerAdapter()

        // Setup unified server support (connector, observers)
        setupUnifiedServers()

        // FAB for adding servers
        binding.addServerFab?.setOnClickListener {
            showAddServerWizard()
        }

        // Playback controls
        binding.previousButton.setOnClickListener {
            onPreviousClicked()
        }

        binding.playPauseButton.setOnClickListener {
            onPlayPauseClicked()
        }

        binding.nextButton.setOnClickListener {
            onNextClicked()
        }

        binding.switchGroupButton.setOnClickListener {
            onSwitchGroupClicked()
        }

        // Favorite button - Only visible when connected to MA server
        binding.favoriteButton?.setOnClickListener {
            onFavoriteClicked()
        }

        // Observe MA connection state to show/hide favorite button
        observeMaConnectionState()

        // Volume slider - controls device STREAM_MUSIC (Spotify-style)
        // Initialize slider to current device volume
        syncSliderWithDeviceVolume()

        binding.volumeSlider.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                onVolumeChanged(value / 100f)
                // Sync state to ViewModel for Compose UI
                viewModel.updateVolume(value / 100f)
                val volumePercent = value.toInt()
                // Update accessibility description with current volume
                updateVolumeAccessibility(volumePercent)
                // Announce volume changes at 10% increments for screen readers
                // and provide haptic feedback at these increments
                if (volumePercent % 10 == 0) {
                    announceForAccessibility("Volume $volumePercent percent")
                    slider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }

        // Initialize volume accessibility
        updateVolumeAccessibility(binding.volumeSlider.value.toInt())

        // Start with server list view and begin discovery in background
        showServerListView()

        // Setup bottom navigation (Step 1 - infrastructure only)
        setupBottomNavigation()
    }

    // Track whether navigation content is currently shown (vs full player)
    private var isNavigationContentVisible = false

    // Current selected navigation tab
    private var currentNavTab: Int = R.id.nav_home

    /**
     * Sets up the bottom navigation bar with item selection handling.
     * Handles switching between full player and navigation content views.
     */
    private fun setupBottomNavigation() {
        binding.bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    Log.d(TAG, "Bottom nav: Home selected")
                    currentNavTab = R.id.nav_home
                    viewModel.setCurrentNavTab(NavTab.HOME)
                    showNavigationContent(HomeFragment.newInstance())
                    true
                }
                R.id.nav_search -> {
                    Log.d(TAG, "Bottom nav: Search selected")
                    currentNavTab = R.id.nav_search
                    viewModel.setCurrentNavTab(NavTab.SEARCH)
                    showNavigationContent(SearchFragment.newInstance())
                    true
                }
                R.id.nav_library -> {
                    Log.d(TAG, "Bottom nav: Library selected")
                    currentNavTab = R.id.nav_library
                    viewModel.setCurrentNavTab(NavTab.LIBRARY)
                    showNavigationContent(LibraryFragment.newInstance())
                    true
                }
                else -> false
            }
        }

        // Setup Compose mini player
        setupMiniPlayerComposeView()

        // Initially, no tab should be selected (full player is default view)
        // We clear the selection by setting checked to false on all menu items
        binding.bottomNavigation?.menu?.let { menu ->
            for (i in 0 until menu.size()) {
                menu.getItem(i).isChecked = false
            }
        }
    }

    /**
     * Sets up both Compose-based mini player views (top and bottom) with the ViewModel.
     * Only one is visible at a time based on user setting.
     */
    private fun setupMiniPlayerComposeView() {
        listOf(binding.miniPlayerTop, binding.miniPlayerBottom).forEach { miniPlayer ->
            miniPlayer?.apply {
                viewModel = this@MainActivity.viewModel

                onCardClick = {
                    Log.d(TAG, "Mini player tapped - returning to full player")
                    hideNavigationContent()
                }

                onStopClick = {
                    Log.d(TAG, "Mini player: Stop/disconnect pressed")
                    // First return to full view, then disconnect
                    hideNavigationContent()
                    onDisconnectClicked()
                }

                onPlayPauseClick = {
                    Log.d(TAG, "Mini player: Play/Pause pressed")
                    onPlayPauseClicked()
                }

                onVolumeChange = { newVolume ->
                    // Compose slider uses 0-1 range
                    onVolumeChanged(newVolume)
                }
            }
        }
    }

    /**
     * Updates the mini-player position based on user setting.
     * Simply toggles visibility of top vs bottom mini player instances.
     */
    private fun updateMiniPlayerPosition() {
        if (!isNavigationContentVisible) return  // Only matters when nav content is showing

        val position = UserSettings.miniPlayerPosition
        binding.miniPlayerTop?.visibility =
            if (position == UserSettings.MiniPlayerPosition.TOP) View.VISIBLE else View.GONE
        binding.miniPlayerBottom?.visibility =
            if (position == UserSettings.MiniPlayerPosition.BOTTOM) View.VISIBLE else View.GONE
    }

    /**
     * Shows the navigation content view with the specified fragment.
     * Hides the full player (now playing view) and shows the mini player.
     */
    private fun showNavigationContent(fragment: Fragment) {
        if (!isNavigationContentVisible) {
            isNavigationContentVisible = true
            // Sync state to ViewModel for Compose UI
            viewModel.setNavigationContentVisible(true)
            Log.d(TAG, "Showing navigation content")

            // Content visibility: show nav fragment, hide others
            binding.nowPlayingView.visibility = View.GONE
            binding.serverListView?.visibility = View.GONE
            binding.navFragmentContainer?.visibility = View.VISIBLE
            binding.addServerFab?.visibility = View.GONE

            // Show mini player in correct position
            val position = UserSettings.miniPlayerPosition
            binding.miniPlayerTop?.visibility =
                if (position == UserSettings.MiniPlayerPosition.TOP) View.VISIBLE else View.GONE
            binding.miniPlayerBottom?.visibility =
                if (position == UserSettings.MiniPlayerPosition.BOTTOM) View.VISIBLE else View.GONE

            // Clear the big player background (blurred art + tint) when navigating away
            clearPlayerBackground()
        }

        // Load the fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.navFragmentContainer, fragment)
            .commitAllowingStateLoss()
    }

    /**
     * Hides the navigation content view and returns to the full player.
     * Called when tapping the mini player or pressing back.
     */
    private fun hideNavigationContent() {
        if (isNavigationContentVisible) {
            isNavigationContentVisible = false
            // Sync state to ViewModel for Compose UI
            viewModel.setNavigationContentVisible(false)
            Log.d(TAG, "Hiding navigation content, returning to full player")

            // Hide nav content and both mini players
            binding.navFragmentContainer?.visibility = View.GONE
            binding.miniPlayerTop?.visibility = View.GONE
            binding.miniPlayerBottom?.visibility = View.GONE

            // Restore the appropriate view based on connection state
            when (connectionState) {
                is AppConnectionState.ServerList -> {
                    binding.serverListView?.visibility = View.VISIBLE
                    binding.addServerFab?.visibility = View.VISIBLE
                }
                is AppConnectionState.Connected,
                is AppConnectionState.Connecting,
                is AppConnectionState.Reconnecting -> {
                    binding.nowPlayingView.visibility = View.VISIBLE
                    // Restore the big player background (blurred art + tint)
                    restorePlayerBackground()
                }
                is AppConnectionState.Error -> {
                    binding.serverListView?.visibility = View.VISIBLE
                    binding.addServerFab?.visibility = View.VISIBLE
                }
            }

            // Clear bottom nav selection
            binding.bottomNavigation?.menu?.let { menu ->
                for (i in 0 until menu.size()) {
                    menu.getItem(i).isChecked = false
                }
            }
        }
    }

    // updateMiniPlayer() removed - Compose MiniPlayer observes ViewModel state directly

    /**
     * Sets up the back press handler to return from navigation content to full player.
     * Uses the modern OnBackPressedCallback approach (onBackPressed is deprecated).
     */
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isNavigationContentVisible) {
                    // Return to full player from navigation content
                    hideNavigationContent()
                } else {
                    // Default back behavior (exit app or navigate back)
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    /**
     * Sets up the sectioned server adapter for the unified server list.
     * Displays saved servers and discovered servers in separate sections.
     */
    private fun setupSectionedServerAdapter() {
        sectionedServerAdapter = SectionedServerAdapter(object : SectionedServerAdapter.Callback {
            override fun onServerClick(server: UnifiedServer) {
                onUnifiedServerSelected(server)
            }

            override fun onQuickConnect(server: UnifiedServer) {
                onUnifiedServerQuickConnect(server)
            }

            override fun onServerLongClick(server: UnifiedServer): Boolean {
                showUnifiedServerContextMenu(server)
                return true
            }
        })

        binding.serverListRecyclerView?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = sectionedServerAdapter
        }

        // Observe saved servers
        lifecycleScope.launch {
            UnifiedServerRepository.savedServers.collectLatest { servers ->
                sectionedServerAdapter?.updateSavedServers(servers)
                Log.d(TAG, "Saved servers updated: ${servers.size} servers")
                updateServerListEmptyState()
            }
        }

        // Observe filtered discovered servers (excludes servers that match saved servers)
        lifecycleScope.launch {
            UnifiedServerRepository.filteredDiscoveredServers.collectLatest { servers ->
                sectionedServerAdapter?.updateDiscoveredServers(servers)
                Log.d(TAG, "Discovered servers updated: ${servers.size} servers (filtered)")
                updateServerListEmptyState()
            }
        }

        // Observe online status of saved servers (discovered on local network)
        lifecycleScope.launch {
            UnifiedServerRepository.onlineSavedServerIds.collectLatest { onlineIds ->
                sectionedServerAdapter?.updateOnlineServers(onlineIds)
                if (onlineIds.isNotEmpty()) {
                    Log.d(TAG, "Online saved servers: ${onlineIds.joinToString()}")
                }
            }
        }
    }

    /**
     * Updates the empty state visibility for the server list.
     * Only shows empty state when there are no saved AND no filtered discovered servers.
     */
    private fun updateServerListEmptyState() {
        val hasSaved = UnifiedServerRepository.savedServers.value.isNotEmpty()
        val hasFilteredDiscovered = UnifiedServerRepository.filteredDiscoveredServers.value.isNotEmpty()

        if (!hasSaved && !hasFilteredDiscovered) {
            binding.emptyServerListView?.visibility = View.VISIBLE
            binding.serverListRecyclerView?.visibility = View.GONE
        } else {
            binding.emptyServerListView?.visibility = View.GONE
            binding.serverListRecyclerView?.visibility = View.VISIBLE
        }
    }

    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
        registerVolumeObserver()
        // Notify pinger of foreground state for adaptive intervals
        defaultServerPinger?.onForegroundChanged(true)
    }

    override fun onStop() {
        super.onStop()
        unregisterNetworkCallback()
        unregisterVolumeObserver()
        // Notify pinger of background state for adaptive intervals
        defaultServerPinger?.onForegroundChanged(false)
    }

    /**
     * Called when activity comes to foreground.
     * Re-syncs UI with current playback state to handle cases where:
     * - User returns from another app
     * - Screen was turned off and on
     * - Activity was in background while playback continued
     */
    override fun onResume() {
        super.onResume()
        // Re-apply full screen mode (picks up changes made in Settings)
        applyFullScreenMode()
        // Re-apply mini-player position (picks up changes made in Settings)
        updateMiniPlayerPosition()
        // Re-evaluate keep screen on (picks up setting changes + current playback state)
        updateKeepScreenOn(mediaController?.isPlaying == true)
        // Re-sync UI state with MediaController
        syncUIWithPlayerState()
        // Re-sync volume slider with device volume (may have changed while in background)
        syncSliderWithDeviceVolume()
    }

    /**
     * Called when activity receives a new intent while already running.
     * This happens when user taps the notification (FLAG_ACTIVITY_SINGLE_TOP).
     * Without this, the activity doesn't know it was re-launched and UI can get stuck.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-sync UI state with MediaController
        syncUIWithPlayerState()
    }

    // ============================================================================
    // Network State Monitoring
    // ============================================================================

    /**
     * Registers a network callback to monitor connectivity changes.
     * Shows error when network is lost while connected to a server.
     */
    private fun registerNetworkCallback() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                // If auto-reconnecting, trigger immediate retry (skip backoff delay)
                if (autoReconnectManager?.isReconnecting() == true) {
                    Log.i(TAG, "Network available during auto-reconnect - triggering immediate retry")
                    autoReconnectManager?.onNetworkAvailable()
                }
                // Notify default server pinger of network change (may trigger immediate ping)
                defaultServerPinger?.onNetworkChanged()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // Network type may have changed (WiFi ↔ cellular) - notify pinger
                // This triggers re-evaluation of connection priority
                networkEvaluator?.evaluateCurrentNetwork(network)
                defaultServerPinger?.onNetworkChanged()
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Network lost")
                runOnUiThread {
                    // Only show error if we're connected or connecting to a server
                    if (connectionState is AppConnectionState.Connected ||
                        connectionState is AppConnectionState.Connecting) {
                        showErrorSnackbar(
                            message = "Network connection lost",
                            errorType = ErrorType.NETWORK
                        )
                    }
                }
            }
        }

        networkCallback = callback

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Unregisters the network callback to prevent leaks.
     */
    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
    }

    // ============================================================================
    // View State Management (Two Views: Server List, Now Playing)
    // ============================================================================

    /**
     * Transitions to server list state.
     * Called on disconnect or when returning from now playing.
     */
    private fun transitionToServerList() {
        connectionState = AppConnectionState.ServerList
        currentConnectedServerId = null  // Clear tracked server
        // Sync state to ViewModel for Compose UI
        viewModel.resetToServerList()
        showServerListView()
    }

    /**
     * Shows the server list view with saved and discovered servers.
     * mDNS discovery runs in the background, updating the discovered section.
     */
    private fun showServerListView() {
        // Clear toolbar subtitle when not connected
        supportActionBar?.subtitle = null

        // Content visibility
        if (binding.serverListView?.visibility != View.VISIBLE) {
            binding.serverListView?.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in))
        }
        binding.serverListView?.visibility = View.VISIBLE
        binding.nowPlayingView.visibility = View.GONE
        binding.navFragmentContainer?.visibility = View.GONE

        // No mini player on server list
        binding.miniPlayerTop?.visibility = View.GONE
        binding.miniPlayerBottom?.visibility = View.GONE

        // Show FAB for adding servers
        binding.addServerFab?.visibility = View.VISIBLE

        // Update empty state
        updateServerListEmptyState()

        // Start discovery automatically (runs in background)
        startAutoDiscovery()
        sectionedServerAdapter?.setScanning(true)

        // Update toolbar subtitle to show scanning status
        supportActionBar?.subtitle = getString(R.string.scanning_ellipsis)

        // Check for default server and auto-connect after a brief delay
        checkDefaultServerAutoConnect()

        // Start default server pinger for remote/proxy connections
        // (mDNS only finds local servers; this pings remote/proxy when on cellular or away from home)
        if (!userManuallyDisconnected) {
            defaultServerPinger?.start()
        }
    }

    /**
     * Checks if there's a default server configured and auto-connects to it.
     *
     * This runs once at app launch to attempt immediate connection to the default server.
     * It provides a brief startup window for the server to be available before falling
     * back to waiting for mDNS discovery (which triggers checkAutoConnectOnDiscovery).
     *
     * Note: This uses a one-time startup flag separate from userManuallyDisconnected,
     * since this delay-based approach should only run once per app launch.
     */
    private var hasRunStartupAutoConnect = false

    private fun checkDefaultServerAutoConnect() {
        if (hasRunStartupAutoConnect) return
        hasRunStartupAutoConnect = true

        // Don't auto-connect if user manually disconnected in a previous session
        // (though this will typically be false at startup)
        if (userManuallyDisconnected) {
            Log.d(TAG, "Skipping startup auto-connect - user manually disconnected")
            return
        }

        val defaultServer = UnifiedServerRepository.getDefaultServer()
        if (defaultServer != null) {
            Log.d(TAG, "Default server found: ${defaultServer.name}, scheduling auto-connect")

            // Update subtitle to show auto-connect status
            supportActionBar?.subtitle = getString(R.string.auto_connecting_to_default, defaultServer.name)

            // Delay to allow UI to render and user to see the server list
            lifecycleScope.launch {
                delay(DEFAULT_SERVER_AUTO_CONNECT_DELAY_MS)

                // Only auto-connect if still in ServerList state and user hasn't manually disconnected
                if (connectionState == AppConnectionState.ServerList && !userManuallyDisconnected) {
                    Log.d(TAG, "Auto-connecting to default server: ${defaultServer.name}")
                    onUnifiedServerSelected(defaultServer)
                }
            }
        }
    }

    /**
     * Checks if a newly discovered server matches the default server and triggers auto-connect.
     *
     * This is called when mDNS discovers a server on the local network. If the discovered
     * server matches the default server's local address, and the user hasn't manually
     * disconnected, we automatically connect.
     *
     * This handles scenarios like:
     * - Server rebooting and reappearing on mDNS
     * - Auto-reconnect failing but server coming back later
     * - App starting while server is temporarily unavailable
     *
     * @param discoveredAddress The IP address of the newly discovered server
     */
    private fun checkAutoConnectOnDiscovery(discoveredAddress: String) {
        // Don't auto-connect if user manually disconnected ("Switch Server" or manual switch)
        if (userManuallyDisconnected) {
            Log.d(TAG, "Skipping auto-connect on discovery - user manually disconnected")
            return
        }

        // Don't auto-connect if already connected or connecting
        if (connectionState !is AppConnectionState.ServerList) {
            Log.d(TAG, "Skipping auto-connect on discovery - not in ServerList state")
            return
        }

        // Don't auto-connect if auto-reconnect is in progress
        if (autoReconnectManager?.isReconnecting() == true) {
            Log.d(TAG, "Skipping auto-connect on discovery - auto-reconnect in progress")
            return
        }

        // Check if discovered server matches default server's local address
        val defaultServer = UnifiedServerRepository.getDefaultServer() ?: return
        if (defaultServer.local?.address == discoveredAddress) {
            Log.i(TAG, "Default server discovered on mDNS ($discoveredAddress) - auto-connecting")
            onUnifiedServerSelected(defaultServer)
        }
    }

    /**
     * Shows the now playing view (album art, playback controls).
     * Called when connected to a server.
     */
    private fun showNowPlayingView(serverName: String) {
        // Set toolbar state based on current playing state, no subtitle
        val isPlaying = mediaController?.isPlaying == true
        supportActionBar?.title = if (isPlaying) "Playing" else "Paused"
        supportActionBar?.subtitle = null

        // Content visibility
        binding.serverListView?.visibility = View.GONE
        binding.nowPlayingView.visibility = View.VISIBLE
        binding.navFragmentContainer?.visibility = View.GONE

        // No mini player on full player view
        binding.miniPlayerTop?.visibility = View.GONE
        binding.miniPlayerBottom?.visibility = View.GONE

        // Hide FAB when in now playing view
        binding.addServerFab?.visibility = View.GONE

        binding.nowPlayingContent.visibility = View.VISIBLE
        binding.connectionProgressContainer.visibility = View.GONE

        // Stop discovery and pinging while connected (saves battery)
        discoveryManager?.stopDiscovery()
        defaultServerPinger?.stop()
        sectionedServerAdapter?.setScanning(false)

        // Sync volume slider with current device volume
        syncSliderWithDeviceVolume()

        // Sync play/pause button with current state
        updatePlayPauseButton(isPlaying)
    }

    /**
     * Initializes Android-native NsdManager for server discovery.
     *
     * Why NsdManager instead of Go's hashicorp/mdns?
     * - NsdManager is Android's native mDNS implementation
     * - It properly handles network interface selection on Android
     * - It works reliably with Android's WiFi stack and multicast lock
     * - hashicorp/mdns has issues selecting the correct interface on Android
     */
    private fun initializeDiscoveryManager() {
        try {
            discoveryManager = NsdDiscoveryManager(
                context = this,
                listener = object : NsdDiscoveryManager.DiscoveryListener {
                    override fun onServerDiscovered(name: String, address: String, path: String) {
                        runOnUiThread {
                            Log.d(TAG, "Server discovered: $name at $address path=$path")
                            val server = ServerInfo(name, address, path)
                            addServer(server)

                            // Add to UnifiedServerRepository for unified server list
                            UnifiedServerRepository.addDiscoveredServer(name, address, path)

                            // Check if this discovery should trigger auto-connect to default server
                            checkAutoConnectOnDiscovery(address)
                        }
                    }

                    override fun onServerLost(name: String) {
                        runOnUiThread {
                            Log.d(TAG, "Server lost: $name")
                            // Remove from legacy list
                            servers.removeAll { it.name == name }
                            serverAdapter.submitList(servers.toList())

                            // Remove from UnifiedServerRepository discovered servers
                            val serverToRemove = UnifiedServerRepository.discoveredServers.value
                                .find { it.name == name }
                            serverToRemove?.local?.address?.let { address ->
                                UnifiedServerRepository.removeDiscoveredServer(address)
                            }
                        }
                    }

                    override fun onDiscoveryStarted() {
                        runOnUiThread {
                            Log.d(TAG, "Discovery started")
                            sectionedServerAdapter?.setScanning(true)
                            // Update toolbar subtitle only if on server list
                            if (connectionState == AppConnectionState.ServerList) {
                                supportActionBar?.subtitle = getString(R.string.scanning_ellipsis)
                            }
                        }
                    }

                    override fun onDiscoveryStopped() {
                        runOnUiThread {
                            Log.d(TAG, "Discovery stopped")
                            sectionedServerAdapter?.setScanning(false)
                            // Clear toolbar subtitle only if on server list
                            if (connectionState == AppConnectionState.ServerList) {
                                supportActionBar?.subtitle = null
                            }
                        }
                    }

                    override fun onDiscoveryError(error: String) {
                        runOnUiThread {
                            Log.e(TAG, "Discovery error: $error")
                            sectionedServerAdapter?.setScanning(false)
                            supportActionBar?.subtitle = null
                            // Show error snackbar - server list is still usable with saved servers
                            showErrorSnackbar(
                                message = getString(R.string.error_discovery),
                                errorType = ErrorType.DISCOVERY,
                                retryAction = { startAutoDiscovery() }
                            )
                        }
                    }
                }
            )
            Log.d(TAG, "NsdDiscoveryManager initialized")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize discovery manager", e)
            showErrorSnackbar(
                message = getString(R.string.error_discovery_start),
                errorType = ErrorType.GENERAL
            )
        }
    }

    /**
     * Initializes the AutoReconnectManager for persistent auto-reconnection.
     *
     * When connection is lost unexpectedly (not user-initiated), this manager
     * handles progressive backoff reconnection from the ServerList UI.
     */
    private fun initializeAutoReconnectManager() {
        autoReconnectManager = AutoReconnectManager(
            context = this,
            onAttempt = { serverId, attempt, maxAttempts, connectionType ->
                runOnUiThread {
                    Log.d(TAG, "Auto-reconnect attempt $attempt/$maxAttempts for server $serverId")
                    sectionedServerAdapter?.setReconnectProgress(serverId, attempt, maxAttempts, null)

                    // Update toolbar subtitle
                    supportActionBar?.subtitle = getString(R.string.reconnecting_toolbar_subtitle)

                    // Announce for accessibility
                    announceForAccessibility(getString(R.string.accessibility_reconnecting, attempt, maxAttempts))
                }
            },
            onMethodAttempt = { serverId, method ->
                runOnUiThread {
                    val methodName = when (method) {
                        ConnectionType.LOCAL -> getString(R.string.connection_method_local)
                        ConnectionType.REMOTE -> getString(R.string.connection_method_remote)
                        ConnectionType.PROXY -> getString(R.string.connection_method_proxy)
                    }
                    Log.d(TAG, "Auto-reconnect trying $methodName for server $serverId")
                    val currentProgress = autoReconnectManager?.getCurrentAttempt() ?: 1
                    sectionedServerAdapter?.setReconnectProgress(serverId, currentProgress, AutoReconnectManager.MAX_ATTEMPTS, methodName)
                }
            },
            onSuccess = { serverId ->
                runOnUiThread {
                    Log.i(TAG, "Auto-reconnect succeeded for server $serverId")
                    reconnectingToServer = null
                    sectionedServerAdapter?.clearReconnectProgress(serverId)
                    supportActionBar?.subtitle = null
                    hideReconnectingIndicator()
                    // Note: Connection success will be handled by handleConnectionStateChange
                }
            },
            onFailure = { serverId, error ->
                runOnUiThread {
                    Log.w(TAG, "Auto-reconnect failed for server $serverId: $error")
                    reconnectingToServer = null
                    sectionedServerAdapter?.clearReconnectProgress(serverId)
                    supportActionBar?.subtitle = null
                    hideReconnectingIndicator()
                    // NOTE: We do NOT set userManuallyDisconnected here - if the server
                    // reappears on mDNS later, we still want to auto-connect to it.
                    showErrorSnackbar(
                        message = getString(R.string.reconnecting_failed, AutoReconnectManager.MAX_ATTEMPTS),
                        errorType = ErrorType.CONNECTION
                    )
                }
            },
            connectToServer = { server, selectedConnection ->
                // This is called from a coroutine - perform the actual connection
                performAutoReconnect(server, selectedConnection)
            }
        )
        Log.d(TAG, "AutoReconnectManager initialized")
    }

    /**
     * Initializes the DefaultServerPinger for remote/proxy auto-connect.
     *
     * When the default server isn't discovered via mDNS (user on cellular,
     * server only has remote/proxy config, etc.), this pinger periodically
     * checks if the server is reachable and triggers auto-connect on success.
     */
    private fun initializeDefaultServerPinger() {
        // Initialize NetworkEvaluator for connection priority decisions
        networkEvaluator = NetworkEvaluator(this)
        networkEvaluator?.evaluateCurrentNetwork()

        // Initialize the pinger
        defaultServerPinger = DefaultServerPinger(
            networkEvaluator = networkEvaluator!!,
            onServerReachable = { server ->
                runOnUiThread {
                    // Only connect if conditions still allow
                    if (!userManuallyDisconnected &&
                        connectionState == AppConnectionState.ServerList &&
                        autoReconnectManager?.isReconnecting() != true) {
                        Log.i(TAG, "Default server reachable via ping - auto-connecting to ${server.name}")
                        onUnifiedServerSelected(server)
                    } else {
                        Log.d(TAG, "Ping found server but conditions changed - skipping connect")
                    }
                }
            }
        )

        // Register charging state receiver for adaptive ping intervals
        chargingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val isCharging = intent.action == Intent.ACTION_POWER_CONNECTED
                Log.d(TAG, "Charging state changed: isCharging=$isCharging")
                defaultServerPinger?.onChargingChanged(isCharging)
            }
        }
        registerReceiver(chargingReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, Context.RECEIVER_NOT_EXPORTED)

        Log.d(TAG, "DefaultServerPinger initialized")
    }

    /**
     * Performs a connection attempt for auto-reconnection.
     * Returns true if connection was initiated successfully (doesn't wait for completion).
     */
    private suspend fun performAutoReconnect(
        server: UnifiedServer,
        selectedConnection: ConnectionSelector.SelectedConnection
    ): Boolean {
        val controller = mediaController
        if (controller == null) {
            Log.e(TAG, "Cannot auto-reconnect: MediaController not available")
            return false
        }

        return try {
            when (selectedConnection) {
                is ConnectionSelector.SelectedConnection.Local -> {
                    val args = Bundle().apply {
                        putString(PlaybackService.ARG_SERVER_ADDRESS, selectedConnection.address)
                        putString(PlaybackService.ARG_SERVER_PATH, selectedConnection.path)
                    }
                    val command = SessionCommand(PlaybackService.COMMAND_CONNECT, Bundle.EMPTY)
                    controller.sendCustomCommand(command, args)
                    Log.d(TAG, "Auto-reconnect: sent local connect command to ${selectedConnection.address}")
                }
                is ConnectionSelector.SelectedConnection.Remote -> {
                    val args = Bundle().apply {
                        putString(PlaybackService.ARG_REMOTE_ID, selectedConnection.remoteId)
                    }
                    val command = SessionCommand(PlaybackService.COMMAND_CONNECT_REMOTE, Bundle.EMPTY)
                    controller.sendCustomCommand(command, args)
                    Log.d(TAG, "Auto-reconnect: sent remote connect command")
                }
                is ConnectionSelector.SelectedConnection.Proxy -> {
                    val args = Bundle().apply {
                        putString(PlaybackService.ARG_PROXY_URL, selectedConnection.url)
                        putString(PlaybackService.ARG_AUTH_TOKEN, selectedConnection.authToken)
                    }
                    val command = SessionCommand(PlaybackService.COMMAND_CONNECT_PROXY, Bundle.EMPTY)
                    controller.sendCustomCommand(command, args)
                    Log.d(TAG, "Auto-reconnect: sent proxy connect command")
                }
            }
            // Note: We return true to indicate the command was sent.
            // The actual success/failure will be determined by connection state change.
            // For now, we optimistically assume the attempt was valid.
            // A more robust implementation would wait for connection confirmation.
            true
        } catch (e: Exception) {
            Log.e(TAG, "Auto-reconnect: failed to send connect command", e)
            false
        }
    }

    /**
     * Starts auto-discovery for servers.
     * Called automatically when showing the server list view.
     */
    private fun startAutoDiscovery() {
        Log.d(TAG, "Starting auto-discovery")
        try {
            discoveryManager?.startDiscovery()
            sectionedServerAdapter?.setScanning(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            sectionedServerAdapter?.setScanning(false)
            showErrorSnackbar(
                message = getString(R.string.error_discovery_start),
                errorType = ErrorType.DISCOVERY,
                retryAction = { startAutoDiscovery() }
            )
        }
    }

    /**
     * Initializes MediaController to communicate with PlaybackService.
     *
     * MediaController provides:
     * - Playback control (play, pause, stop)
     * - State observation (playing, paused, buffering)
     * - Metadata updates (title, artist, album)
     * - Custom commands (connect, disconnect, volume)
     */
    private fun initializeMediaController() {
        // Create session token for our PlaybackService
        val sessionToken = SessionToken(
            this,
            ComponentName(this, PlaybackService::class.java)
        )

        // Build MediaController asynchronously
        mediaControllerFuture = MediaController.Builder(this, sessionToken)
            .setListener(MediaControllerListener())
            .buildAsync()

        // Add callback when controller is ready
        mediaControllerFuture?.addListener(
            {
                try {
                    mediaController = mediaControllerFuture?.get()
                    Log.d(TAG, "MediaController connected to PlaybackService")

                    // Add player listener for state updates
                    mediaController?.addListener(PlayerStateListener())

                    // Sync UI with current player state
                    syncUIWithPlayerState()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect MediaController", e)
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    /**
     * Listener for MediaController connection events and session extras.
     */
    private inner class MediaControllerListener : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            Log.d(TAG, "MediaController disconnected from service")
            runOnUiThread {
                enablePlaybackControls(false)
            }
        }

        /**
         * Called when session extras change (metadata and connection state updates from PlaybackService).
         * This is how PlaybackService notifies us of:
         * - Connection state changes (connected, disconnected, error)
         * - Metadata updates (since we can't use MediaItem metadata with custom protocol)
         */
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            runOnUiThread {
                // Handle connection state changes
                val connectionStateStr = extras.getString(PlaybackService.EXTRA_CONNECTION_STATE)
                if (connectionStateStr != null) {
                    handleConnectionStateChange(connectionStateStr, extras)
                }

                // Handle metadata updates
                val title = extras.getString(PlaybackService.EXTRA_TITLE, "")
                val artist = extras.getString(PlaybackService.EXTRA_ARTIST, "")
                val album = extras.getString(PlaybackService.EXTRA_ALBUM, "")
                val artworkUrl = extras.getString(PlaybackService.EXTRA_ARTWORK_URL, "")

                if (title.isNotEmpty() || artist.isNotEmpty() || album.isNotEmpty()) {
                    Log.d(TAG, "Metadata changed: $title / $artist (artwork: $artworkUrl)")
                    updateMetadata(title, artist, album)

                    // Load artwork from URL if available
                    if (artworkUrl.isNotEmpty()) {
                        loadArtworkFromUrl(artworkUrl)
                    }
                }

                // Handle volume updates from server
                val volume = extras.getInt(PlaybackService.EXTRA_VOLUME, -1)
                if (volume in 0..100) {
                    Log.d(TAG, "Server volume update received: $volume%")
                    binding.volumeSlider.value = volume.toFloat()
                    // Sync state to ViewModel for Compose UI
                    viewModel.updateVolume(volume / 100f)
                    updateVolumeAccessibility(volume)
                }

                // Handle group name updates
                val groupName = extras.getString(PlaybackService.EXTRA_GROUP_NAME)
                if (groupName != null) {
                    Log.d(TAG, "Group name update received: $groupName")
                    updateGroupName(groupName)
                }
            }
        }
    }

    /**
     * Handles connection state changes broadcast from PlaybackService.
     * Updates the UI state machine based on the connection state.
     */
    private fun handleConnectionStateChange(stateStr: String, extras: Bundle) {
        // Don't update UI if activity is finishing or destroyed
        if (isFinishing || isDestroyed) {
            Log.d(TAG, "Ignoring connection state change - activity finishing/destroyed")
            return
        }

        Log.d(TAG, "Connection state changed: $stateStr")

        when (stateStr) {
            PlaybackService.STATE_CONNECTING -> {
                // Already handled by connectToServer(), but sync if needed
                if (connectionState !is AppConnectionState.Connecting) {
                    Log.d(TAG, "Received CONNECTING state from service")
                }
            }
            PlaybackService.STATE_CONNECTED -> {
                val serverName = extras.getString(PlaybackService.EXTRA_SERVER_NAME, "Unknown Server")
                Log.d(TAG, "Connected to: $serverName")

                // Get address from current connecting state or reconnecting state
                val address = when (val currentState = connectionState) {
                    is AppConnectionState.Connecting -> currentState.serverAddress
                    is AppConnectionState.Reconnecting -> currentState.serverAddress
                    else -> ""
                }

                // Cancel any auto-reconnect in progress (we're now connected)
                autoReconnectManager?.cancelReconnection()
                reconnectingToServer?.let { server ->
                    sectionedServerAdapter?.clearReconnectProgress(server.id)
                }
                reconnectingToServer = null

                connectionState = AppConnectionState.Connected(serverName, address)
                // Sync state to ViewModel for Compose UI
                viewModel.updateConnectionState(connectionState)
                viewModel.clearReconnectingState()

                showNowPlayingView(serverName)
                enablePlaybackControls(true)
                hideConnectionLoading()
                hideReconnectingIndicator()  // Hide any reconnecting indicator
                invalidateOptionsMenu() // Show "Switch Server" menu option

                // Announce connection for accessibility
                announceForAccessibility(getString(R.string.accessibility_connected))
            }
            PlaybackService.STATE_RECONNECTING -> {
                val serverName = extras.getString(PlaybackService.EXTRA_SERVER_NAME, "Unknown Server")
                val attempt = extras.getInt("reconnect_attempt", 1)
                val bufferMs = extras.getLong("buffer_remaining_ms", 0)
                Log.d(TAG, "Reconnecting to: $serverName (attempt $attempt, buffer ${bufferMs}ms)")

                // Preserve server address from previous state
                val address = when (val currentState = connectionState) {
                    is AppConnectionState.Connected -> currentState.serverAddress
                    is AppConnectionState.Reconnecting -> currentState.serverAddress
                    else -> ""
                }

                connectionState = AppConnectionState.Reconnecting(
                    serverName = serverName,
                    serverAddress = address,
                    attempt = attempt,
                    nextRetrySeconds = (1 shl (attempt - 1)).coerceAtMost(30)
                )
                // Sync state to ViewModel for Compose UI
                viewModel.updateConnectionState(connectionState)
                viewModel.updateReconnectingState(serverName, attempt, bufferMs)

                // Show reconnecting indicator without disrupting playback view
                showReconnectingIndicator(attempt, bufferMs)

                // Keep playback controls enabled - playback continues from buffer
            }
            PlaybackService.STATE_DISCONNECTED -> {
                val wasUserInitiated = extras.getBoolean(PlaybackService.EXTRA_WAS_USER_INITIATED, false)
                val wasReconnectExhausted = extras.getBoolean(PlaybackService.EXTRA_WAS_RECONNECT_EXHAUSTED, false)
                Log.d(TAG, "Disconnected from server (userInitiated=$wasUserInitiated, reconnectExhausted=$wasReconnectExhausted)")

                connectionState = AppConnectionState.ServerList
                // Sync state to ViewModel for Compose UI
                viewModel.updateConnectionState(connectionState)
                viewModel.resetPlaybackState()
                showServerListView()
                enablePlaybackControls(false)
                invalidateOptionsMenu() // Hide "Switch Server" menu option

                // Handle auto-reconnection based on disconnect reason
                if (!wasUserInitiated && !wasReconnectExhausted) {
                    // Unexpected disconnect - start UI-level auto-reconnect
                    // NOTE: We do NOT set userManuallyDisconnected here - unexpected disconnects
                    // should still allow mDNS discovery to trigger auto-connect if server reappears.
                    // The checkAutoConnectOnDiscovery() method already checks isReconnecting().
                    val serverId = currentConnectedServerId
                    val server = serverId?.let { UnifiedServerRepository.getServer(it) }
                        ?: reconnectingToServer

                    if (server != null) {
                        Log.i(TAG, "Starting UI-level auto-reconnect for server: ${server.name}")
                        reconnectingToServer = server
                        currentConnectedServerId = server.id

                        // Update adapter to show reconnecting status
                        sectionedServerAdapter?.setReconnectProgress(server.id, 1, AutoReconnectManager.MAX_ATTEMPTS, null)

                        // Start auto-reconnect manager
                        autoReconnectManager?.startReconnecting(server)
                    } else {
                        Log.w(TAG, "Cannot start auto-reconnect: no server info available")
                        sectionedServerAdapter?.clearStatuses()
                    }
                } else {
                    // User-initiated or reconnect exhausted - clear statuses
                    reconnectingToServer = null
                    sectionedServerAdapter?.clearStatuses()

                    // Announce disconnection for accessibility
                    announceForAccessibility(getString(R.string.accessibility_disconnected))
                }
            }
            PlaybackService.STATE_ERROR -> {
                val errorMessage = extras.getString(PlaybackService.EXTRA_ERROR_MESSAGE, "Unknown error")
                Log.e(TAG, "Connection error: $errorMessage")

                connectionState = AppConnectionState.Error(errorMessage)
                // Sync state to ViewModel for Compose UI
                viewModel.updateConnectionState(connectionState)
                hideConnectionLoading()
                showServerListView()

                // Clear unified server adapter statuses
                sectionedServerAdapter?.clearStatuses()

                showErrorSnackbar(
                    message = errorMessage,
                    errorType = ErrorType.CONNECTION
                )
            }
        }
    }

    /**
     * Listener for player state changes from the service.
     */
    private inner class PlayerStateListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            runOnUiThread {
                Log.d(TAG, "isPlaying changed: $isPlaying")
                // Sync state to ViewModel for Compose UI
                val vmPlaybackState = if (isPlaying) PlaybackState.READY else PlaybackState.IDLE
                viewModel.updatePlaybackState(isPlaying, vmPlaybackState)

                if (isPlaying) {
                    updatePlaybackState("playing")
                    enablePlaybackControls(true)
                    // Announce playback started for accessibility
                    announceForAccessibility(getString(R.string.accessibility_playback_started))
                } else {
                    updatePlaybackState("paused")
                    // Announce playback paused for accessibility
                    announceForAccessibility(getString(R.string.accessibility_playback_paused))
                }
                // Update play/pause button text and content description based on current state
                updatePlayPauseButton(isPlaying)
                updateKeepScreenOn(isPlaying)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            runOnUiThread {
                Log.d(TAG, "Playback state: $playbackState")
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        // Sync state to ViewModel for Compose UI
                        viewModel.updatePlaybackState(false, PlaybackState.IDLE)
                        enablePlaybackControls(false)
                        hideConnectionLoading()
                        hideBufferingIndicator()
                        // Only transition to server list if we were connected/connecting
                        // (not during initial startup)
                        val currentState = connectionState
                        if (currentState is AppConnectionState.Connected ||
                            currentState is AppConnectionState.Connecting) {
                            connectionState = AppConnectionState.ServerList
                            viewModel.updateConnectionState(connectionState)
                            showServerListView()
                            sectionedServerAdapter?.clearStatuses()
                        }
                        // Announce disconnection for accessibility
                        announceForAccessibility(getString(R.string.accessibility_disconnected))
                    }
                    Player.STATE_BUFFERING -> {
                        // Sync state to ViewModel for Compose UI
                        viewModel.updatePlaybackState(false, PlaybackState.BUFFERING)
                        showBufferingIndicator()
                        // Transition to Connected state and show now playing view
                        val currentState = connectionState
                        if (currentState is AppConnectionState.Connecting) {
                            connectionState = AppConnectionState.Connected(
                                currentState.serverName,
                                currentState.serverAddress
                            )
                            viewModel.updateConnectionState(connectionState)
                            showNowPlayingView(currentState.serverName)
                        }
                        // Announce buffering for accessibility
                        announceForAccessibility(getString(R.string.accessibility_buffering))
                    }
                    Player.STATE_READY -> {
                        // Sync state to ViewModel for Compose UI
                        viewModel.updatePlaybackState(mediaController?.isPlaying ?: false, PlaybackState.READY)
                        enablePlaybackControls(true)
                        hideConnectionLoading()
                        hideBufferingIndicator()
                        // Transition to Connected state and show now playing view
                        val currentState = connectionState
                        if (currentState is AppConnectionState.Connecting) {
                            connectionState = AppConnectionState.Connected(
                                currentState.serverName,
                                currentState.serverAddress
                            )
                            viewModel.updateConnectionState(connectionState)
                            showNowPlayingView(currentState.serverName)
                        } else if (currentState is AppConnectionState.Connected) {
                            showNowPlayingView(currentState.serverName)
                        }
                        // Announce connection for accessibility
                        announceForAccessibility(getString(R.string.accessibility_connected))
                    }
                    Player.STATE_ENDED -> {
                        // Sync state to ViewModel for Compose UI
                        viewModel.updatePlaybackState(false, PlaybackState.ENDED)
                        updatePlaybackState("stopped")
                        hideBufferingIndicator()
                        // Announce playback stopped for accessibility
                        announceForAccessibility(getString(R.string.accessibility_playback_stopped))
                    }
                }
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            runOnUiThread {
                val title = mediaMetadata.title?.toString() ?: ""
                val artist = mediaMetadata.artist?.toString() ?: ""
                val album = mediaMetadata.albumTitle?.toString() ?: ""
                Log.d(TAG, "Metadata from service: $title / $artist / $album")
                updateMetadata(title, artist, album)

                // Load album art from MediaMetadata
                updateAlbumArt(mediaMetadata)

                // Announce new track for accessibility
                if (title.isNotEmpty() && artist.isNotEmpty()) {
                    announceForAccessibility(getString(R.string.accessibility_now_playing, title, artist))
                }
            }
        }
    }

    /**
     * Syncs UI with current player state when controller connects.
     * Also restores the correct view (now playing vs searching) based on playback state.
     * Called from onResume(), onNewIntent(), and when MediaController first connects.
     */
    private fun syncUIWithPlayerState() {
        mediaController?.let { controller ->
            val isPlaying = controller.isPlaying
            val state = controller.playbackState

            runOnUiThread {
                // Check if we're actively connected (playing or ready to play)
                val isConnected = isPlaying || state == Player.STATE_READY || state == Player.STATE_BUFFERING

                if (isConnected) {
                    // Restore connection state if needed (e.g., after activity recreation)
                    // Don't overwrite Reconnecting state - it's still valid while playing from buffer
                    if (connectionState !is AppConnectionState.Connected &&
                        connectionState !is AppConnectionState.Reconnecting) {
                        // Get server name from toolbar subtitle or use default
                        val serverName = supportActionBar?.subtitle?.toString() ?: "Connected"
                        connectionState = AppConnectionState.Connected(serverName, "")
                        showNowPlayingView(serverName)
                        invalidateOptionsMenu()
                    }

                    // Update playback state
                    if (isPlaying) {
                        updatePlaybackState("playing")
                    } else {
                        updatePlaybackState("paused")
                    }
                    enablePlaybackControls(true)
                } else {
                    enablePlaybackControls(false)

                    // If we were showing connected/connecting state but player is now disconnected,
                    // transition back to server list view to prevent stale UI state
                    if (connectionState is AppConnectionState.Connected ||
                        connectionState is AppConnectionState.Reconnecting ||
                        connectionState is AppConnectionState.Connecting) {
                        Log.d(TAG, "Player disconnected but UI shows connected - resetting to server list")
                        connectionState = AppConnectionState.ServerList
                        showServerListView()
                        sectionedServerAdapter?.clearStatuses()
                        invalidateOptionsMenu()
                    }
                }

                // Sync play/pause button icon
                updatePlayPauseButton(isPlaying)
                updateKeepScreenOn(isPlaying)

                // Sync metadata and artwork
                val metadata = controller.mediaMetadata
                updateMetadata(
                    metadata.title?.toString() ?: "",
                    metadata.artist?.toString() ?: "",
                    metadata.albumTitle?.toString() ?: ""
                )
                updateAlbumArt(metadata)
            }
        }
    }

    private fun showAddServerDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_server, null)
        val serverNameInput = dialogView.findViewById<EditText>(R.id.serverNameInput)
        val serverAddressInput = dialogView.findViewById<EditText>(R.id.serverAddressInput)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_server_manually))
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val name = serverNameInput.text.toString().trim()
                val address = serverAddressInput.text.toString().trim()

                if (validateServerAddress(address)) {
                    val serverName = if (name.isEmpty()) address else name
                    // Use addManualServer for user-added servers (persisted)
                    addManualServer(ServerInfo(serverName, address))
                    showSuccessSnackbar(getString(R.string.server_added, serverName))
                } else {
                    showErrorSnackbar(
                        message = getString(R.string.invalid_address),
                        errorType = ErrorType.VALIDATION
                    )
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Shows the Remote Connect dialog for connecting via Music Assistant Remote Access.
     * Uses WebRTC for NAT traversal, allowing connection from outside the local network.
     */
    private fun showRemoteConnectDialog() {
        RemoteConnectDialog.show(supportFragmentManager) { remoteId, nickname ->
            // Save connection preferences for quick reconnection
            UserSettings.setLastRemoteId(remoteId)
            UserSettings.setLastConnectionMode(UserSettings.ConnectionMode.REMOTE)

            // Initiate remote connection via WebRTC
            connectToRemoteServer(remoteId)
        }
    }

    /**
     * Initiates a remote connection via WebRTC using the Music Assistant Remote ID.
     * Sends a custom command to PlaybackService which handles the WebRTC connection.
     *
     * @param remoteId The 26-character Remote ID from Music Assistant settings
     */
    private fun connectToRemoteServer(remoteId: String) {
        val controller = mediaController
        if (controller == null) {
            showErrorSnackbar(
                message = getString(R.string.error_service_not_connected),
                errorType = ErrorType.CONNECTION
            )
            return
        }

        // Update state to show connecting UI
        connectionState = AppConnectionState.Connecting("Remote Server", remoteId)
        showConnectionLoading("Remote Server")

        // Send remote connect command to PlaybackService
        try {
            val args = Bundle().apply {
                putString(PlaybackService.ARG_REMOTE_ID, remoteId)
            }
            val command = SessionCommand(PlaybackService.COMMAND_CONNECT_REMOTE, Bundle.EMPTY)
            controller.sendCustomCommand(command, args)
            Log.d(TAG, "Sent remote connect command for Remote ID: $remoteId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send remote connect command", e)
            connectionState = AppConnectionState.Error("Remote connection failed")
            hideConnectionLoading()
            showErrorSnackbar(
                message = getString(R.string.remote_connection_failed, e.message ?: "Unknown error"),
                errorType = ErrorType.CONNECTION
            )
        }
    }

    /**
     * Shows the proxy connection dialog for connecting via reverse proxy.
     */
    private fun showProxyConnectDialog() {
        ProxyConnectDialog.show(supportFragmentManager) { url, authToken, nickname ->
            // Save connection preferences for quick reconnection
            UserSettings.setLastProxyUrl(url)
            UserSettings.setLastConnectionMode(UserSettings.ConnectionMode.PROXY)

            // Initiate proxy connection
            connectToProxyServer(url, authToken)
        }
    }

    /**
     * Initiates a connection via authenticated reverse proxy.
     * Sends a custom command to PlaybackService which handles the WebSocket connection.
     *
     * @param url The proxy server URL (e.g., "https://ma.example.com/sendspin")
     * @param authToken The long-lived authentication token from Music Assistant
     */
    private fun connectToProxyServer(url: String, authToken: String) {
        val controller = mediaController
        if (controller == null) {
            showErrorSnackbar(
                message = getString(R.string.error_service_not_connected),
                errorType = ErrorType.CONNECTION
            )
            return
        }

        // Update state to show connecting UI
        connectionState = AppConnectionState.Connecting("Proxy Server", url)
        showConnectionLoading("Proxy Server")

        // Send proxy connect command to PlaybackService
        try {
            val args = Bundle().apply {
                putString(PlaybackService.ARG_PROXY_URL, url)
                putString(PlaybackService.ARG_AUTH_TOKEN, authToken)
            }
            val command = SessionCommand(PlaybackService.COMMAND_CONNECT_PROXY, Bundle.EMPTY)
            controller.sendCustomCommand(command, args)
            Log.d(TAG, "Sent proxy connect command for URL: $url")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send proxy connect command", e)
            connectionState = AppConnectionState.Error("Proxy connection failed")
            hideConnectionLoading()
            showErrorSnackbar(
                message = getString(R.string.proxy_connection_failed, e.message ?: "Unknown error"),
                errorType = ErrorType.CONNECTION
            )
        }
    }

    // ============================================================================
    // Unified Server Support (MVP)
    // ============================================================================

    /**
     * Sets up unified server functionality.
     * This MVP implementation adds a FAB for adding unified servers and observes
     * the UnifiedServerRepository for server list updates.
     */
    private fun setupUnifiedServers() {
        // Initialize the connector for handling unified server connections
        unifiedServerConnector = UnifiedServerConnector(this) { selected ->
            // Callback when connection method is selected
            Log.d(TAG, "Connection method selected: ${ConnectionSelector.getConnectionDescription(selected)}")
        }

        // Setup unified server adapter
        unifiedServerAdapter = UnifiedServerAdapter(object : UnifiedServerAdapter.Callback {
            override fun onServerClick(server: UnifiedServer) {
                onUnifiedServerSelected(server)
            }

            override fun onQuickConnect(server: UnifiedServer) {
                onUnifiedServerQuickConnect(server)
            }

            override fun onServerLongClick(server: UnifiedServer): Boolean {
                showUnifiedServerContextMenu(server)
                return true
            }
        })

        // Observe unified servers and update the list
        lifecycleScope.launch {
            UnifiedServerRepository.allServers.collectLatest { servers ->
                unifiedServerAdapter?.submitList(servers)
                Log.d(TAG, "Unified servers updated: ${servers.size} servers")
            }
        }

        // For MVP: The Add Server Wizard is accessible via menu (action_add_unified_server)
        // A FAB can be added to the layout later for quick access
    }

    /**
     * Shows the add server wizard activity for creating unified servers.
     * Uses full-screen Activity instead of Dialog for proper keyboard handling.
     */
    private fun showAddServerWizard() {
        val intent = Intent(this, AddServerWizardActivity::class.java)
        addServerWizardLauncher.launch(intent)
    }

    /**
     * Shows the add server wizard activity for editing an existing server.
     */
    private fun showEditServerWizard(server: UnifiedServer) {
        val intent = Intent(this, AddServerWizardActivity::class.java).apply {
            putExtra(AddServerWizardActivity.EXTRA_EDIT_SERVER_ID, server.id)
        }
        addServerWizardLauncher.launch(intent)
    }

    /**
     * Handles tap on a unified server - connects using auto-selection.
     */
    private fun onUnifiedServerSelected(server: UnifiedServer) {
        val controller = mediaController
        if (controller == null) {
            showErrorSnackbar(
                message = getString(R.string.error_service_not_connected),
                errorType = ErrorType.CONNECTION
            )
            return
        }

        val connector = unifiedServerConnector
        if (connector == null) {
            Log.e(TAG, "UnifiedServerConnector not initialized")
            return
        }

        // Cancel any ongoing auto-reconnect if user taps a different server
        val reconnectingId = autoReconnectManager?.getReconnectingServerId()
        if (reconnectingId != null && reconnectingId != server.id) {
            Log.i(TAG, "User selected different server - cancelling auto-reconnect for $reconnectingId")
            autoReconnectManager?.cancelReconnection()
            sectionedServerAdapter?.clearReconnectProgress(reconnectingId)
            reconnectingToServer = null
        } else if (reconnectingId == server.id) {
            // User tapped the server we're reconnecting to - let auto-reconnect continue
            Log.d(TAG, "User tapped reconnecting server - auto-reconnect continues")
            return
        }

        // Handle userManuallyDisconnected flag based on what the user selected
        if (server.isDefaultServer) {
            // User manually connected to default server - allow future auto-connects
            userManuallyDisconnected = false
            viewModel.setUserManuallyDisconnected(false)
            Log.d(TAG, "User selected default server - cleared userManuallyDisconnected flag")
        } else if (connectionState is AppConnectionState.Connected) {
            // User switching from one server to another (non-default) - block auto-connect
            userManuallyDisconnected = true
            viewModel.setUserManuallyDisconnected(true)
            defaultServerPinger?.stop()  // Don't ping after manual switch
            Log.d(TAG, "User switched to non-default server - set userManuallyDisconnected flag")
        }

        // Stop discovery if running
        discoveryManager?.stopDiscovery()

        // Track the server ID for editing while connected
        currentConnectedServerId = server.id
        // Sync state to ViewModel for Compose UI
        viewModel.setCurrentConnectedServerId(server.id)

        // Update state to connecting
        connectionState = AppConnectionState.Connecting(server.name, server.id)
        viewModel.updateConnectionState(connectionState)
        showConnectionLoading(server.name)

        // Connect using auto-selection
        val selected = connector.connect(server, controller)
        if (selected == null) {
            hideConnectionLoading()
            connectionState = AppConnectionState.Error("No connection method available")
            showErrorSnackbar(
                message = getString(R.string.no_connection_available),
                errorType = ErrorType.CONNECTION
            )
            return
        }

        // Update adapter status for both adapters
        unifiedServerAdapter?.setServerStatus(server.id, UnifiedServerAdapter.ServerStatus.CONNECTING)
        sectionedServerAdapter?.setServerStatus(server.id, SectionedServerAdapter.ServerStatus.CONNECTING)

        // Show which method was selected
        val methodDesc = ConnectionSelector.getConnectionDescription(selected)
        Log.d(TAG, "Connecting to ${server.name} via $methodDesc")
    }

    /**
     * Handles Quick Connect on a discovered unified server.
     * Offers to save the server after successful connection.
     */
    private fun onUnifiedServerQuickConnect(server: UnifiedServer) {
        // Connect first
        onUnifiedServerSelected(server)

        // TODO: After successful connection, show "Save this server?" prompt
        // This can be implemented by observing connection state changes
    }

    /**
     * Shows context menu for a unified server (long press).
     */
    private fun showUnifiedServerContextMenu(server: UnifiedServer) {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // Edit option (only for saved servers)
        if (!server.isDiscovered) {
            items.add(getString(R.string.edit_server))
            actions.add {
                showEditServerWizard(server)
            }
        }

        // Set as default / Remove default (only for saved servers)
        if (!server.isDiscovered) {
            if (server.isDefaultServer) {
                items.add(getString(R.string.remove_default))
                actions.add {
                    UnifiedServerRepository.setDefaultServer(null)
                    showInfoSnackbar("Default server cleared")
                }
            } else {
                items.add(getString(R.string.set_as_default))
                actions.add {
                    UnifiedServerRepository.setDefaultServer(server.id)
                    showInfoSnackbar("${server.name} set as default")
                }
            }
        }

        // Delete option (only for saved servers)
        if (!server.isDiscovered) {
            items.add(getString(R.string.delete_server))
            actions.add {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_server)
                    .setMessage("Delete \"${server.name}\"?")
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        UnifiedServerRepository.deleteServer(server.id)
                        showInfoSnackbar("Server deleted")
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        // Save option (only for discovered servers)
        if (server.isDiscovered) {
            items.add(getString(R.string.save_server))
            actions.add {
                UnifiedServerRepository.promoteDiscoveredServer(server)
                showSuccessSnackbar(getString(R.string.server_added, server.name))
            }
        }

        if (items.isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(server.name)
                .setItems(items.toTypedArray()) { _, which ->
                    actions[which]()
                }
                .show()
        }
    }

    /**
     * Updates unified server adapter status when connection state changes.
     * Called from MediaController listener callbacks.
     */
    private fun updateUnifiedServerConnectionStatus(serverId: String?, status: UnifiedServerAdapter.ServerStatus) {
        serverId?.let { id ->
            unifiedServerAdapter?.setServerStatus(id, status)
        }
        if (status == UnifiedServerAdapter.ServerStatus.DISCONNECTED) {
            unifiedServerAdapter?.clearStatuses()
        }
    }

    /**
     * Validates server address format (host:port).
     *
     * Current implementation: Basic validation only
     * TODO: Add hostname/IP validation (regex for IP, DNS lookup for hostnames)
     * TODO: Consider using Inet4Address.getByName() for proper validation
     */
    private fun validateServerAddress(address: String): Boolean {
        if (address.isEmpty()) return false

        // Check for host:port format
        val parts = address.split(":")
        if (parts.size != 2) return false

        val host = parts[0]
        val portStr = parts[1]

        // Validate host is not empty (but doesn't validate if it's a valid IP/hostname)
        if (host.isEmpty()) return false

        // Validate port is a valid number in the valid port range
        val port = portStr.toIntOrNull() ?: return false
        if (port !in 1..65535) return false

        return true
    }

    /**
     * Handles user selecting a server from the manual entry list.
     */
    private fun onServerSelected(server: ServerInfo) {
        Log.d(TAG, "Server selected: ${server.name}")

        val controller = mediaController
        if (controller == null) {
            showErrorSnackbar(
                message = getString(R.string.error_service_not_connected),
                errorType = ErrorType.CONNECTION
            )
            return
        }

        // Update state to Connecting
        connectionState = AppConnectionState.Connecting(server.name, server.address)

        // Show connection loading UI
        showConnectionLoading(server.name)

        // Perform the actual connection
        connectToServer(server, controller)
    }

    /**
     * Shared connection logic used by both auto-connect and manual selection.
     */
    private fun connectToServer(server: ServerInfo, controller: MediaController) {
        try {
            // Add to recent servers for quick access (Android Auto shows these)
            ServerRepository.addToRecent(server)

            // Send CONNECT command to PlaybackService via MediaController
            val args = Bundle().apply {
                putString(PlaybackService.ARG_SERVER_ADDRESS, server.address)
                putString(PlaybackService.ARG_SERVER_PATH, server.path)
            }
            val command = SessionCommand(PlaybackService.COMMAND_CONNECT, Bundle.EMPTY)

            controller.sendCustomCommand(command, args)
            Log.d(TAG, "Sent connect command to ${server.address} path=${server.path}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send connect command", e)
            connectionState = AppConnectionState.Error("Connection failed")
            hideConnectionLoading()
            showErrorSnackbar(
                message = getString(R.string.error_connection_failed),
                errorType = ErrorType.CONNECTION,
                retryAction = { onServerSelected(server) }
            )
        }
    }

    /**
     * Handles previous button click.
     * Sends previous track command to PlaybackService via custom command.
     */
    private fun onPreviousClicked() {
        Log.d(TAG, "Previous clicked")
        val controller = mediaController ?: return
        val command = SessionCommand(PlaybackService.COMMAND_PREVIOUS, Bundle.EMPTY)
        controller.sendCustomCommand(command, Bundle.EMPTY)
    }

    /**
     * Handles play/pause toggle button click.
     * Toggles between play and pause based on current state.
     */
    private fun onPlayPauseClicked() {
        val controller = mediaController ?: return

        if (controller.isPlaying) {
            Log.d(TAG, "Pause clicked")
            controller.pause()
        } else {
            Log.d(TAG, "Play clicked")
            controller.play()
        }
    }

    /**
     * Handles next button click.
     * Sends next track command to PlaybackService via custom command.
     */
    private fun onNextClicked() {
        Log.d(TAG, "Next clicked")
        val controller = mediaController ?: return
        val command = SessionCommand(PlaybackService.COMMAND_NEXT, Bundle.EMPTY)
        controller.sendCustomCommand(command, Bundle.EMPTY)
    }

    /**
     * Handles switch group button click.
     * Sends switch group command to PlaybackService to cycle to next available group.
     */
    private fun onSwitchGroupClicked() {
        Log.d(TAG, "Switch group clicked")
        val controller = mediaController ?: return
        val command = SessionCommand(PlaybackService.COMMAND_SWITCH_GROUP, Bundle.EMPTY)
        controller.sendCustomCommand(command, Bundle.EMPTY)
    }

    /**
     * Handles favorite button click.
     * Adds the currently playing track to Music Assistant favorites.
     */
    private fun onFavoriteClicked() {
        Log.d(TAG, "Favorite clicked")

        lifecycleScope.launch {
            val result = MusicAssistantManager.favoriteCurrentTrack()
            result.fold(
                onSuccess = { message ->
                    Snackbar.make(binding.coordinatorLayout, R.string.favorite_added, Snackbar.LENGTH_SHORT).show()
                    announceForAccessibility(getString(R.string.favorite_added))
                },
                onFailure = { error ->
                    val errorMessage = when {
                        error.message?.contains("No track") == true -> R.string.favorite_no_track
                        else -> R.string.favorite_error
                    }
                    Snackbar.make(binding.coordinatorLayout, errorMessage, Snackbar.LENGTH_SHORT).show()
                    Log.e(TAG, "Favorite failed: ${error.message}")
                }
            )
        }
    }

    /**
     * Observes Music Assistant connection state to show/hide MA-dependent UI elements.
     * - Favorite button: only visible when connected to MA
     * - Bottom navigation: only visible when connected to MA (Home/Search/Library need MA API)
     */
    private fun observeMaConnectionState() {
        lifecycleScope.launch {
            MusicAssistantManager.connectionState.collectLatest { state ->
                val isMaConnected = state is MaConnectionState.Connected

                // Favorite button visibility
                binding.favoriteButton?.visibility = if (isMaConnected) View.VISIBLE else View.GONE

                // Bottom navigation visibility - only show when MA is connected
                // LinearLayout stack handles spacing automatically, no margin hacks needed
                binding.bottomNavigation?.visibility = if (isMaConnected) View.VISIBLE else View.GONE

                // If MA disconnects while showing navigation content, return to full player
                if (!isMaConnected && isNavigationContentVisible) {
                    hideNavigationContent()
                }

                Log.d(TAG, "MA connection state changed: $state, MA-dependent UI visible: $isMaConnected")
            }
        }
    }

    /**
     * Handles disconnect button click.
     * Shows confirmation dialog before disconnecting.
     */
    private fun onDisconnectClicked() {
        Log.d(TAG, "Disconnect clicked")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disconnect_dialog_title)
            .setMessage(R.string.disconnect_dialog_message)
            .setPositiveButton(R.string.disconnect_dialog_positive) { _, _ ->
                performDisconnect()
            }
            .setNegativeButton(R.string.disconnect_dialog_negative, null)
            .show()
    }

    /**
     * Performs the actual disconnect operation.
     * Sends disconnect command to PlaybackService and returns to server list view.
     *
     * This is a user-initiated disconnect ("Switch Server"), so we set
     * userManuallyDisconnected to prevent auto-connecting to the default server.
     */
    private fun performDisconnect() {
        val controller = mediaController ?: return

        // User explicitly chose to disconnect - block auto-connect to default server
        userManuallyDisconnected = true
        viewModel.setUserManuallyDisconnected(true)
        defaultServerPinger?.stop()  // Don't ping after manual disconnect

        try {
            val command = SessionCommand(PlaybackService.COMMAND_DISCONNECT, Bundle.EMPTY)
            controller.sendCustomCommand(command, Bundle.EMPTY)
            transitionToServerList()
            sectionedServerAdapter?.clearStatuses()
            showInfoSnackbar("Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
            showErrorSnackbar(
                message = "Failed to disconnect",
                errorType = ErrorType.CONNECTION
            )
        }
    }

    /**
     * Handles volume slider changes.
     *
     * Sets device STREAM_MUSIC volume directly (Spotify-style) AND syncs to server
     * via PlaybackService custom command for multi-client coordination.
     *
     * @param volume Normalized volume from 0.0 to 1.0
     */
    private fun onVolumeChanged(volume: Float) {
        Log.d(TAG, "Volume changed: $volume")

        // Set device volume directly (Spotify-style)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val newVolume = (volume * maxVolume).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

        // Also notify PlaybackService to sync to server (for multi-client coordination)
        val controller = mediaController ?: return
        val args = Bundle().apply {
            putFloat(PlaybackService.ARG_VOLUME, volume)
        }
        val command = SessionCommand(PlaybackService.COMMAND_SET_VOLUME, Bundle.EMPTY)
        controller.sendCustomCommand(command, args)
    }

    /**
     * Syncs the volume slider with the current device STREAM_MUSIC volume.
     * Called on startup and when returning from background.
     * Also syncs mini player volume slider if visible.
     */
    private fun syncSliderWithDeviceVolume() {
        // Safety check - observer callback can fire during lifecycle transitions
        if (!::binding.isInitialized) return

        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // Round to nearest integer - slider has stepSize=1.0 and crashes on decimal values
        val sliderValue = ((currentVolume.toFloat() / maxVolume) * 100).toInt().toFloat()
        binding.volumeSlider.value = sliderValue

        // Mini player volume updates automatically via Compose/ViewModel state observation

        Log.d(TAG, "Synced slider with device volume: $currentVolume/$maxVolume ($sliderValue%)")
    }

    /**
     * Registers a ContentObserver to detect device volume changes from hardware buttons.
     * This keeps the UI slider in sync when user presses hardware volume buttons.
     */
    private fun registerVolumeObserver() {
        if (volumeObserver != null) return  // Already registered

        volumeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // Sync slider to device volume when hardware buttons are pressed
                syncSliderWithDeviceVolume()
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver!!
        )
        Log.d(TAG, "Volume observer registered")
    }

    /**
     * Unregisters the volume ContentObserver.
     */
    private fun unregisterVolumeObserver() {
        volumeObserver?.let {
            contentResolver.unregisterContentObserver(it)
            volumeObserver = null
            Log.d(TAG, "Volume observer unregistered")
        }
    }

    /**
     * Adds a discovered server to the list if not already present.
     *
     * Deduplication: Uses address as unique key (not name, since multiple servers
     * could have the same name but different addresses).
     *
     * Uses ListAdapter.submitList() which efficiently calculates the diff
     * on a background thread and applies minimal changes to the RecyclerView.
     */
    private fun addServer(server: ServerInfo) {
        // Add to ServerRepository so PlaybackService/Android Auto can see it
        ServerRepository.addDiscoveredServer(server)

        // Also add to local list for UI (deduplication by address)
        if (!servers.any { it.address == server.address }) {
            servers.add(server)
            // Submit a new list copy - ListAdapter requires a new list instance for diff calculation
            serverAdapter.submitList(servers.toList())
            // Update accessibility description for server list
            updateServerListAccessibility()
            updateServerListEmptyState()
        }
    }

    /**
     * Adds a manually entered server.
     * These are persisted across app restarts.
     */
    private fun addManualServer(server: ServerInfo) {
        // Add to ServerRepository (persisted)
        ServerRepository.addManualServer(server)

        // Also add to local list for UI (deduplication by address)
        if (!servers.any { it.address == server.address }) {
            servers.add(server)
            // Submit a new list copy - ListAdapter requires a new list instance for diff calculation
            serverAdapter.submitList(servers.toList())
            updateServerListAccessibility()
            updateServerListEmptyState()
        }
    }

    /**
     * Loads previously saved manual servers from ServerRepository.
     * Called on app startup to restore user's saved servers.
     */
    private fun loadPersistedServers() {
        val manualServers = ServerRepository.manualServers.value
        for (server in manualServers) {
            if (!servers.any { it.address == server.address }) {
                servers.add(server)
            }
        }
        if (manualServers.isNotEmpty()) {
            // Submit a new list copy - ListAdapter requires a new list instance for diff calculation
            serverAdapter.submitList(servers.toList())
            Log.d(TAG, "Loaded ${manualServers.size} saved servers")
        }
        // Update empty state after loading
        updateServerListEmptyState()
    }


    private fun enablePlaybackControls(enabled: Boolean) {
        binding.previousButton.isEnabled = enabled
        binding.playPauseButton.isEnabled = enabled
        binding.nextButton.isEnabled = enabled
        binding.switchGroupButton.isEnabled = enabled
        binding.volumeSlider.isEnabled = enabled
    }

    /**
     * Updates the group name display.
     * Shows the group name TextView if a group name is provided, hides it otherwise.
     */
    private fun updateGroupName(groupName: String) {
        // Sync state to ViewModel for Compose UI
        viewModel.updateGroupName(groupName)

        if (groupName.isNotEmpty()) {
            binding.groupNameText.text = getString(R.string.group_label, groupName)
            binding.groupNameText.visibility = View.VISIBLE
        } else {
            binding.groupNameText.visibility = View.GONE
        }
    }

    private fun updatePlaybackState(state: String) {
        // Show playback state in the toolbar title
        supportActionBar?.title = when (state) {
            "playing" -> "Playing"
            "paused" -> "Paused"
            "stopped" -> "Stopped"
            else -> "Not Playing"
        }
    }

    /**
     * Updates the play/pause button icon and content description based on playing state.
     * Also updates toolbar title to keep UI in sync.
     * Shows pause icon when playing, play icon when paused.
     */
    private fun updatePlayPauseButton(isPlaying: Boolean) {
        if (isPlaying) {
            binding.playPauseButton.setIconResource(R.drawable.ic_pause)
            binding.playPauseButton.contentDescription = getString(R.string.accessibility_pause_button)
            supportActionBar?.title = "Playing"
        } else {
            binding.playPauseButton.setIconResource(R.drawable.ic_play)
            binding.playPauseButton.contentDescription = getString(R.string.accessibility_play_button)
            supportActionBar?.title = "Paused"
        }

        // Mini player updates automatically via Compose/ViewModel state observation
    }

    private fun updateMetadata(title: String, artist: String, album: String) {
        // Sync state to ViewModel for Compose UI
        viewModel.updateMetadata(title, artist, album)

        // Song title goes in the large text field
        binding.nowPlayingText.text = if (title.isNotEmpty()) title else getString(R.string.not_playing)

        // Artist and album info in the smaller metadata field
        val metadata = buildString {
            if (artist.isNotEmpty()) append(artist)
            if (album.isNotEmpty()) {
                if (isNotEmpty()) append(" \u2022 ")  // bullet separator
                append(album)
            }
        }
        binding.metadataText.text = metadata

        // Update album art content description with current track info
        val artDescription = if (title.isNotEmpty()) {
            "Album artwork for $title"
        } else {
            getString(R.string.album_art)
        }
        binding.albumArtView.contentDescription = artDescription

        // Mini player updates automatically via Compose/ViewModel state observation
    }

    /**
     * Updates album art from MediaMetadata.
     *
     * Tries to load artwork from:
     * 1. artworkData (byte array embedded in metadata)
     * 2. artworkUri (URI reference)
     *
     * Uses Coil for efficient image loading with crossfade animation.
     * Also extracts colors from the artwork to apply to the volume slider.
     */
    private fun updateAlbumArt(mediaMetadata: MediaMetadata) {
        val artworkData = mediaMetadata.artworkData
        val artworkUri = mediaMetadata.artworkUri

        when {
            artworkData != null && artworkData.isNotEmpty() -> {
                // Sync state to ViewModel for Compose UI
                viewModel.updateArtwork(ArtworkSource.ByteArray(artworkData))

                // Load from byte array (binary artwork from protocol)
                Log.d(TAG, "Loading artwork from byte array: ${artworkData.size} bytes")
                binding.albumArtView.load(artworkData) {
                    crossfade(true)
                    placeholder(R.drawable.placeholder_album)
                    error(R.drawable.placeholder_album)
                    transformations(RoundedCornersTransformation(8f))
                    listener(
                        onSuccess = { _, result ->
                            // Extract colors from loaded artwork and update blurred background
                            (result.drawable as? BitmapDrawable)?.bitmap?.let { bitmap ->
                                extractAndApplyColors(bitmap)
                                updateBlurredBackground(bitmap)
                            }
                        }
                    )
                }
            }
            artworkUri != null -> {
                // Sync state to ViewModel for Compose UI
                viewModel.updateArtwork(ArtworkSource.Uri(artworkUri))

                // Load from URI (could be local or remote)
                Log.d(TAG, "Loading artwork from URI: $artworkUri")
                binding.albumArtView.load(artworkUri) {
                    crossfade(true)
                    placeholder(R.drawable.placeholder_album)
                    error(R.drawable.placeholder_album)
                    transformations(RoundedCornersTransformation(8f))
                    listener(
                        onSuccess = { _, result ->
                            // Extract colors from loaded artwork and update blurred background
                            (result.drawable as? BitmapDrawable)?.bitmap?.let { bitmap ->
                                extractAndApplyColors(bitmap)
                                updateBlurredBackground(bitmap)
                            }
                        }
                    )
                }
            }
            else -> {
                // Sync state to ViewModel for Compose UI
                viewModel.clearArtwork()

                // No artwork available, show placeholder and reset colors
                binding.albumArtView.setImageResource(R.drawable.placeholder_album)
                resetSliderColors()
                clearBlurredBackground()
            }
        }
    }

    /**
     * Loads artwork from a URL using Coil.
     * Called when we receive artwork URL via session extras.
     * Skipped in low memory mode - shows placeholder instead.
     */
    private fun loadArtworkFromUrl(url: String) {
        // Sync state to ViewModel for Compose UI
        viewModel.updateArtwork(ArtworkSource.Url(url))
        // Skip artwork loading in low memory mode
        if (UserSettings.lowMemoryMode) {
            binding.albumArtView.setImageResource(R.drawable.placeholder_album)
            resetSliderColors()
            clearBlurredBackground()
            return
        }

        Log.d(TAG, "Loading artwork from URL: $url")
        binding.albumArtView.load(url) {
            crossfade(true)
            placeholder(R.drawable.placeholder_album)
            error(R.drawable.placeholder_album)
            transformations(RoundedCornersTransformation(8f))
            listener(
                onSuccess = { _, result ->
                    // Extract colors from loaded artwork and update blurred background
                    (result.drawable as? BitmapDrawable)?.bitmap?.let { bitmap ->
                        extractAndApplyColors(bitmap)
                        updateBlurredBackground(bitmap)
                    }
                }
            )
        }
    }

    /**
     * Extracts dominant colors from artwork and applies them to UI elements.
     * Uses the Palette library to analyze the image.
     * Skipped in low memory mode to save memory.
     *
     * Applies colors to:
     * - Volume slider (vibrant/muted color)
     * - Background (dark muted color for ambient effect)
     */
    private fun extractAndApplyColors(bitmap: Bitmap) {
        // Skip Palette extraction in low memory mode
        if (UserSettings.lowMemoryMode) {
            resetSliderColors()
            return
        }

        Palette.from(bitmap).generate { palette ->
            palette?.let {
                // Get the vibrant swatch for slider, or fall back to muted, then dominant
                val accentSwatch = it.vibrantSwatch
                    ?: it.mutedSwatch
                    ?: it.dominantSwatch

                accentSwatch?.let { color ->
                    // Apply extracted color to volume slider
                    binding.volumeSlider.trackActiveTintList = ColorStateList.valueOf(color.rgb)
                    binding.volumeSlider.thumbTintList = ColorStateList.valueOf(color.rgb)
                    Log.d(TAG, "Applied artwork color to volume slider: ${Integer.toHexString(color.rgb)}")
                }

                // Get dark muted color for background, or darken the dominant color
                val backgroundSwatch = it.darkMutedSwatch
                    ?: it.darkVibrantSwatch
                    ?: it.mutedSwatch

                val backgroundColor = if (backgroundSwatch != null) {
                    // Darken the color further for a subtle ambient background
                    darkenColor(backgroundSwatch.rgb, 0.3f)
                } else if (it.dominantSwatch != null) {
                    // Fall back to darkened dominant color
                    darkenColor(it.dominantSwatch!!.rgb, 0.2f)
                } else {
                    // Default dark background
                    ContextCompat.getColor(this, R.color.md_theme_dark_background)
                }

                // Store and apply background color to entire window (root CoordinatorLayout)
                lastBackgroundColor = backgroundColor
                binding.coordinatorLayout.setBackgroundColor(backgroundColor)
                Log.d(TAG, "Applied background color to window: ${Integer.toHexString(backgroundColor)}")
            }
        }
    }

    /**
     * Darkens a color by a given factor.
     * @param color The original color
     * @param factor How much to darken (0.0 = black, 1.0 = original color)
     */
    private fun darkenColor(color: Int, factor: Float): Int {
        val a = android.graphics.Color.alpha(color)
        val r = (android.graphics.Color.red(color) * factor).toInt()
        val g = (android.graphics.Color.green(color) * factor).toInt()
        val b = (android.graphics.Color.blue(color) * factor).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    /**
     * Resets the volume slider and background colors to default theme colors.
     * Called when no artwork is available or when disconnected.
     */
    private fun resetSliderColors() {
        val primaryColor = ContextCompat.getColor(this, com.google.android.material.R.color.design_default_color_primary)
        binding.volumeSlider.trackActiveTintList = ColorStateList.valueOf(primaryColor)
        binding.volumeSlider.thumbTintList = ColorStateList.valueOf(primaryColor)

        // Reset background to default theme color for entire window
        val backgroundColor = ContextCompat.getColor(this, R.color.md_theme_dark_background)
        binding.coordinatorLayout.setBackgroundColor(backgroundColor)

        // Clear the blurred background
        clearBlurredBackground()
    }

    /**
     * Updates the background with a blurred, scaled-up, rotated version of the album art.
     * Creates an ambient visual effect behind all UI elements.
     * Skipped in low memory mode to save resources.
     *
     * Uses RenderEffect for true Gaussian blur (requires API 31+).
     * On older devices, this feature is simply disabled.
     */
    private fun updateBlurredBackground(bitmap: Bitmap) {
        if (UserSettings.lowMemoryMode) return

        // RenderEffect blur requires API 31+ (Android 12)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return

        // De-duplicate: skip if this is the same artwork (use generationId as cheap identity check)
        val artworkId = "${bitmap.generationId}_${bitmap.width}x${bitmap.height}"
        if (artworkId == lastArtworkSource) {
            Log.d(TAG, "Skipping duplicate background update for artwork: $artworkId")
            return
        }
        lastArtworkSource = artworkId
        Log.d(TAG, "Updating blurred background for artwork: $artworkId")

        // Apply rotation and scale to ensure full coverage despite rotation
        binding.backgroundArtView.rotation = 30f
        binding.backgroundArtView.scaleX = 1.8f
        binding.backgroundArtView.scaleY = 1.8f

        // Load the image first
        binding.backgroundArtView.load(bitmap) {
            crossfade(500)
        }

        // Apply Gaussian blur via RenderEffect (API 31+)
        applyBlurEffect()
    }

    /**
     * Applies Gaussian blur effect to the background using RenderEffect.
     * Blur radius can be adjusted (higher = more blur).
     */
    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.S)
    private fun applyBlurEffect() {
        val blurRadius = 80f  // Adjust blur intensity here (1-150+)
        val blurEffect = android.graphics.RenderEffect.createBlurEffect(
            blurRadius, blurRadius,
            android.graphics.Shader.TileMode.CLAMP
        )
        binding.backgroundArtView.setRenderEffect(blurEffect)
    }

    /**
     * Clears the blurred background with a fade-out animation.
     */
    private fun clearBlurredBackground() {
        // Skip if feature not available
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return

        // Clear tracking so next artwork will update
        lastArtworkSource = null

        binding.backgroundArtView.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                binding.backgroundArtView.setImageDrawable(null)
                binding.backgroundArtView.setRenderEffect(null)
                binding.backgroundArtView.rotation = 0f
                binding.backgroundArtView.scaleX = 1f
                binding.backgroundArtView.scaleY = 1f
                binding.backgroundArtView.alpha = 0.5f // Reset for next use
            }
            .start()
    }

    /**
     * Clears the player background (blurred art and tint) when navigating to other screens.
     * The now playing view is hidden, so we don't want its background showing through.
     */
    private fun clearPlayerBackground() {
        // Reset to default dark background
        val defaultBg = ContextCompat.getColor(this, R.color.md_theme_dark_background)
        binding.coordinatorLayout.setBackgroundColor(defaultBg)

        // Hide the blurred background art (don't clear it, just hide for quick restore)
        binding.backgroundArtView.alpha = 0f
        Log.d(TAG, "Cleared player background for navigation")
    }

    /**
     * Restores the player background when returning to the now playing view.
     * Uses the stored background color and shows the blurred art again.
     */
    private fun restorePlayerBackground() {
        // Restore the tinted background color
        lastBackgroundColor?.let { color ->
            binding.coordinatorLayout.setBackgroundColor(color)
            Log.d(TAG, "Restored background color: ${Integer.toHexString(color)}")
        }

        // Fade in the blurred background art
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            binding.backgroundArtView.animate()
                .alpha(0.5f)
                .setDuration(300)
                .start()
        }
    }

    /**
     * Formats a duration in milliseconds to MM:SS format.
     */
    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return String.format("%d:%02d", minutes, seconds)
    }

    // ============================================================================
    // Loading State Management
    // ============================================================================

    /**
     * Shows the connection progress indicator.
     * Called when attempting to connect to a server.
     * Switches to now playing view and shows the connecting spinner.
     *
     * @param serverName The name of the server being connected to
     */
    private fun showConnectionLoading(serverName: String) {
        // Switch to now playing view but show connection progress
        binding.serverListView?.visibility = View.GONE
        binding.nowPlayingView.visibility = View.VISIBLE
        binding.connectionProgressContainer.visibility = View.VISIBLE
        binding.nowPlayingContent.visibility = View.GONE
        binding.connectionStatusText.text = getString(R.string.connecting_to_server, serverName)

        // Hide FAB while connecting
        binding.addServerFab?.visibility = View.GONE

        // Announce connecting state for accessibility
        announceForAccessibility(getString(R.string.accessibility_connecting))
    }

    /**
     * Hides the connection progress indicator.
     * Called when connection succeeds or fails.
     */
    private fun hideConnectionLoading() {
        binding.connectionProgressContainer.visibility = View.GONE
        binding.nowPlayingContent.visibility = View.VISIBLE
    }

    /**
     * Shows the buffering indicator overlay on album art.
     * Called during playback buffering state.
     */
    private fun showBufferingIndicator() {
        binding.bufferingIndicator.visibility = View.VISIBLE
    }

    /**
     * Hides the buffering indicator overlay.
     * Called when buffering completes and playback is ready.
     */
    private fun hideBufferingIndicator() {
        binding.bufferingIndicator.visibility = View.GONE
    }


    // ========================================================================
    // Menu Handling
    // ========================================================================

    /**
     * Inflate options menu. Settings is always visible; other items shown when connected.
     */
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_now_playing, menu)
        return true
    }

    /**
     * Update menu items visibility and titles based on connection state.
     * When connected:
     *   - "Switch Server" replaces disconnect (takes you back to server list)
     *   - "Edit Server" replaces add server (edits current server)
     * When not connected:
     *   - "Add Server" shows the wizard
     * Settings is always "App Settings".
     */
    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val isConnected = connectionState is AppConnectionState.Connected

        // Connection info header (only when connected)
        menu?.findItem(R.id.action_connection_info)?.apply {
            isVisible = isConnected
            if (isConnected) {
                val state = connectionState as AppConnectionState.Connected
                title = getString(R.string.connected_to, state.serverName)
            }
        }

        // Stats (only when connected)
        menu?.findItem(R.id.action_stats)?.isVisible = isConnected

        // Disconnect/Switch Server (only when connected, title changes)
        menu?.findItem(R.id.action_disconnect)?.apply {
            isVisible = isConnected
            title = getString(R.string.action_switch_server)
        }

        // Add Server / Edit Server (title changes based on connection)
        menu?.findItem(R.id.action_add_unified_server)?.apply {
            title = if (isConnected) {
                getString(R.string.action_edit_server)
            } else {
                getString(R.string.add_server)
            }
        }

        // Settings is always "App Settings"
        menu?.findItem(R.id.action_settings)?.title = getString(R.string.action_app_settings)

        return super.onPrepareOptionsMenu(menu)
    }

    /**
     * Handle menu item selection.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_stats -> {
                // Show Stats for Nerds bottom sheet
                StatsBottomSheet().show(supportFragmentManager, "stats")
                true
            }
            R.id.action_disconnect -> {
                // Show confirmation dialog before disconnecting
                onDisconnectClicked()
                true
            }
            R.id.action_settings -> {
                // Open Settings activity
                startActivity(android.content.Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_add_unified_server -> {
                val isConnected = connectionState is AppConnectionState.Connected
                if (isConnected && currentConnectedServerId != null) {
                    // Edit the currently connected server
                    val server = UnifiedServerRepository.getServer(currentConnectedServerId!!)
                    if (server != null) {
                        showEditServerWizard(server)
                    } else {
                        // Fallback: server not found in repository (discovered server)
                        showAddServerWizard()
                    }
                } else {
                    // Not connected - show Add Server Wizard
                    showAddServerWizard()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Activity cleanup - critical for preventing resource leaks.
     *
     * Best practice: Proper resource cleanup in lifecycle methods
     * Order matters: Release MediaController before cleaning up other resources
     */
    override fun onDestroy() {
        super.onDestroy()

        // Cancel any pending handler callbacks
        handler.removeCallbacksAndMessages(null)

        // Release MediaController connection to service
        mediaControllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        mediaController = null
        mediaControllerFuture = null

        // Cleanup NsdDiscoveryManager (handles multicast lock internally)
        discoveryManager?.cleanup()
        discoveryManager = null

        // Cleanup AutoReconnectManager
        autoReconnectManager?.destroy()
        autoReconnectManager = null

        // Cleanup DefaultServerPinger and charging receiver
        defaultServerPinger?.destroy()
        defaultServerPinger = null
        chargingReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister charging receiver", e)
            }
        }
        chargingReceiver = null
    }

    // ============================================================================
    // Android TV Remote Control Support
    // ============================================================================

    /**
     * Handles key events from TV remotes and D-pad controllers.
     *
     * Media keys (play/pause, next, previous) are handled when connected to a server.
     * On TV devices, volume up/down keys adjust the app volume slider instead of
     * system volume, providing a more intuitive experience.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle media keys when connected to a server
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (connectionState is AppConnectionState.Connected) {
                    onPlayPauseClicked()
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (connectionState is AppConnectionState.Connected) {
                    onPlayPauseClicked()
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (connectionState is AppConnectionState.Connected) {
                    onPlayPauseClicked()
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (connectionState is AppConnectionState.Connected) {
                    onNextClicked()
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (connectionState is AppConnectionState.Connected) {
                    onPreviousClicked()
                    return true
                }
            }
            // On TV devices, handle volume keys to adjust app volume
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (isTvDevice && connectionState is AppConnectionState.Connected) {
                    adjustVolume(+5)
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (isTvDevice && connectionState is AppConnectionState.Connected) {
                    adjustVolume(-5)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Adjusts the volume slider by a delta value.
     * Used for TV remote volume button handling.
     *
     * @param delta The amount to adjust (positive = up, negative = down)
     */
    private fun adjustVolume(delta: Int) {
        val currentValue = binding.volumeSlider.value.toInt()
        val newValue = (currentValue + delta).coerceIn(0, 100)
        binding.volumeSlider.value = newValue.toFloat()
        onVolumeChanged(newValue / 100f)

        // Provide feedback
        updateVolumeAccessibility(newValue)
        announceForAccessibility("Volume $newValue percent")
    }
}
