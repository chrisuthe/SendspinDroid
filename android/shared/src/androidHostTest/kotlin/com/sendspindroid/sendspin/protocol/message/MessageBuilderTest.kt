package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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

    // --- No serialize needed (returns String directly) ---

    @Test
    fun buildClientTime_outputIsValidJson() {
        val text = MessageBuilder.buildClientTime(12345L)
        // Should not throw
        Json.parseToJsonElement(text)
        assertFalse("Should not contain escaped slashes", text.contains("\\/"))
    }
}
