package com.sendspindroid

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.preference.PreferenceManager
import com.sendspindroid.network.TransportType

/**
 * Centralized access to user settings stored in SharedPreferences.
 * Uses the default SharedPreferences file for compatibility with PreferenceFragmentCompat.
 */
object UserSettings {

    // Preference keys - must match keys in preferences.xml
    const val KEY_PLAYER_NAME = "player_name"
    const val KEY_SYNC_OFFSET_MS = "sync_offset_ms"
    const val KEY_LOW_MEMORY_MODE = "low_memory_mode"
    const val KEY_PREFERRED_CODEC = "preferred_codec"

    // Network-specific codec preference keys
    const val KEY_CODEC_WIFI = "codec_wifi"
    const val KEY_CODEC_CELLULAR = "codec_cellular"

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
     * Gets the preferred audio codec for streaming.
     * The server will be asked for this codec first; if unavailable, falls back to others.
     * Values: "pcm" (default), "flac", "opus"
     */
    fun getPreferredCodec(): String {
        return prefs?.getString(KEY_PREFERRED_CODEC, "pcm") ?: "pcm"
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
}
