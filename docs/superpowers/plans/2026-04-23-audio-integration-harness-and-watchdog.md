# Audio Integration Harness + Stuck-State Watchdog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a fake-AudioTrack integration harness that lets us unit-test
`SyncAudioPlayer` state transitions end-to-end, plus a stuck-state watchdog
that surfaces playback-loop deadlocks in logcat. Together these convert a
class of bugs (tick starvation, FLAC corruption, BUFFERING/PLAYING mismatch)
from "caught by the user on spotty Wi-Fi" to "caught by CI in seconds" or
"self-reports in logs within 5 s."

**Architecture:**

- **Harness:** introduce `AudioSink` interface wrapping the methods
  `SyncAudioPlayer` calls on `android.media.AudioTrack`. Provide
  `AudioTrackSink` (production) wrapping a real `AudioTrack`, and
  `FakeAudioSink` (test-only) with scripted `getTimestamp()` and a write log.
  Inject a `nowNs: () -> Long` clock provider to replace direct
  `System.nanoTime()` calls. Use `kotlinx.coroutines.test` with virtual time
  to drive the playback loop deterministically, or — if full virtual-time
  driving is too invasive — test the extracted helpers (`handleStartGating`,
  `playbackLoopIteration`) directly via reflection/visibility tweaks.
- **Watchdog:** extend the existing stats logger (runs every 1 s in the
  playback loop) with a check: if the state has been non-`PLAYING` for
  longer than `STUCK_STATE_WARNING_S` while chunks are arriving, emit a
  warning log with state, buffered-ms, and estimator status. No recovery
  action — diagnostic only.

**Design decisions (locked in here, not for further debate during execution):**

1. **Interface-based abstraction over mocking.** Introduce `AudioSink`; do
   not try to mock `android.media.AudioTrack` directly. AudioTrack is a
   final Android class with native resources — mocking it is fragile and
   requires Robolectric. A plain-Kotlin interface is cheaper to maintain
   and doesn't add a test runtime dependency.
2. **Thin wrapper, not a rewrite.** `AudioSink` mirrors the exact methods
   currently called on `AudioTrack`. No method consolidation, no semantic
   changes. This keeps the production-behavior diff minimal.
3. **Introduce `SinkTimestamp` data class** so tests don't need
   `android.media.AudioTimestamp` (also final, hard to construct in JVM
   tests). Production sink translates between `AudioTimestamp` and
   `SinkTimestamp`.
4. **Inject clock now, not later.** All `System.nanoTime()` calls in
   `SyncAudioPlayer` are replaced with a `nowNs: () -> Long` injected at
   construction. The default `{ System.nanoTime() }` keeps production
   behaviour identical. Tests inject a controllable clock.
5. **Test-driver decision: start with reflection, graduate to virtual
   time if needed.** Task 7 writes the tick-starvation regression test
   using reflection on `handleStartGatingDacAware`. If that proves brittle
   or insufficient for future tests, Task 10 (follow-up) introduces a
   `TestDispatcher`-based full loop driver. Not committing to full
   virtual-time driving up front — it's a bigger refactor than needed for
   the immediate goal.
6. **Watchdog logs only.** No auto-recovery (reset state, trigger
   reanchor). Recovery changes behaviour and needs its own design. The
   watchdog's only job is diagnostic visibility.

**Tech Stack:** Kotlin, JUnit 4 (existing), mockk (existing), no new test
dependencies.

---

## File Structure

**Create:**
- `android/app/src/main/java/com/sendspindroid/sendspin/audio/AudioSink.kt`
- `android/app/src/main/java/com/sendspindroid/sendspin/audio/AudioTrackSink.kt`
- `android/app/src/test/java/com/sendspindroid/sendspin/audio/FakeAudioSink.kt`
- `android/app/src/test/java/com/sendspindroid/sendspin/SyncAudioPlayerIntegrationTest.kt`

