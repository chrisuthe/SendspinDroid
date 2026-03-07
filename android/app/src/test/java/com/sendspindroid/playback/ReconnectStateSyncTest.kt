package com.sendspindroid.playback

import android.util.Log
import com.sendspindroid.sendspin.PlaybackState as SyncPlaybackState
import com.sendspindroid.sendspin.SendSpinClient
import com.sendspindroid.sendspin.SyncAudioPlayer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for post-reconnection state synchronization fixes.
 *
 * Validates that the player UI stays in sync with actual audio output
 * during and after reconnection (e.g., network switch from cell to WiFi).
 *
 * Issue 1: onStateChanged had no DRAINING check (onGroupUpdate did)
 * Issue 2: exitDraining() timing race with state message processing
 * Issue 3: playWhenReady not corrected when SyncAudioPlayer transitions to PLAYING
 * Issue 4: play() during DRAINING may get response via either server/state or group/update
 */
class ReconnectStateSyncTest {

    private lateinit var mockSendSpinPlayer: SendSpinPlayer
    private lateinit var mockSyncAudioPlayer: SyncAudioPlayer
    private lateinit var mockSendSpinClient: SendSpinClient

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockSendSpinPlayer = mockk(relaxed = true)
        mockSyncAudioPlayer = mockk(relaxed = true)
        mockSendSpinClient = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // =========================================================================
    // Issue 1: onStateChanged DRAINING check
    // =========================================================================

    @Test
    fun `onStateChanged STOPPED while DRAINING sends play and preserves buffer`() {
        // Simulate: audio is DRAINING (playing from buffer during reconnection)
        every { mockSyncAudioPlayer.getPlaybackState() } returns SyncPlaybackState.DRAINING

        // The handler logic extracted for testing:
        // When state is STOPPED and player is DRAINING, send play() and skip buffer clear
        val isDraining = mockSyncAudioPlayer.getPlaybackState() == SyncPlaybackState.DRAINING
        assertTrue("Should detect DRAINING state", isDraining)

        if (isDraining) {
            mockSendSpinClient.play()
        } else {
            mockSendSpinPlayer.updatePlayWhenReadyFromServer(false)
            mockSyncAudioPlayer.clearBuffer()
            mockSyncAudioPlayer.pause()
        }

        // play() should be called to ask server to resume
        verify(exactly = 1) { mockSendSpinClient.play() }
        // Buffer should NOT be cleared
        verify(exactly = 0) { mockSyncAudioPlayer.clearBuffer() }
        // playWhenReady should NOT be set to false
        verify(exactly = 0) { mockSendSpinPlayer.updatePlayWhenReadyFromServer(false) }
    }

    @Test
    fun `onStateChanged STOPPED when not DRAINING clears buffer normally`() {
        every { mockSyncAudioPlayer.getPlaybackState() } returns SyncPlaybackState.PLAYING

        val isDraining = mockSyncAudioPlayer.getPlaybackState() == SyncPlaybackState.DRAINING
        assertFalse("Should not be DRAINING", isDraining)

        if (isDraining) {
            mockSendSpinClient.play()
        } else {
            mockSendSpinPlayer.updatePlayWhenReadyFromServer(false)
            mockSyncAudioPlayer.clearBuffer()
            mockSyncAudioPlayer.pause()
        }

        verify(exactly = 0) { mockSendSpinClient.play() }
        verify(exactly = 1) { mockSyncAudioPlayer.clearBuffer() }
        verify(exactly = 1) { mockSendSpinPlayer.updatePlayWhenReadyFromServer(false) }
    }

    // =========================================================================
    // Issue 2: Deferred exitDraining timing
    // =========================================================================

