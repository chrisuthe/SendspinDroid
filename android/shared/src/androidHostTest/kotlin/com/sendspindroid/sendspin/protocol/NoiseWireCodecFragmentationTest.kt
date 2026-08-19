package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.protocol.message.FragmentWriter
import org.junit.Assert.*
import org.junit.Test

/**
 * Fragmentation as seen through [NoiseWireCodec].
 *
 * The codec is the only place that sees decrypted plaintexts, so it is where
 * reassembly has to happen. These tests use identity "crypto" so the framing is
 * the only thing under test - the real AEAD is covered by the handshake driver's
 * golden vectors.
 */
class NoiseWireCodecFragmentationTest {

    /** A codec whose encrypt/decrypt are the identity, so plaintext == frame. */
    private fun plaintextCodec() = NoiseWireCodec(
        object : com.sendspindroid.sendspin.crypto.NoiseCrypto {
            override fun encrypt(plaintext: ByteArray) = plaintext
            override fun decrypt(frame: ByteArray) = frame
        }
    )

    @Test
    fun aFragmentedJsonMessageIsReassembledIntoJson() {
        // orig_type 0 must come back out as Json, not as a Typed frame of type 0.
        val codec = plaintextCodec()
        val json = """{"type":"server/state","payload":{}}"""
        val frames = FragmentWriter.frames(
            SendSpinProtocol.BinaryType.JSON,
            json.encodeToByteArray(),
        )
        // Force fragmentation regardless of size by feeding hand-built frames.
        val big = FragmentWriter.frames(
            SendSpinProtocol.BinaryType.JSON,
            ByteArray(70_000) { 0x20 },  // spaces, so it is still valid UTF-8
        )
        assertTrue("precondition: should have fragmented", big.size > 1)

        var last: NoiseWireCodec.Decoded? = null
        for (frame in frames) last = codec.decode(frame)
        assertTrue(last is NoiseWireCodec.Decoded.Json)
        assertEquals(json, (last as NoiseWireCodec.Decoded.Json).text)
    }

    @Test
    fun aFragmentedAudioMessageIsReassembledAsTyped() {
        val codec = plaintextCodec()
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val frames = FragmentWriter.frames(SendSpinProtocol.BinaryType.AUDIO, payload)
        assertTrue("precondition: should have fragmented", frames.size > 2)

        val results = frames.map { codec.decode(it) }
        // Every frame but the last buffers silently.
        for (r in results.dropLast(1)) {
            assertTrue("expected Buffered, got $r", r is NoiseWireCodec.Decoded.Buffered)
        }
        val complete = results.last()
        assertTrue(complete is NoiseWireCodec.Decoded.Typed)
        complete as NoiseWireCodec.Decoded.Typed
        assertEquals(SendSpinProtocol.BinaryType.AUDIO, complete.type)
        assertArrayEquals(payload, complete.body)
    }

    @Test
    fun anUnfragmentedFrameStillDecodesNormally() {
        val codec = plaintextCodec()
        val decoded = codec.decode(
            byteArrayOf(SendSpinProtocol.BinaryType.AUDIO.toByte(), 1, 2, 3)
        )
        assertTrue(decoded is NoiseWireCodec.Decoded.Typed)
        assertArrayEquals(byteArrayOf(1, 2, 3), (decoded as NoiseWireCodec.Decoded.Typed).body)
    }

    @Test
    fun aMalformedFragmentSequenceIsAProtocolError() {
        // fragment-end with nothing in flight. The caller closes the socket on
        // this, so it must not be swallowed as an ignorable unknown frame.
        val codec = plaintextCodec()
        val decoded = codec.decode(
            byteArrayOf(SendSpinProtocol.BinaryType.FRAGMENT_END.toByte(), 0xA)
        )
        assertTrue(decoded is NoiseWireCodec.Decoded.ProtocolError)
    }

    @Test
    fun aNonFragmentFrameArrivingMidSequenceIsAProtocolError() {
        val codec = plaintextCodec()
        codec.decode(byteArrayOf(SendSpinProtocol.BinaryType.FRAGMENT_MORE.toByte(), 0, 0xA))
        val decoded = codec.decode(
            byteArrayOf(SendSpinProtocol.BinaryType.AUDIO.toByte(), 1, 2, 3)
        )
        assertTrue(decoded is NoiseWireCodec.Decoded.ProtocolError)
    }

    @Test
    fun encodeSplitsAnOversizePayloadAndKeepsEveryFrameUnderTheNoiseLimit() {
        val codec = plaintextCodec()
        val frames = kotlinx.coroutines.runBlocking {
            codec.encode(SendSpinProtocol.BinaryType.AUDIO, ByteArray(200_000))
        }
        assertTrue("should have fragmented", frames.size > 2)
        for (f in frames) {
            assertTrue("frame too big", f.size <= SendSpinProtocol.NoiseFraming.MAX_TRANSPORT_MESSAGE)
        }
    }

    @Test
    fun encodeNoLongerThrowsOnAnOversizePayload() {
        // Before fragmentation landed this threw MessageTooLarge. It must now
        // succeed, or artwork over 64 KB can never be sent.
        val codec = plaintextCodec()
        kotlinx.coroutines.runBlocking {
            codec.encode(SendSpinProtocol.BinaryType.ARTWORK_BASE, ByteArray(300_000))
        }
    }
}
