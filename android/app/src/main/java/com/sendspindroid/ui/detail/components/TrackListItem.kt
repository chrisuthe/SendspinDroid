package com.sendspindroid.ui.detail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * A single track row for album/artist/playlist track listings.
 *
 * Displays track number, title, optional featured artist, and duration.
 * The track number uses monospace font for consistent alignment.
 *
 * When [onAddToPlaylist] or [onRemoveFromPlaylist] is non-null, a three-dot
 * overflow menu appears at the trailing edge with contextual actions.
 *
 * @param track The track to display
 * @param trackNumber The track position (1-indexed)
 * @param showArtist Whether to show the artist name below the title
 * @param isPlaying Whether this track is currently playing (for visual highlight)
 * @param onClick Called when the track is tapped
 * @param onAddToPlaylist Called when "Add to Playlist" is selected from overflow menu
 * @param onRemoveFromPlaylist Called when "Remove from Playlist" is selected from overflow menu
 * @param modifier Optional modifier for the row
 */
@Composable
fun TrackListItem(
    track: MaTrack,
    trackNumber: Int,
    showArtist: Boolean = false,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val hasMenu = onAddToPlaylist != null || onRemoveFromPlaylist != null
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = 16.dp,
                end = if (hasMenu) 4.dp else 16.dp,
                top = 12.dp,
                bottom = 12.dp
            ),
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

        // Overflow menu
        if (hasMenu) {
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Play") },
                        leadingIcon = {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            onClick()
                        }
                    )

                    if (onAddToPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_to_playlist)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Add, contentDescription = null)
                            },
                            onClick = {
                                showMenu = false
                                onAddToPlaylist()
                            }
                        )
                    }

                    if (onRemoveFromPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remove_from_playlist)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Delete, contentDescription = null)
                            },
                            onClick = {
                                showMenu = false
                                onRemoveFromPlaylist()
                            }
                        )
                    }
                }
            }
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
                onClick = {},
                onAddToPlaylist = {}
            )
        }
    }
}
