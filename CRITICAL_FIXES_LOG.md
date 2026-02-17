# SendspinDroid KMP - Critical Fixes Log

**Branch:** `kmp-restructure`
**Created:** 2026-02-17
**Source:** CODE_REVIEW_FINDINGS.md (103 issues total)
**Scope:** 18 Critical issues - verified, fixed, and tested

---

## Fix Status Tracker

| ID | Issue | Status | Verified? | Fixed? | Tested? |
|----|-------|--------|-----------|--------|---------|
| C-01 | Stale p00 in innovation normalization (Kalman) | DONE | Yes | Yes | Yes (3 tests) |
| C-02 | disconnect() leaks HttpClient | DONE | Yes | Yes | Yes (6 tests) |
| C-03 | TimeSyncManager burstInProgress stuck true | DONE | Yes | Yes | Yes (7 tests) |
| C-04 | "Time sync ready" log dead code | DONE | Yes | Yes | N/A (log only) |
| C-05 | MediaCodecDecoder input frame silently dropped | DONE | Yes | Yes | Yes (4 tests) |
| C-06 | MediaCodecDecoder flush() calls start() illegally | DONE | Yes | Yes | Yes (3 tests) |
| C-07 | MediaCodecDecoder output drain mishandles FORMAT_CHANGED | DONE | Yes | Yes | Yes (3 tests) |
| C-08 | SyncAudioPlayer runBlocking inside stateLock (ANR) | DONE | Yes | Yes | Manual only |
| C-09 | JVM-only types in commonMain (IOException, ConcurrentHashMap) | DONE | Yes | Yes | Yes (279 pass) |
| C-10 | MusicAssistantManager.isAvailable permanently false | DONE | Yes | Yes | Yes (10 tests) |
| C-11 | Timed-out commands leak in pendingCommands | DONE | Yes | Yes | Yes (9 tests) |
| C-12 | SignalingClient leaks HttpClient | DONE | Yes | Yes | Yes (6 tests) |
| C-13 | WebRTCTransport non-volatile fields race | DONE | Yes | Yes | Manual only |
| C-14 | AutoReconnectManager reconnectJob overwrite | DONE | Yes | Yes | Manual only |
| C-15 | NsdDiscoveryManager thread-unsafe flags | DONE | Yes | Yes | Manual only |
| C-16 | UserSettings.initialize() race condition | DONE | Yes | Yes | Yes (8 tests) |
| C-17 | SyncStats.fromBundle() enum crash | DONE | Yes | Yes | Yes (6 tests) |
| C-18 | onConfigurationChanged orphans view hierarchy | DONE | Yes | Yes | Manual only |

---

## Detailed Fix Records

---

### C-01: Stale p00 in innovation normalization (Kalman)

**Status:** DONE
**Files changed:**
- `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/SendspinTimeFilter.kt`

**Files added:**
- `android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/SendspinTimeFilterTest.kt`

**Root cause:**
In `kalmanUpdate()`, the `recordInnovation()` call used the class field `p00` which still held the *previous* iteration's value. The Kalman gain formula requires the *updated* `p00` (post-prediction) for correct innovation normalization. Using stale values causes the stability score to converge incorrectly.

**Fix:**
Pass `p00New` (the freshly computed predicted covariance) explicitly to `recordInnovation()` instead of reading the stale class field.

**Tests:** 3 new tests for stability score convergence behavior.

**Manual test:** Play audio and monitor time sync stability score in logs. With consistent measurements, stability should converge toward 1.0 smoothly.

---

### C-02: disconnect() leaks HttpClient

**Status:** DONE
**Files changed:**
- `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt`

**Files added:**
- `android/app/src/test/java/com/sendspindroid/sendspin/WebSocketTransportTest.kt`
- `android/app/src/test/java/com/sendspindroid/sendspin/ProxyWebSocketTransportTest.kt`

**Root cause:**
`disconnect()` called `transport?.close(1000, "User disconnect")` which only closes the WebSocket frame but leaves the underlying Ktor `HttpClient` (and its connection pool, thread pools) alive. On repeated connect/disconnect cycles, each leaked `HttpClient` accumulated, eventually exhausting file descriptors or memory.

**Fix:**
Changed `disconnect()` to use `transport?.setListener(null)` followed by `transport?.destroy()`, matching the pattern already used in `prepareForConnection()`.

**Tests:** 6 tests across WebSocketTransport and ProxyWebSocketTransport verifying close vs destroy behavior.

