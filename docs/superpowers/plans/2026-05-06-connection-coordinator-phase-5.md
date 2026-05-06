# ConnectionCoordinator Phase 5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the WiFi → Cell login bug AND tighten the Music Assistant transport API. The original user complaint ("why am I being asked to log in again after going from WiFi to Cell?") is resolved by Task 1; the rest of the phase is architectural cleanup parallel to Phase 4's SendSpin work.

**The bug, isolated:** `MusicAssistantManager.connectWithToken` line 414-419 unconditionally passes `clearTokenForServer = serverId` to `handleConnectionFailure`, which then unconditionally clears the stored token on line 283. Result: any exception during connect — including `IOException` from a transient network handover — wipes the token. The user's still-valid credentials get destroyed by a transient network blip.

**The fix:** classify the exception before passing the clear-flag. Only clear if `e is MaApiTransport.AuthenticationException` (which the transport throws specifically for 401/403 from a fully-handshaked server). All other exception types preserve the token.

**Architecture after this phase:**

```
MusicAssistant (renamed from MusicAssistantManager — singleton object stays)
   |
   +-- val connectionState: StateFlow<TransportState>
   |       Idle | Connecting | Ready | Failed(FailureReason)
   |       (replaces MaConnectionState's Unavailable/NeedsAuth/Connecting/Connected/Error(isAuthError))
   |
   +-- fun connect(endpoint: MaEndpoint, token: String?)
   |       MaEndpoint.Local(address, port)   # WS to local MA
   |       MaEndpoint.Proxy(baseUrl)         # via proxy
   |       MaEndpoint.Remote(remoteId)       # WebRTC DataChannel
   |
   +-- fun login(username, password)         # unchanged
   +-- fun clearAuth()                       # unchanged
   +-- ...rest of public surface unchanged...

Single token-clearing call site:
   connectWithToken's exception handler classifies the exception.
   AuthRejected -> clearTokenForServer; everything else -> preserve.
```

**Tech Stack:** Kotlin, kotlinx.coroutines, JUnit 4, MockK, kotlinx-coroutines-test.

**Reference spec:** `docs/superpowers/specs/2026-05-05-connection-coordinator-design.md` Phase 5 row in §9.

---

## File Structure

**Create:**
- `android/app/src/main/java/com/sendspindroid/musicassistant/MaEndpoint.kt` — sealed class

**Modify (eventually rename):**
- `android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistantManager.kt` — eventually renamed to `MusicAssistant.kt` in Task 5.
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` — `MaConnectionState.toTransportState()` helper deletes; mapping no longer needed.

**Don't touch:**
- `MaSettings.kt` — token storage API stays.
- `MaApiTransport.kt` — exception types already exist; no changes needed.
- `coordinator/TransportState.kt`, `coordinator/FailureReason.kt`.

---

## Task 1: Fix the WiFi → Cell login bug

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistantManager.kt`

This is the user-impactful change. Only the exception handlers in `connectWithToken` and `authWithToken` change. `handleConnectionFailure` itself is unchanged.

The bug is that today's code passes `clearTokenForServer = serverId` regardless of which exception type fired. After this task, the parameter is `serverId` ONLY when the exception is `MaApiTransport.AuthenticationException` (which the transport throws specifically for 401/403 from a fully-handshaked server). For any other exception type — `IOException`, `MaTransportException`, generic exceptions — the parameter stays `null` and the token is preserved.

This fix is small enough to ship and validate independently.

- [ ] **Step 1: Find the call sites**

```bash
grep -n "handleConnectionFailure" android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistantManager.kt
```

Should show ~3 callers: `connectWithToken` (line ~417), `login` (line ~477), `authWithToken` (line ~502).

- [ ] **Step 2: Read the current bodies**

Read each catch block. Note:
- `connectWithToken` — passes `clearTokenForServer = serverId` (BUG: unconditional)
- `login` — passes `clearTokenForServer = null` (correct already; the user is trying new credentials)
- `authWithToken` — passes `clearTokenForServer = server.id` (BUG: unconditional, same as connectWithToken)

- [ ] **Step 3: Update `connectWithToken`'s catch block**

