package com.sendspindroid.sendspin.crypto

/**
 * The record and namespace semantics, with no persistence.
 *
 * All the logic lives here so it can be tested without Android; the encrypted
 * preferences implementation wraps this and adds loading and flushing.
 *
 * @param initial records restored from storage
 * @param pairingPskId the client's own Pairing PSK id once 2.2 provides one. It
 *   participates in the namespace even though it is not a record, because a
 *   `psk_id` must be unique across all three categories.
 * @param storageIsEncrypted reported through [TrustStore]; always true for a
 *   store that never touches disk.
 */
open class InMemoryTrustStore(
    initial: List<PskRecord> = emptyList(),
    private val pairingPskId: String? = null,
    override val storageIsEncrypted: Boolean = true,
) : TrustStore {

    private val records = initial.toMutableList()

    /**
     * Called after any mutation so a subclass can flush.
     *
     * The persistence layer subclasses rather than wraps: a wrapper would have
     * to redeclare all seven members just to add a write, and the one that got
     * forgotten would lose records silently.
     */
    protected open fun onChanged() {}

    override fun listRecords(): List<PskRecord> = records.toList()

    override fun findByPskId(pskId: String): PskRecord? =
        records.firstOrNull { it.pskId == pskId }

    override fun addRecord(psk: ByteArray, serverId: String?): TrustStore.AddRecordResult {
        if (psk.size != Psk.PSK_SIZE) return TrustStore.AddRecordResult.Invalid

        val pskId = PskId.derive(psk)
        if (isClaimed(pskId)) return TrustStore.AddRecordResult.AlreadyExists

        val record = PskRecord(pskId, psk, serverId, used = false)
        records += record
        onChanged()
        return TrustStore.AddRecordResult.Ok(record)
    }

    override fun removeRecord(pskId: String): Boolean {
        val removed = records.removeAll { it.pskId == pskId }
        if (removed) onChanged()
        return removed
    }

    override fun markUsed(pskId: String) {
        val index = records.indexOfFirst { it.pskId == pskId }
        if (index < 0) return
        if (records[index].used) return  // idempotent; no needless write
        records[index] = records[index].withUsed(true)
        onChanged()
    }

    override fun candidates(): List<Psk> =
        records.map { it.toPsk() } + SentinelPsk.psk

    /**
     * The write-path half of the single-namespace rule.
     *
     * [PskCandidateSet.of] enforces the same rule when the set is built; this
     * stops a colliding record being persisted in the first place, so the
     * failure surfaces as a rejected pairing rather than as a client that can
     * no longer build a candidate set at all.
     */
    private fun isClaimed(pskId: String): Boolean =
        pskId == SentinelPsk.EXPECTED_PSK_ID ||
            pskId == pairingPskId ||
            records.any { it.pskId == pskId }
}
