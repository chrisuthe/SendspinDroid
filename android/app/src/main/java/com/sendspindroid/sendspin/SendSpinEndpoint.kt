package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.protocol.SendSpinProtocol

/**
 * Endpoint a SendSpin connection targets. Replaces the three explicit
 * connect{Local,Proxy,Remote} methods with a single `connect(endpoint)`
 * entry point.
 *
 * Phase 4 of the ConnectionCoordinator design.
 * See docs/superpowers/specs/2026-05-05-connection-coordinator-design.md
 */
sealed class SendSpinEndpoint {
    /**
     * Direct WebSocket to a server on the local network.
     * @param address host[:port], e.g. "10.0.1.5:8927"
     * @param path WebSocket path, defaults to SendSpin's standard endpoint.
     */
    data class Local(
        val address: String,
        val path: String = SendSpinProtocol.ENDPOINT_PATH,
    ) : SendSpinEndpoint()

    /**
     * Authenticated WebSocket to a reverse proxy (e.g. Music Assistant cloud).
     * @param url full proxy URL including scheme.
     * @param authToken proxy bearer/auth token sent in the auth message.
     */
    data class Proxy(
        val url: String,
        val authToken: String,
    ) : SendSpinEndpoint()

    /**
     * WebRTC DataChannel via Music Assistant Remote Access.
     * @param remoteId 26-character remote-access identifier.
     */
    data class Remote(
        val remoteId: String,
    ) : SendSpinEndpoint()
}