**Modify:**
- `android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt`
  - Accept `audioSink: AudioSink` (optional) and `nowNs: () -> Long` in constructor
  - Replace direct `AudioTrack`/`AudioTimestamp` calls with sink calls
  - Replace all `System.nanoTime()` with `nowNs()`
  - Add stuck-state watchdog to the stats logger
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`
  - Wrap the constructed `AudioTrack` in `AudioTrackSink` when creating
    `SyncAudioPlayer` (only if needed — preferred path is SyncAudioPlayer
    keeps owning AudioTrack creation and wraps it internally)
- `android/app/src/test/java/com/sendspindroid/sendspin/SyncAudioPlayerTest.kt`
  - Update existing tests to pass `nowNs` where construction changed

---

## Task 1: Technical backlog file committed

**Files:**
- Modify: `docs/BACKLOG.md` (already created in this branch's base commit)

- [ ] **Step 1: Verify `docs/BACKLOG.md` contains the SyncAudioPlayer-split
      entry and other deferred items**

Read: `docs/BACKLOG.md`
Expected: mentions "Split SyncAudioPlayer into focused units", MA upstream
issue, and the deferred reliability tasks #16 and #47.

- [ ] **Step 2: No commit needed if already committed in base**

If the file is untracked, commit it:

```bash
git add docs/BACKLOG.md
git commit -m "docs: add BACKLOG.md with deferred technical work"
```

---

## Task 2: Introduce AudioSink interface

**Files:**
- Create: `android/app/src/main/java/com/sendspindroid/sendspin/audio/AudioSink.kt`

- [ ] **Step 1: Write the interface**

```kotlin
package com.sendspindroid.sendspin.audio

/**
 * Abstraction over the audio output device. Production code wraps
 * android.media.AudioTrack via AudioTrackSink; tests use FakeAudioSink.
 *
 * This interface mirrors the methods SyncAudioPlayer calls on AudioTrack —
 * it is not a consolidation or redesign. Method semantics must match
 * AudioTrack's exactly.
 */
interface AudioSink {
    /** Begin playback. Mirrors AudioTrack.play(). */
    fun play()

    /** Pause playback. Mirrors AudioTrack.pause(). */
    fun pause()

    /** Stop playback. Mirrors AudioTrack.stop(). */
    fun stop()

    /** Discard queued audio. Mirrors AudioTrack.flush(). */
    fun flush()

    /** Release native resources. Mirrors AudioTrack.release(). */
    fun release()

    /**
     * Write PCM data. Mirrors AudioTrack.write(buffer, offset, size) in
     * blocking mode. Returns the number of bytes written, or a negative
     * error code.
     */
    fun write(buffer: ByteArray, offset: Int, size: Int): Int

    /**
     * Query the DAC timestamp. Returns null if the hardware hasn't
     * produced a valid timestamp yet (mirrors AudioTrack.getTimestamp()
     * returning false).
     */
    fun getTimestamp(): SinkTimestamp?

    /** Current playback head position in frames. */
    val playbackHeadPosition: Int

    /** Current state (matches AudioTrack.STATE_* constants). */
    val state: Int

    /** Buffer size in bytes. */
    val bufferSizeInBytes: Int
}

/**
 * DAC timestamp snapshot. Mirrors android.media.AudioTimestamp but is a
 * plain data class so it can be constructed in JVM tests without the
 * Android runtime.
 */
data class SinkTimestamp(val framePosition: Long, val nanoTime: Long)
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/audio/AudioSink.kt
git commit -m "feat: introduce AudioSink interface for testable audio output"
```

---

## Task 3: Implement AudioTrackSink (production wrapper)

**Files:**
- Create: `android/app/src/main/java/com/sendspindroid/sendspin/audio/AudioTrackSink.kt`
- Test: `android/app/src/test/java/com/sendspindroid/sendspin/audio/AudioTrackSinkTest.kt`
  (optional — smoke test only, construction requires real AudioTrack)

- [ ] **Step 1: Write the wrapper**

```kotlin
package com.sendspindroid.sendspin.audio

import android.media.AudioTimestamp
import android.media.AudioTrack

/**
 * AudioSink backed by a real android.media.AudioTrack.
 *
 * All methods delegate directly to the underlying track with identical
 * semantics. Callers must construct the AudioTrack (with the desired
 * sample rate, channels, bit depth, buffer size) and hand it in.
 */
class AudioTrackSink(private val track: AudioTrack) : AudioSink {

    private val ts = AudioTimestamp()

    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun stop() = track.stop()
    override fun flush() = track.flush()
    override fun release() = track.release()

    override fun write(buffer: ByteArray, offset: Int, size: Int): Int =
        track.write(buffer, offset, size)

    override fun getTimestamp(): SinkTimestamp? =
        if (track.getTimestamp(ts)) SinkTimestamp(ts.framePosition, ts.nanoTime)
        else null

