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
}
