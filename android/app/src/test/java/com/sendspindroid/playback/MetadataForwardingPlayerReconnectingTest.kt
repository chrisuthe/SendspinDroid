package com.sendspindroid.playback

import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the reconnecting-overlay behavior on [MetadataForwardingPlayer]
 * introduced by issue #132. The overlay substitutes
 * "Reconnecting to {server}..." for the title and forces
 * [Player.STATE_BUFFERING] so lock screen / Android Auto / AVRCP render a
 * recovering indicator while [SendSpinClient] is in the `Reconnecting` state.
 */
class MetadataForwardingPlayerReconnectingTest {

    private lateinit var mockPlayer: Player
    private lateinit var forwardingPlayer: MetadataForwardingPlayer

    @Before
    fun setUp() {
        mockPlayer = mockk(relaxed = true)
        every { mockPlayer.playbackState } returns Player.STATE_READY
        forwardingPlayer = MetadataForwardingPlayer(mockPlayer)
    }

    @Test
    fun `setReconnectingOverlay substitutes title while preserving subtitle and album`() {
        forwardingPlayer.updateMetadata(title = "Song Title", artist = "Some Artist", album = "Some Album")

        forwardingPlayer.setReconnectingOverlay("Living Room")

        val metadata = forwardingPlayer.mediaMetadata
        assertEquals("Reconnecting to Living Room...", metadata.title.toString())
        assertEquals("Reconnecting to Living Room...", metadata.displayTitle.toString())
        // Subtitle / artist / album preserved so lock-screen layout + album-art match last known.
        assertEquals("Some Artist - Some Album", metadata.subtitle.toString())
        assertEquals("Some Artist", metadata.artist.toString())
        assertEquals("Some Album", metadata.albumTitle.toString())
    }

    @Test
    fun `getPlaybackState returns STATE_BUFFERING while overlay is set`() {
        every { mockPlayer.playbackState } returns Player.STATE_READY

        forwardingPlayer.setReconnectingOverlay("Kitchen")

        assertEquals(Player.STATE_BUFFERING, forwardingPlayer.playbackState)
    }

    @Test
    fun `getPlaybackState falls through to underlying player when no overlay`() {
        every { mockPlayer.playbackState } returns Player.STATE_READY
        assertEquals(Player.STATE_READY, forwardingPlayer.playbackState)

        every { mockPlayer.playbackState } returns Player.STATE_IDLE
        assertEquals(Player.STATE_IDLE, forwardingPlayer.playbackState)
    }

    @Test
    fun `clearReconnectingOverlay restores the underlying title`() {
        forwardingPlayer.updateMetadata(title = "Song Title", artist = "Artist", album = "Album")
        forwardingPlayer.setReconnectingOverlay("Office")
        assertEquals("Reconnecting to Office...", forwardingPlayer.mediaMetadata.title.toString())

        forwardingPlayer.clearReconnectingOverlay()

        assertEquals("Song Title", forwardingPlayer.mediaMetadata.title.toString())
        assertEquals("Song Title", forwardingPlayer.mediaMetadata.displayTitle.toString())
        // Playback state falls through again.
        every { mockPlayer.playbackState } returns Player.STATE_READY
        assertEquals(Player.STATE_READY, forwardingPlayer.playbackState)
    }

    @Test
    fun `updateMetadata during reconnecting keeps overlay title`() {
        forwardingPlayer.updateMetadata(title = "Original", artist = "A", album = "B")
        forwardingPlayer.setReconnectingOverlay("Dining")

        // A server metadata update arrives mid-reconnect. It must not unseat the overlay --
        // the lock screen should keep reading "Reconnecting to Dining..." until connected.
        forwardingPlayer.updateMetadata(title = "Different Song", artist = "Different Artist", album = "Different Album")

        val metadata = forwardingPlayer.mediaMetadata
        assertEquals("Reconnecting to Dining...", metadata.title.toString())
        // Underlying state is still updated -- once overlay clears, the latest will surface.
        assertEquals("Different Artist - Different Album", metadata.subtitle.toString())

        forwardingPlayer.clearReconnectingOverlay()
        assertEquals("Different Song", forwardingPlayer.mediaMetadata.title.toString())
    }

    @Test
    fun `setReconnectingOverlay is idempotent (same server fires listeners only once)`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        forwardingPlayer.addListener(listener)

        forwardingPlayer.setReconnectingOverlay("Garage")
        forwardingPlayer.setReconnectingOverlay("Garage")
        forwardingPlayer.setReconnectingOverlay("Garage")

        verify(exactly = 1) { listener.onMediaMetadataChanged(any()) }
        verify(exactly = 1) { listener.onPlaybackStateChanged(any()) }
    }

    @Test
    fun `setReconnectingOverlay with different server rebuilds and notifies`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        forwardingPlayer.addListener(listener)

        forwardingPlayer.setReconnectingOverlay("Garage")
        forwardingPlayer.setReconnectingOverlay("Basement")  // different server, e.g. user switched

        verify(exactly = 2) { listener.onMediaMetadataChanged(any()) }
        assertEquals("Reconnecting to Basement...", forwardingPlayer.mediaMetadata.title.toString())
    }

    @Test
    fun `clearReconnectingOverlay is idempotent when no overlay is active`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        forwardingPlayer.addListener(listener)

        forwardingPlayer.clearReconnectingOverlay()
        forwardingPlayer.clearReconnectingOverlay()

        verify(exactly = 0) { listener.onMediaMetadataChanged(any()) }
        verify(exactly = 0) { listener.onPlaybackStateChanged(any()) }
    }

    @Test
    fun `overlay works even when no prior metadata was set`() {
        // Fresh client, no updateMetadata call has been made yet.
        forwardingPlayer.setReconnectingOverlay("FirstBoot")

        val metadata = forwardingPlayer.mediaMetadata
        assertEquals("Reconnecting to FirstBoot...", metadata.title.toString())
        assertEquals(Player.STATE_BUFFERING, forwardingPlayer.playbackState)
    }
}
