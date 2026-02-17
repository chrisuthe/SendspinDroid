package com.sendspindroid.ui.detail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Hero header component for artist/album detail screens.
 *
 * Displays large artwork, title, and subtitle metadata.
 * This is the static version - for collapsing toolbar behavior,
 * wrap in a LargeTopAppBar with nestedScroll.
 *
 * @param title The main title (artist name or album name)
 * @param subtitle Metadata subtitle (e.g., "5 albums - 47 tracks" or "2023 - 12 tracks")
 * @param imageUri The artwork URL, or null for placeholder
 * @param placeholderIcon Icon to show when no image is available
 * @param modifier Optional modifier
 */
@Composable
fun HeroHeader(
    title: String,
    subtitle: String,
    imageUri: String?,
    placeholderIcon: ImageVector = Icons.Filled.Favorite,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Artwork
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            } else {
                Icon(
                    imageVector = placeholderIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .size(80.dp)
                        .align(Alignment.Center)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Title
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Subtitle
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

/**
 * Builds a subtitle string for an artist.
 *
 * @param albumCount Number of albums
 * @param trackCount Number of tracks
 * @return Formatted subtitle like "5 albums - 47 tracks"
 */
fun buildArtistSubtitle(albumCount: Int, trackCount: Int): String {
    val albumText = if (albumCount == 1) "1 album" else "$albumCount albums"
    val trackText = if (trackCount == 1) "1 track" else "$trackCount tracks"
    return "$albumText - $trackText"
}

/**
 * Builds a subtitle string for an album.
 *
 * @param artist Artist name, or null
 * @param year Release year, or null
 * @param trackCount Number of tracks, or null
 * @param totalDuration Total duration in seconds, or null
 * @return Formatted subtitle like "Artist Name - 2023 - 12 tracks - 45 min"
 */
fun buildAlbumSubtitle(
    artist: String?,
    year: Int?,
    trackCount: Int?,
    totalDuration: Long? = null
): String {
    val parts = mutableListOf<String>()

    if (!artist.isNullOrEmpty()) {
        parts.add(artist)
    }
    if (year != null && year > 0) {
        parts.add(year.toString())
    }
    if (trackCount != null && trackCount > 0) {
        val trackText = if (trackCount == 1) "1 track" else "$trackCount tracks"
        parts.add(trackText)
    }
    if (totalDuration != null && totalDuration > 0) {
        val minutes = totalDuration / 60
        parts.add("$minutes min")
    }

    return parts.joinToString(" - ")
}

@Preview(showBackground = true)
@Composable
private fun HeroHeaderArtistPreview() {
    SendSpinTheme {
        HeroHeader(
            title = "Artist Name",
            subtitle = buildArtistSubtitle(albumCount = 5, trackCount = 47),
            imageUri = null,
            placeholderIcon = Icons.Filled.Person
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HeroHeaderAlbumPreview() {
    SendSpinTheme {
        HeroHeader(
            title = "Album Name",
            subtitle = buildAlbumSubtitle(
                artist = "Artist Name",
                year = 2023,
                trackCount = 12,
                totalDuration = 2700
            ),
            imageUri = null,
            placeholderIcon = Icons.Filled.Favorite
        )
    }
}
