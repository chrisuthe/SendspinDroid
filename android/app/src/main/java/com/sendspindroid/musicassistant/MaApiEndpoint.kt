package com.sendspindroid.musicassistant

import com.sendspindroid.UserSettings.ConnectionMode
import com.sendspindroid.model.UnifiedServer

/**
 * Derives Music Assistant API WebSocket URL from server configuration.
 *
 * The MA API is exposed on a WebSocket endpoint at `/ws`. The base URL
 * depends on the active connection mode:
 *
 * | Connection Mode | API URL Source |
 * |-----------------|----------------|
 * | LOCAL | local address + port 8095 |
 * | PROXY | proxy URL base + /ws |
 * | REMOTE | falls back to local or proxy if available |
 *
 * ## Why Remote-Only Can't Access MA API
 * Remote Access uses WebRTC for audio streaming, which doesn't tunnel
 * HTTP/WebSocket connections. The MA API requires a direct WS connection,
 * so Remote-only servers need a local or proxy fallback configured.
 *
 * ## Usage
 * ```kotlin
 * val apiUrl = MaApiEndpoint.deriveUrl(server, ConnectionMode.LOCAL)
 * if (apiUrl != null) {
 *     // Can connect to MA API
 *     MusicAssistantClient.connect(apiUrl)
 * } else {
 *     // No API endpoint available for this connection
 * }
 * ```
 */
object MaApiEndpoint {

    /**
     * Derives the MA API WebSocket URL for a server.
     *
     * @param server The unified server configuration
     * @param activeConnectionMode The currently active connection mode
     * @return WebSocket URL (ws:// or wss://), or null if no endpoint available
     */
    fun deriveUrl(server: UnifiedServer, activeConnectionMode: ConnectionMode): String? {
        return when (activeConnectionMode) {
            ConnectionMode.LOCAL -> {
                // Use local host + MA default port (8095)
                deriveFromLocal(server)
            }
            ConnectionMode.PROXY -> {
                // Use proxy base URL + /ws
                deriveFromProxy(server)
            }
            ConnectionMode.REMOTE -> {
                // WebRTC doesn't tunnel HTTP/WS - try fallbacks
                // First try local (might be reachable even if we connected via Remote)
                deriveFromLocal(server)
                    ?: deriveFromProxy(server)
            }
        }
    }

    /**
     * Derives MA API URL from local connection configuration.
     *
     * @param server The unified server configuration
     * @return WebSocket URL like "ws://192.168.1.100:8095/ws", or null
     */
    fun deriveFromLocal(server: UnifiedServer): String? {
        return server.local?.let { local ->
            val host = local.address.substringBefore(":")
            val port = MaSettings.getDefaultPort()
            "ws://$host:$port/ws"
        }
    }

    /**
     * Derives MA API URL from proxy connection configuration.
     *
     * Converts proxy URL from HTTP(S) to WS(S) and changes path to /ws.
     *
     * @param server The unified server configuration
     * @return WebSocket URL like "wss://ma.example.com/ws", or null
     */
    fun deriveFromProxy(server: UnifiedServer): String? {
        return server.proxy?.let { proxy ->
            val baseUrl = proxy.url
                .removeSuffix("/sendspin")
                .trimEnd('/')

            // Convert HTTP(S) to WS(S)
            val wsUrl = when {
                baseUrl.startsWith("https://") -> baseUrl.replaceFirst("https://", "wss://")
                baseUrl.startsWith("http://") -> baseUrl.replaceFirst("http://", "ws://")
                baseUrl.startsWith("wss://") || baseUrl.startsWith("ws://") -> baseUrl
                else -> "wss://$baseUrl"
            }

            "$wsUrl/ws"
        }
    }

    /**
     * Checks if a server has any endpoint that could support MA API.
     *
     * @param server The unified server configuration
     * @return true if local or proxy is configured
     */
    fun hasApiEndpoint(server: UnifiedServer): Boolean {
        return server.local != null || server.proxy != null
    }

    /**
     * Checks if MA API can be accessed for a given connection mode.
     *
     * @param server The unified server configuration
     * @param connectionMode The connection mode to check
     * @return true if MA API would be accessible
     */
    fun isApiAccessible(server: UnifiedServer, connectionMode: ConnectionMode): Boolean {
        return deriveUrl(server, connectionMode) != null
    }
}
