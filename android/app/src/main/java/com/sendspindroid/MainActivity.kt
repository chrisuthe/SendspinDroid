package com.sendspindroid

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.slider.Slider
import com.sendspindroid.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var serverAdapter: ServerAdapter
    private val servers = mutableListOf<ServerInfo>()

    // Player instance (gomobile-generated)
    private var audioPlayer: player.Player_? = null

    // AudioTrack fields
    private var audioTrack: AudioTrack? = null
    private var audioPlaybackJob: Job? = null

    // Multicast lock for mDNS discovery
    private var multicastLock: WifiManager.MulticastLock? = null

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        initializePlayer()
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

    private fun initializePlayer() {
        try {
            val callback = object : player.PlayerCallback {
                override fun onServerDiscovered(name: String, address: String) {
                    runOnUiThread {
                        Log.d(TAG, "Server discovered: $name at $address")
                        addServer(ServerInfo(name, address))
                    }
                }

                override fun onConnected(serverName: String) {
                    runOnUiThread {
                        Log.d(TAG, "Connected to: $serverName")
                        updateStatus("Connected to $serverName")
                        enablePlaybackControls(true)
                        // Setup audio playback when connected
                        setupAudioPlayback()
                    }
                }

                override fun onDisconnected() {
                    runOnUiThread {
                        Log.d(TAG, "Disconnected from server")
                        updateStatus("Disconnected")
                        enablePlaybackControls(false)
                    }
                }

                override fun onStateChanged(state: String) {
                    runOnUiThread {
                        Log.d(TAG, "State changed: $state")
                        updatePlaybackState(state)
                    }
                }

                override fun onMetadata(title: String, artist: String, album: String) {
                    runOnUiThread {
                        Log.d(TAG, "Metadata: $title by $artist from $album")
                        updateMetadata(title, artist, album)
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        Log.e(TAG, "Player error: $message")
                        showError(message)
                    }
                }
            }

            audioPlayer = player.Player.newPlayer("Android Player", callback)
            Log.d(TAG, "Player initialized")

            // Add hardcoded test server for debugging
            addServer(ServerInfo("Test Server (10.0.2.8)", "10.0.2.8:8927"))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize player", e)
            showError("Failed to initialize player: ${e.message}")
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

    private fun validateServerAddress(address: String): Boolean {
        if (address.isEmpty()) return false

        // Check for host:port format
        val parts = address.split(":")
        if (parts.size != 2) return false

        val host = parts[0]
        val portStr = parts[1]

        // Validate host is not empty
        if (host.isEmpty()) return false

        // Validate port is a valid number
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

            audioPlayer?.startDiscovery()
            Toast.makeText(this, "Discovering servers...", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Discovery started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            showError("Failed to start discovery: ${e.message}")
        }
    }

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

        try {
            audioPlayer?.connect(server.address)
            Toast.makeText(this, "Connecting to ${server.name}...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            showError("Failed to connect: ${e.message}")
        }
    }

    private fun setupAudioPlayback() {
        try {
            // Audio format parameters (hardcoded for now, will be from Go player later)
            val sampleRate = 48000
            val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            // Calculate buffer size
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize == AudioTrack.ERROR_BAD_VALUE || minBufferSize == AudioTrack.ERROR) {
                Log.e(TAG, "Invalid buffer size")
                showError("Failed to setup audio playback")
                return
            }

            val bufferSize = minBufferSize * 4

            // Build AudioTrack
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(audioFormat)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // Start playback
            audioTrack?.play()

            // Launch coroutine to read and write audio data
            audioPlaybackJob = lifecycleScope.launch(Dispatchers.IO) {
                val buffer = ByteArray(8192) // Larger buffer for audio chunks

                while (isActive) {
                    try {
                        // Read audio data from Go player (blocking call with 100ms timeout)
                        val bytesRead = audioPlayer?.readAudioData(buffer)?.toInt() ?: 0

                        if (bytesRead > 0) {
                            // Write audio data to AudioTrack
                            val written = audioTrack?.write(buffer, 0, bytesRead) ?: 0
                            if (written < 0) {
                                Log.e(TAG, "AudioTrack write error: $written")
                            }
                        }
                        // No need for delay - readAudioData() blocks until data is available or timeout
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading/writing audio data", e)
                        delay(100)
                    }
                }
            }

            Log.d(TAG, "Audio playback setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup audio playback", e)
            showError("Failed to setup audio playback: ${e.message}")
        }
    }

    private fun stopAudioPlayback() {
        try {
            // Cancel the playback job
            audioPlaybackJob?.cancel()
            audioPlaybackJob = null

            // Stop and release AudioTrack
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    stop()
                }
                release()
            }
            audioTrack = null

            Log.d(TAG, "Audio playback stopped and released")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio playback", e)
        }
    }

    private fun onPlayClicked() {
        Log.d(TAG, "Play clicked")
        audioPlayer?.play()

        // Resume AudioTrack if it's paused
        audioTrack?.apply {
            if (playState == AudioTrack.PLAYSTATE_PAUSED) {
                play()
            }
        }

        updatePlaybackState("playing")
    }

    private fun onPauseClicked() {
        Log.d(TAG, "Pause clicked")
        audioPlayer?.pause()

        // Pause AudioTrack
        audioTrack?.apply {
            if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                pause()
            }
        }

        updatePlaybackState("paused")
    }

    private fun onStopClicked() {
        Log.d(TAG, "Stop clicked")
        audioPlayer?.stop()

        // Stop audio playback
        stopAudioPlayback()

        updatePlaybackState("stopped")
    }

    private fun onVolumeChanged(volume: Float) {
        Log.d(TAG, "Volume changed: $volume")
        audioPlayer?.setVolume(volume.toDouble())
    }

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

    override fun onDestroy() {
        super.onDestroy()
        // Stop audio playback and cleanup
        stopAudioPlayback()
        releaseMulticastLock()
        audioPlayer?.cleanup()
    }
}
