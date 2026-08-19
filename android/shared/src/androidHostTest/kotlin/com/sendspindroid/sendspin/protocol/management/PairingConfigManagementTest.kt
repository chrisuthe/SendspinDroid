package com.sendspindroid.sendspin.protocol.management

import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PairingConfig
import com.sendspindroid.sendspin.crypto.PairingConfigStore
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.PskId
import com.sendspindroid.sendspin.crypto.PskRecord
import com.sendspindroid.sendspin.crypto.SentinelPsk
import com.sendspindroid.sendspin.crypto.TrustStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `management/get-pairing-config` and `management/set-pairing-config`.
 *
 * `management.md#server--client-managementset-pairing-config`: "The request
 * applies as a patch: only fields present in the payload are written, and any
 * absent field (including an absent method object) leaves the corresponding
 * stored value unchanged."
 *
 * Two traps, both of which produce a client that mostly works:
 *
 *  - **Presence is not nullness.** An absent field means "leave alone"; a field
 *    present with a null value is malformed. Collapsing the two silently
 *    rewrites configuration the server never asked to change.
 *  - **A patch is atomic.** A patch that is invalid anywhere must write
 *    nothing, or a rejected request still half-applies and the server's view of
 *    the client diverges from the client's own.
 */
class PairingConfigManagementTest {

    // ========== Reading ==========

    @Test
    fun theConfigReportsTheStoredFlags() {
        for (enabled in listOf(true, false)) {
            val service = service(pairingPskEnabled = enabled, unpairedAccess = !enabled)

            val data = service.handle(ManagementRequest.GetPairingConfig, session()).okData()

            assertEquals(enabled, data.bool("pairing_psk", "enabled"))
            assertEquals(!enabled, data.bool("unpaired_access", "enabled"))
        }
    }

    @Test
    fun unimplementedMethodsAreAbsentRatherThanDisabled() {
        // "A PIN-method object is absent if the client does not implement that
        // method." Emitting {"enabled": false} would advertise a method we
        // could be asked to turn on.
        val data = service().handle(ManagementRequest.GetPairingConfig, session()).okData()

        assertFalse("static_pin present", data.containsKey("static_pin"))
        assertFalse("dynamic_pin present", data.containsKey("dynamic_pin"))
    }

    @Test
    fun theConfigNeverCarriesTheSecret() {
        // "Configured secrets (the Pairing PSK and the static PIN) are not
        // returned; use management/set-pairing-config to rotate them."
        val service = service()

        val data = service.handle(ManagementRequest.GetPairingConfig, session()).okData()

        val serialized = data.toString()
        assertFalse(serialized.contains(base64Url(PAIRING_PSK)))
        // The id is a hash of the secret and is safe; the bytes are not.
        assertTrue(serialized.contains(SHARED_RECORD_ID))
    }

    @Test
    fun recordModeNamesTheSharedRecord() {
        val data = service().handle(ManagementRequest.GetPairingConfig, session()).okData()

        assertEquals(SHARED_RECORD_ID, data.str("record_mode", "psk_id"))
    }

    // ========== Patching: presence semantics ==========

    @Test
    fun anEmptyPatchChangesNothing() {
        val store = FakeConfigStore()
        val service = service(store = store)

        assertEquals(ManagementResultCode.OK, service.set("{}").code)
        assertEquals(0, store.writes)
    }

    @Test
    fun anAbsentFieldIsLeftAlone() {
        val store = FakeConfigStore(pairingPskEnabled = true, unpairedAccess = true)
        val service = service(store = store)

        service.set("""{"pairing_psk":{"enabled":false}}""")

        assertEquals(false, store.config.pairingPskEnabled)
        assertEquals("unpaired_access was absent and must be untouched", true, store.config.unpairedAccessEnabled)
    }

    @Test
    fun anEmptyMethodObjectSetsNothing() {
        // "only fields present in the payload are written" - an object with no
        // fields sets none, so it is a no-op rather than an error.
        val store = FakeConfigStore(pairingPskEnabled = true)
        val service = service(store = store)

        assertEquals(ManagementResultCode.OK, service.set("""{"pairing_psk":{}}""").code)
        assertEquals(true, store.config.pairingPskEnabled)
    }

    @Test
    fun aNullMethodObjectIsMalformed() {
        // Present but not an object. Treating null as "absent" would let a
        // malformed patch pass silently as a no-op.
        val store = FakeConfigStore(pairingPskEnabled = true)
        val service = service(store = store)

        assertEquals(ManagementResultCode.INVALID, service.set("""{"pairing_psk":null}""").code)
        assertEquals(0, store.writes)
    }

    @Test
    fun aNonObjectMethodValueIsMalformed() {
        val store = FakeConfigStore()
        val service = service(store = store)

        assertEquals(ManagementResultCode.INVALID, service.set("""{"unpaired_access":5}""").code)
        assertEquals(0, store.writes)
    }

