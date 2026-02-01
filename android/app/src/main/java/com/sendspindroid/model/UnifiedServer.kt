package com.sendspindroid.model

/**
 * A unified server that can have multiple connection methods.
 *
 * The unified server abstraction allows users to configure a single server entry
 * that can be reached via different connection methods (local network, remote access,
 * or proxy). The app automatically selects the best method based on current network.
 *
 * ## Connection Priority by Network
 * - **WiFi/Ethernet**: Local → Proxy → Remote
 * - **Cellular**: Proxy → Remote (skip local)
 * - **VPN**: Proxy → Remote → Local
 * - **Unknown**: Local → Proxy → Remote
 *
 * @property id Unique identifier (UUID format)
 * @property name User-friendly display name
 * @property lastConnectedMs Timestamp of last successful connection
 * @property local Local network connection (IP:port)
 * @property remote Remote Access connection (26-char Remote ID)
 * @property proxy Reverse proxy connection (URL + auth)
 * @property connectionPreference User's preferred connection method
 * @property isDiscovered True if this server was found via mDNS (transient)
 */
data class UnifiedServer(
    val id: String,
    val name: String,
    val lastConnectedMs: Long = 0L,

    // Connection methods (all optional, at least one required)
    val local: LocalConnection? = null,
    val remote: RemoteConnection? = null,
    val proxy: ProxyConnection? = null,

    val connectionPreference: ConnectionPreference = ConnectionPreference.AUTO,
    val isDiscovered: Boolean = false,
    val isDefaultServer: Boolean = false
) {
    /**
     * Returns true if at least one connection method is configured.
     */
    val hasAnyConnection: Boolean
        get() = local != null || remote != null || proxy != null

    /**
     * Returns a list of all configured connection types.
     */
    val configuredMethods: List<ConnectionType>
        get() = buildList {
            if (local != null) add(ConnectionType.LOCAL)
            if (remote != null) add(ConnectionType.REMOTE)
            if (proxy != null) add(ConnectionType.PROXY)
        }

    /**
     * Human-readable description of last connection time.
     */
    val formattedLastConnected: String
        get() {
            if (lastConnectedMs == 0L) return "Never connected"
            val elapsed = System.currentTimeMillis() - lastConnectedMs
            val minutes = elapsed / 60_000
            val hours = minutes / 60
            val days = hours / 24

            return when {
                days > 0 -> "$days day${if (days > 1) "s" else ""} ago"
                hours > 0 -> "$hours hour${if (hours > 1) "s" else ""} ago"
                minutes > 0 -> "$minutes minute${if (minutes > 1) "s" else ""} ago"
                else -> "Just now"
            }
        }
}

/**
 * Local network connection via direct WebSocket.
 *
 * @property address Server address in "host:port" format (e.g., "192.168.1.100:8927")
 * @property path WebSocket endpoint path (default: /sendspin)
 */
data class LocalConnection(
    val address: String,
    val path: String = "/sendspin"
)

/**
 * Remote Access connection via WebRTC (Music Assistant Remote).
 *
 * @property remoteId 26-character Remote ID from Music Assistant
 */
data class RemoteConnection(
    val remoteId: String
) {
    /**
     * Formatted Remote ID with dashes for display (XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX).
     */
    val formattedId: String
        get() = remoteId.chunked(5).joinToString("-")
}

/**
 * Proxy connection via authenticated reverse proxy.
 *
 * @property url Full proxy URL (e.g., "https://ma.example.com/sendspin")
 * @property authToken Long-lived authentication token
 * @property username Optional username for display (not used for auth)
 */
data class ProxyConnection(
    val url: String,
    val authToken: String,
    val username: String? = null
)

/**
 * User's preference for connection method selection.
 */
enum class ConnectionPreference {
    /** Automatically select best method based on network type */
    AUTO,
    /** Only use local network connection */
    LOCAL_ONLY,
    /** Only use Remote Access */
    REMOTE_ONLY,
    /** Only use proxy connection */
    PROXY_ONLY
}

/**
 * Type of connection method.
 */
enum class ConnectionType {
    LOCAL,
    REMOTE,
    PROXY
}
