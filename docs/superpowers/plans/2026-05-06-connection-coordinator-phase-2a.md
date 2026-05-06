# ConnectionCoordinator Phase 2A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `AutoReconnectManager` construction from `MainActivity` (Activity scope) to `PlaybackService` (Service scope), route all reconnect-related entry points through `ConnectionCoordinator`, and replace its UI-update callback constructor with a `ReconnectStatus` StateFlow that the UI observes. AutoReconnectManager's retry logic itself is unchanged. The Coordinator's `connect()/cancelReconnect()/onNetworkAvailable()` methods delegate to it via lambdas, in the same adapter pattern Phase 1 used for `disconnect()`.

**Architecture:** New sealed `ReconnectStatus` (Idle / Attempting / Succeeded / Failed) lives in the `coordinator` package. Coordinator gains a `reconnectStatus: StateFlow<ReconnectStatus>` input flow plus three new methods that delegate via constructor lambdas. PlaybackService instantiates `AutoReconnectManager` and translates its 5 callbacks into emissions on a `MutableStateFlow<ReconnectStatus>` that's passed to Coordinator. Three new MediaSession custom commands (`COMMAND_CONNECT_AUTO`, `COMMAND_CANCEL_RECONNECT`, `COMMAND_NETWORK_AVAILABLE`) bridge MainActivity → Coordinator. MainActivity drops its own `AutoReconnectManager` field, drops its `initializeAutoReconnectManager()` method, sends commands instead of calling the manager directly, and observes `coordinator.reconnectStatus` for UI state.

**Side benefit:** Fixes a real existing bug — auto-reconnect no longer dies when `MainActivity` is destroyed by rotation or backgrounding, because the manager now lives in the foreground service.

**Tech Stack:** Kotlin, kotlinx.coroutines (`StateFlow`, `combine`), JUnit 4, MockK, kotlinx-coroutines-test, AndroidX Media3 (MediaSession custom commands).

**Reference spec:** `docs/superpowers/specs/2026-05-05-connection-coordinator-design.md`. Phase 2A is the first half of the design's "Phase 2" row in §9; the duel-killing follow-up is a separate plan ("Phase 2B").

---

## File Structure

**Create:**
- `android/app/src/main/java/com/sendspindroid/coordinator/ReconnectStatus.kt` — sealed class
- `android/app/src/test/java/com/sendspindroid/coordinator/ReconnectStatusTest.kt` — exhaustiveness test

**Modify:**
- `android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt` — add `reconnectStatus` flow and three delegating methods
- `android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt` — add tests for the new surface
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` — instantiate AutoReconnectManager, translate its callbacks, expose three MediaSession commands, pass new dependencies into Coordinator
- `android/app/src/main/java/com/sendspindroid/MainActivity.kt` — remove AutoReconnectManager construction and direct calls, send commands, observe `coordinator.reconnectStatus` flow

---

## Task 1: Add `ReconnectStatus` type

**Files:**
- Create: `android/app/src/main/java/com/sendspindroid/coordinator/ReconnectStatus.kt`
- Create: `android/app/src/test/java/com/sendspindroid/coordinator/ReconnectStatusTest.kt`

`ReconnectStatus` mirrors the four signals AutoReconnectManager emits today via callbacks. No logic in this file beyond the data classes.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sendspindroid/coordinator/ReconnectStatusTest.kt`:

```kotlin
package com.sendspindroid.coordinator

import com.sendspindroid.model.ConnectionType
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectStatusTest {
    @Test
    fun `Attempting carries server id, attempt counters, and method`() {
        val s: ReconnectStatus = ReconnectStatus.Attempting(
            serverId = "s1",
            attempt = 3,
            maxAttempts = 11,
            method = ConnectionType.LOCAL,
        )
        assertEquals("s1", (s as ReconnectStatus.Attempting).serverId)
        assertEquals(3, s.attempt)
        assertEquals(11, s.maxAttempts)
        assertEquals(ConnectionType.LOCAL, s.method)
    }

    @Test
    fun `Failed and Succeeded carry server id`() {
        val f: ReconnectStatus = ReconnectStatus.Failed("s1", "boom")
        val ok: ReconnectStatus = ReconnectStatus.Succeeded("s1")
        assertEquals("s1", (f as ReconnectStatus.Failed).serverId)
        assertEquals("boom", f.error)
        assertEquals("s1", (ok as ReconnectStatus.Succeeded).serverId)
    }

    @Test
    fun `when expression is exhaustive`() {
        val cases: List<ReconnectStatus> = listOf(
            ReconnectStatus.Idle,
            ReconnectStatus.Attempting("s", 1, 1, null),
            ReconnectStatus.Succeeded("s"),
            ReconnectStatus.Failed("s", "e"),
        )
        val labels = cases.map {
            when (it) {
                ReconnectStatus.Idle -> "idle"
                is ReconnectStatus.Attempting -> "attempting"
                is ReconnectStatus.Succeeded -> "succeeded"
                is ReconnectStatus.Failed -> "failed"
            }
        }
        assertEquals(listOf("idle", "attempting", "succeeded", "failed"), labels)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.coordinator.ReconnectStatusTest`

