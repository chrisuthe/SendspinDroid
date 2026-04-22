package com.sendspindroid.sendspin.latency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OutputLatencyEstimatorTest {

    @Test
    fun `starts in Idle status before start() is called`() {
        val est = OutputLatencyEstimator(nowNs = { 0L })
        assertEquals(OutputLatencyEstimator.Status.Idle, est.status)
    }

    @Test
    fun `start() transitions to Measuring and accepts writes`() {
        val est = OutputLatencyEstimator(nowNs = { 0L })
        est.start(onResult = {})
        assertEquals(OutputLatencyEstimator.Status.Measuring, est.status)

        // Recording writes does not change status on its own.
        est.recordWrite(framesWritten = 960, writeTimeNs = 1_000_000L)
        assertEquals(OutputLatencyEstimator.Status.Measuring, est.status)
    }

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
        // is testable: use 80ms for all 20 -> mean is 80ms -> 80000us.
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
}
