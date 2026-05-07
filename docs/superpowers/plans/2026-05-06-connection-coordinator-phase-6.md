# ConnectionCoordinator Phase 6 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Wizard test connections use the same transport code path as live connections. After this phase, a fix to MA auth handling (or SendSpin handshake logic) reaches the wizard's "Test Connection" button automatically — no duplicate OkHttp client to keep in sync.

**Pragmatic adaptation from the design:** the design said "transient SendSpin/MusicAssistant instances." `SendSpin` is a class (Phase 4) so this works literally. `MusicAssistant` is a singleton object (Phase 5 kept it that way to avoid touching 25+ caller sites). For MA we use a stateless helper function `testMaAuth(endpoint, credentials)` that creates a temporary `MaApiTransport` and runs the same auth code the singleton uses. End result is the same: no duplicated transport/auth logic in the wizard.

**Architecture after this phase:**

```
AddServerWizardActivity
   |
   +-- SendSpin tests:
   |     val transient = SendSpin(context, deviceName, listener = noop)
   |     transient.connect(SendSpinEndpoint.Local(...))
   |     transient.connectionState.first { it is Ready || it is Failed }
   |     transient.disconnect()
   |
   +-- MA tests:
         val result = testMaAuth(MaEndpoint.X(...), MaCredentials.Token(...))
         result.onSuccess { ... } / onFailure { ... }

(deleted: 3 OkHttpClient.Builder() constructions in AddServerWizardActivity)
(deleted: MaApiTransport scaffolding in AddServerWizardViewModel.testMaConnection)
```

**What stays the same:**

- Production live-connection paths: PlaybackService still constructs the SendSpin instance and uses the MusicAssistant singleton.
- The `MaApiTransport` interface and its impls.
- The `SendSpin` class API (already taking SendSpinEndpoint as of Phase 4).

**Tech Stack:** Kotlin, kotlinx.coroutines.

**Reference spec:** `docs/superpowers/specs/2026-05-05-connection-coordinator-design.md` Phase 6 row in §9.

---

## Task 1: Wizard SendSpin tests use transient `SendSpin` instances

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/ui/server/AddServerWizardActivity.kt`

The three test functions today each construct an `OkHttpClient.Builder()` and run their own WebSocket handshake. Replace each with construction + use of a transient `SendSpin` instance.

- [ ] **Step 1: Read each existing test method**

```bash
grep -n "fun testLocalConnection\|fun testRemoteConnection\|fun testProxyConnection\|OkHttpClient" android/app/src/main/java/com/sendspindroid/ui/server/AddServerWizardActivity.kt
```

Read the body of each:
- `testLocalConnection(address: String)` at ~line 358 — uses `OkHttpClient.Builder()` at line 369
- `testRemoteConnection(remoteId: String)` at ~line 453 — note whether it uses an OkHttp client or WebRTC signaling
- The third OkHttp client at ~line 536 — likely inside a proxy test method

For each, note:
- The success criteria (what state means "test passed"?)
- The timeout value
- What error info gets surfaced to the UI (just success/fail Boolean? Or an error message?)
- Cleanup on cancel/Activity-finish — where the WebSocket gets closed

The test methods return things like `Result<Int>` or `Result<String>` — preserve those return types.

- [ ] **Step 2: Refactor `testLocalConnection`**

Replace the OkHttpClient setup with:

```kotlin
    private suspend fun testLocalConnection(address: String): Result<Int> {
        val transient = SendSpin(
            applicationContext,
            deviceName = android.os.Build.MODEL,
            listener = object : SendSpin.Listener {
                // Override only what's needed; the wizard test doesn't need
                // metadata or audio callbacks. Most overrides are no-op default.
            }
        )
        transient.selfReconnectEnabled = false  // no retry loops in the wizard
        transient.connect(SendSpinEndpoint.Local(address))

        return try {
            kotlinx.coroutines.withTimeoutOrNull(TEST_TIMEOUT_MS) {
                transient.connectionState
                    .first { it is TransportState.Ready || it is TransportState.Failed }
            }.let { terminal ->
                when (terminal) {
                    is TransportState.Ready -> Result.success(0)  // adapt return value
                    is TransportState.Failed -> Result.failure(
                        IOException("Connection failed: ${terminal.reason::class.simpleName}")
                    )
                    null -> Result.failure(IOException("Connection timed out"))
                    else -> Result.failure(IOException("Unexpected state: $terminal"))
                }
            }
        } finally {
            transient.disconnect()
            transient.destroy()  // if SendSpin has a destroy() — preserve cleanup semantics
        }
    }
