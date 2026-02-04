package com.sendspindroid.musicassistant

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-server token storage for Music Assistant API authentication.
 *
 * Stores MA API tokens separately from the main server configuration to:
 * - Allow token refresh without touching server configs
 * - Keep sensitive auth data in a dedicated preferences file
 * - Support clearing tokens independently
 *
 * ## Usage
 * ```kotlin
 * MaSettings.initialize(context)
 *
 * // Store token after login
 * MaSettings.setTokenForServer(serverId, token)
 *
 * // Retrieve token for API calls
 * val token = MaSettings.getTokenForServer(serverId)
 * ```
 */
object MaSettings {

    private const val PREFS_NAME = "ma_settings"
    private const val KEY_TOKEN_PREFIX = "token_"
    private const val KEY_DEFAULT_PORT = "default_port"
    private const val KEY_SELECTED_PLAYER_PREFIX = "selected_player_"

    private const val DEFAULT_MA_PORT = 8095

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Initialize MaSettings with application context.
     * Must be called before accessing settings.
     */
    fun initialize(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext.getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                }
            }
        }
    }

    /**
     * Gets the stored MA API token for a specific server.
     *
     * @param serverId The UnifiedServer.id
     * @return The stored token, or null if not set
     */
    fun getTokenForServer(serverId: String): String? {
        return prefs?.getString("$KEY_TOKEN_PREFIX$serverId", null)
    }

    /**
     * Stores an MA API token for a specific server.
     *
     * @param serverId The UnifiedServer.id
     * @param token The MA API access token
     */
    fun setTokenForServer(serverId: String, token: String) {
        prefs?.edit()?.putString("$KEY_TOKEN_PREFIX$serverId", token)?.apply()
    }

    /**
     * Clears the stored token for a specific server.
     * Call this when the token expires or user logs out.
     *
     * @param serverId The UnifiedServer.id
     */
    fun clearTokenForServer(serverId: String) {
        prefs?.edit()?.remove("$KEY_TOKEN_PREFIX$serverId")?.apply()
    }

    /**
     * Gets the default MA API port.
     * Used when deriving API URL from local connection address.
     *
     * @return The port number (default: 8095)
     */
    fun getDefaultPort(): Int {
        return prefs?.getInt(KEY_DEFAULT_PORT, DEFAULT_MA_PORT) ?: DEFAULT_MA_PORT
    }

    /**
     * Sets the default MA API port.
     * Only change if your MA installation uses a non-standard port.
     *
     * @param port The MA API port number
     */
    fun setDefaultPort(port: Int) {
        prefs?.edit()?.putInt(KEY_DEFAULT_PORT, port)?.apply()
    }

    /**
     * Checks if a server has a stored token.
     *
     * @param serverId The UnifiedServer.id
     * @return true if a token exists for this server
     */
    fun hasTokenForServer(serverId: String): Boolean {
        return getTokenForServer(serverId) != null
    }

    /**
     * Clears all stored tokens.
     * Use with caution - this logs out from all MA servers.
     */
    fun clearAllTokens() {
        prefs?.let { p ->
            val editor = p.edit()
            p.all.keys
                .filter { it.startsWith(KEY_TOKEN_PREFIX) }
                .forEach { editor.remove(it) }
            editor.apply()
        }
    }

    /**
     * Gets the selected MA player ID for a specific server.
     * This player will be used as the target for playback commands.
     *
     * @param serverId The UnifiedServer.id
     * @return The stored player ID, or null if not set (will use auto-detection)
     */
    fun getSelectedPlayerForServer(serverId: String): String? {
        return prefs?.getString("$KEY_SELECTED_PLAYER_PREFIX$serverId", null)
    }

    /**
     * Sets the selected MA player ID for a specific server.
     * Playback commands will target this player's queue.
     *
     * @param serverId The UnifiedServer.id
     * @param playerId The MA player_id to use for playback
     */
    fun setSelectedPlayerForServer(serverId: String, playerId: String) {
        prefs?.edit()?.putString("$KEY_SELECTED_PLAYER_PREFIX$serverId", playerId)?.apply()
    }

    /**
     * Clears the selected player for a specific server.
     * Playback will fall back to auto-detecting an available player.
     *
     * @param serverId The UnifiedServer.id
     */
    fun clearSelectedPlayerForServer(serverId: String) {
        prefs?.edit()?.remove("$KEY_SELECTED_PLAYER_PREFIX$serverId")?.apply()
    }
}
