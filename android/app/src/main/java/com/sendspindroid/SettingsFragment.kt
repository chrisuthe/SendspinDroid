package com.sendspindroid

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference

/**
 * Fragment displaying user preferences.
 * Uses AndroidX Preference library for standard settings UI.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    companion object {
        // SeekBar key (different from UserSettings key since we use 0-10000 range)
        private const val KEY_SYNC_OFFSET_SEEKBAR = "sync_offset_ms_seekbar"
        // Offset to convert seekbar value (0-10000) to actual ms (-5000 to +5000)
        private const val SEEKBAR_OFFSET = 5000
    }

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

        // Set up sync offset preference
        findPreference<SeekBarPreference>(KEY_SYNC_OFFSET_SEEKBAR)?.apply {
            // Initialize seekbar from saved offset
            val savedOffset = UserSettings.getSyncOffsetMs()
            value = savedOffset + SEEKBAR_OFFSET

            // Update summary to show actual offset value
            updateSyncOffsetSummary(this, savedOffset)

            // Listen for changes
            setOnPreferenceChangeListener { _, newValue ->
                val seekbarValue = newValue as Int
                val actualOffset = seekbarValue - SEEKBAR_OFFSET
                UserSettings.setSyncOffsetMs(actualOffset)
                updateSyncOffsetSummary(this, actualOffset)
                true
            }
        }
    }

    private fun updateSyncOffsetSummary(pref: SeekBarPreference, offsetMs: Int) {
        pref.summary = getString(R.string.pref_sync_offset_value, offsetMs)
    }
}
