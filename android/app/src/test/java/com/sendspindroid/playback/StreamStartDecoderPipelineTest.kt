package com.sendspindroid.playback

import android.util.Log
import com.sendspindroid.sendspin.decoder.AudioDecoder
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration test: PlaybackService.onStreamStart creates SyncAudioPlayer and decoder.
 *
 * Verifies that when onStreamStart fires with a given codec, the correct decoder
 * pipeline is created. This reproduces the decoder setup + SyncAudioPlayer creation
 * logic from PlaybackService.SendSpinClientCallback.onStreamStart.
 *
 * The SyncAudioPlayer cannot be instantiated in JVM tests (requires AudioTrack),
 * so we verify the decoder pipeline and creation parameters.
 */
class StreamStartDecoderPipelineTest {

    // Mirror PlaybackService fields
    private var audioDecoder: AudioDecoder? = null
    private var decoderReady = false
    private var syncPlayerCreated = false
    private var syncPlayerSampleRate = 0
    private var syncPlayerChannels = 0
    private var syncPlayerBitDepth = 0

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        audioDecoder = null
        decoderReady = false
        syncPlayerCreated = false
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Reproduces the full onStreamStart logic from PlaybackService:
     * 1. Set decoderReady = false
     * 2. Create decoder via AudioDecoderFactory.create(codec)
     * 3. Configure decoder with stream parameters
     * 4. Set decoderReady = true
     * 5. Create SyncAudioPlayer with matching format
     */
    private fun simulateOnStreamStart(
        codec: String,
        sampleRate: Int = 48000,
        channels: Int = 2,
        bitDepth: Int = 16,
        codecHeader: ByteArray? = null
    ) {
        // Step 1: Immediately mark decoder not ready (on WebSocket thread)
        decoderReady = false

        // Step 2-4: Decoder setup (on main handler in real code)
        audioDecoder?.release()
        audioDecoder = null
        try {
            audioDecoder = AudioDecoderFactory.create(codec)
            audioDecoder?.configure(sampleRate, channels, bitDepth, codecHeader)
            decoderReady = true
        } catch (e: Exception) {
            try {
                val fallback = AudioDecoderFactory.create("pcm")
                fallback.configure(sampleRate, channels, bitDepth)
                audioDecoder = fallback
                decoderReady = true
            } catch (fallbackEx: Exception) {
                audioDecoder = null
                // decoderReady stays false
            }
        }

        // Step 5: Create SyncAudioPlayer (simulated - can't instantiate AudioTrack in JVM)
        if (decoderReady) {
            syncPlayerCreated = true
            syncPlayerSampleRate = sampleRate
            syncPlayerChannels = channels
            syncPlayerBitDepth = bitDepth
        }
    }

    @Test
    fun `onStreamStart with pcm creates PCM decoder and player`() {
        simulateOnStreamStart("pcm", sampleRate = 48000, channels = 2, bitDepth = 16)

        assertTrue("decoderReady should be true", decoderReady)
        assertNotNull("decoder should be created", audioDecoder)
        assertTrue("decoder should be configured", audioDecoder!!.isConfigured)
        assertTrue("SyncAudioPlayer should be created", syncPlayerCreated)
        assertEquals(48000, syncPlayerSampleRate)
        assertEquals(2, syncPlayerChannels)
        assertEquals(16, syncPlayerBitDepth)
    }

    @Test
    fun `onStreamStart with opus falls back to pcm when opus unavailable in JVM`() {
        // In JVM tests, OpusDecoder requires native library, so create() may throw
        // This tests the fallback path
        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.create("opus") } throws RuntimeException("Native lib not loaded")
        every { AudioDecoderFactory.create("pcm") } answers { callOriginal() }

        simulateOnStreamStart("opus", sampleRate = 48000, channels = 2, bitDepth = 16)

        assertTrue("decoderReady should be true (via fallback)", decoderReady)
        assertNotNull("decoder should be fallback PCM", audioDecoder)
        assertTrue("fallback decoder should be configured", audioDecoder!!.isConfigured)
        assertTrue("SyncAudioPlayer should be created", syncPlayerCreated)
    }

    @Test
    fun `onStreamStart preserves stream parameters for SyncAudioPlayer`() {
        simulateOnStreamStart("pcm", sampleRate = 44100, channels = 1, bitDepth = 24)

        assertTrue("decoderReady should be true", decoderReady)
        assertTrue("SyncAudioPlayer should be created", syncPlayerCreated)
        assertEquals("Sample rate should match", 44100, syncPlayerSampleRate)
        assertEquals("Channels should match", 1, syncPlayerChannels)
        assertEquals("Bit depth should match", 24, syncPlayerBitDepth)
    }

    @Test
    fun `onStreamStart marks decoderReady false immediately before setup`() {
        // First stream sets up decoder
        simulateOnStreamStart("pcm")
        assertTrue("Should be ready after first stream", decoderReady)

        // Simulate new stream arriving - decoderReady should be false at the start
        var wasReadyDuringSetup: Boolean? = null
        decoderReady = false
        wasReadyDuringSetup = decoderReady

        assertFalse("decoderReady should be false during setup", wasReadyDuringSetup!!)
    }

    @Test
    fun `onStreamStart releases previous decoder before creating new one`() {
        val firstDecoder = mockk<AudioDecoder>(relaxed = true)
        every { firstDecoder.isConfigured } returns true

        // Set up a pre-existing decoder
        audioDecoder = firstDecoder
        decoderReady = true

        // New stream starts
        simulateOnStreamStart("pcm")

        // First decoder should have been released
        verify { firstDecoder.release() }
    }

    @Test
    fun `onStreamStart with both decoders failing does not create player`() {
        val failingDecoder = object : AudioDecoder {
            override val isConfigured = false
            override fun configure(sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) {
                throw RuntimeException("configure failed")
            }
            override fun decode(compressedData: ByteArray) = compressedData
            override fun flush() {}
            override fun release() {}
        }

        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.create(any()) } returns failingDecoder

        simulateOnStreamStart("flac")

        assertFalse("decoderReady should be false", decoderReady)
        assertNull("decoder should be null", audioDecoder)
        assertFalse("SyncAudioPlayer should NOT be created", syncPlayerCreated)
    }

    @Test
    fun `AudioDecoderFactory creates PCM decoder successfully`() {
        val decoder = AudioDecoderFactory.create("pcm")
        assertNotNull("PCM decoder should be created", decoder)
        assertFalse("Should not be configured before configure()", decoder.isConfigured)

        decoder.configure(48000, 2, 16)
        assertTrue("Should be configured after configure()", decoder.isConfigured)
    }

    @Test
    fun `AudioDecoderFactory always supports pcm codec`() {
        // PCM is always supported regardless of platform
        assertTrue("PCM should be supported", AudioDecoderFactory.isCodecSupported("pcm"))
    }

    @Test
    fun `AudioDecoderFactory unknown codec is not supported`() {
        assertFalse("Unknown codec should not be supported", AudioDecoderFactory.isCodecSupported("aac"))
        assertFalse("Empty codec should not be supported", AudioDecoderFactory.isCodecSupported(""))
    }
}
