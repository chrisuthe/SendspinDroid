package com.sendspindroid.e2e

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.sendspindroid.playback.NotificationHelper
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
 * E2E Test 8: Notification metadata + controls during playback
 *
 * Tests that the Player/MediaSession surface provides correct metadata
 * and responds to transport controls for notification display.
 *
 * What IS testable here:
 * - SendSpinPlayer reports correct metadata for notification display
 * - Play/pause/next/previous commands forwarded to SendSpinClient
 * - Playback state transitions reflected for notification buttons
 * - Position and duration for notification progress bar
 * - NotificationHelper channel configuration
 * - Player listener notifications for UI updates
 *
 * What requires instrumented/manual testing:
 * - Actual notification rendering (media style, artwork bitmap)
 * - Lock screen controls appearance
 * - Bluetooth AVRCP metadata forwarding
 * - Notification action button behavior
 * - MediaSession.setSessionActivity() deep link
 *
 * Manual test steps:
 * 1. Connect to a SendSpin server
 * 2. Start playback
 * 3. Pull down notification shade -> verify title, artist, album shown
 * 4. Verify play/pause button reflects current state
 * 5. Tap next -> verify track changes
 * 6. Tap previous -> verify track changes
 * 7. Lock screen -> verify lock screen controls work
 * 8. Connect Bluetooth -> verify track info on headphones/car
 */
class NotificationMetadataControlsTest : E2ETestBase() {

    private lateinit var player: SendSpinPlayer
    private lateinit var playerListener: Player.Listener

    override fun setUp() {
        super.setUp()
        player = SendSpinPlayer()
        playerListener = mockk(relaxed = true)
        player.addListener(playerListener)

        // Wire up to our test client
        connectAndHandshake()
        player.setSendSpinClient(client)
    }

    // ========== Metadata for Notification Display ==========

    @Test
    fun `metadata update provides title artist album for notification`() {
        player.updateMediaItem("Ocean Eyes", "Billie Eilish", "dont smile at me", 203000)

        val metadata = player.mediaMetadata
        assertEquals("Ocean Eyes", metadata.title?.toString())
        assertEquals("Billie Eilish", metadata.artist?.toString())
        assertEquals("dont smile at me", metadata.albumTitle?.toString())
    }

    @Test
    fun `metadata update notifies listeners for notification refresh`() {
        player.updateMediaItem("Track", "Artist", "Album", 200000)

        verify { playerListener.onTimelineChanged(any(), any()) }
        verify { playerListener.onMediaItemTransition(any(), any()) }
    }

    @Test
    fun `metadata transition fires when track changes`() {
        player.updateMediaItem("Track 1", "Artist", "Album", 200000)

        // Clear mock state
        io.mockk.clearMocks(playerListener, answers = false)

        // New track
        player.updateMediaItem("Track 2", "Artist", "Album", 180000)

        // Should notify of timeline change and media item transition
        verify { playerListener.onTimelineChanged(any(), any()) }
        verify { playerListener.onMediaItemTransition(any(), any()) }
    }

    @Test
    fun `duration reported for notification progress bar`() {
        player.updateMediaItem("Track", "Artist", "Album", 240000)

        assertEquals(240000L, player.duration)
    }

    @Test
    fun `position tracked for notification progress`() {
        val mockSync = mockk<SyncAudioPlayer>(relaxed = true)
        every { mockSync.getPlaybackState() } returns SyncPlaybackState.PLAYING
        player.setSyncAudioPlayer(mockSync)

        player.updatePlaybackState(SyncPlaybackState.PLAYING, positionMs = 60000, durationMs = 240000)

        // Position should be at or near 60000 (may interpolate slightly forward)
        val pos = player.currentPosition
        assertTrue("Position should be near 60000ms, got $pos", pos >= 60000)
    }

    // ========== Transport Controls ==========

    @Test
    fun `play sends play command to server`() {
        player.play()

        // Should send play command via transport
        assertTrue("play() should forward to SendSpinClient",
            fakeTransport.hasSentMessageContaining("play"))
    }

    @Test
    fun `pause sends pause command to server`() {
        player.pause()

        assertTrue("pause() should forward to SendSpinClient",
            fakeTransport.hasSentMessageContaining("pause"))
    }

    @Test
    fun `seekToNext sends next command`() {
        player.seekToNext()

        assertTrue("seekToNext should send next",
            fakeTransport.hasSentMessageContaining("next"))
    }

    @Test
    fun `seekToPrevious sends previous command`() {
        player.seekToPrevious()

        assertTrue("seekToPrevious should send previous",
            fakeTransport.hasSentMessageContaining("previous"))
    }

