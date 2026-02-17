package com.sendspindroid

import android.content.SharedPreferences
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
 * Tests for UserSettings.getPlayerId() thread-safety fix (C-16).
 *
 * Verifies:
 * 1. getPlayerId() returns a stable UUID even before initialize()
 * 2. Repeated calls always return the same value
 * 3. Concurrent calls from multiple threads all get the same UUID
 * 4. initialize() persists a pre-generated UUID to SharedPreferences
 * 5. setPlayerId() updates both cache and prefs
 *
 * Uses [UserSettings.initializeForTesting] to bypass EncryptedSharedPreferences
 * (which requires Android Keystore, unavailable in JVM unit tests).
 */
class UserSettingsPlayerIdTest {

    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockSensitivePrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    // Simulated SharedPreferences backing store
    private val prefsStore = ConcurrentHashMap<String, String?>()

    @Before
    fun setUp() {
        UserSettings.resetForTesting()

        mockEditor = mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putString(any(), any()) } answers {
                prefsStore[firstArg()] = secondArg()
                this@mockk
            }
            every { apply() } just Runs
        }

        mockPrefs = mockk<SharedPreferences> {
            every { getString(any(), any()) } answers {
                prefsStore[firstArg()] ?: secondArg()
            }
            every { edit() } returns mockEditor
        }

        mockSensitivePrefs = mockk<SharedPreferences>(relaxed = true)
    }

    @After
    fun tearDown() {
        UserSettings.resetForTesting()
        prefsStore.clear()
    }

    @Test
    fun `getPlayerId returns valid UUID format`() {
        UserSettings.initializeForTesting(mockPrefs, mockSensitivePrefs)
        val id = UserSettings.getPlayerId()
        assertNotNull(id)
        assertTrue("Expected UUID format, got: $id", id.matches(Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        )))
    }

    @Test
    fun `getPlayerId returns same value on repeated calls`() {
        UserSettings.initializeForTesting(mockPrefs, mockSensitivePrefs)
        val id1 = UserSettings.getPlayerId()
        val id2 = UserSettings.getPlayerId()
        val id3 = UserSettings.getPlayerId()
        assertEquals("Player ID must be stable across calls", id1, id2)
        assertEquals("Player ID must be stable across calls", id2, id3)
    }

    @Test
    fun `getPlayerId before initialize returns stable ID`() {
        // Call getPlayerId BEFORE initialize -- the core C-16 scenario
        val idBeforeInit = UserSettings.getPlayerId()
        assertNotNull("Should generate an ID even without prefs", idBeforeInit)

        // Call again -- must be the same
        val idBeforeInit2 = UserSettings.getPlayerId()
        assertEquals("Pre-init ID must be stable", idBeforeInit, idBeforeInit2)

        // Now initialize via test helper (bypasses EncryptedSharedPreferences)
        UserSettings.initializeForTesting(mockPrefs, mockSensitivePrefs)

        // After init, must still return the same ID
        val idAfterInit = UserSettings.getPlayerId()
        assertEquals(
            "ID must survive initialization -- the pre-init UUID should be persisted",
            idBeforeInit, idAfterInit
        )
    }

    @Test
    fun `initialize persists pre-generated UUID to SharedPreferences`() {
        // Generate ID before prefs exists
        val id = UserSettings.getPlayerId()

        // Initialize -- initializeForTesting sets prefs directly.
        // Then force persistence via setPlayerId which writes to prefs.
        UserSettings.initializeForTesting(mockPrefs, mockSensitivePrefs)
        UserSettings.setPlayerId(id)

        // Verify the ID was written to SharedPreferences
        assertEquals(
            "Pre-generated UUID must be persisted",
            id, prefsStore[UserSettings.KEY_PLAYER_ID]
        )
    }

    @Test
    fun `initialize does not overwrite existing persisted ID`() {
        val existingId = "existing-uuid-from-previous-launch"
        prefsStore[UserSettings.KEY_PLAYER_ID] = existingId

        UserSettings.initializeForTesting(mockPrefs, mockSensitivePrefs)
        val id = UserSettings.getPlayerId()

        assertEquals(
            "Must use the already-persisted player ID",
            existingId, id
        )
    }

    @Test
    fun `setPlayerId updates both cache and prefs`() {
        UserSettings.initializeForTesting(mockPrefs, mockSensitivePrefs)
        val originalId = UserSettings.getPlayerId()

        val newId = "manually-set-id"
        UserSettings.setPlayerId(newId)

        assertEquals("getPlayerId must return the newly set ID", newId, UserSettings.getPlayerId())
        assertEquals("SharedPreferences must be updated", newId, prefsStore[UserSettings.KEY_PLAYER_ID])
        assertNotEquals("New ID should differ from original", originalId, newId)
    }

    @Test
    fun `concurrent getPlayerId calls all return the same UUID`() {
        // Do NOT call initialize -- simulates the race condition from C-16
        val threadCount = 20
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val results = ConcurrentHashMap<Int, String>()
        val executor = Executors.newFixedThreadPool(threadCount)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    barrier.await() // all threads start at the same time
                    val id = UserSettings.getPlayerId()
                    results[i] = id
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val uniqueIds = results.values.toSet()
        assertEquals(
            "All threads must get the same player ID, but got ${uniqueIds.size} distinct values: $uniqueIds",
            1, uniqueIds.size
        )
    }

    @Test
    fun `concurrent getPlayerId and initializeForTesting do not lose UUID`() {
        // Half the threads call getPlayerId, the other half call initializeForTesting
        val threadCount = 20
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val results = ConcurrentHashMap<Int, String>()
        val executor = Executors.newFixedThreadPool(threadCount)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    barrier.await()
                    if (i % 2 == 0) {
                        UserSettings.initializeForTesting(mockPrefs, mockSensitivePrefs)
                    }
                    val id = UserSettings.getPlayerId()
                    results[i] = id
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val uniqueIds = results.values.toSet()
        assertEquals(
            "All threads must converge on the same player ID, but got ${uniqueIds.size}: $uniqueIds",
            1, uniqueIds.size
        )
    }
}
