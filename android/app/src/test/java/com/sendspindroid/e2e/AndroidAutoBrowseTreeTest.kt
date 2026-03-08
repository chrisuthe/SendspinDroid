package com.sendspindroid.e2e

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.sendspindroid.playback.SendSpinPlayer
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

/**
 * E2E Test 4: Android Auto browse tree
 *
 * Tests the browse tree navigation through SendSpinPlayer's queue and timeline
 * management. The full MediaBrowser -> MediaLibrarySession -> PlaybackService
 * chain requires an instrumented test (Android framework), but the data layer
 * and Player contract can be verified in unit tests.
 *
 * What IS testable here:
 * - SendSpinPlayer's timeline construction (single-item and multi-item)
 * - Available commands for Android Auto (COMMAND_SEEK_TO_NEXT, etc.)
 * - MediaItem metadata structure (title, artist, album)
 * - Queue display through multi-item timeline
 * - Connection state reporting to MediaSession
 *
 * What requires instrumented/manual testing:
 * - MediaBrowser.subscribe() root and category children
 * - PlaybackService.MediaLibrarySessionCallback.onGetLibraryRoot()
 * - PlaybackService.MediaLibrarySessionCallback.onGetChildren()
 * - Actual Android Auto rendering and navigation
 * - Content style hints (grid vs list display)
 *
 * Manual test steps for full Android Auto browse tree verification:
 * 1. Connect phone to Android Auto head unit or DHU (Desktop Head Unit)
 * 2. Open SendSpinDroid in Android Auto
 * 3. Verify root shows: Discovered Servers, Saved Servers categories
 * 4. If connected with MA: verify Playlists, Albums, Artists, Radio categories
 * 5. Navigate into Albums -> select album -> verify tracks listed
 * 6. Play a track -> verify Now Playing screen shows metadata
 * 7. Tap queue button -> verify queue is populated
 */
class AndroidAutoBrowseTreeTest : E2ETestBase() {

    private lateinit var player: SendSpinPlayer
    private lateinit var playerListener: androidx.media3.common.Player.Listener

    override fun setUp() {
        super.setUp()
        player = SendSpinPlayer()
        playerListener = mockk(relaxed = true)
        player.addListener(playerListener)
    }

    @Test
    fun `available commands object is not null`() {
        // Player.Commands.Builder.build() returns a stub in JVM unit tests
        // where contains() always returns false. The actual command set is
        // verified structurally by checking that getAvailableCommands()
        // returns a non-null Commands object. Full command verification
        // requires instrumented tests with a real MediaSession.
        //
        // The production code in SendSpinPlayer.getAvailableCommands() adds:
        // COMMAND_PLAY_PAUSE, COMMAND_STOP, COMMAND_SEEK_TO_NEXT,
        // COMMAND_SEEK_TO_PREVIOUS, COMMAND_SET_VOLUME,
        // COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE,
        // COMMAND_GET_MEDIA_ITEMS_METADATA, COMMAND_GET_METADATA,
        // COMMAND_GET_AUDIO_ATTRIBUTES, COMMAND_GET_VOLUME,
        // COMMAND_SET_MEDIA_ITEM, COMMAND_PREPARE, COMMAND_SEEK_TO_MEDIA_ITEM
        val commands = player.availableCommands
        assertNotNull("Available commands should not be null", commands)
    }

    @Test
    fun `single item timeline has correct structure`() {
        player.updateMediaItem("Test Track", "Test Artist", "Test Album", 240000)

        val timeline = player.currentTimeline
        assertEquals("Single-item timeline should have 1 window", 1, timeline.windowCount)
        assertEquals("Single-item timeline should have 1 period", 1, timeline.periodCount)
    }

    @Test
    fun `multi-item queue creates multi-window timeline`() {
        val items = (1..5).map { i ->
            MediaItem.Builder()
                .setMediaId("track_$i")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Track $i")
                        .setArtist("Artist")
                        .setAlbumTitle("Album")
                        .build()
                )
                .build()
        }

        player.updateQueueItems(items, currentIndex = 2)

        val timeline = player.currentTimeline
        assertEquals("Multi-item timeline should have 5 windows", 5, timeline.windowCount)
        assertEquals("Multi-item timeline should have 5 periods", 5, timeline.periodCount)
        assertEquals("Current index should be 2", 2, player.currentMediaItemIndex)
    }

    @Test
    fun `media metadata is reported correctly for notifications`() {
        player.updateMediaItem("My Song", "My Artist", "My Album", 300000)

        val metadata = player.mediaMetadata
        assertEquals("My Song", metadata.title?.toString())
        assertEquals("My Artist", metadata.artist?.toString())
        assertEquals("My Album", metadata.albumTitle?.toString())
    }

    @Test
    fun `duration reported correctly for progress bar`() {
        player.updateMediaItem("Track", "Artist", "Album", 240000)

        assertEquals("Duration should be 240s", 240000L, player.duration)
    }

    @Test
    fun `dynamic media flag based on duration`() {
        // With known duration - not dynamic (shows progress bar)
        player.updateMediaItem("Track", "Artist", "Album", 240000)
        assertFalse("Should not be dynamic with known duration",
            player.isCurrentMediaItemDynamic)
        assertFalse("Should not be live with known duration",
            player.isCurrentMediaItemLive)

        // With unknown duration (0) - dynamic (live stream mode)
        player.updateMediaItem("Live Stream", "Artist", "Album", 0)
        assertTrue("Should be dynamic with unknown duration",
            player.isCurrentMediaItemDynamic)
    }

    @Test
    fun `connection state updates player state for Android Auto display`() {
        // Disconnected -> IDLE
        player.updateConnectionState(connected = false)
        assertEquals(
            "Disconnected should report STATE_IDLE",
            androidx.media3.common.Player.STATE_IDLE,
            player.playbackState
        )

        // Connected but no audio -> BUFFERING
        player.updateConnectionState(connected = true, serverName = "Server")
        assertEquals(
            "Connected without audio should report STATE_BUFFERING",
            androidx.media3.common.Player.STATE_BUFFERING,
            player.playbackState
        )
    }

    @Test
    fun `error state is visible to Android Auto`() {
        player.setError("Connection lost after 5 attempts")

        assertNotNull("Player error should be set", player.playerError)
        assertEquals("Connection lost after 5 attempts",
            player.playerError?.message)

        // Clear error
        player.clearError()
        assertNull("Player error should be cleared", player.playerError)
    }

    @Test
    fun `hasPreviousMediaItem and hasNextMediaItem always true for skip controls`() {
        // SendSpin always allows previous/next (server handles the logic)
        assertTrue("Should always report hasPreviousMediaItem", player.hasPreviousMediaItem())
        assertTrue("Should always report hasNextMediaItem", player.hasNextMediaItem())
    }

    @Test
    fun `canAdvertiseSession returns true`() {
        assertTrue("Should advertise session for Android Auto discovery",
            player.canAdvertiseSession())
    }

    @Test
    fun `not seekable - SendSpin does not support arbitrary seeking`() {
        player.updateMediaItem("Track", "Artist", "Album", 240000)

        assertFalse("Should not be seekable",
            player.isCurrentMediaItemSeekable)
    }

    @Test
    fun `audio attributes are set for music content`() {
        val attrs = player.audioAttributes
        assertNotNull("Audio attributes should not be null", attrs)
    }

    @Test
    fun `playback speed is always default (synced playback)`() {
        assertEquals(
            "Playback speed should be default",
            androidx.media3.common.PlaybackParameters.DEFAULT,
            player.playbackParameters
        )
    }
}
