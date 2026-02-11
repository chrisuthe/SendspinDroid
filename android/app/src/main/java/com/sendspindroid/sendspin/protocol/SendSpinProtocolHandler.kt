package com.sendspindroid.sendspin.protocol

import android.util.Log
import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.protocol.message.BinaryMessageParser
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import com.sendspindroid.sendspin.protocol.message.MessageParser
import com.sendspindroid.sendspin.protocol.timesync.TimeSyncManager
import kotlinx.coroutines.CoroutineScope
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * Abstract base class for SendSpin protocol handling.
 *
 * Contains shared protocol logic for both SendSpinClient and SendSpinServer:
 * - Message building and sending
 * - Message parsing and dispatching
 * - Time synchronization
 * - Binary message handling
 *
 * Subclasses implement transport-specific behavior (OkHttp vs Java-WebSocket)
 * and connection state management.
 *
 * @param tag Log tag for debugging
 */
abstract class SendSpinProtocolHandler(
    protected val tag: String
) {
    // Protocol state
    protected var handshakeComplete = false
    protected var currentVolume: Int = 100
    protected var currentMuted: Boolean = false
    protected var currentSyncState: String = "synchronized"  // "synchronized" or "error"

    // Time sync manager (lazy initialized by subclass)
    protected var timeSyncManager: TimeSyncManager? = null

    // ========== Abstract Transport Methods ==========

    /**
     * Send a text message over the WebSocket.
     */
    protected abstract fun sendTextMessage(text: String)

    /**
     * Get the coroutine scope for async operations.
     */
    protected abstract fun getCoroutineScope(): CoroutineScope

    /**
     * Get the time filter for this connection.
     */
    abstract fun getTimeFilter(): SendspinTimeFilter

    /**
     * Get the buffer capacity for this connection.
     */
    protected abstract fun getBufferCapacity(): Int

    /**
     * Get the client ID for this connection.
     */
    protected abstract fun getClientId(): String

    /**
     * Get the device name for this connection.
     */
    protected abstract fun getDeviceName(): String

    // ========== Abstract Event Callbacks ==========

    /**
     * Called when handshake completes with server.
     */
    protected abstract fun onHandshakeComplete(serverName: String, serverId: String)

    /**
     * Called when track metadata is updated.
     */
    protected abstract fun onMetadataUpdate(metadata: TrackMetadata)

    /**
     * Called when playback state changes.
     */
    protected abstract fun onPlaybackStateChanged(state: String)

    /**
     * Called when server sends a volume command.
     */
    protected abstract fun onVolumeCommand(volume: Int)

    /**
     * Called when server sends a mute command.
     */
    protected abstract fun onMuteCommand(muted: Boolean)

    /**
     * Called when group info is updated.
     */
    protected abstract fun onGroupUpdate(info: GroupInfo)

    /**
     * Called when audio stream starts.
     */
    protected abstract fun onStreamStart(config: StreamConfig)

    /**
     * Called when stream clear is requested.
     */
    protected abstract fun onStreamClear()

    /**
     * Called when stream ends (server terminates playback).
     */
    protected abstract fun onStreamEnd()

    /**
     * Called when audio chunk is received.
     */
    protected abstract fun onAudioChunk(timestampMicros: Long, audioData: ByteArray)

    /**
     * Called when artwork is received.
     */
    protected abstract fun onArtwork(channel: Int, payload: ByteArray)

    /**
     * Called when sync offset is received from GroupSync.
     */
    protected abstract fun onSyncOffsetApplied(offsetMs: Double, source: String)

    // ========== Protocol Message Sending ==========

    /**
     * Send client/hello message to start handshake.
     */
    protected fun sendClientHello() {
        val message = MessageBuilder.buildClientHello(
            clientId = getClientId(),
            deviceName = getDeviceName(),
            bufferCapacity = getBufferCapacity()
        )
        sendMessage(message)
        // Log full message for debugging protocol issues (helps identify field name mismatches)
        Log.d(tag, "Sent client/hello: ${MessageBuilder.serialize(message).take(500)}")
    }

    /**
     * Send client/time message for clock synchronization.
     */
    protected fun sendClientTime() {
        val clientTransmitted = System.nanoTime() / 1000 // Convert to microseconds
        val message = MessageBuilder.buildClientTime(clientTransmitted)
        sendMessage(message)
    }

    /**
     * Send goodbye message before disconnecting.
     */
    protected fun sendGoodbye(reason: String) {
        if (!handshakeComplete) return
        val message = MessageBuilder.buildGoodbye(reason)
        sendMessage(message)
    }

    /**
     * Send player state update (volume/muted/sync state).
     */
    protected fun sendPlayerStateUpdate() {
        val message = MessageBuilder.buildPlayerState(currentVolume, currentMuted, currentSyncState)
        sendMessage(message)
    }

    /**
     * Set sync state and notify server.
     *
     * Per spec: report "synchronized" when locked to server timeline,
     * report "error" when unable to maintain sync (buffer underrun, clock issues).
     *
     * @param syncState Either "synchronized" or "error"
     */
    fun setSyncState(syncState: String) {
        if (syncState != "synchronized" && syncState != "error") {
            Log.w(tag, "Invalid sync state: $syncState (must be 'synchronized' or 'error')")
            return
        }
        if (currentSyncState != syncState) {
            currentSyncState = syncState
            Log.d(tag, "Sync state changed to: $syncState")
            if (handshakeComplete) {
                sendPlayerStateUpdate()
            }
        }
    }

    /**
     * Send a media command (play, pause, next, previous, switch).
     */
    fun sendCommand(command: String) {
        val message = MessageBuilder.buildCommand(command)
        sendMessage(message)
    }

    /**
     * Send a JSON message, handling slash escaping.
     */
    protected fun sendMessage(message: JSONObject) {
        val text = MessageBuilder.serialize(message)
        sendTextMessage(text)
        // Only log non-time-sync messages to avoid flooding logcat
        val type = message.optString("type")
        if (type != SendSpinProtocol.MessageType.CLIENT_TIME) {
            Log.d(tag, "sendMessage: type=$type, length=${text.length}")
        }
    }

    // ========== Player State Methods ==========

    /**
     * Set volume and notify server.
     *
     * @param volume Volume level from 0.0 to 1.0
     */
    fun setVolume(volume: Double) {
        val volumePercent = (volume * 100).toInt().coerceIn(0, 100)
        currentVolume = volumePercent
        Log.d(tag, "setVolume: $volumePercent%")
        sendPlayerStateUpdate()
    }

    /**
     * Set muted state and notify server.
     */
    fun setMuted(muted: Boolean) {
        currentMuted = muted
        Log.d(tag, "setMuted: $muted")
        sendPlayerStateUpdate()
    }

    /**
     * Set initial volume before handshake.
     *
     * @param volume Volume level from 0 to 100
     * @param muted Whether audio is muted
     */
    fun setInitialVolume(volume: Int, muted: Boolean = false) {
        currentVolume = volume.coerceIn(0, 100)
        currentMuted = muted
        Log.d(tag, "Initial volume set: $currentVolume, muted=$currentMuted")
    }

    // ========== Time Sync ==========

    /**
     * Start time synchronization.
     */
    protected fun startTimeSync() {
        val manager = timeSyncManager
        if (manager != null && !manager.isRunning) {
            manager.start(getCoroutineScope())
        }
    }

    /**
     * Stop time synchronization.
     */
    protected fun stopTimeSync() {
        timeSyncManager?.stop()
    }

    /**
     * Initialize time sync manager.
     */
    protected fun initTimeSyncManager(timeFilter: SendspinTimeFilter) {
        timeSyncManager = TimeSyncManager(
            timeFilter = timeFilter,
            sendClientTime = { sendClientTime() },
            tag = tag
        )
    }

    // ========== Message Handling ==========

    /**
     * Handle incoming text (JSON) message.
     * Dispatches to appropriate handler based on message type.
     */
    protected fun handleTextMessage(text: String) {
        // Trace all incoming messages for debugging (truncate for large payloads)
        Log.d(tag, "Received: ${text.take(500)}")

        try {
            val json = JSONObject(text)
            val type = json.getString("type")
            val payload = json.optJSONObject("payload")

            when (type) {
                SendSpinProtocol.MessageType.SERVER_HELLO -> handleServerHello(payload)
                SendSpinProtocol.MessageType.SERVER_TIME -> handleServerTime(payload)
                SendSpinProtocol.MessageType.SERVER_STATE -> handleServerState(payload)
                SendSpinProtocol.MessageType.SERVER_COMMAND -> handleServerCommand(payload)
                SendSpinProtocol.MessageType.GROUP_UPDATE -> handleGroupUpdate(payload)
                SendSpinProtocol.MessageType.STREAM_START -> handleStreamStart(payload)
                SendSpinProtocol.MessageType.STREAM_END -> handleStreamEnd()
                SendSpinProtocol.MessageType.STREAM_CLEAR -> handleStreamClear()
                SendSpinProtocol.MessageType.CLIENT_SYNC_OFFSET -> handleClientSyncOffset(payload)
                else -> Log.d(tag, "Unhandled message type: $type")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse message: ${text.take(100)}", e)
        }
    }

    /**
     * Handle server/hello - handshake response.
     */
    protected open fun handleServerHello(payload: JSONObject?) {
        val result = MessageParser.parseServerHello(payload, "Unknown")
        if (result == null) {
            Log.e(tag, "Failed to parse server/hello")
            return
        }

        Log.i(tag, "server/hello: name=${result.serverName}, id=${result.serverId}, reason=${result.connectionReason}")
        Log.d(tag, "Active roles: ${result.activeRoles}")

        handshakeComplete = true
        onHandshakeComplete(result.serverName, result.serverId)

        // Start time sync and send initial player state
        sendPlayerStateUpdate()
        startTimeSync()
    }

    /**
     * Handle server/time - clock sync response.
     */
    protected fun handleServerTime(payload: JSONObject?) {
        val clientReceived = System.nanoTime() / 1000
        val measurement = MessageParser.parseServerTime(payload, clientReceived)

        if (measurement != null) {
            timeSyncManager?.onServerTime(measurement)
        }
    }

    /**
     * Handle server/state - metadata and playback state.
     */
    protected fun handleServerState(payload: JSONObject?) {
        val (metadata, state) = MessageParser.parseServerState(payload)

        if (metadata != null) {
            onMetadataUpdate(metadata)
        }

        if (state != null) {
            onPlaybackStateChanged(state)
        }
    }

    /**
     * Handle server/command - player control commands.
     */
    protected fun handleServerCommand(payload: JSONObject?) {
        when (val result = MessageParser.parseServerCommand(payload)) {
            is ServerCommandResult.Volume -> {
                Log.d(tag, "Server command: set volume to ${result.volume}%")
                currentVolume = result.volume
                onVolumeCommand(result.volume)
                sendPlayerStateUpdate()
            }
            is ServerCommandResult.Mute -> {
                Log.d(tag, "Server command: set mute to ${result.muted}")
                currentMuted = result.muted
                onMuteCommand(result.muted)
                sendPlayerStateUpdate()
            }
            is ServerCommandResult.Unknown -> {
                Log.d(tag, "Unknown player command: ${result.command}")
            }
            null -> { /* No player command in payload */ }
        }
    }

    /**
     * Handle group/update - group state changes.
     */
    protected fun handleGroupUpdate(payload: JSONObject?) {
        val info = MessageParser.parseGroupUpdate(payload)
        if (info != null) {
            Log.v(tag, "group/update: id=${info.groupId}, name=${info.groupName}, state=${info.playbackState}")
            onGroupUpdate(info)
        }
    }

    /**
     * Handle stream/start - audio stream configuration.
     */
    protected fun handleStreamStart(payload: JSONObject?) {
        val config = MessageParser.parseStreamStart(payload)
        if (config != null) {
            Log.i(tag, "Stream started: codec=${config.codec}, rate=${config.sampleRate}, ch=${config.channels}, bits=${config.bitDepth}, header=${config.codecHeader?.size ?: 0} bytes")
            onStreamStart(config)
        }
    }

    /**
     * Handle stream/clear - flush audio buffers.
     */
    protected fun handleStreamClear() {
        Log.v(tag, "Stream clear - flushing audio buffers")
        onStreamClear()
    }

    /**
     * Handle stream/end - server terminates playback.
     */
    protected fun handleStreamEnd() {
        Log.i(tag, "Stream end - server terminated playback")
        onStreamEnd()
    }

    /**
     * Handle client/sync_offset - GroupSync calibration offset.
     */
    protected fun handleClientSyncOffset(payload: JSONObject?) {
        val result = MessageParser.parseSyncOffset(payload)
        if (result == null) {
            Log.w(tag, "client/sync_offset: missing or invalid payload")
            return
        }

        Log.i(tag, "client/sync_offset: offset=${result.offsetMs}ms from ${result.source}")

        // Clamp offset to reasonable range (-5000 to +5000 ms)
        val clampedOffset = result.offsetMs.coerceIn(-5000.0, 5000.0)
        if (clampedOffset != result.offsetMs) {
            Log.w(tag, "client/sync_offset: clamped from ${result.offsetMs}ms to ${clampedOffset}ms")
        }

        // Apply to time filter
        getTimeFilter().staticDelayMs = clampedOffset
        Log.d(tag, "client/sync_offset: static delay set to ${clampedOffset}ms")

        onSyncOffsetApplied(clampedOffset, result.source)
    }

    // ========== Binary Message Handling ==========

    /**
     * Handle binary message from OkHttp ByteString (SendSpinClient).
     */
    protected fun handleBinaryMessage(bytes: ByteString) {
        val message = BinaryMessageParser.parse(bytes)
        if (message != null) {
            dispatchBinaryMessage(message)
        }
    }

    /**
     * Handle binary message from Java-WebSocket ByteBuffer (SendSpinServer).
     */
    protected fun handleBinaryMessage(bytes: ByteBuffer) {
        val message = BinaryMessageParser.parse(bytes)
        if (message != null) {
            dispatchBinaryMessage(message)
        }
    }

    /**
     * Dispatch parsed binary message to appropriate handler.
     */
    private fun dispatchBinaryMessage(message: BinaryMessageParser.BinaryMessage) {
        when (message) {
            is BinaryMessageParser.BinaryMessage.Audio -> {
                onAudioChunk(message.timestampMicros, message.payload)
            }
            is BinaryMessageParser.BinaryMessage.Artwork -> {
                Log.v(tag, "Received artwork channel ${message.channel}: ${message.payload.size} bytes")
                onArtwork(message.channel, message.payload)
            }
            is BinaryMessageParser.BinaryMessage.Visualizer -> {
                // Visualization data - currently not used, no logging needed
            }
            is BinaryMessageParser.BinaryMessage.Unknown -> {
                Log.v(tag, "Unknown binary message type: ${message.type}")
            }
        }
    }
}
