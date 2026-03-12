package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SendSpinProtocolHandler.
 *
 * Uses a concrete test subclass to exercise the abstract handler's
 * volume clamping, metadata dispatch, and sync state validation.
 */
class SendSpinProtocolHandlerTest {

    private lateinit var handler: TestProtocolHandler

    @Before
    fun setUp() {
        handler = TestProtocolHandler()
        // Mark handshake complete so sendPlayerStateUpdate doesn't short-circuit
        handler.setHandshakeCompleteForTest()
    }

    // ========== Volume Clamping Tests ==========

    @Test
    fun `setVolume clamps values above 1_0 to 100 percent`() {
        handler.setVolume(1.5)
        assertEquals(100, handler.exposedVolume())
    }

    @Test
    fun `setVolume clamps negative values to 0 percent`() {
        handler.setVolume(-0.1)
        assertEquals(0, handler.exposedVolume())
    }

    @Test
    fun `setVolume converts 0_5 to 50 percent`() {
        handler.setVolume(0.5)
        assertEquals(50, handler.exposedVolume())
    }

    @Test
    fun `setVolume converts 0_0 to 0 percent`() {
        handler.setVolume(0.0)
        assertEquals(0, handler.exposedVolume())
    }

    @Test
    fun `setVolume converts 1_0 to 100 percent`() {
        handler.setVolume(1.0)
        assertEquals(100, handler.exposedVolume())
    }

    // ========== Metadata Dispatch Tests ==========

    @Test
    fun `identical metadata fires onMetadataUpdate for every message`() {
        val metadata = buildServerStateJson(
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album"
        )

        // Send the same metadata twice - both should dispatch so that
        // playback_speed changes are never suppressed by position dedup
        handler.handleTextMessageForTest(metadata)
        handler.handleTextMessageForTest(metadata)

        assertEquals(
            "Every metadata message should fire callback regardless of content",
            2,
            handler.metadataUpdates.size
        )
    }

    @Test
    fun `different metadata fires onMetadataUpdate for each`() {
        val metadata1 = buildServerStateJson(
            title = "Song A",
            artist = "Artist A",
            album = "Album A"
        )
        val metadata2 = buildServerStateJson(
            title = "Song B",
            artist = "Artist B",
            album = "Album B"
        )

        handler.handleTextMessageForTest(metadata1)
        handler.handleTextMessageForTest(metadata2)

        assertEquals(
            "Different metadata should fire callback for each",
            2,
            handler.metadataUpdates.size
        )
        assertEquals("Song A", handler.metadataUpdates[0].title)
        assertEquals("Song B", handler.metadataUpdates[1].title)
    }

    // ========== Sync State Validation Tests ==========

    @Test
    fun `setSyncState accepts synchronized`() {
        handler.setSyncState("synchronized")
        assertEquals("synchronized", handler.exposedSyncState())
    }

    @Test
    fun `setSyncState accepts error`() {
        handler.setSyncState("error")
        assertEquals("error", handler.exposedSyncState())
    }

    @Test
    fun `setSyncState rejects invalid value and keeps previous state`() {
        // Default is "synchronized"
        assertEquals("synchronized", handler.exposedSyncState())

        handler.setSyncState("invalid_state")
        assertEquals(
            "Invalid sync state should be rejected, keeping previous value",
            "synchronized",
            handler.exposedSyncState()
        )
    }

    @Test
    fun `setSyncState rejects empty string`() {
        handler.setSyncState("error") // Set to a known state first
        handler.setSyncState("")
        assertEquals(
            "Empty string should be rejected",
            "error",
            handler.exposedSyncState()
        )
    }

    @Test
    fun `setSyncState rejects close misspellings`() {
        handler.setSyncState("Synchronized") // capital S
        assertEquals(
            "Case-sensitive: 'Synchronized' should be rejected",
            "synchronized",
            handler.exposedSyncState()
        )
    }

    // ========== Stream Start Dispatch Tests ==========

