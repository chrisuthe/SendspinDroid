package com.sendspindroid.playback

import android.util.Log
import com.sendspindroid.sendspin.decoder.AudioDecoder
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for the decoder setup logic extracted from PlaybackService.onStreamStart.
 *
 * Verifies:
 * - H-10: decoderReady is NOT set to true when both primary and fallback decoders fail
 * - H-10: decoderReady IS set to true only when a decoder is successfully configured
 * - H-10: Fallback PCM decoder is explicitly configured (not left unconfigured)
 */
class DecoderSetupTest {

    // These mirror the PlaybackService fields relevant to the decoder setup logic
    private var audioDecoder: AudioDecoder? = null
    private var decoderReady = false

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
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Reproduces the fixed decoder setup logic from PlaybackService.onStreamStart.
     * This is the exact pattern after the H-10 fix.
     */
    private fun setupDecoder(
        codec: String,
        sampleRate: Int = 48000,
        channels: Int = 2,
        bitDepth: Int = 16,
        codecHeader: ByteArray? = null
    ) {
        decoderReady = false
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
    }

    // =========================================================================
    // H-10: PCM codec (always works)
    // =========================================================================

    @Test
    fun setupDecoder_pcm_decoderReadyAndConfigured() {
        setupDecoder("pcm")

        assertTrue("decoderReady should be true for PCM", decoderReady)
        assertNotNull("audioDecoder should not be null", audioDecoder)
        assertTrue("audioDecoder should be configured", audioDecoder!!.isConfigured)
    }

    // =========================================================================
    // H-10: Primary decoder configure() throws -> fallback configured
    // =========================================================================

    @Test
    fun setupDecoder_primaryThrows_fallbackConfigured() {
        // Mock the factory to return a decoder that throws on configure
        val failingDecoder = object : AudioDecoder {
            override val isConfigured = false
            override fun configure(sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) {
                throw RuntimeException("MediaCodec init failed")
            }
            override fun decode(compressedData: ByteArray) = compressedData
            override fun flush() {}
            override fun release() {}
        }

        mockkObject(AudioDecoderFactory)
        // First call (for "flac") returns the failing decoder
        // Second call (for "pcm") returns a real PCM decoder
        every { AudioDecoderFactory.create("flac") } returns failingDecoder
        every { AudioDecoderFactory.create("pcm") } answers { callOriginal() }

        setupDecoder("flac")

        assertTrue("decoderReady should be true (fallback succeeded)", decoderReady)
        assertNotNull("audioDecoder should be the fallback", audioDecoder)
        assertTrue("fallback decoder should be configured", audioDecoder!!.isConfigured)
    }

    // =========================================================================
    // H-10: Both primary and fallback fail -> decoderReady stays false
    // =========================================================================

    @Test
    fun setupDecoder_bothFail_decoderReadyFalse() {
        val failingDecoder = object : AudioDecoder {
            override val isConfigured = false
            override fun configure(sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) {
                throw RuntimeException("MediaCodec init failed")
            }
            override fun decode(compressedData: ByteArray) = compressedData
            override fun flush() {}
            override fun release() {}
        }

        mockkObject(AudioDecoderFactory)
        // Both primary and fallback return failing decoders
        every { AudioDecoderFactory.create(any()) } returns failingDecoder

        setupDecoder("flac")

        assertFalse("decoderReady must be false when both decoders fail", decoderReady)
        assertNull("audioDecoder must be null when both decoders fail", audioDecoder)
    }

    // =========================================================================
    // H-10: Verify the OLD (buggy) behavior would have been wrong
    // =========================================================================

    @Test
    fun oldBehavior_wouldSetDecoderReadyTrueEvenOnTotalFailure() {
        // This test documents the bug that was fixed.
        // The old code unconditionally set decoderReady = true after the try/catch.
        // With the fix, this scenario correctly leaves decoderReady = false.

        val failingDecoder = object : AudioDecoder {
            override val isConfigured = false
            override fun configure(sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) {
                throw RuntimeException("failure")
            }
            override fun decode(compressedData: ByteArray) = compressedData
            override fun flush() {}
            override fun release() {}
        }

        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.create(any()) } returns failingDecoder

        setupDecoder("opus")

        // With the fix: decoderReady is false and audioDecoder is null
        // The old buggy code would have: decoderReady = true, audioDecoder = failingDecoder
        // which means onAudioChunk would pass raw compressed data as PCM (loud noise)
        assertFalse("decoderReady must NOT be true when all decoders fail", decoderReady)
        assertNull("audioDecoder must be null, not a broken decoder", audioDecoder)
    }

    // =========================================================================
    // H-10: Fallback PCM decoder IS configured (not just created)
    // =========================================================================

    @Test
    fun setupDecoder_fallback_isExplicitlyConfigured() {
        // The old code created a PcmDecoder fallback but never called configure() on it.
        // While PcmDecoder.decode() is a pass-through regardless, isConfigured should be true.
        val failingDecoder = object : AudioDecoder {
            override val isConfigured = false
            override fun configure(sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) {
                throw RuntimeException("primary failure")
            }
            override fun decode(compressedData: ByteArray) = compressedData
            override fun flush() {}
            override fun release() {}
        }

        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.create("opus") } returns failingDecoder
        every { AudioDecoderFactory.create("pcm") } answers { callOriginal() }

        setupDecoder("opus")

        assertTrue("Fallback decoder should be explicitly configured", audioDecoder!!.isConfigured)
    }
}
