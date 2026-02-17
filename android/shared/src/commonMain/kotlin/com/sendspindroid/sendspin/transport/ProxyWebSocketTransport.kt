package com.sendspindroid.sendspin.transport

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header

/**
 * WebSocket transport for authenticated reverse proxy connections using Ktor.
 *
 * Unlike [WebSocketTransport] which takes a host:port format for local networks,
 * this transport accepts full URLs (with TLS support) for connecting through
 * reverse proxies like Nginx Proxy Manager, Traefik, or Caddy.
 *
 * ## URL Handling
 * - `https://domain.com/path` -> converted to `wss://domain.com/path`
 * - `http://domain.com/path` -> converted to `ws://domain.com/path`
 * - `wss://` and `ws://` URLs used as-is
 * - URLs without scheme default to `wss://` (secure)
 *
 * ## Security
 * - TLS is enforced by default for remote connections
 * - Use wss:// for encrypted connections through proxies
 *
 * @param url Full URL to connect to (e.g., "https://ma.example.com/sendspin")
 * @param authToken Optional Bearer token to include in the WebSocket upgrade request header.
 *   The SendSpin proxy server authenticates via the HTTP upgrade request, not post-connection
 *   JSON messages. If provided, an `Authorization: Bearer <token>` header is added.
 * @param pingIntervalSeconds Ping interval in seconds (default: 30, 15 in High Power Mode)
 * @param connectTimeoutMs Connect timeout in milliseconds (default: 10000)
 * @param httpClient Optional Ktor HttpClient (creates one if not provided)
 */
class ProxyWebSocketTransport(
    private val url: String,
    private val authToken: String? = null,
    pingIntervalSeconds: Long = 30,
    connectTimeoutMs: Long = 10000,
    httpClient: HttpClient = createDefaultClient(pingIntervalSeconds, connectTimeoutMs)
) : BaseWebSocketTransport(
    tag = TAG,
    httpClient = httpClient
) {

    companion object {
        private const val TAG = "ProxyWSTransport"
    }

    override fun buildWebSocketUrl(): String = convertToWebSocketUrl(url)

    override fun configureRequest(builder: HttpRequestBuilder) {
        // Add Bearer token to the HTTP upgrade request if provided
        if (!authToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $authToken")
        }
    }

    override fun isRecoverableError(t: Throwable): Boolean {
        val message = t.message?.lowercase() ?: ""

        // Auth failures are not recoverable -- check before the base class defaults
        if (message.contains("401") ||
            message.contains("403") ||
            message.contains("unauthorized")) {
            return false
        }

        return super.isRecoverableError(t)
    }

    /**
     * Convert HTTP/HTTPS URL to WebSocket URL.
     */
    private fun convertToWebSocketUrl(url: String): String {
        return when {
            url.startsWith("https://") -> url.replaceFirst("https://", "wss://")
            url.startsWith("http://") -> url.replaceFirst("http://", "ws://")
            url.startsWith("wss://") || url.startsWith("ws://") -> url
            // No scheme - assume secure
            else -> "wss://$url"
        }
    }
}
