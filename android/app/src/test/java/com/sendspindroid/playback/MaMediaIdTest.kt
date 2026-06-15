package com.sendspindroid.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [MaMediaId] provider-qualified media id encoding/decoding,
 * in particular that decoding splits from the right so item ids containing
 * '~' are preserved.
 */
class MaMediaIdTest {

    @Test
    fun `decode splits id and provider`() {
        assertEquals("a1" to "spotify", MaMediaId.decode("a1~spotify"))
    }

    @Test
    fun `decode falls back to library when no provider suffix`() {
        assertEquals("a1" to "library", MaMediaId.decode("a1"))
    }

    @Test
    fun `decode preserves an id that itself contains a tilde`() {
        // Splitting from the left would corrupt this id to "spotify" and drop
        // the real provider; splitting from the right keeps the id intact.
        assertEquals("spotify://album/x~y" to "spotify", MaMediaId.decode("spotify://album/x~y~spotify"))
    }

    @Test
    fun `decode falls back to library when provider suffix is empty`() {
        assertEquals("a1" to "library", MaMediaId.decode("a1~"))
    }

    @Test
    fun `encode appends provider`() {
        assertEquals("a1~spotify", MaMediaId.encode("a1", "spotify"))
    }

    @Test
    fun `encode then decode round-trips ids containing a tilde`() {
        val id = "ytmusic://track/a~b~c"
        val provider = "ytmusic"
        val (decodedId, decodedProvider) = MaMediaId.decode(MaMediaId.encode(id, provider))
        assertEquals(id, decodedId)
        assertEquals(provider, decodedProvider)
    }
}
