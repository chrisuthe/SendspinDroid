package com.sendspindroid.sendspin.crypto

/**
 * The Sentinel PSK: a published constant, used whenever no other PSK applies -
 * that is, before any pairing record exists.
 *
 * From `connection.md#pre-shared-key`:
 *
 *     Sentinel PSK = SHA-256("sendspin-sentinel-psk-v1")
 *                  = 0x1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3
 *
 * It "provides no authentication on its own (its value is public);
 * authentication, when needed, is established later during Pairing". A
 * Sentinel-keyed session is confidential and replay-protected but says nothing
 * about *who* the peer is, which is why the spec limits what a server may
 * declare on one and why unpaired playback carries a MITM warning.
 *
 * The value is derived here rather than hard-coded so the derivation is
 * self-documenting; [EXPECTED_HEX] and [EXPECTED_PSK_ID] are the published
 * constants the tests check it against. They are deliberately not asserted in an
 * `init` block - a crashing initializer inside a crypto object is a far worse
 * failure mode than a failing test.
 */
object SentinelPsk {
    const val SEED_LABEL = "sendspin-sentinel-psk-v1"

    /** Published in the spec; asserted by the tests. */
    const val EXPECTED_HEX =
        "1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3"

    /** Published in the spec; asserted by the tests. */
    const val EXPECTED_PSK_ID = "GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo"

    /** The 32 raw bytes. */
    val bytes: ByteArray by lazy { sha256(SEED_LABEL.encodeToByteArray()) }

    /** A fresh [Psk] wrapper. */
    val psk: Psk get() = Psk(bytes, PskCategory.SENTINEL)
}