```

Adapt parameters:
- The `Listener` type — Phase 4 renamed `SendSpinClientCallback` to `SendSpin.Callback` (per the implementer's report), or maybe `SendSpin.Listener`. Use whatever it actually is. Verify via grep: `grep -n "interface Callback\|interface Listener" android/app/src/main/java/com/sendspindroid/sendspin/SendSpin.kt`.
- The constructor signature for `SendSpin` — confirm exactly what it takes today.
- The `Result<Int>` type — keep whatever the existing return type is. The "0" value is a placeholder; preserve whatever the existing success value was.
- `TEST_TIMEOUT_MS` — use whatever the existing timeout value was, or set it as a private const at the top of the class.

The `selfReconnectEnabled = false` is critical — the wizard wants fail-fast, no retry.

The `Listener` for the wizard test can be a no-op object: the wizard doesn't care about audio/metadata callbacks. Just `object : SendSpin.Listener {}` if all interface methods have default implementations. If they don't, override each with empty body.

Required imports:
```kotlin
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SendSpinEndpoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
```

Remove:
```kotlin
import okhttp3.OkHttpClient
// any other okhttp.* imports the wizard used only for SendSpin tests
```

(Be careful: if okhttp.* imports are also used by MA tests, leave them for now — Task 2 handles those.)

- [ ] **Step 3: Refactor `testRemoteConnection` and the proxy test**

Same pattern:

```kotlin
private suspend fun testRemoteConnection(remoteId: String): Result<String> {
    val transient = SendSpin(...)
    transient.selfReconnectEnabled = false
    transient.connect(SendSpinEndpoint.Remote(remoteId))
    // ... await Ready or Failed ...
    // ... return Result.success/failure with appropriate payload ...
    // ... finally { transient.disconnect(); transient.destroy() } ...
}
```

For the proxy test (around line 536), construct `SendSpinEndpoint.Proxy(url, authToken)`.

If `testRemoteConnection`'s success payload is some specific string from the server (e.g., the server name), retrieve it via `transient.getServerName()` after Ready. Adapt as needed.

- [ ] **Step 4: Build and run tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/ui/server/AddServerWizardActivity.kt
git commit -m "refactor(wizard): use transient SendSpin for connection tests

Phase 6. AddServerWizardActivity's testLocalConnection,
testRemoteConnection, and the proxy test now construct a transient
SendSpin instance with selfReconnectEnabled=false, call
connect(endpoint), and observe connectionState until terminal. The
three OkHttpClient.Builder constructions delete; the wizard now uses
the same transport code path that production uses for live
connections, so a future fix to SendSpin's handshake reaches the
'Test Connection' button automatically."
```

---

## Task 2: Wizard MA tests use a stateless `testMaAuth` helper

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistant.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/ui/server/AddServerWizardViewModel.kt`

Add a stateless `testMaAuth(endpoint, credentials): Result<MaServerInfo>` function in the `musicassistant/` package (could be a top-level function in a new file, a companion-object function on `MusicAssistant`, or a top-level function in `MusicAssistant.kt` itself). It should:
- Create a temporary `MaApiTransport` (the same impls the singleton uses)
- Run auth (with token or username/password)
- Return success with `MaServerInfo` or failure with the classified exception
- Not touch `MusicAssistant`'s singleton state at all

The wizard's `testMaConnection` (around line 495 of `AddServerWizardViewModel.kt`) calls this helper and updates UI state based on the result.

- [ ] **Step 1: Read the existing testMaConnection**

Read AddServerWizardViewModel:495 fully. Note:
- What credentials does it use (token? username/password?)
- What URL/endpoint format does it construct?
- How does it map the result to `MaTestState` (or whatever the UI state type is)?

- [ ] **Step 2: Read the existing MusicAssistant.connect(endpoint, token) facade and MaApiTransport setup**

Look at `MusicAssistant.connect(endpoint, token)` (added in Phase 5 Task 4) and its private helpers. The internal `connectTransport` method probably encapsulates the create-and-auth pattern. The new `testMaAuth` should reuse the SAME transport-creation logic without sharing state.

If `connectTransport` is well-isolated (just creates a transport, runs auth, returns server info, doesn't touch singleton state), `testMaAuth` could BE `connectTransport` exposed as a public function. Otherwise it needs to be a separate function that mirrors the relevant parts.

**STOP and report** if the existing connect logic is too tangled with singleton state to extract cleanly.

- [ ] **Step 3: Add `testMaAuth` to MusicAssistant.kt**

Add the helper as a top-level function in `MusicAssistant.kt` (not on the singleton). Top-level position keeps the singleton untouched and avoids any state coupling:

```kotlin
/**
 * Stateless one-shot helper for wizard tests. Creates a temporary MaApiTransport,
 * authenticates with the provided credentials, and returns success or failure.
 * Does not touch MusicAssistant singleton state.
 *
 * Phase 6 of the ConnectionCoordinator design.
 */
