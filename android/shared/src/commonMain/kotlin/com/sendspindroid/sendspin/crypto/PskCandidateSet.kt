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