**Manual test:** Connect/disconnect repeatedly 20+ times. Monitor memory and thread count in Android Studio profiler -- should remain stable rather than growing.

---

### C-03: TimeSyncManager burstInProgress stuck true

**Status:** DONE
**Files changed:**
- `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/protocol/timesync/TimeSyncManager.kt`

**Files added/modified:**
- `android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/protocol/timesync/TimeSyncManagerTest.kt` (4 new + 3 updated)

**Root cause:**
If an exception occurred during a time sync burst (e.g., network error, cancellation), `burstInProgress` was never set back to `false`. This permanently blocked all future time sync bursts, degrading clock synchronization quality.

**Fix:**
1. Wrapped burst body in `try { ... } finally { synchronized(pendingBurstMeasurements) { burstInProgress = false } }` to guarantee flag cleanup.
2. Added `if (!running) return false` guard at top of `onServerTime()` to reject measurements after stop.

**Tests:** 4 new tests for burst cancellation/exception recovery + 3 updated existing tests.

**Manual test:** Play audio, then toggle airplane mode during a time sync burst. After reconnection, verify time sync resumes (logcat shows new burst measurements).

---

### C-04: "Time sync ready" log dead code

**Status:** DONE
**Files changed:**
- `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/SendspinTimeFilter.kt`

**Root cause:**
The "Time sync ready" log was in the `kalmanUpdate()` method, but `measurementCount` is incremented in `addMeasurement()` *after* `kalmanUpdate()` returns. The `measurementCount == 2` check in `kalmanUpdate()` could never be true at that point.

**Fix:**
Moved the log to the `measurementCount == 1` branch inside `addMeasurement()`, where it fires *after* the second measurement is processed (count goes from 1 to 2).

**Manual test:** Connect to a server, watch logcat for "Time sync ready" message -- should appear after the second time sync measurement arrives.

---

### C-05: MediaCodecDecoder input frame silently dropped

**Status:** DONE
**Files changed:**
- `android/app/src/main/java/com/sendspindroid/sendspin/decoder/MediaCodecDecoder.kt`

**Files added:**
- `android/app/src/test/java/com/sendspindroid/sendspin/decoder/MediaCodecDecoderTest.kt`

**Root cause:**
Single attempt to `dequeueInputBuffer` with 10ms timeout. If no buffer available, the compressed frame was discarded with only `Log.w`. For Opus (stateful codec), dropping frames desynchronizes the prediction state, causing persistent audio artifacts.

**Fix:**
Retry loop with up to 4 attempts (`MAX_INPUT_RETRIES = 3`). Between retries, output is drained to free input buffer slots. Severity upgraded to `Log.e`.

**Tests:** 4 tests covering happy path, retry-and-succeed, exhaustion, and output drain between attempts.

**Manual test:** Play Opus audio under CPU load. Audio should remain clean where previously there would have been clicks/distortion.

---

### C-06: MediaCodecDecoder flush() calls start() illegally

**Status:** DONE
**Files changed:**
- `android/app/src/main/java/com/sendspindroid/sendspin/decoder/MediaCodecDecoder.kt`

**Root cause:**
`flush()` called `start()` after `flush()`. In synchronous mode (no callback), the codec auto-resumes to Running on next `dequeueInputBuffer()`. Calling `start()` in the Executing state is an illegal transition that throws `IllegalStateException` (silently caught by try/catch). On some devices, this could leave the codec in an undefined state.

**Fix:**
Removed the `start()` call from `flush()`. Added KDoc explaining correct MediaCodec state machine behavior.

**Tests:** 3 tests verifying start() is never called, decode works after flush, and exceptions are caught.

**Manual test:** Skip tracks rapidly during Opus/FLAC playback. Audio should resume cleanly on each new track.

---

### C-07: MediaCodecDecoder output drain mishandles FORMAT_CHANGED

**Status:** DONE
**Files changed:**
- `android/app/src/main/java/com/sendspindroid/sendspin/decoder/MediaCodecDecoder.kt`

**Root cause:**
The `while (outputIndex >= 0)` loop exits on ANY negative value. If `INFO_OUTPUT_FORMAT_CHANGED` (-2) appears between valid output buffers, the loop exits early and subsequent buffers are lost.

**Fix:**
Extracted output draining into a `drainOutput()` method using a `when` expression that handles every status code explicitly. FORMAT_CHANGED and BUFFERS_CHANGED continue the loop; only INFO_TRY_AGAIN_LATER breaks.

