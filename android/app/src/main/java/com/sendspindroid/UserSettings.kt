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
}
