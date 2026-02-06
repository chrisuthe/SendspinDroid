package com.sendspindroid.ui.navigation.search.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sendspindroid.R
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaRadio
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Search result item displaying thumbnail, title, and subtitle.
 *
 * Renders different subtitles based on item type:
 * - Track: Artist name
 * - Album: Artist - Year
 * - Playlist: Track count
 * - Radio: Provider
 * - Artist: No subtitle
 *
 * When [onAddToPlaylist] or [onAddToQueue] is non-null and the item is a [MaTrack],
 * [MaAlbum], or [MaArtist], a three-dot overflow menu appears with available actions.
 *
 * @param item The library item to display
 * @param onClick Called when the item is tapped
 * @param onAddToPlaylist Called when "Add to Playlist" is selected (tracks, albums, artists)
 * @param onAddToQueue Called when "Add to Queue" is selected (tracks, albums, artists)
 * @param modifier Modifier for the item
 */
@Composable
fun SearchResultItem(
    item: MaLibraryItem,
    onClick: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isActionableType = item is MaTrack || item is MaAlbum || item is MaArtist
    val showOverflow = isActionableType && (onAddToPlaylist != null || onAddToQueue != null)
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = 16.dp,
                end = if (showOverflow) 4.dp else 16.dp,
                top = 12.dp,
                bottom = 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        AsyncImage(
            model = item.imageUri ?: R.drawable.placeholder_album,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Title and subtitle
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val subtitle = getSubtitle(item)
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Overflow menu (tracks only)
        if (showOverflow) {
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

                    if (onAddToQueue != null) {
                        DropdownMenuItem(
                            text = { Text("Add to Queue") },
                            leadingIcon = {
                                Icon(Icons.Filled.Add, contentDescription = null)
                            },
                            onClick = {
                                showMenu = false
                                onAddToQueue()
                            }
                        )
                    }

                    if (onAddToPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_to_playlist)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                showMenu = false
                                onAddToPlaylist()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Section header for search results.
 */
@Composable
fun SearchResultsHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

private fun getSubtitle(item: MaLibraryItem): String = when (item) {
    is MaTrack -> item.artist ?: ""
    is MaPlaylist -> formatTrackCount(item.trackCount)
    is MaAlbum -> buildAlbumSubtitle(item)
    is MaArtist -> "" // No subtitle for artists
    is MaRadio -> item.provider ?: ""
    else -> ""
}

private fun formatTrackCount(count: Int): String = when {
    count == 0 -> ""
    count == 1 -> "1 track"
    else -> "$count tracks"
}

private fun buildAlbumSubtitle(album: MaAlbum): String {
    return listOfNotNull(
        album.artist,
        album.year?.toString()
    ).joinToString(" - ")
}

@Preview(showBackground = true)
@Composable
private fun SearchResultItemTrackPreview() {
    SendSpinTheme {
        SearchResultItem(
            item = MaTrack(
                itemId = "1",
                name = "Song Title",
                artist = "Artist Name",
                album = "Album Name",
                imageUri = null,
                uri = null,
                duration = 225
            ),
            onClick = {},
            onAddToPlaylist = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SearchResultsHeaderPreview() {
    SendSpinTheme {
        SearchResultsHeader(title = "Artists")
    }
}