    @Test
    fun aNonBooleanEnabledIsMalformed() {
        val store = FakeConfigStore()
        val service = service(store = store)

        assertEquals(
            ManagementResultCode.INVALID,
            service.set("""{"pairing_psk":{"enabled":"yes"}}""").code,
        )
        assertEquals(0, store.writes)
    }

    // ========== Patching: methods we do not implement ==========

    @Test
    fun settingAFieldOnAnUnimplementedMethodIsRejected() {
        // "Fields set on a method the client does not implement are rejected as
        // invalid."
        for (patch in listOf(
            """{"static_pin":{"enabled":true}}""",
            """{"dynamic_pin":{"min_pin_length":6}}""",
        )) {
            val store = FakeConfigStore()
            assertEquals(patch, ManagementResultCode.INVALID, service(store = store).set(patch).code)
            assertEquals(0, store.writes)
        }
    }

    @Test
    fun anEmptyUnimplementedMethodObjectSetsNoFieldsAndIsAccepted() {
        // The literal reading of the sentence above: it rejects field *sets*,
        // and an empty object sets nothing. The conservative choice.
        val store = FakeConfigStore()

        assertEquals(ManagementResultCode.OK, service(store = store).set("""{"static_pin":{}}""").code)
        assertEquals(0, store.writes)
    }

    // ========== Patching: atomicity ==========

    @Test
    fun aPatchThatFailsAnywhereWritesNothing() {
        val store = FakeConfigStore(pairingPskEnabled = true)
        val service = service(store = store)

        val outcome = service.set(
            """{"pairing_psk":{"enabled":false},"static_pin":{"enabled":true}}"""
        )

        assertEquals(ManagementResultCode.INVALID, outcome.code)
        assertEquals("the valid half must not have been applied", true, store.config.pairingPskEnabled)
        assertEquals(0, store.writes)
    }

    // ========== Rotation ==========

    @Test
    fun rotatingThePairingPskReplacesItAndLeavesRecordsAlone() {
        val store = FakeConfigStore()
        val trust = FakeTrust()
        val service = service(store = store, trust = trust)
        val fresh = ByteArray(32) { 0x5A }

        val before = trust.listRecords().map { it.pskId }

        val outcome = service.set("""{"pairing_psk":{"psk":"${base64Url(fresh)}"}}""")

        assertEquals(ManagementResultCode.OK, outcome.code)
        assertTrue(store.config.pairingPsk.contentEquals(fresh))
        // "Rotation invalidates previously distributed copies but leaves
        // established pairing records untouched."
        assertEquals(before, trust.listRecords().map { it.pskId })
    }

    @Test
    fun aPskThatIsNotThirtyTwoBytesIsMalformed() {
        for (bad in listOf("", "AAAA", base64Url(ByteArray(31)), base64Url(ByteArray(33)), "not base64url!!")) {
            val store = FakeConfigStore()
            assertEquals(
                "psk=$bad",
                ManagementResultCode.INVALID,
                service(store = store).set("""{"pairing_psk":{"psk":"$bad"}}""").code,
            )
            assertEquals(0, store.writes)
        }
    }

    @Test
    fun aPskCollidingWithAnotherCategoryIsRejected() {
        // "The three PSK categories share one psk_id namespace, so a psk_id
        // must be unique across them." A collision would make one wire psk_id
        // map to two trust levels.
        val trust = FakeTrust()
        val store = FakeConfigStore()

        val outcome = service(store = store, trust = trust)
            .set("""{"pairing_psk":{"psk":"${base64Url(SHARED_RECORD_PSK)}"}}""")

        assertEquals(ManagementResultCode.ALREADY_EXISTS, outcome.code)
        assertEquals(0, store.writes)
    }

    @Test
    fun rotatingToTheCurrentPairingPskIsANoOp() {
        // Same category, so not a cross-category collision.
        val store = FakeConfigStore()

        val outcome = service(store = store).set("""{"pairing_psk":{"psk":"${base64Url(PAIRING_PSK)}"}}""")

        assertEquals(ManagementResultCode.OK, outcome.code)
        assertTrue(store.config.pairingPsk.contentEquals(PAIRING_PSK))
    }

    @Test
    fun aFailedPersistReportsStorageExhausted() {
        val store = FakeConfigStore().apply { failWrites = true }

        val outcome = service(store = store).set("""{"pairing_psk":{"enabled":false}}""")

        assertEquals(ManagementResultCode.STORAGE_EXHAUSTED, outcome.code)
    }

    // ========== Record mode ==========

    @Test
    fun recordModeMayPointAtASharedRecord() {
        val store = FakeConfigStore()

        val outcome = service(store = store).set("""{"record_mode":{"psk_id":"$SHARED_RECORD_ID"}}""")

        assertEquals(ManagementResultCode.OK, outcome.code)
        assertEquals(SHARED_RECORD_ID, store.config.recordModePskId)
    }

