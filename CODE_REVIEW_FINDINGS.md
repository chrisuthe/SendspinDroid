# SendspinDroid KMP - Comprehensive Code Review Findings

**Branch:** `kmp-restructure`
**Date:** 2026-02-17
**Scope:** Full codebase review - 190 Kotlin files across `:shared` (KMP) and `:app` (Android) modules
**Review Areas:** Protocol/Filters, Audio/Playback, Music Assistant Integration, Networking/Connectivity, Models/State/App Core, UI ViewModels/Navigation, UI Components/Wizard

---

## Table of Contents

- [Summary Statistics](#summary-statistics)
- [CRITICAL Issues](#critical-issues)
- [HIGH Issues](#high-issues)
- [MEDIUM Issues](#medium-issues)
- [LOW Issues](#low-issues)
- [Test Coverage Gaps](#test-coverage-gaps)

---

## Summary Statistics

| Severity | Count |
|----------|-------|
| Critical | 18 |
| High | 30 |
| Medium | 37 |
| Low | 18 |
| **Total** | **103** |

---

## CRITICAL Issues

### C-01: Stale `p00` used in innovation normalization corrupts adaptive process noise (Kalman Filter)
- **File:** `shared/.../sendspin/SendspinTimeFilter.kt` (lines 654, 663, 748-756)
- **Category:** Bug (Mathematical Correctness)
- **Description:** In `kalmanUpdate`, `recordInnovation()` uses the OLD pre-prediction `p00` instead of `p00New` for innovation variance normalization. This systematically distorts `adaptiveProcessNoise` and `stabilityScore`. Under stable conditions, innovations appear smaller than expected, causing adaptive noise to unnecessarily decrease.
- **Reasoning:** Innovation covariance for Kalman filter is `S = P_predicted + R` = `p00New + variance`, not `p00 + variance`. Using pre-prediction value fights the filter's natural adaptation.
- **Suggestion:** Pass `p00New` to `recordInnovation()` as an explicit parameter.
- **Tests:** Yes - verify `stabilityScore` converges to ~1.0 under consistent measurements.

### C-02: `disconnect()` leaks HttpClient - `close()` vs `destroy()` mismatch
- **File:** `app/.../sendspin/SendSpinClient.kt` (line 610)
- **Category:** Resource Leak
- **Description:** `disconnect()` calls `transport?.close()` which does NOT close the underlying Ktor `HttpClient`. Only `destroy()` performs full cleanup. Every user-initiated disconnect leaves an `HttpClient` with its thread pool alive indefinitely.
- **Reasoning:** `close()` only cancels the connection coroutine and closes the outgoing channel. Repeated connect/disconnect cycles accumulate leaked thread pools.
- **Suggestion:** Replace `close()` with `destroy()` in `disconnect()`. Also set listener to null first.
- **Tests:** Yes - verify `HttpClient.close()` called after disconnect.

### C-03: `TimeSyncManager` burstInProgress flag stuck true if coroutine cancelled mid-burst
- **File:** `shared/.../sendspin/protocol/timesync/TimeSyncManager.kt` (lines 105-119)
- **Category:** Race Condition
- **Description:** If the burst coroutine exits early via `return` (because `!running`), `processBurstResults()` is never called and `burstInProgress` remains `true` permanently. All subsequent time-sync responses are silently swallowed, breaking synchronization.
- **Reasoning:** Non-local return exits `sendTimeSyncBurst()` entirely, bypassing the flag reset. Only `stop()` also clears it, but timing of `stop()` relative to coroutine is indeterminate.
- **Suggestion:** Use `try/finally` to guarantee `burstInProgress = false`.
- **Tests:** Yes - stop mid-burst then start new burst, verify `onServerTime()` works.

### C-04: "Time sync ready" log is dead code - never fires
- **File:** `shared/.../sendspin/SendspinTimeFilter.kt` (lines 716-722)
- **Category:** Bug
- **Description:** The log checks `measurementCount == MIN_MEASUREMENTS (2)`, but `kalmanUpdate` only runs when `measurementCount >= 2`, and it increments before checking, making minimum value 3. The condition can never be true.
- **Suggestion:** Move the ready-state log to the end of the `1 -> {}` branch in `addMeasurement`.
- **Tests:** None needed.

### C-05: MediaCodecDecoder - Input frame silently dropped when queue full (corrupts Opus state)
- **File:** `app/.../sendspin/decoder/MediaCodecDecoder.kt` (lines 79-89)
- **Category:** Bug
- **Description:** When `dequeueInputBuffer` returns negative (queue full), the compressed input frame is discarded with no retry. For stateful codecs like Opus, every dropped frame permanently corrupts decoder state.
- **Suggestion:** Add retry loop (3 attempts) before giving up on input buffer.
- **Tests:** Yes - verify Opus frames are not silently dropped under queue pressure.

### C-06: MediaCodecDecoder - `flush()` calls `start()` in Executing state (illegal transition)
- **File:** `app/.../sendspin/decoder/MediaCodecDecoder.kt` (lines 117-126)
- **Category:** Bug
- **Description:** After `flush()`, `start()` is called. But `flush()` moves to Flushed sub-state of Executing, and calling `start()` from Executing is illegal per Android docs. The catch block silently swallows the `IllegalStateException`.
- **Suggestion:** Remove the `start()` call after `flush()`.
- **Tests:** Yes - test flush followed by decode works without start().

### C-07: MediaCodecDecoder - Output drain loop mishandles INFO_OUTPUT_FORMAT_CHANGED
- **File:** `app/.../sendspin/decoder/MediaCodecDecoder.kt` (lines 93-113)
- **Category:** Bug
- **Description:** The `while (outputIndex >= 0)` loop exits on FORMAT_CHANGED, losing remaining PCM data in the output queue. Should use `when` expression inside `while(true)` that only breaks on TRY_AGAIN_LATER.
- **Suggestion:** Restructure drain loop to handle all status codes inside the loop.
- **Tests:** Yes - test format change interleaved with output buffer delivery.

### C-08: SyncAudioPlayer - `runBlocking` inside `stateLock` causes ANR and deadlock risk
- **File:** `app/.../sendspin/SyncAudioPlayer.kt` (lines 775-803)
- **Category:** Threading/Bug
- **Description:** `cancelPlaybackLoop()` is called inside `stateLock.withLock {}` from stop/start/release. Inside it, `runBlocking` blocks the calling thread for up to 1000ms. Called from main thread via PlaybackService. Any path in playback loop that calls `stateLock.withLock {}` would deadlock.
- **Suggestion:** Move `runBlocking` wait outside the lock. Capture job reference under lock, join outside.
- **Tests:** Yes - test rapid start/stop cycles without ANR.

### C-09: `java.io.IOException` and `ConcurrentHashMap` used in `commonMain` (KMP incompatibility)
- **Files:** `shared/.../musicassistant/MaCommandClient.kt` (line 24), `MaCommandMultiplexer.kt` (lines 11-12), `MaWebSocketTransport.kt` (line 27)
- **Category:** KMP Compatibility
- **Description:** Three `commonMain` files import JVM-only types. Will not compile for any non-JVM target (iOS, JS, wasmJS).
- **Suggestion:** Replace `IOException` with custom `MaTransportException`. Replace `ConcurrentHashMap` with mutex-guarded HashMap.
- **Tests:** Compile-time verification for non-Android target.

### C-10: `MusicAssistantManager.isAvailable` StateFlow is a permanently-false stub
- **File:** `app/.../musicassistant/MusicAssistantManager.kt` (lines 75-78)
- **Category:** Bug
- **Description:** `MutableStateFlow(false).also { flow -> }` block does nothing with `flow`. No subscription to `connectionState` is set up. Always emits `false`. Any UI observing this to show MA features will never enable them.
- **Suggestion:** Derive from `connectionState.map { it.isAvailable }.stateIn(...)`.
- **Tests:** Yes - transition connectionState to Connected, verify isAvailable becomes true.

### C-11: Timed-out commands never removed from `pendingCommands` (memory leak)
- **Files:** `shared/.../musicassistant/transport/MaWebSocketTransport.kt` (lines 211-230), `app/.../MaDataChannelTransport.kt` (lines 212-227)
- **Category:** Bug/Resource Leak
- **Description:** When `withTimeout` throws, the `CompletableDeferred` and its `messageId` remain in `pendingCommands` indefinitely. Over a session with intermittent connectivity, hundreds of entries accumulate.
- **Suggestion:** Add cleanup in catch block: `multiplexer.unregisterCommand(messageId)`.
- **Tests:** Yes - trigger timeout, verify pendingCommandCount returns to zero.

### C-12: `SignalingClient` leaks a new `HttpClient` on every instantiation
- **File:** `shared/.../remote/SignalingClient.kt` (lines 75-76, 259-271)
- **Category:** Resource Leak
- **Description:** Every `WebRTCTransport.connect()` and every `DefaultServerPinger.pingRemote()` creates a new `SignalingClient` with a new `HttpClient`. Neither `disconnect()` nor `destroy()` ever calls `httpClient.close()`.
- **Suggestion:** Close client in `destroy()`. Track ownership to avoid closing injected clients.
- **Tests:** Yes - verify repeated connect/disconnect cycles don't grow thread count.

### C-13: WebRTCTransport callbacks race with `cleanup()` on non-volatile fields
- **File:** `app/.../remote/WebRTCTransport.kt` (lines 109-118, 221-237, 386-463)
- **Category:** Race Condition
- **Description:** `peerConnection`, `dataChannel`, `signalingClient` are plain `var` with no `@Volatile`. Concurrent callbacks from WebRTC threads can observe partially-written state or null references.
- **Suggestion:** Mark all nullable WebRTC object references `@Volatile`. Add early-exit guards in observer callbacks.
- **Tests:** Yes - integration test with concurrent ICE failure and candidate delivery.

### C-14: `AutoReconnectManager` overwrites `reconnectJob`, making cancellation unreliable
- **File:** `app/.../network/AutoReconnectManager.kt` (lines 193-225, 239-301)
- **Category:** Race Condition/Bug
- **Description:** `reconnectJob` is written in `scheduleNextAttempt()` and then overwritten in `attemptReconnection()`. `cancelReconnection()` only cancels whichever reference is current, but the other job continues. Can trigger connection attempt to wrong server.
- **Suggestion:** Restructure as single coroutine owning the entire retry loop.
- **Tests:** Yes - cancel during active attempt, verify no further callbacks fire.

### C-15: NsdDiscoveryManager `isDiscovering` and `pendingRestart` are plain `var` accessed from multiple threads
- **File:** `app/.../discovery/NsdDiscoveryManager.kt` (lines 50-53, 96-108, 221-242)
- **Category:** Race Condition
- **Description:** Read/written on main thread and NSD binder thread without synchronization. A stop/start race can leave two discovery sessions running simultaneously.
- **Suggestion:** Mark both fields `@Volatile`. Move `releaseMulticastLock()` to `onDiscoveryStopped` callback.
- **Tests:** Yes - concurrent start/stop cycling.

### C-16: `UserSettings.initialize()` race condition - silent UUID loss
- **File:** `app/.../UserSettings.kt` (lines 45-55, 82-91)
- **Category:** Race Condition
- **Description:** `prefs` is plain `var` with no `@Volatile`. If `getPlayerId()` called before `initialize()` completes on another thread, silently generates then loses a UUID. PlaybackService starts on launch and may call before MainActivity finishes init.
- **Suggestion:** Add `@Volatile` and double-checked locking, matching ServerRepository pattern.
- **Tests:** Yes - verify getPlayerId() returns stable ID across init timing.

### C-17: `SyncStats.fromBundle()` crashes on unknown enum values
- **File:** `app/.../model/SyncStats.kt` (lines 210-212)
- **Category:** Bug/Error Handling
- **Description:** `PlaybackState.valueOf()` throws `IllegalArgumentException` on unrecognized string. MediaSession extras persist across process restarts and app updates - upgrading while service running can crash.
- **Suggestion:** Wrap in try/catch with INITIALIZING fallback.
- **Tests:** Yes - test with unrecognized playback state string.

### C-18: `onConfigurationChanged` re-inflates layout, orphaning Snackbar anchor
- **File:** `app/.../MainActivity.kt` (lines 517-578, 768-800)
- **Category:** Bug/Lifecycle
- **Description:** `setupComposeShell()` removes `coordinatorLayout` from parent. On rotation, re-inflation + `setupComposeShell()` again leaves the new `coordinatorLayout` detached. Snackbars crash with `IllegalArgumentException: No suitable parent`.
- **Suggestion:** Don't re-inflate in onConfigurationChanged. Let Compose recompose naturally.
- **Tests:** Manual rotation test while connected, then trigger any Snackbar.

---

## HIGH Issues

### H-01: `SendspinTimeFilter` is not thread-safe but accessed from multiple threads
- **File:** `shared/.../sendspin/SendspinTimeFilter.kt` (lines 260-318, 510-571, 458-486)
- **Category:** Race Condition
- **Description:** ~20 mutable fields with no synchronization. Accessed from IO dispatcher (addMeasurement) and audio thread (serverToClient). On JVM, 64-bit double reads/writes are not atomic.
- **Suggestion:** Add `@Volatile` to `offset`/`staticDelayMicros` for hot path. Use `synchronized(this)` for `addMeasurement`/`thaw`/`freeze`.
- **Tests:** Document thread-safety contract.

### H-02: Double `onDisconnected` callback on user-initiated disconnect
- **File:** `app/.../sendspin/SendSpinClient.kt` (lines 598-615, 903-931)
- **Category:** Race Condition
- **Description:** `disconnect()` fires `callback.onDisconnected()` synchronously, then `transport?.close()` eventually triggers async `onClosed` which fires it again.
- **Suggestion:** Clear transport listener before closing: `transport?.setListener(null)`.
- **Tests:** Yes - verify onDisconnected fires exactly once.

### H-03: TimeSyncManager `rttHistory` fields mutated in `stop()` outside lock
- **File:** `shared/.../sendspin/protocol/timesync/TimeSyncManager.kt` (lines 68-80, 167-205)
- **Category:** Race Condition
- **Description:** `stop()` resets `rttHistoryIndex`, `rttHistoryCount`, etc. outside the `synchronized(pendingBurstMeasurements)` block while `processBurstResults()` reads/writes them inside the lock.
- **Suggestion:** Move all resets inside the synchronized block.
- **Tests:** Yes.

### H-04: Proxy auth flow - auth-ack message forwarded to protocol handler, risking double handshake
- **File:** `app/.../sendspin/SendSpinClient.kt` (lines 884-892)
- **Category:** Bug (Auth Flow)
- **Description:** After proxy auth succeeds, `sendClientHello()` is called AND the message is forwarded to `handleTextMessage()`. If auth-ack IS a server/hello, handshake completes before client/hello is sent.
- **Suggestion:** Return after `sendClientHello()`, don't forward auth-ack to protocol handler.
- **Tests:** Yes - proxy auth state machine tests.

### H-05: `SyncErrorFilter` forgetting inflation is asymmetric - breaks covariance matrix
- **File:** `shared/.../sendspin/SyncErrorFilter.kt` (lines 168-179)
- **Category:** Bug (Mathematical)
- **Description:** Step-change forgetting inflates `p00` and `p11` by 10x but leaves `p01`/`p10` unchanged. This makes drift gain smaller than it should be during step changes, slowing adaptation when fast adaptation is most needed.
- **Suggestion:** Apply same inflation factor to all four matrix elements.
- **Tests:** Yes - verify drift adapts at expected rate after step change.

### H-06: `System.currentTimeMillis()` in `commonMain` - KMP incompatibility
- **File:** `shared/.../sendspin/SendspinTimeFilter.kt` (lines 521, 579)
- **Category:** KMP Compatibility
- **Description:** JVM-only API in commonMain. Will fail for any non-JVM target.
- **Suggestion:** Add expected Clock declaration to platform layer.
- **Tests:** None needed.

### H-07: `SyncErrorFilter` drift decay prevents convergence to true drift rate
- **File:** `shared/.../sendspin/SyncErrorFilter.kt` (lines 194-200)
- **Category:** Bug (Numerical)
- **Description:** 1% per-update multiplicative decay fights Kalman convergence. Genuine DAC drift is systematically underestimated.
- **Suggestion:** Remove the decay - process noise model handles this correctly already.
- **Tests:** Yes - verify driftValue converges to true drift with constant-rate measurements.

### H-08: `processChunk()` modifies shared state without `stateLock`
- **File:** `app/.../sendspin/SyncAudioPlayer.kt` (lines 954-1162)
- **Category:** Race Condition
- **Description:** Called on WebSocket thread. Modifies `chunkQueue`, `totalQueuedSamples`, `expectedNextTimestampUs`, `playbackState` without lock. Races with `clearBuffer()`/`stop()` on main thread.
- **Suggestion:** Check `streamGeneration` before processing, or acquire lock for state transitions.
- **Tests:** Yes - concurrent queueChunk/clearBuffer stress test.

### H-09: `totalFramesWritten` is plain Long accessed from multiple threads
- **File:** `app/.../sendspin/SyncAudioPlayer.kt` (lines 334, 1427, 1474, 1950)
- **Category:** Race Condition
- **Description:** Written from playback loop, reset on main thread. On 32-bit ARM, long reads/writes are not atomic. Torn read causes wildly incorrect pending frame count.
- **Suggestion:** Change to `AtomicLong`, matching existing `totalQueuedSamples` pattern.
- **Tests:** Yes.

### H-10: `decoderReady = true` set even when `configure()` throws
- **File:** `app/.../playback/PlaybackService.kt` (lines 1068-1077)
- **Category:** Bug/Error Handling
- **Description:** Fallback PcmDecoder is not configured. If `create("pcm")` also throws, `audioDecoder` is null but `decoderReady = true` - raw FLAC bytes play as PCM (loud noise).
- **Suggestion:** Move `decoderReady = true` inside try block. Configure fallback explicitly.
- **Tests:** Yes.

### H-11: `clearBuffer()` flushes AudioTrack while playback loop may be mid-write
- **File:** `app/.../sendspin/SyncAudioPlayer.kt` (lines 876-893)
- **Category:** Threading
- **Description:** Playback loop doesn't hold `stateLock` during `track.write()`. Concurrent `pause()`/`flush()` causes clicks/pops and incorrect frame accounting.
- **Suggestion:** Add `isFlushPending` AtomicBoolean flag checked by playback loop before writes.
- **Tests:** Yes.

### H-12: `MetadataForwardingPlayer` duplicate listener registration causes double callbacks
- **File:** `app/.../playback/MetadataForwardingPlayer.kt` (lines 200-211)
- **Category:** Bug
- **Description:** `CopyOnWriteArrayList.add()` doesn't enforce uniqueness. Media3 may add same listener twice. `onMediaMetadataChanged` fires once per registration per update.
- **Suggestion:** Add `if (!listeners.contains(listener))` guard, matching `SendSpinPlayer` pattern.
- **Tests:** Yes.

### H-13: ByteArray silence allocated in hot audio loop every 10ms
- **File:** `app/.../sendspin/SyncAudioPlayer.kt` (lines 1416-1420, 1467-1470)
- **Category:** Performance/Memory
- **Description:** `ByteArray(silenceBytes)` allocated on every call during INITIALIZING/WAITING_FOR_START. 100 allocations/sec causing GC pressure in audio path.
- **Suggestion:** Pre-allocate silence buffer in `initialize()`.
- **Tests:** None needed, profile to confirm.

### H-14: `MaDataChannelTransport.sendCommand` corrupts List<T> arguments
- **File:** `app/.../musicassistant/transport/MaDataChannelTransport.kt` (lines 214-222)
- **Category:** Bug
- **Description:** `JSONObject(Map)` converts `List<String>` to string via `toString()` instead of JSON array. Makes `addPlaylistTracks`, `removePlaylistTracks`, filtered search non-functional in REMOTE mode.
- **Suggestion:** Use `mapToJsonObject()` helper from MaWebSocketTransport.
- **Tests:** Yes - verify List<String> args serialize as JSON array.

### H-15: Partial result accumulation thread-safety gap in MaCommandMultiplexer
- **File:** `shared/.../musicassistant/transport/MaCommandMultiplexer.kt` (lines 114-122)
- **Category:** Race Condition
- **Description:** `containsKey` and `getOrPut` not atomic. `MutableList.addAll()` not thread-safe. Large library batches arrive in rapid succession.
- **Suggestion:** Use CopyOnWriteArrayList for partial accumulation, or synchronize all accesses.
- **Tests:** Yes - concurrent partial batch test.

### H-16: Proxy request IDs use JVM-only APIs and are collision-prone
- **File:** `shared/.../musicassistant/transport/MaCommandMultiplexer.kt` (lines 78-82)
- **Category:** Bug/KMP Compatibility
- **Description:** Uses `System.currentTimeMillis()` and `Math.random()` in commonMain. 1-in-10,000 collision per same-millisecond pair (routine for image grid loads).
- **Suggestion:** Use `Uuid.random()`, matching `registerCommand()` pattern.
- **Tests:** Yes.

### H-17: `hexToBytes` throws on odd-length hex strings
- **File:** `shared/.../musicassistant/transport/MaCommandMultiplexer.kt` (lines 245-258)
- **Category:** Bug/Error Handling
- **Description:** Accesses `hex[i + 1]` which throws `StringIndexOutOfBoundsException` when length is odd.
- **Suggestion:** Add `require(hex.length % 2 == 0)` with descriptive message.
- **Tests:** Yes.

### H-18: `MaWebSocketTransport.disconnect()` not atomic - concurrent connect races
- **File:** `shared/.../musicassistant/transport/MaWebSocketTransport.kt` (lines 248-270)
- **Category:** Race Condition
- **Description:** Each `?.` read and assignment is separate. No lock prevents `connect()` running concurrently, orphaning new `authResult` deferred.
- **Suggestion:** Add Mutex guarding all state transitions.
- **Tests:** Yes.

### H-19: `MaDataChannelTransport` login state machine mutates `isLoginMode` unsafely
- **File:** `app/.../musicassistant/transport/MaDataChannelTransport.kt` (lines 386-391)
- **Category:** Race Condition
- **Description:** Temporarily sets `isLoginMode = false` without `@Volatile`. Another `handleMessage` call on WebRTC thread could read stale value.
- **Suggestion:** Replace boolean flags with sealed state class.
- **Tests:** Yes.

### H-20: `MaCommandClient.setTransport()` writes three `@Volatile` fields non-atomically
- **File:** `shared/.../musicassistant/MaCommandClient.kt` (lines 59-79)
- **Category:** Race Condition
- **Description:** `transport`, `currentApiUrl`, `isRemoteMode` written sequentially. Reader between writes 1 and 3 sees inconsistent combination.
- **Suggestion:** Bundle into single immutable `TransportContext` data class with `@Volatile` reference.
- **Tests:** None (structural fix).

### H-21: `MaWebSocketTransport` CoroutineScope never cancelled - resource leak
- **File:** `shared/.../musicassistant/transport/MaWebSocketTransport.kt` (line 93)
- **Category:** Resource Leak
- **Description:** `disconnect()` cancels `connectionJob` but not `scope`. Discarded transports leave live SupervisorJob. Accumulates under repeated reconnects.
- **Suggestion:** Call `scope.cancel()` in `disconnect()`.
- **Tests:** None.

### H-22: `connectWithToken` launches untracked coroutine - concurrent calls race
- **File:** `app/.../musicassistant/MusicAssistantManager.kt` (lines 238-309)
- **Category:** Race Condition
- **Description:** Job not stored. Two rapid calls to `onServerConnected` race on `apiTransport`.
- **Suggestion:** Store job, cancel previous on re-entry: `connectJob?.cancel(); connectJob = scope.launch { ... }`.
- **Tests:** Yes.

### H-23: `WebRTCTransport` EglBase singleton never disposed
- **File:** `app/.../remote/WebRTCTransport.kt` (lines 67-101)
- **Category:** Resource Leak
- **Description:** `eglBase` and `peerConnectionFactory` are static singletons never released. App is audio-only - EGL context is unnecessary.
- **Suggestion:** Remove `eglBase` entirely (pass null to video factories). Add `releaseFactory()`.
- **Tests:** None.

### H-24: AutoReconnectManager `onNetworkAvailable()` allows unbounded retries on flapping network
- **File:** `app/.../network/AutoReconnectManager.kt` (lines 175-188)
- **Category:** Bug/Logic
- **Description:** `onNetworkAvailable()` bypasses attempt counter increment. On flapping WiFi, counter stalls and MAX_ATTEMPTS never reached.
- **Suggestion:** Add debounce, document whether network-triggered retries count toward MAX_ATTEMPTS.
- **Tests:** Yes.

### H-25: `SignalingClient.connect()` has TOCTOU race on `_state`
- **File:** `shared/.../remote/SignalingClient.kt` (lines 151-165)
- **Category:** Race Condition
- **Description:** Check-then-set on `_state.value` is not atomic. Two concurrent callers can both pass the guard, opening two WebSocket connections.
- **Suggestion:** Use Mutex or AtomicReference with compare-and-swap.
- **Tests:** Yes.

### H-26: `ServerRepository` discovered server mutations not thread-safe
- **File:** `app/.../ServerRepository.kt` (lines 136-148)
- **Category:** Race Condition
- **Description:** Read-modify-write on `_discoveredServers` StateFlow is not atomic. Concurrent calls silently lose updates.
- **Suggestion:** Use `_discoveredServers.update { }` which is atomic.
- **Tests:** Yes - concurrent stress test.

### H-27: `onServerLost` removes by name but `addDiscoveredServer` deduplicates by address
- **File:** `app/.../MainActivity.kt` (lines 1434-1447)
- **Category:** Bug/State Management
- **Description:** Asymmetric keys. Same-named servers: wrong one removed. Name-changed server: stale entry persists.
- **Suggestion:** Change onServerLost to use address as removal key.
- **Tests:** Yes.

### H-28: `FileLogger.checkRotation` reads entire 2MB log into memory on every write
- **File:** `app/.../debug/FileLogger.kt` (lines 149-187)
- **Category:** Performance
- **Description:** After exceeding MAX_SIZE, every subsequent write reads 2MB into a String. Under active logging (2Hz), causes massive GC pressure. Also not synchronized.
- **Suggestion:** Track rotation state, use RandomAccessFile for partial reads.
- **Tests:** Yes.

### H-29: `PlaybackState.withMetadata` always stamps `positionUpdatedAt` even when position is 0
- **File:** `shared/.../model/PlaybackState.kt` (lines 44-75)
- **Category:** Bug/State Management
- **Description:** `positionUpdatedAt` set to current time even for `positionMs = 0`. `interpolatedPositionMs` then counts up from zero before audio starts, showing phantom progress.
- **Suggestion:** Only update `positionUpdatedAt` when `positionMs` changes to non-zero.
- **Tests:** Yes.

### H-30: `UnifiedServerRepository.allServers` requires manual `updateCombinedServers()` call
- **File:** `app/.../UnifiedServerRepository.kt` (lines 72-80, 295-329)
- **Category:** State Management
- **Description:** Any future mutator that forgets calling `updateCombinedServers()` silently produces stale data. Seven current mutators must all remember.
- **Suggestion:** Derive reactively using `combine(_savedServers, _discoveredServers) { ... }.stateIn(...)`.
- **Tests:** Yes.

---

## MEDIUM Issues

### M-01: `MessageParser.parseServerTime` uses `0L` as both default and invalid sentinel
- **File:** `shared/.../sendspin/protocol/message/MessageParser.kt` (lines 51-58)
- **Suggestion:** Use nullable long to distinguish absent from zero-valued fields.

### M-02: WebSocket transports don't send close frame on `close()`
- **Files:** `shared/.../sendspin/transport/WebSocketTransport.kt` (192-197), `ProxyWebSocketTransport.kt` (221-226)
- **Suggestion:** Send Close sentinel through outgoing channel.

### M-03: `extractImageFromMetadata` falls back to last image regardless of type
- **File:** `shared/.../musicassistant/MaCommandClient.kt` (lines 2036-2055)
- **Suggestion:** Add preferred type list: thumb > cover > front > any.

### M-04: `parseQueueState` uses page-local index for `isCurrentItem` (wrong when offset > 0)
- **File:** `shared/.../musicassistant/MaCommandClient.kt` (lines 1781-1782)
- **Suggestion:** Remove `i == currentIndex`, rely only on `queueItemId == currentItemId`.

### M-05: `MaSettings` silently no-ops if `initialize()` not called
- **File:** `app/.../musicassistant/MaSettings.kt` (lines 60-82)
- **Suggestion:** Throw in `requirePrefs()` if not initialized.

### M-06: `MaProxyImageFetcher` doesn't handle HTTP redirects
- **File:** `app/.../musicassistant/MaProxyImageFetcher.kt` (lines 63-84)

### M-07: `remoteDescriptionSet` non-volatile in WebRTCTransport
- **File:** `app/.../remote/WebRTCTransport.kt` (lines 118-119)
- **Suggestion:** Add `@Volatile`.

### M-08: `DefaultServerPinger.calculateNextInterval()` ignores foreground/background on failure
- **File:** `app/.../network/DefaultServerPinger.kt` (lines 336-351)
- **Suggestion:** Take max of backoff and base interval.
- **Tests:** Yes.

### M-09: `NsdDiscoveryManager.resolvingServices` never cleared on stop
- **File:** `app/.../discovery/NsdDiscoveryManager.kt` (lines 56, 145-155)
- **Suggestion:** Clear in `onDiscoveryStopped`.
- **Tests:** Yes.

### M-10: `WebRTCTransport.destroy()` may silently drop `onClosed` callback
- **File:** `app/.../remote/WebRTCTransport.kt` (lines 216-219)
- **Tests:** Yes.

### M-11: `DefaultServerPinger.pingRemote()` doesn't resume on immediate disconnect
- **File:** `app/.../network/DefaultServerPinger.kt` (lines 401-451)
- **Suggestion:** Resume continuation from `onDisconnected`.
- **Tests:** Yes.

### M-12: `RemoteConnection.parseRemoteId()` has overlapping URL patterns
- **File:** `app/.../remote/RemoteConnection.kt` (lines 73-79)
- **Tests:** Yes.

### M-13: `NetworkEvaluator` uses deprecated `WifiManager.getConnectionInfo()` on API 31+
- **File:** `app/.../network/NetworkEvaluator.kt` (lines 141-149)

### M-14: VPN transport detection in `NetworkEvaluator` unreachable
- **File:** `app/.../network/NetworkEvaluator.kt` (lines 99-105)
- **Suggestion:** Check VPN before WiFi in the `when` chain.
- **Tests:** Yes.

### M-15: Parallel ServerRepository and UnifiedServerRepository sources of truth
- **Files:** `ServerRepository.kt`, `UnifiedServerRepository.kt`
- **Suggestion:** Remove legacy ServerRepository, migrate UnifiedServerRepository to shared/commonMain.

### M-16: `SettingsActivity.restartApp()` calls `exitProcess(0)` - drops pending writes
- **File:** `app/.../SettingsActivity.kt` (lines 57-62)
- **Suggestion:** Use `finishAffinity()` instead.

### M-17: `Platform.base64Decode` uses `java.util.Base64` (requires API 26+, but minSdk is 26)
- **File:** `shared/androidMain/.../platform/Platform.android.kt` (lines 9-10)
- **Note:** minSdk is 26, so this is actually safe. Consider `android.util.Base64` for broader compatibility if minSdk changes.

### M-18: `checkDefaultServerAutoConnect` busy-polls for `mediaController`
- **File:** `app/.../MainActivity.kt` (lines 1306-1328)
- **Suggestion:** Gate auto-connect from mediaControllerFuture listener callback.

### M-19: `observeMaConnectionState()` called in both onCreate and onConfigurationChanged
- **File:** `app/.../MainActivity.kt` (lines 2682-2713)
- **Suggestion:** Move to onCreate with `repeatOnLifecycle(STARTED)`.
- **Tests:** Yes.

### M-20: `LibraryItemGrouper` uses `hashCode()` for synthetic album ID
- **File:** `shared/.../ui/navigation/home/LibraryItemGrouper.kt` (line 105)
- **Suggestion:** Use raw string directly as ID.
- **Tests:** Yes.

### M-21: Infinite scanning poll loop duplicates on every configuration change
- **File:** `app/.../MainActivity.kt` (lines 864-871)
- **Suggestion:** Track job reference and cancel before relaunching.

### M-22: Composition-phase side effects in AppShell (MA connection state)
- **File:** `app/.../ui/AppShell.kt` (lines 243-254)
- **Category:** Critical Compose violation
- **Suggestion:** Replace with `LaunchedEffect(isMaConnected)`.
- **Tests:** Yes.

### M-23: `currentDetail` StateFlow derived via leaked coroutine with stale-read risk
- **File:** `app/.../ui/main/MainActivityViewModel.kt` (lines 101-107)
- **Suggestion:** Use `_detailBackStack.map { it.lastOrNull() }.stateIn(...)`.
- **Tests:** Yes.

### M-24: `viewModel()` called conditionally inside composable
- **File:** `app/.../ui/AppShell.kt` (lines 267-268)
- **Suggestion:** Always call `viewModel()` unconditionally.
- **Tests:** Yes.

### M-25: Duplicate `NowPlayingScreen` composable in two packages
- **Files:** `ui/main/NowPlayingScreen.kt`, `ui/player/NowPlayingScreen.kt`
- **Suggestion:** Delete `ui/player/NowPlayingScreen.kt` if unused.

### M-26: `QueueBottomSheet.kt` and `QueueSheetContent.kt` fully duplicate composables
- **Suggestion:** `QueueBottomSheet` should host `QueueSheetContent` inside ModalBottomSheet.

### M-27: Queue reload on every track title change causes loading flash
- **File:** `app/.../ui/queue/QueueSheetContent.kt` (lines 84-86)
- **Suggestion:** Use silent background refresh, not loading state.

### M-28: `HomeViewModel` uses LiveData instead of StateFlow
- **Suggestion:** Convert to StateFlow for KMP compatibility and consistency.

### M-29: Detail ViewModels make sequential network calls instead of parallel
- **Files:** `AlbumDetailViewModel.kt`, `PlaylistDetailViewModel.kt`
- **Suggestion:** Use `async`/`await` pattern matching HomeViewModel.

### M-30: `StatsViewModel` uses `directExecutor()` for callbacks writing non-thread-safe fields
- **File:** `app/.../ui/stats/StatsViewModel.kt` (lines 63-74)
- **Suggestion:** Use `mainExecutor` instead.

### M-31: `LaunchedEffect` inside `forEach` loop in AddTracksBottomSheet
- **File:** `app/.../ui/detail/components/AddTracksBottomSheet.kt` (lines 200-214)
- **Suggestion:** Use `rememberCoroutineScope()` and call API from onAdd callback.
- **Tests:** Yes.

### M-32: Track removal from playlist has no ViewModel path
- **File:** `app/.../ui/detail/PlaylistDetailScreen.kt` (lines 54-55)
- **Suggestion:** Wire `viewModel.removeTrack(index)` from screen.
- **Tests:** Yes.

### M-33: `ProxyConnectDialog` Fragment silently swallows all connection errors
- **File:** `app/.../ui/remote/ProxyConnectDialog.kt` (lines 198-205)
- **Suggestion:** Add error state and display inline.
- **Tests:** Yes.

### M-34: `SendSpinTheme` casts `view.context` to `Activity` unconditionally
- **File:** `app/.../ui/theme/Theme.kt` (line 112)
- **Suggestion:** Use safe cast `as? Activity ?: return@SideEffect`.
- **Tests:** Yes.

### M-35: `MaLoginStep` port field ignores external changes after initial composition
- **File:** `app/.../ui/wizard/steps/MaLoginStep.kt` (lines 107-114)
- **Suggestion:** Use `remember(port) { mutableStateOf(port.toString()) }`.
- **Tests:** Yes.

### M-36: Context captured stale in `remember {}` lambdas in AppShell
- **File:** `app/.../ui/AppShell.kt` (lines 671-703)
- **Suggestion:** Use `rememberUpdatedState(LocalContext.current)`.

### M-37: `STATE_CONNECTED` handler falls through to empty address
- **File:** `app/.../MainActivity.kt` (lines 1840-1853)
- **Suggestion:** Carry UnifiedServer reference in connection state.
- **Tests:** Yes.

---

## LOW Issues

### L-01: Massive duplication between WebSocketTransport and ProxyWebSocketTransport
- **Suggestion:** Extract `BaseWebSocketTransport` abstract class.

### L-02: `destroy()` calls `disconnect()` causing unexpected callback during teardown
- **File:** `app/.../sendspin/SendSpinClient.kt` (lines 626-636)

### L-03: `booleanOrDefault` uses string comparison instead of `booleanOrNull`
- **File:** `shared/.../sendspin/protocol/message/MessageParser.kt` (lines 199-202)

### L-04: OpusDecoder CSD buffers use `nativeOrder()` instead of LITTLE_ENDIAN
- **File:** `app/.../sendspin/decoder/OpusDecoder.kt` (lines 58-71)

### L-05: `stateCallback` missing `@Volatile` in SyncAudioPlayer
- **File:** `app/.../sendspin/SyncAudioPlayer.kt` (line 307)

### L-06: `syncAudioPlayer` not `@Volatile` in PlaybackService
- **File:** `app/.../playback/PlaybackService.kt` (line 122)

### L-07: SendSpinPlayer uses plain ArrayList for listeners (no snapshot before iteration)
- **File:** `app/.../playback/SendSpinPlayer.kt` (line 68)
- **Suggestion:** Use CopyOnWriteArrayList or `.toList()` before iterating.

### L-08: `AudioDecoderFactory.isMediaCodecSupported()` instantiates full codec for capability check
- **File:** `app/.../sendspin/decoder/AudioDecoderFactory.kt` (lines 72-81)
- **Suggestion:** Use `MediaCodecList.findDecoderForFormat()`.

### L-09: `framePosition` wrap on pre-API-28 hardware triggers spurious reanchor
- **File:** `app/.../sendspin/SyncAudioPlayer.kt` (lines 2247-2251)

### L-10: `dacLoopCalibrations` sorted on every sync update (already time-ordered)
- **File:** `app/.../sendspin/SyncAudioPlayer.kt` (line 2446)
- **Suggestion:** Remove sort, use linear scan on already-ordered deque.

### L-11: Statistics counters not `@Volatile` in SyncAudioPlayer
- **File:** `app/.../sendspin/SyncAudioPlayer.kt` (lines 400-407)

### L-12: `pausedAtUs` not `@Volatile`
- **File:** `app/.../sendspin/SyncAudioPlayer.kt` (line 303)

### L-13: `SyncStats` pure domain model in app module (should be in commonMain)
- **File:** `app/.../model/SyncStats.kt`

### L-14: `ServerInfo` is structural duplicate of `UnifiedServer.LocalConnection`
- **File:** `app/.../ServerInfo.kt`

### L-15: Auth tokens stored in plain-text SharedPreferences
- **File:** `app/.../UserSettings.kt` (lines 405-453)

### L-16: `DebugLogger` session state fields not `@Volatile`
- **File:** `app/.../debug/DebugLogger.kt` (lines 40-42)

### L-17: Hardcoded English strings throughout UI components (accessibility/i18n)
- **Files:** Multiple - AppShell, PlayerSheetContent, QueueBottomSheet, QueueSheetContent, SaveQueueAsPlaylistDialog, SearchResultItem, TrackListItem, AlbumGridItem, MediaCard, MediaCarousel, ActionRow, AddToQueueButton

### L-18: Various minor Compose best practice issues
- Infinite animation running when not needed (ServerListEmptyState)
- Index-based key in PlaylistDetailScreen (should use itemId only)
- Redundant fontSize overrides matching typography scale
- Duplicate import in HeroHeader
- NavigationContentScreen appears to be dead code
- QueueSheetFragment appears to be dead code

---

## Test Coverage Gaps

The following areas currently have **no test coverage** and were identified as needing tests:

### Protocol & Filters
1. `SendspinTimeFilter` - stabilityScore convergence under consistent measurements
2. `SyncErrorFilter` - drift adaptation rate after step change
3. `SyncErrorFilter` - drift convergence with constant-rate measurements
4. `TimeSyncManager` - burst cancellation / mid-burst stop recovery
5. `TimeSyncManager` - RTT history state consistency under concurrent stop
6. `SendSpinClient` - onDisconnected callback count verification
7. `SendSpinClient` - proxy auth state machine (auth_ok, auth_failed, server/hello as first message)

### Audio Pipeline
8. `MediaCodecDecoder` - input queue pressure frame drop behavior
9. `MediaCodecDecoder` - flush/decode sequence without start()
10. `MediaCodecDecoder` - format change interleaved with output
11. `SyncAudioPlayer` - rapid start/stop cycles (ANR detection)
12. `SyncAudioPlayer` - concurrent queueChunk/clearBuffer
13. `SyncAudioPlayer` - clearBuffer during active playback (click/pop verification)
14. `MetadataForwardingPlayer` - double listener registration
15. `PlaybackService` - decoder configure failure and fallback

### Music Assistant
16. `MaCommandMultiplexer` - timeout cleanup (pendingCommandCount returns to zero)
17. `MaCommandMultiplexer` - concurrent partial batch accumulation
18. `MaDataChannelTransport` - List<T> argument serialization
19. `MaCommandMultiplexer` - odd-length hex body handling
20. `MusicAssistantManager` - concurrent onServerConnected calls
21. `MusicAssistantManager` - isAvailable state transitions

### Networking
22. `AutoReconnectManager` - cancellation during active attempt
23. `AutoReconnectManager` - onNetworkAvailable unbounded retry prevention
24. `DefaultServerPinger` - foreground/background + failure count matrix
25. `DefaultServerPinger` - immediate server-side close on pingRemote
26. `NsdDiscoveryManager` - concurrent start/stop cycling
27. `NsdDiscoveryManager` - resolvingServices cleanup on stop/restart
28. `SignalingClient` - concurrent connect() calls
29. `WebRTCTransport` - destroy() from each state (Connecting, Connected, Failed, Closed)
30. `RemoteConnection` - URL pattern matching with hyphenated IDs
31. `ConnectionSelector` - VPN+WiFi transport combination

### Models & Core
32. `UserSettings` - getPlayerId() stability across initialization timing
33. `SyncStats` - fromBundle() with unrecognized enum values
34. `ServerRepository` - concurrent add/remove discovered servers
35. `PlaybackState` - interpolatedPositionMs after withMetadata(positionMs=0)
36. `UnifiedServerRepository` - allServers updates without explicit updateCombinedServers()
37. `LibraryItemGrouper` - hashCode collision handling

### UI
38. `AppShell` - tab selection after isMaConnected transitions
39. `MainActivityViewModel` - currentDetail consistency with detailBackStack
40. `QueueSheetContent` - no loading flash on track change
41. `HomeViewModel` - StateFlow conversion (currently LiveData)
42. `AlbumDetailViewModel`/`PlaylistDetailViewModel` - parallel network call verification
43. `StatsViewModel` - background thread MediaController completion
44. `AddTracksBottomSheet` - single API call per track tap
45. `PlaylistDetailScreen` - remove track functionality
46. `QueueBottomSheet`/`QueueSheetContent` - swipe state reset on failed removal
47. `PlaylistsViewModel` - undo delete preserves concurrent changes

---

*Generated by 7 parallel code review agents analyzing 190 Kotlin files across the SendspinDroid KMP codebase.*
