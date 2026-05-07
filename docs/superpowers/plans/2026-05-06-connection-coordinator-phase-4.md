# ConnectionCoordinator Phase 4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clean up the `SendSpinClient` transport API. Specifically:
- Replace the three explicit `connectLocal/connectRemote/connectProxy` methods with a single `connect(endpoint: SendSpinEndpoint)` accepting a sealed-class endpoint.
- Replace the `StateFlow<ConnectionState>` (with `Disconnected/Connecting/Connected/Error`) with `StateFlow<TransportState>` (with `Idle/Connecting/Ready/Failed(reason)`). This consolidates the two parallel state types and adds `FailureReason` classification at the source instead of in PlaybackService's mapping helper.
- Remove the state-management methods (`onConnected`, `onDisconnected`, `onError`, `onReconnecting`, `onReconnected`) from `SendSpinClientCallback`. Consumers observe `connectionState` directly. Streaming and metadata callbacks (`onAudioChunk`, `onMetadataUpdate`, `onArtwork`, etc.) STAY — they're hot-path / binary payloads where the callback overhead matters more than API uniformity.
- Rename `SendSpinClient` to `SendSpin`.

**Trade-off explicitly chosen:** the streaming/metadata callbacks remain. Replacing them with Flow/Channel would add scheduling overhead on the audio hot path with no clear win. Phase 5+ can revisit if a real need emerges.

**Architecture after this phase:**

```
SendSpin (renamed from SendSpinClient)
   |
   +-- val connectionState: StateFlow<TransportState>
   |       Idle | Connecting | Ready | Failed(FailureReason)
   |
   +-- fun connect(endpoint: SendSpinEndpoint)   # single entry point
   |       SendSpinEndpoint.Local(address, path)
   |       SendSpinEndpoint.Proxy(url, authToken)
   |       SendSpinEndpoint.Remote(remoteId)
   |
   +-- fun disconnect()                # unchanged
   +-- fun setNetworkAvailable(...)    # unchanged
   +-- fun onNetworkChanged()          # unchanged
   +-- fun disconnectForReselection()  # unchanged
   +-- selfReconnectEnabled flag       # unchanged
   +-- fun setProxyFallback(...)       # unchanged
   +-- network telemetry getters       # unchanged
   +-- play / pause / next / previous / setVolume / etc.  # unchanged
   |
   +-- callback: SendSpinListener (renamed from SendSpinClientCallback)
       Streaming/metadata methods only:
         onServerDiscovered, onStateChanged, onGroupUpdate,
         onMetadataUpdate, onArtwork, onArtworkCleared,
         onStreamStart, onStreamClear, onStreamEnd, onAudioChunk,
         onVolumeChanged, onMutedChanged, onSyncOffsetApplied,
         onSyncMuteChanged, onNetworkChanged
       State-management methods REMOVED (replaced by connectionState flow):
         onConnected, onDisconnected, onError, onReconnecting, onReconnected
```

**Tech Stack:** Kotlin, kotlinx.coroutines, JUnit 4, MockK, kotlinx-coroutines-test.

**Reference spec:** `docs/superpowers/specs/2026-05-05-connection-coordinator-design.md` Phase 4 row in §9.

---

## File Structure

**Create:**
- `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinEndpoint.kt` — sealed class (small).

**Modify (eventually rename):**
- `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt` — eventually renamed to `SendSpin.kt` in Task 6.
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` — caller migration.
- `android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt` — adapts to the renamed types.

**Don't touch:**
- `coordinator/TransportState.kt`, `coordinator/FailureReason.kt` — types stay where they are.
- The streaming/metadata callback paths (audio decode, metadata broadcast).

---

## Task 1: Add `SendSpinEndpoint` sealed class

**Files:**
- Create: `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinEndpoint.kt`
- Create: `android/app/src/test/java/com/sendspindroid/sendspin/SendSpinEndpointTest.kt`

Pure additive type definition. Mirrors today's three connect methods.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sendspindroid/sendspin/SendSpinEndpointTest.kt`:

