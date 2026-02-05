package com.sendspindroid.ui.detail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Row of action buttons for artist/album detail screens.
 *
 * Displays Shuffle and Add to Queue buttons in an outlined style.
 * These stay in place and don't collapse with the header.
 *
 * @param onShuffle Called when Shuffle is tapped
 * @param onAddToQueue Called when Add to Queue is tapped
 * @param shuffleEnabled Whether the shuffle button is enabled
 * @param queueEnabled Whether the add to queue button is enabled
 * @param modifier Optional modifier for the row
 */
@Composable
fun ActionRow(
    onShuffle: () -> Unit,
    onAddToQueue: () -> Unit,
    shuffleEnabled: Boolean = true,
    queueEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onShuffle,
            enabled = shuffleEnabled,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(text = "Shuffle")
        }

        OutlinedButton(
            onClick = onAddToQueue,
            enabled = queueEnabled,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(text = "Add to Queue")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ActionRowPreview() {
    SendSpinTheme {
        ActionRow(
            onShuffle = {},
            onAddToQueue = {}
        )
    }
}

@Preview(showBackground = true, name = "Disabled")
@Composable
private fun ActionRowDisabledPreview() {
    SendSpinTheme {
        ActionRow(
            onShuffle = {},
            onAddToQueue = {},
            shuffleEnabled = false,
            queueEnabled = false
        )
    }
}
