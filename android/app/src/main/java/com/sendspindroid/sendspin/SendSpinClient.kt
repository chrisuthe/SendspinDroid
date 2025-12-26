package com.sendspindroid.sendspin

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Native Kotlin SendSpin client.
 *
 * Replaces the Go-based player with a pure Kotlin implementation
 * for WebSocket communication with SendSpin servers.
 *
 * Protocol reference: Python CLI player (sendspin/audio.py)
 *
 * ## Architecture
 * - WebSocket connection to SendSpin server
 * - Binary protocol for audio data with timestamps
 * - JSON protocol for control messages
 * - Clock synchronization for audio timing
 *
 * ## TODO: Implementation phases
 * 1. WebSocket connection and basic protocol parsing
 * 2. Clock synchronization (Kalman filter)
 * 3. Audio buffering with timestamps
 * 4. AAudio/Oboe playback with sync correction
 */
class SendSpinClient(
    private val deviceName: String,
    private val callback: Callback
) {
    companion object {
        private const val TAG = "SendSpinClient"
    }

    /**
     * Callback interface for SendSpin events.
     * Mirrors the Go PlayerCallback interface.
     */
    interface Callback {
        fun onServerDiscovered(name: String, address: String)
        fun onConnected(serverName: String)
        fun onDisconnected()
        fun onStateChanged(state: String)
        fun onGroupUpdate(groupId: String, groupName: String, playbackState: String)
        fun onMetadataUpdate(
            title: String,
            artist: String,
            album: String,
            artworkUrl: String,
            durationMs: Long,
            positionMs: Long
        )
        fun onArtwork(imageData: ByteArray)
        fun onError(message: String)
    }

    // Connection state
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val serverName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for WebSocket
        .build()

    private var webSocket: WebSocket? = null
    private var serverAddress: String? = null

    val isConnected: Boolean
        get() = _connectionState.value is ConnectionState.Connected

    /**
     * Connect to a SendSpin server.
     *
     * @param address Server address in "host:port" format
     */
    fun connect(address: String) {
        if (isConnected) {
            Log.w(TAG, "Already connected, disconnecting first")
            disconnect()
        }

        Log.d(TAG, "Connecting to: $address")
        _connectionState.value = ConnectionState.Connecting
        serverAddress = address

        val request = Request.Builder()
            .url("ws://$address/ws")
            .build()

        webSocket = okHttpClient.newWebSocket(request, WebSocketEventListener())
    }

    /**
     * Disconnect from the current server.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting")
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
        callback.onDisconnected()
    }

    /**
     * Start audio playback.
     */
    fun play() {
        sendCommand("play")
    }

    /**
     * Pause audio playback.
     */
    fun pause() {
        sendCommand("pause")
    }

    /**
     * Skip to next track.
     */
    fun next() {
        sendCommand("next")
    }

    /**
     * Go to previous track.
     */
    fun previous() {
        sendCommand("previous")
    }

    /**
     * Set playback volume.
     *
     * @param volume Volume level from 0.0 to 1.0
     */
    fun setVolume(volume: Double) {
        // TODO: Implement volume control
        Log.d(TAG, "setVolume: $volume (not yet implemented)")
    }

    /**
     * Send a command to the server.
     */
    fun sendCommand(command: String) {
        Log.d(TAG, "Sending command: $command")
        // TODO: Implement command protocol
        // Format: JSON message with command type
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        disconnect()
        scope.launch {
            // Cancel any pending operations
        }
    }

    /**
     * WebSocket event listener.
     */
    private inner class WebSocketEventListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected")
            val serverName = serverAddress ?: "Unknown"
            _connectionState.value = ConnectionState.Connected(serverName)
            callback.onConnected(serverName)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Text message: ${text.take(100)}")
            // TODO: Parse JSON messages (metadata, state updates)
            handleTextMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d(TAG, "Binary message: ${bytes.size} bytes")
            // TODO: Parse binary protocol (audio data, artwork)
            handleBinaryMessage(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            _connectionState.value = ConnectionState.Disconnected
            callback.onDisconnected()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
            callback.onError(t.message ?: "Connection failed")
        }
    }

    /**
     * Handle text (JSON) messages from server.
     *
     * Message types (from Python CLI):
     * - server/state: Server state and metadata
     * - group/update: Group playback state
     * - stream/start: Audio stream starting
     * - stream/stop: Audio stream stopping
     */
    private fun handleTextMessage(text: String) {
        // TODO: Parse JSON and dispatch to appropriate handlers
        // Reference: Python CLI's _handle_server_state, _handle_group_update
    }

    /**
     * Handle binary messages from server.
     *
     * Binary protocol (from Python CLI):
     * - First byte: message type (0-7 = audio slots, 8-11 = artwork)
     * - Bytes 1-8: timestamp (int64, microseconds)
     * - Remaining: payload (audio PCM or image data)
     */
    private fun handleBinaryMessage(bytes: ByteString) {
        if (bytes.size < 9) {
            Log.w(TAG, "Binary message too short: ${bytes.size} bytes")
            return
        }

        val msgType = bytes[0].toInt() and 0xFF
        // TODO: Extract timestamp and payload
        // TODO: Route audio to buffer, artwork to callback
    }
}
