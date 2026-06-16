package com.sendspindroid.logging

/**
 * Scrubs sensitive data from text before it leaves the device (log export,
 * bug-report bundles, telemetry).
 *
 * Combines two layers:
 * - **Pattern-based**: IP addresses (v4 + v6), scheme URLs (`ws`/`wss`/`http`/
 *   `https`/`ma-proxy`), JWTs, and labeled `key=value` tokens.
 * - **Literal**: caller-supplied [sensitiveTerms] -- the saved servers' names and
 *   addresses, which are not otherwise pattern-matchable (a chosen name like
 *   "Living Room" or a LAN hostname can only be removed if we know it).
 *
 * Designed to never throw and to err toward over-redaction. Token detection is
 * deliberately conservative (labeled/JWT/bearer only) to avoid mangling
 * legitimate opaque payloads in logs (e.g. base64 codec headers); see the
 * Diagnostics & Feedback spec, open question on token aggressiveness.
 */
class RedactionFilter(sensitiveTerms: Collection<String> = emptyList()) {

    // Longest first so a term isn't partially clobbered by a shorter overlapping one.
    private val literalTerms: List<String> = sensitiveTerms
        .map { it.trim() }
        .filter { it.length >= MIN_TERM_LENGTH }
        .distinct()
        .sortedByDescending { it.length }

    fun redact(input: String): String {
        var s = input
        for (term in literalTerms) s = s.replace(term, REDACTED)
        s = URL.replace(s) { "${it.groupValues[1]}://$REDACTED" }
        s = IPV6_BRACKETED.replace(s, IP)
        s = IPV6_COMPRESSED.replace(s, IP)
        s = IPV4.replace(s, IP)
        s = JWT.replace(s, TOKEN)
        s = BEARER.replace(s) { "${it.groupValues[1]} $TOKEN" }
        s = LABELED_TOKEN.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}$TOKEN" }
        return s
    }

    companion object {
        private const val MIN_TERM_LENGTH = 3
        private const val REDACTED = "<redacted>"
        private const val IP = "<ip>"
        private const val TOKEN = "<token>"

        private val URL = Regex("(wss?|https?|ma-proxy)://[^\\s\"'<>]+")
        private val IPV6_BRACKETED = Regex("\\[[0-9A-Fa-f:]+\\]")
        // Compressed IPv6 (must contain "::"); the lookbehind avoids eating a
        // preceding hextet/colon, and the trailing hex avoids matching a lone "::".
        private val IPV6_COMPRESSED = Regex("(?<![\\w:])[0-9A-Fa-f:]*::[0-9A-Fa-f:]*[0-9A-Fa-f]")
        private val IPV4 = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?\\b")
        private val JWT = Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b")
        private val BEARER = Regex("(?i)\\b(bearer)\\s+[A-Za-z0-9._-]{8,}")
        private val LABELED_TOKEN = Regex(
            "(?i)\\b(token|authtoken|auth_token|access_token|password|secret|api_key|apikey)(\\s*[=:]\\s*)[^\\s\"]+"
        )
    }
}
