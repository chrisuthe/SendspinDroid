package com.sendspindroid.sendspin.crypto

/**
 * Which kind of secret a PSK is.
 *
 * The category is not cosmetic: it decides what the server is allowed to do
 * once the handshake matches it. Per `messaging.md#server--client-serveractivate`,
 * a long-term Sendspin PSK admits `['pairing']` or any subset of
 * `{'playback', 'management'}`; a Pairing PSK admits only `['pairing']`; and the
 * Sentinel admits `[]`, `['pairing']`, or `['playback']` (the last only with
 * unpaired access enabled).
 *
 * The spec is explicit that the three share ONE `psk_id` namespace, "so a
 * `psk_id` must be unique across them. Two categories sharing one would make a
 * single wire `psk_id` map to two trust levels." [PskCandidateSet] enforces it.
 */
enum class PskCategory {
    /** A per-(client, server) secret established by pairing. Trust level `user`. */
    LONG_TERM,

    /** The client's own long-lived pairing secret, distributed as a pairing token. */
    PAIRING,

    /** The published constant used before any pairing record exists. */
    SENTINEL,
}

/**
 * A 32-byte pre-shared key, tagged with its category and its derived `psk_id`.
 *
 * @param serverId the stored-pubkey binding. Non-null only for [PskCategory.LONG_TERM]
 *   records created under the stored-pubkey model, where the client "verifies
 *   that the matched PSK's stored `server_id` equals the one in `server/init`"
 *   (`connection.md#pre-shared-key`). Null means no binding: the Sentinel, the
 *   Pairing PSK, and shared-PSK records.
 */
class Psk(
    bytes: ByteArray,
    val category: PskCategory,
    val serverId: String? = null,
) {
    init {
        require(bytes.size == PSK_SIZE) {
            "a Sendspin PSK is $PSK_SIZE bytes, got ${bytes.size}"
        }
        require(serverId == null || category == PskCategory.LONG_TERM) {
            "only a long-term record binds a server_id, not $category"
        }
    }

    private val secret = bytes.copyOf()

    /** A copy; the internal array is never handed out. */
    val bytes: ByteArray get() = secret.copyOf()

    /** `base64url(SHA-256("sendspin-psk-id-v1" || psk))`, 43 characters. */
    val pskId: String by lazy { PskId.derive(secret) }

    /** Category and `psk_id` only. The bytes must never reach a log. */
    override fun toString(): String = "Psk($category, pskId=$pskId)"

    companion object {
        const val PSK_SIZE = 32
    }
}