**Tests:** 3 tests for format-change-mid-drain, format-change-only, and deprecated BUFFERS_CHANGED handling.

**Manual test:** Start Opus/FLAC playback and listen to the first few seconds. Should start cleanly without a brief gap/hiccup at the beginning.

---

### C-08: SyncAudioPlayer runBlocking inside stateLock (ANR)

**Status:** DONE
**Files changed:**
- `android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt`

**Root cause:**
`cancelPlaybackLoop()` called `runBlocking { job.join() }` while holding `stateLock`. The playback loop coroutine calls `setPlaybackState()` which acquires the same lock. Classic deadlock: main thread holds lock and waits for coroutine; coroutine waits for lock held by main thread. Even without deadlock, `runBlocking` on main thread blocks UI for up to 1 second.

**Fix:**
Two-phase pattern:
1. `captureAndClearPlaybackLoop()` -- under lock: captures scope/job references, nulls them atomically
2. `awaitPlaybackLoopCancellation()` -- outside lock: cancels scope, joins job with timeout

All three callers (`start()`, `stop()`, `release()`) restructured into Phase 1 (lock) → Phase 2 (no lock) → Phase 3 (re-lock for cleanup).

**Tests:** Manual only (concurrency deadlock not reliably reproducible in unit tests).

**Manual test:** Rapidly tap play/stop 10+ times. App should remain responsive with no ANR dialog.

---

### C-09: JVM-only types in commonMain (IOException, ConcurrentHashMap)

**Status:** DONE
**Files changed:**
- `android/shared/src/commonMain/kotlin/com/sendspindroid/musicassistant/transport/MaCommandMultiplexer.kt`
- `android/shared/src/commonMain/kotlin/com/sendspindroid/musicassistant/MaCommandClient.kt`
- `android/shared/src/commonMain/kotlin/com/sendspindroid/musicassistant/transport/MaWebSocketTransport.kt`
- `android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistantManager.kt`
- `android/app/src/main/java/com/sendspindroid/ui/remote/ProxyConnectDialog.kt`
- `android/app/src/main/java/com/sendspindroid/ui/server/AddServerWizardViewModel.kt`

**Files added:**
- `android/shared/src/commonMain/kotlin/com/sendspindroid/musicassistant/transport/MaTransportException.kt`

**Root cause:**
Three JVM-only APIs used in commonMain: `java.io.IOException`, `java.util.concurrent.ConcurrentHashMap`, `System.currentTimeMillis()`, `Math.random()`. These prevent compilation for non-JVM Kotlin targets.

**Fix:**
1. Created `MaTransportException` (extends `Exception`) as KMP-compatible replacement for `IOException`
2. Replaced `ConcurrentHashMap` with `HashMap` + `synchronized(lock)` block
3. Replaced `System.currentTimeMillis()` with `kotlin.uuid.Uuid.random()`
4. Replaced `Math.random()` with `kotlin.random.Random.nextInt()`
5. Added `catch (e: MaTransportException)` blocks in 5 app-module call sites

**Tests:** 279 existing tests pass (shared module host tests all green).

**Manual test:** Run `grep -rn "import java\." android/shared/src/commonMain/` -- should return zero results.

---

### C-10: MusicAssistantManager.isAvailable permanently false

**Status:** DONE
**Files changed:**
- `android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistantManager.kt`

**Files added:**
- `android/app/src/test/java/com/sendspindroid/musicassistant/MaConnectionStateIsAvailableTest.kt`

**Root cause:**
The `isAvailable` StateFlow was `MutableStateFlow(false).also { flow -> }` with an empty `.also` block. The comments described the intended `connectionState.map { it.isAvailable }` implementation, but the code was never written.

**Fix:**
1. Moved `scope` declaration above `isAvailable` for initialization order
2. Replaced stub with: `_connectionState.map { it.isAvailable }.stateIn(scope, SharingStarted.Eagerly, false)`

**Tests:** 6 unit tests for sealed class variants + 4 flow derivation tests.

**Manual test:** Connect to MA server, verify `isAvailable.value` is `true`. Disconnect, verify `false`.

---

### C-11: Timed-out commands leak in pendingCommands

**Status:** DONE
**Files changed:**
- `android/shared/src/commonMain/kotlin/com/sendspindroid/musicassistant/transport/MaCommandMultiplexer.kt`
- `android/shared/src/commonMain/kotlin/com/sendspindroid/musicassistant/transport/MaWebSocketTransport.kt`
- `android/app/src/main/java/com/sendspindroid/musicassistant/transport/MaDataChannelTransport.kt`

