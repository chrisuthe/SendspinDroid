package com.sendspindroid.musicassistant

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests that MaSettings throws IllegalStateException when accessed before initialize() (M-05).
 *
 * MaSettings is a singleton object backed by SharedPreferences. Prior to this fix,
 * all methods silently returned null/defaults when initialize() had not been called,
 * which caused silent data loss (e.g. tokens written to nowhere).
 *
 * These tests verify:
 * 1. Every public method throws IllegalStateException before initialization
 * 2. After initialization, methods delegate to SharedPreferences correctly
 */
class MaSettingsRequirePrefsTest {

    private val prefsField = MaSettings::class.java.getDeclaredField("prefs").apply {
        isAccessible = true
    }

    /** Reset MaSettings to uninitialized state before each test. */
    @Before
    fun setUp() {
        prefsField.set(MaSettings, null)
    }

    /** Clean up after each test. */
    @After
    fun tearDown() {
        prefsField.set(MaSettings, null)
    }

    // -- Uninitialized: every method must throw --

    @Test
    fun getTokenForServer_throwsBeforeInit() {
        val ex = assertThrows(IllegalStateException::class.java) {
            MaSettings.getTokenForServer("server1")
        }
        assertTrue(ex.message!!.contains("initialize"))
    }

    @Test
    fun setTokenForServer_throwsBeforeInit() {
        assertThrows(IllegalStateException::class.java) {
            MaSettings.setTokenForServer("server1", "tok")
        }
    }

    @Test
    fun clearTokenForServer_throwsBeforeInit() {
        assertThrows(IllegalStateException::class.java) {
            MaSettings.clearTokenForServer("server1")
        }
    }

    @Test
    fun hasTokenForServer_throwsBeforeInit() {
        assertThrows(IllegalStateException::class.java) {
            MaSettings.hasTokenForServer("server1")
        }
    }

    @Test
    fun getDefaultPort_throwsBeforeInit() {
        assertThrows(IllegalStateException::class.java) {
            MaSettings.getDefaultPort()
        }
    }

    @Test
    fun setDefaultPort_throwsBeforeInit() {
        assertThrows(IllegalStateException::class.java) {
            MaSettings.setDefaultPort(9999)
        }
    }

    @Test
    fun clearAllTokens_throwsBeforeInit() {
        assertThrows(IllegalStateException::class.java) {
            MaSettings.clearAllTokens()
        }
    }

    @Test
    fun getSelectedPlayerForServer_throwsBeforeInit() {
        assertThrows(IllegalStateException::class.java) {
            MaSettings.getSelectedPlayerForServer("server1")
        }
    }

    @Test
    fun setSelectedPlayerForServer_throwsBeforeInit() {
        assertThrows(IllegalStateException::class.java) {
            MaSettings.setSelectedPlayerForServer("server1", "player1")
        }
    }

    @Test
    fun clearSelectedPlayerForServer_throwsBeforeInit() {
        assertThrows(IllegalStateException::class.java) {
            MaSettings.clearSelectedPlayerForServer("server1")
        }
    }

    // -- After initialization: methods work correctly --

    @Test
    fun getTokenForServer_returnsValueAfterInit() {
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        every { mockPrefs.getString("token_server1", null) } returns "my-token"
        prefsField.set(MaSettings, mockPrefs)

        assertEquals("my-token", MaSettings.getTokenForServer("server1"))
    }

    @Test
    fun getTokenForServer_returnsNullWhenAbsent() {
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        every { mockPrefs.getString("token_server1", null) } returns null
        prefsField.set(MaSettings, mockPrefs)

        assertNull(MaSettings.getTokenForServer("server1"))
    }

    @Test
    fun setTokenForServer_writesToPrefs() {
        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { mockEditor.putString(any(), any()) } returns mockEditor
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        every { mockPrefs.edit() } returns mockEditor
        prefsField.set(MaSettings, mockPrefs)

        MaSettings.setTokenForServer("server1", "new-token")

        verify { mockEditor.putString("token_server1", "new-token") }
        verify { mockEditor.apply() }
    }

    @Test
    fun getDefaultPort_returnsStoredValue() {
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        every { mockPrefs.getInt("default_port", 8095) } returns 9090
        prefsField.set(MaSettings, mockPrefs)

        assertEquals(9090, MaSettings.getDefaultPort())
    }

    @Test
    fun getDefaultPort_returnsDefaultWhenNotSet() {
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        every { mockPrefs.getInt("default_port", 8095) } returns 8095
        prefsField.set(MaSettings, mockPrefs)

        assertEquals(8095, MaSettings.getDefaultPort())
    }

    @Test
    fun initializeIsIdempotent() {
        // Inject a mock so we can verify initialize doesn't overwrite existing prefs
        val existingPrefs = mockk<SharedPreferences>(relaxed = true)
        prefsField.set(MaSettings, existingPrefs)

        val context = mockk<Context>(relaxed = true)
        val newPrefs = mockk<SharedPreferences>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns newPrefs

        MaSettings.initialize(context)

        // Should still be the original prefs since double-checked locking guards reassignment
        val currentPrefs = prefsField.get(MaSettings)
        assertEquals(existingPrefs, currentPrefs)
    }
}
