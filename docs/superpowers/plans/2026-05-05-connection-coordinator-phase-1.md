# ConnectionCoordinator Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `ConnectionCoordinator` as a thin adapter over today's `SendSpinClient`, `MusicAssistantManager`, and `AutoReconnectManager`. Expose the new `SessionState` flow. Route exactly one caller (the disconnect path) through the Coordinator as a smoke test of the API surface. No user-visible behavior change.

**Architecture:** A new `coordinator` package contains the `ConnectionCoordinator` class plus three small data types (`SessionState`, `TransportState`, `FailureReason`). The Coordinator is constructed inside `PlaybackService.onCreate` with references to the existing `SendSpinClient` and `MusicAssistantManager` instances. Its `sessionState: StateFlow<SessionState>` is derived from those existing flows via `combine`. Its `disconnect()` method delegates to `PlaybackService.disconnectFromServer()`. The `COMMAND_DISCONNECT` branch in `PlaybackService.onCustomAction` switches to call `coordinator.disconnect()`.

**Tech Stack:** Kotlin, kotlinx.coroutines (`StateFlow`, `combine`), JUnit 4, MockK 1.13.16, kotlinx-coroutines-test 1.10.2.

**Reference spec:** `docs/superpowers/specs/2026-05-05-connection-coordinator-design.md`

---

## File Structure

**Create:**
- `android/app/src/main/java/com/sendspindroid/coordinator/TransportState.kt` — `TransportState` and `FailureReason` sealed classes
- `android/app/src/main/java/com/sendspindroid/coordinator/SessionState.kt` — `SessionState` data class
- `android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt` — `ConnectionCoordinator` class
- `android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt` — unit tests

**Modify:**
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` — instantiate Coordinator in `onCreate`, dispose in `onDestroy`, route `COMMAND_DISCONNECT` through `coordinator.disconnect()`

---

## Task 1: Define `TransportState` and `FailureReason`

**Files:**
- Create: `android/app/src/main/java/com/sendspindroid/coordinator/TransportState.kt`

These types are pure data — no logic — so the test is just a sanity check that the discriminated union works as intended (`when` exhaustiveness, `Failed` carries a reason).

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sendspindroid/coordinator/TransportStateTest.kt`:

```kotlin
package com.sendspindroid.coordinator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportStateTest {
    @Test
    fun `Failed carries a FailureReason`() {
        val state: TransportState = TransportState.Failed(FailureReason.TransientNetwork)
        assertTrue(state is TransportState.Failed)
        assertEquals(FailureReason.TransientNetwork, (state as TransportState.Failed).reason)
    }

    @Test
    fun `when expression is exhaustive across all states`() {
        val states: List<TransportState> = listOf(
            TransportState.Idle,
            TransportState.Connecting,
            TransportState.Ready,
            TransportState.Failed(FailureReason.AuthRejected),
        )
        val labels = states.map {
            when (it) {
                TransportState.Idle -> "idle"
                TransportState.Connecting -> "connecting"
                TransportState.Ready -> "ready"
                is TransportState.Failed -> "failed"
            }
        }
        assertEquals(listOf("idle", "connecting", "ready", "failed"), labels)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.coordinator.TransportStateTest`

Expected: FAIL with "unresolved reference: TransportState".

- [ ] **Step 3: Implement the types**

Create `android/app/src/main/java/com/sendspindroid/coordinator/TransportState.kt`:

```kotlin
package com.sendspindroid.coordinator

/**
 * Lifecycle of a single transport (SendSpin or MusicAssistant).
 *
 * The transport reports state changes; it does not decide retry. The
 * ConnectionCoordinator owns retry decisions based on Failed.reason.
 */
sealed class TransportState {
    object Idle : TransportState()
    object Connecting : TransportState()
    object Ready : TransportState()
    data class Failed(val reason: FailureReason) : TransportState()
}

/**
 * Why a transport ended up in TransportState.Failed.
 *
 * The Coordinator inspects this to decide retry/fallback/token-clear policy.
 * AuthRejected is the only reason that clears a stored Music Assistant token,
 * and by construction it requires a completed transport handshake.
 */
sealed class FailureReason {
    object TransientNetwork : FailureReason()
    object HandshakeFailed : FailureReason()
    object AuthRejected : FailureReason()
    object ProtocolError : FailureReason()
    object Exhausted : FailureReason()
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.coordinator.TransportStateTest`

