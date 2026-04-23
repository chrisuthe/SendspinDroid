# Codec-Safe Decoder Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move MediaCodec decoding off the OkHttp WebSocket IO thread via a
single-owner decode coroutine, using a bounded Kotlin `Channel` with
suspend-on-full semantics. Eliminates H-4 (WS-IO blocked by decode cost)
and M-8 (decoder TOCTOU) without reintroducing PR #142's FLAC-corruption
regression.

**Architecture:** Dedicated decode coroutine on `Dispatchers.IO.limitedParallelism(1)`
(serialized, single-thread-equivalent). Owns `audioDecoder` exclusively for
its entire lifetime. All lifecycle events (StartStream, Flush, Release)
travel through the same `Channel<DecodeTask>(capacity = 500)` as chunks,
preserving FIFO ordering.

**Tech Stack:** Kotlin coroutines, existing `AudioDecoder` interface,
JUnit 4 + mockk (existing), no new dependencies.

**Spec:** See `docs/superpowers/specs/2026-04-23-codec-safe-decoder-redesign-design.md`
for context, prior-art rationale, and the specific reason PR #142 failed.

---

## File Structure

**Create:**
- `android/app/src/test/java/com/sendspindroid/playback/FakeAudioDecoder.kt`
- `android/app/src/test/java/com/sendspindroid/playback/DecoderPipelineIntegrationTest.kt`

**Modify:**
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`
  (decode coroutine + channel + lifecycle-on-channel)
- `android/app/src/test/java/com/sendspindroid/playback/StreamStartDecoderPipelineTest.kt`
  (if existing tests directly observe `audioDecoder` lifecycle on main thread)

---

## Task 1: FakeAudioDecoder with contiguity enforcement

**Files:**
- Create: `android/app/src/test/java/com/sendspindroid/playback/FakeAudioDecoder.kt`

This is the test harness that would have caught PR #142's drop-oldest bug.
The fake models MediaCodec's sensitivity to missing input: if any chunk in
the input stream is dropped, subsequent `decode()` calls throw.

- [ ] **Step 1: Write FakeAudioDecoder**

Reference the existing `AudioDecoder` interface to get the exact method
signatures. Expected shape:

```kotlin
package com.sendspindroid.playback

import com.sendspindroid.sendspin.decoder.AudioDecoder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Test-only AudioDecoder that enforces input contiguity. Models the way
 * real MediaCodec (FLAC especially) transitions to ERROR state when it
 * receives a non-contiguous chunk sequence.
 *
 * Each chunk is expected to start with an 8-byte big-endian sequence
 * number. If any chunk is missing (next seq != last seq + 1), every
 * subsequent decode() throws until flush() is called.
 *
 * Output PCM is deterministic: for input chunk N, returns a 480-sample
 * (10 ms at 48 kHz stereo 16-bit) ByteArray whose first 8 bytes encode N.
 * Tests can reconstruct the output sequence to assert no data loss.
 */
class FakeAudioDecoder : AudioDecoder {
    val configureCalls = AtomicInteger(0)
    val flushCalls = AtomicInteger(0)
    val releaseCalls = AtomicInteger(0)
    val decodeCalls = AtomicInteger(0)
    val nonContiguousInputDetected = java.util.concurrent.atomic.AtomicBoolean(false)

    private var expectedNextSeq = 0L
    private var errored = false
    private val outputs = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    val observedSequences: List<Long> get() = outputs.toList()

    override fun configure(sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) {
        configureCalls.incrementAndGet()
        expectedNextSeq = 0L
        errored = false
    }

    override fun decode(audioData: ByteArray): ByteArray? {
        decodeCalls.incrementAndGet()
        if (errored) throw IllegalStateException("decoder is in ERROR state")
        require(audioData.size >= 8) { "input chunk must carry 8-byte seq prefix" }
        val seq = readLongBE(audioData, 0)
        if (seq != expectedNextSeq) {
            errored = true
            nonContiguousInputDetected.set(true)
            throw IllegalStateException("non-contiguous input: expected $expectedNextSeq got $seq")
        }
        expectedNextSeq = seq + 1
        outputs.add(seq)
        return makePcmWithSeq(seq)
    }

