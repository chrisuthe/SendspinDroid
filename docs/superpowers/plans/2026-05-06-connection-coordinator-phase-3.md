# ConnectionCoordinator Phase 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate all network observation into `ConnectionCoordinator`. Today there are TWO `ConnectivityManager.NetworkCallback` registrations (`PlaybackService.networkCallback` at lines 441–570 and `MainActivity.networkCallback` at lines 1230–1298) plus shared `NetworkEvaluator` state. After Phase 3 there is ONE callback owned by Coordinator, and existing consumers (`SendSpinClient`, `NsdDiscoveryManager`, `DefaultServerPinger`, `MainActivity` UI) become observers of `coordinator.networkState`.

**Side benefit:** Replaces the Phase-2B carryover where Coordinator hardcoded `TransportType.UNKNOWN` for retry priority. The new `networkState` flow has the real transport type, so `priorityMethodsForCurrentNetwork()` now picks LOCAL on WiFi, REMOTE on cellular, etc.

**Architecture after this phase:**

```
ConnectionCoordinator
   |
   +-- owns ConnectivityManager.NetworkCallback (the only one in the codebase)
   +-- exposes networkState: StateFlow<NetworkState>
   +-- exposes onConnectivityValidationLoss debouncer (3s, coroutine-based)
   +-- internal subscription to networkState for retry-loop priority + skipDelay
   |
PlaybackService
   |
   +-- constructs Coordinator with applicationContext (for ConnectivityManager)
   +-- launches collector on coordinator.networkState that dispatches:
       - sendSpinClient.setNetworkAvailable(state.isConnected)
       - sendSpinClient.onNetworkChanged() on identity-change
       - sendSpinClient.disconnectForReselection() on identity-change while connected
       - browseDiscoveryManager.refreshMulticastLockIfActive() on link-properties change
       - networkEvaluator.evaluateCurrentNetwork(...) on every event
   +-- networkCallback field DELETED. registerNetworkCallback / unregisterNetworkCallback DELETED.

MainActivity
   |
   +-- networkCallback field DELETED. registerNetworkCallback / unregisterNetworkCallback DELETED.
   +-- launches collector on coordinator.networkState that:
       - notifies defaultServerPinger.onNetworkChanged() on relevant transitions
       - shows snackbars on validation loss / network lost (was the only thing the callback did)
   +-- COMMAND_NETWORK_AVAILABLE custom command + sendCommandNetworkAvailable() helper DELETE
       (now redundant — Coordinator's own callback drives skipDelay directly)
```

**What stays the same:**

- `NetworkEvaluator` class continues to exist; called once-per-network-event from the Coordinator's collector. Its public `networkState: StateFlow<NetworkState>` becomes the same one Coordinator exposes (or Coordinator wraps it). Either is fine — the implementer can pick.
- `NsdDiscoveryManager.refreshMulticastLockIfActive` unchanged.
- `DefaultServerPinger` API unchanged; just called from a different observer.
- `SendSpinClient.setNetworkAvailable / onNetworkChanged / disconnectForReselection` APIs unchanged.

**Tech Stack:** Kotlin, kotlinx.coroutines, JUnit 4, MockK, kotlinx-coroutines-test.

**Reference spec:** `docs/superpowers/specs/2026-05-05-connection-coordinator-design.md` (Phase 3 row in §9).

---

## File Structure

