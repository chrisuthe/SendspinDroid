package com.sendspindroid.coordinator

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.sendspindroid.model.ConnectionType
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.network.ConnectionSelector
import com.sendspindroid.network.NetworkEvaluator
import com.sendspindroid.network.NetworkState
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Events emitted by [ConnectionCoordinator] for Android-specific network signals
 * that cannot be represented in the platform-neutral [NetworkState] data class.
 *
 * PlaybackService subscribes to [ConnectionCoordinator.networkEvents] to dispatch
 * side effects (onNetworkChanged, disconnectForReselection, multicast-lock refresh)
 * that depend on these signals.
 */
sealed class NetworkEvent {
    /**
     * The active network's identity changed (different [networkHandle]).
     * Indicates a transport handover (e.g., WiFi -> Cellular). The reconnect
     * loop should re-run ConnectionSelector against the new network.
     */
    data class IdentityChanged(val networkHandle: Long) : NetworkEvent()

    /**
     * Link addresses changed on the active network without an identity change.
     * Indicates a DHCP renewal or IPv4/IPv6 stack change. RTT baseline should
     * be dropped and multicast locks refreshed.
     */
    data class LinkAddressesChanged(val addresses: Set<LinkAddress>) : NetworkEvent()
}

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
 * Phase 3: Owns the ConnectivityManager.NetworkCallback. PlaybackService observes
 * [networkState] and [networkEvents] to dispatch side effects.
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
    private val context: android.content.Context,
) {
    companion object {
        private const val TAG = "ConnectionCoordinator"
        private val BACKOFF_DELAYS = listOf(
            500L, 1000L, 2000L, 4000L, 8000L,
            15000L, 30000L, 60000L, 60000L, 60000L, 60000L,
        )
        private const val MAX_ATTEMPTS = 11
        private const val NETWORK_DEBOUNCE_MS = 2_000L
        private const val MIN_DELAY_AFTER_NETWORK_SKIP_MS = 500L
        private const val VALIDATION_LOSS_DEBOUNCE_MS = 3_000L
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

    private val _networkState = MutableStateFlow<NetworkState>(NetworkState())
    val networkState: StateFlow<NetworkState> = _networkState

    // Android-specific network events that cannot be encoded in the platform-neutral NetworkState.
    // Replay=0: events are ephemeral -- a late subscriber should not re-process stale events.
    private val _networkEvents = MutableSharedFlow<NetworkEvent>(replay = 0, extraBufferCapacity = 8)
    val networkEvents: SharedFlow<NetworkEvent> = _networkEvents.asSharedFlow()

    private var reconnectJob: Job? = null
    private var reconnectingServer: UnifiedServer? = null
    private val currentAttempt = AtomicInteger(0)
    private val isReconnecting = AtomicBoolean(false)
    @Volatile private var skipDelay: CompletableDeferred<Unit>? = null
    @Volatile private var lastNetworkSkipNanos: Long = 0L

    // Network callback state -- all written exclusively from binder/callback threads.
    @Volatile private var lastNetworkHandle: Long = -1L
    @Volatile private var lastLinkAddresses: Set<LinkAddress>? = null
    @Volatile private var lastValidatedState: Boolean? = null
    private var validationLossJob: Job? = null

    private val networkEvaluator: NetworkEvaluator? = try {
        NetworkEvaluator(context)
    } catch (e: Exception) {
        Log.w(TAG, "NetworkEvaluator unavailable (test environment?)", e)
        null
    }

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val handle = network.networkHandle
            Log.d(TAG, "Network available: handle=$handle (last=$lastNetworkHandle)")

            networkEvaluator?.evaluateCurrentNetwork(network)
            networkEvaluator?.networkState?.value?.let { _networkState.value = it }

            // Trigger the reconnect-skip path (replaces the coordinator.onNetworkAvailable()
            // call that PlaybackService used to make directly).
            triggerNetworkAvailableSkip()

            // Detect network identity change (not the very first callback).
            if (lastNetworkHandle != -1L && lastNetworkHandle != handle) {
                Log.i(TAG, "Network identity changed: $lastNetworkHandle -> $handle")
                // Clear link-address baseline so the next onLinkPropertiesChanged on the
                // new network starts fresh rather than comparing against old addresses.
                lastLinkAddresses = null
                scope.launch { _networkEvents.emit(NetworkEvent.IdentityChanged(handle)) }
            }
            lastNetworkHandle = handle
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost: handle=${network.networkHandle}")
            val stillHaveNetwork = connectivityManager?.activeNetwork != null
            networkEvaluator?.evaluateCurrentNetwork(
                if (stillHaveNetwork) connectivityManager?.activeNetwork else null
            )
            networkEvaluator?.networkState?.value?.let { _networkState.value = it }

            // Cancel any pending validation-loss debounce; onLost is authoritative.
            validationLossJob?.cancel()
            validationLossJob = null

            if (!stillHaveNetwork) {
                Log.i(TAG, "No active network remaining - pausing client reconnect")
                // isConnected will be false in the emitted NetworkState; PlaybackService
                // observes that and calls setNetworkAvailable(false).
                lastLinkAddresses = null
            } else {
                Log.d(TAG, "Another network still active - keeping client reconnect running")
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            Log.d(TAG, "Network capabilities changed: handle=${network.networkHandle}")
            networkEvaluator?.evaluateCurrentNetwork(network)
            networkEvaluator?.networkState?.value?.let { _networkState.value = it }

            val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val wasValidated = lastValidatedState
            lastValidatedState = isValidated

            // Skip transition logic on first callback (wasValidated == null).
            if (wasValidated == true && !isValidated) {
                Log.w(TAG, "Network lost VALIDATED - debouncing ${VALIDATION_LOSS_DEBOUNCE_MS}ms")
                validationLossJob?.cancel()
                validationLossJob = scope.launch {
                    kotlinx.coroutines.delay(VALIDATION_LOSS_DEBOUNCE_MS)
                    if (lastValidatedState == false) {
                        Log.w(TAG, "Validation loss confirmed after debounce - marking network unavailable")
                        // Emit a disconnected NetworkState to signal PlaybackService.
                        _networkState.value = _networkState.value.copy(isConnected = false)
                    }
                }
            } else if (wasValidated == false && isValidated) {
                Log.i(TAG, "Network regained VALIDATED")
                validationLossJob?.cancel()
                validationLossJob = null
                // Re-emit the current (connected) evaluator state, which will cause
                // PlaybackService's collector to call setNetworkAvailable(true).
                networkEvaluator?.networkState?.value?.let { _networkState.value = it }
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            // Only care about changes on the currently-active network.
            if (network.networkHandle != lastNetworkHandle) return

            val prev = lastLinkAddresses
            val current = linkProperties.linkAddresses.toSet()
            lastLinkAddresses = current

            // First callback after onAvailable establishes the baseline -- no action.
            if (prev == null) return
            // Filter noise: DNS reorders, MTU changes, route updates all fire this.
            if (prev == current) return

            Log.i(TAG, "Link addresses changed on active network: $prev -> $current")
            scope.launch { _networkEvents.emit(NetworkEvent.LinkAddressesChanged(current)) }
        }
    }

    init {
        val cm = connectivityManager
        if (cm != null) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                cm.registerNetworkCallback(request, networkCallback)
                Log.d(TAG, "Network callback registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register network callback", e)
            }
        } else {
            Log.w(TAG, "ConnectivityManager unavailable - network callback not registered")
        }
    }

    /**
     * Releases resources owned by this Coordinator. Call from PlaybackService.onDestroy
     * before cancelling the service scope.
     */
    fun close() {
        validationLossJob?.cancel()
        validationLossJob = null
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "Network callback unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
    }

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

    /**
     * Called when network becomes available — skips the current backoff delay so the
     * reconnect loop retries immediately. No-op if not currently reconnecting, or if
     * the 2s debounce window has not elapsed since the last skip.
     *
     * This is still callable externally (COMMAND_NETWORK_AVAILABLE path in PlaybackService);
     * the NetworkCallback also calls the private equivalent directly.
     */
    fun onNetworkAvailable() {
        triggerNetworkAvailableSkip()
    }

    private fun triggerNetworkAvailableSkip() {
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

            val methods = priorityMethodsForCurrentNetwork()
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

    private fun priorityMethodsForCurrentNetwork(): List<ConnectionType> {
        return ConnectionSelector.getPriorityOrder(_networkState.value.transportType)
    }

    private fun serverHasMethod(server: UnifiedServer, method: ConnectionType): Boolean = when (method) {
        ConnectionType.LOCAL -> server.local != null
        ConnectionType.REMOTE -> server.remote != null
        ConnectionType.PROXY -> server.proxy != null
    }
}
