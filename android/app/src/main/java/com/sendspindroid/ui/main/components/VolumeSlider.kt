package com.sendspindroid.ui.main.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Volume slider with volume icons on either side.
 * Supports custom accent color for dynamic theming based on album art.
 */
@Composable
fun VolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentColor: Color? = null
) {
    val activeTrackColor = accentColor ?: MaterialTheme.colorScheme.primary
    val thumbColor = accentColor ?: MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Volume Down Icon
        Icon(
            painter = painterResource(R.drawable.ic_volume_down),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Volume Slider
        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = thumbColor,
                activeTrackColor = activeTrackColor,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Volume Up Icon
        Icon(
            painter = painterResource(R.drawable.ic_volume_up),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun VolumeSliderPreview() {
    SendSpinTheme {
        var volume by remember { mutableFloatStateOf(0.75f) }
        VolumeSlider(
            volume = volume,
            onVolumeChange = { volume = it }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun VolumeSliderCustomColorPreview() {
    SendSpinTheme {
        var volume by remember { mutableFloatStateOf(0.5f) }
        VolumeSlider(
            volume = volume,
            onVolumeChange = { volume = it },
            accentColor = Color(0xFF4CAF50)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun VolumeSliderDisabledPreview() {
    SendSpinTheme {
        VolumeSlider(
            volume = 0.75f,
            onVolumeChange = {},
            enabled = false
        )
    }
}
