package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaPlaybackState
import com.sendspindroid.musicassistant.model.MaPlayerFeature
import com.sendspindroid.musicassistant.model.MaPlayerType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for MaCommandClient player parsing methods.
 */
class MaCommandClientPlayerParsingTest {

    private lateinit var client: MaCommandClient

    @Before
    fun setUp() {
        client = MaCommandClient(TestMaSettingsProvider())
    }

    // ========================================================================
    // parsePlayers
    // ========================================================================

    @Test
    fun `parsePlayers extracts from result array`() {
        val json = parseJson("""
        {
            "result": [
                {
                    "player_id": "player1",
                    "display_name": "Living Room",
                    "type": "player",
                    "provider": "sonos",
                    "available": true,
                    "volume_level": 50
                },
                {
                    "player_id": "player2",
                    "display_name": "Kitchen",
                    "type": "player",
                    "provider": "sonos",
                    "available": false
                }
            ]
        }
        """)

        val players = client.parsePlayers(json)
        assertEquals(2, players.size)
        assertEquals("player1", players[0].playerId)
        assertEquals("Living Room", players[0].name)
        assertTrue(players[0].available)
        assertEquals(50, players[0].volumeLevel)
        assertEquals("player2", players[1].playerId)
        assertFalse(players[1].available)
    }

    @Test
    fun `parsePlayers handles nested players object`() {
        val json = parseJson("""
        {
            "result": {
                "players": [
                    {
                        "player_id": "p1",
                        "display_name": "Speaker",
                        "type": "player",
                        "available": true
                    }
                ]
            }
        }
        """)

        val players = client.parsePlayers(json)
        assertEquals(1, players.size)
        assertEquals("p1", players[0].playerId)
    }

    @Test
    fun `parsePlayers returns empty for missing result`() {
        val json = parseJson("""{ "message_id": "1" }""")
        assertTrue(client.parsePlayers(json).isEmpty())
    }

    // ========================================================================
    // parsePlayer
    // ========================================================================

    @Test
    fun `parsePlayer standard player with all fields`() {
        val json = parseJson("""
        {
            "player_id": "sonos_livingroom",
            "display_name": "Living Room Sonos",
            "type": "player",
            "provider": "sonos",
            "available": true,
            "powered": true,
            "state": "playing",
            "volume_level": 65,
            "volume_muted": false,
            "group_members": ["sonos_livingroom", "sonos_kitchen"],
            "can_group_with": ["sonos_bedroom"],
            "synced_to": "sonos_kitchen",
            "supported_features": ["volume_set", "pause", "seek"],
            "icon": "mdi-speaker-wireless",
            "enabled": true,
            "hide_in_ui": false
        }
        """)

        val player = client.parsePlayer(json)
        assertEquals("sonos_livingroom", player.playerId)
        assertEquals("Living Room Sonos", player.name)
        assertEquals(MaPlayerType.PLAYER, player.type)
        assertEquals("sonos", player.provider)
        assertTrue(player.available)
        assertEquals(true, player.powered)
        assertEquals(MaPlaybackState.PLAYING, player.playbackState)
        assertEquals(65, player.volumeLevel)
        assertEquals(false, player.volumeMuted)
        assertEquals(2, player.groupMembers.size)
        assertEquals(1, player.canGroupWith.size)
        assertEquals("sonos_kitchen", player.syncedTo)
        assertTrue(player.supportedFeatures.contains(MaPlayerFeature.VOLUME_SET))
        assertTrue(player.supportedFeatures.contains(MaPlayerFeature.PAUSE))
        assertTrue(player.supportedFeatures.contains(MaPlayerFeature.SEEK))
        assertEquals("mdi-speaker-wireless", player.icon)
        assertTrue(player.enabled)
        assertFalse(player.hideInUi)
    }

    @Test
    fun `parsePlayer falls back to id and name fields`() {
        val json = parseJson("""
        {
            "id": "fallback_id",
            "name": "Fallback Player",
            "type": "group",
            "available": true
        }
        """)

        val player = client.parsePlayer(json)
        assertEquals("fallback_id", player.playerId)
        assertEquals("Fallback Player", player.name)
        assertEquals(MaPlayerType.GROUP, player.type)
    }

    @Test
    fun `parsePlayer handles missing optional fields`() {
        val json = parseJson("""
        {
            "player_id": "minimal",
            "display_name": "Minimal Player",
            "type": "player",
            "available": false
        }
        """)

        val player = client.parsePlayer(json)
        assertEquals("minimal", player.playerId)
        assertNull(player.powered)
        assertNull(player.volumeLevel)
        assertNull(player.volumeMuted)
        assertTrue(player.groupMembers.isEmpty())
        assertTrue(player.canGroupWith.isEmpty())
        assertNull(player.syncedTo)
        assertNull(player.activeGroup)
        assertTrue(player.supportedFeatures.isEmpty())
    }

