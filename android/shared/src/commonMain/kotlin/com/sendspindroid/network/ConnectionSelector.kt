package com.sendspindroid.network

import com.sendspindroid.model.*
import com.sendspindroid.shared.log.Log

/**
 * Selects the best connection method for a unified server based on network type.
 *
 * ## Selection Priority by Network Type
 *
 * | Network       | Priority Order            | Rationale                          |
 * |---------------|---------------------------|------------------------------------|
 * | WiFi/Ethernet | Local -> Proxy -> Remote    | Local has lowest latency           |
 * | Cellular      | Proxy -> Remote -> Local    | Local last - supports publicly-routable hostnames |
 * | VPN           | Proxy -> Remote -> Local    | VPN may route home, proxy preferred|
 * | Unknown       | Proxy -> Remote -> Local    | Can't determine network, proxy safest|
 *
 * ## Connection Preference Override
 * If the user has set a connection preference (LOCAL_ONLY, REMOTE_ONLY, PROXY_ONLY),
 * only that method will be attempted regardless of network type.
 */
object ConnectionSelector {

    private const val TAG = "ConnectionSelector"

    /**
     * Result of connection selection.
     */
    sealed class SelectedConnection {
        data class Local(val address: String, val path: String) : SelectedConnection()
        data class Remote(val remoteId: String) : SelectedConnection()
        data class Proxy(val url: String, val authToken: String) : SelectedConnection()
    }

    /**
     * Selects the best connection method for the given server based on network state.
     *
     * @param server The unified server with configured connection methods
     * @param networkState Current network state from NetworkEvaluator
     * @return The selected connection, or null if no suitable method is available
     */
    fun selectConnection(
        server: UnifiedServer,
        networkState: NetworkState
    ): SelectedConnection? {
        // Handle user preference override
        when (server.connectionPreference) {
            ConnectionPreference.LOCAL_ONLY -> {
                return server.local?.let {
                    SelectedConnection.Local(it.address, it.path)
                }.also {
                    if (it == null) Log.w(TAG, "LOCAL_ONLY preference but no local connection configured")
                }
            }
            ConnectionPreference.REMOTE_ONLY -> {
                return server.remote?.let {
                    SelectedConnection.Remote(it.remoteId)
                }.also {
                    if (it == null) Log.w(TAG, "REMOTE_ONLY preference but no remote connection configured")
                }
            }
            ConnectionPreference.PROXY_ONLY -> {
                return server.proxy?.let {
                    SelectedConnection.Proxy(it.url, it.authToken)
                }.also {
                    if (it == null) Log.w(TAG, "PROXY_ONLY preference but no proxy connection configured")
                }
            }
            ConnectionPreference.AUTO -> {
                // Continue with automatic selection
            }
        }

        // Auto-select based on network type
        val priority = getPriorityOrder(networkState.transportType)

        for (connectionType in priority) {
            val selected = when (connectionType) {
                ConnectionType.LOCAL -> server.local?.let {
                    SelectedConnection.Local(it.address, it.path)
                }
                ConnectionType.REMOTE -> server.remote?.let {
                    SelectedConnection.Remote(it.remoteId)
                }
                ConnectionType.PROXY -> server.proxy?.let {
                    SelectedConnection.Proxy(it.url, it.authToken)
                }
            }

            if (selected != null) {
                Log.d(TAG, "Selected ${connectionType.name} for ${server.name} on ${networkState.transportType}")
                return selected
            }
        }

        Log.w(TAG, "No connection method available for ${server.name}")
        return null
    }

    /**
     * Returns the connection priority order for a given network transport type.
     */
    fun getPriorityOrder(transportType: TransportType): List<ConnectionType> {
        return when (transportType) {
            // WiFi/Ethernet: Local first (lowest latency on LAN)
            TransportType.WIFI,
            TransportType.ETHERNET -> listOf(
                ConnectionType.LOCAL,
                ConnectionType.PROXY,
                ConnectionType.REMOTE
            )

            // Cellular: Proxy first (direct, usually fastest on cellular), then Remote
            // (WebRTC signaling), then Local last. Local is included because users may
            // configure a publicly-routable hostname (AAAA, public IP, dyndns) as their
            // "local" address - those work over cellular even though mDNS-discovered
            // LAN servers don't. A doomed attempt to a LAN-only server times out fast.
            TransportType.CELLULAR -> listOf(
                ConnectionType.PROXY,
                ConnectionType.REMOTE,
                ConnectionType.LOCAL
            )

            // VPN: Proxy first (VPN might tunnel to home network)
            TransportType.VPN -> listOf(
                ConnectionType.PROXY,
                ConnectionType.REMOTE,
                ConnectionType.LOCAL
            )

            // Unknown: Proxy first (can't determine network, local may be unreachable)
            TransportType.UNKNOWN -> listOf(
                ConnectionType.PROXY,
                ConnectionType.REMOTE,
                ConnectionType.LOCAL
            )
        }
    }

    /**
     * Checks whether local connections should be attempted on the current network.
     * Always true: the "local" slot may hold a publicly-routable hostname or IP,
     * and we would rather make a quick doomed attempt than wrongly exclude a
     * working configuration. Kept as a function to avoid breaking callers.
     */
    fun shouldAttemptLocal(@Suppress("UNUSED_PARAMETER") transportType: TransportType): Boolean {
        return true
    }

    /**
     * Returns a human-readable description of the selected connection type.
     */
    fun getConnectionDescription(selected: SelectedConnection): String {
        return when (selected) {
            is SelectedConnection.Local -> "Local (${selected.address})"
            is SelectedConnection.Remote -> "Remote Access"
            is SelectedConnection.Proxy -> "Proxy"
        }
    }
}