    override fun flush() {
        flushCalls.incrementAndGet()
        errored = false
        expectedNextSeq = 0L
    }

    override fun release() {
        releaseCalls.incrementAndGet()
    }

    companion object {
        fun makeChunk(seq: Long, payloadBytes: Int = 32): ByteArray {
            val out = ByteArray(8 + payloadBytes)
            writeLongBE(out, 0, seq)
            return out
        }
        fun makePcmWithSeq(seq: Long): ByteArray {
            val pcm = ByteArray(480 * 4) // 10ms of stereo 16-bit at 48kHz
            writeLongBE(pcm, 0, seq)
            return pcm
        }
        fun readLongBE(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
        fun writeLongBE(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = ((v shr (56 - i * 8)) and 0xFF).toByte()
        }
    }
}
```

NOTE: Check the actual `AudioDecoder` interface at
`android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/decoder/AudioDecoder.kt`
(or equivalent location). Match its exact method signatures. The sketch
above is approximate - adjust as needed.

- [ ] **Step 2: Write a sanity test for the fake itself**

Create `android/app/src/test/java/com/sendspindroid/playback/FakeAudioDecoderTest.kt`
with these cases:

  - contiguous sequence decodes cleanly, outputs match inputs
  - missing chunk (skip seq) causes error on the next decode
  - flush() resets error state
  - release() is counted

- [ ] **Step 3: Run the tests**

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.playback.FakeAudioDecoderTest"
```

Expected: all pass.

- [ ] **Step 4: Commit**

```
git add android/app/src/test/java/com/sendspindroid/playback/FakeAudioDecoder.kt \
        android/app/src/test/java/com/sendspindroid/playback/FakeAudioDecoderTest.kt
git commit -m "test: FakeAudioDecoder enforcing input contiguity"
```

---

## Task 2: Integration test that reproduces PR #142's burst-corruption scenario

**Files:**
- Create: `android/app/src/test/java/com/sendspindroid/playback/DecoderPipelineIntegrationTest.kt`

This test MUST be written before the implementation in Task 3-5. It
should fail against the current code (decode on WS-IO is correct but
synchronous) and fail against PR #142's design (drop-oldest corrupts
the fake decoder just like it corrupted real MediaCodec). It must
pass against the new suspend-on-full design.

- [ ] **Step 1: Write the burst test**

```kotlin
@Test
fun `burst of 200 chunks at stream start preserves codec contiguity`() {
    // Set up PlaybackService with FakeAudioDecoder installed via
    // AudioDecoderFactory mock. Inject a mocked SyncAudioPlayer that
    // records queueChunk calls.

    // Trigger onStreamStart(codec="flac", 48000, 2, 16, headerBytes)
    // Trigger 200 rapid onAudioChunk calls with sequential seq 0..199
    // (all from a thread simulating OkHttp WS-IO)

    // Wait for decode coroutine to drain (use a CountDownLatch or similar
    // synchronization point - NOT Thread.sleep)

    // Assertions:
    assertFalse("decoder must not have seen non-contiguous input",
        fakeDecoder.nonContiguousInputDetected.get())
    assertEquals("all 200 chunks must have been decoded", 200, fakeDecoder.decodeCalls.get())
    assertEquals("SyncAudioPlayer must have received 200 PCM outputs",
        200, mockSyncPlayer.queueChunkCallCount)
}
```

- [ ] **Step 2: Verify this test fails against current code**

