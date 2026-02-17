package com.sendspindroid.sendspin

import com.sendspindroid.shared.log.Log
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SendspinTimeFilterTest {

    private lateinit var filter: SendspinTimeFilter

    @Before
    fun setUp() {
        mockkObject(Log)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        filter = SendspinTimeFilter()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- Initial state ---

    @Test
    fun initialState_isNotReady() {
        assertFalse(filter.isReady)
    }

    @Test
    fun initialState_isNotConverged() {
        assertFalse(filter.isConverged)
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
    fun initialState_measurementCountIsZero() {
        assertEquals(0, filter.measurementCountValue)
    }

    @Test
    fun initialState_isNotFrozen() {
        assertFalse(filter.isFrozen)
    }

    // --- Readiness ---

    @Test
    fun addMeasurement_afterOneMeasurement_isNotReady() {
        filter.addMeasurement(1000L, 5000L, 1_000_000L)
        assertFalse(filter.isReady)
    }

    @Test
    fun addMeasurement_afterTwoMeasurements_isReady() {
        filter.addMeasurement(1000L, 5000L, 1_000_000L)
        filter.addMeasurement(1000L, 5000L, 2_000_000L)
        assertTrue(filter.isReady)
    }

    @Test
    fun addMeasurement_incrementsMeasurementCount() {
        filter.addMeasurement(1000L, 5000L, 1_000_000L)
        assertEquals(1, filter.measurementCountValue)
        filter.addMeasurement(1000L, 5000L, 2_000_000L)
        assertEquals(2, filter.measurementCountValue)
    }

    // --- Convergence ---

    @Test
    fun isConverged_afterFewMeasurements_isFalse() {
        // Feed 3 measurements (need 5 for convergence)
        for (i in 1..3) {
            filter.addMeasurement(1000L, 5000L, i * 1_000_000L)
        }
        assertFalse(filter.isConverged)
    }

    @Test
    fun isConverged_afterManyConsistentMeasurements_isTrue() {
        // Feed many consistent low-error measurements
        for (i in 1..30) {
            filter.addMeasurement(1000L, 3000L, i * 1_000_000L)
        }
        assertTrue("Filter should converge after many consistent measurements", filter.isConverged)
    }

    // --- Offset convergence ---

    @Test
    fun addMeasurement_constantOffset_converges() {
        val targetOffset = 50_000L // 50ms
        for (i in 1..20) {
            filter.addMeasurement(targetOffset, 5000L, i * 1_000_000L)
        }
        assertTrue(filter.isReady)
        assertEquals(targetOffset.toDouble(), filter.offsetMicros.toDouble(), 5000.0)
    }

    @Test
    fun addMeasurement_firstMeasurement_initializesOffset() {
        filter.addMeasurement(50_000L, 5000L, 1_000_000L)
        assertEquals(50_000L, filter.offsetMicros)
    }

    // --- Time conversion ---

    @Test
    fun serverToClient_subtractsOffset() {
        // Converge the filter at a known offset
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        val serverTime = 100_000_000L
        val clientTime = filter.serverToClient(serverTime)
        // client = server - offset, so clientTime should be approximately 100_000_000 - 10_000
        assertEquals(serverTime - filter.offsetMicros, clientTime)
    }

    @Test
    fun clientToServer_addsOffset() {
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        val clientTime = 100_000_000L
        val serverTime = filter.clientToServer(clientTime)
        assertEquals(clientTime + filter.offsetMicros, serverTime)
    }

    @Test
    fun serverToClient_clientToServer_roundTrip() {
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        val originalTime = 100_000_000L
        val roundTrip = filter.clientToServer(filter.serverToClient(originalTime))
        assertEquals(originalTime, roundTrip)
    }

    // --- Static delay ---

    @Test
    fun staticDelayMs_shiftsServerToClient() {
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        val serverTime = 100_000_000L
        val withoutDelay = filter.serverToClient(serverTime)

        filter.staticDelayMs = 5.0 // 5ms = 5000us
        val withDelay = filter.serverToClient(serverTime)

        // Positive delay = play later = higher client time
        assertEquals(withoutDelay + 5000, withDelay)
    }

    @Test
    fun staticDelayMs_getterReturnsSetValue() {
        filter.staticDelayMs = 10.5
        assertEquals(10.5, filter.staticDelayMs, 0.01)
    }

    // --- Outlier rejection ---

    @Test
    fun addMeasurement_outlier_isRejected() {
        // Build history with consistent measurements
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 5000L, i * 1_000_000L)
        }
        val offsetBefore = filter.offsetMicros

        // Send a wild outlier
        val accepted = filter.addMeasurement(1_000_000L, 5000L, 11_000_000L)

        assertFalse("Outlier should be rejected", accepted)
        // Offset should not have changed significantly
        assertEquals(offsetBefore, filter.offsetMicros)
    }

    @Test
    fun addMeasurement_forceAcceptAfterThreeRejections() {
        // Build history
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 5000L, i * 1_000_000L)
        }

        // Send 3 outliers (rejected), then 4th should be force-accepted
        val outlierOffset = 500_000L
        filter.addMeasurement(outlierOffset, 5000L, 11_000_000L)
        filter.addMeasurement(outlierOffset, 5000L, 12_000_000L)
        filter.addMeasurement(outlierOffset, 5000L, 13_000_000L)
        val accepted = filter.addMeasurement(outlierOffset, 5000L, 14_000_000L)

        assertTrue("4th consecutive outlier should be force-accepted", accepted)
    }

    // --- Freeze / thaw ---

    @Test
    fun freeze_whenReady_storesState() {
        for (i in 1..5) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        assertTrue(filter.isReady)

        filter.freeze()
        assertTrue(filter.isFrozen)
    }

    @Test
    fun freeze_whenNotReady_doesNotStore() {
        filter.addMeasurement(10_000L, 3000L, 1_000_000L)
        assertFalse(filter.isReady)

        filter.freeze()
        assertFalse(filter.isFrozen)
    }

    @Test
    fun thaw_restoresOffsetWithInflatedCovariance() {
        // Converge the filter
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        val offsetBeforeFreeze = filter.offsetMicros
        val errorBeforeFreeze = filter.errorMicros

        filter.freeze()
        filter.reset()

        assertFalse(filter.isReady)

        filter.thaw()

        // Offset should be restored
        assertEquals(offsetBeforeFreeze, filter.offsetMicros)
        // Error should be inflated (covariance * 10)
        assertTrue(
            "Error should be inflated after thaw",
            filter.errorMicros > errorBeforeFreeze
        )
        assertFalse("Frozen state should be cleared after thaw", filter.isFrozen)
    }

    // --- resetAndDiscard ---

    @Test
    fun resetAndDiscard_clearsFrozenStateAndResets() {
        for (i in 1..5) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        filter.freeze()
        assertTrue(filter.isFrozen)

        filter.resetAndDiscard()

        assertFalse(filter.isFrozen)
        assertFalse(filter.isReady)
        assertEquals(0L, filter.offsetMicros)
    }

    // --- Reset ---

    @Test
    fun reset_returnsToInitialState() {
        for (i in 1..5) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        assertTrue(filter.isReady)

        filter.reset()

        assertFalse(filter.isReady)
        assertEquals(0L, filter.offsetMicros)
        assertEquals(Long.MAX_VALUE, filter.errorMicros)
        assertEquals(0, filter.measurementCountValue)
    }

    // --- Drift ---

    @Test
    fun driftPpm_returnsValueInPpm() {
        // After initialization, drift should be 0 ppm
        assertEquals(0.0, filter.driftPpm, 0.01)
    }

    @Test
    fun drift_isBoundedToMaxDrift() {
        // Feed measurements with extreme increasing offset to push drift
        filter.addMeasurement(0L, 5000L, 1_000_000L)
        filter.addMeasurement(0L, 5000L, 2_000_000L)
        for (i in 3..30) {
            // Each measurement is 1 second worth of drift at extreme rate
            filter.addMeasurement((i * 100_000).toLong(), 5000L, i * 1_000_000L)
        }
        // MAX_DRIFT is 5e-4, which is 500 ppm
        assertTrue(
            "Drift should be bounded to 500 ppm, was ${filter.driftPpm}",
            kotlin.math.abs(filter.driftPpm) <= 500.0 + 1.0
        )
    }

    // --- Error decreases with measurements ---

    @Test
    fun addMeasurement_consistentMeasurements_errorDecreases() {
        filter.addMeasurement(10_000L, 5000L, 1_000_000L)
        filter.addMeasurement(10_000L, 5000L, 2_000_000L)
        val errorAfterTwo = filter.errorMicros

        for (i in 3..20) {
            filter.addMeasurement(10_000L, 5000L, i * 1_000_000L)
        }
        val errorAfterMany = filter.errorMicros

        assertTrue(
            "Error should decrease with consistent measurements (was $errorAfterTwo, now $errorAfterMany)",
            errorAfterMany < errorAfterTwo
        )
    }

    // --- Stability score (innovation variance ratio) ---

    @Test
    fun stabilityScore_consistentMeasurements_convergesToOne() {
        // Feed many consistent measurements so the filter converges fully.
        // With correct innovation normalization (using predicted P00),
        // the stability score (mean normalized innovation) should settle near 1.0.
        // The stale-p00 bug caused this to be systematically > 1.0 because the
        // denominator was too small (prior posterior instead of predicted covariance).
        val targetOffset = 50_000L  // 50ms
        val maxError = 5000L

        // Feed enough measurements to fill the innovation window and let
        // the adaptive process noise settle. INNOVATION_WINDOW_SIZE is 20,
        // so we need well beyond that for convergence.
        for (i in 1..60) {
            filter.addMeasurement(targetOffset, maxError, i * 1_000_000L)
        }

        val score = filter.stability
        assertTrue(
            "Stability score should converge near 1.0 with consistent measurements, " +
                    "but was $score (> 1.0 suggests stale covariance in innovation normalization)",
            score in 0.5..1.5
        )
    }

    @Test
    fun stabilityScore_afterReset_isOne() {
        // Feed some measurements then reset
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 5000L, i * 1_000_000L)
        }
        filter.reset()
        assertEquals(1.0, filter.stability, 0.001)
    }

    @Test
    fun stabilityScore_noisyMeasurements_remainsReasonable() {
        // Feed measurements with moderate noise (simulating real-world jitter).
        // Stability score should still be in a reasonable range, not diverging.
        val baseOffset = 50_000L
        val maxError = 5000L
        val jitterAmplitude = 3000L  // Within measurement uncertainty

        for (i in 1..80) {
            // Alternate jitter pattern: +/- jitterAmplitude
            val jitter = if (i % 2 == 0) jitterAmplitude else -jitterAmplitude
            filter.addMeasurement(baseOffset + jitter, maxError, i * 1_000_000L)
        }

        val score = filter.stability
        assertTrue(
            "Stability score should remain in reasonable range with noisy measurements, " +
                    "but was $score",
            score in 0.1..5.0
        )
    }
}