Expected: PASS, both tests green.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/coordinator/TransportState.kt \
        android/app/src/test/java/com/sendspindroid/coordinator/TransportStateTest.kt
git commit -m "feat(coordinator): add TransportState and FailureReason types

Phase 1 of ConnectionCoordinator design.
See docs/superpowers/specs/2026-05-05-connection-coordinator-design.md"
```

---

## Task 2: Define `SessionState`

**Files:**
- Create: `android/app/src/main/java/com/sendspindroid/coordinator/SessionState.kt`

`SessionState` is a flat record exposing the active server plus per-transport state. UI consumers derive their own roll-ups from it. No logic in this file beyond the data class.

- [ ] **Step 1: Write the failing test**

Add to `android/app/src/test/java/com/sendspindroid/coordinator/SessionStateTest.kt`:

```kotlin
package com.sendspindroid.coordinator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStateTest {
    @Test
    fun `default SessionState has no server and both transports Idle`() {
        val state = SessionState()
        assertNull(state.server)
        assertEquals(TransportState.Idle, state.sendSpin)
        assertEquals(TransportState.Idle, state.musicAssistant)
    }

    @Test
    fun `SessionState holds independent per-transport states`() {
        val state = SessionState(
            server = null,
            sendSpin = TransportState.Ready,
            musicAssistant = TransportState.Failed(FailureReason.TransientNetwork),
        )
        assertEquals(TransportState.Ready, state.sendSpin)
        assertEquals(
            TransportState.Failed(FailureReason.TransientNetwork),
            state.musicAssistant
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.coordinator.SessionStateTest`

Expected: FAIL with "unresolved reference: SessionState".

- [ ] **Step 3: Implement the type**

Create `android/app/src/main/java/com/sendspindroid/coordinator/SessionState.kt`:

```kotlin
package com.sendspindroid.coordinator

import com.sendspindroid.model.UnifiedServer

/**
 * Top-level published state of the connection session.
 *
 * UI consumers observe ConnectionCoordinator.sessionState and derive whatever
 * roll-up they need. The matrix of (sendSpin, musicAssistant) state combinations
 * is too combinatorial to enumerate; this flat record exposes exactly what is true.
 */
data class SessionState(
    val server: UnifiedServer? = null,
    val sendSpin: TransportState = TransportState.Idle,
    val musicAssistant: TransportState = TransportState.Idle,
)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.coordinator.SessionStateTest`

Expected: PASS, both tests green.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/coordinator/SessionState.kt \
        android/app/src/test/java/com/sendspindroid/coordinator/SessionStateTest.kt
git commit -m "feat(coordinator): add SessionState data class"
```

---

## Task 3: Create `ConnectionCoordinator` skeleton with derived `sessionState`

**Files:**
- Create: `android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt`
- Create: `android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt`

The Coordinator's first responsibility is exposing a single `sessionState` flow that combines today's per-component state. In Phase 1 it does *only* this and `disconnect()`. Everything else stays in PlaybackService.

The Coordinator takes its dependencies as constructor parameters of type `Flow<...>` (for the state inputs) and a single lambda (for the disconnect action). This avoids depending on the concrete `SendSpinClient` and `MusicAssistantManager` classes in the unit-test path, so we can test the Coordinator with pure Kotlin flows and no MockK on Android-coupled types.

- [ ] **Step 1: Write the failing test for sessionState combination**

Create `android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt`:

```kotlin
package com.sendspindroid.coordinator

import com.sendspindroid.model.UnifiedServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorTest {

    @Test
    fun `sessionState combines current server, sendSpin state, and ma state`() = runTest {
        val server = MutableStateFlow<UnifiedServer?>(null)
        val sendSpin = MutableStateFlow<TransportState>(TransportState.Idle)
        val ma = MutableStateFlow<TransportState>(TransportState.Idle)

        val coordinator = ConnectionCoordinator(
            currentServerFlow = server,
            sendSpinStateFlow = sendSpin,
            musicAssistantStateFlow = ma,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            onDisconnectRequested = {},
        )

        // Initial state
        assertEquals(SessionState(), coordinator.sessionState.first())

        // Transitions on each input flow propagate
        sendSpin.value = TransportState.Ready
        ma.value = TransportState.Connecting
        testScheduler.runCurrent()

        val combined = coordinator.sessionState.first()
        assertEquals(TransportState.Ready, combined.sendSpin)
        assertEquals(TransportState.Connecting, combined.musicAssistant)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.coordinator.ConnectionCoordinatorTest`

Expected: FAIL with "unresolved reference: ConnectionCoordinator".

- [ ] **Step 3: Implement the Coordinator**

Create `android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt`:

```kotlin
package com.sendspindroid.coordinator

import com.sendspindroid.model.UnifiedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Single authority for "what server is active, and which of its transports are up."
 *
 * Phase 1: Thin adapter that combines today's per-component state into one
 * SessionState flow and forwards a single disconnect entry point. Internals
 * (retry, fallback, network observation) still live in SendSpinClient,
 * MusicAssistantManager, AutoReconnectManager, and the per-component network
 * callbacks. Subsequent phases migrate those responsibilities here.
 *
 * See docs/superpowers/specs/2026-05-05-connection-coordinator-design.md
 */
class ConnectionCoordinator(
    currentServerFlow: Flow<UnifiedServer?>,
    sendSpinStateFlow: Flow<TransportState>,
    musicAssistantStateFlow: Flow<TransportState>,
    scope: CoroutineScope,
    private val onDisconnectRequested: () -> Unit,
) {
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

    /**
     * User-initiated disconnect. Forwards to PlaybackService.disconnectFromServer().
     */
    fun disconnect() {
        onDisconnectRequested()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.coordinator.ConnectionCoordinatorTest`

Expected: PASS.

- [ ] **Step 5: Add a test that disconnect() forwards to the lambda**

Append to `ConnectionCoordinatorTest.kt`:

```kotlin
    @Test
    fun `disconnect forwards to onDisconnectRequested lambda`() = runTest {
        var called = 0
        val coordinator = ConnectionCoordinator(
            currentServerFlow = MutableStateFlow(null),
            sendSpinStateFlow = MutableStateFlow(TransportState.Idle),
            musicAssistantStateFlow = MutableStateFlow(TransportState.Idle),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            onDisconnectRequested = { called++ },
        )

        coordinator.disconnect()
        coordinator.disconnect()

        assertEquals(2, called)
    }
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.coordinator.ConnectionCoordinatorTest`

Expected: PASS, both tests green.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt \
        android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt
git commit -m "feat(coordinator): add ConnectionCoordinator skeleton with sessionState combine"
```

---

## Task 4: Map existing state into the Coordinator's input flows

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

Today the relevant state lives in three places:
- `SendSpinClient` exposes `connectionState: StateFlow<SendSpinClient.ConnectionState>` (the existing sealed class with `Disconnected/Connecting/Connected/Error`).
- `MusicAssistantManager` exposes `connectionState: StateFlow<MaConnectionState>`.
- The active server is tracked indirectly via `PlaybackService.currentServerId: String?` plus `UnifiedServerRepository.getServer(id)`.

To feed the Coordinator we map each into the Phase-1 abstractions. The mapping is intentionally lossy — for Phase 1 we only need the four target states. Refinements (carrying `Reconnecting` through, classifying failures) come in later phases.

This task adds *only* the mapping helpers and the input flows on PlaybackService — it does not yet construct the Coordinator. Constructing the Coordinator and wiring `COMMAND_DISCONNECT` through it are the next two tasks, kept separate so each commit is independently revertable.

- [ ] **Step 1: Read the current state-flow shapes you'll be mapping**

Run: `grep -n "_connectionState\|val connectionState" android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistantManager.kt`

Confirm both classes expose a public `val connectionState: StateFlow<...>`. If either is private-only, expose it (single-line change: add `val connectionState: StateFlow<...> = _connectionState.asStateFlow()`).

- [ ] **Step 2: Add a private mapping helper inside `PlaybackService`**

Add to `PlaybackService.kt` (companion-object level or top-of-file private extension functions; pick whichever matches existing patterns nearby):

```kotlin
private fun com.sendspindroid.sendspin.SendSpinClient.ConnectionState.toTransportState():
        com.sendspindroid.coordinator.TransportState = when (this) {
    is com.sendspindroid.sendspin.SendSpinClient.ConnectionState.Disconnected ->
        com.sendspindroid.coordinator.TransportState.Idle
    is com.sendspindroid.sendspin.SendSpinClient.ConnectionState.Connecting ->
        com.sendspindroid.coordinator.TransportState.Connecting
    is com.sendspindroid.sendspin.SendSpinClient.ConnectionState.Connected ->
        com.sendspindroid.coordinator.TransportState.Ready
    is com.sendspindroid.sendspin.SendSpinClient.ConnectionState.Error ->
        com.sendspindroid.coordinator.TransportState.Failed(
            com.sendspindroid.coordinator.FailureReason.TransientNetwork
        )
}
```

Add the equivalent mapping for the MA state. The exact `MaConnectionState` shape will dictate the `when` arms — read `MusicAssistantManager.kt` (around line 88, per the design's reference) to enumerate them. Map `Connected -> Ready`, `Unavailable/Error -> Idle/Failed` as appropriate. Document each arm with a one-line comment if the mapping is non-obvious.

- [ ] **Step 3: Add the `currentServerFlow` to `PlaybackService`**

Find the existing `currentServerId: String?` field (around `PlaybackService.kt:146`). Add alongside it:

```kotlin
private val _currentServerFlow =
    kotlinx.coroutines.flow.MutableStateFlow<com.sendspindroid.model.UnifiedServer?>(null)
```

Find `setCurrentServer(serverId: String?, connectionMode: ConnectionMode)` (around line 2104). Inside it, after the existing field assignment, add:

```kotlin
_currentServerFlow.value = serverId?.let {
    com.sendspindroid.UnifiedServerRepository.getServer(it)
}
```

(Use the repository accessor that already exists; if its name differs, match the existing call site that loads `UnifiedServer` by id elsewhere in `PlaybackService`.)

- [ ] **Step 4: Build to verify nothing breaks**

Run: `cd android && ./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL. No new warnings related to the additions.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "feat(playback): add coordinator-shaped input flows to PlaybackService

Maps SendSpinClient.ConnectionState and MusicAssistantManager state into
TransportState. Tracks active UnifiedServer in a StateFlow. Coordinator
construction follows in the next commit."
```

---

## Task 5: Construct the Coordinator inside `PlaybackService`

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

This task wires the Coordinator into the service lifecycle but does not yet route any caller through it. After this commit, `coordinator.sessionState` is observable but unused.

- [ ] **Step 1: Add the field declaration**

Near the top of the `PlaybackService` class, alongside the other field declarations:

```kotlin
private lateinit var coordinator: com.sendspindroid.coordinator.ConnectionCoordinator
```

- [ ] **Step 2: Construct in `onCreate`**

Find `onCreate()` in `PlaybackService.kt`. After `sendSpinClient` and `musicAssistantManager` have been instantiated (the existing instantiations should be near `onCreate` start; locate them via grep `sendSpinClient =`), add:

```kotlin
coordinator = com.sendspindroid.coordinator.ConnectionCoordinator(
    currentServerFlow = _currentServerFlow,
    sendSpinStateFlow = sendSpinClient.connectionState.map { it.toTransportState() },
    musicAssistantStateFlow = musicAssistantManager.connectionState.map { it.toTransportState() },
    scope = serviceScope,
    onDisconnectRequested = { disconnectFromServer() },
)
```

The required imports are:
```kotlin
import kotlinx.coroutines.flow.map
```

`serviceScope` is the existing service-lifetime CoroutineScope used elsewhere in `PlaybackService` (look for the existing field; common name is `serviceScope` or `mainScope`). Use whatever matches.

- [ ] **Step 3: Build to verify it compiles**

Run: `cd android && ./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify Coordinator initializes at runtime (smoke test, optional but recommended)**

Install the debug build on a device, launch the app, watch logcat for any crashes during service creation:

Run: `adb logcat -d -t 200 | grep -E 'AndroidRuntime|FATAL|sendspindroid'`

Expected: no FATAL or AndroidRuntime errors related to `ConnectionCoordinator`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "feat(playback): construct ConnectionCoordinator in PlaybackService onCreate

Coordinator is wired up but no callers route through it yet. Its sessionState
flow is computed from existing SendSpinClient and MusicAssistantManager
state but is not yet observed by any consumer."
```

---

## Task 6: Route `COMMAND_DISCONNECT` through `coordinator.disconnect()`

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

This is the smoke test that the adapter actually works end-to-end. The `COMMAND_DISCONNECT` MediaSession custom command is the entry point invoked when the user taps the Disconnect menu item in `MainActivity`. It currently calls `disconnectFromServer()` directly. After this task it calls `coordinator.disconnect()`, which forwards to the same method via the lambda. Behavior is identical; the call goes through one extra hop.

If this task introduces a regression, every other path remains direct — so the blast radius is bounded.

- [ ] **Step 1: Locate the COMMAND_DISCONNECT handler**

Run: `grep -n "COMMAND_DISCONNECT" android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

Note the line where `disconnectFromServer()` is called from inside the `COMMAND_DISCONNECT` branch of `onCustomAction`.

- [ ] **Step 2: Replace the direct call with the coordinator call**

In `onCustomAction`, change:
```kotlin
COMMAND_DISCONNECT -> {
    disconnectFromServer()
    // ... any existing post-disconnect logic stays
}
```
to:
```kotlin
COMMAND_DISCONNECT -> {
    coordinator.disconnect()
    // ... any existing post-disconnect logic stays
}
```

Do not change anything else in the branch. Preserve all logging, broadcast emission, and follow-up calls exactly as they were.

- [ ] **Step 3: Build to verify it compiles**

Run: `cd android && ./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual smoke test — verify disconnect still works**

Install the debug build. Connect to a known-working SendSpin server. Tap the Disconnect option in the overflow menu. Confirm:

1. The connection drops cleanly (logcat shows `WebSocketTransport: Closing WebSocket: code=1000 reason=User disconnect`).
2. The UI returns to the server-list screen.
3. `MusicAssistantManager: Server disconnected` appears in logcat as before.
4. Reconnecting to the same server works as expected.

If any of these regress, revert with `git revert` and investigate before proceeding.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "feat(playback): route COMMAND_DISCONNECT through ConnectionCoordinator

Phase 1 smoke test of the adapter pattern. The Coordinator's disconnect()
forwards to PlaybackService.disconnectFromServer() via a lambda, so behavior
is unchanged. Future phases route additional callers through the Coordinator
and migrate the underlying logic out of PlaybackService."
```

---

## Task 7: Verify and document Phase 1 completion

**Files:**
- Modify: `docs/superpowers/specs/2026-05-05-connection-coordinator-design.md` (status note only, optional)

- [ ] **Step 1: Run the full unit-test suite**

Run: `cd android && ./gradlew :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL, all tests pass. Specifically the three new test classes:
- `TransportStateTest` (2 tests)
- `SessionStateTest` (2 tests)
- `ConnectionCoordinatorTest` (2 tests)

If any pre-existing test regresses, that's a bug introduced in Phase 1 — fix before continuing.

- [ ] **Step 2: Build the release variant**

Run: `cd android && ./gradlew :app:assembleRelease`

Expected: BUILD SUCCESSFUL. Confirms no proguard/R8 issues with the new package.

- [ ] **Step 3: Manual end-to-end verification**

On a physical device:

1. Connect to a SendSpin server with Music Assistant configured. Confirm normal playback works.
2. Tap Disconnect. Confirm clean disconnect (logs show code 1000).
3. Reconnect. Confirm normal playback resumes.
4. Background the app, foreground it. Confirm state is preserved.
5. Toggle airplane mode briefly. Confirm reconnect behavior is unchanged from before this Phase. (If it changed, that's a regression.)

- [ ] **Step 4: Confirm no behavior regression in beta channel before proceeding to Phase 2**

Phase 1 is intentionally invisible to users. If beta testers report any change in behavior (timing of reconnects, login prompts, error messages), investigate before proceeding to Phase 2.

---

## Self-Review Notes

- Spec coverage: Phase 1 implements only the type definitions and the adapter shell. It does not implement: retry policy (Phase 2), single network observer (Phase 3), `SendSpin`/`MusicAssistant` rename (Phase 4/5), wizard test path (Phase 6), or state-of-truth cleanup (Phase 7). This is intentional and matches the design's phasing.
- Type consistency: `TransportState`, `FailureReason`, `SessionState` names match the design doc exactly. `ConnectionCoordinator` constructor parameters match the names used across all tasks.
- No placeholders: every code block contains complete code. No "TBD" markers. The one looseness is the MA state mapping (Task 4 Step 2) which depends on enumerating `MaConnectionState` arms — this is expected exploration work the implementer will do, not a placeholder.
- Granularity: each task is a single commit. Each step is 2-5 minutes of focused work.
