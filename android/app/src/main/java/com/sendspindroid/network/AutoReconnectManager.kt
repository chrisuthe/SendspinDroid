package com.sendspindroid.network

import android.content.Context
import android.util.Log
import com.sendspindroid.model.ConnectionType
import com.sendspindroid.model.UnifiedServer
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages persistent auto-reconnection when connection is lost unexpectedly.
 *
 * ## Design Philosophy
 * - Returns to ServerList UI immediately on connection loss
 * - Shows reconnection progress on the server card being reconnected
 * - Tries all connection methods (local -> remote -> proxy) to handle network transitions
 * - User can see status, tap other servers, or wait for auto-reconnect
 *
 * ## Architecture
 * The entire retry loop (delay + attempt + loop-back) runs inside a **single coroutine**
 * stored in [reconnectJob]. This makes [cancelReconnection] trivially correct: cancelling
 * the one job terminates everything -- backoff delay, in-flight connection attempt, and
 * any future iterations.
 *
 * When the network comes back mid-delay, [onNetworkAvailable] completes a
 * [CompletableDeferred] that the loop is selecting on, skipping the remaining
 * backoff without cancelling the coroutine.
 *
 * ## Backoff Strategy
 * ```
 * Phase 1: Quick Recovery (server hiccup)
 * ----------------------------------------
 * Attempt  Delay    Cumulative
 * 1        500ms    0.5s
 * 2        1s       1.5s
 * 3        2s       3.5s
 * 4        4s       7.5s
 * 5        8s       15s
 *
 * Phase 2: Patient Recovery (server reboot / network change)
 * ----------------------------------------
 * 6        15s      30s
 * 7        30s      1m
 * 8        60s      2m
 * 9        60s      3m
 * 10       60s      4m
 * 11       60s      5m      <- stop here
 * ```
 *
 * Each attempt tries ALL connection methods in priority order based on network type.
 *
 * ## Usage
 * ```kotlin
 * val manager = AutoReconnectManager(context,
 *     onAttempt = { serverId, attempt, maxAttempts, method ->
 *         // Update UI to show reconnecting status
 *     },
 *     onMethodAttempt = { serverId, method ->
 *         // Show which method is being tried
 *     },
 *     onSuccess = { serverId ->
 *         // Handle successful reconnection
 *     },
 *     onFailure = { serverId, error ->
 *         // Handle failure after all attempts exhausted
 *     }
 * )
 *
 * manager.startReconnecting(server)
 * manager.cancelReconnection() // if user taps different server
 * ```
 */