Find the catch block around line 413-419. Replace the unconditional `serverId` argument with a conditional based on exception type:

```kotlin
            } catch (e: Exception) {
                connectJob = null
                // Only clear the stored token if the server actually rejected it.
                // Transient errors (network, transport, generic IO) preserve the token
                // so the user isn't forced to re-login on a brief WiFi->Cell handover.
                val authRejected = e is MaApiTransport.AuthenticationException
                handleConnectionFailure(
                    e = e,
                    logPrefix = "connectWithToken",
                    clearTokenForServer = if (authRejected) serverId else null,
                )
            }
```

- [ ] **Step 4: Update `authWithToken`'s catch block**

Find the catch block around line 498-503. Apply the same conditional:

```kotlin
            } catch (e: Exception) {
                connectJob = null
                val authRejected = e is MaApiTransport.AuthenticationException
                handleConnectionFailure(
                    e = e,
                    logPrefix = "authWithToken",
                    clearTokenForServer = if (authRejected) server.id else null,
                )
                return Result.failure(e)
            }
```

(Adapt the `server.id` reference to whatever the existing code uses — it might be `currentServer?.id` or similar. Read the surrounding code.)

- [ ] **Step 5: Build and run tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green. The existing tests should still pass — they likely cover login/logout flows but probably not the specific WiFi→Cell scenario. If any test breaks because it depended on the bug behavior (e.g., asserting that token IS cleared on a transient error), update or delete that assertion as a known-buggy expectation.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistantManager.kt
git commit -m "fix(musicassistant): only clear MA token on confirmed auth rejection

The WiFi->Cell login bug: when the device switches networks, MA's
transport fails with a transient IOException, triggering
handleConnectionFailure. Today's code unconditionally clears the stored
token (because connectWithToken / authWithToken passed
clearTokenForServer = serverId regardless of exception type), forcing
re-login even though the user's credentials are still valid.

The fix classifies the exception: only e is
MaApiTransport.AuthenticationException (server actually returned 401/403
after a completed transport handshake) clears the token. IOException,
MaTransportException, and any other exception preserve the token; the
Coordinator's retry logic will reconnect using the surviving token
once the network stabilizes.

This is the user-impactful payoff of the ConnectionCoordinator design.
Phase 5 continues with architectural cleanup paralleling Phase 4
(MaEndpoint, TransportState migration, MusicAssistantManager rename),
but those are pure refactors -- the user-visible fix lands here."
```

---

## Task 2: Add `MaEndpoint` sealed class

**Files:**
- Create: `android/app/src/main/java/com/sendspindroid/musicassistant/MaEndpoint.kt`
- Create: `android/app/src/test/java/com/sendspindroid/musicassistant/MaEndpointTest.kt`

Pure additive type, parallels `SendSpinEndpoint` from Phase 4.

- [ ] **Step 1: Read MA's connection-establishment paths**

Read `MusicAssistantManager.connectWithToken` (line 405) and the URL-derivation logic above it. The current parameters are `apiUrl` and `token`. The `apiUrl` is already a derived string (constructed by `onServerConnected` based on `connectionMode`).

The three logical "endpoints" for MA mirror SendSpin's:
- **Local**: WebSocket to a host:port pair (the MA service on the LAN)
- **Proxy**: WebSocket to a proxy URL with auth
- **Remote**: WebRTC DataChannel via Music Assistant Remote Access (`maApiDataChannel`)

Inspect `onServerConnected` (line 197) to see exactly how it derives the apiUrl from `connectionMode` (LOCAL / REMOTE / PROXY) — that's the spec for the sealed class.

- [ ] **Step 2: Write the failing test**

Create `android/app/src/test/java/com/sendspindroid/musicassistant/MaEndpointTest.kt`:

```kotlin
package com.sendspindroid.musicassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class MaEndpointTest {
    @Test
    fun `Local carries address`() {
        val e: MaEndpoint = MaEndpoint.Local("10.0.1.5", 8095)
        assertEquals("10.0.1.5", (e as MaEndpoint.Local).address)
        assertEquals(8095, e.port)
    }

    @Test
    fun `Proxy carries baseUrl`() {
        val e: MaEndpoint = MaEndpoint.Proxy("https://example.com")
        assertEquals("https://example.com", (e as MaEndpoint.Proxy).baseUrl)
    }

    @Test
    fun `Remote carries remoteId`() {
        val e: MaEndpoint = MaEndpoint.Remote("ABC123")
        assertEquals("ABC123", (e as MaEndpoint.Remote).remoteId)
    }

    @Test
    fun `when expression is exhaustive`() {
        val cases: List<MaEndpoint> = listOf(
            MaEndpoint.Local("a", 1),
            MaEndpoint.Proxy("u"),
            MaEndpoint.Remote("r"),
        )
        val labels = cases.map {
            when (it) {
                is MaEndpoint.Local -> "local"
                is MaEndpoint.Proxy -> "proxy"
                is MaEndpoint.Remote -> "remote"
            }
        }
        assertEquals(listOf("local", "proxy", "remote"), labels)
    }
}
```

If the actual `MaEndpoint.Local` constructor needs different fields than `(address: String, port: Int)` based on what `onServerConnected` does today, adapt. Read `onServerConnected` first to find out the actual fields. **STOP and report** if the parameters don't fit cleanly.

- [ ] **Step 3: Run, expect failure**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.musicassistant.MaEndpointTest
```

