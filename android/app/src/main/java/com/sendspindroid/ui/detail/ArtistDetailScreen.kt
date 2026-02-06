package com.sendspindroid.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
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
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.ui.detail.components.ActionRow
import com.sendspindroid.ui.detail.components.AlbumGridItem
import com.sendspindroid.ui.detail.components.HeroHeader
import com.sendspindroid.ui.detail.components.TrackListItem
import com.sendspindroid.ui.detail.components.buildArtistSubtitle
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Artist Detail screen displaying artist info, top tracks, and discography.
 * Back navigation is handled by the Activity toolbar.
 *
 * @param artistId The MA artist item_id to display
 * @param onAlbumClick Called when an album in the discography is tapped
 * @param viewModel The ViewModel managing artist state
 */
@Composable
fun ArtistDetailScreen(
    artistId: String,
    onAlbumClick: (MaAlbum) -> Unit,
    viewModel: ArtistDetailViewModel = viewModel()
) {
    // Load artist when screen is first shown
    LaunchedEffect(artistId) {
        viewModel.loadArtist(artistId)
    }

    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is ArtistDetailUiState.Loading -> {
                LoadingContent()
            }

            is ArtistDetailUiState.Error -> {
                ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.refresh() }
                )
            }

            is ArtistDetailUiState.Success -> {
                ArtistDetailContent(
                    state = state,
                    onTrackClick = { viewModel.playTrack(it) },
                    onShowAllTracksClick = { viewModel.toggleShowAllTracks() },
                    onAlbumClick = onAlbumClick,
                    onShuffle = { viewModel.shuffleAll() },
                    onAddToQueue = { viewModel.addToQueue() }
                )
            }
        }
    }
}

@Composable
private fun ArtistDetailContent(
    state: ArtistDetailUiState.Success,
    onTrackClick: (MaTrack) -> Unit,
    onShowAllTracksClick: () -> Unit,
    onAlbumClick: (MaAlbum) -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize()
    ) {
        // Hero header
        item {
            HeroHeader(
                title = state.artist.name,
                subtitle = buildArtistSubtitle(
                    albumCount = state.albums.size,
                    trackCount = state.totalTrackCount
                ),
                imageUri = state.artist.imageUri,
                placeholderIcon = Icons.Filled.Person
            )
        }

        // Action row
        item {
            ActionRow(
                onShuffle = onShuffle,
                onAddToQueue = onAddToQueue
            )
        }

        // Top tracks section
        if (state.topTracks.isNotEmpty()) {
            item {
                SectionHeader(title = "TOP TRACKS")
            }

            itemsIndexed(
                items = state.displayedTracks,
                key = { _, track -> track.itemId }
            ) { index, track ->
                TrackListItem(
                    track = track,
                    trackNumber = index + 1,
                    showArtist = false,
                    onClick = { onTrackClick(track) }
                )
            }

            // Show all tracks button
            if (state.topTracks.size > 5) {
                item {
                    TextButton(
                        onClick = onShowAllTracksClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = if (state.showAllTracks) "Show less" else "Show all tracks"
                        )
                    }
                }
            }
        }

        // Discography section
        if (state.albums.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(title = "DISCOGRAPHY")
            }

            // Album grid - we use a fixed height grid inside LazyColumn
            item {
                AlbumGrid(
                    albums = state.albums,
                    onAlbumClick = onAlbumClick
                )
            }
        }

        // Bottom spacing for FAB
        item {
            Spacer(modifier = Modifier.height(88.dp))
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<MaAlbum>,
    onAlbumClick: (MaAlbum) -> Unit,
    modifier: Modifier = Modifier
) {
    // Fixed-height grid for use inside LazyColumn
    // Calculate height based on number of rows (2 columns)
    val rows = (albums.size + 1) / 2
    val itemHeight = 220.dp // Approximate height per grid item
    val gridHeight = (itemHeight * rows)

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .fillMaxWidth()
            .height(gridHeight),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        userScrollEnabled = false // Disable nested scrolling
    ) {
        items(
            items = albums,
            key = { it.albumId }
        ) { album ->
            AlbumGridItem(
                album = album,
                onClick = { onAlbumClick(album) }
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
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
private fun ArtistDetailScreenPreview() {
    SendSpinTheme {
        ArtistDetailContent(
            state = ArtistDetailUiState.Success(
                artist = MaArtist(
                    artistId = "1",
                    name = "Artist Name",
                    imageUri = null,
                    uri = "library://artist/1"
                ),
                topTracks = listOf(
                    MaTrack("1", "Track One", "Artist Name", "Album", null, null, 225),
                    MaTrack("2", "Track Two", "Artist Name", "Album", null, null, 312),
                    MaTrack("3", "Track Three", "Artist Name", "Album", null, null, 198)
                ),
                albums = listOf(
                    MaAlbum("1", "First Album", null, null, "Artist Name", 2023, 12, "album"),
                    MaAlbum("2", "Extended Play", null, null, "Artist Name", 2021, 5, "ep"),
                    MaAlbum("3", "Single Track", null, null, "Artist Name", 2020, 1, "single")
                )
            ),
            onTrackClick = {},
            onShowAllTracksClick = {},
            onAlbumClick = {},
            onShuffle = {},
            onAddToQueue = {}
        )
    }
}
