package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.protocol.management.ManagementResultCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `management/...` on the live dispatch path.
 *
 * `management.md#management`: "If a `management/...` message arrives on a
 * connection without `'management'` in activities, the client replies with
 * `management/result` `permission_denied`."
 *
 * Replies are matched to requests by ordering alone - there is no request id -
 * so the count and the order of `management/result` frames are the contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagementDispatchTest {

    private val listRecords = """{"type":"management/list-records"}"""

    @Test
    fun `a request outside a management session is answered and not closed`() {
        val handler = handler(activities = """["playback"]""")

        handler.handleTextMessageForTest(listRecords)
        handler.scope.runCurrent()

        assertEquals(listOf("send:management/result"), handler.events)
        assertEquals(ManagementResultCode.PERMISSION_DENIED.wire, handler.sent.single().result())
    }

    @Test
    fun `an unknown management type is answered rather than dropped`() {
        // The generic unhandled-message log would leave the server waiting for
        // a reply that never arrives - which is exactly how MA's device
        // dialog hangs today.
        val handler = handler(activities = """["management"]""")

        handler.handleTextMessageForTest("""{"type":"management/does-not-exist"}""")
        handler.scope.runCurrent()

        assertEquals(listOf("send:management/result"), handler.events)
        assertEquals(ManagementResultCode.INVALID.wire, handler.sent.single().result())
    }

    @Test
    fun `every request gets exactly one reply, in order`() {
        val handler = handler(activities = """["management"]""")

        handler.handleTextMessageForTest(listRecords)
        handler.handleTextMessageForTest("""{"type":"management/does-not-exist"}""")
        handler.handleTextMessageForTest("""{"type":"management/open-pairing-window"}""")
        handler.scope.runCurrent()

        assertEquals(3, handler.sent.size)
        assertEquals(
            listOf("send:management/result", "send:management/result", "send:management/result"),
            handler.events,
        )
        // Second and third are known-invalid; if replies were reordered or
        // coalesced the server would attribute them to the wrong request.
        assertEquals(ManagementResultCode.INVALID.wire, handler.sent[1].result())
        assertEquals(ManagementResultCode.INVALID.wire, handler.sent[2].result())
    }

    @Test
    fun `a management request never closes the connection`() {
        val handler = handler(activities = """["playback"]""")

        handler.handleTextMessageForTest(listRecords)
        handler.handleTextMessageForTest("""{"type":"management/add-record","payload":{}}""")
        handler.scope.runCurrent()

        assertEquals(emptyList<String>(), handler.events.filter { it == "close" })
    }

    private fun handler(activities: String): AbortTestHandler {
        val handler = AbortTestHandler(PskCategory.LONG_TERM)
        handler.setHandshakeCompleteForTest()
        handler.handleTextMessageForTest(
            """{"type":"server/activate","payload":{"activities":$activities,"roles":["player@v1"]}}"""
        )
        handler.scope.runCurrent()
        handler.clearEvents()
        return handler
    }

    private fun String.result(): String? =
        Json.parseToJsonElement(this).jsonObject["payload"]
            ?.jsonObject?.get("result")?.jsonPrimitive?.content
}