```kotlin
package com.sendspindroid.sendspin

import org.junit.Assert.assertEquals
import org.junit.Test

class SendSpinEndpointTest {
    @Test
    fun `Local carries address and path`() {
        val e: SendSpinEndpoint = SendSpinEndpoint.Local("10.0.1.5:8927", "/sendspin")
        assertEquals("10.0.1.5:8927", (e as SendSpinEndpoint.Local).address)
        assertEquals("/sendspin", e.path)
    }

    @Test
    fun `Local default path is the SendSpin endpoint constant`() {
        val e = SendSpinEndpoint.Local("10.0.1.5:8927")
        assertEquals(SendSpinProtocol.ENDPOINT_PATH, e.path)
    }

    @Test
    fun `Proxy carries url and authToken`() {
        val e: SendSpinEndpoint = SendSpinEndpoint.Proxy("https://example.com/sendspin", "tok")
        assertEquals("https://example.com/sendspin", (e as SendSpinEndpoint.Proxy).url)
        assertEquals("tok", e.authToken)
    }

    @Test
    fun `Remote carries remoteId`() {
        val e: SendSpinEndpoint = SendSpinEndpoint.Remote("ABCDEFGH012345678901234567")
        assertEquals("ABCDEFGH012345678901234567", (e as SendSpinEndpoint.Remote).remoteId)
    }

    @Test
    fun `when expression is exhaustive`() {
        val cases: List<SendSpinEndpoint> = listOf(
            SendSpinEndpoint.Local("a", "/p"),
            SendSpinEndpoint.Proxy("u", "t"),
            SendSpinEndpoint.Remote("r"),
        )
        val labels = cases.map {
            when (it) {
                is SendSpinEndpoint.Local -> "local"
                is SendSpinEndpoint.Proxy -> "proxy"
                is SendSpinEndpoint.Remote -> "remote"
            }
        }
        assertEquals(listOf("local", "proxy", "remote"), labels)
    }
}
```

- [ ] **Step 2: Run test, expect failure**

`cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.sendspin.SendSpinEndpointTest`

Expected: FAIL ("unresolved reference: SendSpinEndpoint").

- [ ] **Step 3: Implement the type**

Create `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinEndpoint.kt`:

```kotlin
package com.sendspindroid.sendspin

/**
 * Endpoint a SendSpin connection targets. Replaces the three explicit
 * connect{Local,Proxy,Remote} methods with a single `connect(endpoint)`
 * entry point.
 *
 * Phase 4 of the ConnectionCoordinator design.
 * See docs/superpowers/specs/2026-05-05-connection-coordinator-design.md
 */
sealed class SendSpinEndpoint {
    /**
     * Direct WebSocket to a server on the local network.
     * @param address host[:port], e.g. "10.0.1.5:8927"
     * @param path WebSocket path, defaults to SendSpin's standard endpoint.
     */
    data class Local(
        val address: String,
        val path: String = SendSpinProtocol.ENDPOINT_PATH,
    ) : SendSpinEndpoint()

    /**
     * Authenticated WebSocket to a reverse proxy (e.g. Music Assistant cloud).
     * @param url full proxy URL including scheme.
     * @param authToken proxy bearer/auth token sent in the auth message.
     */
    data class Proxy(
        val url: String,
        val authToken: String,
    ) : SendSpinEndpoint()

    /**
     * WebRTC DataChannel via Music Assistant Remote Access.
     * @param remoteId 26-character remote-access identifier.
     */
    data class Remote(
        val remoteId: String,
    ) : SendSpinEndpoint()
}
```

- [ ] **Step 4: Run test, expect pass**

`cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.sendspin.SendSpinEndpointTest`

Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/SendSpinEndpoint.kt \
        android/app/src/test/java/com/sendspindroid/sendspin/SendSpinEndpointTest.kt
git commit -m "feat(sendspin): add SendSpinEndpoint sealed class

Phase 4 of ConnectionCoordinator design. Three sealed-class variants
mirror the existing connect{Local,Proxy,Remote} methods so callers can
construct one value and pass it to a single SendSpin.connect(endpoint)
entry point (added in the next commit). Local.path defaults to
SendSpinProtocol.ENDPOINT_PATH to match the existing connectLocal
default."
```

---

## Task 2: Add `SendSpinClient.connect(endpoint)` facade

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt`

Add a new public method that dispatches to the existing connect methods. Keep the existing methods PUBLIC for now — Task 3 migrates callers, Task 6 may consider deprecating them.

- [ ] **Step 1: Add the method**

Place it near the existing `connectLocal/Remote/Proxy` methods (around line 600-700 per the surface map):

