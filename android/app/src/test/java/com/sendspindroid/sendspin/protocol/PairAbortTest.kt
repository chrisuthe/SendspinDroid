package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.TrustStore
import com.sendspindroid.sendspin.pairing.PairAbortReason
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `pair/abort`, both directions, and the attempt timeout that bounds every
 * attempt.
 *
 * `pairing.md#client--server-pairabort`: "With reason `concurrent_attempt` the
 * sender closes the connection after sending, otherwise the connection stays
 * open. A `pair/abort` received after the receiver has itself ended the attempt
 * has no effect."
 *
 * The two behaviours worth guarding are asymmetric and easy to invert: only the
 * *sender* of `concurrent_attempt` closes, and a received abort never closes
 * anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairAbortTest {

    private val pairingActivation =
        """{"type":"server/activate","payload":{"activities":["pairing"],"pairing":{"method":"pairing_psk"}}}"""

    // ========== Sending ==========

    @Test
    fun `concurrent_attempt is sent and then closes the connection`() {
        val handler = handler()

        handler.sendPairAbortForTest(PairAbortReason.CONCURRENT_ATTEMPT)
        handler.scope.runCurrent()

        // The frame must be out before the close, not merely queued before it.
        assertEquals(listOf("send:pair/abort", "close"), handler.events)
    }

    @Test
    fun `every other reason leaves the connection open`() {
        for (reason in PairAbortReason.ALL - PairAbortReason.CLOSES_CONNECTION) {
            val handler = handler()

            handler.sendPairAbortForTest(reason)
            handler.scope.runCurrent()

            assertEquals("reason $reason", listOf("send:pair/abort"), handler.events)
            assertEquals(reason, handler.sent.single().reason())
        }
    }

    // ========== The attempt timeout ==========

    @Test
    fun `the attempt times out and aborts without closing`() {
        val handler = handler(matched = PskCategory.PAIRING)
        handler.handleTextMessageForTest(pairingActivation)
        handler.scope.runCurrent()
        handler.clearEvents()

        handler.scope.advanceTimeBy(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS + 1)
        handler.scope.runCurrent()

        assertEquals(listOf("send:pair/abort"), handler.events)
        assertEquals(PairAbortReason.ATTEMPT_TIMEOUT, handler.sent.last().reason())
    }

    @Test
    fun `the attempt has not timed out one millisecond early`() {
        val handler = handler(matched = PskCategory.PAIRING)
        handler.handleTextMessageForTest(pairingActivation)
        handler.scope.runCurrent()
        handler.clearEvents()

        handler.scope.advanceTimeBy(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS - 1)
        handler.scope.runCurrent()

        assertEquals(emptyList<String>(), handler.events)
    }

    @Test
    fun `a successful pairing cancels the timeout`() {
        val handler = handler(matched = PskCategory.PAIRING)
        handler.handleTextMessageForTest(pairingActivation)
        handler.scope.runCurrent()
        handler.handleTextMessageForTest("""{"type":"server/pair-finalize"}""")
        handler.scope.runCurrent()
        handler.clearEvents()

        handler.scope.advanceTimeBy(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS * 2)
        handler.scope.runCurrent()

        // A timer left running after success aborts a pairing that already
        // worked, two minutes later, for no reason the user can see.
        assertEquals(emptyList<String>(), handler.events)
    }

    @Test
    fun `a received abort cancels the timeout`() {
        val handler = handler(matched = PskCategory.PAIRING)
        handler.handleTextMessageForTest(pairingActivation)
        handler.scope.runCurrent()
        handler.clearEvents()

        handler.handleTextMessageForTest(abort(PairAbortReason.USER_CANCELLED))
        handler.scope.advanceTimeBy(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS * 2)
        handler.scope.runCurrent()

        assertEquals(emptyList<String>(), handler.events)
    }

    // ========== Receiving ==========

    @Test
    fun `a received abort sends nothing and closes nothing`() {
        val handler = handler(matched = PskCategory.PAIRING)
        handler.handleTextMessageForTest(pairingActivation)
        handler.scope.runCurrent()
        handler.clearEvents()

        handler.handleTextMessageForTest(abort(PairAbortReason.PIN_MISMATCH))
        handler.scope.runCurrent()

        assertEquals(emptyList<String>(), handler.events)
    }

    @Test
    fun `a received concurrent_attempt does not close our end`() {
        val handler = handler(matched = PskCategory.PAIRING)
        handler.handleTextMessageForTest(pairingActivation)
        handler.scope.runCurrent()
        handler.clearEvents()

        handler.handleTextMessageForTest(abort(PairAbortReason.CONCURRENT_ATTEMPT))
        handler.scope.runCurrent()

        // The spec makes the SENDER close. Closing as receiver too would race
        // the peer's close and report the wrong close reason.
        assertEquals(emptyList<String>(), handler.events)
    }

    @Test
    fun `an abort outside any attempt is ignored`() {
        val handler = handler()

        handler.handleTextMessageForTest(abort(PairAbortReason.ATTEMPT_TIMEOUT))
        handler.scope.runCurrent()

        assertEquals(emptyList<String>(), handler.events)
    }

    @Test
    fun `an unknown reason is accepted rather than treated as an error`() {
        val handler = handler(matched = PskCategory.PAIRING)
        handler.handleTextMessageForTest(pairingActivation)
        handler.scope.runCurrent()
        handler.clearEvents()

        handler.handleTextMessageForTest(abort("future_reason"))
        handler.scope.runCurrent()

        // A newer peer's reason must still end the attempt. Rejecting it would
        // leave us waiting on an attempt the other side has abandoned.
        assertEquals(emptyList<String>(), handler.events)
        handler.scope.advanceTimeBy(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS * 2)
        handler.scope.runCurrent()
        assertEquals(emptyList<String>(), handler.events)
    }

    @Test
    fun `unknown payload fields are ignored`() {
        val handler = handler(matched = PskCategory.PAIRING)
        handler.handleTextMessageForTest(pairingActivation)
        handler.scope.runCurrent()
        handler.clearEvents()

        handler.handleTextMessageForTest(
            """{"type":"pair/abort","payload":{"reason":"user_cancelled","future":1}}"""
        )
        handler.scope.advanceTimeBy(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS * 2)
        handler.scope.runCurrent()

        assertEquals(emptyList<String>(), handler.events)
    }

    // ========== Operator cancellation ==========

    @Test
    fun `cancelling the attempt sends user_cancelled and stays open`() {
        val handler = handler(matched = PskCategory.PAIRING)
        handler.handleTextMessageForTest(pairingActivation)
        handler.scope.runCurrent()
        handler.clearEvents()

        handler.cancelPairing()
        handler.scope.runCurrent()

        assertEquals(listOf("send:pair/abort"), handler.events)
        assertEquals(PairAbortReason.USER_CANCELLED, handler.sent.last().reason())
    }

    @Test
    fun `cancelling with no attempt in flight sends nothing`() {
        val handler = handler()

        handler.cancelPairing()
        handler.scope.runCurrent()

        assertEquals(emptyList<String>(), handler.events)
    }

    // ========== Helpers ==========

    private fun abort(reason: String) =
        """{"type":"pair/abort","payload":{"reason":"$reason"}}"""

    private fun handler(matched: PskCategory = PskCategory.SENTINEL): AbortTestHandler {
        val handler = AbortTestHandler(matched)
        handler.setHandshakeCompleteForTest()
        return handler
    }

    private fun String.reason(): String? =
        Json.parseToJsonElement(this).jsonObject["payload"]
            ?.jsonObject?.get("reason")?.jsonPrimitive?.content
}

