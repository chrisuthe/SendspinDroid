package com.sendspindroid.shared.platform

import android.os.Build
import android.os.SystemClock

actual object Platform {
    actual fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()
    actual fun base64Decode(input: String): ByteArray =
        java.util.Base64.getDecoder().decode(input)
    actual fun manufacturer(): String = Build.MANUFACTURER
    actual fun sha256Hex(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.encodeToByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
