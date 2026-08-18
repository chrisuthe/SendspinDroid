package com.sendspindroid.sendspin.crypto

/**
 * The client's pairing configuration.
 *
 * Shaped after the `data` payload of `management/get-pairing-config` so 3.2
 * (#228) can read and patch this object rather than inventing a second model.
 *
 * @param pairingPsk the per-device Pairing PSK. "Generated from a CSPRNG per
 *   device - never a shared default", and long-lived: "a successful pairing
 *   does not consume or rotate it". Nothing may rewrite it except a deliberate
 *   operator rotation or `management/set-pairing-config`.
 * @param pairingPskEnabled whether the method is offered. A disabled method's
 *   PSK leaves the candidate set, so a handshake naming it fails as a lookup
 *   miss, and the descriptor leaves `client/hello`.
 * @param unpairedAccessEnabled whether this client admits a server with no
 *   pairing record. Advertised as `unpaired_access.enabled`.
 */
class PairingConfig(
    pairingPsk: ByteArray,
    val pairingPskEnabled: Boolean,
    val unpairedAccessEnabled: Boolean,
) {
    init {
        require(pairingPsk.size == Psk.PSK_SIZE) {
            "a Sendspin PSK is ${Psk.PSK_SIZE} bytes, got ${pairingPsk.size}"
        }
    }

    private val secret = pairingPsk.copyOf()

    /** A copy; the internal array is never handed out. */
    val pairingPsk: ByteArray get() = secret.copyOf()

    /** `psk_id` of the Pairing PSK, whether or not the method is enabled. */
    val pairingPskId: String by lazy { PskId.derive(secret) }

    fun withEnabled(enabled: Boolean): PairingConfig =
        PairingConfig(secret, enabled, unpairedAccessEnabled)

    fun withUnpairedAccess(enabled: Boolean): PairingConfig =
        PairingConfig(secret, pairingPskEnabled, enabled)

    fun withPairingPsk(psk: ByteArray): PairingConfig =
        PairingConfig(psk, pairingPskEnabled, unpairedAccessEnabled)

    // Hand-written: a data class holding a ByteArray compares by reference.
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PairingConfig &&
                pairingPskEnabled == other.pairingPskEnabled &&
                unpairedAccessEnabled == other.unpairedAccessEnabled &&
                secret.contentEquals(other.secret)
            )

    override fun hashCode(): Int {
        var result = secret.contentHashCode()
        result = 31 * result + pairingPskEnabled.hashCode()
        result = 31 * result + unpairedAccessEnabled.hashCode()
        return result
    }

    /** Never the bytes. */
    override fun toString(): String =
        "PairingConfig(pairingPskId=$pairingPskId, enabled=$pairingPskEnabled, " +
            "unpairedAccess=$unpairedAccessEnabled)"
}
