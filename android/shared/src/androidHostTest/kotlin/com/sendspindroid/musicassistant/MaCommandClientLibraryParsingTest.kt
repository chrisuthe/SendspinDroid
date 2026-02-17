package com.sendspindroid.musicassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for MaCommandClient library item parsing (playlists, radios, podcasts, browse).
 */
class MaCommandClientLibraryParsingTest {

    private lateinit var client: MaCommandClient

    @Before
    fun setUp() {
        client = MaCommandClient(TestMaSettingsProvider())
        client.setTransport(null, "ws://192.168.1.100:8095/ws", false)
    }

    // ========================================================================
    // Playlists
    // ========================================================================

    @Test
    fun `parsePlaylists extracts from result array`() {
        val json = parseJson("""
        {
            "result": [
                {
                    "item_id": "pl1",
                    "name": "My Favorites",
                    "owner": "chris",
                    "is_editable": true,
                    "uri": "library://music/playlist/pl1"
                }
            ]
        }
        """)

        val playlists = client.parsePlaylists(json)
        assertEquals(1, playlists.size)
        assertEquals("pl1", playlists[0].id)
        assertEquals("My Favorites", playlists[0].name)
        assertEquals("chris", playlists[0].owner)
    }

    @Test
    fun `parsePlaylistsArray filters invalid entries`() {
        val array = Json.parseToJsonElement("""
        [
            {"item_id": "pl1", "name": "Good Playlist"},
            {"item_id": "pl2"},
            {"name": "No ID"},
            {}
        ]
        """).jsonArray

        val playlists = client.parsePlaylistsArray(array)
        assertEquals(1, playlists.size)
        assertEquals("pl1", playlists[0].id)
    }

    @Test
    fun `parsePlaylistsArray returns empty for null`() {
        assertTrue(client.parsePlaylistsArray(null).isEmpty())
    }

    // ========================================================================
    // Radio Stations
    // ========================================================================

    @Test
    fun `parseRadioStations extracts from result array`() {
        val json = parseJson("""
        {
            "result": [
                {
                    "item_id": "radio1",
                    "name": "BBC Radio 1",
                    "uri": "tunein://station/s1234"
                }
            ]
        }
        """)

        val radios = client.parseRadioStations(json)
        assertEquals(1, radios.size)
        assertEquals("radio1", radios[0].id)
        assertEquals("BBC Radio 1", radios[0].name)
    }

    @Test
    fun `parseRadiosArray filters invalid entries`() {
        val array = Json.parseToJsonElement("""
        [
            {"item_id": "r1", "name": "Valid Radio"},
            {"item_id": "r2"},
            {}
        ]
        """).jsonArray

        val radios = client.parseRadiosArray(array)
        assertEquals(1, radios.size)
    }

    // ========================================================================
    // Podcasts
    // ========================================================================

    @Test
    fun `parsePodcasts extracts from result array`() {
        val json = parseJson("""
        {
            "result": [
                {
                    "item_id": "pod1",
                    "name": "Tech Podcast",
                    "publisher": "Tech Corp",
                    "uri": "library://podcast/pod1"
                }
            ]
        }
        """)

        val podcasts = client.parsePodcasts(json)
        assertEquals(1, podcasts.size)
        assertEquals("pod1", podcasts[0].id)
        assertEquals("Tech Podcast", podcasts[0].name)
        assertEquals("Tech Corp", podcasts[0].publisher)
    }

    @Test
    fun `parsePodcastsArray filters invalid entries`() {
        val array = Json.parseToJsonElement("""
        [
            {"item_id": "p1", "name": "Valid"},
            {"name": "No ID"}
        ]
        """).jsonArray

        val podcasts = client.parsePodcastsArray(array)
        assertEquals(1, podcasts.size)
    }

    // ========================================================================
    // Podcast Episodes
    // ========================================================================

    @Test
    fun `parsePodcastEpisodes extracts episodes`() {
        val json = parseJson("""
        {
            "result": [
                {
                    "item_id": "ep1",
                    "name": "Episode 1: Introduction",
                    "duration": 3600,
                    "uri": "library://podcast/pod1/ep1"
                }
            ]
        }
        """)

        val episodes = client.parsePodcastEpisodes(json)
        assertEquals(1, episodes.size)
        assertEquals("ep1", episodes[0].id)
        assertEquals("Episode 1: Introduction", episodes[0].name)
        assertEquals(3600L, episodes[0].duration)
    }

    // ========================================================================
    // Browse Items
    // ========================================================================

    @Test
    fun `parseBrowseItems handles folders`() {
        val json = parseJson("""
        {
            "result": [
                {
                    "media_type": "folder",
                    "item_id": "f1",
                    "name": "Music Library",
                    "path": "/music/library",
                    "uri": "library://browse/music",
                    "is_playable": false
                }
            ]
        }
        """)

        val items = client.parseBrowseItems(json)
        assertEquals(1, items.size)
        assertTrue(items[0] is MaBrowseFolder)
        val folder = items[0] as MaBrowseFolder
        assertEquals("f1", folder.folderId)
        assertEquals("Music Library", folder.name)
        assertEquals("/music/library", folder.path)
        assertFalse(folder.isPlayable)
    }

    @Test
    fun `parseBrowseItems handles tracks`() {
        val json = parseJson("""
        {
            "result": [
                {
                    "media_type": "track",
                    "item_id": "t1",
                    "name": "Song Title",
                    "artist": "Some Artist",
                    "duration": 240
                }
            ]
        }
        """)

        val items = client.parseBrowseItems(json)
        assertEquals(1, items.size)
        assertTrue(items[0] is MaTrack)
        val track = items[0] as MaTrack
        assertEquals("t1", track.itemId)
        assertEquals("Song Title", track.name)
        assertEquals("Some Artist", track.artist)
    }

    @Test
    fun `parseBrowseItems skips back navigation entries`() {
        val json = parseJson("""
        {
            "result": [
                {
                    "media_type": "folder",
                    "item_id": "parent",
                    "name": "..",
                    "path": "/music"
                },
                {
                    "media_type": "folder",
                    "item_id": "f1",
                    "name": "Library",
                    "path": "/music/library"
                }
            ]
        }
        """)

        val items = client.parseBrowseItems(json)
        assertEquals(1, items.size)
        assertEquals("Library", items[0].name)
    }

    @Test
    fun `parseBrowseItems handles mixed types`() {
        val json = parseJson("""
        {
            "result": [
                {"media_type": "folder", "item_id": "f1", "name": "Folder", "path": "/a"},
                {"media_type": "track", "item_id": "t1", "name": "Track"},
                {"media_type": "radio", "item_id": "r1", "name": "Radio"},
                {"media_type": "playlist", "item_id": "pl1", "name": "Playlist"}
            ]
        }
        """)

        val items = client.parseBrowseItems(json)
        assertTrue(items.size >= 2) // folder and track at minimum
    }

    @Test
    fun `parseBrowseItems returns empty for no result`() {
        val json = parseJson("""{ "message_id": "1" }""")
        assertTrue(client.parseBrowseItems(json).isEmpty())
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun parseJson(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject
}
