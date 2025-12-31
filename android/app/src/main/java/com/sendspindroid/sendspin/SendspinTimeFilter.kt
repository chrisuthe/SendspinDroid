package com.sendspindroid.sendspin

import kotlin.math.sqrt

/**
 * Kalman filter for time synchronization between client and server.
 *
 * Implements a 2D Kalman filter tracking:
 * - offset: Time difference between client and server clocks (microseconds)
 * - drift: Rate of clock divergence (microseconds per microsecond)
 *
 * ## Algorithm
 * Uses NTP-style measurements to estimate clock offset:
 * ```
 * offset = ((T2 - T1) + (T3 - T4)) / 2
 * ```
 * where:
 * - T1 = client_transmitted
 * - T2 = server_received
 * - T3 = server_transmitted
 * - T4 = client_received
 *
 * The Kalman filter smooths these measurements and tracks drift over time.
 *
 * ## Time Conversion
 * - Server to Client: T_client = (T_server - offset + drift * T_last) / (1 + drift)
 * - Client to Server: T_server = T_client + offset + drift * (T_client - T_last)
 *
 * Reference: aiosendspin time_sync.py
 */
class SendspinTimeFilter {

    companion object {
        // Process noise - how much we expect the clock to drift
        private const val PROCESS_NOISE_OFFSET = 100.0 // μs² per second
        private const val PROCESS_NOISE_DRIFT = 1e-6 // (μs/μs)² per second

        // Minimum measurements before we consider ourselves synchronized
        private const val MIN_MEASUREMENTS = 2

        // Forgetting factor - reset if residual exceeds this fraction of max_error
        private const val FORGETTING_THRESHOLD = 0.75
    }

    // State vector: [offset, drift]
    private var offset: Double = 0.0
    private var drift: Double = 0.0

    // Covariance matrix (2x2, stored as 4 elements: P[0,0], P[0,1], P[1,0], P[1,1])
    private var p00: Double = Double.MAX_VALUE // offset variance
    private var p01: Double = 0.0              // offset-drift covariance
    private var p10: Double = 0.0              // drift-offset covariance
    private var p11: Double = 1e-6             // drift variance

    // Timing state
    private var lastUpdateTime: Long = 0
    private var measurementCount: Int = 0

    // Static delay offset for speaker synchronization (GroupSync calibration)
    // Positive = delay playback (plays later), Negative = advance (plays earlier)
    private var staticDelayMicros: Long = 0

    /**
     * Whether enough measurements have been collected for reliable time conversion.
     */
    val isReady: Boolean
        get() = measurementCount >= MIN_MEASUREMENTS && p00.isFinite()

    /**
     * Current estimated offset in microseconds.
     */
    val offsetMicros: Long
        get() = offset.toLong()

    /**
     * Estimated error (standard deviation) in microseconds.
     */
    val errorMicros: Long
        get() = if (p00.isFinite() && p00 >= 0) sqrt(p00).toLong() else Long.MAX_VALUE

    /**
     * Number of measurements collected so far.
     */
    val measurementCountValue: Int
        get() = measurementCount

    /**
     * Static delay in milliseconds for speaker synchronization.
     * Positive = delay playback (plays later), Negative = advance (plays earlier).
     * Used by GroupSync calibration tool.
     */
    var staticDelayMs: Double
        get() = staticDelayMicros / 1000.0
        set(value) {
            staticDelayMicros = (value * 1000).toLong()
        }

    /**
     * Reset the filter to initial state.
     */
    fun reset() {
        offset = 0.0
        drift = 0.0
        p00 = Double.MAX_VALUE
        p01 = 0.0
        p10 = 0.0
        p11 = 1e-6
        lastUpdateTime = 0
        measurementCount = 0
    }

