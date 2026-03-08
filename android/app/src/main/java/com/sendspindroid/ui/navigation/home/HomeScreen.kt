package com.sendspindroid.ui.navigation.home

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.ui.navigation.home.HomeViewModel.SectionState
import com.sendspindroid.ui.navigation.home.components.MediaCarousel
import com.sendspindroid.ui.theme.SendSpinTheme

private const val TAG = "HomeScreen"

/**
 * Home screen displaying horizontal carousels for different library sections.
 *
 * Sections displayed:
 * - Recently Played
 * - Recently Added
 * - Albums
 * - Artists
 * - Playlists
 * - Radio Stations
 *
 * @param viewModel The HomeViewModel managing section data
 * @param onAlbumClick Called when an album card is tapped
 * @param onArtistClick Called when an artist card is tapped
 * @param onItemClick Called when any other item card is tapped (tracks, playlists, radio)
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onAlbumClick: (MaAlbum) -> Unit,
    onArtistClick: (MaArtist) -> Unit,
    onItemClick: (MaLibraryItem) -> Unit
) {
    // Load data when screen is first shown
    LaunchedEffect(Unit) {
        viewModel.loadHomeData()
    }

    // Observe all section states via StateFlow
    val recentlyPlayedState by viewModel.recentlyPlayed.collectAsStateWithLifecycle()
    val recentlyAddedState by viewModel.recentlyAdded.collectAsStateWithLifecycle()
    val albumsState by viewModel.albums.collectAsStateWithLifecycle()
    val artistsState by viewModel.artists.collectAsStateWithLifecycle()
    val playlistsState by viewModel.playlists.collectAsStateWithLifecycle()
    val radioState by viewModel.radioStations.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    HomeScreenContent(
        recentlyPlayedState = recentlyPlayedState,
        recentlyAddedState = recentlyAddedState,
        albumsState = albumsState,
        artistsState = artistsState,
        playlistsState = playlistsState,
        radioState = radioState,
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
        onItemClick = { item ->
            handleItemClick(item, onAlbumClick, onArtistClick, onItemClick)
        }
    )
}

/**
 * Handle click on a library item, routing to the appropriate callback.
 */
private fun handleItemClick(
    item: MaLibraryItem,
    onAlbumClick: (MaAlbum) -> Unit,
    onArtistClick: (MaArtist) -> Unit,
    onItemClick: (MaLibraryItem) -> Unit
) {
    when (item) {
        is MaAlbum -> {
            Log.d(TAG, "Album clicked: ${item.name}")
            onAlbumClick(item)
        }
        is MaArtist -> {
            Log.d(TAG, "Artist clicked: ${item.name}")
            onArtistClick(item)
        }
        else -> {
            Log.d(TAG, "Item clicked: ${item.name} (${item.mediaType})")
            onItemClick(item)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenContent(
    recentlyPlayedState: SectionState<MaLibraryItem>,
    recentlyAddedState: SectionState<MaLibraryItem>,
    albumsState: SectionState<MaLibraryItem>,
    artistsState: SectionState<MaLibraryItem>,
    playlistsState: SectionState<MaLibraryItem>,
    radioState: SectionState<MaLibraryItem>,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onItemClick: (MaLibraryItem) -> Unit
) {
    // Check if all sections have finished loading and all are empty
    val allSections = listOf(
        recentlyPlayedState, recentlyAddedState, albumsState,
        artistsState, playlistsState, radioState
    )
    val allLoaded = allSections.none { it is SectionState.Loading }
    val allEmpty = allLoaded && allSections.all { state ->
        state is SectionState.Success && state.items.isEmpty()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        val pullToRefreshState = rememberPullToRefreshState()

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = pullToRefreshState,
            modifier = Modifier.fillMaxSize()
        ) {
            if (allEmpty) {
                HomeEmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Only show sections that have content or are still loading/errored.
                    // Hide sections that loaded successfully but returned no items.

                    // Recently Played section
                    if (!recentlyPlayedState.isEmptySuccess()) {
                        item(key = "recently_played") {
                            MediaCarousel(
                                title = stringResource(R.string.home_recently_played),
                                state = recentlyPlayedState,
                                onItemClick = onItemClick,
                                onRetry = onRefresh,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }

                    // Recently Added section
                    if (!recentlyAddedState.isEmptySuccess()) {
                        item(key = "recently_added") {
                            MediaCarousel(
                                title = stringResource(R.string.home_recently_added),
                                state = recentlyAddedState,
                                onItemClick = onItemClick,
                                onRetry = onRefresh
                            )
                        }
                    }

                    // Albums section
                    if (!albumsState.isEmptySuccess()) {
                        item(key = "albums") {
                            MediaCarousel(
                                title = stringResource(R.string.home_albums),
                                state = albumsState,
                                onItemClick = onItemClick,
                                onRetry = onRefresh
                            )
                        }
                    }

                    // Artists section
                    if (!artistsState.isEmptySuccess()) {
                        item(key = "artists") {
                            MediaCarousel(
                                title = stringResource(R.string.home_artists),
                                state = artistsState,
                                onItemClick = onItemClick,
                                onRetry = onRefresh
                            )
                        }
                    }

                    // Playlists section
                    if (!playlistsState.isEmptySuccess()) {
                        item(key = "playlists") {
                            MediaCarousel(
                                title = stringResource(R.string.home_playlists),
                                state = playlistsState,
                                onItemClick = onItemClick,
                                onRetry = onRefresh
                            )
                        }
                    }

                    // Radio Stations section
                    if (!radioState.isEmptySuccess()) {
                        item(key = "radio") {
                            MediaCarousel(
                                title = stringResource(R.string.home_radio),
                                state = radioState,
                                onItemClick = onItemClick,
                                onRetry = onRefresh
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Returns true if this section loaded successfully but has no items.
 */
private fun <T> SectionState<T>.isEmptySuccess(): Boolean =
    this is SectionState.Success && items.isEmpty()

/**
 * Empty state shown when all home screen sections have loaded but contain no content.
 */
@Composable
private fun HomeEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_nav_home),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.home_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.home_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenEmptyPreview() {
    SendSpinTheme {
        HomeScreenContent(
            recentlyPlayedState = SectionState.Success(emptyList()),
            recentlyAddedState = SectionState.Success(emptyList()),
            albumsState = SectionState.Success(emptyList()),
            artistsState = SectionState.Success(emptyList()),
            playlistsState = SectionState.Success(emptyList()),
            radioState = SectionState.Success(emptyList()),
            isRefreshing = false,
            onRefresh = {},
            onItemClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    SendSpinTheme {
        HomeScreenContent(
            recentlyPlayedState = SectionState.Loading,
            recentlyAddedState = SectionState.Loading,
            albumsState = SectionState.Loading,
            artistsState = SectionState.Loading,
            playlistsState = SectionState.Loading,
            radioState = SectionState.Loading,
            isRefreshing = false,
            onRefresh = {},
            onItemClick = {}
        )
    }
}
