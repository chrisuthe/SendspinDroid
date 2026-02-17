package com.sendspindroid.sendspin.protocol.message

import java.nio.ByteBuffer

/**
 * Extension to parse from Java-WebSocket ByteBuffer (used by SendSpinServer).
 */
fun BinaryMessageParser.parse(bytes: ByteBuffer): BinaryMessageParser.BinaryMessage? {
    if (bytes.remaining() < 9) return null

    val array = ByteArray(bytes.remaining())
    bytes.get(array)
    return parse(array)
}
