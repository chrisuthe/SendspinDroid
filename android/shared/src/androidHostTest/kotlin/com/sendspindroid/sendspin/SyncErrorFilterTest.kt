package com.sendspindroid.sendspin

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SyncErrorFilterTest {

    private lateinit var filter: SyncErrorFilter

    @Before
    fun setUp() {
        filter = SyncErrorFilter()
    }

    // --- Initial state ---

    @Test
    fun initialState_isNotReady() {
        assertFalse(filter.isReady)
    }

    @Test
    fun initialState_offsetIsZero() {
        assertEquals(0L, filter.offsetMicros)
    }

    @Test
    fun initialState_errorIsMaxValue() {
        assertEquals(Long.MAX_VALUE, filter.errorMicros)
    }

    @Test
    fun initialState_driftIsZero() {
        assertEquals(0.0, filter.driftValue, 0.0)
    }

    // --- Readiness ---

    @Test
    fun update_afterOneMeasurement_isNotReady() {
        filter.update(1000L, 1_000_000L)
        assertFalse(filter.isReady)
    }

    @Test
    fun update_afterTwoMeasurements_isReady() {
        filter.update(1000L, 1_000_000L)
        filter.update(1000L, 2_000_000L)
        assertTrue(filter.isReady)
    }

    // --- Offset convergence ---

    @Test
    fun update_constantMeasurements_offsetConverges() {
        val targetOffset = 5000L
        for (i in 0 until 20) {
            filter.update(targetOffset, (i + 1) * 1_000_000L)
        }
        assertTrue(filter.isReady)
        assertEquals(targetOffset.toDouble(), filter.offsetMicros.toDouble(), 500.0)
    }

    @Test
    fun update_firstMeasurement_initializesOffset() {
        filter.update(3000L, 1_000_000L)
        assertEquals(3000L, filter.offsetMicros)
    }

    // --- Drift detection ---

    @Test
    fun update_linearlyIncreasingMeasurements_detectsDrift() {
        // Feed measurements that increase by 100us per second (100 ppm drift)
        val driftPerUs = 100e-6 // 100 ppm
        for (i in 0 until 30) {
            val timeUs = (i + 1) * 1_000_000L
            val measurement = (1000 + driftPerUs * timeUs).toLong()
            filter.update(measurement, timeUs)
        }
        assertTrue(filter.isReady)
        // Drift should be positive (measurements increasing)
        assertTrue("Drift should be positive for increasing measurements", filter.driftValue > 0)
    }

    @Test
    fun update_driftBoundedToMaxDrift() {
        // Feed extreme measurements that would imply very high drift
        filter.update(0L, 1_000_000L)
        filter.update(1_000_000L, 2_000_000L) // 1 second jump in 1 second = drift of 1.0
        for (i in 3..30) {
            filter.update((i * 1_000_000).toLong(), (i * 1_000_000).toLong())
        }
        // MAX_DRIFT is 5e-4, drift should be bounded
        assertTrue(
            "Drift should be bounded to MAX_DRIFT",
            kotlin.math.abs(filter.driftValue) <= 5e-4 + 1e-6
        )
    }

    // --- Reset ---

    @Test
    fun reset_returnsToInitialState() {
        filter.update(5000L, 1_000_000L)
        filter.update(5000L, 2_000_000L)
        assertTrue(filter.isReady)

        filter.reset()

        assertFalse(filter.isReady)
        assertEquals(0L, filter.offsetMicros)
        assertEquals(Long.MAX_VALUE, filter.errorMicros)
        assertEquals(0.0, filter.driftValue, 0.0)
    }

    // --- predictAt ---

    @Test
    fun predictAt_usesOffsetPlusDriftTimesDt() {
        // Set up filter with known state
        filter.update(1000L, 1_000_000L)
        filter.update(1000L, 2_000_000L)
        // After 2 measurements, offset ~1000, drift ~0
        // predictAt at lastUpdateTime should return approximately the offset
        val prediction = filter.predictAt(2_000_000L)
        assertEquals(1000.0, prediction.toDouble(), 100.0)
    }

    @Test
    fun predictAt_futureTimePredicts() {
        filter.update(1000L, 1_000_000L)
        filter.update(1000L, 2_000_000L)
        // With drift near 0, future prediction should still be near 1000
        val prediction = filter.predictAt(10_000_000L)
        assertEquals(1000.0, prediction.toDouble(), 500.0)
    }

    // --- Error decreases ---

    @Test
    fun update_consistentMeasurements_errorDecreases() {
        filter.update(5000L, 1_000_000L)
        filter.update(5000L, 2_000_000L)
        val errorAfterTwo = filter.errorMicros

        for (i in 3..20) {
            filter.update(5000L, i * 1_000_000L)
        }
        val errorAfterMany = filter.errorMicros

        assertTrue(
            "Error should decrease with consistent measurements (was $errorAfterTwo, now $errorAfterMany)",
            errorAfterMany < errorAfterTwo
        )
    }

    // --- Negative measurements ---

    @Test
    fun update_negativeMeasurements_handledCorrectly() {
        filter.update(-3000L, 1_000_000L)
        filter.update(-3000L, 2_000_000L)
        assertTrue(filter.isReady)
        assertEquals(-3000.0, filter.offsetMicros.toDouble(), 500.0)
    }

    // --- Zero dt protection ---

    @Test
    fun update_zeroDt_doesNotCrash() {
        filter.update(1000L, 1_000_000L)
        filter.update(1000L, 2_000_000L)
        // Same timestamp as previous - should not crash or corrupt state
        filter.update(1000L, 2_000_000L)
        assertTrue(filter.isReady)
    }

    // --- Step change detection ---

    @Test
    fun update_stepChange_adaptsToNewOffset() {
        // Converge at 1000us
        for (i in 1..15) {
            filter.update(1000L, i * 1_000_000L)
        }
        val offsetBefore = filter.offsetMicros

        // Step change to 5000us
        for (i in 16..30) {
            filter.update(5000L, i * 1_000_000L)
        }
        val offsetAfter = filter.offsetMicros

        assertTrue(
            "Filter should adapt toward new offset after step change",
            kotlin.math.abs(offsetAfter - 5000) < kotlin.math.abs(offsetBefore - 5000)
        )
    }

    // --- H-07: Drift convergence (no decay) ---

    @Test
    fun update_constantDrift_convergesToTrueDriftRate() {
        // Simulate a genuine 50 ppm DAC clock drift (50 us per second).
        // With the drift decay removed, the Kalman filter should converge
        // to approximately the true drift rate.
        val trueDriftPerUs = 50e-6  // 50 ppm
        val baseOffset = 1000.0

        for (i in 0 until 100) {
            val timeUs = (i + 1) * 1_000_000L  // 1 second apart
            val measurement = (baseOffset + trueDriftPerUs * timeUs).toLong()
            filter.update(measurement, timeUs)
        }

        val estimatedDrift = filter.driftValue
        // Drift should be within 20% of true value (50 ppm = 50e-6)
        assertTrue(
            "Drift should converge near true rate of 50e-6, but was $estimatedDrift",
            estimatedDrift > trueDriftPerUs * 0.8 && estimatedDrift < trueDriftPerUs * 1.2
        )
    }

    @Test
    fun update_zeroDrift_driftStaysNearZero() {
        // With constant measurements (no real drift), drift should stay near zero
        // rather than decaying toward zero from some nonzero value
        for (i in 0 until 50) {
            filter.update(5000L, (i + 1) * 1_000_000L)
        }

        assertTrue(
            "Drift should stay near zero for constant measurements, but was ${filter.driftValue}",
            kotlin.math.abs(filter.driftValue) < 1e-6
        )
    }

    // --- H-05: Symmetric covariance inflation during step changes ---

    @Test
    fun update_stepChange_driftAdaptsQuickly() {
        // After convergence, introduce a step change that also implies a new drift rate.
        // With symmetric covariance inflation, the drift should adapt faster because
        // the off-diagonal terms (which couple offset and drift) are also inflated.
        val driftPerUs = 30e-6  // 30 ppm drift after step change

        // Converge at 1000us with zero drift
        for (i in 1..20) {
            filter.update(1000L, i * 1_000_000L)
        }

        // Step change to 5000us with ongoing drift of 30 ppm
        val stepOffset = 5000.0
        for (i in 21..40) {
            val timeUs = i * 1_000_000L
            val timeSinceStep = (i - 20) * 1_000_000L
            val measurement = (stepOffset + driftPerUs * timeSinceStep).toLong()
            filter.update(measurement, timeUs)
        }

        val offsetError = kotlin.math.abs(filter.offsetMicros - (stepOffset + driftPerUs * 20 * 1_000_000).toLong())
        assertTrue(
            "After step change with drift, offset should track well (error=$offsetError)",
            offsetError < 2000  // Within 2ms
        )

        // Drift should be positive (tracking the 30 ppm drift)
        assertTrue(
            "Drift should be positive after step change with increasing measurements, was ${filter.driftValue}",
            filter.driftValue > 0
        )
    }
}