Expected: FAIL ("unresolved reference: MaEndpoint").

- [ ] **Step 4: Implement the type**

Create `android/app/src/main/java/com/sendspindroid/musicassistant/MaEndpoint.kt`:

```kotlin
package com.sendspindroid.musicassistant

/**
 * Endpoint a Music Assistant connection targets. Replaces the implicit
 * URL-derivation in MusicAssistantManager.onServerConnected with a typed
 * sealed class.
 *
 * Phase 5 of the ConnectionCoordinator design.
 * See docs/superpowers/specs/2026-05-05-connection-coordinator-design.md
 */
sealed class MaEndpoint {
    /** Direct WebSocket to MA running on the local network. */
    data class Local(val address: String, val port: Int) : MaEndpoint()

    /** WebSocket via authenticated reverse proxy. */
    data class Proxy(val baseUrl: String) : MaEndpoint()

    /** WebRTC DataChannel via Music Assistant Remote Access. */
    data class Remote(val remoteId: String) : MaEndpoint()
}
```

Adapt the field shapes if `onServerConnected` uses different parameters today.

- [ ] **Step 5: Run, expect pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.musicassistant.MaEndpointTest
```

Expected: 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/musicassistant/MaEndpoint.kt \
        android/app/src/test/java/com/sendspindroid/musicassistant/MaEndpointTest.kt
git commit -m "feat(musicassistant): add MaEndpoint sealed class

Phase 5 of ConnectionCoordinator design. Three sealed-class variants
mirror MA's three connection paths (Local / Proxy / Remote). Parallels
SendSpinEndpoint from Phase 4. Used by the connect(endpoint, token)
facade added in the next commit."
```

---

## Task 3: Migrate `MaConnectionState` → `TransportState`

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistantManager.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

Parallels Phase 4's SendSpin migration. After this task:
- `MusicAssistantManager.connectionState` is `StateFlow<TransportState>` directly.
- The `MaConnectionState.Error(message, isAuthError)` becomes `TransportState.Failed(FailureReason)`.
- The `Unavailable / NeedsAuth` arms collapse into `TransportState.Idle`.
- The `MaConnectionState` sealed class itself can be deleted (or kept as an internal-only type if other code references it; probably the cleanest is to delete it).
- PlaybackService's `MaConnectionState.toTransportState()` mapping helper deletes.

The `isAuthError` boolean disappears — it's now expressed via `Failed(FailureReason.AuthRejected)`. The `needsUserAction` helper that previously distinguished `NeedsAuth` from `Error(isAuthError = true)` becomes a simple equality check on the FailureReason.

- [ ] **Step 1: Map the existing arms to TransportState**

