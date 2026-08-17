package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol

/**
 * Message fragmentation, `messaging.md#fragmentation`.
 *
 * Noise caps a transport message at 65535 bytes, so any Sendspin message whose
 * payload exceeds [Fragmentation.MAX_UNFRAGMENTED_PAYLOAD] must be split across
 * fragment frames. Fragment-more (`2`) opens and continues; fragment-end (`3`)
 * closes.
 *
 * Both halves live here because they are exact inverses and are round-tripped
 * against each other in tests.
 */
object Fragmentation {

    /** Noise's own per-transport-message limit. */
    const val NOISE_MAX_MESSAGE = SendSpinProtocol.NoiseFraming.MAX_TRANSPORT_MESSAGE

    /** Both defined cipher suites use a 16-byte AEAD tag. */
    const val AEAD_TAG = SendSpinProtocol.NoiseFraming.AEAD_TAG

    /** The most plaintext one frame can carry: 65519. */
    const val MAX_PLAINTEXT = SendSpinProtocol.NoiseFraming.MAX_PLAINTEXT

    /** `[type][payload]`, so 65518. */
    const val MAX_UNFRAGMENTED_PAYLOAD = SendSpinProtocol.NoiseFraming.MAX_PAYLOAD

    /** Opening fragment-more is `[2][orig_type][data]`, so 65517. */
    const val MAX_FIRST_FRAGMENT_DATA = MAX_PLAINTEXT - 2

    /** Continuation `[2][data]` and fragment-end `[3][data]`, so 65518. */
    const val MAX_FRAGMENT_DATA = MAX_PLAINTEXT - 1

    /**
     * Default ceiling on a single reassembled message.
     *
     * The spec sets no cap. Without one a peer can hold the connection open
     * sending fragment-more frames forever and exhaust memory on a phone, so
     * exceeding this is a protocol error rather than a silent truncation.
     */
    const val DEFAULT_MAX_MESSAGE_BYTES = 8 * 1024 * 1024

    /** `2` and `3` are the fragment types and may never be an `orig_type`. */
    fun isFragmentType(type: Int): Boolean =
        type == SendSpinProtocol.BinaryType.FRAGMENT_MORE ||
            type == SendSpinProtocol.BinaryType.FRAGMENT_END
}

/**
 * Splits an outbound message into frames.
 *
 * Returns AEAD *plaintexts*; the Noise codec encrypts each one. The whole list
 * must go on the wire consecutively, because "a sender must finish a fragmented
 * message with a fragment-end frame before sending any other frame in that
 * direction" - which is why this returns a list rather than streaming.
 */
object FragmentWriter {

    /**
     * @param type the message's real type; MUST NOT be `2` or `3`
     * @return one `[type][payload]` frame when it fits, otherwise
     *   `[2][type][data]`, zero or more `[2][data]`, and a closing `[3][data]`
     */
    fun frames(type: Int, payload: ByteArray): List<ByteArray> {
        require(!Fragmentation.isFragmentType(type)) {
            "a fragment type ($type) may never be used as orig_type"
        }
        require(type in 0..255) { "binary message type is a uint8, got $type" }

        if (1 + payload.size <= Fragmentation.MAX_PLAINTEXT) {
            return listOf(byteArrayOf(type.toByte()) + payload)
        }

        val out = mutableListOf<ByteArray>()
        // Opening frame spends one byte on orig_type, so it carries less data
        // than the continuations that follow it.
        val first = payload.copyOfRange(0, Fragmentation.MAX_FIRST_FRAGMENT_DATA)
        out += byteArrayOf(SendSpinProtocol.BinaryType.FRAGMENT_MORE.toByte(), type.toByte()) + first

        var offset = Fragmentation.MAX_FIRST_FRAGMENT_DATA
        // Leave at least one byte for the fragment-end frame so the sequence
        // always terminates with a type 3, as the spec requires.
        while (payload.size - offset > Fragmentation.MAX_FRAGMENT_DATA) {
            val next = payload.copyOfRange(offset, offset + Fragmentation.MAX_FRAGMENT_DATA)
            out += byteArrayOf(SendSpinProtocol.BinaryType.FRAGMENT_MORE.toByte()) + next
            offset += Fragmentation.MAX_FRAGMENT_DATA
        }

        val remainder = payload.copyOfRange(offset, payload.size)
        out += byteArrayOf(SendSpinProtocol.BinaryType.FRAGMENT_END.toByte()) + remainder
        return out
    }
}

