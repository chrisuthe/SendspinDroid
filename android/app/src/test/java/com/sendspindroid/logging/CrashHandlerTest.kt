package com.sendspindroid.logging

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashHandlerTest {

    private var original: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        AppLog.init(ctx) // creates the log writer used by recordCrash()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit()
    }

    @Test
    fun `consumePending returns null when nothing crashed`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertNull(CrashHandler.consumePending(ctx))
    }

    @Test
    fun `uncaught exception records crash, flags pending, and delegates to previous handler`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        var delegated = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated = true }
        CrashHandler.install(ctx)

        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), IllegalStateException("kaboom"))

        assertTrue("must delegate to the previously-installed handler", delegated)

        val logged = AppLog.writer!!.currentFiles().joinToString("\n") { it.readText() }
        assertTrue("crash marker present in log file", logged.contains("=== CRASH ==="))
        assertTrue(
            "exception present in log file",
            logged.contains("kaboom") || logged.contains("IllegalStateException")
        )

        val summary = CrashHandler.consumePending(ctx)
        assertNotNull("pending crash summary present", summary)
        assertTrue("summary mentions the exception", summary!!.contains("kaboom"))
        assertNull("flag cleared after consume", CrashHandler.consumePending(ctx))
    }

    @Test
    fun `install is idempotent`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        CrashHandler.install(ctx)
        val first = Thread.getDefaultUncaughtExceptionHandler()
        CrashHandler.install(ctx)
        assertSame(
            "second install must not re-wrap the handler",
            first,
            Thread.getDefaultUncaughtExceptionHandler()
        )
    }
}
