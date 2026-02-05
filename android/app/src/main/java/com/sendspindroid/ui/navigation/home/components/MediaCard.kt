package com.sendspindroid.ui.navigation.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Media card for horizontal carousels on the home screen.
 *
 * Displays artwork, title, and type-specific subtitle.
 * Shows play overlay on focus (TV/keyboard navigation).
 *
 * @param item The library item to display
 * @param onClick Called when the card is tapped
 * @param modifier Modifier for the card
 */
@Composable
fun MediaCard(
    item: MaLibraryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .width(160.dp)
            .clickable(onClick = onClick)
            .onFocusChanged { isFocused = it.isFocused },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            // Album art with play overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                AsyncImage(
                    model = item.imageUri ?: R.drawable.placeholder_album,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                )

                // Play overlay (shown on focus)
                if (isFocused) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(48.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Title
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    lineHeight = 18.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp)
            )

            // Subtitle (type-specific)
            val subtitle = getSubtitle(item)
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp)
                )
            } else {
                // Bottom padding when no subtitle
                Box(modifier = Modifier.padding(bottom = 12.dp))
            }
        }
    }
}

/**
 * Get the subtitle string based on item type.
 */
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
private fun MediaCardPreview() {
    SendSpinTheme {
        MediaCard(
            item = MaTrack(
                itemId = "1",
                name = "Long Track Name That Might Overflow",
                artist = "Artist Name",
                album = "Album Name",
                imageUri = null,
                uri = null,
                duration = 225
            ),
            onClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MediaCardAlbumPreview() {
    SendSpinTheme {
        MediaCard(
            item = MaAlbum(
                albumId = "1",
                name = "Album Title",
                imageUri = null,
                uri = null,
                artist = "Artist Name",
                year = 2024,
                trackCount = 12,
                albumType = "album"
            ),
            onClick = {}
        )
    }
}
