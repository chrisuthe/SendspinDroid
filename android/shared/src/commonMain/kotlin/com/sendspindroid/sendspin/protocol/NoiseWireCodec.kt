package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.NoiseCrypto
import com.sendspindroid.sendspin.crypto.NoiseHandshakeException
import com.sendspindroid.sendspin.crypto.asNoiseCrypto
import com.sendspindroid.sendspin.crypto.NoiseTransport
import com.sendspindroid.sendspin.protocol.message.FragmentReassembler
import com.sendspindroid.sendspin.protocol.message.FragmentWriter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Encodes and decodes the encrypted binary frames that carry every Sendspin
 * message once the Noise handshake completes.
 *
 * From `messaging.md#communication`: "After the encrypted channel is
 * established, all messages are sent as WebSocket **binary** frames carrying
 * Noise transport ciphertexts", and "after AEAD decryption, the first byte is a
 * uint8 representing the message type."
 *
 * So the plaintext layout inside every frame is:
 *
 *     [type:1][body...]
 *
 * with `type == 0` meaning the body is a UTF-8 JSON message, and the audio,
 * artwork and visualizer types keeping their existing
 * `[8-byte big-endian timestamp][payload]` body layout.
 *
 * This is the only place that byte is added or stripped. Fragmentation (item
 * 1.5) plugs in here too, which is why [encodeJson] and [encode] return a list.
 */
class NoiseWireCodec(
    crypto: NoiseCrypto,
    private val reassembler: FragmentReassembler = FragmentReassembler(),
) {

    constructor(transport: NoiseTransport) : this(transport.asNoiseCrypto())

    /**
     * Replaced wholesale by an in-band re-handshake, never mutated in place.
     *
     * Volatile because the receive path reads it from the socket thread while
     * [encodeAndSwap] installs a new one from a coroutine.
     */
    @Volatile
    private var crypto: NoiseCrypto = crypto

    /**
     * Serialises sends.
     *
     * Two things depend on strict ordering: the AEAD nonce counter (frames must
     * be encrypted in the order they hit the socket, or the peer's counter
     * desynchronises) and, once 1.5 lands, the rule that only one fragmented
     * message may be in flight per direction.
     */
    private val sendMutex = Mutex()

    /** Encrypt a JSON message. */
    suspend fun encodeJson(text: String): List<ByteArray> =
        encode(SendSpinProtocol.BinaryType.JSON, text.encodeToByteArray())

    /**
     * Encrypt one typed message.
     *
     * @return the frames to put on the wire, in order. More than one only once
     *   fragmentation lands; today an oversized payload is rejected instead.
     */
    suspend fun encode(type: Int, payload: ByteArray): List<ByteArray> {
        require(type in 0..255) { "binary message type is a uint8, got $type" }
        // FragmentWriter returns one frame when the payload fits and a
        // fragment-more/.../fragment-end sequence when it does not. The mutex is
        // held across the whole list, which is what enforces "a sender must
        // finish a fragmented message before sending any other frame in that
        // direction" - a second sender interleaving here would corrupt both
        // messages and desynchronise the peer's reassembly buffer.
        val plaintexts = FragmentWriter.frames(type, payload)
        return sendMutex.withLock { plaintexts.map { crypto.encrypt(it) } }
    }

    /**
     * Encrypt one message under the CURRENT keys, then install [next].
     *
     * This is the frame boundary of an in-band re-handshake. "Noise message 2
     * is still encrypted under the pre-re-handshake transport keys; the first
     * frame each side sends after the handshake completes uses the new keys."
     *
     * Encrypting and swapping are one critical section on purpose. Doing them
     * as two calls would let another producer - the `client/time` burst loop is
     * the realistic one - slip a frame in between, encrypted under the old keys
     * but arriving after the peer has moved to the new ones. That frame fails
     * the peer's tag check, and the spec's answer to an AEAD failure in
     * transport mode is to close the socket without saying why.
     *
     * The receive direction swaps here too: the peer's next frame is already
     * under the new keys.
     */
    suspend fun encodeAndSwap(
        type: Int,
        payload: ByteArray,
        next: NoiseCrypto,
    ): List<ByteArray> {
        require(type in 0..255) { "binary message type is a uint8, got $type" }
        val plaintexts = FragmentWriter.frames(type, payload)
        return sendMutex.withLock {
            val frames = plaintexts.map { crypto.encrypt(it) }
            crypto = next
            // A half-reassembled inbound message cannot survive a key change:
            // its remaining fragments would arrive under keys that no longer
            // decrypt into the same stream.
            reassembler.reset()
            frames
        }
    }

    /**
     * Decrypt one inbound binary frame and split off its type byte.
     *
     * Never throws for peer-controlled input: an undecryptable or structurally
     * impossible frame comes back as [Decoded.ProtocolError] so the caller can
     * apply the spec's response, which is to close the socket without sending
     * anything.
     */
    fun decode(frameBytes: ByteArray): Decoded {
        if (frameBytes.size > SendSpinProtocol.NoiseFraming.MAX_TRANSPORT_MESSAGE) {
            return Decoded.ProtocolError(
                "frame of ${frameBytes.size} bytes exceeds the Noise " +
                    "${SendSpinProtocol.NoiseFraming.MAX_TRANSPORT_MESSAGE}-byte limit"
            )
        }
        val plaintext = try {
            crypto.decrypt(frameBytes)
        } catch (e: NoiseHandshakeException) {
            // Includes a replayed or reordered frame: the per-direction counter
            // means a repeat fails the tag check rather than decrypting twice.
            return Decoded.ProtocolError("AEAD failure: ${e.reason}")
        }
        if (plaintext.isEmpty()) {
            return Decoded.ProtocolError("decrypted frame carries no type byte")
        }
        val type = plaintext[0].toInt() and 0xFF
        val body = plaintext.copyOfRange(1, plaintext.size)

        // Every frame goes through the reassembler: it owns the rule that a
        // non-fragment frame arriving mid-sequence is a protocol error, which
        // cannot be decided by looking at the frame alone.
        return when (val result = reassembler.accept(type, body)) {
            is FragmentReassembler.Result.Complete -> asMessage(result.type, result.body)
            FragmentReassembler.Result.Passthrough -> asMessage(type, body)
            FragmentReassembler.Result.Buffered -> Decoded.Buffered
            is FragmentReassembler.Result.ProtocolError ->
                Decoded.ProtocolError(result.reason)
        }
    }

    /**
     * A completed message, however it arrived. A reassembled `orig_type` of 0
     * has to land here too, or a fragmented JSON message would be delivered as
     * an opaque binary frame.
     */
    private fun asMessage(type: Int, body: ByteArray): Decoded = when (type) {
        SendSpinProtocol.BinaryType.JSON -> Decoded.Json(body.decodeToString())
        else -> Decoded.Typed(type, body)
    }

    sealed interface Decoded {
        /** A JSON message body; hand to the existing text dispatcher. */
        data class Json(val text: String) : Decoded

        /** Audio (4), artwork (8-11), visualizer (16-20), or an unknown type. */
        data class Typed(val type: Int, val body: ByteArray) : Decoded {
            override fun equals(other: Any?): Boolean =
                this === other ||
                    (other is Typed && type == other.type && body.contentEquals(other.body))

            override fun hashCode(): Int = 31 * type + body.contentHashCode()
        }

        /**
         * A fragment was consumed and the message is still incomplete. Nothing
         * to dispatch; wait for the next frame.
         */
        object Buffered : Decoded

        /** Close the socket, send nothing. */
        data class ProtocolError(val reason: String) : Decoded
    }
}
