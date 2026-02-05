package com.sendspindroid.ui.stats

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.sendspindroid.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * ViewModel for Stats Bottom Sheet.
 * Handles MediaController connection and periodic stats updates.
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "StatsViewModel"
        private const val UPDATE_INTERVAL_MS = 500L  // 2 Hz

        // Thresholds for color-coded status
        private const val SYNC_ERROR_GOOD_US = 2_000L      // <2ms = green
        private const val SYNC_ERROR_WARNING_US = 10_000L  // 2-10ms = yellow
        private const val CLOCK_ERROR_GOOD_US = 1_000L     // <1ms = green
        private const val CLOCK_ERROR_WARNING_US = 5_000L  // 1-5ms = yellow
        private const val CLOCK_DRIFT_GOOD_PPM = 10.0      // <10 ppm = green
        private const val CLOCK_DRIFT_WARNING_PPM = 50.0   // 10-50 ppm = yellow
    }

    private val _statsState = MutableStateFlow(StatsState())
    val statsState: StateFlow<StatsState> = _statsState.asStateFlow()

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var isPolling = false

    init {
        initializeMediaController()
    }

    private fun initializeMediaController() {
        val context = getApplication<Application>()
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )

        mediaControllerFuture = MediaController.Builder(context, sessionToken)
            .buildAsync()

        mediaControllerFuture?.addListener(
            {
                try {
                    mediaController = mediaControllerFuture?.get()
                    Log.d(TAG, "MediaController connected")
                    startPolling()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect MediaController", e)
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    private fun startPolling() {
        if (isPolling) return
        isPolling = true

        viewModelScope.launch {
            while (isActive && isPolling) {
                requestStats()
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        isPolling = false
    }

    private fun requestStats() {
        val controller = mediaController ?: return

        try {
            val command = SessionCommand(PlaybackService.COMMAND_GET_STATS, Bundle.EMPTY)
            val result = controller.sendCustomCommand(command, Bundle.EMPTY)

            result.addListener(
                {
                    try {
                        val bundle = result.get().extras
                        updateStats(bundle)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get stats", e)
                    }
                },
                MoreExecutors.directExecutor()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request stats", e)
        }
    }

    private fun updateStats(bundle: Bundle) {
        _statsState.value = StatsState(
            // Connection
            serverName = bundle.getString("server_name", null),
            serverAddress = bundle.getString("server_address", null),
            connectionState = bundle.getString("connection_state", "Unknown"),
            audioCodec = bundle.getString("audio_codec", "--"),
            reconnectAttempts = bundle.getInt("reconnect_attempts", 0),

            // Network
            networkType = bundle.getString("network_type", "UNKNOWN"),
            networkQuality = bundle.getString("network_quality", "UNKNOWN"),
            networkMetered = bundle.getBoolean("network_metered", true),
            wifiRssi = bundle.getInt("wifi_rssi", Int.MIN_VALUE),
            wifiSpeed = bundle.getInt("wifi_link_speed", -1),
            wifiFrequency = bundle.getInt("wifi_frequency", -1),
            cellularType = bundle.getString("cellular_type", null),

            // Sync Error
            playbackState = bundle.getString("playback_state", "UNKNOWN"),
            syncErrorUs = bundle.getLong("sync_error_us", 0L),
            smoothedSyncErrorUs = bundle.getLong("smoothed_sync_error_us", 0L),
            syncErrorDrift = bundle.getDouble("sync_error_drift", 0.0),
            gracePeriodRemainingUs = bundle.getLong("grace_period_remaining_us", -1L),

            // Clock Sync
            clockOffsetUs = bundle.getLong("clock_offset_us", 0L),
            clockDriftPpm = bundle.getDouble("clock_drift_ppm", 0.0),
            clockErrorUs = bundle.getLong("clock_error_us", 0L),
            clockConverged = bundle.getBoolean("clock_converged", false),
            measurementCount = bundle.getInt("measurement_count", 0),
            lastTimeSyncAgeMs = bundle.getLong("last_time_sync_age_ms", -1L),
            clockFrozen = bundle.getBoolean("clock_frozen", false),
            staticDelayMs = bundle.getDouble("static_delay_ms", 0.0),

            // DAC / Audio
            startTimeCalibrated = bundle.getBoolean("start_time_calibrated", false),
            dacCalibrationCount = bundle.getInt("dac_calibration_count", 0),
            totalFramesWritten = bundle.getLong("total_frames_written", 0L),
            serverTimelineCursorUs = bundle.getLong("server_timeline_cursor_us", 0L),
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

            // Sync Correction
            insertEveryNFrames = bundle.getInt("insert_every_n_frames", 0),
            dropEveryNFrames = bundle.getInt("drop_every_n_frames", 0),
            framesInserted = bundle.getLong("frames_inserted", 0L),
            framesDropped = bundle.getLong("frames_dropped", 0L),
            syncCorrections = bundle.getLong("sync_corrections", 0L),
            reanchorCount = bundle.getLong("reanchor_count", 0L)
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
        mediaControllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        mediaController = null
        mediaControllerFuture = null
    }
}

/**
 * Complete stats state for the Stats Bottom Sheet.
 */
data class StatsState(
    // Connection
    val serverName: String? = null,
    val serverAddress: String? = null,
    val connectionState: String = "Unknown",
    val audioCodec: String = "--",
    val reconnectAttempts: Int = 0,

    // Network
    val networkType: String = "UNKNOWN",
    val networkQuality: String = "UNKNOWN",
    val networkMetered: Boolean = true,
    val wifiRssi: Int = Int.MIN_VALUE,
    val wifiSpeed: Int = -1,
    val wifiFrequency: Int = -1,
    val cellularType: String? = null,

    // Sync Error
    val playbackState: String = "UNKNOWN",
    val syncErrorUs: Long = 0L,
    val smoothedSyncErrorUs: Long = 0L,
    val syncErrorDrift: Double = 0.0,
    val gracePeriodRemainingUs: Long = -1L,

    // Clock Sync
    val clockOffsetUs: Long = 0L,
    val clockDriftPpm: Double = 0.0,
    val clockErrorUs: Long = 0L,
    val clockConverged: Boolean = false,
    val measurementCount: Int = 0,
    val lastTimeSyncAgeMs: Long = -1L,
    val clockFrozen: Boolean = false,
    val staticDelayMs: Double = 0.0,

    // DAC / Audio
    val startTimeCalibrated: Boolean = false,
    val dacCalibrationCount: Int = 0,
    val totalFramesWritten: Long = 0L,
    val serverTimelineCursorUs: Long = 0L,
    val bufferUnderrunCount: Long = 0L,

    // Buffer
    val queuedSamples: Long = 0L,
    val chunksReceived: Long = 0L,
    val chunksPlayed: Long = 0L,
    val chunksDropped: Long = 0L,
    val gapsFilled: Long = 0L,
    val gapSilenceMs: Long = 0L,
    val overlapsTrimmed: Long = 0L,
    val overlapTrimmedMs: Long = 0L,

    // Sync Correction
    val insertEveryNFrames: Int = 0,
    val dropEveryNFrames: Int = 0,
    val framesInserted: Long = 0L,
    val framesDropped: Long = 0L,
    val syncCorrections: Long = 0L,
    val reanchorCount: Long = 0L
) {
    // Derived values
    val syncErrorMs: Double get() = syncErrorUs / 1000.0
    val smoothedSyncErrorMs: Double get() = smoothedSyncErrorUs / 1000.0
    val clockOffsetMs: Double get() = clockOffsetUs / 1000.0
    val clockErrorMs: Double get() = clockErrorUs / 1000.0
    val serverPositionSec: Double get() = serverTimelineCursorUs / 1_000_000.0
    val queuedMs: Long get() = (queuedSamples * 1000) / 48000  // 48kHz sample rate

    val isWifi: Boolean get() = networkType == "WIFI"
    val isCellular: Boolean get() = networkType == "CELLULAR"

    val correctionMode: String get() = when {
        insertEveryNFrames > 0 -> "Insert 1/$insertEveryNFrames"
        dropEveryNFrames > 0 -> "Drop 1/$dropEveryNFrames"
        else -> "None"
    }

    val wifiBand: String get() = when {
        wifiFrequency >= 5000 -> "5 GHz"
        wifiFrequency > 0 -> "2.4 GHz"
        else -> "--"
    }

    val cellularTypeDisplay: String get() = cellularType?.removePrefix("TYPE_") ?: "--"
}

/**
 * Status indicator for color coding.
 */
enum class ThresholdStatus { GOOD, WARNING, BAD }

// Helper functions for threshold status
fun getSyncErrorStatus(errorUs: Long): ThresholdStatus {
    val absError = abs(errorUs)
    return when {
        absError < 2_000L -> ThresholdStatus.GOOD
        absError < 10_000L -> ThresholdStatus.WARNING
        else -> ThresholdStatus.BAD
    }
}

fun getClockErrorStatus(errorUs: Long): ThresholdStatus {
    val absError = abs(errorUs)
    return when {
        absError < 1_000L -> ThresholdStatus.GOOD
        absError < 5_000L -> ThresholdStatus.WARNING
        else -> ThresholdStatus.BAD
    }
}

fun getClockDriftStatus(driftPpm: Double): ThresholdStatus {
    val absDrift = abs(driftPpm)
    return when {
        absDrift < 10.0 -> ThresholdStatus.GOOD
        absDrift < 50.0 -> ThresholdStatus.WARNING
        else -> ThresholdStatus.BAD
    }
}

fun getBufferStatus(ms: Long): ThresholdStatus {
    return when {
        ms < 50 -> ThresholdStatus.BAD
        ms < 200 -> ThresholdStatus.WARNING
        else -> ThresholdStatus.GOOD
    }
}

fun getConnectionStatus(state: String): ThresholdStatus {
    return when {
        state.contains("Connected", ignoreCase = true) -> ThresholdStatus.GOOD
        state.contains("Connecting", ignoreCase = true) -> ThresholdStatus.WARNING
        state.contains("Reconnecting", ignoreCase = true) -> ThresholdStatus.WARNING
        else -> ThresholdStatus.BAD
    }
}

fun getPlaybackStatus(state: String): ThresholdStatus {
    return when (state) {
        "PLAYING" -> ThresholdStatus.GOOD
        "WAITING_FOR_START", "INITIALIZING", "DRAINING" -> ThresholdStatus.WARNING
        "REANCHORING" -> ThresholdStatus.BAD
        else -> ThresholdStatus.WARNING
    }
}