/**
 * Reassembles one direction of a connection.
 *
 * Stateful and single-direction, so one instance per connection per direction.
 * "Only one fragmented message may be in flight at a time per direction", which
 * is why a single buffer and a single [origType] suffice.
 *
 * Chunks are accumulated and concatenated once on completion rather than
 * regrown per frame: a 300 KB message arrives as five frames, and repeated
 * array copying would make reassembly quadratic in message size.
 */
class FragmentReassembler(
    private val maxMessageBytes: Int = Fragmentation.DEFAULT_MAX_MESSAGE_BYTES,
) {

    sealed class Result {
        /** Dispatch this as a whole message of [type]. */
        data class Complete(val type: Int, val body: ByteArray) : Result() {
            override fun equals(other: Any?): Boolean =
                this === other ||
                    (other is Complete && type == other.type && body.contentEquals(other.body))

            override fun hashCode(): Int = 31 * type + body.contentHashCode()
        }

        /** Fragment consumed; nothing to dispatch yet. */
        object Buffered : Result()

        /** Not fragment-related and nothing in flight: dispatch normally. */
        object Passthrough : Result()

        /** MUST close the connection. */
        data class ProtocolError(val reason: String) : Result()
    }

    private var origType: Int? = null
    private val chunks = mutableListOf<ByteArray>()
    private var buffered = 0

    /** Whether a fragmented message is currently being reassembled. */
    val inFlight: Boolean get() = origType != null

    /**
     * Feed one decrypted frame, type byte already stripped.
     *
     * @param type the frame's own type byte
     * @param body everything after it
     */
    fun accept(type: Int, body: ByteArray): Result {
        val open = origType

        if (type == SendSpinProtocol.BinaryType.FRAGMENT_MORE) {
            return if (open == null) startNew(body) else append(body)
        }

        if (type == SendSpinProtocol.BinaryType.FRAGMENT_END) {
            if (open == null) {
                return Result.ProtocolError(
                    "fragment-end with no fragmented message in flight"
                )
            }
            val appended = append(body)
            if (appended is Result.ProtocolError) return appended
            val complete = Result.Complete(open, concatenate())
            reset()
            return complete
        }

        // A non-fragment frame while a message is in flight breaks the rule that
        // a sender must finish a fragmented message before sending anything else
        // in that direction.
        if (open != null) {
            return Result.ProtocolError(
                "non-fragment frame (type $type) while a fragmented message is in flight"
            )
        }
        return Result.Passthrough
    }

    /** Drop any in-flight message. Call on reconnect and after a re-handshake. */
    fun reset() {
        origType = null
        chunks.clear()
        buffered = 0
    }

    private fun startNew(body: ByteArray): Result {
        if (body.isEmpty()) {
            return Result.ProtocolError("opening fragment-more carries no orig_type")
        }
        val orig = body[0].toInt() and 0xFF
        if (Fragmentation.isFragmentType(orig)) {
            return Result.ProtocolError("orig_type of $orig is itself a fragment type")
        }
        origType = orig
        // Byte 0 was orig_type; the rest is data.
        return append(body.copyOfRange(1, body.size))
    }

    private fun append(data: ByteArray): Result {
        if (buffered + data.size > maxMessageBytes) {
            reset()
            return Result.ProtocolError(
                "fragmented message exceeds the ${maxMessageBytes}-byte reassembly cap"
            )
        }
        chunks.add(data)
        buffered += data.size
        return Result.Buffered
    }

    private fun concatenate(): ByteArray {
        val out = ByteArray(buffered)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }
}
