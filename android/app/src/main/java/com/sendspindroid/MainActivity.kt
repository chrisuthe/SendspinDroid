package com.sendspindroid

import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.sendspindroid.databinding.ActivityMainBinding
import com.sendspindroid.discovery.NsdDiscoveryManager
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

    // Connection state tracking
    private var isConnected = false
    private var connectedServerName: String? = null

    // NsdManager-based discovery (Android native - more reliable than Go's hashicorp/mdns)
    private var discoveryManager: NsdDiscoveryManager? = null

    // MediaController for communicating with PlaybackService
    // Provides playback control and state observation
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    companion object {
        private const val TAG = "MainActivity"
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        initializeDiscoveryManager()
        initializeMediaController()
    }

    private fun setupUI() {
        // Setup RecyclerView for servers
        serverAdapter = ServerAdapter(servers) { server ->
            onServerSelected(server)
        }
        binding.serversRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = serverAdapter
        }

        // Initialize accessibility for server list
        updateServerListAccessibility()

        // Discover button
        binding.discoverButton.setOnClickListener {
            onDiscoverClicked()
        }

        // Add manual server button
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

        // Volume slider with accessibility updates
        binding.volumeSlider.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                onVolumeChanged(value / 100f)
                // Update accessibility description with current volume
                updateVolumeAccessibility(value.toInt())
            }
        }

        // Disconnect button
        binding.disconnectButton.setOnClickListener {
            onDisconnectClicked()
        }

        // Initialize volume accessibility
        updateVolumeAccessibility(binding.volumeSlider.value.toInt())

        // Start with discovery view visible
        showDiscoveryView()
    }

    // ============================================================================
    // View State Management
    // ============================================================================

    /**
     * Shows the discovery view (server list, discover button, manual add).
     * Called when not connected to any server.
     */
    private fun showDiscoveryView() {
        binding.discoveryView.visibility = View.VISIBLE
        binding.nowPlayingView.visibility = View.GONE
        isConnected = false
        connectedServerName = null
    }

    /**
     * Shows the now playing view (album art, playback controls, disconnect).
     * Called when connected to a server.
     */
    private fun showNowPlayingView(serverName: String? = null) {
        binding.discoveryView.visibility = View.GONE
        binding.nowPlayingView.visibility = View.VISIBLE
        binding.nowPlayingContent.visibility = View.VISIBLE
        binding.connectionProgressContainer.visibility = View.GONE
        isConnected = true
        connectedServerName = serverName

        // Update the status bar with connected server name
        binding.connectedServerText.text = serverName ?: getString(R.string.connected_to, "server")
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
                    override fun onServerDiscovered(name: String, address: String) {
                        runOnUiThread {
                            Log.d(TAG, "Server discovered: $name at $address")
                            addServer(ServerInfo(name, address))
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
                            binding.statusText.text = getString(R.string.discovering)
                            showDiscoveryLoading()
                        }
                    }

                    override fun onDiscoveryStopped() {
                        runOnUiThread {
                            Log.d(TAG, "Discovery stopped")
                            hideDiscoveryLoading()
                            if (servers.isEmpty()) {
                                binding.statusText.text = getString(R.string.no_servers_found)
                            }
                        }
                    }

                    override fun onDiscoveryError(error: String) {
                        runOnUiThread {
                            Log.e(TAG, "Discovery error: $error")
                            hideDiscoveryLoading()
                            showErrorSnackbar(
                                message = getString(R.string.error_discovery),
                                errorType = ErrorType.DISCOVERY,
                                retryAction = { onDiscoverClicked() }
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
                updateStatus("Service disconnected")
                enablePlaybackControls(false)
            }
        }

        /**
         * Called when session extras change (metadata updates from PlaybackService).
         * This is how PlaybackService notifies us of metadata since we can't use
         * MediaItem metadata with our custom MediaSource.
         */
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            runOnUiThread {
                val title = extras.getString(PlaybackService.EXTRA_TITLE, "")
                val artist = extras.getString(PlaybackService.EXTRA_ARTIST, "")
                val album = extras.getString(PlaybackService.EXTRA_ALBUM, "")
                val artworkUrl = extras.getString(PlaybackService.EXTRA_ARTWORK_URL, "")

                Log.d(TAG, "Extras changed: $title / $artist (artwork: $artworkUrl)")

                // Update text metadata
                updateMetadata(title, artist, album)

                // Load artwork from URL if available
                if (artworkUrl.isNotEmpty()) {
                    loadArtworkFromUrl(artworkUrl)
                }
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
                        updateStatus(getString(R.string.not_connected))
                        enablePlaybackControls(false)
                        hideConnectionLoading()
                        hideBufferingIndicator()
                        // Return to discovery view when disconnected
                        showDiscoveryView()
                        // Announce disconnection for accessibility
                        announceForAccessibility(getString(R.string.accessibility_disconnected))
                    }
                    Player.STATE_BUFFERING -> {
                        updateStatus("Buffering...")
                        showBufferingIndicator()
                        // Show now playing view during buffering (we're connected)
                        if (!isConnected) {
                            showNowPlayingView(connectedServerName)
                        }
                        // Announce buffering for accessibility
                        announceForAccessibility(getString(R.string.accessibility_buffering))
                    }
                    Player.STATE_READY -> {
                        updateStatus(getString(R.string.connected_to, connectedServerName ?: "server"))
                        enablePlaybackControls(true)
                        hideConnectionLoading()
                        hideBufferingIndicator()
                        // Show now playing view when connected
                        showNowPlayingView(connectedServerName)
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
     */
    private fun syncUIWithPlayerState() {
        mediaController?.let { controller ->
            val isPlaying = controller.isPlaying
            val state = controller.playbackState

            runOnUiThread {
                when {
                    isPlaying -> {
                        updateStatus("Connected")
                        updatePlaybackState("playing")
                        enablePlaybackControls(true)
                    }
                    state == Player.STATE_READY -> {
                        updateStatus("Connected")
                        updatePlaybackState("paused")
                        enablePlaybackControls(true)
                    }
                    else -> {
                        updateStatus("Ready")
                        enablePlaybackControls(false)
                    }
                }

                // Sync play/pause button text
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
                    addServer(ServerInfo(serverName, address))
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

    private fun onDiscoverClicked() {
        Log.d(TAG, "Starting discovery")

        try {
            // NsdDiscoveryManager handles multicast lock internally
            discoveryManager?.startDiscovery()
            showInfoSnackbar(getString(R.string.discovering))
            Log.d(TAG, "Discovery started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            showErrorSnackbar(
                message = getString(R.string.error_discovery_start),
                errorType = ErrorType.DISCOVERY,
                retryAction = { onDiscoverClicked() }
            )
        }
    }

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

        try {
            // Save server name for later display
            connectedServerName = server.name

            // Send CONNECT command to PlaybackService via MediaController
            val args = Bundle().apply {
                putString(PlaybackService.ARG_SERVER_ADDRESS, server.address)
            }
            val command = SessionCommand(PlaybackService.COMMAND_CONNECT, Bundle.EMPTY)

            controller.sendCustomCommand(command, args)
            updateStatus("Connecting to ${server.name}...")
            showConnectionLoading(server.name)
            showInfoSnackbar(getString(R.string.connecting_to_server, server.name))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send connect command", e)
            connectedServerName = null
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
     * Handles disconnect button click.
     * Sends disconnect command to PlaybackService and returns to discovery view.
     */
    private fun onDisconnectClicked() {
        Log.d(TAG, "Disconnect clicked")
        val controller = mediaController ?: return

        try {
            val command = SessionCommand(PlaybackService.COMMAND_DISCONNECT, Bundle.EMPTY)
            controller.sendCustomCommand(command, Bundle.EMPTY)
            showDiscoveryView()
            updateStatus(getString(R.string.not_connected))
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
     * Adds a server to the list if not already present.
     *
     * Deduplication: Uses address as unique key (not name, since multiple servers
     * could have the same name but different addresses).
     *
     * Best practice: notifyItemInserted for efficient RecyclerView updates
     * vs notifyDataSetChanged which would re-render entire list
     */
    private fun addServer(server: ServerInfo) {
        if (!servers.any { it.address == server.address }) {
            servers.add(server)
            serverAdapter.notifyItemInserted(servers.size - 1)
            // Update accessibility description for server list
            updateServerListAccessibility()
        }
    }

    private fun updateStatus(status: String) {
        binding.statusText.text = status
    }

    private fun enablePlaybackControls(enabled: Boolean) {
        binding.previousButton.isEnabled = enabled
        binding.playPauseButton.isEnabled = enabled
        binding.nextButton.isEnabled = enabled
        binding.volumeSlider.isEnabled = enabled
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
                }
            }
            else -> {
                // No artwork available, show placeholder
                binding.albumArtView.setImageResource(R.drawable.placeholder_album)
            }
        }
    }

    /**
     * Loads artwork from a URL using Coil.
     * Called when we receive artwork URL via session extras.
     */
    private fun loadArtworkFromUrl(url: String) {
        Log.d(TAG, "Loading artwork from URL: $url")
        binding.albumArtView.load(url) {
            crossfade(true)
            placeholder(R.drawable.placeholder_album)
            error(R.drawable.placeholder_album)
            transformations(RoundedCornersTransformation(8f))
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
     * Shows the discovery loading indicator.
     * Called when server discovery begins.
     */
    private fun showDiscoveryLoading() {
        binding.discoveryProgressIndicator.visibility = View.VISIBLE
        binding.discoveryStatusText.visibility = View.VISIBLE
        binding.discoverButton.isEnabled = false
    }

    /**
     * Hides the discovery loading indicator.
     * Called when server discovery completes or fails.
     */
    private fun hideDiscoveryLoading() {
        binding.discoveryProgressIndicator.visibility = View.GONE
        binding.discoveryStatusText.visibility = View.GONE
        binding.discoverButton.isEnabled = true
    }

    /**
     * Shows the connection progress indicator.
     * Called when attempting to connect to a server.
     * Switches to now playing view and shows the connecting spinner.
     *
     * @param serverName The name of the server being connected to
     */
    private fun showConnectionLoading(serverName: String) {
        // Switch to now playing view but show connection progress
        binding.discoveryView.visibility = View.GONE
        binding.nowPlayingView.visibility = View.VISIBLE
        binding.connectionProgressContainer.visibility = View.VISIBLE
        binding.nowPlayingContent.visibility = View.GONE
        binding.connectionStatusText.text = getString(R.string.connecting_to_server, serverName)
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

    /**
     * Activity cleanup - critical for preventing resource leaks.
     *
     * Best practice: Proper resource cleanup in lifecycle methods
     * Order matters: Release MediaController before cleaning up other resources
     */
    override fun onDestroy() {
        super.onDestroy()

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
