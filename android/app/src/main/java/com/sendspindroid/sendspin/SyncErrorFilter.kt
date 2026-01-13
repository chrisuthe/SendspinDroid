package com.sendspindroid.sendspin

import kotlin.math.sqrt

/**
 * Kalman filter for sync error smoothing.
 *
 * Uses a 1D Kalman filter to optimally estimate the true sync error from noisy
 * measurements. This provides better smoothing than simple EMA by:
 * - Adapting to measurement noise characteristics
 * - Providing optimal gain based on uncertainty
 * - Tracking drift/trend in the error
 *
 * Based on the Python reference implementation (audio.py) which uses a full
 * SendspinTimeFilter for sync error smoothing.
 *
 * @param processStdDev Expected standard deviation of process noise (how much
 *                      true sync error can change between updates). Default 0.01.
 * @param measurementNoiseUs Expected measurement noise in microseconds. Default 5000 (5ms).
 */
class SyncErrorFilter(
    private val processStdDev: Double = 0.01,
    private val measurementNoiseUs: Long = 5_000L
) {
    companion object {
        // Minimum measurements before filter is ready
        private const val MIN_MEASUREMENTS = 2

        // Forgetting threshold - if residual exceeds this, increase uncertainty
        private const val FORGETTING_THRESHOLD_SIGMA = 3.0
    }

    // State: estimated sync error
    private var estimate: Double = 0.0

    // Uncertainty: variance of the estimate
    private var variance: Double = Double.MAX_VALUE

    // Timing
    private var lastUpdateTimeUs: Long = 0
    private var measurementCount: Int = 0

    /**
     * Whether enough measurements have been collected for reliable estimation.
     */
    val isReady: Boolean
        get() = measurementCount >= MIN_MEASUREMENTS && variance.isFinite()

    /**
     * Current estimated sync error in microseconds.
     */
    val offsetMicros: Long
        get() = estimate.toLong()

    /**
     * Estimated uncertainty (standard deviation) in microseconds.
     */
    val errorMicros: Long
        get() = if (variance.isFinite() && variance >= 0) sqrt(variance).toLong() else Long.MAX_VALUE

    /**
     * Reset the filter to initial state.
     */
    fun reset() {
        estimate = 0.0
        variance = Double.MAX_VALUE
        lastUpdateTimeUs = 0
        measurementCount = 0
    }

    /**
     * Add a new sync error measurement.
     *
     * @param measurement The measured sync error in microseconds
     * @param timeUs Current time in microseconds (for process noise scaling)
     */
    fun update(measurement: Long, timeUs: Long) {
        val measDouble = measurement.toDouble()
        val measurementVariance = (measurementNoiseUs.toDouble() * measurementNoiseUs).coerceAtLeast(1.0)

        when (measurementCount) {
            0 -> {
                // First measurement - initialize directly
                estimate = measDouble
                variance = measurementVariance
                lastUpdateTimeUs = timeUs
                measurementCount = 1
            }
            else -> {
                // Calculate time delta for process noise scaling
                val dt = (timeUs - lastUpdateTimeUs).toDouble().coerceAtLeast(1.0)
                val dtSeconds = dt / 1_000_000.0

                // === Prediction Step ===
                // State prediction: estimate stays the same (random walk)
                // Variance prediction: increases by process noise
                val processVariance = processStdDev * processStdDev * dtSeconds * 1_000_000.0 * 1_000_000.0
                val predictedVariance = variance + processVariance

                // === Innovation (Residual) ===
                val innovation = measDouble - estimate
                val innovationVariance = predictedVariance + measurementVariance

                // Check for outlier - if residual is too large, increase uncertainty
                if (innovationVariance > 0) {
                    val normalizedResidual = kotlin.math.abs(innovation) / sqrt(innovationVariance)
                    if (normalizedResidual > FORGETTING_THRESHOLD_SIGMA) {
                        // Large residual - this might be a step change
                        // Increase variance to adapt faster
                        variance = predictedVariance * 4.0
                    } else {
                        variance = predictedVariance
                    }
                } else {
                    variance = predictedVariance
                }

                // === Update Step ===
                val s = variance + measurementVariance
                if (s > 0) {
                    val kalmanGain = variance / s
                    estimate += kalmanGain * innovation
                    variance = (1 - kalmanGain) * variance
                }

                lastUpdateTimeUs = timeUs
                measurementCount++
            }
        }
    }
}
