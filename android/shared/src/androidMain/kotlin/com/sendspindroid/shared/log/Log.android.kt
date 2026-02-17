package com.sendspindroid.shared.log

actual object Log {
    actual fun v(tag: String, msg: String): Int = android.util.Log.v(tag, msg)
    actual fun d(tag: String, msg: String): Int = android.util.Log.d(tag, msg)
    actual fun i(tag: String, msg: String): Int = android.util.Log.i(tag, msg)
    actual fun w(tag: String, msg: String): Int = android.util.Log.w(tag, msg)
    actual fun w(tag: String, msg: String, tr: Throwable): Int = android.util.Log.w(tag, msg, tr)
    actual fun e(tag: String, msg: String): Int = android.util.Log.e(tag, msg)
    actual fun e(tag: String, msg: String, tr: Throwable): Int = android.util.Log.e(tag, msg, tr)
}
