package com.sendspindroid.sendspin.protocol.timesync

import android.util.Log
import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.TimeMeasurement
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages NTP-style time synchronization with best-of-N burst measurements.
 *
 * Sends bursts of time sync packets and picks the measurement with lowest RTT
 * (least network congestion) to feed to the Kalman filter.
 *
 * @param timeFilter The Kalman filter to feed measurements to
 * @param sendClientTime Function to send a client/time message
 * @param tag Log tag for debugging
 */
class TimeSyncManager(
    private val timeFilter: SendspinTimeFilter,
    private val sendClientTime: () -> Unit,
    private val tag: String = "TimeSyncManager"
) {
    companion object {
        // RTT-scaled measurement noise: base variance floor (μs²)
        // Ensures minimum uncertainty even on very low-RTT connections.
        // 1,000,000 μs² = 1ms standard deviation floor.
        private const val BASE_MEASUREMENT_VARIANCE = 1_000_000.0

        // Maximum acceptable RTT (μs) - any response with RTT above this is stale/unusable.
        // On cellular networks, old responses from previous bursts can arrive very late,
        // contaminating the current burst with 25+ second RTTs. 10 seconds is generous
        // enough for any real network while rejecting clearly stale responses.
        private const val MAX_ACCEPTABLE_RTT_US = 10_000_000L  // 10 seconds

        // Network quality tracking window
        private const val RTT_HISTORY_SIZE = 15  // ~15 bursts of history

        // Network-aware burst parameters (dynamic overrides of SendSpinProtocol.TimeSync)
        private const val BURST_COUNT_HIGH_JITTER = 15  // More packets on noisy networks
        private const val BURST_COUNT_LOW_JITTER = 5    // Fewer packets on stable networks
        private const val INTERVAL_MS_HIGH_JITTER = 200L   // Faster bursts on noisy networks
        private const val INTERVAL_MS_LOW_JITTER = 500L    // Slower bursts on stable networks

        // Jitter thresholds for switching strategy (microseconds)
        private const val HIGH_JITTER_THRESHOLD_US = 20_000L  // 20ms jitter → aggressive mode
        private const val LOW_JITTER_THRESHOLD_US = 5_000L    // 5ms jitter → conservative mode
    }

    @Volatile
    private var running = false
    private var syncJob: Job? = null

    // NTP-style burst measurement collection
    private val pendingBurstMeasurements = mutableListOf<TimeMeasurement>()
    @Volatile
    private var burstInProgress = false

    // Network quality tracking for adaptive burst strategy
    private val rttHistory = LongArray(RTT_HISTORY_SIZE)
    private var rttHistoryIndex = 0
    private var rttHistoryCount = 0

    // Current adaptive burst parameters
    private var currentBurstCount = SendSpinProtocol.TimeSync.BURST_COUNT
    private var currentIntervalMs = SendSpinProtocol.TimeSync.INTERVAL_MS

    val isRunning: Boolean
        get() = running

    /**
     * Start the continuous time sync loop.
     *
     * @param scope CoroutineScope to run the sync loop in
     */
    fun start(scope: CoroutineScope) {
        if (running) return
        running = true

        syncJob = scope.launch {
            // Initial burst for fast convergence
            sendTimeSyncBurst()

            // Then periodic bursts with adaptive interval
            while (running && isActive) {
                delay(currentIntervalMs)
                if (running) {
                    sendTimeSyncBurst()
                }
            }
        }
    }

    /**
     * Stop the time sync loop.
     */
    fun stop() {
        running = false
        syncJob?.cancel()
        syncJob = null
        synchronized(pendingBurstMeasurements) {
            pendingBurstMeasurements.clear()
            burstInProgress = false
        }
        // Reset network tracking
        rttHistoryIndex = 0
        rttHistoryCount = 0
        currentBurstCount = SendSpinProtocol.TimeSync.BURST_COUNT
        currentIntervalMs = SendSpinProtocol.TimeSync.INTERVAL_MS
    }

    /**
     * Handle a server/time response measurement.
     *
     * If a burst is in progress, the measurement is collected for later selection.
     * Otherwise, it's fed directly to the time filter.
     *
     * @param measurement The time measurement from server/time response
     * @return true if measurement was collected for burst, false if processed immediately
     */
    fun onServerTime(measurement: TimeMeasurement): Boolean {
        synchronized(pendingBurstMeasurements) {
            if (burstInProgress) {
                pendingBurstMeasurements.add(measurement)
                return true
            }
        }

        // Outside burst mode (shouldn't happen normally, but handle gracefully)
        if (measurement.rtt > MAX_ACCEPTABLE_RTT_US) {
            Log.v(tag, "Ignoring stale time response: RTT=${measurement.rtt / 1_000_000}s")
            return false
        }

        val maxError = computeMaxError(measurement.rtt)
        timeFilter.addMeasurement(measurement.offset, maxError, measurement.clientReceived, measurement.rtt)

        if (timeFilter.isReady) {
            Log.v(tag, "Time sync: offset=${timeFilter.offsetMicros}μs, error=${timeFilter.errorMicros}μs")
        }

        return false
    }

    /**
     * Send a burst of time sync packets.
     * The responses will be collected and the best one (lowest RTT) used.
     */
    private suspend fun sendTimeSyncBurst() {
        // Start a new burst
        synchronized(pendingBurstMeasurements) {
            pendingBurstMeasurements.clear()
            burstInProgress = true
        }

        // Send N packets at configured intervals (adaptive burst count)
        repeat(currentBurstCount) {
            if (!running) return
            sendClientTime()
            delay(SendSpinProtocol.TimeSync.BURST_DELAY_MS)
        }

        // Wait a bit for final responses to arrive
        delay(SendSpinProtocol.TimeSync.BURST_DELAY_MS * 2)

        // Process the burst results
        processBurstResults()
    }

    /**
     * Process collected burst measurements and pick the best one.
     * Best = lowest RTT (least network congestion).
     */
    private fun processBurstResults() {
        synchronized(pendingBurstMeasurements) {
            burstInProgress = false

            if (pendingBurstMeasurements.isEmpty()) {
                Log.w(tag, "No time sync responses received in burst")
                return
            }

            // Filter out stale measurements (from previous bursts arriving late)
            val validMeasurements = pendingBurstMeasurements.filter { it.rtt < MAX_ACCEPTABLE_RTT_US }
            if (validMeasurements.isEmpty()) {
                Log.w(tag, "All ${pendingBurstMeasurements.size} responses had RTT > ${MAX_ACCEPTABLE_RTT_US / 1_000_000}s - skipping burst")
                pendingBurstMeasurements.clear()
                return
            }

            // Find measurement with lowest RTT from valid set
            val best = validMeasurements.minByOrNull { it.rtt }!!

            // RTT-scaled measurement noise: R = BASE + (rtt/2)²
            val maxError = computeMaxError(best.rtt)

            // Track RTT for network-aware burst adaptation
            recordRtt(best.rtt)
            updateBurstStrategy()

            val staleCount = pendingBurstMeasurements.size - validMeasurements.size
            Log.v(tag, "Time sync burst: ${validMeasurements.size}/$currentBurstCount responses" +
                    (if (staleCount > 0) " ($staleCount stale rejected)" else "") +
                    ", best RTT=${best.rtt}μs, offset=${best.offset}μs")

            // Feed only the best measurement to the Kalman filter (including RTT for 4D mode)
            val accepted = timeFilter.addMeasurement(best.offset, maxError, best.clientReceived, best.rtt)

            if (timeFilter.isReady) {
                Log.v(tag, "Time sync: offset=${timeFilter.offsetMicros}μs, error=${timeFilter.errorMicros}μs, " +
                        "drift=${String.format("%.3f", timeFilter.driftPpm)}ppm" +
                        if (!accepted) " [rejected]" else "")
            }

            pendingBurstMeasurements.clear()
        }
    }

    /**
     * Compute RTT-scaled measurement noise (maxError) using quadratic scaling.
     *
     * Instead of linear R = rtt/2, uses R = sqrt(BASE + (rtt/2)²).
     * This more aggressively discounts high-RTT measurements on cellular networks
     * while maintaining a minimum uncertainty floor for very fast connections.
     *
     * When squared by the filter: variance = BASE + rtt²/4
     * - WiFi (10ms RTT):  variance = 1ms² + 25ms² = 26ms² (~5.1ms std dev)
     * - Cell (100ms RTT): variance = 1ms² + 2500ms² = 2501ms² (~50ms std dev)
     */
    private fun computeMaxError(rtt: Long): Long {
        val rttHalf = rtt.toDouble() / 2.0
        return sqrt(BASE_MEASUREMENT_VARIANCE + rttHalf * rttHalf).toLong().coerceAtLeast(1L)
    }

    /**
     * Record a best-of-burst RTT for network quality tracking.
     */
    private fun recordRtt(rtt: Long) {
        rttHistory[rttHistoryIndex] = rtt
        rttHistoryIndex = (rttHistoryIndex + 1) % RTT_HISTORY_SIZE
        if (rttHistoryCount < RTT_HISTORY_SIZE) rttHistoryCount++
    }

    /**
     * Update burst strategy based on observed network jitter.
     *
     * Jitter is measured as the interquartile range (IQR) of recent RTTs,
     * which is robust to outliers. Based on jitter level:
     * - High jitter (>20ms): More packets per burst, faster interval
     * - Low jitter (<5ms): Fewer packets, slower interval (saves battery)
     * - Medium: Use default parameters
     */
    private fun updateBurstStrategy() {
        if (rttHistoryCount < 5) return  // Need minimum history

        val count = minOf(rttHistoryCount, RTT_HISTORY_SIZE)
        val sorted = LongArray(count)
        for (i in 0 until count) {
            sorted[i] = rttHistory[i]
        }
        sorted.sort()

        // Compute IQR as jitter estimate (robust to outliers)
        val q1 = sorted[count / 4]
        val q3 = sorted[(count * 3) / 4]
        val jitter = q3 - q1

        when {
            jitter > HIGH_JITTER_THRESHOLD_US -> {
                // High jitter: aggressive sampling to catch low-jitter windows
                currentBurstCount = BURST_COUNT_HIGH_JITTER
                currentIntervalMs = INTERVAL_MS_HIGH_JITTER
            }
            jitter < LOW_JITTER_THRESHOLD_US -> {
                // Low jitter: conservative sampling to save battery
                currentBurstCount = BURST_COUNT_LOW_JITTER
                currentIntervalMs = INTERVAL_MS_LOW_JITTER
            }
            else -> {
                // Medium jitter: use defaults
                currentBurstCount = SendSpinProtocol.TimeSync.BURST_COUNT
                currentIntervalMs = SendSpinProtocol.TimeSync.INTERVAL_MS
            }
        }
    }
}