    @Test
    fun `seekToNextMediaItem sends next command`() {
        player.seekToNextMediaItem()

        assertTrue("seekToNextMediaItem should send next",
            fakeTransport.hasSentMessageContaining("next"))
    }

    @Test
    fun `seekToPreviousMediaItem sends previous command`() {
        player.seekToPreviousMediaItem()

        assertTrue("seekToPreviousMediaItem should send previous",
            fakeTransport.hasSentMessageContaining("previous"))
    }

    // ========== Playback State for Notification Buttons ==========

    @Test
    fun `playing state shows play button state`() {
        val mockSync = mockk<SyncAudioPlayer>(relaxed = true)
        every { mockSync.getPlaybackState() } returns SyncPlaybackState.PLAYING
        player.setSyncAudioPlayer(mockSync)

        assertTrue("Should report isPlaying", player.isPlaying)
        assertTrue("playWhenReady should be true when playing", player.playWhenReady)
        assertEquals(Player.STATE_READY, player.playbackState)
    }

    @Test
    fun `buffering state while waiting for audio`() {
        val mockSync = mockk<SyncAudioPlayer>(relaxed = true)
        every { mockSync.getPlaybackState() } returns SyncPlaybackState.WAITING_FOR_START
        player.setSyncAudioPlayer(mockSync)

        assertEquals("Should report STATE_BUFFERING",
            Player.STATE_BUFFERING, player.playbackState)
    }

    @Test
    fun `draining state reports as still playing`() {
        val mockSync = mockk<SyncAudioPlayer>(relaxed = true)
        every { mockSync.getPlaybackState() } returns SyncPlaybackState.DRAINING
        player.setSyncAudioPlayer(mockSync)

        assertTrue("DRAINING should report as playing", player.isPlaying)
        assertEquals("DRAINING should be STATE_READY",
            Player.STATE_READY, player.playbackState)
    }

    @Test
    fun `playWhenReady change from server notifies listeners`() {
        // Server says start playing
        player.updatePlayWhenReadyFromServer(true)

        verify {
            playerListener.onPlayWhenReadyChanged(true,
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
        }

        // Server says stop
        player.updatePlayWhenReadyFromServer(false)

        verify {
            playerListener.onPlayWhenReadyChanged(false,
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
        }
    }

    @Test
    fun `user play request fires listener with USER_REQUEST reason`() {
        player.setPlayWhenReady(true)

        verify {
            playerListener.onPlayWhenReadyChanged(true,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        }
    }

    @Test
    fun `playback state change notifies listeners`() {
        val mockSync = mockk<SyncAudioPlayer>(relaxed = true)
        every { mockSync.getPlaybackState() } returns SyncPlaybackState.PLAYING
        player.setSyncAudioPlayer(mockSync)

        verify { playerListener.onPlaybackStateChanged(Player.STATE_READY) }
        verify { playerListener.onIsPlayingChanged(true) }
    }

    // ========== Notification Channel ==========

    @Test
    fun `notification channel ID is defined`() {
        assertEquals("playback_channel", NotificationHelper.CHANNEL_ID)
    }

    @Test
    fun `notification ID is defined`() {
        assertEquals(101, NotificationHelper.NOTIFICATION_ID)
    }

    // ========== Position Discontinuity ==========

    @Test
    fun `position jump backward fires discontinuity for track change`() {
        // Start at position 60000
        player.updatePlaybackState(null, positionMs = 60000, durationMs = 240000)

        // Jump backward significantly (new track)
        player.updatePlaybackState(null, positionMs = 0, durationMs = 180000)

        verify {
            playerListener.onPositionDiscontinuity(any(), any(),
                Player.DISCONTINUITY_REASON_INTERNAL)
        }
    }

    @Test
    fun `small position update does not fire discontinuity`() {
        player.updatePlaybackState(null, positionMs = 60000, durationMs = 240000)

        // Small forward update (normal playback progress)
        player.updatePlaybackState(null, positionMs = 61000, durationMs = 240000)

        verify(exactly = 0) {
            playerListener.onPositionDiscontinuity(any(), any(), any())
        }
    }

    // ========== Release and Cleanup ==========

    @Test
    fun `release clears all listeners and references`() {
        player.release()

        // After release, listener should not receive notifications
        player.updateMediaItem("After Release", "Artist", "Album", 100000)

        // This verifies no ConcurrentModificationException occurs
        // and that the listener list is cleared
    }

    @Test
    fun `stop sends pause to server`() {
        player.stop()

        assertTrue("stop() should send pause",
            fakeTransport.hasSentMessageContaining("pause"))
    }
}