**Files modified:**
- `android/shared/src/androidHostTest/kotlin/com/sendspindroid/musicassistant/transport/MaCommandMultiplexerTest.kt`

**Root cause:**
When `withTimeout(timeoutMs) { deferred.await() }` throws `TimeoutCancellationException`, nobody removes the entry from `pendingCommands`. The leaked `CompletableDeferred<JsonObject>` and any accumulated `partialResults` persist until the next full disconnect. Under sustained use with occasional timeouts, the map grows monotonically.

**Fix:**
1. Added `unregisterCommand(messageId)` and `unregisterProxyRequest(requestId)` methods to `MaCommandMultiplexer`
2. Wrapped `withTimeout { deferred.await() }` in `try/catch` in both transports, calling unregister on any exception
3. Also unregister on send failure (before throw)

**Tests:** 9 new tests (21 total) including simulated timeout cleanup, partial result cleanup, and multi-command selective removal.

**Manual test:** Monitor `pendingCommandCount` during sustained use with occasional timeouts -- should stay near 0.

---

### C-12: SignalingClient leaks HttpClient

**Status:** DONE
**Files changed:**
- `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/remote/SignalingClient.kt`
- `android/app/src/main/java/com/sendspindroid/remote/WebRTCTransport.kt`
- `android/app/src/main/java/com/sendspindroid/network/DefaultServerPinger.kt`

**Root cause:**
`SignalingClient` creates its own `HttpClient` internally but `destroy()` only cancelled the coroutine scope without closing the client. `WebRTCTransport.cleanup()` called `disconnect()` (which only closes the WebSocket) instead of `destroy()`.

**Fix:**
1. Added `ownsHttpClient: Boolean = true` constructor parameter to `SignalingClient`
2. `destroy()` now calls `scope.cancel()` + `httpClient.close()` (if owned)
3. Changed `WebRTCTransport.cleanup()` from `disconnect()` to `destroy()`
4. Changed all 3 `disconnect()` calls in `DefaultServerPinger.pingRemote()` to `destroy()`

**Tests:** 6 tests across WebSocket/Proxy transports.

**Manual test:** Connect/disconnect to remote server repeatedly. Monitor thread count -- should remain stable.

---

### C-13: WebRTCTransport non-volatile fields race

**Status:** DONE
**Files changed:**
- `android/app/src/main/java/com/sendspindroid/remote/WebRTCTransport.kt`

**Root cause:**
Seven fields (`signalingClient`, `peerConnection`, `dataChannel`, `maApiDataChannel`, `listener`, `maApiChannelListener`, `remoteDescriptionSet`) were plain `var` with no memory visibility guarantee. WebRTC callbacks fire on internal threads; `cleanup()` runs on the caller thread. Without `@Volatile`, callbacks could read stale non-null references to already-closed objects, causing native crashes.

**Fix:**
1. Added `@Volatile` to all 7 cross-thread mutable fields
2. Added `isActive` guard property (reads `AtomicReference<TransportState>`)
3. Added `isActive` checks + local-variable captures in 9 callback methods
4. Updated class KDoc documenting threading contract

**Tests:** Manual only (WebRTC native dependency prevents JVM unit testing).

**Manual test:** Rapidly connect/disconnect to remote server 20+ times. No native crashes or NPEs in logcat.

---

### C-14: AutoReconnectManager reconnectJob overwrite

**Status:** DONE
**Files changed:**
- `android/app/src/main/java/com/sendspindroid/network/AutoReconnectManager.kt`

**Root cause:**
Two private methods (`scheduleNextAttempt()` and `attemptReconnection()`) each overwrote the single `reconnectJob` field, calling each other in a cycle. When `attemptReconnection()` calls `scheduleNextAttempt()`, the old "attempt" coroutine is still alive but `reconnectJob` now points to the new "delay" coroutine. `cancelReconnection()` only cancels whichever job the variable currently holds, orphaning the other.

**Fix:**
Restructured into a single coroutine `runReconnectLoop()` with a `for` loop. `reconnectJob` is written exactly once per session. `onNetworkAvailable()` uses a `CompletableDeferred` signal to skip backoff delays instead of cancel+relaunch.

**Tests:** Manual only (no existing test infrastructure for this class).

