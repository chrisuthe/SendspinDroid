package com.sendspindroid.sendspin.crypto

/**
 * The set of PSKs a handshake may match, and the lookup the Noise layer runs
 * against the `psk_id` in Noise message 1.
 *
 * Construction enforces the single-namespace rule from
 * `connection.md#pre-shared-key`: "The three PSK categories share one `psk_id`
 * namespace, so a `psk_id` must be unique across them. Two categories sharing
 * one would make a single wire `psk_id` map to two trust levels. Clients enforce
 * this when records are configured."
 *
 * Phase 1 builds this with exactly one member, the Sentinel. Phase 2 adds the
 * stored long-term records and, when the method is enabled, the Pairing PSK -
 * which must be present **whenever the method is enabled**, not merely while a
 * pairing screen is open, because the server re-handshakes to it unprompted.
 */
class PskCandidateSet private constructor(private val candidates: List<Psk>) {

    /** Every candidate, in lookup order. */
    val all: List<Psk> get() = candidates

    /**
     * Find the PSK a server named by `psk_id`.
     *
     * A miss is not an error at this layer - it is the caller that maps it to
     * `NoiseHandshakeException.Cause.PskLookupMiss` and closes the socket with
     * no application-level message.
     */
    fun resolve(pskId: String): Psk? = candidates.firstOrNull { it.pskId == pskId }

    /**
     * The stored-pubkey post-match check.
     *
     * "After a `psk_id` match, the client verifies that the matched PSK's stored
     * `server_id` equals the one in `server/init`; mismatch fails the
     * handshake." A candidate with no binding (the Sentinel, the Pairing PSK, a
     * shared-PSK record) passes unconditionally.
     *
     * Kept here so the rule has exactly one home rather than being re-derived at
     * each call site.
     */
    fun verifyServerBinding(matched: Psk, serverIdFromServerInit: String): Boolean =
        matched.serverId == null || matched.serverId == serverIdFromServerInit

    /** The outcome of choosing a PSK for a handshake. */
    sealed interface Selection {
        data class Matched(val candidate: Psk) : Selection

        /** No candidate claims this `psk_id`. */
        object NoMatch : Selection

        /**
         * The `psk_id` matched a record bound to a different server.
         *
         * Kept apart from [NoMatch] because both close the socket with no
         * application-level message, so a log line is the only place they can
         * ever be distinguished - and they mean very different things. A miss
         * is "I have never been told about this secret"; a mismatch is "I hold
         * this secret, but for someone else", which is what a spoofed or
         * misconfigured server looks like.
         */
        data class ServerIdMismatch(val expected: String, val actual: String) : Selection
    }

    /**
     * Choose the PSK for a handshake: [resolve] then the stored-pubkey check,
     * in one call so the two cannot drift apart or be applied in the wrong
     * order.
     */
    fun select(pskId: String, serverIdFromServerInit: String): Selection {
        val matched = resolve(pskId) ?: return Selection.NoMatch
        val bound = matched.serverId
        if (bound != null && bound != serverIdFromServerInit) {
            return Selection.ServerIdMismatch(expected = bound, actual = serverIdFromServerInit)
        }
        return Selection.Matched(matched)
    }

    companion object {
        /**
         * @return a failure if two candidates derive the same `psk_id`. That is a
         *   configuration error the client must refuse rather than resolve
         *   arbitrarily, because the wire value would then map to two different
         *   trust levels depending on iteration order.
         */
        fun of(candidates: List<Psk>): Result<PskCandidateSet> {
            val byId = mutableMapOf<String, Psk>()
            for (candidate in candidates) {
                val existing = byId[candidate.pskId]
                if (existing != null) {
                    return Result.failure(
                        IllegalArgumentException(
                            "psk_id ${candidate.pskId} is claimed by both " +
                                "${existing.category} and ${candidate.category}; " +
                                "the three categories share one namespace"
                        )
                    )
                }
                byId[candidate.pskId] = candidate
            }
            return Result.success(PskCandidateSet(candidates.toList()))
        }

        /** Phase 1's set: the Sentinel alone. */
        fun sentinelOnly(): PskCandidateSet =
            PskCandidateSet(listOf(SentinelPsk.psk))
    }
}
