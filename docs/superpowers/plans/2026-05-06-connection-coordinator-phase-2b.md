# ConnectionCoordinator Phase 2B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the dueling-timer problem. The retry loop currently lives in `AutoReconnectManager` (driven by the Coordinator's lambdas after Phase 2A). At the same time, `SendSpinClient.attemptReconnect` runs its OWN internal reconnect loop on transport failures. Phase 2B moves the retry policy entirely into `ConnectionCoordinator`, gates SendSpinClient's self-retry off, and deletes `AutoReconnectManager`.

**Architecture after this phase:**

```
PlaybackService
   |
   v constructs
ConnectionCoordinator -- owns the only retry loop --
   |                      ^
   |                      | uses
   |                      v
   +-- public connect/cancelReconnect/onNetworkAvailable
   +-- private runReconnectLoop (ported from AutoReconnectManager)
   +-- private state: reconnectJob, reconnectingServer, currentAttempt, skipDelay
   +-- ctor lambda: connectAttempt: suspend (UnifiedServer, ConnectionType) -> Boolean
                     ^
                     | provided by PlaybackService (calls existing connectViaSelectedConnection)

SendSpinClient
   |
   +-- selfReconnectEnabled: Boolean (default true)
   |                                  set to false by PlaybackService when constructing the coordinator-driven instance
   +-- onClosed / onFailure: only call attemptReconnect when selfReconnectEnabled
```

**What stays preserved from today:**

- The 11-attempt backoff schedule (`500ms, 1s, 2s, 4s, 8s, 15s, 30s, 60s, 60s, 60s, 60s`).
- The per-attempt method-iteration (try LOCAL, REMOTE, PROXY in priority order; first success wins).
- The 2-second debounce on network-available skips.
- The 500ms minimum delay after network-triggered skips.
- Stall watchdog inside SendSpinClient (still triggers a normal `onClosed` with code 1001 -> Coordinator decides retry).

**What becomes simpler:**

- One retry loop in one class. No more dueling timers.
- AutoReconnectManager.kt file deletes. `network/` package shrinks.
- PlaybackService no longer constructs or owns AutoReconnectManager.

**Tech Stack:** Kotlin, kotlinx.coroutines, JUnit 4, MockK, kotlinx-coroutines-test.

**Reference spec:** `docs/superpowers/specs/2026-05-05-connection-coordinator-design.md` (Phase 2 row in §9 — second half).

---

## File Structure

**Modify:**
- `android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt` — add internal retry loop; replace 3 delegation lambdas with one `connectAttempt` lambda; expand state.
- `android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt` — add tests for retry policy (success on first attempt, retry on failure, network-skip, cancel, exhaustion).
- `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt` — add `selfReconnectEnabled: Boolean` field; gate `attemptReconnect()` calls on it.
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` — replace AutoReconnectManager construction with Coordinator absorbing its responsibilities; pass `connectAttempt` lambda; set `sendSpinClient.selfReconnectEnabled = false`; remove autoReconnectManager field and onDestroy cleanup.

**Delete:**
- `android/app/src/main/java/com/sendspindroid/network/AutoReconnectManager.kt` — gone.

---

## Task 1: Coordinator absorbs the retry loop

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt`
- Modify: `android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt`

This is the biggest task. Coordinator gains:

- A `connectAttempt: suspend (UnifiedServer, ConnectionType) -> Boolean` constructor lambda. Replaces the three Phase-2A delegation lambdas (`onConnectRequested`, `onCancelReconnectRequested`, `onNetworkAvailableSignaled`).
- Backoff constants and an internal CoroutineScope.
- Private state: `reconnectJob: Job?`, `reconnectingServerId: String?`, `reconnectingServer: UnifiedServer?`, `currentAttempt: AtomicInteger`, `isReconnecting: AtomicBoolean`, `skipDelay: CompletableDeferred<Unit>?`, `lastNetworkSkipNanos: Long`.
- A `_reconnectStatusFlow` MutableStateFlow that the loop updates. The constructor's `reconnectStatusFlow` Flow input becomes a backing `MutableStateFlow` owned by the Coordinator (no longer supplied externally — the Coordinator IS the producer now).