```kotlin
    /**
     * Connect to the given endpoint. Single entry point that replaces the
     * three explicit connectLocal/Remote/Proxy methods. Phase 4 introduces
     * this facade; the underlying methods stay for now (Task 3 migrates
     * callers; existing methods may be made private or deprecated later).
     */
    fun connect(endpoint: SendSpinEndpoint) {
        when (endpoint) {
            is SendSpinEndpoint.Local -> connectLocal(endpoint.address, endpoint.path)
            is SendSpinEndpoint.Proxy -> connectProxy(endpoint.url, endpoint.authToken)
            is SendSpinEndpoint.Remote -> connectRemote(endpoint.remoteId)
        }
    }
```

- [ ] **Step 2: Build to confirm compile**

`cd android && ./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Add unit-test smoke check (optional but recommended)**

Add a test that calls `connect(SendSpinEndpoint.Local("test", "/path"))` against a mocked SendSpinClient and verifies the corresponding `connectLocal` was invoked. Skip if this would require deeper SendSpinClient mocking that isn't in place — the value of the dispatcher logic is small and Task 3's caller migration is the real verification.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt
git commit -m "feat(sendspin): add connect(endpoint) facade dispatching to existing methods

Phase 4. Single entry point taking a SendSpinEndpoint sealed class.
Internally dispatches to the existing connectLocal / connectProxy /
connectRemote methods which stay public for now. Caller migration in
the next commit."
```

---

## Task 3: Migrate `PlaybackService` callers to `connect(endpoint)`

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

Today PlaybackService has three call sites:
- `connectToServer(address, path)` calls `sendSpinClient?.connectLocal(address, path)`
- `connectToRemoteServer(remoteId)` calls `sendSpinClient?.connectRemote(remoteId)`
- `connectToProxyServer(url, authToken)` calls `sendSpinClient?.connectProxy(url, authToken)`

Replace each with `sendSpinClient?.connect(SendSpinEndpoint.X(...))`.

- [ ] **Step 1: Find the call sites**

```bash
grep -n "sendSpinClient?\.connect" android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
```

- [ ] **Step 2: Replace each call**

Inside `connectToServer(address, path)` — the `sendSpinClient?.connectLocal(address, path)` call becomes:

```kotlin
        sendSpinClient?.connect(
            com.sendspindroid.sendspin.SendSpinEndpoint.Local(address, path)
        )
```

Inside `connectToRemoteServer(remoteId)`:

```kotlin
        sendSpinClient?.connect(
            com.sendspindroid.sendspin.SendSpinEndpoint.Remote(remoteId)
        )
```

Inside `connectToProxyServer(url, authToken)`:

```kotlin
        sendSpinClient?.connect(
            com.sendspindroid.sendspin.SendSpinEndpoint.Proxy(url, authToken)
        )
```

Add `import com.sendspindroid.sendspin.SendSpinEndpoint` to the imports block (alphabetically into the `com.sendspindroid.sendspin.*` group). Then use the short name in each call site:

```kotlin
        sendSpinClient?.connect(SendSpinEndpoint.Local(address, path))
        sendSpinClient?.connect(SendSpinEndpoint.Remote(remoteId))
        sendSpinClient?.connect(SendSpinEndpoint.Proxy(url, authToken))
```

Don't change anything else in those methods.

- [ ] **Step 3: Build and run tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "refactor(playback): use SendSpinEndpoint via connect(endpoint)

Phase 4. PlaybackService's three connect-flavor wrappers
(connectToServer / connectToRemoteServer / connectToProxyServer) now
construct a SendSpinEndpoint and call sendSpinClient.connect(endpoint)
instead of the three explicit methods. Behavior unchanged. The legacy
connect{Local,Proxy,Remote} methods remain on SendSpinClient for now;
they may be made private after Task 6 (the rename)."
```

---

## Task 4: SendSpinClient exposes `StateFlow<TransportState>`; `Failed` carries `FailureReason`

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/coordinator/ConnectionCoordinator.kt` (only if it imported `SendSpinClient.ConnectionState` directly)

The current `SendSpinClient.ConnectionState` sealed class (`Disconnected/Connecting/Connected(name)/Error(message)`) gets retired. The class instead exposes `connectionState: StateFlow<TransportState>` (`Idle/Connecting/Ready/Failed(reason)`).

