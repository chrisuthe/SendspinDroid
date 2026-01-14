package com.sendspindroid

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.sendspindroid.databinding.FragmentStatsBinding
import com.sendspindroid.playback.PlaybackService
import kotlin.math.abs

/**
 * Stats for Nerds - Bottom Sheet showing real-time audio synchronization diagnostics.
 *
 * Displays detailed stats about:
 * - Clock synchronization (Kalman filter state)
 * - Audio buffering (chunks queued, played, dropped)
 * - Sync correction (sample insert/drop)
 * - Playback state machine
 *
 * Updates at 10 Hz (100ms intervals) for smooth real-time monitoring.
 *
 * ## Design Notes
 * - Material 3 bottom sheet with dark technical aesthetic
 * - Monospace font for values (like Windows reference)
 * - Color-coded status: green (good), yellow (warning), red (bad)
 * - Section headers in purple (Material 3 primary)
 *
 * ## Usage
 * ```kotlin
 * StatsBottomSheet().show(supportFragmentManager, "stats")
 * ```
 */
class StatsBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "StatsBottomSheet"
        private const val UPDATE_INTERVAL_MS = 100L  // 10 Hz update rate

        // Thresholds for color-coded status (matching Python reference)
        private const val SYNC_ERROR_GOOD_US = 2_000L      // <2ms = green
        private const val SYNC_ERROR_WARNING_US = 10_000L  // 2-10ms = yellow
        // >10ms = red

        private const val CLOCK_ERROR_GOOD_US = 1_000L     // <1ms = green
        private const val CLOCK_ERROR_WARNING_US = 5_000L  // 1-5ms = yellow
        // >5ms = red

        // Clock drift thresholds in ppm
        private const val CLOCK_DRIFT_GOOD_PPM = 10.0      // <10 ppm = green
        private const val CLOCK_DRIFT_WARNING_PPM = 50.0   // 10-50 ppm = yellow
        // >50 ppm = red

        // Last sync age thresholds in ms
        private const val LAST_SYNC_GOOD_MS = 2_000L       // <2s = green
        private const val LAST_SYNC_WARNING_MS = 10_000L   // 2-10s = yellow
        // >10s = red
    }

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    // MediaController to fetch stats from PlaybackService
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    // Update handler
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        initializeMediaController()
    }

    private fun setupUI() {
        binding.closeButton.setOnClickListener {
            dismiss()
        }
    }

    /**
     * Initializes MediaController to communicate with PlaybackService.
     */
    private fun initializeMediaController() {
        val context = requireContext()
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
                    Log.d(TAG, "MediaController connected to PlaybackService")
                    startUpdates()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect MediaController", e)
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    /**
     * Starts periodic stats updates at UPDATE_INTERVAL_MS.
     */
    private fun startUpdates() {
        updateRunnable = object : Runnable {
            override fun run() {
                if (isAdded && mediaController != null) {
                    requestStatsUpdate()
                    handler.postDelayed(this, UPDATE_INTERVAL_MS)
                }
            }
        }
        handler.post(updateRunnable!!)
    }

    /**
     * Requests stats from PlaybackService via custom command.
     * PlaybackService responds via session extras update.
     */
    private fun requestStatsUpdate() {
        val controller = mediaController ?: return

        try {
            // Send GET_STATS command to service
            val command = SessionCommand(PlaybackService.COMMAND_GET_STATS, Bundle.EMPTY)
            val result = controller.sendCustomCommand(command, Bundle.EMPTY)

            // Parse stats from result bundle
            result.addListener(
                {
                    try {
                        val statsBundle = result.get().extras
                        updateStatsUI(statsBundle)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get stats from service", e)
                    }
                },
                MoreExecutors.directExecutor()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request stats", e)
        }
    }

    /**
     * Updates the UI with stats from the bundle.
     */
    private fun updateStatsUI(bundle: Bundle) {
        // === CONNECTION ===
        val serverName = bundle.getString("server_name", null)
        binding.serverNameValue.text = serverName ?: "--"
        binding.serverNameValue.setTextColor(
            if (serverName != null) getColorNeutral() else getColorWarning()
        )

        val serverAddress = bundle.getString("server_address", null)
        binding.serverAddressValue.text = serverAddress ?: "--"
        binding.serverAddressValue.setTextColor(getColorNeutral())

        val connectionState = bundle.getString("connection_state", "Unknown")
        binding.connectionStateValue.text = connectionState
        binding.connectionStateValue.setTextColor(getColorForConnectionState(connectionState))

        val audioCodec = bundle.getString("audio_codec", "--")
        binding.audioCodecValue.text = audioCodec
        binding.audioCodecValue.setTextColor(getColorNeutral())

        // === NETWORK ===
        val networkType = bundle.getString("network_type", "UNKNOWN")
        binding.networkTypeValue.text = networkType
        binding.networkTypeValue.setTextColor(getColorForNetworkType(networkType))

        val networkQuality = bundle.getString("network_quality", "UNKNOWN")
        binding.networkQualityValue.text = networkQuality
        binding.networkQualityValue.setTextColor(getColorForNetworkQuality(networkQuality))

        val networkMetered = bundle.getBoolean("network_metered", true)
        binding.networkMeteredValue.text = if (networkMetered) "Yes" else "No"
        binding.networkMeteredValue.setTextColor(if (networkMetered) getColorWarning() else getColorGood())

        // Show WiFi-specific rows only when on WiFi
        val isWifi = networkType == "WIFI"
        binding.wifiRssiRow.visibility = if (isWifi) View.VISIBLE else View.GONE
        binding.wifiSpeedRow.visibility = if (isWifi) View.VISIBLE else View.GONE

        if (isWifi) {
            val wifiRssi = bundle.getInt("wifi_rssi", Int.MIN_VALUE)
            if (wifiRssi != Int.MIN_VALUE) {
                binding.wifiRssiValue.text = "$wifiRssi dBm"
                binding.wifiRssiValue.setTextColor(getColorForWifiRssi(wifiRssi))
            } else {
                binding.wifiRssiValue.text = "--"
                binding.wifiRssiValue.setTextColor(getColorNeutral())
            }

            val wifiSpeed = bundle.getInt("wifi_link_speed", -1)
            if (wifiSpeed > 0) {
                binding.wifiSpeedValue.text = "$wifiSpeed Mbps"
                binding.wifiSpeedValue.setTextColor(getColorNeutral())
            } else {
                binding.wifiSpeedValue.text = "--"
                binding.wifiSpeedValue.setTextColor(getColorNeutral())
            }
        }

        // Show Cellular-specific row only when on Cellular
        val isCellular = networkType == "CELLULAR"
        binding.cellularTypeRow.visibility = if (isCellular) View.VISIBLE else View.GONE

        if (isCellular) {
            val cellularType = bundle.getString("cellular_type", null)
            if (cellularType != null) {
                // Format cellular type for display (TYPE_LTE -> LTE)
                val displayType = cellularType.removePrefix("TYPE_")
                binding.cellularTypeValue.text = displayType
                binding.cellularTypeValue.setTextColor(getColorForCellularType(cellularType))
            } else {
                binding.cellularTypeValue.text = "--"
                binding.cellularTypeValue.setTextColor(getColorNeutral())
            }
        }

        // === SYNC ERROR ===
        val playbackState = bundle.getString("playback_state", "UNKNOWN")
        binding.playbackStateValue.text = playbackState
        binding.playbackStateValue.setTextColor(getColorForPlaybackState(playbackState))

        val syncErrorUs = bundle.getLong("sync_error_us", 0L)
        val syncErrorMs = syncErrorUs / 1000.0
        binding.syncErrorValue.text = String.format("%+.2f ms", syncErrorMs)
        binding.syncErrorValue.setTextColor(getColorForSyncError(syncErrorUs))

        val syncErrorDrift = bundle.getDouble("sync_error_drift", 0.0)
        binding.syncErrorDriftValue.text = String.format("%+.4f", syncErrorDrift)
        binding.syncErrorDriftValue.setTextColor(getColorForDrift(syncErrorDrift))

        val gracePeriodRemainingUs = bundle.getLong("grace_period_remaining_us", -1L)
        if (gracePeriodRemainingUs >= 0) {
            val gracePeriodMs = gracePeriodRemainingUs / 1000.0
            binding.gracePeriodValue.text = String.format("%.1fs", gracePeriodMs / 1000.0)
            binding.gracePeriodValue.setTextColor(getColorWarning())
        } else {
            binding.gracePeriodValue.text = "Inactive"
            binding.gracePeriodValue.setTextColor(getColorGood())
        }

        // === CLOCK SYNC ===
        val clockOffsetUs = bundle.getLong("clock_offset_us", 0L)
        val clockOffsetMs = clockOffsetUs / 1000.0
        binding.clockOffsetValue.text = String.format("%+.2f ms", clockOffsetMs)

        val clockDriftPpm = bundle.getDouble("clock_drift_ppm", 0.0)
        binding.clockDriftValue.text = String.format("%+.3f ppm", clockDriftPpm)
        binding.clockDriftValue.setTextColor(getColorForClockDrift(clockDriftPpm))

        val clockErrorUs = bundle.getLong("clock_error_us", 0L)
        val clockErrorMs = clockErrorUs / 1000.0
        binding.clockErrorValue.text = String.format("±%.2f ms", clockErrorMs)
        binding.clockErrorValue.setTextColor(getColorForClockError(clockErrorUs))

        val clockConverged = bundle.getBoolean("clock_converged", false)
        binding.clockConvergedValue.text = if (clockConverged) "Yes" else "No"
        binding.clockConvergedValue.setTextColor(
            if (clockConverged) getColorGood() else getColorWarning()
        )

        val measurementCount = bundle.getInt("measurement_count", 0)
        binding.measurementCountValue.text = measurementCount.toString()

        val lastTimeSyncAgeMs = bundle.getLong("last_time_sync_age_ms", -1L)
        if (lastTimeSyncAgeMs >= 0) {
            binding.lastTimeSyncValue.text = String.format("%.1fs ago", lastTimeSyncAgeMs / 1000.0)
            binding.lastTimeSyncValue.setTextColor(getColorForLastSync(lastTimeSyncAgeMs))
        } else {
            binding.lastTimeSyncValue.text = "--"
            binding.lastTimeSyncValue.setTextColor(getColorWarning())
        }

        // === DAC / AUDIO ===
        val startTimeCalibrated = bundle.getBoolean("start_time_calibrated", false)
        binding.dacCalibratedValue.text = if (startTimeCalibrated) "Yes" else "No"
        binding.dacCalibratedValue.setTextColor(
            if (startTimeCalibrated) getColorGood() else getColorWarning()
        )

        val dacCalibrationCount = bundle.getInt("dac_calibration_count", 0)
        binding.dacCalibrationCountValue.text = dacCalibrationCount.toString()

        val totalFramesWritten = bundle.getLong("total_frames_written", 0L)
        binding.framesWrittenValue.text = formatNumber(totalFramesWritten)

        val serverTimelineCursorUs = bundle.getLong("server_timeline_cursor_us", 0L)
        val serverPositionSec = serverTimelineCursorUs / 1_000_000.0
        binding.serverPositionValue.text = String.format("%.1fs", serverPositionSec)

        val bufferUnderrunCount = bundle.getLong("buffer_underrun_count", 0L)
        binding.bufferUnderrunsValue.text = bufferUnderrunCount.toString()
        binding.bufferUnderrunsValue.setTextColor(
            if (bufferUnderrunCount > 0) getColorBad() else getColorGood()
        )

        // === BUFFER ===
        val queuedSamples = bundle.getLong("queued_samples", 0L)
        val sampleRate = 48000 // Fixed in SendSpin protocol
        val queuedMs = (queuedSamples * 1000) / sampleRate
        binding.queuedAudioValue.text = String.format("%d ms", queuedMs)
        binding.queuedAudioValue.setTextColor(getColorForBufferLevel(queuedMs))

        val chunksReceived = bundle.getLong("chunks_received", 0L)
        binding.chunksReceivedValue.text = chunksReceived.toString()

        val chunksPlayed = bundle.getLong("chunks_played", 0L)
        binding.chunksPlayedValue.text = chunksPlayed.toString()

        val chunksDropped = bundle.getLong("chunks_dropped", 0L)
        binding.chunksDroppedValue.text = chunksDropped.toString()
        binding.chunksDroppedValue.setTextColor(
            if (chunksDropped > 0) getColorBad() else getColorNeutral()
        )

        val gapsFilled = bundle.getLong("gaps_filled", 0L)
        val gapSilenceMs = bundle.getLong("gap_silence_ms", 0L)
        binding.gapsFilledValue.text = String.format("%d (%d ms)", gapsFilled, gapSilenceMs)
        binding.gapsFilledValue.setTextColor(
            if (gapsFilled > 0) getColorWarning() else getColorNeutral()
        )

        val overlapsTrimmed = bundle.getLong("overlaps_trimmed", 0L)
        val overlapTrimmedMs = bundle.getLong("overlap_trimmed_ms", 0L)
        binding.overlapsTrimmedValue.text = String.format("%d (%d ms)", overlapsTrimmed, overlapTrimmedMs)
        binding.overlapsTrimmedValue.setTextColor(
            if (overlapsTrimmed > 0) getColorWarning() else getColorNeutral()
        )

        // === SYNC CORRECTION ===
        val insertEveryNFrames = bundle.getInt("insert_every_n_frames", 0)
        val dropEveryNFrames = bundle.getInt("drop_every_n_frames", 0)

        val correctionMode = when {
            insertEveryNFrames > 0 -> "Insert 1/$insertEveryNFrames"
            dropEveryNFrames > 0 -> "Drop 1/$dropEveryNFrames"
            else -> "None"
        }
        binding.correctionModeValue.text = correctionMode
        binding.correctionModeValue.setTextColor(
            if (correctionMode == "None") getColorGood() else getColorWarning()
        )

        val framesInserted = bundle.getLong("frames_inserted", 0L)
        binding.framesInsertedValue.text = framesInserted.toString()

        val framesDropped = bundle.getLong("frames_dropped", 0L)
        binding.framesDroppedValue.text = framesDropped.toString()

        val syncCorrections = bundle.getLong("sync_corrections", 0L)
        binding.totalCorrectionsValue.text = syncCorrections.toString()

        val reanchorCount = bundle.getLong("reanchor_count", 0L)
        binding.reanchorsValue.text = reanchorCount.toString()
        binding.reanchorsValue.setTextColor(
            if (reanchorCount > 0) getColorWarning() else getColorGood()
        )
    }

    /**
     * Formats a large number with commas for readability.
     */
    private fun formatNumber(value: Long): String {
        return String.format("%,d", value)
    }

    // ========================================================================
    // Color Helpers (matching Windows WPF reference design)
    // ========================================================================

    private fun getColorForConnectionState(state: String): Int {
        return when {
            state.contains("Connected", ignoreCase = true) -> getColorGood()
            state.contains("Connecting", ignoreCase = true) -> getColorWarning()
            state.contains("Reconnecting", ignoreCase = true) -> getColorWarning()
            else -> getColorBad()
        }
    }

    private fun getColorForPlaybackState(state: String): Int {
        return when (state) {
            "PLAYING" -> getColorGood()
            "WAITING_FOR_START" -> getColorWarning()
            "INITIALIZING" -> getColorWarning()
            "REANCHORING" -> getColorBad()
            else -> getColorNeutral()
        }
    }

    private fun getColorForSyncError(errorUs: Long): Int {
        val absError = abs(errorUs)
        return when {
            absError < SYNC_ERROR_GOOD_US -> getColorGood()
            absError < SYNC_ERROR_WARNING_US -> getColorWarning()
            else -> getColorBad()
        }
    }

    private fun getColorForDrift(drift: Double): Int {
        val absDrift = abs(drift)
        return when {
            absDrift < 0.0001 -> getColorGood()    // Very small drift = good
            absDrift < 0.001 -> getColorWarning()   // Small drift = warning
            else -> getColorBad()                   // Large drift = bad
        }
    }

    private fun getColorForClockError(errorUs: Long): Int {
        val absError = abs(errorUs)
        return when {
            absError < CLOCK_ERROR_GOOD_US -> getColorGood()
            absError < CLOCK_ERROR_WARNING_US -> getColorWarning()
            else -> getColorBad()
        }
    }

    private fun getColorForClockDrift(driftPpm: Double): Int {
        val absDrift = abs(driftPpm)
        return when {
            absDrift < CLOCK_DRIFT_GOOD_PPM -> getColorGood()
            absDrift < CLOCK_DRIFT_WARNING_PPM -> getColorWarning()
            else -> getColorBad()
        }
    }

    private fun getColorForLastSync(ageMs: Long): Int {
        return when {
            ageMs < LAST_SYNC_GOOD_MS -> getColorGood()
            ageMs < LAST_SYNC_WARNING_MS -> getColorWarning()
            else -> getColorBad()
        }
    }

    private fun getColorForBufferLevel(ms: Long): Int {
        return when {
            ms < 50 -> getColorBad()     // <50ms = low buffer (red)
            ms < 200 -> getColorWarning() // 50-200ms = medium (yellow)
            else -> getColorGood()         // >200ms = healthy (green)
        }
    }

    private fun getColorForNetworkType(type: String): Int {
        return when (type) {
            "WIFI" -> getColorGood()
            "ETHERNET" -> getColorGood()
            "CELLULAR" -> getColorWarning()
            "VPN" -> getColorNeutral()
            else -> getColorNeutral()
        }
    }

    private fun getColorForNetworkQuality(quality: String): Int {
        return when (quality) {
            "EXCELLENT" -> getColorGood()
            "GOOD" -> getColorGood()
            "FAIR" -> getColorWarning()
            "POOR" -> getColorBad()
            else -> getColorNeutral()
        }
    }

    private fun getColorForWifiRssi(rssi: Int): Int {
        return when {
            rssi > -50 -> getColorGood()     // Excellent
            rssi > -65 -> getColorGood()     // Good
            rssi > -75 -> getColorWarning()  // Fair
            else -> getColorBad()             // Poor
        }
    }

    private fun getColorForCellularType(type: String): Int {
        return when (type) {
            "TYPE_5G" -> getColorGood()
            "TYPE_LTE" -> getColorGood()
            "TYPE_3G" -> getColorWarning()
            "TYPE_2G" -> getColorBad()
            else -> getColorNeutral()
        }
    }

    // Color values matching Windows reference: green, yellow, red
    private fun getColorGood(): Int =
        ContextCompat.getColor(requireContext(), R.color.stats_value_good)

    private fun getColorWarning(): Int =
        ContextCompat.getColor(requireContext(), R.color.stats_value_warning)

    private fun getColorBad(): Int =
        ContextCompat.getColor(requireContext(), R.color.stats_value_bad)

    private fun getColorNeutral(): Int =
        ContextCompat.getColor(requireContext(), R.color.stats_value_neutral)

    override fun onDestroyView() {
        super.onDestroyView()

        // Stop updates
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null

        // Release MediaController
        mediaControllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        mediaController = null
        mediaControllerFuture = null

        _binding = null
    }
}