**Modify:**
- `android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt` — gains `Context` param, `networkState` flow, internal `NetworkCallback`, validation-loss debouncer.
- `android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt` — add tests for network-state-driven retry priority.
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` — pass Context to Coordinator; replace networkCallback with a `coordinator.networkState` collector that dispatches side effects.
- `android/app/src/main/java/com/sendspindroid/MainActivity.kt` — delete networkCallback; observe `coordinator.networkState` for snackbars and DefaultServerPinger; delete `sendCommandNetworkAvailable` helper.

**Don't touch:**
- `network/NetworkEvaluator.kt`, `network/NetworkState.kt`, `network/DefaultServerPinger.kt`, `discovery/NsdDiscoveryManager.kt`, `sendspin/SendSpinClient.kt` — their public APIs are unchanged; only call sites move.

---

## Task 1: Coordinator scaffolding for network ownership

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt`
- Modify: `android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

This task adds the API surface for Coordinator-owned network observation but does NOT yet register a `ConnectivityManager.NetworkCallback`. Both observers (PlaybackService's and MainActivity's) continue working unchanged. Coordinator gains:

- `context: Context` constructor param.
- `networkState: StateFlow<NetworkState>` exposed (initially backed by a stub MutableStateFlow with a default `NetworkState.disconnected()` value).
- Coordinator's internal `priorityMethodsForCurrentNetwork()` now reads from `networkState.value.transportType` instead of hardcoding `UNKNOWN`. Since the stub flow stays at default for this task (nobody updates it yet), the behavior is functionally equivalent to UNKNOWN until Task 2 wires up real callbacks.

**Step 1: Read the existing types**

Run:
```bash
grep -n "data class NetworkState\|enum class TransportType\|fun disconnected\|companion object" android/app/src/main/java/com/sendspindroid/network/NetworkState.kt
```

Confirm `NetworkState` has a default-constructor or factory for "disconnected/unknown" state. If there's no factory like `NetworkState.disconnected()`, find the simplest way to construct one (e.g., `NetworkState(connected = false, transportType = TransportType.UNKNOWN, ...)`). Use whatever the existing patterns are.

**Step 2: Update `ConnectionCoordinator` constructor**

Add `context: Context` as the LAST constructor param (don't insert it in the middle and break call sites):

```kotlin
class ConnectionCoordinator(
    currentServerFlow: Flow<UnifiedServer?>,
    sendSpinStateFlow: Flow<TransportState>,
    musicAssistantStateFlow: Flow<TransportState>,
    private val scope: CoroutineScope,
    private val onDisconnectRequested: () -> Unit,
    private val connectAttempt: suspend (UnifiedServer, ConnectionType) -> Boolean,
    private val context: android.content.Context,
)
```

Add the network-state field:

```kotlin
    private val _networkState = MutableStateFlow<NetworkState>(
        NetworkState(
            connected = false,
            transportType = TransportType.UNKNOWN,
            // ...other fields with safe defaults; match NetworkState's actual constructor
        )
    )
    val networkState: StateFlow<NetworkState> = _networkState
```

Adjust the `NetworkState(...)` constructor arguments to match the actual data class.

**Step 3: Use `networkState` in `priorityMethodsForCurrentNetwork`**

Replace the existing `priorityMethodsForCurrentNetwork()` and `defaultTransportType()` methods (which return UNKNOWN) with:

```kotlin
    private fun priorityMethodsForCurrentNetwork(): List<ConnectionType> {
        return com.sendspindroid.network.ConnectionSelector
            .getPriorityOrder(_networkState.value.transportType)
    }
```

Delete the `defaultTransportType()` helper.

**Step 4: Update Coordinator unit tests for the new constructor**

The existing tests construct Coordinator without a Context. Pass a mock:

```kotlin
import io.mockk.mockk
import android.content.Context

// In each test or in makeCoordinatorForRetryTest:
context = mockk<Context>(relaxed = true),
```

Pass `context = mockk(relaxed = true)` in every test that constructs `ConnectionCoordinator(...)`. The `relaxed = true` means MockK returns sensible defaults for any method call — sufficient since this task doesn't yet register any callback.

**Step 5: Update PlaybackService construction call**

`PlaybackService.kt`'s `coordinator = ConnectionCoordinator(...)` block needs the new param:

```kotlin
        coordinator = ConnectionCoordinator(
            // ... existing args ...
            context = applicationContext,
        )
