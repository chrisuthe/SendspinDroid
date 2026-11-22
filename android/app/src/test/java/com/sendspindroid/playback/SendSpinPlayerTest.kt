package com.sendspindroid.playback

import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
 * - setError exposing error via getPlayerError and state transitions
 * - the timeline stays single-item only (SendSpin has no queue state)
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
    // Every way a pause reaches the server
    // =========================================================================

    private fun pauseRoutes(): List<Pair<String, () -> Unit>> = listOf(
        "pause" to { player.pause() },
        "stop" to { player.stop() },
        "setPlayWhenReady" to { player.setPlayWhenReady(false) },
    )

    @Test
    fun `a pause that was dealt with sends no command, on every route`() {
        for ((name, route) in pauseRoutes()) {
            val client = mockk<com.sendspindroid.sendspin.SendSpin>(relaxed = true)
            player.setSendSpinClient(client)
            player.onPauseRequested = { true }
            route()
            verify(exactly = 0) { client.pause() }
            assertFalse(name, player.playWhenReady)
        }
    }

    @Test
    fun `a pause that was not dealt with sends one command, on every route`() {
        for ((_, route) in pauseRoutes()) {
            val client = mockk<com.sendspindroid.sendspin.SendSpin>(relaxed = true)
            player.setSendSpinClient(client)
            player.onPauseRequested = { false }
            route()
            verify(exactly = 1) { client.pause() }
        }
    }

    @Test
    fun `play does not ask the pause hook`() {
        var asked = false
        player.onPauseRequested = { asked = true; true }
        player.play()
        assertFalse(asked)
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

    // =========================================================================
    // Timeline stays single-item -- SendSpin supplies no queue state
    // =========================================================================

    @Test
    fun timelineIsAlwaysSingleItem() {
        val source = java.io.File("src/main/java/com/sendspindroid/playback/SendSpinPlayer.kt").readText()
        val offending = listOf(
            "updateQueueItems",
            "onQueueItemSelected",
            "MultiItemTimeline",
            "queueMediaItems",
            "currentQueueIndex"
        ).filter { source.contains(it) }
        org.junit.Assert.assertEquals(
            "SendSpin supplies no queue state, so the player has no multi-item timeline",
            emptyList<String>(),
            offending
        )
    }

    // =========================================================================
    // Position follows the server's reported progress and speed
    // =========================================================================

    @Test
    fun `position advances at the server speed`() {
        player.updatePlaybackState(null, positionMs = 60_000, durationMs = 240_000, playbackSpeed = 1500)

        every { SystemClock.elapsedRealtime() } returns 11_000L

        assertEquals(75_000L, player.currentPosition)
    }

    @Test
    fun `position advances while local audio is not playing`() {
        // The group plays on while this device buffers.
        player.updatePlaybackState(null, positionMs = 60_000, durationMs = 240_000, playbackSpeed = 1000)

        every { SystemClock.elapsedRealtime() } returns 11_000L

        assertFalse(player.isPlaying)
        assertEquals(70_000L, player.currentPosition)
    }

    @Test
    fun `speed zero holds the position while local audio plays`() {
        player.updatePlaybackState(
            com.sendspindroid.sendspin.PlaybackState.PLAYING,
            positionMs = 60_000, durationMs = 240_000, playbackSpeed = 0
        )

        every { SystemClock.elapsedRealtime() } returns 11_000L

        assertTrue(player.isPlaying)
        assertEquals(60_000L, player.currentPosition)
    }

    @Test
    fun `playback parameters report the server speed`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        player.addListener(listener)

        player.updatePlaybackState(null, positionMs = 0, durationMs = 240_000, playbackSpeed = 1500)

        assertEquals(1.5f, player.playbackParameters.speed, 0.0f)
        verify(exactly = 1) { listener.onPlaybackParametersChanged(any()) }
    }

    @Test
    fun `speed zero leaves the playback parameters at normal speed`() {
        // Media3 rejects a speed of 0; paused is carried by the position not moving.
        player.updatePlaybackState(null, positionMs = 0, durationMs = 240_000, playbackSpeed = 0)

        assertEquals(1.0f, player.playbackParameters.speed, 0.0f)
    }

    // =========================================================================
    // A play request reaches the host before the server
    // =========================================================================

    @Test
    fun `play runs the play hook before sending the command`() {
        val order = mutableListOf<String>()
        val client = mockk<com.sendspindroid.sendspin.SendSpin>(relaxed = true)
        every { client.play() } answers { order += "command" }
        player.setSendSpinClient(client)
        player.onPlayRequested = { order += "hook"; true }

        player.play()

        assertEquals(listOf("hook", "command"), order)
    }

    @Test
    fun `play is not sent when nothing can play`() {
        val client = mockk<com.sendspindroid.sendspin.SendSpin>(relaxed = true)
        player.setSendSpinClient(client)
        player.onPlayRequested = { false }

        player.play()

        verify(exactly = 0) { client.play() }
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `pause does not run the play hook`() {
        var ran = false
        player.onPlayRequested = { ran = true; true }
        player.pause()
        assertFalse(ran)
    }
}
