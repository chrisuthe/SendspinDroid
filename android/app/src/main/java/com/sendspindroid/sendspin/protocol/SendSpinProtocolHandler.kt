package com.sendspindroid.sendspin.protocol

import android.util.Log
import com.sendspindroid.sendspin.AdaptiveBufferPolicy
import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.crypto.NoiseTransport
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.protocol.message.BinaryMessageParser
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import com.sendspindroid.sendspin.protocol.message.MessageParser
import com.sendspindroid.sendspin.protocol.timesync.TimeSyncManager
import kotlinx.coroutines.CoroutineScope
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
            // On an encrypted session client_id and version live in
            // client/init; repeating them here would be sending fields the
            // spec does not define for this message.
            clientId = if (isEncrypted) null else getClientId(),
            deviceName = getDeviceName(),
            bufferCapacity = bufferCapacity,
            manufacturer = getManufacturer(),
            supportedFormats = formats,
            softwareVersion = getSoftwareVersion(),
            trustLevel = getTrustLevel(),
            unpairedAccessEnabled = isUnpairedAccessEnabled(),
        )
        sendProtocolMessage(text)
        Log.d(tag, "Sent client/hello: ${text.take(500)}")
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
     */
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
        getCoroutineScope().launch {
            try {
                codec.encodeJson(text).forEach { sendBinaryFrame(it) }
            } catch (e: Exception) {
                Log.e(tag, "Failed to encrypt outbound message", e)
                onProtocolFailure("outbound encryption failed: ${e.message}")
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
                else -> Log.d(tag, "Unhandled message type: $type")
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
                if (first) {
                    // Now, and only now, may we speak.
                    sendPlayerStateUpdate()
                    startTimeSync()
                }
            }
        }
    }

    /**
     * Send `pair/abort`. Item 2.9 (#226) owns the full reason enum and the
     * attempt state machine; this is the one path 1.6 can already reach.
     */
    protected open fun onPairAbort(reason: String) {
        sendProtocolMessage(MessageBuilder.buildPairAbort(reason))
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
            is NoiseWireCodec.Decoded.Fragment ->
                // Reassembly lands in item 1.5. Until then a fragmented message
                // is unreadable, and silently ignoring it would strand the
                // stream; the spec's answer to an unhandleable frame is to close.
                onProtocolFailure("fragmentation is not implemented yet (item 1.5)")
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