| Old | New |
|---|---|
| `MaConnectionState.Unavailable` | `TransportState.Idle` |
| `MaConnectionState.NeedsAuth` | `TransportState.Idle` (UI distinguishes via observing `MaSettings.getTokenForServer(serverId)` if needed) |
| `MaConnectionState.Connecting` | `TransportState.Connecting` |
| `MaConnectionState.Connected(serverInfo)` | `TransportState.Ready` (the `serverInfo` moves to a separate field, like Phase 4's `connectedServerName`) |
| `MaConnectionState.Error(msg, isAuthError = true)` | `TransportState.Failed(FailureReason.AuthRejected)` |
| `MaConnectionState.Error(msg, isAuthError = false)` | `TransportState.Failed(FailureReason.TransientNetwork)` (or `HandshakeFailed` if the message indicates a handshake-level failure — be conservative; default to TransientNetwork) |

- [ ] **Step 2: Add a `currentServerInfo` field**

Today's `MaConnectionState.Connected(serverInfo)` carries the MaServerInfo. After migration, store it separately:

```kotlin
private var currentServerInfo: MaServerInfo? = null
```

Update `getCurrentServerInfo()` if such a getter exists (or add a public accessor for whichever method needs it).

- [ ] **Step 3: Update `MusicAssistantManager.kt`**

Add imports:
```kotlin
import com.sendspindroid.coordinator.FailureReason
import com.sendspindroid.coordinator.TransportState
```

Replace:
```kotlin
private val _connectionState = MutableStateFlow<MaConnectionState>(MaConnectionState.Unavailable)
val connectionState: StateFlow<MaConnectionState> = _connectionState.asStateFlow()
```

With:
```kotlin
private val _connectionState = MutableStateFlow<TransportState>(TransportState.Idle)
val connectionState: StateFlow<TransportState> = _connectionState.asStateFlow()
```

For every place that does `_connectionState.value = MaConnectionState.X(...)`, apply the mapping table from Step 1. For example:
```kotlin
// Old: _connectionState.value = MaConnectionState.Connected(serverInfo)
currentServerInfo = serverInfo
_connectionState.value = TransportState.Ready

// Old: _connectionState.value = MaConnectionState.Error(message, isAuthError = true)
_connectionState.value = TransportState.Failed(FailureReason.AuthRejected)

// Old: _connectionState.value = MaConnectionState.Error(message, isAuthError = false)
_connectionState.value = TransportState.Failed(FailureReason.TransientNetwork)
```

In `handleConnectionFailure`, the existing line ~290+ that builds the state should now classify based on the exception type:

```kotlin
val reason: FailureReason = when (e) {
    is MaApiTransport.AuthenticationException -> FailureReason.AuthRejected
    is MaTransportException, is IOException -> FailureReason.TransientNetwork
    else -> FailureReason.TransientNetwork
}
_connectionState.value = TransportState.Failed(reason)
```

The `message` parameter of `Error` is no longer carried in the new state. If consumers needed the exact message, they'll need to derive a user-facing string from the FailureReason instead. (Most likely consumers showed a snackbar based on `isAuthError` — that's now `state is Failed && state.reason is AuthRejected`.)

The `clearTokenForServer` parameter on `handleConnectionFailure` can ALSO be removed entirely now: the function can decide internally based on the classified FailureReason. Even better: hoist the token-clearing OUT of `handleConnectionFailure` to a single Coordinator-level observer (PlaybackService observes `musicAssistant.connectionState` and clears when it sees `Failed(AuthRejected)`). This is the design's "single token-clearing site" goal.

For Task 3, simpler: keep the existing `clearTokenForServer` parameter but have the function compute it from the reason instead of taking it as an argument. The callers stop passing it. This is a smaller, contained change.

Update each caller (`connectWithToken`, `login`, `authWithToken`):
```kotlin
// Old:
handleConnectionFailure(e, "connectWithToken", clearTokenForServer = if (authRejected) serverId else null)

// New (handleConnectionFailure decides):
handleConnectionFailure(e, "connectWithToken")
```

