package com.sendspindroid.debug

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.sendspindroid.model.SyncStats
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Debug logger for capturing sync statistics over time.
 *
 * Collects periodic sync stats samples in a ring buffer and allows exporting
 * them to a shareable file for debugging sync issues.
 *
 * ## Usage
 * ```kotlin
 * // Enable logging
 * DebugLogger.isEnabled = true
 *
 * // Log stats periodically (called from playback service)
 * DebugLogger.logStats(syncStats)
 *
 * // Export and share
 * val intent = DebugLogger.createShareIntent(context)
 * context.startActivity(intent)
 * ```
 */
object DebugLogger {

    private const val TAG = "DebugLogger"

    // Maximum number of samples to keep (at 1 sample/sec = ~30 minutes)
    private const val MAX_SAMPLES = 1800

    // Minimum interval between samples (milliseconds)
    private const val MIN_SAMPLE_INTERVAL_MS = 1000L

    /**
     * Whether debug logging is enabled.
     * When disabled, logStats() does nothing.
     */
    @Volatile
    var isEnabled: Boolean = false

    // Ring buffer of stats samples with timestamps
    private val samples = ConcurrentLinkedDeque<StatsSnapshot>()

    // Timestamp of last sample to enforce minimum interval
    private var lastSampleTimeMs = 0L

    // Session start time for relative timestamps
    private var sessionStartTimeMs = 0L

    // Connection info for context
    private var serverName: String = ""
    private var serverAddress: String = ""

    /**
     * A timestamped stats snapshot.
     */
    data class StatsSnapshot(
        val timestampMs: Long,
        val relativeTimeMs: Long,
        val stats: SyncStats
    )

    /**
     * Start a new logging session.
     * Call this when connecting to a server.
     */
    fun startSession(serverName: String, serverAddress: String) {
        this.serverName = serverName
        this.serverAddress = serverAddress
        this.sessionStartTimeMs = System.currentTimeMillis()
        samples.clear()
        Log.d(TAG, "Debug logging session started: $serverName ($serverAddress)")
    }

    /**
     * End the current logging session.
     */
    fun endSession() {
        Log.d(TAG, "Debug logging session ended. ${samples.size} samples collected.")
    }

    /**
     * Log a stats sample if enabled and minimum interval has passed.
     */
    fun logStats(stats: SyncStats) {
        if (!isEnabled) return

        val now = System.currentTimeMillis()

        // Enforce minimum interval
        if (now - lastSampleTimeMs < MIN_SAMPLE_INTERVAL_MS) {
            return
        }
        lastSampleTimeMs = now

        // Create snapshot
        val snapshot = StatsSnapshot(
            timestampMs = now,
            relativeTimeMs = now - sessionStartTimeMs,
            stats = stats
        )
        samples.addLast(snapshot)

        // Trim to max size
        while (samples.size > MAX_SAMPLES) {
            samples.pollFirst()
        }
    }

    /**
     * Get the current sample count.
     */
    fun getSampleCount(): Int = samples.size

    /**
     * Clear all collected samples.
     */
    fun clear() {
        samples.clear()
        Log.d(TAG, "Debug log cleared")
    }