    override val playbackHeadPosition: Int
        get() = track.playbackHeadPosition

    override val state: Int
        get() = track.state

    override val bufferSizeInBytes: Int
        get() = track.bufferSizeInFrames * 4  // caller-known bytesPerFrame; see note
}
```

**Note on `bufferSizeInBytes`:** `AudioTrack.bufferSizeInFrames` is an Int;
bytes-per-frame depends on format. Either pass `bytesPerFrame` into the
constructor (preferred — the production caller already knows it) or expose
`bufferSizeInFrames` directly and let callers compute. Pick whichever makes
the downstream refactor (Task 4) cleanest.

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/audio/AudioTrackSink.kt
git commit -m "feat: add AudioTrackSink wrapping real AudioTrack"
```

---

## Task 4: Implement FakeAudioSink (test-only)

**Files:**
- Create: `android/app/src/test/java/com/sendspindroid/sendspin/audio/FakeAudioSink.kt`
- Test: `android/app/src/test/java/com/sendspindroid/sendspin/audio/FakeAudioSinkTest.kt`

- [ ] **Step 1: Write the fake**

```kotlin
package com.sendspindroid.sendspin.audio

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Test-only AudioSink that records all calls and returns scripted values.
 *
 * Usage:
 * ```
 * val sink = FakeAudioSink()
 * sink.scriptTimestamp(framePosition = 960, nanoTime = 1_000_000_000)
 * // ... drive SyncAudioPlayer ...
 * assertEquals(1, sink.playCallCount)
 * assertEquals(960 * 4, sink.totalBytesWritten)
 * ```
 */
class FakeAudioSink(
    override val bufferSizeInBytes: Int = 192_000,  // 1s at 48kHz stereo 16-bit
) : AudioSink {

    // --- call counters ---
    val playCallCount = AtomicInteger(0)
    val pauseCallCount = AtomicInteger(0)
    val stopCallCount = AtomicInteger(0)
    val flushCallCount = AtomicInteger(0)
    val releaseCallCount = AtomicInteger(0)

    // --- write log ---
    private val _writes = ConcurrentLinkedQueue<WriteRecord>()
    val writes: List<WriteRecord> get() = _writes.toList()
    val totalBytesWritten = AtomicLong(0)

    data class WriteRecord(val offset: Int, val size: Int, val snapshotFirstBytes: ByteArray)

    // --- timestamp script ---
    @Volatile private var nextTimestamp: SinkTimestamp? = null

    /** Configure what getTimestamp() returns next. Null means "not ready." */
    fun scriptTimestamp(ts: SinkTimestamp?) {
        nextTimestamp = ts
    }

    fun scriptTimestamp(framePosition: Long, nanoTime: Long) {
        nextTimestamp = SinkTimestamp(framePosition, nanoTime)
    }

    // --- AudioSink impl ---
    override fun play() { playCallCount.incrementAndGet() }
    override fun pause() { pauseCallCount.incrementAndGet() }
    override fun stop() { stopCallCount.incrementAndGet() }
    override fun flush() { flushCallCount.incrementAndGet() }
    override fun release() { releaseCallCount.incrementAndGet() }

    override fun write(buffer: ByteArray, offset: Int, size: Int): Int {
        _writes.add(WriteRecord(
            offset = offset,
            size = size,
            snapshotFirstBytes = buffer.copyOfRange(offset, minOf(offset + 16, offset + size)),
        ))
        totalBytesWritten.addAndGet(size.toLong())
        return size
    }

    override fun getTimestamp(): SinkTimestamp? = nextTimestamp

    override val playbackHeadPosition: Int = 0  // extend if needed
    override val state: Int = 3  // AudioTrack.STATE_INITIALIZED = 3
}
```

- [ ] **Step 2: Write failing test for the fake**

