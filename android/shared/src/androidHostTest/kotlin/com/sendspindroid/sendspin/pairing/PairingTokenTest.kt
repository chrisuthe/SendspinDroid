package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.Psk
import org.junit.Assert.*
import org.junit.Test

/**
 * The pairing token: the client's `client_key` and Pairing PSK, in one string
 * an operator can transfer out of band.
 *
 * `pairing.md#pairing-token`: "A version-0 token is 107 characters drawn only
 * from the QR code alphanumeric set (`0-9`, `A-Z`, `:`), so it renders as a
 * compact QR code and survives manual transcription."
 *
 * Confirmed against a live Music Assistant in #191: its field accepts the
 * token with or without the `SP:` prefix and in either case, uppercases it, and
 * **rejects any interior separator**. So this must render ungrouped - grouping
 * it for legibility produces an opaque "not valid" error with nothing pointing
 * at the separator as the cause.
 */
class PairingTokenTest {

    private val clientKey = ByteArray(32) { it.toByte() }
    private val pairingPsk = ByteArray(32) { (it + 0x40).toByte() }

    @Test
    fun aTokenIs107CharactersOfTheQrAlphanumericSet() {
        val token = PairingToken.encode(clientKey, pairingPsk)
        assertEquals(107, token.length)
        assertTrue(
            "token must use only 0-9, A-Z and ':' - got $token",
            token.all { it in '0'..'9' || it in 'A'..'Z' || it == ':' },
        )
    }

    @Test
    fun aTokenStartsWithTheVersionZeroPrefix() {
        assertTrue(PairingToken.encode(clientKey, pairingPsk).startsWith("SP:0"))
    }

    @Test
    fun theBodyNeverContainsATwo() {
        // The `2`->`9` substitution exists so the alphabet stays inside the QR
        // alphanumeric set; a stray '2' means the substitution was skipped and
        // the server's inverse replace would corrupt the decode.
        val body = PairingToken.encode(clientKey, pairingPsk).removePrefix("SP:0")
        assertFalse("body must not contain '2': $body", body.contains('2'))
    }

    @Test
    fun encodeAndDecodeRoundTrip() {
        val token = PairingToken.encode(clientKey, pairingPsk)
        val decoded = PairingToken.decode(token)
        assertNotNull(decoded)
        assertArrayEquals(clientKey, decoded!!.clientKey)
        assertArrayEquals(pairingPsk, decoded.pairingPsk)
    }

    @Test
    fun decodeAcceptsWhatMusicAssistantAccepts() {
        // Verified against a running instance in #191.
        val token = PairingToken.encode(clientKey, pairingPsk)
        assertNotNull("lowercase must decode", PairingToken.decode(token.lowercase()))
        assertNotNull("a missing SP: prefix must decode", PairingToken.decode(token.removePrefix("SP:")))
        assertNotNull("surrounding whitespace must decode", PairingToken.decode("  $token  "))
    }

    @Test
    fun decodeRejectsWhatMusicAssistantRejects() {
        // The finding that constrains rendering: interior separators are fatal.
        val token = PairingToken.encode(clientKey, pairingPsk)
        val grouped = token.chunked(8).joinToString("-")
        assertNull("an interior hyphen must be rejected", PairingToken.decode(grouped))
        assertNull("an interior space must be rejected", PairingToken.decode(token.chunked(8).joinToString(" ")))
    }

    @Test
    fun decodeRejectsMalformedInputRatherThanThrowing() {
        assertNull(PairingToken.decode(""))
        assertNull(PairingToken.decode("not a token"))
        assertNull(PairingToken.decode("SP:9" + "A".repeat(103)))  // unknown version
        assertNull(PairingToken.decode("SP:0" + "A".repeat(10)))   // too short
    }

    @Test
    fun requiresBothHalvesToBeTheRightLength() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingToken.encode(ByteArray(16), pairingPsk)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingToken.encode(clientKey, ByteArray(Psk.PSK_SIZE - 1))
        }
    }
}
