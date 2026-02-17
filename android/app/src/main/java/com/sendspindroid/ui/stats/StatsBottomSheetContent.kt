package com.sendspindroid.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlin.math.abs

// Color constants for status indicators
private val ColorGood = Color(0xFF4CAF50)      // Green
private val ColorWarning = Color(0xFFFFC107)   // Yellow/Amber
private val ColorBad = Color(0xFFF44336)       // Red

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsBottomSheet(
    sheetState: SheetState,
    state: StatsState,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        StatsContent(state = state)
    }
}

@Composable
fun StatsContent(
    state: StatsState,
    modifier: Modifier = Modifier
) {
    // Use nestedScroll to properly integrate with BottomSheetDialogFragment
    val nestedScrollInterop = rememberNestedScrollInteropConnection()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .nestedScroll(nestedScrollInterop)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Title
        Text(
            text = stringResource(R.string.stats_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // === CONNECTION ===
        SectionHeader(stringResource(R.string.stats_section_connection))
        StatRow(stringResource(R.string.stats_server), state.serverName ?: "--", getStatusColor(state.serverName != null))
        StatRow(stringResource(R.string.stats_address), state.serverAddress ?: "--")
        StatRow(stringResource(R.string.stats_state), state.connectionState, getStatusColor(getConnectionStatus(state.connectionState)))
        StatRow(stringResource(R.string.stats_codec), state.audioCodec)
        StatRow(stringResource(R.string.stats_reconnects), state.reconnectAttempts.toString(), getStatusColor(state.reconnectAttempts == 0))

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // === NETWORK ===
        SectionHeader(stringResource(R.string.stats_section_network))
        StatRow(stringResource(R.string.stats_type), state.networkType, getNetworkTypeColor(state.networkType))
        StatRow(stringResource(R.string.stats_quality), state.networkQuality, getNetworkQualityColor(state.networkQuality))
        StatRow(stringResource(R.string.stats_metered), if (state.networkMetered) stringResource(R.string.action_yes) else stringResource(R.string.action_no),
            if (state.networkMetered) ColorWarning else ColorGood)

        if (state.isWifi) {
            if (state.wifiRssi != Int.MIN_VALUE) {
                StatRow(stringResource(R.string.stats_wifi_rssi), "${state.wifiRssi} dBm", getWifiRssiColor(state.wifiRssi))
            }
            if (state.wifiSpeed > 0) {
                StatRow(stringResource(R.string.stats_wifi_speed), "${state.wifiSpeed} Mbps")
            }
            if (state.wifiFrequency > 0) {
                StatRow(stringResource(R.string.stats_wifi_band), state.wifiBand,
                    if (state.wifiFrequency >= 5000) ColorGood else ColorWarning)
            }
        }

        if (state.isCellular && state.cellularType != null) {
            StatRow(stringResource(R.string.stats_cellular), state.cellularTypeDisplay, getCellularTypeColor(state.cellularType))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // === SYNC ERROR ===
        SectionHeader(stringResource(R.string.stats_section_sync_error))
        StatRow(stringResource(R.string.stats_playback), state.playbackState, getStatusColor(getPlaybackStatus(state.playbackState)))
        StatRow(stringResource(R.string.stats_sync_error), String.format("%+.2f ms", state.syncErrorMs),
            getStatusColor(getSyncErrorStatus(state.syncErrorUs)))
        StatRow(stringResource(R.string.stats_smoothed), String.format("%+.2f ms", state.smoothedSyncErrorMs),
            getStatusColor(getSyncErrorStatus(state.smoothedSyncErrorUs)))
        StatRow(stringResource(R.string.stats_drift_rate), String.format("%+.4f", state.syncErrorDrift))

        if (state.gracePeriodRemainingUs >= 0) {
            StatRow(stringResource(R.string.stats_grace_period), String.format("%.1fs", state.gracePeriodRemainingUs / 1_000_000.0), ColorWarning)
        } else {
            StatRow(stringResource(R.string.stats_grace_period), stringResource(R.string.stats_grace_inactive), ColorGood)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // === CLOCK SYNC ===
        SectionHeader(stringResource(R.string.stats_section_clock_sync))
        StatRow(stringResource(R.string.stats_offset), String.format("%+.2f ms", state.clockOffsetMs))
        StatRow(stringResource(R.string.stats_drift), String.format("%+.3f ppm", state.clockDriftPpm),
            getStatusColor(getClockDriftStatus(state.clockDriftPpm)))
        StatRow(stringResource(R.string.stats_error), String.format("+/- %.2f ms", state.clockErrorMs),
            getStatusColor(getClockErrorStatus(state.clockErrorUs)))
        StatRow(stringResource(R.string.stats_converged), if (state.clockConverged) stringResource(R.string.action_yes) else stringResource(R.string.action_no),
            if (state.clockConverged) ColorGood else ColorWarning)
        StatRow(stringResource(R.string.stats_measurements), state.measurementCount.toString())

        if (state.lastTimeSyncAgeMs >= 0) {
            StatRow(stringResource(R.string.stats_last_sync), String.format("%.1fs ago", state.lastTimeSyncAgeMs / 1000.0),
                getLastSyncColor(state.lastTimeSyncAgeMs))
        }

        StatRow(stringResource(R.string.stats_frozen), if (state.clockFrozen) stringResource(R.string.stats_frozen_reconnecting) else stringResource(R.string.action_no),
            if (state.clockFrozen) ColorWarning else null)

        if (state.staticDelayMs != 0.0) {
            StatRow(stringResource(R.string.stats_sync_offset), String.format("%+.0f ms", state.staticDelayMs))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // === DAC / AUDIO ===
        SectionHeader(stringResource(R.string.stats_section_dac_audio))
        StatRow(stringResource(R.string.stats_calibrated), if (state.startTimeCalibrated) stringResource(R.string.action_yes) else stringResource(R.string.action_no),
            if (state.startTimeCalibrated) ColorGood else ColorWarning)
        StatRow(stringResource(R.string.stats_calibrations), state.dacCalibrationCount.toString())
        StatRow(stringResource(R.string.stats_frames_written), formatNumber(state.totalFramesWritten))
        StatRow(stringResource(R.string.stats_server_position), String.format("%.1fs", state.serverPositionSec))
        StatRow(stringResource(R.string.stats_underruns), state.bufferUnderrunCount.toString(),
            if (state.bufferUnderrunCount > 0) ColorBad else ColorGood)

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // === BUFFER ===
        SectionHeader(stringResource(R.string.stats_section_buffer))
        StatRow(stringResource(R.string.stats_queued), "${state.queuedMs} ms", getStatusColor(getBufferStatus(state.queuedMs)))
        StatRow(stringResource(R.string.stats_received), state.chunksReceived.toString())
        StatRow(stringResource(R.string.stats_played), state.chunksPlayed.toString())
        StatRow(stringResource(R.string.stats_dropped), state.chunksDropped.toString(),
            if (state.chunksDropped > 0) ColorBad else null)
        StatRow(stringResource(R.string.stats_gaps), "${state.gapsFilled} (${state.gapSilenceMs} ms)",
            if (state.gapsFilled > 0) ColorWarning else null)
        StatRow(stringResource(R.string.stats_overlaps), "${state.overlapsTrimmed} (${state.overlapTrimmedMs} ms)",
            if (state.overlapsTrimmed > 0) ColorWarning else null)

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // === SYNC CORRECTION ===
        SectionHeader(stringResource(R.string.stats_section_sync_correction))
        StatRow(stringResource(R.string.stats_mode), state.correctionMode,
            if (state.correctionMode == "None") ColorGood else ColorWarning)
        StatRow(stringResource(R.string.stats_inserted), state.framesInserted.toString())
        StatRow(stringResource(R.string.stats_dropped), state.framesDropped.toString())
        StatRow(stringResource(R.string.stats_corrections), state.syncCorrections.toString())
        StatRow(stringResource(R.string.stats_reanchors), state.reanchorCount.toString(),
            if (state.reanchorCount > 0) ColorWarning else ColorGood)

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: Color? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (valueColor != null) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ============================================================================
// Color Helpers
// ============================================================================

private fun getStatusColor(status: ThresholdStatus): Color {
    return when (status) {
        ThresholdStatus.GOOD -> ColorGood
        ThresholdStatus.WARNING -> ColorWarning
        ThresholdStatus.BAD -> ColorBad
    }
}

private fun getStatusColor(isGood: Boolean): Color? {
    return if (isGood) ColorGood else ColorWarning
}

private fun getNetworkTypeColor(type: String): Color? {
    return when (type) {
        "WIFI", "ETHERNET" -> ColorGood
        "CELLULAR" -> ColorWarning
        else -> null
    }
}

private fun getNetworkQualityColor(quality: String): Color? {
    return when (quality) {
        "EXCELLENT", "GOOD" -> ColorGood
        "FAIR" -> ColorWarning
        "POOR" -> ColorBad
        else -> null
    }
}

private fun getWifiRssiColor(rssi: Int): Color {
    return when {
        rssi > -50 -> ColorGood
        rssi > -65 -> ColorGood
        rssi > -75 -> ColorWarning
        else -> ColorBad
    }
}

private fun getCellularTypeColor(type: String): Color? {
    return when (type) {
        "TYPE_5G", "TYPE_LTE" -> ColorGood
        "TYPE_3G" -> ColorWarning
        "TYPE_2G" -> ColorBad
        else -> null
    }
}

private fun getLastSyncColor(ageMs: Long): Color {
    return when {
        ageMs < 2_000L -> ColorGood
        ageMs < 10_000L -> ColorWarning
        else -> ColorBad
    }
}

private fun formatNumber(value: Long): String {
    return String.format("%,d", value)
}

// ============================================================================
// Previews
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun StatsContentPreview() {
    SendSpinTheme {
        StatsContent(
            state = StatsState(
                serverName = "Living Room",
                serverAddress = "192.168.1.100:8927",
                connectionState = "Connected",
                audioCodec = "Opus",
                networkType = "WIFI",
                networkQuality = "EXCELLENT",
                networkMetered = false,
                wifiRssi = -55,
                wifiSpeed = 866,
                wifiFrequency = 5180,
                playbackState = "PLAYING",
                syncErrorUs = 1500,
                smoothedSyncErrorUs = 1200,
                clockOffsetUs = 5000,
                clockDriftPpm = 2.5,
                clockErrorUs = 800,
                clockConverged = true,
                measurementCount = 150,
                lastTimeSyncAgeMs = 500,
                startTimeCalibrated = true,
                queuedSamples = 9600,
                chunksReceived = 1000,
                chunksPlayed = 998
            )
        )
    }
}