```kotlin
package com.sendspindroid.sendspin.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeAudioSinkTest {

    @Test
    fun `records write calls with byte counts`() {
        val sink = FakeAudioSink()
        val buf = ByteArray(480 * 4)  // 10ms of stereo 16-bit at 48k
        sink.write(buf, 0, buf.size)
        sink.write(buf, 0, buf.size)
        assertEquals(2, sink.writes.size)
        assertEquals((480 * 4 * 2).toLong(), sink.totalBytesWritten.get())
    }

    @Test
    fun `scriptTimestamp returns configured value, null by default`() {
        val sink = FakeAudioSink()
        assertNull(sink.getTimestamp())
        sink.scriptTimestamp(framePosition = 960, nanoTime = 1_000_000)
        val ts = sink.getTimestamp()!!
        assertEquals(960L, ts.framePosition)
        assertEquals(1_000_000L, ts.nanoTime)
    }

    @Test
    fun `play pause stop flush release counted`() {
        val sink = FakeAudioSink()
        sink.play(); sink.pause(); sink.stop(); sink.flush(); sink.release()
        assertEquals(1, sink.playCallCount.get())
        assertEquals(1, sink.pauseCallCount.get())
        assertEquals(1, sink.stopCallCount.get())
        assertEquals(1, sink.flushCallCount.get())
        assertEquals(1, sink.releaseCallCount.get())
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.audio.FakeAudioSinkTest"
```

Expected: all three tests pass.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/test/java/com/sendspindroid/sendspin/audio/
git commit -m "test: add FakeAudioSink for integration-level SyncAudioPlayer tests"
```

---

## Task 5: Inject nowNs clock into SyncAudioPlayer

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt`
- Modify: `android/app/src/test/java/com/sendspindroid/sendspin/SyncAudioPlayerTest.kt` (if any existing tests construct the player)

- [ ] **Step 1: Add nowNs parameter to SyncAudioPlayer constructor**

Change the primary constructor signature:

```kotlin
class SyncAudioPlayer(
    private val timeFilter: SendspinTimeFilter,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitDepth: Int,
    private val nowNs: () -> Long = { System.nanoTime() },  // NEW
) { ... }
```

- [ ] **Step 2: Replace all `System.nanoTime()` inside SyncAudioPlayer with `nowNs()`**

```bash
# Count before:
grep -c "System\.nanoTime()" android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt
# Expected: 28

# Apply replacements — but do this by reading each call site, not blind sed,
# because some may be inside string interpolations or comments.
```

Use the Edit tool to replace each occurrence. The typical pattern is:

```kotlin
// Before:
val nowMicros = System.nanoTime() / 1000

// After:
val nowMicros = nowNs() / 1000
```

- [ ] **Step 3: Update OutputLatencyEstimator construction to share the clock**

```kotlin
// In SyncAudioPlayer (~line 333):
private val latencyEstimator = com.sendspindroid.sendspin.latency.OutputLatencyEstimator(
    nowNs = nowNs,  // was: { System.nanoTime() }
)
```

- [ ] **Step 4: Run existing tests to verify no regressions**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.SyncAudioPlayerTest"
```

Expected: all existing tests pass (default `nowNs` = `System.nanoTime()` preserves prod behaviour).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt
git commit -m "refactor: inject nowNs clock into SyncAudioPlayer for testability"
```

---

## Task 6: Route SyncAudioPlayer's AudioTrack calls through AudioSink

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt`

- [ ] **Step 1: Replace the internal `audioTrack: AudioTrack?` field with `audioSink: AudioSink?`**

Keep `AudioTrack` creation inside SyncAudioPlayer for now (Task 6 is about the
internal seam, not external construction). Wrap the created track in
`AudioTrackSink` right after `.build()`:

```kotlin
// Where AudioTrack is currently built (~line 543):
val track = AudioTrack.Builder()
    .setAudioAttributes(...)
    .setAudioFormat(...)
    .setBufferSizeInBytes(bufferSize)
    .setTransferMode(AudioTrack.MODE_STREAM)
    .build()
audioSink = AudioTrackSink(track)
```

- [ ] **Step 2: Replace every `audioTrack?.foo()` and `audioTrack!!.foo()` with
       `audioSink?.foo()` / `audioSink!!.foo()`**

Find-and-replace each usage. Pay attention to:
- `audioTrack.write(...)` -> `audioSink.write(...)`
- `audioTrack.getTimestamp(audioTimestamp)` -> `audioSink.getTimestamp()?.let { ts -> ... }` (signature changed — no shared AudioTimestamp buffer any more)
- `audioTrack.play() / pause() / stop() / flush() / release()` -> `audioSink.foo()`
- `audioTrack.playbackHeadPosition` -> `audioSink.playbackHeadPosition`
- `audioTrack.state` -> `audioSink.state`

- [ ] **Step 3: Delete the now-unused `audioTimestamp: AudioTimestamp` field
       (line 377) and the `AudioTimestamp` import (line 6)**

- [ ] **Step 4: Remove `AudioTrack` import** if no longer used in the file.

- [ ] **Step 5: Run build to catch missed references**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -30
```

