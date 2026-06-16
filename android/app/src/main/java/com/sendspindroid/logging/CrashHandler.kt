package com.sendspindroid.logging

import android.content.Context
import androidx.preference.PreferenceManager
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures otherwise-silent crashes so the app can offer to report them.
 *
 * On an uncaught exception it appends the stack trace to the log file
 * (synchronously, since the process is about to die) and sets a "pending crash"
 * preference with a one-line summary. On the next launch the app reads the flag
 * via [consumePending] and offers to send a report. It always delegates to the
 * previously-installed handler so the OS still records/reports the crash normally.
 */
class CrashHandler private constructor(
    private val appContext: Context,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching { record(thread, throwable) } // never let crash handling crash the crash
        previous?.uncaughtException(thread, throwable)
    }

    private fun record(thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        AppLog.recordCrash("$ts on thread '${thread.name}'\n$stack")

        val summary = "${throwable.javaClass.simpleName}: ${throwable.message ?: "no message"}"
        // commit() (synchronous) so the flag survives the imminent process death.
        PreferenceManager.getDefaultSharedPreferences(appContext).edit()
            .putBoolean(PREF_PENDING_CRASH, true)
            .putString(PREF_CRASH_SUMMARY, summary)
            .commit()
    }

    companion object {
        const val PREF_PENDING_CRASH = "pending_crash_report"
        const val PREF_CRASH_SUMMARY = "pending_crash_summary"

        /** Install once, as early as possible (Application.onCreate). Idempotent. */
        fun install(context: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashHandler) return // already installed
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext, current))
        }

        /**
         * If the previous run crashed, returns its summary and clears the flag;
         * otherwise null. Call once at startup to drive the report prompt.
         */
        fun consumePending(context: Context): String? {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (!prefs.getBoolean(PREF_PENDING_CRASH, false)) return null
            val summary = prefs.getString(PREF_CRASH_SUMMARY, null) ?: "Unexpected error"
            prefs.edit().remove(PREF_PENDING_CRASH).remove(PREF_CRASH_SUMMARY).commit()
            return summary
        }
    }
}