```

Use `applicationContext` (NOT `this`) — Coordinator's lifetime is the Service's lifetime, and `applicationContext` avoids accidentally leaking the Service if Coordinator is ever held longer.

**Step 6: Build and run tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green. The Coordinator tests use the mock Context.

**Step 7: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt \
        android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt \
        android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "feat(coordinator): add Context param + networkState flow scaffolding

Phase 3 step 1. ConnectionCoordinator gains a Context constructor
parameter (passed by PlaybackService as applicationContext) and a
networkState: StateFlow<NetworkState> backed by a private MutableStateFlow.
The flow defaults to a disconnected/UNKNOWN state and is not yet driven by
any callback -- that wiring lands in step 2. priorityMethodsForCurrentNetwork
now reads from _networkState.value, so it will respond correctly once the
flow starts emitting in step 2."
```

---

## Task 2: Coordinator owns the NetworkCallback; PlaybackService observes

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

The substantive change: move the entire ConnectivityManager.NetworkCallback from `PlaybackService` into `ConnectionCoordinator`. The Coordinator drives `_networkState` from its own callback. PlaybackService's networkCallback field, registration, and unregistration delete. PlaybackService gains a coroutine collector that observes `coordinator.networkState` and dispatches the side effects that the original callback was doing inline.

This is the largest single task in Phase 3. Plan to budget 2-3 hours.

**Step 1: Add the NetworkCallback inside ConnectionCoordinator**

Add to ConnectionCoordinator (after the existing constructor body, before `companion object`):

```kotlin
    private val networkEvaluator = com.sendspindroid.network.NetworkEvaluator(context)
    private val connectivityManager: android.net.ConnectivityManager? =
        context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager

    private var lastNetworkId: Long? = null
    private var lastLinkAddresses: List<android.net.LinkAddress>? = null
    @Volatile private var lastValidatedState: Boolean? = null
    private var validationLossDebouncerJob: kotlinx.coroutines.Job? = null

    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            networkEvaluator.evaluateCurrentNetwork(network)
            _networkState.value = networkEvaluator.networkState.value
            // Skip-backoff signal for our retry loop:
            triggerNetworkAvailableSkip()

            val networkId = network.networkHandle
            if (lastNetworkId != null && lastNetworkId != networkId) {
                // Identity change: clear baseline so next link-properties callback re-evaluates.
                lastLinkAddresses = null
            }
            lastNetworkId = networkId
        }

        override fun onLost(network: android.net.Network) {
            networkEvaluator.evaluateCurrentNetwork(null)
            _networkState.value = networkEvaluator.networkState.value
            cancelValidationLossDebounce()

            // If no other network active, we're truly disconnected.
            val activeNetwork = connectivityManager?.activeNetwork
            if (activeNetwork == null) {
                lastNetworkId = null
                lastLinkAddresses = null
            }
        }

        override fun onCapabilitiesChanged(
            network: android.net.Network,
            capabilities: android.net.NetworkCapabilities,
        ) {
            networkEvaluator.evaluateCurrentNetwork(network)
            _networkState.value = networkEvaluator.networkState.value

            val validated = capabilities.hasCapability(
                android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
            val previousValidated = lastValidatedState
            lastValidatedState = validated

            if (previousValidated == true && !validated) {
                // True->false: schedule the 3s debounce.
                scheduleValidationLossDebounce()
            } else if (previousValidated == false && validated) {
                // False->true: cancel pending debounce.
                cancelValidationLossDebounce()
            }
        }

        override fun onLinkPropertiesChanged(
            network: android.net.Network,
            linkProperties: android.net.LinkProperties,
        ) {
            // Only act on the active network.
            if (connectivityManager?.activeNetwork != network) return

            val newAddresses = linkProperties.linkAddresses
            val prev = lastLinkAddresses
            val changed = prev == null || prev.size != newAddresses.size ||
                prev.toSet() != newAddresses.toSet()
            if (changed) {
                lastLinkAddresses = newAddresses.toList()
                // Re-emit networkState (consumers like PlaybackService observe and dispatch
                // SendSpinClient.onNetworkChanged() + multicast lock refresh).
                _networkState.value = networkEvaluator.networkState.value
            }
        }
    }

    init {
        registerNetworkCallback()
    }

    fun close() {
        unregisterNetworkCallback()
        cancelValidationLossDebounce()
    }

    private fun registerNetworkCallback() {
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            // Best-effort; some devices reject. Network changes still trigger updates via
            // periodic NetworkEvaluator polling if we add one — for now, accept the gap.
            android.util.Log.w("ConnectionCoordinator", "registerNetworkCallback failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // Tolerate "not registered" exceptions during teardown.
        }
    }

    private fun triggerNetworkAvailableSkip() {
        if (!isReconnecting.get()) return
        val now = System.nanoTime()
        val elapsedMs = (now - lastNetworkSkipNanos) / 1_000_000
        if (elapsedMs < NETWORK_DEBOUNCE_MS) return
        lastNetworkSkipNanos = now
        skipDelay?.complete(Unit)
    }

    private fun scheduleValidationLossDebounce() {
        cancelValidationLossDebounce()
        validationLossDebouncerJob = scope.launch {
            kotlinx.coroutines.delay(VALIDATION_LOSS_DEBOUNCE_MS)
            // After 3s with no internet validation, mark networkState as effectively disconnected.
            // Down-stream observers (PlaybackService) will pause SendSpinClient via setNetworkAvailable(false).
            val current = _networkState.value
            _networkState.value = current.copy(
                // Adapt to NetworkState's actual fields. The intent: "we have a network but no internet."
                connected = false,
            )
        }
    }

    private fun cancelValidationLossDebounce() {
        validationLossDebouncerJob?.cancel()
        validationLossDebouncerJob = null
    }

    companion object {
        // ... existing BACKOFF_DELAYS / MAX_ATTEMPTS / NETWORK_DEBOUNCE_MS / MIN_DELAY_AFTER_NETWORK_SKIP_MS ...
        private const val VALIDATION_LOSS_DEBOUNCE_MS = 3_000L
    }
```

