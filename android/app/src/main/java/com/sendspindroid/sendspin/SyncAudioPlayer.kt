package com.sendspindroid.sendspin

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Synchronized audio player for Sendspin protocol.
 *
 * Receives PCM audio chunks with server timestamps and plays them at the correct
 * client time using the Kalman-filtered time offset. Implements 4-tier sync correction
 * to maintain multi-device synchronization.
 *
 * ## Sync Correction Tiers
 * 1. Deadband (< 1ms): No correction needed
 * 2. Sample manipulation (< 15ms): Insert/delete samples
 * 3. Rate adjustment (15-200ms): Speed up/slow down playback
 * 4. Hard resync (> 200ms): Reset playback position
 *
 * ## Architecture
 * ```
 * SendSpinClient ──┬── Audio chunks (timestamped) ──► SyncAudioPlayer
 *                  │                                        │
 *                  └── TimeFilter ◄─────────────────────────┘
 *                         │
 *                    serverToClient()
 * ```
 */
class SyncAudioPlayer(
    private val timeFilter: SendspinTimeFilter,
    private val sampleRate: Int = 48000,
    private val channels: Int = 2,
    private val bitDepth: Int = 16
) {
    companion object {
        private const val TAG = "SyncAudioPlayer"

        // Sync correction thresholds (microseconds)
        private const val DEADBAND_THRESHOLD_US = 1_000L        // 1ms - no correction
        private const val SAMPLE_CORRECTION_THRESHOLD_US = 15_000L  // 15ms - sample insert/delete
        private const val RATE_CORRECTION_THRESHOLD_US = 200_000L   // 200ms - rate adjustment
        // > 200ms triggers hard resync

        // Rate adjustment bounds
        private const val MIN_PLAYBACK_RATE = 0.98f
        private const val MAX_PLAYBACK_RATE = 1.02f

        // Buffer configuration
        private const val BUFFER_HEADROOM_MS = 200  // Schedule audio 200ms ahead
        private const val MIN_BUFFER_MS = 50        // Minimum buffer before playing

        // Smoothing for sync error measurement
        private const val SYNC_ERROR_ALPHA = 0.1    // EMA smoothing factor
    }

    /**
     * Timestamped audio chunk waiting to be played.
     */
    private data class AudioChunk(
        val serverTimeMicros: Long,
        val clientPlayTimeMicros: Long,
        val pcmData: ByteArray,
        val sampleCount: Int
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Audio output
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    // Chunk queue
    private val chunkQueue = ConcurrentLinkedQueue<AudioChunk>()
    private val totalQueuedSamples = AtomicLong(0)

    // Sync tracking
    private var smoothedSyncErrorUs = 0.0
    private var lastChunkServerTime = 0L
    private var streamGeneration = 0  // Incremented on stream/clear to invalidate old chunks

    // Statistics
    private var chunksReceived = 0L
    private var chunksPlayed = 0L
    private var chunksDropped = 0L
    private var syncCorrections = 0L

    // Bytes per sample (e.g., 2 channels * 2 bytes = 4 bytes per sample frame)
    private val bytesPerFrame = channels * (bitDepth / 8)

    // Microseconds per sample frame
    private val microsPerSample = 1_000_000.0 / sampleRate

    /**
     * Initialize the audio player with the specified format.
     */
    fun initialize() {
        if (audioTrack != null) {
            Log.w(TAG, "Already initialized")
            return
        }

        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> {
                Log.e(TAG, "Unsupported channel count: $channels")
                return
            }
        }

        val encoding = when (bitDepth) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            else -> {
                Log.e(TAG, "Unsupported bit depth: $bitDepth")
                return
            }
        }

        // Calculate minimum buffer size
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        // Use larger buffer for scheduling headroom
        val bufferSize = maxOf(minBufferSize * 4, sampleRate * bytesPerFrame) // ~1 second

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            Log.i(TAG, "AudioTrack initialized: ${sampleRate}Hz, ${channels}ch, ${bitDepth}bit, buffer=${bufferSize}bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack", e)
        }
    }

    /**
     * Start playback.
     */
    fun start() {
        if (isPlaying.get()) {
            Log.w(TAG, "Already playing")
            return
        }

        val track = audioTrack
        if (track == null) {
            Log.e(TAG, "AudioTrack not initialized")
            return
        }

        isPlaying.set(true)
        isPaused.set(false)
        track.play()

        // Start the playback loop
        startPlaybackLoop()

        Log.i(TAG, "Playback started")
    }

    /**
     * Pause playback.
     */
    fun pause() {
        isPaused.set(true)
        audioTrack?.pause()
        Log.d(TAG, "Playback paused")
    }

    /**
     * Resume playback.
     */
    fun resume() {
        isPaused.set(false)
        audioTrack?.play()
        Log.d(TAG, "Playback resumed")
    }

    /**
     * Stop playback and clear buffers.
     */
    fun stop() {
        isPlaying.set(false)
        isPaused.set(false)
        audioTrack?.stop()
        audioTrack?.flush()
        chunkQueue.clear()
        totalQueuedSamples.set(0)
        Log.i(TAG, "Playback stopped")
    }

    /**
     * Release all resources.
     */
    fun release() {
        stop()
        scope.cancel()
        audioTrack?.release()
        audioTrack = null
        Log.i(TAG, "Released")
    }

    /**
     * Clear the audio buffer (called on stream/clear or seek).
     */
    fun clearBuffer() {
        streamGeneration++
        chunkQueue.clear()
        totalQueuedSamples.set(0)
        audioTrack?.flush()
        smoothedSyncErrorUs = 0.0
        lastChunkServerTime = 0L
        Log.d(TAG, "Buffer cleared, generation=$streamGeneration")
    }

    /**
     * Queue an audio chunk for playback.
     *
     * @param serverTimeMicros Server timestamp when this audio should play
     * @param pcmData Raw PCM audio data
     */
    fun queueChunk(serverTimeMicros: Long, pcmData: ByteArray) {
        chunksReceived++

        // Wait for time sync to be ready
        if (!timeFilter.isReady) {
            chunksDropped++
            if (chunksDropped % 100 == 1L) {
                Log.v(TAG, "Dropping chunk - time sync not ready (dropped: $chunksDropped)")
            }
            return
        }

        // Convert server time to client time
        val clientPlayTime = timeFilter.serverToClient(serverTimeMicros)

        // Check for discontinuity (new stream or seek)
        if (lastChunkServerTime > 0) {
            val serverGap = serverTimeMicros - lastChunkServerTime
            val expectedGapUs = (pcmData.size.toLong() / bytesPerFrame) * microsPerSample.toLong()

            // If gap is more than 100ms different from expected, log it
            if (abs(serverGap - expectedGapUs) > 100_000) {
                Log.w(TAG, "Discontinuity detected: gap=${serverGap}μs, expected=${expectedGapUs}μs")
            }
        }
        lastChunkServerTime = serverTimeMicros

        // Calculate sample count
        val sampleCount = pcmData.size / bytesPerFrame

        // Create and queue the chunk
        val chunk = AudioChunk(
            serverTimeMicros = serverTimeMicros,
            clientPlayTimeMicros = clientPlayTime,
            pcmData = pcmData,
            sampleCount = sampleCount
        )
        chunkQueue.add(chunk)
        totalQueuedSamples.addAndGet(sampleCount.toLong())

        // Log periodically
        if (chunksReceived % 100 == 0L) {
            val queuedMs = (totalQueuedSamples.get() * 1000) / sampleRate
            Log.d(TAG, "Chunks: received=$chunksReceived, played=$chunksPlayed, dropped=$chunksDropped, queued=${queuedMs}ms")
        }
    }

    /**
     * Main playback loop that writes audio to AudioTrack at the correct time.
     */
    private fun startPlaybackLoop() {
        scope.launch {
            Log.d(TAG, "Playback loop started")

            while (isActive && isPlaying.get()) {
                if (isPaused.get()) {
                    delay(10)
                    continue
                }

                val chunk = chunkQueue.peek()
                if (chunk == null) {
                    // No chunks available, wait a bit
                    delay(5)
                    continue
                }

                // Get current client time
                val nowMicros = System.nanoTime() / 1000

                // How far in the future should this chunk play?
                val delayMicros = chunk.clientPlayTimeMicros - nowMicros

                when {
                    // Too early - wait
                    delayMicros > BUFFER_HEADROOM_MS * 1000L -> {
                        delay(10)
                        continue
                    }

                    // Hard resync needed - too late or too early
                    delayMicros < -RATE_CORRECTION_THRESHOLD_US -> {
                        // Chunk is more than 200ms late - drop it
                        chunkQueue.poll()
                        totalQueuedSamples.addAndGet(-chunk.sampleCount.toLong())
                        chunksDropped++
                        syncCorrections++
                        Log.w(TAG, "Hard resync: dropped chunk ${delayMicros/1000}ms late")
                        continue
                    }

                    delayMicros > RATE_CORRECTION_THRESHOLD_US -> {
                        // Chunk is more than 200ms early - wait more
                        delay(50)
                        continue
                    }

                    // Rate adjustment zone (15-200ms)
                    abs(delayMicros) > SAMPLE_CORRECTION_THRESHOLD_US -> {
                        // Apply rate adjustment
                        applyRateCorrection(delayMicros)
                        playChunk(chunk)
                    }

                    // Sample correction zone (1-15ms)
                    abs(delayMicros) > DEADBAND_THRESHOLD_US -> {
                        // Apply sample-level correction
                        applySampleCorrection(chunk, delayMicros)
                    }

                    // Deadband - play normally
                    else -> {
                        playChunk(chunk)
                    }
                }
            }

            Log.d(TAG, "Playback loop ended")
        }
    }

    /**
     * Write a chunk to AudioTrack.
     */
    private fun playChunk(chunk: AudioChunk) {
        chunkQueue.poll() // Remove from queue
        totalQueuedSamples.addAndGet(-chunk.sampleCount.toLong())

        val track = audioTrack ?: return

        // Write to AudioTrack (blocking if buffer is full)
        val written = track.write(chunk.pcmData, 0, chunk.pcmData.size)
        if (written < 0) {
            Log.e(TAG, "AudioTrack write error: $written")
        } else if (written < chunk.pcmData.size) {
            Log.w(TAG, "Partial write: $written/${chunk.pcmData.size}")
        }

        chunksPlayed++

        // Update sync error tracking
        val nowMicros = System.nanoTime() / 1000
        val actualError = nowMicros - chunk.clientPlayTimeMicros
        smoothedSyncErrorUs = SYNC_ERROR_ALPHA * actualError + (1 - SYNC_ERROR_ALPHA) * smoothedSyncErrorUs
    }

    /**
     * Apply sample-level correction for small timing errors.
     * Inserts silence or skips samples to adjust timing.
     */
    private fun applySampleCorrection(chunk: AudioChunk, delayMicros: Long) {
        val track = audioTrack ?: return

        if (delayMicros > 0) {
            // Chunk is early - insert silence
            val silenceSamples = ((delayMicros * sampleRate) / 1_000_000).toInt()
            if (silenceSamples > 0) {
                val silenceBytes = ByteArray(silenceSamples * bytesPerFrame)
                track.write(silenceBytes, 0, silenceBytes.size)
                syncCorrections++
                Log.v(TAG, "Sample correction: inserted ${silenceSamples} silence samples")
            }
        } else {
            // Chunk is late - skip samples from the beginning
            val skipSamples = ((-delayMicros * sampleRate) / 1_000_000).toInt()
            val skipBytes = (skipSamples * bytesPerFrame).coerceAtMost(chunk.pcmData.size - bytesPerFrame)

            if (skipBytes > 0) {
                // Write remaining data after skipping
                chunkQueue.poll()
                totalQueuedSamples.addAndGet(-chunk.sampleCount.toLong())

                val remaining = chunk.pcmData.size - skipBytes
                if (remaining > 0) {
                    track.write(chunk.pcmData, skipBytes, remaining)
                }
                syncCorrections++
                chunksPlayed++
                Log.v(TAG, "Sample correction: skipped ${skipBytes/bytesPerFrame} samples")
                return
            }
        }

        // Play the chunk normally after correction
        playChunk(chunk)
    }

    /**
     * Apply playback rate adjustment for medium timing errors.
     */
    private fun applyRateCorrection(delayMicros: Long) {
        val track = audioTrack ?: return

        // Calculate rate adjustment
        val rate = if (delayMicros > 0) {
            // Early - slow down
            MAX_PLAYBACK_RATE.coerceAtMost(1.0f + (delayMicros / 1_000_000f) * 0.1f)
        } else {
            // Late - speed up
            MIN_PLAYBACK_RATE.coerceAtLeast(1.0f + (delayMicros / 1_000_000f) * 0.1f)
        }

        try {
            // setPlaybackRate expects samples per second
            val adjustedRate = (sampleRate * rate).toInt()
            track.playbackRate = adjustedRate
            syncCorrections++
            Log.v(TAG, "Rate correction: ${rate}x (delay=${delayMicros/1000}ms)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set playback rate", e)
        }
    }

    /**
     * Reset playback rate to normal.
     */
    private fun resetPlaybackRate() {
        try {
            audioTrack?.playbackRate = sampleRate
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reset playback rate", e)
        }
    }

    /**
     * Get current sync statistics.
     */
    fun getStats(): SyncStats {
        return SyncStats(
            chunksReceived = chunksReceived,
            chunksPlayed = chunksPlayed,
            chunksDropped = chunksDropped,
            syncCorrections = syncCorrections,
            queuedSamples = totalQueuedSamples.get(),
            smoothedSyncErrorUs = smoothedSyncErrorUs.toLong(),
            isPlaying = isPlaying.get()
        )
    }

    data class SyncStats(
        val chunksReceived: Long,
        val chunksPlayed: Long,
        val chunksDropped: Long,
        val syncCorrections: Long,
        val queuedSamples: Long,
        val smoothedSyncErrorUs: Long,
        val isPlaying: Boolean
    )
}
