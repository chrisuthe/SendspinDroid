package com.sendspindroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import com.sendspindroid.R
import com.sendspindroid.UserSettings
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Settings screen composable with all app preferences.
 *
 * @param viewModel SettingsViewModel for state management
 * @param onNavigateBack Called when back navigation is triggered
 * @param onExportLogs Called when export logs is clicked
 * @param onRestartApp Called when app restart is confirmed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onExportLogs: () -> Unit,
    onRestartApp: () -> Unit
) {
    val playerName by viewModel.playerName.collectAsState()
    val fullscreenMode by viewModel.fullscreenMode.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val miniPlayerPosition by viewModel.miniPlayerPosition.collectAsState()
    val syncOffset by viewModel.syncOffset.collectAsState()
    val preferredCodec by viewModel.preferredCodec.collectAsState()
    val wifiCodec by viewModel.wifiCodec.collectAsState()
    val cellularCodec by viewModel.cellularCodec.collectAsState()
    val lowMemoryMode by viewModel.lowMemoryMode.collectAsState()
    val highPowerMode by viewModel.highPowerMode.collectAsState()
    val batteryOptExempt by viewModel.batteryOptExempt.collectAsState()
    val debugLogging by viewModel.debugLogging.collectAsState()
    val debugSampleCount by viewModel.debugSampleCount.collectAsState()
    val appVersion by viewModel.appVersion.collectAsState()

    var showPlayerNameDialog by remember { mutableStateOf(false) }
    var showSyncOffsetDialog by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showCodecDialog by remember { mutableStateOf<CodecDialogType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.wizard_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Player Category
            PreferenceCategory(title = stringResource(R.string.pref_category_player))
            TextPreference(
                title = stringResource(R.string.pref_player_name_title),
                summary = playerName.ifEmpty { stringResource(R.string.pref_player_name_summary) },
                onClick = { showPlayerNameDialog = true }
            )

            // Display Category
            PreferenceCategory(title = stringResource(R.string.pref_category_display))
            SwitchPreference(
                title = stringResource(R.string.pref_full_screen_title),
                summary = stringResource(R.string.pref_full_screen_summary),
                checked = fullscreenMode,
                onCheckedChange = { viewModel.setFullscreenMode(it) }
            )
            SwitchPreference(
                title = stringResource(R.string.pref_keep_screen_on_title),
                summary = stringResource(R.string.pref_keep_screen_on_summary),
                checked = keepScreenOn,
                onCheckedChange = { viewModel.setKeepScreenOn(it) }
            )
            SegmentedButtonPreference(
                title = stringResource(R.string.pref_mini_player_position_title),
                summary = stringResource(R.string.pref_mini_player_position_summary),
                options = listOf(
                    stringResource(R.string.pref_mini_player_position_top) to UserSettings.MiniPlayerPosition.TOP,
                    stringResource(R.string.pref_mini_player_position_bottom) to UserSettings.MiniPlayerPosition.BOTTOM
                ),
                selectedOption = miniPlayerPosition,
                onOptionSelected = { viewModel.setMiniPlayerPosition(it) }
            )

            // Audio Category
            PreferenceCategory(title = stringResource(R.string.pref_category_audio))
            SyncOffsetPreference(
                offset = syncOffset,
                onDecrease = { viewModel.decreaseSyncOffset() },
                onIncrease = { viewModel.increaseSyncOffset() },
                onValueClick = { showSyncOffsetDialog = true }
            )
            CodecPreference(
                title = stringResource(R.string.pref_codec_title),
                summary = getCodecDisplayName(preferredCodec),
                onClick = { showCodecDialog = CodecDialogType.PREFERRED }
            )

            // Network Codecs Category
            PreferenceCategory(title = stringResource(R.string.pref_category_network_codecs))
            CodecPreference(
                title = stringResource(R.string.pref_codec_wifi_title),
                summary = getCodecDisplayName(wifiCodec),
                onClick = { showCodecDialog = CodecDialogType.WIFI }
            )
            CodecPreference(
                title = stringResource(R.string.pref_codec_cellular_title),
                summary = getCodecDisplayName(cellularCodec),
                onClick = { showCodecDialog = CodecDialogType.CELLULAR }
            )

            // Performance Category
            PreferenceCategory(title = stringResource(R.string.pref_category_performance))
            SwitchPreference(
                title = stringResource(R.string.pref_low_memory_title),
                summary = stringResource(R.string.pref_low_memory_summary),
                checked = lowMemoryMode,
                onCheckedChange = {
                    if (viewModel.setLowMemoryMode(it)) {
                        showRestartDialog = true
                    }
                }
            )
            SwitchPreference(
                title = stringResource(R.string.pref_high_power_mode_title),
                summary = stringResource(R.string.pref_high_power_mode_summary),
                checked = highPowerMode,
                onCheckedChange = { viewModel.setHighPowerMode(it) }
            )
            if (highPowerMode) {
                val context = LocalContext.current
                TextPreference(
                    title = if (batteryOptExempt) {
                        stringResource(R.string.pref_battery_opt_exempt)
                    } else {
                        stringResource(R.string.pref_battery_opt_not_exempt)
                    },
                    summary = "",
                    enabled = !batteryOptExempt,
                    onClick = {
                        val intent = Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                        // Refresh status when user returns
                        viewModel.refreshBatteryOptExempt()
                    }
                )
            }

            // Debug Category
            PreferenceCategory(title = stringResource(R.string.pref_category_debug))
            SwitchPreference(
                title = stringResource(R.string.pref_debug_logging_title),
                summary = if (debugLogging) {
                    stringResource(R.string.pref_debug_logging_summary_on, debugSampleCount)
                } else {
                    stringResource(R.string.pref_debug_logging_summary_off)
                },
                checked = debugLogging,
                onCheckedChange = { viewModel.setDebugLogging(it) }
            )
            TextPreference(
                title = stringResource(R.string.pref_export_logs_title),
                summary = if (debugSampleCount > 0) {
                    stringResource(R.string.pref_export_logs_summary)
                } else {
                    stringResource(R.string.pref_export_logs_summary_empty)
                },
                enabled = debugSampleCount > 0,
                onClick = onExportLogs
            )

            // About Category
            PreferenceCategory(title = stringResource(R.string.pref_category_about))
            TextPreference(
                title = stringResource(R.string.pref_version_title),
                summary = appVersion,
                enabled = false,
                onClick = {}
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Player Name Dialog
    if (showPlayerNameDialog) {
        PlayerNameDialog(
            currentName = playerName,
            onConfirm = { name ->
                viewModel.setPlayerName(name)
                showPlayerNameDialog = false
            },
            onDismiss = { showPlayerNameDialog = false }
        )
    }

    // Sync Offset Dialog
    if (showSyncOffsetDialog) {
        SyncOffsetDialog(
            currentOffset = syncOffset,
            onConfirm = { offset ->
                viewModel.setSyncOffset(offset)
                showSyncOffsetDialog = false
            },
            onDismiss = { showSyncOffsetDialog = false }
        )
    }

    // Restart Dialog
    if (showRestartDialog) {
        RestartAppDialog(
            onConfirm = {
                showRestartDialog = false
                onRestartApp()
            },
            onDismiss = { showRestartDialog = false }
        )
    }

    // Codec Selection Dialog
    showCodecDialog?.let { dialogType ->
        CodecSelectionDialog(
            title = when (dialogType) {
                CodecDialogType.PREFERRED -> stringResource(R.string.pref_codec_title)
                CodecDialogType.WIFI -> stringResource(R.string.pref_codec_wifi_title)
                CodecDialogType.CELLULAR -> stringResource(R.string.pref_codec_cellular_title)
            },
            currentCodec = when (dialogType) {
                CodecDialogType.PREFERRED -> preferredCodec
                CodecDialogType.WIFI -> wifiCodec
                CodecDialogType.CELLULAR -> cellularCodec
            },
            onSelect = { codec ->
                when (dialogType) {
                    CodecDialogType.PREFERRED -> viewModel.setPreferredCodec(codec)
                    CodecDialogType.WIFI -> viewModel.setWifiCodec(codec)
                    CodecDialogType.CELLULAR -> viewModel.setCellularCodec(codec)
                }
                showCodecDialog = null
            },
            onDismiss = { showCodecDialog = null }
        )
    }
}

private enum class CodecDialogType {
    PREFERRED, WIFI, CELLULAR
}

// ============================================================================
// Preference Components
// ============================================================================

@Composable
private fun PreferenceCategory(
    title: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 8.dp)
        )
    }
}

