package com.sendspindroid.sendspin.protocol.message

import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extension to parse from OkHttp ByteString (used by WebSocket transports).
 */
fun BinaryMessageParser.parse(bytes: ByteString): BinaryMessageParser.BinaryMessage? {
    return parse(bytes.toByteArray())
}

/**
 * Extension to parse from Java-WebSocket ByteBuffer (used by SendSpinServer).
 */
fun BinaryMessageParser.parse(bytes: ByteBuffer): BinaryMessageParser.BinaryMessage? {
    if (bytes.remaining() < 9) return null

    val array = ByteArray(bytes.remaining())
    bytes.get(array)
    return parse(array)
}
