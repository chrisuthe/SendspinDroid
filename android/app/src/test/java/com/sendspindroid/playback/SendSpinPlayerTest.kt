package com.sendspindroid.playback

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SendSpinPlayer].
 *
 * Covers:
 * - playWhenReady listener notification from server updates
 * - seekTo media item invoking queue callback
 * - setError exposing error via getPlayerError and state transitions
 */
class SendSpinPlayerTest {

    private lateinit var player: SendSpinPlayer

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1000L

        player = SendSpinPlayer()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // =========================================================================
    // updatePlayWhenReadyFromServer notifies listeners
    // =========================================================================

    @Test
    fun `updatePlayWhenReadyFromServer true fires onPlayWhenReadyChanged`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        player.addListener(listener)

        player.updatePlayWhenReadyFromServer(true)

        verify(exactly = 1) {
            listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
        }
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `updatePlayWhenReadyFromServer false fires onPlayWhenReadyChanged`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        player.addListener(listener)

        // First set to true, then back to false
        player.updatePlayWhenReadyFromServer(true)
        player.updatePlayWhenReadyFromServer(false)

        verify(exactly = 1) {
            listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
        }
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `updatePlayWhenReadyFromServer same value does not notify`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        player.addListener(listener)

        // playWhenReady starts as false, setting false again should not notify
        player.updatePlayWhenReadyFromServer(false)

        verify(exactly = 0) {
            listener.onPlayWhenReadyChanged(any(), any())
        }
    }

    // =========================================================================
    // seekTo media item invokes onQueueItemSelected
    // =========================================================================

    @Test
    fun `seekTo mediaItemIndex invokes onQueueItemSelected callback`() {
        var selectedMediaId: String? = null
        player.onQueueItemSelected = { mediaId -> selectedMediaId = mediaId }

        // Set up a queue with multiple items
        val items = listOf(
            MediaItem.Builder().setMediaId("track-1").build(),
            MediaItem.Builder().setMediaId("track-2").build(),
            MediaItem.Builder().setMediaId("track-3").build()
        )
        player.updateQueueItems(items, 0)

        // Seek to a different queue item
        player.seekTo(/* mediaItemIndex= */ 2, /* positionMs= */ 0L)

        assertEquals("track-3", selectedMediaId)
    }

    @Test
    fun `seekTo same mediaItemIndex does not invoke callback`() {
        var callbackCount = 0
        player.onQueueItemSelected = { callbackCount++ }

        val items = listOf(
            MediaItem.Builder().setMediaId("track-1").build(),
            MediaItem.Builder().setMediaId("track-2").build()
        )
        player.updateQueueItems(items, 0)

        // Seek to the currently playing item
        player.seekTo(/* mediaItemIndex= */ 0, /* positionMs= */ 0L)

        assertEquals(0, callbackCount)
    }

    @Test
    fun `seekTo mediaItemIndex notifies onMediaItemTransition`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        player.addListener(listener)
        player.onQueueItemSelected = {}

        val items = listOf(
            MediaItem.Builder().setMediaId("track-1").build(),
            MediaItem.Builder().setMediaId("track-2").build()
        )
        player.updateQueueItems(items, 0)

        player.seekTo(1, 0L)

        verify {
            listener.onMediaItemTransition(
                match { it.mediaId == "track-2" },
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            )
        }
    }

    // =========================================================================
    // setError exposes via getPlayerError
    // =========================================================================

    @Test
    fun `setError makes error retrievable via getPlayerError`() {
        assertNull(player.playerError)

        player.setError("Connection lost")

        val error = player.playerError
        assertNotNull(error)
        assertEquals("Connection lost", error!!.message)
    }

    @Test
    fun `setError notifies listeners with onPlayerError`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        player.addListener(listener)

        player.setError("Network timeout")

        verify(exactly = 1) { listener.onPlayerError(any()) }
        verify(exactly = 1) { listener.onPlayerErrorChanged(any()) }
    }

    @Test
    fun `clearError removes error and notifies listeners`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        player.addListener(listener)

        player.setError("Temporary error")
        assertNotNull(player.playerError)

        player.clearError()

        assertNull(player.playerError)
        verify(exactly = 1) { listener.onPlayerErrorChanged(null) }
    }

    @Test
    fun `clearError when no error does not notify`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        player.addListener(listener)

        player.clearError()

        verify(exactly = 0) { listener.onPlayerErrorChanged(any()) }
    }
}