Expected: FAIL with "unresolved reference: ReconnectStatus".

- [ ] **Step 3: Implement the type**

Create `android/app/src/main/java/com/sendspindroid/coordinator/ReconnectStatus.kt`:

```kotlin
package com.sendspindroid.coordinator

import com.sendspindroid.model.ConnectionType

/**
 * Status of an in-progress (or recently completed) auto-reconnection attempt.
 *
 * Phase 2A: emitted by PlaybackService translating AutoReconnectManager's
 * five callbacks into a single StateFlow. UI observers read this flow via
 * ConnectionCoordinator.reconnectStatus to render the "Reconnecting..."
 * overlay, success snackbar, or failure dialog.
 *
 * The retry logic that drives these transitions is unchanged from today —
 * Phase 2B is where Coordinator takes ownership of that loop and the
 * dueling-timer problem is finally killed.
 */
sealed class ReconnectStatus {
    object Idle : ReconnectStatus()

    data class Attempting(
        val serverId: String,
        val attempt: Int,
        val maxAttempts: Int,
        val method: ConnectionType?,
    ) : ReconnectStatus()

    data class Succeeded(val serverId: String) : ReconnectStatus()

    data class Failed(val serverId: String, val error: String) : ReconnectStatus()
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.coordinator.ReconnectStatusTest`

Expected: PASS, all three tests green.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/coordinator/ReconnectStatus.kt \
        android/app/src/test/java/com/sendspindroid/coordinator/ReconnectStatusTest.kt
git commit -m "feat(coordinator): add ReconnectStatus sealed class

Phase 2A of ConnectionCoordinator design. Mirrors today's
AutoReconnectManager callback signatures (Idle / Attempting /
Succeeded / Failed) so the UI can observe a single flow instead
of receiving five separate callbacks."
```

---

## Task 2: Extend `ConnectionCoordinator` with the reconnect surface

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt`
- Modify: `android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt`

The Coordinator gains:
- A `reconnectStatusFlow: Flow<ReconnectStatus>` constructor input, exposed as `val reconnectStatus: StateFlow<ReconnectStatus>` via `stateIn`.
- Three `private val` lambda properties: `onConnectRequested`, `onCancelReconnectRequested`, `onNetworkAvailableSignaled`.
- Three public methods: `connect(server: UnifiedServer)`, `cancelReconnect()`, `onNetworkAvailable()`. Each forwards to the corresponding lambda.

- [ ] **Step 1: Write failing tests**

Append to `android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt` (inside the existing `class ConnectionCoordinatorTest { ... }`, before the closing brace):

```kotlin
    @Test
    fun `reconnectStatus reflects upstream flow`() = runTest {
        val recon = MutableStateFlow<ReconnectStatus>(ReconnectStatus.Idle)
        val coordinator = ConnectionCoordinator(
            currentServerFlow = MutableStateFlow(null),
            sendSpinStateFlow = MutableStateFlow(TransportState.Idle),
            musicAssistantStateFlow = MutableStateFlow(TransportState.Idle),
            reconnectStatusFlow = recon,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            onDisconnectRequested = {},
            onConnectRequested = {},
            onCancelReconnectRequested = {},
            onNetworkAvailableSignaled = {},
        )

        assertEquals(ReconnectStatus.Idle, coordinator.reconnectStatus.first())

        recon.value = ReconnectStatus.Attempting("s1", 1, 11, null)
        testScheduler.runCurrent()

        val v = coordinator.reconnectStatus.first()
        assertTrue(v is ReconnectStatus.Attempting)
        assertEquals("s1", (v as ReconnectStatus.Attempting).serverId)
    }

    @Test
    fun `connect cancelReconnect onNetworkAvailable forward to lambdas`() = runTest {
        var connectCalls = mutableListOf<UnifiedServer>()
        var cancelCount = 0
        var networkCount = 0

        val coordinator = ConnectionCoordinator(
            currentServerFlow = MutableStateFlow(null),
            sendSpinStateFlow = MutableStateFlow(TransportState.Idle),
            musicAssistantStateFlow = MutableStateFlow(TransportState.Idle),
            reconnectStatusFlow = MutableStateFlow(ReconnectStatus.Idle),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            onDisconnectRequested = {},
            onConnectRequested = { connectCalls.add(it) },
            onCancelReconnectRequested = { cancelCount++ },
            onNetworkAvailableSignaled = { networkCount++ },
        )

        val server = UnifiedServer(id = "s1", name = "test")
        coordinator.connect(server)
        coordinator.cancelReconnect()
        coordinator.cancelReconnect()
        coordinator.onNetworkAvailable()

        assertEquals(listOf(server), connectCalls)
        assertEquals(2, cancelCount)
        assertEquals(1, networkCount)
    }
```