    @Test
    fun recordModeMayNotPointAtAStoredPubkeyRecord() {
        // "psk_id MUST reference a shared-PSK record ... any management request
        // that would set psk_id to a missing or stored-pubkey record is
        // rejected."
        val store = FakeConfigStore()

        val outcome = service(store = store).set("""{"record_mode":{"psk_id":"$BOUND_RECORD_ID"}}""")

        assertEquals(ManagementResultCode.INVALID, outcome.code)
        assertEquals(0, store.writes)
    }

    @Test
    fun recordModeMayNotPointAtSomethingThatIsNotARecord() {
        for (id in listOf("no-such-record", SentinelPsk.psk.pskId, PskId.derive(PAIRING_PSK))) {
            val store = FakeConfigStore()
            assertEquals(
                "psk_id=$id",
                ManagementResultCode.INVALID,
                service(store = store).set("""{"record_mode":{"psk_id":"$id"}}""").code,
            )
        }
    }

    // ========== Helpers ==========

    private fun ManagementService.set(patch: String): ManagementOutcome = handle(
        ManagementRequest.SetPairingConfig(Json.parseToJsonElement(patch).jsonObject),
        session(),
    )

    private fun session() = ManagementSessionContext(
        hasManagementActivity = true,
        pinMethodEnabled = false,
    )

    private fun service(
        pairingPskEnabled: Boolean = true,
        unpairedAccess: Boolean = true,
        store: FakeConfigStore = FakeConfigStore(pairingPskEnabled, unpairedAccess),
        trust: FakeTrust = FakeTrust(),
    ) = ManagementService(trust, store)

    private fun ManagementOutcome.okData(): JsonObject {
        assertEquals(ManagementResultCode.OK, code)
        return requireNotNull(data) { "an ok get-pairing-config must carry data" }
    }

    private fun JsonObject.bool(obj: String, key: String): Boolean =
        this[obj]!!.jsonObject[key]!!.jsonPrimitive.content.toBoolean()

    private fun JsonObject.str(obj: String, key: String): String =
        this[obj]!!.jsonObject[key]!!.jsonPrimitive.content

    companion object {
        val PAIRING_PSK = ByteArray(32) { 0x11 }
        val SHARED_RECORD_PSK = ByteArray(32) { 0x22 }
        val BOUND_RECORD_PSK = ByteArray(32) { 0x33 }
        val SHARED_RECORD_ID: String = PskId.derive(SHARED_RECORD_PSK)
        val BOUND_RECORD_ID: String = PskId.derive(BOUND_RECORD_PSK)

        fun base64Url(bytes: ByteArray): String =
            com.sendspindroid.sendspin.crypto.Base64Url.encode(bytes)
    }

    /** Records: one shared-PSK (record mode's target) and one stored-pubkey. */
    class FakeTrust : TrustStore {
        private val records = mutableListOf(
            PskRecord(SHARED_RECORD_ID, SHARED_RECORD_PSK, serverId = null),
            PskRecord(BOUND_RECORD_ID, BOUND_RECORD_PSK, serverId = "srv1"),
        )

        override fun listRecords(): List<PskRecord> = records.toList()
        override fun findByPskId(pskId: String): PskRecord? = records.find { it.pskId == pskId }
        override fun addRecord(psk: ByteArray, serverId: String?) =
            throw UnsupportedOperationException()
        override fun removeRecord(pskId: String): Boolean = records.removeAll { it.pskId == pskId }
        override fun markUsed(pskId: String) = Unit
        override fun candidates(): List<Psk> = records.map { it.toPsk() }
        override val storageIsEncrypted: Boolean get() = true
    }

    class FakeConfigStore(
        pairingPskEnabled: Boolean = true,
        unpairedAccess: Boolean = true,
    ) : PairingConfigStore {

        var config = PairingConfig(PAIRING_PSK, pairingPskEnabled, unpairedAccess, SHARED_RECORD_ID)
            private set

        var writes = 0
            private set

        var failWrites = false

        override fun load(): PairingConfig = config

        override fun setEnabled(enabled: Boolean): Boolean = write(config.withEnabled(enabled))

        override fun setUnpairedAccess(enabled: Boolean): Boolean =
            write(config.withUnpairedAccess(enabled))

        override fun setRecordModePskId(pskId: String): Boolean =
            write(config.withRecordModePskId(pskId))

        override fun rotatePairingPsk(
            newPsk: ByteArray,
            claimedPskIds: Set<String>,
        ): PairingConfigStore.RotateResult {
            if (newPsk.size != Psk.PSK_SIZE) return PairingConfigStore.RotateResult.Invalid
            val id = PskId.derive(newPsk)
            if (id != config.pairingPskId && id in claimedPskIds) {
                return PairingConfigStore.RotateResult.AlreadyExists
            }
            if (failWrites) return PairingConfigStore.RotateResult.StorageFailed
            if (id != config.pairingPskId) write(config.withPairingPsk(newPsk))
            return PairingConfigStore.RotateResult.Ok
        }

        private fun write(next: PairingConfig): Boolean {
            if (failWrites) return false
            config = next
            writes++
            return true
        }
    }
}
