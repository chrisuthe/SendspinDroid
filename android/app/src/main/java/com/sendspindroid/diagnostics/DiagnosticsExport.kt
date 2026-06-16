package com.sendspindroid.diagnostics

import android.content.Context
import android.content.Intent
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.logging.AppLog

/**
 * Builds the shareable, **redacted** diagnostics bundle. Shared by the Settings
 * "Export logs" action and the crash-report prompt so both scrub the same
 * sensitive terms.
 */
object DiagnosticsExport {

    /**
     * Literal strings to scrub from exported logs: every known server's chosen
     * name, LAN address, and proxy URL. (Remote IDs are identifiers, not secrets,
     * so they're kept for debugging.)
     */
    fun redactionTerms(): List<String> {
        val servers = UnifiedServerRepository.savedServers.value +
            UnifiedServerRepository.discoveredServers.value
        return servers.flatMap { server ->
            listOfNotNull(server.name, server.local?.address, server.proxy?.url)
        }
    }

    /** A redacted log share intent, or null when there's nothing to share. */
    fun shareIntent(context: Context): Intent? = AppLog.shareIntent(context, redactionTerms())

    /**
     * A bug-report share intent: the redacted logs plus a diagnostic [snapshot]
     * (also redacted), with the share text carrying the non-sensitive environment
     * block and a pre-filled GitHub issue link. Returns null when there's nothing
     * to share.
     */
    fun reportIntent(context: Context, snapshot: String): Intent? {
        val intent = AppLog.shareIntent(context, redactionTerms(), prepend = snapshot) ?: return null
        val environment = DiagnosticsReport.environmentBlock(context)
        intent.putExtra(Intent.EXTRA_SUBJECT, "SendSpin bug report")
        intent.putExtra(
            Intent.EXTRA_TEXT,
            buildString {
                appendLine("SendSpin diagnostics attached (redacted).")
                appendLine()
                appendLine(environment)
                appendLine()
                appendLine("File a GitHub issue and attach the file:")
                append(DiagnosticsReport.githubIssueUrl(environment))
            }
        )
        return intent
    }
}
