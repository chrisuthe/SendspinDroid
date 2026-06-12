package com.sendspindroid.sendspin.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * JVM `actual` for [createWebSocketHttpClient]. Same configuration as the
 * Android actual: ping interval on the underlying OkHttpClient (the Ktor
 * OkHttp engine ignores `pingIntervalMillis`).
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
        socketTimeoutMillis = Long.MAX_VALUE
    }
}
