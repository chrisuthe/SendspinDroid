package com.sendspindroid.sendspin

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Configurable Kalman filter dimensions for time synchronization.
 *
 * - **D2**: Tracks offset + drift (stable WiFi - fast convergence)
 * - **D3**: Adds acceleration tracking (handles thermal drift over time)
 * - **D4**: Adds RTT tracking (network-aware, detects network changes)
 */
enum class FilterDimension {
    D2,  // offset + drift (original implementation)
    D3,  // + acceleration (drift rate of change)
    D4   // + RTT tracking (network baseline)
}

/**
 * Kalman filter for time synchronization between client and server.
 *
 * Implements a configurable Kalman filter (2D/3D/4D) tracking:
 * - offset: Time difference between client and server clocks (microseconds)
 * - drift: Rate of clock divergence (microseconds per microsecond)
 * - acceleration (3D/4D): Rate of drift change (μs/s²)
 * - expectedRtt (4D): Network baseline RTT for anomaly detection (μs)
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
class SendspinTimeFilter(
    initialDimension: FilterDimension = FilterDimension.D2
) {

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
        // BASE_PROCESS_NOISE_OFFSET: Base variance for adaptive process noise (μs²/s)
        // ----------------------------------------------------------------------------
        // This is the baseline Q_offset value. The actual process noise is dynamically
        // scaled based on observed innovation variance vs expected variance.
        //
        // On stable WiFi: adaptive Q stays near BASE (smooth tracking)
        // On jittery cellular: adaptive Q rises to BASE * ADAPTIVE_Q_MAX (faster adaptation)
        // During handoff: Q spikes to handle step changes, then settles back
        // ----------------------------------------------------------------------------
        private const val BASE_PROCESS_NOISE_OFFSET = 100.0

        // Adaptive process noise bounds (multipliers on BASE_PROCESS_NOISE_OFFSET)
        private const val ADAPTIVE_Q_MIN = 0.5    // Minimum: half of base (very stable network)
        private const val ADAPTIVE_Q_MAX = 5.0    // Maximum: 5x base (high-jitter/handoff)
        private const val INNOVATION_WINDOW_SIZE = 20  // ~5 seconds at 4Hz burst rate

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
        // the filter has enough information for reliable time conversion.
        //
        // Value rationale: 4 measurements provide:
        //   - 1st measurement: Initializes offset
        //   - 2nd measurement: First drift estimate
        //   - 3rd-4th measurements: Kalman filter converges toward true offset
        //
        // With only 2 measurements, the offset may still be off by 50-100ms due
        // to RTT asymmetry in the first samples. 4 measurements give the Kalman
        // filter enough iterations to reduce uncertainty significantly.
        //
        // At ~500ms burst interval, this adds ~1 second before playback starts,
        // but ensures much better initial sync accuracy (avoids "starting too
        // far apart" issue where playback starts out of sync and must converge).
        //
        // If too low (2): Playback may start with poor offset, needing large
        //                 corrections as filter converges.
        // If too high: Longer delay before audio starts.
        // ----------------------------------------------------------------------------
        private const val MIN_MEASUREMENTS = 4

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

        // ============================================================================
        // 3D/4D FILTER CONSTANTS
        // ============================================================================

        // ----------------------------------------------------------------------------
        // PROCESS_NOISE_ACCEL: Variance for acceleration estimate ((μs/s²)²/s)
        // ----------------------------------------------------------------------------
        // Physical meaning: Models how much the drift rate changes over time due
        // to thermal effects on the crystal oscillator.
        //
        // Value rationale: 0.1 from windowsSpin - conservative value that allows
        // slow tracking of thermal drift without over-fitting to noise.
        // ----------------------------------------------------------------------------
        private const val PROCESS_NOISE_ACCEL = 0.1

        // ----------------------------------------------------------------------------
        // PROCESS_NOISE_RTT: Variance for RTT baseline estimate (μs²/s)
        // ----------------------------------------------------------------------------
        // Physical meaning: Models network stability - how much we expect the
        // baseline RTT to change over time.
        //
        // Value rationale: 1000.0 allows slow adaptation to changing network
        // conditions while being stable enough to detect genuine changes.
        // ----------------------------------------------------------------------------
        private const val PROCESS_NOISE_RTT = 1000.0

        // ----------------------------------------------------------------------------
        // ACCEL_DECAY: Acceleration decay factor toward zero (per update)
        // ----------------------------------------------------------------------------
        // Physical meaning: Acceleration is a transient phenomenon - thermal
        // changes eventually stabilize. This decay pulls acceleration toward
        // zero over time.
        //
        // Value rationale: 0.92 means acceleration halves every ~8 updates
        // (~2 seconds at 4Hz). Fast enough to respond to thermal events,
        // slow enough to not oscillate.
        // ----------------------------------------------------------------------------
        private const val ACCEL_DECAY = 0.92

        // ----------------------------------------------------------------------------
        // RTT_DECAY: RTT baseline decay/adaptation rate (per update)
        // ----------------------------------------------------------------------------
        // Physical meaning: How quickly the RTT baseline adapts to new values.
        // Higher = more stable baseline, slower to adapt.
        //
        // Value rationale: 0.98 provides very slow adaptation, suitable for
        // detecting network route changes rather than tracking jitter.
        // ----------------------------------------------------------------------------
        private const val RTT_DECAY = 0.98

        // ----------------------------------------------------------------------------
        // RTT_DEVIATION_THRESHOLD: Multiplier for detecting network changes
        // ----------------------------------------------------------------------------
        // Physical meaning: When measured RTT exceeds expected by this many
        // standard deviations, we suspect a network change.
        //
        // Value rationale: 3.0 = 3 sigma, catches genuine changes while
        // ignoring normal jitter.
        // ----------------------------------------------------------------------------
        private const val RTT_DEVIATION_THRESHOLD = 3.0

        // ----------------------------------------------------------------------------
        // MIN_RTT_DEVIATION_US: Minimum RTT change to trigger network detection
        // ----------------------------------------------------------------------------
        // Physical meaning: Absolute floor for RTT deviation detection. Even if
        // the statistical threshold (3 sigma) is exceeded, we don't trigger unless
        // the absolute deviation exceeds this value.
        //
        // Value rationale: 10,000 μs (10ms) is well above normal WiFi jitter
        // (~1-3ms) but catches genuine route changes or network switches.
        // Without this floor, normal jitter triggers constant resets when p33
        // stabilizes to a small value.
        // ----------------------------------------------------------------------------
        private const val MIN_RTT_DEVIATION_US = 10_000.0

        // ----------------------------------------------------------------------------
        // INITIAL_RTT_VARIANCE: Initial uncertainty for RTT tracking (μs²)
        // ----------------------------------------------------------------------------
        // Physical meaning: Starting variance for RTT estimate. High value
        // allows quick learning of actual network RTT.
        //
        // Value rationale: 1e8 = ±10ms standard deviation, reasonable for
        // unknown network conditions.
        // ----------------------------------------------------------------------------
        private const val INITIAL_RTT_VARIANCE = 1e8

        private const val TAG = "SendspinTimeFilter"

        // ----------------------------------------------------------------------------
        // Convergence-Aware Warmup: Ends based on filter state, not fixed count
        // ----------------------------------------------------------------------------
        // MIN_WARMUP: Absolute minimum before forgetting can activate (prevents
        //   premature activation while Kalman gain is still large)
        // MAX_WARMUP: Absolute maximum before we force-enable forgetting (prevents
        //   indefinite warmup on very noisy networks)
        // WARMUP_CONVERGENCE_THRESHOLD_US: If error drops below this, warmup ends
        //   early (filter has converged, safe to enable adaptive mechanisms)
        //
        // Behavior:
        //   WiFi: Warmup ends at ~20 measurements (~5s) when error < threshold
        //   Cellular: Warmup may extend to 100 measurements (~25s) for convergence
        // ----------------------------------------------------------------------------
        private const val MIN_WARMUP = 20
        private const val MAX_WARMUP = 100
        private const val WARMUP_CONVERGENCE_THRESHOLD_US = 15_000L  // 15ms

        // ----------------------------------------------------------------------------
        // Outlier Pre-Rejection: Protects filter from cellular spikes
        // ----------------------------------------------------------------------------
        // Measurements that deviate too far from recent history are rejected before
        // reaching the Kalman filter. Uses median + IQR for robust statistics.
        //
        // OUTLIER_WINDOW_SIZE: Number of recent accepted offsets to track
        // OUTLIER_IQR_MULTIPLIER: How many IQRs from median to allow
        // MIN_OUTLIER_MEASUREMENTS: Accept everything during early warmup
        // ----------------------------------------------------------------------------
        private const val OUTLIER_WINDOW_SIZE = 10
        private const val OUTLIER_IQR_MULTIPLIER = 3.0
        private const val MIN_OUTLIER_MEASUREMENTS = 5

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

    // Current filter dimension
    private var dimension: FilterDimension = initialDimension

    // State vector: [offset, drift, acceleration (3D/4D), expectedRtt (4D)]
    private var offset: Double = 0.0
    private var drift: Double = 0.0
    private var acceleration: Double = 0.0      // 3D/4D: drift rate of change (μs/s²)
    private var expectedRtt: Double = 0.0       // 4D: network baseline RTT (μs)

    // Covariance matrix (up to 4x4, stored as individual elements)
    // 2D uses p00, p01, p10, p11
    // 3D adds p02, p12, p20, p21, p22
    // 4D adds p03, p13, p23, p30, p31, p32, p33
    private var p00: Double = Double.MAX_VALUE  // offset variance
    private var p01: Double = 0.0               // offset-drift covariance
    private var p10: Double = 0.0               // drift-offset covariance
    private var p11: Double = 1e-6              // drift variance
    // 3D/4D additions
    private var p02: Double = 0.0               // offset-acceleration covariance
    private var p12: Double = 0.0               // drift-acceleration covariance
    private var p20: Double = 0.0               // acceleration-offset covariance
    private var p21: Double = 0.0               // acceleration-drift covariance
    private var p22: Double = 1e-8              // acceleration variance
    // 4D additions
    private var p03: Double = 0.0               // offset-RTT covariance
    private var p13: Double = 0.0               // drift-RTT covariance
    private var p23: Double = 0.0               // acceleration-RTT covariance
    private var p30: Double = 0.0               // RTT-offset covariance
    private var p31: Double = 0.0               // RTT-drift covariance
    private var p32: Double = 0.0               // RTT-acceleration covariance
    private var p33: Double = INITIAL_RTT_VARIANCE  // RTT variance

    // RTT reliability tracking (4D mode)
    private var rttMeasurementCount: Int = 0
    private var networkChangeTriggerCount: Int = 0

    // Timing state
    private var lastUpdateTime: Long = 0
    private var measurementCount: Int = 0

    // Adaptive process noise: tracks innovation (residual) magnitudes
    private val innovationWindow = DoubleArray(INNOVATION_WINDOW_SIZE)
    private var innovationWindowIndex = 0
    private var innovationWindowCount = 0
    private var adaptiveProcessNoise = BASE_PROCESS_NOISE_OFFSET

    // Outlier pre-rejection: tracks recent accepted offset measurements
    private val recentOffsets = DoubleArray(OUTLIER_WINDOW_SIZE)
    private var recentOffsetsIndex = 0
    private var recentOffsetsCount = 0
    private var rejectedCount = 0  // Consecutive rejections (for forced acceptance)

    // Baseline time for relative calculations - prevents drift accumulation over long periods
    // Set when first measurement is received, used as reference point for time conversions
    private var baselineClientTime: Long = 0

    // Static delay offset for speaker synchronization (GroupSync calibration)
    // Positive = delay playback (plays later), Negative = advance (plays earlier)
    private var staticDelayMicros: Long = 0

    // Convergence tracking
    private var convergenceTimeMs: Long = 0L       // Time to reach isConverged
    private var firstMeasurementTimeMs: Long = 0L  // Timestamp of first measurement
    private var hasLoggedConvergence: Boolean = false

    // Stability tracking (innovation variance ratio)
    private var stabilityScore: Double = 1.0  // Should be ~1.0 for well-tuned filter

    // Frozen state for reconnection - preserves sync across network drops
    @Volatile private var frozenState: FrozenState? = null

    private data class FrozenState(
        val offset: Double,
        val drift: Double,
        val acceleration: Double,
        val expectedRtt: Double,
        val p00: Double,
        val p01: Double,
        val p10: Double,
        val p11: Double,
        val p02: Double,
        val p12: Double,
        val p20: Double,
        val p21: Double,
        val p22: Double,
        val p03: Double,
        val p13: Double,
        val p23: Double,
        val p30: Double,
        val p31: Double,
        val p32: Double,
        val p33: Double,
        val measurementCount: Int,
        val baselineClientTime: Long,
        val recentOffsets: DoubleArray,
        val recentOffsetsIndex: Int,
        val recentOffsetsCount: Int,
        val dimension: FilterDimension
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

    // ========== 3D/4D Properties ==========

    /**
     * Current filter dimension (2D, 3D, or 4D).
     */
    val currentDimension: FilterDimension
        get() = dimension

    /**
     * Current acceleration estimate in μs/s² (3D/4D only).
     * Represents the rate of drift change over time.
     */
    val accelerationUs2: Double
        get() = if (dimension >= FilterDimension.D3) acceleration else 0.0

    /**
     * Expected RTT baseline in microseconds (4D only).
     * Network baseline used to detect network changes.
     */
    val expectedRttUs: Long
        get() = if (dimension == FilterDimension.D4) expectedRtt.toLong() else 0L

    /**
     * Whether the RTT estimate is reliable (4D only).
     * Requires sufficient measurements and reasonable variance.
     */
    val isRttReliable: Boolean
        get() = dimension == FilterDimension.D4 &&
                rttMeasurementCount >= MIN_MEASUREMENTS_FOR_CONVERGENCE &&
                p33 < INITIAL_RTT_VARIANCE / 10

    /**
     * Number of network change triggers detected (4D only).
     */
    val networkChangeTriggers: Int
        get() = networkChangeTriggerCount

    /**
     * Time to reach convergence in milliseconds.
     * 0 if not yet converged.
     */
    val convergenceTimeMillis: Long
        get() = convergenceTimeMs

    /**
     * Stability score (innovation variance ratio).
     * Should be ~1.0 for a well-tuned filter.
     * < 1 = filter is over-responsive, > 1 = filter is too sluggish.
     */
    val stability: Double
        get() = stabilityScore

    /**
     * Reset the filter to initial state.
     */
    fun reset() {
        offset = 0.0
        drift = 0.0
        acceleration = 0.0
        expectedRtt = 0.0
        p00 = Double.MAX_VALUE
        p01 = 0.0
        p10 = 0.0
        p11 = 1e-6
        // 3D additions
        p02 = 0.0
        p12 = 0.0
        p20 = 0.0
        p21 = 0.0
        p22 = 1e-8
        // 4D additions
        p03 = 0.0
        p13 = 0.0
        p23 = 0.0
        p30 = 0.0
        p31 = 0.0
        p32 = 0.0
        p33 = INITIAL_RTT_VARIANCE
        lastUpdateTime = 0
        measurementCount = 0
        baselineClientTime = 0
        // Reset adaptive mechanisms
        innovationWindowIndex = 0
        innovationWindowCount = 0
        adaptiveProcessNoise = BASE_PROCESS_NOISE_OFFSET
        recentOffsetsIndex = 0
        recentOffsetsCount = 0
        rejectedCount = 0
        // Reset 3D/4D tracking
        rttMeasurementCount = 0
        networkChangeTriggerCount = 0
        // Reset convergence tracking
        convergenceTimeMs = 0
        firstMeasurementTimeMs = 0
        hasLoggedConvergence = false
        stabilityScore = 1.0
    }

    /**
     * Whether the filter has frozen state that can be restored.
     */
    val isFrozen: Boolean
        get() = frozenState != null

    /**
     * Change the filter dimension at runtime.
     *
     * When switching dimensions:
     * - Preserves current offset estimate for continuity
     * - Resets covariance to high uncertainty for rapid re-convergence
     * - Audio continues from buffer during ~3s re-convergence period
     *
     * @param newDimension The new filter dimension to use
     */
    fun setDimension(newDimension: FilterDimension) {
        if (newDimension == dimension) return

        val oldDimension = dimension
        val preservedOffset = offset
        val preservedDrift = drift

        Log.i(TAG, "Filter dimension: $oldDimension -> $newDimension, preserving offset=${preservedOffset.toLong()}us")

        dimension = newDimension

        // Reset covariance to high uncertainty while preserving state estimates
        p00 = Double.MAX_VALUE
        p01 = 0.0
        p10 = 0.0
        p11 = 1e-6

        // Reset 3D covariances
        p02 = 0.0
        p12 = 0.0
        p20 = 0.0
        p21 = 0.0
        p22 = 1e-8
        acceleration = 0.0

        // Reset 4D covariances
        p03 = 0.0
        p13 = 0.0
        p23 = 0.0
        p30 = 0.0
        p31 = 0.0
        p32 = 0.0
        p33 = INITIAL_RTT_VARIANCE
        expectedRtt = 0.0
        rttMeasurementCount = 0

        // Preserve the best estimate we have
        offset = preservedOffset
        drift = preservedDrift

        // Reset convergence tracking for new dimension
        hasLoggedConvergence = false
        convergenceTimeMs = 0
        stabilityScore = 1.0

        // Reset adaptive mechanisms
        innovationWindowIndex = 0
        innovationWindowCount = 0
        adaptiveProcessNoise = BASE_PROCESS_NOISE_OFFSET
    }

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
            acceleration = acceleration,
            expectedRtt = expectedRtt,
            p00 = p00,
            p01 = p01,
            p10 = p10,
            p11 = p11,
            p02 = p02,
            p12 = p12,
            p20 = p20,
            p21 = p21,
            p22 = p22,
            p03 = p03,
            p13 = p13,
            p23 = p23,
            p30 = p30,
            p31 = p31,
            p32 = p32,
            p33 = p33,
            measurementCount = measurementCount,
            baselineClientTime = baselineClientTime,
            recentOffsets = recentOffsets.copyOf(),
            recentOffsetsIndex = recentOffsetsIndex,
            recentOffsetsCount = recentOffsetsCount,
            dimension = dimension
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
        acceleration = frozen.acceleration
        expectedRtt = frozen.expectedRtt
        dimension = frozen.dimension

        // Increase covariance by 10x to allow faster adaptation
        // while preserving the general sync estimate
        p00 = frozen.p00 * 10.0
        p01 = frozen.p01 * 3.0
        p10 = frozen.p10 * 3.0
        p11 = frozen.p11 * 10.0
        // 3D covariances
        p02 = frozen.p02 * 3.0
        p12 = frozen.p12 * 3.0
        p20 = frozen.p20 * 3.0
        p21 = frozen.p21 * 3.0
        p22 = frozen.p22 * 10.0
        // 4D covariances
        p03 = frozen.p03 * 3.0
        p13 = frozen.p13 * 3.0
        p23 = frozen.p23 * 3.0
        p30 = frozen.p30 * 3.0
        p31 = frozen.p31 * 3.0
        p32 = frozen.p32 * 3.0
        p33 = frozen.p33 * 10.0

        measurementCount = frozen.measurementCount
        baselineClientTime = frozen.baselineClientTime

        // Restore outlier rejection state (offsets are still valid reference)
        frozen.recentOffsets.copyInto(recentOffsets)
        recentOffsetsIndex = frozen.recentOffsetsIndex
        recentOffsetsCount = frozen.recentOffsetsCount
        rejectedCount = 0

        // Reset innovation window (network conditions may have changed)
        innovationWindowIndex = 0
        innovationWindowCount = 0
        adaptiveProcessNoise = BASE_PROCESS_NOISE_OFFSET

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
     * Includes outlier pre-rejection: measurements that deviate significantly from
     * recent history are rejected before reaching the Kalman filter, protecting
     * against cellular congestion spikes and handoff transients.
     *
     * @param measurementOffset The measured offset in microseconds
     * @param maxError The maximum error (uncertainty) in microseconds
     * @param clientTimeMicros The client timestamp when measurement was taken
     * @param rtt Optional round-trip time in microseconds (used for 4D mode)
     * @return true if measurement was accepted, false if rejected as outlier
     */
    fun addMeasurement(
        measurementOffset: Long,
        maxError: Long,
        clientTimeMicros: Long,
        rtt: Long = 0L
    ): Boolean {
        val measurement = measurementOffset.toDouble()
        val variance = (maxError.toDouble() * maxError).coerceAtLeast(1.0)

        // Track first measurement time for convergence timing
        if (measurementCount == 0) {
            firstMeasurementTimeMs = System.currentTimeMillis()
        }

        when (measurementCount) {
            0 -> {
                // First measurement - initialize offset directly
                offset = measurement
                p00 = variance
                lastUpdateTime = clientTimeMicros
                baselineClientTime = clientTimeMicros  // Set baseline for relative calculations
                measurementCount = 1
                recordAcceptedOffset(measurement)

                // Initialize RTT baseline for 4D mode
                if (dimension == FilterDimension.D4 && rtt > 0) {
                    expectedRtt = rtt.toDouble()
                    rttMeasurementCount = 1
                }
            }
            1 -> {
                // Second measurement - initialize drift covariance but NOT drift value
                // Drift = 0 initially; the Kalman filter will learn it over time.
                // DO NOT calculate drift from (measurement - offset) / dt because:
                // - Measurement noise of ~3ms with dt of ~1s gives drift error of ~3000ppm
                // - Real crystal drift is only 10-50ppm, so noise dominates completely
                // - Starting at 0 and learning is much better than starting wildly wrong
                val dt = (clientTimeMicros - lastUpdateTime).toDouble()
                if (dt > 0) {
                    // Initialize drift covariance based on how much drift could have changed
                    // Use a conservative estimate: 100ppm (0.0001) uncertainty
                    p11 = 0.0001 * 0.0001  // (100ppm)^2 variance
                }
                // Keep drift at 0 (initialized in reset())
                offset = measurement
                p00 = variance
                lastUpdateTime = clientTimeMicros
                measurementCount = 2
                recordAcceptedOffset(measurement)

                // Update RTT baseline for 4D mode
                if (dimension == FilterDimension.D4 && rtt > 0) {
                    expectedRtt = (expectedRtt + rtt) / 2.0
                    rttMeasurementCount++
                }
            }
            else -> {
                // Outlier pre-rejection: check before feeding to Kalman filter
                if (!shouldAcceptMeasurement(measurement, maxError.toDouble())) {
                    rejectedCount++
                    return false
                }
                rejectedCount = 0

                // Steady state - full Kalman update based on dimension
                when (dimension) {
                    FilterDimension.D2 -> kalmanUpdate2D(measurement, variance, clientTimeMicros)
                    FilterDimension.D3 -> kalmanUpdate3D(measurement, variance, clientTimeMicros)
                    FilterDimension.D4 -> kalmanUpdate4D(measurement, variance, clientTimeMicros, rtt)
                }
                recordAcceptedOffset(measurement)

                // Check and log convergence
                checkConvergence()
            }
        }
        return true
    }

    /**
     * Check for convergence and log milestone.
     */
    private fun checkConvergence() {
        if (!hasLoggedConvergence && isConverged) {
            hasLoggedConvergence = true
            convergenceTimeMs = System.currentTimeMillis() - firstMeasurementTimeMs
            Log.i(TAG, "Kalman locked: dim=$dimension, time=${convergenceTimeMs}ms, " +
                    "offset=${offset.toLong()}us (+/-$errorMicros), drift=${String.format("%.2f", driftPpm)}ppm")
        }
    }

    /**
     * Determine if a measurement should be accepted or rejected as an outlier.
     *
     * Uses robust statistics (median + IQR) to detect measurements that are
     * far from the recent accepted history. This protects the Kalman filter
     * from being pulled by cellular congestion spikes (200ms+ outliers).
     *
     * Force-accepts after 3 consecutive rejections to handle genuine step changes
     * (e.g., network route change where ALL measurements shift).
     */
    private fun shouldAcceptMeasurement(measurement: Double, maxError: Double): Boolean {
        // Accept during early warmup - not enough history for outlier detection
        if (recentOffsetsCount < MIN_OUTLIER_MEASUREMENTS) return true

        // Force-accept after consecutive rejections (genuine step change)
        if (rejectedCount >= 3) return true

        val count = minOf(recentOffsetsCount, OUTLIER_WINDOW_SIZE)
        val sorted = DoubleArray(count)
        for (i in 0 until count) {
            sorted[i] = recentOffsets[(recentOffsetsIndex - count + i + OUTLIER_WINDOW_SIZE) % OUTLIER_WINDOW_SIZE]
        }
        sorted.sort()

        val median = if (count % 2 == 0) {
            (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0
        } else {
            sorted[count / 2]
        }

        val q1 = sorted[count / 4]
        val q3 = sorted[(count * 3) / 4]
        val iqr = q3 - q1

        // Threshold: at least RTT-sized window (maxError), or IQR-based
        val threshold = maxOf(OUTLIER_IQR_MULTIPLIER * iqr, maxError)

        return abs(measurement - median) <= threshold
    }

    /**
     * Record an accepted offset measurement in the recent history window.
     */
    private fun recordAcceptedOffset(measurement: Double) {
        recentOffsets[recentOffsetsIndex] = measurement
        recentOffsetsIndex = (recentOffsetsIndex + 1) % OUTLIER_WINDOW_SIZE
        if (recentOffsetsCount < OUTLIER_WINDOW_SIZE) recentOffsetsCount++
    }

    /**
     * Perform 2D Kalman filter prediction and update (offset + drift).
     *
     * Optimizations over the base implementation:
     * - Adaptive process noise: Q_offset scales with observed innovation variance,
     *   automatically responding to network conditions (stable WiFi vs jittery cellular)
     * - Convergence-aware warmup: Forgetting activates when filter converges OR after
     *   MAX_WARMUP, not at a fixed measurement count
     * - Absolute residual threshold for forgetting (prevents instability feedback loop)
     */
    private fun kalmanUpdate2D(measurement: Double, variance: Double, clientTimeMicros: Long) {
        val dt = (clientTimeMicros - lastUpdateTime).toDouble()
        if (dt <= 0) return

        // === Prediction Step ===
        // State prediction: offset_predicted = offset + drift * dt
        val offsetPredicted = offset + drift * dt

        // Covariance prediction: P = F * P * F^T + Q
        // Uses adaptive process noise instead of fixed constant
        val p00New = p00 + 2 * p01 * dt + p11 * dt * dt + adaptiveProcessNoise * dt
        val p01New = p01 + p11 * dt
        val p10New = p10 + p11 * dt
        val p11New = p11 + PROCESS_NOISE_DRIFT * dt

        // === Innovation (Residual) ===
        val innovation = measurement - offsetPredicted

        // === Track Innovation for Adaptive Process Noise ===
        recordInnovation(innovation * innovation, variance)

        // === Adaptive Forgetting (convergence-aware warmup) ===
        if (isWarmupComplete()) {
            val absResidual = abs(innovation)
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

        // Log when filter becomes ready for the first time
        if (measurementCount == MIN_MEASUREMENTS) {
            Log.i(TAG, "Time sync ready: offset=${offset.toLong()}us, error=${errorMicros}us, " +
                    "drift=${String.format("%.3f", driftPpm)}ppm (after $measurementCount measurements)")
        }

        // === Update Adaptive Process Noise ===
        updateAdaptiveProcessNoise()
    }

    /**
     * Perform 3D Kalman filter prediction and update (offset + drift + acceleration).
     *
     * The acceleration term models thermal drift of the crystal oscillator,
     * where the drift rate changes slowly over time.
     *
     * State model:
     * - offset_new = offset + drift * dt + 0.5 * acceleration * dt²
     * - drift_new = drift + acceleration * dt
     * - acceleration_new = acceleration * ACCEL_DECAY (decays toward zero)
     */
    private fun kalmanUpdate3D(measurement: Double, variance: Double, clientTimeMicros: Long) {
        val dt = (clientTimeMicros - lastUpdateTime).toDouble()
        if (dt <= 0) return

        // Scale factor: acceleration is per-second², so we need dt in seconds for accel terms
        val dtSec = dt / 1_000_000.0
        val dtSec2 = dtSec * dtSec

        // === Prediction Step (3D kinematic model) ===
        // offset: μs, drift: dimensionless, acceleration: 1/s (drift change per second)
        val offsetPredicted = offset + drift * dt + 0.5 * acceleration * dt * dtSec
        val driftPredicted = drift + acceleration * dtSec
        val accelPredicted = acceleration * ACCEL_DECAY

        // Covariance prediction: P = F * P * F^T + Q
        // State transition Jacobian F:
        //   [ 1    dt    0.5*dt*dtSec ]
        //   [ 0    1     dtSec        ]
        //   [ 0    0     ACCEL_DECAY  ]
        // p01/p10 terms: p02/p20 contribution uses dtSec (not dt*dtSec) because
        // acceleration affects drift via dtSec, not offset directly
        val p00New = p00 + 2 * p01 * dt + p11 * dt * dt + adaptiveProcessNoise * dt
        val p01New = p01 + p11 * dt + p02 * dtSec
        val p10New = p10 + p11 * dt + p20 * dtSec
        val p11New = p11 + PROCESS_NOISE_DRIFT * dt + 2 * p12 * dtSec + p22 * dtSec2
        val p02New = (p02 + p12 * dtSec) * ACCEL_DECAY
        val p12New = (p12 + p22 * dtSec) * ACCEL_DECAY
        val p20New = (p20 + p21 * dtSec) * ACCEL_DECAY
        val p21New = (p21 + p22 * dtSec) * ACCEL_DECAY
        val p22New = p22 * ACCEL_DECAY * ACCEL_DECAY + PROCESS_NOISE_ACCEL * dtSec

        // === Innovation (Residual) ===
        val innovation = measurement - offsetPredicted

        // === Track Innovation for Adaptive Process Noise ===
        recordInnovation(innovation * innovation, variance)

        // === Adaptive Forgetting ===
        if (isWarmupComplete()) {
            val absResidual = abs(innovation)
            val maxError = sqrt(variance)
            if (absResidual > FORGETTING_THRESHOLD * maxError) {
                val lambdaSquared = FORGETTING_FACTOR * FORGETTING_FACTOR
                p00 = p00New * lambdaSquared
                p01 = p01New * lambdaSquared
                p10 = p10New * lambdaSquared
                p11 = p11New * lambdaSquared
                p02 = p02New * lambdaSquared
                p12 = p12New * lambdaSquared
                p20 = p20New * lambdaSquared
                p21 = p21New * lambdaSquared
                p22 = p22New * lambdaSquared
            } else {
                p00 = p00New; p01 = p01New; p10 = p10New; p11 = p11New
                p02 = p02New; p12 = p12New; p20 = p20New; p21 = p21New; p22 = p22New
            }
        } else {
            p00 = p00New; p01 = p01New; p10 = p10New; p11 = p11New
            p02 = p02New; p12 = p12New; p20 = p20New; p21 = p21New; p22 = p22New
        }

        // === Update Step ===
        val s = p00 + variance
        if (s <= 0) return

        val k0 = p00 / s  // Kalman gain for offset
        val k1 = p10 / s  // Kalman gain for drift
        val k2 = p20 / s  // Kalman gain for acceleration

        // State update
        offset = offsetPredicted + k0 * innovation
        drift = (driftPredicted + k1 * innovation).coerceIn(-MAX_DRIFT, MAX_DRIFT)
        acceleration = accelPredicted + k2 * innovation

        // Covariance update: P = (I - K * H) * P
        // Must use temporary variables to avoid using already-updated values
        val p00Upd = (1 - k0) * p00
        val p01Upd = (1 - k0) * p01
        val p02Upd = (1 - k0) * p02
        val p10Upd = p10 - k1 * p00
        val p11Upd = p11 - k1 * p01
        val p12Upd = p12 - k1 * p02
        val p20Upd = p20 - k2 * p00
        val p21Upd = p21 - k2 * p01
        val p22Upd = p22 - k2 * p02

        p00 = p00Upd; p01 = p01Upd; p02 = p02Upd
        p10 = p10Upd; p11 = p11Upd; p12 = p12Upd
        p20 = p20Upd; p21 = p21Upd; p22 = p22Upd

        lastUpdateTime = clientTimeMicros
        measurementCount++

        // Log when filter becomes ready for the first time
        if (measurementCount == MIN_MEASUREMENTS) {
            Log.i(TAG, "Time sync ready (3D): offset=${offset.toLong()}us, error=${errorMicros}us, " +
                    "drift=${String.format("%.3f", driftPpm)}ppm, accel=${String.format("%.1f", acceleration * 1e6)}us/s2 " +
                    "(after $measurementCount measurements)")
        }

        updateAdaptiveProcessNoise()
    }

    /**
     * Perform 4D Kalman filter prediction and update (offset + drift + acceleration + RTT).
     *
     * The RTT tracking allows detection of network changes. When measured RTT
     * deviates significantly from expected, we inflate covariance to adapt faster.
     *
     * State model:
     * - offset, drift, acceleration: Same as 3D
     * - expectedRtt: Slowly adapts to network baseline, triggers reset on large deviation
     */
    private fun kalmanUpdate4D(
        measurement: Double,
        variance: Double,
        clientTimeMicros: Long,
        rtt: Long
    ) {
        val dt = (clientTimeMicros - lastUpdateTime).toDouble()
        if (dt <= 0) return

        val dtSec = dt / 1_000_000.0

        // === RTT-based network change detection ===
        // Only trigger if deviation exceeds BOTH:
        // 1. Statistical threshold (3 sigma)
        // 2. Absolute minimum (10ms) to ignore normal WiFi jitter
        if (rtt > 0 && rttMeasurementCount > MIN_MEASUREMENTS_FOR_CONVERGENCE) {
            val rttDeviation = abs(rtt.toDouble() - expectedRtt)
            val rttStdDev = sqrt(p33)
            val statisticalThreshold = RTT_DEVIATION_THRESHOLD * rttStdDev
            val effectiveThreshold = maxOf(statisticalThreshold, MIN_RTT_DEVIATION_US)

            if (rttDeviation > effectiveThreshold) {
                // Significant RTT change detected - likely network route change
                networkChangeTriggerCount++
                Log.w(TAG, "Network change (#$networkChangeTriggerCount): RTT ${expectedRtt.toLong()} -> $rtt us (dev=${rttDeviation.toLong()}us > ${effectiveThreshold.toLong()}us), resetting covariance")

                // Inflate covariance significantly to adapt faster
                val inflationFactor = 5.0
                p00 *= inflationFactor
                p11 *= inflationFactor
                p22 *= inflationFactor
                // Don't inflate p33 - let RTT adapt naturally
            }
        }

        // Update RTT baseline (slow adaptation)
        if (rtt > 0) {
            expectedRtt = RTT_DECAY * expectedRtt + (1 - RTT_DECAY) * rtt
            p33 = RTT_DECAY * RTT_DECAY * p33 + PROCESS_NOISE_RTT * dtSec
            rttMeasurementCount++
        }

        // === Prediction Step (same as 3D for offset/drift/accel) ===
        // Scale factor: acceleration is per-second², so we need dt in seconds for accel terms
        val dtSec2 = dtSec * dtSec

        val offsetPredicted = offset + drift * dt + 0.5 * acceleration * dt * dtSec
        val driftPredicted = drift + acceleration * dtSec
        val accelPredicted = acceleration * ACCEL_DECAY

        // Covariance prediction: P = F * P * F^T + Q
        // State transition Jacobian F (same as 3D):
        //   [ 1    dt    0.5*dt*dtSec ]
        //   [ 0    1     dtSec        ]
        //   [ 0    0     ACCEL_DECAY  ]
        // p01/p10 terms: p02/p20 contribution uses dtSec (not dt*dtSec) because
        // acceleration affects drift via dtSec, not offset directly
        val p00New = p00 + 2 * p01 * dt + p11 * dt * dt + adaptiveProcessNoise * dt
        val p01New = p01 + p11 * dt + p02 * dtSec
        val p10New = p10 + p11 * dt + p20 * dtSec
        val p11New = p11 + PROCESS_NOISE_DRIFT * dt + 2 * p12 * dtSec + p22 * dtSec2
        val p02New = (p02 + p12 * dtSec) * ACCEL_DECAY
        val p12New = (p12 + p22 * dtSec) * ACCEL_DECAY
        val p20New = (p20 + p21 * dtSec) * ACCEL_DECAY
        val p21New = (p21 + p22 * dtSec) * ACCEL_DECAY
        val p22New = p22 * ACCEL_DECAY * ACCEL_DECAY + PROCESS_NOISE_ACCEL * dtSec

        // === Innovation ===
        val innovation = measurement - offsetPredicted
        recordInnovation(innovation * innovation, variance)

        // === Adaptive Forgetting ===
        if (isWarmupComplete()) {
            val absResidual = abs(innovation)
            val maxError = sqrt(variance)
            if (absResidual > FORGETTING_THRESHOLD * maxError) {
                val lambdaSquared = FORGETTING_FACTOR * FORGETTING_FACTOR
                p00 = p00New * lambdaSquared
                p01 = p01New * lambdaSquared
                p10 = p10New * lambdaSquared
                p11 = p11New * lambdaSquared
                p02 = p02New * lambdaSquared
                p12 = p12New * lambdaSquared
                p20 = p20New * lambdaSquared
                p21 = p21New * lambdaSquared
                p22 = p22New * lambdaSquared
            } else {
                p00 = p00New; p01 = p01New; p10 = p10New; p11 = p11New
                p02 = p02New; p12 = p12New; p20 = p20New; p21 = p21New; p22 = p22New
            }
        } else {
            p00 = p00New; p01 = p01New; p10 = p10New; p11 = p11New
            p02 = p02New; p12 = p12New; p20 = p20New; p21 = p21New; p22 = p22New
        }

        // === Update Step ===
        val s = p00 + variance
        if (s <= 0) return

        val k0 = p00 / s
        val k1 = p10 / s
        val k2 = p20 / s

        // State update
        offset = offsetPredicted + k0 * innovation
        drift = (driftPredicted + k1 * innovation).coerceIn(-MAX_DRIFT, MAX_DRIFT)
        acceleration = accelPredicted + k2 * innovation

        // Covariance update: P = (I - K * H) * P
        // Must use temporary variables to avoid using already-updated values
        val p00Upd = (1 - k0) * p00
        val p01Upd = (1 - k0) * p01
        val p02Upd = (1 - k0) * p02
        val p10Upd = p10 - k1 * p00
        val p11Upd = p11 - k1 * p01
        val p12Upd = p12 - k1 * p02
        val p20Upd = p20 - k2 * p00
        val p21Upd = p21 - k2 * p01
        val p22Upd = p22 - k2 * p02

        p00 = p00Upd; p01 = p01Upd; p02 = p02Upd
        p10 = p10Upd; p11 = p11Upd; p12 = p12Upd
        p20 = p20Upd; p21 = p21Upd; p22 = p22Upd

        lastUpdateTime = clientTimeMicros
        measurementCount++

        // Log when filter becomes ready for the first time
        if (measurementCount == MIN_MEASUREMENTS) {
            Log.i(TAG, "Time sync ready (4D): offset=${offset.toLong()}us, error=${errorMicros}us, " +
                    "drift=${String.format("%.3f", driftPpm)}ppm, RTT=${expectedRtt.toLong()}us " +
                    "(after $measurementCount measurements)")
        }

        updateAdaptiveProcessNoise()
    }

    /**
     * Convergence-aware warmup check.
     *
     * Warmup is complete when EITHER:
     * - measurementCount >= MIN_WARMUP AND filter error < convergence threshold
     * - measurementCount >= MAX_WARMUP (force-end regardless of convergence)
     *
     * This means WiFi converges and exits warmup quickly (~5s), while cellular
     * stays in warmup longer until it actually converges or hits the max.
     */
    private fun isWarmupComplete(): Boolean {
        if (measurementCount >= MAX_WARMUP) return true
        if (measurementCount < MIN_WARMUP) return false
        return errorMicros < WARMUP_CONVERGENCE_THRESHOLD_US
    }

    /**
     * Record a squared innovation and its associated measurement variance.
     * Used to compute the innovation variance ratio for adaptive process noise.
     */
    private fun recordInnovation(innovationSquared: Double, measurementVariance: Double) {
        // Store the normalized innovation: innovation² / (p00 + R)
        // This ratio should be ~1.0 if the filter model matches reality
        val expectedVariance = p00 + measurementVariance
        val normalizedInnovation = if (expectedVariance > 0) {
            innovationSquared / expectedVariance
        } else {
            1.0
        }
        innovationWindow[innovationWindowIndex] = normalizedInnovation
        innovationWindowIndex = (innovationWindowIndex + 1) % INNOVATION_WINDOW_SIZE
        if (innovationWindowCount < INNOVATION_WINDOW_SIZE) innovationWindowCount++
    }

    /**
     * Update the adaptive process noise based on observed innovation variance.
     *
     * If innovations are consistently larger than expected (ratio > 1), the filter's
     * process noise model is too optimistic — increase Q to track faster.
     * If innovations are smaller than expected (ratio < 1), the filter is over-responsive
     * — decrease Q for smoother tracking.
     *
     * This naturally handles:
     * - Stable WiFi: ratio ~1.0, Q stays near BASE
     * - Jittery cellular: ratio > 1, Q increases for faster adaptation
     * - Handoff events: ratio spikes, Q increases temporarily
     */
    private fun updateAdaptiveProcessNoise() {
        if (innovationWindowCount < 5) return  // Need minimum data

        // Compute mean of normalized innovations (should be ~1.0 for well-tuned filter)
        val count = minOf(innovationWindowCount, INNOVATION_WINDOW_SIZE)
        var sum = 0.0
        for (i in 0 until count) {
            sum += innovationWindow[i]
        }
        val meanRatio = sum / count

        // Update stability score (visible in Stats for Nerds)
        stabilityScore = meanRatio

        // Scale process noise by the ratio, clamped to bounds
        val scaleFactor = meanRatio.coerceIn(ADAPTIVE_Q_MIN, ADAPTIVE_Q_MAX)
        adaptiveProcessNoise = BASE_PROCESS_NOISE_OFFSET * scaleFactor
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