The public surface stays:
- `val sessionState: StateFlow<SessionState>` — unchanged.
- `val reconnectStatus: StateFlow<ReconnectStatus>` — backed by the Coordinator's own flow.
- `disconnect()` — unchanged (still delegates to `onDisconnectRequested` lambda).
- `connect(server: UnifiedServer)` — now starts the internal retry loop.
- `cancelReconnect()` — now cancels the internal `reconnectJob`.
- `onNetworkAvailable()` — now signals the internal `skipDelay` deferred with debounce.

**Step 1: Write failing tests**

Append the following to `android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt` (inside the existing class, before the closing brace):

```kotlin
    @Test
    fun `connect succeeds on first attempt and emits Attempting then Succeeded`() = runTest {
        val statuses = mutableListOf<ReconnectStatus>()
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, _ -> true },
            statusCollector = statuses,
        )

        coordinator.connect(makeTestServer())
        testScheduler.advanceUntilIdle()

        assertTrue("Should emit Attempting", statuses.any { it is ReconnectStatus.Attempting })
        assertTrue("Should emit Succeeded", statuses.any { it is ReconnectStatus.Succeeded })
    }

    @Test
    fun `connect retries on attempt failure and respects backoff`() = runTest {
        val attempts = mutableListOf<Pair<UnifiedServer, ConnectionType>>()
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { server, method ->
                attempts.add(server to method)
                false  // every attempt fails
            },
            statusCollector = null,
        )

        coordinator.connect(makeTestServerWithLocalOnly())
        // First attempt: 500ms backoff -> attempt
        testScheduler.advanceTimeBy(600)
        testScheduler.runCurrent()

        assertTrue("Should have attempted at least once", attempts.isNotEmpty())
    }

    @Test
    fun `cancelReconnect stops the loop and emits Idle`() = runTest {
        val statuses = mutableListOf<ReconnectStatus>()
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, _ ->
                kotlinx.coroutines.delay(10_000)  // never returns within test
                false
            },
            statusCollector = statuses,
        )

        coordinator.connect(makeTestServer())
        testScheduler.advanceTimeBy(700)  // past first backoff into the attempt
        coordinator.cancelReconnect()
        testScheduler.advanceUntilIdle()

        assertTrue("Should emit Idle on cancel", statuses.last() is ReconnectStatus.Idle)
    }

    private fun makeCoordinatorForRetryTest(
        connectAttempt: suspend (UnifiedServer, ConnectionType) -> Boolean,
        statusCollector: MutableList<ReconnectStatus>?,
    ): ConnectionCoordinator {
        val coordinator = ConnectionCoordinator(
            currentServerFlow = MutableStateFlow(null),
            sendSpinStateFlow = MutableStateFlow(TransportState.Idle),
            musicAssistantStateFlow = MutableStateFlow(TransportState.Idle),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            onDisconnectRequested = {},
            connectAttempt = connectAttempt,
        )
        if (statusCollector != null) {
            // Collect status emissions for assertion
            backgroundScope.launch {
                coordinator.reconnectStatus.collect { statusCollector.add(it) }
            }
        }
        return coordinator
    }

    private fun makeTestServer(): UnifiedServer = UnifiedServer(id = "s1", name = "Test")

    private fun makeTestServerWithLocalOnly(): UnifiedServer = UnifiedServer(
        id = "s1",
        name = "Test",
        local = LocalConnection(address = "10.0.0.1:8927", path = "/sendspin"),
    )
```

(`makeTestServer` already exists from Phase 2A — REPLACE the existing one with the version above only if the existing one matches; otherwise rename to avoid collision. `LocalConnection` and other UnifiedServer field types: read `android/app/src/main/java/com/sendspindroid/model/UnifiedServer.kt` first to confirm the constructor.)

