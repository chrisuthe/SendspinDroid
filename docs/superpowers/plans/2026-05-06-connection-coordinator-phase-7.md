# ConnectionCoordinator Phase 7 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Eliminate duplicate transport-state holders. After this phase, `coordinator.sessionState` is the only place SendSpin/MA connection state lives; `coordinator.reconnectStatus` is the only place reconnect-attempt state lives. Both are exposed for cross-component consumption via `PlaybackService` companion-StateFlow relays (same pattern Phase 3 introduced for `networkState`).

**Explicit non-goal:** `AppConnectionState` in `MainActivity` (~50 references, UI state machine with `ServerList`/`Connecting`/`Connected`/`Reconnecting`/`Error` arms) STAYS. It models UI lifecycle, not transport state. The `ServerList` arm has no mapping into Coordinator state. Replacing it would be a UI-layer rewrite outside Phase 7's scope.

**Architecture after this phase:**

```
PlaybackService
   |
   +-- companion object {
   |     val networkState: StateFlow<NetworkState>          # Phase 3 (kept)
   |     val reconnectStatus: StateFlow<ReconnectStatus>    # Phase 7 NEW
   |     // (no more relay needed for sessionState — its consumers in
   |     //  PlaybackService observe coordinator.sessionState directly)
   |   }
   |
   +-- broadcasts session extras from:
   |     coordinator.sessionState.value
   |     coordinator.reconnectStatus.value
   |     (no longer reads `_connectionState.value` — that field is gone)
   |
   +-- (DELETED) sealed class ConnectionState { Disconnected, Connecting, Connected, Reconnecting, Error }
   +-- (DELETED) private val _connectionState: MutableStateFlow<ConnectionState>

MainActivity
   |
   +-- handleReconnectStatusChange(status):
   |     drives UI based on the parameter; no longer assigns to lastReconnectStatus
   |
   +-- isReconnecting checks (3 sites):
   |     read PlaybackService.reconnectStatus.value is ReconnectStatus.Attempting
   |
   +-- (DELETED) private var lastReconnectStatus: ReconnectStatus

(`AppConnectionState` UI state machine in MainActivity stays — out of scope.)
```

**Tech Stack:** Kotlin, kotlinx.coroutines.

**Reference spec:** `docs/superpowers/specs/2026-05-05-connection-coordinator-design.md` Phase 7 row in §9.

---

## Task 1: PlaybackService stops mirroring connection state

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

The pre-Coordinator architecture had PlaybackService maintain its own `_connectionState` for broadcasting via session extras. After Phase 1 the Coordinator's `sessionState` flow became the canonical source — but `_connectionState` stuck around because deletion would have rippled through the broadcast path.

This task does that ripple. The `_connectionState` field deletes; `broadcastSessionExtras` derives the broadcast from `coordinator.sessionState.value` and `coordinator.reconnectStatus.value`; reads of `_connectionState.value` are replaced by the Coordinator's flows.

- [ ] **Step 1: Map every reference**

```bash
grep -n "_connectionState\|sealed class ConnectionState\|ConnectionState\\.\|: ConnectionState" android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
```

Expect:
- The field declaration (~line 276-277)
- The sealed class (~line 529)
- ~10 write sites: `_connectionState.value = ConnectionState.X` at lines 766, 832, 881, 915, 941, 1054, 1189, 1919, 1945, 1957, 1982, 1998, 2023, ...
- ~3 read sites: `_connectionState.value is ConnectionState.X` at lines 1832, 2275, 3999

For each WRITE site, decide whether the equivalent state already comes through Coordinator's flows:
- `Disconnected/Connecting/Connected/Error` are emitted by SendSpin's `connectionState` flow, which Coordinator's `sessionState` observes. PlaybackService doesn't need to write these — they ARE the source. The writes are redundant.
- `Reconnecting(serverName, attempt)` is emitted by Coordinator's retry loop via `reconnectStatus`. PlaybackService doesn't write reconnect state.

For each READ site, identify the question being asked:
- `_connectionState.value is ConnectionState.Connected` → "is the SendSpin transport ready?" → `coordinator.sessionState.value.sendSpin is TransportState.Ready`
- Other variants similarly.

Quote each read site (line + context) in your report so I can verify the replacement is semantically equivalent.

- [ ] **Step 2: Replace read sites with coordinator-derived equivalents**

