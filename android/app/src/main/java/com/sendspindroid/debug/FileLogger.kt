package com.sendspindroid.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-based logger for debugging.
 *
 * When [isEnabled] is true, logs are written to a file in the app's cache directory
 * in addition to Android's logcat. This allows collecting logs from test devices
 * where logcat access may be limited.
 *
 * ## Usage
 * ```kotlin
 * // Initialize once at app startup
 * FileLogger.init(context)
 *
 * // Enable file logging (e.g., from settings)
 * FileLogger.isEnabled = true
 *
 * // Log messages (always goes to logcat, file when enabled)
 * FileLogger.i("MyTag", "Info message")
 * FileLogger.d("MyTag", "Debug message")
 * FileLogger.w("MyTag", "Warning message")
 * FileLogger.e("MyTag", "Error message", exception)
 * ```
 *
 * The log file can be shared via [getLogFile] or pulled via adb:
 * ```
 * adb pull /data/data/com.sendspindroid/cache/debug.log
 * ```
 */
object FileLogger {
    private const val TAG = "FileLogger"
    private const val LOG_FILE = "debug.log"
    private const val MAX_SIZE = 2 * 1024 * 1024L // 2MB max before rotation

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val writeLock = Any()

    /**
     * Whether file logging is enabled.
     * When false, only Android logcat is used.
     * When true, logs also go to the debug.log file.
     */
    @Volatile
    var isEnabled: Boolean = false

    /**
     * Initialize the logger. Call once at app startup.
     * Creates/clears the log file in the app's cache directory.
     */
    fun init(context: Context) {
        logFile = File(context.cacheDir, LOG_FILE)
        // Clear old log on init
        logFile?.writeText("=== SendSpinDroid Debug Log ===\n")
        logFile?.appendText("Initialized at ${Date()}\n")
        logFile?.appendText("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        logFile?.appendText("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
        logFile?.appendText("${"=".repeat(50)}\n\n")
    }

    /**
     * Log an info message.
     * Always goes to logcat. Goes to file when [isEnabled] is true.
     */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        if (isEnabled) writeToFile("I", tag, message)
    }

    /**
     * Log a debug message.
     * Always goes to logcat. Goes to file when [isEnabled] is true.
     */
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        if (isEnabled) writeToFile("D", tag, message)
    }

    /**
     * Log a warning message.
     * Always goes to logcat. Goes to file when [isEnabled] is true.
     */
    fun w(tag: String, message: String) {
        Log.w(tag, message)
        if (isEnabled) writeToFile("W", tag, message)
    }

    /**
     * Log an error message with optional throwable.
     * Always goes to logcat. Goes to file when [isEnabled] is true.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        if (isEnabled) {
            writeToFile("E", tag, message)
            throwable?.let { writeStackTrace(it) }
        }
    }

    /**
     * Write a raw line to the log file (no formatting).
     * Useful for section headers or structured data.
     */
    fun raw(line: String) {
        if (!isEnabled) return
        val file = logFile ?: return
        synchronized(writeLock) {
            try {
                checkRotation(file)
                file.appendText("$line\n")
            } catch (e: Exception) {
                // Ignore file errors
            }
        }
    }

    /**
     * Add a section marker to the log for easier reading.
     */
    fun section(title: String) {
        if (!isEnabled) return
        raw("\n--- $title ---")
    }

    /**
     * Get the log file for sharing.
     * Returns null if not initialized.
     */
    fun getLogFile(): File? = logFile

    /**
     * Get the log file path.
     * Returns null if not initialized.
     */
    fun getLogPath(): String? = logFile?.absolutePath

    /**
     * Clear the log file contents.
     */
    fun clear() {
        synchronized(writeLock) {
            logFile?.writeText("=== Log cleared at ${Date()} ===\n\n")
        }
    }

    private fun writeToFile(level: String, tag: String, message: String) {
        val file = logFile ?: return
        synchronized(writeLock) {
            try {
                checkRotation(file)
                val timestamp = dateFormat.format(Date())
                file.appendText("$timestamp $level/$tag: $message\n")
            } catch (e: Exception) {
                // Ignore file errors - don't want logging to crash the app
            }
        }
    }

    private fun writeStackTrace(throwable: Throwable) {
        val file = logFile ?: return
        synchronized(writeLock) {
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                file.appendText(sw.toString())
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun checkRotation(file: File) {
        if (file.exists() && file.length() > MAX_SIZE) {
            // Truncate and start fresh to avoid reading the entire file into memory.
            // Previous approach read the full 2MB file into a String on every rotation,
            // causing GC pressure under active logging.
            try {
                file.writeText("=== Log rotated at ${Date()} ===\n\n")
            } catch (e: Exception) {
                // Ignore - rotation is best-effort
            }
        }
    }
}