You may also need to remove the obsolete Phase-2A tests `reconnectStatus reflects upstream flow` and `connect cancelReconnect onNetworkAvailable forward to lambdas` — they reference the old constructor signature. Update them to use the new constructor (with `connectAttempt` instead of three delegation lambdas), or delete and rewrite them in terms of the new internal-loop semantics. **Read the existing tests first; don't break them blindly.**

**Step 2: Run the tests — should fail**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.coordinator.ConnectionCoordinatorTest
```

Expected: compile failure ("no parameter named connectAttempt", and the three Phase-2A lambda params still exist but don't match).

**Step 3: Update `ConnectionCoordinator.kt`**

Replace the entire contents of `android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt` with:

```kotlin
package com.sendspindroid.coordinator

import com.sendspindroid.model.ConnectionType
import com.sendspindroid.model.UnifiedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineContext
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
 * Phase 1: combined sessionState flow + disconnect() forward.
 * Phase 2A: added reconnectStatus flow; delegated connect/cancel/network to AutoReconnectManager via lambdas.
 * Phase 2B: absorbs the retry loop entirely. AutoReconnectManager deletes after this phase.
 *
 * The loop preserves today's behavior:
 * - 11-attempt backoff schedule (500ms, 1s, 2s, 4s, 8s, 15s, 30s, 60s x 4)
 * - Per-attempt iteration over LOCAL / REMOTE / PROXY methods in priority order
 * - 2s debounce on network-availability skip
 * - 500ms minimum stabilization delay after network-triggered skip
 *
 * SendSpinClient.selfReconnectEnabled is set to false externally so it no longer
 * runs its own reconnect loop -- the Coordinator is the only retry driver.
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
        // Backoff schedule preserved from AutoReconnectManager.
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

    // Retry-loop state.
    private var reconnectJob: Job? = null
    private var reconnectingServer: UnifiedServer? = null
    private val currentAttempt = AtomicInteger(0)
    private val isReconnecting = AtomicBoolean(false)
    @Volatile private var skipDelay: CompletableDeferred<Unit>? = null
    @Volatile private var lastNetworkSkipNanos: Long = 0L

    fun disconnect() {
        onDisconnectRequested()
    }

    /**
     * Start auto-reconnection to the given server. Cancels any in-progress reconnect first.
     */
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

    /**
     * Cancel any in-progress auto-reconnection. The loop coroutine is cancelled
     * cleanly and the status flow returns to Idle.
     */
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
     * Network became available. Wakes the loop early from its current backoff.
     * Debounced to NETWORK_DEBOUNCE_MS to prevent flapping networks from burning
     * through all retry attempts.
     */
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

            // Backoff (skippable via onNetworkAvailable).
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

            // Try methods in priority order. ConnectionSelector lives in the network/
            // package today; Phase 2B keeps that dependency.
            val methods = com.sendspindroid.network.ConnectionSelector
                .getPriorityOrder(currentTransportType())
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

        // Exhausted.
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

    private fun currentTransportType(): com.sendspindroid.network.NetworkEvaluator.TransportType {
        // Phase 2B preserves AutoReconnectManager's behavior of asking ConnectionSelector
        // for a priority order based on current network. The original used
        // NetworkEvaluator(context).evaluateCurrentNetwork() — but the Coordinator
        // doesn't have a Context, so we accept "default to UNKNOWN" here and let
        // ConnectionSelector return its safe default order. PlaybackService's
        // network observation still kicks the loop via onNetworkAvailable when the
        // network state changes; that's sufficient signal for retry pacing.
        return com.sendspindroid.network.NetworkEvaluator.TransportType.UNKNOWN
    }
}
```

**Reading note for the implementer:** The `currentTransportType()` placeholder above accepts an UNKNOWN value and lets ConnectionSelector return a default priority order. If `NetworkEvaluator.TransportType` doesn't have an UNKNOWN value, use whatever default value the existing `AutoReconnectManager.runReconnectLoop` would see when `evaluateCurrentNetwork` returns nothing useful. Read `NetworkEvaluator.kt` and `ConnectionSelector.kt` to confirm. **If the network-aware priority ordering is critical for correctness in some scenarios, STOP and report — we may need PlaybackService to push the current network state into the Coordinator via a constructor input flow.**

**Step 4: Run the tests — should pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.coordinator.ConnectionCoordinatorTest
```

