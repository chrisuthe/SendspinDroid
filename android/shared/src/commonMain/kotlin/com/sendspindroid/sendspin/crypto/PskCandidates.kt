package com.sendspindroid.sendspin.crypto

/**
 * Builds the set of PSKs a handshake may match.
 *
 * Deliberately a pure function of stored state. The server re-handshakes to the
 * Pairing PSK unprompted - "not only while a pairing activity is running" - so
 * this must be answerable without reference to any UI state. There is no
 * parameter here that could express "a pairing screen is open", which is the
 * safeguard: making the Pairing PSK conditional on activity would require
 * adding one on purpose.
 */
object PskCandidates {

    /**
     * @param records long-term PSKs from the trust store
     * @param config the pairing configuration; its Pairing PSK joins the set
     *   only while [PairingConfig.pairingPskEnabled] is true
     * @return records, the Sentinel, and (when enabled) the Pairing PSK.
     *   Suitable for [PskCandidateSet.of], which will not reject it: the trust
     *   store rejects a colliding record on the write path, and a Pairing PSK
     *   colliding with a record cannot be stored either.
     */
    fun build(records: List<PskRecord>, config: PairingConfig): List<Psk> = buildList {
        records.forEach { add(it.toPsk()) }
        add(SentinelPsk.psk)
        if (config.pairingPskEnabled) {
            add(Psk(config.pairingPsk, PskCategory.PAIRING))
        }
    }
}
