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

        /**
         * The handshake did not complete within the watchdog window.
         * `connection.md#failure-handling` recommends 30 seconds for each side
         * to receive the next expected message during the prologue and
         * Noise-handshake phases.
         */
        Timeout,

        /**
         * The peer answered `client/init` with something other than
         * `server/init` - in practice a legacy `server/hello`, from a server
         * predating mandatory encryption (spec #84, 2026-06-29).
         *
         * Distinct from [MalformedMessage] because nothing is malformed: the
         * reply is a well-formed message in an older dialect. It is the one
         * handshake failure a user can act on, so it must be separable from
         * the crypto failures in order to say so.
         */
        ServerLacksEncryption,
    }
}
