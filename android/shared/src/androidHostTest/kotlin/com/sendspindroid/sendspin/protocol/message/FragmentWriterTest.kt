package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import org.junit.Assert.*
import org.junit.Test

/**
 * Sender side of `messaging.md#fragmentation`.
 *
 * The writer emits AEAD *plaintexts* - `[type][data]` - which the Noise codec
 * then encrypts one at a time. Nothing here knows about encryption.
 */
class FragmentWriterTest {

    private val more = SendSpinProtocol.BinaryType.FRAGMENT_MORE
    private val end = SendSpinProtocol.BinaryType.FRAGMENT_END

    @Test
    fun payloadOf65518BytesProducesExactlyOneUnfragmentedFrame() {
        // "Senders should not fragment messages that fit in a single
        // non-fragmented frame." 1 + 65518 == 65519 == MAX_PLAINTEXT.
        val frames = FragmentWriter.frames(4, ByteArray(Fragmentation.MAX_UNFRAGMENTED_PAYLOAD))
        assertEquals(1, frames.size)
        assertEquals(Fragmentation.MAX_PLAINTEXT, frames[0].size)
        assertEquals(4, frames[0][0].toInt() and 0xFF)
    }

    @Test
    fun payloadOf65519BytesProducesTwoFrames() {
        // One byte over. Opening frame spends a byte on orig_type, so it can
        // only carry 65517 data; the 2-byte remainder rides the fragment-end.
        val frames = FragmentWriter.frames(4, ByteArray(Fragmentation.MAX_UNFRAGMENTED_PAYLOAD + 1))
        assertEquals(2, frames.size)
        assertEquals(Fragmentation.MAX_PLAINTEXT, frames[0].size)
        assertEquals(more, frames[0][0].toInt() and 0xFF)
        assertEquals(4, frames[0][1].toInt() and 0xFF)   // orig_type
        assertEquals(3, frames[1].size)                   // [3] + 2 data bytes
        assertEquals(end, frames[1][0].toInt() and 0xFF)
    }

    @Test
    fun writerNeverProducesPlaintextOverTheNoiseLimit() {
        for (size in listOf(0, 1, 1000, 65517, 65518, 65519, 65520, 131072, 300000)) {
            for (frame in FragmentWriter.frames(0, ByteArray(size))) {
                assertTrue(
                    "payload $size produced a ${frame.size}-byte plaintext",
                    frame.size <= Fragmentation.MAX_PLAINTEXT
                )
            }
        }
    }

    @Test
    fun aFragmentedMessageOpensWithMoreContinuesWithMoreAndEndsWithEnd() {
        // "A fragmented message consists of an opening fragment-more frame
        // (carrying orig_type), zero or more continuation fragment-more frames,
        // and a closing fragment-end frame."
        val frames = FragmentWriter.frames(8, ByteArray(200_000))
        assertTrue("should have fragmented", frames.size > 2)
        assertEquals(more, frames.first()[0].toInt() and 0xFF)
        assertEquals(8, frames.first()[1].toInt() and 0xFF)  // orig_type, opening frame only
        for (middle in frames.subList(1, frames.size - 1)) {
            assertEquals(more, middle[0].toInt() and 0xFF)
        }
        assertEquals(end, frames.last()[0].toInt() and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun writerRejectsOrigTypeTwo() {
        FragmentWriter.frames(more, ByteArray(10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun writerRejectsOrigTypeThree() {
        FragmentWriter.frames(end, ByteArray(10))
    }

    @Test
    fun roundTripWriterThenReassembler() {
        val sizes = listOf(0, 1, 1000, 65517, 65518, 65519, 131072, 300000)
        val types = listOf(0, 4, 8, 16)
        for (type in types) {
            for (size in sizes) {
                val payload = ByteArray(size) { (it * 31 + type).toByte() }
                val frames = FragmentWriter.frames(type, payload)
                val r = FragmentReassembler()
                var delivered: Pair<Int, ByteArray>? = null
                for (frame in frames) {
                    val frameType = frame[0].toInt() and 0xFF
                    val body = frame.copyOfRange(1, frame.size)
                    when (val result = r.accept(frameType, body)) {
                        is FragmentReassembler.Result.Complete ->
                            delivered = result.type to result.body
                        // A message small enough not to need fragmenting comes
                        // straight back out as itself.
                        FragmentReassembler.Result.Passthrough ->
                            delivered = frameType to body
                        FragmentReassembler.Result.Buffered -> {}
                        is FragmentReassembler.Result.ProtocolError ->
                            fail("type $type size $size: ${result.reason}")
                    }
                }
                assertNotNull("type $type size $size never completed", delivered)
                assertEquals("type $type size $size", type, delivered!!.first)
                assertArrayEquals("type $type size $size", payload, delivered.second)
            }
        }
    }
}