suspend fun testMaAuth(
    endpoint: MaEndpoint,
    credentials: MaCredentials,
): Result<MaServerInfo> {
    val apiUrl = endpoint.toApiUrl()  // assuming Phase 5 added this extension
    val transport = createMaApiTransport(apiUrl)  // factory
    return try {
        when (credentials) {
            is MaCredentials.Token -> transport.connect(credentials.token)
            is MaCredentials.UsernamePassword -> transport.connectWithCredentials(
                credentials.username,
                credentials.password,
            )
        }
        // The transport's `serverInfo` after successful auth — extract however
        // the existing connectTransport extracts it.
        Result.success(transport.serverInfo!!)
    } catch (e: Exception) {
        Result.failure(e)
    } finally {
        transport.disconnect()
    }
}

sealed class MaCredentials {
    data class Token(val token: String) : MaCredentials()
    data class UsernamePassword(val username: String, val password: String) : MaCredentials()
}
```

The `createMaApiTransport` factory is whatever the existing singleton uses internally. Probably `MaApiTransportFactory` or similar — find it and call it the same way. If there's no factory and the singleton inlines `MaWebSocketTransport(...)` directly, mirror that here.

The `MaCredentials` sealed class can be in the same file or a new one. Keep things together for simplicity unless the codebase has a strong convention otherwise.

- [ ] **Step 4: Update `AddServerWizardViewModel.testMaConnection`**

Replace the inline `MaApiTransport` setup with a call to `testMaAuth`. The function signature stays the same; the body becomes:

```kotlin
    fun testMaConnection(onComplete: (Boolean) -> Unit) {
        val apiUrl = deriveMaApiUrl()
        if (apiUrl == null) {
            _maTestState.value = ConnectionTestState.Failed("No MA endpoint available")
            onComplete(false)
            return
        }
        if (maUsername.isBlank() || maPassword.isBlank()) {
            _maTestState.value = ConnectionTestState.Failed("Username and password required")
            onComplete(false)
            return
        }

        _maTestState.value = ConnectionTestState.Testing

        viewModelScope.launch {
            val endpoint = parseMaEndpoint(apiUrl)  // adapt: convert URL to MaEndpoint
            val credentials = MaCredentials.UsernamePassword(maUsername, maPassword)

            val result = testMaAuth(endpoint, credentials)
            result.onSuccess { serverInfo ->
                maToken = serverInfo.accessToken  // whatever field carries the token
                _maTestState.value = ConnectionTestState.Success("Connected to Music Assistant")
                if (serverName.isBlank() && serverInfo.baseUrl.isNotBlank()) {
                    serverName = extractServerNameFromUrl(serverInfo.baseUrl)
                }
                if (maPort != MaSettings.getDefaultPort()) {
                    MaSettings.setDefaultPort(maPort)
                }
                onComplete(true)
            }.onFailure { e ->
                maToken = null
                _maTestState.value = when (e) {
                    is MaApiTransport.AuthenticationException -> ConnectionTestState.Failed("Invalid credentials")
                    else -> ConnectionTestState.Failed("Network error")
                }
                onComplete(false)
            }
        }
    }
