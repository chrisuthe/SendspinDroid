package com.sendspindroid.musicassistant

/**
 * Endpoint a Music Assistant connection targets. Replaces the implicit
 * URL-derivation in MusicAssistantManager.onServerConnected with a typed
 * sealed class.
 *
 * Phase 5 of the ConnectionCoordinator design.
 * See docs/superpowers/specs/2026-05-05-connection-coordinator-design.md
 */
sealed class MaEndpoint {
    /** Direct WebSocket to MA running on the local network. */
    data class Local(val address: String, val port: Int) : MaEndpoint()

    /** WebSocket via authenticated reverse proxy. */
    data class Proxy(val baseUrl: String) : MaEndpoint()

    /** WebRTC DataChannel via Music Assistant Remote Access. */
    data class Remote(val remoteId: String) : MaEndpoint()
}
