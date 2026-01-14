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

        // Stricter convergence threshold for good sync quality
        private const val MIN_MEASUREMENTS_FOR_CONVERGENCE = 5
        private const val MAX_ERROR_FOR_CONVERGENCE_US = 10_000L  // 10ms

        // Forgetting factor - reset if residual exceeds this fraction of max_error
        private const val FORGETTING_THRESHOLD = 0.75

        // Maximum allowed drift (±500 ppm = ±5e-4)
        // Real quartz oscillators have drift of ~20-100 ppm, so this is generous
        private const val MAX_DRIFT = 5e-4

        // Drift decay factor - slowly decay drift towards zero to prevent long-term accumulation
        // Applied every update: drift *= (1 - DRIFT_DECAY_RATE)
        // At 1Hz updates, 0.01 gives ~1% decay per second, ~50% after 70 seconds
        private const val DRIFT_DECAY_RATE = 0.01
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

    // Baseline time for relative calculations - prevents drift accumulation over long periods
    // Set when first measurement is received, used as reference point for time conversions
    private var baselineClientTime: Long = 0

    // Static delay offset for speaker synchronization (GroupSync calibration)
    // Positive = delay playback (plays later), Negative = advance (plays earlier)
    private var staticDelayMicros: Long = 0

    /**
     * Whether enough measurements have been collected for reliable time conversion.
     * This is the minimum threshold - playback can start, but may need corrections.
     */
    val isReady: Boolean
        get() = measurementCount >= MIN_MEASUREMENTS && p00.isFinite()

    /**
     * Whether the filter has converged to a high-quality sync.
     * This is stricter than isReady - requires more measurements and lower uncertainty.
     * When true, sync corrections should be minimal.
     */
    val isConverged: Boolean
        get() = measurementCount >= MIN_MEASUREMENTS_FOR_CONVERGENCE &&
                p00.isFinite() &&
                errorMicros < MAX_ERROR_FOR_CONVERGENCE_US

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
     * Current drift in parts per million (ppm).
     * Positive = server clock running faster than client.
     */
    val driftPpm: Double
        get() = drift * 1_000_000.0

    /**
     * Time of last measurement update in microseconds (client time).
     */
    val lastUpdateTimeUs: Long
        get() = lastUpdateTime

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
        baselineClientTime = 0
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
                baselineClientTime = clientTimeMicros  // Set baseline for relative calculations
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
        var newDrift = drift + k1 * innovation

        // Bound drift to physically realistic values (±500 ppm)
        // This prevents the drift * lastUpdateTime term from exploding over time
        newDrift = newDrift.coerceIn(-MAX_DRIFT, MAX_DRIFT)

        // Apply drift decay to prevent long-term accumulation
        // This slowly pulls drift back towards zero, ensuring small estimation errors
        // don't compound indefinitely
        newDrift *= (1.0 - DRIFT_DECAY_RATE)

        drift = newDrift

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
     * NOTE: Drift is intentionally NOT used in time conversion (matching Python reference).
     * The sync correction feedback loop handles clock rate differences naturally through
     * sample insert/drop. Using drift here caused sync error oscillation because noisy
     * drift estimates fed back into the sync error calculation, creating instability.
     *
     * Drift is still tracked and displayed in Stats for Nerds for debugging purposes.
     *
     * @param serverTimeMicros Server timestamp in microseconds
     * @return Equivalent client timestamp in microseconds
     */
    fun serverToClient(serverTimeMicros: Long): Long {
        // Simple offset-only conversion (matches Python sendspin-cli)
        // Drift is NOT applied here - the sync correction loop handles rate differences
        val baseResult = serverTimeMicros - offset.toLong()

        // Apply static delay: positive delay = play later = higher client time
        return baseResult + staticDelayMicros
    }

    /**
     * Convert client time to server time.
     *
     * NOTE: Drift is intentionally NOT used (see serverToClient comments).
     *
     * @param clientTimeMicros Client timestamp in microseconds
     * @return Equivalent server timestamp in microseconds
     */
    fun clientToServer(clientTimeMicros: Long): Long {
        // Simple offset-only conversion (matches Python sendspin-cli)
        return clientTimeMicros + offset.toLong() - staticDelayMicros
    }
}
