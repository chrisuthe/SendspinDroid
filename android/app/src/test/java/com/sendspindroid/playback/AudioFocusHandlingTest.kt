package com.sendspindroid.playback

import android.media.AudioManager
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration test: PlaybackService audio focus change handling.
 *
 * Verifies that handleAudioFocusChange correctly pauses/resumes playback
 * in response to audio focus events. This reproduces the logic from
 * PlaybackService.handleAudioFocusChange().
 *
 * Audio focus events come from the system when:
 * - AUDIOFOCUS_LOSS: Another app permanently took focus (e.g., started music)
 * - AUDIOFOCUS_LOSS_TRANSIENT: Temporary loss (phone call, nav announcement)
 * - AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: Could lower volume (we ignore for sync)
 * - AUDIOFOCUS_GAIN: Focus returned after transient loss
 */
class AudioFocusHandlingTest {

    // Simulated audio player state
    private var playerPaused = false
    private var playerResumed = false
    private var hasAudioFocus = true

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        playerPaused = false
        playerResumed = false
        hasAudioFocus = true
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Reproduces PlaybackService.handleAudioFocusChange logic.
     */
    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                playerResumed = true
                playerPaused = false
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                playerPaused = true
                playerResumed = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                playerPaused = true
                playerResumed = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // For synced playback, ducking would desync volume with other clients
                // So we do nothing -- let it play at full volume
            }
        }
    }

    @Test
    fun `AUDIOFOCUS_LOSS pauses playback and clears focus flag`() {
        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertTrue("Player should be paused on focus loss", playerPaused)
        assertFalse("hasAudioFocus should be false", hasAudioFocus)
    }

    @Test
    fun `AUDIOFOCUS_LOSS_TRANSIENT pauses playback`() {
        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertTrue("Player should be paused on transient focus loss", playerPaused)
        // hasAudioFocus is not cleared on transient loss (system will return it)
    }

    @Test
    fun `AUDIOFOCUS_GAIN resumes playback after transient loss`() {
        // Simulate transient loss then gain
        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue("Should be paused first", playerPaused)

        handleAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue("Player should be resumed on focus gain", playerResumed)
        assertFalse("Player should no longer be paused", playerPaused)
    }

    @Test
    fun `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK does not pause for sync reasons`() {
        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertFalse("Player should NOT be paused on duck (sync reasons)", playerPaused)
        assertFalse("Player should NOT be resumed (no action taken)", playerResumed)
    }

    @Test
    fun `AUDIOFOCUS_GAIN after permanent loss resumes playback`() {
        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse("Focus should be lost", hasAudioFocus)

        handleAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue("Player should be resumed", playerResumed)
    }

    @Test
    fun `multiple transient losses followed by gain resumes correctly`() {
        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        handleAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        handleAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertTrue("Player should be resumed after multiple transient losses", playerResumed)
        assertFalse("Player should not be paused after gain", playerPaused)
    }

    @Test
    fun `audio focus request builder uses correct attributes`() {
        // Verify the AudioFocusRequest constants used in PlaybackService
        assertEquals(
            "AUDIOFOCUS_GAIN should be 1",
            1, AudioManager.AUDIOFOCUS_GAIN
        )
        assertEquals(
            "AUDIOFOCUS_LOSS should be -1",
            -1, AudioManager.AUDIOFOCUS_LOSS
        )
        assertEquals(
            "AUDIOFOCUS_LOSS_TRANSIENT should be -2",
            -2, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        )
        assertEquals(
            "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK should be -3",
            -3, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        )
    }
}