Expected: PASS. The previous 4 Phase-2A tests are removed/rewritten; the 3 new retry-loop tests pass.

**Step 5: Update PlaybackService for the new constructor signature**

`PlaybackService.kt`'s `coordinator = ConnectionCoordinator(...)` call drops 4 args (the three delegation lambdas plus `reconnectStatusFlow`) and adds 1 (`connectAttempt`). The replacement:

```kotlin
        coordinator = ConnectionCoordinator(
            currentServerFlow = _currentServerFlow,
            sendSpinStateFlow = sendSpinClient?.connectionState?.map { it.toTransportState() }
                ?: flowOf(TransportState.Idle),
            musicAssistantStateFlow = MusicAssistantManager.connectionState.map { it.toTransportState() },
            scope = serviceScope,
            onDisconnectRequested = { disconnectFromServer() },
            connectAttempt = { server, method ->
                val selected = when (method) {
                    com.sendspindroid.model.ConnectionType.LOCAL -> server.local?.let {
                        com.sendspindroid.network.ConnectionSelector.SelectedConnection.Local(it.address, it.path)
                    }
                    com.sendspindroid.model.ConnectionType.REMOTE -> server.remote?.let {
                        com.sendspindroid.network.ConnectionSelector.SelectedConnection.Remote(it.remoteId)
                    }
                    com.sendspindroid.model.ConnectionType.PROXY -> server.proxy?.let {
                        com.sendspindroid.network.ConnectionSelector.SelectedConnection.Proxy(it.url, it.authToken)
                    }
                } ?: return@ConnectionCoordinator false
                connectViaSelectedConnection(server, selected)
            },
        )
```

**Important**: the existing `_reconnectStatusFlow` field on `PlaybackService` is no longer used (the Coordinator now owns its own). The collector that calls `broadcastSessionExtras()` on every emission needs to be updated:

```kotlin
        // OLD:
        // serviceScope.launch { _reconnectStatusFlow.collect { broadcastSessionExtras() } }

        // NEW:
        serviceScope.launch { coordinator.reconnectStatus.collect { broadcastSessionExtras() } }
```

The `_reconnectStatusFlow` field on `PlaybackService` AND the AutoReconnectManager construction can stay for one more commit — we delete those in Task 3 — for now they're orphaned but harmless. Actually wait: AutoReconnectManager's callbacks update `_reconnectStatusFlow`, and that flow is no longer being read by anyone — fine. But AutoReconnectManager is still alive and will run its retry loop independently if anyone calls `startReconnecting`. **Stop calling it.** That means: in `connect/cancelReconnect/onNetworkAvailable` paths in PlaybackService and MainActivity, the route now goes Activity -> Service -> Coordinator (Phase 2A wiring) -> Coordinator's internal loop (Phase 2B). AutoReconnectManager's `startReconnecting` is no longer reachable. Confirm via grep that no caller invokes `autoReconnectManager.startReconnecting/cancelReconnection/onNetworkAvailable` after this commit.

In the `broadcastSessionExtras` method, the line `_reconnectStatusFlow.value` should be replaced with `coordinator.reconnectStatus.value` so the bundle reflects the Coordinator's flow.

**Step 6: Build and run all tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green.

**Step 7: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt \
        android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt \
        android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "feat(coordinator): absorb retry loop, drop AutoReconnectManager delegation

Phase 2B step 1. ConnectionCoordinator now owns the auto-reconnect
retry loop (ported from AutoReconnectManager.runReconnectLoop verbatim:
11-attempt schedule, per-attempt method iteration, network-skip
debounce). PlaybackService passes a connectAttempt lambda that wraps
its existing connectViaSelectedConnection helper.

The AutoReconnectManager instance still lives in PlaybackService but is
no longer reachable from any caller; it is removed in the next commit.
SendSpinClient still self-reconnects internally; gated off in step 2."
```

---

## Task 2: Gate `SendSpinClient.attemptReconnect` behind a flag

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt`