This collapses the existing PlaybackService mapping helper (`SendSpinClient.ConnectionState.toTransportState()`) — that helper deletes since the source is now already TransportState.

`FailureReason` classification is added in the close/fail paths. Today's `isRecoverableError` heuristic is the foundation:

| Throwable / close code | New FailureReason |
|---|---|
| Code 1000 (normal) | (no Failed; transitions Idle) |
| `SocketException`, `EOFException`, message contains "reset"/"abort"/"broken pipe"/"connection closed", `SocketTimeoutException` | `TransientNetwork` |
| `UnknownHostException`, `SSLHandshakeException`, message contains "refused" | `HandshakeFailed` |
| HTTP 401 / 403 from a successfully-handshaked transport (proxy auth response) | `AuthRejected` |
| Anything else | `TransientNetwork` (conservative default) |

The `AuthRejected` mapping is conservative: only set if the response was clearly an auth rejection AFTER a completed transport handshake. Phase 5's MA work depends on this distinction being correct.

- [ ] **Step 1: Read the existing state-flow declarations**

```bash
grep -n "_connectionState\|sealed class ConnectionState\|ConnectionState\\.Connected\|ConnectionState\\.Disconnected\|ConnectionState\\.Connecting\|ConnectionState\\.Error" android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt | head -40
```

Quote the existing `ConnectionState` sealed class and `_connectionState` field declaration in your report. Note the line numbers.

- [ ] **Step 2: Replace ConnectionState with TransportState in SendSpinClient**

In `SendSpinClient.kt`:

a. Add imports:
```kotlin
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.coordinator.FailureReason
```

b. Replace the existing `_connectionState` field type:
```kotlin
    private val _connectionState = MutableStateFlow<TransportState>(TransportState.Idle)
    val connectionState: StateFlow<TransportState> = _connectionState.asStateFlow()
```

c. Delete the existing `ConnectionState` sealed class (the inner one with `Disconnected/Connecting/Connected/Error`).

d. Replace every `_connectionState.value = ConnectionState.X` assignment with the appropriate `TransportState`:

| Old | New |
|---|---|
| `_connectionState.value = ConnectionState.Disconnected` | `_connectionState.value = TransportState.Idle` |
| `_connectionState.value = ConnectionState.Connecting` | `_connectionState.value = TransportState.Connecting` |
| `_connectionState.value = ConnectionState.Connected(serverName)` | `_connectionState.value = TransportState.Ready` (the serverName goes to a separate field if needed for `getServerName()`; check whether the existing class already tracks it elsewhere) |
| `_connectionState.value = ConnectionState.Error(message)` | `_connectionState.value = TransportState.Failed(classifyFailureReason(throwable, message))` |

The "serverName" field — today it lives ONLY inside `ConnectionState.Connected(serverName)`. After Phase 4, store it in a separate field `private var connectedServerName: String? = null` and have `getServerName()` (around line 503) return it. Update at the same time as the `TransportState.Ready` transition.

e. Add the classification helper:

```kotlin
    private fun classifyFailureReason(
        throwable: Throwable?,
        closeCode: Int? = null,
        responseCode: Int? = null,
    ): FailureReason {
        // Auth rejected only when we had a fully-handshaked transport AND received
        // a 401/403 response. Everything else stays TransientNetwork or
        // HandshakeFailed -- the conservative default.
        if (responseCode == 401 || responseCode == 403) {
            return FailureReason.AuthRejected
        }
        if (throwable is javax.net.ssl.SSLException ||
            throwable is java.net.UnknownHostException ||
            throwable?.message?.contains("refused", ignoreCase = true) == true) {
            return FailureReason.HandshakeFailed
        }
        // SocketException, EOFException, SocketTimeoutException, "reset",
        // "abort", "broken pipe", "connection closed", code 1006 etc.
        return FailureReason.TransientNetwork
    }
```

Adjust the throwable-import alias if it already exists in the file. Read the existing `isRecoverableError` (around line 1215) to see what's currently imported.

f. Update the close/fail paths to call `classifyFailureReason` and emit `Failed(reason)`:

In `TransportEventListener.onClosed(code, reason)`: when emitting Error today, change to `_connectionState.value = TransportState.Failed(classifyFailureReason(null, closeCode = code))`.

In `TransportEventListener.onFailure(throwable, response)`: change to `_connectionState.value = TransportState.Failed(classifyFailureReason(throwable, responseCode = response?.code))`.