Expected: compiles clean.

- [ ] **Step 6: Run full test suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: all tests pass.

- [ ] **Step 7: Install on device and verify normal-path playback still works**

```bash
./gradlew :app:installDebug
# Play a track, confirm audio comes out, check logcat for any new errors.
```

This is the big "did I break prod" checkpoint. Do not proceed until audio
plays normally.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt
git commit -m "refactor: route SyncAudioPlayer AudioTrack calls through AudioSink"
```

---

## Task 7: Add SyncAudioPlayer constructor overload accepting an AudioSink directly

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt`

The goal: tests need to inject `FakeAudioSink` without SyncAudioPlayer
constructing an `AudioTrack` internally.

- [ ] **Step 1: Extract AudioTrack creation into a factory parameter**

Change the primary constructor:

```kotlin
class SyncAudioPlayer(
    private val timeFilter: SendspinTimeFilter,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitDepth: Int,
    private val nowNs: () -> Long = { System.nanoTime() },
    private val sinkFactory: (sampleRate: Int, channels: Int, bitDepth: Int, bufferSize: Int) -> AudioSink = ::defaultSinkFactory,
) {
    private fun defaultSinkFactory(
        sampleRate: Int, channels: Int, bitDepth: Int, bufferSize: Int,
    ): AudioSink {
        val track = AudioTrack.Builder()
            .setAudioAttributes(...)
            .setAudioFormat(...)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        return AudioTrackSink(track)
    }
    // ...
}
```

Note: the `companion object` is a common location for `defaultSinkFactory`
if top-level or member-function form is awkward given the AudioAttributes
code already in the file. Pick whichever matches existing patterns — check
how SyncAudioPlayer currently organises helper methods.

- [ ] **Step 2: Update the AudioTrack creation path to use `sinkFactory(...)`**

```kotlin
// In the existing init method:
val bufferSize = AudioTrack.getMinBufferSize(...)
audioSink = sinkFactory(sampleRate, channels, bitDepth, bufferSize)
```

- [ ] **Step 3: Run existing tests**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: pass (default factory preserves prod behaviour).

- [ ] **Step 4: Smoke-test on device**

Normal playback must still work.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt
git commit -m "refactor: accept sinkFactory in SyncAudioPlayer for test injection"
```

---

## Task 8: Write the tick-starvation regression test

**Files:**
- Create: `android/app/src/test/java/com/sendspindroid/sendspin/SyncAudioPlayerIntegrationTest.kt`

- [ ] **Step 1: Write the failing test**

The test must fail if we revert the tick-starvation fix from PR #153.

```kotlin
package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.audio.FakeAudioSink
import com.sendspindroid.sendspin.audio.SinkTimestamp
import com.sendspindroid.sendspin.latency.OutputLatencyEstimator
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Integration tests for SyncAudioPlayer driven through the AudioSink
 * abstraction with a controlled clock. Exercises real state-machine
 * behaviour without a real AudioTrack.
 */
class SyncAudioPlayerIntegrationTest {

    private val sampleRate = 48000
    private val channels = 2
    private val bitDepth = 16

