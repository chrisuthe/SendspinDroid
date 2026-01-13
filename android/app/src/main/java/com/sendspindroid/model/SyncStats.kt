package com.sendspindroid.model

import android.os.Bundle
import com.sendspindroid.sendspin.PlaybackState

/**
 * Data class holding all synchronization statistics for display in Stats for Nerds.
 *
 * This aggregates data from:
 * - SendSpinClient (connection info)
 * - SendspinTimeFilter (clock synchronization)
 * - SyncAudioPlayer (buffer and playback stats)
 * - SyncErrorFilter (sync error smoothing)
 *
 * ## Design Rationale
 * Separating the data model from the UI allows for:
 * - Easy serialization to Bundle for MediaSession extras
 * - Testability of stats collection logic
 * - Future use in other UI contexts (notifications, widgets, etc.)
 */
data class SyncStats(
    // === CONNECTION ===
    val serverName: String? = null,
    val serverAddress: String? = null,
    val connectionState: String = "Unknown",

    // === PLAYBACK STATE ===
    val playbackState: PlaybackState = PlaybackState.INITIALIZING,
    val isPlaying: Boolean = false,

    // === SYNC ERROR ===
    /**
     * Raw sync error in microseconds.
     * syncError = playback_position_server - read_cursor_server
     * Positive = DAC ahead (need DROP), Negative = DAC behind (need INSERT)
     */
    val syncErrorUs: Long = 0L,

    /**
     * Kalman-filtered sync error for corrections.
     */
    val smoothedSyncErrorUs: Long = 0L,

    /**
     * Sync error drift from 2D Kalman filter.
     * Shows trend of sync error change.
     */
    val syncErrorDrift: Double = 0.0,

    /**
     * Grace period remaining in microseconds (-1 if inactive).
     */
    val gracePeriodRemainingUs: Long = -1L,

    // === DAC/AUDIO ===
    /**
     * Whether start time has been calibrated from AudioTimestamp.
     */
    val startTimeCalibrated: Boolean = false,

    /**
     * Number of DAC calibration pairs stored.
     */
    val dacCalibrationCount: Int = 0,

    /**
     * Samples consumed since playback started.
     */
    val samplesReadSinceStart: Long = 0L,

    /**
     * Total frames written to AudioTrack.
     */
    val totalFramesWritten: Long = 0L,

    /**
     * Count of buffer underruns (queue empty during playback).
     */
    val bufferUnderrunCount: Long = 0L,

    // === BUFFER ===
    val queuedSamples: Long = 0L,
    val chunksReceived: Long = 0L,
    val chunksPlayed: Long = 0L,
    val chunksDropped: Long = 0L,

    // Gap/overlap handling
    val gapsFilled: Long = 0L,
    val gapSilenceMs: Long = 0L,
    val overlapsTrimmed: Long = 0L,
    val overlapTrimmedMs: Long = 0L,

    // === SYNC CORRECTION ===
    val insertEveryNFrames: Int = 0,
    val dropEveryNFrames: Int = 0,
    val framesInserted: Long = 0L,
    val framesDropped: Long = 0L,
    val syncCorrections: Long = 0L,
    val reanchorCount: Long = 0L,

    // === CLOCK SYNC ===
    val clockReady: Boolean = false,
    val clockConverged: Boolean = false,
    val clockOffsetUs: Long = 0L,
    val clockDriftPpm: Double = 0.0,
    val clockErrorUs: Long = 0L,
    val measurementCount: Int = 0,
    val lastTimeSyncAgeMs: Long = -1L,

    // === PLAYBACK TRACKING ===
    val serverTimelineCursorUs: Long = 0L,

    // === TIMING ===
    val scheduledStartLoopTimeUs: Long? = null,
    val firstServerTimestampUs: Long? = null
) {
    /**
     * Converts stats to a Bundle for MediaSession extras.
     * This allows PlaybackService to publish stats via session extras.
     */
    fun toBundle(): Bundle {
        return Bundle().apply {
            // Connection
            serverName?.let { putString("server_name", it) }
            serverAddress?.let { putString("server_address", it) }
            putString("connection_state", connectionState)

            // Playback state
            putString("playback_state", playbackState.name)
            putBoolean("is_playing", isPlaying)

            // Sync error
            putLong("sync_error_us", syncErrorUs)
            putLong("smoothed_sync_error_us", smoothedSyncErrorUs)
            putDouble("sync_error_drift", syncErrorDrift)
            putLong("grace_period_remaining_us", gracePeriodRemainingUs)

            // DAC/Audio
            putBoolean("start_time_calibrated", startTimeCalibrated)
            putInt("dac_calibration_count", dacCalibrationCount)
            putLong("samples_read_since_start", samplesReadSinceStart)
            putLong("total_frames_written", totalFramesWritten)
            putLong("buffer_underrun_count", bufferUnderrunCount)

            // Buffer
            putLong("queued_samples", queuedSamples)
            putLong("chunks_received", chunksReceived)
            putLong("chunks_played", chunksPlayed)
            putLong("chunks_dropped", chunksDropped)
            putLong("gaps_filled", gapsFilled)
            putLong("gap_silence_ms", gapSilenceMs)
            putLong("overlaps_trimmed", overlapsTrimmed)
            putLong("overlap_trimmed_ms", overlapTrimmedMs)

            // Sync correction
            putInt("insert_every_n_frames", insertEveryNFrames)
            putInt("drop_every_n_frames", dropEveryNFrames)
            putLong("frames_inserted", framesInserted)
            putLong("frames_dropped", framesDropped)
            putLong("sync_corrections", syncCorrections)
            putLong("reanchor_count", reanchorCount)

            // Clock sync
            putBoolean("clock_ready", clockReady)
            putBoolean("clock_converged", clockConverged)
            putLong("clock_offset_us", clockOffsetUs)
            putDouble("clock_drift_ppm", clockDriftPpm)
            putLong("clock_error_us", clockErrorUs)
            putInt("measurement_count", measurementCount)
            putLong("last_time_sync_age_ms", lastTimeSyncAgeMs)

            // Playback tracking
            putLong("server_timeline_cursor_us", serverTimelineCursorUs)

            // Timing
            scheduledStartLoopTimeUs?.let { putLong("scheduled_start_loop_time_us", it) }
            firstServerTimestampUs?.let { putLong("first_server_timestamp_us", it) }
        }
    }

    companion object {
        /**
         * Creates a SyncStats instance from a Bundle.
         * Inverse of toBundle().
         */
        fun fromBundle(bundle: Bundle): SyncStats {
            return SyncStats(
                // Connection
                serverName = bundle.getString("server_name"),
                serverAddress = bundle.getString("server_address"),
                connectionState = bundle.getString("connection_state", "Unknown"),

                // Playback state
                playbackState = PlaybackState.valueOf(
                    bundle.getString("playback_state", PlaybackState.INITIALIZING.name)
                ),
                isPlaying = bundle.getBoolean("is_playing", false),

                // Sync error
                syncErrorUs = bundle.getLong("sync_error_us", 0L),
                smoothedSyncErrorUs = bundle.getLong("smoothed_sync_error_us", 0L),
                syncErrorDrift = bundle.getDouble("sync_error_drift", 0.0),
                gracePeriodRemainingUs = bundle.getLong("grace_period_remaining_us", -1L),

                // DAC/Audio
                startTimeCalibrated = bundle.getBoolean("start_time_calibrated", false),
                dacCalibrationCount = bundle.getInt("dac_calibration_count", 0),
                samplesReadSinceStart = bundle.getLong("samples_read_since_start", 0L),
                totalFramesWritten = bundle.getLong("total_frames_written", 0L),
                bufferUnderrunCount = bundle.getLong("buffer_underrun_count", 0L),

                // Buffer
                queuedSamples = bundle.getLong("queued_samples", 0L),
                chunksReceived = bundle.getLong("chunks_received", 0L),
                chunksPlayed = bundle.getLong("chunks_played", 0L),
                chunksDropped = bundle.getLong("chunks_dropped", 0L),
                gapsFilled = bundle.getLong("gaps_filled", 0L),
                gapSilenceMs = bundle.getLong("gap_silence_ms", 0L),
                overlapsTrimmed = bundle.getLong("overlaps_trimmed", 0L),
                overlapTrimmedMs = bundle.getLong("overlap_trimmed_ms", 0L),

                // Sync correction
                insertEveryNFrames = bundle.getInt("insert_every_n_frames", 0),
                dropEveryNFrames = bundle.getInt("drop_every_n_frames", 0),
                framesInserted = bundle.getLong("frames_inserted", 0L),
                framesDropped = bundle.getLong("frames_dropped", 0L),
                syncCorrections = bundle.getLong("sync_corrections", 0L),
                reanchorCount = bundle.getLong("reanchor_count", 0L),

                // Clock sync
                clockReady = bundle.getBoolean("clock_ready", false),
                clockConverged = bundle.getBoolean("clock_converged", false),
                clockOffsetUs = bundle.getLong("clock_offset_us", 0L),
                clockDriftPpm = bundle.getDouble("clock_drift_ppm", 0.0),
                clockErrorUs = bundle.getLong("clock_error_us", 0L),
                measurementCount = bundle.getInt("measurement_count", 0),
                lastTimeSyncAgeMs = bundle.getLong("last_time_sync_age_ms", -1L),

                // Playback tracking
                serverTimelineCursorUs = bundle.getLong("server_timeline_cursor_us", 0L),

                // Timing
                scheduledStartLoopTimeUs = if (bundle.containsKey("scheduled_start_loop_time_us"))
                    bundle.getLong("scheduled_start_loop_time_us") else null,
                firstServerTimestampUs = if (bundle.containsKey("first_server_timestamp_us"))
                    bundle.getLong("first_server_timestamp_us") else null
            )
        }
    }
}
