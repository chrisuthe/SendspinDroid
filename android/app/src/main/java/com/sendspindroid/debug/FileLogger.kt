package com.sendspindroid.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple file-based logger for debugging on devices where logcat is disabled.
 * Writes to app's cache directory which can be pulled via adb.
 */
object FileLogger {
    private const val TAG = "FileLogger"
    private const val LOG_FILE = "debug.log"
    private const val MAX_SIZE = 1024 * 1024 // 1MB max

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        logFile = File(context.cacheDir, LOG_FILE)
        // Clear old log on init
        logFile?.writeText("=== SendSpinDroid Debug Log ===\n")
        log("FileLogger", "Initialized at ${Date()}")
    }

    fun log(tag: String, message: String) {
        val file = logFile ?: return
        try {
            // Rotate if too large
            if (file.exists() && file.length() > MAX_SIZE) {
                file.writeText("=== Log rotated ===\n")
            }

            val timestamp = dateFormat.format(Date())
            val line = "$timestamp $tag: $message\n"

            file.appendText(line)

            // Also log to Android log (in case it works)
            Log.d(tag, message)
        } catch (e: Exception) {
            // Ignore file errors
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val file = logFile ?: return
        try {
            val timestamp = dateFormat.format(Date())
            file.appendText("$timestamp $tag: ERROR: $message\n")
            throwable?.let {
                val sw = java.io.StringWriter()
                it.printStackTrace(PrintWriter(sw))
                file.appendText(sw.toString())
            }
            Log.e(tag, message, throwable)
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun getLogPath(): String? = logFile?.absolutePath
}
