package com.sendspindroid.sendspin.crypto

/**
 * A Noise-layer failure.
 *
 * Per `connection.md#failure-handling`, every one of these closes the WebSocket
 * "without sending any application-level error message". The peer therefore
 * learns nothing and neither do we - which makes [reason] the only diagnostic
 * anyone will have in the field. Keep the distinctions sharp, and never put key
 * material in [message].
 */
class NoiseHandshakeException(
    val reason: Cause,
    message: String,
    throwable: Throwable? = null,
) : Exception("$reason: $message", throwable) {

    enum class Cause {
        /** A handshake message was truncated or structurally impossible. */
        MalformedMessage,

        /** The inner handshake payload was not the expected UTF-8 JSON. */
        PayloadNotJson,

        /** No candidate PSK matched the `psk_id` in Noise message 1. */
        PskLookupMiss,

        /** AEAD tag check failed: wrong keys, wrong prologue, or tampering. */
        AeadFailure,

        /** A method was called out of order for the handshake's current phase. */
        WrongPhase,

        /** The Noise 65535-byte per-message limit was exceeded. */
        MessageTooLarge,

        /** The 64-bit nonce counter reached its sentinel value. */
        NonceExhausted,
    }
}
