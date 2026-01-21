package com.sendspindroid.sendspin

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/**
 * Playback state machine for synchronized audio.
 *
 * Follows the Python reference implementation pattern for start gating and reanchoring.
 * This state machine ensures synchronized playback by controlling when audio starts
 * and handling sync errors gracefully.
 *
 * ## State Diagram
 * ```
 *                              ┌─────────────────────────────────────────────────┐
 *                              │                                                 │
 *                              ▼                                                 │
 *                      ┌──────────────┐                                          │
 *          ┌──────────►│ INITIALIZING │◄──────────────────────────────┐          │
 *          │           └──────┬───────┘                               │          │
 *          │                  │ first chunk received                  │          │
 *          │                  │ (queueChunk)                          │          │
 *          │                  ▼                                       │          │
 *          │      ┌───────────────────────┐                           │          │
 *          │      │  WAITING_FOR_START    │◄──────┐                   │          │
 *          │      │  (buffer filling)     │       │                   │          │
 *          │      └───────────┬───────────┘       │                   │          │
 *          │                  │ buffer >= 200ms   │                   │          │
 *          │                  │ AND scheduled     │ reanchor chunk    │          │
 *          │                  │ start time        │ received          │          │
 *          │                  │ reached           │                   │          │
 *          │                  ▼                   │                   │          │
 *          │           ┌──────────────┐     ┌─────┴──────┐            │          │
 *          │           │   PLAYING    │────►│ REANCHORING│────────────┘          │
 *          │           │              │     └────────────┘                       │
 *          │           └──────┬───────┘      large sync error                    │
 *          │                  │              (> 500ms)                           │
 *          │                  │                                                  │
 *          │                  │ connection lost                                  │
 *          │                  │ (enterDraining)                                  │
 *          │                  ▼                                                  │
 *          │           ┌──────────────┐                                          │
 *          │           │   DRAINING   │──────────────────────────────────────────┘
 *          │           │              │  buffer exhausted
 *          │           └──────┬───────┘
 *          │                  │ reconnected (exitDraining)
 *          │                  │ OR new chunks arrive
 *          │                  ▼
 *          │           ┌──────────────┐
 *          └───────────┤   PLAYING    │
 *            stop()    └──────────────┘
 *            clearBuffer()
 * ```
 *
 * ## State Transition Table
 * ```
 * ┌─────────────────────┬─────────────────────┬─────────────────────────────────────────────────┐
 * │ From State          │ To State            │ Trigger / Condition                             │
 * ├─────────────────────┼─────────────────────┼─────────────────────────────────────────────────┤
 * │ INITIALIZING        │ WAITING_FOR_START   │ First audio chunk received in queueChunk()     │
 * │ WAITING_FOR_START   │ PLAYING             │ Buffer >= 200ms AND scheduled start time       │
 * │                     │                     │ reached (handleStartGating)                    │
 * │ PLAYING             │ REANCHORING         │ Sync error > 500ms (triggerReanchor)           │
 * │ PLAYING             │ DRAINING            │ Connection lost (enterDraining called)         │
 * │ REANCHORING         │ INITIALIZING        │ After clearing buffers (triggerReanchor)       │
 * │ REANCHORING         │ WAITING_FOR_START   │ New chunk received during reanchor             │
 * │ DRAINING            │ PLAYING             │ Reconnected (exitDraining called)              │
 * │ DRAINING            │ INITIALIZING        │ Buffer exhausted during drain                  │
 * │ Any State           │ INITIALIZING        │ stop() or clearBuffer() called                 │
 * └─────────────────────┴─────────────────────┴─────────────────────────────────────────────────┘
 * ```
 *
 * ## State Descriptions
 *
 * ### INITIALIZING
 * Initial state. Waiting for the first audio chunk and time synchronization.
 * No audio output occurs. Transitions to WAITING_FOR_START when first chunk arrives.
 *
 * ### WAITING_FOR_START
 * Buffer is being filled with audio chunks. A scheduled start time has been computed
 * based on the first chunk's server timestamp. Waits until:
 * - Buffer has at least 200ms of audio (MIN_BUFFER_BEFORE_START_MS)
 * - Scheduled start time is reached or passed
 * During this state, the scheduled start time is continuously updated as time sync improves.
 *
 * ### PLAYING
 * Active synchronized playback with sample insert/drop corrections.
 * Audio is written to AudioTrack with:
 * - Sync error monitoring (Kalman filtered)
 * - Sample insertion (slow down) or dropping (speed up) to maintain sync
 * - 500ms startup grace period before corrections begin
 *
 * ### REANCHORING
 * Transient state triggered by large sync error (> 500ms).
 * Clears all buffers and resets timing state to recover from severe desync.
 * Has a 5-second cooldown to prevent thrashing. Transitions to INITIALIZING
 * immediately, then to WAITING_FOR_START when new chunk arrives.
 *
 * ### DRAINING
 * Connection lost but buffer contains audio. Continues playing from buffer while
 * reconnection is attempted. Monitors buffer level and notifies via callback:
 * - onBufferLow() when < 1 second remains
 * - onBufferExhausted() when buffer runs out
 * New chunks can still be queued (seamlessly spliced via gap/overlap handling).
 */
enum class PlaybackState {
    /** Waiting for first audio chunk and time sync to be ready. */
    INITIALIZING,

    /** Buffer filling, scheduled start time computed. Waiting for enough buffer and start time. */
    WAITING_FOR_START,

    /** Active synchronized playback with sample insert/drop corrections. */
    PLAYING,

    /** Large sync error exceeded threshold. Resetting timing state to recover. */
    REANCHORING,

    /** Connection lost. Playing from buffer only while reconnecting. */
    DRAINING
}

/**
 * Callback interface for SyncAudioPlayer state changes.
 */
interface SyncAudioPlayerCallback {
    /**
     * Called when the playback state changes.
     */
    fun onPlaybackStateChanged(state: PlaybackState)

    /**
     * Called when buffer is running low during DRAINING state.
     * This is a warning that playback may stop soon if reconnection doesn't succeed.
     *
     * @param remainingMs Remaining buffer duration in milliseconds
     */
    fun onBufferLow(remainingMs: Long) {}

    /**
     * Called when buffer has been exhausted during DRAINING state.
     * Playback will stop - the connection was lost and buffer ran out.
     */
    fun onBufferExhausted() {}
}

