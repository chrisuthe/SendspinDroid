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
 * | REMOTE | WebRTC DataChannel (sentinel URL), or local/proxy fallback |
 *
 * ## Remote Mode
 * The MA server's WebRTC gateway supports a second DataChannel ("ma-api")
 * that transparently bridges to the full WebSocket API. When in REMOTE mode
 * with a WebRTC connection, we return a sentinel URL "webrtc://ma-api" to
 * indicate that the transport should use the DataChannel rather than a
 * direct WebSocket. If no WebRTC connection is available, falls back to
 * local or proxy URLs if configured.
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
     * Sentinel URL returned for REMOTE mode when a WebRTC DataChannel is available.
     * This signals [MusicAssistantManager] to use [MaDataChannelTransport] instead
     * of a WebSocket.
     */
    const val WEBRTC_SENTINEL_URL = "webrtc://ma-api"

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
                // REMOTE mode: prefer WebRTC DataChannel (sentinel URL)
                // If the server has a remote config, the DataChannel will be available
                if (server.remote != null) {
                    WEBRTC_SENTINEL_URL
                } else {
                    // No remote config - try local/proxy fallbacks
                    deriveFromLocal(server) ?: deriveFromProxy(server)
                }
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
     * @return true if local, proxy, or remote is configured
     */
    fun hasApiEndpoint(server: UnifiedServer): Boolean {
        return server.local != null || server.proxy != null || server.remote != null
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
