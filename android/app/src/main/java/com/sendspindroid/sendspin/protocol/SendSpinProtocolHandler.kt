package com.sendspindroid.sendspin.protocol

import android.util.Log
import com.sendspindroid.sendspin.AdaptiveBufferPolicy
import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.crypto.NoiseCrypto
import com.sendspindroid.sendspin.crypto.NoiseTransport
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.PairingConfigStore
import com.sendspindroid.sendspin.protocol.management.ManagementResultCode
import com.sendspindroid.sendspin.protocol.management.ManagementRequestParser
import com.sendspindroid.sendspin.protocol.management.ManagementService
import com.sendspindroid.sendspin.protocol.management.ManagementSessionContext
import com.sendspindroid.sendspin.protocol.message.BinaryMessageParser
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import com.sendspindroid.sendspin.protocol.message.MessageParser
import com.sendspindroid.sendspin.protocol.timesync.TimeSyncManager
import kotlinx.coroutines.CoroutineScope
import com.sendspindroid.sendspin.crypto.TrustStore
import com.sendspindroid.sendspin.pairing.PairAbortReason
import com.sendspindroid.sendspin.pairing.PairingAction
import com.sendspindroid.sendspin.pairing.PairingEvent
import com.sendspindroid.sendspin.pairing.PairingPskFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Abstract base class for SendSpin protocol handling.
 *
 * Contains shared protocol logic used by [SendSpin]:
 * - Message building and sending
 * - Message parsing and dispatching
 * - Time synchronization
 * - Binary message handling
 *
 * Subclasses implement transport-specific behavior and connection state
 * management.
 *
 * @param tag Log tag for debugging
 */
