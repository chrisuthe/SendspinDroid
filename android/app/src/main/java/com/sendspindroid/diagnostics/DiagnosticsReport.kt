package com.sendspindroid.diagnostics

import android.content.Context
import android.os.Build
import com.sendspindroid.UnifiedServerRepository
import java.net.URLEncoder

/**
 * Builds the non-sensitive parts of a bug report: the environment block and a
 * pre-filled GitHub "new issue" URL. The redacted logs + diagnostic snapshot
 * travel as an attached file (see [DiagnosticsExport.reportIntent]); GitHub URLs
 * can't carry attachments, so the URL only carries the environment + a template.
 */
object DiagnosticsReport {

    private const val REPO = "chrisuthe/SendspinDroid"

    /**
     * Non-sensitive environment: app version, device, Android, and the configured
     * connection method *types* (LOCAL/REMOTE/PROXY) -- never addresses or names.
     */
    fun environmentBlock(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val methods = UnifiedServerRepository.savedServers.value
            .flatMap { it.configuredMethods }
            .map { it.name }
            .distinct()
            .ifEmpty { listOf("none") }
            .joinToString(", ")
        return buildString {
            appendLine("- App: $version")
            appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append("- Connection methods: $methods")
        }
    }

    /** Pre-filled GitHub new-issue URL carrying the [environment] block + a template. */
    fun githubIssueUrl(environment: String): String {
        val body = """
            **Describe the problem**


            **Steps to reproduce**
            1.
            2.

            **Environment**
            $environment

            **Diagnostics**
            Please attach the diagnostics file shared from the app.
        """.trimIndent()
        val title = enc("[Bug] ")
        val encodedBody = enc(body)
        return "https://github.com/$REPO/issues/new?labels=bug&title=$title&body=$encodedBody"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
