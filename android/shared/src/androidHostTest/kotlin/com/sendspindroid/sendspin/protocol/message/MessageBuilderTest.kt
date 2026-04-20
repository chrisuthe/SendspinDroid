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
    fun buildSupportedFormats_preferredCodecFirst_pcmLast() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "opus",
            isCodecSupported = { it in listOf("opus", "pcm") }
        )
        assertTrue(formats.isNotEmpty())
        assertEquals("opus", formats.first().codec)
        assertEquals("pcm", formats.last().codec)
    }

    @Test
    fun buildSupportedFormats_onlyPreferredAndPcm_noSecondaryCodec() {
        // Regression guard for issue #26: previously a hardcoded [flac, opus] secondary
        // list was appended after the preferred codec. Now only the preferred codec
        // and PCM are advertised.
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "opus",
            isCodecSupported = { it in listOf("opus", "flac", "pcm") }
        )
        val codecsAdvertised = formats.map { it.codec }.toSet()
        assertEquals(setOf("opus", "pcm"), codecsAdvertised)
    }

    @Test
    fun buildSupportedFormats_preferredPcmProducesPcmOnly() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "pcm",
            isCodecSupported = { it == "pcm" }
        )
        assertTrue(formats.all { it.codec == "pcm" })
    }

    @Test
    fun buildSupportedFormats_preferredUnsupportedFallsBackToPcm() {
        // On a device where the preferred codec is not decodable, we still advertise
        // PCM so the session is not silently broken.
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "opus",
            isCodecSupported = { it == "pcm" }
        )
        val codecsAdvertised = formats.map { it.codec }.toSet()
        assertEquals(setOf("pcm"), codecsAdvertised)
    }

    @Test
    fun buildSupportedFormats_stereoAndMonoForEachCodec() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "flac",
            isCodecSupported = { it in listOf("flac", "pcm") }
        )
        // flac at 16-bit stereo + mono = 2
        // pcm at 16-bit stereo + mono = 2 (default supportedBitDepths = [16])
        // total = 4
        assertEquals(4, formats.size)
        val channelSet = formats.map { it.channels }.toSet()
        assertEquals(setOf(2, 1), channelSet)
    }

    @Test
    fun buildSupportedFormats_multiBitDepthOnlyAppliesToPcm() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "flac",
            isCodecSupported = { it in listOf("flac", "pcm") },
            supportedBitDepths = listOf(16, 32)
        )
        // flac: 16-bit only (stereo + mono) = 2
        // pcm:  32-bit stereo/mono + 16-bit stereo/mono = 4 (higher depths first)
        assertEquals(6, formats.size)
        assertEquals("flac", formats[0].codec)
        assertEquals(16, formats[0].bitDepth)
        assertEquals("pcm", formats[2].codec)
        assertEquals(32, formats[2].bitDepth)
        assertEquals("pcm", formats[4].codec)
        assertEquals(16, formats[4].bitDepth)
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

    // --- calculateBufferCapacity ---

    @Test
    fun calculateBufferCapacity_16bitStereo35sec() {
        val formats = listOf(
            MessageBuilder.FormatEntry("pcm", 48000, 2, 16),
            MessageBuilder.FormatEntry("pcm", 48000, 1, 16)
        )
        // 35 * 48000 * 2 * 2 = 6,720,000
        assertEquals(6_720_000, MessageBuilder.calculateBufferCapacity(formats, 35))
    }

    @Test
    fun calculateBufferCapacity_32bitStereo35sec() {
        val formats = listOf(
            MessageBuilder.FormatEntry("pcm", 48000, 2, 32),
            MessageBuilder.FormatEntry("pcm", 48000, 1, 32),
            MessageBuilder.FormatEntry("pcm", 48000, 2, 16),
            MessageBuilder.FormatEntry("pcm", 48000, 1, 16)
        )
        // Uses max PCM entry: 35 * 48000 * 2 * 4 = 13,440,000
        assertEquals(13_440_000, MessageBuilder.calculateBufferCapacity(formats, 35))
    }

    @Test
    fun calculateBufferCapacity_lowMemory16bit() {
        val formats = listOf(
            MessageBuilder.FormatEntry("pcm", 48000, 2, 16),
            MessageBuilder.FormatEntry("pcm", 48000, 1, 16)
        )
        // 10 * 48000 * 2 * 2 = 1,920,000
        assertEquals(1_920_000, MessageBuilder.calculateBufferCapacity(formats, 10))
    }

    @Test
    fun calculateBufferCapacity_ignoresCompressedCodecs() {
        val formats = listOf(
            MessageBuilder.FormatEntry("flac", 48000, 2, 16),
            MessageBuilder.FormatEntry("opus", 48000, 2, 16),
            MessageBuilder.FormatEntry("pcm", 48000, 2, 16),
            MessageBuilder.FormatEntry("pcm", 48000, 1, 16)
        )
        // Only PCM entries matter: 35 * 48000 * 2 * 2 = 6,720,000
        assertEquals(6_720_000, MessageBuilder.calculateBufferCapacity(formats, 35))
    }

    @Test
    fun calculateBufferCapacity_fallbackWhenNoPcm() {
        val formats = listOf(
            MessageBuilder.FormatEntry("flac", 48000, 2, 16)
        )
        // Fallback: 35 * 48000 * 2 * 2 = 6,720,000
        assertEquals(6_720_000, MessageBuilder.calculateBufferCapacity(formats, 35))
    }

    // --- buildClientHello field names ---

    @Test
    fun buildClientHello_usesV1FieldNames() {
        val formats = listOf(
            MessageBuilder.FormatEntry("pcm", 48000, 2, 16)
        )
        val text = MessageBuilder.buildClientHello(
            clientId = "test-id",
            deviceName = "Test Device",
            bufferCapacity = 6_720_000,
            manufacturer = "Test",
            supportedFormats = formats
        )
        val payload = Json.parseToJsonElement(text).jsonObject["payload"]!!.jsonObject
        assertNotNull("player@v1_support should be present", payload["player@v1_support"])
        assertNotNull("artwork@v1_support should be present", payload["artwork@v1_support"])
        assertNull("legacy player_support should not be present", payload["player_support"])
        assertNull("legacy artwork_support should not be present", payload["artwork_support"])
    }

    @Test
    fun buildClientHello_hasCorrectBufferCapacity() {
        val formats = listOf(
            MessageBuilder.FormatEntry("pcm", 48000, 2, 16)
        )
        val text = MessageBuilder.buildClientHello(
            clientId = "test-id",
            deviceName = "Test Device",
            bufferCapacity = 6_720_000,
            manufacturer = "Test",
            supportedFormats = formats
        )
        val payload = Json.parseToJsonElement(text).jsonObject["payload"]!!.jsonObject
        val playerSupport = payload["player@v1_support"]!!.jsonObject
        assertEquals(6_720_000, playerSupport["buffer_capacity"]?.jsonPrimitive?.int)
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
