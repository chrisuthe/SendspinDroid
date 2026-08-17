package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import org.junit.Assert.*
import org.junit.Test

/**
 * Receiver side of `messaging.md#fragmentation`.
 *
 * Pure byte manipulation, so no Log mocking is needed here - the reassembler
 * reports problems by returning [FragmentReassembler.Result.ProtocolError],
 * never by logging and continuing. A malformed sequence MUST close the
 * connection, so silently swallowing one would be a spec violation.
 */
class FragmentReassemblerTest {

    private val more = SendSpinProtocol.BinaryType.FRAGMENT_MORE
    private val end = SendSpinProtocol.BinaryType.FRAGMENT_END

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun threeFrameSequenceReassemblesJsonBody() {
        val r = FragmentReassembler()
        assertEquals(
            FragmentReassembler.Result.Buffered,
            r.accept(more, bytes(0, 0x7B, 0x22))  // orig_type 0 (JSON), then {"
        )
        assertEquals(
            FragmentReassembler.Result.Buffered,
            r.accept(more, bytes(0x61, 0x22))     // a"
        )
        val result = r.accept(end, bytes(0x7D))   // }
        assertTrue(result is FragmentReassembler.Result.Complete)
        result as FragmentReassembler.Result.Complete
        assertEquals(0, result.type)
        assertArrayEquals(bytes(0x7B, 0x22, 0x61, 0x22, 0x7D), result.body)
    }

    @Test
    fun minimumSequenceOneMoreThenOneEndCompletes() {
        // "The minimum is one fragment-more frame followed by one fragment-end
        // frame."
        val r = FragmentReassembler()
        r.accept(more, bytes(4, 0xAA))
        val result = r.accept(end, bytes(0xBB))
        assertTrue(result is FragmentReassembler.Result.Complete)
        result as FragmentReassembler.Result.Complete
        assertEquals(4, result.type)
        assertArrayEquals(bytes(0xAA, 0xBB), result.body)
    }

    @Test
    fun emptyFinalFragmentCompletes() {
        // A fragment-end carrying no data is legal: it terminates the message.
        val r = FragmentReassembler()
        r.accept(more, bytes(8, 0x01, 0x02))
        val result = r.accept(end, ByteArray(0))
        assertTrue(result is FragmentReassembler.Result.Complete)
        result as FragmentReassembler.Result.Complete
        assertEquals(8, result.type)
        assertArrayEquals(bytes(0x01, 0x02), result.body)
    }

    @Test
    fun continuationFrameFirstByteIsDataNotOrigType() {
        // The subtle one. "when a fragmented message is already in flight, a
        // type 2 frame is a continuation and carries only data" - so a leading
        // 0x02 here is payload, not a nested orig_type.
        val r = FragmentReassembler()
        r.accept(more, bytes(0, 0xAA))
        r.accept(more, bytes(0x02))
        val result = r.accept(end, ByteArray(0))
        assertTrue(result is FragmentReassembler.Result.Complete)
        result as FragmentReassembler.Result.Complete
        assertEquals(0, result.type)
        assertArrayEquals(bytes(0xAA, 0x02), result.body)
    }

    @Test
    fun origTypeTwoIsProtocolError() {
        // "A sender MUST NOT use a fragment type (2 or 3) as orig_type."
        val r = FragmentReassembler()
        assertTrue(r.accept(more, bytes(2, 0xAA)) is FragmentReassembler.Result.ProtocolError)
    }

    @Test
    fun origTypeThreeIsProtocolError() {
        val r = FragmentReassembler()
        assertTrue(r.accept(more, bytes(3, 0xAA)) is FragmentReassembler.Result.ProtocolError)
    }

    @Test
    fun fragmentEndWithNothingInFlightIsProtocolError() {
        val r = FragmentReassembler()
        assertTrue(r.accept(end, bytes(0xAA)) is FragmentReassembler.Result.ProtocolError)
    }

    @Test
    fun nonFragmentFrameWhileInFlightIsProtocolError() {
        // "a non-fragment frame received while a fragmented message is in
        // flight in the same direction" - type 4 is audio.
        val r = FragmentReassembler()
        r.accept(more, bytes(0, 0xAA))
        assertTrue(r.accept(4, bytes(0xBB)) is FragmentReassembler.Result.ProtocolError)
    }

    @Test
    fun nonFragmentFrameWithNothingInFlightPassesThrough() {
        // Lets the codec hand every frame to the reassembler rather than
        // duplicating the "is this fragment-related?" test at the call site.
        val r = FragmentReassembler()
        assertEquals(FragmentReassembler.Result.Passthrough, r.accept(4, bytes(0xAA)))
    }

    @Test
    fun openingFragmentWithEmptyBodyIsProtocolError() {
        // No room for orig_type.
        val r = FragmentReassembler()
        assertTrue(r.accept(more, ByteArray(0)) is FragmentReassembler.Result.ProtocolError)
    }

    @Test
    fun exceedingSizeCapIsProtocolErrorNotOom() {
        // The spec sets no cap; an unbounded reassembly buffer is a remote
        // memory-exhaustion vector on a phone. Exceeding it must be an error,
        // never a silent truncation.
        val r = FragmentReassembler(maxMessageBytes = 1024)
        r.accept(more, bytes(0) + ByteArray(600))
        val result = r.accept(more, ByteArray(600))
        assertTrue(result is FragmentReassembler.Result.ProtocolError)
    }

    @Test
    fun resetClearsInFlightState() {
        val r = FragmentReassembler()
        r.accept(more, bytes(0, 0xAA))
        r.reset()
        // With nothing in flight this is an opening frame again, so orig_type
        // is read from byte 0 and the result is Buffered, not ProtocolError.
        assertEquals(FragmentReassembler.Result.Buffered, r.accept(more, bytes(4, 0xBB)))
        val result = r.accept(end, ByteArray(0))
        assertTrue(result is FragmentReassembler.Result.Complete)
        assertEquals(4, (result as FragmentReassembler.Result.Complete).type)
    }

    @Test
    fun completingAMessageClearsStateForTheNext() {
        val r = FragmentReassembler()
        r.accept(more, bytes(0, 0xAA))
        r.accept(end, ByteArray(0))
        // A second, independent message must reassemble cleanly.
        r.accept(more, bytes(16, 0xCC))
        val result = r.accept(end, bytes(0xDD))
        assertTrue(result is FragmentReassembler.Result.Complete)
        result as FragmentReassembler.Result.Complete
        assertEquals(16, result.type)
        assertArrayEquals(bytes(0xCC, 0xDD), result.body)
    }
}
