# static_delay_ms auto-measurement — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-measure output latency via `AudioTrack.getTimestamp()` deltas and populate `static_delay_ms` on the server in the pre-playback window, so multi-room sync works without manual per-device calibration.

**Architecture:** New `OutputLatencyEstimator` (pure Kotlin, in shared module) performs ring-buffered sampling of write-time vs DAC-time deltas, computes a 20-sample mean with 2 s timeout fallback. `SendspinTimeFilter.staticDelayMicros` is split into `autoMeasuredDelayMicros` + `userSyncOffsetMicros` with a computed sum; the existing `staticDelayMs` getter is preserved. `SyncAudioPlayer` owns the estimator, feeds it events from its existing write + `getTimestamp` paths, and the `WAITING_FOR_START → PLAYING` gate gains a measurement-complete clause. L-3 torn-read on the Kalman `offset` field is fixed alongside.

**Tech Stack:** Kotlin, Android Gradle Plugin, KMP shared module, JUnit 4 (existing pattern: `shared/src/androidHostTest/kotlin`), `:app:testDebugUnitTest` + `:shared:testAndroidHostTest`.

**Spec:** `docs/superpowers/specs/2026-04-21-static-delay-auto-measurement-design.md`

**Worktree:** `C:/CodeProjects/SendspinDroid-static-delay-auto-measurement`, branch `task/static-delay-auto-measurement`, based on `origin/main` at `5a258a7`.

---

## File Structure

