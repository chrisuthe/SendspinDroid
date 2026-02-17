package com.sendspindroid.musicassistant.transport

/**
 * KMP-compatible exception for Music Assistant transport errors.
 *
 * Replaces `java.io.IOException` throughout the commonMain source set
 * to ensure Kotlin Multiplatform compatibility. This exception is thrown
 * for transport-level errors such as:
 * - Transport not connected
 * - Failed to send commands
 * - Transport disconnected unexpectedly
 *
 * Mirrors the role of `IOException` in a platform-independent way.
 */
open class MaTransportException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