class AutoReconnectManager(
    private val context: Context,
    private val onAttempt: (serverId: String, attempt: Int, maxAttempts: Int, connectionType: ConnectionType?) -> Unit,
    private val onMethodAttempt: (serverId: String, method: ConnectionType) -> Unit,
    private val onSuccess: (serverId: String) -> Unit,
    private val onFailure: (serverId: String, error: String) -> Unit,
    private val connectToServer: suspend (server: UnifiedServer, method: ConnectionSelector.SelectedConnection) -> Boolean
) {
    companion object {
        private const val TAG = "AutoReconnectManager"

        // Backoff delays in milliseconds
        // Phase 1: Quick recovery (0-15s) - catches brief network glitches
        // Phase 2: Patient recovery (15s-5min) - handles server restarts, network transitions
        private val BACKOFF_DELAYS = listOf(
            500L,    // Attempt 1: 0.5s
            1000L,   // Attempt 2: 1s
            2000L,   // Attempt 3: 2s
            4000L,   // Attempt 4: 4s
            8000L,   // Attempt 5: 8s
            15000L,  // Attempt 6: 15s
            30000L,  // Attempt 7: 30s
            60000L,  // Attempt 8: 60s
            60000L,  // Attempt 9: 60s
            60000L,  // Attempt 10: 60s
            60000L   // Attempt 11: 60s
        )

        const val MAX_ATTEMPTS = 11
    }

    // State
    private var reconnectingServerId: String? = null
    private var reconnectingServer: UnifiedServer? = null
    private val currentAttempt = AtomicInteger(0)
    private val isReconnecting = AtomicBoolean(false)

    // Coroutine management -- single job owns the entire retry lifecycle
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reconnectJob: Job? = null

    // Signal to skip the current backoff delay (set by onNetworkAvailable)
    @Volatile
    private var skipDelay: CompletableDeferred<Unit>? = null

    // Network evaluator for determining connection priority
    private val networkEvaluator = NetworkEvaluator(context)

    /**
     * Starts auto-reconnection for the given server.
     * If already reconnecting to another server, cancels that first.
     */
    fun startReconnecting(server: UnifiedServer) {
        // Cancel any existing reconnection
        cancelReconnection()

        Log.i(TAG, "Starting auto-reconnect for server: ${server.name} (${server.id})")

        reconnectingServerId = server.id
        reconnectingServer = server
        currentAttempt.set(0)
        isReconnecting.set(true)

        // Get initial network state
        networkEvaluator.evaluateCurrentNetwork()

        // Launch a single coroutine that owns the entire retry loop.
        // Cancelling this one job stops everything: backoff delay, in-flight
        // connection attempt, and any future loop iterations.
        reconnectJob = scope.launch {
            runReconnectLoop(server, server.id)
        }
    }

    /**
     * Cancels any ongoing reconnection attempt.
     * Called when user selects a different server or explicitly disconnects.
     *
     * Because the entire retry loop lives in [reconnectJob], cancelling it
     * is sufficient to stop all activity -- no orphaned coroutines possible.
     */
    fun cancelReconnection() {
        if (!isReconnecting.get()) return

        Log.i(TAG, "Cancelling auto-reconnect for server: $reconnectingServerId")

        reconnectJob?.cancel()
        reconnectJob = null
        skipDelay = null
        isReconnecting.set(false)
        reconnectingServerId = null
        reconnectingServer = null
        currentAttempt.set(0)
    }

    /**
     * Checks if currently reconnecting to the given server.
     */
    fun isReconnecting(serverId: String): Boolean {
        return isReconnecting.get() && reconnectingServerId == serverId
    }

    /**
     * Checks if any reconnection is in progress.
     */
    fun isReconnecting(): Boolean = isReconnecting.get()

    /**
     * Gets the current reconnect attempt number (1-based).
     */
    fun getCurrentAttempt(): Int = currentAttempt.get()

    /**
     * Gets the server ID currently being reconnected to.
     */
    fun getReconnectingServerId(): String? = reconnectingServerId

    /**
     * Called when network becomes available.
     * If reconnecting, skips the remaining backoff delay so the next attempt
     * fires immediately. Does NOT cancel the coroutine -- it simply signals
     * the loop to wake up early.
     */
    fun onNetworkAvailable() {
        if (!isReconnecting.get()) return

        Log.i(TAG, "Network available during reconnection - triggering immediate retry")

        // Re-evaluate network state so the next attempt picks the right methods
        networkEvaluator.evaluateCurrentNetwork()

        // Complete the deferred to wake the loop from its delay
        skipDelay?.complete(Unit)
    }

    /**
     * The single coroutine that owns the entire retry lifecycle.
     *
     * Structure:
     * ```
     * for each attempt 1..MAX_ATTEMPTS:
     *     wait backoff delay (skippable via onNetworkAvailable)
     *     try all connection methods
     *     if success -> return
     * on exhaustion -> notify failure
     * ```
     *
     * Cancellation of [reconnectJob] propagates into this function via
     * [ensureActive] / [delay] / [CompletableDeferred.await], stopping
     * execution at the earliest suspension point.
     */
    private suspend fun runReconnectLoop(server: UnifiedServer, serverId: String) {
        for (attemptNumber in 1..MAX_ATTEMPTS) {
            // Update attempt counter for UI reads
            currentAttempt.set(attemptNumber)

            val delayMs = BACKOFF_DELAYS.getOrElse(attemptNumber - 1) { BACKOFF_DELAYS.last() }

            Log.i(TAG, "Scheduling reconnect attempt $attemptNumber/$MAX_ATTEMPTS in ${delayMs}ms")

            // Notify UI of upcoming attempt
            onAttempt(serverId, attemptNumber, MAX_ATTEMPTS, null)

            // Backoff delay -- cancellable via job cancellation, skippable via
            // onNetworkAvailable completing the deferred.
            val signal = CompletableDeferred<Unit>()
            skipDelay = signal
            try {
                withTimeout(delayMs) {
                    signal.await()
                    Log.d(TAG, "Backoff delay skipped by network event")
                }
            } catch (_: TimeoutCancellationException) {
                // Normal: the full delay elapsed without a skip signal
            }
            skipDelay = null

            // Check cancellation after waking from delay
            coroutineContext.ensureActive()

            // -- Attempt reconnection using all available methods --
            networkEvaluator.evaluateCurrentNetwork()
            val networkState = networkEvaluator.networkState.value
            val methods = ConnectionSelector.getPriorityOrder(networkState.transportType)

            Log.d(TAG, "Attempting reconnect with methods: ${methods.joinToString()} on ${networkState.transportType}")

            var succeeded = false

            for (method in methods) {
                coroutineContext.ensureActive()

                val selectedConnection = when (method) {
                    ConnectionType.LOCAL -> server.local?.let {
                        ConnectionSelector.SelectedConnection.Local(it.address, it.path)
                    }
                    ConnectionType.REMOTE -> server.remote?.let {
                        ConnectionSelector.SelectedConnection.Remote(it.remoteId)
                    }
                    ConnectionType.PROXY -> server.proxy?.let {
                        ConnectionSelector.SelectedConnection.Proxy(it.url, it.authToken)
                    }
                }

                if (selectedConnection == null) {
                    Log.d(TAG, "Skipping $method - not configured for this server")
                    continue
                }

                Log.d(TAG, "Trying $method connection...")
                onMethodAttempt(serverId, method)

                try {
                    val success = connectToServer(server, selectedConnection)

                    if (success) {
                        Log.i(TAG, "Reconnection successful via $method")
                        isReconnecting.set(false)
                        onSuccess(serverId)
                        reconnectingServerId = null
                        reconnectingServer = null
                        currentAttempt.set(0)
                        succeeded = true
                        break
                    } else {
                        Log.d(TAG, "$method connection failed, trying next method")
                    }
                } catch (e: CancellationException) {
                    throw e // Always propagate cancellation
                } catch (e: Exception) {
                    Log.w(TAG, "$method connection failed with exception: ${e.message}")
                    // Continue to next method
                }
            }

            if (succeeded) return

            Log.d(TAG, "All connection methods failed for attempt $attemptNumber, will retry")
        }

        // All attempts exhausted
        Log.w(TAG, "Max reconnection attempts ($MAX_ATTEMPTS) reached, giving up")
        isReconnecting.set(false)
        onFailure(serverId, "Connection lost after $MAX_ATTEMPTS reconnection attempts")
        reconnectingServerId = null
        reconnectingServer = null
    }

    /**
     * Cleans up resources when no longer needed.
     */
    fun destroy() {
        cancelReconnection()
        scope.cancel()
    }
}
