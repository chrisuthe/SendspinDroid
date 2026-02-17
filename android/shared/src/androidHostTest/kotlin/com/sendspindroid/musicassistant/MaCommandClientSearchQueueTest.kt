package com.sendspindroid.musicassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for MaCommandClient search results and queue state parsing.
 */
class MaCommandClientSearchQueueTest {

    private lateinit var client: MaCommandClient

    @Before
    fun setUp() {
        client = MaCommandClient(TestMaSettingsProvider())
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)
    }

    // ========================================================================
    // Search Results
    // ========================================================================

    @Test
    fun `parseSearchResults with all types`() {
        val json = parseJson("""
        {
            "result": {
                "tracks": [
                    {"item_id": "t1", "name": "Track 1"}
                ],
                "albums": [
                    {"item_id": "a1", "name": "Album 1"}
                ],
                "artists": [
                    {"item_id": "art1", "name": "Artist 1"}
                ],
                "playlists": [
                    {"item_id": "pl1", "name": "Playlist 1"}
                ],
                "radios": [
                    {"item_id": "r1", "name": "Radio 1"}
                ],
                "podcasts": [
                    {"item_id": "pod1", "name": "Podcast 1"}
                ]
            }
        }
        """)

        val results = client.parseSearchResults(json)
        assertEquals(1, results.tracks.size)
        assertEquals(1, results.albums.size)
        assertEquals(1, results.artists.size)
        assertEquals(1, results.playlists.size)
        assertEquals(1, results.radios.size)
        assertEquals(1, results.podcasts.size)

        assertEquals("t1", results.tracks[0].itemId)
        assertEquals("a1", results.albums[0].id)
        assertEquals("art1", results.artists[0].id)
    }

    @Test
    fun `parseSearchResults with empty result`() {
        val json = parseJson("""{ "result": {} }""")

        val results = client.parseSearchResults(json)
        assertTrue(results.tracks.isEmpty())
        assertTrue(results.albums.isEmpty())
        assertTrue(results.artists.isEmpty())
        assertTrue(results.playlists.isEmpty())
        assertTrue(results.radios.isEmpty())
        assertTrue(results.podcasts.isEmpty())
    }

    @Test
    fun `parseSearchResults with missing result`() {
        val json = parseJson("""{ "message_id": "1" }""")

        val results = client.parseSearchResults(json)
        assertTrue(results.tracks.isEmpty())
        assertTrue(results.albums.isEmpty())
    }

    @Test
    fun `parseSearchResults partial types`() {
        val json = parseJson("""
        {
            "result": {
                "tracks": [
                    {"item_id": "t1", "name": "Only Track"}
                ]
            }
        }
        """)

        val results = client.parseSearchResults(json)
        assertEquals(1, results.tracks.size)
        assertTrue(results.albums.isEmpty())
        assertTrue(results.artists.isEmpty())
    }

    // ========================================================================
    // Queue State
    // ========================================================================

    @Test
    fun `parseQueueState standard queue`() {
        val queueJson = parseJson("""
        {
            "result": {
                "shuffle_enabled": true,
                "repeat_mode": "repeat_all",
                "current_index": 1,
                "current_item": "qi_2"
            }
        }
        """)

        val itemsJson = parseJson("""
        {
            "result": [
                {
                    "queue_item_id": "qi_1",
                    "name": "First Track",
                    "artist": "Artist A",
                    "duration": 200,
                    "uri": "library://track/1"
                },
                {
                    "queue_item_id": "qi_2",
                    "name": "Second Track",
                    "artist": "Artist B",
                    "duration": 300,
                    "uri": "library://track/2"
                },
                {
                    "queue_item_id": "qi_3",
                    "name": "Third Track",
                    "duration": 250
                }
            ]
        }
        """)

        val state = client.parseQueueState(queueJson, itemsJson)
        assertTrue(state.shuffleEnabled)
        assertEquals("all", state.repeatMode)
        assertEquals(1, state.currentIndex)
        assertEquals(3, state.items.size)

        assertEquals("qi_1", state.items[0].queueItemId)
        assertEquals("First Track", state.items[0].name)
        assertEquals("Artist A", state.items[0].artist)
        assertEquals(200L, state.items[0].duration)
        assertFalse(state.items[0].isCurrentItem)

        assertEquals("qi_2", state.items[1].queueItemId)
        assertTrue(state.items[1].isCurrentItem)  // matches both index and current_item
    }

    @Test
    fun `parseQueueState repeat modes`() {
        val items = parseJson("""{ "result": [] }""")

        // repeat_one
        val q1 = parseJson("""{ "result": { "repeat_mode": "repeat_one" } }""")
        assertEquals("one", client.parseQueueState(q1, items).repeatMode)

        // repeat_all
        val q2 = parseJson("""{ "result": { "repeat_mode": "repeat_all" } }""")
        assertEquals("all", client.parseQueueState(q2, items).repeatMode)

        // off
        val q3 = parseJson("""{ "result": { "repeat_mode": "off" } }""")
        assertEquals("off", client.parseQueueState(q3, items).repeatMode)

        // missing
        val q4 = parseJson("""{ "result": {} }""")
        assertEquals("off", client.parseQueueState(q4, items).repeatMode)
    }

    @Test
    fun `parseQueueState with media_item nested data`() {
        val queueJson = parseJson("""
        {
            "result": {
                "current_index": 0
            }
        }
        """)

        val itemsJson = parseJson("""
        {
            "result": [
                {
                    "queue_item_id": "qi_1",
                    "media_item": {
                        "name": "Nested Name",
                        "artist": "Nested Artist",
                        "uri": "library://track/nested"
                    }
                }
            ]
        }
        """)

        val state = client.parseQueueState(queueJson, itemsJson)
        assertEquals(1, state.items.size)
        assertEquals("Nested Name", state.items[0].name)
        assertEquals("Nested Artist", state.items[0].artist)
    }

    @Test
    fun `parseQueueState item id fallbacks`() {
        val queueJson = parseJson("""{ "result": {} }""")

        // Falls back to item_id
        val itemsJson1 = parseJson("""
        {
            "result": [
                {"item_id": "fallback_id", "name": "Test"}
            ]
        }
        """)
        assertEquals("fallback_id", client.parseQueueState(queueJson, itemsJson1).items[0].queueItemId)

        // Falls back to id
        val itemsJson2 = parseJson("""
        {
            "result": [
                {"id": "bare_id", "name": "Test"}
            ]
        }
        """)
        assertEquals("bare_id", client.parseQueueState(queueJson, itemsJson2).items[0].queueItemId)
    }

    @Test
    fun `parseQueueState skips items with no id`() {
        val queueJson = parseJson("""{ "result": {} }""")
        val itemsJson = parseJson("""
        {
            "result": [
                {"name": "No ID item"},
                {"queue_item_id": "valid", "name": "Has ID"}
            ]
        }
        """)

        val state = client.parseQueueState(queueJson, itemsJson)
        assertEquals(1, state.items.size)
        assertEquals("valid", state.items[0].queueItemId)
    }

    @Test
    fun `parseQueueState empty queue`() {
        val queueJson = parseJson("""{ "result": {} }""")
        val itemsJson = parseJson("""{ "result": [] }""")

        val state = client.parseQueueState(queueJson, itemsJson)
        assertTrue(state.items.isEmpty())
        assertFalse(state.shuffleEnabled)
        assertEquals("off", state.repeatMode)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun parseJson(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject
}
