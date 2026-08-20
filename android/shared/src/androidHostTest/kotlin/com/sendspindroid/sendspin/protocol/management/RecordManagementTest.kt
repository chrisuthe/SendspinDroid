package com.sendspindroid.sendspin.protocol.management

import com.sendspindroid.sendspin.crypto.Base64Url
import com.sendspindroid.sendspin.protocol.GoodbyeReason
import com.sendspindroid.sendspin.crypto.PskId
import com.sendspindroid.sendspin.crypto.SentinelPsk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `management/list-records`, `add-record` and `remove-record`.
 *
 * Mostly CRUD, with three sharp edges:
 *
 *  - The three PSK categories share one `psk_id` namespace, so a new record can
 *    collide with the Sentinel or with our own Pairing PSK. "Two categories
 *    sharing one would make a single wire `psk_id` map to two trust levels."
 *  - The record named by `record_mode.psk_id` is pinned and cannot be removed.
 *  - Removing the requester's own record must answer *before* the goodbye that
 *    tears the session down.
 */
class RecordManagementTest {

    private val trust = PairingConfigManagementTest.FakeTrust()
    private val config = PairingConfigManagementTest.FakeConfigStore()
    private val service = ManagementService(trust, config)

    // ========== Listing ==========

    @Test
    fun aStoredPubkeyRecordReportsItsServer() {
        val entry = list().first { it.str("psk_id") == PairingConfigManagementTest.BOUND_RECORD_ID }

        assertEquals("srv1", entry.str("server_id"))
        assertEquals(false, entry["used"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun aSharedRecordOmitsServerIdRatherThanNullingIt() {
        // "present for stored-pubkey records, absent for shared-PSK records".
        // An explicit null would read as "bound to nothing" rather than "not
        // bound", and the two mean different things at handshake time.
        val entry = list().first { it.str("psk_id") == PairingConfigManagementTest.SHARED_RECORD_ID }

        assertFalse("server_id present on a shared record", entry.containsKey("server_id"))
        assertNotNull(entry["used"])
    }

    @Test
    fun listingPreservesStoreOrder() {
        val ids = list().map { it.str("psk_id") }

        assertEquals(trust.listRecords().map { it.pskId }, ids)
    }

    @Test
    fun anEmptyStoreListsAnEmptyArrayRatherThanNothing() {
        val empty = PairingConfigManagementTest.FakeTrust().apply {
            listRecords().forEach { removeRecord(it.pskId) }
        }

        val data = ManagementService(empty, config)
            .handle(ManagementRequest.ListRecords, session()).okData()

        assertNotNull("records key must exist", data["records"])
        assertEquals(0, data["records"]!!.jsonArray.size)
    }

    // ========== Adding ==========

    @Test
    fun aRecordCanBeAddedWithAndWithoutAServer() {
        // A server_id is a Curve25519 public key, so it has the same shape as
        // a psk_id - 43 base64url characters over 32 bytes.
        val serverId = Base64Url.encode(ByteArray(32) { 0x51 })
        val bound = ByteArray(32) { 0x41 }
        assertEquals(ManagementResultCode.OK, add(bound, serverId).code)
        assertEquals(serverId, trust.findByPskId(PskId.derive(bound))?.serverId)

        val shared = ByteArray(32) { 0x42 }
        assertEquals(ManagementResultCode.OK, add(shared, null).code)
        assertNull(trust.findByPskId(PskId.derive(shared))?.serverId)
    }

    @Test
    fun anAddedRecordStartsUnused() {
        val psk = ByteArray(32) { 0x43 }

        add(psk, null)

        assertEquals(false, trust.findByPskId(PskId.derive(psk))?.used)
    }

    @Test
    fun aMalformedPskIsRejected() {
        for (bad in listOf(
            "",
            "AAAA",
            Base64Url.encode(ByteArray(31)),
            Base64Url.encode(ByteArray(33)),
            Base64Url.encode(ByteArray(32)) + "=",
            "not+base64url/at+all",
        )) {
            val before = trust.listRecords().size
            val outcome = service.handle(
                ManagementRequest.AddRecord(bad, null), session()
            )
            assertEquals("psk=$bad", ManagementResultCode.INVALID, outcome.code)
            assertEquals("store changed for psk=$bad", before, trust.listRecords().size)
        }
    }

    @Test
    fun aCollisionWithAnyCategoryIsRejected() {
        // All three categories in one namespace: an existing record, the
        // published Sentinel, and our own Pairing PSK.
        val colliding = listOf(
            PairingConfigManagementTest.SHARED_RECORD_PSK,
            SentinelPsk.psk.bytes,
            PairingConfigManagementTest.PAIRING_PSK,
        )

        for (psk in colliding) {
            val before = trust.listRecords().size
            assertEquals(
                "psk_id=${PskId.derive(psk)}",
                ManagementResultCode.ALREADY_EXISTS,
                add(psk, null).code,
            )
            assertEquals(before, trust.listRecords().size)
        }
    }

    @Test
    fun aMalformedServerIdIsRejected() {
        val before = trust.listRecords().size

        val outcome = add(ByteArray(32) { 0x44 }, "not-a-key")

        assertEquals(ManagementResultCode.INVALID, outcome.code)
        assertEquals(before, trust.listRecords().size)
    }

    @Test
    fun aFailedWriteReportsStorageExhausted() {
        trust.failAdds = true

        assertEquals(ManagementResultCode.STORAGE_EXHAUSTED, add(ByteArray(32) { 0x45 }, null).code)
    }

    // ========== Removing ==========

    @Test
    fun aRecordCanBeRemoved() {
        val outcome = remove(PairingConfigManagementTest.BOUND_RECORD_ID)

        assertEquals(ManagementResultCode.OK, outcome.code)
        assertNull(trust.findByPskId(PairingConfigManagementTest.BOUND_RECORD_ID))
        assertNull("removing someone else's record must not close", outcome.closeAfterReply)
    }

    @Test
    fun removingSomethingThatIsNotARecordIsNotFound() {
        // The Sentinel and the Pairing PSK are not records, so they are
        // not_found rather than invalid.
        for (id in listOf("no-such-id", SentinelPsk.psk.pskId, PskId.derive(PairingConfigManagementTest.PAIRING_PSK))) {
            assertEquals(id, ManagementResultCode.NOT_FOUND, remove(id).code)
        }
    }

    @Test
    fun theRecordModeRecordIsPinned() {
        // "the referenced shared-PSK record cannot be removed while the
        // reference exists ... rejected as invalid."
        val outcome = remove(PairingConfigManagementTest.SHARED_RECORD_ID)

        assertEquals(ManagementResultCode.INVALID, outcome.code)
        assertNotNull(trust.findByPskId(PairingConfigManagementTest.SHARED_RECORD_ID))
    }

    @Test
    fun removingOurOwnRecordAnswersThenAsksToClose() {
        // "Removing the requester's own record closes the management session
        // with client/goodbye reason 'unauthorized' AFTER the response."
        val outcome = service.handle(
            ManagementRequest.RemoveRecord(PairingConfigManagementTest.BOUND_RECORD_ID),
            session(matchedPskId = PairingConfigManagementTest.BOUND_RECORD_ID),
        )

        assertEquals(ManagementResultCode.OK, outcome.code)
        assertEquals(GoodbyeReason.UNAUTHORIZED.wire, outcome.closeAfterReply)
    }

    // ========== Helpers ==========

    private fun list(): List<JsonObject> =
        service.handle(ManagementRequest.ListRecords, session())
            .okData()["records"]!!.jsonArray.map { it.jsonObject }

    private fun add(psk: ByteArray, serverId: String?) =
        service.handle(ManagementRequest.AddRecord(Base64Url.encode(psk), serverId), session())

    private fun remove(pskId: String) =
        service.handle(ManagementRequest.RemoveRecord(pskId), session())

    private fun session(matchedPskId: String? = null) = ManagementSessionContext(
        hasManagementActivity = true,
        pinMethodEnabled = false,
        matchedPskId = matchedPskId,
    )

    private fun ManagementOutcome.okData(): JsonObject {
        assertEquals(ManagementResultCode.OK, code)
        return requireNotNull(data)
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
}
