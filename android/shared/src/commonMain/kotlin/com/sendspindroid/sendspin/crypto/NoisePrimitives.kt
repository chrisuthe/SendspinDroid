package com.sendspindroid.sendspin.crypto

/**
 * The raw cryptographic primitives the Noise layer is built from.
 *
 * Declared `expect` because the JDK cannot supply all of them on Android:
 * there is no XDH provider for X25519, and ChaCha20-Poly1305 only arrives at
 * API 28 while this module's minSdk is 26. The actual implementation lives in
 * the `jvmShared` source set on BouncyCastle's low-level API.
 *
 * All functions are pure: no state, no provider registration, no logging. Key
 * material must never be logged, so nothing here formats or prints its inputs.
 */

/** SHA-256 of the concatenation of [parts]. */
expect fun sha256(vararg parts: ByteArray): ByteArray

/** HMAC-SHA-256 of [data] under [key]. */
expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/**
 * X25519 scalar multiplication: [privateKey] * [publicKey].
 *
 * Both inputs are 32 raw bytes. Scalar clamping is applied internally, so a
 * private key straight from a CSPRNG is valid input.
 */
expect fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray

/** X25519 base-point multiplication: derives the public key for [privateKey]. */
expect fun x25519PublicKey(privateKey: ByteArray): ByteArray

/**
 * AEAD seal. [nonce] is 12 bytes, already formatted for the suite. Returns
 * ciphertext with the 16-byte tag appended.
 */
expect fun aeadSeal(
    algorithm: NoiseAeadAlgorithm,
    key: ByteArray,
    nonce: ByteArray,
    associatedData: ByteArray,
    plaintext: ByteArray,
): ByteArray

/**
 * AEAD open. Throws [NoiseAeadFailure] on a tag mismatch - which, per
 * `connection.md#failure-handling`, the caller must treat as a handshake
 * failure that closes the socket with no application-level error message.
 */
expect fun aeadOpen(
    algorithm: NoiseAeadAlgorithm,
    key: ByteArray,
    nonce: ByteArray,
    associatedData: ByteArray,
    ciphertext: ByteArray,
): ByteArray

/** The two AEADs Sendspin defines. */
enum class NoiseAeadAlgorithm { CHACHA20_POLY1305, AES_GCM }

/** Thrown when an AEAD tag check fails. Carries no key material. */
class NoiseAeadFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** CSPRNG bytes. Backed by java.security.SecureRandom on both targets. */
expect fun secureRandomBytes(size: Int): ByteArray
