package com.sendspindroid.sendspin.crypto

/**
 * Noise HKDF, CipherState and SymmetricState.
 *
 * These are internal to the crypto package: the wire layer only ever touches
 * [NoiseSession]. Nothing here logs, because every value passing through is
 * either key material or something an attacker could correlate with it.
 */

internal const val HASH_LEN = 32
internal const val DH_LEN = 32
internal const val AEAD_TAG_LEN = 16

/** A Noise transport message may not exceed 65535 bytes, tag included. */
internal const val MAX_NOISE_MESSAGE = 65535

/**
 * Noise's HKDF.
 *
 * This is plain RFC 5869 HKDF with the chaining key as the salt and empty
 * info - the Noise spec (section 4.3) says so outright. It is written out here
 * rather than pulled from a library only because [hmacSha256] is the one
 * primitive already available in common code; a library HKDF with
 * `salt = chainingKey`, `info = empty`, `length = 32 * numOutputs` is
 * equivalent and equally correct.
 *
 * The thing to get right is the parameter roles, not the construction: the
 * chaining key is the SALT, not the input key material.
 */
internal fun noiseHkdf(
    chainingKey: ByteArray,
    inputKeyMaterial: ByteArray,
    numOutputs: Int,
): List<ByteArray> {
    require(numOutputs == 2 || numOutputs == 3) { "Noise HKDF yields 2 or 3 outputs" }
    val tempKey = hmacSha256(chainingKey, inputKeyMaterial)
    val output1 = hmacSha256(tempKey, byteArrayOf(0x01))
    val output2 = hmacSha256(tempKey, output1 + byteArrayOf(0x02))
    if (numOutputs == 2) return listOf(output1, output2)
    val output3 = hmacSha256(tempKey, output2 + byteArrayOf(0x03))
    return listOf(output1, output2, output3)
}

/**
 * A Noise CipherState: a key plus a monotonic 64-bit nonce counter.
 *
 * Owning the counter here rather than exposing raw keys is deliberate. A caller
 * handed a `ByteArray` key has to manage nonces itself, and nonce reuse under a
 * repeated key is catastrophic for both AEADs.
 */
internal class NoiseCipherState(
    private val suite: NoiseCipherSuite,
    private var key: ByteArray?,
) {
    private var nonce: Long = 0

    val hasKey: Boolean get() = key != null

    /**
     * Format the 12-byte AEAD nonce: 4 zero bytes then the 64-bit counter.
     *
     * ChaChaPoly is little-endian, AES-GCM big-endian. At counter 0 both encode
     * as twelve zero bytes, so getting this wrong is invisible until the second
     * message on a connection - which is why the vector tests cover n = 1.
     */
    private fun nonceBytes(counter: Long): ByteArray {
        val out = ByteArray(12)
        for (i in 0 until 8) {
            val shift = if (suite.nonceIsLittleEndian) 8 * i else 8 * (7 - i)
            out[4 + i] = ((counter ushr shift) and 0xFF).toByte()
        }
        return out
    }

    private fun nextNonce(): ByteArray {
        // Noise reserves 2^64-1 as the "must rekey" sentinel; we never rekey, so
        // reaching it is a hard stop rather than a wrap into nonce reuse.
        if (nonce == -1L) throw NoiseHandshakeException(
            NoiseHandshakeException.Cause.NonceExhausted,
            "Noise nonce counter exhausted",
        )
        return nonceBytes(nonce).also { nonce++ }
    }

    fun encryptWithAd(associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext
        if (plaintext.size + AEAD_TAG_LEN > MAX_NOISE_MESSAGE) {
            throw NoiseHandshakeException(
                NoiseHandshakeException.Cause.MessageTooLarge,
                "Noise message would exceed $MAX_NOISE_MESSAGE bytes",
            )
        }
        return aeadSeal(suite.aead, k, nextNonce(), associatedData, plaintext)
    }

    fun decryptWithAd(associatedData: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext
        if (ciphertext.size > MAX_NOISE_MESSAGE) {
            throw NoiseHandshakeException(
                NoiseHandshakeException.Cause.MessageTooLarge,
                "Noise message exceeds $MAX_NOISE_MESSAGE bytes",
            )
        }
        // The nonce is consumed whether or not the tag verifies. Noise mandates
        // aborting on failure, so there is no retry that could desynchronise.
        val n = nextNonce()
        return try {
            aeadOpen(suite.aead, k, n, associatedData, ciphertext)
        } catch (cause: NoiseAeadFailure) {
            throw NoiseHandshakeException(
                NoiseHandshakeException.Cause.AeadFailure,
                "AEAD authentication failed",
                cause,
            )
        }
    }

    /** Best-effort wipe. The JVM may have copied the array, but not trying is worse. */
    fun destroy() {
        key?.fill(0)
        key = null
    }
}

/**
 * A Noise SymmetricState: the chaining key `ck`, the handshake hash `h`, and
 * the cipher state used for handshake payloads.
 */
internal class NoiseSymmetricState(private val suite: NoiseCipherSuite) {
    var chainingKey: ByteArray private set
    var handshakeHash: ByteArray private set
    private var cipher: NoiseCipherState

    init {
        val name = suite.protocolName.encodeToByteArray()
        // If the protocol name is 32 bytes or fewer it is used verbatim,
        // zero-padded; otherwise it is hashed. Noise_KKpsk2_25519_AESGCM_SHA256
        // is EXACTLY 32 bytes and so takes the unhashed branch, while the
        // ChaChaPoly name is 36 and is hashed. Both branches ship.
        handshakeHash = if (name.size <= HASH_LEN) name.copyOf(HASH_LEN) else sha256(name)
        chainingKey = handshakeHash.copyOf()
        cipher = NoiseCipherState(suite, null)
    }

    fun mixHash(data: ByteArray) {
        handshakeHash = sha256(handshakeHash, data)
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val out = noiseHkdf(chainingKey, inputKeyMaterial, 2)
        chainingKey = out[0]
        cipher.destroy()
        cipher = NoiseCipherState(suite, out[1])
    }

    /** The `psk` token: mixes into the chaining key AND the handshake hash. */
    fun mixKeyAndHash(inputKeyMaterial: ByteArray) {
        val out = noiseHkdf(chainingKey, inputKeyMaterial, 3)
        chainingKey = out[0]
        mixHash(out[1])
        cipher.destroy()
        cipher = NoiseCipherState(suite, out[2])
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipher.encryptWithAd(handshakeHash, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipher.decryptWithAd(handshakeHash, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    /**
     * Split into the two transport cipher states.
     *
     * The first output always encrypts initiator-to-responder traffic. We are
     * the responder, so it is our RECEIVE key and the second is our SEND key.
     * Swapping these yields a handshake that completes with an agreeing
     * handshake hash and then fails on the first transport frame.
     */
    fun split(): Pair<NoiseCipherState, NoiseCipherState> {
        val out = noiseHkdf(chainingKey, ByteArray(0), 2)
        val receive = NoiseCipherState(suite, out[0])
        val send = NoiseCipherState(suite, out[1])
        cipher.destroy()
        return receive to send
    }

    fun destroy() {
        cipher.destroy()
        chainingKey.fill(0)
    }
}
