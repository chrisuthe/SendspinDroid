package com.sendspindroid.sendspin.protocol.message

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryMessageParserTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
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

    /**
     * Helper: Build a binary message as ByteString.
     * Format: [type(1)] [timestamp(8, big-endian)] [payload(N)]
     */
    private fun buildBinaryMessage(type: Int, timestamp: Long, payload: ByteArray = ByteArray(0)): ByteString {
        val buffer = ByteBuffer.allocate(9 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(type.toByte())
        buffer.putLong(timestamp)
        buffer.put(payload)
        buffer.flip()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes.toByteString()
    }

    /**
     * Helper: Build a binary message as ByteBuffer (Java-WebSocket path).
     */
    private fun buildBinaryMessageByteBuffer(type: Int, timestamp: Long, payload: ByteArray = ByteArray(0)): ByteBuffer {
        val buffer = ByteBuffer.allocate(9 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(type.toByte())
        buffer.putLong(timestamp)
        buffer.put(payload)
        buffer.flip()
        return buffer
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
        // Less than 9 bytes (header size)
        val shortBytes = byteArrayOf(4, 0, 0, 0).toByteString()
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
        // Timestamp 0x0000000000000001 = 1
        val message = BinaryMessageParser.parse(buildBinaryMessage(4, 1L))
        assertEquals(1L, (message as BinaryMessageParser.BinaryMessage.Audio).timestampMicros)
    }

    @Test
    fun parse_maxTimestamp_producesCorrectLong() {
        val maxTimestamp = Long.MAX_VALUE
        val message = BinaryMessageParser.parse(buildBinaryMessage(4, maxTimestamp))
        assertEquals(maxTimestamp, (message as BinaryMessageParser.BinaryMessage.Audio).timestampMicros)
    }

    // --- ByteBuffer path ---

    @Test
    fun parse_byteBuffer_audioMessage_returnsCorrectResult() {
        val timestamp = 987_654_321L
        val payload = byteArrayOf(10, 20, 30)
        val message = BinaryMessageParser.parse(buildBinaryMessageByteBuffer(4, timestamp, payload))

        assertTrue(message is BinaryMessageParser.BinaryMessage.Audio)
        val audio = message as BinaryMessageParser.BinaryMessage.Audio
        assertEquals(timestamp, audio.timestampMicros)
        assertArrayEquals(payload, audio.payload)
    }

    @Test
    fun parse_byteBuffer_tooShort_returnsNull() {
        val shortBuffer = ByteBuffer.wrap(byteArrayOf(4, 0, 0, 0))
        assertNull(BinaryMessageParser.parse(shortBuffer))
    }
}