    /**
     * Add a new time measurement to the filter.
     *
     * @param measurementOffset The measured offset in microseconds
     * @param maxError The maximum error (uncertainty) in microseconds
     * @param clientTimeMicros The client timestamp when measurement was taken
     */
    fun addMeasurement(measurementOffset: Long, maxError: Long, clientTimeMicros: Long) {
        val measurement = measurementOffset.toDouble()
        val variance = (maxError.toDouble() * maxError).coerceAtLeast(1.0)

        when (measurementCount) {
            0 -> {
                // First measurement - initialize offset directly
                offset = measurement
                p00 = variance
                lastUpdateTime = clientTimeMicros
                measurementCount = 1
            }
            1 -> {
                // Second measurement - estimate initial drift
                val dt = (clientTimeMicros - lastUpdateTime).toDouble()
                if (dt > 0) {
                    drift = (measurement - offset) / dt
                    p11 = variance / (dt * dt) // Drift variance from measurement
                }
                offset = measurement
                p00 = variance
                lastUpdateTime = clientTimeMicros
                measurementCount = 2
            }
            else -> {
                // Steady state - full Kalman update
                kalmanUpdate(measurement, variance, clientTimeMicros)
            }
        }
    }

    /**
     * Perform Kalman filter prediction and update.
     */
    private fun kalmanUpdate(measurement: Double, variance: Double, clientTimeMicros: Long) {
        val dt = (clientTimeMicros - lastUpdateTime).toDouble()
        if (dt <= 0) return

        // === Prediction Step ===
        // State prediction: offset_predicted = offset + drift * dt
        val offsetPredicted = offset + drift * dt
        // Drift prediction: drift stays the same (random walk model)

        // Covariance prediction: P = F * P * F^T + Q
        // F = [1, dt; 0, 1] (state transition matrix)
        // Q = [q_offset * dt, 0; 0, q_drift * dt] (process noise)
        val p00New = p00 + 2 * p01 * dt + p11 * dt * dt + PROCESS_NOISE_OFFSET * dt
        val p01New = p01 + p11 * dt
        val p10New = p10 + p11 * dt
        val p11New = p11 + PROCESS_NOISE_DRIFT * dt

        // === Innovation (Residual) ===
        val innovation = measurement - offsetPredicted

        // Check for outlier - apply adaptive forgetting if needed
        val innovationVariance = p00New + variance
        val normalizedResidual = if (innovationVariance > 0) {
            (innovation * innovation) / innovationVariance
        } else {
            0.0
        }

        // If residual is too large, this might be a step change - partially reset
        if (normalizedResidual > FORGETTING_THRESHOLD * FORGETTING_THRESHOLD) {
            // Increase covariance to adapt faster
            p00 = p00New * 10
            p01 = p01New
            p10 = p10New
            p11 = p11New * 10
        } else {
            p00 = p00New
            p01 = p01New
            p10 = p10New
            p11 = p11New
        }

        // === Update Step ===
        // Kalman gain: K = P * H^T * (H * P * H^T + R)^-1
        // H = [1, 0] (measurement matrix - we only measure offset)
        val s = p00 + variance // Innovation covariance
        if (s <= 0) return

        val k0 = p00 / s // Kalman gain for offset
        val k1 = p10 / s // Kalman gain for drift

        // State update
        offset = offsetPredicted + k0 * innovation
        drift = drift + k1 * innovation

        // Covariance update: P = (I - K * H) * P
        val p00Updated = (1 - k0) * p00
        val p01Updated = (1 - k0) * p01
        val p10Updated = p10 - k1 * p00
        val p11Updated = p11 - k1 * p01

        p00 = p00Updated
        p01 = p01Updated
        p10 = p10Updated
        p11 = p11Updated

        lastUpdateTime = clientTimeMicros
        measurementCount++
    }

    /**
     * Convert server time to client time.
     * Includes static delay offset for speaker synchronization.
     *
     * @param serverTimeMicros Server timestamp in microseconds
     * @return Equivalent client timestamp in microseconds
     */
    fun serverToClient(serverTimeMicros: Long): Long {
        // T_client = (T_server - offset + drift * T_last) / (1 + drift)
        val denom = 1.0 + drift
        val baseResult = if (denom == 0.0) {
            serverTimeMicros - offset.toLong()
        } else {
            ((serverTimeMicros - offset + drift * lastUpdateTime) / denom).toLong()
        }

        // Apply static delay: positive delay = play later = higher client time
        return baseResult + staticDelayMicros
    }

    /**
     * Convert client time to server time.
     *
     * @param clientTimeMicros Client timestamp in microseconds
     * @return Equivalent server timestamp in microseconds
     */
    fun clientToServer(clientTimeMicros: Long): Long {
        // T_server = T_client + offset + drift * (T_client - T_last)
        val result = clientTimeMicros + offset + drift * (clientTimeMicros - lastUpdateTime)
        return result.toLong()
    }
}
