package com.sendspindroid.sendspin.protocol.timesync

import android.util.Log
import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.TimeMeasurement
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
    @Volatile
    private var running = false
    private var syncJob: Job? = null

    // NTP-style burst measurement collection
    private val pendingBurstMeasurements = mutableListOf<TimeMeasurement>()
    @Volatile
    private var burstInProgress = false

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

            // Then periodic bursts to maintain accuracy
            while (running && isActive) {
                delay(SendSpinProtocol.TimeSync.INTERVAL_MS)
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
        val maxError = measurement.rtt / 2
        timeFilter.addMeasurement(measurement.offset, maxError, measurement.clientReceived)

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

        // Send N packets at configured intervals
        repeat(SendSpinProtocol.TimeSync.BURST_COUNT) {
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

            // Find measurement with lowest RTT
            val best = pendingBurstMeasurements.minByOrNull { it.rtt }
            if (best == null) {
                Log.w(tag, "Failed to find best measurement")
                return
            }

            val maxError = best.rtt / 2

            Log.v(tag, "Time sync burst: ${pendingBurstMeasurements.size}/${SendSpinProtocol.TimeSync.BURST_COUNT} responses, " +
                    "best RTT=${best.rtt}μs, offset=${best.offset}μs")

            // Feed only the best measurement to the Kalman filter
            timeFilter.addMeasurement(best.offset, maxError, best.clientReceived)

            if (timeFilter.isReady) {
                Log.v(tag, "Time sync: offset=${timeFilter.offsetMicros}μs, error=${timeFilter.errorMicros}μs, " +
                        "drift=${String.format("%.3f", timeFilter.driftPpm)}ppm")
            }

            pendingBurstMeasurements.clear()
        }
    }
}
