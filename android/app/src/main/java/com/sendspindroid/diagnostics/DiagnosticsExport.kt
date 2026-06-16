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
}