The `selfReconnectEnabled = false` branch added in Phase 2B Task 2 should also use the new Failed state (not Error/Disconnected).

- [ ] **Step 3: Delete the toTransportState mapping helper in PlaybackService**

```bash
grep -n "toTransportState" android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
```

The helper at the bottom of PlaybackService.kt (added in Phase 1 Task 4) becomes a no-op: `connectionState.value` is already a `TransportState`. Delete the extension function. Update the construction call in `onCreate` from:

```kotlin
sendSpinStateFlow = sendSpinClient?.connectionState?.map { it.toTransportState() } ?: flowOf(TransportState.Idle),
```

to:

```kotlin
sendSpinStateFlow = sendSpinClient?.connectionState ?: flowOf(TransportState.Idle),
```

The MA mapping helper (`MaConnectionState.toTransportState()`) STAYS — that's still needed for now.

- [ ] **Step 4: Build and run all tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt \
        android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "refactor(sendspin): expose StateFlow<TransportState>; add FailureReason

Phase 4. SendSpinClient.connectionState now emits TransportState directly
(Idle / Connecting / Ready / Failed(reason)) instead of the local
ConnectionState sealed class. Failures carry a FailureReason classified
from throwable + response code (TransientNetwork / HandshakeFailed /
AuthRejected). The conservative classifier only sets AuthRejected when
the transport completed handshake and the server returned 401/403 --
this is the basis for Phase 5's WiFi->Cell login fix.

PlaybackService's SendSpinClient.ConnectionState.toTransportState mapping
helper deletes (the source is now already TransportState). The MA
mapping helper stays."
```

---

## Task 5: Remove state-management methods from `SendSpinClientCallback`

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

The `SendSpinClientCallback` interface today has ~17 methods. Five of them (`onConnected`, `onDisconnected`, `onError`, `onReconnecting`, `onReconnected`) duplicate state-machine signals that are now expressible via `StateFlow<TransportState>`. PlaybackService's implementation of those five callbacks is what drives all sorts of state-machine work (broadcast updates, audio drain control, etc.) — that work now happens in a `connectionState` collector.

The streaming and metadata callbacks STAY: `onAudioChunk`, `onMetadataUpdate`, `onArtwork`, `onArtworkCleared`, `onStreamStart`, `onStreamClear`, `onStreamEnd`, `onVolumeChanged`, `onMutedChanged`, `onSyncOffsetApplied`, `onSyncMuteChanged`, `onNetworkChanged`, `onStateChanged`, `onGroupUpdate`, `onServerDiscovered`. These deliver hot-path data (audio frames every few ms) or binary payloads (artwork). Replacing them with Flow has no architectural payoff and adds scheduling overhead.

- [ ] **Step 1: List all callers of the to-be-deleted callbacks**

```bash
grep -n "override fun onConnected\|override fun onDisconnected\|override fun onError\|override fun onReconnecting\|override fun onReconnected" android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
```

Read each implementation. Note what it does (state broadcast, audio drain, MediaSession update, etc.). All that work moves to a `connectionState.collect { ... }` block.

- [ ] **Step 2: Add the connectionState collector in PlaybackService**

In `PlaybackService.onCreate`, after the existing coordinator-related collectors, add:

```kotlin
        // Drives the work that used to live in SendSpinClientCallback's onConnected /
        // onDisconnected / onError / onReconnecting / onReconnected. Phase 4 removed
        // those callbacks; consumers observe the StateFlow instead.
        var prevState: TransportState = TransportState.Idle
        serviceScope.launch {
            sendSpinClient?.connectionState?.collect { state ->
                when {
                    state is TransportState.Ready && prevState !is TransportState.Ready -> {
                        // Ported logic from old onConnected: foreground service start,
                        // high-power locks, debug logging, MediaSession broadcasts.
                        // Insert the body of the deleted onConnected callback here.
                    }
                    state is TransportState.Idle && prevState !is TransportState.Idle -> {
                        // Ported logic from old onDisconnected (plus onReconnected drain
                        // logic if applicable). Stop audio if not draining, clear metadata,
                        // release locks, broadcast.
                    }
                    state is TransportState.Failed -> {
                        // Ported logic from old onError. The reason field carries the
                        // FailureReason classification.
                    }
                    state is TransportState.Connecting && prevState is TransportState.Failed -> {
                        // Ported logic from old onReconnecting (or its functional
                        // equivalent). The Coordinator's retry loop is what advances
                        // the attempt counter; this collector just observes the
                        // resulting state transitions.
                    }
                }
                prevState = state
            }
        }
