package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.ServerCommandResult
import com.sendspindroid.shared.log.Log
import com.sendspindroid.shared.platform.Platform
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MessageParserTest {

    @Before
    fun setUp() {
        mockkObject(Log)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        mockkObject(Platform)
        every { Platform.base64Decode(any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- parseServerHello ---

    @Test
    fun parseServerHello_validPayload_returnsResult() {
        val payload = buildJsonObject {
            put("name", "TestServer")
            put("server_id", "abc-123")
            put("connection_reason", "user_request")
            put("active_roles", buildJsonArray {
                add(JsonPrimitive("player@v1"))
                add(JsonPrimitive("controller@v1"))
            })
        }
        val result = MessageParser.parseServerHello(payload, "default")

        assertNotNull(result)
        assertEquals("TestServer", result!!.serverName)
        assertEquals("abc-123", result.serverId)
        assertEquals("user_request", result.connectionReason)
        assertEquals(2, result.activeRoles.size)
        assertEquals("player@v1", result.activeRoles[0])
    }

    @Test
    fun parseServerHello_nullPayload_returnsNull() {
        assertNull(MessageParser.parseServerHello(null, "default"))
    }

    @Test
    fun parseServerHello_missingOptionalFields_usesDefaults() {
        val payload = buildJsonObject { } // empty
        val result = MessageParser.parseServerHello(payload, "MyDefault")

        assertNotNull(result)
        assertEquals("MyDefault", result!!.serverName)
        assertEquals("", result.serverId)
        assertEquals("discovery", result.connectionReason)
        assertTrue(result.activeRoles.isEmpty())
    }

    // --- parseServerTime ---

    @Test
    fun parseServerTime_validTimestamps_returnsCorrectOffset() {
        val payload = buildJsonObject {
            put("client_transmitted", 100L)
            put("server_received", 200L)
            put("server_transmitted", 300L)
        }
        val clientReceived = 400L

        val result = MessageParser.parseServerTime(payload, clientReceived)

        assertNotNull(result)
        assertEquals(0L, result!!.offset)
    }

    @Test
    fun parseServerTime_withClockOffset_calculatesCorrectly() {
        val payload = buildJsonObject {
            put("client_transmitted", 100L)
            put("server_received", 1200L)
            put("server_transmitted", 1300L)
        }
        val result = MessageParser.parseServerTime(payload, 200L)

        assertNotNull(result)
        assertEquals(1100L, result!!.offset)
    }

    @Test
    fun parseServerTime_calculatesRtt() {
        val payload = buildJsonObject {
            put("client_transmitted", 100L)
            put("server_received", 200L)
            put("server_transmitted", 300L)
        }
        val result = MessageParser.parseServerTime(payload, 400L)

        assertNotNull(result)
        assertEquals(200L, result!!.rtt)
    }

    @Test
    fun parseServerTime_nullPayload_returnsNull() {
        assertNull(MessageParser.parseServerTime(null, 0L))
    }

    @Test
    fun parseServerTime_zeroTimestamps_returnsNull() {
        val payload = buildJsonObject {
            put("client_transmitted", 0L)
            put("server_received", 0L)
            put("server_transmitted", 0L)
        }
        assertNull(MessageParser.parseServerTime(payload, 100L))
    }

    // --- parseServerState ---

    @Test
    fun parseServerState_specCompliantNested_parsesCorrectly() {
        val payload = buildJsonObject {
            put("metadata", buildJsonObject {
                put("timestamp", 1234567890L)
                put("title", "Test Song")
                put("artist", "Test Artist")
                put("album_artist", "Album Artist")
                put("album", "Test Album")
                put("artwork_url", "https://example.com/art.jpg")
                put("year", 2024)
                put("track", 5)
                put("progress", buildJsonObject {
                    put("track_progress", 45000L)
                    put("track_duration", 180000L)
                    put("playback_speed", 1000)
                })
            })
            put("state", "playing")
        }

        val (metadata, state) = MessageParser.parseServerState(payload)

        assertNotNull(metadata)
        assertEquals("Test Song", metadata!!.title)
        assertEquals("Test Artist", metadata.artist)
        assertEquals("Album Artist", metadata.albumArtist)
        assertEquals(45000L, metadata.progress.trackProgress)
        assertEquals(180000L, metadata.progress.trackDuration)
        assertEquals("playing", state)
    }

    @Test
    fun parseServerState_legacyFlatStructure_parsesAsFallback() {
        val payload = buildJsonObject {
            put("metadata", buildJsonObject {
                put("title", "Legacy Song")
                put("artist", "Legacy Artist")
                put("position_ms", 30000L)
                put("duration_ms", 200000L)
            })
        }

        val (metadata, _) = MessageParser.parseServerState(payload)

        assertNotNull(metadata)
        assertEquals("Legacy Song", metadata!!.title)
        assertEquals(30000L, metadata.progress.trackProgress)
        assertEquals(200000L, metadata.progress.trackDuration)
    }

    @Test
    fun parseServerState_nullPayload_returnsNulls() {
        val (metadata, state) = MessageParser.parseServerState(null)
        assertNull(metadata)
        assertNull(state)
    }

    @Test
    fun parseServerState_noMetadata_returnsNullMetadata() {
        val payload = buildJsonObject {
            put("state", "paused")
        }
        val (metadata, state) = MessageParser.parseServerState(payload)
        assertNull(metadata)
        assertEquals("paused", state)
    }

    // --- parseServerCommand ---

    @Test
    fun parseServerCommand_volume_returnsVolumeResult() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("command", "volume")
                put("volume", 75)
            })
        }
        val result = MessageParser.parseServerCommand(payload)

        assertTrue(result is ServerCommandResult.Volume)
        assertEquals(75, (result as ServerCommandResult.Volume).volume)
    }

    @Test
    fun parseServerCommand_volumeOutOfRange_returnsNull() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("command", "volume")
                put("volume", 101)
            })
        }
        assertNull(MessageParser.parseServerCommand(payload))
    }

    @Test
    fun parseServerCommand_volumeNegative_returnsNull() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("command", "volume")
                put("volume", -1)
            })
        }
        assertNull(MessageParser.parseServerCommand(payload))
    }

    @Test
    fun parseServerCommand_mute_returnsMuteResult() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("command", "mute")
                put("mute", true)
            })
        }
        val result = MessageParser.parseServerCommand(payload)
        assertTrue(result is ServerCommandResult.Mute)
        assertTrue((result as ServerCommandResult.Mute).muted)
    }

    @Test
    fun parseServerCommand_unknownCommand_returnsUnknown() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("command", "custom_cmd")
            })
        }
        val result = MessageParser.parseServerCommand(payload)
        assertTrue(result is ServerCommandResult.Unknown)
        assertEquals("custom_cmd", (result as ServerCommandResult.Unknown).command)
    }

    @Test
    fun parseServerCommand_nullPayload_returnsNull() {
        assertNull(MessageParser.parseServerCommand(null))
    }

    @Test
    fun parseServerCommand_noPlayerObject_returnsNull() {
        assertNull(MessageParser.parseServerCommand(buildJsonObject { }))
    }

    // --- parseGroupUpdate ---

    @Test
    fun parseGroupUpdate_validPayload_returnsGroupInfo() {
        val payload = buildJsonObject {
            put("group_id", "group-1")
            put("group_name", "Living Room")
            put("playback_state", "playing")
        }
        val result = MessageParser.parseGroupUpdate(payload)

        assertNotNull(result)
        assertEquals("group-1", result!!.groupId)
        assertEquals("Living Room", result.groupName)
        assertEquals("playing", result.playbackState)
    }

    @Test
    fun parseGroupUpdate_nullPayload_returnsNull() {
        assertNull(MessageParser.parseGroupUpdate(null))
    }

    // --- parseStreamStart ---

    @Test
    fun parseStreamStart_validPayload_returnsStreamConfig() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("codec", "flac")
                put("sample_rate", 44100)
                put("channels", 2)
                put("bit_depth", 24)
            })
        }
        val result = MessageParser.parseStreamStart(payload)

        assertNotNull(result)
        assertEquals("flac", result!!.codec)
        assertEquals(44100, result.sampleRate)
        assertEquals(2, result.channels)
        assertEquals(24, result.bitDepth)
        assertNull(result.codecHeader)
    }

    @Test
    fun parseStreamStart_withCodecHeader_decodesBase64() {
        val headerBytes = byteArrayOf(0x66, 0x4C, 0x61, 0x43) // "fLaC"
        val headerBase64 = java.util.Base64.getEncoder().encodeToString(headerBytes)

        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("codec", "flac")
                put("sample_rate", 48000)
                put("channels", 2)
                put("bit_depth", 16)
                put("codec_header", headerBase64)
            })
        }
        val result = MessageParser.parseStreamStart(payload)

        assertNotNull(result)
        assertNotNull(result!!.codecHeader)
        assertArrayEquals(headerBytes, result.codecHeader)
    }

    @Test
    fun parseStreamStart_nullPayload_returnsNull() {
        assertNull(MessageParser.parseStreamStart(null))
    }

    @Test
    fun parseStreamStart_noPlayerObject_returnsNull() {
        assertNull(MessageParser.parseStreamStart(buildJsonObject { }))
    }

    // --- parseSyncOffset ---

    @Test
    fun parseSyncOffset_validPayload_returnsResult() {
        val payload = buildJsonObject {
            put("player_id", "player-1")
            put("offset_ms", 5.5)
            put("source", "calibration")
        }
        val result = MessageParser.parseSyncOffset(payload)

        assertNotNull(result)
        assertEquals("player-1", result!!.playerId)
        assertEquals(5.5, result.offsetMs, 0.001)
        assertEquals("calibration", result.source)
    }

    @Test
    fun parseSyncOffset_nullPayload_returnsNull() {
        assertNull(MessageParser.parseSyncOffset(null))
    }
}
