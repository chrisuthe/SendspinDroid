package com.sendspindroid

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.preference.PreferenceManager

/**
 * Centralized access to user settings stored in SharedPreferences.
 * Uses the default SharedPreferences file for compatibility with PreferenceFragmentCompat.
 */
object UserSettings {

    // Preference keys - must match keys in preferences.xml
    const val KEY_PLAYER_NAME = "player_name"
    const val KEY_SYNC_OFFSET_MS = "sync_offset_ms"

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
}
