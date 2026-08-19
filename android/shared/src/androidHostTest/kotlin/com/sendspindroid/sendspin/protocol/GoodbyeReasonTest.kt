package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `client/goodbye` reasons.
 *
 * `messaging.md#client--server-clientgoodbye` defines a closed set. The server
 * reads the reason to decide whether to reconnect, so an unrecognised or
 * misspelled value is not a cosmetic problem: it falls back to the
 * no-goodbye heuristic and the server reconnects to a client that just told it
 * to stop.
 */
class GoodbyeReasonTest {

    @Test
    fun theEnumIsExactlyTheSpecSet() {
        // Pinned deliberately. A new reason must be added to the spec first,
        // and a removed one must not linger as a value we can still send.
        assertEquals(
            setOf(
                "another_server",
                "shutdown",
                "restart",
                "user_request",
                "unauthorized",
                "pairing_required",
                "concurrent_attempt",
                "unpaired",
            ),
            GoodbyeReason.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun everyReasonHasADistinctWireValue() {
        assertEquals(GoodbyeReason.entries.size, GoodbyeReason.entries.map { it.wire }.toSet().size)
    }

    @Test
    fun buildGoodbyeCarriesTheReasonAndNothingElse() {
        val json = Json.parseToJsonElement(
            MessageBuilder.buildGoodbye(GoodbyeReason.UNPAIRED)
        ).jsonObject

        assertEquals("client/goodbye", json["type"]?.jsonPrimitive?.content)
        val payload = json["payload"]!!.jsonObject
        assertEquals("unpaired", payload["reason"]?.jsonPrimitive?.content)
        assertEquals(setOf("reason"), payload.keys)
        assertEquals(setOf("type", "payload"), json.keys)
    }
}