**Important adaptation note:** The `_networkState.value = current.copy(connected = false)` call in `scheduleValidationLossDebounce` is a placeholder — `NetworkState`'s actual `copy` semantics depend on its real fields. Read `NetworkState.kt` and adapt: the goal is "publish a state that downstream observers will treat as 'pause SendSpinClient.'" The exact field changes depend on the data class.

The existing `onNetworkAvailable()` public method on Coordinator becomes redundant (the internal callback does the same thing). Keep it as a public API for now — Task 3 deletes the COMMAND_NETWORK_AVAILABLE custom command path that calls it, and Task 4 verifies. After Task 3 lands, this public method has no callers; we can leave it as a no-op API for a future cleanup, or delete it now. Recommend leaving it for safety in this task.

**Step 2: Wire `close()` into PlaybackService.onDestroy**

In PlaybackService's `onDestroy()`, after the existing teardown, add:
```kotlin
        if (::coordinator.isInitialized) coordinator.close()
```

Place it BEFORE `serviceScope.cancel()` so the Coordinator's coroutines have a chance to run their `unregisterNetworkCallback` cleanup before the scope is torn down.

**Step 3: Delete PlaybackService.networkCallback and its registration**

In `PlaybackService.kt`:
- Delete the entire `networkCallback` field (lines ~441–570).
- Delete `registerNetworkCallback()` and `unregisterNetworkCallback()` methods.
- Delete the call to `registerNetworkCallback()` in `onCreate()`.
- Delete the call to `unregisterNetworkCallback()` in `onDestroy()`.
- Delete the `lastNetworkId`, `lastLinkAddresses`, `lastValidatedState`, `validationLossRunnable`, `validationLossDebounceMs` fields if they're no longer used elsewhere (Coordinator now owns them).
- Delete the `connectivityManager` private field if it's no longer used elsewhere.
- Delete the `validationLossDebounceMs` constant from companion object if present.

