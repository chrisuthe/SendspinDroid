package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.NoiseCrypto
import com.sendspindroid.sendspin.crypto.NoiseHandshakeException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Ordering of the in-band re-handshake key swap.
 *
 * `messaging.md#client--server-noisehandshake`: "Noise message 2 is still
 * encrypted under the pre-re-handshake transport keys; the first frame each
 * side sends after the handshake completes uses the new keys."
 *
 * So there is exactly one frame boundary where the keys change, and it falls
 * *after* our message 2. Swapping a frame too early makes the server unable to
 * read the message that completes the handshake; a frame too late makes
 * everything after it unreadable. Both present as a silent close.
 *
 * Real AEAD is covered by NoiseRehandshakeTest against reference vectors. Here
 * the "crypto" only tags which key was used, because ordering is the thing
 * under test.
 */
class ChannelKeySwapTest {

    /** Tags each frame with the key that encrypted it, and refuses the others. */
    private class TaggedCrypto(private val tag: Byte) : NoiseCrypto {
        override fun encrypt(plaintext: ByteArray): ByteArray = byteArrayOf(tag) + plaintext

        override fun decrypt(frame: ByteArray): ByteArray {
            if (frame.isEmpty() || frame[0] != tag) {
                throw NoiseHandshakeException(
                    NoiseHandshakeException.Cause.AeadFailure,
                    "frame was not encrypted under key $tag",
                )
            }
            return frame.copyOfRange(1, frame.size)
        }
    }

    private val oldKey: Byte = 0x11
    private val newKey: Byte = 0x22

    private fun codec() = NoiseWireCodec(TaggedCrypto(oldKey))

    @Test
    fun messageTwoGoesOutUnderTheOldKeyAndEverythingAfterUnderTheNew() = runBlocking {
        val codec = codec()

        // The frame that completes the re-handshake.
        val message2 = codec.encodeAndSwap(
            SendSpinProtocol.BinaryType.JSON,
            """{"type":"noise/handshake"}""".encodeToByteArray(),
            next = TaggedCrypto(newKey),
        )
        assertEquals(1, message2.size)
        assertEquals("message 2 must use the PRE-swap key", oldKey, message2[0][0])

        // The re-asserted client/hello, the first frame of the new session.
        val hello = codec.encodeJson("""{"type":"client/hello"}""")
        assertEquals("the frame after the swap must use the NEW key", newKey, hello[0][0])
    }

    @Test
    fun theSwapAlsoChangesTheReceiveDirection() {
        // "the first frame each side sends after the handshake completes uses
        // the new keys" - the server's next frame is already under them, so
        // installing only the send side would break the very next message in.
        val codec = codec()
        runBlocking {
            codec.encodeAndSwap(
                SendSpinProtocol.BinaryType.JSON, "{}".encodeToByteArray(),
                next = TaggedCrypto(newKey),
            )
        }
        val underNew = byteArrayOf(newKey, SendSpinProtocol.BinaryType.JSON.toByte()) +
            """{"type":"server/hello"}""".encodeToByteArray()
        val decoded = codec.decode(underNew)
        assertTrue("a peer frame under the new key must decode, got $decoded",
            decoded is NoiseWireCodec.Decoded.Json)
    }

    @Test
    fun aFrameStillUnderTheOldKeyIsRejectedAfterTheSwap() {
        val codec = codec()
        runBlocking {
            codec.encodeAndSwap(
                SendSpinProtocol.BinaryType.JSON, "{}".encodeToByteArray(),
                next = TaggedCrypto(newKey),
            )
        }
        val stale = byteArrayOf(oldKey, SendSpinProtocol.BinaryType.JSON.toByte()) + "{}".encodeToByteArray()
        assertTrue(codec.decode(stale) is NoiseWireCodec.Decoded.ProtocolError)
    }

    @Test
    fun noOtherFrameCanInterleaveBetweenMessageTwoAndTheSwap() = runBlocking {
        // "No other messages flow during the exchange." The swap is only safe if
        // encrypt-then-install is one critical section: a client/time racing it
        // could otherwise be encrypted under the old key and arrive after the
        // server has already moved to the new one.
        val codec = codec()
        val emitted = mutableListOf<Byte>()

        val racer = async {
            repeat(20) { emitted += codec.encodeJson("""{"type":"client/time"}""")[0][0] }
        }
        val message2 = codec.encodeAndSwap(
            SendSpinProtocol.BinaryType.JSON, "{}".encodeToByteArray(),
            next = TaggedCrypto(newKey),
        )
        racer.await()

        assertEquals(oldKey, message2[0][0])
        // Every racing frame used one key or the other, never a torn state -
        // and none of them can be the message-2 frame itself.
        assertTrue(emitted.all { it == oldKey || it == newKey })
        // Once the new key appears, it must never revert.
        val firstNew = emitted.indexOfFirst { it == newKey }
        if (firstNew >= 0) {
            assertTrue(
                "keys went backwards after the swap: $emitted",
                emitted.drop(firstNew).all { it == newKey },
            )
        }
    }
}
