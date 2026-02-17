package com.sendspindroid.playback

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MetadataForwardingPlayer].
 *
 * Verifies:
 * - H-12: Duplicate listener registration is prevented (matching SendSpinPlayer pattern)
 * - Metadata notification fires exactly once per listener per update
 */
class MetadataForwardingPlayerTest {

    private lateinit var mockPlayer: Player
    private lateinit var forwardingPlayer: MetadataForwardingPlayer

    @Before
    fun setUp() {
        mockPlayer = mockk(relaxed = true)
        forwardingPlayer = MetadataForwardingPlayer(mockPlayer)
    }

    // =========================================================================
    // H-12: Duplicate listener registration
    // =========================================================================

    @Test
    fun addListener_sameListenerTwice_onlyRegisteredOnce() {
        val listener = mockk<Player.Listener>(relaxed = true)

        forwardingPlayer.addListener(listener)
        forwardingPlayer.addListener(listener)

        // Trigger metadata notification
        forwardingPlayer.updateMetadata(title = "Test Song", artist = "Test Artist", album = null)

        // Listener should only be called once, not twice
        verify(exactly = 1) { listener.onMediaMetadataChanged(any()) }
    }

    @Test
    fun addListener_differentListeners_bothRegistered() {
        val listener1 = mockk<Player.Listener>(relaxed = true)
        val listener2 = mockk<Player.Listener>(relaxed = true)

        forwardingPlayer.addListener(listener1)
        forwardingPlayer.addListener(listener2)

        forwardingPlayer.updateMetadata(title = "Test Song", artist = null, album = null)

        verify(exactly = 1) { listener1.onMediaMetadataChanged(any()) }
        verify(exactly = 1) { listener2.onMediaMetadataChanged(any()) }
    }

    @Test
    fun addListener_sameListenerThreeTimes_onlyOneCallback() {
        val listener = mockk<Player.Listener>(relaxed = true)

        forwardingPlayer.addListener(listener)
        forwardingPlayer.addListener(listener)
        forwardingPlayer.addListener(listener)

        forwardingPlayer.updateMetadata(title = "Song", artist = "Artist", album = "Album")

        verify(exactly = 1) { listener.onMediaMetadataChanged(any()) }
    }

    @Test
    fun removeListener_thenUpdate_noCallback() {
        val listener = mockk<Player.Listener>(relaxed = true)

        forwardingPlayer.addListener(listener)
        forwardingPlayer.removeListener(listener)

        forwardingPlayer.updateMetadata(title = "Song", artist = null, album = null)

        verify(exactly = 0) { listener.onMediaMetadataChanged(any()) }
    }

    @Test
    fun addListener_addRemoveAdd_singleCallback() {
        val listener = mockk<Player.Listener>(relaxed = true)

        forwardingPlayer.addListener(listener)
        forwardingPlayer.removeListener(listener)
        forwardingPlayer.addListener(listener)

        forwardingPlayer.updateMetadata(title = "Song", artist = null, album = null)

        // Re-added once after removal, so exactly one callback
        verify(exactly = 1) { listener.onMediaMetadataChanged(any()) }
    }

    // =========================================================================
    // clearMetadata also respects dedup
    // =========================================================================

    @Test
    fun clearMetadata_duplicateListener_onlyOneCallback() {
        val listener = mockk<Player.Listener>(relaxed = true)

        forwardingPlayer.addListener(listener)
        forwardingPlayer.addListener(listener)

        forwardingPlayer.clearMetadata()

        verify(exactly = 1) { listener.onMediaMetadataChanged(MediaMetadata.EMPTY) }
    }

    // =========================================================================
    // Metadata content correctness
    // =========================================================================

    @Test
    fun updateMetadata_setsTitle_returnedByGetMediaMetadata() {
        forwardingPlayer.updateMetadata(title = "My Song", artist = "My Artist", album = null)

        val metadata = forwardingPlayer.mediaMetadata
        assertEquals("My Song", metadata.title?.toString())
        assertEquals("My Artist", metadata.artist?.toString())
    }

    @Test
    fun updateMetadata_emptyTitle_clearsField() {
        forwardingPlayer.updateMetadata(title = "Song", artist = null, album = null)
        forwardingPlayer.updateMetadata(title = "", artist = null, album = null)

        // Empty string clears the field; with no title or artist, falls back to underlying player
        val metadata = forwardingPlayer.mediaMetadata
        // Since mockPlayer.mediaMetadata returns default (null/empty), it should be that
        assertNull(metadata.title)
    }

    @Test
    fun updateMetadata_nullTitle_preservesExisting() {
        forwardingPlayer.updateMetadata(title = "Song", artist = "Artist", album = null)
        forwardingPlayer.updateMetadata(title = null, artist = "New Artist", album = null)

        val metadata = forwardingPlayer.mediaMetadata
        assertEquals("Song", metadata.title?.toString())
        assertEquals("New Artist", metadata.artist?.toString())
    }
}