And update `handleConnectionFailure` to internally classify and clear:
```kotlin
private fun handleConnectionFailure(e: Exception, logPrefix: String) {
    Log.e(TAG, "$logPrefix failed", e)
    apiTransport?.setEventListener(null)
    apiTransport?.disconnect()
    apiTransport = null
    commandClient.setTransport(null, null, false)

    val reason: FailureReason = when (e) {
        is MaApiTransport.AuthenticationException -> FailureReason.AuthRejected
        is MaTransportException, is IOException -> FailureReason.TransientNetwork
        else -> FailureReason.TransientNetwork
    }

    val server = currentServer
    if (reason is FailureReason.AuthRejected && server != null) {
        MaSettings.clearTokenForServer(server.id)
    }

    _connectionState.value = TransportState.Failed(reason)
}
```

The Task 1 fix (only AuthRejected clears token) is now structurally enforced — `handleConnectionFailure` itself is the gate, and the caller can't accidentally pass a wrong serverId.

- [ ] **Step 4: Update PlaybackService**

Find the `MaConnectionState.toTransportState()` extension function in `PlaybackService.kt` (added in Phase 1 Task 4). DELETE it entirely along with its KDoc.

Update the Coordinator construction call. The line that maps the flow:
```kotlin
musicAssistantStateFlow = MusicAssistantManager.connectionState.map { it.toTransportState() }
```
becomes:
```kotlin
musicAssistantStateFlow = MusicAssistantManager.connectionState
```

Search for any other references to `MaConnectionState.X` in PlaybackService and update.

- [ ] **Step 5: Delete `MaConnectionState`**

After Steps 3 and 4, run:
```bash
grep -rn "MaConnectionState" android/app/src/ --include="*.kt"
```

If only the file `MaConnectionState.kt` itself contains references (the sealed class definition + its helper methods), DELETE the file:
```bash
git rm android/shared/src/commonMain/kotlin/com/sendspindroid/musicassistant/model/MaConnectionState.kt
```

