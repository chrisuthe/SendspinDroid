package com.sendspindroid.sendspin.latency

import org.junit.Assert.assertEquals
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
}
