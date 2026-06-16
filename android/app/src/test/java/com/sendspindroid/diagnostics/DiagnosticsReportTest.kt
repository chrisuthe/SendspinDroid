package com.sendspindroid.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLDecoder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsReportTest {

    @Test
    fun `githubIssueUrl targets the repo with the bug label and carries the environment`() {
        val url = DiagnosticsReport.githubIssueUrl("- App: 2.0.0-Beta12\n- Device: nubia NX809J")

        assertTrue(url.startsWith("https://github.com/chrisuthe/SendspinDroid/issues/new?"))
        assertTrue("issue is labeled bug", url.contains("labels=bug"))

        val body = URLDecoder.decode(url.substringAfter("body="), "UTF-8")
        assertTrue("environment carried in body", body.contains("- App: 2.0.0-Beta12"))
        assertTrue("template prompts for repro", body.contains("Steps to reproduce"))
        assertTrue("template asks for the diagnostics file", body.contains("attach", ignoreCase = true))
    }

    @Test
    fun `environmentBlock reports app device android and method types, never an address`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val env = DiagnosticsReport.environmentBlock(ctx)

        assertTrue(env.contains("- App:"))
        assertTrue(env.contains("- Device:"))
        assertTrue(env.contains("- Android:"))
        assertTrue(env.contains("Connection methods:"))
        // No saved servers in the test -> "none"; and never an IP-looking address.
        assertFalse("must not leak an address", Regex("""\d{1,3}(\.\d{1,3}){3}""").containsMatchIn(env))
    }
}
