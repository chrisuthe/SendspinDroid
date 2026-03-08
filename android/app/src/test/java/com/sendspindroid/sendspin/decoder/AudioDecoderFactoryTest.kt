package com.sendspindroid.sendspin.decoder

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AudioDecoderFactory.
 */
class AudioDecoderFactoryTest {

    @Test
    fun `unknown codec falls back to PcmDecoder`() {
        val decoder = AudioDecoderFactory.create("aac")
        assertTrue(
            "Unknown codec 'aac' should fall back to PcmDecoder, got ${decoder::class.simpleName}",
            decoder is PcmDecoder
        )
    }

    @Test
    fun `pcm codec returns PcmDecoder`() {
        val decoder = AudioDecoderFactory.create("pcm")
        assertTrue(
            "Codec 'pcm' should return PcmDecoder, got ${decoder::class.simpleName}",
            decoder is PcmDecoder
        )
    }

    @Test
    fun `unknown codec is case insensitive`() {
        val decoder = AudioDecoderFactory.create("AAC")
        assertTrue(
            "Unknown codec 'AAC' should fall back to PcmDecoder, got ${decoder::class.simpleName}",
            decoder is PcmDecoder
        )
    }

    @Test
    fun `empty codec falls back to PcmDecoder`() {
        val decoder = AudioDecoderFactory.create("")
        assertTrue(
            "Empty codec should fall back to PcmDecoder, got ${decoder::class.simpleName}",
            decoder is PcmDecoder
        )
    }
}
