package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.PskRecord
import com.sendspindroid.sendspin.crypto.TrustStore
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `server/unpair`.
 *
 * `messaging.md#server--client-serverunpair`: "Sent by a paired server to drop
 * its own pairing record from the client. Valid at any time regardless of the
 * current `activities`; does not require `'management'` in the activity set."
 *
 * Three branches that behave differently, and the middle one is a MUST NOT:
 *
 * | matched PSK                        | record       | goodbye | close |
 * |------------------------------------|--------------|---------|-------|
 * | long-term, bound to a `server_id`  | removed      | yes     | yes   |
 * | long-term, shared (no `server_id`) | **retained** | yes     | yes   |
 * | Sentinel or Pairing (trust none)   | untouched    | no      | no    |
 *
 * Treating all records alike would delete a shared PSK that may authenticate
 * other servers, none of which asked to be unpaired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerUnpairTest {

    private val unpair = """{"type":"server/unpair"}"""

    // ========== Stored-pubkey record: removed ==========

    @Test
    fun `a bound record is removed`() {
        val (handler, psk) = handlerPairedWith(serverId = "srv1")

        handler.handleTextMessageForTest(unpair)

        assertNull(handler.store.findByPskId(psk.pskId))
    }

    @Test
    fun `unpairing sends exactly one goodbye with reason unpaired`() {
        val (handler, _) = handlerPairedWith(serverId = "srv1")

        handler.handleTextMessageForTest(unpair)

        val goodbyes = handler.sent.filter { it.type() == "client/goodbye" }
        assertEquals(1, goodbyes.size)
        assertEquals("unpaired", goodbyes.single().reason())
    }

    @Test
    fun `the goodbye is sent before the connection is closed`() {
        val (handler, _) = handlerPairedWith(serverId = "srv1")

        handler.handleTextMessageForTest(unpair)

        // If the frame never lands the server applies its no-goodbye
        // heuristic, which for a playback connection means "assume restart,
        // reconnect" - the exact opposite of unpairing.
        assertEquals(listOf("send:client/goodbye", "close"), handler.events)
    }

    // ========== Shared-PSK record: retained ==========

    @Test
    fun `a shared record is NOT removed`() {
        val (handler, psk) = handlerPairedWith(serverId = null)

        handler.handleTextMessageForTest(unpair)

        assertNotNull(
            "a shared-PSK record may back other servers; removing it is a MUST NOT",
            handler.store.findByPskId(psk.pskId),
        )
    }

    @Test
    fun `a shared record still gets a goodbye and a close`() {
        val (handler, _) = handlerPairedWith(serverId = null)

        handler.handleTextMessageForTest(unpair)

        assertEquals(listOf("send:client/goodbye", "close"), handler.events)
        assertEquals("unpaired", handler.sent.single().reason())
    }

    // ========== Trust level none: ignored ==========

    @Test
    fun `a sentinel session ignores the unpair entirely`() {
        val handler = handlerMatching(Psk(ByteArray(32) { 1 }, PskCategory.SENTINEL))
        val other = handler.store.seed(Psk(ByteArray(32) { 9 }, PskCategory.LONG_TERM, "srv1"))

        handler.handleTextMessageForTest(unpair)

        assertEquals(emptyList<String>(), handler.events)
        assertNotNull(handler.store.findByPskId(other))
    }

    @Test
    fun `a pairing session ignores the unpair entirely`() {
        // The dangerous case: a prior pairing may well have left a record for
        // this very server, but THIS session was admitted by the Pairing PSK
        // and is trust_level none. Deciding the branch on "do we hold a record
        // for this server_id" rather than on the matched PSK deletes it.
        val handler = handlerMatching(Psk(ByteArray(32) { 2 }, PskCategory.PAIRING))
        val existing = handler.store.seed(Psk(ByteArray(32) { 9 }, PskCategory.LONG_TERM, "srv1"))

        handler.handleTextMessageForTest(unpair)

        assertEquals(emptyList<String>(), handler.events)
        assertNotNull(handler.store.findByPskId(existing))
    }

    @Test
    fun `an ignored unpair leaves the connection usable`() {
        val handler = handlerMatching(Psk(ByteArray(32) { 1 }, PskCategory.SENTINEL))

        handler.handleTextMessageForTest(unpair)
        handler.handleTextMessageForTest("""{"type":"server/state","payload":{}}""")

        assertFalse("the connection must continue unchanged", handler.events.contains("close"))
    }

    // ========== Valid at any time ==========

    @Test
    fun `unpair is handled before the first server hello`() {
        // The likeliest way to gate this by accident. `handshakeComplete` is
        // set by server/hello, and sendGoodbye used to return early without it,
        // so an unpair arriving straight after the Noise handshake would be
        // swallowed with no log and no goodbye.
        val (handler, psk) = handlerPairedWith(serverId = "srv1", handshakeComplete = false)

        handler.handleTextMessageForTest(unpair)

        assertNull(handler.store.findByPskId(psk.pskId))
        assertEquals(listOf("send:client/goodbye", "close"), handler.events)
    }

    // ========== Payload tolerance ==========

    @Test
    fun `unknown payload fields are ignored`() {
        val (handler, psk) = handlerPairedWith(serverId = "srv1")

        handler.handleTextMessageForTest("""{"type":"server/unpair","payload":{"future":1}}""")

        assertNull(handler.store.findByPskId(psk.pskId))
        assertEquals(listOf("send:client/goodbye", "close"), handler.events)
    }

    // ========== Idempotency ==========

    @Test
    fun `a second unpair is a no-op`() {
        val (handler, _) = handlerPairedWith(serverId = "srv1")

        handler.handleTextMessageForTest(unpair)
        handler.handleTextMessageForTest(unpair)

        assertEquals(1, handler.sent.count { it.type() == "client/goodbye" })
        assertEquals(1, handler.store.removals)
    }

    // ========== Fail closed ==========

    @Test
    fun `a store that cannot persist the removal sends no goodbye`() {
        val (handler, _) = handlerPairedWith(serverId = "srv1")
        handler.store.failRemovals = true

        handler.handleTextMessageForTest(unpair)

        // Telling the server we unpaired while the record survives leaves the
        // device authenticating with a credential the server has dropped - and
        // it looks like a working pairing until the next handshake fails.
        assertEquals(emptyList<String>(), handler.events)
    }

    @Test
    fun `the unpaired callback reports the record it acted on`() {
        val (handler, psk) = handlerPairedWith(serverId = "srv1")

        handler.handleTextMessageForTest(unpair)

        assertEquals(listOf(psk.pskId to "srv1"), handler.unpaired)
    }

    // ========== Helpers ==========

    private fun handlerPairedWith(
        serverId: String?,
        handshakeComplete: Boolean = true,
    ): Pair<UnpairTestHandler, Psk> {
        val psk = Psk(ByteArray(32) { 7 }, PskCategory.LONG_TERM, serverId)
        val handler = handlerMatching(psk, handshakeComplete)
        handler.store.seed(psk)
        return handler to psk
    }

    private fun handlerMatching(
        psk: Psk,
        handshakeComplete: Boolean = true,
    ): UnpairTestHandler {
        val handler = UnpairTestHandler(psk)
        if (handshakeComplete) handler.setHandshakeCompleteForTest()
        return handler
    }

    private fun String.type(): String? =
        Json.parseToJsonElement(this).jsonObject["type"]?.jsonPrimitive?.content

    private fun String.reason(): String? =
        Json.parseToJsonElement(this).jsonObject["payload"]
            ?.jsonObject?.get("reason")?.jsonPrimitive?.content
}