    /**
     * Export logs to a file and return a share intent.
     *
     * @param context Android context for file operations
     * @return Intent for sharing the log file, or null if export failed
     */
    fun createShareIntent(context: Context): Intent? {
        try {
            val logContent = generateLogContent(context)
            val logFile = writeLogFile(context, logContent)

            if (logFile == null) {
                Log.e(TAG, "Failed to write log file")
                return null
            }

            // Create share intent using FileProvider
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile
            )

            return Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SendSpin Debug Log - ${getTimestampString()}")
                putExtra(Intent.EXTRA_TEXT, "SendSpin debug log attached. ${samples.size} samples collected over ${getSessionDurationString()}.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create share intent", e)
            return null
        }
    }

    /**
     * Generate the log file content.
     */
    private fun generateLogContent(context: Context): String {
        val sb = StringBuilder()

        // Get version info from package manager
        val (versionName, versionCode) = try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val vName = packageInfo.versionName ?: "unknown"
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            Pair(vName, vCode)
        } catch (e: PackageManager.NameNotFoundException) {
            Pair("unknown", 0L)
        }

        // Header
        sb.appendLine("=" .repeat(60))
        sb.appendLine("SENDSPIN DEBUG LOG")
        sb.appendLine("=" .repeat(60))
        sb.appendLine()

        // Device & App Info
        sb.appendLine("--- DEVICE INFO ---")
        sb.appendLine("App Version: $versionName ($versionCode)")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Build: ${Build.DISPLAY}")
        sb.appendLine()

        // Session Info
        sb.appendLine("--- SESSION INFO ---")
        sb.appendLine("Server: $serverName")
        sb.appendLine("Address: $serverAddress")
        sb.appendLine("Session Start: ${formatTimestamp(sessionStartTimeMs)}")
        sb.appendLine("Log Generated: ${formatTimestamp(System.currentTimeMillis())}")
        sb.appendLine("Duration: ${getSessionDurationString()}")
        sb.appendLine("Samples: ${samples.size}")
        sb.appendLine()

        // Stats Summary
        sb.appendLine("--- STATS SUMMARY ---")
        val snapshotList = samples.toList()
        if (snapshotList.isNotEmpty()) {
            val syncErrors = snapshotList.map { it.stats.trueSyncErrorUs }
            val avgSyncError = syncErrors.average()
            val maxSyncError = syncErrors.maxOrNull() ?: 0
            val minSyncError = syncErrors.minOrNull() ?: 0

            sb.appendLine("Sync Error (us): avg=${avgSyncError.toLong()}, min=$minSyncError, max=$maxSyncError")

            val lastStats = snapshotList.last().stats
            sb.appendLine("Total Chunks: received=${lastStats.chunksReceived}, played=${lastStats.chunksPlayed}, dropped=${lastStats.chunksDropped}")
            sb.appendLine("Frame Corrections: inserted=${lastStats.framesInserted}, dropped=${lastStats.framesDropped}")
            sb.appendLine("Gaps Filled: ${lastStats.gapsFilled} (${lastStats.gapSilenceMs}ms)")
            sb.appendLine("Overlaps Trimmed: ${lastStats.overlapsTrimmed} (${lastStats.overlapTrimmedMs}ms)")
        }
        sb.appendLine()

        // Time Series Data Header
        sb.appendLine("--- TIME SERIES DATA ---")
        sb.appendLine("Columns: RelativeTime(ms), PlaybackState, SyncError(us), QueuedSamples, ClockOffset(us), ClockError(us), InsertN, DropN, FramesIns, FramesDrop, ChunksDrop")
        sb.appendLine()

        // Time Series Data
        for (snapshot in snapshotList) {
            val s = snapshot.stats
            sb.appendLine(
                "${snapshot.relativeTimeMs}," +
                "${s.playbackState.name}," +
                "${s.trueSyncErrorUs}," +
                "${s.queuedSamples}," +
                "${s.clockOffsetUs}," +
                "${s.clockErrorUs}," +
                "${s.insertEveryNFrames}," +
                "${s.dropEveryNFrames}," +
                "${s.framesInserted}," +
                "${s.framesDropped}," +
                "${s.chunksDropped}"
            )
        }

        sb.appendLine()
        sb.appendLine("--- END OF LOG ---")

        return sb.toString()
    }

    /**
     * Write log content to a file in the cache directory.
     */
    private fun writeLogFile(context: Context, content: String): File? {
        return try {
            // Create logs directory in cache
            val logsDir = File(context.cacheDir, "debug_logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }

            // Create timestamped log file
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val logFile = File(logsDir, "sendspin_debug_$timestamp.txt")

            logFile.writeText(content)
            Log.d(TAG, "Log written to: ${logFile.absolutePath} (${content.length} bytes)")

            logFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log file", e)
            null
        }
    }

    /**
     * Clean up old log files (keep last 5).
     */
    fun cleanupOldLogs(context: Context) {
        try {
            val logsDir = File(context.cacheDir, "debug_logs")
            if (!logsDir.exists()) return

            val logFiles = logsDir.listFiles { file ->
                file.name.startsWith("sendspin_debug_") && file.name.endsWith(".txt")
            }?.sortedByDescending { it.lastModified() } ?: return

            // Delete all but the 5 most recent
            logFiles.drop(5).forEach { file ->
                file.delete()
                Log.d(TAG, "Deleted old log: ${file.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup old logs", e)
        }
    }

    private fun getTimestampString(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }

    private fun formatTimestamp(timeMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))
    }

    private fun getSessionDurationString(): String {
        val durationMs = System.currentTimeMillis() - sessionStartTimeMs
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / 60000) % 60
        val hours = durationMs / 3600000
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}
