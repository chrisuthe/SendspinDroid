package com.sendspindroid.sendspin.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Android `actual` for [createWebSocketHttpClient].
 *
 * Configures the ping interval on the underlying `OkHttpClient` rather than
 * via Ktor's `install(WebSockets) { pingIntervalMillis = ... }` (which the
 * OkHttp engine ignores per https://ktor.io/docs/client-websockets).
 *
 * OkHttp's `pingInterval` sends pings at the configured cadence AND
 * auto-closes the socket if the peer fails to respond within the same
 * interval. That close propagates up to Ktor's receive loop, which triggers
 * the existing `TransportEventListener.onClosed` path and its
 * infinite-backoff reconnect logic.
 */
internal actual fun createWebSocketHttpClient(
    pingIntervalSeconds: Long,
    connectTimeoutMs: Long,
): HttpClient = HttpClient(OkHttp) {
    engine {
        preconfigured = OkHttpClient.Builder()
            .pingInterval(pingIntervalSeconds, TimeUnit.SECONDS)
            .build()
    }
    install(WebSockets)
    install(HttpTimeout) {
        connectTimeoutMillis = connectTimeoutMs
        // No socket timeout for WebSocket (long-lived connection); ping/pong
        // is the keepalive and dead-peer detector.
        socketTimeoutMillis = Long.MAX_VALUE
    }
}
