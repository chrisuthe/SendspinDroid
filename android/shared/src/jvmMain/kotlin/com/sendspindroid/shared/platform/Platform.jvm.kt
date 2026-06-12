package com.sendspindroid.shared.platform

actual object Platform {
    actual fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()
    actual fun base64Decode(input: String): ByteArray =
        java.util.Base64.getDecoder().decode(input)
    actual fun manufacturer(): String = "JVM"
}
