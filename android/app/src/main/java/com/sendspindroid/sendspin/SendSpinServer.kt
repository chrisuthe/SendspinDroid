package com.sendspindroid.sendspin

import android.util.Log
import com.sendspindroid.sendspin.protocol.GroupInfo
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.SendSpinProtocolHandler
import com.sendspindroid.sendspin.protocol.StreamConfig
import com.sendspindroid.sendspin.protocol.TrackMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket server for server-initiated connections (Stay Connected mode).
 *
 * In this mode, SendSpin servers discover the client via mDNS (_sendspin._tcp)
 * and connect to initiate playback remotely.
 *
 * Protocol flow (server-initiated):
 * 1. Server connects to client's WebSocket server
 * 2. Client sends client/hello (client initiates handshake in server-initiated mode)
 * 3. Server responds with server/hello
 * 4. Protocol proceeds as normal (same as client-initiated)
 *
 * This server can handle multiple simultaneous server connections.
 * Each connection gets its own ServerConnectionHandler for protocol management.
 */
class SendSpinServer(
    private val deviceName: String,
    private val port: Int = DEFAULT_PORT,
    private val callback: Callback
) {
    companion object {
        private const val TAG = "SendSpinServer"
        const val DEFAULT_PORT = 8928
    }

    /**
     * Callback interface for SendSpinServer events.
     */
    interface Callback {
        fun onServerConnected(serverName: String, serverAddress: String)
        fun onServerDisconnected(serverAddress: String)
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
        fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?)
        fun onStreamClear()
        fun onAudioChunk(serverTimeMicros: Long, pcmData: ByteArray)
        fun onVolumeChanged(volume: Int)
        fun onMutedChanged(muted: Boolean)
        fun onSyncOffsetApplied(offsetMs: Double, source: String)
        fun onNetworkChanged()
        fun onListeningStarted(port: Int)
        fun onListeningStopped()
    }

    // Server state
    sealed class ListeningState {
        object Stopped : ListeningState()
        data class Listening(val port: Int) : ListeningState()
        data class Error(val message: String) : ListeningState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocketServer: InnerWebSocketServer? = null

    private val _listeningState = MutableStateFlow<ListeningState>(ListeningState.Stopped)
    val listeningState: StateFlow<ListeningState> = _listeningState.asStateFlow()

    // Connected servers (address -> handler)
    private val connectionHandlers = ConcurrentHashMap<String, ServerConnectionHandler>()

    val isListening: Boolean
        get() = _listeningState.value is ListeningState.Listening

    /**
     * Gets the time filter for the active connection (first connected server).
     */
    fun getTimeFilter(): SendspinTimeFilter? {
        return connectionHandlers.values.firstOrNull { it.isHandshakeComplete }?.getTimeFilter()
    }

    /**
     * Gets the name of the first connected server.
     */
    fun getServerName(): String? {
        return connectionHandlers.values.firstOrNull { it.isHandshakeComplete }?.serverName
    }

    /**
     * Gets the address of the first connected server.
     */
    fun getServerAddress(): String? {
        return connectionHandlers.values.firstOrNull { it.isHandshakeComplete }?.address
    }

    /**
     * Checks if any server is connected.
     */
    val isConnected: Boolean
        get() = connectionHandlers.values.any { it.isHandshakeComplete }

    /**
     * Start listening for incoming server connections.
     */
    fun startListening() {
        if (isListening) {
            Log.w(TAG, "Already listening")
            return
        }

        Log.d(TAG, "Starting WebSocket server on port $port")

        try {
            webSocketServer = InnerWebSocketServer(InetSocketAddress(port))
            webSocketServer?.isReuseAddr = true
            webSocketServer?.start()
            _listeningState.value = ListeningState.Listening(port)
            callback.onListeningStarted(port)
            Log.i(TAG, "WebSocket server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket server", e)
            _listeningState.value = ListeningState.Error("Failed to start: ${e.message}")
            callback.onError("Failed to start listening: ${e.message}")
        }
    }

    /**
     * Stop listening and disconnect all servers.
     */
    fun stopListening() {
        Log.d(TAG, "Stopping WebSocket server")

        // Stop all connection handlers
        connectionHandlers.values.forEach { handler ->
            try {
                handler.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping connection handler", e)
            }
        }
        connectionHandlers.clear()

        // Stop server
        try {
            webSocketServer?.stop(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket server", e)
        }
        webSocketServer = null

        _listeningState.value = ListeningState.Stopped
        callback.onListeningStopped()
        Log.i(TAG, "WebSocket server stopped")
    }

    /**
     * Disconnect from a specific server.
     */
    fun disconnectServer(address: String) {
        val handler = connectionHandlers[address] ?: return
        handler.stop()
        connectionHandlers.remove(address)
        callback.onServerDisconnected(address)
    }

    /**
     * Send a media command to the active server.
     */
    fun sendCommand(command: String) {
        connectionHandlers.values.firstOrNull { it.isHandshakeComplete }?.sendCommand(command)
    }

    fun play() = sendCommand("play")
    fun pause() = sendCommand("pause")
    fun next() = sendCommand("next")
    fun previous() = sendCommand("previous")
    fun switchGroup() = sendCommand("switch")

    /**
     * Set volume and send to server.
     */
    fun setVolume(volume: Double) {
        connectionHandlers.values.firstOrNull { it.isHandshakeComplete }?.setVolume(volume)
    }

    /**
     * Set muted state and send to server.
     */
    fun setMuted(muted: Boolean) {
        connectionHandlers.values.firstOrNull { it.isHandshakeComplete }?.setMuted(muted)
    }

    /**
     * Set initial volume for a connection.
     */
    fun setInitialVolume(volume: Int, muted: Boolean = false) {
        Log.d(TAG, "Initial volume set: $volume, muted=$muted")
    }

    /**
     * Called when network changes.
     */
    fun onNetworkChanged() {
        connectionHandlers.values.forEach { handler ->
            if (handler.isHandshakeComplete) {
                Log.i(TAG, "Network changed - resetting time filter for ${handler.address}")
                handler.getTimeFilter().reset()
            }
        }
        callback.onNetworkChanged()
    }

    /**
     * Cleanup resources.
     */
    fun destroy() {
        stopListening()
    }

    // ========== Inner WebSocket Server ==========

    private inner class InnerWebSocketServer(address: InetSocketAddress) : WebSocketServer(address) {

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val address = conn.remoteSocketAddress?.toString() ?: "unknown"
            Log.d(TAG, "Server connected from: $address")

            // Create connection handler
            val handler = ServerConnectionHandler(conn, address)
            connectionHandlers[address] = handler

            // In server-initiated mode, client sends hello first
            handler.sendInitialHello()
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            val address = conn.remoteSocketAddress?.toString() ?: "unknown"
            Log.d(TAG, "Server disconnected: $address (code=$code, reason=$reason)")

            val handler = connectionHandlers.remove(address)
            handler?.stop()

            callback.onServerDisconnected(address)
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val address = conn.remoteSocketAddress?.toString() ?: return
            val handler = connectionHandlers[address] ?: return
            handler.onTextMessage(message)
        }

        override fun onMessage(conn: WebSocket, bytes: ByteBuffer) {
            val address = conn.remoteSocketAddress?.toString() ?: return
            val handler = connectionHandlers[address] ?: return
            handler.onBinaryMessage(bytes)
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.e(TAG, "WebSocket error", ex)
            callback.onError("WebSocket error: ${ex.message}")
        }

        override fun onStart() {
            Log.d(TAG, "WebSocket server started")
        }
    }

    // ========== Per-Connection Protocol Handler ==========

    /**
     * Handles protocol for a single server connection.
     * Extends SendSpinProtocolHandler for shared protocol logic.
     */
    private inner class ServerConnectionHandler(
        private val webSocket: WebSocket,
        val address: String
    ) : SendSpinProtocolHandler("$TAG:$address") {

        private val clientId = UUID.randomUUID().toString()
        private val timeFilter = SendspinTimeFilter()

        var serverName: String? = null
            private set

        val isHandshakeComplete: Boolean
            get() = handshakeComplete

        init {
            initTimeSyncManager(timeFilter)
        }

        // ========== SendSpinProtocolHandler Implementation ==========

        override fun sendTextMessage(text: String) {
            try {
                webSocket.send(text)
            } catch (e: Exception) {
                Log.e(tag, "Failed to send message", e)
            }
        }

        override fun getCoroutineScope(): CoroutineScope = scope

        override fun getTimeFilter(): SendspinTimeFilter = timeFilter

        override fun getBufferCapacity(): Int = SendSpinClient.getBufferCapacity()

        override fun getClientId(): String = clientId

        override fun getDeviceName(): String = deviceName

        override fun onHandshakeComplete(serverName: String, serverId: String) {
            this.serverName = serverName
            callback.onServerConnected(serverName, address)
        }

        override fun onMetadataUpdate(metadata: TrackMetadata) {
            callback.onMetadataUpdate(
                metadata.title,
                metadata.artist,
                metadata.album,
                metadata.artworkUrl,
                metadata.durationMs,
                metadata.positionMs
            )
        }

        override fun onPlaybackStateChanged(state: String) {
            callback.onStateChanged(state)
        }

        override fun onVolumeCommand(volume: Int) {
            callback.onVolumeChanged(volume)
        }

        override fun onMuteCommand(muted: Boolean) {
            callback.onMutedChanged(muted)
        }

        override fun onGroupUpdate(info: GroupInfo) {
            callback.onGroupUpdate(info.groupId, info.groupName, info.playbackState)
        }

        override fun onStreamStart(config: StreamConfig) {
            Log.i(tag, "Stream started: codec=${config.codec}, rate=${config.sampleRate}")
            callback.onStreamStart(
                config.codec,
                config.sampleRate,
                config.channels,
                config.bitDepth,
                config.codecHeader
            )
        }

        override fun onStreamClear() {
            callback.onStreamClear()
        }

        override fun onAudioChunk(timestampMicros: Long, payload: ByteArray) {
            callback.onAudioChunk(timestampMicros, payload)
        }

        override fun onArtwork(channel: Int, payload: ByteArray) {
            Log.v(tag, "Received artwork channel $channel: ${payload.size} bytes")
            callback.onArtwork(payload)
        }

        override fun onSyncOffsetApplied(offsetMs: Double, source: String) {
            callback.onSyncOffsetApplied(offsetMs, source)
        }

        // ========== Handler Methods ==========

        fun sendInitialHello() {
            sendClientHello()
        }

        fun onTextMessage(text: String) {
            handleTextMessage(text)
        }

        fun onBinaryMessage(bytes: ByteBuffer) {
            handleBinaryMessage(bytes)
        }

        fun stop() {
            stopTimeSync()
            sendGoodbye("server_stopped")
            try {
                webSocket.close()
            } catch (e: Exception) {
                Log.e(tag, "Error closing WebSocket", e)
            }
        }
    }
}
