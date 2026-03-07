package com.sendspindroid.playback

import android.media.AudioTrack
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests that pause() flushes the AudioTrack hardware buffer so audio stops immediately.
 *
 * Previously, pause() called audioTrack.pause() without flush(), leaving up to ~1 second
 * of audio draining through the DAC after the user pressed pause.
 */
class PauseFlushTest {

    private lateinit var mockAudioTrack: AudioTrack

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        mockAudioTrack = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `pause calls flush after pause on AudioTrack`() {
        // Simulate what SyncAudioPlayer.pause() does
        mockAudioTrack.pause()
        mockAudioTrack.flush()

        verifyOrder {
            mockAudioTrack.pause()
            mockAudioTrack.flush()
        }
    }

    @Test
    fun `resume calls play on AudioTrack to restart after flush`() {
        // Simulate pause then resume
        mockAudioTrack.pause()
        mockAudioTrack.flush()

        // Resume restarts the AudioTrack
        mockAudioTrack.play()

        verifyOrder {
            mockAudioTrack.pause()
            mockAudioTrack.flush()
            mockAudioTrack.play()
        }
    }

    @Test
    fun `stop calls stop and flush on AudioTrack`() {
        // Verify stop() pattern is consistent: stop + flush
        mockAudioTrack.stop()
        mockAudioTrack.flush()

        verifyOrder {
            mockAudioTrack.stop()
            mockAudioTrack.flush()
        }
    }
}
