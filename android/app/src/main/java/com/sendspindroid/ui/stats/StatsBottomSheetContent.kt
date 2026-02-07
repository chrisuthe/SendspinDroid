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
        StatRow("Server", state.serverName ?: "--", getStatusColor(state.serverName != null))
        StatRow("Address", state.serverAddress ?: "--")
        StatRow("State", state.connectionState, getStatusColor(getConnectionStatus(state.connectionState)))
        StatRow("Codec", state.audioCodec)
        StatRow("Reconnects", state.reconnectAttempts.toString(), getStatusColor(state.reconnectAttempts == 0))

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // === NETWORK ===
        SectionHeader(stringResource(R.string.stats_section_network))
        StatRow("Type", state.networkType, getNetworkTypeColor(state.networkType))
        StatRow("Quality", state.networkQuality, getNetworkQualityColor(state.networkQuality))
        StatRow("Metered", if (state.networkMetered) "Yes" else "No",
            if (state.networkMetered) ColorWarning else ColorGood)

        if (state.isWifi) {
            if (state.wifiRssi != Int.MIN_VALUE) {
                StatRow("WiFi RSSI", "${state.wifiRssi} dBm", getWifiRssiColor(state.wifiRssi))
            }
            if (state.wifiSpeed > 0) {
                StatRow("WiFi Speed", "${state.wifiSpeed} Mbps")
            }
            if (state.wifiFrequency > 0) {
                StatRow("WiFi Band", state.wifiBand,
                    if (state.wifiFrequency >= 5000) ColorGood else ColorWarning)
            }
        }

        if (state.isCellular && state.cellularType != null) {
            StatRow("Cellular", state.cellularTypeDisplay, getCellularTypeColor(state.cellularType))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // === SYNC ERROR ===
        SectionHeader(stringResource(R.string.stats_section_sync_error))
        StatRow("Playback", state.playbackState, getStatusColor(getPlaybackStatus(state.playbackState)))
        StatRow("Sync Error", String.format("%+.2f ms", state.syncErrorMs),
            getStatusColor(getSyncErrorStatus(state.syncErrorUs)))
        StatRow("Smoothed", String.format("%+.2f ms", state.smoothedSyncErrorMs),
            getStatusColor(getSyncErrorStatus(state.smoothedSyncErrorUs)))
        StatRow("Drift Rate", String.format("%+.4f", state.syncErrorDrift))

        if (state.gracePeriodRemainingUs >= 0) {
            StatRow("Grace Period", String.format("%.1fs", state.gracePeriodRemainingUs / 1_000_000.0), ColorWarning)
        } else {
            StatRow("Grace Period", "Inactive", ColorGood)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // === CLOCK SYNC ===
        SectionHeader(stringResource(R.string.stats_section_clock_sync))
        StatRow("Offset", String.format("%+.2f ms", state.clockOffsetMs))
        StatRow("Drift", String.format("%+.3f ppm", state.clockDriftPpm),
            getStatusColor(getClockDriftStatus(state.clockDriftPpm)))
        StatRow("Error", String.format("+/- %.2f ms", state.clockErrorMs),
            getStatusColor(getClockErrorStatus(state.clockErrorUs)))
        StatRow("Converged", if (state.clockConverged) "Yes" else "No",
            if (state.clockConverged) ColorGood else ColorWarning)
        StatRow("Measurements", state.measurementCount.toString())

        if (state.lastTimeSyncAgeMs >= 0) {
            StatRow("Last Sync", String.format("%.1fs ago", state.lastTimeSyncAgeMs / 1000.0),
                getLastSyncColor(state.lastTimeSyncAgeMs))
        }

        StatRow("Frozen", if (state.clockFrozen) "Yes (reconnecting)" else "No",
            if (state.clockFrozen) ColorWarning else null)

        if (state.staticDelayMs != 0.0) {
            StatRow("Sync Offset", String.format("%+.0f ms", state.staticDelayMs))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // === DAC / AUDIO ===
        SectionHeader(stringResource(R.string.stats_section_dac_audio))
        StatRow("Calibrated", if (state.startTimeCalibrated) "Yes" else "No",
            if (state.startTimeCalibrated) ColorGood else ColorWarning)
        StatRow("Calibrations", state.dacCalibrationCount.toString())
        StatRow("Frames Written", formatNumber(state.totalFramesWritten))
        StatRow("Server Position", String.format("%.1fs", state.serverPositionSec))
        StatRow("Underruns", state.bufferUnderrunCount.toString(),
            if (state.bufferUnderrunCount > 0) ColorBad else ColorGood)

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // === BUFFER ===
        SectionHeader(stringResource(R.string.stats_section_buffer))
        StatRow("Queued", "${state.queuedMs} ms", getStatusColor(getBufferStatus(state.queuedMs)))
        StatRow("Received", state.chunksReceived.toString())
        StatRow("Played", state.chunksPlayed.toString())
        StatRow("Dropped", state.chunksDropped.toString(),
            if (state.chunksDropped > 0) ColorBad else null)
        StatRow("Gaps Filled", "${state.gapsFilled} (${state.gapSilenceMs} ms)",
            if (state.gapsFilled > 0) ColorWarning else null)
        StatRow("Overlaps", "${state.overlapsTrimmed} (${state.overlapTrimmedMs} ms)",
            if (state.overlapsTrimmed > 0) ColorWarning else null)

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // === SYNC CORRECTION ===
        SectionHeader(stringResource(R.string.stats_section_sync_correction))
        StatRow("Mode", state.correctionMode,
            if (state.correctionMode == "None") ColorGood else ColorWarning)
        StatRow("Inserted", state.framesInserted.toString())
        StatRow("Dropped", state.framesDropped.toString())
        StatRow("Corrections", state.syncCorrections.toString())
        StatRow("Reanchors", state.reanchorCount.toString(),
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
