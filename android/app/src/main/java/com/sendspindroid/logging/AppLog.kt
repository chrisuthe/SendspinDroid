package com.sendspindroid.logging

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Public facade for on-device logging.
 *
 * Call sites use category-scoped loggers:
 * ```
 * AppLog.Audio.d("chunk queued")
 * AppLog.Protocol.w("bad message type")
 * AppLog.Network.e("dropped", exception)
 * ```
 *
 * Level is global ([level]), set by the user in Settings. Gating happens in [Logger.x] before the
 * `android.util.Log` call. The [LogcatBridge] is the sole writer to the file system; facade calls
 * do not touch disk directly.
 */
object AppLog {

    private const val PREF_LOG_LEVEL = "log_level"
    private const val PREF_LEGACY_DEBUG_LOGGING = "debug_logging_enabled"

    @Volatile
    var level: LogLevel = LogLevel.OFF
        private set

    @Volatile
    internal var writer: LogFileWriter? = null
        private set

    // Bridge is wired in Task 6.
    @Volatile
    internal var bridge: LogcatBridge? = null
        private set

    @Volatile
    private var appContext: Context? = null

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val Audio: Logger = Logger(LogCategory.Audio)
    val Sync: Logger = Logger(LogCategory.Sync)
    val Protocol: Logger = Logger(LogCategory.Protocol)
    val Network: Logger = Logger(LogCategory.Network)
    val Playback: Logger = Logger(LogCategory.Playback)
    val MusicAssistant: Logger = Logger(LogCategory.MusicAssistant)
    val Remote: Logger = Logger(LogCategory.Remote)
    val UI: Logger = Logger(LogCategory.UI)
    val App: Logger = Logger(LogCategory.App)

    /** Session markers for connect/disconnect events. */
    object session {
        fun start(serverName: String, serverAddress: String) {
            App.i("Session started: $serverName ($serverAddress)")
        }

        fun end() {
            App.i("Session ended")
        }
    }

    /**
     * Initialize the logger. Call once at app startup from [com.sendspindroid.MainActivity].
     *
     * Creates the log directory + active file, runs the one-time preference migration from the
     * legacy `debug_logging_enabled` boolean to the new `log_level` string, and applies the
     * resulting level. Bridge startup is handled in [setLevel].
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        val logsDir = File(context.cacheDir, "logs")
        val w = LogFileWriter(logsDir)
        w.init()
        writer = w

        // Also clean up the legacy single-file location if present.
        File(context.cacheDir, "debug.log").takeIf { it.exists() }?.delete()

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getString(PREF_LOG_LEVEL, null)
        val resolved: LogLevel = when {
            stored != null -> runCatching { LogLevel.valueOf(stored) }.getOrDefault(LogLevel.OFF)
            prefs.contains(PREF_LEGACY_DEBUG_LOGGING) -> {
                if (prefs.getBoolean(PREF_LEGACY_DEBUG_LOGGING, false)) LogLevel.DEBUG else LogLevel.OFF
            }
            else -> LogLevel.OFF
        }
        // Persist & strip legacy key regardless of which branch we took, so future runs skip this block.
        prefs.edit()
            .putString(PREF_LOG_LEVEL, resolved.name)
            .remove(PREF_LEGACY_DEBUG_LOGGING)
            .apply()

        bridge?.stop()
        bridge = LogcatBridge(w, bridgeScope)
        setLevel(resolved)
    }

    /**
     * Change the global log level. Persists to preferences and transitions the [LogcatBridge]:
     * - OFF -> X: start(X)
     * - X -> OFF: stop()
     * - X -> Y (both non-OFF): setLevel(Y) which internally does stop() + start(Y)
     * - OFF -> OFF: no-op
     */
    fun setLevel(newLevel: LogLevel) {
        val previous = level
        level = newLevel
        appContext?.let { ctx ->
            PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(PREF_LOG_LEVEL, newLevel.name)
                .apply()
        }
        bridge?.let { br ->
            if (previous == LogLevel.OFF && newLevel != LogLevel.OFF) br.start(newLevel)
            else if (newLevel == LogLevel.OFF && previous != LogLevel.OFF) br.stop()
            else if (newLevel != LogLevel.OFF) br.setLevel(newLevel)
        }
        if (newLevel != LogLevel.OFF) {
            App.i("Log level set to ${newLevel.name}")
        }
    }

    /** For Settings UI. Returns the total size (KB) and file count across rotated files. */
    fun logFileStats(): Pair<Long, Int> {
        val w = writer ?: return 0L to 0
        val files = w.currentFiles()
        val totalBytes = files.sumOf { it.length() }
        return (totalBytes / 1024L) to files.size
    }

    /** Create a share intent with a concatenated log file. */
    fun shareIntent(context: Context): Intent? = writer?.shareIntent(context)

    /** Clear all rotated log files. */
    fun clear() {
        writer?.clear()
    }
}

/**
 * Per-category logger. One instance exists as a property on [AppLog] per [LogCategory].
 * All methods are gate-checked against [AppLog.level] before delegating to [android.util.Log].
 */
class Logger internal constructor(private val category: LogCategory) {

    fun v(msg: String) {
        if (AppLog.level.permits(LogLevel.VERBOSE)) Log.v(category.tag, msg)
    }

    fun d(msg: String) {
        if (AppLog.level.permits(LogLevel.DEBUG)) Log.d(category.tag, msg)
    }

    fun i(msg: String) {
        if (AppLog.level.permits(LogLevel.INFO)) Log.i(category.tag, msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        if (AppLog.level.permits(LogLevel.WARN)) {
            if (t != null) Log.w(category.tag, msg, t) else Log.w(category.tag, msg)
        }
    }

    fun e(msg: String, t: Throwable? = null) {
        if (AppLog.level.permits(LogLevel.ERROR)) {
            if (t != null) Log.e(category.tag, msg, t) else Log.e(category.tag, msg)
        }
    }
}
