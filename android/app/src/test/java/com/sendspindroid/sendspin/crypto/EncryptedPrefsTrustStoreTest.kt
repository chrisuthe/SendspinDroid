package com.sendspindroid.sendspin.crypto

import android.content.SharedPreferences
import com.sendspindroid.UserSettings
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistence for the trust store.
 *
 * The case that matters is survival across a process: a record written during
 * pairing but lost on restart leaves the server holding a credential the client
 * has forgotten, and the only symptom is an unexplained `unauthorized` on the
 * next connect. Rebuilding the store over the same backing map is the closest
 * a unit test gets to a reboot.
 */
class EncryptedPrefsTrustStoreTest {

    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockSensitivePrefs: SharedPreferences
    private val sensitiveStore = ConcurrentHashMap<String, String?>()

    private fun psk(fill: Byte) = ByteArray(Psk.PSK_SIZE) { fill }

    @Before
    fun setUp() {
        UserSettings.resetForTesting()

        val sensitiveEditor = mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putString(any(), any()) } answers {
                sensitiveStore[firstArg()] = secondArg()
                this@mockk
            }
            // The store must use commit(), not apply(): an async write losing a
            // race with process death loses the record.
            every { commit() } returns true
        }
        mockSensitivePrefs = mockk<SharedPreferences> {
            every { getString(any(), any()) } answers {
                sensitiveStore[firstArg()] ?: secondArg()
            }
            every { edit() } returns sensitiveEditor
        }
        mockPrefs = mockk<SharedPreferences>(relaxed = true)
    }

    @After
    fun tearDown() {
        UserSettings.resetForTesting()
        sensitiveStore.clear()
    }

    @Test
    fun recordsSurviveRebuildingTheStore() {
        UserSettings.initializeForTesting(mockPrefs, mockSensitivePrefs, encrypted = true)

        val first = EncryptedPrefsTrustStore()
        first.addRecord(psk(1), serverId = "server-a")
        first.addRecord(psk(2), serverId = null)

        // A brand-new store object over the same backing prefs: the reboot proxy.
        val second = EncryptedPrefsTrustStore()
        val records = second.listRecords().sortedBy { it.pskId }

        assertEquals(2, records.size)
        val a = second.findByPskId(PskId.derive(psk(1)))
        assertNotNull(a)
        assertEquals("server-a", a!!.serverId)
        assertArrayEquals(psk(1), a.psk)

        val shared = second.findByPskId(PskId.derive(psk(2)))
        assertNotNull(shared)
        assertNull("a shared-PSK record has no server binding", shared!!.serverId)
    }

    @Test
    fun markUsedSurvivesRebuildingTheStore() {
        UserSettings.initializeForTesting(mockPrefs, mockSensitivePrefs, encrypted = true)
        val id = PskId.derive(psk(1))

        val first = EncryptedPrefsTrustStore()
        first.addRecord(psk(1), serverId = "server-a")
        first.markUsed(id)

        assertTrue(EncryptedPrefsTrustStore().findByPskId(id)!!.used)
    }

    @Test
    fun removeRecordSurvivesRebuildingTheStore() {
        UserSettings.initializeForTesting(mockPrefs, mockSensitivePrefs, encrypted = true)
        val id = PskId.derive(psk(1))

        val first = EncryptedPrefsTrustStore()
        first.addRecord(psk(1), serverId = "server-a")
        assertTrue(first.removeRecord(id))

        assertNull(EncryptedPrefsTrustStore().findByPskId(id))
    }

    @Test
    fun theNamespaceRuleStillAppliesAfterReload() {
        // The collision check must consider records restored from storage, not
        // only those added in this process.
        UserSettings.initializeForTesting(mockPrefs, mockSensitivePrefs, encrypted = true)
        EncryptedPrefsTrustStore().addRecord(psk(1), serverId = "server-a")

        val result = EncryptedPrefsTrustStore().addRecord(psk(1), serverId = "server-b")
        assertTrue(result is TrustStore.AddRecordResult.AlreadyExists)
    }

    @Test
    fun thereIsExactlyOneTrustStorePerProcess() {
        // Two instances over the same prefs would each hold their own record
        // list, and a write through one would be invisible to the other until
        // it happened to be rebuilt - a namespace check against a stale view.
        UserSettings.initializeForTesting(mockPrefs, mockSensitivePrefs, encrypted = true)
        assertSame(UserSettings.getOrCreateTrustStore(), UserSettings.getOrCreateTrustStore())
    }

    @Test
    fun aBrokenKeystoreStillStoresButReportsItselfUnencrypted() {
        // The app deliberately falls back to plain prefs rather than refusing to
        // run. That tradeoff has to be visible, not merely logged.
        UserSettings.initializeForTesting(mockPrefs, mockSensitivePrefs, encrypted = false)

        val store = EncryptedPrefsTrustStore()
        assertFalse(store.storageIsEncrypted)
        assertTrue(store.addRecord(psk(1), "server-a") is TrustStore.AddRecordResult.Ok)
        assertEquals(1, EncryptedPrefsTrustStore().listRecords().size)
    }
}
