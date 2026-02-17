package com.sendspindroid.musicassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for MaCommandClient image URL extraction.
 */
class MaCommandClientImageTest {

    private lateinit var client: MaCommandClient

    @Before
    fun setUp() {
        client = MaCommandClient(TestMaSettingsProvider())
    }

    // ========================================================================
    // extractImageUri — Direct URL
    // ========================================================================

    @Test
    fun `extractImageUri direct http URL in local mode`() {
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "image": "http://example.com/cover.jpg"
        }
        """)

        assertEquals("http://example.com/cover.jpg", client.extractImageUri(json))
    }

    @Test
    fun `extractImageUri direct https URL in local mode`() {
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "image": "https://cdn.example.com/art.png"
        }
        """)

        assertEquals("https://cdn.example.com/art.png", client.extractImageUri(json))
    }

    // ========================================================================
    // extractImageUri — Remote mode proxying
    // ========================================================================

    @Test
    fun `extractImageUri rewrites imageproxy URL in remote mode`() {
        client.setTransport(null, "webrtc://ma-api", true)

        val json = parseJson("""
        {
            "name": "Track",
            "image": "http://192.168.1.100:8095/imageproxy?provider=plex&path=/library/art"
        }
        """)

        val result = client.extractImageUri(json)
        assertTrue(result.startsWith("ma-proxy://"))
        assertTrue(result.contains("/imageproxy"))
    }

    @Test
    fun `extractImageUri non-imageproxy URL preserved in remote mode`() {
        client.setTransport(null, "webrtc://ma-api", true)

        val json = parseJson("""
        {
            "name": "Track",
            "image": "https://cdn.spotify.com/cover.jpg"
        }
        """)

        // External CDN URLs should be preserved as-is even in remote mode
        assertEquals("https://cdn.spotify.com/cover.jpg", client.extractImageUri(json))
    }

    // ========================================================================
    // extractImageUri — Image as JsonObject (provider/path)
    // ========================================================================

    @Test
    fun `extractImageUri image object with provider and http path`() {
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "image": {
                "path": "https://i.scdn.co/image/abc123",
                "provider": "spotify"
            }
        }
        """)

        val result = client.extractImageUri(json)
        assertTrue(result.startsWith("http://192.168.1.100:8095/imageproxy"))
        assertTrue(result.contains("provider=spotify"))
        assertTrue(result.contains("size=300"))
        assertTrue(result.contains("fmt=jpeg"))
    }

    @Test
    fun `extractImageUri image object with provider and local path`() {
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "image": {
                "path": "/music/cover.jpg",
                "provider": "filesystem"
            }
        }
        """)

        val result = client.extractImageUri(json)
        assertTrue(result.contains("/imageproxy"))
        assertTrue(result.contains("provider=filesystem"))
    }

    @Test
    fun `extractImageUri image object with empty path returns empty`() {
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "image": {
                "path": "",
                "provider": "spotify"
            }
        }
        """)

        assertEquals("", client.extractImageUri(json))
    }

    // ========================================================================
    // extractImageUri — Metadata fallback
    // ========================================================================

    @Test
    fun `extractImageUri falls back to metadata images array`() {
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "metadata": {
                "images": [
                    {
                        "path": "https://image.server.com/art.jpg",
                        "provider": "url"
                    }
                ]
            }
        }
        """)

        val result = client.extractImageUri(json)
        assertTrue(result.isNotEmpty())
        assertTrue(result.contains("/imageproxy"))
    }

    // ========================================================================
    // extractImageFromMetadata — Image type preference (M-03)
    // ========================================================================

    @Test
    fun `extractImageUri metadata prefers thumb over other types`() {
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "metadata": {
                "images": [
                    {"path": "https://img.server.com/landscape.jpg", "provider": "url", "type": "landscape"},
                    {"path": "https://img.server.com/thumb.jpg", "provider": "url", "type": "thumb"},
                    {"path": "https://img.server.com/banner.jpg", "provider": "url", "type": "banner"}
                ]
            }
        }
        """)

        val result = client.extractImageUri(json)
        assertTrue("Expected thumb image URL", result.contains("thumb.jpg"))
    }

    @Test
    fun `extractImageUri metadata prefers cover over non-preferred types`() {
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "metadata": {
                "images": [
                    {"path": "https://img.server.com/landscape.jpg", "provider": "url", "type": "landscape"},
                    {"path": "https://img.server.com/cover.jpg", "provider": "url", "type": "cover"},
                    {"path": "https://img.server.com/banner.jpg", "provider": "url", "type": "banner"}
                ]
            }
        }
        """)

        val result = client.extractImageUri(json)
        assertTrue("Expected cover image URL", result.contains("cover.jpg"))
    }

    @Test
    fun `extractImageUri metadata prefers front over non-preferred types`() {
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "metadata": {
                "images": [
                    {"path": "https://img.server.com/banner.jpg", "provider": "url", "type": "banner"},
                    {"path": "https://img.server.com/front.jpg", "provider": "url", "type": "front"}
                ]
            }
        }
        """)

        val result = client.extractImageUri(json)
        assertTrue("Expected front image URL", result.contains("front.jpg"))
    }

    @Test
    fun `extractImageUri metadata thumb beats cover and front`() {
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "metadata": {
                "images": [
                    {"path": "https://img.server.com/cover.jpg", "provider": "url", "type": "cover"},
                    {"path": "https://img.server.com/front.jpg", "provider": "url", "type": "front"},
                    {"path": "https://img.server.com/thumb.jpg", "provider": "url", "type": "thumb"}
                ]
            }
        }
        """)

        val result = client.extractImageUri(json)
        assertTrue("Expected thumb image URL", result.contains("thumb.jpg"))
    }

    @Test
    fun `extractImageUri metadata falls back to first valid image when no preferred type`() {
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "metadata": {
                "images": [
                    {"path": "https://img.server.com/first.jpg", "provider": "url", "type": "landscape"},
                    {"path": "https://img.server.com/second.jpg", "provider": "url", "type": "banner"}
                ]
            }
        }
        """)

        val result = client.extractImageUri(json)
        // Should pick the first image with a valid path, not blindly the last one
        assertTrue("Expected first image URL", result.contains("first.jpg"))
    }

    @Test
    fun `extractImageUri metadata skips images with empty path`() {
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "metadata": {
                "images": [
                    {"path": "", "provider": "url", "type": "thumb"},
                    {"path": "https://img.server.com/valid.jpg", "provider": "url", "type": "banner"}
                ]
            }
        }
        """)

        val result = client.extractImageUri(json)
        assertTrue("Expected valid image URL", result.contains("valid.jpg"))
    }

    // ========================================================================
    // extractImageUri — Album image fallback
    // ========================================================================

    @Test
    fun `extractImageUri falls back to album image`() {
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "album": {
                "name": "Album Name",
                "image": "http://example.com/album-cover.jpg"
            }
        }
        """)

        assertEquals("http://example.com/album-cover.jpg", client.extractImageUri(json))
    }

    @Test
    fun `extractImageUri falls back to album metadata images`() {
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "album": {
                "name": "Album",
                "metadata": {
                    "images": [
                        {
                            "path": "https://server.com/album-art.jpg",
                            "provider": "url"
                        }
                    ]
                }
            }
        }
        """)

        val result = client.extractImageUri(json)
        assertTrue(result.isNotEmpty())
    }

    // ========================================================================
    // extractImageUri — No transport context
    // ========================================================================

    @Test
    fun `extractImageUri with no apiUrl returns empty for non-http images`() {
        // Don't set transport — apiUrl will be null
        val json = parseJson("""
        {
            "name": "Track",
            "image": {
                "path": "/local/cover.jpg",
                "provider": "filesystem"
            }
        }
        """)

        assertEquals("", client.extractImageUri(json))
    }

    @Test
    fun `extractImageUri with no apiUrl still returns http URLs directly`() {
        // No transport set
        val json = parseJson("""
        {
            "name": "Track",
            "image": "http://cdn.example.com/cover.jpg"
        }
        """)

        assertEquals("http://cdn.example.com/cover.jpg", client.extractImageUri(json))
    }

    // ========================================================================
    // extractImageUri — WSS URL conversion
    // ========================================================================

    @Test
    fun `extractImageUri converts wss to https for base URL`() {
        client.setTransport(null, "wss://music.example.com/ws", false)

        val json = parseJson("""
        {
            "name": "Track",
            "image": {
                "path": "https://i.scdn.co/image/123",
                "provider": "spotify"
            }
        }
        """)

        val result = client.extractImageUri(json)
        assertTrue(result.startsWith("https://music.example.com/imageproxy"))
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun parseJson(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject
}
