package com.sendspindroid

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.preference.PreferenceManager
import com.sendspindroid.network.TransportType
import java.util.UUID

/**
 * Centralized access to user settings stored in SharedPreferences.
 * Uses the default SharedPreferences file for compatibility with PreferenceFragmentCompat.
 */
object UserSettings {

    // Preference keys - must match keys in preferences.xml
    const val KEY_PLAYER_ID = "player_id"
    const val KEY_PLAYER_NAME = "player_name"
    const val KEY_SYNC_OFFSET_MS = "sync_offset_ms"
    const val KEY_LOW_MEMORY_MODE = "low_memory_mode"
    const val KEY_PREFERRED_CODEC = "preferred_codec"
    const val KEY_FULL_SCREEN_MODE = "full_screen_mode"
    const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

    // Network-specific codec preference keys
    const val KEY_CODEC_WIFI = "codec_wifi"
    const val KEY_CODEC_CELLULAR = "codec_cellular"

    // Remote access preference keys
    const val KEY_REMOTE_SERVERS = "remote_servers"
    const val KEY_LAST_CONNECTION_MODE = "last_connection_mode"
    const val KEY_LAST_REMOTE_ID = "last_remote_id"

    // Proxy access preference keys
    const val KEY_PROXY_SERVERS = "proxy_servers"
    const val KEY_LAST_PROXY_URL = "last_proxy_url"

    // Sync offset range limits (milliseconds)
    const val SYNC_OFFSET_MIN = -5000
    const val SYNC_OFFSET_MAX = 5000
    const val SYNC_OFFSET_DEFAULT = 0

    private var prefs: SharedPreferences? = null

