package com.sendspindroid.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.ui.detail.components.ActionRow
import com.sendspindroid.ui.detail.components.HeroHeader
import com.sendspindroid.ui.detail.components.TrackListItem
import com.sendspindroid.ui.detail.components.buildAlbumSubtitle
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Album Detail screen displaying album info and track listing.
 * Back navigation is handled by the Activity toolbar.
 *
 * @param albumId The MA album item_id to display
 * @param onArtistClick Called when the artist name is tapped (navigates to artist detail)
 * @param viewModel The ViewModel managing album state
 */
@Composable
fun AlbumDetailScreen(
    albumId: String,
    onArtistClick: (artistName: String) -> Unit,
    viewModel: AlbumDetailViewModel = viewModel()
) {
    // Load album when screen is first shown
    LaunchedEffect(albumId) {
        viewModel.loadAlbum(albumId)
    }

    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is AlbumDetailUiState.Loading -> {
                LoadingContent()
            }

            is AlbumDetailUiState.Error -> {
                ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.refresh() }
                )
            }

            is AlbumDetailUiState.Success -> {
                AlbumDetailContent(
                    state = state,
                    onTrackClick = { viewModel.playTrack(it) },
                    onArtistClick = onArtistClick,
                    onShuffle = { viewModel.shuffleAll() },
                    onAddToQueue = { viewModel.addToQueue() }
                )
            }
        }
    }
}

@Composable
private fun AlbumDetailContent(
    state: AlbumDetailUiState.Success,
    onTrackClick: (MaTrack) -> Unit,
    onArtistClick: (artistName: String) -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize()
    ) {
        // Hero header with album artwork and metadata
        item {
            AlbumHeroHeader(
                album = state.album,
                totalDuration = state.totalDuration,
                trackCount = state.tracks.size,
                onArtistClick = onArtistClick
            )
        }

        // Action row
        item {
            ActionRow(
                onShuffle = onShuffle,
                onAddToQueue = onAddToQueue
            )
        }

        // Track divider
        item {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        // Track listing
        itemsIndexed(
            items = state.tracks,
            key = { _, track -> track.itemId }
        ) { index, track ->
            TrackListItem(
                track = track,
                trackNumber = index + 1,
                showArtist = track.artist != state.album.artist,
                onClick = { onTrackClick(track) }
            )
        }

        // Bottom spacing for FAB
        item {
            Spacer(modifier = Modifier.height(88.dp))
        }
    }
}

/**
 * Album-specific hero header with tappable artist name.
 */
@Composable
private fun AlbumHeroHeader(
    album: MaAlbum,
    totalDuration: Long,
    trackCount: Int,
    onArtistClick: (artistName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeroHeader(
            title = album.name,
            subtitle = "", // We'll show artist separately to make it tappable
            imageUri = album.imageUri,
            placeholderIcon = Icons.Filled.Favorite
        )

        // Tappable artist name
        if (!album.artist.isNullOrEmpty()) {
            Text(
                text = album.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onArtistClick(album.artist) }
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // Metadata line (year, track count, duration)
        Text(
            text = buildAlbumSubtitle(
                artist = null, // Already shown above
                year = album.year,
                trackCount = trackCount,
                totalDuration = totalDuration
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AlbumDetailScreenPreview() {
    SendSpinTheme {
        AlbumDetailContent(
            state = AlbumDetailUiState.Success(
                album = MaAlbum(
                    albumId = "1",
                    name = "Album Name",
                    imageUri = null,
                    uri = "library://album/1",
                    artist = "Artist Name",
                    year = 2023,
                    trackCount = 12,
                    albumType = "album"
                ),
                tracks = listOf(
                    MaTrack("1", "Opening Track", "Artist Name", "Album Name", null, null, 225),
                    MaTrack("2", "Second Song", "Artist Name", "Album Name", null, null, 312),
                    MaTrack("3", "Third Track (feat. Guest)", "Guest Artist", "Album Name", null, null, 198),
                    MaTrack("4", "Fourth Song", "Artist Name", "Album Name", null, null, 285),
                    MaTrack("5", "Closing Track", "Artist Name", "Album Name", null, null, 420)
                )
            ),
            onTrackClick = {},
            onArtistClick = {},
            onShuffle = {},
            onAddToQueue = {}
        )
    }
}