You will need additional imports at the top of the test file:
```kotlin
import com.sendspindroid.model.UnifiedServer
import org.junit.Assert.assertTrue
```

(`UnifiedServer` is a data class — pass the minimum required fields. If the constructor requires non-defaulted fields beyond `id` and `name`, fill them with sensible defaults like `local = null, remote = null, proxy = null, isMusicAssistant = false, isDefaultServer = false`. Read the `UnifiedServer` definition in `android/app/src/main/java/com/sendspindroid/model/UnifiedServer.kt` to confirm the constructor shape.)

- [ ] **Step 2: Run the tests — they should fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.coordinator.ConnectionCoordinatorTest`

Expected: FAIL — the new constructor parameters don't exist yet.

- [ ] **Step 3: Update `ConnectionCoordinator`**

Replace the contents of `android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt` with:

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
 * Phase 1: combined sessionState flow + disconnect() forward.
 * Phase 2A: adds reconnectStatus flow + connect / cancelReconnect / onNetworkAvailable
 *           forwards. Underlying retry logic still lives in AutoReconnectManager
 *           and SendSpinClient — Phase 2B kills the dueling-timer problem.
 *
 * See docs/superpowers/specs/2026-05-05-connection-coordinator-design.md
 */
class ConnectionCoordinator(
    currentServerFlow: Flow<UnifiedServer?>,
    sendSpinStateFlow: Flow<TransportState>,
    musicAssistantStateFlow: Flow<TransportState>,
    reconnectStatusFlow: Flow<ReconnectStatus>,
    scope: CoroutineScope,
    private val onDisconnectRequested: () -> Unit,
    private val onConnectRequested: (UnifiedServer) -> Unit,
    private val onCancelReconnectRequested: () -> Unit,
    private val onNetworkAvailableSignaled: () -> Unit,
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

    val reconnectStatus: StateFlow<ReconnectStatus> = reconnectStatusFlow.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ReconnectStatus.Idle,
    )

    /** User-initiated disconnect. Forwards to PlaybackService.disconnectFromServer(). */
    fun disconnect() {
        onDisconnectRequested()
    }

    /** Request auto-reconnection to the given server. */
    fun connect(server: UnifiedServer) {
        onConnectRequested(server)
    }

    /** Cancel any in-progress auto-reconnection. */
    fun cancelReconnect() {
        onCancelReconnectRequested()
    }

    /** Network became available — wake any backoff-delayed reconnect. */
    fun onNetworkAvailable() {
        onNetworkAvailableSignaled()
    }
}
```

- [ ] **Step 4: Run the tests — they should pass now**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.coordinator.ConnectionCoordinatorTest`

Expected: PASS, four tests total green (two from Phase 1 + two new).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt \
        android/app/src/test/java/com/sendspindroid/coordinator/ConnectionCoordinatorTest.kt
git commit -m "feat(coordinator): expose reconnect surface (status flow + 3 forwards)

Phase 2A. Coordinator gains reconnectStatus: StateFlow<ReconnectStatus>
plus connect(server) / cancelReconnect() / onNetworkAvailable() methods
that forward to constructor-supplied lambdas. The PlaybackService wiring
to AutoReconnectManager follows in the next commit."
```

---

## Task 3: Construct `AutoReconnectManager` in `PlaybackService`

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

This task moves AutoReconnectManager into Service scope. Its 5 UI callbacks become emissions onto a `MutableStateFlow<ReconnectStatus>` that's passed into the Coordinator. The `connectToServer` suspend lambda calls PlaybackService's existing connect methods directly.

The Coordinator's existing 5-arg constructor becomes 9-arg (4 new params from Task 2). All call sites of `ConnectionCoordinator(...)` must be updated.

