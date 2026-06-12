package com.sendspindroid.shared.log

actual object Log {
    private fun log(level: String, tag: String, msg: String, tr: Throwable? = null): Int {
        println("$level/$tag: $msg")
        tr?.printStackTrace()
        return 0
    }

    actual fun v(tag: String, msg: String): Int = log("V", tag, msg)
    actual fun d(tag: String, msg: String): Int = log("D", tag, msg)
    actual fun i(tag: String, msg: String): Int = log("I", tag, msg)
    actual fun w(tag: String, msg: String): Int = log("W", tag, msg)
    actual fun w(tag: String, msg: String, tr: Throwable): Int = log("W", tag, msg, tr)
    actual fun e(tag: String, msg: String): Int = log("E", tag, msg)
    actual fun e(tag: String, msg: String, tr: Throwable): Int = log("E", tag, msg, tr)
}
