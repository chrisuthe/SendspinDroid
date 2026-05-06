package com.sendspindroid.coordinator

import com.sendspindroid.model.ConnectionType
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.network.ConnectionSelector
import com.sendspindroid.network.TransportType
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single authority for "what server is active, and which of its transports are up,"
 * plus owner of the auto-reconnect retry loop.
 *
 * Phase 2B: absorbs the retry loop entirely. AutoReconnectManager.kt deletes
 * after this phase. The loop preserves today's behavior:
 * - 11-attempt backoff schedule (500ms, 1s, 2s, 4s, 8s, 15s, 30s, 60s x 4)
 * - Per-attempt iteration over LOCAL / REMOTE / PROXY in priority order
 * - 2s debounce on network-availability skip
 * - 500ms minimum stabilization delay after network-triggered skip
 *
 * SendSpinClient.selfReconnectEnabled is set to false externally so it no longer
 * runs its own reconnect loop -- this Coordinator is the only retry driver.
 *
 * See docs/superpowers/specs/2026-05-05-connection-coordinator-design.md
 */
class ConnectionCoordinator(
    currentServerFlow: Flow<UnifiedServer?>,
    sendSpinStateFlow: Flow<TransportState>,
    musicAssistantStateFlow: Flow<TransportState>,
    private val scope: CoroutineScope,
    private val onDisconnectRequested: () -> Unit,
    private val connectAttempt: suspend (UnifiedServer, ConnectionType) -> Boolean,
) {
    companion object {
        private val BACKOFF_DELAYS = listOf(
            500L, 1000L, 2000L, 4000L, 8000L,
            15000L, 30000L, 60000L, 60000L, 60000L, 60000L,
        )
        private const val MAX_ATTEMPTS = 11
        private const val NETWORK_DEBOUNCE_MS = 2_000L
        private const val MIN_DELAY_AFTER_NETWORK_SKIP_MS = 500L
    }

    val sessionState: StateFlow<SessionState> = combine(
        currentServerFlow,
        sendSpinStateFlow,
        musicAssistantStateFlow,
    ) { server, sendSpin, ma ->
        SessionState(server = server, sendSpin = sendSpin, musicAssistant = ma)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SessionState(),
    )

    private val _reconnectStatusFlow = MutableStateFlow<ReconnectStatus>(ReconnectStatus.Idle)
    val reconnectStatus: StateFlow<ReconnectStatus> = _reconnectStatusFlow

    private var reconnectJob: Job? = null
    private var reconnectingServer: UnifiedServer? = null
    private val currentAttempt = AtomicInteger(0)
    private val isReconnecting = AtomicBoolean(false)
    @Volatile private var skipDelay: CompletableDeferred<Unit>? = null
    @Volatile private var lastNetworkSkipNanos: Long = 0L

    fun disconnect() {
        onDisconnectRequested()
    }

    fun connect(server: UnifiedServer) {
        cancelReconnect()
        reconnectingServer = server
        currentAttempt.set(0)
        isReconnecting.set(true)
        lastNetworkSkipNanos = 0L
        reconnectJob = scope.launch {
            runReconnectLoop(server)
        }
    }

    fun cancelReconnect() {
        if (!isReconnecting.get()) return
        reconnectJob?.cancel()
        reconnectJob = null
        skipDelay = null
        isReconnecting.set(false)
        reconnectingServer = null
        currentAttempt.set(0)
        lastNetworkSkipNanos = 0L
        _reconnectStatusFlow.value = ReconnectStatus.Idle
    }

    fun onNetworkAvailable() {
        if (!isReconnecting.get()) return
        val now = System.nanoTime()
        val elapsedMs = (now - lastNetworkSkipNanos) / 1_000_000
        if (elapsedMs < NETWORK_DEBOUNCE_MS) return
        lastNetworkSkipNanos = now
        skipDelay?.complete(Unit)
    }

    private suspend fun runReconnectLoop(server: UnifiedServer) {
        for (attemptNumber in 1..MAX_ATTEMPTS) {
            currentAttempt.set(attemptNumber)
            val delayMs = BACKOFF_DELAYS.getOrElse(attemptNumber - 1) { BACKOFF_DELAYS.last() }

            _reconnectStatusFlow.value = ReconnectStatus.Attempting(
                serverId = server.id,
                attempt = attemptNumber,
                maxAttempts = MAX_ATTEMPTS,
                method = null,
            )

            val signal = CompletableDeferred<Unit>()
            skipDelay = signal
            var skippedByNetwork = false
            try {
                withTimeout(delayMs) {
                    signal.await()
                    skippedByNetwork = true
                }
            } catch (_: TimeoutCancellationException) {
                // Normal: full delay elapsed.
            }
            skipDelay = null

            if (skippedByNetwork) delay(MIN_DELAY_AFTER_NETWORK_SKIP_MS)
            coroutineContext.ensureActive()

            val methods = ConnectionSelector.getPriorityOrder(TransportType.UNKNOWN)
            var succeeded = false
            for (method in methods) {
                coroutineContext.ensureActive()
                if (!serverHasMethod(server, method)) continue

                _reconnectStatusFlow.value = ReconnectStatus.Attempting(
                    serverId = server.id,
                    attempt = attemptNumber,
                    maxAttempts = MAX_ATTEMPTS,
                    method = method,
                )

                try {
                    if (connectAttempt(server, method)) {
                        succeeded = true
                        break
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Continue to next method.
                }
            }

            if (succeeded) {
                _reconnectStatusFlow.value = ReconnectStatus.Succeeded(server.id)
                isReconnecting.set(false)
                reconnectingServer = null
                currentAttempt.set(0)
                return
            }
        }

        _reconnectStatusFlow.value = ReconnectStatus.Failed(
            serverId = server.id,
            error = "Connection lost after $MAX_ATTEMPTS reconnection attempts",
        )
        isReconnecting.set(false)
        reconnectingServer = null
        currentAttempt.set(0)
    }

    private fun serverHasMethod(server: UnifiedServer, method: ConnectionType): Boolean = when (method) {
        ConnectionType.LOCAL -> server.local != null
        ConnectionType.REMOTE -> server.remote != null
        ConnectionType.PROXY -> server.proxy != null
    }
}
