package com.sendspindroid.sendspin.crypto

import android.content.SharedPreferences
import com.sendspindroid.UserSettings
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * Generation and persistence of the per-device Pairing PSK.
 *
 * The two properties that matter are that it is unique per install - "generated
 * from a CSPRNG per device - never a shared default" - and that nothing
 * consumes it: "a successful pairing does not consume or rotate it ... so it
 * can pair the client with any number of servers."
 */
class AndroidPairingConfigStoreTest {

    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockSensitivePrefs: SharedPreferences
    private lateinit var sensitiveStore: ConcurrentHashMap<String, String?>

    private fun psk(fill: Byte) = ByteArray(Psk.PSK_SIZE) { fill }

    /** A mock prefs pair over an independent backing map, like a fresh install. */
    private fun freshBacking(): ConcurrentHashMap<String, String?> {
        val store = ConcurrentHashMap<String, String?>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putString(any(), any()) } answers {
                store[firstArg()] = secondArg(); this@mockk
            }
            every { putBoolean(any(), any()) } answers {
                store[firstArg()] = secondArg<Boolean>().toString(); this@mockk
            }
            every { commit() } returns true
        }
        val prefs = mockk<SharedPreferences> {
            every { getString(any(), any()) } answers { store[firstArg()] ?: secondArg() }
            every { getBoolean(any(), any()) } answers {
                store[firstArg()]?.toBoolean() ?: secondArg()
            }
            every { edit() } returns editor
        }
        UserSettings.resetForTesting()
        UserSettings.initializeForTesting(
            mockk(relaxed = true), prefs, encrypted = true,
        )
        return store
    }

    @Before
    fun setUp() {
        UserSettings.resetForTesting()
        sensitiveStore = freshBacking()
        mockPrefs = mockk(relaxed = true)
        mockSensitivePrefs = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        UserSettings.resetForTesting()
    }

    @Test
    fun firstLoadGeneratesA32BytePskAndPersistsIt() {
        val first = AndroidPairingConfigStore().load()
        assertEquals(Psk.PSK_SIZE, first.pairingPsk.size)

        // A new store object over the same backing prefs is the reboot proxy.
        val second = AndroidPairingConfigStore().load()
        assertArrayEquals(
            "the Pairing PSK must persist across restarts",
            first.pairingPsk, second.pairingPsk,
        )
    }

    @Test
    fun twoIndependentInstallsProduceDifferentPairingPsks() {
        // "never a shared default" - a fixed value would let anyone pair with
        // any SendSpinDroid install.
        val a = AndroidPairingConfigStore().load().pairingPsk
        freshBacking()
        val b = AndroidPairingConfigStore().load().pairingPsk
        assertFalse("two installs produced identical Pairing PSKs", a.contentEquals(b))
    }

    @Test
    fun concurrentFirstLoadsAgreeOnOnePsk() {
        // Two threads racing first-run generation would each mint a PSK and one
        // would win the write, leaving the other's token unpairable.
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val done = CountDownLatch(threads)
        val results = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.submit {
                barrier.await()
                results += Base64Url.encode(AndroidPairingConfigStore().load().pairingPsk)
                done.countDown()
            }
        }
        done.await()
        pool.shutdown()
        assertEquals("concurrent first loads minted more than one PSK", 1, results.toSet().size)
    }

    @Test
    fun defaultsEnablePairingAndUnpairedAccess() {
        val config = AndroidPairingConfigStore().load()
        assertTrue(config.pairingPskEnabled)
        assertTrue(config.unpairedAccessEnabled)
    }

    @Test
    fun enabledAndUnpairedAccessFlagsPersist() {
        val store = AndroidPairingConfigStore()
        store.setEnabled(false)
        store.setUnpairedAccess(false)

        val reloaded = AndroidPairingConfigStore().load()
        assertFalse(reloaded.pairingPskEnabled)
        assertFalse(reloaded.unpairedAccessEnabled)
    }

    @Test
    fun disablingTheMethodDoesNotDiscardThePsk() {
        // Re-enabling must restore the same secret, or every previously issued
        // pairing token silently stops working.
        val store = AndroidPairingConfigStore()
        val before = store.load().pairingPsk
        store.setEnabled(false)
        store.setEnabled(true)
        assertArrayEquals(before, AndroidPairingConfigStore().load().pairingPsk)
    }

    @Test
    fun rotationIsRejectedWhenTheNewPskCollidesWithAKnownPskId() {
        val store = AndroidPairingConfigStore()
        val before = store.load().pairingPsk
        val claimed = setOf(PskId.derive(psk(1)))

        val result = store.rotatePairingPsk(psk(1), claimed)
        assertTrue(result is PairingConfigStore.RotateResult.AlreadyExists)
        assertArrayEquals(
            "a rejected rotation must leave the stored PSK untouched",
            before, AndroidPairingConfigStore().load().pairingPsk,
        )
    }

    @Test
    fun rotationRejectsAPskOfTheWrongLength() {
        val store = AndroidPairingConfigStore()
        val before = store.load().pairingPsk
        assertTrue(
            store.rotatePairingPsk(ByteArray(16), emptySet())
                is PairingConfigStore.RotateResult.Invalid
        )
        assertArrayEquals(before, AndroidPairingConfigStore().load().pairingPsk)
    }

    @Test
    fun acceptedRotationPersists() {
        val store = AndroidPairingConfigStore()
        assertTrue(
            store.rotatePairingPsk(psk(5), emptySet())
                is PairingConfigStore.RotateResult.Ok
        )
        assertArrayEquals(psk(5), AndroidPairingConfigStore().load().pairingPsk)
    }

    @Test
    fun pairingDoesNotConsumeThePairingPsk() {
        // "a successful pairing does not consume or rotate it". Simulate a
        // pairing by writing a long-term record, then assert the secret is
        // byte-identical.
        val before = AndroidPairingConfigStore().load().pairingPsk
        UserSettings.getOrCreateTrustStore().addRecord(psk(3), serverId = "server-a")
        assertArrayEquals(before, AndroidPairingConfigStore().load().pairingPsk)
    }
}
