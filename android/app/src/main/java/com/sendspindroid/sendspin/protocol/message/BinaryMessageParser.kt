package com.sendspindroid.sendspin.protocol.message

import android.util.Log
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses incoming binary messages from the SendSpin protocol.
 *
 * Binary protocol format:
 * - Byte 0: message type
 *   - 4: Audio chunk
 *   - 8-11: Artwork for channels 0-3
 *   - 16: Visualization data
 * - Bytes 1-8: timestamp (int64, big-endian, microseconds since server start)
 * - Remaining: payload data
 */
object BinaryMessageParser {
    private const val TAG = "BinaryMessageParser"
    private const val HEADER_SIZE = 9 // 1 byte type + 8 bytes timestamp

    /**
     * Parsed binary message result.
     */
    sealed class BinaryMessage {
        /**
         * Audio chunk with server timestamp and PCM data.
         */
        data class Audio(
            val timestampMicros: Long,
            val payload: ByteArray
        ) : BinaryMessage() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as Audio

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

        /**
         * Artwork image data for a specific channel.
         */
        data class Artwork(
            val channel: Int,
            val timestampMicros: Long,
            val payload: ByteArray
        ) : BinaryMessage() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as Artwork

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

        /**
         * Visualization data.
         */
        data class Visualizer(
            val timestampMicros: Long,
            val payload: ByteArray
        ) : BinaryMessage() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as Visualizer

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

        /**
         * Unknown message type.
         */
        data class Unknown(
            val type: Int,
            val timestampMicros: Long,
            val payload: ByteArray
        ) : BinaryMessage() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as Unknown

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
     * Parse binary message from OkHttp ByteString (used by SendSpinClient).
     *
     * @param bytes The ByteString containing the binary message
     * @return Parsed BinaryMessage or null if message is malformed
     */
    fun parse(bytes: ByteString): BinaryMessage? {
        if (bytes.size < HEADER_SIZE) {
            Log.w(TAG, "Binary message too short: ${bytes.size} bytes")
            return null
        }

        val msgType = bytes[0].toInt() and 0xFF

        // Extract timestamp (big-endian int64)
        val timestampBytes = bytes.substring(1, 9).toByteArray()
        val buffer = ByteBuffer.wrap(timestampBytes).order(ByteOrder.BIG_ENDIAN)
        val timestampMicros = buffer.getLong()

        // Get payload
        val payload = bytes.substring(9).toByteArray()

        return createMessage(msgType, timestampMicros, payload)
    }

    /**
     * Parse binary message from Java-WebSocket ByteBuffer (used by SendSpinServer).
     *
     * @param bytes The ByteBuffer containing the binary message
     * @return Parsed BinaryMessage or null if message is malformed
     */
    fun parse(bytes: ByteBuffer): BinaryMessage? {
        if (bytes.remaining() < HEADER_SIZE) {
            Log.w(TAG, "Binary message too short: ${bytes.remaining()} bytes")
            return null
        }

        val msgType = bytes.get().toInt() and 0xFF

        // Extract timestamp (big-endian int64)
        bytes.order(ByteOrder.BIG_ENDIAN)
        val timestampMicros = bytes.getLong()

        // Get payload
        val payloadSize = bytes.remaining()
        val payload = ByteArray(payloadSize)
        bytes.get(payload)

        return createMessage(msgType, timestampMicros, payload)
    }

    /**
     * Create appropriate BinaryMessage subtype based on message type.
     */
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