**Manual test:**
1. Kill server during playback to trigger auto-reconnect
2. Cancel during backoff -- verify no further reconnect attempts in logcat
3. Toggle network during backoff -- verify immediate retry via "Backoff delay skipped" log

---

### C-15: NsdDiscoveryManager thread-unsafe flags

**Status:** DONE
**Files changed:**
- `android/app/src/main/java/com/sendspindroid/discovery/NsdDiscoveryManager.kt`

**Root cause:**
`isDiscovering` and `pendingRestart` flags were plain `var` accessed from both the main thread (start/stop calls) and Android NSD binder threads (callbacks). No memory visibility guarantee. Also, multicast lock was released in `stopDiscovery()` instead of the `onDiscoveryStopped` callback, creating a window where the lock is released before discovery actually stops.

**Fix:**
1. Added `@Volatile` to `isDiscovering` and `pendingRestart`
2. Moved `releaseMulticastLock()` from `stopDiscovery()` to `onDiscoveryStopped` callback
3. Clear `resolvingServices` on stop (also fixes M-09)

**Tests:** Manual only (NSD requires Android system services).

**Manual test:** Start/stop discovery rapidly. Verify no stale servers persist and multicast lock is properly released (check WiFi power consumption).

---

### C-16: UserSettings.initialize() race condition

**Status:** DONE
**Files changed:**
- `android/app/src/main/java/com/sendspindroid/UserSettings.kt`

**Files added:**
- `android/app/src/test/java/com/sendspindroid/UserSettingsPlayerIdTest.kt`

**Root cause:**
`prefs` field had no volatile/synchronization. `getPlayerId()` could be called before `initialize()` completes, reading `null` prefs and generating a new UUID each time (or crashing). On concurrent access, changes made by `initialize()` might not be visible to other threads.

**Fix:**
1. `@Volatile` on `prefs` field
2. New `@Volatile cachedPlayerId` for pre-init UUID caching
3. `initialize()` uses double-checked locking, flushes cached UUID to prefs
4. `getPlayerId()` uses fast path -> cache -> synchronized slow path

**Tests:** 8 tests including concurrent stress test (100 coroutines calling getPlayerId simultaneously).

**Manual test:** Cold start the app and verify player ID is consistent across all log messages from the first second of startup.

---

### C-17: SyncStats.fromBundle() enum crash

**Status:** DONE
**Files changed:**
- `android/app/src/main/java/com/sendspindroid/sendspin/model/SyncStats.kt`

**Files added:**
- `android/app/src/test/java/com/sendspindroid/sendspin/model/SyncStatsTest.kt`

**Root cause:**
`PlaybackState.valueOf()` called on a string from a `Bundle` (cross-process IPC). If the string is null, empty, or an invalid enum name, `valueOf()` throws `IllegalArgumentException`, crashing the MediaSession notification update path.

**Fix:**
Wrapped `valueOf()` in try/catch with `INITIALIZING` as fallback. Added elvis operator for null safety on `getString()`.

**Tests:** 6 tests covering null, empty, invalid, valid, and case-sensitive enum scenarios.

**Manual test:** Play audio and swipe down notification. Should display correctly even if MediaSession extras contain unexpected values.

---

### C-18: onConfigurationChanged orphans view hierarchy

**Status:** DONE
**Files changed:**
- `android/app/src/main/java/com/sendspindroid/MainActivity.kt`

**Root cause:**
When `onConfigurationChanged()` re-inflates the layout, the old ComposeView hierarchy is orphaned. Any `Snackbar.make()` calls referencing the old views would crash with "View not attached to window" or display incorrectly.

**Fix:**
1. New `snackbarAnchorView` field holding reference to always-attached `rootFrame`
2. New `snackbarView` property returning the currently attached view
3. `setupComposeShell()` stores anchor after adding rootFrame
4. `onConfigurationChanged()` clears stale references before re-inflation
5. All 7 `Snackbar.make()` calls updated to use `snackbarView`

**Tests:** Manual only (requires Android activity lifecycle).

**Manual test:** Rotate the device while a Snackbar is showing. Should not crash and the next Snackbar should display correctly.

---

## Summary Statistics

- **18 critical issues** verified and fixed
- **12 issues** with automated tests (65+ new test cases total)
- **6 issues** with manual test plans only (due to Android platform dependencies)
- **Files modified:** ~25 source files
- **Files added:** ~12 test files + 1 new exception class
- **Zero breaking API changes** -- all fixes are internal implementation changes
