package com.sendspindroid.ui.main.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Preset playback speeds (as Int multipliers where 1000 = 1.0x).
 */
private val SPEED_PRESETS = listOf(500, 750, 1000, 1250, 1500, 2000)

/**
 * Speed control chip for the Now Playing screen.
 *
 * Displays the current playback speed as a compact text chip (e.g. "1.5x").
 * - Tap: cycles to the next preset speed.
 * - Long-press: opens a dropdown menu with all preset options.
 * - Highlighted when speed is not 1.0x.
 *
 * @param currentSpeed Current speed as Int multiplier (1000 = 1.0x)
 * @param onSpeedChange Callback with the new speed as Float (e.g. 1.5f)
 * @param enabled Whether the control is interactive
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeedControl(
    currentSpeed: Int,
    onSpeedChange: (Float) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val isNonDefault = currentSpeed != 1000
    val displayText = formatSpeed(currentSpeed)

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isNonDefault)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isNonDefault)
                MaterialTheme.colorScheme.onPrimaryContainer
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .combinedClickable(
                    enabled = enabled,
                    onClick = {
                        // Cycle to next preset
                        val nextSpeed = nextPreset(currentSpeed)
                        onSpeedChange(nextSpeed / 1000f)
                    },
                    onLongClick = { showMenu = true },
                    onClickLabel = "Change playback speed",
                    onLongClickLabel = "Show all speed options"
                )
                .semantics {
                    contentDescription = "Playback speed: $displayText. Tap to change, long press for options."
                }
        ) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            SPEED_PRESETS.forEach { preset ->
                val label = formatSpeed(preset)
                val isSelected = preset == currentSpeed
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        showMenu = false
                        onSpeedChange(preset / 1000f)
                    }
                )
            }
        }
    }
}

/**
 * Get the next preset speed after the current one, wrapping around.
 */
private fun nextPreset(current: Int): Int {
    val idx = SPEED_PRESETS.indexOf(current)
    return if (idx >= 0) {
        SPEED_PRESETS[(idx + 1) % SPEED_PRESETS.size]
    } else {
        // Current speed isn't a preset - snap to nearest higher preset or 1.0x
        SPEED_PRESETS.firstOrNull { it > current } ?: SPEED_PRESETS.first()
    }
}

/**
 * Format a speed multiplier Int (1000 = 1.0x) as a display string.
 */
private fun formatSpeed(speed: Int): String {
    val value = speed / 1000f
    return if (value == value.toLong().toFloat()) {
        "${value.toLong().toInt()}.0x"
    } else {
        "${value}x"
    }
}

@Preview(showBackground = true)
@Composable
private fun SpeedControlDefaultPreview() {
    SendSpinTheme {
        SpeedControl(
            currentSpeed = 1000,
            onSpeedChange = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SpeedControlFastPreview() {
    SendSpinTheme {
        SpeedControl(
            currentSpeed = 1500,
            onSpeedChange = {}
        )
    }
}