Before continuing, confirm the test compiles. It will fail because the
current code decodes synchronously on the test thread and the test is
written against the NEW API (which doesn't exist yet). Capture the
exact failure mode before proceeding.

- [ ] **Step 3: Write two more integration tests**

```kotlin
@Test
fun `stream start followed by chunks then stream end releases decoder cleanly`()

@Test
fun `stream clear flushes decoder without dropping chunks before the flush`()
```

These should be similar shape. All three tests initially fail because
the new API doesn't exist.

- [ ] **Step 4: Commit the tests (failing)**

```
git add android/app/src/test/java/com/sendspindroid/playback/DecoderPipelineIntegrationTest.kt
git commit -m "test: decoder pipeline integration tests (currently failing; drive upcoming refactor)"
```

---

## Task 3: Introduce DecodeTask sealed class and the channel

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

- [ ] **Step 1: Add the DecodeTask sealed class**

Near the top of the class (after the companion object), add:

```kotlin
private sealed class DecodeTask {
    data class Chunk(val serverTimeMicros: Long, val audioData: ByteArray) : DecodeTask()
    data class StartStream(
        val codec: String,
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int,
        val codecHeader: ByteArray?,
    ) : DecodeTask()
    object Flush : DecodeTask()
    object Release : DecodeTask()
}

// Separate dispatcher for the decode worker. limitedParallelism(1) gives
// single-thread serialization guarantees without a dedicated thread.
private val decodeDispatcher = Dispatchers.IO.limitedParallelism(1)

// Capacity = 500 ≈ 10 seconds at the expected 50 chunks/sec. Sized to
// absorb cold-start + pre-buffered bursts (~100 entries) with 5x headroom.
// See docs/superpowers/specs/2026-04-23-codec-safe-decoder-redesign-design.md.
private val decodeChannel = Channel<DecodeTask>(capacity = 500)
```

- [ ] **Step 2: Add the decode coroutine launch in onCreate (or equivalent init path)**

Find the `serviceScope` / existing coroutine setup in PlaybackService's
initialization. Launch a coroutine that consumes `decodeChannel`:

```kotlin
private var decodeJob: Job? = null

private fun startDecodeWorker() {
    decodeJob = serviceScope.launch(decodeDispatcher) {
        for (task in decodeChannel) {
            try {
                when (task) {
                    is DecodeTask.Chunk -> handleDecodeChunk(task)
                    is DecodeTask.StartStream -> handleDecodeStartStream(task)
                    DecodeTask.Flush -> handleDecodeFlush()
                    DecodeTask.Release -> handleDecodeRelease()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Decode worker error on task $task", e)
            }
        }
    }
}
```

- [ ] **Step 3: Add handler method stubs (bodies filled in Task 4)**

```kotlin
private suspend fun handleDecodeChunk(t: DecodeTask.Chunk) {
    // Task 4 implementation
}
private suspend fun handleDecodeStartStream(t: DecodeTask.StartStream) {
    // Task 4 implementation
}
private suspend fun handleDecodeFlush() {
    // Task 4 implementation
}
private suspend fun handleDecodeRelease() {
    // Task 4 implementation
}
```

- [ ] **Step 4: Build (compile only)**

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin
```

Expected: builds.

- [ ] **Step 5: Commit**

```
git add android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "refactor: scaffold decode worker channel + DecodeTask (no behavior change)"
```

---

## Task 4: Move decoder lifecycle onto the channel

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

Implement the handler methods and reroute the callback sites.

- [ ] **Step 1: Implement handleDecodeStartStream**

Move the existing body of `onStreamStart` (lines ~1301-1331) into this
handler. It runs on the decode dispatcher so it owns `audioDecoder`
mutations.

```kotlin
private suspend fun handleDecodeStartStream(t: DecodeTask.StartStream) {
    // Same body as the existing onStreamStart, with one change:
    // no mainHandler.post wrapper needed since we're already off main thread.
    // Release old decoder, create new, configure, set decoderReady = true.
}
```

- [ ] **Step 2: Implement handleDecodeChunk**

```kotlin
private suspend fun handleDecodeChunk(t: DecodeTask.Chunk) {
    val decoder = audioDecoder ?: return
    val pcmData = try {
        decoder.decode(t.audioData)
    } catch (e: Exception) {
        Log.e(TAG, "Decode error on chunk", e)
        return
    }
    val player = syncAudioPlayer ?: return
    if (pcmData != null) {
        player.queueChunk(t.serverTimeMicros, pcmData)
    }
}
```

- [ ] **Step 3: Implement handleDecodeFlush and handleDecodeRelease**

```kotlin
private suspend fun handleDecodeFlush() {
    audioDecoder?.flush()
}

private suspend fun handleDecodeRelease() {
    audioDecoder?.release()
    audioDecoder = null
    decoderReady = false
}
```

- [ ] **Step 4: Rewrite onStreamStart to send StartStream**

Replace the existing body of `onStreamStart` (lines ~1301-1331) with:

```kotlin
override fun onStreamStart(codec: String, sampleRate: Int, channels: Int,
                           bitDepth: Int, codecHeader: ByteArray?) {
    // Track stream metadata for diagnostics that run on main thread
    currentCodec = codec
    currentStreamConfig = StreamConfig(codec, sampleRate, channels, bitDepth)

    // Hand decoder lifecycle to the decode worker via the channel.
    // decoderReady is set optimistically here; the decode coroutine resets
    // it to false briefly during decoder recreation, then back to true.
    // A chunk enqueued during the brief gap will be processed with the
    // new decoder because the channel is FIFO.
    decoderReady = true
    serviceScope.launch { decodeChannel.send(
        DecodeTask.StartStream(codec, sampleRate, channels, bitDepth, codecHeader)
    ) }
}
```

- [ ] **Step 5: Rewrite onStreamClear to send Flush**

```kotlin
override fun onStreamClear() {
    // ... any main-thread-only side effects that already exist ...
    serviceScope.launch { decodeChannel.send(DecodeTask.Flush) }
}
```

- [ ] **Step 6: Rewrite onAudioChunk to send Chunk**

```kotlin
override fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray) {
    if (!decoderReady) return
    // Non-blocking path when channel has space; suspending path when full.
    // channel.send from a non-suspending context requires launch.
    serviceScope.launch { decodeChannel.send(
        DecodeTask.Chunk(serverTimeMicros, audioData)
    ) }
}
```

NOTE on the launch wrapper: `onAudioChunk` is called from OkHttp's
non-suspending listener. Wrapping in `serviceScope.launch { ... send() }`
means the send happens on a coroutine that suspends if the channel is
full, while `onAudioChunk` itself returns immediately to the WS-IO
thread. This is the structural fix for H-4 — WS-IO's return path is
always constant-time. The launched coroutine inherits serviceScope and
is cheap (no thread creation).

Open question for reviewer: should we instead use
`decodeChannel.trySend(...)` and log on failure? No - trySend returns
ClosedSendChannelException or false on full, either of which drops
the chunk. We specifically want suspend-on-full, not drop-on-full.

- [ ] **Step 7: Update onDestroy**

Before existing `audioDecoder?.release()`:

```kotlin
// Send a final Release through the channel so it runs after any
// pending decode tasks, then close the channel and join the worker.
decodeChannel.trySend(DecodeTask.Release)
decodeChannel.close()
runBlocking {
    withTimeoutOrNull(500) { decodeJob?.join() }
}
// The decode worker has now released audioDecoder; remove the
// redundant main-thread release call.
```

- [ ] **Step 8: Remove `@Volatile` from `audioDecoder`**

Since only the decode coroutine mutates it now, single-writer, no
volatile needed. Keep `@Volatile` on `decoderReady` (WS-IO still
reads this as a fast-path gate).

- [ ] **Step 9: Remove the TOCTOU-mitigation local-ref capture**

In the old `onAudioChunk` the code did:
```
val decoder = audioDecoder
// ... decoder?.decode(...)
```
No longer needed; `audioDecoder` is read exclusively on the decode
dispatcher inside `handleDecodeChunk`.

- [ ] **Step 10: Run the integration tests (should now pass)**

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.playback.DecoderPipelineIntegrationTest"
```

