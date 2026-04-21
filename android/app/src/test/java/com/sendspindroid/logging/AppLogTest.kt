package com.sendspindroid.logging

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLogTest {

    @Before
    fun setUp() {
        ShadowLog.clear()
        AppLog.setLevel(LogLevel.OFF)
    }

    @After
    fun tearDown() {
        AppLog.setLevel(LogLevel.OFF)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit()
    }

    @Test
    fun `tag prefix contract - every category starts with SendSpin dot`() {
        for (cat in LogCategory.values()) {
            assertTrue("tag for $cat must start with SendSpin.", cat.tag.startsWith("SendSpin."))
        }
    }

    @Test
    fun `level gating - OFF emits nothing`() {
        AppLog.setLevel(LogLevel.OFF)
        AppLog.Audio.v("v")
        AppLog.Audio.d("d")
        AppLog.Audio.i("i")
        AppLog.Audio.w("w")
        AppLog.Audio.e("e")
        val logs = ShadowLog.getLogs().filter { it.tag.startsWith("SendSpin.") }
        assertTrue("no SendSpin logs should be emitted at OFF, found: $logs", logs.isEmpty())
    }

    @Test
    fun `level gating - WARN permits WARN and ERROR only`() {
        AppLog.setLevel(LogLevel.WARN)
        AppLog.Audio.d("must-not-appear")
        AppLog.Audio.i("must-not-appear")
        AppLog.Audio.w("appears-warn")
        AppLog.Audio.e("appears-error")

        val msgs = ShadowLog.getLogs().filter { it.tag == "SendSpin.Audio" }.map { it.msg }
        assertFalse("debug should be gated", msgs.contains("must-not-appear"))
        assertTrue("warn should pass", msgs.contains("appears-warn"))
        assertTrue("error should pass", msgs.contains("appears-error"))
    }

    @Test
    fun `level gating - VERBOSE permits everything`() {
        AppLog.setLevel(LogLevel.VERBOSE)
        AppLog.Protocol.v("v")
        AppLog.Protocol.d("d")
        AppLog.Protocol.i("i")
        AppLog.Protocol.w("w")
        AppLog.Protocol.e("e")
        val msgs = ShadowLog.getLogs().filter { it.tag == "SendSpin.Protocol" }.map { it.msg }
        assertEquals(listOf("v", "d", "i", "w", "e"), msgs)
    }

    @Test
    fun `session start end emit INFO markers via App category`() {
        AppLog.setLevel(LogLevel.INFO)
        AppLog.session.start("MyServer", "192.168.1.10")
        AppLog.session.end()
        val appLogs = ShadowLog.getLogs().filter { it.tag == "SendSpin.App" }.map { it.msg }
        assertTrue("session start message present: $appLogs", appLogs.any { it.contains("MyServer") })
        assertTrue("session end message present: $appLogs", appLogs.any { it.contains("ended", ignoreCase = true) })
    }

    @Test
    fun `preference migration - old true maps to DEBUG and old key is removed`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit().putBoolean("debug_logging_enabled", true).commit()
        prefs.edit().remove("log_level").commit()

        AppLog.init(ctx)

        assertEquals(LogLevel.DEBUG, AppLog.level)
        assertFalse("old pref should be removed", prefs.contains("debug_logging_enabled"))
        assertEquals("DEBUG", prefs.getString("log_level", null))
    }

    @Test
    fun `preference migration - old false maps to OFF`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit().putBoolean("debug_logging_enabled", false).commit()
        prefs.edit().remove("log_level").commit()

        AppLog.init(ctx)

        assertEquals(LogLevel.OFF, AppLog.level)
        assertFalse(prefs.contains("debug_logging_enabled"))
    }

    @Test
    fun `preference migration - existing new key wins over old`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit()
            .putBoolean("debug_logging_enabled", true)
            .putString("log_level", "WARN")
            .commit()

        AppLog.init(ctx)

        assertEquals(LogLevel.WARN, AppLog.level)
        assertFalse(prefs.contains("debug_logging_enabled"))
    }

    @Test
    fun `setLevel persists to preferences`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        AppLog.init(ctx)
        AppLog.setLevel(LogLevel.INFO)
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        assertEquals("INFO", prefs.getString("log_level", null))
    }
}
