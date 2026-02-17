package com.sendspindroid.remote

/**
 * ICE server configuration for WebRTC.
 *
 * @property url STUN or TURN server URL (e.g., "stun:stun.l.google.com:19302")
 * @property username Optional username for TURN servers
 * @property credential Optional credential for TURN servers
 */
data class IceServerConfig(
    val url: String,
    val username: String? = null,
    val credential: String? = null
) {
    val isTurn: Boolean get() = url.startsWith("turn:")
    val isStun: Boolean get() = url.startsWith("stun:")
}
