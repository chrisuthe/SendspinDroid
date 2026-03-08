package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import java.nio.ByteBuffer

/**
 * Extension to parse from Java-WebSocket ByteBuffer (used by SendSpinServer).
 */
fun BinaryMessageParser.parse(bytes: ByteBuffer): BinaryMessageParser.BinaryMessage? {
    if (bytes.remaining() < SendSpinProtocol.BINARY_HEADER_SIZE_BYTES) return null

    val array = ByteArray(bytes.remaining())
    bytes.get(array)
    return parse(array)
}
