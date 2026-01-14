package com.sendspindroid

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.AnimationUtils
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    // List backing the RecyclerView - Consider moving to ViewModel with StateFlow for v2
    private val servers = mutableListOf<ServerInfo>()

    // Connection state machine
    private var connectionState: AppConnectionState = AppConnectionState.Searching

    // NsdManager-based discovery (Android native - more reliable than Go's hashicorp/mdns)
    private var discoveryManager: NsdDiscoveryManager? = null

    // MediaController for communicating with PlaybackService
    // Provides playback control and state observation
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    // Handler for timeout-based transitions
    private val handler = Handler(Looper.getMainLooper())
    private var showManualButtonRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null

    // Reconnecting indicator - persists while reconnection is in progress
    private var reconnectingSnackbar: Snackbar? = null

    // Network state monitoring
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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

    companion object {
        private const val TAG = "MainActivity"
        // Time before enabling "Enter manually" button during search
        private const val MANUAL_BUTTON_DELAY_MS = 5_000L
        private const val COUNTDOWN_INTERVAL_MS = 1_000L
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
     */
    private fun updateServerListAccessibility() {
        val count = servers.size
        binding.serversRecyclerView.contentDescription =
            getString(R.string.accessibility_server_list, count)
    }

    /**
     * Shows/hides the empty state view based on server list size.
     */
    private fun updateEmptyServerListState() {
        if (servers.isEmpty()) {
            binding.emptyServerListView.visibility = View.VISIBLE
            binding.serversRecyclerView.visibility = View.GONE
        } else {
            binding.emptyServerListView.visibility = View.GONE
            binding.serversRecyclerView.visibility = View.VISIBLE
        }
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
        // Dismiss any existing reconnecting snackbar
        reconnectingSnackbar?.dismiss()

        val bufferSec = bufferMs / 1000
        val message = if (bufferSec > 0) {
            "Reconnecting (attempt $attempt)... ${bufferSec}s buffer"
        } else {
            "Reconnecting (attempt $attempt)..."
        }

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

        // Initialize ServerRepository for sharing server state with PlaybackService
        // This enables Android Auto to see discovered servers
        ServerRepository.initialize(this)

        // Initialize UserSettings for accessing user preferences
        UserSettings.initialize(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up the toolbar as the action bar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowHomeEnabled(false)

        // Initialize discovery manager BEFORE setupUI, because setupUI calls
        // showSearchingView() which starts auto-discovery
        initializeDiscoveryManager()
        initializeMediaController()
        setupUI()

        // Show onboarding dialog for first-time users
        showOnboardingIfNeeded()

        // Request notification permission for Android 13+
        requestNotificationPermission()
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

    private fun setupUI() {
        // Setup RecyclerView for servers (in manual entry view)
        serverAdapter = ServerAdapter { server ->
            onServerSelected(server)
        }
        binding.serversRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = serverAdapter
        }

        // Load previously saved manual servers
        loadPersistedServers()

        // Initialize accessibility for server list
        updateServerListAccessibility()

        // Searching view - "Enter manually" button (shown after timeout)
        binding.searchingManualButton.setOnClickListener {
            transitionToManualEntry()
        }

        // Manual entry view - Search Again button
        binding.searchAgainButton.setOnClickListener {
            transitionToSearching()
        }

        // Manual entry view - Add manual server button
        binding.addManualServerButton.setOnClickListener {
            showAddServerDialog()
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

        // Volume slider with accessibility updates and haptic feedback
        binding.volumeSlider.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                onVolumeChanged(value / 100f)
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

        // Start with searching view and begin auto-discovery
        showSearchingView()
    }

    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
    }

    override fun onStop() {
        super.onStop()
        unregisterNetworkCallback()
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
        // Re-sync UI state with MediaController
        syncUIWithPlayerState()
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
                // Network restored - could optionally restart discovery if in searching state
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
    // View State Management (Three Views: Searching, Manual Entry, Now Playing)
    // ============================================================================

    /**
     * Transitions to searching state and starts auto-discovery.
     * Called on app launch and when user taps "Search Again".
     */
    private fun transitionToSearching() {
        connectionState = AppConnectionState.Searching
        showSearchingView()
    }

    /**
     * Transitions to manual entry state.
     * Called after search timeout or when user taps "Enter manually".
     */
    private fun transitionToManualEntry() {
        connectionState = AppConnectionState.ManualEntry
        cancelManualButtonTimeout()
        discoveryManager?.stopDiscovery()
        showManualEntryView()
    }

    /**
     * Shows the searching view (spinner, status text).
     * Starts discovery automatically and schedules timeout for manual button.
     */
    private fun showSearchingView() {
        // Clear toolbar subtitle when not connected
        supportActionBar?.subtitle = null

        // Animate view transitions
        if (binding.searchingView.visibility != View.VISIBLE) {
            binding.searchingView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in))
        }
        binding.searchingView.visibility = View.VISIBLE
        binding.manualEntryView.visibility = View.GONE
        binding.nowPlayingView.visibility = View.GONE

        // Reset the manual button to hidden
        binding.searchingManualButton.visibility = View.GONE
        binding.searchingStatusText.text = getString(R.string.searching_for_servers)

        // Announce searching state for accessibility
        announceForAccessibility(getString(R.string.searching_for_servers))

        // Start discovery automatically
        startAutoDiscovery()

        // Schedule showing the "Enter manually" button after timeout
        scheduleManualButtonTimeout()
    }

    /**
     * Shows the manual entry view (search again, add server, server list).
     * Called after timeout or explicit user choice.
     */
    private fun showManualEntryView() {
        // Clear toolbar subtitle when not connected
        supportActionBar?.subtitle = null

        binding.searchingView.visibility = View.GONE
        // Animate view transition
        if (binding.manualEntryView.visibility != View.VISIBLE) {
            binding.manualEntryView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in))
        }
        binding.manualEntryView.visibility = View.VISIBLE
        binding.nowPlayingView.visibility = View.GONE
    }

    /**
     * Shows the now playing view (album art, playback controls).
     * Called when connected to a server.
     * Connection status is shown in the toolbar subtitle.
     */
    private fun showNowPlayingView(serverName: String) {
        cancelManualButtonTimeout()

        // Show server name in toolbar subtitle
        supportActionBar?.subtitle = serverName

        binding.searchingView.visibility = View.GONE
        binding.manualEntryView.visibility = View.GONE

        // Animate view transition with slide up
        if (binding.nowPlayingView.visibility != View.VISIBLE) {
            binding.nowPlayingView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_up))
        }
        binding.nowPlayingView.visibility = View.VISIBLE
        binding.nowPlayingContent.visibility = View.VISIBLE
        binding.connectionProgressContainer.visibility = View.GONE
    }

    /**
     * Shows the "Enter manually" button with a countdown, then enables it.
     * Button is visible immediately but disabled until countdown completes.
     */
    private fun scheduleManualButtonTimeout() {
        cancelManualButtonTimeout()

        // Show button immediately, but disabled with countdown
        binding.searchingManualButton.visibility = View.VISIBLE
        binding.searchingManualButton.isEnabled = false

        var secondsRemaining = (MANUAL_BUTTON_DELAY_MS / 1000).toInt()
        binding.searchingManualButton.text = "${getString(R.string.enter_manually)} (${secondsRemaining}s)"

        // Countdown runnable
        countdownRunnable = object : Runnable {
            override fun run() {
                if (connectionState != AppConnectionState.Searching) return

                secondsRemaining--
                if (secondsRemaining > 0) {
                    binding.searchingManualButton.text = "${getString(R.string.enter_manually)} (${secondsRemaining}s)"
                    handler.postDelayed(this, COUNTDOWN_INTERVAL_MS)
                } else {
                    // Countdown finished - enable button
                    binding.searchingManualButton.text = getString(R.string.enter_manually)
                    binding.searchingManualButton.isEnabled = true
                    binding.searchingStatusText.text = getString(R.string.no_servers_found)
                }
            }
        }
        handler.postDelayed(countdownRunnable!!, COUNTDOWN_INTERVAL_MS)
    }

    /**
     * Cancels the pending manual button timeout and countdown.
     */
    private fun cancelManualButtonTimeout() {
        showManualButtonRunnable?.let { handler.removeCallbacks(it) }
        showManualButtonRunnable = null
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
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

                            // Auto-connect to first discovered server if in searching state
                            if (connectionState == AppConnectionState.Searching) {
                                Log.d(TAG, "Auto-connecting to first discovered server: $name")
                                binding.searchingStatusText.text = getString(R.string.server_found, name)
                                // Small delay to show "Found: X" before connecting
                                handler.postDelayed({
                                    autoConnectToServer(server)
                                }, 500)
                            }
                        }
                    }

                    override fun onServerLost(name: String) {
                        runOnUiThread {
                            Log.d(TAG, "Server lost: $name")
                            // Optionally remove from list
                            // servers.removeAll { it.name == name }
                            // serverAdapter.notifyDataSetChanged()
                        }
                    }

                    override fun onDiscoveryStarted() {
                        runOnUiThread {
                            Log.d(TAG, "Discovery started")
                        }
                    }

                    override fun onDiscoveryStopped() {
                        runOnUiThread {
                            Log.d(TAG, "Discovery stopped")
                        }
                    }

                    override fun onDiscoveryError(error: String) {
                        runOnUiThread {
                            Log.e(TAG, "Discovery error: $error")
                            if (connectionState == AppConnectionState.Searching) {
                                // Show error and allow manual entry
                                binding.searchingStatusText.text = getString(R.string.error_discovery)
                                binding.searchingManualButton.visibility = View.VISIBLE
                            } else {
                                showErrorSnackbar(
                                    message = getString(R.string.error_discovery),
                                    errorType = ErrorType.DISCOVERY,
                                    retryAction = { transitionToSearching() }
                                )
                            }
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
     * Starts auto-discovery for servers.
     * Called automatically when showing the searching view.
     */
    private fun startAutoDiscovery() {
        Log.d(TAG, "Starting auto-discovery")
        try {
            discoveryManager?.startDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            binding.searchingStatusText.text = getString(R.string.error_discovery_start)
            binding.searchingManualButton.visibility = View.VISIBLE
        }
    }

    /**
     * Auto-connects to a discovered server.
     * Called when a server is found during Searching state.
     *
     * @param server The server to connect to
     * @param retryCount Current retry attempt (default 0, max 10)
     */
    private fun autoConnectToServer(server: ServerInfo, retryCount: Int = 0) {
        val maxRetries = 10
        val controller = mediaController
        if (controller == null) {
            if (retryCount >= maxRetries) {
                Log.w(TAG, "Auto-connect failed: MediaController not ready after $maxRetries attempts")
                return
            }
            Log.w(TAG, "MediaController not ready, delaying auto-connect (attempt ${retryCount + 1}/$maxRetries)")
            // Retry after a short delay if controller isn't ready yet
            handler.postDelayed({ autoConnectToServer(server, retryCount + 1) }, 500)
            return
        }

        // Update state to Connecting
        connectionState = AppConnectionState.Connecting(server.name, server.address)
        cancelManualButtonTimeout()
        discoveryManager?.stopDiscovery()

        // Update UI to show connecting state
        binding.searchingStatusText.text = getString(R.string.auto_connecting)

        // Perform the actual connection
        connectToServer(server, controller)
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

                connectionState = AppConnectionState.Connected(serverName, address)
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

                // Show reconnecting indicator without disrupting playback view
                showReconnectingIndicator(attempt, bufferMs)

                // Keep playback controls enabled - playback continues from buffer
            }
            PlaybackService.STATE_DISCONNECTED -> {
                Log.d(TAG, "Disconnected from server")
                connectionState = AppConnectionState.ManualEntry
                showManualEntryView()
                enablePlaybackControls(false)
                invalidateOptionsMenu() // Hide "Switch Server" menu option

                // Announce disconnection for accessibility
                announceForAccessibility(getString(R.string.accessibility_disconnected))
            }
            PlaybackService.STATE_ERROR -> {
                val errorMessage = extras.getString(PlaybackService.EXTRA_ERROR_MESSAGE, "Unknown error")
                Log.e(TAG, "Connection error: $errorMessage")

                connectionState = AppConnectionState.Error(errorMessage)
                hideConnectionLoading()
                showManualEntryView()

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
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            runOnUiThread {
                Log.d(TAG, "Playback state: $playbackState")
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        enablePlaybackControls(false)
                        hideConnectionLoading()
                        hideBufferingIndicator()
                        // Only transition to manual entry if we were connected/connecting
                        // (not during initial startup)
                        val currentState = connectionState
                        if (currentState is AppConnectionState.Connected ||
                            currentState is AppConnectionState.Connecting) {
                            connectionState = AppConnectionState.ManualEntry
                            showManualEntryView()
                        }
                        // Announce disconnection for accessibility
                        announceForAccessibility(getString(R.string.accessibility_disconnected))
                    }
                    Player.STATE_BUFFERING -> {
                        showBufferingIndicator()
                        // Transition to Connected state and show now playing view
                        val currentState = connectionState
                        if (currentState is AppConnectionState.Connecting) {
                            connectionState = AppConnectionState.Connected(
                                currentState.serverName,
                                currentState.serverAddress
                            )
                            showNowPlayingView(currentState.serverName)
                        }
                        // Announce buffering for accessibility
                        announceForAccessibility(getString(R.string.accessibility_buffering))
                    }
                    Player.STATE_READY -> {
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
                            showNowPlayingView(currentState.serverName)
                        } else if (currentState is AppConnectionState.Connected) {
                            showNowPlayingView(currentState.serverName)
                        }
                        // Announce connection for accessibility
                        announceForAccessibility(getString(R.string.accessibility_connected))
                    }
                    Player.STATE_ENDED -> {
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
                    if (connectionState !is AppConnectionState.Connected) {
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
                }

                // Sync play/pause button icon
                updatePlayPauseButton(isPlaying)

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
     * Sends disconnect command to PlaybackService and returns to manual entry view.
     */
    private fun performDisconnect() {
        val controller = mediaController ?: return

        try {
            val command = SessionCommand(PlaybackService.COMMAND_DISCONNECT, Bundle.EMPTY)
            controller.sendCustomCommand(command, Bundle.EMPTY)
            transitionToManualEntry()
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
     * Sends SET_VOLUME command to PlaybackService via MediaController.
     */
    private fun onVolumeChanged(volume: Float) {
        Log.d(TAG, "Volume changed: $volume")

        val controller = mediaController ?: return

        val args = Bundle().apply {
            putFloat(PlaybackService.ARG_VOLUME, volume)
        }
        val command = SessionCommand(PlaybackService.COMMAND_SET_VOLUME, Bundle.EMPTY)
        controller.sendCustomCommand(command, args)
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
            updateEmptyServerListState()
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
            updateEmptyServerListState()
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
        updateEmptyServerListState()
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
        if (groupName.isNotEmpty()) {
            binding.groupNameText.text = getString(R.string.group_label, groupName)
            binding.groupNameText.visibility = View.VISIBLE
        } else {
            binding.groupNameText.visibility = View.GONE
        }
    }

    private fun updatePlaybackState(state: String) {
        binding.nowPlayingText.text = when (state) {
            "playing" -> "Playing"
            "paused" -> "Paused"
            "stopped" -> "Stopped"
            else -> "Not Playing"
        }
    }

    /**
     * Updates the play/pause button icon and content description based on playing state.
     * Shows pause icon when playing, play icon when paused.
     */
    private fun updatePlayPauseButton(isPlaying: Boolean) {
        if (isPlaying) {
            binding.playPauseButton.setIconResource(R.drawable.ic_pause)
            binding.playPauseButton.contentDescription = getString(R.string.accessibility_pause_button)
        } else {
            binding.playPauseButton.setIconResource(R.drawable.ic_play)
            binding.playPauseButton.contentDescription = getString(R.string.accessibility_play_button)
        }
    }

    private fun updateMetadata(title: String, artist: String, album: String) {
        val metadata = buildString {
            if (title.isNotEmpty()) append(title)
            if (artist.isNotEmpty()) {
                if (isNotEmpty()) append(" - ")
                append(artist)
            }
            if (album.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
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
                // Load from byte array (binary artwork from protocol)
                Log.d(TAG, "Loading artwork from byte array: ${artworkData.size} bytes")
                binding.albumArtView.load(artworkData) {
                    crossfade(true)
                    placeholder(R.drawable.placeholder_album)
                    error(R.drawable.placeholder_album)
                    transformations(RoundedCornersTransformation(8f))
                    listener(
                        onSuccess = { _, result ->
                            // Extract colors from loaded artwork
                            (result.drawable as? BitmapDrawable)?.bitmap?.let { bitmap ->
                                extractAndApplyColors(bitmap)
                            }
                        }
                    )
                }
            }
            artworkUri != null -> {
                // Load from URI (could be local or remote)
                Log.d(TAG, "Loading artwork from URI: $artworkUri")
                binding.albumArtView.load(artworkUri) {
                    crossfade(true)
                    placeholder(R.drawable.placeholder_album)
                    error(R.drawable.placeholder_album)
                    transformations(RoundedCornersTransformation(8f))
                    listener(
                        onSuccess = { _, result ->
                            // Extract colors from loaded artwork
                            (result.drawable as? BitmapDrawable)?.bitmap?.let { bitmap ->
                                extractAndApplyColors(bitmap)
                            }
                        }
                    )
                }
            }
            else -> {
                // No artwork available, show placeholder and reset colors
                binding.albumArtView.setImageResource(R.drawable.placeholder_album)
                resetSliderColors()
            }
        }
    }

    /**
     * Loads artwork from a URL using Coil.
     * Called when we receive artwork URL via session extras.
     * Skipped in low memory mode - shows placeholder instead.
     */
    private fun loadArtworkFromUrl(url: String) {
        // Skip artwork loading in low memory mode
        if (UserSettings.lowMemoryMode) {
            binding.albumArtView.setImageResource(R.drawable.placeholder_album)
            resetSliderColors()
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
                    // Extract colors from loaded artwork
                    (result.drawable as? BitmapDrawable)?.bitmap?.let { bitmap ->
                        extractAndApplyColors(bitmap)
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

                binding.nowPlayingView.setBackgroundColor(backgroundColor)
                Log.d(TAG, "Applied background color: ${Integer.toHexString(backgroundColor)}")
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

        // Reset background to default theme color
        val backgroundColor = ContextCompat.getColor(this, R.color.md_theme_dark_background)
        binding.nowPlayingView.setBackgroundColor(backgroundColor)
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
        binding.searchingView.visibility = View.GONE
        binding.manualEntryView.visibility = View.GONE
        binding.nowPlayingView.visibility = View.VISIBLE
        binding.connectionProgressContainer.visibility = View.VISIBLE
        binding.nowPlayingContent.visibility = View.GONE
        binding.connectionStatusText.text = getString(R.string.connecting_to_server, serverName)

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
     * Update menu items visibility based on connection state.
     * Settings is always visible; connection-specific items only when connected.
     */
    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val isConnected = connectionState is AppConnectionState.Connected

        // Connection-specific items only visible when connected
        menu?.findItem(R.id.action_connection_info)?.apply {
            isVisible = isConnected
            if (isConnected) {
                val state = connectionState as AppConnectionState.Connected
                title = getString(R.string.connected_to, state.serverName)
            }
        }
        menu?.findItem(R.id.action_stats)?.isVisible = isConnected
        menu?.findItem(R.id.action_disconnect)?.isVisible = isConnected

        // Settings is always visible (no change needed, visible by default)

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
        cancelManualButtonTimeout()
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
    }
}
