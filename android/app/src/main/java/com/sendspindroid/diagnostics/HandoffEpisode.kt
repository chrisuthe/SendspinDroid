package com.sendspindroid.diagnostics

/**
 * One network-handoff "episode": a connection drop + the reconnect attempts that
 * followed, and whether audio recovered. This is the field instrument for the
 * "drive away from Wi-Fi" scenario (#2). All fields are categorical/timing -- no
 * addresses or names -- so an episode is safe to include in a (redacted) report
 * and, later, in opt-in telemetry.
 *
 * See docs/superpowers/specs/2026-06-16-connection-telemetry-design.md.
 */
data class HandoffEpisode(
    val startTs: Long,
    val fromTransport: String,           // TransportType at episode start (WIFI/CELLULAR/...)
    val toTransport: String,             // TransportType at close
    val wasPlaying: Boolean,
    val configuredMethods: List<String>, // the server's method types (LOCAL/REMOTE/PROXY) -- the denominator
    val attempts: List<Attempt>,
    val outcome: Outcome,
    val recoveredMethod: String?,        // method that succeeded, if RECOVERED
    val recoveryMs: Long?,               // start -> recovered, if RECOVERED
) {
    data class Attempt(val method: String?)

    enum class Outcome { RECOVERED, EXHAUSTED, ABANDONED }

    /** One-line, non-sensitive summary for the diagnostic snapshot / report. */
    fun summarize(): String {
        val methods = attempts.joinToString(">") { it.method ?: "?" }
        val recovery = recoveryMs?.let { " in ${it}ms via ${recoveredMethod ?: "?"}" } ?: ""
        return "$fromTransport->$toTransport playing=$wasPlaying configured=${configuredMethods.joinToString("/").ifEmpty { "none" }} " +
            "attempts=[${methods}] -> $outcome$recovery"
    }
}