```

The conversion from `apiUrl: String` to `MaEndpoint` may need a small helper (or an inverse of `MaEndpoint.toApiUrl()` if Phase 5 added it). If `deriveMaApiUrl()` already produces an `apiUrl` string and we need to construct an `MaEndpoint` from the wizard's known address/port/proxy/remote-id fields, do that directly without round-tripping through a string:

```kotlin
val endpoint: MaEndpoint = when {
    localAddress.isNotBlank() -> MaEndpoint.Local(WebSocketUrlBuilder.extractHost(localAddress), maPort)
    proxyUrl.isNotBlank() -> MaEndpoint.Proxy(proxyUrl)
    // remote not handled in this test path? confirm
    else -> { onComplete(false); return@launch }
}
```

Read the existing `deriveMaApiUrl()` method to see what it does today and replicate the logic in MaEndpoint terms.

- [ ] **Step 5: Build and run tests**

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Both green.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistant.kt \
        android/app/src/main/java/com/sendspindroid/ui/server/AddServerWizardViewModel.kt
git commit -m "refactor(wizard): use testMaAuth helper for MA connection tests

Phase 6. AddServerWizardViewModel.testMaConnection now calls the new
top-level testMaAuth(endpoint, credentials) helper in MusicAssistant.kt
instead of constructing its own MaApiTransport scaffolding. The helper
is stateless: creates a temporary transport, runs auth, returns
Result<MaServerInfo>, doesn't touch the MusicAssistant singleton.

A fix to MA's auth handling now reaches both the wizard's 'Test
Connection' button and the live connection path in one change."
```

---

## Task 3: Verify Phase 6 end-to-end

- [ ] **Step 1: Confirm no OkHttp imports remain in wizard files**

```bash
grep -n "import okhttp3\|OkHttpClient" android/app/src/main/java/com/sendspindroid/ui/server/AddServerWizardActivity.kt android/app/src/main/java/com/sendspindroid/ui/server/AddServerWizardViewModel.kt
```

Should be empty (or only references that are inside MA imports — but those should also be gone since we now route through `testMaAuth`).

- [ ] **Step 2: Full test suite + release build**

```bash
cd android && ./gradlew :app:testDebugUnitTest :app:assembleRelease
```

All green.

- [ ] **Step 3: Manual smoke test**

The wizard test path is the user-visible thing.

1. Open the wizard, choose "Add SendSpin server", enter a known-good local address, hit "Test Connection". Should show success.
2. Same wizard, enter a known-bad address (e.g., `192.168.255.255:8927`). Should show failure within the test timeout.
3. Choose "Add Music Assistant", enter credentials for a known-good MA server, hit "Test Connection". Should show success.
4. Same MA wizard, enter wrong password. Should show "Invalid credentials".
5. MA wizard with unreachable address. Should show "Network error".

If any of these regress vs Phase 5's behavior, that's a real bug — investigate.

- [ ] **Step 4: Confirm no stale wizard state survives**

The `transient.disconnect()` + `transient.destroy()` cleanup in the SendSpin test path is critical: pre-Phase-6 the wizard had documented leak risk on user-cancel mid-test. Phase 6 fixes that by ensuring cleanup runs in a `finally` block. A specific test:

6. Start a SendSpin test (against a slow server), then back out of the wizard before it completes. Confirm no orphaned WebSocket appears in logcat.

---

## Self-Review Notes

- **Spec coverage:** Phase 6 satisfies the design's §9 row 6 — the wizard uses the same transport code path as live connections. Pragmatic adaptation: MA uses a stateless helper instead of literal "transient instances" because `MusicAssistant` is a singleton.
- **Risk surfaces:**
  - Task 1's transient SendSpin needs `selfReconnectEnabled = false`. Forgetting this means wizard tests trigger production-style retry loops (slow + confusing UI).
  - Task 1's `Listener` parameter — the wizard test doesn't need any callback methods, but `SendSpin.Listener` may have abstract methods that require implementation. Use empty default impls.
  - Task 2's `testMaAuth` MUST not touch singleton state. If it accidentally writes to `_connectionState` or `currentServer`, the wizard test would interfere with a live MA session (e.g., user is connected to MA on Server A, opens wizard to add Server B — the test should not affect Server A's session).
- **Phase 7 carryover:** The wizard's `apiUrl: String` round-tripping (instead of carrying typed `MaEndpoint` end-to-end) is a small carryover for future cleanup. Phase 7's "five-sources-of-truth cleanup" might address it.
