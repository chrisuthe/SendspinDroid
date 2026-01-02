package com.sendspindroid

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.sendspindroid.debug.DebugLogger

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

        // Debug logging keys
        private const val KEY_DEBUG_LOGGING = "debug_logging_enabled"
        private const val KEY_EXPORT_LOGS = "export_debug_logs"

        // Update interval for debug log sample count display
        private const val DEBUG_STATS_UPDATE_INTERVAL_MS = 2000L
    }

    // Handler for periodic updates of debug log sample count
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

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

        // Set up debug logging toggle
        findPreference<SwitchPreferenceCompat>(KEY_DEBUG_LOGGING)?.apply {
            // Initialize from current state
            isChecked = DebugLogger.isEnabled
            updateDebugLoggingSummary(this)

            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                DebugLogger.isEnabled = enabled

                if (!enabled) {
                    // Clear logs when disabled
                    DebugLogger.clear()
                }

                updateDebugLoggingSummary(this)
                updateExportLogsSummary()
                true
            }
        }

        // Set up export logs button
        findPreference<Preference>(KEY_EXPORT_LOGS)?.apply {
            updateExportLogsSummary()

            setOnPreferenceClickListener {
                exportDebugLogs()
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Start periodic updates for debug log sample count
        startDebugStatsUpdates()
    }

    override fun onPause() {
        super.onPause()
        // Stop periodic updates
        stopDebugStatsUpdates()
    }

    private fun startDebugStatsUpdates() {
        updateRunnable = object : Runnable {
            override fun run() {
                updateDebugLoggingSummary(findPreference(KEY_DEBUG_LOGGING))
                updateExportLogsSummary()
                handler.postDelayed(this, DEBUG_STATS_UPDATE_INTERVAL_MS)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun stopDebugStatsUpdates() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
    }

    private fun updateSyncOffsetSummary(pref: SeekBarPreference, offsetMs: Int) {
        pref.summary = getString(R.string.pref_sync_offset_value, offsetMs)
    }

    private fun updateDebugLoggingSummary(pref: SwitchPreferenceCompat?) {
        pref ?: return
        pref.summary = if (DebugLogger.isEnabled) {
            getString(R.string.pref_debug_logging_summary_on, DebugLogger.getSampleCount())
        } else {
            getString(R.string.pref_debug_logging_summary_off)
        }
    }

    private fun updateExportLogsSummary() {
        findPreference<Preference>(KEY_EXPORT_LOGS)?.apply {
            val sampleCount = DebugLogger.getSampleCount()
            summary = if (sampleCount > 0) {
                getString(R.string.pref_export_logs_summary)
            } else {
                getString(R.string.pref_export_logs_summary_empty)
            }
            isEnabled = sampleCount > 0
        }
    }

    private fun exportDebugLogs() {
        val context = context ?: return

        // Clean up old logs first
        DebugLogger.cleanupOldLogs(context)

        // Create share intent
        val shareIntent = DebugLogger.createShareIntent(context)

        if (shareIntent != null) {
            val chooserIntent = Intent.createChooser(
                shareIntent,
                getString(R.string.debug_share_chooser_title)
            )
            startActivity(chooserIntent)
            Toast.makeText(context, R.string.debug_log_exported, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, R.string.debug_log_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
