package com.sendspindroid.sendspin.crypto

/**
 * The two cipher suites Sendspin defines (`connection.md#cipher-suites`).
 *
 * "Servers must support both suites. Clients must support at least one." The
 * client picks one and announces it in `client/init`; there is no negotiation.
 */
enum class NoiseCipherSuite(
    /** The value that goes in `client/init.suite`. */
    val wireName: String,
    val aead: NoiseAeadAlgorithm,
    /** How the 64-bit AEAD nonce counter is laid out. */
    val nonceIsLittleEndian: Boolean,
) {
    CHACHA_POLY("25519_ChaChaPoly_SHA256", NoiseAeadAlgorithm.CHACHA20_POLY1305, true),
    AES_GCM("25519_AESGCM_SHA256", NoiseAeadAlgorithm.AES_GCM, false);

    /** The full Noise protocol name that seeds the symmetric state. */
    val protocolName: String get() = "Noise_KKpsk2_$wireName"

    companion object {
        fun fromWireName(value: String): NoiseCipherSuite? =
            entries.firstOrNull { it.wireName == value }
    }
}
