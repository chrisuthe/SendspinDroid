package com.sendspindroid.ui.detail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * A single track row for album/artist track listings.
 *
 * Displays track number, title, optional featured artist, and duration.
 * The track number uses monospace font for consistent alignment.
 *
 * @param track The track to display
 * @param trackNumber The track position (1-indexed)
 * @param showArtist Whether to show the artist name below the title
 * @param isPlaying Whether this track is currently playing (for visual highlight)
 * @param onClick Called when the track is tapped
 * @param modifier Optional modifier for the row
 */
@Composable
fun TrackListItem(
    track: MaTrack,
    trackNumber: Int,
    showArtist: Boolean = false,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Track number - uses monospace for alignment
        Text(
            text = trackNumber.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = if (isPlaying) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.width(32.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Title and optional artist
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isPlaying) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (showArtist && !track.artist.isNullOrEmpty()) {
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Duration
        track.duration?.let { durationSeconds ->
            Text(
                text = formatDuration(durationSeconds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Format duration in seconds to "m:ss" format.
 */
private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "$mins:${secs.toString().padStart(2, '0')}"
}

@Preview(showBackground = true)
@Composable
private fun TrackListItemPreview() {
    SendSpinTheme {
        Column {
            TrackListItem(
                track = MaTrack(
                    itemId = "1",
                    name = "Opening Track",
                    artist = "Artist Name",
                    album = "Album Name",
                    imageUri = null,
                    uri = null,
                    duration = 225
                ),
                trackNumber = 1,
                onClick = {}
            )
            TrackListItem(
                track = MaTrack(
                    itemId = "2",
                    name = "Second Song (feat. Guest Artist)",
                    artist = "Guest Artist",
                    album = "Album Name",
                    imageUri = null,
                    uri = null,
                    duration = 312
                ),
                trackNumber = 2,
                showArtist = true,
                isPlaying = true,
                onClick = {}
            )
        }
    }
}
