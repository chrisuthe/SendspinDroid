package com.sendspindroid.sendspin.crypto

import java.util.Base64

/**
 * `java.util.Base64` is available from API 26, which is this module's minSdk,
 * so no third-party encoder is needed on either target.
 */
actual object Base64Url {
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    actual fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    actual fun decodeOrNull(value: String): ByteArray? = try {
        decoder.decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }
}