/** A handler on a session keyed by [matchedCategory], recording what it sends. */
@OptIn(ExperimentalCoroutinesApi::class)
class AbortTestHandler(
    private val matchedCategory: PskCategory,
) : SendSpinProtocolHandler("AbortTest") {

    val scope = TestScope()
    val sent = mutableListOf<String>()
    val events = mutableListOf<String>()

    private val timeFilter = SendspinTimeFilter()
    private val store = FakeTrustStore()

    fun setHandshakeCompleteForTest() { handshakeComplete = true }

    fun handleTextMessageForTest(text: String) = handleTextMessage(text)

    fun sendPairAbortForTest(reason: String) = sendPairAbort(reason)

    fun clearEvents() {
        events.clear()
        sent.clear()
    }

    override fun matchedPskCategory(): PskCategory = matchedCategory

    override fun matchedPsk(): Psk? =
        if (matchedCategory == PskCategory.LONG_TERM) {
            Psk(ByteArray(32) { 3 }, PskCategory.LONG_TERM, "srv1")
        } else {
            Psk(ByteArray(32) { 3 }, matchedCategory)
        }

    override fun trustStore(): TrustStore = store

    override fun currentServerId(): String = "srv1"

    override fun closeConnectionAfterFlush() {
        events.add("close")
    }

    override fun sendTextMessage(text: String) {
        sent.add(text)
        events.add("send:" + Json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content)
    }

    override fun sendBinaryFrame(bytes: ByteArray) = Unit

    override fun getCoroutineScope(): CoroutineScope = scope

    override fun getTimeFilter(): SendspinTimeFilter = timeFilter

    override fun isLowMemoryMode(): Boolean = false
    override fun getClientId(): String = "test-client"
    override fun getDeviceName(): String = "Test"
    override fun getManufacturer(): String = "Test"
    override fun getSoftwareVersion(): String = "0.0.0"
    override fun getSupportedFormats(): List<MessageBuilder.FormatEntry> = emptyList()

    override fun onHandshakeComplete(serverName: String, serverId: String) = Unit
    override fun onMetadataUpdate(metadata: TrackMetadata) = Unit
    override fun onPlaybackStateChanged(state: String) = Unit
    override fun onVolumeCommand(volume: Int) = Unit
    override fun onMuteCommand(muted: Boolean) = Unit
    override fun onGroupUpdate(info: GroupInfo) = Unit
    override fun onStreamStart(config: StreamConfig) = Unit
    override fun onStreamClear() = Unit
    override fun onStreamEnd() = Unit
    override fun onAudioChunk(timestampMicros: Long, audioData: ByteArray) = Unit
    override fun onArtwork(channel: Int, payload: ByteArray) = Unit
    override fun onSyncOffsetApplied(offsetMs: Double, source: String) = Unit
    override fun onSyncMuteChanged(muted: Boolean) = Unit
}
