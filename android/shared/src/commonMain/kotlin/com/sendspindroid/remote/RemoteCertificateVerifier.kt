package com.sendspindroid.remote

/**
 * WebRTC DTLS certificate pinning for the Remote (WebRTC) connection.
 *
 * A Music Assistant Remote ID *is* a custom base32 encoding (RFC 4648 alphabet
 * with `2` rendered as `9`) of the first 16 bytes of the server certificate's
 * SHA-256 fingerprint. Verifying the SDP answer's `a=fingerprint` line(s)
 * against the Remote ID before accepting the connection authenticates the peer
 * end-to-end: a malicious or compromised signaling server cannot redirect the
 * client to an impostor, because it cannot produce a certificate whose
 * fingerprint matches the Remote ID.
 *
 * Mirrors the official PWA's `crypto-utils.ts#verifyAndSanitizeSdp`
 * (music-assistant/app.music-assistant.io).
 */
object RemoteCertificateVerifier {

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val FINGERPRINT_PREFIX_BYTES = 16

    sealed class Result {
        /**
         * The peer certificate matches the Remote ID. [sanitizedSdp] is the SDP
         * with any non-SHA-256 `a=fingerprint` lines removed and must be used
         * for `setRemoteDescription` (algorithm-substitution defense).
         */
        data class Verified(val sanitizedSdp: String) : Result()

        /** The SDP was rejected; [reason] explains why. Do NOT connect. */
        data class Rejected(val reason: String) : Result()
    }

    /**
     * Verify every SHA-256 fingerprint in [sdp] against [remoteId].
     *
     * @param remoteId canonical Remote ID (26-char custom base32, uppercase)
     * @param sdp the SDP answer received from the peer via signaling
     */
    fun verify(remoteId: String, sdp: String?): Result {
        if (sdp.isNullOrEmpty()) return Result.Rejected("No SDP provided")

        // Remote ID uses "9" in place of base32 "2"; reverse before decoding.
        val expected = base32DecodeOrNull(remoteId.uppercase().replace('9', '2'))
            ?: return Result.Rejected("Invalid Remote ID encoding")
        if (expected.size != FINGERPRINT_PREFIX_BYTES) {
            return Result.Rejected("Invalid Remote ID length")
        }

        // Strip any non-SHA-256 fingerprint lines so a weaker advertised
        // algorithm can't be substituted for the verified one.
        val sanitized = sdp.replace(
            Regex("""(?im)^a=fingerprint:(?!sha-256).*\r?\n?"""), ""
        )

        val matches = Regex(
            """a=fingerprint:sha-256\s+([A-Fa-f0-9:]+)""",
            RegexOption.IGNORE_CASE
        ).findAll(sanitized).toList()
        if (matches.isEmpty()) return Result.Rejected("No SHA-256 fingerprint in SDP")

        for (match in matches) {
            val hex = match.groupValues[1].replace(":", "")
            if (hex.length < FINGERPRINT_PREFIX_BYTES * 2) {
                return Result.Rejected("Malformed fingerprint")
            }
            for (i in 0 until FINGERPRINT_PREFIX_BYTES) {
                val b = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)
                    ?: return Result.Rejected("Malformed fingerprint")
                if (b != (expected[i].toInt() and 0xFF)) {
                    return Result.Rejected("Fingerprint mismatch")
                }
            }
        }
        return Result.Verified(sanitized)
    }

    /**
     * Decode an RFC 4648 base32 string (no padding) to bytes, or null on an
     * invalid character. Trailing '=' padding is tolerated.
     */
    private fun base32DecodeOrNull(input: String): ByteArray? {
        val clean = input.trimEnd('=')
        val out = ByteArray((clean.length * 5) / 8)
        var bits = 0
        var value = 0
        var idx = 0
        for (c in clean) {
            val v = BASE32_ALPHABET.indexOf(c)
            if (v < 0) return null
            value = (value shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[idx++] = ((value shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(idx)
    }
}
