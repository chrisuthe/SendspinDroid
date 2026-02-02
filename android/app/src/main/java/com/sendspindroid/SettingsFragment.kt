package com.sendspindroid

import android.content.ComponentName
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import androidx.core.content.getSystemService
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.MoreExecutors
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sendspindroid.debug.DebugLogger
import com.sendspindroid.playback.PlaybackService
import kotlin.system.exitProcess

// Note: SyncOffsetPreference is a custom preference that handles its own UI

/**
 * Fragment displaying user preferences.
 * Uses AndroidX Preference library for standard settings UI.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    companion object {
        private const val TAG = "SettingsFragment"

        // Debug logging keys
        private const val KEY_DEBUG_LOGGING = "debug_logging_enabled"
        private const val KEY_EXPORT_LOGS = "export_debug_logs"

        // About keys
        private const val KEY_APP_VERSION = "app_version"

        // Update interval for debug log sample count display
        private const val DEBUG_STATS_UPDATE_INTERVAL_MS = 2000L

        // Broadcast action for debug logging toggle
        const val ACTION_DEBUG_LOGGING_CHANGED = "com.sendspindroid.ACTION_DEBUG_LOGGING_CHANGED"
        const val EXTRA_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"

        // Broadcast action for Kalman dimension change
        const val ACTION_KALMAN_DIMENSION_CHANGED = "com.sendspindroid.ACTION_KALMAN_DIMENSION_CHANGED"
        const val EXTRA_KALMAN_DIMENSION = "kalman_dimension"
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

        // Sync offset preference is handled by SyncOffsetPreference custom class

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

                // Broadcast to PlaybackService to start/stop logging loop
                val intent = Intent(ACTION_DEBUG_LOGGING_CHANGED).apply {
                    putExtra(EXTRA_DEBUG_LOGGING_ENABLED, enabled)
                }
                LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(intent)

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

        // Set up low memory mode toggle with restart dialog
        findPreference<SwitchPreferenceCompat>(UserSettings.KEY_LOW_MEMORY_MODE)?.apply {
            setOnPreferenceChangeListener { _, _ ->
                // Show restart dialog after preference is changed
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.pref_low_memory_restart_title)
                    .setMessage(R.string.pref_low_memory_restart_message)
                    .setPositiveButton(R.string.pref_low_memory_restart_now) { _, _ ->
                        // Restart the app
                        val intent = requireContext().packageManager
                            .getLaunchIntentForPackage(requireContext().packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        requireContext().startActivity(intent)
                        exitProcess(0)
                    }
                    .setNegativeButton(R.string.pref_low_memory_restart_later, null)
                    .show()
                true  // Accept the preference change
            }
        }

        // Set up preferred codec with reconnect dialog
        findPreference<ListPreference>(UserSettings.KEY_PREFERRED_CODEC)?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val codec = newValue as String
                Log.i(TAG, "Preferred codec changed to: $codec")

                // Show reconnect dialog after preference is changed
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.pref_codec_reconnect_title)
                    .setMessage(R.string.pref_codec_reconnect_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true  // Accept the preference change
            }
        }

        // Set up Kalman dimension with dynamic summary and network hint
        findPreference<ListPreference>(UserSettings.KEY_KALMAN_DIMENSION)?.apply {
            updateKalmanDimensionSummary(this)

            setOnPreferenceChangeListener { _, newValue ->
                val dimension = newValue as String
                Log.i(TAG, "Kalman dimension changed to: $dimension")

                // Broadcast to PlaybackService to apply immediately
                val intent = Intent(ACTION_KALMAN_DIMENSION_CHANGED).apply {
                    putExtra(EXTRA_KALMAN_DIMENSION, dimension)
                }
                LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(intent)

                // Update summary on next frame (after preference value is saved)
                handler.post { updateKalmanDimensionSummary(this) }

                true  // Accept the preference change
            }
        }

        // Set up version display
        findPreference<Preference>(KEY_APP_VERSION)?.apply {
            summary = try {
                val packageInfo = requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0)
                packageInfo.versionName
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get version info", e)
                "Unknown"
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

    private fun updateDebugLoggingSummary(pref: SwitchPreferenceCompat?) {
        pref ?: return
        pref.summary = if (DebugLogger.isEnabled) {
            getString(R.string.pref_debug_logging_summary_on, DebugLogger.getSampleCount())
        } else {
            getString(R.string.pref_debug_logging_summary_off)
        }
    }

    /**
     * Updates the Kalman dimension summary with current selection and network hint.
     */
    private fun updateKalmanDimensionSummary(pref: ListPreference?) {
        pref ?: return

        val currentEntry = pref.entry ?: "2D - Stable"
        val networkHint = getNetworkHint()

        pref.summary = if (networkHint.isNotEmpty()) {
            "$currentEntry\n$networkHint"
        } else {
            currentEntry
        }
    }

    /**
     * Gets a network-specific recommendation hint for the Kalman dimension.
     */
    private fun getNetworkHint(): String {
        val connectivityManager = context?.getSystemService<ConnectivityManager>() ?: return ""
        val network = connectivityManager.activeNetwork ?: return ""
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return ""

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                "2D recommended for WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                "4D recommended for cellular"
            else -> ""
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