    @Test
    fun `parsePlayer synced_to null string is treated as null`() {
        val json = parseJson("""
        {
            "player_id": "p1",
            "display_name": "Test",
            "type": "player",
            "synced_to": "null",
            "active_group": "null"
        }
        """)

        val player = client.parsePlayer(json)
        assertNull(player.syncedTo)
        assertNull(player.activeGroup)
    }

    @Test
    fun `parsePlayer stereo pair type`() {
        val json = parseJson("""
        {
            "player_id": "stereo1",
            "display_name": "Stereo Pair",
            "type": "stereo_pair",
            "available": true
        }
        """)

        val player = client.parsePlayer(json)
        assertEquals(MaPlayerType.STEREO_PAIR, player.type)
    }

    // ========================================================================
    // parsePlayerType
    // ========================================================================

    @Test
    fun `parsePlayerType all known types`() {
        assertEquals(MaPlayerType.PLAYER, client.parsePlayerType("player"))
        assertEquals(MaPlayerType.PLAYER, client.parsePlayerType("Player"))
        assertEquals(MaPlayerType.STEREO_PAIR, client.parsePlayerType("stereo_pair"))
        assertEquals(MaPlayerType.GROUP, client.parsePlayerType("group"))
        assertEquals(MaPlayerType.PROTOCOL, client.parsePlayerType("protocol"))
        assertEquals(MaPlayerType.UNKNOWN, client.parsePlayerType("something_else"))
        assertEquals(MaPlayerType.UNKNOWN, client.parsePlayerType(""))
    }

    // ========================================================================
    // parsePlaybackState
    // ========================================================================

    @Test
    fun `parsePlaybackState all known states`() {
        assertEquals(MaPlaybackState.IDLE, client.parsePlaybackState("idle"))
        assertEquals(MaPlaybackState.IDLE, client.parsePlaybackState("Idle"))
        assertEquals(MaPlaybackState.PAUSED, client.parsePlaybackState("paused"))
        assertEquals(MaPlaybackState.PLAYING, client.parsePlaybackState("playing"))
        assertEquals(MaPlaybackState.UNKNOWN, client.parsePlaybackState("buffering"))
        assertEquals(MaPlaybackState.UNKNOWN, client.parsePlaybackState(""))
    }

    // ========================================================================
    // parsePlayerFeature
    // ========================================================================

    @Test
    fun `parsePlayerFeature all known features`() {
        assertEquals(MaPlayerFeature.POWER, client.parsePlayerFeature("power"))
        assertEquals(MaPlayerFeature.VOLUME_SET, client.parsePlayerFeature("volume_set"))
        assertEquals(MaPlayerFeature.VOLUME_MUTE, client.parsePlayerFeature("volume_mute"))
        assertEquals(MaPlayerFeature.PAUSE, client.parsePlayerFeature("pause"))
        assertEquals(MaPlayerFeature.SET_MEMBERS, client.parsePlayerFeature("set_members"))
        assertEquals(MaPlayerFeature.SEEK, client.parsePlayerFeature("seek"))
        assertEquals(MaPlayerFeature.NEXT_PREVIOUS, client.parsePlayerFeature("next_previous"))
        assertEquals(MaPlayerFeature.PLAY_ANNOUNCEMENT, client.parsePlayerFeature("play_announcement"))
        assertEquals(MaPlayerFeature.ENQUEUE, client.parsePlayerFeature("enqueue"))
        assertEquals(MaPlayerFeature.SELECT_SOURCE, client.parsePlayerFeature("select_source"))
        assertEquals(MaPlayerFeature.GAPLESS_PLAYBACK, client.parsePlayerFeature("gapless_playback"))
        assertEquals(MaPlayerFeature.PLAY_MEDIA, client.parsePlayerFeature("play_media"))
        assertEquals(MaPlayerFeature.UNKNOWN, client.parsePlayerFeature("teleport"))
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun parseJson(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject
}

/**
 * Test implementation of MaSettingsProvider for unit tests.
 */
class TestMaSettingsProvider(
    private val tokens: MutableMap<String, String> = mutableMapOf(),
    private val players: MutableMap<String, String> = mutableMapOf(),
    private var port: Int = 8095
) : MaSettingsProvider {
    override fun getTokenForServer(serverId: String) = tokens[serverId]
    override fun setTokenForServer(serverId: String, token: String) { tokens[serverId] = token }
    override fun clearTokenForServer(serverId: String) { tokens.remove(serverId) }
    override fun hasTokenForServer(serverId: String) = serverId in tokens
    override fun getDefaultPort() = port
    override fun getSelectedPlayerForServer(serverId: String) = players[serverId]
    override fun setSelectedPlayerForServer(serverId: String, playerId: String) { players[serverId] = playerId }
    override fun clearSelectedPlayerForServer(serverId: String) { players.remove(serverId) }
}
