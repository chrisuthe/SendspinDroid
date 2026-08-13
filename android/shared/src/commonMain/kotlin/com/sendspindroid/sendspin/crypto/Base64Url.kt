package com.sendspindroid.sendspin.crypto

/**
 * base64url without padding - RFC 4648 section 5.
 *
 * Sendspin uses this encoding for every identifier on the wire: `client_id`,
 * `server_id`, `psk_id`, the `noise/handshake` `data` field, and the PSKs in
 * pairing messages. All of those are fixed-length, so the padding is redundant
 * and the spec omits it.
 *
 * Note this is NOT the same as [com.sendspindroid.shared.platform.Platform.base64Decode],
 * which is standard base64 *with* padding and is used for the FLAC
 * `codec_header`. Mixing them up produces values that differ only in a few
 * characters, which is exactly the kind of thing that surfaces as an
 * unexplained handshake failure.
 */
expect object Base64Url {
    /** Encode without padding. */
    fun encode(bytes: ByteArray): String

    /**
     * Decode, tolerating an input that carries padding anyway.
     *
     * @return null if [value] is not valid base64url. Callers are parsing
     *   untrusted wire data, so this never throws.
     */
    fun decodeOrNull(value: String): ByteArray?
}
