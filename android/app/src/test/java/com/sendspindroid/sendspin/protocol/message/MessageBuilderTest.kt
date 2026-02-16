package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessageBuilderTest {

    // --- buildClientTime ---

    @Test
    fun buildClientTime_hasCorrectType() {
        val msg = MessageBuilder.buildClientTime(12345L)
        assertEquals(SendSpinProtocol.MessageType.CLIENT_TIME, msg.getString("type"))
    }

    @Test
    fun buildClientTime_hasClientTransmittedInPayload() {
        val msg = MessageBuilder.buildClientTime(12345L)
        val payload = msg.getJSONObject("payload")
        assertEquals(12345L, payload.getLong("client_transmitted"))
    }

    // --- buildGoodbye ---

    @Test
    fun buildGoodbye_hasCorrectType() {
        val msg = MessageBuilder.buildGoodbye("user_disconnect")
        assertEquals(SendSpinProtocol.MessageType.CLIENT_GOODBYE, msg.getString("type"))
    }

    @Test
    fun buildGoodbye_hasReasonInPayload() {
        val msg = MessageBuilder.buildGoodbye("user_disconnect")
        val payload = msg.getJSONObject("payload")
        assertEquals("user_disconnect", payload.getString("reason"))
    }

    // --- buildPlayerState ---

    @Test
    fun buildPlayerState_hasCorrectType() {
        val msg = MessageBuilder.buildPlayerState(50, false)
        assertEquals(SendSpinProtocol.MessageType.CLIENT_STATE, msg.getString("type"))
    }

    @Test
    fun buildPlayerState_hasPlayerObjectWithFields() {
        val msg = MessageBuilder.buildPlayerState(75, true, "error")
        val player = msg.getJSONObject("payload").getJSONObject("player")
        assertEquals(75, player.getInt("volume"))
        assertTrue(player.getBoolean("muted"))
        assertEquals("error", player.getString("state"))
    }

    @Test
    fun buildPlayerState_defaultSyncState() {
        val msg = MessageBuilder.buildPlayerState(50, false)
        val player = msg.getJSONObject("payload").getJSONObject("player")
        assertEquals("synchronized", player.getString("state"))
    }

    // --- buildCommand ---

    @Test
    fun buildCommand_hasCorrectType() {
        val msg = MessageBuilder.buildCommand("play")
        assertEquals(SendSpinProtocol.MessageType.CLIENT_COMMAND, msg.getString("type"))
    }

    @Test
    fun buildCommand_hasCommandInControllerObject() {
        val msg = MessageBuilder.buildCommand("next")
        val controller = msg.getJSONObject("payload").getJSONObject("controller")
        assertEquals("next", controller.getString("command"))
    }

    // --- serialize ---

    @Test
    fun serialize_replacesEscapedSlashes() {
        val msg = JSONObject().apply {
            put("url", "https://example.com/path/to/resource")
        }
        val serialized = MessageBuilder.serialize(msg)
        assertFalse("Should not contain escaped slashes", serialized.contains("\\/"))
        assertTrue("Should contain unescaped slashes", serialized.contains("/"))
    }
}
