package com.sendspindroid.model

import com.sendspindroid.shared.platform.Platform

data class UnifiedServer(
    val id: String,
    val name: String,
    val lastConnectedMs: Long = 0L,

    val local: LocalConnection? = null,
    val remote: RemoteConnection? = null,
    val proxy: ProxyConnection? = null,

    val connectionPreference: ConnectionPreference = ConnectionPreference.AUTO,
    val isDiscovered: Boolean = false,
    val isDefaultServer: Boolean = false,
    val isMusicAssistant: Boolean = false
) {
    val hasAnyConnection: Boolean
        get() = local != null || remote != null || proxy != null

    val configuredMethods: List<ConnectionType>
        get() = buildList {
            if (local != null) add(ConnectionType.LOCAL)
            if (remote != null) add(ConnectionType.REMOTE)
            if (proxy != null) add(ConnectionType.PROXY)
        }

    val formattedLastConnected: String
        get() {
            if (lastConnectedMs == 0L) return "Never connected"
            val elapsed = Platform.currentTimeMillis() - lastConnectedMs
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

data class LocalConnection(
    val address: String,
    val path: String = "/sendspin"
)

data class RemoteConnection(
    val remoteId: String
) {
    val formattedId: String
        get() = remoteId.chunked(5).joinToString("-")
}

data class ProxyConnection(
    val url: String,
    val authToken: String,
    val username: String? = null
)

enum class ConnectionPreference {
    AUTO,
    LOCAL_ONLY,
    REMOTE_ONLY,
    PROXY_ONLY
}

enum class ConnectionType {
    LOCAL,
    REMOTE,
    PROXY
}
