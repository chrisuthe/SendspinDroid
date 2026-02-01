package com.sendspindroid

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.sendspindroid.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Singleton repository for managing unified servers.
 *
 * Unified servers combine multiple connection methods (local, remote, proxy)
 * into a single entity. The repository manages both persisted servers and
 * transient discovered servers.
 *
 * ## Serialization Format
 * Uses pipe-delimited format to avoid JSON dependency:
 * `id;;name;;timestamp;;pref;;local;;remote;;proxy;;isDefault`
 * Where each connection is encoded as:
 * - local: `address::path`
 * - remote: `remoteId`
 * - proxy: `url::token::username`
 * - isDefault: `1` or `0` (boolean)
 *
 * ## Usage
 * ```kotlin
 * UnifiedServerRepository.initialize(context)
 *
 * // Add a saved server
 * UnifiedServerRepository.saveServer(server)
 *
 * // Observe servers reactively
 * lifecycleScope.launch {
 *     UnifiedServerRepository.allServers.collect { servers ->
 *         updateUI(servers)
 *     }
 * }
 * ```
 */
object UnifiedServerRepository {

    private const val TAG = "UnifiedServerRepo"
    private const val PREFS_NAME = "unified_server_repository"
    private const val KEY_SAVED_SERVERS = "saved_servers"

    // Field separators (chosen to be unlikely in user data)
    private const val SERVER_SEPARATOR = "|"
    private const val FIELD_SEPARATOR = ";;"
    private const val SUBFIELD_SEPARATOR = "::"

    private val _savedServers = MutableStateFlow<List<UnifiedServer>>(emptyList())
    /**
     * Persisted unified servers. Saved to SharedPreferences.
     */
    val savedServers: StateFlow<List<UnifiedServer>> = _savedServers.asStateFlow()

    private val _discoveredServers = MutableStateFlow<List<UnifiedServer>>(emptyList())
    /**
     * Servers discovered via mDNS. Transient, not persisted.
     * These have isDiscovered=true and only local connection configured.
     */
    val discoveredServers: StateFlow<List<UnifiedServer>> = _discoveredServers.asStateFlow()

    /**
     * All servers combined (saved + discovered), deduplicated.
     * Discovered servers that match a saved server by local address are merged.
     */
    val allServers: StateFlow<List<UnifiedServer>>
        get() {
            // Return a combined flow - for now computed on access
            // In a more complex implementation, this would be a derived StateFlow
            return _allServersFlow.asStateFlow()
        }

    private val _allServersFlow = MutableStateFlow<List<UnifiedServer>>(emptyList())

    private val _onlineSavedServerIds = MutableStateFlow<Set<String>>(emptySet())
    /**
     * IDs of saved servers that are currently discovered on the local network.
     * Use this to show "online" status indicators on saved servers.
     */
    val onlineSavedServerIds: StateFlow<Set<String>> = _onlineSavedServerIds.asStateFlow()

    private val _filteredDiscoveredServers = MutableStateFlow<List<UnifiedServer>>(emptyList())
    /**
     * Discovered servers filtered to exclude those that match saved servers.
     * Use this for the "Nearby Servers" section to avoid duplicates.
     */
    val filteredDiscoveredServers: StateFlow<List<UnifiedServer>> = _filteredDiscoveredServers.asStateFlow()

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var persistedDataLoaded = false