```

The `// Insert the body...` placeholders MUST be replaced with the actual code from the deleted callback methods. The implementer must read each deleted method and port its logic into the matching `when` branch. **Do NOT leave placeholder comments.**

Some of the old callbacks took parameters (e.g., `onConnected(serverName: String)`). The serverName is now retrieved via `sendSpinClient?.getServerName()` (which Task 4 wired to a separate field). `wasUserInitiated` and `wasReconnectExhausted` from the old `onDisconnected` are no longer needed in the same way — the Coordinator's retry-state flow already exposes exhaustion (`Failed(Exhausted)`), and user-initiated disconnect is implicit (the user-disconnect path goes through `coordinator.disconnect()` which is the only thing that takes the transport to `Idle` non-failing).

- [ ] **Step 3: Delete the five callback methods from `SendSpinClientCallback`**

Edit the interface in `SendSpinClient.kt`. Remove:

```kotlin
fun onConnected(serverName: String)
fun onDisconnected(wasUserInitiated: Boolean = false, wasReconnectExhausted: Boolean = false)
fun onError(message: String)
fun onReconnecting(attempt: Int, serverName: String)
fun onReconnected()
```

Remove the matching `override` implementations from PlaybackService's callback impl.

Anywhere SendSpinClient's INTERNAL code calls `callback.onConnected(...)` / `.onDisconnected(...)` / `.onError(...)` / `.onReconnecting(...)` / `.onReconnected(...)` — replace with the appropriate `_connectionState.value = TransportState.X`. Note that several of these calls were already added in Phase 2B Task 2 (the `selfReconnectEnabled = false` branch). Verify those still emit the right states.

- [ ] **Step 4: Build and run tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt \
        android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "refactor(sendspin): remove state callbacks; PlaybackService observes flow

Phase 4. SendSpinClientCallback no longer has onConnected, onDisconnected,
onError, onReconnecting, or onReconnected -- those duplicated the state
flow. PlaybackService now drives the same work (foreground service,
audio drain, MediaSession broadcasts, error UI) from a
connectionState.collect { ... } block. The Coordinator's retry-state
flow handles 'wasReconnectExhausted'-style signals; user-initiated
disconnect routes through coordinator.disconnect() which takes the
transport to Idle.

Streaming and metadata callbacks (onAudioChunk, onMetadataUpdate,
onArtwork, onStreamStart/End, onVolumeChanged, etc.) STAY -- their
hot-path / binary nature makes the callback shape preferable to a
Flow."
```

---

## Task 6: Rename `SendSpinClient` → `SendSpin`

**Files:**
- Rename: `sendspin/SendSpinClient.kt` → `sendspin/SendSpin.kt`
- Rename: class `SendSpinClient` → `SendSpin`
- Rename: interface `SendSpinClientCallback` → `SendSpinListener` (and matching impl class names)
- Update: ~10 caller files

Per the surface map, only PlaybackService and ConnectionCoordinator directly reference `SendSpinClient`. UI goes through MediaSession. So the rename's blast radius is small.

- [ ] **Step 1: Find all references**

```bash
grep -rn "SendSpinClient\|SendSpinClientCallback" android/app/src/ | grep -v ".kt:.*//"
```

Should be roughly: ~30-50 lines across ~5-10 files (PlaybackService, ConnectionCoordinator, the SendSpinClient.kt file itself, possibly any test files). Note all the locations.

- [ ] **Step 2: File rename**

```bash
git mv android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt \
       android/app/src/main/java/com/sendspindroid/sendspin/SendSpin.kt
```

- [ ] **Step 3: Class and interface rename inside the file**

Edit `SendSpin.kt`:
- `class SendSpinClient` → `class SendSpin`
- `interface SendSpinClientCallback` → `interface SendSpinListener`
- Update any `SendSpinClient.ConnectionMode` to `SendSpin.ConnectionMode`
- Update any other `SendSpinClient.*` qualified references inside the file

- [ ] **Step 4: Update PlaybackService**

```bash
sed -i 's/SendSpinClient/SendSpin/g; s/SendSpinClientCallback/SendSpinListener/g' \
    android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
