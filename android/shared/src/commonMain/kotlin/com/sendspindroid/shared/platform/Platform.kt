package com.sendspindroid.shared.platform

expect object Platform {
    /** Monotonic elapsed time in milliseconds (like SystemClock.elapsedRealtime on Android) */
    fun elapsedRealtimeMs(): Long

    /** Wall clock time in milliseconds */
    fun currentTimeMillis(): Long

    /** Base64 decode a string to bytes */
    fun base64Decode(input: String): ByteArray

    /** Device manufacturer name */
    fun manufacturer(): String
}
