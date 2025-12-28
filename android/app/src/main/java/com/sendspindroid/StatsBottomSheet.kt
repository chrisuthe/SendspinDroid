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
        // === SYNC STATUS ===
        val playbackState = bundle.getString("playback_state", "UNKNOWN")
        binding.playbackStateValue.text = playbackState
        binding.playbackStateValue.setTextColor(getColorForPlaybackState(playbackState))

        val syncErrorUs = bundle.getLong("sync_error_us", 0L)
        val syncErrorMs = syncErrorUs / 1000.0
        binding.syncErrorValue.text = String.format("%.2f ms", syncErrorMs)
        binding.syncErrorValue.setTextColor(getColorForSyncError(syncErrorUs))

        val trueSyncErrorUs = bundle.getLong("true_sync_error_us", 0L)
        val trueSyncErrorMs = trueSyncErrorUs / 1000.0
        binding.trueSyncErrorValue.text = String.format("%.2f ms", trueSyncErrorMs)
        binding.trueSyncErrorValue.setTextColor(getColorForSyncError(trueSyncErrorUs))

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

        // === CLOCK SYNC ===
        val clockReady = bundle.getBoolean("clock_ready", false)
        binding.clockReadyValue.text = if (clockReady) "Yes" else "No"
        binding.clockReadyValue.setTextColor(
            if (clockReady) getColorGood() else getColorBad()
        )

        val clockOffsetUs = bundle.getLong("clock_offset_us", 0L)
        val clockOffsetMs = clockOffsetUs / 1000.0
        binding.clockOffsetValue.text = String.format("%.0f µs (%.2f ms)",
            clockOffsetUs.toDouble(), clockOffsetMs)

        val clockErrorUs = bundle.getLong("clock_error_us", 0L)
        val clockErrorMs = clockErrorUs / 1000.0
        binding.clockErrorValue.text = String.format("%.0f µs (%.2f ms)",
            clockErrorUs.toDouble(), clockErrorMs)
        binding.clockErrorValue.setTextColor(getColorForClockError(clockErrorUs))

        val measurementCount = bundle.getInt("measurement_count", 0)
        binding.measurementCountValue.text = measurementCount.toString()

        val dacCalibrationCount = bundle.getInt("dac_calibration_count", 0)
        binding.dacCalibrationCountValue.text = dacCalibrationCount.toString()
        binding.dacCalibrationCountValue.setTextColor(
            if (dacCalibrationCount > 0) getColorGood() else getColorWarning()
        )
    }

    // ========================================================================
    // Color Helpers (matching Windows WPF reference design)
    // ========================================================================

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

    private fun getColorForClockError(errorUs: Long): Int {
        val absError = abs(errorUs)
        return when {
            absError < CLOCK_ERROR_GOOD_US -> getColorGood()
            absError < CLOCK_ERROR_WARNING_US -> getColorWarning()
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
