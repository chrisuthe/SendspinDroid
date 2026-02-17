package com.sendspindroid.musicassistant

/**
 * Platform-agnostic interface for Music Assistant settings storage.
 *
 * Provides access to per-server tokens, selected player IDs, and default
 * configuration values. Android implementation uses SharedPreferences;
 * other platforms can use their native key-value stores.
 */
interface MaSettingsProvider {
    /** Gets the stored MA API token for a specific server. */
    fun getTokenForServer(serverId: String): String?

    /** Stores an MA API token for a specific server. */
    fun setTokenForServer(serverId: String, token: String)

    /** Clears the stored token for a specific server. */
    fun clearTokenForServer(serverId: String)

    /** Checks if a server has a stored token. */
    fun hasTokenForServer(serverId: String): Boolean

    /** Gets the default MA API port (default: 8095). */
    fun getDefaultPort(): Int

    /** Gets the selected MA player ID for a specific server. */
    fun getSelectedPlayerForServer(serverId: String): String?

    /** Sets the selected MA player ID for a specific server. */
    fun setSelectedPlayerForServer(serverId: String, playerId: String)

    /** Clears the selected player for a specific server. */
    fun clearSelectedPlayerForServer(serverId: String)
}