**Step 4: Add a `coordinator.networkState` collector in PlaybackService.onCreate**

After the existing coordinator-related collector(s), add a new one that dispatches the 5 side effects that the deleted networkCallback was doing:

```kotlin
        var lastObservedNetworkId: Long? = null
        var lastObservedLinkAddresses: List<android.net.LinkAddress>? = null

        serviceScope.launch {
            coordinator.networkState.collect { state ->
                // 1. Setting network available/unavailable on SendSpinClient.
                sendSpinClient?.setNetworkAvailable(state.connected)

                // 2. Identity change handling (replaces the lastNetworkId comparison
                //    that lived in networkCallback.onAvailable):
                //    NetworkState's representation of "current network identity" is
                //    needed here. Inspect NetworkState and pull whatever signals
                //    network identity (likely transportType + a network handle if it
                //    carries one). For Phase 3 simplicity: detect identity change by
                //    transport-type change; full network-handle tracking can be a
                //    later refinement.
                // ...

                // 3. Link-properties change -> SendSpinClient.onNetworkChanged() +
                //    multicast lock refresh. Detected by comparing addresses field
                //    on NetworkState (if present) or via a separate "linkAddresses"
                //    flow Coordinator could expose.

                // 4. browseDiscoveryManager.refreshMulticastLockIfActive() on link
                //    properties change.

                // 5. NetworkEvaluator update (Coordinator already does this internally
                //    in its callback; this comment is for documentation).
            }
        }
```

**The implementer must adapt the body** based on what fields NetworkState actually has. The original PlaybackService.networkCallback's identity-change detection used `network.networkHandle` (a `Long` from the framework Network object). NetworkState may or may not preserve this through its evaluation. Options:

A. Add a `networkId: Long?` field to NetworkState and have the Coordinator's callback set it. Cleanest.

B. Have Coordinator expose a separate `Flow<NetworkEvent>` (sealed class with `Available(networkId)`, `Lost`, `LinkAddressesChanged(addresses)`, `ValidationLost`, etc.) alongside `networkState`. Each downstream observer subscribes to whichever signal it cares about.

C. Inside PlaybackService's collector, derive the side-effect triggers from successive networkState emissions: if `state.transportType` changes, treat as identity change. If `state.linkAddresses` (assuming it's exposed) changes, treat as link change. Less precise than the original but adequate.

**Recommend (B)** — explicit event flow. Adapt as needed; the implementer should pick the cleanest option given what NetworkState actually contains. **STOP and report NEEDS_CONTEXT** if none of these options work cleanly given the existing types.

**Step 5: Build and run tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Coordinator's existing 5 tests still pass. Build green.

**Step 6: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt \
        android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "feat(coordinator): own the NetworkCallback, PlaybackService becomes observer

Phase 3 step 2. ConnectionCoordinator now owns the only
ConnectivityManager.NetworkCallback in the codebase. PlaybackService's
networkCallback field, registerNetworkCallback, and unregisterNetworkCallback
all delete; PlaybackService dispatches the side effects (setNetworkAvailable,
onNetworkChanged, disconnectForReselection, multicast-lock refresh) by
observing coordinator.networkState.