    @Test
    fun `tick starvation: estimator times out and state machine progresses`() {
        // Controlled clock; tests advance it explicitly.
        var now = 0L
        val nowNs = { now }

        val timeFilter = mockk<SendspinTimeFilter>(relaxed = true)
        every { timeFilter.isReady } returns true
        every { timeFilter.serverToClient(any()) } answers { firstArg() }
        every { timeFilter.clientToServer(any()) } answers { firstArg() }

        val fakeSink = FakeAudioSink()
        val player = SyncAudioPlayer(
            timeFilter = timeFilter,
            sampleRate = sampleRate, channels = channels, bitDepth = bitDepth,
            nowNs = nowNs,
            sinkFactory = { _, _, _, _ -> fakeSink },
        )

        // Reach into private state: put the player into WAITING_FOR_START with
        // dacTimestampsStable = true (mimics the on-device deadlock scenario).
        setPrivate(player, "playbackState", PlaybackState.WAITING_FOR_START)
        setPrivate(player, "dacTimestampsStable", true)

        // The latency estimator is started when AudioTrack/sink is created.
        // Grab the estimator and advance the clock past the 2s timeout.
        val est: OutputLatencyEstimator = getPrivate(player, "latencyEstimator")
        assertEquals(OutputLatencyEstimator.Status.Measuring, est.status)

        // Simulate arrival at WAITING_FOR_START with stable DAC and no DAC
        // samples — invoke handleStartGatingDacAware directly via reflection.
        fakeSink.scriptTimestamp(SinkTimestamp(framePosition = 960, nanoTime = now))

        val method = SyncAudioPlayer::class.java.getDeclaredMethod(
            "handleStartGatingDacAware",
            com.sendspindroid.sendspin.audio.AudioSink::class.java,  // update param type as refactored
        )
        method.isAccessible = true

        // First call: estimator still Measuring, should keep waiting.
        method.invoke(player, fakeSink)
        assertEquals(OutputLatencyEstimator.Status.Measuring, est.status)

        // Advance the clock past the 2s timeout.
        now = 2_100_000_000L

        // Second call: tick() at top of handleStartGatingDacAware must fire the
        // timeout. If the fix is reverted, status stays Measuring.
        method.invoke(player, fakeSink)
        assertNotEquals(
            "estimator must leave Measuring once 2s has elapsed",
            OutputLatencyEstimator.Status.Measuring, est.status,
        )
    }

    // --- reflection helpers ---

    private fun <T> getPrivate(target: Any, name: String): T {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(target) as T
    }

    private fun setPrivate(target: Any, name: String, value: Any?) {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }
}
```

**Note:** the second parameter type of `handleStartGatingDacAware` was
`AudioTrack` before the refactor; after Task 6 it should be `AudioSink`. Adjust
the `getDeclaredMethod` call to match the refactored signature.

- [ ] **Step 2: Verify the test passes with the current fix in place**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.SyncAudioPlayerIntegrationTest.tick starvation*"
```

Expected: PASS.

- [ ] **Step 3: Verify the test fails if the fix is reverted**

Temporarily remove the `latencyEstimator.tick()` call from
`handleStartGatingDacAware` and rerun. Expected: FAIL. Revert the
temporary change.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/test/java/com/sendspindroid/sendspin/SyncAudioPlayerIntegrationTest.kt
git commit -m "test: regression test for tick-starvation deadlock"
```

---

## Task 9: Stuck-state watchdog

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt`

- [ ] **Step 1: Add watchdog state fields**

Near the other stats-related fields, add:

```kotlin
// Stuck-state watchdog: tracks when a non-PLAYING state was first entered.
// Used by the stats logger to surface state-machine deadlocks.
private var stuckStateEnteredAtUs: Long = 0L
private var lastObservedState: PlaybackState = PlaybackState.INITIALIZING

companion object {
    // ... existing constants ...
    private const val STUCK_STATE_WARNING_US = 5_000_000L       // 5s
    private const val STUCK_STATE_WARNING_INTERVAL_US = 10_000_000L  // 10s between warnings
}

private var lastStuckWarningAtUs: Long = 0L
```

- [ ] **Step 2: Write the failing test**

Test file: reuse `SyncAudioPlayerIntegrationTest.kt`.

```kotlin
@Test
fun `watchdog warns when non-PLAYING state persists with chunks arriving`() {
    var now = 0L
    val nowNs = { now }

    val timeFilter = mockk<SendspinTimeFilter>(relaxed = true)
    every { timeFilter.isReady } returns true
    every { timeFilter.serverToClient(any()) } answers { firstArg() }
    every { timeFilter.clientToServer(any()) } answers { firstArg() }

    val fakeSink = FakeAudioSink()
    val player = SyncAudioPlayer(
        timeFilter, sampleRate, channels, bitDepth, nowNs,
        sinkFactory = { _, _, _, _ -> fakeSink },
    )

    setPrivate(player, "playbackState", PlaybackState.WAITING_FOR_START)

    // Put some chunks in the queue so the watchdog has reason to warn.
    val chunkQueue: ConcurrentLinkedQueue<*> = getPrivate(player, "chunkQueue")
    // ... push a chunk; see existing tests for the helper ...

    // Invoke stats-log / watchdog path via reflection.
    val method = SyncAudioPlayer::class.java.getDeclaredMethod("checkStuckState")
    method.isAccessible = true

    // First tick: establishes baseline, no warning yet.
    method.invoke(player)
    val warn1 = getPrivate<Long>(player, "lastStuckWarningAtUs")
    assertEquals("no warning on first observation", 0L, warn1)

    // Advance clock past the 5s threshold.
    now = 5_500_000_000L
    method.invoke(player)
    val warn2 = getPrivate<Long>(player, "lastStuckWarningAtUs")
    assertNotEquals("warning should have fired after 5s stuck", 0L, warn2)
}
```

