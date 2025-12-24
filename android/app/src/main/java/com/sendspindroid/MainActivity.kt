package com.sendspindroid

import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.RoundedCornersTransformation
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

    // NsdManager-based discovery (Android native - more reliable than Go's hashicorp/mdns)
    private var discoveryManager: NsdDiscoveryManager? = null

    // MediaController for communicating with PlaybackService
    // Provides playback control and state observation
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    companion object {
        private const val TAG = "MainActivity"
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

        // Volume slider
        binding.volumeSlider.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                onVolumeChanged(value / 100f)
            }
        }
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
                        }
                    }

                    override fun onDiscoveryStopped() {
                        runOnUiThread {
                            Log.d(TAG, "Discovery stopped")
                            if (servers.isEmpty()) {
                                binding.statusText.text = getString(R.string.no_servers_found)
                            }
                        }
                    }

                    override fun onDiscoveryError(error: String) {
                        runOnUiThread {
                            Log.e(TAG, "Discovery error: $error")
                            showError(error)
                        }
                    }
                }
            )
            Log.d(TAG, "NsdDiscoveryManager initialized")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize discovery manager", e)
            showError("Failed to initialize: ${e.message}")
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
                } else {
                    updatePlaybackState("paused")
                }
                // Update play/pause button text based on current state
                updatePlayPauseButton(isPlaying)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            runOnUiThread {
                Log.d(TAG, "Playback state: $playbackState")
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        updateStatus("Disconnected")
                        enablePlaybackControls(false)
                    }
                    Player.STATE_BUFFERING -> {
                        updateStatus("Buffering...")
                    }
                    Player.STATE_READY -> {
                        updateStatus("Connected")
                        enablePlaybackControls(true)
                    }
                    Player.STATE_ENDED -> {
                        updatePlaybackState("stopped")
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
                    Toast.makeText(this, "Server added: $serverName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.invalid_address), Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Discovering servers...", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Discovery started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            showError("Failed to start discovery: ${e.message}")
        }
    }

    private fun onServerSelected(server: ServerInfo) {
        Log.d(TAG, "Server selected: ${server.name}")

        val controller = mediaController
        if (controller == null) {
            showError("Service not connected")
            return
        }

        try {
            // Send CONNECT command to PlaybackService via MediaController
            val args = Bundle().apply {
                putString(PlaybackService.ARG_SERVER_ADDRESS, server.address)
            }
            val command = SessionCommand(PlaybackService.COMMAND_CONNECT, Bundle.EMPTY)

            controller.sendCustomCommand(command, args)
            updateStatus("Connecting to ${server.name}...")
            Toast.makeText(this, "Connecting to ${server.name}...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send connect command", e)
            showError("Failed to connect: ${e.message}")
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
     * Updates the play/pause button text based on playing state.
     * Shows "Pause" when playing, "Play" when paused.
     */
    private fun updatePlayPauseButton(isPlaying: Boolean) {
        binding.playPauseButton.text = if (isPlaying) {
            getString(R.string.pause)
        } else {
            getString(R.string.play)
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

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
