package com.sendspindroid.ui.detail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sendspindroid.R
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * A grid item displaying an album with artwork, name, year, and optional type badge.
 *
 * Used in the Artist Detail screen's discography section.
 * Shows album artwork with rounded corners, album name, year, and
 * a type badge for non-"album" releases (EP, Single, Live, Compilation).
 *
 * @param album The album to display
 * @param onClick Called when the item is tapped
 * @param onAddToPlaylist Optional callback for adding album to playlist (shows overflow menu)
 * @param modifier Optional modifier for the item
 */
@Composable
fun AlbumGridItem(
    album: MaAlbum,
    onClick: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        // Album artwork
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (album.imageUri != null) {
                AsyncImage(
                    model = album.imageUri,
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            } else {
                // Placeholder icon
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center)
                )
            }

            // Overflow menu button at top-right
            if (onAddToPlaylist != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                ) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More options for ${album.name}",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
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
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Album name
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Year and type badge
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            album.year?.let { year ->
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Type badge for non-album releases
            album.albumType?.let { type ->
                if (type != "album" && type.isNotEmpty()) {
                    if (album.year != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    AlbumTypeBadge(type = type)
                }
            }
        }
    }
}

/**
 * A small badge showing the album type (EP, Single, Live, Compilation).
 */
@Composable
private fun AlbumTypeBadge(
    type: String,
    modifier: Modifier = Modifier
) {
    val displayText = when (type.lowercase()) {
        "ep" -> "EP"
        "single" -> "Single"
        "live" -> "Live"
        "compilation" -> "Compilation"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
    ) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AlbumGridItemPreview() {
    SendSpinTheme {
        Row(modifier = Modifier.width(320.dp)) {
            AlbumGridItem(
                album = MaAlbum(
                    albumId = "1",
                    name = "Greatest Hits",
                    imageUri = null,
                    uri = null,
                    artist = "Artist Name",
                    year = 2023,
                    trackCount = 12,
                    albumType = "album"
                ),
                onClick = {},
                modifier = Modifier.weight(1f)
            )
            AlbumGridItem(
                album = MaAlbum(
                    albumId = "2",
                    name = "Extended Play",
                    imageUri = null,
                    uri = null,
                    artist = "Artist Name",
                    year = 2021,
                    trackCount = 5,
                    albumType = "ep"
                ),
                onClick = {},
                modifier = Modifier.weight(1f)
            )
        }
    }
}