Expected: all three tests pass.

- [ ] **Step 11: Run the full app test suite**

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest
```

Expected: all pass. If existing tests in `StreamStartDecoderPipelineTest`
fail because they observed main-thread decoder mutations, update them
to observe via the channel.

- [ ] **Step 12: Commit**

```
git add android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt \
        android/app/src/test/java/com/sendspindroid/playback/StreamStartDecoderPipelineTest.kt
git commit -m "refactor: decoder lifecycle and decode run on dedicated coroutine (H-4 + M-8)"
```

---

## Task 5: Worst-case-burst test + teardown test

**Files:**
- Modify: `android/app/src/test/java/com/sendspindroid/playback/DecoderPipelineIntegrationTest.kt`

Broader coverage now that the implementation is in place.

- [ ] **Step 1: Add a test that overflows the queue and verifies suspend-on-full**

```kotlin
@Test
fun `submitting more than channel capacity suspends producer and preserves contiguity`() {
    // Configure fakeDecoder to sleep ~10 ms per decode (simulating real cost).
    // Submit 600 chunks rapidly (600 > 500 capacity).
    // Measure: WS-IO sending thread should complete when the last chunk is
    // successfully sent (i.e. channel drained enough for 600 total); all 600
    // sequences should appear in decoder output; nonContiguousInputDetected is false.
}
```

- [ ] **Step 2: Add a test for onDestroy teardown ordering**

```kotlin
@Test
fun `onDestroy drains pending chunks before releasing decoder`() {
    // Submit 50 chunks. Immediately call onDestroy.
    // Assert that the decoder saw at most 50 chunks (it's OK to see fewer
    // if the 500 ms timeout hits), and that release() was called exactly once.
}
```

- [ ] **Step 3: Run the extended tests**

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.playback.DecoderPipelineIntegrationTest"
```

