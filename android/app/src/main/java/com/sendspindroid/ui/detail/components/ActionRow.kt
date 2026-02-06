package com.sendspindroid.ui.detail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Row of action buttons for artist/album/playlist detail screens.
 *
 * Displays Shuffle and a second action button in an outlined style.
 * Defaults to "Add to Playlist" but the label and icon can be overridden
 * (e.g. PlaylistDetailScreen uses "Add Tracks").
 *
 * @param onShuffle Called when Shuffle is tapped
 * @param onAddToPlaylist Called when the second button is tapped
 * @param shuffleEnabled Whether the shuffle button is enabled
 * @param playlistEnabled Whether the second button is enabled
 * @param secondButtonLabel Label for the second button
 * @param secondButtonIcon Icon for the second button
 * @param modifier Optional modifier for the row
 */
@Composable
fun ActionRow(
    onShuffle: () -> Unit,
    onAddToPlaylist: () -> Unit,
    shuffleEnabled: Boolean = true,
    playlistEnabled: Boolean = true,
    secondButtonLabel: String = "Add to Playlist",
    secondButtonIcon: ImageVector = Icons.Filled.Add,
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
            onClick = onAddToPlaylist,
            enabled = playlistEnabled,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = secondButtonIcon,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(text = secondButtonLabel)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ActionRowPreview() {
    SendSpinTheme {
        ActionRow(
            onShuffle = {},
            onAddToPlaylist = {}
        )
    }
}

@Preview(showBackground = true, name = "Disabled")
@Composable
private fun ActionRowDisabledPreview() {
    SendSpinTheme {
        ActionRow(
            onShuffle = {},
            onAddToPlaylist = {},
            shuffleEnabled = false,
            playlistEnabled = false
        )
    }
}