The 3-second validation-loss debounce moves from a Handler-based
postDelayed to a coroutine Job. MainActivity's networkCallback still
exists (covers UI snackbars and DefaultServerPinger); Task 3 removes it."
```

---

## Task 3: Delete MainActivity's NetworkCallback and the redundant custom command

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/MainActivity.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

After Task 2, MainActivity's networkCallback no longer needs to send `COMMAND_NETWORK_AVAILABLE` (the Coordinator's own callback drives `skipDelay`), and its remaining concerns (snackbars, DefaultServerPinger) can become observers of `coordinator.networkState`. After this task, MainActivity has zero ConnectivityManager registrations.

**Step 1: Delete MainActivity.networkCallback field, registration, unregistration**

In `MainActivity.kt`:
- Delete the `networkCallback` field declaration (line ~210).
- Delete `registerNetworkCallback()` method body (lines ~1230–1298).
- Delete `unregisterNetworkCallback()` (lines ~1303–1312).
- Delete the call to `registerNetworkCallback()` from `onStart()` (line ~1173).
- Delete the call to `unregisterNetworkCallback()` from `onStop()` (line ~1181).
- Delete `lastActivityValidatedState` field (line ~222) — no longer used.

**Step 2: Add coordinator.networkState collector**

In MainActivity's `onCreate` or `onStart` (whichever matches the existing lifecycle pattern for other coordinator observations):

```kotlin
        lifecycleScope.launch {
            coordinator.networkState.collectLatest { state ->
                // DefaultServerPinger ping on every change.
                defaultServerPinger?.onNetworkChanged()

                // Snackbar on validation loss (only if we're connected/connecting).
                if (state.previousWasValidated && !state.isValidated) {
                    if (connectionState is AppConnectionState.Connected ||
                        connectionState is AppConnectionState.Connecting) {
                        showSnackbar(getString(R.string.network_no_internet))
                    }
                }

                // Snackbar on full network loss.
                if (!state.connected && previousState?.connected == true) {
                    if (connectionState is AppConnectionState.Connected ||
                        connectionState is AppConnectionState.Connecting) {
                        showSnackbar(getString(R.string.network_lost))
                    }
                }
            }
        }
```

The above is a sketch — the actual `state.previousWasValidated` and `state.isValidated` field accesses depend on NetworkState's real shape. The implementer needs to adapt: the snackbar-on-validation-loss requires comparing successive emissions, which `collectLatest` doesn't make easy. Two cleaner patterns:

A. Maintain a `var previousNetworkState: NetworkState? = null` field in MainActivity, update it inside the collector, compare current vs previous.

B. Use a `.scan(initial) { acc, value -> ... }` operator on the flow to pair successive states.

**Recommend (A)** — simpler.

**The implementer should also access the snackbar string resources** that the original networkCallback used (`R.string.network_no_internet`, `R.string.network_lost`, or whatever the actual resource IDs are — find them via grep on the deleted lines 1262 and 1278).

**Step 3: Delete `sendCommandNetworkAvailable()` helper and `COMMAND_NETWORK_AVAILABLE` plumbing**

In `MainActivity.kt`:
- Delete `sendCommandNetworkAvailable()` (lines ~2957–2959).

In `PlaybackService.kt`:
- Delete the `COMMAND_NETWORK_AVAILABLE` constant from companion object.
- Delete the `COMMAND_NETWORK_AVAILABLE` branch in `onCustomAction` (lines ~3136–3139).
- Delete the `COMMAND_NETWORK_AVAILABLE` registration in `onConnect` (look around line ~3000 where Phase 2A added `commandBuilder.add(SessionCommand(COMMAND_NETWORK_AVAILABLE, Bundle.EMPTY))` or similar).

**Step 4: Build and run tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green.

**Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/MainActivity.kt \
        android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "feat(activity): delete networkCallback, observe coordinator.networkState

Phase 3 step 3. MainActivity no longer registers a
ConnectivityManager.NetworkCallback. UI concerns previously driven
by it (DefaultServerPinger.onNetworkChanged, no-internet/network-lost
snackbars) now derive from coordinator.networkState observation.

The COMMAND_NETWORK_AVAILABLE custom command plumbing is also removed
-- it was only used by MainActivity's deleted callback and is redundant
now that ConnectionCoordinator drives skipDelay from its own callback.

Result: exactly one ConnectivityManager.NetworkCallback in the codebase,
owned by ConnectionCoordinator. The 'single observer' goal of the
ConnectionCoordinator design is achieved."
```

