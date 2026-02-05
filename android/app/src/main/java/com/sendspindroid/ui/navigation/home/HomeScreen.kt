package com.sendspindroid.ui.navigation.home

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

    // Observe all section states
    val recentlyPlayedState by viewModel.recentlyPlayed.observeAsState(SectionState.Loading)
    val recentlyAddedState by viewModel.recentlyAdded.observeAsState(SectionState.Loading)
    val albumsState by viewModel.albums.observeAsState(SectionState.Loading)
    val artistsState by viewModel.artists.observeAsState(SectionState.Loading)
    val playlistsState by viewModel.playlists.observeAsState(SectionState.Loading)
    val radioState by viewModel.radioStations.observeAsState(SectionState.Loading)

    HomeScreenContent(
        recentlyPlayedState = recentlyPlayedState,
        recentlyAddedState = recentlyAddedState,
        albumsState = albumsState,
        artistsState = artistsState,
        playlistsState = playlistsState,
        radioState = radioState,
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

@Composable
private fun HomeScreenContent(
    recentlyPlayedState: SectionState<MaLibraryItem>,
    recentlyAddedState: SectionState<MaLibraryItem>,
    albumsState: SectionState<MaLibraryItem>,
    artistsState: SectionState<MaLibraryItem>,
    playlistsState: SectionState<MaLibraryItem>,
    radioState: SectionState<MaLibraryItem>,
    onItemClick: (MaLibraryItem) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            // Recently Played section
            item(key = "recently_played") {
                MediaCarousel(
                    title = stringResource(R.string.home_recently_played),
                    state = recentlyPlayedState,
                    onItemClick = onItemClick,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Recently Added section
            item(key = "recently_added") {
                MediaCarousel(
                    title = stringResource(R.string.home_recently_added),
                    state = recentlyAddedState,
                    onItemClick = onItemClick
                )
            }

            // Albums section
            item(key = "albums") {
                MediaCarousel(
                    title = stringResource(R.string.home_albums),
                    state = albumsState,
                    onItemClick = onItemClick
                )
            }

            // Artists section
            item(key = "artists") {
                MediaCarousel(
                    title = stringResource(R.string.home_artists),
                    state = artistsState,
                    onItemClick = onItemClick
                )
            }

            // Playlists section
            item(key = "playlists") {
                MediaCarousel(
                    title = stringResource(R.string.home_playlists),
                    state = playlistsState,
                    onItemClick = onItemClick
                )
            }

            // Radio Stations section
            item(key = "radio") {
                MediaCarousel(
                    title = stringResource(R.string.home_radio),
                    state = radioState,
                    onItemClick = onItemClick
                )
            }

            // Bottom padding for mini player
            item(key = "bottom_spacing") {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
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
            onItemClick = {}
        )
    }
}
