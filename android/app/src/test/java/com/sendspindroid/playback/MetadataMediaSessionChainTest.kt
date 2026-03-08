package com.sendspindroid.playback

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration test: MetadataForwardingPlayer -> MediaSession chain.
 *
 * Verifies the end-to-end flow of metadata from SendSpinClient callback
 * through MetadataForwardingPlayer to MediaSession listeners. This is the
 * chain that drives lock screen, notifications, and Android Auto display.
 *
 * The flow:
 * 1. SendSpinClient.Callback.onMetadataUpdate fires (from server)
 * 2. PlaybackService calls forwardingPlayer.updateMetadata(title, artist, album)
 * 3. MetadataForwardingPlayer updates cached metadata
 * 4. MetadataForwardingPlayer notifies listeners (MediaSession)
 * 5. MediaSession updates lock screen/notification via getMediaMetadata()
 */
class MetadataMediaSessionChainTest {

    private lateinit var mockPlayer: Player
    private lateinit var forwardingPlayer: MetadataForwardingPlayer

    @Before
    fun setUp() {
        mockPlayer = mockk(relaxed = true)
        forwardingPlayer = MetadataForwardingPlayer(mockPlayer)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `metadata update reaches listener with correct title and artist`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        forwardingPlayer.addListener(listener)

        forwardingPlayer.updateMetadata(
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night at the Opera"
        )

        val slot = slot<MediaMetadata>()
        verify(exactly = 1) { listener.onMediaMetadataChanged(capture(slot)) }

        val captured = slot.captured
        assertEquals("Bohemian Rhapsody", captured.title?.toString())
        assertEquals("Queen", captured.artist?.toString())
        assertEquals("A Night at the Opera", captured.albumTitle?.toString())
    }

    @Test
    fun `getMediaMetadata returns updated metadata after updateMetadata`() {
        forwardingPlayer.updateMetadata(
            title = "Song Title",
            artist = "Artist Name",
            album = "Album Name"
        )

        val metadata = forwardingPlayer.mediaMetadata
        assertEquals("Song Title", metadata.title?.toString())
        assertEquals("Artist Name", metadata.artist?.toString())
        assertEquals("Album Name", metadata.albumTitle?.toString())
    }

    @Test
    fun `partial update preserves existing fields`() {
        // First update sets all fields
        forwardingPlayer.updateMetadata(
            title = "Song 1",
            artist = "Artist 1",
            album = "Album 1"
        )

        // Partial update: only change artist
        forwardingPlayer.updateMetadata(
            title = null,  // preserve
            artist = "Artist 2",
            album = null   // preserve
        )

        val metadata = forwardingPlayer.mediaMetadata
        assertEquals("Song 1", metadata.title?.toString())
        assertEquals("Artist 2", metadata.artist?.toString())
        assertEquals("Album 1", metadata.albumTitle?.toString())
    }

    @Test
    fun `clearMetadata resets all fields`() {
        forwardingPlayer.updateMetadata(
            title = "Song", artist = "Artist", album = "Album"
        )

        forwardingPlayer.clearMetadata()

        val metadata = forwardingPlayer.mediaMetadata
        // After clear, should fall back to underlying player's metadata
        // (which is empty in our mock)
        assertNull(metadata.title)
        assertNull(metadata.artist)
    }

    @Test
    fun `clearMetadata notifies listeners with EMPTY metadata`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        forwardingPlayer.addListener(listener)

        forwardingPlayer.clearMetadata()