Expected: pass.

- [ ] **Step 4: Commit**

```
git add android/app/src/test/java/com/sendspindroid/playback/DecoderPipelineIntegrationTest.kt
git commit -m "test: worst-case burst and teardown coverage for decoder pipeline"
```

---

## Task 6: Device smoke + PR

**Files:** all touched in Tasks 1-5.

- [ ] **Step 1: Full build**

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: all green.

- [ ] **Step 2: Install on device, verify healthy playback**

```
./gradlew :app:installDebug
```

Test matrix (critical - PR #142's failure was on device only):

- [ ] FLAC playback: start a stream, hear audio, confirm no cutout after 1s
- [ ] FLAC track change: skip to next track, confirm new track plays cleanly
- [ ] Opus playback (if available): same matrix
- [ ] PCM passthrough: same matrix
- [ ] Stream start -> stream end -> stream start cycle (pause/resume or
      skip): decoder released and recreated
- [ ] Logcat: no `IllegalStateException: Invalid to call at Released state`
- [ ] Logcat: no `Decode queue full` or overflow warnings during normal play
- [ ] Logcat: at steady state, time-sync bursts succeed on schedule
      (no delays that correlate with decode events)

- [ ] **Step 3: Check for decode-thread-name in adb shell**

```
adb shell ps -T -p $(adb shell pidof com.sendspindroid) | grep -i decode
```

Should show a thread named `DefaultDispatcher-worker` (coroutine pool) or
similar, consuming decoder work. Not a hard requirement - limitedParallelism
borrows from the IO pool rather than creating a dedicated thread - but
worth verifying decode activity is NOT on WS-IO threads named
`OkHttp WebSocket http://...`.

- [ ] **Step 4: Push branch, open PR**

```
git push -u origin task/codec-safe-decoder-redesign
gh pr create --title "fix: codec-safe decoder redesign (H-4 + M-8, second attempt)" --body "..."
```

PR body should cover:
- Links to PR #142 (original attempt) and PR #148 (revert) for context
- Design summary (single-owner channel-based worker, no drop-on-full)
- The exact change that makes this safe where #142 failed (suspend-on-full
  vs drop-oldest)
- The regression test that would have caught #142's failure
- Device test matrix results

- [ ] **Step 5: After review + merge**

Close task #47 in the task list.

---

## Notes for the executor

- **Task 1 + Task 2 are the safety net** — they MUST pass before Task 3-4
  implementation work begins, and MUST continue to pass as Task 3-4 evolve.
  Don't skip-and-circle-back.
- **Task 3 is scaffolding only** — no behavior change should be observable
  after Task 3. The old synchronous path still runs because we haven't
  rewritten the callback sites yet.
- **Task 4 is the switch-over** — callback sites reroute to the channel.
  This is where behavior changes. Tests from Task 2 transition from failing
  to passing.
- **Do not add dropping behavior back under any name** — "graceful overflow",
  "queue full recovery", etc. Suspend-on-full is the correctness property.
  If the test surfaces a scenario where suspending is unacceptable,
  STOP and escalate rather than adding a drop policy.