/**
 * A handler whose session matched [matched], recording what reaches the wire.
 *
 * The scope is unconfined so a coroutine launched by the handler runs to its
 * first real suspension inline - which is what makes the send/close ordering
 * assertions deterministic without a scheduler dance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnpairTestHandler(
    private val matched: Psk,
) : SendSpinProtocolHandler("UnpairTest") {

    val store = FakeTrustStore()
    val sent = mutableListOf<String>()
    val unpaired = mutableListOf<Pair<String, String?>>()

    /** Sends and the close, in the order they happened. */
    val events = mutableListOf<String>()

    private val scope = CoroutineScope(UnconfinedTestDispatcher())
    private val timeFilter = SendspinTimeFilter()

    fun setHandshakeCompleteForTest() { handshakeComplete = true }

    fun handleTextMessageForTest(text: String) = handleTextMessage(text)

    override fun matchedPsk(): Psk = matched

    override fun trustStore(): TrustStore = store

    override fun onUnpaired(pskId: String, serverId: String?) {
        unpaired.add(pskId to serverId)
    }

    override fun closeConnectionAfterFlush() {
        events.add("close")
    }

    override fun sendTextMessage(text: String) {
        sent.add(text)
        val type = Json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
        events.add("send:$type")
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

/** In-memory [TrustStore] that can count and fail removals. */
class FakeTrustStore : TrustStore {

    private val records = mutableMapOf<String, PskRecord>()

    var removals = 0
        private set

    var failRemovals = false

    /** @return the seeded record's `psk_id`. */
    fun seed(psk: Psk): String {
        records[psk.pskId] = PskRecord(psk.pskId, psk.bytes, psk.serverId)
        return psk.pskId
    }

    override fun listRecords(): List<PskRecord> = records.values.toList()

    override fun findByPskId(pskId: String): PskRecord? = records[pskId]

    override fun addRecord(psk: ByteArray, serverId: String?): TrustStore.AddRecordResult =
        throw UnsupportedOperationException("not used by these tests")

    override fun removeRecord(pskId: String): Boolean {
        if (failRemovals) throw IllegalStateException("storage unavailable")
        removals++
        return records.remove(pskId) != null
    }

    override fun markUsed(pskId: String) = Unit

    override fun candidates(): List<Psk> = records.values.map { it.toPsk() }

    override val storageIsEncrypted: Boolean get() = true
}
