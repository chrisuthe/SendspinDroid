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
 * @param ringCapacity how many recent writes to retain; must be larger
 *              than the expected lag between write and DAC callback.
 */
class OutputLatencyEstimator(
    private val nowNs: () -> Long,
    private val ringCapacity: Int = DEFAULT_RING_CAPACITY,
) {
    companion object {
        const val DEFAULT_RING_CAPACITY = 64
    }

    enum class Status { Idle, Measuring, Converged, TimedOut, Cancelled }

    sealed class Result {
        data class Converged(val latencyMicros: Long, val sampleCount: Int) : Result()
        data class TimedOut(val sampleCount: Int) : Result()
    }

    // Ring buffer entry: (framesWritten cumulative, writeTimeNs)
    private data class WriteEntry(val framesWritten: Long, val writeTimeNs: Long)

    @Volatile var status: Status = Status.Idle
        private set

    private val lock = Any()
    private var onResult: ((Result) -> Unit)? = null
    private val ring = ArrayDeque<WriteEntry>(DEFAULT_RING_CAPACITY)

    fun start(onResult: (Result) -> Unit) {
        synchronized(lock) {
            if (status != Status.Idle) return
            this.onResult = onResult
            ring.clear()
            status = Status.Measuring
        }
    }

    fun cancel() {
        TODO("Task 7")
    }

    fun recordWrite(framesWritten: Long, writeTimeNs: Long) {
        synchronized(lock) {
            if (status != Status.Measuring) return
            if (ring.size >= ringCapacity) ring.removeFirst()
            ring.addLast(WriteEntry(framesWritten, writeTimeNs))
        }
    }

    fun recordDacTimestamp(framePosition: Long, dacTimeNs: Long) {
        TODO("Task 3")
    }
}
