package com.sendspindroid.logging

/**
 * Global log level for the app.
 *
 * Ordered from most verbose to silent. Each level permits itself and all levels above it in severity:
 * VERBOSE permits every call, OFF permits none.
 *
 * Used by [AppLog] to gate facade calls and by [LogcatBridge] to filter the logcat subprocess output.
 */
enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    OFF;

    /**
     * Logcat priority letter for the `*:<priority>` filter argument.
     * OFF is not valid for the subprocess -- the bridge should be stopped instead.
     */
    fun logcatPriorityChar(): Char = when (this) {
        VERBOSE -> 'V'
        DEBUG -> 'D'
        INFO -> 'I'
        WARN -> 'W'
        ERROR -> 'E'
        OFF -> throw IllegalStateException("OFF has no logcat priority; stop the bridge instead")
    }

    /**
     * True if a log call at [callLevel] should be emitted given the current gate level.
     * OFF permits nothing.
     */
    fun permits(callLevel: LogLevel): Boolean {
        if (this == OFF) return false
        return callLevel.ordinal >= this.ordinal
    }
}
