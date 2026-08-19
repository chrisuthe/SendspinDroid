package com.sendspindroid.sendspin.crypto

/**
 * A persisted long-term PSK record.
 *
 * `management.md#records`: "Each record holds a [Sendspin PSK]; every record
 * carries `user` [trust level]", and "Across all record operations, a record is
 * identified by its `psk_id`."
 *
 * This is [Psk] plus the two things that only matter once a record is stored:
 * it is serialisable, and it remembers whether a server has ever authenticated
 * with it ([used], reported by `management/list-records`).
 *
 * @param serverId the stored-pubkey binding (audit decision D4). Null means a
 *   shared-PSK record, which this phase never creates but 2.7 and 3.3 must not
 *   destroy if one appears.
 */
class PskRecord(
    val pskId: String,
    psk: ByteArray,
    val serverId: String?,
    val used: Boolean = false,
) {
    init {
        require(psk.size == Psk.PSK_SIZE) {
            "a Sendspin PSK is ${Psk.PSK_SIZE} bytes, got ${psk.size}"
        }
    }

    private val secret = psk.copyOf()

    /** A copy; the internal array is never handed out. */
    val psk: ByteArray get() = secret.copyOf()

    fun withUsed(used: Boolean): PskRecord = PskRecord(pskId, secret, serverId, used)

    /** Every record is a long-term PSK, so the category is not stored. */
    fun toPsk(): Psk = Psk(secret, PskCategory.LONG_TERM, serverId)

    // Hand-written rather than a data class: ByteArray equality on a data class
    // is reference identity, which would make every record comparison pass or
    // fail for the wrong reason.
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PskRecord &&
                pskId == other.pskId &&
                serverId == other.serverId &&
                used == other.used &&
                secret.contentEquals(other.secret)
            )

    override fun hashCode(): Int {
        var result = pskId.hashCode()
        result = 31 * result + (serverId?.hashCode() ?: 0)
        result = 31 * result + used.hashCode()
        result = 31 * result + secret.contentHashCode()
        return result
    }

    /** Never the bytes. */
    override fun toString(): String =
        "PskRecord(pskId=$pskId, serverId=$serverId, used=$used)"
}

/**
 * Storage for long-term PSK records, and the source of the handshake candidate
 * set.
 *
 * An interface so tests and `:conformance-client` can substitute an in-memory
 * implementation for the Android one.
 */
interface TrustStore {

    sealed interface AddRecordResult {
        data class Ok(val record: PskRecord) : AddRecordResult

        /**
         * The `psk_id` is already claimed - by a record, by the Sentinel, or by
         * the client's own Pairing PSK. Named after the spec's
         * `already_exists`, which 3.3 reports verbatim.
         */
        object AlreadyExists : AddRecordResult

        /** Not a 32-byte PSK. */
        object Invalid : AddRecordResult

        /** The record was fine; persisting it was not. */
        object StorageFailed : AddRecordResult
    }

    fun listRecords(): List<PskRecord>

    fun findByPskId(pskId: String): PskRecord?

    /**
     * Add a record, rejecting any `psk_id` already claimed in the shared
     * namespace. Never overwrites: a collision is an error, not a merge.
     */
    fun addRecord(psk: ByteArray, serverId: String?): AddRecordResult

    /** @return true if a record was removed. */
    fun removeRecord(pskId: String): Boolean

    /** Record that a server has authenticated a session with this PSK. */
    fun markUsed(pskId: String)

    /**
     * Every PSK a handshake may match: the records, the Sentinel, and (once 2.2
     * lands) the Pairing PSK.
     *
     * Feeds [PskCandidateSet.of] directly. Because [addRecord] enforces the
     * namespace on the write path, that call is not expected to fail.
     */
    fun candidates(): List<Psk>

    /**
     * Whether the backing storage is actually encrypted.
     *
     * False on devices with a broken Keystore, where the app deliberately falls
     * back to plain preferences rather than refusing to run. Surfaced rather
     * than only logged so 2.8's UI can warn instead of silently implying the
     * PSKs are protected.
     */
    val storageIsEncrypted: Boolean
}
