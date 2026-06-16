package com.sendspindroid.diagnostics

/**
 * Builds [HandoffEpisode]s from reconnect-status transitions and keeps a bounded
 * ring buffer of recent ones.
 *
 * Decoupled from the coordinator types (it takes primitives) so it's pure and
 * unit-testable; [com.sendspindroid.playback.PlaybackService] translates
 * `ReconnectStatus` + the current network/server context into these calls.
 *
 * The reconnect cycle `Idle -> Attempting(1) -> Attempting(2) -> Succeeded/Failed`
 * maps to one episode: the first Attempting opens it, each further Attempting adds
 * an attempt, and Succeeded/Failed (or a stray Idle) closes it.
 */
class HandoffEpisodeRecorder(
    private val capacity: Int = MAX_EPISODES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    enum class Phase { ATTEMPTING, SUCCEEDED, FAILED, IDLE }

    private class OpenEpisode(
        val startTs: Long,
        val fromTransport: String,
        val wasPlaying: Boolean,
        val configuredMethods: List<String>,
        val attempts: MutableList<HandoffEpisode.Attempt> = mutableListOf(),
    )

    private val ring = ArrayDeque<HandoffEpisode>()
    private var open: OpenEpisode? = null

    /** Invoked when an episode closes (used to feed opt-in telemetry upload). */
    var onEpisodeClosed: ((HandoffEpisode) -> Unit)? = null

    @Synchronized
    fun onReconnect(
        phase: Phase,
        method: String?,
        transportType: String,
        configuredMethods: List<String>,
        isPlaying: Boolean,
    ) {
        when (phase) {
            Phase.ATTEMPTING -> {
                val ep = open ?: OpenEpisode(
                    startTs = clock(),
                    fromTransport = transportType,
                    wasPlaying = isPlaying,
                    configuredMethods = configuredMethods,
                ).also { open = it }
                ep.attempts.add(HandoffEpisode.Attempt(method))
            }
            Phase.SUCCEEDED -> close(
                HandoffEpisode.Outcome.RECOVERED,
                transportType,
                recoveredMethod = open?.attempts?.lastOrNull()?.method,
            )
            Phase.FAILED -> close(HandoffEpisode.Outcome.EXHAUSTED, transportType, null)
            Phase.IDLE -> if (open != null) close(HandoffEpisode.Outcome.ABANDONED, transportType, null)
        }
    }

    private fun close(outcome: HandoffEpisode.Outcome, toTransport: String, recoveredMethod: String?) {
        val ep = open ?: return
        val episode = HandoffEpisode(
            startTs = ep.startTs,
            fromTransport = ep.fromTransport,
            toTransport = toTransport,
            wasPlaying = ep.wasPlaying,
            configuredMethods = ep.configuredMethods,
            attempts = ep.attempts.toList(),
            outcome = outcome,
            recoveredMethod = recoveredMethod,
            recoveryMs = if (outcome == HandoffEpisode.Outcome.RECOVERED) clock() - ep.startTs else null,
        )
        ring.addLast(episode)
        while (ring.size > capacity) ring.removeFirst()
        open = null
        onEpisodeClosed?.invoke(episode)
    }

    @Synchronized
    fun recent(): List<HandoffEpisode> = ring.toList()

    /** Newest-first summary block for the diagnostic snapshot / report. */
    @Synchronized
    fun summary(): String {
        if (ring.isEmpty()) return "--- Connection health ---\n(no handoff episodes recorded)"
        return buildString {
            appendLine("--- Connection health (${ring.size} recent handoff episodes) ---")
            ring.reversed().forEach { appendLine(it.summarize()) }
        }.trimEnd()
    }

    companion object {
        const val MAX_EPISODES = 50
    }
}