**Create:**
- `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimator.kt` — estimator with ring buffer, status enum, callback. Pure Kotlin, no Android deps.
- `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/StaticDelaySource.kt` — small enum (kept in its own file because it's referenced by both the filter and the estimator).
- `android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimatorTest.kt` — comprehensive unit tests.

**Modify:**
- `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/SendspinTimeFilter.kt` — split `staticDelayMicros` into two fields; fix L-3 torn-read on `offset`; add explicit typed setters.
- `android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/SendspinTimeFilterTest.kt` (if it exists; otherwise new) — add additive-sum and torn-read regression coverage.
- `android/app/src/main/java/com/sendspindroid/sendspin/protocol/SendSpinProtocolHandler.kt` — call sites that wrote `timeFilter.staticDelayMs = ...` now call `setServerSyncOffsetMs(...)`; add public `sendClientStateSnapshot()` wrapper.
- `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt` — forward `sendClientStateSnapshot()` so `SyncAudioPlayer` can reach it.
- `android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt` — construct + feed the estimator, add measurement-complete clause to the `WAITING_FOR_START → PLAYING` gate.
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` — three new stats bundle keys; route two existing slider write sites to the new `setUserSyncOffsetMs` setter.

---

## Task 1: Scaffold StaticDelaySource enum + OutputLatencyEstimator skeleton

**Files:**
- Create: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/StaticDelaySource.kt`
- Create: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimator.kt`
- Create: `android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimatorTest.kt`

- [ ] **Step 1.1: Write the failing test for initial status**

```kotlin
// OutputLatencyEstimatorTest.kt
package com.sendspindroid.sendspin.latency

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputLatencyEstimatorTest {

    @Test
    fun `starts in Idle status before start() is called`() {
        val est = OutputLatencyEstimator(nowNs = { 0L })
        assertEquals(OutputLatencyEstimator.Status.Idle, est.status)
    }
}
```

- [ ] **Step 1.2: Run the test to verify it fails**

```bash
cd "C:/CodeProjects/SendspinDroid-static-delay-auto-measurement/android"
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.latency.OutputLatencyEstimatorTest"
```
Expected: FAIL with "unresolved reference: OutputLatencyEstimator".

- [ ] **Step 1.3: Create the enum**

```kotlin
// StaticDelaySource.kt
package com.sendspindroid.sendspin.latency

/**
 * Source that most recently wrote the effective static delay.
 *
 * - [NONE]: no source has written; effective delay is 0
 * - [AUTO]: [OutputLatencyEstimator] converged successfully
 * - [USER]: user's settings slider
 * - [SERVER]: server-pushed `client/sync_offset`
 */
enum class StaticDelaySource { NONE, AUTO, USER, SERVER }
```

- [ ] **Step 1.4: Create the estimator skeleton**

```kotlin
// OutputLatencyEstimator.kt
package com.sendspindroid.sendspin.latency

/**
 * Measures device output latency (time from AudioTrack.write() to sound
 * leaving the DAC) by cross-referencing write timestamps against DAC
 * timestamp callbacks.
 *
 * Pure Kotlin, no Android dependencies. Takes write events in via
 * [recordWrite] and DAC timestamp events in via [recordDacTimestamp];
 * emits a single [Result] via the callback when the session converges
 * or times out.
 *
 * @param nowNs monotonic clock source (System.nanoTime in production,
 *              a mock in tests).
 */
class OutputLatencyEstimator(
    private val nowNs: () -> Long,
) {
    enum class Status { Idle, Measuring, Converged, TimedOut, Cancelled }

    sealed class Result {
        data class Converged(val latencyMicros: Long, val sampleCount: Int) : Result()
        data class TimedOut(val sampleCount: Int) : Result()
    }

    @Volatile var status: Status = Status.Idle
        private set

    fun start(onResult: (Result) -> Unit) {
        TODO("Task 2")
    }

    fun cancel() {
        TODO("Task 7")
    }

    fun recordWrite(framesWritten: Long, writeTimeNs: Long) {
        TODO("Task 2")
    }

    fun recordDacTimestamp(framePosition: Long, dacTimeNs: Long) {
        TODO("Task 3")
    }
}
```

- [ ] **Step 1.5: Run the test to verify it passes**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.latency.OutputLatencyEstimatorTest"
```
Expected: PASS.

- [ ] **Step 1.6: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/ android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/
git commit -m "feat(latency): scaffold OutputLatencyEstimator skeleton"
```

---

## Task 2: Ring buffer for writes + start()

**Files:**
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimator.kt`
- Modify: `android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimatorTest.kt`

- [ ] **Step 2.1: Write the failing test for start() + write recording**

Add this test to `OutputLatencyEstimatorTest.kt`:

```kotlin
@Test
fun `start() transitions to Measuring and accepts writes`() {
    val est = OutputLatencyEstimator(nowNs = { 0L })
    est.start(onResult = {})
    assertEquals(OutputLatencyEstimator.Status.Measuring, est.status)

    // Recording writes does not change status on its own.
    est.recordWrite(framesWritten = 960, writeTimeNs = 1_000_000L)
    assertEquals(OutputLatencyEstimator.Status.Measuring, est.status)
}
```

- [ ] **Step 2.2: Run the test to verify it fails**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.latency.OutputLatencyEstimatorTest.start transitions to Measuring and accepts writes"
```
Expected: FAIL with NotImplementedError.

- [ ] **Step 2.3: Implement start() + internal ring buffer**

Replace the `OutputLatencyEstimator` class body with:

```kotlin
class OutputLatencyEstimator(
    private val nowNs: () -> Long,
    private val ringCapacity: Int = DEFAULT_RING_CAPACITY,
) {
    companion object {
        const val DEFAULT_RING_CAPACITY = 64
    }

    enum class Status { Idle, Measuring, Converged, TimedOut, Cancelled }

    sealed class Result {
        data class Converged(val latencyMicros: Long, val sampleCount: Int) : Result()
        data class TimedOut(val sampleCount: Int) : Result()
    }

    // Ring buffer entry: (framesWritten cumulative, writeTimeNs)
    private data class WriteEntry(val framesWritten: Long, val writeTimeNs: Long)

    @Volatile var status: Status = Status.Idle
        private set

    private val lock = Any()
    private var onResult: ((Result) -> Unit)? = null
    private val ring = ArrayDeque<WriteEntry>(DEFAULT_RING_CAPACITY)

    fun start(onResult: (Result) -> Unit) {
        synchronized(lock) {
            if (status != Status.Idle) return
            this.onResult = onResult
            ring.clear()
            status = Status.Measuring
        }
    }

    fun cancel() {
        TODO("Task 7")
    }

    fun recordWrite(framesWritten: Long, writeTimeNs: Long) {
        synchronized(lock) {
            if (status != Status.Measuring) return
            if (ring.size >= ringCapacity) ring.removeFirst()
            ring.addLast(WriteEntry(framesWritten, writeTimeNs))
        }
    }

    fun recordDacTimestamp(framePosition: Long, dacTimeNs: Long) {
        TODO("Task 3")
    }
}
```

- [ ] **Step 2.4: Run the test to verify it passes**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.latency.OutputLatencyEstimatorTest"
```
Expected: PASS (both tests).

- [ ] **Step 2.5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimator.kt android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimatorTest.kt
git commit -m "feat(latency): ring buffer + start() in OutputLatencyEstimator"
```

---

## Task 3: Sample evaluation on recordDacTimestamp

**Files:**
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimator.kt`
- Modify: `android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimatorTest.kt`

- [ ] **Step 3.1: Write the failing tests**

Add to `OutputLatencyEstimatorTest.kt`:

```kotlin
@Test
fun `recordDacTimestamp produces a sample when writeTime for frame is in ring`() {
    var captured: OutputLatencyEstimator.Result? = null
    val est = OutputLatencyEstimator(nowNs = { 0L })
    est.start { captured = it }

    // Write 10 entries: framesWritten advances by 960 each, writeTimeNs by 20ms each.
    repeat(10) { i ->
        est.recordWrite(framesWritten = (i + 1) * 960L, writeTimeNs = i * 20_000_000L)
    }

    // DAC reports it's at frame 5760 (== 6 writes' worth). Look up should find
    // the write at framesWritten=5760 with writeTimeNs=5*20_000_000=100_000_000.
    // If dacTimeNs is 180_000_000, latency = 80_000_000 ns = 80ms = 80000us.
    // We can't observe the sample directly yet (no accumulator test), but we
    // can assert no result has been emitted (1 sample is not enough to converge).
    est.recordDacTimestamp(framePosition = 5760L, dacTimeNs = 180_000_000L)
    assertEquals(null, captured)
    assertEquals(OutputLatencyEstimator.Status.Measuring, est.status)
}

@Test
fun `recordDacTimestamp drops samples when frame is before ring buffer start`() {
    val est = OutputLatencyEstimator(nowNs = { 0L }, ringCapacity = 4)
    est.start {}

    // Fill then overflow the ring so frame 960 is evicted.
    repeat(6) { i ->
        est.recordWrite(framesWritten = (i + 1) * 960L, writeTimeNs = i * 20_000_000L)
    }
    // Ring now contains frames 2880, 3840, 4800, 5760 (oldest to newest).

    // Asking about frame 960 should be dropped (no throw, no crash, no sample).
    est.recordDacTimestamp(framePosition = 960L, dacTimeNs = 100_000_000L)
    // Implicit assertion: no exception thrown.
}
```

- [ ] **Step 3.2: Run the tests to verify they fail**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.latency.OutputLatencyEstimatorTest"
```
Expected: FAIL on the new tests with NotImplementedError.

- [ ] **Step 3.3: Implement recordDacTimestamp + sample accumulation**

In `OutputLatencyEstimator.kt`, add fields after `ring`:

```kotlin
    private val samples = ArrayDeque<Long>()  // latency values in nanoseconds
    private var rejectedSamples = 0
```

Replace the `recordDacTimestamp` stub with:

```kotlin
    fun recordDacTimestamp(framePosition: Long, dacTimeNs: Long) {
        synchronized(lock) {
            if (status != Status.Measuring) return
            val writeTimeNs = lookupWriteTime(framePosition) ?: run {
                rejectedSamples++
                return
            }
            val latencyNs = dacTimeNs - writeTimeNs
            if (latencyNs <= 0 || latencyNs > MAX_REASONABLE_LATENCY_NS) {
                rejectedSamples++
                return
            }
            samples.addLast(latencyNs)
            // Convergence trigger lands in Task 4.
        }
    }

    /**
     * Linear scan for the write entry whose `framesWritten` is >= the query
     * frame — i.e., the earliest write that contains the requested frame.
     * Returns its `writeTimeNs`, or null if the frame is older than the
     * oldest entry in the ring.
     */
    private fun lookupWriteTime(framePosition: Long): Long? {
        for (entry in ring) {
            if (entry.framesWritten >= framePosition) return entry.writeTimeNs
        }
        return null
    }
```

Add to the companion object:

```kotlin
        // Reject latency samples outside [0, 1_000 ms]. Negative = measurement
        // bug, > 1 s = pathological device or Bluetooth routing. Don't poison
        // the mean with these.
        const val MAX_REASONABLE_LATENCY_NS = 1_000_000_000L  // 1 second
```

- [ ] **Step 3.4: Run the tests to verify they pass**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.latency.OutputLatencyEstimatorTest"
```
Expected: PASS (all four tests so far).

- [ ] **Step 3.5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimator.kt android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimatorTest.kt
git commit -m "feat(latency): recordDacTimestamp sample evaluation + frame lookup"
```

---

## Task 4: Convergence on 20 samples

**Files:**
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimator.kt`
- Modify: `android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimatorTest.kt`

- [ ] **Step 4.1: Write the failing test**

Add to `OutputLatencyEstimatorTest.kt`:

```kotlin
@Test
fun `converges on exactly 20 accepted samples with arithmetic mean`() {
    var captured: OutputLatencyEstimator.Result? = null
    val est = OutputLatencyEstimator(nowNs = { 0L })
    est.start { captured = it }

    // Seed the ring with 25 writes so every DAC frame has a lookup.
    repeat(25) { i ->
        est.recordWrite(framesWritten = (i + 1) * 960L, writeTimeNs = i * 20_000_000L)
    }

    // Emit 20 DAC samples. For sample i, DAC reports frame (i+1)*960, DAC time
    // is (write time for that frame) + latency_ns. Vary latency so the mean
    // is testable: use 80ms for all 20 → mean is 80ms → 80000us.
    val latencyNs = 80_000_000L
    for (i in 0 until 20) {
        val frame = (i + 1) * 960L
        val writeTimeNs = i * 20_000_000L
        est.recordDacTimestamp(framePosition = frame, dacTimeNs = writeTimeNs + latencyNs)
    }

    val result = captured
    assertNotNull("should have converged after 20 samples", result)
    result as OutputLatencyEstimator.Result.Converged
    assertEquals(80_000L, result.latencyMicros)
    assertEquals(20, result.sampleCount)
    assertEquals(OutputLatencyEstimator.Status.Converged, est.status)
}
```

Also add at top of test file (imports):

```kotlin
import org.junit.Assert.assertNotNull
```

- [ ] **Step 4.2: Run the test to verify it fails**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.latency.OutputLatencyEstimatorTest.converges on exactly 20 accepted samples with arithmetic mean"
```
Expected: FAIL — `captured` is still null because we never fire the callback.

- [ ] **Step 4.3: Implement convergence**

In `OutputLatencyEstimator.kt`, add to the companion object:

```kotlin
        const val CONVERGENCE_SAMPLE_COUNT = 20
```

In `recordDacTimestamp`, replace the comment `// Convergence trigger lands in Task 4.` with:

```kotlin
            if (samples.size >= CONVERGENCE_SAMPLE_COUNT) {
                val sum = samples.sum()
                val meanNs = sum / samples.size
                val result = Result.Converged(
                    latencyMicros = meanNs / 1_000,
                    sampleCount = samples.size,
                )
                status = Status.Converged
                val cb = onResult
                onResult = null
                cb?.invoke(result)
            }
```

- [ ] **Step 4.4: Run the test to verify it passes**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.latency.OutputLatencyEstimatorTest"
```
Expected: PASS (all five tests).

- [ ] **Step 4.5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimator.kt android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimatorTest.kt
git commit -m "feat(latency): convergence on 20-sample mean"
```

---

## Task 5: Sample rejection — negative, over-cap, dropped lookups don't count

**Files:**
- Modify: `android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimatorTest.kt`

- [ ] **Step 5.1: Write the failing tests**

Add to `OutputLatencyEstimatorTest.kt`:

```kotlin
@Test
fun `rejects negative latency and still converges on 20 real samples`() {
    var captured: OutputLatencyEstimator.Result? = null
    val est = OutputLatencyEstimator(nowNs = { 0L })
    est.start { captured = it }

    // 30 writes so every frame resolves.
    repeat(30) { i ->
        est.recordWrite(framesWritten = (i + 1) * 960L, writeTimeNs = i * 20_000_000L)
    }

    // First 5 "samples" have negative latency (DAC time before write time) --
    // must be rejected and NOT count toward the 20.
    for (i in 0 until 5) {
        est.recordDacTimestamp(framePosition = (i + 1) * 960L, dacTimeNs = i * 20_000_000L - 1_000L)
    }
    // Then 20 clean samples at 50ms.
    for (i in 0 until 20) {
        val frame = (i + 6) * 960L
        val writeTimeNs = (i + 5) * 20_000_000L
        est.recordDacTimestamp(frame, writeTimeNs + 50_000_000L)
    }

    val result = captured as? OutputLatencyEstimator.Result.Converged
    assertNotNull(result)
    assertEquals(50_000L, result!!.latencyMicros)
    assertEquals(20, result.sampleCount)
}

@Test
fun `rejects latency above 1000ms cap`() {
    var captured: OutputLatencyEstimator.Result? = null
    val est = OutputLatencyEstimator(nowNs = { 0L })
    est.start { captured = it }

    repeat(30) { i ->
        est.recordWrite(framesWritten = (i + 1) * 960L, writeTimeNs = i * 20_000_000L)
    }

    // 10 "samples" with 2-second latency -- rejected.
    for (i in 0 until 10) {
        est.recordDacTimestamp(
            framePosition = (i + 1) * 960L,
            dacTimeNs = i * 20_000_000L + 2_000_000_000L,
        )
    }
    // No result yet.
    assertEquals(null, captured)
    assertEquals(OutputLatencyEstimator.Status.Measuring, est.status)
}
```

- [ ] **Step 5.2: Run the tests to verify they pass (rejection logic already exists)**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.latency.OutputLatencyEstimatorTest"
```
Expected: PASS. The rejection logic was added in Task 3; these tests just lock that behavior down.

- [ ] **Step 5.3: Commit**

```bash
git add android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimatorTest.kt
git commit -m "test(latency): pin sample-rejection behavior"
```

---

## Task 6: Timeout mechanism

**Files:**
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimator.kt`
- Modify: `android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimatorTest.kt`

- [ ] **Step 6.1: Write the failing test**

Add to `OutputLatencyEstimatorTest.kt`:

```kotlin
@Test
fun `times out after 2 seconds with partial sample count`() {
    var now = 0L
    var captured: OutputLatencyEstimator.Result? = null
    val est = OutputLatencyEstimator(nowNs = { now })
    est.start { captured = it }

    // Feed 10 clean samples over the first 200 ms.
    repeat(15) { i ->
        est.recordWrite(framesWritten = (i + 1) * 960L, writeTimeNs = i * 20_000_000L)
    }
    for (i in 0 until 10) {
        est.recordDacTimestamp((i + 1) * 960L, i * 20_000_000L + 50_000_000L)
    }
    assertEquals(null, captured)

    // Advance the clock 2.1 s forward and tick.
    now = 2_100_000_000L
    est.tick()

    val result = captured as? OutputLatencyEstimator.Result.TimedOut
    assertNotNull("should have timed out", result)
    assertEquals(10, result!!.sampleCount)
    assertEquals(OutputLatencyEstimator.Status.TimedOut, est.status)
}
```

- [ ] **Step 6.2: Run the test to verify it fails**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.latency.OutputLatencyEstimatorTest.times out after 2 seconds with partial sample count"
```
Expected: FAIL — `est.tick()` does not exist.

- [ ] **Step 6.3: Implement timeout + tick()**

In `OutputLatencyEstimator.kt`, add to the companion object:

```kotlin
        const val TIMEOUT_NS = 2_000_000_000L  // 2 seconds
```

Add field next to `rejectedSamples`:

```kotlin
    private var startNs: Long = 0L
```

In `start()`, set the start time right before `status = Status.Measuring`:

```kotlin
        startNs = nowNs()
```

Add a new method `tick()` that callers invoke periodically (e.g., from the audio thread's existing polling loop):

```kotlin
    /**
     * Check the timeout clock. Call this periodically from any thread that
     * also calls [recordWrite] / [recordDacTimestamp] (so the same lock
     * serializes state). When the timeout has elapsed and the session has
     * not yet converged, fires [Result.TimedOut].
     */
    fun tick() {
        synchronized(lock) {
            if (status != Status.Measuring) return
            if (nowNs() - startNs < TIMEOUT_NS) return
            val result = Result.TimedOut(sampleCount = samples.size)
            status = Status.TimedOut
            val cb = onResult
            onResult = null
            cb?.invoke(result)
        }
    }
```

- [ ] **Step 6.4: Run the test to verify it passes**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.latency.OutputLatencyEstimatorTest"
```
Expected: PASS (all tests).

- [ ] **Step 6.5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimator.kt android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimatorTest.kt
git commit -m "feat(latency): 2s timeout via tick()"
```

---

## Task 7: Cancel during active session

**Files:**
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimator.kt`
- Modify: `android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimatorTest.kt`

- [ ] **Step 7.1: Write the failing test**

```kotlin
@Test
fun `cancel() stops firing callbacks even if convergence would have happened`() {
    var callbackCount = 0
    val est = OutputLatencyEstimator(nowNs = { 0L })
    est.start { callbackCount++ }

    est.cancel()
    assertEquals(OutputLatencyEstimator.Status.Cancelled, est.status)

    // Feed enough samples that it would normally converge.
    repeat(30) { i ->
        est.recordWrite(framesWritten = (i + 1) * 960L, writeTimeNs = i * 20_000_000L)
    }
    for (i in 0 until 20) {
        val frame = (i + 1) * 960L
        est.recordDacTimestamp(frame, i * 20_000_000L + 50_000_000L)
    }

    assertEquals(0, callbackCount)
    assertEquals(OutputLatencyEstimator.Status.Cancelled, est.status)
}
```

- [ ] **Step 7.2: Run the test to verify it fails**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.latency.OutputLatencyEstimatorTest.cancel\$\$ stops firing callbacks"
```
Expected: FAIL with NotImplementedError on `cancel()`.

- [ ] **Step 7.3: Implement cancel()**

Replace the `cancel()` stub:

```kotlin
    fun cancel() {
        synchronized(lock) {
            if (status != Status.Measuring) return
            status = Status.Cancelled
            onResult = null
        }
    }
```

- [ ] **Step 7.4: Run the test to verify it passes**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.latency.OutputLatencyEstimatorTest"
```
Expected: PASS.

- [ ] **Step 7.5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimator.kt android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/latency/OutputLatencyEstimatorTest.kt
git commit -m "feat(latency): cancel() suppresses late callbacks"
```

---

## Task 8: Split staticDelayMicros in SendspinTimeFilter

**Files:**
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/SendspinTimeFilter.kt`

**Context:** Today `staticDelayMicros` at `SendspinTimeFilter.kt:306` is a single `@Volatile Long` updated by three separate call paths (user slider, server sync_offset, settings initial apply). This task splits it into two independent fields with explicit per-source setters. Existing callers get compatibility setters in Task 10; this task only changes `SendspinTimeFilter` itself.

- [ ] **Step 8.1: Create SendspinTimeFilterTest if it does not exist**

Check whether the test file exists:

```bash
ls android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/SendspinTimeFilterTest.kt 2>/dev/null && echo EXISTS || echo MISSING
```

If MISSING, create the file:

```kotlin
// android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/SendspinTimeFilterTest.kt
package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.latency.StaticDelaySource
import org.junit.Assert.assertEquals
import org.junit.Test

class SendspinTimeFilterTest {
    // Tests added by the static-delay-auto-measurement plan.
}
```

- [ ] **Step 8.2: Write the failing tests for field split**

Add to `SendspinTimeFilterTest.kt`:

```kotlin
@Test
fun `staticDelayMs returns sum of auto-measured and user sync offset`() {
    val f = SendspinTimeFilter()
    f.setUserSyncOffsetMs(30.0)
    f.setAutoMeasuredDelayMicros(50_000L, StaticDelaySource.AUTO)
    assertEquals(80.0, f.staticDelayMs, 0.0001)
}

@Test
fun `user and auto-measured writes do not clobber each other`() {
    val f = SendspinTimeFilter()
    f.setAutoMeasuredDelayMicros(100_000L, StaticDelaySource.AUTO)
    f.setUserSyncOffsetMs(25.0)
    assertEquals(125.0, f.staticDelayMs, 0.0001)
    assertEquals(StaticDelaySource.USER, f.staticDelaySource)  // Most recent writer

    f.setAutoMeasuredDelayMicros(0L, StaticDelaySource.NONE)
    assertEquals(25.0, f.staticDelayMs, 0.0001)
}

@Test
fun `server sync_offset writes route to user field with SERVER source`() {
    val f = SendspinTimeFilter()
    f.setServerSyncOffsetMs(-40.0)
    assertEquals(-40.0, f.staticDelayMs, 0.0001)
    assertEquals(StaticDelaySource.SERVER, f.staticDelaySource)
}
```

- [ ] **Step 8.3: Run the tests to verify they fail**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.SendspinTimeFilterTest"
```
Expected: FAIL (methods not defined).

- [ ] **Step 8.4: Refactor SendspinTimeFilter fields**

In `SendspinTimeFilter.kt`:

Add import at the top:

```kotlin
import com.sendspindroid.sendspin.latency.StaticDelaySource
```

Replace the block at lines 303-306 (the `@Volatile private var staticDelayMicros: Long = 0` and its comment):

```kotlin
    // Static delay = auto-measured output latency + user sync offset.
    // Each source is tracked separately so auto-measurement and user
    // corrections don't clobber each other. [staticDelayMs] returns the sum.
    // @Volatile fields: read by audio thread (serverToClient), written from
    // UI/main or estimator threads.
    @Volatile private var autoMeasuredDelayMicros: Long = 0
    @Volatile private var userSyncOffsetMicros: Long = 0
    @Volatile var staticDelaySource: StaticDelaySource = StaticDelaySource.NONE
        private set
```

Replace the existing `staticDelayMs` get/set property (lines ~397-401):

```kotlin
    /**
     * Effective static delay in milliseconds. Sum of the auto-measured
     * hardware latency and the user's sync-offset correction. Both
     * components may be written independently by their respective setters.
     *
     * Positive = delay playback (plays later), Negative = advance (plays earlier).
     */
    val staticDelayMs: Double
        get() = (autoMeasuredDelayMicros + userSyncOffsetMicros) / 1000.0

    /**
     * Raw auto-measured component (microseconds).
     */
    val autoMeasuredDelayMs: Double
        get() = autoMeasuredDelayMicros / 1000.0

    /**
     * Raw user sync-offset component (milliseconds).
     */
    val userSyncOffsetMs: Double
        get() = userSyncOffsetMicros / 1000.0

    /**
     * Write the auto-measured hardware output latency. Called by
     * [OutputLatencyEstimator] when measurement converges (source=AUTO)
     * or times out (source=NONE).
     */
    fun setAutoMeasuredDelayMicros(micros: Long, source: StaticDelaySource) {
        autoMeasuredDelayMicros = micros
        staticDelaySource = source
    }

    /**
     * Write the user's manual sync-offset correction (milliseconds).
     * Called by the settings slider's broadcast path.
     */
    fun setUserSyncOffsetMs(ms: Double) {
        userSyncOffsetMicros = (ms * 1000).toLong()
        staticDelaySource = StaticDelaySource.USER
    }

    /**
     * Write a server-pushed sync-offset (from `client/sync_offset`).
     * Goes into the same field as the user slider because both are
     * semantically "corrections on top of the measured hardware latency".
     */
    fun setServerSyncOffsetMs(ms: Double) {
        userSyncOffsetMicros = (ms * 1000).toLong()
        staticDelaySource = StaticDelaySource.SERVER
    }
```

- [ ] **Step 8.5: Run the tests to verify they pass**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.SendspinTimeFilterTest"
```
Expected: PASS.

- [ ] **Step 8.6: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/SendspinTimeFilter.kt android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/SendspinTimeFilterTest.kt
git commit -m "refactor(timefilter): split staticDelayMicros into auto + user fields"
```

---

## Task 9: Fix L-3 torn-read on Kalman offset field

**Files:**
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/SendspinTimeFilter.kt`

**Context:** `@Volatile private var offset: Double` at line 274 is theoretically torn-readable on 32-bit JVMs. Switch to `AtomicLong` storage with `Double.toRawBits`/`Double.fromBits` bit-cast. The Kalman update paths already acquire `lock` for the covariance matrix; readers access `offset` unlocked on the audio thread.

- [ ] **Step 9.1: Write the failing torn-read test**

Add to `SendspinTimeFilterTest.kt`:

```kotlin
@Test
fun `concurrent writer-reader stress on offset does not tear`() {
    val f = SendspinTimeFilter()
    val writer = Thread {
        for (i in 0 until 10_000) {
            f.addMeasurement(serverTimeMicros = i.toLong() * 1_000L, clientTimeMicros = i.toLong() * 1_000L, rttMicros = 1_000L)
        }
    }
    val reader = Thread {
        for (i in 0 until 10_000) {
            val now = i.toLong() * 1_000L
            val v = f.serverToClient(now)
            // A torn read would yield NaN or an impossible magnitude.
            // Accept any finite long as non-torn.
            require(v in Long.MIN_VALUE..Long.MAX_VALUE)
        }
    }
    writer.start()
    reader.start()
    writer.join()
    reader.join()
}
```

- [ ] **Step 9.2: Run the test to verify it passes (torn reads are nondeterministic)**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.SendspinTimeFilterTest.concurrent writer-reader"
```
Expected: PASS on modern ARM64 (the test exists to lock in regression coverage after the fix — it is unlikely to fail before the fix on 64-bit hardware, but would catch a regression on 32-bit). The test's primary value is protecting the AtomicLong representation across refactors.

- [ ] **Step 9.3: Replace the `@Volatile Double` with atomic Long bit-cast**

In `SendspinTimeFilter.kt`:

Add import:

```kotlin
import java.util.concurrent.atomic.AtomicLong
```

Replace line 274 (`@Volatile private var offset: Double = 0.0`):

```kotlin
    // offset is stored as AtomicLong (bit-cast from Double via toRawBits /
    // fromBits) so reads on 32-bit JVMs are atomic. The covariance matrix
    // (p00, p01, p10, p11) is still guarded by [lock] on writes. Readers
    // on the audio thread (serverToClient, clientToServer) read offset
    // lock-free via [offsetDouble].
    private val offsetBits = AtomicLong(0L)

    private var offset: Double
        get() = Double.fromBits(offsetBits.get())
        set(value) { offsetBits.set(value.toRawBits()) }
```

- [ ] **Step 9.4: Run all existing filter tests to verify no regression**

```bash
./gradlew :shared:testAndroidHostTest --tests "com.sendspindroid.sendspin.*"
```
Expected: PASS (including the pre-existing filter tests and the new torn-read test).

- [ ] **Step 9.5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/SendspinTimeFilter.kt android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/SendspinTimeFilterTest.kt
git commit -m "fix(timefilter): atomic Long storage for Kalman offset (L-3)"
```

---

## Task 10: Migrate existing staticDelayMs writers to explicit setters

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/protocol/SendSpinProtocolHandler.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

**Context:** The old `staticDelayMs` setter was removed in Task 8. Four call sites wrote to it:
- `SendSpinProtocolHandler.kt:468` — server `client/sync_offset` handler
- `PlaybackService.kt:231` — `syncOffsetReceiver` broadcast handler
- `PlaybackService.kt:2936` — `applySyncOffsetFromSettings`
- `PlaybackService.kt:2949` — `updateSyncOffset`

All four need to use the new typed setters.

- [ ] **Step 10.1: Update SendSpinProtocolHandler**

In `SendSpinProtocolHandler.kt`, at the line that currently reads `getTimeFilter().staticDelayMs = clampedOffset` (around line 468):

```kotlin
        getTimeFilter().setServerSyncOffsetMs(clampedOffset)
```

- [ ] **Step 10.2: Update PlaybackService broadcast receiver**

In `PlaybackService.kt`, at line ~231:

Before:
```kotlin
                timeFilter.staticDelayMs = offsetMs.toDouble()
```
After:
```kotlin
                timeFilter.setUserSyncOffsetMs(offsetMs.toDouble())
```

- [ ] **Step 10.3: Update applySyncOffsetFromSettings (line ~2936)**

Before:
```kotlin
                timeFilter.staticDelayMs = offsetMs.toDouble()
```
After:
```kotlin
                timeFilter.setUserSyncOffsetMs(offsetMs.toDouble())
```

- [ ] **Step 10.4: Update updateSyncOffset (line ~2949)**

Before:
```kotlin
            timeFilter.staticDelayMs = offsetMs.toDouble()
```
After:
```kotlin
            timeFilter.setUserSyncOffsetMs(offsetMs.toDouble())
```

- [ ] **Step 10.5: Build and run protocol + playback tests**

```bash
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.protocol.*" --tests "com.sendspindroid.playback.*"
```
Expected: PASS. The behavior is unchanged (all four writers still ultimately populate `userSyncOffsetMicros`); this just uses the new typed API.

- [ ] **Step 10.6: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/protocol/SendSpinProtocolHandler.kt android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "refactor: route existing static-delay writers to explicit setters"
```

---

## Task 11: Add sendClientStateSnapshot on protocol handler + client

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/protocol/SendSpinProtocolHandler.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt`

- [ ] **Step 11.1: Expose sendPlayerStateUpdate as sendClientStateSnapshot**

In `SendSpinProtocolHandler.kt`, right after the existing `protected fun sendPlayerStateUpdate()` block (around line 206):

```kotlin
    /**
     * Public hook for code outside the protocol handler (e.g.
     * [OutputLatencyEstimator] via [SyncAudioPlayer]) to push a fresh
     * `client/state` to the server, for example after auto-measured
     * `static_delay_ms` converges.
     */
    fun sendClientStateSnapshot() {
        if (!handshakeComplete) return
        sendPlayerStateUpdate()
    }
```

- [ ] **Step 11.2: Verify SendSpinClient exposes it (inherited)**

Because `SendSpinClient` extends `SendSpinProtocolHandler`, `sendClientStateSnapshot()` is already callable on a `SendSpinClient` instance. No change required in `SendSpinClient.kt`.

- [ ] **Step 11.3: Build**

```bash
./gradlew assembleDebug
```
Expected: PASS.

- [ ] **Step 11.4: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/protocol/SendSpinProtocolHandler.kt
git commit -m "feat(protocol): sendClientStateSnapshot public hook"
```

---

## Task 12: Wire OutputLatencyEstimator into SyncAudioPlayer

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt`

**Context:** `SyncAudioPlayer` owns the `AudioTrack` lifetime and already performs write + `getTimestamp()` operations. The estimator hooks into both. The result callback writes to the `SendspinTimeFilter` and requests a `client/state` push via a lambda injected at construction (to avoid `SyncAudioPlayer` depending directly on `SendSpinClient`).

- [ ] **Step 12.1: Add an injection point for the "request client/state push" callback**

In `SyncAudioPlayer.kt`, find the primary constructor and add a new parameter:

Before:
```kotlin
class SyncAudioPlayer(
    private val timeFilter: SendspinTimeFilter,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitDepth: Int,
    private val maxQueueSamples: Long = 0L,
) {
```

After:
```kotlin
class SyncAudioPlayer(
    private val timeFilter: SendspinTimeFilter,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitDepth: Int,
    private val maxQueueSamples: Long = 0L,
    private val requestClientStateSnapshot: () -> Unit = {},
) {
```

- [ ] **Step 12.2: Update PlaybackService construction site**

In `PlaybackService.kt`, find the `SyncAudioPlayer(...)` construction (around line ~1315 from earlier exploration) and add the callback:

```kotlin
                    syncAudioPlayer = SyncAudioPlayer(
                        timeFilter = timeFilter,
                        sampleRate = sampleRate,
                        channels = channels,
                        bitDepth = bitDepth,
                        maxQueueSamples = maxSamples,
                        requestClientStateSnapshot = {
                            sendSpinClient?.sendClientStateSnapshot()
                        },
                    ).apply {
```

- [ ] **Step 12.3: Add estimator field + start it when a new AudioTrack is created**

Near the `audioTrack` field declaration in `SyncAudioPlayer.kt`, add:

```kotlin
    private val latencyEstimator = com.sendspindroid.sendspin.latency.OutputLatencyEstimator(
        nowNs = { System.nanoTime() },
    )
```

In the method that creates the `AudioTrack` (look for `AudioTrack.Builder()` usage around line ~524), immediately after the track is successfully built, start the estimator:

```kotlin
        latencyEstimator.start { result ->
            when (result) {
                is com.sendspindroid.sendspin.latency.OutputLatencyEstimator.Result.Converged -> {
                    timeFilter.setAutoMeasuredDelayMicros(
                        result.latencyMicros,
                        com.sendspindroid.sendspin.latency.StaticDelaySource.AUTO,
                    )
                    AppLog.Audio.i("[delay-cal] converged: ${result.latencyMicros}us from ${result.sampleCount} samples")
                }
                is com.sendspindroid.sendspin.latency.OutputLatencyEstimator.Result.TimedOut -> {
                    timeFilter.setAutoMeasuredDelayMicros(
                        0L,
                        // Don't overwrite a user slider with NONE if one is in place;
                        // the filter's staticDelaySource will reflect USER or NONE
                        // based on whether a user write happened after this one.
                        com.sendspindroid.sendspin.latency.StaticDelaySource.NONE,
                    )
                    AppLog.Audio.w("[delay-cal] timed out with ${result.sampleCount} samples; falling back to 0")
                }
            }
            requestClientStateSnapshot()
        }
```

- [ ] **Step 12.4: Locate the write loop and cumulative-frames counter**

Find the `AudioTrack.write` call site and the field tracking cumulative frames. These names depend on the current file content; locate them with:

```bash
grep -n "audioTrack.write\|framesWritten\|totalFrames" android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt
```

Note the variable name used for cumulative frames written (examples from past versions: `totalFramesWritten`, `framesWritten`). Call this variable `F_CUMULATIVE` for the rest of this task.

- [ ] **Step 12.5: Feed recordWrite on each AudioTrack.write()**

Immediately before the `audioTrack.write(...)` call, capture `writeTimeNs = System.nanoTime()`. Immediately after, once `F_CUMULATIVE` has been updated with the just-written frames, call:

```kotlin
        latencyEstimator.recordWrite(F_CUMULATIVE, writeTimeNs)
```

The exact insertion point: the write-time capture goes right before `val written = audioTrack.write(...)`, and the `recordWrite` call goes in the block that handles a successful write (the same place where `F_CUMULATIVE` is already updated). Do not add a new counter — reuse what's already there. If no such counter exists, that is a red flag — stop and ask, because frame-position lookup in the ring buffer requires a cumulative count that matches what `AudioTrack.getTimestamp()` returns.

- [ ] **Step 12.6: Feed recordDacTimestamp on each successful getTimestamp()**

Locate the `AudioTimestamp` poll site:

```bash
grep -n "AudioTimestamp\|getTimestamp(" android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt
```

After a successful `audioTrack.getTimestamp(ts)` call (the overload returns `Boolean`), add:

```kotlin
        if (success) {
            latencyEstimator.recordDacTimestamp(ts.framePosition, ts.nanoTime)
        }
        latencyEstimator.tick()
```

The `tick()` call runs on every poll regardless of success, so the timeout clock advances even if `getTimestamp` is failing persistently.

- [ ] **Step 12.7: Cancel the estimator when AudioTrack is released**

Find the `audioTrack?.release()` call in `release()` (around line 980). Immediately before it:

```kotlin
        latencyEstimator.cancel()
```

- [ ] **Step 12.8: Build**

```bash
./gradlew assembleDebug
```
Expected: PASS.

- [ ] **Step 12.9: Run the existing SyncAudioPlayer tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.SyncAudioPlayerTest" --tests "com.sendspindroid.playback.*"
```
Expected: PASS. The new estimator is default-off when not started, and the construction path now starts it — but on the unit test bench there's no real `AudioTrack`, so the tests must still pass. If they fail, the construction site wiring was too aggressive — the fix is to only start the estimator when a non-null `AudioTrack` was actually built (not on mock/failure paths).

- [ ] **Step 12.10: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "feat(audio): wire OutputLatencyEstimator into SyncAudioPlayer"
```

---

## Task 13: Gate WAITING_FOR_START → PLAYING on measurement complete

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt`

**Context:** `handleStartGatingDacAware` at `SyncAudioPlayer.kt:1458` returns `true` to mean "keep waiting." The new gate clause: if measurement is still in `Measuring` status, return `true` regardless of DAC alignment.

- [ ] **Step 13.1: Add measurement-complete check at the top of handleStartGatingDacAware**

In `SyncAudioPlayer.kt`, at the very start of `handleStartGatingDacAware` (around line 1459, right after the opening brace):

```kotlin
    private fun handleStartGatingDacAware(track: AudioTrack): Boolean {
        // Measurement-complete clause: don't transition to PLAYING until
        // the latency estimator has converged or timed out. If we don't
        // wait here, an unusually-early server-scheduled start could make
        // us enter PLAYING with staticDelay=0, then change it mid-stream
        // once measurement finishes -- causing a one-time sync jump / click.
        if (latencyEstimator.status == com.sendspindroid.sendspin.latency.OutputLatencyEstimator.Status.Measuring) {
            return true  // keep waiting
        }

        val nowMicros = System.nanoTime() / 1000
        // ... (rest of existing body unchanged)
```

- [ ] **Step 13.2: Build**

```bash
./gradlew assembleDebug
```
Expected: PASS.

- [ ] **Step 13.3: Run playback tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sendspindroid.sendspin.*" --tests "com.sendspindroid.playback.*"
```
Expected: PASS. Existing tests may not exercise the gate directly; they should still pass because the estimator's default state is `Idle` (gate does not block) and if the test constructs a `SyncAudioPlayer` and starts it, the estimator goes to `Measuring` — if a test then simulates DAC alignment without providing DAC timestamps, the gate now blocks it. That's a **legitimate test failure** if it happens, and the fix is to update the test to either call `estimator.tick()` with an advanced clock to force timeout or to inject a different `requestClientStateSnapshot` callback. Investigate each failing test individually.

- [ ] **Step 13.4: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/sendspin/SyncAudioPlayer.kt
git commit -m "feat(audio): hold WAITING_FOR_START until measurement completes"
```

---

## Task 14: Stats bundle keys in PlaybackService

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

**Context:** The stats bundle at `PlaybackService.kt:2898` already exposes `static_delay_ms`. Adding `auto_measured_delay_ms`, `user_sync_offset_ms`, and `static_delay_source` lets the stats UI show the breakdown without new UI.

- [ ] **Step 14.1: Add the three new keys**

In `PlaybackService.kt`, immediately after the existing `bundle.putDouble("static_delay_ms", timeFilter.staticDelayMs)` line (around line 2898):

```kotlin
            bundle.putDouble("auto_measured_delay_ms", timeFilter.autoMeasuredDelayMs)
            bundle.putDouble("user_sync_offset_ms", timeFilter.userSyncOffsetMs)
            bundle.putString("static_delay_source", timeFilter.staticDelaySource.name)
```

- [ ] **Step 14.2: Build**

```bash
./gradlew assembleDebug
```
Expected: PASS.

- [ ] **Step 14.3: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt
git commit -m "feat(stats): expose auto-measured and user-sync-offset breakdown"
```

---

## Task 15: Final build, full test run, open PR

**Files:**
- None (verification only)

- [ ] **Step 15.1: Full build**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 15.2: Full unit-test run, both modules**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :shared:testAndroidHostTest
```
Expected: PASS.

- [ ] **Step 15.3: Review the combined diff**

```bash
git log --oneline origin/main..HEAD
git diff --stat origin/main..HEAD
```

Expected: ~11 commits, modifications to `SendspinTimeFilter`, two new files under `sendspin/latency/`, and the wiring across `SyncAudioPlayer`, `PlaybackService`, `SendSpinProtocolHandler`. No changes to `SendSpinClient.kt` (it inherits `sendClientStateSnapshot` for free).

- [ ] **Step 15.4: Push and open PR**

```bash
git push -u origin task/static-delay-auto-measurement
gh pr create --base main --title "feat: auto-measure static_delay_ms for multi-room sync (H-2 + L-3)" --body "$(cat <<'EOF'
## Summary

Adds automatic measurement of device output latency, populating `static_delay_ms` without manual per-device calibration. Addresses audit findings H-2 (primary) and L-3 (folded in). This is Group 5 of the architecture-audit rollup; Groups 1-4 and 6 have landed (#139, #140, #141, #142, #143).

Full design at `docs/superpowers/specs/2026-04-21-static-delay-auto-measurement-design.md`.
Implementation plan at `docs/superpowers/plans/2026-04-21-static-delay-auto-measurement.md`.

## Changes

- New `OutputLatencyEstimator` (pure Kotlin, shared module) with ring-buffered sampling, 20-sample fixed-window mean, 2 s timeout, drop-sample rejection (`<0 ms` and `>1000 ms`).
- `SendspinTimeFilter.staticDelayMicros` split into two fields: `autoMeasuredDelayMicros` (written by estimator) and `userSyncOffsetMicros` (written by slider / server). Existing `staticDelayMs` getter returns the sum. Explicit typed setters replace the old catch-all setter.
- L-3 fix: Kalman `offset` field migrated to `AtomicLong` + `Double.toRawBits/fromBits` to eliminate torn-read risk on 32-bit JVMs.
- `SyncAudioPlayer` owns the estimator lifetime, feeds it from existing write and `getTimestamp()` paths, and the `WAITING_FOR_START -> PLAYING` gate now holds until measurement completes.
- New `SendSpinProtocolHandler.sendClientStateSnapshot()` public wrapper so the estimator can push an updated `client/state` before first audible chunk.
- Stats bundle gains `auto_measured_delay_ms`, `user_sync_offset_ms`, `static_delay_source`.

## Verified

- [x] `./gradlew assembleDebug`
- [x] `:app:testDebugUnitTest` (all existing tests + new wiring checks)
- [x] `:shared:testAndroidHostTest` (full `OutputLatencyEstimatorTest`, new `SendspinTimeFilterTest` additions)

## Test plan

- [ ] CI build + tests pass
- [ ] On-device: connect fresh, check logcat `[delay-cal]` for convergence log; check stats sheet shows non-zero `auto_measured_delay_ms` and `static_delay_source: AUTO`
- [ ] On-device: user slider still works independently; stats show `user_sync_offset_ms` changing, source flipping to `USER`
- [ ] Multi-room (if available): drift between two devices is noticeably better than pre-PR baseline
EOF
)"
```

- [ ] **Step 15.5: Update tracking table in conversation**

Report the PR URL to the user and mark Group 5 complete in the audit rollup.

---

## Deferred / out of scope

- **M-4** and **L-5** are tracked as deferred tasks (#15 and #16 in the session task list); not part of this PR.
- **H-1 code side** (AAudio/Oboe migration) remains a future design spec of its own.
- Multi-device automated sync test is explicitly excluded — validation is manual per the spec.

## Risks

- **Test-construction of `SyncAudioPlayer` activates the estimator.** Existing tests may exercise gate behavior in unintended ways. Task 12.8 / 13.3 include checkpoints for investigating.
- **Estimator starts before the AudioTrack is actually playing silence.** If the first `getTimestamp()` returns before any `recordWrite` has populated the ring buffer, samples are dropped harmlessly — but persistent `getTimestamp()` success with empty ring would lead to timeout. Not observed in practice because the write loop feeds the ring before the first poll.
- **Late result after AudioTrack release.** `cancel()` in `release()` prevents this; Task 7 tested it.