The implementer should NOT delete the existing `MainActivity.initializeAutoReconnectManager()` method yet — that happens in Task 5 once the Coordinator path is wired. For Task 3, both paths exist but only the Service-scoped instance is used by Coordinator.

- [ ] **Step 1: Add the import for `AutoReconnectManager`, `ReconnectStatus`, and `ConnectionType` to `PlaybackService.kt`**

Add to the existing `com.sendspindroid.*` import group (alphabetical position):
```kotlin
import com.sendspindroid.coordinator.ReconnectStatus
import com.sendspindroid.model.ConnectionType
import com.sendspindroid.network.AutoReconnectManager
import com.sendspindroid.network.ConnectionSelector
```

(`ConnectionSelector` is needed because AutoReconnectManager's `connectToServer` lambda receives a `ConnectionSelector.SelectedConnection`.)

- [ ] **Step 2: Add the `MutableStateFlow<ReconnectStatus>` field**

Find the existing `_currentServerFlow` declaration (around line 154 after Phase 1, may have shifted slightly). Immediately below it, add:

```kotlin
    private val _reconnectStatusFlow = MutableStateFlow<ReconnectStatus>(ReconnectStatus.Idle)
```

- [ ] **Step 3: Add the `autoReconnectManager` field**

Below the `coordinator` field declaration (`private lateinit var coordinator: ConnectionCoordinator`):

```kotlin
    private lateinit var autoReconnectManager: AutoReconnectManager
```

- [ ] **Step 4: Construct AutoReconnectManager in `onCreate` BEFORE the Coordinator is constructed**

Find the Coordinator construction in `onCreate` (around line 832 after Phase 1). Immediately BEFORE the `coordinator = ConnectionCoordinator(...)` call, add:

```kotlin
        autoReconnectManager = AutoReconnectManager(
            context = this,
            onAttempt = { serverId, attempt, maxAttempts, method ->
                _reconnectStatusFlow.value = ReconnectStatus.Attempting(
                    serverId = serverId,
                    attempt = attempt,
                    maxAttempts = maxAttempts,
                    method = method,
                )
            },
            onMethodAttempt = { serverId, method ->
                // Update the in-progress Attempting record's `method` field.
                val current = _reconnectStatusFlow.value
                if (current is ReconnectStatus.Attempting && current.serverId == serverId) {
                    _reconnectStatusFlow.value = current.copy(method = method)
                }
            },
            onSuccess = { serverId ->
                _reconnectStatusFlow.value = ReconnectStatus.Succeeded(serverId)
            },
            onFailure = { serverId, error ->
                _reconnectStatusFlow.value = ReconnectStatus.Failed(serverId, error)
            },
            connectToServer = { server, selectedConnection ->
                connectViaSelectedConnection(server, selectedConnection)
            },
        )
```

- [ ] **Step 5: Add the `connectViaSelectedConnection` helper to `PlaybackService`**

The existing `connectToServer / connectToRemoteServer / connectToProxyServer` methods are fire-and-forget — they don't return a Boolean indicating success. AutoReconnectManager needs a suspend function that returns `Boolean`. Add this helper as a private suspend method on `PlaybackService` (near the existing `connectToServer` definition around line 1959):

```kotlin
    /**
     * Used by AutoReconnectManager to attempt a connection and wait for the
     * outcome. Returns true if the connection reaches Connected within
     * CONNECT_TIMEOUT_MS, false otherwise.
     *
     * Reads SendSpinClient.connectionState because that is the authoritative
     * signal for "did the connection succeed?". The fire-and-forget connectXxx
     * methods kick off the attempt; this method awaits the result.
     */
    private suspend fun connectViaSelectedConnection(
        server: com.sendspindroid.model.UnifiedServer,
        selectedConnection: ConnectionSelector.SelectedConnection,
    ): Boolean {
        // Set the active server first so subsequent state observation has context.
        setCurrentServer(server.id, when (selectedConnection) {
            is ConnectionSelector.SelectedConnection.Local -> ConnectionMode.LOCAL
            is ConnectionSelector.SelectedConnection.Remote -> ConnectionMode.REMOTE
            is ConnectionSelector.SelectedConnection.Proxy -> ConnectionMode.PROXY
        })

        when (selectedConnection) {
            is ConnectionSelector.SelectedConnection.Local ->
                connectToServer(selectedConnection.address, selectedConnection.path)
            is ConnectionSelector.SelectedConnection.Remote ->
                connectToRemoteServer(selectedConnection.remoteId)
            is ConnectionSelector.SelectedConnection.Proxy ->
                connectToProxyServer(selectedConnection.url, selectedConnection.authToken)
        }

        // Wait for the SendSpinClient to reach a terminal state.
        val client = sendSpinClient ?: return false
        return kotlinx.coroutines.withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            client.connectionState
                .first {
                    it is com.sendspindroid.sendspin.SendSpinClient.ConnectionState.Connected ||
                    it is com.sendspindroid.sendspin.SendSpinClient.ConnectionState.Error
                }
                .let { it is com.sendspindroid.sendspin.SendSpinClient.ConnectionState.Connected }
        } ?: false
    }

    companion object {
        // ... existing constants ...
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }
```

(If a `companion object` already exists in `PlaybackService` — and it does, around line 588 — add `private const val CONNECT_TIMEOUT_MS = 15_000L` to it rather than declaring a new companion. Don't duplicate the companion block.)

You will need imports for:
```kotlin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
```

- [ ] **Step 6: Update the `coordinator = ConnectionCoordinator(...)` call**

Add the four new constructor arguments. After the existing `onDisconnectRequested = ...` line, add:

```kotlin
            reconnectStatusFlow = _reconnectStatusFlow,
            // ... after existing onDisconnectRequested
            onConnectRequested = { server -> autoReconnectManager.startReconnecting(server) },
            onCancelReconnectRequested = { autoReconnectManager.cancelReconnection() },
            onNetworkAvailableSignaled = { autoReconnectManager.onNetworkAvailable() },
```

The full call should now be:

```kotlin
        coordinator = ConnectionCoordinator(
            currentServerFlow = _currentServerFlow,
            sendSpinStateFlow = sendSpinClient?.connectionState?.map { it.toTransportState() }
                ?: flowOf(TransportState.Idle),
            musicAssistantStateFlow = MusicAssistantManager.connectionState.map { it.toTransportState() },
            reconnectStatusFlow = _reconnectStatusFlow,
            scope = serviceScope,
            onDisconnectRequested = { disconnectFromServer() },
            onConnectRequested = { server -> autoReconnectManager.startReconnecting(server) },
            onCancelReconnectRequested = { autoReconnectManager.cancelReconnection() },
            onNetworkAvailableSignaled = { autoReconnectManager.onNetworkAvailable() },
        )
```

- [ ] **Step 7: Cancel `autoReconnectManager` in `onDestroy`**

Find the existing `onDestroy()` method. Add a call to `autoReconnectManager.destroy()` BEFORE the existing `serviceScope.cancel()` line. Look for an existing pattern (e.g., where SendSpinClient is destroyed) and place the line nearby:

```kotlin
        if (::autoReconnectManager.isInitialized) {
            autoReconnectManager.destroy()
        }
```

The `isInitialized` check guards against race conditions where `onDestroy` fires before `onCreate` finishes.

- [ ] **Step 8: Build to verify it compiles**

Run: `cd android && ./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "feat(playback): construct AutoReconnectManager in service scope

Phase 2A. AutoReconnectManager is now owned by PlaybackService instead
of MainActivity. Its five callbacks emit to a MutableStateFlow<ReconnectStatus>
that the Coordinator exposes to UI consumers. The connectToServer suspend
lambda awaits SendSpinClient.connectionState transitions to a terminal
state with a 15s timeout. MainActivity still constructs its own instance
in this commit; that instance and its initializeAutoReconnectManager
method are removed in Task 5 once the command path is wired."
```

---

## Task 4: Add MediaSession custom commands for connect / cancel / network

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

Three new custom commands route MainActivity → PlaybackService → Coordinator. They follow the existing pattern (look at how `COMMAND_DISCONNECT` is handled in `onCustomAction` for reference).

- [ ] **Step 1: Add command constants**

Find the existing `COMMAND_*` constants in `PlaybackService.companion object` (around line 589). Add three new constants alphabetically with the others:

```kotlin
        const val COMMAND_CANCEL_RECONNECT = "com.sendspindroid.CANCEL_RECONNECT"
        const val COMMAND_CONNECT_AUTO = "com.sendspindroid.CONNECT_AUTO"
        const val COMMAND_NETWORK_AVAILABLE = "com.sendspindroid.NETWORK_AVAILABLE"
```

Also add a key for the server-id args bundle, alphabetically near any existing `EXTRA_*` constants:

```kotlin
        const val EXTRA_SERVER_ID = "server_id"
```

(If `EXTRA_SERVER_ID` already exists in the companion — `BootReceiver` already uses one — verify it has the same value `"server_id"` and reuse it instead of duplicating.)

- [ ] **Step 2: Add command handlers in `onCustomAction`**

Find the existing `COMMAND_DISCONNECT` branch in `onCustomAction` (around line 2992 after Phase 1). Add the three new branches alongside it. Pattern:

```kotlin
            COMMAND_CONNECT_AUTO -> {
                val serverId = args.getString(EXTRA_SERVER_ID)
                if (serverId != null) {
                    val server = UnifiedServerRepository.getServer(serverId)
                    if (server != null) {
                        coordinator.connect(server)
                    } else {
                        Log.w(TAG, "COMMAND_CONNECT_AUTO: unknown server id $serverId")
                    }
                } else {
                    Log.w(TAG, "COMMAND_CONNECT_AUTO: missing $EXTRA_SERVER_ID")
                }
            }
            COMMAND_CANCEL_RECONNECT -> {
                coordinator.cancelReconnect()
            }
            COMMAND_NETWORK_AVAILABLE -> {
                coordinator.onNetworkAvailable()
            }
```

Match the existing pattern around how the branch returns its `ListenableFuture<SessionResult>` — copy from `COMMAND_DISCONNECT`'s post-call return value.

- [ ] **Step 3: Build to verify**

Run: `cd android && ./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "feat(playback): add CONNECT_AUTO/CANCEL_RECONNECT/NETWORK_AVAILABLE commands

Phase 2A. Three new MediaSession custom commands forward to
ConnectionCoordinator. COMMAND_CONNECT_AUTO carries the server id
in its args bundle; the others take no payload. MainActivity wiring
to these commands is in the next commit."
```

---

## Task 5: Migrate `MainActivity` from direct `AutoReconnectManager` calls to Coordinator commands

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/MainActivity.kt`

This is the biggest single edit of Phase 2A. MainActivity has 12 references to `autoReconnectManager` today (line numbers approximate, as Phase 1 did not modify this file):

| Line(s) | Reference | Replace with |
|---|---|---|
| 81 | `import com.sendspindroid.network.AutoReconnectManager` | Remove (no longer needed). Add `import com.sendspindroid.coordinator.ReconnectStatus` instead. |
| 194 | `private var autoReconnectManager: AutoReconnectManager? = null` | Remove. |
| 542 | `initializeAutoReconnectManager()` call in `onCreate` | Remove. |
| 1236-1238 | `if (autoReconnectManager?.isReconnecting() == true) autoReconnectManager?.onNetworkAvailable()` | Replace with: send `COMMAND_NETWORK_AVAILABLE`. (No need to gate on isReconnecting — PlaybackService's coordinator no-ops when not reconnecting.) |
| 1487 | `if (autoReconnectManager?.isReconnecting() == true)` | Replace with reading `mediaController?.let { ... }`'s observed reconnect status, or simpler: track a local `Boolean` updated by the reconnectStatus flow observation. See Step 6 below for the pattern. |
| 1617-1675 | `initializeAutoReconnectManager()` method | Remove the entire method. |
| 1698 | `autoReconnectManager?.isReconnecting() != true` | Same treatment as line 1487. |
| 1979 | `autoReconnectManager?.cancelReconnection()` | Send `COMMAND_CANCEL_RECONNECT`. |
| 2067 | `autoReconnectManager?.startReconnecting(server)` | Send `COMMAND_CONNECT_AUTO` with `EXTRA_SERVER_ID`. |
| 2456 | `val reconnectingId = autoReconnectManager?.getReconnectingServerId()` | Read from observed `ReconnectStatus.Attempting.serverId` (track in a local field updated by the flow observation). |
| 2459 | `autoReconnectManager?.cancelReconnection()` | Send `COMMAND_CANCEL_RECONNECT`. |
| 3546-3548 | `autoReconnectManager?.destroy() ; autoReconnectManager = null` | Remove (PlaybackService owns lifecycle now). |

`AutoReconnectManager.MAX_ATTEMPTS` reference at line 1665 inside `initializeAutoReconnectManager` — that whole method goes away, so the reference goes too.

- [ ] **Step 1: Add the new imports**

In MainActivity.kt at the top, add (alphabetically into the `com.sendspindroid.*` group):
```kotlin
import com.sendspindroid.coordinator.ReconnectStatus
```

Remove:
```kotlin
import com.sendspindroid.network.AutoReconnectManager
```

- [ ] **Step 2: Add a local mirror of reconnectStatus**

Find an appropriate field-declaration location in MainActivity (e.g., near where `autoReconnectManager` was declared). Add:

```kotlin
    private var lastReconnectStatus: ReconnectStatus = ReconnectStatus.Idle
```

This is updated by a flow observation collector you add in Step 5; existing code that needs to ask "are we reconnecting?" reads `lastReconnectStatus is ReconnectStatus.Attempting`.

- [ ] **Step 3: Remove the `autoReconnectManager` field and the `initializeAutoReconnectManager()` method**

Delete the field declaration and the entire method body (including its KDoc comment). Delete the call to `initializeAutoReconnectManager()` in `onCreate`. Delete the cleanup lines in `onDestroy`.

- [ ] **Step 4: Add helper methods to send the three new commands**

In MainActivity, near where other `mediaController?.sendCustomCommand(...)` invocations are made, add:

```kotlin
    private fun sendCommandConnectAuto(serverId: String) {
        val controller = mediaController ?: return
        val args = Bundle().apply {
            putString(PlaybackService.EXTRA_SERVER_ID, serverId)
        }
        val command = SessionCommand(PlaybackService.COMMAND_CONNECT_AUTO, args)
        controller.sendCustomCommand(command, args)
    }

    private fun sendCommandCancelReconnect() {
        val controller = mediaController ?: return
        val command = SessionCommand(PlaybackService.COMMAND_CANCEL_RECONNECT, Bundle.EMPTY)
        controller.sendCustomCommand(command, Bundle.EMPTY)
    }

    private fun sendCommandNetworkAvailable() {
        val controller = mediaController ?: return
        val command = SessionCommand(PlaybackService.COMMAND_NETWORK_AVAILABLE, Bundle.EMPTY)
        controller.sendCustomCommand(command, Bundle.EMPTY)
    }
```

(Match the exact pattern used by an existing helper such as how `COMMAND_DISCONNECT` is sent. Imports for `Bundle` and `SessionCommand` are likely already present.)

- [ ] **Step 5: Replace each direct call**

Walk the table at the top of this task and replace each call site:

- Line ~1238: `autoReconnectManager?.onNetworkAvailable()` → `sendCommandNetworkAvailable()`
- Line ~1979: `autoReconnectManager?.cancelReconnection()` → `sendCommandCancelReconnect()`
- Line ~2067: `autoReconnectManager?.startReconnecting(server)` → `sendCommandConnectAuto(server.id)`
- Line ~2459: `autoReconnectManager?.cancelReconnection()` → `sendCommandCancelReconnect()`
- Line ~1487: `autoReconnectManager?.isReconnecting() == true` → `lastReconnectStatus is ReconnectStatus.Attempting`
- Line ~1698: `autoReconnectManager?.isReconnecting() != true` → `lastReconnectStatus !is ReconnectStatus.Attempting`
- Line ~1236: `if (autoReconnectManager?.isReconnecting() == true) ... onNetworkAvailable()` → just `sendCommandNetworkAvailable()` (the gating now happens server-side)
- Line ~2456: `val reconnectingId = autoReconnectManager?.getReconnectingServerId()` → `val reconnectingId = (lastReconnectStatus as? ReconnectStatus.Attempting)?.serverId`

- [ ] **Step 6: Observe `coordinator.reconnectStatus` and update UI on transitions**

Find the existing place in MainActivity where MediaController readiness is handled (look for `mediaController = ...` assignment and any subsequent collectors). After the `coordinator` is reachable through the controller's session manager, add a collector that observes `coordinator.reconnectStatus` and:

1. Updates `lastReconnectStatus` so the existing isReconnecting checks work.
2. Drives the existing UI updates that today are triggered by AutoReconnectManager's callbacks (the body of `onAttempt`, `onMethodAttempt`, `onSuccess`, `onFailure` in the deleted `initializeAutoReconnectManager` method).

Concretely: open the deleted `initializeAutoReconnectManager` method's previous body — the bodies of those four callbacks contain the UI updates. Move that logic into a `when (status)` block:

```kotlin
    private fun observeReconnectStatus() {
        // serviceScope or lifecycleScope, whichever is appropriate for MainActivity
        lifecycleScope.launch {
            coordinator.reconnectStatus.collect { status ->
                lastReconnectStatus = status
                when (status) {
                    ReconnectStatus.Idle -> {
                        // No active reconnect
                    }
                    is ReconnectStatus.Attempting -> {
                        // Existing onAttempt + onMethodAttempt UI updates go here.
                        // (Move the bodies from the deleted initializeAutoReconnectManager method.)
                    }
                    is ReconnectStatus.Succeeded -> {
                        // Existing onSuccess UI updates go here.
                    }
                    is ReconnectStatus.Failed -> {
                        // Existing onFailure UI updates go here.
                    }
                }
            }
        }
    }
```

Call `observeReconnectStatus()` once `coordinator` is reachable (e.g., from the MediaController-ready callback path).

If you cannot find a clean way to access `coordinator` from MainActivity (it lives in PlaybackService), the alternative is to expose `reconnectStatus` via a new MediaController custom-command response or via a Flow accessible through the bound service. **STOP and report NEEDS_CONTEXT** if the access path isn't clear — this is a real architectural question and not something to guess at.

- [ ] **Step 7: Build and run unit tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both should succeed.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/MainActivity.kt
git commit -m "feat(activity): route reconnect entry points through Coordinator

Phase 2A. MainActivity no longer constructs AutoReconnectManager —
that lifecycle is owned by PlaybackService now. Instead, MainActivity
sends COMMAND_CONNECT_AUTO / COMMAND_CANCEL_RECONNECT /
COMMAND_NETWORK_AVAILABLE custom commands and observes
ConnectionCoordinator.reconnectStatus to drive UI state.

Side benefit: auto-reconnect no longer dies when MainActivity is
destroyed by rotation or backgrounding."
```

---

## Task 6: Verify Phase 2A end-to-end

**Files:**
- None (verification only).

- [ ] **Step 1: Full unit test suite**

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. The four ConnectionCoordinator-related test classes (TransportStateTest, SessionStateTest, ReconnectStatusTest, ConnectionCoordinatorTest) plus all pre-existing tests should pass.

- [ ] **Step 2: Release build**

```bash
cd android && ./gradlew :app:assembleRelease
```

Expected: BUILD SUCCESSFUL. Confirms no R8/proguard regressions from the new types and reordered AutoReconnectManager ownership.

- [ ] **Step 3: Manual smoke — basic functionality**

On a physical device:

1. Connect to a SendSpin server. Confirm normal playback.
2. Tap Disconnect. Confirm clean disconnect (still routes through Coordinator from Phase 1).
3. Reconnect. Confirm full session resumes.

If any of these regress, this Phase has broken something the Coordinator already handled — investigate before proceeding.

- [ ] **Step 4: Manual smoke — auto-reconnect path**

On a physical device:

1. Connect to a SendSpin server. Confirm playback.
2. Toggle airplane mode briefly (5-10 seconds). Observe:
   - UI shows "Reconnecting..." status (driven by `ReconnectStatus.Attempting` flow).
   - When network returns, reconnect should fire and reach `ReconnectStatus.Succeeded`.
   - Playback resumes.
3. While in the "Reconnecting..." state from step 2, tap a different server. Confirm:
   - The original server's reconnect is cancelled (`COMMAND_CANCEL_RECONNECT` sent).
   - The new server attempts to connect.

- [ ] **Step 5: Manual smoke — Activity rotation during reconnect (regression fix)**

This validates the side benefit:

1. Connect to a SendSpin server.
2. Disable WiFi or yank the cable to simulate sustained disconnection.
3. While "Reconnecting..." is showing, rotate the device (or background/foreground).
4. Re-enable WiFi.
5. Confirm: reconnect still fires and succeeds. (Pre-Phase-2A this would have been killed by Activity destruction.)

- [ ] **Step 6: Confirm no behavior regression in beta channel before proceeding to Phase 2B**

Phase 2A should be invisible to users *except* for the rotation-during-reconnect fix. If beta testers report anything else changing — login prompts, connect flow timing, server-list behavior, error messaging — investigate before Phase 2B.

---

## Self-Review Notes

- **Spec coverage:** Phase 2A delivers the "relocate + adapter" half of the design's Phase 2 row. Phase 2B (kill the duel: disable SendSpinClient.attemptReconnect, move retry logic into Coordinator, delete AutoReconnectManager) is a separate plan.
- **Type consistency:** `ReconnectStatus`, `ConnectionCoordinator` constructor params, MediaSession command names all consistent across tasks.
- **Granularity:** Tasks 1-2 are small (single-file additions with TDD). Tasks 3-4 are PlaybackService changes scoped to one logical concern each. Task 5 is the largest because MainActivity has 12 touch points — the table at the top of Task 5 is the implementer's checklist.
- **Risk surfaces:**
  - Task 3 introduces a new `connectViaSelectedConnection` suspend method that awaits SendSpinClient state. The 15s timeout matches typical handshake behavior; tune if beta shows it's too short.
  - Task 5 has a known unknown: the access path from MainActivity to `coordinator.reconnectStatus`. The plan calls out the STOP-and-report case. If MediaController doesn't expose the Coordinator directly, an alternative (custom-command broadcast, bound-service access) becomes a sub-decision before completing Task 5.
