package com.sendspindroid.sendspin.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.AEADCipher
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519

/**
 * BouncyCastle implementations of the Noise primitives, shared by the Android
 * and JVM targets.
 *
 * Uses the low-level `org.bouncycastle.crypto.*` API rather than JCA on
 * purpose: registering a `BouncyCastleProvider` on Android collides with the
 * platform's repackaged `com.android.org.bouncycastle` and has historically
 * resolved algorithms to different implementations than intended. Going
 * straight to the engines sidesteps provider resolution entirely and keeps
 * behaviour identical on both targets.
 */

private const val TAG_BITS = 128
private const val TAG_BYTES = 16

actual fun sha256(vararg parts: ByteArray): ByteArray {
    val digest = SHA256Digest()
    for (part in parts) digest.update(part, 0, part.size)
    val out = ByteArray(digest.digestSize)
    digest.doFinal(out, 0)
    return out
}

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = HMac(SHA256Digest())
    mac.init(KeyParameter(key))
    mac.update(data, 0, data.size)
    val out = ByteArray(mac.macSize)
    mac.doFinal(out, 0)
    return out
}

actual fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
    require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
    require(publicKey.size == 32) { "X25519 public key must be 32 bytes" }
    val out = ByteArray(32)
    X25519.scalarMult(privateKey, 0, publicKey, 0, out, 0)
    return out
}

actual fun x25519PublicKey(privateKey: ByteArray): ByteArray {
    require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
    val out = ByteArray(32)
    X25519.scalarMultBase(privateKey, 0, out, 0)
    return out
}

private fun cipherFor(algorithm: NoiseAeadAlgorithm): AEADCipher = when (algorithm) {
    NoiseAeadAlgorithm.CHACHA20_POLY1305 -> ChaCha20Poly1305()
    NoiseAeadAlgorithm.AES_GCM -> GCMBlockCipher.newInstance(AESEngine.newInstance())
}

actual fun aeadSeal(
    algorithm: NoiseAeadAlgorithm,
    key: ByteArray,
    nonce: ByteArray,
    associatedData: ByteArray,
    plaintext: ByteArray,
): ByteArray {
    val cipher = cipherFor(algorithm)
    cipher.init(true, AEADParameters(KeyParameter(key), TAG_BITS, nonce, associatedData))
    val out = ByteArray(cipher.getOutputSize(plaintext.size))
    var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
    len += cipher.doFinal(out, len)
    return if (len == out.size) out else out.copyOf(len)
}

actual fun aeadOpen(
    algorithm: NoiseAeadAlgorithm,
    key: ByteArray,
    nonce: ByteArray,
    associatedData: ByteArray,
    ciphertext: ByteArray,
): ByteArray {
    if (ciphertext.size < TAG_BYTES) {
        throw NoiseAeadFailure(
            "ciphertext shorter than the AEAD tag: ${ciphertext.size} bytes"
        )
    }
    val cipher = cipherFor(algorithm)
    cipher.init(false, AEADParameters(KeyParameter(key), TAG_BITS, nonce, associatedData))
    val out = ByteArray(cipher.getOutputSize(ciphertext.size))
    return try {
        var len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        len += cipher.doFinal(out, len)
        if (len == out.size) out else out.copyOf(len)
    } catch (cause: Exception) {
        // Deliberately generic: a tag mismatch is indistinguishable from a
        // wrong key, wrong nonce, or tampered ciphertext, and saying more here
        // would be guessing. Callers add the protocol context they have.
        throw NoiseAeadFailure("AEAD authentication failed", cause)
    }
}

private val secureRandom = java.security.SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also { secureRandom.nextBytes(it) }
