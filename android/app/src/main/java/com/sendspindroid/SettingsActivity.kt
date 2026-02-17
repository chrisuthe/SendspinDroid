package com.sendspindroid

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.sendspindroid.debug.DebugLogger
import com.sendspindroid.ui.settings.SettingsScreen
import com.sendspindroid.ui.settings.SettingsViewModel
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Activity hosting the Compose-based settings screen.
 * Provides a standard settings screen with back navigation.
 */
class SettingsActivity : AppCompatActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SendSpinTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = { finish() },
                    onExportLogs = { exportDebugLogs() },
                    onRestartApp = { restartApp() }
                )
            }
        }
    }

    private fun exportDebugLogs() {
        // Clean up old logs first
        DebugLogger.cleanupOldLogs(this)

        // Create share intent
        val shareIntent = DebugLogger.createShareIntent(this)

        if (shareIntent != null) {
            val chooserIntent = Intent.createChooser(
                shareIntent,
                getString(R.string.debug_share_chooser_title)
            )
            startActivity(chooserIntent)
            Toast.makeText(this, R.string.debug_log_exported, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.debug_log_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finishAffinity()
    }
}