Expected: FAIL (`checkStuckState` doesn't exist yet).

- [ ] **Step 3: Implement checkStuckState**

```kotlin
/**
 * Watchdog invoked once per stats-log cycle. Warns if the state machine has
 * been in a non-PLAYING state for more than STUCK_STATE_WARNING_US while
 * chunks are arriving (indicating the pipeline is wedged, not just idle).
 *
 * Diagnostic only — no recovery action.
 */
private fun checkStuckState() {
    val nowUs = nowNs() / 1000
    val state = playbackState

    if (state != lastObservedState) {
        lastObservedState = state
        stuckStateEnteredAtUs = nowUs
        return
    }

    if (state == PlaybackState.PLAYING) return

    val stuckUs = nowUs - stuckStateEnteredAtUs
    if (stuckUs < STUCK_STATE_WARNING_US) return

    // Only warn if there's actual audio backlog — don't spam when the user
    // just paused playback.
    if (totalQueuedSamples.get() == 0L) return

    if (nowUs - lastStuckWarningAtUs < STUCK_STATE_WARNING_INTERVAL_US) return
    lastStuckWarningAtUs = nowUs

    val bufferedMs = (totalQueuedSamples.get() * 1000) / sampleRate
    AppLog.Audio.w(
        "WATCHDOG: state=$state stuck for ${stuckUs / 1000}ms, " +
            "buffered=${bufferedMs}ms, chunks=${chunkQueue.size}, " +
            "estimatorStatus=${latencyEstimator.status}, " +
            "dacTimestampsStable=$dacTimestampsStable"
    )
}
```

- [ ] **Step 4: Call checkStuckState from the existing stats logger**

Find the existing 1-second stats log site (search for `"Stats: state="`).
Add a call to `checkStuckState()` immediately before or after the stats
log line.

- [ ] **Step 5: Run the watchdog test**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.SyncAudioPlayerIntegrationTest.watchdog*"
```

Expected: PASS.

- [ ] **Step 6: Full test suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: all pass.

- [ ] **Step 7: Install on device and confirm no spurious warnings in
       normal playback**

Watch logcat during a normal track. `WATCHDOG:` lines must not appear
under healthy playback.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt \
        android/app/src/test/java/com/sendspindroid/sendspin/SyncAudioPlayerIntegrationTest.kt
git commit -m "feat: stuck-state watchdog logs when playback loop deadlocks"
```

---

## Task 10: Build, full-suite test, open PR

**Files:** all touched in Tasks 1-9.

- [ ] **Step 1: Full build**

```bash
./gradlew :app:assembleDebug :shared:testAndroidHostTest :app:testDebugUnitTest
```

Expected: all green.

- [ ] **Step 2: Install on device, verify healthy playback**

```bash
./gradlew :app:installDebug
```

Play a track. Confirm:
- Audio plays immediately (no `WATCHDOG:` lines in logcat).
- Track changes work.
- Pausing / unpausing works.
- Seeking works.

- [ ] **Step 3: Push branch and open PR**

```bash
git push -u origin <branch-name>
gh pr create --title "feat: audio integration harness + stuck-state watchdog" --body "..."
```

PR body should include:
- Motivation (the "we've had three integration bugs slip through unit tests" story)
- Summary of what this adds
- Explicit callout that `SyncAudioPlayer` still owns AudioTrack creation (no prod behaviour change)
- Link to `docs/BACKLOG.md` for the deferred `SyncAudioPlayer` split

- [ ] **Step 4: Note follow-up work**

After merge:
- Consider whether the harness is sufficient or if a `TestDispatcher`-based
  full loop driver is worth adding next.
- Revisit `docs/BACKLOG.md` to decide if any of the deferred items are now
  ripe to pick up.
