package com.sendspindroid

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat

/**
 * Fragment displaying user preferences.
 * Uses AndroidX Preference library for standard settings UI.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Set up player name preference with dynamic default hint
        findPreference<EditTextPreference>(UserSettings.KEY_PLAYER_NAME)?.apply {
            // Show current value or default in summary
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()

            // Set hint to device model name
            setOnBindEditTextListener { editText ->
                editText.hint = UserSettings.getDefaultPlayerName()
            }
        }
    }
}