(Adjust the path — the agent's earlier mapping put it in shared/commonMain.)

If MaConnectionState is referenced from MainActivity or other UI code, leave the file in place and update those references first. Run the grep, then decide.

- [ ] **Step 6: Build and run tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green. Tests that referenced MaConnectionState (per Phase 4 Task 4 we saw 7 such tests for SendSpinClient) will need updating. Apply the same pattern.

- [ ] **Step 7: Commit**

```bash
git add -A android/
git commit -m "refactor(musicassistant): expose StateFlow<TransportState>; hoist token clear

Phase 5. MusicAssistantManager.connectionState now emits TransportState
directly (Idle / Connecting / Ready / Failed(reason)) instead of the
local MaConnectionState sealed class. Failures carry a FailureReason
classified from the exception type (AuthenticationException ->
AuthRejected; everything else -> TransientNetwork).

handleConnectionFailure now internally clears the token if and only
if the classified FailureReason is AuthRejected. Callers
(connectWithToken / login / authWithToken) no longer pass
clearTokenForServer -- the gate is structural. Task 1's tactical fix
becomes architectural here.

PlaybackService's MaConnectionState.toTransportState mapping helper
deletes (the source is now already TransportState). The
MaConnectionState sealed class itself deletes [or moves] -- the new
state machine is unified with SendSpin's."
```

---

## Task 4: Add `connect(endpoint, token?)` facade

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistantManager.kt`

Add a new public method that takes an `MaEndpoint` and an optional token and dispatches to the existing `connectWithToken / authWithToken / login` logic internally. Keep the existing methods for backward compat / specific UI paths.

The facade:

```kotlin
    /**
     * Connect to the given MA endpoint, optionally with a stored token.
     *
     * Phase 5 facade. Internally:
     * - With token: calls connectWithToken (auth via stored credentials).
     * - Without token: emits NeedsAuth (transport-level Idle) so UI prompts login.
     */
    suspend fun connect(endpoint: MaEndpoint, token: String?) {
        val apiUrl = when (endpoint) {
            is MaEndpoint.Local -> "ws://${endpoint.address}:${endpoint.port}/ws"
            is MaEndpoint.Proxy -> {
                val ws = endpoint.baseUrl.replaceFirst("https://", "wss://")
                    .replaceFirst("http://", "ws://")
                "$ws/ws"
            }
            is MaEndpoint.Remote -> {
                // For Remote, the data channel is supplied via setMaApiDataChannel.
                // The endpoint's remoteId is informational; the actual transport is
                // the DataChannel that PlaybackService passes in.
                "datachannel://${endpoint.remoteId}/ws"
            }
        }

        if (token != null && currentServer != null) {
            connectWithToken(apiUrl, token, currentServer!!.id)
        } else {
            // No token; transport-level state is Idle (UI prompts login).
            _connectionState.value = TransportState.Idle
        }
    }
```

Adapt the URL construction to match exactly what `onServerConnected`'s URL-derivation does today. Read it before writing the facade body.

`onServerConnected` can be REWRITTEN to use the new facade:

```kotlin
    fun onServerConnected(server: UnifiedServer, mode: ConnectionMode) {
        if (!server.isMusicAssistant) {
            _connectionState.value = TransportState.Idle
            return
        }
        currentServer = server
        currentConnectionMode = mode

        val endpoint = when (mode) {
            ConnectionMode.LOCAL -> MaEndpoint.Local(
                address = server.local!!.address,
                port = MaSettings.getDefaultPort(),
            )
            ConnectionMode.PROXY -> MaEndpoint.Proxy(server.proxy!!.url)
            ConnectionMode.REMOTE -> MaEndpoint.Remote(server.remote!!.remoteId)
        }

        val token = MaSettings.getTokenForServer(server.id)
        coroutineScope.launch { connect(endpoint, token) }
    }
```

The existing `onServerConnected` is bigger than this — preserve any logic it does that isn't covered by the facade (e.g., setting `apiUrl`, scheduling). The implementer should compare carefully.

- [ ] **Step 1: Read `onServerConnected` and `connectWithToken` carefully**

- [ ] **Step 2: Add the `connect(endpoint, token)` facade**

Add to `MusicAssistantManager.kt` near the existing `connectWithToken` method.

- [ ] **Step 3: Refactor `onServerConnected` to use the facade**

Make the URL construction live in `connect(endpoint, token)`, not in `onServerConnected`.

- [ ] **Step 4: Build and run tests**

Both green.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistantManager.kt
git commit -m "feat(musicassistant): add connect(endpoint, token) facade

Phase 5. Single entry point taking an MaEndpoint sealed class plus an
optional token. Internally derives the apiUrl (Local -> ws://, Proxy ->
wss://, Remote -> datachannel://) and dispatches to connectWithToken
when a token is present, or transitions to Idle (UI login prompt) when
not. onServerConnected refactored to construct an MaEndpoint and call
the facade -- URL-derivation is no longer scattered."
```

---

## Task 5: Rename `MusicAssistantManager` → `MusicAssistant`

**Files:**
- Rename: `MusicAssistantManager.kt` → `MusicAssistant.kt`
- Modify: every file that references `MusicAssistantManager`

Parallels Phase 4 Task 6's SendSpinClient → SendSpin rename.

- [ ] **Step 1: Find all references**

```bash
grep -rn "MusicAssistantManager" android/app/src/ --include="*.kt"
```

Note the file count and line count. Per the surface map this is mostly PlaybackService + MainActivity + a few tests.

- [ ] **Step 2: File rename**

```bash
git mv android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistantManager.kt \
       android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistant.kt
```

- [ ] **Step 3: Class rename inside the file**

```kotlin
// Old:
object MusicAssistantManager { ... }
// New:
object MusicAssistant { ... }
```

The singleton object name changes. Any qualified self-references inside the file (`MusicAssistantManager.X`) update to `MusicAssistant.X`.

- [ ] **Step 4: Update PlaybackService**

```bash
grep -n "MusicAssistantManager" android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
```

Replace each. Imports + usages.

- [ ] **Step 5: Update MainActivity**

```bash
grep -n "MusicAssistantManager" android/app/src/main/java/com/sendspindroid/MainActivity.kt
```

Replace each.

- [ ] **Step 6: Update tests**

```bash
grep -rn "MusicAssistantManager" android/app/src/test/ --include="*.kt"
```

Replace each.

- [ ] **Step 7: Update any other production callers**

```bash
grep -rn "MusicAssistantManager" android/app/src/main/ --include="*.kt"
```

Should be empty after Steps 4-5. If stragglers remain, address them.

- [ ] **Step 8: Build and run tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green.

- [ ] **Step 9: Commit**

```bash
git add -A android/app/src/
git commit -m "refactor(musicassistant): rename MusicAssistantManager to MusicAssistant

Phase 5 final. The singleton is renamed to MusicAssistant for parallelism
with SendSpin (Phase 4). File renamed accordingly. All call sites in
PlaybackService, MainActivity, and tests updated.

Phase 5 is now complete: the WiFi->Cell login bug is fixed (Task 1),
MaEndpoint provides a typed connect surface (Task 2), MaConnectionState
is replaced by TransportState with FailureReason classification (Task 3),
connect(endpoint, token) unifies the connection paths (Task 4), and the
class is renamed for consistency with SendSpin (this commit)."
```

---

## Task 6: Verify Phase 5 end-to-end

- [ ] **Step 1: Full test suite + release build**

```bash
cd android && ./gradlew :app:testDebugUnitTest :app:assembleRelease
```

All green.

- [ ] **Step 2: Confirm references**

```bash
grep -rn "MusicAssistantManager\|MaConnectionState" android/app/src/main/ --include="*.kt"
```

Should be empty (live code) — only KDoc / comments may reference historical names.

```bash
grep -rn "MaEndpoint" android/app/src/main/
```

Should appear in: `MaEndpoint.kt` (definition), `MusicAssistant.kt` (the connect facade + onServerConnected), and any other consumers.

- [ ] **Step 3: Manual smoke test — the actual user-impactful test**

The bug-fix scenario:

1. On the Pixel 7, log in to MA on a server. Confirm playback works.
2. Walk WiFi → Cell (or simulate by toggling WiFi off while leaving cellular on).
3. Wait for the reconnect to settle.
4. **Verify: NO login prompt appears.** The session continues seamlessly. This is the user-impactful win.

If a login prompt DOES appear, check logcat for `TransportState.Failed` events:
- `Failed(AuthRejected)` would mean MA's server actually returned 401 — token clear is correct.
- `Failed(TransientNetwork)` or `Failed(HandshakeFailed)` would mean the token should NOT have been cleared. If this happens but login is still prompted, the bug is elsewhere — the token was cleared somehow else.

- [ ] **Step 4: Other validations**

5. Provoke a real auth failure (e.g., revoke the user's token via MA admin UI, then try to play something). Confirm the login prompt DOES appear, with `Failed(AuthRejected)` in logcat.
6. Connect / disconnect / reconnect — basic round-trip.
7. Rotation during reconnect (Phase 2A regression check).

---

## Self-Review Notes

- **Spec coverage:** Phase 5 satisfies the design's Phase 5 row in §9 — both the WiFi→Cell login fix AND the architectural alignment (rename, MaEndpoint, TransportState migration).
- **The bug-fix value:** Task 1 alone delivers the user-impactful win. Even if Phase 5 stops at Task 1 for any reason, the user gets relief. Tasks 2-5 are pure refactors.
- **Token clearing single-site:** After Task 3, the only place that calls `MaSettings.clearTokenForServer(...)` is `handleConnectionFailure` (when it classifies `Failed(AuthRejected)`) and `clearAuth()` (explicit logout). The Phase-2A pattern of "scattered conditionals deciding when to clear" is gone.
- **Risk surfaces:**
  - Task 3's MaConnectionState deletion may need to be deferred if MainActivity's UI directly switches on `MaConnectionState.NeedsAuth` vs `MaConnectionState.Error(isAuthError = true)`. The new model expresses both as `Failed(AuthRejected)` or `Idle` — UI may need to also check `MaSettings.getTokenForServer(serverId)` to distinguish them. Read MainActivity's UI logic before deleting MaConnectionState.
  - Task 4's URL construction (Local → `ws://`, Proxy → `wss://`) needs to match exactly what `onServerConnected` does today. Get this wrong and connections silently fail.
- **Phase 5 is the END of the user's original request.** After this lands and is validated, the WiFi→Cell login bug is fixed and the Coordinator design's primary architectural goals are achieved. Phases 6 (wizard test path) and 7 (state-of-truth cleanup) remain as polish.
