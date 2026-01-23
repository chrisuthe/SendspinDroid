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
        // ============================================================================
        // KALMAN FILTER CONSTANTS - Detailed Documentation
        // ============================================================================
        //
        // This Kalman filter tracks two state variables:
        //   1. offset: Time difference between client and server clocks (microseconds)
        //   2. drift: Rate of clock divergence (microseconds per microsecond, i.e., dimensionless)
        //
        // The filter uses a constant-velocity model where offset evolves according to:
        //   offset(t+dt) = offset(t) + drift * dt + process_noise
        //
        // References:
        // - NTP clock discipline algorithm (RFC 5905)
        // - Kalman filter theory: Welch & Bishop, "An Introduction to the Kalman Filter"
        // - aiosendspin Python reference implementation (time_sync.py)
        // ============================================================================

        // ----------------------------------------------------------------------------
        // PROCESS_NOISE_OFFSET: Variance added to offset estimate per second (μs²/s)
        // ----------------------------------------------------------------------------
        // Physical meaning: Models random jitter in the clock offset measurement.
        // This accounts for unpredictable variations in network latency, OS scheduling
        // delays, and other sources of timing noise that cause the apparent offset to
        // change even when the underlying clocks are stable.
        //
        // Value rationale: 100 μs²/s means we expect ~10μs standard deviation of
        // random offset noise per second. This is typical for WiFi networks where
        // jitter is 1-10ms. The value was tuned empirically against the Python
        // reference implementation to achieve similar convergence behavior.
        //
        // If too low: Filter becomes overconfident, slow to adapt to real changes
        //             in network conditions. May take many seconds to correct after
        //             a WiFi handoff or route change.
        // If too high: Filter never settles, constantly chasing noise. Sync error
        //              remains high and playback corrections are frequent.
        // ----------------------------------------------------------------------------
        private const val PROCESS_NOISE_OFFSET = 100.0

        // ----------------------------------------------------------------------------
        // PROCESS_NOISE_DRIFT: Variance added to drift estimate per second ((μs/μs)²/s)
        // ----------------------------------------------------------------------------
        // Physical meaning: Models how much we expect the clock frequency ratio to
        // change over time. Quartz oscillators have stable short-term frequency but
        // can drift due to temperature changes, aging, and voltage variations.
        //
        // Value rationale: Set to 0.0 to match the web player. Phone crystal
        // oscillators have extremely stable short-term frequency, so adding drift
        // noise just makes the estimate noisy on high-jitter networks. The offset
        // process noise alone provides sufficient adaptation. With zero drift noise,
        // the drift estimate converges faster and stays stable.
        //
        // If too high: Drift estimate becomes noisy, oscillating wildly and causing
        //              the offset prediction to overshoot/undershoot. This is
        //              especially problematic on cellular networks with high RTT
        //              variance.
        // ----------------------------------------------------------------------------
        private const val PROCESS_NOISE_DRIFT = 0.0

        // ----------------------------------------------------------------------------
        // MIN_MEASUREMENTS: Minimum measurements before isReady becomes true
        // ----------------------------------------------------------------------------
        // Physical meaning: Number of round-trip time measurements needed before
        // the filter has enough information for basic time conversion.
        //
        // Value rationale: 2 measurements are needed because:
        //   - 1st measurement: Initializes offset (no drift information yet)
        //   - 2nd measurement: Provides first drift estimate (needs two points)
        // With only 1 measurement, we have offset but no drift, making predictions
        // unreliable over time.
        //
        // If too low (1): Playback starts with no drift estimate, may drift away
        //                 from sync before more measurements arrive.
        // If too high: Unnecessary delay before playback can begin. User waits
        //              longer for audio to start.
        // ----------------------------------------------------------------------------
        private const val MIN_MEASUREMENTS = 2

        // ----------------------------------------------------------------------------
        // MIN_MEASUREMENTS_FOR_CONVERGENCE: Measurements for high-confidence sync
        // ----------------------------------------------------------------------------
        // Physical meaning: Number of measurements needed before we consider the
        // filter "converged" to a stable, high-quality estimate.
        //
        // Value rationale: 5 measurements provide enough statistical samples for
        // the Kalman gain to stabilize. After ~5 iterations, the covariance matrix
        // typically settles to its steady-state value. This matches behavior
        // observed in the Python reference implementation.
        //
        // If too low: isConverged triggers early, possibly before the filter has
        //             settled. May cause premature "sync locked" indications.
        // If too high: Takes too long to report convergence. UI may show "syncing"
        //              status longer than necessary.
        // ----------------------------------------------------------------------------
        private const val MIN_MEASUREMENTS_FOR_CONVERGENCE = 5

        // ----------------------------------------------------------------------------
        // MAX_ERROR_FOR_CONVERGENCE_US: Maximum acceptable uncertainty for convergence
        // ----------------------------------------------------------------------------
        // Physical meaning: The standard deviation threshold (in microseconds) below
        // which we consider the sync estimate reliable. This is derived from the
        // covariance matrix diagonal element P[0,0].
        //
        // Value rationale: 10,000μs (10ms) is chosen because:
        //   - Human perception: Audio sync errors <20ms are generally imperceptible
        //   - Sample accuracy: At 48kHz, 10ms = 480 samples - well within correction range
        //   - Network reality: WiFi jitter is often 5-20ms, so <10ms uncertainty
        //     indicates the filter has successfully averaged out the noise.
        //
        // If too low: Convergence may never be reached on high-jitter networks.
        //             Filter perpetually reports "not converged".
        // If too high: Reports convergence even when sync quality is poor.
        //              Playback may have audible glitches despite "converged" status.
        // ----------------------------------------------------------------------------
        private const val MAX_ERROR_FOR_CONVERGENCE_US = 10_000L

        // ----------------------------------------------------------------------------
        // FORGETTING_THRESHOLD: Absolute residual threshold for step change detection
        // ----------------------------------------------------------------------------
        // Physical meaning: When |residual| > FORGETTING_THRESHOLD * maxError, we
        // suspect a step change in offset (e.g., network route change) and gently
        // inflate covariance to adapt faster. Uses absolute threshold (matching the
        // web player) rather than normalized threshold.
        //
        // Value rationale: 0.75 means the residual must exceed 75% of the
        // measurement uncertainty (sqrt(variance) ≈ RTT/2) to trigger forgetting.
        // This naturally scales with network conditions:
        //   - WiFi (RTT ~20ms): threshold ≈ 7.5ms — rarely triggered by jitter
        //   - Cell (RTT ~100ms): threshold ≈ 37ms — only genuine step changes
        //
        // Unlike the previous normalized approach, this doesn't become more
        // sensitive as the filter converges (which caused the instability loop).
        //
        // If too low: Normal jitter triggers forgetting, preventing convergence.
        // If too high: Genuine step changes are ignored, slow to reconverge.
        // ----------------------------------------------------------------------------
        private const val FORGETTING_THRESHOLD = 0.75

        // ----------------------------------------------------------------------------
        // MAX_DRIFT: Maximum allowed drift magnitude (dimensionless, μs/μs)
        // ----------------------------------------------------------------------------
        // Physical meaning: Hard limit on clock frequency difference. ±5e-4 means
        // ±500 parts per million (ppm), or ±0.05% frequency difference.
        //
        // Value rationale: 500 ppm is generous for real hardware:
        //   - Typical quartz oscillators: 20-100 ppm accuracy
        //   - Phone crystals: Often 10-50 ppm
        //   - Temperature effects: Can add 10-20 ppm variation
        // The limit prevents the drift term from growing unbounded due to
        // measurement noise, which would cause offset predictions to diverge.
        // At 500 ppm, over 1 hour the drift would only contribute 1.8 seconds
        // of offset change - well within correctable range.
        //
        // If too low: May clip legitimate drift, causing persistent sync error
        //             if client and server clocks genuinely differ by more.
        // If too high: Noisy drift estimates can cause wild offset predictions.
        //              Filter may oscillate around true value.
        // ----------------------------------------------------------------------------
        private const val MAX_DRIFT = 5e-4

        // ----------------------------------------------------------------------------
        // WARMUP_MEASUREMENTS: Number of measurements before adaptive forgetting activates
        // ----------------------------------------------------------------------------
        // Physical meaning: During the warmup period, no forgetting is applied,
        // allowing the filter to converge to a stable estimate without interference.
        // This matches the web player's approach (which uses 100 measurements).
        //
        // Value rationale: 50 measurements at ~4 Hz = ~12 seconds of warmup.
        // This is long enough for the Kalman gain to stabilize and for the
        // covariance to shrink to a steady-state level. Once warmup ends, the
        // absolute-threshold forgetting can detect genuine step changes without
        // triggering on normal convergence behavior.
        //
        // On WiFi (~20ms RTT): Filter converges well within 50 measurements,
        //   so forgetting activates after the filter is already stable.
        // On cellular (~100ms RTT): Filter needs more measurements to converge,
        //   but 50 gives enough initial stability before forgetting kicks in.
        //
        // If too low: Forgetting triggers during convergence, fighting the filter
        //             and preventing it from ever reaching steady state.
        // If too high: Step changes that occur during warmup won't be adapted to
        //              quickly enough.
        // ----------------------------------------------------------------------------
        private const val WARMUP_MEASUREMENTS = 50

        // ----------------------------------------------------------------------------
        // FORGETTING_FACTOR: Covariance inflation factor for step changes (λ)
        // ----------------------------------------------------------------------------
        // Physical meaning: When a step change is detected (residual exceeds
        // FORGETTING_THRESHOLD * maxError), covariance is multiplied by λ² to
        // allow faster adaptation. Unlike the previous approach, this is NOT
        // applied continuously — only when the absolute threshold is exceeded.
        //
        // Value rationale: λ = 1.001 gives gentle inflation (~0.2% per trigger):
        //   - Matches the web player's approach (1.001² on threshold exceed)
        //   - Gentle enough that repeated triggers don't cause explosion
        //   - Combined with the absolute threshold, provides smooth reconvergence
        //
        // The key difference from the old approach: previously λ² was applied
        // EVERY update (continuous inflation), fighting against the filter's
        // natural convergence. Now it's only applied when genuinely needed.
        //
        // If too low (1.0): After a step change, filter adapts too slowly.
        // If too high (1.01+): Each trigger inflates too much, risking instability.
        // ----------------------------------------------------------------------------
        private const val FORGETTING_FACTOR = 1.001
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

    // Frozen state for reconnection - preserves sync across network drops
    @Volatile private var frozenState: FrozenState? = null

    private data class FrozenState(
        val offset: Double,
        val drift: Double,
        val p00: Double,
        val p01: Double,
        val p10: Double,
        val p11: Double,
        val measurementCount: Int,
        val baselineClientTime: Long
    )

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
     * Whether the filter has frozen state that can be restored.
     */
    val isFrozen: Boolean
        get() = frozenState != null

    /**
     * Freeze the current filter state for reconnection.
     * Preserves the converged time sync across network drops so playback
     * can continue from buffer without losing synchronization.
     *
     * Call this when connection is lost but reconnection will be attempted.
     */
    fun freeze() {
        if (!isReady) return  // Nothing worth preserving

        frozenState = FrozenState(
            offset = offset,
            drift = drift,
            p00 = p00,
            p01 = p01,
            p10 = p10,
            p11 = p11,
            measurementCount = measurementCount,
            baselineClientTime = baselineClientTime
        )
    }

    /**
     * Restore frozen state after reconnection.
     * Increases covariance to allow faster adaptation to potentially
     * changed network conditions while preserving the general sync estimate.
     *
     * Call this after successful reconnection, before resuming time sync.
     */
    fun thaw() {
        val frozen = frozenState ?: return

        offset = frozen.offset
        drift = frozen.drift
        // Increase covariance by 10x to allow faster adaptation
        // while preserving the general sync estimate
        p00 = frozen.p00 * 10.0
        p01 = frozen.p01 * 3.0
        p10 = frozen.p10 * 3.0
        p11 = frozen.p11 * 10.0
        measurementCount = frozen.measurementCount
        baselineClientTime = frozen.baselineClientTime
        // Don't reset lastUpdateTime - will be updated on next measurement

        frozenState = null
    }

    /**
     * Discard frozen state and perform full reset.
     * Call this when reconnection fails and we need to start fresh.
     */
    fun resetAndDiscard() {
        frozenState = null
        reset()
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
     *
     * Key differences from the previous implementation (matching web player):
     * - No continuous covariance inflation (was causing runaway gain on cell networks)
     * - Absolute residual threshold instead of normalized (doesn't get more sensitive
     *   as filter converges, preventing the instability feedback loop)
     * - Warmup period protects initial convergence from premature forgetting
     * - No drift decay (drift converges naturally with zero process noise)
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

        // === Adaptive Forgetting (absolute threshold, after warmup only) ===
        // Only apply after warmup to let the filter converge first.
        // Uses absolute residual compared to measurement uncertainty (sqrt(variance) ≈ RTT/2),
        // which naturally scales with network conditions without becoming more sensitive
        // as the filter converges (the key fix for cell network instability).
        if (measurementCount >= WARMUP_MEASUREMENTS) {
            val absResidual = kotlin.math.abs(innovation)
            val maxError = sqrt(variance)
            if (absResidual > FORGETTING_THRESHOLD * maxError) {
                val lambdaSquared = FORGETTING_FACTOR * FORGETTING_FACTOR
                p00 = p00New * lambdaSquared
                p01 = p01New * lambdaSquared
                p10 = p10New * lambdaSquared
                p11 = p11New * lambdaSquared
            } else {
                p00 = p00New
                p01 = p01New
                p10 = p10New
                p11 = p11New
            }
        } else {
            // During warmup: no forgetting, let the filter converge undisturbed
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
        newDrift = newDrift.coerceIn(-MAX_DRIFT, MAX_DRIFT)

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