```

(Or do it manually per Edit tool. The sed approach is fine if you spot-check the diff.)

Watch for false positives: the file may have a comment or string literal containing "SendSpinClient" that shouldn't change. Inspect the diff before committing.

- [ ] **Step 5: Update ConnectionCoordinator**

Same treatment for `ConnectionCoordinator.kt` if it contains references (likely just import statements + KDoc comments; the comments are still valid historical references but the imports must be updated).

- [ ] **Step 6: Update any other caller**

The grep at Step 1 shows the full list. Update each.

- [ ] **Step 7: Build and run tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green.

- [ ] **Step 8: Commit**

```bash
git add -A android/app/src/
git commit -m "refactor(sendspin): rename SendSpinClient to SendSpin

Phase 4 final. The class is now named SendSpin to match its actual role
(the SendSpin transport client). Its callback interface is renamed to
SendSpinListener for symmetry. File renamed accordingly. All call sites
in PlaybackService and ConnectionCoordinator updated.

Phase 4 is now complete: the SendSpin class exposes a single
connect(endpoint) entry point taking a sealed SendSpinEndpoint, surfaces
TransportState (with FailureReason classification on Failed) directly
via connectionState, and has dropped the state-management half of its
callback interface. Phase 5 next addresses the WiFi->Cell login bug
using the new AuthRejected classification."
```

---

## Task 7: Verify Phase 4 end-to-end

- [ ] **Step 1: Full unit test suite + release build**

```bash
cd android && ./gradlew :app:testDebugUnitTest :app:assembleRelease
```

All green. The new `SendSpinEndpointTest` tests pass.

- [ ] **Step 2: Confirm references**

```bash
grep -rn "SendSpinClient\|SendSpinClientCallback" android/app/src/main/ | grep -v "\.kt:.*//" || echo "(no live references)"
```

Should be empty (all renamed) — comments referencing the old name are OK, but live code references should be zero.

```bash
grep -rn "SendSpinEndpoint" android/app/src/main/
```

Should appear in: `SendSpinEndpoint.kt` (definition), `SendSpin.kt` (the connect facade), `PlaybackService.kt` (the three call sites).

- [ ] **Step 3: Manual smoke test**

The behavior should be **identical to Phase 3**. Anything visibly different is a regression.

1. Connect / playback / disconnect — basic round-trip.
2. Airplane mode toggle — Reconnecting UI advances through attempts; resumes on network return.
3. Provoke a fast-fail authentication (e.g. a proxy with a wrong token) — confirm the Failed state shows `FailureReason.AuthRejected` (visible in logcat or by error message), NOT generic Error.
4. Provoke a network-level failure (e.g. wrong port) — confirm `FailureReason.TransientNetwork` (or `HandshakeFailed`).
5. Rotation during reconnect — Phase 2A regression check.
6. Walk WiFi → Cell handover — confirm reselection still works (Phase 3 regression check).

If FailureReason classification is wrong in any of (3) or (4), file as a Phase 4 follow-up and tighten the `classifyFailureReason` heuristic.

---

## Self-Review Notes

- **Spec coverage:** Phase 4 satisfies the design's §9 Phase 4 row (rename + simplification + sealed Endpoint + StateFlow<TransportState>) WITH the explicit decision to keep streaming/metadata callbacks. The decision is documented in the goal statement and Task 5's prose.
- **Risk surfaces:**
  - Task 5's `connectionState.collect { ... }` block is the riskiest single change. The placeholder comments MUST be replaced with the actual ported logic from the five deleted callbacks, and edge cases (e.g., what if state.collect emits after `serviceScope.cancel()`) need to honor the existing service lifecycle. Read the existing callbacks carefully before porting.
  - Task 4's `classifyFailureReason` is a heuristic. Phase 5's WiFi->Cell login fix depends on `AuthRejected` being correctly identified. If beta testing shows the classifier mis-labels, that's a tightening pass.
  - Task 6 (rename) crosses many files. Before committing, verify no stray `SendSpinClient` references remain in live code paths.
- **Phase 5 prerequisite:** The `FailureReason.AuthRejected` classification path is the foundation of Phase 5's "only clear MA token on confirmed auth rejection" fix. Phase 4 establishes the infrastructure; Phase 5 wires it into the MA token-clearing decision.