    /**
     * Initialize UserSettings with application context.
     * Must be called before accessing settings, typically in Application.onCreate() or MainActivity.onCreate().
     */
    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        }
    }

    /**
     * Gets the user-configured player name, or the device model as default.
     * This name is sent to the SendSpin server to identify this player.
     */
    fun getPlayerName(): String {
        val savedName = prefs?.getString(KEY_PLAYER_NAME, null)
        return if (savedName.isNullOrBlank()) {
            Build.MODEL
        } else {
            savedName
        }
    }

    /**
     * Sets the player name.
     */
    fun setPlayerName(name: String) {
        prefs?.edit()?.putString(KEY_PLAYER_NAME, name)?.apply()
    }

    /**
     * Gets the persistent player ID, generating one if it doesn't exist.
     * This ID is stable across app launches and name changes, allowing the server
     * to consistently identify this player.
     */
    fun getPlayerId(): String {
        val saved = prefs?.getString(KEY_PLAYER_ID, null)
        return if (saved.isNullOrBlank()) {
            val newId = UUID.randomUUID().toString()
            setPlayerId(newId)
            newId
        } else {
            saved
        }
    }

    /**
     * Sets the player ID (typically only called internally on first launch).
     */
    fun setPlayerId(id: String) {
        prefs?.edit()?.putString(KEY_PLAYER_ID, id)?.apply()
    }

    /**
     * Gets the default player name (device model).
     * Used as placeholder/hint in settings UI.
     */
    fun getDefaultPlayerName(): String = Build.MODEL

    /**
     * Gets the manual sync offset in milliseconds.
     * Positive = delay playback (plays later), Negative = advance (plays earlier).
     */
    fun getSyncOffsetMs(): Int {
        return prefs?.getInt(KEY_SYNC_OFFSET_MS, SYNC_OFFSET_DEFAULT) ?: SYNC_OFFSET_DEFAULT
    }

    /**
     * Sets the manual sync offset in milliseconds.
     */
    fun setSyncOffsetMs(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(SYNC_OFFSET_MIN, SYNC_OFFSET_MAX)
        prefs?.edit()?.putInt(KEY_SYNC_OFFSET_MS, clamped)?.apply()
    }

    /**
     * Whether Low Memory Mode is enabled.
     * When enabled:
     * - Album artwork is not fetched (uses placeholder)
     * - Audio buffer is reduced from 32MB to 8MB
     * - Coil ImageLoader is not initialized
     * Use when controlling playback from the server and UI isn't needed.
     */
    val lowMemoryMode: Boolean
        get() = prefs?.getBoolean(KEY_LOW_MEMORY_MODE, false) ?: false

    /**
     * Whether Full Screen Mode is enabled.
     * When enabled, the status bar and navigation bar are hidden.
     * Users can reveal them by swiping from screen edges.
     */
    val fullScreenMode: Boolean
        get() = prefs?.getBoolean(KEY_FULL_SCREEN_MODE, false) ?: false

    /**
     * Whether Keep Screen On is enabled.
     * When enabled and audio is playing, the screen won't dim or lock.
     */
    val keepScreenOn: Boolean
        get() = prefs?.getBoolean(KEY_KEEP_SCREEN_ON, false) ?: false

    /**
     * Gets the preferred audio codec for streaming.
     * The server will be asked for this codec first; PCM is always used as fallback.
     * Values: "opus" (default), "flac"
     */
    fun getPreferredCodec(): String {
        return prefs?.getString(KEY_PREFERRED_CODEC, "opus") ?: "opus"
    }

    /**
     * Sets the preferred audio codec for streaming.
     */
    fun setPreferredCodec(codec: String) {
        prefs?.edit()?.putString(KEY_PREFERRED_CODEC, codec)?.apply()
    }

    /**
     * Gets the preferred codec for a specific network type.
     * Falls back to the global preferred codec if no network-specific preference is set.
     *
     * @param transportType The current network transport type
     * @return The codec to use (pcm, flac, or opus)
     */
    fun getCodecForNetwork(transportType: TransportType): String {
        val key = when (transportType) {
            TransportType.WIFI -> KEY_CODEC_WIFI
            TransportType.CELLULAR -> KEY_CODEC_CELLULAR
            else -> KEY_PREFERRED_CODEC
        }
        // Fall back to global preferred codec if network-specific not set
        return prefs?.getString(key, null) ?: getPreferredCodec()
    }

    /**
     * Sets the preferred codec for a specific network type.
     *
     * @param transportType The network transport type
     * @param codec The codec to use (pcm, flac, or opus)
     */
    fun setCodecForNetwork(transportType: TransportType, codec: String) {
        val key = when (transportType) {
            TransportType.WIFI -> KEY_CODEC_WIFI
            TransportType.CELLULAR -> KEY_CODEC_CELLULAR
            else -> KEY_PREFERRED_CODEC
        }
        prefs?.edit()?.putString(key, codec)?.apply()
    }

    // ========== Remote Access Settings ==========

    /**
     * Connection mode for the app.
     */
    enum class ConnectionMode {
        LOCAL,   // Direct WebSocket connection on local network
        REMOTE,  // WebRTC connection via Music Assistant Remote Access
        PROXY    // WebSocket via authenticated reverse proxy
    }

    /**
     * Gets the last used connection mode.
     * Defaults to LOCAL for first-time users.
     */
    fun getLastConnectionMode(): ConnectionMode {
        val modeStr = prefs?.getString(KEY_LAST_CONNECTION_MODE, null)
        return try {
            if (modeStr != null) ConnectionMode.valueOf(modeStr) else ConnectionMode.LOCAL
        } catch (e: Exception) {
            ConnectionMode.LOCAL
        }
    }

    /**
     * Sets the last used connection mode.
     */
    fun setLastConnectionMode(mode: ConnectionMode) {
        prefs?.edit()?.putString(KEY_LAST_CONNECTION_MODE, mode.name)?.apply()
    }

    /**
     * Gets the last used Remote ID for quick reconnection.
     */
    fun getLastRemoteId(): String? {
        return prefs?.getString(KEY_LAST_REMOTE_ID, null)
    }

    /**
     * Sets the last used Remote ID.
     */
    fun setLastRemoteId(remoteId: String?) {
        prefs?.edit()?.putString(KEY_LAST_REMOTE_ID, remoteId)?.apply()
    }

    /**
     * Gets all saved remote servers with nicknames.
     * Format: "remoteId::nickname::timestamp" separated by "|"
     *
     * @return List of saved remote servers ordered by most recently used
     */
    fun getSavedRemoteServers(): List<SavedRemoteServer> {
        val data = prefs?.getString(KEY_REMOTE_SERVERS, null) ?: return emptyList()
        return data.split("|")
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val parts = entry.split("::")
                if (parts.size >= 2) {
                    SavedRemoteServer(
                        remoteId = parts[0],
                        nickname = parts[1],
                        lastConnectedMs = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    )
                } else null
            }
            .sortedByDescending { it.lastConnectedMs }
    }

    /**
     * Saves or updates a remote server entry.
     * If the Remote ID already exists, updates its nickname and timestamp.
     */
    fun saveRemoteServer(remoteId: String, nickname: String) {
        val existing = getSavedRemoteServers().toMutableList()

        // Remove existing entry with same Remote ID
        existing.removeAll { it.remoteId == remoteId }

        // Add new/updated entry at the front
        existing.add(0, SavedRemoteServer(remoteId, nickname, System.currentTimeMillis()))

        // Keep only the most recent 10 entries
        val limited = existing.take(10)

        // Serialize and save
        val serialized = limited.joinToString("|") { "${it.remoteId}::${it.nickname}::${it.lastConnectedMs}" }
        prefs?.edit()?.putString(KEY_REMOTE_SERVERS, serialized)?.apply()
    }

    /**
     * Updates the last connected timestamp for a remote server.
     */
    fun touchRemoteServer(remoteId: String) {
        val existing = getSavedRemoteServers().toMutableList()
        val index = existing.indexOfFirst { it.remoteId == remoteId }
        if (index >= 0) {
            val server = existing.removeAt(index)
            existing.add(0, server.copy(lastConnectedMs = System.currentTimeMillis()))

            val serialized = existing.joinToString("|") { "${it.remoteId}::${it.nickname}::${it.lastConnectedMs}" }
            prefs?.edit()?.putString(KEY_REMOTE_SERVERS, serialized)?.apply()
        }
    }

    /**
     * Removes a saved remote server.
     */
    fun removeRemoteServer(remoteId: String) {
        val existing = getSavedRemoteServers().filter { it.remoteId != remoteId }
        val serialized = existing.joinToString("|") { "${it.remoteId}::${it.nickname}::${it.lastConnectedMs}" }
        prefs?.edit()?.putString(KEY_REMOTE_SERVERS, serialized)?.apply()
    }

    /**
     * Data class for saved remote server information.
     */
    data class SavedRemoteServer(
        val remoteId: String,
        val nickname: String,
        val lastConnectedMs: Long = 0L
    ) {
        /**
         * Formatted Remote ID with dashes for display (e.g., "VVPN3-TLP34-YMGIZ-DINCE-KQKSI-R")
         */
        val formattedId: String
            get() = if (remoteId.length == 26) remoteId.chunked(5).joinToString("-") else remoteId

        /**
         * Human-readable time since last connection.
         */
        val lastConnectedAgo: String
            get() {
                if (lastConnectedMs == 0L) return "Never"
                val diffMs = System.currentTimeMillis() - lastConnectedMs
                val seconds = diffMs / 1000
                val minutes = seconds / 60
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

    // ========== Proxy Access Settings ==========

    /**
     * Gets the last used proxy URL for quick reconnection.
     */
    fun getLastProxyUrl(): String? {
        return prefs?.getString(KEY_LAST_PROXY_URL, null)
    }

    /**
     * Sets the last used proxy URL.
     */
    fun setLastProxyUrl(url: String?) {
        prefs?.edit()?.putString(KEY_LAST_PROXY_URL, url)?.apply()
    }

    /**
     * Gets all saved proxy servers with nicknames and tokens.
     * Format: "url::nickname::token::timestamp::username" separated by "|"
     *
     * Note: Tokens are stored in plain text. For production, consider using
     * EncryptedSharedPreferences for sensitive data. Passwords are NEVER stored.
     *
     * @return List of saved proxy servers ordered by most recently used
     */
    fun getSavedProxyServers(): List<SavedProxyServer> {
        val data = prefs?.getString(KEY_PROXY_SERVERS, null) ?: return emptyList()
        return data.split("|")
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val parts = entry.split("::")
                if (parts.size >= 3) {
                    SavedProxyServer(
                        url = parts[0],
                        nickname = parts[1],
                        authToken = parts[2],
                        lastConnectedMs = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
                        username = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
                    )
                } else null
            }
            .sortedByDescending { it.lastConnectedMs }
    }

    /**
     * Saves or updates a proxy server entry.
     * If the URL already exists, updates its nickname, token, and timestamp.
     *
     * @param url The proxy server URL
     * @param nickname User-friendly name for the server
     * @param authToken The authentication token (never store password!)
     * @param username Optional username for re-login convenience
     */
    fun saveProxyServer(url: String, nickname: String, authToken: String, username: String? = null) {
        val existing = getSavedProxyServers().toMutableList()

        // Remove existing entry with same URL
        existing.removeAll { it.url == url }

        // Add new/updated entry at the front
        existing.add(0, SavedProxyServer(url, nickname, authToken, System.currentTimeMillis(), username))

        // Keep only the most recent 10 entries
        val limited = existing.take(10)

        // Serialize and save
        val serialized = limited.joinToString("|") {
            "${it.url}::${it.nickname}::${it.authToken}::${it.lastConnectedMs}::${it.username ?: ""}"
        }
        prefs?.edit()?.putString(KEY_PROXY_SERVERS, serialized)?.apply()
    }

    /**
     * Updates the last connected timestamp for a proxy server.
     */
    fun touchProxyServer(url: String) {
        val existing = getSavedProxyServers().toMutableList()
        val index = existing.indexOfFirst { it.url == url }
        if (index >= 0) {
            val server = existing.removeAt(index)
            existing.add(0, server.copy(lastConnectedMs = System.currentTimeMillis()))

            val serialized = existing.joinToString("|") {
                "${it.url}::${it.nickname}::${it.authToken}::${it.lastConnectedMs}::${it.username ?: ""}"
            }
            prefs?.edit()?.putString(KEY_PROXY_SERVERS, serialized)?.apply()
        }
    }

    /**
     * Removes a saved proxy server.
     */
    fun removeProxyServer(url: String) {
        val existing = getSavedProxyServers().filter { it.url != url }
        val serialized = existing.joinToString("|") {
            "${it.url}::${it.nickname}::${it.authToken}::${it.lastConnectedMs}::${it.username ?: ""}"
        }
        prefs?.edit()?.putString(KEY_PROXY_SERVERS, serialized)?.apply()
    }

    /**
     * Data class for saved proxy server information.
     *
     * @property url The proxy server URL
     * @property nickname User-friendly name for the server
     * @property authToken The authentication token (passwords are NEVER stored)
     * @property lastConnectedMs Timestamp of last connection
     * @property username Optional username for re-login convenience (password not stored)
     */
    data class SavedProxyServer(
        val url: String,
        val nickname: String,
        val authToken: String,
        val lastConnectedMs: Long = 0L,
        val username: String? = null
    ) {
        /**
         * Shortened URL for display (removes https:// and trailing path).
         */
        val displayUrl: String
            get() = url
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("wss://")
                .removePrefix("ws://")

        /**
         * Human-readable time since last connection.
         */
        val lastConnectedAgo: String
            get() {
                if (lastConnectedMs == 0L) return "Never"
                val diffMs = System.currentTimeMillis() - lastConnectedMs
                val seconds = diffMs / 1000
                val minutes = seconds / 60
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
}