---

## Task 4: Verify Phase 3 end-to-end

- [ ] **Step 1: Full unit test suite + release build**

```bash
cd android && ./gradlew :app:testDebugUnitTest :app:assembleRelease
```

All green.

- [ ] **Step 2: Confirm exactly one NetworkCallback in the codebase**

```bash
grep -r "registerNetworkCallback" android/app/src/main/ | grep -v "\.kt:.*//" | grep -v "private fun"
```

Should show exactly one call: inside `ConnectionCoordinator`.

```bash
grep -r ": ConnectivityManager.NetworkCallback" android/app/src/main/
```

Should show exactly one anonymous-object expression: inside `ConnectionCoordinator`.

```bash
grep -r "COMMAND_NETWORK_AVAILABLE\|sendCommandNetworkAvailable" android/app/src/main/ | grep -v "\.kt:.*//" || echo "(none — clean)"
```

Should show nothing — the redundant custom-command path is gone.

- [ ] **Step 3: Manual smoke test on device**

The behavior should be identical to Phase 2B. Anything visibly different is a regression.

1. Connect to a SendSpin server, normal playback.
2. Toggle airplane mode briefly — observe Reconnecting UI, then resume.
3. Toggle airplane mode for 30+ seconds — observe attempt counter advance through the schedule (1 → 2 → 3 → ...). Confirm reconnect resumes when network returns.
4. Walk out of WiFi range while connected (or simulate by disabling WiFi on the device while keeping cellular up) — confirm the loop tries REMOTE/PROXY methods and either succeeds via cellular or eventually reaches Failed.
5. Connect via cellular, then re-enable WiFi — confirm the connection survives the transport identity change without prompting for re-login (this is what the Phase 5 work will fully fix; for Phase 3 we just want no NEW regressions).
6. Rotation during reconnect — survives (Phase 2A's fix should still work).
7. Cancel during reconnect — works (Phase 2A's behavior).
8. Snackbar messages — when WiFi drops to "validated=false" (associated but no internet), a snackbar appears after the 3s debounce. When network is fully lost while connected, "Network connection lost" snackbar appears.

- [ ] **Step 4: Confirm no behavior regressions in beta channel**

Phase 3 is supposed to be invisible to users. If beta testers report anything changed (snackbar timing, reconnect counter behavior, mDNS discovery health), investigate before Phase 4.

---

## Self-Review Notes

- **Spec coverage:** Phase 3 satisfies the "single observer" goal in the design's §9 row 3. The Phase-2B carryover (hardcoded UNKNOWN priority) is also fixed — Coordinator's retry loop now sees real transport type via `_networkState`.
- **Risk surfaces:**
  - The 3-second validation-loss debounce moves from `Handler.postDelayed` to a coroutine `Job` in `Coordinator.scheduleValidationLossDebounce`. Cancellation semantics are different — verify in Step 2's manual test that quickly toggling validation loss / restored doesn't leave a debounce job leaking.
  - `coordinator.close()` ordering in `onDestroy` matters: must run BEFORE `serviceScope.cancel()` so `unregisterNetworkCallback` can complete.
  - The `connectivityManager?.activeNetwork` query in `onLost` and `onLinkPropertiesChanged` requires the system service. On rare devices this can fail; the existing code catches; preserve that behavior.
  - Identity-change detection inside the Coordinator uses `network.networkHandle` (Long) for the comparison. Confirm this is preserved through `NetworkState`'s representation (or use the recommended Option B: a separate `Flow<NetworkEvent>`).
- **Test coverage:** Coordinator tests don't yet exercise the new `_networkState` flow — adding a unit test for "network-state-driven priority order" would be a small follow-up. Not blocking Phase 3 since the integration is observable end-to-end via manual smoke test.
