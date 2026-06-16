package com.sendspindroid

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sendspindroid.diagnostics.DiagnosticSnapshot
import com.sendspindroid.diagnostics.DiagnosticsExport
import com.sendspindroid.ui.settings.SettingsScreen
import com.sendspindroid.ui.settings.SettingsViewModel
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.launch

/**
 * Activity hosting the Compose-based settings screen.
 * Provides a standard settings screen with back navigation.
 */
class SettingsActivity : AppCompatActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: must be called before super.onCreate. The
        // Material3 Scaffold inside SettingsScreen handles system-bar
        // insets automatically.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            SendSpinTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = { finish() },
                    onExportLogs = { exportDebugLogs() },
                    onReportProblem = { reportProblem() },
                    onRestartApp = { restartApp() }
                )
            }
        }
    }

    private fun exportDebugLogs() {
        val shareIntent = DiagnosticsExport.shareIntent(this)

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

    /**
     * Capture a live diagnostic snapshot, bundle it with the redacted logs, and
     * open the share sheet (the share text carries the environment + a pre-filled
     * GitHub issue link). Snapshot capture is async, so this runs in a coroutine.
     */
    private fun reportProblem() {
        lifecycleScope.launch {
            val snapshot = DiagnosticSnapshot.capture(this@SettingsActivity)
            val shareIntent = DiagnosticsExport.reportIntent(this@SettingsActivity, snapshot)
            if (shareIntent != null) {
                startActivity(
                    Intent.createChooser(shareIntent, getString(R.string.report_chooser_title))
                )
            } else {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.debug_log_export_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finishAffinity()
    }
}
