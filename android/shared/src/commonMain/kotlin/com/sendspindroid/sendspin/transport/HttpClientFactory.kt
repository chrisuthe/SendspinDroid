package com.sendspindroid.sendspin.transport

import io.ktor.client.HttpClient

/**
 * Platform-specific factory for a Ktor [HttpClient] configured with WebSocket
 * keepalive pings.
 *
 * Implementations must ensure pings actually reach the wire at the given
 * interval AND that the socket is auto-closed if the peer fails to pong
 * within that interval. Ktor's own `install(WebSockets) { pingIntervalMillis = ... }`
 * is silently ignored by the OkHttp engine — the ping interval must be
 * configured on the underlying `OkHttpClient.Builder`. Without keepalive
 * pings, a server crash in idle state is never detected (TCP may never
 * deliver a RST), and the client appears connected to a dead socket
 * forever. See https://ktor.io/docs/client-websockets for the Ktor docs
 * noting this engine caveat.
 */
internal expect fun createWebSocketHttpClient(
    pingIntervalSeconds: Long,
    connectTimeoutMs: Long,
): HttpClient
