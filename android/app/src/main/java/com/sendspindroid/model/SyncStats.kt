package com.sendspindroid.model

import android.os.Bundle
import com.sendspindroid.sendspin.PlaybackState

/**
 * Data class holding all synchronization statistics for display in Stats for Nerds.
 *
 * This aggregates data from:
 * - SendspinTimeFilter (clock synchronization)
 * - SyncAudioPlayer (buffer and playback stats)
 *
 * ## Design Rationale
 * Separating the data model from the UI allows for:
 * - Easy serialization to Bundle for MediaSession extras
 * - Testability of stats collection logic
 * - Future use in other UI contexts (notifications, widgets, etc.)
 */
data class SyncStats(
    // === PLAYBACK STATE ===
    val playbackState: PlaybackState = PlaybackState.INITIALIZING,
    val isPlaying: Boolean = false,

    // === SYNC STATUS ===
    /**
     * Smoothed sync error in microseconds.
     * Positive = audio is ahead of schedule, negative = behind.
     */
    val syncErrorUs: Long = 0L,

    /**
     * True sync error measured from DAC timestamps.
     * More accurate than smoothed error as it uses actual hardware timing.
     */
    val trueSyncErrorUs: Long = 0L,

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
    val correctionErrorUs: Long = 0L,

    // === CLOCK SYNC ===
    val clockReady: Boolean = false,
    val clockOffsetUs: Long = 0L,
    val clockErrorUs: Long = 0L,
    val measurementCount: Int = 0,

    // === DAC CALIBRATION ===
    val dacCalibrationCount: Int = 0,
    val totalFramesWritten: Long = 0L,
    val lastKnownPlaybackPositionUs: Long = 0L,
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
            // Playback state
            putString("playback_state", playbackState.name)
            putBoolean("is_playing", isPlaying)

            // Sync status
            putLong("sync_error_us", syncErrorUs)
            putLong("true_sync_error_us", trueSyncErrorUs)

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
            putLong("correction_error_us", correctionErrorUs)

            // Clock sync
            putBoolean("clock_ready", clockReady)
            putLong("clock_offset_us", clockOffsetUs)
            putLong("clock_error_us", clockErrorUs)
            putInt("measurement_count", measurementCount)

            // DAC calibration
            putInt("dac_calibration_count", dacCalibrationCount)
            putLong("total_frames_written", totalFramesWritten)
            putLong("last_known_playback_position_us", lastKnownPlaybackPositionUs)
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
                playbackState = PlaybackState.valueOf(
                    bundle.getString("playback_state", PlaybackState.INITIALIZING.name)
                ),
                isPlaying = bundle.getBoolean("is_playing", false),
                syncErrorUs = bundle.getLong("sync_error_us", 0L),
                trueSyncErrorUs = bundle.getLong("true_sync_error_us", 0L),
                queuedSamples = bundle.getLong("queued_samples", 0L),
                chunksReceived = bundle.getLong("chunks_received", 0L),
                chunksPlayed = bundle.getLong("chunks_played", 0L),
                chunksDropped = bundle.getLong("chunks_dropped", 0L),
                gapsFilled = bundle.getLong("gaps_filled", 0L),
                gapSilenceMs = bundle.getLong("gap_silence_ms", 0L),
                overlapsTrimmed = bundle.getLong("overlaps_trimmed", 0L),
                overlapTrimmedMs = bundle.getLong("overlap_trimmed_ms", 0L),
                insertEveryNFrames = bundle.getInt("insert_every_n_frames", 0),
                dropEveryNFrames = bundle.getInt("drop_every_n_frames", 0),
                framesInserted = bundle.getLong("frames_inserted", 0L),
                framesDropped = bundle.getLong("frames_dropped", 0L),
                syncCorrections = bundle.getLong("sync_corrections", 0L),
                correctionErrorUs = bundle.getLong("correction_error_us", 0L),
                clockReady = bundle.getBoolean("clock_ready", false),
                clockOffsetUs = bundle.getLong("clock_offset_us", 0L),
                clockErrorUs = bundle.getLong("clock_error_us", 0L),
                measurementCount = bundle.getInt("measurement_count", 0),
                dacCalibrationCount = bundle.getInt("dac_calibration_count", 0),
                totalFramesWritten = bundle.getLong("total_frames_written", 0L),
                lastKnownPlaybackPositionUs = bundle.getLong("last_known_playback_position_us", 0L),
                serverTimelineCursorUs = bundle.getLong("server_timeline_cursor_us", 0L),
                scheduledStartLoopTimeUs = if (bundle.containsKey("scheduled_start_loop_time_us"))
                    bundle.getLong("scheduled_start_loop_time_us") else null,
                firstServerTimestampUs = if (bundle.containsKey("first_server_timestamp_us"))
                    bundle.getLong("first_server_timestamp_us") else null
            )
        }
    }
}
