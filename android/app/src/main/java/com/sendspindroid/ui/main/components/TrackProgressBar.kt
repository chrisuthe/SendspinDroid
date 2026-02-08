package com.sendspindroid.ui.main.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Track progress display showing elapsed / total as a simple timestamp pair.
 *
 * Interpolates position forward at 250ms intervals when playing, anchored to
 * the last server position update. Hidden when duration is unknown (<=0).
 */
@Composable
fun TrackProgressBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    accentColor: Color?,
    modifier: Modifier = Modifier
) {
    if (durationMs <= 0) return

    // Anchor point for interpolation
    var anchorPositionMs by remember { mutableLongStateOf(positionMs) }
    var anchorTime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var displayPositionMs by remember { mutableLongStateOf(positionMs) }

    // When the server sends a new position, reset the anchor
    LaunchedEffect(positionMs) {
        anchorPositionMs = positionMs
        anchorTime = SystemClock.elapsedRealtime()
        displayPositionMs = positionMs
    }

    // Interpolation timer: advance position while playing
    LaunchedEffect(isPlaying, anchorPositionMs, anchorTime) {
        if (!isPlaying) {
            displayPositionMs = anchorPositionMs
            return@LaunchedEffect
        }
        while (isActive) {
            delay(250)
            val elapsed = SystemClock.elapsedRealtime() - anchorTime
            displayPositionMs = minOf(durationMs, anchorPositionMs + elapsed)
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = "${formatTime(displayPositionMs)} / ${formatTime(durationMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Formats milliseconds as M:SS or H:MM:SS.
 */
private fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun TrackProgressBarPreview() {
    SendSpinTheme {
        TrackProgressBar(
            positionMs = 45000,
            durationMs = 210000,
            isPlaying = true,
            accentColor = null,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun TrackProgressBarLongTrackPreview() {
    SendSpinTheme {
        TrackProgressBar(
            positionMs = 3725000,
            durationMs = 7200000,
            isPlaying = false,
            accentColor = null,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}
