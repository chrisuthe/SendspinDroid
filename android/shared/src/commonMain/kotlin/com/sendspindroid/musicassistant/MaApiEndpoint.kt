package com.sendspindroid.musicassistant

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
 */
object MaApiEndpoint {

    /**
     * Sentinel URL returned for REMOTE mode when a WebRTC DataChannel is available.
     * This signals the manager to use a DataChannel transport instead of a WebSocket.
     */
    const val WEBRTC_SENTINEL_URL = "webrtc://ma-api"

    /**
     * Derives the MA API WebSocket URL for a server.
     *
     * @param server The unified server configuration
     * @param activeConnectionMode The currently active connection mode
     * @param defaultPort The default MA API port (typically 8095)
     * @return WebSocket URL (ws:// or wss://), or null if no endpoint available
     */
    fun deriveUrl(
        server: UnifiedServer,
        activeConnectionMode: MaConnectionMode,
        defaultPort: Int = 8095
    ): String? {
        return when (activeConnectionMode) {
            MaConnectionMode.LOCAL -> {
                deriveFromLocal(server, defaultPort)
            }
            MaConnectionMode.PROXY -> {
                deriveFromProxy(server)
            }
            MaConnectionMode.REMOTE -> {
                if (server.remote != null) {
                    WEBRTC_SENTINEL_URL
                } else {
                    deriveFromLocal(server, defaultPort) ?: deriveFromProxy(server)
                }
            }
        }
    }

    /**
     * Derives MA API URL from local connection configuration.
     *
     * @param server The unified server configuration
     * @param defaultPort The MA API port (default: 8095)
     * @return WebSocket URL like "ws://192.168.1.100:8095/ws", or null
     */
    fun deriveFromLocal(server: UnifiedServer, defaultPort: Int = 8095): String? {
        return server.local?.let { local ->
            val host = local.address.substringBefore(":")
            "ws://$host:$defaultPort/ws"
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
     * @param defaultPort The MA API port (default: 8095)
     * @return true if MA API would be accessible
     */
    fun isApiAccessible(
        server: UnifiedServer,
        connectionMode: MaConnectionMode,
        defaultPort: Int = 8095
    ): Boolean {
        return deriveUrl(server, connectionMode, defaultPort) != null
    }
}
