package com.sendspindroid.remote

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [RemoteCertificateVerifier] DTLS certificate pinning.
 *
 * A Remote ID is `base32(certFingerprintSha256[:16])` with `2` rendered as `9`.
 * These tests synthesize a 16-byte prefix, derive the matching Remote ID, build
 * an SDP carrying a SHA-256 fingerprint with that prefix, and check the verifier
 * accepts matches and rejects everything else.
 */
class RemoteCertificateVerifierTest {

    private val base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** RFC 4648 base32 encode (no padding) — inverse of the verifier's decode. */
    private fun base32Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(base32Alphabet[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) sb.append(base32Alphabet[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    /** Remote ID for a 16-byte fingerprint prefix (base32, MA's 2->9). */
    private fun remoteIdFor(prefix: ByteArray): String =
        base32Encode(prefix).replace('2', '9')

    /** Colon-separated hex SHA-256 fingerprint (32 bytes) with the given prefix. */
    private fun fingerprintHex(prefix: ByteArray, fill: Int = 0xAB): String {
        val full = ByteArray(32) { if (it < 16) prefix[it] else fill.toByte() }
        return full.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun sdp(vararg fingerprintLines: String): String = buildString {
        appendLine("v=0")
        appendLine("o=- 0 0 IN IP4 0.0.0.0")
        appendLine("a=setup:active")
        fingerprintLines.forEach { appendLine(it) }
    }

    // 16-byte prefix. First byte 0xD0 = 11010000 -> leading base32 group 11010
    // = index 26 = '2', which becomes '9' in the Remote ID, so the substitution
    // path is always exercised.
    private val prefix = ByteArray(16) { i -> if (i == 0) 0xD0.toByte() else (i * 17 + 0x17).toByte() }

    @Test
    fun `matching fingerprint is verified`() {
        val id = remoteIdFor(prefix)
        val result = RemoteCertificateVerifier.verify(id, sdp("a=fingerprint:sha-256 ${fingerprintHex(prefix)}"))
        assertTrue("expected Verified but was $result", result is RemoteCertificateVerifier.Result.Verified)
    }

    @Test
    fun `remote id uses 9 not 2 and still verifies`() {
        val id = remoteIdFor(prefix)
        assertFalse("Remote ID must not contain base32 '2'", id.contains('2'))
        assertTrue(id.contains('9')) // sanity: this prefix does produce a 9
        assertTrue(
            RemoteCertificateVerifier.verify(id, sdp("a=fingerprint:sha-256 ${fingerprintHex(prefix)}"))
                is RemoteCertificateVerifier.Result.Verified
        )
    }

    @Test
    fun `mismatched fingerprint is rejected`() {
        val id = remoteIdFor(prefix)
        val tampered = prefix.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val result = RemoteCertificateVerifier.verify(id, sdp("a=fingerprint:sha-256 ${fingerprintHex(tampered)}"))
        assertTrue(result is RemoteCertificateVerifier.Result.Rejected)
        assertEquals("Fingerprint mismatch", (result as RemoteCertificateVerifier.Result.Rejected).reason)
    }

    @Test
    fun `sdp without sha256 fingerprint is rejected`() {
        val id = remoteIdFor(prefix)
        val result = RemoteCertificateVerifier.verify(id, sdp("a=setup:active"))
        assertTrue(result is RemoteCertificateVerifier.Result.Rejected)
    }

    @Test
    fun `non-sha256 fingerprint is stripped and matching sha256 verifies`() {
        val id = remoteIdFor(prefix)
        val result = RemoteCertificateVerifier.verify(
            id,
            sdp(
                "a=fingerprint:sha-1 AA:BB:CC:DD:EE:FF",
                "a=fingerprint:sha-256 ${fingerprintHex(prefix)}"
            )
        )
        assertTrue(result is RemoteCertificateVerifier.Result.Verified)
        val sanitized = (result as RemoteCertificateVerifier.Result.Verified).sanitizedSdp
        assertFalse("sha-1 fingerprint must be stripped", sanitized.contains("sha-1"))
        assertTrue(sanitized.contains("sha-256"))
    }

    @Test
    fun `any mismatched sha256 fingerprint rejects the whole sdp`() {
        val id = remoteIdFor(prefix)
        val other = prefix.copyOf().also { it[15] = (it[15] + 1).toByte() }
        val result = RemoteCertificateVerifier.verify(
            id,
            sdp(
                "a=fingerprint:sha-256 ${fingerprintHex(prefix)}",
                "a=fingerprint:sha-256 ${fingerprintHex(other)}"
            )
        )
        assertTrue(result is RemoteCertificateVerifier.Result.Rejected)
    }

    @Test
    fun `empty or null sdp is rejected`() {
        val id = remoteIdFor(prefix)
        assertTrue(RemoteCertificateVerifier.verify(id, null) is RemoteCertificateVerifier.Result.Rejected)
        assertTrue(RemoteCertificateVerifier.verify(id, "") is RemoteCertificateVerifier.Result.Rejected)
    }

    @Test
    fun `malformed remote id is rejected`() {
        // Lowercase 'l'/'0'/'1'/'8' are not in the base32 alphabet -> decode fails.
        assertTrue(
            RemoteCertificateVerifier.verify("0118", sdp("a=fingerprint:sha-256 ${fingerprintHex(prefix)}"))
                is RemoteCertificateVerifier.Result.Rejected
        )
    }

    @Test
    fun `case-insensitive fingerprint hex still matches`() {
        val id = remoteIdFor(prefix)
        val lower = fingerprintHex(prefix).lowercase()
        assertTrue(
            RemoteCertificateVerifier.verify(id, sdp("a=fingerprint:sha-256 $lower"))
                is RemoteCertificateVerifier.Result.Verified
        )
    }
}
