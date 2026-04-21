package com.sendspindroid.sendspin.latency

/**
 * Measures device output latency (time from AudioTrack.write() to sound
 * leaving the DAC) by cross-referencing write timestamps against DAC
 * timestamp callbacks.
 *
 * Pure Kotlin, no Android dependencies. Takes write events in via
 * [recordWrite] and DAC timestamp events in via [recordDacTimestamp];
 * emits a single [Result] via the callback when the session converges
 * or times out.
 *
 * @param nowNs monotonic clock source (System.nanoTime in production,
 *              a mock in tests).
 */
class OutputLatencyEstimator(
    private val nowNs: () -> Long,
) {
    enum class Status { Idle, Measuring, Converged, TimedOut, Cancelled }

    sealed class Result {
        data class Converged(val latencyMicros: Long, val sampleCount: Int) : Result()
        data class TimedOut(val sampleCount: Int) : Result()
    }

    @Volatile var status: Status = Status.Idle
        private set

    fun start(onResult: (Result) -> Unit) {
        TODO("Task 2")
    }

    fun cancel() {
        TODO("Task 7")
    }

    fun recordWrite(framesWritten: Long, writeTimeNs: Long) {
        TODO("Task 2")
    }

    fun recordDacTimestamp(framePosition: Long, dacTimeNs: Long) {
        TODO("Task 3")
    }
}
