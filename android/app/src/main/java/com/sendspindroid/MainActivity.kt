package com.sendspindroid

import android.content.ComponentName
import android.content.Context
import android.net.wifi.WifiManager
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
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.sendspindroid.databinding.ActivityMainBinding
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

    // Discovery player (gomobile-generated) - JNI bridge to Go code
    // Used ONLY for mDNS server discovery, NOT for playback
    // Playback is handled by PlaybackService
    private var discoveryPlayer: player.Player_? = null

    // MediaController for communicating with PlaybackService
    // Provides playback control and state observation
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    // Multicast lock required for mDNS discovery to work on Android
    // Without this, multicast packets are filtered by the WiFi driver for battery optimization
    private var multicastLock: WifiManager.MulticastLock? = null

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        initializeDiscoveryPlayer()
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
        binding.playButton.setOnClickListener {
            onPlayClicked()
        }

        binding.pauseButton.setOnClickListener {
            onPauseClicked()
        }

        binding.stopButton.setOnClickListener {
            onStopClicked()
        }

        // Volume slider
        binding.volumeSlider.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                onVolumeChanged(value / 100f)
            }
        }
    }

    /**
     * Initializes the Go player for server discovery ONLY.
     *
     * This player handles mDNS server discovery. Playback is handled by
     * PlaybackService via MediaController.
     *
     * All callbacks must use runOnUiThread() since they're called from Go runtime threads.
     */
    private fun initializeDiscoveryPlayer() {
        try {
            // Callback only handles discovery - playback events come via MediaController
            val callback = object : player.PlayerCallback {
                override fun onServerDiscovered(name: String, address: String) {
                    runOnUiThread {
                        Log.d(TAG, "Server discovered: $name at $address")
                        addServer(ServerInfo(name, address))
                    }
                }

                // These callbacks are ignored - PlaybackService handles playback
                override fun onConnected(serverName: String) {
                    Log.d(TAG, "Discovery player connected (ignored): $serverName")
                }

                override fun onDisconnected() {
                    Log.d(TAG, "Discovery player disconnected (ignored)")
                }

                override fun onStateChanged(state: String) {
                    Log.d(TAG, "Discovery player state (ignored): $state")
                }

                override fun onMetadata(title: String, artist: String, album: String) {
                    Log.d(TAG, "Discovery player metadata (ignored)")
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        Log.e(TAG, "Discovery player error: $message")
                        // Only show discovery-related errors
                        if (message.contains("discovery", ignoreCase = true)) {
                            showError(message)
                        }
                    }
                }
            }

            discoveryPlayer = player.Player.newPlayer("SendSpinDroid Discovery", callback)
            Log.d(TAG, "Discovery player initialized")

            // Add hardcoded test server for debugging
            // TODO: Remove or make conditional on BuildConfig.DEBUG for production
            addServer(ServerInfo("Test Server (10.0.2.8)", "10.0.2.8:8927"))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize discovery player", e)
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
     * Listener for MediaController connection events.
     */
    private inner class MediaControllerListener : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            Log.d(TAG, "MediaController disconnected from service")
            runOnUiThread {
                updateStatus("Service disconnected")
                enablePlaybackControls(false)
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
                }
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
                        enablePlaybackControls(true)
                    }
                    else -> {
                        updateStatus("Ready")
                        enablePlaybackControls(false)
                    }
                }

                // Sync metadata
                val metadata = controller.mediaMetadata
                updateMetadata(
                    metadata.title?.toString() ?: "",
                    metadata.artist?.toString() ?: "",
                    metadata.albumTitle?.toString() ?: ""
                )
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
        binding.statusText.text = getString(R.string.discovering)

        try {
            // Acquire multicast lock for mDNS
            acquireMulticastLock()

            discoveryPlayer?.startDiscovery()
            Toast.makeText(this, "Discovering servers...", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Discovery started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            showError("Failed to start discovery: ${e.message}")
        }
    }

    /**
     * Acquires a multicast lock for mDNS discovery.
     *
     * Why this is needed: Android filters multicast packets by default to save battery.
     * mDNS (Multicast DNS) requires receiving multicast packets on 224.0.0.251.
     * This lock tells the WiFi driver to allow multicast packets through.
     *
     * Best practice: setReferenceCounted(true) allows multiple acquires without leak
     * Security note: Requires CHANGE_WIFI_MULTICAST_STATE permission (declared in manifest)
     */
    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("SendSpinDroid_mDNS").apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "Multicast lock acquired for mDNS discovery")
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Multicast lock released")
            }
            multicastLock = null
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
     * Handles play button click.
     * Sends play command to PlaybackService via MediaController.
     */
    private fun onPlayClicked() {
        Log.d(TAG, "Play clicked")
        mediaController?.play()
    }

    /**
     * Handles pause button click.
     * Sends pause command to PlaybackService via MediaController.
     */
    private fun onPauseClicked() {
        Log.d(TAG, "Pause clicked")
        mediaController?.pause()
    }

    /**
     * Handles stop button click.
     * Sends DISCONNECT command to PlaybackService to stop playback.
     */
    private fun onStopClicked() {
        Log.d(TAG, "Stop clicked")

        val controller = mediaController ?: return

        // Send DISCONNECT command to stop playback and disconnect from server
        val command = SessionCommand(PlaybackService.COMMAND_DISCONNECT, Bundle.EMPTY)
        controller.sendCustomCommand(command, Bundle.EMPTY)

        updatePlaybackState("stopped")
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
        binding.playButton.isEnabled = enabled
        binding.pauseButton.isEnabled = enabled
        binding.stopButton.isEnabled = enabled
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

        // Release WiFi multicast lock to save battery
        releaseMulticastLock()

        // Cleanup discovery Go player (service manages playback player)
        discoveryPlayer?.cleanup()
        discoveryPlayer = null
    }
}
