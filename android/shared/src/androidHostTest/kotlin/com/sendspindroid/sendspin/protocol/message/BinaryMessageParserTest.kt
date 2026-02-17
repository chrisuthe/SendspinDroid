package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.shared.log.Log
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryMessageParserTest {

    @Before
    fun setUp() {
        mockkObject(Log)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun buildBinaryMessage(type: Int, timestamp: Long, payload: ByteArray = ByteArray(0)): ByteArray {
        val buffer = ByteBuffer.allocate(9 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(type.toByte())
        buffer.putLong(timestamp)
        buffer.put(payload)
        buffer.flip()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    // --- Audio messages (type 4) ---

    @Test
    fun parse_audioMessage_returnsAudioWithCorrectTimestampAndPayload() {
        val timestamp = 123_456_789L
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val message = BinaryMessageParser.parse(buildBinaryMessage(4, timestamp, payload))

        assertTrue(message is BinaryMessageParser.BinaryMessage.Audio)
        val audio = message as BinaryMessageParser.BinaryMessage.Audio
        assertEquals(timestamp, audio.timestampMicros)
        assertArrayEquals(payload, audio.payload)
    }

    // --- Artwork messages (types 8-11) ---

    @Test
    fun parse_artworkType8_returnsArtworkChannel0() {
        val message = BinaryMessageParser.parse(buildBinaryMessage(8, 100L, byteArrayOf(0xFF.toByte())))
        assertTrue(message is BinaryMessageParser.BinaryMessage.Artwork)
        assertEquals(0, (message as BinaryMessageParser.BinaryMessage.Artwork).channel)
    }

    @Test
    fun parse_artworkType9_returnsArtworkChannel1() {
        val message = BinaryMessageParser.parse(buildBinaryMessage(9, 100L))
        assertTrue(message is BinaryMessageParser.BinaryMessage.Artwork)
        assertEquals(1, (message as BinaryMessageParser.BinaryMessage.Artwork).channel)
    }

    @Test
    fun parse_artworkType10_returnsArtworkChannel2() {
        val message = BinaryMessageParser.parse(buildBinaryMessage(10, 100L))
        assertTrue(message is BinaryMessageParser.BinaryMessage.Artwork)
        assertEquals(2, (message as BinaryMessageParser.BinaryMessage.Artwork).channel)
    }

    @Test
    fun parse_artworkType11_returnsArtworkChannel3() {
        val message = BinaryMessageParser.parse(buildBinaryMessage(11, 100L))
        assertTrue(message is BinaryMessageParser.BinaryMessage.Artwork)
        assertEquals(3, (message as BinaryMessageParser.BinaryMessage.Artwork).channel)
    }

    // --- Visualizer message (type 16) ---

    @Test
    fun parse_visualizerMessage_returnsVisualizer() {
        val message = BinaryMessageParser.parse(buildBinaryMessage(16, 500L, byteArrayOf(10, 20)))
        assertTrue(message is BinaryMessageParser.BinaryMessage.Visualizer)
        val viz = message as BinaryMessageParser.BinaryMessage.Visualizer
        assertEquals(500L, viz.timestampMicros)
        assertArrayEquals(byteArrayOf(10, 20), viz.payload)
    }

    // --- Unknown type ---

    @Test
    fun parse_unknownType_returnsUnknown() {
        val message = BinaryMessageParser.parse(buildBinaryMessage(99, 300L))
        assertTrue(message is BinaryMessageParser.BinaryMessage.Unknown)
        assertEquals(99, (message as BinaryMessageParser.BinaryMessage.Unknown).type)
    }

    // --- Too short ---

    @Test
    fun parse_tooShortMessage_returnsNull() {
        val shortBytes = byteArrayOf(4, 0, 0, 0)
        assertNull(BinaryMessageParser.parse(shortBytes))
    }

    // --- Empty payload ---

    @Test
    fun parse_emptyPayload_returnsMessageWithEmptyPayload() {
        val message = BinaryMessageParser.parse(buildBinaryMessage(4, 100L))
        assertTrue(message is BinaryMessageParser.BinaryMessage.Audio)
        assertEquals(0, (message as BinaryMessageParser.BinaryMessage.Audio).payload.size)
    }

    // --- Timestamp parsing ---

    @Test
    fun parse_knownTimestampBytes_producesCorrectLong() {
        val message = BinaryMessageParser.parse(buildBinaryMessage(4, 1L))
        assertEquals(1L, (message as BinaryMessageParser.BinaryMessage.Audio).timestampMicros)
    }

    @Test
    fun parse_maxTimestamp_producesCorrectLong() {
        val maxTimestamp = Long.MAX_VALUE
        val message = BinaryMessageParser.parse(buildBinaryMessage(4, maxTimestamp))
        assertEquals(maxTimestamp, (message as BinaryMessageParser.BinaryMessage.Audio).timestampMicros)
    }
}
