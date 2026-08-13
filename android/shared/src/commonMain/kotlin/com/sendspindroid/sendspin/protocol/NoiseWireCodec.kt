package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.NoiseHandshakeException
import com.sendspindroid.sendspin.crypto.NoiseTransport
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
class NoiseWireCodec(private val transport: NoiseTransport) {

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
        if (payload.size > SendSpinProtocol.NoiseFraming.MAX_PAYLOAD) {
            // Item 1.5 replaces this with a fragmented send. Failing loudly is
            // the right interim behaviour: silently truncating or letting the
            // AEAD reject it would both be far harder to diagnose.
            throw NoiseHandshakeException(
                NoiseHandshakeException.Cause.MessageTooLarge,
                "payload of ${payload.size} bytes exceeds the " +
                    "${SendSpinProtocol.NoiseFraming.MAX_PAYLOAD}-byte single-frame " +
                    "limit and fragmentation is not implemented yet (item 1.5)",
            )
        }
        val plaintext = ByteArray(1 + payload.size)
        plaintext[0] = type.toByte()
        payload.copyInto(plaintext, 1)
        return sendMutex.withLock { listOf(transport.encrypt(plaintext)) }
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
            transport.decrypt(frameBytes)
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
        return when (type) {
            SendSpinProtocol.BinaryType.JSON -> Decoded.Json(body.decodeToString())
            SendSpinProtocol.BinaryType.FRAGMENT_MORE,
            SendSpinProtocol.BinaryType.FRAGMENT_END ->
                Decoded.Fragment(type, body)
            else -> Decoded.Typed(type, body)
        }
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

        /** A fragmentation frame. Reassembly arrives in item 1.5. */
        data class Fragment(val type: Int, val body: ByteArray) : Decoded {
            override fun equals(other: Any?): Boolean =
                this === other ||
                    (other is Fragment && type == other.type && body.contentEquals(other.body))

            override fun hashCode(): Int = 31 * type + body.contentHashCode()
        }

        /** Close the socket, send nothing. */
        data class ProtocolError(val reason: String) : Decoded
    }
}