Each `_connectionState.value is ConnectionState.X` becomes the corresponding `coordinator.sessionState.value.sendSpin is TransportState.Y` (with possibly an additional `coordinator.reconnectStatus.value is ReconnectStatus.Attempting` check for `Reconnecting` semantics).

Mapping table:

| Old read | New read |
|---|---|
| `_connectionState.value is ConnectionState.Disconnected` | `coordinator.sessionState.value.sendSpin is TransportState.Idle` |
| `_connectionState.value is ConnectionState.Connecting` | `coordinator.sessionState.value.sendSpin is TransportState.Connecting` |
| `_connectionState.value is ConnectionState.Connected` | `coordinator.sessionState.value.sendSpin is TransportState.Ready` |
| `_connectionState.value is ConnectionState.Reconnecting` | `coordinator.reconnectStatus.value is ReconnectStatus.Attempting` |
| `_connectionState.value is ConnectionState.Error` | `coordinator.sessionState.value.sendSpin is TransportState.Failed` |

If a read needs the `serverName` (e.g., line 1832 might fetch `(_connectionState.value as ConnectionState.Connected).serverName`), get it from `coordinator.sessionState.value.server?.name` instead.

- [ ] **Step 3: Delete write sites**

Each `_connectionState.value = ConnectionState.X(...)` line deletes. The Coordinator's flows already reflect the same state via the SendSpin/MA `connectionState` chain.

**Caveat:** there are PlaybackService-specific synchronous-exception paths at lines 1945, 1982, 2023:

```kotlin
} catch (e: Exception) {
    _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
}
```

These set Error when `connect()` itself throws synchronously (before the SendSpin state machine has a chance to emit). After deleting the write, the Coordinator's `sessionState` will NOT reflect the failure (because SendSpin's flow never updated).

For Phase 7, the cleanest solution: if those catch blocks need to surface an error, log it and let the existing Coordinator timeout / retry handle the absence-of-Ready signal. Since `connect()` is fire-and-forget today, a synchronous throw is rare (most failures arrive via the WebSocket `onClosed`/`onFailure` paths). If it happens, the user-facing UI shows "Connection timed out" via the Coordinator's normal flow.

If the implementer is uncomfortable losing the synchronous-Error path, an alternative: have those catch blocks call `sendSpinClient?.markFailedSynchronously(reason)` (a small new method on SendSpin that flips its `_connectionState` to `Failed(reason)`). Add this method to SendSpin if needed. **STOP and report** if this turns out to be the right call — Phase 7 should not silently lose error visibility.

- [ ] **Step 4: Update broadcastSessionExtras**