abstract class SendSpinProtocolHandler(
    protected val tag: String
) {
    // Protocol state
    @Volatile
    protected var handshakeComplete = false
    protected var currentVolume: Int = 100
    protected var currentMuted: Boolean = false
    // Per Sendspin spec, a client that has not yet synchronized to the
    // server timeline reports "error". Updated by [evaluateAndPublishSyncState].
    protected var currentSyncState: String = "error"

    private val syncStateLock = Any()
    private var hasEverConverged: Boolean = false
    private var lastPublishedMute: Boolean = false

    // True while the client's audio output is in use by an external system
    // (e.g. another app holds audio focus). Overrides synchronized/error
    // reporting until cleared. Per spec, the server reacts by parking this
    // client in a solo group and ending its streams.
    private var externalSourceActive: Boolean = false

    // Stream active tracking (mirrors CLI _stream_active)
    private var _streamActive = false
    private var _currentStreamConfig: StreamConfig? = null

    // Last received values for change detection (avoids unnecessary UI recomposition)
    private var lastMetadata: TrackMetadata? = null
    private var lastPlaybackState: String? = null
    private var lastGroupInfo: GroupInfo? = null

    // Merged controller (group-level) state from server/state deltas.
    private var currentControllerState: ControllerState? = null

    // Time sync manager (lazy initialized by subclass)
    protected var timeSyncManager: TimeSyncManager? = null

    // Adaptive jitter-buffer policy: reports a generous min_buffer_ms by default
    // and grows it on trouble (RTT spikes / sync loss), backing off slowly on a
    // sustained-good link. Constructed by [initTimeSyncManager] with the
    // memory-appropriate profile. Guarded by [adaptiveBufferLock] because the
    // time-sync callback can fire from either the burst-loop or the receive thread.
    private var adaptiveBuffer: AdaptiveBufferPolicy? = null
    private val adaptiveBufferLock = Any()
    private var lastReportedMinBufferMs: Int = SendSpinProtocol.PlayerTiming.MIN_BUFFER_MS

    // ========== Abstract Transport Methods ==========

    /**
     * Send a raw WebSocket TEXT frame.
     *
     * After the Noise handshake this is a protocol violation - "all messages
     * are sent as WebSocket binary frames carrying Noise transport ciphertexts".
     * Only the handshake driver and the legacy path may call it; everything else
     * goes through [sendProtocolMessage].
     */
    protected abstract fun sendTextMessage(text: String)

    /** Send a raw WebSocket BINARY frame. */
    protected abstract fun sendBinaryFrame(bytes: ByteArray)

    /**
     * Get the coroutine scope for async operations.
     */
    protected abstract fun getCoroutineScope(): CoroutineScope

    /**
     * Get the time filter for this connection.
     */
    abstract fun getTimeFilter(): SendspinTimeFilter

    /**
     * Whether the device is in low-memory mode (smaller buffer target).
     */
    protected abstract fun isLowMemoryMode(): Boolean

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

    /**
     * Called when the merged controller (group-level) state changes:
     * supported_commands, group volume/mute, repeat, shuffle.
     * Default no-op for handlers that don't surface controller state.
     */
    protected open fun onControllerStateUpdate(state: ControllerState) {}

    /**
     * Called when the audio output should be silenced or unsilenced because
     * the client cannot maintain sync. Per Sendspin spec, clients in the
     * "error" state must mute their audio output and continue buffering
     * until they can resume synchronized playback.
     *
     * Fires only on transitions, not on every re-evaluation. The argument
     * is the desired mute state.
     */
    protected abstract fun onSyncMuteChanged(muted: Boolean)

    // ========== Protocol Message Sending ==========

    /**
     * Get the manufacturer name for device identification.
     */
    protected abstract fun getManufacturer(): String

    /**
     * Get the supported audio formats for the client/hello handshake.
     */
    protected abstract fun getSupportedFormats(): List<MessageBuilder.FormatEntry>

    /**
     * Get the client app version reported in device_info.software_version.
     */
    protected abstract fun getSoftwareVersion(): String

    /**
     * Send client/hello message to start handshake.
     *
     * Buffer capacity is computed from the format list and target duration
     * so the wire-byte cap scales with the highest PCM bitrate we advertise.
     */
    protected fun sendClientHello() {
        val formats = getSupportedFormats()
        val bufferDuration = if (isLowMemoryMode()) {
            SendSpinProtocol.Buffer.DURATION_LOW_MEM_SEC
        } else {
            SendSpinProtocol.Buffer.DURATION_NORMAL_SEC
        }
        val bufferCapacity = MessageBuilder.calculateBufferCapacity(formats, bufferDuration)
        val text = MessageBuilder.buildClientHello(
            // Always null: client_id and version live in client/init, and every
            // session is encrypted, so repeating them here would be sending
            // fields the spec does not define for this message.
            clientId = null,
            deviceName = getDeviceName(),
            bufferCapacity = bufferCapacity,
            manufacturer = getManufacturer(),
            supportedFormats = formats,
            softwareVersion = getSoftwareVersion(),
            trustLevel = getTrustLevel(),
            unpairedAccessEnabled = isUnpairedAccessEnabled(),
            supportedPairMethods = getSupportedPairMethods(),
        )
        sendProtocolMessage(text)
        // Logged whole, not truncated. The pairing fields sit at the end of the
        // payload, and whether a server offers pairing at all is decided by
        // them - a 500-character cut hid exactly the thing worth checking when
        // Music Assistant reports "this player has nothing to pair". Nothing
        // here is secret: client_id is public, and the pair methods are names.
        Log.d(tag, "Sent client/hello: $text")
    }

    /**
     * Send client/time message for clock synchronization.
     */
    protected fun sendClientTime() {
        val clientTransmitted = System.nanoTime() / 1000 // Convert to microseconds
        sendProtocolMessage(MessageBuilder.buildClientTime(clientTransmitted))
    }

    /**
     * Send goodbye message before disconnecting.
     *
     * Note the [handshakeComplete] gate, which means "server/hello seen". A
     * goodbye is legitimate before that, as soon as the Noise handshake
     * finishes, so this swallows one silently. `server/unpair` sidesteps it by
     * sending its own goodbye - it has to sequence the send against the close
     * anyway - but item 2.9's `concurrent_attempt` will need this relaxed.
     */
    protected fun sendGoodbye(reason: GoodbyeReason) = sendGoodbye(reason.wire)

    protected fun sendGoodbye(reason: String) {
        if (!handshakeComplete) return
        sendProtocolMessage(MessageBuilder.buildGoodbye(reason))
    }

    /**
     * Whether this client can currently participate in playback.
     *
     * Two conditions, and they mean different things:
     *
     * - The time filter must have converged. "A player MUST NOT report
     *   `available: true` until its time filter has converged enough to begin
     *   scheduling playback." Reporting availability early invites the server to
     *   schedule audio against a clock estimate we do not trust yet.
     * - The output must not be held by an external system. That is the ONLY
     *   other meaning `available: false` carries since the spec replaced the old
     *   `state` string (#115) - it is not a way to signal a sync problem.
     *
     * Note the asymmetry with the old tri-state: there is no longer any way to
     * tell the server "I am here but unhealthy". A client that loses sync mid
     * stream stays `available: true` and mutes its own output; going
     * `available: false` would make the server move us to a solo group and
     * require an explicit `switch` to get back, which is much more disruptive
     * than a brief mute.
     */
    protected fun isAvailable(): Boolean {
        if (externalSourceActive) return false
        return getTimeFilter().isConverged
    }

    /**
     * `'user'` when a pairing record exists for this server, `'none'` otherwise.
     *
     * Phase 1 has no record store, so this is always `'none'`. Item 2.1 (#202)
     * gives it a real answer; 2.3 (#204) makes it follow the matched PSK.
     */
    protected open fun getTrustLevel(): String = MessageBuilder.TRUST_NONE

    /**
     * Whether this client admits a server holding no pairing record.
     *
     * This is what decides whether an unpaired connection can carry audio at
     * all: the spec allows `['playback']` on a Sentinel-keyed session "only when
     * the client has unpaired access enabled". Default on, so a fresh install
     * plays before anyone has paired anything; item 3.2 (#228) lets a paired
     * server toggle it.
     */
    protected open fun isUnpairedAccessEnabled(): Boolean = true

    /**
     * The pairing methods this client currently offers.
     *
     * "An implemented method that is disabled is omitted", so a disabled
     * `pairing_psk` leaves this list - and its PSK leaves the handshake
     * candidate set at the same time, or the server could still re-handshake to
     * a method the client no longer advertises.
     */
    protected open fun getSupportedPairMethods(): List<MessageBuilder.PairMethodDescriptor> =
        listOf(MessageBuilder.PairMethodDescriptor.PAIRING_PSK)

    /**
     * Send player state update (volume/muted/availability).
     */
    protected fun sendPlayerStateUpdate() {
        val delayMs = getTimeFilter().staticDelayMs
        val minBufferMs = synchronized(adaptiveBufferLock) {
            val target = adaptiveBuffer?.currentTargetMs ?: SendSpinProtocol.PlayerTiming.MIN_BUFFER_MS
            lastReportedMinBufferMs = target
            target
        }
        sendProtocolMessage(
            MessageBuilder.buildPlayerState(
                currentVolume, currentMuted, isAvailable(), delayMs,
                minBufferMs = minBufferMs,
                // On the legacy dialect there is no server/activate, so the
                // player object always ships; on the spec path it may only
                // appear once the role is actually active.
                playerRoleActive = !isEncrypted || activeRoles.contains(ROLE_PLAYER_V1),
            )
        )
    }

    /**
     * Feed one time-sync measurement into the adaptive buffer policy and, if the
     * learned `min_buffer_ms` target shifted, report it (debounced by the policy's
     * own grow/shrink cooldowns). Also re-evaluates sync state, preserving the
     * previous [onMeasurementApplied] behavior.
     */
    private fun onTimeMeasurement(rttMicros: Long) {
        val filter = getTimeFilter()
        val quality = when {
            filter.isReady && filter.isConverged -> AdaptiveBufferPolicy.SyncQuality.GOOD
            filter.isReady -> AdaptiveBufferPolicy.SyncQuality.DEGRADED
            else -> AdaptiveBufferPolicy.SyncQuality.LOST
        }
        val changed = synchronized(adaptiveBufferLock) {
            val policy = adaptiveBuffer
            if (policy != null) {
                policy.update(
                    nowMs = android.os.SystemClock.elapsedRealtime(),
                    rttMs = rttMicros / 1000.0,
                    quality = quality
                )
                policy.currentTargetMs != lastReportedMinBufferMs
            } else {
                false
            }
        }
        evaluateAndPublishSyncState()
        if (changed && handshakeComplete) {
            Log.d(tag, "Adaptive min_buffer_ms -> ${adaptiveBuffer?.currentTargetMs}")
            sendPlayerStateUpdate()
        }
    }

    /**
     * Public hook for code outside the protocol handler (e.g.
     * [OutputLatencyEstimator] via [SyncAudioPlayer]) to push a fresh
     * `client/state` to the server, for example after auto-measured
     * `static_delay_ms` converges.
     */
    fun sendClientStateSnapshot() {
        if (!handshakeComplete) return
        sendPlayerStateUpdate()
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
     * Report or clear the 'external_source' client state (spec: output is
     * in use by an external system, e.g. another app holds audio focus).
     *
     * While active, [evaluateAndPublishSyncState] is suspended so the
     * filter-derived synchronized/error states don't overwrite it. On
     * clear, the state is recomputed from the time filter and republished.
     *
     * Safe to call from any thread.
     */
    fun setExternalSource(active: Boolean) {
        val changed = synchronized(syncStateLock) {
            if (externalSourceActive == active) return
            externalSourceActive = active
            if (active) {
                currentSyncState = "external_source"
            } else {
                val filter = getTimeFilter()
                currentSyncState = if (filter.isReady && filter.isConverged) "synchronized" else "error"
            }
            true
        }
        if (changed) {
            Log.i(tag, "External source ${if (active) "active" else "cleared"}: state=$currentSyncState")
            if (handshakeComplete) sendPlayerStateUpdate()
        }
    }

    /**
     * Recompute the client's sync state from the time filter and publish
     * any change to the server and to the audio sink.
     *
     * Reports "synchronized" once the filter is converged for the first
     * time, "error" otherwise. Audio mute is requested only after a
     * successful sync has been established at least once and is then lost
     * — the initial pre-sync window does not silence playback.
     *
     * Idempotent: only fires server / mute notifications on transitions.
     * Safe to call from any thread.
     */
    fun evaluateAndPublishSyncState() {
        val muteChange: Boolean? = synchronized(syncStateLock) {
            // While an external source owns the output, synchronized/error
            // reporting (and its mute side effects) is suspended.
            if (externalSourceActive) return

            val filter = getTimeFilter()
            val converged = filter.isReady && filter.isConverged
            if (converged) {
                hasEverConverged = true
            }

            val desiredState = if (converged) "synchronized" else "error"
            setSyncState(desiredState)

            val desiredMute = hasEverConverged && desiredState == "error"
            if (desiredMute != lastPublishedMute) {
                lastPublishedMute = desiredMute
                desiredMute
            } else {
                null
            }
        }
        if (muteChange != null) {
            onSyncMuteChanged(muteChange)
        }
    }

    /**
     * Reset all sync-state tracking back to "before any sync has been
     * achieved on this server." Call this on a fresh connection to a new
     * server; do NOT call it during a normal reconnect cycle.
     *
     * Safe to call from any thread.
     */
    fun resetSyncStateTracking() {
        val needsUnmute = synchronized(syncStateLock) {
            hasEverConverged = false
            externalSourceActive = false
            currentSyncState = "error"
            if (lastPublishedMute) {
                lastPublishedMute = false
                true
            } else {
                false
            }
        }
        if (needsUnmute) {
            onSyncMuteChanged(false)
        }
    }

    /**
     * Send a controller command (play, pause, stop, next, previous, volume,
     * mute, repeat_off, repeat_one, repeat_all, shuffle, unshuffle, switch).
     *
     * Per spec, commands should be one of the server's advertised
     * supported_commands; once the server has told us its set, anything
     * outside it is dropped (the server would ignore it anyway).
     *
     * @param volume only used when [command] is "volume"
     * @param mute only used when [command] is "mute"
     */
    fun sendCommand(command: String, volume: Int? = null, mute: Boolean? = null) {
        val supported = currentControllerState?.supportedCommands
        if (supported != null && command !in supported) {
            Log.w(tag, "Dropping controller command '$command': not in server supported_commands $supported")
            return
        }
        sendProtocolMessage(MessageBuilder.buildCommand(command, volume, mute))
    }

    /**
     * Request a different stream format from the server (spec
     * stream/request-format). Omitted fields keep their current value.
     * The server responds with stream/start, which flows through the
     * normal format-change reconfiguration path.
     */
    fun requestStreamFormat(
        codec: String? = null,
        sampleRate: Int? = null,
        channels: Int? = null,
        bitDepth: Int? = null
    ) {
        if (!handshakeComplete) return
        Log.i(tag, "Requesting stream format: codec=$codec, rate=$sampleRate, ch=$channels, bits=$bitDepth")
        sendProtocolMessage(MessageBuilder.buildStreamRequestFormat(codec, sampleRate, channels, bitDepth))
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
        synchronized(adaptiveBufferLock) {
            val policy = AdaptiveBufferPolicy(
                if (isLowMemoryMode()) AdaptiveBufferPolicy.lowMemory() else AdaptiveBufferPolicy.generous()
            )
            adaptiveBuffer = policy
            lastReportedMinBufferMs = policy.currentTargetMs
        }
        timeSyncManager = TimeSyncManager(
            timeFilter = timeFilter,
            sendClientTime = { sendClientTime() },
            onMeasurementApplied = { rttMicros -> onTimeMeasurement(rttMicros) },
            tag = tag
        )
    }

    // ========== Encrypted channel ==========

    /**
     * Set once the Noise handshake completes. Null means the legacy
     * (unencrypted) dialect, which Music Assistant still accepts today behind
     * its `allow_legacy_clients` toggle but has documented as temporary.
     *
     * This one field is what switches the whole protocol layer between the two
     * wire formats: every existing caller of [sendProtocolMessage] becomes
     * encrypted with no further edits, and [handleBinaryMessage] routes through
     * the codec instead of parsing a bare frame.
     */
    @Volatile
    private var wireCodec: NoiseWireCodec? = null

    /** True once the connection is carrying Noise ciphertexts. */
    val isEncrypted: Boolean get() = wireCodec != null

    /** Install the transport produced by the handshake driver. */
    fun installEncryptedTransport(transport: NoiseTransport) {
        wireCodec = NoiseWireCodec(transport)
        Log.i(tag, "Encrypted channel established")
    }

    /**
     * A `noise/handshake` arrived inside the encrypted channel.
     *
     * Overridden by the connection, which owns the identity, the candidate set
     * and the prior handshake hash. The base implementation closes: a
     * `noise/handshake` is only ever valid in transport mode, and a handler
     * that cannot run one must not silently ignore it.
     */
    protected open fun onRehandshakeMessage(payload: JsonObject?) {
        onProtocolFailure("noise/handshake received but re-handshake is not supported here")
    }

    /**
     * Reset the application-level handshake state after a re-handshake.
     *
     * The channel is promoted, not replaced: the transport, the group and the
     * time filter all survive, so this deliberately does NOT touch them. What
     * does reset is the message sequence - the server sends `server/hello`
     * again, and the next `server/activate` is a *first* activation, so a
     * server that omits `active_roles` clears them rather than inheriting the
     * roles from before the promotion.
     */
    protected fun resetForRehandshake() {
        handshakeComplete = false
        activationSeen = false
        activeRoles = emptyList()
        activities = emptySet()
    }

    /** Drop the encrypted channel (disconnect, or falling back to legacy). */
    fun clearEncryptedTransport() {
        wireCodec = null
    }

    /**
     * Send an application protocol message.
     *
     * Encrypted when a Noise transport is installed, a plain text frame
     * otherwise. Callers do not need to know which.
     */
    protected fun sendProtocolMessage(text: String) {
        val codec = wireCodec
        if (codec == null) {
            // Legacy dialect: a plain text frame.
            sendTextMessage(text)
            return
        }
        // encodeJson takes the send mutex, so this has to be in a coroutine.
        getCoroutineScope().launch { sendProtocolMessageAwaiting(text) }
    }

    /**
     * [sendProtocolMessage], but the caller can tell when the frames have been
     * handed to the transport.
     *
     * Needed wherever something must happen strictly after a message is on the
     * wire - closing the connection after a goodbye, for instance. The
     * fire-and-forget version returns while the encrypt is still queued, so
     * "send, then close" written in that order does not execute in it.
     */
    protected suspend fun sendProtocolMessageAwaiting(text: String) {
        val codec = wireCodec
        if (codec == null) {
            sendTextMessage(text)
            return
        }
        try {
            codec.encodeJson(text).forEach { sendBinaryFrame(it) }
        } catch (e: Exception) {
            Log.e(tag, "Failed to encrypt outbound message", e)
            onProtocolFailure("outbound encryption failed: ${e.message}")
        }
    }

    /**
     * Send [text] under the current keys, then promote the channel to [next].
     *
     * The completing frame of a re-handshake. Ordering is the codec's problem
     * (it holds the send mutex across encrypt-then-install); ordering with
     * respect to the *application* is this method's: [onSwapped] runs only
     * after the frame is on the wire, so the re-asserted `client/hello` cannot
     * be built from state the swap has not finished changing.
     */
    protected fun sendAndSwapKeys(text: String, next: NoiseCrypto, onSwapped: () -> Unit) {
        val codec = wireCodec
        if (codec == null) {
            onProtocolFailure("re-handshake attempted with no encrypted channel")
            return
        }
        getCoroutineScope().launch {
            try {
                codec.encodeAndSwap(
                    SendSpinProtocol.BinaryType.JSON,
                    text.encodeToByteArray(),
                    next,
                ).forEach { sendBinaryFrame(it) }
                onSwapped()
            } catch (e: Exception) {
                Log.e(tag, "Re-handshake key swap failed", e)
                onProtocolFailure("re-handshake key swap failed: ${e.message}")
            }
        }
    }

    /**
     * A protocol-level failure that requires closing the socket.
     *
     * The spec allows no application-level error message for these, so the only
     * thing to do is close - and the only diagnostic anyone will ever have is
     * the log line the implementation writes here.
     */
    protected open fun onProtocolFailure(reason: String) {
        Log.e(tag, "Protocol failure: $reason")
    }

    // ========== Message Handling ==========

    /**
     * Handle incoming text (JSON) message.
     * Dispatches to appropriate handler based on message type.
     */
    protected fun handleTextMessage(text: String) {
        Log.d(tag, "Received: ${text.take(500)}")

        try {
            val json = Json.parseToJsonElement(text).jsonObject
            val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return
            val payload = json["payload"]?.jsonObject

            when (type) {
                // An in-band re-handshake. It arrives as an ordinary encrypted
                // JSON message inside the current channel, which is why it is
                // dispatched here and not by the cleartext handshake driver.
                SendSpinProtocol.MessageType.NOISE_HANDSHAKE -> onRehandshakeMessage(payload)

                // Deliberately not gated on `activities`, and deliberately
                // discarding the payload: "Valid at any time regardless of the
                // current `activities`", and the message has no fields.
                SendSpinProtocol.MessageType.SERVER_UNPAIR -> handleServerUnpair()

                SendSpinProtocol.MessageType.PAIR_ABORT -> handlePairAbort(payload)
                SendSpinProtocol.MessageType.SERVER_PAIR_FINALIZE -> handleServerPairFinalize()
                SendSpinProtocol.MessageType.SERVER_HELLO -> handleServerHello(payload)
                SendSpinProtocol.MessageType.SERVER_ACTIVATE -> handleServerActivate(payload)
                SendSpinProtocol.MessageType.SERVER_TIME -> handleServerTime(payload)
                SendSpinProtocol.MessageType.SERVER_STATE -> handleServerState(payload)
                SendSpinProtocol.MessageType.SERVER_COMMAND -> handleServerCommand(payload)
                SendSpinProtocol.MessageType.GROUP_UPDATE -> handleGroupUpdate(payload)
                SendSpinProtocol.MessageType.STREAM_START -> handleStreamStart(payload)
                SendSpinProtocol.MessageType.STREAM_END -> handleStreamEnd(payload)
                SendSpinProtocol.MessageType.STREAM_CLEAR -> handleStreamClear()
                SendSpinProtocol.MessageType.CLIENT_SYNC_OFFSET -> handleClientSyncOffset(payload)
                // Before the else: every management request must be
                // answered, including one we do not implement. Falling through
                // to the unhandled log leaves the server waiting for a reply
                // that never comes - which is exactly how MA's device-settings
                // dialog hangs (#228).
                else -> if (type.startsWith("management/")) {
                    handleManagementRequest(type, payload)
                } else {
                    Log.d(tag, "Unhandled message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse message: ${text.take(100)}", e)
        }
    }

    protected open fun handleServerHello(payload: JsonObject?) {
        val result = MessageParser.parseServerHello(payload, "Unknown")
        if (result == null) {
            Log.e(tag, "Failed to parse server/hello")
            return
        }

        // server/hello carries only `name` in the current spec. active_roles
        // moved to server/activate and connection_reason was an aiosendspin
        // legacy-mode invention; both are still parsed for the legacy dialect
        // but must not be acted on here.
        Log.i(tag, "server/hello: name=${result.serverName}")

        handshakeComplete = true

        // Clear cached values so the first post-handshake messages always propagate
        _streamActive = false
        _currentStreamConfig = null
        lastMetadata = null
        lastPlaybackState = null
        lastGroupInfo = null
        currentControllerState = null
        activationSeen = false
        activeRoles = emptyList()

        onHandshakeComplete(result.serverName, result.serverId)

        if (isEncrypted) {
            // "Only after receiving the initial server/activate should the
            // client send any other messages (including client/time and the
            // initial client/state)." Starting either here would put frames on
            // the wire before the server has told us what this connection is
            // for.
            Log.d(tag, "Waiting for server/activate before sending state or time")
        } else {
            sendPlayerStateUpdate()
            startTimeSync()
        }
    }

    /** The versioned player role, as it appears in active_roles. */
    protected val ROLE_PLAYER_V1 = SendSpinProtocol.Roles.PLAYER

    /** True once the first server/activate has been accepted on this connection. */
    @Volatile
    protected var activationSeen = false
        private set

    /** Roles the server has activated, persisted across activations that omit them. */
    @Volatile
    protected var activeRoles: List<String> = emptyList()
        private set

    /** Activities currently declared on this connection. */
    @Volatile
    protected var activities: Set<Activity> = emptySet()
        private set

    /**
     * The PSK category that admitted this connection. Drives the admissibility
     * table; item 2.3 (#204) makes it follow the real handshake result.
     */
    protected open fun matchedPskCategory(): PskCategory = PskCategory.SENTINEL

    /** Pairing methods this client currently offers, as live configuration. */
    protected open fun offeredPairMethods(): Set<String> = setOf("pairing_psk")

    protected fun handleServerActivate(payload: JsonObject?) {
        val activate = ServerActivateRules.parse(payload)
        if (activate == null) {
            Log.e(tag, "server/activate missing required activities")
            onProtocolFailure("malformed server/activate")
            return
        }
        if (activate.unknownActivities.isNotEmpty()) {
            // Forward compatibility: ignore, but say so - an unknown activity
            // usually means the server is newer than we are.
            Log.i(tag, "Ignoring unknown activities: ${activate.unknownActivities}")
        }

        val outcome = ServerActivateRules.evaluate(
            activate = activate,
            category = matchedPskCategory(),
            unpairedAccessEnabled = isUnpairedAccessEnabled(),
            previousRoles = activeRoles,
            isFirstActivation = !activationSeen,
            offeredPairMethods = offeredPairMethods(),
        )

        when (outcome) {
            is ActivationOutcome.Close -> {
                Log.w(tag, "Rejecting server/activate: ${outcome.goodbyeReason} " +
                    "(activities=${activate.activities}, roles=${activate.activeRoles})")
                sendGoodbye(outcome.goodbyeReason)
                onProtocolFailure("server/activate not admissible: ${outcome.goodbyeReason}")
            }

            is ActivationOutcome.AbortPairing -> {
                // Connection stays open; the server may re-activate with a
                // method we do offer.
                Log.w(tag, "Aborting pairing: ${outcome.reason}")
                onPairAbort(outcome.reason)
            }

            is ActivationOutcome.Accept -> {
                val first = !activationSeen
                activities = activate.activities
                activeRoles = outcome.activeRoles
                activationSeen = true
                Log.i(tag, "server/activate accepted: activities=${activate.activities} " +
                    "roles=${outcome.activeRoles}")
                val pairing = Activity.PAIRING in activate.activities

                // A pairing activation is answered with client/pair-finalize and
                // NOTHING else. The server is sitting in _receive_pairing
                // waiting for that exact message, so a client/state or the
                // client/time burst arriving first is read as the finalize and
                // rejected as malformed - which is precisely how this failed
                // against both Music Assistant builds.
                //
                // Withholding them costs nothing: the admissibility table grants
                // no roles on a Pairing-PSK connection, so there is no player
                // state worth reporting and no stream to synchronise to. The
                // activation that follows the promotion starts them.
                if (first && !pairing) {
                    // Now, and only now, may we speak.
                    sendPlayerStateUpdate()
                    startTimeSync()
                }

                // Every accepted activation is fed to the pairing flow, not
                // only the pairing ones: an activation WITHOUT `pairing` is how
                // the server ends an attempt without finalizing, and the client
                // must then discard the PSK it generated.
                runPairingActions(
                    if (pairing) {
                        PairingEvent.PairingActivation(
                            method = activate.pairingMethod,
                            // From the handshake, never re-derived: this is the
                            // only thing keeping a long-term secret off an
                            // unauthenticated connection.
                            matchedCategory = matchedPskCategory(),
                        )
                    } else {
                        PairingEvent.NonPairingActivation
                    }
                )
            }
        }
    }

    /**
     * Send `pair/abort`.
     *
     * "With reason `concurrent_attempt` the sender closes the connection after
     * sending, otherwise the connection stays open." The close is the sender's
     * job only - see [handlePairAbort] for the receiving side, which never
     * closes.
     *
     * The send and the close are sequenced in one coroutine for the same reason
     * the unpair goodbye is: `sendProtocolMessage` returns while the encrypt is
     * still queued, so a close issued after it can outrun the frame.
     */
    protected fun sendPairAbort(reason: String) {
        Log.w(tag, "Pairing aborted (sent): reason=$reason")
        val closes = reason in PairAbortReason.CLOSES_CONNECTION
        getCoroutineScope().launch {
            sendProtocolMessageAwaiting(MessageBuilder.buildPairAbort(reason))
            if (closes) closeConnectionAfterFlush()
        }
    }

    /**
     * Kept as an override point for the legacy call sites in 1.6's activation
     * handling, which abort before any attempt exists.
     */
    protected open fun onPairAbort(reason: String) {
        sendPairAbort(reason)
    }

    /**
     * `pair/abort` from the server.
     *
     * Never closes the connection, for any reason including
     * `concurrent_attempt`: the spec makes the *sender* close, and closing here
     * too would race the peer's close and report the wrong reason for it.
     *
     * The reason is passed through unvalidated on purpose. A reason we do not
     * recognise still means the peer has abandoned the attempt, and treating it
     * as a protocol error would leave us waiting on an attempt that is over.
     */
    protected fun handlePairAbort(payload: JsonObject?) {
        val reason = payload?.get("reason")?.jsonPrimitive?.contentOrNull ?: "unspecified"
        Log.w(tag, "Pairing aborted (received): reason=$reason")
        runPairingActions(PairingEvent.PairAbortReceived(reason))
    }

    // ========== Management (item 3.1) ==========

    /** Where the pairing configuration lives. Null on the legacy path. */
    protected open fun pairingConfigStore(): PairingConfigStore? = null

    /**
     * Answer one `management/` request.
     *
     * Handled to completion on the receive path, synchronously, and that is a
     * requirement rather than a convenience: "At most one management request
     * may be in flight per connection; in-order WebSocket delivery makes the
     * reply unambiguous", and the reply carries no request identifier. The Nth
     * result on the wire IS the answer to the Nth request. Moving the work to
     * another dispatcher would let two replies race and be attributed to the
     * wrong requests, with nothing in either frame to reveal the swap.
     *
     * If persistence ever has to leave this thread, it needs a single-consumer
     * serialized queue, not a `launch`.
     */
    private fun handleManagementRequest(type: String, payload: JsonObject?) {
        val request = ManagementRequestParser.parse(type, payload) ?: return

        val matched = matchedPsk()
        val outcome = ManagementService(trustStore(), pairingConfigStore()).handle(
            request,
            ManagementSessionContext(
                hasManagementActivity = Activity.MANAGEMENT in activities,
                // No PIN method is implemented (audit D2), so none can be on.
                pinMethodEnabled = false,
                // Only a record identifies "our own record"; the Sentinel and
                // the Pairing PSK are not records and cannot be removed.
                matchedPskId = matched?.takeIf { it.category == PskCategory.LONG_TERM }?.pskId,
            ),
        )

        Log.i(tag, "management: $type -> ${outcome.code.wire}")

        // Sequenced in one coroutine, and that is the contract: the result must
        // reach the wire before the goodbye, because the result is the only
        // thing that tells the server the operation happened. A close on its
        // own reads as a failure. sendProtocolMessage returns while the encrypt
        // is still queued, so the awaiting form is what makes the order real.
        getCoroutineScope().launch {
            sendProtocolMessageAwaiting(
                MessageBuilder.buildManagementResult(outcome.code, outcome.data)
            )
            outcome.closeAfterReply?.let { reason ->
                sendProtocolMessageAwaiting(MessageBuilder.buildGoodbye(reason))
                onManagementSessionRevoked()
                closeConnectionAfterFlush()
            }
        }
    }

    /**
     * The client removed the record that authenticated this session.
     *
     * "Server should not auto-reconnect with the same activity set" - and we
     * should not either: the credential is gone, so every attempt would fail
     * the handshake PSK lookup and loop.
     */
    protected open fun onManagementSessionRevoked() {}

    /** The operator cancelled pairing from the UI. Leaves the connection open. */
    fun cancelPairing() {
        runPairingActions(PairingEvent.UserCancelled)
    }

    // ========== Pairing PSK flow (item 2.5) ==========

    /** One attempt at a time, owned by the connection. */
    private val pairingFlow = PairingPskFlow()

    private var attemptTimeoutJob: Job? = null

    /**
     * The `server_id` this connection authenticated against, for the record a
     * successful pairing persists. Null on the legacy path.
     */
    protected open fun currentServerId(): String? = null

    /** Where a completed pairing stores its record. */
    protected open fun trustStore(): TrustStore? = null

    /** Surfaced for the pairing UI (#225). */
    protected open fun onPaired(serverId: String) {}

    // ========== server/unpair (item 2.7) ==========

    /**
     * The PSK that admitted this connection, or null before the handshake.
     *
     * This is the single source of truth for the session's trust level, and
     * `server/unpair` must read it rather than ask "do we hold a record for
     * this server?". The two differ in a case that matters: during a pairing
     * handshake we may well hold a record for that same server from a previous
     * pairing, while the current session was admitted by the Pairing PSK and is
     * `trust_level: none`. Deciding on the record would delete it.
     *
     * A re-handshake replaces it at the key swap, so an unpair arriving just
     * after a promotion sees the post-swap value.
     */
    protected open fun matchedPsk(): Psk? = null

    /** The record this connection dropped. Drives the UI and reconnect policy. */
    protected open fun onUnpaired(pskId: String, serverId: String?) {}

    /**
     * Close the connection once the goodbye is on the wire.
     *
     * Separate from an ordinary close because the frame must actually be
     * flushed first; see [handleServerUnpair].
     */
    protected open fun closeConnectionAfterFlush() {}

    /** One unpair per connection; a repeat is a no-op. */
    private var unpairHandled = false

    /**
     * `messaging.md#server--client-serverunpair`: "Remove the matched pairing
     * record, send `client/goodbye` reason `'unpaired'`, and close the
     * connection."
     *
     * Takes no payload: the message has no fields, and ignoring whatever
     * arrives is exactly the required tolerance for unknown ones.
     */
    protected fun handleServerUnpair() {
        val matched = matchedPsk()

        // "If the connection's `trust_level` is `'none'` (e.g., an in-flight
        // pairing handshake), ignore the message and continue unchanged." Not
        // an error, and specifically not a close: the connection carries on.
        if (matched == null || matched.category != PskCategory.LONG_TERM) {
            Log.i(tag, "server/unpair at trust_level none (psk=${matched?.category}) - ignoring")
            return
        }

        if (unpairHandled) {
            Log.d(tag, "server/unpair already handled on this connection - ignoring")
            return
        }

        if (matched.serverId == null) {
            // "If the matched record is a shared-PSK record ... the client MUST
            // NOT remove it." The same PSK may authenticate other servers, and
            // none of them asked to be unpaired. Wholesale removal is
            // management/remove-record's job.
            Log.i(tag, "server/unpair matched shared-PSK record ${matched.pskId} - retaining it")
        } else {
            val store = trustStore()
            if (store == null) {
                Log.e(tag, "server/unpair with no trust store - cannot drop the record")
                return
            }
            // Durable before we say a word. A crash between the two must not
            // leave a record the server has already forgotten, and telling the
            // server we unpaired while the record survives is worse still: the
            // device keeps authenticating with a credential that is gone, and
            // it looks like a working pairing until the next handshake fails.
            try {
                store.removeRecord(matched.pskId)
            } catch (e: Exception) {
                Log.e(tag, "server/unpair could not remove record ${matched.pskId}", e)
                return
            }
            Log.i(tag, "server/unpair removed record ${matched.pskId} for ${matched.serverId}")
        }

        unpairHandled = true
        onUnpaired(matched.pskId, matched.serverId)

        // The goodbye has to reach the wire before the close, and on the
        // encrypted path sending is a suspending encrypt. Sequencing them in
        // one coroutine is what makes "send then close" true rather than
        // merely written in that order.
        getCoroutineScope().launch {
            sendProtocolMessageAwaiting(MessageBuilder.buildGoodbye(GoodbyeReason.UNPAIRED))
            closeConnectionAfterFlush()
        }
    }

    protected fun handleServerPairFinalize() {
        runPairingActions(PairingEvent.ServerPairFinalize)
    }

    /** Called by the connection when the socket goes away mid-attempt. */
    fun onConnectionClosedForPairing() {
        runPairingActions(PairingEvent.ConnectionClosed)
    }

    private fun runPairingActions(event: PairingEvent) {
        for (action in pairingFlow.onEvent(event)) {
            when (action) {
                is PairingAction.SendPairFinalize -> {
                    // Metadata only. The payload carries the long-term PSK in
                    // the clear (inside the encrypted channel), so logging the
                    // message itself would put a live credential in logcat.
                    Log.i(tag, "Pairing: sending client/pair-finalize (32-byte PSK)")
                    sendProtocolMessage(
                        MessageBuilder.buildClientPairFinalize(action.longTermPsk)
                    )
                }

                is PairingAction.SendPairAbort -> sendPairAbort(action.reason)

                is PairingAction.PersistRecord -> persistPairingRecord(action.psk)

                PairingAction.StartAttemptTimeout -> {
                    attemptTimeoutJob?.cancel()
                    attemptTimeoutJob = getCoroutineScope().launch {
                        delay(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS)
                        Log.w(tag, "Pairing attempt timed out")
                        runPairingActions(PairingEvent.AttemptTimeout)
                    }
                }

                PairingAction.ClearAttemptTimeout -> {
                    attemptTimeoutJob?.cancel()
                    attemptTimeoutJob = null
                }
            }
        }
    }

    private fun persistPairingRecord(psk: ByteArray) {
        val store = trustStore()
        val serverId = currentServerId()
        if (store == null || serverId == null) {
            // The server has already stored its half, so this is not
            // recoverable by retrying - say so loudly rather than leaving a
            // half-pairing that fails as `unauthorized` on the next connect.
            Log.e(tag, "Paired, but there is nowhere to store the record")
            return
        }
        when (val result = store.addRecord(psk, serverId)) {
            is TrustStore.AddRecordResult.Ok -> {
                Log.i(tag, "Paired with $serverId (psk_id=${result.record.pskId})")
                onPaired(serverId)
            }
            // Astronomically unlikely, and not worth a silent retry: the server
            // holds a PSK we cannot store, so the pairing is already broken.
            TrustStore.AddRecordResult.AlreadyExists ->
                Log.e(tag, "Cannot store pairing record: psk_id already claimed")
            TrustStore.AddRecordResult.Invalid ->
                Log.e(tag, "Cannot store pairing record: PSK rejected as invalid")
            TrustStore.AddRecordResult.StorageFailed ->
                // The one failure a user could actually act on, so it names the
                // cause rather than the symptom.
                Log.e(tag, "Cannot store pairing record: the write did not persist")
        }
    }

    protected fun handleServerTime(payload: JsonObject?) {
        val clientReceived = System.nanoTime() / 1000
        val measurement = MessageParser.parseServerTime(payload, clientReceived)

        if (measurement != null) {
            timeSyncManager?.onServerTime(measurement)
        }
    }

    protected fun handleServerState(payload: JsonObject?) {
        val (metadata, state, controllerDelta) = MessageParser.parseServerState(payload)

        if (metadata != null) {
            lastMetadata = metadata
            onMetadataUpdate(metadata)
        }

        if (state != null && state != lastPlaybackState) {
            lastPlaybackState = state
            onPlaybackStateChanged(state)
        }

        if (controllerDelta != null) {
            val merged = currentControllerState?.mergedWith(controllerDelta) ?: controllerDelta
            if (merged != currentControllerState) {
                currentControllerState = merged
                onControllerStateUpdate(merged)
            }
        }
    }

    protected fun handleServerCommand(payload: JsonObject?) {
        Log.i(tag, "[cmd-trace] T1 handleServerCommand ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
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
            is ServerCommandResult.SetStaticDelay -> {
                Log.i(tag, "Server command: set static delay to ${result.delayMs}ms")
                // Same application path as the client/sync_offset extension:
                // a server-pushed correction on top of the auto-measured
                // hardware latency.
                getTimeFilter().setServerSyncOffsetMs(result.delayMs.toDouble())
                onSyncOffsetApplied(result.delayMs.toDouble(), "server_command")
                sendPlayerStateUpdate()
            }
            is ServerCommandResult.Unknown -> {
                Log.d(tag, "Unknown player command: ${result.command}")
            }
            null -> { /* No player command in payload */ }
        }
    }

    protected fun handleGroupUpdate(payload: JsonObject?) {
        val info = MessageParser.parseGroupUpdate(payload)
        if (info != null) {
            lastGroupInfo = info
            Log.v(tag, "group/update: id=${info.groupId}, name=${info.groupName}, state=${info.playbackState}")
            onGroupUpdate(info)
        }
    }

    protected fun handleStreamStart(payload: JsonObject?) {
        val config = MessageParser.parseStreamStart(payload)
        if (config == null) return

        val formatChanged = _streamActive && config != _currentStreamConfig
        if (_streamActive) {
            if (formatChanged) {
                Log.i(tag, "Stream format changed: codec=${config.codec}, rate=${config.sampleRate}, ch=${config.channels}, bits=${config.bitDepth} - reconfiguring pipeline")
            } else {
                Log.d(tag, "Stream restart (same format): codec=${config.codec}, rate=${config.sampleRate}")
            }
        } else {
            Log.i(tag, "Stream started: codec=${config.codec}, rate=${config.sampleRate}, ch=${config.channels}, bits=${config.bitDepth}, header=${config.codecHeader?.size ?: 0} bytes")
        }

        _streamActive = true
        _currentStreamConfig = config
        onStreamStart(config)
    }

    protected fun handleStreamClear() {
        Log.i(tag, "[cmd-trace] T1 handleStreamClear ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
        Log.v(tag, "Stream clear - flushing audio buffers")
        onStreamClear()
    }

    protected fun handleStreamEnd(payload: JsonObject?) {
        Log.i(tag, "[cmd-trace] T1 handleStreamEnd ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
        val rolesArray = payload?.get("roles")?.jsonArray
        val roles = rolesArray?.map { it.jsonPrimitive.content }

        if (roles != null && SendSpinProtocol.Roles.PLAYER !in roles) {
            Log.d(tag, "Stream end for non-player roles: $roles - ignoring")
            return
        }

        Log.i(tag, "Stream end - server terminated playback (roles=${roles ?: "all"})")
        _streamActive = false
        _currentStreamConfig = null
        onStreamEnd()
    }

    protected fun handleClientSyncOffset(payload: JsonObject?) {
        val result = MessageParser.parseSyncOffset(payload)
        if (result == null) {
            Log.w(tag, "client/sync_offset: missing or invalid payload")
            return
        }

        Log.i(tag, "client/sync_offset: offset=${result.offsetMs}ms from ${result.source}")

        val clampedOffset = result.offsetMs.coerceIn(-5000.0, 5000.0)
        if (clampedOffset != result.offsetMs) {
            Log.w(tag, "client/sync_offset: clamped from ${result.offsetMs}ms to ${clampedOffset}ms")
        }

        getTimeFilter().setServerSyncOffsetMs(clampedOffset)
        Log.d(tag, "client/sync_offset: static delay set to ${clampedOffset}ms")

        onSyncOffsetApplied(clampedOffset, result.source)
    }

    // ========== Binary Message Handling ==========

    /**
     * Handle binary message from the transport.
     */
    protected fun handleBinaryMessage(bytes: ByteArray) {
        val codec = wireCodec
        if (codec == null) {
            // Legacy path: the frame is a bare SendSpin binary message.
            BinaryMessageParser.parse(bytes)?.let { dispatchBinaryMessage(it) }
            return
        }
        when (val decoded = codec.decode(bytes)) {
            is NoiseWireCodec.Decoded.Json -> handleTextMessage(decoded.text)
            is NoiseWireCodec.Decoded.Typed ->
                BinaryMessageParser.parse(decoded.type, decoded.body)
                    ?.let { dispatchBinaryMessage(it) }
            is NoiseWireCodec.Decoded.Buffered -> {
                // A fragment landed and the message is still incomplete. The
                // codec holds the partial buffer; nothing to dispatch until the
                // fragment-end frame arrives.
            }
            is NoiseWireCodec.Decoded.ProtocolError ->
                onProtocolFailure(decoded.reason)
        }
    }

    /**
     * Dispatch parsed binary message to appropriate handler.
     */
    private fun dispatchBinaryMessage(message: BinaryMessageParser.BinaryMessage) {
        when (message) {
            is BinaryMessageParser.BinaryMessage.Audio -> {
                // Spec: binary messages should be rejected if there is no
                // active stream (e.g. chunks in flight after stream/end).
                if (!_streamActive) {
                    Log.v(tag, "Dropping audio chunk: no active stream")
                    return
                }
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
