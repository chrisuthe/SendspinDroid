package com.sendspindroid.e2e

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.sendspindroid.playback.SendSpinPlayer
import com.sendspindroid.sendspin.SendSpinClient
import com.sendspindroid.sendspin.SyncAudioPlayer
import com.sendspindroid.sendspin.PlaybackState as SyncPlaybackState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

/**
 * E2E Test 3: Browse MA library -> select album -> verify queue
 *
 * Tests the Music Assistant library browsing flow through the SendSpinPlayer's
 * queue management. This test operates at the Player level since MA library
 * browsing goes through PlaybackService's MediaLibrarySession callbacks, which
 * are not testable in unit tests (require Android framework).
 *
 * What IS testable here:
 * - SendSpinPlayer's queue management (updateQueueItems, seekTo for queue selection)
 * - MediaItem construction with proper metadata
 * - Timeline updates when queue changes
 * - Queue item selection callback
 *
 * What requires instrumented/manual testing:
 * - MusicAssistantManager.getAlbumTracks() API calls
 * - MediaLibrarySession.getChildren() browse tree
 * - Android Auto queue display rendering
 */
class BrowseMaLibraryQueueTest : E2ETestBase() {

    private lateinit var player: SendSpinPlayer
    private lateinit var mockSyncAudioPlayer: SyncAudioPlayer
    private lateinit var playerListener: androidx.media3.common.Player.Listener

    override fun setUp() {
        super.setUp()
        player = SendSpinPlayer()
        mockSyncAudioPlayer = mockk(relaxed = true)
        playerListener = mockk(relaxed = true)
        player.addListener(playerListener)

        // Connect the player to the client
        player.setSendSpinClient(client)
    }

    @Test
    fun `update queue items populates timeline with multiple items`() {
        val items = createAlbumTracks("Test Album", 5)

        player.updateQueueItems(items, currentIndex = 0)

        assertEquals("Media item count should match queue size", 5, player.mediaItemCount)
        assertEquals("Current index should be 0", 0, player.currentMediaItemIndex)
        assertEquals("Current item should be first track",
            "Track 1", player.currentMediaItem?.mediaMetadata?.title?.toString())

        // Verify timeline change notification
        verify { playerListener.onTimelineChanged(any(), any()) }
    }

    @Test
    fun `selecting queue item triggers callback and updates current index`() {
        val items = createAlbumTracks("Test Album", 5)
        var selectedMediaId: String? = null
        player.onQueueItemSelected = { mediaId -> selectedMediaId = mediaId }

        player.updateQueueItems(items, currentIndex = 0)

        // Simulate selecting track 3 (index 2)
        player.seekTo(/* mediaItemIndex= */ 2, /* positionMs= */ 0)

        assertEquals("Current index should be 2", 2, player.currentMediaItemIndex)
        assertEquals("Track 3", player.currentMediaItem?.mediaMetadata?.title?.toString())
        assertNotNull("Queue item selection callback should fire", selectedMediaId)
        assertEquals("album_track_2", selectedMediaId)

        // Verify media item transition notification
        verify {
            playerListener.onMediaItemTransition(any(),
                androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        }
    }

    @Test
    fun `current media item updates in-place within queue`() {
        val items = createAlbumTracks("Test Album", 3)
        player.updateQueueItems(items, currentIndex = 1)

        // Now update the currently playing item's metadata (server sends new track info)
        player.updateMediaItem(
            title = "Updated Track 2",
            artist = "Updated Artist",
            album = "Test Album",
            durationMs = 300000
        )

        // Queue should still have 3 items
        assertEquals("Queue size should remain 3", 3, player.mediaItemCount)
        // Current item should be updated
        assertEquals("Updated Track 2",
            player.currentMediaItem?.mediaMetadata?.title?.toString())
        // Index should remain the same
        assertEquals("Current index should stay at 1", 1, player.currentMediaItemIndex)
    }

    @Test
    fun `empty queue falls back to single item mode`() {
        // Set a current media item first
        player.updateMediaItem("Track 1", "Artist", "Album", 200000)

        // Set queue
        val items = createAlbumTracks("Album", 3)
        player.updateQueueItems(items, currentIndex = 0)
        assertEquals(3, player.mediaItemCount)

        // Clear queue
        player.updateQueueItems(emptyList(), currentIndex = 0)

        // Should fall back to single item (the previously set current item)
        assertEquals(0, player.currentMediaItemIndex)
    }

    @Test
    fun `queue index is coerced to valid range`() {
        val items = createAlbumTracks("Album", 3)

        // Set index beyond range
        player.updateQueueItems(items, currentIndex = 10)

        // Should be coerced to max valid index (2)
        assertEquals("Index should be coerced to max valid", 2, player.currentMediaItemIndex)
    }

    @Test
    fun `previous and next media item indices are correct`() {
        val items = createAlbumTracks("Album", 5)

        // At start
        player.updateQueueItems(items, currentIndex = 0)
        assertEquals(-1, player.previousMediaItemIndex) // INDEX_UNSET is -1 (C.INDEX_UNSET)
        assertEquals(1, player.nextMediaItemIndex)

        // In middle
        player.updateQueueItems(items, currentIndex = 2)
        assertEquals(1, player.previousMediaItemIndex)
        assertEquals(3, player.nextMediaItemIndex)

        // At end
        player.updateQueueItems(items, currentIndex = 4)
        assertEquals(3, player.previousMediaItemIndex)
        assertEquals(-1, player.nextMediaItemIndex) // INDEX_UNSET
    }

    @Test
    fun `getMediaItemAt returns correct items`() {
        val items = createAlbumTracks("Album", 3)
        player.updateQueueItems(items, currentIndex = 0)

        for (i in 0 until 3) {
            val item = player.getMediaItemAt(i)
            assertEquals("Track ${i + 1}", item.mediaMetadata.title?.toString())
        }
    }

    @Test
    fun `seekToNext sends next command to client`() {
        connectAndHandshake()
        player.setSendSpinClient(client)

        player.seekToNext()

        // Should have sent a "next" command via the transport
        assertTrue(
            "seekToNext should send next command",
            fakeTransport.hasSentMessageContaining("next")
        )
    }

    @Test
    fun `seekToPrevious sends previous command to client`() {
        connectAndHandshake()
        player.setSendSpinClient(client)

        player.seekToPrevious()

        assertTrue(
            "seekToPrevious should send previous command",
            fakeTransport.hasSentMessageContaining("previous")
        )
    }

    @Test
    fun `playback state updates correctly when sync player set`() {
        every { mockSyncAudioPlayer.getPlaybackState() } returns SyncPlaybackState.PLAYING

        player.setSyncAudioPlayer(mockSyncAudioPlayer)

        assertTrue("Should be playing", player.isPlaying)
        assertEquals(
            androidx.media3.common.Player.STATE_READY,
            player.playbackState
        )
    }

    // ========== Helper Methods ==========

    private fun createAlbumTracks(albumName: String, count: Int): List<MediaItem> {
        return (1..count).map { i ->
            MediaItem.Builder()
                .setMediaId("album_track_${i - 1}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Track $i")
                        .setArtist("Test Artist")
                        .setAlbumTitle(albumName)
                        .build()
                )
                .build()
        }
    }
}