`broadcastSessionExtras` (around line 1979-2057 per Phase 1's setup) builds a Bundle. The bundle contains `connection_state` field whose value is one of `STATE_DISCONNECTED`, `STATE_CONNECTING`, `STATE_CONNECTED`, `STATE_RECONNECTING`, `STATE_ERROR`.

Today it reads `_connectionState.value` and switches on the sealed class. Replace with:

```kotlin
private fun broadcastSessionExtras() {
    val session = mediaSession ?: return
    val sessionState = coordinator.sessionState.value
    val reconnectStatus = coordinator.reconnectStatus.value

    val connectionStateString = when {
        reconnectStatus is ReconnectStatus.Attempting -> STATE_RECONNECTING
        sessionState.sendSpin is TransportState.Failed -> STATE_ERROR
        sessionState.sendSpin is TransportState.Ready -> STATE_CONNECTED
        sessionState.sendSpin is TransportState.Connecting -> STATE_CONNECTING
        else -> STATE_DISCONNECTED
    }

    val errorMessage = when {
        sessionState.sendSpin is TransportState.Failed ->
            failureReasonToMessage((sessionState.sendSpin as TransportState.Failed).reason)
        else -> null
    }

    val extras = Bundle().apply {
        putString(EXTRA_CONNECTION_STATE, connectionStateString)
        if (errorMessage != null) putString(EXTRA_ERROR_MESSAGE, errorMessage)
        sessionState.server?.let { putString(EXTRA_SERVER_NAME, it.name) }

        // Reconnect status fields (already added in Phase 5a):
        when (reconnectStatus) {
            ReconnectStatus.Idle -> putString(EXTRA_RECONNECT_STATUS, RECONNECT_IDLE)
            is ReconnectStatus.Attempting -> {
                putString(EXTRA_RECONNECT_STATUS, RECONNECT_ATTEMPTING)
                putString(EXTRA_RECONNECT_SERVER_ID, reconnectStatus.serverId)
                putInt(EXTRA_RECONNECT_ATTEMPT, reconnectStatus.attempt)
                putInt(EXTRA_RECONNECT_MAX_ATTEMPTS, reconnectStatus.maxAttempts)
                putString(EXTRA_RECONNECT_METHOD, reconnectStatus.method?.name)
            }
            is ReconnectStatus.Succeeded -> {
                putString(EXTRA_RECONNECT_STATUS, RECONNECT_SUCCEEDED)
                putString(EXTRA_RECONNECT_SERVER_ID, reconnectStatus.serverId)
            }
            is ReconnectStatus.Failed -> {
                putString(EXTRA_RECONNECT_STATUS, RECONNECT_FAILED)
                putString(EXTRA_RECONNECT_SERVER_ID, reconnectStatus.serverId)
                putString(EXTRA_RECONNECT_ERROR, reconnectStatus.error)
            }
        }
    }
    session.setSessionExtras(extras)
}

private fun failureReasonToMessage(reason: FailureReason): String = when (reason) {
    FailureReason.AuthRejected -> "Authentication failed"
    FailureReason.HandshakeFailed -> "Could not establish connection"
    FailureReason.TransientNetwork -> "Network error"
    FailureReason.ProtocolError -> "Protocol error"
    FailureReason.Exhausted -> "Connection lost after multiple attempts"
}
```

The exact existing fields in the bundle may include more than what's shown (metadata, group info, volume, etc.). PRESERVE all of those — they read from other state, not `_connectionState`. Only the connection-state portion of the bundle changes.

- [ ] **Step 5: Delete the sealed class and field**

Delete:
```kotlin
private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
```

Delete the entire `sealed class ConnectionState { ... }` (lines 529+).

If `connectionState: StateFlow<ConnectionState>` has external readers (e.g., the wizard or anywhere observed it), find those and update — but per the architecture, no such readers should exist. Run:

```bash
grep -rn "PlaybackService\\.connectionState\\|playbackService\\.connectionState" android/app/src/
```

Verify zero matches.

- [ ] **Step 6: Build and run tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green. Tests that referenced `PlaybackService.ConnectionState.X` (similar to Phase 4 Task 4) will need updating.

- [ ] **Step 7: Commit**

```bash
git add -A android/
git commit -m "refactor(playback): delete _connectionState mirror

Phase 7. PlaybackService no longer maintains its own ConnectionState
sealed class or _connectionState MutableStateFlow. Reads switch to
coordinator.sessionState.value (for SendSpin transport state) and
coordinator.reconnectStatus.value (for retry state). broadcastSessionExtras
derives the connection-state bundle field from these flows directly.

The pre-Coordinator architecture had PlaybackService mirror connection
state for broadcasting; Phase 1 made the Coordinator the source of
truth. Deleting the mirror brings the codebase to one source of truth
for transport state. AppConnectionState in MainActivity stays -- it is
UI state, not a transport mirror."
```

---

## Task 2: MainActivity stops caching reconnect status

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/MainActivity.kt`

Add a `companion object reconnectStatus: StateFlow<ReconnectStatus>` to `PlaybackService` (the same pattern as the existing `networkState` companion). The relay's value is updated from the Coordinator's `reconnectStatus` flow, providing a single point of truth that both PlaybackService and MainActivity can read synchronously.

MainActivity drops its `lastReconnectStatus` field and reads `PlaybackService.reconnectStatus.value` at the 3 existing call sites.

- [ ] **Step 1: Add the companion StateFlow to PlaybackService**

In `PlaybackService.kt` companion object (where `networkState` is exposed — find via `grep -n "val networkState" android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`), add:

```kotlin
        private val _reconnectStatusRelay = MutableStateFlow<ReconnectStatus>(ReconnectStatus.Idle)
        val reconnectStatus: StateFlow<ReconnectStatus> = _reconnectStatusRelay.asStateFlow()
```

(Or whatever naming convention the existing `networkState` companion uses. Match exactly — the relay pattern should be visually obvious as the same pattern.)

In `PlaybackService.onCreate`, where the `networkState` relay is updated, add an analogous collector for `reconnectStatus`:

```kotlin
serviceScope.launch {
    coordinator.reconnectStatus.collect { _reconnectStatusRelay.value = it }
}
```

Or wherever the existing networkState collector lives. Match the existing pattern.

- [ ] **Step 2: Update MainActivity**

Find the 3 read sites (lines 1376, 1525, 2359 per the grep). Replace `lastReconnectStatus` with `PlaybackService.reconnectStatus.value`:

```kotlin
// Old:
if (lastReconnectStatus is ReconnectStatus.Attempting) { ... }
// New:
if (PlaybackService.reconnectStatus.value is ReconnectStatus.Attempting) { ... }
```

Same for the `!is` and the `as?` reads. The reads are synchronous — `StateFlow.value` works fine for that.

In `handleReconnectStatusChange(status: ReconnectStatus)`, delete the `lastReconnectStatus = status` assignment. The relay (Step 1's collector) handles propagation now.

- [ ] **Step 3: Delete the field**

Delete:
```kotlin
private var lastReconnectStatus: ReconnectStatus = ReconnectStatus.Idle
```

- [ ] **Step 4: Build and run tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt \
        android/app/src/main/java/com/sendspindroid/MainActivity.kt
git commit -m "refactor(activity): drop lastReconnectStatus mirror; use service relay

Phase 7. MainActivity's local lastReconnectStatus field deletes. The
3 isReconnecting checks now read PlaybackService.reconnectStatus.value
(a new companion-StateFlow relay updated from coordinator.reconnectStatus,
matching the networkState relay pattern from Phase 3). One source of
truth for retry state; no more local mirror that could drift."
```

---

## Task 3: Verify Phase 7 end-to-end

- [ ] **Step 1: Confirm the targeted duplicates are gone**

```bash
grep -n "_connectionState\|sealed class ConnectionState\|lastReconnectStatus" android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt android/app/src/main/java/com/sendspindroid/MainActivity.kt
```

Should be empty. SendSpin's own `_connectionState` (the source of truth for SendSpin transport state) lives in `SendSpin.kt` and is OUT OF SCOPE — don't touch it.

- [ ] **Step 2: Full test suite + release build**

```bash
cd android && ./gradlew :app:testDebugUnitTest :app:assembleRelease
```

All green.

- [ ] **Step 3: Manual smoke test**

Behavior should be identical to Phase 6. Phase 7 is pure cleanup.

1. Connect / playback / disconnect — basic round-trip.
2. Airplane mode toggle — Reconnecting UI advances correctly. The bundle's `connection_state` field reflects the same states it did pre-Phase-7 (transitions through STATE_RECONNECTING).
3. WiFi → Cell handover — no regression.
4. Wizard test connection — no regression.

Pay particular attention to the broadcast-derived UI: anything that read the session-extras `connection_state` field in MainActivity (which we didn't change in Phase 7) should still get the same values it always did.

---

## Self-Review Notes

- **Spec coverage:** Phase 7 satisfies the design's §9 Phase 7 row to the extent that's pragmatic. `AppConnectionState` in MainActivity stays — flagged as out of scope because it's UI state, not transport state.
- **Risk surfaces:**
  - Task 1's removal of synchronous-Error writes in the catch blocks at lines 1945, 1982, 2023 may lose error visibility for synchronous-throw cases. Verify in manual testing that real connect failures still surface to the UI within a reasonable timeout via the existing flow path.
  - Task 1's `broadcastSessionExtras` rewrite must preserve the bundle structure. If a field was emitted that no consumer reads, removing it is OK; if a consumer (MediaController in MainActivity) reads it, it must continue being emitted.
  - Task 2's `PlaybackService.reconnectStatus.value` access is synchronous — it'll return the most recent value the relay coroutine has seen. There's an acceptable race window between `coordinator.reconnectStatus` emitting and the relay updating; in practice the gap is microseconds and shouldn't matter for MainActivity's `isReconnecting` UI checks.
- **Phase 7 is the END of the design.** After this lands, the ConnectionCoordinator design as specified in `docs/superpowers/specs/2026-05-05-connection-coordinator-design.md` is fully implemented. The dueling-timer problem is solved. The five-sources-of-truth situation is reduced to one (Coordinator) plus legitimate per-component derived state. The WiFi→Cell login bug is fixed (Phase 5).