`SendSpinClient` runs its own reconnect loop in `attemptReconnect()` (around line 1048), called from `TransportEventListener.onClosed` and `onFailure`. With the Coordinator now owning retry, this internal loop is redundant — both fire on transport drop, racing.

We add a single boolean `selfReconnectEnabled` (default `true` for backward compat — anyone constructing SendSpinClient outside this codebase, or wizard test instances, keeps the old behavior). The Coordinator-driven instance sets it to `false`, suppressing self-retry entirely.

**Step 1: Add the field**

Find the `SendSpinClient` class field declarations (around line 200, where `_connectionState` etc. live). Add:

```kotlin
    /**
     * When false, transport drops result in a terminal Disconnected state without
     * the internal attemptReconnect loop firing. ConnectionCoordinator (Phase 2B+)
     * sets this to false and drives retries externally. Wizard test instances and
     * any pre-Coordinator code path should leave this true.
     */
    @Volatile
    var selfReconnectEnabled: Boolean = true
```

**Step 2: Gate the calls in `onClosed` and `onFailure`**

Find `TransportEventListener.onClosed` (around line 1351). The branch that decides "abnormal close, schedule reconnect" should check `selfReconnectEnabled` first:

```kotlin
        override fun onClosed(code: Int, reason: String) {
            // ... existing telemetry / logging ...
            if (code == 1000 || userInitiatedDisconnect.get() || !hasConnectionInfo()) {
                // Normal closure paths - unchanged.
            } else if (selfReconnectEnabled) {
                // Old: schedule reconnect
                attemptReconnect()
            } else {
                // New: emit terminal Disconnected; let Coordinator decide.
                _connectionState.value = ConnectionState.Disconnected
                callback?.onDisconnected(wasUserInitiated = false, wasReconnectExhausted = false)
            }
        }
```

The exact existing structure of `onClosed` may differ — read it carefully and place the `selfReconnectEnabled` gate around whichever line(s) call `attemptReconnect()`. Same for `onFailure` at around line 1396.

**Step 3: Build to confirm compile**

```bash
cd android && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. The flag defaults to true so existing behavior is preserved at this point — no caller has flipped it yet.

**Step 4: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt
git commit -m "feat(sendspin): add selfReconnectEnabled flag to gate internal retry

SendSpinClient.attemptReconnect is bypassed when selfReconnectEnabled
is false. Defaults to true so existing callers (wizard test instances,
pre-Coordinator paths) are unaffected. ConnectionCoordinator sets it
to false in the next commit, taking over retry responsibility entirely
and ending the dueling-timer problem."
```

---

## Task 3: Coordinator-driven SendSpinClient + delete AutoReconnectManager

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`
- Delete: `android/app/src/main/java/com/sendspindroid/network/AutoReconnectManager.kt`

The terminal step. PlaybackService:
- Sets `sendSpinClient.selfReconnectEnabled = false` immediately after construction.
- Removes the `autoReconnectManager` field and its construction in `onCreate`.
- Removes the `_reconnectStatusFlow` field (the Coordinator owns it now).
- Removes the AutoReconnectManager destroy call in `onDestroy`.

Then we delete the file.

**Step 1: Set `selfReconnectEnabled = false`**

Find where `sendSpinClient` is created (`initializeSendSpinClient()` around line 959). After the `sendSpinClient = SendSpinClient(...)` line, add:

```kotlin
        sendSpinClient?.selfReconnectEnabled = false
```

**Step 2: Remove AutoReconnectManager construction**

In `onCreate`, delete the entire `autoReconnectManager = AutoReconnectManager(...)` block added in Phase 2A Task 3.

**Step 3: Remove `autoReconnectManager` field and `_reconnectStatusFlow` field**

Delete:
```kotlin
    private lateinit var autoReconnectManager: AutoReconnectManager
    private val _reconnectStatusFlow = MutableStateFlow<ReconnectStatus>(ReconnectStatus.Idle)