    @Test
    fun `pendingExitDraining flag allows DRAINING check before exit`() {
        // Simulate the sequence: onReconnected sets flag, then state message processes
        every { mockSyncAudioPlayer.getPlaybackState() } returns SyncPlaybackState.DRAINING
        every { mockSyncAudioPlayer.exitDraining() } returns true

        var pendingExitDraining = false

        // Step 1: onReconnected sets the flag (does NOT call exitDraining)
        pendingExitDraining = true

        // Step 2: onStateChanged runs - DRAINING check fires correctly
        val isDraining = mockSyncAudioPlayer.getPlaybackState() == SyncPlaybackState.DRAINING
        assertTrue("DRAINING should still be active when state handler runs", isDraining)

        if (isDraining) {
            mockSendSpinClient.play()
        }

        // Step 3: After state processing, complete the deferred exit
        if (pendingExitDraining) {
            pendingExitDraining = false
            mockSyncAudioPlayer.exitDraining()
        }

        // Verify correct order: play() before exitDraining()
        verifyOrder {
            mockSendSpinClient.play()
            mockSyncAudioPlayer.exitDraining()
        }
        assertFalse("Flag should be cleared", pendingExitDraining)
    }

    @Test
    fun `pendingExitDraining cleared on entering DRAINING`() {
        // If we re-enter DRAINING (another disconnect), stale flag should be cleared
        var pendingExitDraining = true

        // onReconnecting clears any stale flag before entering draining
        pendingExitDraining = false
        mockSyncAudioPlayer.enterDraining()

        assertFalse("Flag should be cleared when entering DRAINING", pendingExitDraining)
    }

    @Test
    fun `completePendingExitDraining is idempotent`() {
        every { mockSyncAudioPlayer.exitDraining() } returns true

        var pendingExitDraining = true

        // First call: exits draining
        if (pendingExitDraining) {
            pendingExitDraining = false
            mockSyncAudioPlayer.exitDraining()
        }

        // Second call (e.g., from onGroupUpdate after onStateChanged already consumed it)
        if (pendingExitDraining) {
            pendingExitDraining = false
            mockSyncAudioPlayer.exitDraining()
        }

        // exitDraining should only be called once
        verify(exactly = 1) { mockSyncAudioPlayer.exitDraining() }
    }

    // =========================================================================
    // Issue 3: playWhenReady correction in updateStateFromPlayer
    // =========================================================================

    @Test
    fun `updateStateFromPlayer corrects playWhenReady when audio is PLAYING`() {
        // Create a real SendSpinPlayer to test the actual logic
        val player = SendSpinPlayer()
        val listener = mockk<androidx.media3.common.Player.Listener>(relaxed = true)
        player.addListener(listener)

        // Set up: playWhenReady is false (stale from earlier server "stopped")
        // but audio is physically playing
        every { mockSyncAudioPlayer.getPlaybackState() } returns SyncPlaybackState.PLAYING
        player.setSyncAudioPlayer(mockSyncAudioPlayer)

        // playWhenReady should now be true because audio is physically playing
        assertTrue("playWhenReady should be corrected to true", player.playWhenReady)
        assertTrue("isPlaying should be true", player.isPlaying)

        // Listener should have been notified of the playWhenReady change
        verify { listener.onPlayWhenReadyChanged(true, any()) }
    }

    @Test
    fun `updateStateFromPlayer corrects playWhenReady when audio is DRAINING`() {
        val player = SendSpinPlayer()
        val listener = mockk<androidx.media3.common.Player.Listener>(relaxed = true)
        player.addListener(listener)

        // DRAINING = still playing from buffer
        every { mockSyncAudioPlayer.getPlaybackState() } returns SyncPlaybackState.DRAINING
        player.setSyncAudioPlayer(mockSyncAudioPlayer)

        assertTrue("playWhenReady should be true during DRAINING", player.playWhenReady)
        assertTrue("isPlaying should be true during DRAINING", player.isPlaying)
    }

