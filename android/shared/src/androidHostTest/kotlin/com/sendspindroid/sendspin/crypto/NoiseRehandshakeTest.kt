package com.sendspindroid.sendspin.crypto

import org.junit.Assert.*
import org.junit.Test

/**
 * The in-band re-handshake's prologue rule, against reference vectors.
 *
 * `connection.md#re-handshake`: "`client/init` and `server/init` are not
 * re-sent - `client_id`, `server_id`, and `suite` carry over. The new
 * handshake's prologue is the prior handshake's hash `h`."
 *
 * That sentence is the whole risk in item 2.6. Using the *initial* prologue, or
 * the base64url text of `h` instead of its raw 32 bytes, fails at message-1
 * decryption with an AEAD error several steps removed from the cause - and the
 * spec's answer to any handshake failure is a silent socket close, so there is
 * nothing to read afterwards.
 *
 * Vectors are generated and cross-checked against `noiseprotocol` (the library
 * aiosendspin 9.1.0 depends on) by
 * `ci/conformance/noise/make_rehandshake_vectors.py`. Both sides there are the
 * reference; this class is the only consumer.
 */
class NoiseRehandshakeTest {

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private val clientStatic = hex("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f")
    private val serverStaticPublic = hex("358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254")

    /** `h` from the initial handshake. The re-handshake's prologue, verbatim. */
    private val priorHandshakeHash =
        hex("aaf129d111ad113e55c9cc23439df0e4e43c8f28d8d5f3d3e3ff3e7775562703")

    /** The initial handshake's own prologue - the wrong answer, kept to prove it is wrong. */
    private val initialPrologue = "sendspin-rehandshake-initial-prologue-v1".encodeToByteArray()

    private val rehandshakePsk =
        hex("b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0")
    private val clientEphemeral =
        hex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f")

    private val message1 = hex(
        "07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c" +
            "dd9e7b6b8fa1b6c5c8cc1ef92e141c8bcdc51902ada99e3bfa3a00ef3f639b6c" +
            "5d2742d4886eee53d808a801bf02d2d8e43292ea8cd04a81535f12ef"
    )
    private val expectedMessage2 = hex(
        "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a" +
            "d16b35924d8f5c152f908a0674cabe689914"
    )
    private val expectedHandshakeHash =
        hex("ea0381952fe8a29c171d7ddc01ed80de1d56efa7d8e492d481a9bb41c08e8276")

    private fun responder(prologue: ByteArray) = NoiseHandshake(
        suite = NoiseCipherSuite.CHACHA_POLY,
        staticPrivateKey = clientStatic,
        remoteStaticPublicKey = serverStaticPublic,
        prologue = prologue,
        generateEphemeral = { clientEphemeral },
    )

    @Test
    fun aRehandshakeWithPriorHAsPrologueReproducesTheReference() {
        val handshake = responder(priorHandshakeHash)

        val payload = handshake.readMessage1(message1)
        assertEquals(
            """{"psk_id": "rehandshake-psk-id-placeholder"}""",
            payload.decodeToString(),
        )

        val message2 = handshake.writeMessage2(rehandshakePsk)
        assertArrayEquals(
            "message 2 must match the reference byte for byte",
            expectedMessage2, message2.message,
        )
        assertArrayEquals(
            "the new h must match the reference",
            expectedHandshakeHash, message2.transport.handshakeHash,
        )
    }

    @Test
    fun theInitialPrologueFailsMessageOneDecryption() {
        // The single most likely silent bug: carrying the first handshake's
        // prologue into the second. It fails here, at an AEAD tag check, with
        // nothing naming the prologue as the cause.
        val handshake = responder(initialPrologue)
        val failure = runCatching { handshake.readMessage1(message1) }.exceptionOrNull()
        assertTrue(
            "expected an AEAD failure, got $failure",
            failure is NoiseHandshakeException,
        )
        assertEquals(
            NoiseHandshakeException.Cause.AeadFailure,
            (failure as NoiseHandshakeException).reason,
        )
    }

    @Test
    fun thePrologueIsTheRawBytesNotItsBase64UrlText() {
        // A plausible mistake, because `h` is rendered as base64url everywhere
        // it is logged or carried in a field.
        val asText = Base64Url.encode(priorHandshakeHash).encodeToByteArray()
        val handshake = responder(asText)
        assertTrue(
            runCatching { handshake.readMessage1(message1) }.exceptionOrNull()
                is NoiseHandshakeException
        )
    }

    @Test
    fun theNewHandshakeHashDiffersFromThePriorOne() {
        // It has to, or a chained re-handshake would reuse a prologue and the
        // second exchange would be replayable against the first.
        val handshake = responder(priorHandshakeHash)
        handshake.readMessage1(message1)
        val transport = handshake.writeMessage2(rehandshakePsk).transport
        assertFalse(transport.handshakeHash.contentEquals(priorHandshakeHash))
    }

    @Test
    fun messageTwoPayloadIsTheLiteralTwoBytes() {
        // "the empty object as the literal two bytes `{}` (not a zero-length
        // Noise payload)". The reference read it back as exactly that when the
        // vectors were generated; this pins our side of it.
        val handshake = responder(priorHandshakeHash)
        handshake.readMessage1(message1)
        val message2 = handshake.writeMessage2(rehandshakePsk)
        // 32-byte ephemeral public + AEAD(2-byte payload) + 16-byte tag.
        assertEquals(32 + 2 + 16, message2.message.size)
    }
}
