package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.Psk

/**
 * The pairing token codec.
 *
 * `pairing.md#pairing-token`: "The two are distributed together as a **pairing
 * token**: a single case-insensitive ASCII string the operator transfers out of
 * band (copy/paste, QR scan) into the server to begin the Pairing PSK Flow."
 *
 * Layout: `SP:` + version `0` + base32(client_key || pairing_psk), unpadded,
 * with every `2` rewritten as `9`. 4 + 103 = 107 characters, drawn only from
 * `0-9`, `A-Z` and `:` so the whole thing fits the QR alphanumeric set and
 * survives being read aloud or retyped.
 *
 * The `2`->`9` substitution is not cosmetic: base32's alphabet includes `2`,
 * but the QR alphanumeric mode's does not overlap it the way this needs, so the
 * spec rewrites it. The server applies the inverse before decoding.
 *
 * **Render this ungrouped.** Confirmed against a live Music Assistant in #191:
 * its decoder trims only *surrounding* whitespace, so any interior separator
 * added for legibility makes the token fail with an opaque "not valid" error
 * that names nothing about separators.
 */
object PairingToken {

    private const val PREFIX = "SP:"
    private const val VERSION = '0'
    private const val LENGTH = 107

    /** RFC 4648 base32, uppercase, no padding. */
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    class Decoded(clientKey: ByteArray, pairingPsk: ByteArray) {
        private val key = clientKey.copyOf()
        private val psk = pairingPsk.copyOf()
        val clientKey: ByteArray get() = key.copyOf()
        val pairingPsk: ByteArray get() = psk.copyOf()
    }

    fun encode(clientKey: ByteArray, pairingPsk: ByteArray): String {
        require(clientKey.size == 32) { "client_key is 32 bytes, got ${clientKey.size}" }
        require(pairingPsk.size == Psk.PSK_SIZE) {
            "a Sendspin PSK is ${Psk.PSK_SIZE} bytes, got ${pairingPsk.size}"
        }
        val body = base32(clientKey + pairingPsk).replace('2', '9')
        return "$PREFIX$VERSION$body"
    }

    /**
     * @return null for anything malformed. Never throws: the input is whatever
     *   an operator pasted.
     */
    fun decode(token: String): Decoded? {
        // Exactly what the server does: trim the ends, uppercase, drop the
        // optional prefix. Notably NOT stripping interior whitespace, because
        // the server does not either - and a client that accepted what the
        // server rejects would send people chasing a phantom difference.
        val normalized = token.trim().uppercase().removePrefix(PREFIX)
        if (normalized.length < 2 || normalized[0] != VERSION) return null

        val bytes = unbase32(normalized.substring(1).replace('9', '2')) ?: return null
        if (bytes.size != 64) return null
        return Decoded(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64))
    }

    private fun base32(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    private fun unbase32(text: String): ByteArray? {
        val out = ArrayList<Byte>(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in text) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.add(((buffer shr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
