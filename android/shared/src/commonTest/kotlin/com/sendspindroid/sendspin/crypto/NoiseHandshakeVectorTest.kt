package com.sendspindroid.sendspin.crypto

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Drives the responder against complete KKpsk2 transcripts produced by
 * `noiseprotocol` - the library aiosendspin 9.1.0 depends on - acting as both
 * parties with pinned ephemerals. See [NoiseTestVectors].
 *
 * Both cipher suites are covered because they differ in two ways that fail
 * identically and only on one suite: the AESGCM protocol name is exactly 32
 * bytes (used verbatim as `h`) while ChaChaPoly's is 36 (hashed), and their
 * nonce counters have opposite endianness.
 */
class NoiseHandshakeVectorTest {

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.hex(): String = joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private fun suiteOf(v: NoiseVector): NoiseCipherSuite =
        NoiseCipherSuite.entries.first { v.protocol == it.protocolName }

    /** A handshake pinned to the vector's ephemeral so message 2 is reproducible. */
    private fun handshakeFor(v: NoiseVector) = NoiseHandshake(
        suite = suiteOf(v),
        staticPrivateKey = hex(v.clientStaticPrivate),
        remoteStaticPublicKey = hex(v.serverStaticPublic),
        prologue = hex(v.prologue),
        generateEphemeral = { hex(v.clientEphemeralPrivate) },
    )

    @Test
    fun protocolNamesHashOnlyWhenLongerThan32Bytes() {
        // The boundary case is real, not hypothetical: one shipped suite sits
        // exactly on it. Guard the constants themselves.
        assertEquals(36, NoiseCipherSuite.CHACHA_POLY.protocolName.length)
        assertEquals(32, NoiseCipherSuite.AES_GCM.protocolName.length)
        for (v in NoiseTestVectors.all) {
            assertEquals(
                v.protocolNameIsHashed,
                suiteOf(v).protocolName.length > 32,
                "${v.protocol}: hashing branch disagrees with the reference",
            )
        }
    }

    @Test
    fun message1PayloadDecryptsWithoutAnyPSKMixedIn() {
        // This is the whole point of psk2: psk_id must be readable before the
        // client has chosen a PSK.
        for (v in NoiseTestVectors.all) {
            val payload = handshakeFor(v).readMessage1(hex(v.message1))
            assertEquals(v.message1PayloadUtf8, payload.decodeToString(), v.protocol)
        }
    }

    @Test
    fun message2ReproducesTheReferenceBytes() {
        for (v in NoiseTestVectors.all) {
            val handshake = handshakeFor(v)
            handshake.readMessage1(hex(v.message1))
            val (message2, _) = handshake.writeMessage2(hex(v.psk))
            assertEquals(v.message2, message2.hex(), v.protocol)
            // 32-byte ephemeral + 2-byte payload + 16-byte tag.
            assertEquals(50, message2.size, v.protocol)
        }
    }

    @Test
    fun handshakeHashAgreesWithTheReference() {
        for (v in NoiseTestVectors.all) {
            val handshake = handshakeFor(v)
            handshake.readMessage1(hex(v.message1))
            val (_, transport) = handshake.writeMessage2(hex(v.psk))
            assertEquals(v.handshakeHash, transport.handshakeHash.hex(), v.protocol)
        }
    }

    @Test
    fun transportDecryptsInitiatorFramesAtNonce0And1() {
        // Nonce 0 encodes as twelve zero bytes under either endianness, so the
        // n=1 frame is the one that actually pins the counter layout.
        for (v in NoiseTestVectors.all) {
            val transport = completedTransport(v)
            v.transportI2r.forEachIndexed { i, ct ->
                assertEquals(
                    v.transportI2rPlaintextUtf8[i],
                    transport.decrypt(hex(ct)).decodeToString(),
                    "${v.protocol} frame $i",
                )
            }
        }
    }

    @Test
    fun transportEncryptsResponderFramesMatchingTheReferenceAtNonce0And1() {
        for (v in NoiseTestVectors.all) {
            val transport = completedTransport(v)
            v.transportR2iPlaintextUtf8.forEachIndexed { i, plaintext ->
                assertEquals(
                    v.transportR2i[i],
                    transport.encrypt(plaintext.encodeToByteArray()).hex(),
                    "${v.protocol} frame $i",
                )
            }
        }
    }

