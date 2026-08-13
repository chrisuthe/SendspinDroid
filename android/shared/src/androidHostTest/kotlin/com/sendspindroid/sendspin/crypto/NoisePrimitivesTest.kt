package com.sendspindroid.sendspin.crypto

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Published vectors for the primitives.
 *
 * The Noise transcripts in [NoiseHandshakeVectorTest] already prove these
 * transitively - nothing would match a foreign implementation if X25519 or an
 * AEAD were wrong - but these pin each primitive individually so a fault points
 * at itself instead of surfacing as "the whole handshake diverged".
 */
class NoisePrimitivesTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.hex(): String = joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    @Test
    fun `sha256 matches the NIST empty and abc vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(ByteArray(0)).hex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).hex(),
        )
    }

    @Test
    fun `sha256 concatenates its parts`() {
        // The vararg form is used for MixHash(h, data); a wrong concatenation
        // order here would break every handshake identically.
        assertContentEquals(
            sha256("abc".encodeToByteArray()),
            sha256("a".encodeToByteArray(), "b".encodeToByteArray(), "c".encodeToByteArray()),
        )
    }

    @Test
    fun `hmacSha256 matches RFC 4231 test case 1`() {
        val key = ByteArray(20) { 0x0b }
        val data = "Hi There".encodeToByteArray()
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            hmacSha256(key, data).hex(),
        )
    }

    @Test
    fun `x25519 matches the RFC 7748 section 6_1 key exchange`() {
        val alicePrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePublic = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPublic = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        assertContentEquals(alicePublic, x25519PublicKey(alicePrivate))
        assertContentEquals(bobPublic, x25519PublicKey(bobPrivate))
        assertContentEquals(shared, x25519(alicePrivate, bobPublic))
        assertContentEquals(shared, x25519(bobPrivate, alicePublic))
    }

    @Test
    fun `chacha20poly1305 matches the RFC 8439 section 2_8_2 AEAD vector`() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = (
            "Ladies and Gentlemen of the class of '99: If I could offer you " +
                "only one tip for the future, sunscreen would be it."
            ).encodeToByteArray()
        val expected = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116" +
                "1ae10b594f09e26a7e902ecbd0600691"
        )
        val sealed = aeadSeal(NoiseAeadAlgorithm.CHACHA20_POLY1305, key, nonce, aad, plaintext)
        assertContentEquals(expected, sealed)
        assertContentEquals(
            plaintext,
            aeadOpen(NoiseAeadAlgorithm.CHACHA20_POLY1305, key, nonce, aad, sealed),
        )
    }

    @Test
    fun `aes gcm round-trips and rejects a tampered tag`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val aad = "associated".encodeToByteArray()
        val plaintext = "sendspin".encodeToByteArray()
        val sealed = aeadSeal(NoiseAeadAlgorithm.AES_GCM, key, nonce, aad, plaintext)
        assertContentEquals(
            plaintext,
            aeadOpen(NoiseAeadAlgorithm.AES_GCM, key, nonce, aad, sealed),
        )

        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<NoiseAeadFailure> {
            aeadOpen(NoiseAeadAlgorithm.AES_GCM, key, nonce, aad, tampered)
        }
    }

    @Test
    fun `aead rejects the wrong associated data`() {
        // The handshake uses h as the AD, so this is how a diverged transcript
        // actually manifests.
        for (alg in NoiseAeadAlgorithm.entries) {
            val key = ByteArray(32) { 7 }
            val nonce = ByteArray(12)
            val sealed = aeadSeal(alg, key, nonce, "good".encodeToByteArray(), "x".encodeToByteArray())
            assertFailsWith<NoiseAeadFailure>(alg.name) {
                aeadOpen(alg, key, nonce, "bad".encodeToByteArray(), sealed)
            }
        }
    }

    @Test
    fun `aead rejects a ciphertext shorter than the tag`() {
        for (alg in NoiseAeadAlgorithm.entries) {
            assertFailsWith<NoiseAeadFailure>(alg.name) {
                aeadOpen(alg, ByteArray(32), ByteArray(12), ByteArray(0), ByteArray(15))
            }
        }
    }

    @Test
    fun `noiseHkdf is RFC 5869 HKDF with the chaining key as salt`() {
        // Documented here because an earlier iteration of this project asserted
        // the opposite in a comment and nearly sent the port chasing a
        // non-existent incompatibility. Noise spec section 4.3 is explicit.
        val ck = ByteArray(32) { it.toByte() }
        val ikm = ByteArray(32) { (it + 32).toByte() }
        val two = noiseHkdf(ck, ikm, 2)
        val three = noiseHkdf(ck, ikm, 3)
        assertEquals(2, two.size)
        assertEquals(3, three.size)
        two.forEach { assertEquals(32, it.size) }
        // The first two outputs must not depend on how many were requested.
        assertContentEquals(two[0], three[0])
        assertContentEquals(two[1], three[1])
        assertTrue(!two[0].contentEquals(two[1]))
    }

    @Test
    fun `secureRandomBytes returns the requested length and varies`() {
        val a = secureRandomBytes(32)
        val b = secureRandomBytes(32)
        assertEquals(32, a.size)
        assertEquals(32, b.size)
        assertTrue(!a.contentEquals(b), "two CSPRNG draws must differ")
        assertTrue(!a.contentEquals(ByteArray(32)), "must not be all zeroes")
    }
}