    @Test
    fun `stream start with same format dispatches every time`() {
        val streamStart = buildStreamStartJson(codec = "pcm", sampleRate = 48000, channels = 2, bitDepth = 16)

        handler.handleTextMessageForTest(streamStart)
        handler.handleTextMessageForTest(streamStart)

        assertEquals(
            "Every stream/start should dispatch to onStreamStart regardless of format match",
            2,
            handler.streamStarts.size
        )
    }

    @Test
    fun `stream start with different format dispatches`() {
        val start1 = buildStreamStartJson(codec = "pcm", sampleRate = 48000, channels = 2, bitDepth = 16)
        val start2 = buildStreamStartJson(codec = "pcm", sampleRate = 44100, channels = 2, bitDepth = 24)

        handler.handleTextMessageForTest(start1)
        handler.handleTextMessageForTest(start2)

        assertEquals(2, handler.streamStarts.size)
        assertEquals(48000, handler.streamStarts[0].sampleRate)
        assertEquals(44100, handler.streamStarts[1].sampleRate)
    }

    // ========== Helpers ==========

    private fun buildServerStateJson(
        title: String,
        artist: String,
        album: String
    ): String {
        return """
            {
                "type": "server/state",
                "payload": {
                    "metadata": {
                        "timestamp": 1000000,
                        "title": "$title",
                        "artist": "$artist",
                        "album_artist": "$artist",
                        "album": "$album",
                        "artwork_url": "",
                        "year": 2024,
                        "track": 1,
                        "progress": {
                            "track_progress": 0,
                            "track_duration": 180000,
                            "playback_speed": 1000
                        }
                    },
                    "state": "playing"
                }
            }
        """.trimIndent()
    }

    private fun buildStreamStartJson(
        codec: String,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int
    ): String {
        return """
            {
                "type": "stream/start",
                "payload": {
                    "player": {
                        "codec": "$codec",
                        "sample_rate": $sampleRate,
                        "channels": $channels,
                        "bit_depth": $bitDepth
                    }
                }
            }
        """.trimIndent()
    }
}

/**
 * Concrete test implementation of SendSpinProtocolHandler.
 * Records all callback invocations for assertion.
 */
class TestProtocolHandler : SendSpinProtocolHandler("TestHandler") {

    private val testScope = TestScope()
    private val timeFilter = SendspinTimeFilter()
    val sentMessages = mutableListOf<String>()
    val metadataUpdates = mutableListOf<TrackMetadata>()
    val playbackStateChanges = mutableListOf<String>()
    val groupUpdates = mutableListOf<GroupInfo>()
    val streamStarts = mutableListOf<StreamConfig>()

    fun setHandshakeCompleteForTest() {
        handshakeComplete = true
    }

    fun exposedVolume(): Int = currentVolume
    fun exposedSyncState(): String = currentSyncState

    fun handleTextMessageForTest(text: String) {
        handleTextMessage(text)
    }

    override fun sendTextMessage(text: String) {
        sentMessages.add(text)
    }

    override fun getCoroutineScope(): CoroutineScope = testScope

    override fun getTimeFilter(): SendspinTimeFilter = timeFilter

    override fun isLowMemoryMode(): Boolean = false

    override fun getClientId(): String = "test-client-id"

    override fun getDeviceName(): String = "Test Device"

    override fun getManufacturer(): String = "TestManufacturer"

    override fun getSupportedFormats(): List<MessageBuilder.FormatEntry> = emptyList()

    override fun onHandshakeComplete(serverName: String, serverId: String) {}

    override fun onMetadataUpdate(metadata: TrackMetadata) {
        metadataUpdates.add(metadata)
    }

    override fun onPlaybackStateChanged(state: String) {
        playbackStateChanges.add(state)
    }

    override fun onVolumeCommand(volume: Int) {}

    override fun onMuteCommand(muted: Boolean) {}

    override fun onGroupUpdate(info: GroupInfo) {
        groupUpdates.add(info)
    }

    override fun onStreamStart(config: StreamConfig) {
        streamStarts.add(config)
    }

    override fun onStreamClear() {}

    override fun onStreamEnd() {}

    override fun onAudioChunk(timestampMicros: Long, audioData: ByteArray) {}

    override fun onArtwork(channel: Int, payload: ByteArray) {}

    override fun onSyncOffsetApplied(offsetMs: Double, source: String) {}
}