    @Test
    fun transportKeysAreNotSwapped() {
        // A swapped split still yields an agreeing handshake hash, so this needs
        // its own assertion: our receive key must NOT decrypt our own output.
        for (v in NoiseTestVectors.all) {
            val ours = completedTransport(v)
            val ourFrame = ours.encrypt("probe".encodeToByteArray())
            val fresh = completedTransport(v)
            assertFailsWith<NoiseHandshakeException>(v.protocol) { fresh.decrypt(ourFrame) }
        }
    }

    @Test
    fun aDifferentPrologueBreaksMessage1() {
        // The prologue is the highest-risk input in the whole migration: it must
        // be the exact wire bytes, never a re-encoding.
        for (v in NoiseTestVectors.all) {
            val tampered = hex(v.prologue).copyOf().also { it[0] = (it[0] + 1).toByte() }
            val handshake = NoiseHandshake(
                suite = suiteOf(v),
                staticPrivateKey = hex(v.clientStaticPrivate),
                remoteStaticPublicKey = hex(v.serverStaticPublic),
                prologue = tampered,
                generateEphemeral = { hex(v.clientEphemeralPrivate) },
            )
            val e = assertFailsWith<NoiseHandshakeException>(v.protocol) {
                handshake.readMessage1(hex(v.message1))
            }
            assertEquals(NoiseHandshakeException.Cause.AeadFailure, e.reason, v.protocol)
        }
    }

    @Test
    fun theWrongPSKBreaksMessage2ForThePeerButNotLocally() {
        // psk2 mixes the PSK after all DH, so a wrong PSK cannot fail locally -
        // it produces a well-formed message 2 the server will reject. Pinning
        // this stops anyone "fixing" a non-existent local check later.
        val v = NoiseTestVectors.chaChaPoly
        val handshake = handshakeFor(v)
        handshake.readMessage1(hex(v.message1))
        val (message2, _) = handshake.writeMessage2(ByteArray(32) { 0x11 })
        assertEquals(50, message2.size)
        assertTrue(message2.hex() != v.message2, "wrong PSK must change message 2")
    }

    @Test
    fun truncatedMessage1IsRejectedAsMalformedNotAsAnAEADFailure() {
        val v = NoiseTestVectors.chaChaPoly
        for (size in listOf(0, 1, 31, 32, 47)) {
            val e = assertFailsWith<NoiseHandshakeException>("size=$size") {
                handshakeFor(v).readMessage1(hex(v.message1).copyOf(size))
            }
            assertEquals(
                NoiseHandshakeException.Cause.MalformedMessage,
                e.reason,
                "size=$size must be malformed, not confused with a key mismatch",
            )
        }
    }

    @Test
    fun callingOutOfOrderFailsWithWrongPhase() {
        val v = NoiseTestVectors.chaChaPoly
        val fresh = handshakeFor(v)
        assertEquals(
            NoiseHandshakeException.Cause.WrongPhase,
            assertFailsWith<NoiseHandshakeException> { fresh.writeMessage2(hex(v.psk)) }.reason,
        )

        val used = handshakeFor(v)
        used.readMessage1(hex(v.message1))
        assertEquals(
            NoiseHandshakeException.Cause.WrongPhase,
            assertFailsWith<NoiseHandshakeException> {
                used.readMessage1(hex(v.message1))
            }.reason,
            "replaying message 1 must not silently re-mix the transcript",
        )
    }

    @Test
    fun aFailedHandshakeIsTerminal() {
        val v = NoiseTestVectors.chaChaPoly
        val handshake = handshakeFor(v)
        assertFailsWith<NoiseHandshakeException> { handshake.readMessage1(ByteArray(10)) }
        // Must not be recoverable by simply retrying with a good message.
        assertEquals(
            NoiseHandshakeException.Cause.WrongPhase,
            assertFailsWith<NoiseHandshakeException> {
                handshake.readMessage1(hex(v.message1))
            }.reason,
        )
    }

    @Test
    fun handshakeHashIsDefensivelyCopied() {
        val v = NoiseTestVectors.chaChaPoly
        val transport = completedTransport(v)
        val first = transport.handshakeHash
        first.fill(0)
        assertContentEquals(hex(v.handshakeHash), transport.handshakeHash)
    }

    private fun completedTransport(v: NoiseVector): NoiseTransport {
        val handshake = handshakeFor(v)
        handshake.readMessage1(hex(v.message1))
        return handshake.writeMessage2(hex(v.psk)).transport
    }
}
