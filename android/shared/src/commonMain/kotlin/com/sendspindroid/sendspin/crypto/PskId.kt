package com.sendspindroid.sendspin.crypto

/**
 * Derivation of the `psk_id` a server sends in Noise message 1.
 *
 * From `connection.md#pre-shared-key`:
 *
 *     psk_id = base64url(SHA-256("sendspin-psk-id-v1" || PSK))
 *
 * "The label is the UTF-8 byte sequence of the literal characters shown (no NUL
 * terminator, no surrounding quotes); `||` denotes byte concatenation."
 *
 * The result is 43 base64url characters (a 32-byte digest, unpadded).
 *
 * This exists so the client can pick the right PSK before it is able to mix one
 * in: under `psk2` the PSK lands at the end of message 2, so message 1's payload
 * decrypts without it. The same formula covers all three PSK categories.
 */
object PskId {
    const val LABEL = "sendspin-psk-id-v1"

    /** Length of a `psk_id` on the wire. */
    const val LENGTH = 43

    /**
     * @param psk the raw 32-byte PSK. A wrong length is a programming error
     *   rather than a runtime condition, so this throws rather than returning
     *   null.
     */
    fun derive(psk: ByteArray): String {
        require(psk.size == Psk.PSK_SIZE) {
            "a Sendspin PSK is ${Psk.PSK_SIZE} bytes, got ${psk.size}"
        }
        // Digest over label || psk, in that order.
        return Base64Url.encode(sha256(LABEL.encodeToByteArray(), psk))
    }
}