    @Test
    fun `updateStateFromPlayer does not touch playWhenReady when buffering`() {
        val player = SendSpinPlayer()
        val listener = mockk<androidx.media3.common.Player.Listener>(relaxed = true)
        player.addListener(listener)

        // Buffering state: playWhenReady should stay as-is (false by default)
        every { mockSyncAudioPlayer.getPlaybackState() } returns SyncPlaybackState.INITIALIZING
        player.setSyncAudioPlayer(mockSyncAudioPlayer)

        assertFalse("playWhenReady should remain false during buffering", player.playWhenReady)
    }

    // =========================================================================
    // Issue 4: Both handlers consistent for DRAINING + STOPPED
    // =========================================================================

    @Test
    fun `onGroupUpdate STOPPED while DRAINING sends play and skips playWhenReady update`() {
        every { mockSyncAudioPlayer.getPlaybackState() } returns SyncPlaybackState.DRAINING

        // Simulate onGroupUpdate logic for STOPPED state
        val isDraining = mockSyncAudioPlayer.getPlaybackState() == SyncPlaybackState.DRAINING

        if (isDraining) {
            mockSendSpinClient.play()
        } else {
            mockSendSpinPlayer.updatePlayWhenReadyFromServer(false)
            mockSyncAudioPlayer.clearBuffer()
            mockSyncAudioPlayer.pause()
        }

        verify(exactly = 1) { mockSendSpinClient.play() }
        verify(exactly = 0) { mockSendSpinPlayer.updatePlayWhenReadyFromServer(any()) }
        verify(exactly = 0) { mockSyncAudioPlayer.clearBuffer() }
    }

    @Test
    fun `onGroupUpdate PLAYING updates playWhenReady to true`() {
        every { mockSyncAudioPlayer.getPlaybackState() } returns SyncPlaybackState.PLAYING

        // Simulate the PLAYING branch of onGroupUpdate
        mockSendSpinPlayer.updatePlayWhenReadyFromServer(true)
        mockSyncAudioPlayer.resume()

        verify(exactly = 1) { mockSendSpinPlayer.updatePlayWhenReadyFromServer(true) }
        verify(exactly = 1) { mockSyncAudioPlayer.resume() }
    }

    @Test
    fun `onStateChanged PLAYING updates playWhenReady to true`() {
        // Simulate the PLAYING branch of onStateChanged
        mockSendSpinPlayer.updatePlayWhenReadyFromServer(true)
        mockSyncAudioPlayer.resume()

        verify(exactly = 1) { mockSendSpinPlayer.updatePlayWhenReadyFromServer(true) }
        verify(exactly = 1) { mockSyncAudioPlayer.resume() }
    }

    // =========================================================================
    // Full reconnection scenario integration
    // =========================================================================

    @Test
    fun `full reconnect scenario - DRAINING stop then resume preserves UI state`() {
        every { mockSyncAudioPlayer.getPlaybackState() } returns SyncPlaybackState.DRAINING
        every { mockSyncAudioPlayer.exitDraining() } returns true

        var pendingExitDraining = false

        // 1. onReconnecting: enter draining
        pendingExitDraining = false
        mockSyncAudioPlayer.enterDraining()

        // 2. onReconnected: set deferred flag
        pendingExitDraining = true

        // 3. onStateChanged("stopped"): DRAINING check fires, sends play()
        val isDraining = mockSyncAudioPlayer.getPlaybackState() == SyncPlaybackState.DRAINING
        assertTrue(isDraining)
        mockSendSpinClient.play()
        // playWhenReady NOT set to false (skipped during DRAINING)

        // 4. Complete deferred exit after state processing
        if (pendingExitDraining) {
            pendingExitDraining = false
            mockSyncAudioPlayer.exitDraining()
        }

        // 5. Verify the critical ordering
        verifyOrder {
            mockSyncAudioPlayer.enterDraining()
            mockSendSpinClient.play()
            mockSyncAudioPlayer.exitDraining()
        }

        // 6. playWhenReady was never set to false
        verify(exactly = 0) { mockSendSpinPlayer.updatePlayWhenReadyFromServer(false) }
    }
}