        verify(exactly = 1) { listener.onMediaMetadataChanged(MediaMetadata.EMPTY) }
    }

    @Test
    fun `multiple listeners all receive metadata update`() {
        val listener1 = mockk<Player.Listener>(relaxed = true)
        val listener2 = mockk<Player.Listener>(relaxed = true)
        val listener3 = mockk<Player.Listener>(relaxed = true)

        forwardingPlayer.addListener(listener1)
        forwardingPlayer.addListener(listener2)
        forwardingPlayer.addListener(listener3)

        forwardingPlayer.updateMetadata(title = "Test", artist = null, album = null)

        verify(exactly = 1) { listener1.onMediaMetadataChanged(any()) }
        verify(exactly = 1) { listener2.onMediaMetadataChanged(any()) }
        verify(exactly = 1) { listener3.onMediaMetadataChanged(any()) }
    }

    @Test
    fun `removed listener does not receive subsequent updates`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        forwardingPlayer.addListener(listener)
        forwardingPlayer.removeListener(listener)

        forwardingPlayer.updateMetadata(title = "Test", artist = null, album = null)

        verify(exactly = 0) { listener.onMediaMetadataChanged(any()) }
    }

    @Test
    fun `metadata subtitle includes artist and album`() {
        forwardingPlayer.updateMetadata(
            title = "Song",
            artist = "The Beatles",
            album = "Abbey Road"
        )

        val metadata = forwardingPlayer.mediaMetadata
        // MetadataForwardingPlayer builds subtitle as "Artist - Album"
        val subtitle = metadata.subtitle?.toString()
        assertNotNull("Subtitle should be set", subtitle)
        assertTrue("Subtitle should contain artist", subtitle!!.contains("The Beatles"))
        assertTrue("Subtitle should contain album", subtitle.contains("Abbey Road"))
    }

    @Test
    fun `metadata subtitle with only artist shows artist`() {
        forwardingPlayer.updateMetadata(
            title = "Song",
            artist = "Solo Artist",
            album = null
        )

        val metadata = forwardingPlayer.mediaMetadata
        val subtitle = metadata.subtitle?.toString()
        assertEquals("Solo Artist", subtitle)
    }

    @Test
    fun `metadata sets mediaType to MUSIC`() {
        forwardingPlayer.updateMetadata(title = "Song", artist = null, album = null)

        val metadata = forwardingPlayer.mediaMetadata
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, metadata.mediaType)
    }

    @Test
    fun `metadata sets isPlayable true`() {
        forwardingPlayer.updateMetadata(title = "Song", artist = null, album = null)

        val metadata = forwardingPlayer.mediaMetadata
        assertTrue("Metadata should be marked playable", metadata.isPlayable == true)
    }

    @Test
    fun `sequential track changes each notify listeners`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        forwardingPlayer.addListener(listener)

        // Track 1
        forwardingPlayer.updateMetadata(title = "Track 1", artist = "A1", album = null)

        // Track 2
        forwardingPlayer.updateMetadata(title = "Track 2", artist = "A2", album = null)

        // Track 3
        forwardingPlayer.updateMetadata(title = "Track 3", artist = "A3", album = null)

        // Should have been notified 3 times
        verify(exactly = 3) { listener.onMediaMetadataChanged(any()) }

        // Final metadata should be track 3
        val metadata = forwardingPlayer.mediaMetadata
        assertEquals("Track 3", metadata.title?.toString())
        assertEquals("A3", metadata.artist?.toString())
    }

    @Test
    fun `empty title clears the title field`() {
        forwardingPlayer.updateMetadata(title = "Song", artist = null, album = null)
        forwardingPlayer.updateMetadata(title = "", artist = null, album = null)

        val metadata = forwardingPlayer.mediaMetadata
        // Empty string should clear the field
        assertNull(metadata.title)
    }

    @Test
    fun `getCurrentMediaItem includes enhanced metadata`() {
        // Set up a base media item on the mock player
        every { mockPlayer.currentMediaItem } returns androidx.media3.common.MediaItem.Builder()
            .setMediaId("test-id")
            .build()

        forwardingPlayer.updateMetadata(title = "Enhanced", artist = "Artist", album = null)

        val item = forwardingPlayer.currentMediaItem
        assertNotNull(item)
        val metadata = item!!.mediaMetadata
        assertEquals("Enhanced", metadata.title?.toString())
    }
}