@Composable
private fun SwitchPreference(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun TextPreference(
    title: String,
    summary: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        )
    }
}

@Composable
private fun CodecPreference(
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SyncOffsetPreference(
    offset: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onValueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.pref_sync_offset_title),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.pref_sync_offset_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // -10ms button
        OutlinedButton(
            onClick = onDecrease,
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) {
            Text("-", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Value display (clickable)
        FilledTonalButton(
            onClick = onValueClick,
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (offset >= 0) "+${offset}ms" else "${offset}ms",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // +10ms button
        OutlinedButton(
            onClick = onIncrease,
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SegmentedButtonPreference(
    title: String,
    summary: String,
    options: List<Pair<String, T>>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEachIndexed { index, (label, value) ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    onClick = { onOptionSelected(value) },
                    selected = selectedOption == value
                ) {
                    Text(label)
                }
            }
        }
    }
}

// ============================================================================
// Dialogs
// ============================================================================

@Composable
private fun PlayerNameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pref_player_name_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.pref_player_name_title)) },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun SyncOffsetDialog(
    currentOffset: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var offsetText by remember { mutableStateOf(currentOffset.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pref_sync_offset_dialog_title)) },
        text = {
            OutlinedTextField(
                value = offsetText,
                onValueChange = { offsetText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.pref_sync_offset_title)) },
                singleLine = true,
                suffix = { Text("ms") }
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    offsetText.toIntOrNull()?.let { onConfirm(it) }
                }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun RestartAppDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pref_low_memory_restart_title)) },
        text = { Text(stringResource(R.string.pref_low_memory_restart_message)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.pref_low_memory_restart_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pref_low_memory_restart_later))
            }
        }
    )
}

@Composable
private fun CodecSelectionDialog(
    title: String,
    currentCodec: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val codecs = listOf(
        "opus" to "Opus",
        "flac" to "FLAC",
        "aac" to "AAC",
        "mp3" to "MP3"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                codecs.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = currentCodec == value,
                            onClick = { onSelect(value) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

// ============================================================================
// Utility
// ============================================================================

private fun getCodecDisplayName(codec: String): String {
    return when (codec.lowercase()) {
        "opus" -> "Opus"
        "flac" -> "FLAC"
        "aac" -> "AAC"
        "mp3" -> "MP3"
        else -> codec
    }
}