```

The collector that calls `broadcastSessionExtras()` should already be reading from `coordinator.reconnectStatus` after Task 1; verify and adjust if not.

The `broadcastSessionExtras` method's `when (val reconnectStatus = _reconnectStatusFlow.value)` should also be `coordinator.reconnectStatus.value`. Adjust if Task 1 didn't.

**Step 4: Remove AutoReconnectManager cleanup from `onDestroy`**

Delete:
```kotlin
        if (::autoReconnectManager.isInitialized) {
            autoReconnectManager.destroy()
        }
```

**Step 5: Remove imports**

```kotlin
import com.sendspindroid.network.AutoReconnectManager  // delete
```

`com.sendspindroid.network.ConnectionSelector` import STAYS — Coordinator still uses it.

**Step 6: Delete the file**

```bash
git rm android/app/src/main/java/com/sendspindroid/network/AutoReconnectManager.kt
```

**Step 7: Build and run all tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green.

**Step 8: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "feat(playback): delete AutoReconnectManager, Coordinator owns retry

Phase 2B final step. SendSpinClient.selfReconnectEnabled is set to false
on construction, suppressing its internal attemptReconnect loop.
AutoReconnectManager.kt deletes entirely. PlaybackService no longer
holds autoReconnectManager or _reconnectStatusFlow fields -- the
Coordinator owns both retry orchestration and the status flow.

Dueling-timer problem resolved: there is now exactly one retry loop
in the codebase, owned by ConnectionCoordinator."
```

---

## Task 4: Verify Phase 2B end-to-end

- [ ] **Step 1: Full unit test suite**

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

All green, including the new ConnectionCoordinator retry tests.

- [ ] **Step 2: Release build**

```bash
cd android && ./gradlew :app:assembleRelease
```

BUILD SUCCESSFUL. R8/proguard handles the deletion cleanly.

- [ ] **Step 3: Confirm no `AutoReconnectManager` references remain**

```bash
grep -r "AutoReconnectManager" android/app/src/ || echo "(no matches — clean)"
```

Expected: no matches.

- [ ] **Step 4: Manual smoke test on device**

1. Connect to a SendSpin server with MA, normal playback.
2. Tap Disconnect, reconnect — confirm round-trip works (Phase 1+2A baseline).
3. Toggle airplane mode. Observe "Reconnecting (attempt N)" UI advance through the schedule. When network returns, reconnect completes.
4. Toggle airplane mode again, but this time leave it off long enough that the buffer drains and several attempts pass. Confirm the UI continues advancing attempt counter (1 -> 2 -> 3 -> ...), proving the loop didn't stall after attempt 1.
5. Trigger a reconnect, then while reconnecting tap a different server. Original cancels, new one starts.
6. Rotate during reconnect — same behavior as Phase 2A (still survives Activity destruction).

The behavior should be **identical to Phase 2A** from the user's perspective — Phase 2B is purely an internal architecture cleanup. Any visible behavior change indicates a regression.

---

## Self-Review Notes

- **Spec coverage:** Phase 2B completes the design's Phase 2 row in §9. The dueling-timer problem is resolved.
- **Schedule preservation:** Today's 11-attempt schedule is preserved verbatim. The design's new schedule (15 attempts SendSpin / 30 attempts MA, with per-endpoint fallback) is deferred to a separate phase.
- **Behavior preservation:** From the user's perspective, nothing changes between Phase 2A and Phase 2B. The architecture cleanup is invisible.
- **Open question — currentTransportType placeholder:** Coordinator's `runReconnectLoop` currently hardcodes `TransportType.UNKNOWN` for the priority-order lookup. The original AutoReconnectManager had access to a Context and called `NetworkEvaluator(context).evaluateCurrentNetwork()`. If hardcoding UNKNOWN causes the priority order to differ from today's behavior in a way that matters, Phase 2B Task 1 must be expanded to pass a network-state Flow into the Coordinator. Implementer should verify by reading `ConnectionSelector.getPriorityOrder` and reporting if the UNKNOWN default differs from cellular/WiFi/wired behavior in a meaningful way.
