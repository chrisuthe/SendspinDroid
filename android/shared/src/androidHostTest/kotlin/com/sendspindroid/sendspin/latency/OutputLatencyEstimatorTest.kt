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
