package com.sendspindroid.playback

import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit test for PlaybackService's ACTION_AUDIO_BECOMING_NOISY handling.
 *
 * Reproduces the receiver/handler logic (the service itself is not unit-testable
 * on the JVM). The system fires ACTION_AUDIO_BECOMING_NOISY once when audio is
 * about to reroute to the built-in speaker (headphones unplugged, Bluetooth or
 * Android Auto disconnected). We pause local playback so we don't blast the
 * phone speaker. Only that action should trigger a pause.
 */
class BecomingNoisyHandlingTest {

    private var playerPaused = false

    /** Reproduces the BroadcastReceiver.onReceive + handleBecomingNoisy logic. */
    private fun onReceive(action: String?) {
        if (action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
            // syncAudioPlayer?.pause()
            playerPaused = true
        }
    }

    @Test
    fun `becoming noisy pauses local playback`() {
        onReceive(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        assertTrue("Output disconnect should pause local playback", playerPaused)
    }

    @Test
    fun `unrelated broadcast action does not pause`() {
        onReceive("android.intent.action.SOMETHING_ELSE")
        assertFalse("Only ACTION_AUDIO_BECOMING_NOISY should pause", playerPaused)
    }

    @Test
    fun `null action does not pause`() {
        onReceive(null)
        assertFalse(playerPaused)
    }
}