/**
 * Synchronized audio player for Sendspin protocol.
 *
 * Receives PCM audio chunks with server timestamps and plays them at the correct
 * client time using the Kalman-filtered time offset. Uses imperceptible sample
 * insert/drop for sync correction (no pitch changes).
 *
 * ## Sync Correction Strategy
 * Instead of rate adjustment (which causes audible pitch changes), we use sample
 * insert/drop which is completely imperceptible:
 * - Behind schedule: Drop frames to catch up (skip input samples)
 * - Ahead of schedule: Insert duplicate frames to slow down
 * - At 48kHz with 2ms error: ~48 corrections/sec = 1 frame every 1000 frames
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
        private const val DEADBAND_THRESHOLD_US = 2_000L        // 2ms - no correction needed
        private const val HARD_RESYNC_THRESHOLD_US = 200_000L   // 200ms - hard resync (drop/skip chunks)

        // Sample insert/drop correction constants (matching Windows SDK for stability)
        private const val MAX_SPEED_CORRECTION = 0.02           // +/-2% max correction rate (was 4%)
        private const val CORRECTION_TARGET_SECONDS = 3.0       // Fix error over 3 seconds (was 2)

        // Startup grace period - no corrections until timing stabilizes (Windows SDK: 500ms)
        private const val STARTUP_GRACE_PERIOD_US = 500_000L    // 500ms grace period

        // Reconnect stabilization period - no corrections after reconnect while Kalman re-converges
        private const val RECONNECT_STABILIZATION_US = 2_000_000L  // 2 seconds

        // Buffer configuration
        private const val BUFFER_HEADROOM_MS = 200  // Schedule audio 200ms ahead
        private const val BUFFER_SIZE_MULTIPLIER = 4  // Multiplier for minimum buffer size

        // Sync error Kalman filter parameters
        // Expected measurement noise in microseconds (5ms jitter)
        private const val SYNC_ERROR_MEASUREMENT_NOISE_US = 5_000L

        // DAC calibration parameters
        private const val MAX_DAC_CALIBRATIONS = 50  // Keep last N calibration pairs
        private const val MIN_CALIBRATION_INTERVAL_US = 10_000L  // Don't calibrate more often than 10ms

        // Sync error update interval
        private const val SYNC_ERROR_UPDATE_INTERVAL = 5  // Update every N chunks

        // Start gating configuration (from Python reference)
        private const val MIN_BUFFER_BEFORE_START_MS = 200  // Wait for 200ms buffer before scheduling
        private const val REANCHOR_THRESHOLD_US = 500_000L  // 500ms error triggers reanchor
        private const val REANCHOR_COOLDOWN_US = 5_000_000L // 5 second cooldown between reanchors

        // Buffer exhaustion thresholds for DRAINING state
        private const val BUFFER_WARNING_MS = 1000L   // Warn when buffer drops below 1 second
        private const val BUFFER_CRITICAL_MS = 200L   // Critical warning at 200ms
        private const val BUFFER_WARNING_INTERVAL_US = 500_000L  // Rate limit warnings to 500ms

        // Playback loop timing (milliseconds)
        private const val STATE_POLL_DELAY_MS = 10L   // Polling interval during state transitions
        private const val BUFFER_EMPTY_DELAY_MS = 5L  // Short delay when buffer is empty/draining
        private const val EARLY_CHUNK_DELAY_MS = 50L  // Wait time when chunk is too early

        // Gap/overlap detection
        private const val GAP_THRESHOLD_US = 10_000L  // 10ms minimum gap before filling with silence
        private const val DISCONTINUITY_THRESHOLD_US = 100_000L  // 100ms gap indicates discontinuity (for logging)

        // Logging and diagnostics
        private const val CHUNK_DROP_LOG_INTERVAL = 100  // Log every Nth dropped chunk when time sync not ready

        // Pre-sync buffering - buffer chunks while waiting for time sync to be ready
        private const val MAX_PENDING_CHUNKS = 500  // ~10 seconds at 48kHz/20ms chunks

        // Coroutine cancellation
        private const val PLAYBACK_LOOP_CANCEL_TIMEOUT_MS = 1000L  // Timeout waiting for playback loop to stop
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

    // Coroutine scope for playback - recreated for each playback session
    private var scope: CoroutineScope? = null
    private var playbackJob: Job? = null

    // Lock for thread-safe state transitions
    private val stateLock = ReentrantLock()

    // Flag to track if release() has been called
    private val isReleased = AtomicBoolean(false)

    // Audio output
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var pausedAtUs: Long = 0L  // Timestamp when pause() was called, for long-pause detection

    // Playback state machine (from Python reference)
    @Volatile private var playbackState = PlaybackState.INITIALIZING
    private var stateCallback: SyncAudioPlayerCallback? = null
    private var scheduledStartLoopTimeUs: Long? = null   // When to start in loop time
    private var firstServerTimestampUs: Long? = null     // First chunk's server timestamp
    private var lastReanchorTimeUs: Long = 0             // Cooldown tracking for reanchor

    // DRAINING state tracking - for seamless reconnection
    private var drainingStartTimeUs: Long = 0            // When we entered DRAINING state
    private var lastBufferWarningTimeUs: Long = 0        // Rate limiting for buffer warnings
    private var stateBeforeDraining: PlaybackState? = null  // State to restore if exitDraining during non-PLAYING
    private var reconnectedAtUs: Long = 0L               // When exitDraining() was called (for stabilization)

    // Chunk queue
    private val chunkQueue = ConcurrentLinkedQueue<AudioChunk>()
    private val totalQueuedSamples = AtomicLong(0)

    // Sync tracking
    private var lastChunkServerTime = 0L
    private var streamGeneration = 0  // Incremented on stream/clear to invalidate old chunks

    // Sync error tracking
    private val audioTimestamp = AudioTimestamp()  // Reusable timestamp object
    private var syncUpdateCounter = 0  // Counter for update interval
    private var totalFramesWritten = 0L  // Total frames written to AudioTrack

    // Playback position tracking (in server timeline)
    @Volatile private var serverTimelineCursor = 0L  // Where we've fed audio up to in server time

    // Simplified sync error tracking (Windows SDK style)
    // syncError = elapsedTime - samplesReadTime
    // Positive = behind (need DROP), Negative = ahead (need INSERT)
    private var playbackStartTimeUs = 0L  // When playback started (calibrated from first AudioTimestamp)
    private var startTimeCalibrated = false  // Has playbackStartTimeUs been calibrated from AudioTimestamp?
    private var samplesReadSinceStart = 0L  // Total samples consumed since playback started
    @Volatile private var syncErrorUs = 0L  // Current sync error (for display)

    // 2D Kalman filter for sync error smoothing (tracks offset + drift)
    // Based on Python reference implementation for optimal noise filtering
    private val syncErrorFilter = SyncErrorFilter(
        measurementNoiseUs = SYNC_ERROR_MEASUREMENT_NOISE_US
    )

    // DAC calibration state - tracks (dacTimeUs, loopTimeUs) pairs for time conversion
    // Used to convert DAC hardware time to loop/system time
    private data class DacCalibration(val dacTimeUs: Long, val loopTimeUs: Long)
    private val dacLoopCalibrations = ArrayDeque<DacCalibration>()
    private var lastDacCalibrationTimeUs = 0L

    // Sample insert/drop correction state (from Python reference)
    private var insertEveryNFrames: Int = 0      // Insert duplicate frame every N frames (slow down)
    private var dropEveryNFrames: Int = 0        // Drop frame every N frames (speed up)
    private var framesUntilNextInsert: Int = 0   // Countdown to next insert
    private var framesUntilNextDrop: Int = 0     // Countdown to next drop
    private var lastOutputFrame: ByteArray = ByteArray(0)  // Last frame written (for duplication)

    // Startup grace period tracking (Windows SDK style)
    // No corrections applied until STARTUP_GRACE_PERIOD_US after entering PLAYING state
    private var playingStateEnteredAtUs = 0L     // When we transitioned to PLAYING state

    // Statistics
    private var chunksReceived = 0L
    private var chunksPlayed = 0L
    private var chunksDropped = 0L
    private var syncCorrections = 0L
    private var framesInserted = 0L
    private var framesDropped = 0L
    private var reanchorCount = 0L        // Count of reanchor events
    private var bufferUnderrunCount = 0L  // Count of times queue was empty during playback

    // Pre-sync chunk buffer - holds chunks received before time sync is ready
    // These will be processed once time sync completes
    private val pendingChunks = mutableListOf<Pair<Long, ByteArray>>()

    // Gap/overlap handling (from Python reference)
    private var expectedNextTimestampUs: Long? = null  // Expected server timestamp of next chunk
    private var gapsFilled = 0L           // Count of gaps filled with silence
    private var gapSilenceMs = 0L         // Total milliseconds of silence inserted
    private var overlapsTrimmed = 0L      // Count of overlaps trimmed
    private var overlapTrimmedMs = 0L     // Total milliseconds of audio trimmed

    // Bytes per sample (e.g., 2 channels * 2 bytes = 4 bytes per sample frame)
    private val bytesPerFrame = channels * (bitDepth / 8)

    // Microseconds per sample frame
    private val microsPerSample = 1_000_000.0 / sampleRate

    /**
     * Initialize the audio player with the specified format.
     */
    fun initialize() {
        if (isReleased.get()) {
            Log.e(TAG, "Cannot initialize - player has been released")
            return
        }

        stateLock.withLock {
            if (audioTrack != null) {
                Log.w(TAG, "Already initialized")
                return
            }
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
        val bufferSize = maxOf(minBufferSize * BUFFER_SIZE_MULTIPLIER, sampleRate * bytesPerFrame) // ~1 second

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

            // Pre-allocate lastOutputFrame buffer for sync correction (avoids GC in audio callback)
            lastOutputFrame = ByteArray(bytesPerFrame)

            Log.i(TAG, "AudioTrack initialized: ${sampleRate}Hz, ${channels}ch, ${bitDepth}bit, buffer=${bufferSize}bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack", e)
        }
    }

    /**
     * Start playback.
     *
     * This method is thread-safe and handles rapid start/stop cycles by ensuring
     * any existing coroutine scope is fully cancelled before creating a new one.
     */
    fun start() {
        if (isReleased.get()) {
            Log.e(TAG, "Cannot start - player has been released")
            return
        }

        stateLock.withLock {
            if (isPlaying.get()) {
                Log.w(TAG, "Already playing")
                return
            }

            val track = audioTrack
            if (track == null) {
                Log.e(TAG, "AudioTrack not initialized")
                return
            }

            // Cancel any existing playback job and scope - this ensures complete cleanup
            // even during rapid start/stop cycles. cancelPlaybackLoop() clears the scope
            // reference before waiting, so we're guaranteed scope is null after this.
            cancelPlaybackLoop()

            // Defensive check: scope should be null after cancelPlaybackLoop()
            // This guards against any potential race condition
            if (scope != null) {
                Log.e(TAG, "BUG: Scope was not null after cancelPlaybackLoop - forcing cleanup")
                scope?.cancel()
                scope = null
            }

            // Create a new scope for this playback session
            // Using SupervisorJob so child failures don't cancel the scope
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            isPlaying.set(true)
            isPaused.set(false)
            track.play()

            // Start the playback loop
            startPlaybackLoop()

            Log.i(TAG, "Playback started")
        }
    }

    /**
     * Pause playback.
     */
    fun pause() {
        stateLock.withLock {
            isPaused.set(true)
            pausedAtUs = System.nanoTime() / 1000
            audioTrack?.pause()
            Log.d(TAG, "Playback paused")
        }
    }

    /**
     * Resume playback.
     *
     * Resets sync state that becomes stale during pause:
     * - DAC calibrations (System.nanoTime() continues advancing during pause)
     * - Sync error filter (pre-pause error is no longer relevant)
     * - Correction schedule (start fresh)
     * - Grace period (allow sync to stabilize after resume)
     *
     * For long pauses (>5 seconds), clears the buffer and reinitializes
     * since buffered chunks will be too stale.
     */
    fun resume() {
        stateLock.withLock {
            if (!isPaused.get()) {
                Log.d(TAG, "resume() called but not paused - ignoring")
                return@withLock
            }

            val nowUs = System.nanoTime() / 1000
            val pauseDurationUs = nowUs - pausedAtUs
            val LONG_PAUSE_THRESHOLD_US = 5_000_000L  // 5 seconds

            if (pauseDurationUs > LONG_PAUSE_THRESHOLD_US) {
                Log.d(TAG, "Long pause detected (${pauseDurationUs / 1000}ms) - clearing stale buffer")
                // Clear buffer and let it refill from server
                chunkQueue.clear()
                totalQueuedSamples.set(0)
                setPlaybackState(PlaybackState.INITIALIZING)
                expectedNextTimestampUs = null
            }

            // Clear stale DAC calibrations - they become invalid during pause
            // because System.nanoTime() continues advancing
            clearDacCalibrations()

            // Reset sync error filter - pre-pause error is no longer relevant
            syncErrorFilter.reset()
            syncErrorUs = 0L

            // Reset correction schedule - start fresh
            insertEveryNFrames = 0
            dropEveryNFrames = 0
            framesUntilNextInsert = 0
            framesUntilNextDrop = 0

            // Reset grace period to allow sync to stabilize after resume
            playingStateEnteredAtUs = nowUs

            isPaused.set(false)
            audioTrack?.play()
            Log.d(TAG, "Playback resumed after ${pauseDurationUs / 1000}ms pause - sync state reset")
        }
    }

    /**
     * Set the playback volume.
     *
     * Note: Volume is now controlled via device STREAM_MUSIC (AudioManager),
     * not per-AudioTrack gain. This method is kept for API compatibility but
     * AudioTrack always plays at full volume. Device volume handles attenuation.
     *
     * @param volume Volume level from 0.0 (mute) to 1.0 (full volume) - ignored
     */
    @Suppress("UNUSED_PARAMETER")
    fun setVolume(volume: Float) {
        // Volume is now controlled via device STREAM_MUSIC, not AudioTrack gain.
        // AudioTrack plays at full volume; device media stream handles attenuation.
        // This follows Spotify/Plexamp best practices for hardware volume button support.
        Log.d(TAG, "setVolume called (ignored - using device volume): $volume")
    }

    /**
     * Stop playback and clear buffers.
     *
     * This method is thread-safe and can be called from any thread.
     * It will wait for the playback loop to finish before returning.
     */
    fun stop() {
        stateLock.withLock {
            // Signal the playback loop to stop
            isPlaying.set(false)
            isPaused.set(false)

            // Cancel the playback coroutine and wait for it to finish
            cancelPlaybackLoop()

            // Now safe to manipulate AudioTrack - playback loop has stopped
            audioTrack?.stop()
            audioTrack?.flush()
            chunkQueue.clear()
            totalQueuedSamples.set(0)

            // Clear pending chunks buffer
            synchronized(pendingChunks) {
                pendingChunks.clear()
            }

            // Reset playback state machine
            setPlaybackState(PlaybackState.INITIALIZING)
            scheduledStartLoopTimeUs = null
            firstServerTimestampUs = null

            Log.i(TAG, "Playback stopped")
        }
    }

    /**
     * Cancel the playback loop coroutine and wait for it to complete.
     *
     * Must be called while holding stateLock or when certain no concurrent access.
     * This method ensures complete cleanup even during rapid start/stop cycles.
     */
    private fun cancelPlaybackLoop() {
        val currentScope = scope
        val job = playbackJob

        // Clear references immediately to prevent race conditions where a new
        // start() call could see stale references
        playbackJob = null
        scope = null

        if (currentScope == null) {
            return
        }

        // Cancel the scope first - this cancels ALL coroutines in the scope,
        // not just the playback job. This is safer than cancelling individual jobs.
        currentScope.cancel()

        // Wait for the job to complete if it was active
        if (job != null && job.isActive) {
            try {
                runBlocking {
                    withTimeoutOrNull(PLAYBACK_LOOP_CANCEL_TIMEOUT_MS) {
                        job.join()
                    } ?: Log.w(TAG, "Playback loop did not stop within timeout - scope was cancelled, coroutines will be cleaned up")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception while waiting for playback loop to stop", e)
            }
        }

        Log.v(TAG, "Playback loop cancelled and cleaned up")
    }

    /**
     * Release all resources.
     *
     * After calling this method, the player cannot be reused.
     * This method is idempotent and thread-safe.
     */
    fun release() {
        if (isReleased.getAndSet(true)) {
            Log.w(TAG, "Already released")
            return
        }

        stateLock.withLock {
            // Stop playback and cancel coroutines (stop() handles this)
            isPlaying.set(false)
            isPaused.set(false)
            cancelPlaybackLoop()

            // Release AudioTrack
            try {
                audioTrack?.stop()
            } catch (e: IllegalStateException) {
                // AudioTrack may already be stopped
                Log.v(TAG, "AudioTrack already stopped during release")
            }
            audioTrack?.release()
            audioTrack = null

            // Clear all buffers and state
            chunkQueue.clear()
            totalQueuedSamples.set(0)
            synchronized(pendingChunks) {
                pendingChunks.clear()
            }
            stateCallback = null

            Log.i(TAG, "Released")
        }
    }

    /**
     * Clear the audio buffer (called on stream/clear or seek).
     *
     * This method is thread-safe. It pauses the playback loop during the clear
     * to prevent concurrent access issues.
     */
    fun clearBuffer() {
        if (isReleased.get()) {
            Log.w(TAG, "Cannot clear buffer - player has been released")
            return
        }

        stateLock.withLock {
            streamGeneration++

            // Reset paused state - we're starting a fresh stream (e.g., after seek)
            // This ensures playback loop will process new chunks even if we were paused
            isPaused.set(false)

            // Clear the chunk queue (thread-safe operation)
            chunkQueue.clear()
            totalQueuedSamples.set(0)

            // Clear pending chunks buffer
            synchronized(pendingChunks) {
                pendingChunks.clear()
            }

            // Only flush AudioTrack if we have one and it's safe to do so
            // The stateLock ensures the playback loop won't be writing during this
            val track = audioTrack
            if (track != null) {
                try {
                    // If playing, pause briefly to safely flush
                    val wasPlaying = isPlaying.get()
                    if (wasPlaying) {
                        track.pause()
                    }
                    track.flush()
                    if (wasPlaying) {
                        track.play()
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Failed to flush AudioTrack during clearBuffer", e)
                }
            }

            lastChunkServerTime = 0L

            // Reset playback state machine
            setPlaybackState(PlaybackState.INITIALIZING)
            scheduledStartLoopTimeUs = null
            firstServerTimestampUs = null
            // Note: lastReanchorTimeUs is NOT reset to maintain cooldown across clears

            // Reset sync error tracking (simplified)
            syncUpdateCounter = 0
            totalFramesWritten = 0L
            serverTimelineCursor = 0L
            playbackStartTimeUs = 0L
            startTimeCalibrated = false
            samplesReadSinceStart = 0L
            syncErrorUs = 0L
            syncErrorFilter.reset()
            clearDacCalibrations()  // Clear DAC calibration history
            playingStateEnteredAtUs = 0L  // Reset grace period

            // Reset sample insert/drop correction state
            insertEveryNFrames = 0
            dropEveryNFrames = 0
            framesUntilNextInsert = 0
            framesUntilNextDrop = 0
            // Clear lastOutputFrame contents but keep the pre-allocated buffer
            lastOutputFrame.fill(0)

            // Reset gap/overlap tracking
            expectedNextTimestampUs = null

            Log.d(TAG, "Buffer cleared, generation=$streamGeneration, state=$playbackState")
        }
    }

    /**
     * Queue an audio chunk for playback.
     *
     * Handles gaps and overlaps in the audio stream following the Python reference:
     * - Gaps: Insert silence to fill gaps larger than GAP_THRESHOLD_US
     * - Overlaps: Trim the start of chunks that overlap with already-queued audio
     *
     * @param serverTimeMicros Server timestamp when this audio should play
     * @param pcmData Raw PCM audio data
     */
    fun queueChunk(serverTimeMicros: Long, pcmData: ByteArray) {
        chunksReceived++

        // Buffer chunks until time sync is ready
        if (!timeFilter.isReady) {
            synchronized(pendingChunks) {
                if (pendingChunks.size < MAX_PENDING_CHUNKS) {
                    pendingChunks.add(Pair(serverTimeMicros, pcmData))
                    if (pendingChunks.size == 1) {
                        Log.d(TAG, "Buffering chunks while waiting for time sync...")
                    }
                } else {
                    chunksDropped++  // Only drop if buffer is full
                    if (chunksDropped % CHUNK_DROP_LOG_INTERVAL == 1L) {
                        Log.w(TAG, "Pending buffer full, dropping chunk (dropped: $chunksDropped)")
                    }
                }
            }
            return
        }

        // Process any pending chunks first (once time sync is ready)
        processPendingChunks()

        // Now process the current chunk
        processChunk(serverTimeMicros, pcmData)
    }

    /**
     * Process pending chunks that were buffered while waiting for time sync.
     * Called when time sync becomes ready.
     */
    private fun processPendingChunks() {
        synchronized(pendingChunks) {
            if (pendingChunks.isNotEmpty()) {
                Log.i(TAG, "Time sync ready, processing ${pendingChunks.size} buffered chunks")
                for ((timestamp, data) in pendingChunks) {
                    processChunk(timestamp, data)
                }
                pendingChunks.clear()
            }
        }
    }

    /**
     * Process a single audio chunk (internal implementation).
     * Handles gap/overlap detection and state machine transitions.
     */
    private fun processChunk(serverTimeMicros: Long, pcmData: ByteArray) {
        // Working copies that may be modified by gap/overlap handling
        var workingServerTimeMicros = serverTimeMicros
        var workingPcmData = pcmData

        // Initialize expected next timestamp on first chunk
        val expectedNext = expectedNextTimestampUs
        if (expectedNext == null) {
            expectedNextTimestampUs = serverTimeMicros
        } else {
            // Handle gap: insert silence to fill the gap
            if (serverTimeMicros > expectedNext) {
                val gapUs = serverTimeMicros - expectedNext

                // Only fill gaps larger than threshold (small gaps are normal network jitter)
                if (gapUs > GAP_THRESHOLD_US) {
                    val gapFrames = ((gapUs * sampleRate) / 1_000_000).toInt()
                    val silenceBytes = gapFrames * bytesPerFrame
                    val silenceData = ByteArray(silenceBytes)  // Zeros = silence

                    // Convert the silence's server time to client time
                    val silenceClientPlayTime = timeFilter.serverToClient(expectedNext)

                    // Queue the silence chunk BEFORE the current chunk
                    val silenceChunk = AudioChunk(
                        serverTimeMicros = expectedNext,
                        clientPlayTimeMicros = silenceClientPlayTime,
                        pcmData = silenceData,
                        sampleCount = gapFrames
                    )
                    chunkQueue.add(silenceChunk)
                    totalQueuedSamples.addAndGet(gapFrames.toLong())

                    // Update statistics
                    gapsFilled++
                    val gapMs = gapUs / 1000
                    gapSilenceMs += gapMs

                    // Update expected next timestamp to account for inserted silence
                    val silenceDurationUs = (gapFrames * 1_000_000L) / sampleRate
                    expectedNextTimestampUs = expectedNext + silenceDurationUs
                }
            }
            // Handle overlap: trim the start of the chunk
            else if (serverTimeMicros < expectedNext) {
                val overlapUs = expectedNext - serverTimeMicros
                val overlapFrames = ((overlapUs * sampleRate) / 1_000_000).toInt()
                val trimBytes = overlapFrames * bytesPerFrame

                if (trimBytes < workingPcmData.size) {
                    // Trim the overlapping portion from the start
                    workingPcmData = workingPcmData.copyOfRange(trimBytes, workingPcmData.size)
                    workingServerTimeMicros = expectedNext

                    // Update statistics
                    overlapsTrimmed++
                    val overlapMs = overlapUs / 1000
                    overlapTrimmedMs += overlapMs
                } else {
                    // Entire chunk is overlap - skip it entirely
                    overlapsTrimmed++
                    overlapTrimmedMs += overlapUs / 1000
                    return
                }
            }
        }

        // Check for large discontinuity (new stream or seek) - for logging only
        if (lastChunkServerTime > 0) {
            val serverGap = serverTimeMicros - lastChunkServerTime
            val expectedGapUs = (pcmData.size.toLong() / bytesPerFrame) * microsPerSample.toLong()

            // If gap is more than threshold different from expected, log it
            if (abs(serverGap - expectedGapUs) > DISCONTINUITY_THRESHOLD_US) {
                Log.w(TAG, "Discontinuity detected: gap=${serverGap}us, expected=${expectedGapUs}us")
            }
        }
        lastChunkServerTime = serverTimeMicros

        // Calculate sample count for the (possibly trimmed) chunk
        val sampleCount = workingPcmData.size / bytesPerFrame

        // Skip empty chunks (can happen after trimming)
        if (sampleCount == 0 || workingPcmData.isEmpty()) {
            return
        }

        // Convert server time to client time
        val clientPlayTime = timeFilter.serverToClient(workingServerTimeMicros)

        // Create and queue the chunk
        val chunk = AudioChunk(
            serverTimeMicros = workingServerTimeMicros,
            clientPlayTimeMicros = clientPlayTime,
            pcmData = workingPcmData,
            sampleCount = sampleCount
        )
        chunkQueue.add(chunk)
        totalQueuedSamples.addAndGet(sampleCount.toLong())

        // Update expected next timestamp based on this chunk's duration
        val chunkDurationUs = (sampleCount * 1_000_000L) / sampleRate
        expectedNextTimestampUs = workingServerTimeMicros + chunkDurationUs

        // ====================================================================
        // State Machine Transitions in queueChunk()
        // ====================================================================
        // This is where incoming audio chunks trigger state transitions.
        // The key transitions here are:
        //   INITIALIZING -> WAITING_FOR_START (first chunk establishes timing)
        //   REANCHORING  -> WAITING_FOR_START (recovery from large sync error)
        //
        // See PlaybackState enum for the complete state diagram.
        // ====================================================================
        when (playbackState) {
            PlaybackState.INITIALIZING -> {
                // TRANSITION: INITIALIZING -> WAITING_FOR_START
                // Trigger: First audio chunk received while time sync is ready
                // Action: Record the first chunk's server timestamp as anchor point,
                //         compute scheduled client-time start, begin buffer filling
                firstServerTimestampUs = workingServerTimeMicros
                scheduledStartLoopTimeUs = clientPlayTime
                setPlaybackState(PlaybackState.WAITING_FOR_START)
                Log.i(TAG, "First chunk received: serverTime=${workingServerTimeMicros/1000}ms, " +
                        "scheduled start at ${clientPlayTime/1000}ms, transitioning to WAITING_FOR_START")
            }
            PlaybackState.WAITING_FOR_START -> {
                // NO TRANSITION - Still in WAITING_FOR_START
                // Action: Update scheduled start time as time sync improves.
                // The time filter's offset estimate improves with more samples,
                // so we recompute the client play time using the original server timestamp.
                // This ensures the scheduled start aligns with the corrected time sync.
                val firstTs = firstServerTimestampUs
                if (firstTs != null) {
                    scheduledStartLoopTimeUs = timeFilter.serverToClient(firstTs)
                }
                // Actual transition to PLAYING happens in playback loop's handleStartGating()
                // when buffer >= 200ms AND scheduled start time is reached.
            }
            PlaybackState.REANCHORING -> {
                // TRANSITION: REANCHORING -> WAITING_FOR_START
                // Trigger: New chunk arrives after reanchor cleared all buffers
                // Action: Treat this as the new "first" chunk, establish new timing anchor.
                // This completes the reanchor recovery - we have fresh timing reference.
                firstServerTimestampUs = workingServerTimeMicros
                scheduledStartLoopTimeUs = clientPlayTime
                setPlaybackState(PlaybackState.WAITING_FOR_START)
                Log.i(TAG, "Reanchoring: new first chunk at serverTime=${workingServerTimeMicros/1000}ms")
            }
            PlaybackState.PLAYING,
            PlaybackState.DRAINING -> {
                // NO TRANSITION - Normal chunk processing
                // PLAYING: Standard operation, chunks added to queue for playback.
                // DRAINING: Reconnected! New chunks arrive and are seamlessly spliced
                //           into the existing buffer via gap/overlap handling above.
                //           The exitDraining() call (from SendSpinClient) will
                //           transition back to PLAYING once stream is stable.
            }
        }

    }

    // ========================================================================
    // Start Gating and Reanchoring (from Python reference)
    // ========================================================================

    /**
     * Handle start gating - wait for the scheduled start time before playing.
     *
     * This ensures synchronized playback by:
     * 1. Filling with silence until the scheduled DAC time
     * 2. If we're late, dropping frames to catch up
     * 3. Transitioning to PLAYING when ready
     *
     * @return true if we should continue waiting, false if ready to play
     */
    private fun handleStartGating(): Boolean {
        val scheduledStart = scheduledStartLoopTimeUs ?: return false
        val nowMicros = System.nanoTime() / 1000
        val deltaUs = scheduledStart - nowMicros

        when {
            deltaUs > 0 -> {
                // Not yet time to start - we could write silence to AudioTrack
                // For now, just wait (the AudioTrack is already playing, outputting zeros)
                return true  // Keep waiting
            }
            deltaUs < -HARD_RESYNC_THRESHOLD_US -> {
                // We're very late - need to drop frames to catch up
                val framesToDrop = ((-deltaUs * sampleRate) / 1_000_000).toInt()
                val bytesToDrop = framesToDrop * bytesPerFrame
                var droppedFrames = 0

                Log.w(TAG, "Start gating: late by ${-deltaUs/1000}ms, dropping $framesToDrop frames")

                // Drop chunks until we've caught up
                while (droppedFrames < framesToDrop) {
                    val chunk = chunkQueue.peek() ?: break
                    val chunkFrames = chunk.sampleCount

                    if (droppedFrames + chunkFrames <= framesToDrop) {
                        // Drop entire chunk
                        chunkQueue.poll()
                        totalQueuedSamples.addAndGet(-chunk.sampleCount.toLong())
                        droppedFrames += chunkFrames
                        chunksDropped++
                    } else {
                        // Partial drop not supported with chunk-based queue
                        // Just break and start playing - we'll catch up via rate correction
                        break
                    }
                }

                // CRITICAL: Update firstServerTimestampUs to match what we're actually playing
                // The first chunk we'll play is now at the front of the queue
                val firstPlayableChunk = chunkQueue.peek()
                if (firstPlayableChunk != null) {
                    firstServerTimestampUs = firstPlayableChunk.serverTimeMicros
                }

                // Set initial playbackStartTimeUs, will be calibrated from first AudioTimestamp
                val actualStartTime = System.nanoTime() / 1000
                playbackStartTimeUs = actualStartTime
                startTimeCalibrated = false
                samplesReadSinceStart = 0L

                framesDropped += droppedFrames.toLong()
                setPlaybackState(PlaybackState.PLAYING)
                Log.i(TAG, "Start gating complete: dropped $droppedFrames frames, actualStartTime=${actualStartTime/1000}ms, now PLAYING")
                return false  // Ready to play
            }
            else -> {
                // Within tolerance - start playing
                // Set initial playbackStartTimeUs, will be calibrated from first AudioTimestamp
                val actualStartTime = System.nanoTime() / 1000
                playbackStartTimeUs = actualStartTime
                startTimeCalibrated = false
                samplesReadSinceStart = 0L
                setPlaybackState(PlaybackState.PLAYING)
                Log.i(TAG, "Start gating complete: delta=${deltaUs/1000}ms, actualStartTime=${actualStartTime/1000}ms, now PLAYING")
                return false  // Ready to play
            }
        }
    }

    /**
     * Trigger a reanchor - reset sync state due to large error.
     *
     * Called when sync error exceeds REANCHOR_THRESHOLD_US.
     * Respects cooldown to avoid thrashing.
     *
     * Note: This is called from the playback loop, so we use tryLock to avoid
     * blocking if another thread holds the lock.
     *
     * @return true if reanchor was triggered, false if still in cooldown or lock unavailable
     */
    private fun triggerReanchor(): Boolean {
        val nowMicros = System.nanoTime() / 1000
        val timeSinceLastReanchor = nowMicros - lastReanchorTimeUs

        if (timeSinceLastReanchor < REANCHOR_COOLDOWN_US) {
            return false
        }

        // Try to acquire the lock without blocking - if we can't, skip this reanchor attempt
        if (!stateLock.tryLock()) {
            return false
        }

        try {
            Log.w(TAG, "Triggering reanchor: clearing buffers and resetting state")

            lastReanchorTimeUs = nowMicros
            setPlaybackState(PlaybackState.REANCHORING)

            // Clear audio state but keep AudioTrack playing
            chunkQueue.clear()
            totalQueuedSamples.set(0)

            // Safely flush the AudioTrack
            val track = audioTrack
            if (track != null) {
                try {
                    track.pause()
                    track.flush()
                    track.play()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Failed to flush AudioTrack during reanchor", e)
                }
            }

            // Reset start gating state
            scheduledStartLoopTimeUs = null
            firstServerTimestampUs = null

            // Reset sync tracking (simplified)
            lastChunkServerTime = 0L
            insertEveryNFrames = 0
            dropEveryNFrames = 0

            // Reset sync error state
            syncUpdateCounter = 0
            totalFramesWritten = 0L
            serverTimelineCursor = 0L
            playbackStartTimeUs = 0L
            startTimeCalibrated = false
            samplesReadSinceStart = 0L
            syncErrorUs = 0L
            syncErrorFilter.reset()
            clearDacCalibrations()  // Clear DAC calibration history
            playingStateEnteredAtUs = 0L  // Reset grace period

            // Transition to INITIALIZING to wait for new chunks
            setPlaybackState(PlaybackState.INITIALIZING)
            syncCorrections++
            reanchorCount++

            return true
        } finally {
            stateLock.unlock()
        }
    }

    /**
     * Main playback loop that writes audio to AudioTrack at the correct time.
     *
     * Uses a state machine for start gating and sample insert/drop for sync correction.
     * This is imperceptible to the listener (no pitch/tempo changes).
     */
    private fun startPlaybackLoop() {
        val currentScope = scope ?: run {
            Log.e(TAG, "Cannot start playback loop - scope is null")
            return
        }

        playbackJob = currentScope.launch {
            Log.d(TAG, "Playback loop started, initial state=$playbackState")

            while (isActive && isPlaying.get()) {
                if (isPaused.get()) {
                    delay(STATE_POLL_DELAY_MS)
                    continue
                }

                // State machine for synchronized playback
                when (playbackState) {
                    PlaybackState.INITIALIZING -> {
                        // Waiting for first chunk - nothing to do
                        delay(STATE_POLL_DELAY_MS)
                        continue
                    }

                    PlaybackState.WAITING_FOR_START -> {
                        // Check if we have enough buffer before starting
                        val bufferedMs = (totalQueuedSamples.get() * 1000) / sampleRate
                        if (bufferedMs < MIN_BUFFER_BEFORE_START_MS) {
                            delay(STATE_POLL_DELAY_MS)
                            continue
                        }

                        // Handle start gating logic
                        if (handleStartGating()) {
                            delay(STATE_POLL_DELAY_MS)  // Still waiting for scheduled start
                            continue
                        }
                        // handleStartGating() transitioned us to PLAYING
                    }

                    PlaybackState.REANCHORING -> {
                        // Waiting for new chunks after reanchor
                        delay(STATE_POLL_DELAY_MS)
                        continue
                    }

                    PlaybackState.DRAINING -> {
                        // Connection lost - playing from buffer only
                        // Monitor buffer level and notify if running low
                        val bufferedMs = getBufferedDurationMs()

                        if (bufferedMs <= 0) {
                            // Buffer exhausted - notify and stop
                            Log.e(TAG, "Buffer exhausted during DRAINING - stopping playback")
                            stateCallback?.onBufferExhausted()
                            setPlaybackState(PlaybackState.INITIALIZING)
                            delay(STATE_POLL_DELAY_MS)
                            continue
                        }

                        // Rate-limited buffer warnings
                        if (bufferedMs < BUFFER_WARNING_MS) {
                            val nowUs = System.nanoTime() / 1000
                            if (nowUs - lastBufferWarningTimeUs > BUFFER_WARNING_INTERVAL_US) {
                                lastBufferWarningTimeUs = nowUs
                                stateCallback?.onBufferLow(bufferedMs)
                                Log.w(TAG, "Buffer low during DRAINING: ${bufferedMs}ms remaining")
                            }
                        }

                        // Continue playing from buffer (fall through to chunk processing)
                    }

                    PlaybackState.PLAYING -> {
                        // Normal playback - handled below
                    }
                }

                // PLAYING/DRAINING state: process chunks with sync correction
                val chunk = chunkQueue.peek()
                if (chunk == null) {
                    // No chunks available - buffer underrun
                    if (playbackState == PlaybackState.DRAINING) {
                        // In DRAINING, empty queue means we're exhausted (already handled above)
                        delay(BUFFER_EMPTY_DELAY_MS)
                        continue
                    }
                    bufferUnderrunCount++
                    delay(BUFFER_EMPTY_DELAY_MS)
                    continue
                }

                // Get current client time
                val nowMicros = System.nanoTime() / 1000

                // How far in the future should this chunk play?
                val delayMicros = chunk.clientPlayTimeMicros - nowMicros

                when {
                    // Too early - wait
                    delayMicros > BUFFER_HEADROOM_MS * 1000L -> {
                        delay(STATE_POLL_DELAY_MS)
                        continue
                    }

                    // Check for reanchor condition - very large error
                    abs(delayMicros) > REANCHOR_THRESHOLD_US -> {
                        Log.w(TAG, "Large sync error: ${delayMicros/1000}ms, considering reanchor")
                        if (triggerReanchor()) {
                            continue  // Reanchor triggered, restart loop
                        }
                        // Reanchor blocked by cooldown - fall through to hard resync
                    }

                    // Hard resync needed - chunk is way too late
                    delayMicros < -HARD_RESYNC_THRESHOLD_US -> {
                        // Chunk is more than 200ms late - drop it
                        chunkQueue.poll()
                        totalQueuedSamples.addAndGet(-chunk.sampleCount.toLong())
                        chunksDropped++
                        syncCorrections++
                        Log.w(TAG, "Hard resync: dropped chunk ${delayMicros/1000}ms late")
                        continue
                    }

                    // Hard resync needed - chunk is way too early
                    delayMicros > HARD_RESYNC_THRESHOLD_US -> {
                        // Chunk is more than 200ms early - wait more
                        delay(EARLY_CHUNK_DELAY_MS)
                        continue
                    }

                    // Normal playback with sample insert/drop correction
                    else -> {
                        // Update the correction schedule based on current sync error
                        updateCorrectionSchedule(delayMicros)
                        // Play the chunk with corrections applied
                        playChunkWithCorrection(chunk)
                    }
                }
            }

            Log.d(TAG, "Playback loop ended")
        }
    }

    /**
     * Update the sample insert/drop correction schedule based on sync error.
     *
     * ## Design Overview
     *
     * This implements **proportional control** for imperceptible audio sync correction.
     * Instead of changing playback rate (which causes audible pitch/tempo changes), we
     * insert or drop individual sample frames. At 48kHz, a single frame is ~21 microseconds
     * - far below the ~10ms threshold of human perception for audio discontinuities.
     *
     * ## Why Proportional Control?
     *
     * A simple on/off correction (always correct at max rate when error exists) would:
     * - Overshoot the target, causing oscillation around zero
     * - Create more audible artifacts due to rapid insert/drop transitions
     *
     * Proportional control provides:
     * - Gentle corrections for small errors (most common case)
     * - Aggressive corrections only when truly needed
     * - Smooth convergence to zero error without oscillation
     *
     * ## The Math: Sync Error to Correction Interval
     *
     * Given a sync error in microseconds, we calculate how often to insert/drop frames:
     *
     * ```
     * 1. Convert error to frames:
     *    framesError = |errorUs| * sampleRate / 1,000,000
     *    Example: 2ms error at 48kHz = 2000 * 48000 / 1000000 = 96 frames
     *
     * 2. Calculate desired corrections per second:
     *    correctionsPerSec = framesError / CORRECTION_TARGET_SECONDS
     *    Example: 96 frames / 3 seconds = 32 corrections/sec
     *
     * 3. Cap at maximum correction rate:
     *    maxCorrectionsPerSec = sampleRate * MAX_SPEED_CORRECTION
     *    Example: 48000 * 0.02 = 960 corrections/sec max
     *
     * 4. Calculate interval between corrections:
     *    intervalFrames = sampleRate / correctionsPerSec
     *    Example: 48000 / 32 = 1500 frames between corrections
     *    (drop/insert 1 frame every 1500 frames = 31ms)
     * ```
     *
     * ## Why MAX_SPEED_CORRECTION = 2% (0.02)?
     *
     * The 2% limit balances correction speed against audibility:
     * - Below ~4%: Sample insert/drop is completely imperceptible
     * - At 2%: Very conservative - even sensitive listeners won't notice
     * - Correction of 2% at 48kHz = 960 samples/sec = 1 frame every ~1ms
     * - This can correct up to 960 * 21us = ~20ms of error per second
     *
     * Note: The Python reference uses 4%, but 2% provides extra safety margin.
     *
     * ## Why CORRECTION_TARGET_SECONDS = 3 seconds?
     *
     * This controls the responsiveness vs smoothness tradeoff:
     * - Shorter (1-2s): More responsive but more aggressive corrections
     * - Longer (5-10s): Smoother but slow to converge
     * - 3 seconds: Good balance - corrects typical drift within acceptable time
     *   while keeping correction rate low for normal operation
     *
     * With 3 second target:
     * - 10ms error -> ~160 corrections/sec -> 1 frame every ~300 frames (6ms)
     * - 2ms error -> ~32 corrections/sec -> 1 frame every ~1500 frames (31ms)
     * - This keeps corrections rare and imperceptible in normal conditions
     *
     * ## Deadband: Why 2ms Threshold?
     *
     * The DEADBAND_THRESHOLD_US (2ms / 2000us) creates a "good enough" zone:
     * - Errors below 2ms don't trigger any correction
     * - This prevents constant tiny corrections that waste CPU
     * - 2ms is well within acceptable sync tolerance for audio
     * - Human perception of audio/video sync is ~20-80ms; 2ms is imperceptible
     *
     * Without a deadband, noise in the sync error measurement would cause
     * continuous small corrections even when perfectly synced.
     *
     * ## Correction Direction
     *
     * Uses Kalman-filtered sync error from [updateSyncError]:
     * - **Positive error** = behind schedule (DAC ahead of read cursor)
     *   -> DROP frames to catch up (skip input samples, output less)
     * - **Negative error** = ahead of schedule (DAC behind read cursor)
     *   -> INSERT duplicate frames to slow down (output more, effective slowdown)
     *
     * @param processingTimeErrorUs Unused - kept for API compatibility.
     *        Sync error is obtained from [syncErrorFilter] (Kalman-filtered).
     */
    private fun updateCorrectionSchedule(@Suppress("UNUSED_PARAMETER") processingTimeErrorUs: Long) {
        // Guard: Skip corrections until DAC calibration provides reliable sync error
        if (!startTimeCalibrated) {
            insertEveryNFrames = 0
            dropEveryNFrames = 0
            return
        }

        // Guard: Skip corrections during startup grace period (500ms)
        // AudioTimestamp needs time to stabilize after playback starts
        if (playingStateEnteredAtUs > 0) {
            val nowUs = System.nanoTime() / 1000
            val timeSincePlayingUs = nowUs - playingStateEnteredAtUs
            if (timeSincePlayingUs < STARTUP_GRACE_PERIOD_US) {
                insertEveryNFrames = 0
                dropEveryNFrames = 0
                return
            }
        }

        // Guard: Skip corrections during reconnection stabilization period (2s)
        // After reconnection, the Kalman filter needs time to re-converge with new measurements
        if (reconnectedAtUs > 0) {
            val nowUs = System.nanoTime() / 1000
            val timeSinceReconnectUs = nowUs - reconnectedAtUs
            if (timeSinceReconnectUs < RECONNECT_STABILIZATION_US) {
                insertEveryNFrames = 0
                dropEveryNFrames = 0
                return
            }
        }

        // Get Kalman-filtered sync error (smooths measurement noise and tracks drift)
        val effectiveErrorUs = syncErrorFilter.offsetMicros.toDouble()
        val absErr = abs(effectiveErrorUs)

        // Deadband check: errors below 2ms are "good enough" - no correction needed
        // This prevents oscillation and unnecessary CPU usage for imperceptible errors
        if (absErr <= DEADBAND_THRESHOLD_US) {
            insertEveryNFrames = 0
            dropEveryNFrames = 0
            return
        }

        // Step 1: Convert error from microseconds to sample frames
        // Example: 2000us * 48000Hz / 1,000,000 = 96 frames
        val framesError = absErr * sampleRate / 1_000_000.0

        // Step 2: Calculate desired corrections per second using proportional control
        // We aim to eliminate the error over CORRECTION_TARGET_SECONDS (3 seconds)
        // Example: 96 frames / 3 seconds = 32 corrections/sec
        val desiredCorrectionsPerSec = framesError / CORRECTION_TARGET_SECONDS

        // Step 3: Cap at maximum correction rate (2% of sample rate)
        // Example: 48000 * 0.02 = 960 corrections/sec max
        // This ensures corrections remain imperceptible even for large errors
        val maxCorrectionsPerSec = sampleRate * MAX_SPEED_CORRECTION
        val correctionsPerSec = minOf(desiredCorrectionsPerSec, maxCorrectionsPerSec)

        // Step 4: Calculate interval between corrections (in frames)
        // Example: 48000 / 32 = 1500 frames between corrections (~31ms at 48kHz)
        val intervalFrames = if (correctionsPerSec > 0) {
            (sampleRate / correctionsPerSec).toInt().coerceAtLeast(1)
        } else {
            0
        }

        // Apply correction in the appropriate direction
        if (effectiveErrorUs > 0) {
            // Positive error: DAC is ahead of where we've read to
            // DROP frames to catch up (skip input samples, effectively speeding up)
            dropEveryNFrames = intervalFrames
            insertEveryNFrames = 0
            if (framesUntilNextDrop == 0) {
                framesUntilNextDrop = intervalFrames
            }
        } else {
            // Negative error: DAC is behind where we've read to
            // INSERT duplicate frames to slow down (output more samples per input)
            insertEveryNFrames = intervalFrames
            dropEveryNFrames = 0
            if (framesUntilNextInsert == 0) {
                framesUntilNextInsert = intervalFrames
            }
        }
    }

    /**
     * Write a chunk to AudioTrack with sample insert/drop corrections.
     *
     * When corrections are active, processes frame-by-frame to insert duplicates
     * or skip frames. When no corrections are needed, writes in bulk for efficiency.
     */
    private fun playChunkWithCorrection(chunk: AudioChunk) {
        chunkQueue.poll() // Remove from queue
        totalQueuedSamples.addAndGet(-chunk.sampleCount.toLong())

        val track = audioTrack ?: return

        // Track samples consumed for sync error calculation
        samplesReadSinceStart += chunk.sampleCount

        // Decide if we need frame-by-frame processing or can use fast path
        val needsCorrection = insertEveryNFrames > 0 || dropEveryNFrames > 0

        val written = if (needsCorrection) {
            writeWithCorrection(track, chunk.pcmData)
        } else {
            // Fast path: write entire chunk at once
            val result = track.write(chunk.pcmData, 0, chunk.pcmData.size)
            // Store last frame for potential future insertion
            if (chunk.pcmData.size >= bytesPerFrame) {
                // Safety guard: should not trigger since lastOutputFrame is pre-allocated in initialize()
                if (lastOutputFrame.size != bytesPerFrame) {
                    Log.w(TAG, "lastOutputFrame size mismatch, reallocating (expected: $bytesPerFrame, actual: ${lastOutputFrame.size})")
                    lastOutputFrame = ByteArray(bytesPerFrame)
                }
                System.arraycopy(
                    chunk.pcmData, chunk.pcmData.size - bytesPerFrame,
                    lastOutputFrame, 0, bytesPerFrame
                )
            }
            result
        }

        if (written < 0) {
            Log.e(TAG, "AudioTrack write error: $written")
        }

        // Update frame tracking
        val framesWritten = written / bytesPerFrame
        totalFramesWritten += framesWritten

        // Update server timeline cursor - where we've fed audio up to
        val chunkDurationMicros = (chunk.sampleCount * 1_000_000L) / sampleRate
        serverTimelineCursor = chunk.serverTimeMicros + chunkDurationMicros

        chunksPlayed++

        // Update sync error periodically
        syncUpdateCounter++
        if (syncUpdateCounter >= SYNC_ERROR_UPDATE_INTERVAL) {
            syncUpdateCounter = 0
            updateSyncError()
        }
    }

    /**
     * Write PCM data with sample insert/drop corrections applied.
     *
     * Processes the audio frame-by-frame, inserting duplicate frames or dropping
     * frames according to the current correction schedule.
     *
     * @param track The AudioTrack to write to
     * @param pcmData The raw PCM data
     * @return Total bytes written to AudioTrack
     */
    private fun writeWithCorrection(track: AudioTrack, pcmData: ByteArray): Int {
        val inputFrameCount = pcmData.size / bytesPerFrame
        var totalWritten = 0
        var inputOffset = 0

        // Process each input frame
        for (i in 0 until inputFrameCount) {
            // Check if we should drop this frame (to speed up / catch up)
            if (dropEveryNFrames > 0) {
                framesUntilNextDrop--
                if (framesUntilNextDrop <= 0) {
                    // Drop this frame by skipping it
                    framesUntilNextDrop = dropEveryNFrames
                    framesDropped++
                    inputOffset += bytesPerFrame
                    continue
                }
            }

            // Check if we should insert a duplicate frame (to slow down)
            if (insertEveryNFrames > 0) {
                framesUntilNextInsert--
                if (framesUntilNextInsert <= 0 && lastOutputFrame.isNotEmpty()) {
                    // Insert a duplicate of the last output frame
                    framesUntilNextInsert = insertEveryNFrames
                    val written = track.write(lastOutputFrame, 0, bytesPerFrame)
                    if (written > 0) {
                        totalWritten += written
                        framesInserted++
                    }
                }
            }

            // Write the current frame
            val written = track.write(pcmData, inputOffset, bytesPerFrame)
            if (written > 0) {
                totalWritten += written
                // Store this frame as the last output frame
                // Safety guard: should not trigger since lastOutputFrame is pre-allocated in initialize()
                if (lastOutputFrame.size != bytesPerFrame) {
                    Log.w(TAG, "lastOutputFrame size mismatch in writeWithCorrection, reallocating")
                    lastOutputFrame = ByteArray(bytesPerFrame)
                }
                System.arraycopy(pcmData, inputOffset, lastOutputFrame, 0, bytesPerFrame)
            }
            inputOffset += bytesPerFrame
        }

        return totalWritten
    }

    // ========================================================================
    // DAC Calibration - Maps system time to actual speaker output time
    // ========================================================================

    /**
     * Update sync error using server-time-based calculation (sendspin-cli style).
     *
     * Continuously queries AudioTimestamp to track DAC position, then:
     * 1. Stores DAC calibration pairs (dacTime, loopTime)
     * 2. Converts DAC time → loop time → server time
     * 3. Calculates: sync_error = playback_position_server - read_cursor_server
     *
     * This approach handles drift between CPU clock and audio hardware clock,
     * which the previous Windows SDK style approach did not.
     *
     * Positive error = DAC ahead of read cursor → need to DROP (read faster)
     * Negative error = DAC behind read cursor → need to INSERT (slow down output)
     */
    private fun updateSyncError() {
        val track = audioTrack ?: return
        if (playbackState != PlaybackState.PLAYING) return

        try {
            // Query AudioTimestamp on every update (not just once)
            val success = track.getTimestamp(audioTimestamp)
            if (!success) {
                // AudioTimestamp not available yet - can't calculate sync error
                return
            }

            val dacTimeMicros = audioTimestamp.nanoTime / 1000
            val framePosition = audioTimestamp.framePosition
            val loopTimeUs = System.nanoTime() / 1000

            // Sanity check - framePosition should be reasonable
            if (framePosition <= 0 || framePosition > totalFramesWritten + sampleRate) {
                // Invalid frame position
                return
            }

            // Store DAC calibration pair for time conversion
            storeDacCalibration(dacTimeMicros, loopTimeUs)

            // Mark as calibrated after first successful timestamp
            if (!startTimeCalibrated) {
                startTimeCalibrated = true
                Log.i(TAG, "DAC calibration started: framePos=$framePosition, dacTime=${dacTimeMicros/1000}ms")
            }

            // ================================================================
            // SYNC ERROR IN SERVER TIME (sendspin-cli style)
            // ================================================================
            // sync_error = playback_position_server - read_cursor_server
            //
            // playback_position_server = where DAC is currently playing (in server time)
            // read_cursor_server = where we've read up to (serverTimelineCursor)
            //
            // Positive = DAC ahead of read cursor (playing fast) → DROP to catch up
            // Negative = DAC behind read cursor (playing slow) → INSERT to slow down
            //

            // Step 1: Estimate loop time corresponding to the DAC timestamp
            val loopAtDacUs = estimateLoopTimeForDacTime(dacTimeMicros)
            if (loopAtDacUs == 0L) {
                // Not enough calibrations yet
                return
            }

            // Step 2: Convert loop time to server time
            val playbackPositionServerUs = computeServerTime(loopAtDacUs)

            // Step 3: Read cursor is serverTimelineCursor (where we've fed audio to)
            // But we need to account for audio still in the AudioTrack buffer
            // serverTimelineCursor = where we've fed to
            // framePosition = how many frames have actually played
            // The difference is what's buffered

            // Calculate what server time the DAC is currently at based on frame position
            // We need to find what chunk contains framePosition
            // For simplicity, use the server timeline cursor minus the buffered amount
            val framesInBuffer = (totalFramesWritten - framePosition).coerceAtLeast(0)
            val bufferedTimeUs = (framesInBuffer * 1_000_000L) / sampleRate
            val readCursorServerUs = serverTimelineCursor - bufferedTimeUs

            // Calculate sync error in server time
            val rawSyncError = playbackPositionServerUs - readCursorServerUs
            syncErrorUs = rawSyncError

            // Apply 2D Kalman filter smoothing (tracks offset + drift)
            syncErrorFilter.update(rawSyncError, loopTimeUs)

        } catch (e: Exception) {
            Log.w(TAG, "Failed to update sync error", e)
        }
    }

    // ========================================================================
    // DAC Calibration - Maps DAC hardware time to loop/system time
    // ========================================================================

    /**
     * Store a DAC calibration pair for time conversion.
     *
     * Captures the relationship between DAC hardware time (from AudioTimestamp)
     * and system monotonic time (from System.nanoTime). This allows us to
     * convert DAC times to loop times and then to server times.
     *
     * @param dacTimeUs DAC hardware time in microseconds
     * @param loopTimeUs System monotonic time in microseconds
     */
    private fun storeDacCalibration(dacTimeUs: Long, loopTimeUs: Long) {
        // Don't store calibrations too frequently
        if (loopTimeUs - lastDacCalibrationTimeUs < MIN_CALIBRATION_INTERVAL_US) {
            return
        }

        dacLoopCalibrations.addLast(DacCalibration(dacTimeUs, loopTimeUs))
        lastDacCalibrationTimeUs = loopTimeUs

        // Keep only the most recent calibrations
        while (dacLoopCalibrations.size > MAX_DAC_CALIBRATIONS) {
            dacLoopCalibrations.removeFirst()
        }
    }

    /**
     * Estimate the loop time that corresponds to a given DAC time.
     *
     * Uses linear interpolation between calibration pairs to estimate
     * what system time corresponds to a DAC hardware timestamp.
     *
     * @param dacTimeUs DAC hardware time in microseconds
     * @return Estimated loop (system) time in microseconds
     */
    private fun estimateLoopTimeForDacTime(dacTimeUs: Long): Long {
        if (dacLoopCalibrations.isEmpty()) {
            // No calibrations yet - can't estimate
            return 0L
        }

        if (dacLoopCalibrations.size == 1) {
            // Single calibration - use simple offset
            val cal = dacLoopCalibrations.first()
            val dacOffset = dacTimeUs - cal.dacTimeUs
            return cal.loopTimeUs + dacOffset
        }

        // Find the two calibrations that bracket the target DAC time
        // or use the nearest pair for extrapolation
        val sorted = dacLoopCalibrations.sortedBy { it.dacTimeUs }

        // Find where dacTimeUs falls in the sorted list
        var lower = sorted.first()
        var upper = sorted.last()

        for (i in 0 until sorted.size - 1) {
            if (sorted[i].dacTimeUs <= dacTimeUs && sorted[i + 1].dacTimeUs >= dacTimeUs) {
                lower = sorted[i]
                upper = sorted[i + 1]
                break
            }
        }

        // Linear interpolation between the two calibration points
        val dacDelta = upper.dacTimeUs - lower.dacTimeUs
        if (dacDelta == 0L) {
            return lower.loopTimeUs
        }

        val fraction = (dacTimeUs - lower.dacTimeUs).toDouble() / dacDelta
        val loopDelta = upper.loopTimeUs - lower.loopTimeUs
        return lower.loopTimeUs + (fraction * loopDelta).toLong()
    }

    /**
     * Convert a loop (system) time to server time using the time filter.
     *
     * @param loopTimeUs System monotonic time in microseconds
     * @return Server time in microseconds
     */
    private fun computeServerTime(loopTimeUs: Long): Long {
        return timeFilter.clientToServer(loopTimeUs)
    }

    /**
     * Clear DAC calibrations (called on buffer clear/reanchor).
     */
    private fun clearDacCalibrations() {
        dacLoopCalibrations.clear()
        lastDacCalibrationTimeUs = 0L
    }

    /**
     * Get the server timeline cursor (where we've fed audio up to).
     *
     * @return Server time in microseconds of the last audio chunk queued
     */
    fun getServerTimelineCursorUs(): Long = serverTimelineCursor

    /**
     * Get the current sync error.
     *
     * Positive = behind (haven't read enough) → need to DROP
     * Negative = ahead (read too much) → need to INSERT
     *
     * @return Sync error in microseconds
     */
    fun getSyncErrorUs(): Long = syncErrorUs

    /**
     * Check if start time has been calibrated from AudioTimestamp.
     */
    fun isStartTimeCalibrated(): Boolean = startTimeCalibrated

    /**
     * Get the number of DAC calibration pairs stored.
     */
    fun getDacCalibrationCount(): Int = dacLoopCalibrations.size

    /**
     * Get the sync error filter's drift value.
     */
    fun getSyncErrorDrift(): Double = syncErrorFilter.driftValue

    /**
     * Get the remaining grace period time in microseconds.
     * Returns -1 if grace period is not active.
     */
    fun getGracePeriodRemainingUs(): Long {
        if (playingStateEnteredAtUs <= 0) return -1
        val nowUs = System.nanoTime() / 1000
        val elapsed = nowUs - playingStateEnteredAtUs
        val remaining = STARTUP_GRACE_PERIOD_US - elapsed
        return if (remaining > 0) remaining else -1
    }

    /**
     * Get current playback state.
     */
    fun getPlaybackState(): PlaybackState = playbackState

    /**
     * Get current buffered duration in milliseconds.
     * Useful for monitoring buffer status during DRAINING state.
     */
    fun getBufferedDurationMs(): Long {
        return (totalQueuedSamples.get() * 1000) / sampleRate
    }

    /**
     * Get the expected next timestamp in server time.
     * This is where the next audio chunk should start to maintain continuity.
     * Used for seamless stream handoff during reconnection.
     *
     * @return Expected next server timestamp in microseconds, or null if not set
     */
    fun getExpectedNextTimestampUs(): Long? = expectedNextTimestampUs

    /**
     * Enter draining mode - continue playing from buffer while disconnected.
     * Called when connection is lost but reconnection is being attempted.
     *
     * In DRAINING state:
     * - Playback continues from existing buffer
     * - Buffer exhaustion is monitored and reported
     * - No new chunks are expected until exitDraining() is called
     *
     * @return true if successfully entered draining, false if not applicable
     */
    fun enterDraining(): Boolean {
        stateLock.withLock {
            // Only enter draining if we're currently playing or have buffer
            if (playbackState != PlaybackState.PLAYING && playbackState != PlaybackState.WAITING_FOR_START) {
                Log.w(TAG, "Cannot enter DRAINING from state $playbackState")
                return false
            }

            stateBeforeDraining = playbackState
            drainingStartTimeUs = System.nanoTime() / 1000
            lastBufferWarningTimeUs = 0L
            setPlaybackState(PlaybackState.DRAINING)

            val bufferedMs = getBufferedDurationMs()
            Log.i(TAG, "Entering DRAINING state - buffer: ${bufferedMs}ms")
            return true
        }
    }

    /**
     * Exit draining mode - new stream is available.
     * Called after successful reconnection when new audio stream starts.
     *
     * The existing buffer will continue to be played, and new chunks will be
     * appended. The gap/overlap handling in queueChunk() will handle any
     * discontinuity at the splice point.
     *
     * @return true if successfully exited draining, false if not in draining state
     */
    fun exitDraining(): Boolean {
        stateLock.withLock {
            if (playbackState != PlaybackState.DRAINING) {
                Log.w(TAG, "Cannot exit DRAINING - current state is $playbackState")
                return false
            }

            val drainingDurationMs = (System.nanoTime() / 1000 - drainingStartTimeUs) / 1000
            Log.i(TAG, "Exiting DRAINING state after ${drainingDurationMs}ms - resuming normal playback")

            // Mark reconnection time for stabilization period (skip sync corrections while Kalman re-converges)
            reconnectedAtUs = System.nanoTime() / 1000

            // Transition back to PLAYING (the normal state for active playback)
            setPlaybackState(PlaybackState.PLAYING)
            stateBeforeDraining = null
            return true
        }
    }

    /**
     * Set the callback for playback state changes.
     */
    fun setStateCallback(callback: SyncAudioPlayerCallback?) {
        stateCallback = callback
    }

    /**
     * Update playback state and notify callback if changed.
     * Thread-safe via stateLock (ReentrantLock allows re-entry from callers already holding lock).
     */
    private fun setPlaybackState(newState: PlaybackState) {
        stateLock.withLock {
            if (playbackState != newState) {
                // Track when we enter PLAYING state for grace period calculation
                if (newState == PlaybackState.PLAYING && playbackState != PlaybackState.PLAYING) {
                    playingStateEnteredAtUs = System.nanoTime() / 1000
                    Log.d(TAG, "Entered PLAYING state - grace period starts (${STARTUP_GRACE_PERIOD_US/1000}ms)")
                }
                playbackState = newState
                stateCallback?.onPlaybackStateChanged(newState)
            }
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
            isPlaying = isPlaying.get(),
            // Playback state machine
            playbackState = playbackState,
            scheduledStartLoopTimeUs = scheduledStartLoopTimeUs,
            firstServerTimestampUs = firstServerTimestampUs,
            // Sync error (simplified Windows SDK style)
            syncErrorUs = syncErrorUs,
            smoothedSyncErrorUs = syncErrorFilter.offsetMicros,
            startTimeCalibrated = startTimeCalibrated,
            samplesReadSinceStart = samplesReadSinceStart,
            serverTimelineCursorUs = serverTimelineCursor,
            totalFramesWritten = totalFramesWritten,
            // Sample insert/drop correction stats
            framesInserted = framesInserted,
            framesDropped = framesDropped,
            insertEveryNFrames = insertEveryNFrames,
            dropEveryNFrames = dropEveryNFrames,
            // Gap/overlap handling stats
            gapsFilled = gapsFilled,
            gapSilenceMs = gapSilenceMs,
            overlapsTrimmed = overlapsTrimmed,
            overlapTrimmedMs = overlapTrimmedMs,
            // New stats for comprehensive debugging
            reanchorCount = reanchorCount,
            bufferUnderrunCount = bufferUnderrunCount,
            dacCalibrationCount = dacLoopCalibrations.size,
            syncErrorDrift = syncErrorFilter.driftValue,
            gracePeriodRemainingUs = getGracePeriodRemainingUs()
        )
    }

    data class SyncStats(
        val chunksReceived: Long,
        val chunksPlayed: Long,
        val chunksDropped: Long,
        val syncCorrections: Long,
        val queuedSamples: Long,
        val isPlaying: Boolean,
        // Playback state machine stats
        val playbackState: PlaybackState = PlaybackState.INITIALIZING,
        val scheduledStartLoopTimeUs: Long? = null,
        val firstServerTimestampUs: Long? = null,
        // Sync error stats (simplified Windows SDK style)
        val syncErrorUs: Long = 0,
        val smoothedSyncErrorUs: Long = 0,
        val startTimeCalibrated: Boolean = false,
        val samplesReadSinceStart: Long = 0,
        val serverTimelineCursorUs: Long = 0,
        val totalFramesWritten: Long = 0,
        // Sample insert/drop correction stats
        val framesInserted: Long = 0,
        val framesDropped: Long = 0,
        val insertEveryNFrames: Int = 0,
        val dropEveryNFrames: Int = 0,
        // Gap/overlap handling stats
        val gapsFilled: Long = 0,
        val gapSilenceMs: Long = 0,
        val overlapsTrimmed: Long = 0,
        val overlapTrimmedMs: Long = 0,
        // New stats for comprehensive debugging
        val reanchorCount: Long = 0,
        val bufferUnderrunCount: Long = 0,
        val dacCalibrationCount: Int = 0,
        val syncErrorDrift: Double = 0.0,
        val gracePeriodRemainingUs: Long = -1
    )
}
