package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.shared.log.Log

object BinaryMessageParser {
    private const val TAG = "BinaryMessageParser"
    private const val HEADER_SIZE = SendSpinProtocol.BINARY_HEADER_SIZE_BYTES

    sealed class BinaryMessage {
        data class Audio(
            val timestampMicros: Long,
            val payload: ByteArray
        ) : BinaryMessage() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Audio) return false
                if (timestampMicros != other.timestampMicros) return false
                if (!payload.contentEquals(other.payload)) return false
                return true
            }

            override fun hashCode(): Int {
                var result = timestampMicros.hashCode()
                result = 31 * result + payload.contentHashCode()
                return result
            }
        }

        data class Artwork(
            val channel: Int,
            val timestampMicros: Long,
            val payload: ByteArray
        ) : BinaryMessage() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Artwork) return false
                if (channel != other.channel) return false
                if (timestampMicros != other.timestampMicros) return false
                if (!payload.contentEquals(other.payload)) return false
                return true
            }

            override fun hashCode(): Int {
                var result = channel
                result = 31 * result + timestampMicros.hashCode()
                result = 31 * result + payload.contentHashCode()
                return result
            }
        }

        data class Visualizer(
            val timestampMicros: Long,
            val payload: ByteArray
        ) : BinaryMessage() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Visualizer) return false
                if (timestampMicros != other.timestampMicros) return false
                if (!payload.contentEquals(other.payload)) return false
                return true
            }

            override fun hashCode(): Int {
                var result = timestampMicros.hashCode()
                result = 31 * result + payload.contentHashCode()
                return result
            }
        }

        data class Unknown(
            val type: Int,
            val timestampMicros: Long,
            val payload: ByteArray
        ) : BinaryMessage() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Unknown) return false
                if (type != other.type) return false
                if (timestampMicros != other.timestampMicros) return false
                if (!payload.contentEquals(other.payload)) return false
                return true
            }

            override fun hashCode(): Int {
                var result = type
                result = 31 * result + timestampMicros.hashCode()
                result = 31 * result + payload.contentHashCode()
                return result
            }
        }
    }

    /**
     * Parse a binary message whose type byte has already been stripped.
     *
     * This is the encrypted path: [com.sendspindroid.sendspin.protocol.NoiseWireCodec]
     * removes the type byte when it decrypts, so re-deriving it here would mean
     * stripping it twice. [body] is the remainder, still
     * `[8-byte big-endian timestamp][payload]`.
     */
    fun parse(type: Int, body: ByteArray): BinaryMessage? {
        if (body.size < TIMESTAMP_SIZE) {
            Log.w(TAG, "Binary message body too short: ${body.size} bytes")
            return null
        }
        val timestampMicros = readTimestamp(body, 0)
        return createMessage(type, timestampMicros, body.copyOfRange(TIMESTAMP_SIZE, body.size))
    }

    private const val TIMESTAMP_SIZE = 8

    private fun readTimestamp(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 56) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 48) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 40) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 32) or
            ((bytes[offset + 4].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 5].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 6].toLong() and 0xFF) shl 8) or
            (bytes[offset + 7].toLong() and 0xFF)

    /**
     * Parse a whole binary frame, type byte included.
     *
     * The legacy (unencrypted) path and the conformance client still receive
     * frames this way.
     */
    fun parse(bytes: ByteArray): BinaryMessage? {
        if (bytes.size < HEADER_SIZE) {
            Log.w(TAG, "Binary message too short: ${bytes.size} bytes")
            return null
        }

        val msgType = bytes[0].toInt() and 0xFF
        val timestampMicros = readTimestamp(bytes, 1)
        val payload = bytes.copyOfRange(HEADER_SIZE, bytes.size)

        return createMessage(msgType, timestampMicros, payload)
    }

    private fun createMessage(msgType: Int, timestampMicros: Long, payload: ByteArray): BinaryMessage {
        return when (msgType) {
            SendSpinProtocol.BinaryType.AUDIO -> {
                BinaryMessage.Audio(timestampMicros, payload)
            }
            in SendSpinProtocol.BinaryType.ARTWORK_BASE..(SendSpinProtocol.BinaryType.ARTWORK_BASE + 3) -> {
                val channel = msgType - SendSpinProtocol.BinaryType.ARTWORK_BASE
                BinaryMessage.Artwork(channel, timestampMicros, payload)
            }
            SendSpinProtocol.BinaryType.VISUALIZER -> {
                BinaryMessage.Visualizer(timestampMicros, payload)
            }
            else -> {
                Log.v(TAG, "Unknown binary message type: $msgType")
                BinaryMessage.Unknown(msgType, timestampMicros, payload)
            }
        }
    }
}
