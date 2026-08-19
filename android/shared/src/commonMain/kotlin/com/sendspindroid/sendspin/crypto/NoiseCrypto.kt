package com.sendspindroid.sendspin.crypto

/**
 * The encrypt/decrypt pair for one Noise session.
 *
 * Exists so the wire codec can hold a *replaceable* reference to its crypto.
 * An in-band re-handshake swaps both directions at a single frame boundary
 * (`connection.md#re-handshake`), so the codec cannot bind to one
 * [NoiseTransport] for its lifetime.
 *
 * Deliberately narrow: nothing here exposes keys, nonces, or the handshake
 * hash. The codec's job is framing, and the less of the session it can reach,
 * the less it can get wrong.
 */
interface NoiseCrypto {
    /** @throws NoiseHandshakeException never - encryption of our own data cannot fail the tag check. */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** @throws NoiseHandshakeException on an AEAD tag failure, replay, or reorder. */
    fun decrypt(frame: ByteArray): ByteArray
}

/** Adapts a completed handshake's transport to the codec's narrow view. */
fun NoiseTransport.asNoiseCrypto(): NoiseCrypto = object : NoiseCrypto {
    override fun encrypt(plaintext: ByteArray): ByteArray = this@asNoiseCrypto.encrypt(plaintext)
    override fun decrypt(frame: ByteArray): ByteArray = this@asNoiseCrypto.decrypt(frame)
}
