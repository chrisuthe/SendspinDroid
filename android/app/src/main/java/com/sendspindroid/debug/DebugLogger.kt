package com.sendspindroid.debug

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.sendspindroid.model.SyncStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug logger facade for sharing logs from test installations.
 *
 * This is a thin wrapper around [FileLogger] that provides:
 * - Enable/disable toggle (synced with FileLogger)
 * - Session markers for connect/disconnect events
 * - Share intent creation for the debug log file
 *
 * ## Usage
 * ```kotlin
 * // Enable logging (syncs with FileLogger)
 * DebugLogger.isEnabled = true
 *
 * // Mark session boundaries
 * DebugLogger.startSession("MyServer", "192.168.1.100:8080")
 * // ... app logs via FileLogger.i/d/w/e ...
 * DebugLogger.endSession()
 *
 * // Share the log file
 * val intent = DebugLogger.createShareIntent(context)
 * context.startActivity(intent)
 * ```
 */
object DebugLogger {

    private const val TAG = "DebugLogger"

    // Session tracking for log context.
    // @Volatile ensures cross-thread visibility (L-16): startSession/endSession are
    // called from the main handler, but logStats runs on a debug logging handler and
    // createShareIntent/buildShareMessage can run from any UI thread.
    @Volatile
    private var serverName: String = ""
    @Volatile
    private var serverAddress: String = ""
    @Volatile
    private var sessionStartTimeMs: Long = 0L

    /**
     * Whether debug logging is enabled.
     * Synced with [FileLogger.isEnabled].
     */
    var isEnabled: Boolean
        get() = FileLogger.isEnabled
        set(value) {
            FileLogger.isEnabled = value
            if (value) {
                FileLogger.section("Debug logging enabled")
            }
        }

    /**
     * Start a new logging session.
     * Call this when connecting to a server.
     * Writes a session header to the log file.
     */
    fun startSession(serverName: String, serverAddress: String) {
        this.serverName = serverName
        this.serverAddress = serverAddress
        this.sessionStartTimeMs = System.currentTimeMillis()

        FileLogger.section("SESSION START")
        FileLogger.raw("Server: $serverName")
        FileLogger.raw("Address: $serverAddress")
        FileLogger.raw("Time: ${formatTimestamp(sessionStartTimeMs)}")
        FileLogger.i(TAG, "Session started: $serverName ($serverAddress)")
    }

    /**
     * End the current logging session.
     * Writes a session footer to the log file.
     */
    fun endSession() {
        val duration = getSessionDurationString()
        FileLogger.section("SESSION END")
        FileLogger.raw("Duration: $duration")
        FileLogger.i(TAG, "Session ended after $duration")
    }

    /**
     * Log sync statistics snapshot.
     * Writes key stats to the log file for debugging sync issues.
     */
    fun logStats(stats: SyncStats) {
        if (!isEnabled) return

        // Log a compact stats line for time-series analysis
        FileLogger.d(TAG, "Stats: " +
            "state=${stats.playbackState.name}, " +
            "syncErr=${stats.syncErrorUs}us, " +
            "queue=${stats.queuedSamples}, " +
            "offset=${stats.clockOffsetUs}us, " +
            "insertN=${stats.insertEveryNFrames}, dropN=${stats.dropEveryNFrames}, " +
            "framesIns=${stats.framesInserted}, framesDrop=${stats.framesDropped}")
    }

    /**
     * Get approximate log size indicator.
     * Returns file size in KB, or 0 if not available.
     */
    fun getSampleCount(): Int {
        val file = FileLogger.getLogFile() ?: return 0
        return if (file.exists()) (file.length() / 1024).toInt() else 0
    }

    /**
     * Clear the log file contents.
     */
    fun clear() {
        FileLogger.clear()
    }

    /**
     * Clean up is no longer needed (single file with rotation).
     * Kept for API compatibility.
     */
    fun cleanupOldLogs(context: Context) {
        // No-op: FileLogger handles rotation internally
    }

    /**
     * Create an intent to share the debug log file.
     *
     * @param context Android context for FileProvider
     * @return Share intent, or null if log file is not available
     */
    fun createShareIntent(context: Context): Intent? {
        val logFile = FileLogger.getLogFile()
        if (logFile == null || !logFile.exists()) {
            FileLogger.w(TAG, "Cannot share: log file not available")
            return null
        }

        // Add a final summary before sharing
        FileLogger.section("LOG EXPORT")
        FileLogger.raw("Exported at: ${formatTimestamp(System.currentTimeMillis())}")
        FileLogger.raw("File size: ${logFile.length() / 1024} KB")

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile
            )

            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SendSpin Debug Log - ${getTimestampString()}")
                putExtra(Intent.EXTRA_TEXT, buildShareMessage(context))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to create share intent", e)
            null
        }
    }

    private fun buildShareMessage(context: Context): String {
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        return buildString {
            appendLine("SendSpin Debug Log")
            appendLine()
            appendLine("App: $versionName")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            if (serverName.isNotEmpty()) {
                appendLine("Server: $serverName")
            }
        }
    }

    private fun getTimestampString(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    }

    private fun formatTimestamp(timeMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))
    }

    private fun getSessionDurationString(): String {
        if (sessionStartTimeMs == 0L) return "unknown"
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
