package com.sendspindroid.sendspin.crypto

import org.junit.Assert.*
import org.junit.Test

/**
 * Record semantics for the trust store (`management.md#records`).
 *
 * The store is where the single `psk_id` namespace is enforced on the write
 * path: "The three PSK categories share one `psk_id` namespace, so a `psk_id`
 * must be unique across them." [PskCandidateSet] enforces it at construction;
 * this enforces it before a record is ever created, so a colliding record
 * cannot be persisted and then poison every later handshake.
 */
class InMemoryTrustStoreTest {

    private fun psk(fill: Byte) = ByteArray(Psk.PSK_SIZE) { fill }

    @Test
    fun addRecordStoresItUnderItsDerivedPskId() {
        val store = InMemoryTrustStore()
        val bytes = psk(1)
        val result = store.addRecord(bytes, serverId = "server-a")
        assertTrue(result is TrustStore.AddRecordResult.Ok)

        val expectedId = PskId.derive(bytes)
        val found = store.findByPskId(expectedId)
        assertNotNull(found)
        assertEquals(expectedId, found!!.pskId)
        assertEquals("server-a", found.serverId)
        assertArrayEquals(bytes, found.psk)
        assertFalse("a new record has never authenticated a session", found.used)
    }

    @Test
    fun addingAPskThatIsAlreadyARecordIsRejectedAndChangesNothing() {
        val store = InMemoryTrustStore()
        store.addRecord(psk(1), serverId = "server-a")
        val result = store.addRecord(psk(1), serverId = "server-b")

        assertTrue(result is TrustStore.AddRecordResult.AlreadyExists)
        assertEquals(1, store.listRecords().size)
        // The original binding must survive: a rejected add that quietly
        // rebound the record to another server would be worse than an error.
        assertEquals("server-a", store.findByPskId(PskId.derive(psk(1)))!!.serverId)
    }

    @Test
    fun addingTheSentinelPskIsRejected() {
        // Cross-category collision: the Sentinel is not a record, but it owns
        // its psk_id in the shared namespace.
        val store = InMemoryTrustStore()
        val result = store.addRecord(SentinelPsk.bytes, serverId = "server-a")
        assertTrue(result is TrustStore.AddRecordResult.AlreadyExists)
        assertTrue(store.listRecords().isEmpty())
    }

    @Test
    fun addingTheConfiguredPairingPskIsRejected() {
        val pairing = psk(9)
        val store = InMemoryTrustStore(pairingPskId = PskId.derive(pairing))
        val result = store.addRecord(pairing, serverId = "server-a")
        assertTrue(result is TrustStore.AddRecordResult.AlreadyExists)
        assertTrue(store.listRecords().isEmpty())
    }

    @Test
    fun twoDifferentPsksMayShareAServerId() {
        // Re-pairing with the same server produces a second record; the
        // namespace rule is about psk_id, not server_id.
        val store = InMemoryTrustStore()
        assertTrue(store.addRecord(psk(1), "server-a") is TrustStore.AddRecordResult.Ok)
        assertTrue(store.addRecord(psk(2), "server-a") is TrustStore.AddRecordResult.Ok)
        assertEquals(2, store.listRecords().size)
    }

    @Test
    fun addRecordRejectsAPskOfTheWrongLength() {
        val store = InMemoryTrustStore()
        val result = store.addRecord(ByteArray(16), serverId = null)
        assertTrue(result is TrustStore.AddRecordResult.Invalid)
        assertTrue(store.listRecords().isEmpty())
    }

    @Test
    fun markUsedFlipsTheFlagAndIsIdempotent() {
        val store = InMemoryTrustStore()
        store.addRecord(psk(1), "server-a")
        val id = PskId.derive(psk(1))

        assertFalse(store.findByPskId(id)!!.used)
        store.markUsed(id)
        assertTrue(store.findByPskId(id)!!.used)
        store.markUsed(id)
        assertTrue(store.findByPskId(id)!!.used)
        assertEquals(1, store.listRecords().size)
    }

    @Test
    fun markUsedOnAnUnknownPskIdDoesNothing() {
        val store = InMemoryTrustStore()
        store.markUsed("not-a-real-psk-id")
        assertTrue(store.listRecords().isEmpty())
    }

    @Test
    fun removeRecordReportsWhetherItRemovedAnything() {
        val store = InMemoryTrustStore()
        store.addRecord(psk(1), "server-a")
        assertTrue(store.removeRecord(PskId.derive(psk(1))))
        assertFalse(store.removeRecord(PskId.derive(psk(1))))
        assertFalse(store.removeRecord("not-a-real-psk-id"))
    }

    @Test
    fun candidatesAreTheRecordsPlusTheSentinelWithCorrectCategories() {
        val store = InMemoryTrustStore()
        store.addRecord(psk(1), "server-a")
        val candidates = store.candidates()

        assertEquals(2, candidates.size)
        val sentinel = candidates.single { it.category == PskCategory.SENTINEL }
        assertEquals(SentinelPsk.EXPECTED_PSK_ID, sentinel.pskId)

        val record = candidates.single { it.category == PskCategory.LONG_TERM }
        assertEquals("server-a", record.serverId)
        assertArrayEquals(psk(1), record.bytes)
    }

    @Test
    fun candidatesFormAValidCandidateSet() {
        // The namespace guarantee the store makes must be strong enough that
        // PskCandidateSet.of never rejects what the store produces.
        val store = InMemoryTrustStore()
        store.addRecord(psk(1), "server-a")
        store.addRecord(psk(2), "server-b")
        assertTrue(PskCandidateSet.of(store.candidates()).isSuccess)
    }
}
