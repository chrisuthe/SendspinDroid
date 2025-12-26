package com.sendspindroid

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton repository for managing server state across the app.
 *
 * Provides shared access to discovered and manual servers for both:
 * - MainActivity (server discovery UI)
 * - PlaybackService (Android Auto browse tree)
 *
 * Uses StateFlow for reactive updates and SharedPreferences for persistence.
 *
 * ## Usage
 * ```kotlin
 * // Add a discovered server
 * ServerRepository.addServer(ServerInfo("Living Room", "192.168.1.100:8927"))
 *
 * // Observe servers reactively
 * lifecycleScope.launch {
 *     ServerRepository.servers.collect { servers ->
 *         updateUI(servers)
 *     }
 * }
 *
 * // Get current servers synchronously
 * val currentServers = ServerRepository.servers.value
 * ```
 */
object ServerRepository {

    private const val PREFS_NAME = "server_repository"
    private const val KEY_MANUAL_SERVERS = "manual_servers"
    private const val KEY_RECENT_SERVERS = "recent_servers"

    private val _discoveredServers = MutableStateFlow<List<ServerInfo>>(emptyList())
    /**
     * Servers discovered via mDNS. These are transient and not persisted.
     */
    val discoveredServers: StateFlow<List<ServerInfo>> = _discoveredServers.asStateFlow()

    private val _manualServers = MutableStateFlow<List<ServerInfo>>(emptyList())
    /**
     * Manually added servers. These are persisted to SharedPreferences.
     */
    val manualServers: StateFlow<List<ServerInfo>> = _manualServers.asStateFlow()

    private val _recentServers = MutableStateFlow<List<RecentServer>>(emptyList())
    /**
     * Recently connected servers for quick access. Persisted to SharedPreferences.
     */
    val recentServers: StateFlow<List<RecentServer>> = _recentServers.asStateFlow()

    /**
     * All servers combined (discovered + manual), deduplicated by address.
     */
    val allServers: List<ServerInfo>
        get() {
            val all = mutableListOf<ServerInfo>()
            val addresses = mutableSetOf<String>()

            // Add discovered servers first
            for (server in _discoveredServers.value) {
                if (server.address !in addresses) {
                    all.add(server)
                    addresses.add(server.address)
                }
            }

            // Add manual servers that aren't already discovered
            for (server in _manualServers.value) {
                if (server.address !in addresses) {
                    all.add(server)
                    addresses.add(server.address)
                }
            }

            return all
        }

    private var prefs: SharedPreferences? = null

    /**
     * Initialize the repository with a context for SharedPreferences.
     * Call this from Application.onCreate() or MainActivity.onCreate().
     */
    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadPersistedServers()
    }

    /**
     * Add a discovered server (from mDNS).
     * Does not persist - discovered servers are transient.
     */
    fun addDiscoveredServer(server: ServerInfo) {
        val current = _discoveredServers.value.toMutableList()
        if (!current.any { it.address == server.address }) {
            current.add(server)
            _discoveredServers.value = current
        }
    }

    /**
     * Remove a discovered server (e.g., when it goes offline).
     */
    fun removeDiscoveredServer(address: String) {
        _discoveredServers.value = _discoveredServers.value.filter { it.address != address }
    }

    /**
     * Clear all discovered servers.
     */
    fun clearDiscoveredServers() {
        _discoveredServers.value = emptyList()
    }

    /**
     * Add a manually entered server. Persists to SharedPreferences.
     */
    fun addManualServer(server: ServerInfo) {
        val current = _manualServers.value.toMutableList()
        if (!current.any { it.address == server.address }) {
            current.add(server)
            _manualServers.value = current
            persistManualServers()
        }
    }

    /**
     * Remove a manual server.
     */
    fun removeManualServer(address: String) {
        _manualServers.value = _manualServers.value.filter { it.address != address }
        persistManualServers()
    }

    /**
     * Record a server connection for recent history.
     * Moves the server to the front if already in history.
     */
    fun addToRecent(server: ServerInfo) {
        val current = _recentServers.value.toMutableList()

        // Remove existing entry for this address
        current.removeIf { it.address == server.address }

        // Add to front
        current.add(0, RecentServer(
            name = server.name,
            address = server.address,
            lastConnectedMs = System.currentTimeMillis()
        ))

        // Keep only most recent 10
        _recentServers.value = current.take(10)
        persistRecentServers()
    }

    /**
     * Get a server by address from any source.
     */
    fun getServer(address: String): ServerInfo? {
        return _discoveredServers.value.find { it.address == address }
            ?: _manualServers.value.find { it.address == address }
            ?: _recentServers.value.find { it.address == address }?.toServerInfo()
    }

    private fun loadPersistedServers() {
        val prefs = prefs ?: return

        // Load manual servers
        val manualJson = prefs.getString(KEY_MANUAL_SERVERS, null)
        if (manualJson != null) {
            _manualServers.value = parseServerList(manualJson)
        }

        // Load recent servers
        val recentJson = prefs.getString(KEY_RECENT_SERVERS, null)
        if (recentJson != null) {
            _recentServers.value = parseRecentServerList(recentJson)
        }
    }

    private fun persistManualServers() {
        prefs?.edit()?.putString(KEY_MANUAL_SERVERS, serializeServerList(_manualServers.value))?.apply()
    }

    private fun persistRecentServers() {
        prefs?.edit()?.putString(KEY_RECENT_SERVERS, serializeRecentServerList(_recentServers.value))?.apply()
    }

    // Simple serialization (avoiding JSON library dependency)
    private fun serializeServerList(servers: List<ServerInfo>): String {
        return servers.joinToString("|") { "${it.name}::${it.address}" }
    }

    private fun parseServerList(data: String): List<ServerInfo> {
        if (data.isBlank()) return emptyList()
        return data.split("|").mapNotNull { entry ->
            val parts = entry.split("::", limit = 2)
            if (parts.size == 2) ServerInfo(parts[0], parts[1]) else null
        }
    }

    private fun serializeRecentServerList(servers: List<RecentServer>): String {
        return servers.joinToString("|") { "${it.name}::${it.address}::${it.lastConnectedMs}" }
    }

    private fun parseRecentServerList(data: String): List<RecentServer> {
        if (data.isBlank()) return emptyList()
        return data.split("|").mapNotNull { entry ->
            val parts = entry.split("::", limit = 3)
            if (parts.size == 3) {
                RecentServer(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L)
            } else null
        }
    }
}

/**
 * A recently connected server with timestamp.
 */
data class RecentServer(
    val name: String,
    val address: String,
    val lastConnectedMs: Long
) {
    fun toServerInfo() = ServerInfo(name, address)

    /**
     * Human-readable time since last connection.
     */
    val formattedTime: String
        get() {
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
