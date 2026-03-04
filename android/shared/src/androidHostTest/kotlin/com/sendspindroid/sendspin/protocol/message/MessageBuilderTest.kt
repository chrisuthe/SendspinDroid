package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Test

class MessageBuilderTest {

    // --- buildClientTime ---

    @Test
    fun buildClientTime_hasCorrectType() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildClientTime(12345L)).jsonObject
        assertEquals(SendSpinProtocol.MessageType.CLIENT_TIME, msg["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildClientTime_hasClientTransmittedInPayload() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildClientTime(12345L)).jsonObject
        val payload = msg["payload"]!!.jsonObject
        assertEquals(12345L, payload["client_transmitted"]?.jsonPrimitive?.long)
    }

    // --- buildGoodbye ---

    @Test
    fun buildGoodbye_hasCorrectType() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildGoodbye("user_disconnect")).jsonObject
        assertEquals(SendSpinProtocol.MessageType.CLIENT_GOODBYE, msg["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildGoodbye_hasReasonInPayload() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildGoodbye("user_disconnect")).jsonObject
        val payload = msg["payload"]!!.jsonObject
        assertEquals("user_disconnect", payload["reason"]?.jsonPrimitive?.content)
    }

    // --- buildPlayerState ---

    @Test
    fun buildPlayerState_hasCorrectType() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildPlayerState(50, false)).jsonObject
        assertEquals(SendSpinProtocol.MessageType.CLIENT_STATE, msg["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildPlayerState_hasPlayerObjectWithFields() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildPlayerState(75, true, "error")).jsonObject
        val player = msg["payload"]!!.jsonObject["player"]!!.jsonObject
        assertEquals(75, player["volume"]?.jsonPrimitive?.int)
        assertTrue(player["muted"]?.jsonPrimitive?.boolean ?: false)
        assertEquals("error", player["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildPlayerState_defaultSyncState() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildPlayerState(50, false)).jsonObject
        val player = msg["payload"]!!.jsonObject["player"]!!.jsonObject
        assertEquals("synchronized", player["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildPlayerState_includesStaticDelayMs() {
        val msg = Json.parseToJsonElement(
            MessageBuilder.buildPlayerState(50, false, "synchronized", 12.5)
        ).jsonObject
        val player = msg["payload"]!!.jsonObject["player"]!!.jsonObject
        assertEquals(12.5, player["static_delay_ms"]?.jsonPrimitive?.double ?: 0.0, 0.01)
    }

    @Test
    fun buildPlayerState_staticDelayMsDefaultsToZero() {
        val msg = Json.parseToJsonElement(
            MessageBuilder.buildPlayerState(50, false)
        ).jsonObject
        val player = msg["payload"]!!.jsonObject["player"]!!.jsonObject
        assertEquals(0.0, player["static_delay_ms"]?.jsonPrimitive?.double ?: -1.0, 0.01)
    }

    // --- buildCommand ---

    @Test
    fun buildCommand_hasCorrectType() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildCommand("play")).jsonObject
        assertEquals(SendSpinProtocol.MessageType.CLIENT_COMMAND, msg["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildCommand_hasCommandInControllerObject() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildCommand("next")).jsonObject
        val controller = msg["payload"]!!.jsonObject["controller"]!!.jsonObject
        assertEquals("next", controller["command"]?.jsonPrimitive?.content)
    }

    // --- buildSupportedFormats ---

    @Test
    fun buildSupportedFormats_preferredCodecFirst() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "opus",
            isCodecSupported = { it in listOf("flac", "opus", "pcm") }
        )
        assertTrue(formats.isNotEmpty())
        assertEquals("opus", formats[0].codec)
    }

    @Test
    fun buildSupportedFormats_pcmAlwaysLast() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "flac",
            isCodecSupported = { it in listOf("flac", "opus", "pcm") }
        )
        val lastCodec = formats.last().codec
        assertEquals("pcm", lastCodec)
    }

    @Test
    fun buildSupportedFormats_stereoAndMonoForEachCodec() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "flac",
            isCodecSupported = { it == "flac" }
        )
        // flac: stereo + mono = 2 entries
        assertEquals(2, formats.size)
        assertEquals(2, formats[0].channels)
        assertEquals(1, formats[1].channels)
    }

    @Test
    fun buildSupportedFormats_includesMultipleBitDepths() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "pcm",
            isCodecSupported = { it == "pcm" },
            supportedBitDepths = listOf(16, 24, 32)
        )
        // pcm at 32-bit stereo/mono, 24-bit stereo/mono, 16-bit stereo/mono = 6
        // Higher bit depths should come first (server picks first match)
        assertEquals(6, formats.size)
        assertEquals(32, formats[0].bitDepth)
        assertEquals(32, formats[1].bitDepth)
        assertEquals(24, formats[2].bitDepth)
        assertEquals(24, formats[3].bitDepth)
        assertEquals(16, formats[4].bitDepth)
        assertEquals(16, formats[5].bitDepth)
    }

    @Test
    fun buildSupportedFormats_defaultBitDepthIs16Only() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "pcm",
            isCodecSupported = { it == "pcm" }
        )
        assertEquals(2, formats.size)
        assertTrue(formats.all { it.bitDepth == 16 })
    }

    @Test
    fun buildSupportedFormats_multiBitDepthOnlyAppliesToPcm() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "flac",
            isCodecSupported = { it in listOf("flac", "pcm") },
            supportedBitDepths = listOf(16, 32)
        )
        // flac: 16-bit only (stereo + mono) = 2
        // pcm:  32-bit stereo/mono + 16-bit stereo/mono = 4
        assertEquals(6, formats.size)
        // First 2 are flac at 16-bit only
        assertEquals("flac", formats[0].codec)
        assertEquals(16, formats[0].bitDepth)
        assertEquals("flac", formats[1].codec)
        // Last 4 are pcm with higher depths first
        assertEquals("pcm", formats[2].codec)
        assertEquals(32, formats[2].bitDepth)
        assertEquals("pcm", formats[4].codec)
        assertEquals(16, formats[4].bitDepth)
    }

    // --- No serialize needed (returns String directly) ---

    @Test
    fun buildClientTime_outputIsValidJson() {
        val text = MessageBuilder.buildClientTime(12345L)
        // Should not throw
        Json.parseToJsonElement(text)
        assertFalse("Should not contain escaped slashes", text.contains("\\/"))
    }
}