    private val prefs: SharedPreferences?
        get() {
            val ctx = applicationContext ?: return null
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

    /**
     * Initialize the repository with a context for SharedPreferences.
     * Safe to call multiple times - only first call takes effect.
     */
    fun initialize(context: Context) {
        if (applicationContext == null) {
            synchronized(this) {
                if (applicationContext == null) {
                    applicationContext = context.applicationContext
                }
            }
        }
        ensurePersistedDataLoaded()
    }

    private fun ensurePersistedDataLoaded() {
        if (!persistedDataLoaded && applicationContext != null) {
            synchronized(this) {
                if (!persistedDataLoaded) {
                    loadPersistedServers()
                    persistedDataLoaded = true
                }
            }
        }
    }

    /**
     * Save a new unified server or update an existing one.
     * Server is identified by its ID.
     */
    fun saveServer(server: UnifiedServer) {
        ensurePersistedDataLoaded()
        val current = _savedServers.value.toMutableList()

        // Remove existing entry with same ID
        current.removeIf { it.id == server.id }

        // Add the new/updated server (with isDiscovered=false for saved servers)
        current.add(server.copy(isDiscovered = false))

        _savedServers.value = current
        updateCombinedServers()
        persistServers()

        Log.d(TAG, "Saved server: ${server.name} (${server.id})")
    }

    /**
     * Update the last connected timestamp for a server.
     */
    fun updateLastConnected(serverId: String) {
        ensurePersistedDataLoaded()
        val current = _savedServers.value.toMutableList()

        val index = current.indexOfFirst { it.id == serverId }
        if (index >= 0) {
            current[index] = current[index].copy(lastConnectedMs = System.currentTimeMillis())
            _savedServers.value = current
            updateCombinedServers()
            persistServers()
        }
    }

    /**
     * Delete a saved server by ID.
     */
    fun deleteServer(serverId: String) {
        ensurePersistedDataLoaded()
        _savedServers.value = _savedServers.value.filter { it.id != serverId }
        updateCombinedServers()
        persistServers()

        Log.d(TAG, "Deleted server: $serverId")
    }

    /**
     * Get a server by ID from saved or discovered servers.
     */
    fun getServer(serverId: String): UnifiedServer? {
        ensurePersistedDataLoaded()
        return _savedServers.value.find { it.id == serverId }
            ?: _discoveredServers.value.find { it.id == serverId }
    }

    /**
     * Add a discovered server from mDNS.
     * If a saved server exists with the same local address, it's not duplicated.
     */
    fun addDiscoveredServer(name: String, address: String, path: String = "/sendspin") {
        val current = _discoveredServers.value.toMutableList()

        // Check if already discovered
        if (current.any { it.local?.address == address }) {
            return
        }

        // Create transient unified server
        val server = UnifiedServer(
            id = "discovered-$address",
            name = name,
            local = LocalConnection(address, path),
            isDiscovered = true
        )

        current.add(server)
        _discoveredServers.value = current
        updateCombinedServers()

        Log.d(TAG, "Discovered server: $name at $address")
    }

    /**
     * Remove a discovered server when it goes offline.
     */
    fun removeDiscoveredServer(address: String) {
        _discoveredServers.value = _discoveredServers.value.filter { it.local?.address != address }
        updateCombinedServers()
    }

    /**
     * Clear all discovered servers.
     */
    fun clearDiscoveredServers() {
        _discoveredServers.value = emptyList()
        updateCombinedServers()
    }

    /**
     * Convert a discovered server to a saved server.
     * Used when user chooses to save after successful connection.
     *
     * @return The saved server with a new persistent ID
     */
    fun promoteDiscoveredServer(discoveredServer: UnifiedServer, name: String? = null): UnifiedServer {
        val savedServer = discoveredServer.copy(
            id = UUID.randomUUID().toString(),
            name = name ?: discoveredServer.name,
            isDiscovered = false,
            lastConnectedMs = System.currentTimeMillis()
        )
        saveServer(savedServer)

        // Remove from discovered list
        _discoveredServers.value = _discoveredServers.value.filter { it.id != discoveredServer.id }
        updateCombinedServers()

        return savedServer
    }

    /**
     * Generate a new UUID for a server.
     */
    fun generateId(): String = UUID.randomUUID().toString()

    /**
     * Set a server as the default (auto-connect on app launch).
     * Only one server can be the default at a time.
     *
     * @param serverId The ID of the server to make default, or null to clear the default
     */
    fun setDefaultServer(serverId: String?) {
        ensurePersistedDataLoaded()
        val current = _savedServers.value.toMutableList()

        // Update all servers: clear existing default, set new one
        val updated = current.map { server ->
            when {
                server.id == serverId -> server.copy(isDefaultServer = true)
                server.isDefaultServer -> server.copy(isDefaultServer = false)
                else -> server
            }
        }

        _savedServers.value = updated
        updateCombinedServers()
        persistServers()

        Log.d(TAG, "Set default server: ${serverId ?: "none"}")
    }

    /**
     * Get the current default server, if any.
     */
    fun getDefaultServer(): UnifiedServer? {
        ensurePersistedDataLoaded()
        return _savedServers.value.find { it.isDefaultServer }
    }

    private fun updateCombinedServers() {
        val saved = _savedServers.value
        val discovered = _discoveredServers.value

        // Build a map of local addresses to saved server IDs for matching
        val savedAddressToId = saved
            .filter { it.local != null }
            .associate { it.local!!.address to it.id }

        // Get addresses of discovered servers
        val discoveredAddresses = discovered.mapNotNull { it.local?.address }.toSet()

        // Find saved servers that are currently online (discovered on network)
        val onlineIds = saved
            .filter { server ->
                server.local?.address in discoveredAddresses
            }
            .map { it.id }
            .toSet()

        _onlineSavedServerIds.value = onlineIds

        // Filter out discovered servers that match a saved server's local address
        val uniqueDiscovered = discovered.filter { server ->
            server.local?.address !in savedAddressToId.keys
        }

        _filteredDiscoveredServers.value = uniqueDiscovered

        // Combine: saved servers first, then unique discovered servers
        _allServersFlow.value = saved + uniqueDiscovered

        if (onlineIds.isNotEmpty()) {
            Log.d(TAG, "Online saved servers: ${onlineIds.size}")
        }
    }

    // ========== Persistence ==========

    private fun loadPersistedServers() {
        val prefs = prefs ?: return
        val data = prefs.getString(KEY_SAVED_SERVERS, null)
        if (data != null) {
            _savedServers.value = parseServers(data)
            updateCombinedServers()
            Log.d(TAG, "Loaded ${_savedServers.value.size} saved servers")
        }
    }

    private fun persistServers() {
        prefs?.edit()?.putString(KEY_SAVED_SERVERS, serializeServers(_savedServers.value))?.apply()
    }

    /**
     * Serialize servers to pipe-delimited format.
     * Format: `id;;name;;timestamp;;pref;;local;;remote;;proxy;;isDefault`
     * Where each connection component uses :: as subfield separator.
     */
    private fun serializeServers(servers: List<UnifiedServer>): String {
        return servers.joinToString(SERVER_SEPARATOR) { server ->
            val localStr = server.local?.let { "${it.address}${SUBFIELD_SEPARATOR}${it.path}" } ?: ""
            val remoteStr = server.remote?.remoteId ?: ""
            val proxyStr = server.proxy?.let {
                "${it.url}${SUBFIELD_SEPARATOR}${it.authToken}${SUBFIELD_SEPARATOR}${it.username ?: ""}"
            } ?: ""

            listOf(
                server.id,
                server.name,
                server.lastConnectedMs.toString(),
                server.connectionPreference.name,
                localStr,
                remoteStr,
                proxyStr,
                if (server.isDefaultServer) "1" else "0"
            ).joinToString(FIELD_SEPARATOR)
        }
    }

    /**
     * Parse servers from pipe-delimited format.
     * Supports both old 7-field format and new 8-field format with isDefault.
     */
    private fun parseServers(data: String): List<UnifiedServer> {
        if (data.isBlank()) return emptyList()

        return data.split(SERVER_SEPARATOR).mapNotNull { serverStr ->
            try {
                val fields = serverStr.split(FIELD_SEPARATOR)
                if (fields.size < 7) return@mapNotNull null

                val id = fields[0]
                val name = fields[1]
                val timestamp = fields[2].toLongOrNull() ?: 0L
                val pref = try {
                    ConnectionPreference.valueOf(fields[3])
                } catch (e: IllegalArgumentException) {
                    ConnectionPreference.AUTO
                }

                // Parse local connection
                val local = fields[4].takeIf { it.isNotEmpty() }?.let { localStr ->
                    val parts = localStr.split(SUBFIELD_SEPARATOR, limit = 2)
                    LocalConnection(
                        address = parts[0],
                        path = parts.getOrElse(1) { "/sendspin" }
                    )
                }

                // Parse remote connection
                val remote = fields[5].takeIf { it.isNotEmpty() }?.let { remoteId ->
                    com.sendspindroid.model.RemoteConnection(remoteId)
                }

                // Parse proxy connection
                val proxy = fields[6].takeIf { it.isNotEmpty() }?.let { proxyStr ->
                    val parts = proxyStr.split(SUBFIELD_SEPARATOR, limit = 3)
                    if (parts.size >= 2) {
                        ProxyConnection(
                            url = parts[0],
                            authToken = parts[1],
                            username = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
                        )
                    } else null
                }

                // Parse isDefault (8th field, defaults to false for backward compatibility)
                val isDefault = fields.getOrNull(7) == "1"

                UnifiedServer(
                    id = id,
                    name = name,
                    lastConnectedMs = timestamp,
                    connectionPreference = pref,
                    local = local,
                    remote = remote,
                    proxy = proxy,
                    isDiscovered = false,
                    isDefaultServer = isDefault
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse server entry: $serverStr", e)
                null
            }
        }
    }
}
