package com.sendspindroid.ui.navigation.search

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType
import com.sendspindroid.ui.navigation.search.components.SearchResultItem
import com.sendspindroid.ui.navigation.search.components.SearchResultsHeader
import com.sendspindroid.ui.theme.SendSpinTheme

private const val TAG = "SearchScreen"

/**
 * Search screen for searching the Music Assistant library.
 *
 * Features:
 * - Search input with debounced search
 * - Filter chips for media type selection
 * - Grouped results with section headers
 * - Empty state, no results state, error state, and loading state
 *
 * @param viewModel The SearchViewModel managing search state
 * @param onItemClick Called when a search result item is tapped
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onItemClick: (MaLibraryItem) -> Unit,
    onAlbumClick: (MaAlbum) -> Unit = {},
    onArtistClick: (MaArtist) -> Unit = {},
    onAddToPlaylist: (MaLibraryItem) -> Unit = {},
    onAddToQueue: (MaLibraryItem) -> Unit = {},
    onPlayNext: (MaLibraryItem) -> Unit = {}
) {
    val state by viewModel.searchState.collectAsState()

    SearchScreenContent(
        state = state,
        onQueryChange = { viewModel.setQuery(it) },
        onFilterToggle = { type, enabled -> viewModel.setFilter(type, enabled) },
        onItemClick = { item ->
            Log.d(TAG, "Item clicked: ${item.name} (${item.mediaType})")
            when (item) {
                is MaAlbum -> onAlbumClick(item)
                is MaArtist -> onArtistClick(item)
                else -> onItemClick(item)
            }
        },
        onAddToPlaylist = onAddToPlaylist,
        onAddToQueue = onAddToQueue,
        onPlayNext = onPlayNext
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreenContent(
    state: SearchViewModel.SearchState,
    onQueryChange: (String) -> Unit,
    onFilterToggle: (MaMediaType, Boolean) -> Unit,
    onItemClick: (MaLibraryItem) -> Unit,
    onAddToPlaylist: (MaLibraryItem) -> Unit = {},
    onAddToQueue: (MaLibraryItem) -> Unit = {},
    onPlayNext: (MaLibraryItem) -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()

        ) {
            // Search Input
            SearchBar(
                query = state.query,
                onQueryChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Filter Chips
            FilterChipsRow(
                activeFilters = state.activeFilters,
                onFilterToggle = onFilterToggle,
                modifier = Modifier.fillMaxWidth()
            )

            // Content area - results or states
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.TopCenter
            ) {
                when {
                    state.isLoading -> {
                        LoadingState()
                    }
                    state.error != null -> {
                        ErrorState(message = state.error)
                    }
                    state.hasNoResults -> {
                        NoResultsState(query = state.query)
                    }
                    state.showEmptyState -> {
                        EmptyState()
                    }
                    state.results != null && !state.results.isEmpty() -> {
                        SearchResultsList(
                            results = state.results,
                            onItemClick = onItemClick,
                            onAddToPlaylist = onAddToPlaylist,
                            onAddToQueue = onAddToQueue,
                            onPlayNext = onPlayNext
                        )
                    }
                    else -> {
                        EmptyState()
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = {
            Text(text = stringResource(R.string.search_hint))
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline
        )
    )
}

@Composable
private fun FilterChipsRow(
    activeFilters: Set<MaMediaType>,
    onFilterToggle: (MaMediaType, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val filterOptions = remember {
        listOf(
            MaMediaType.ALBUM to R.string.search_filter_albums,
            MaMediaType.ARTIST to R.string.search_filter_artists,
            MaMediaType.TRACK to R.string.search_filter_tracks,
            MaMediaType.PLAYLIST to R.string.search_filter_playlists,
            MaMediaType.RADIO to R.string.search_filter_radio
        )
    }

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filterOptions) { (type, labelRes) ->
            FilterChip(
                selected = activeFilters.contains(type),
                onClick = { onFilterToggle(type, !activeFilters.contains(type)) },
                label = { Text(stringResource(labelRes)) }
            )
        }
    }
}

@Composable
private fun SearchResultsList(
    results: MusicAssistantManager.SearchResults,
    onItemClick: (MaLibraryItem) -> Unit,
    onAddToPlaylist: (MaLibraryItem) -> Unit = {},
    onAddToQueue: (MaLibraryItem) -> Unit = {},
    onPlayNext: (MaLibraryItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
    ) {
        // Artists section
        if (results.artists.isNotEmpty()) {
            item(key = "header_artists") {
                SearchResultsHeader(title = stringResource(R.string.search_section_artists))
            }
            items(
                items = results.artists,
                key = { "artist_${it.id}" }
            ) { artist ->
                SearchResultItem(
                    item = artist,
                    onClick = { onItemClick(artist) },
                    onAddToPlaylist = { onAddToPlaylist(artist) },
                    onAddToQueue = { onAddToQueue(artist) },
                    onPlayNext = { onPlayNext(artist) }
                )
            }
        }

        // Albums section
        if (results.albums.isNotEmpty()) {
            item(key = "header_albums") {
                SearchResultsHeader(title = stringResource(R.string.search_section_albums))
            }
            items(
                items = results.albums,
                key = { "album_${it.id}" }
            ) { album ->
                SearchResultItem(
                    item = album,
                    onClick = { onItemClick(album) },
                    onAddToPlaylist = { onAddToPlaylist(album) },
                    onAddToQueue = { onAddToQueue(album) },
                    onPlayNext = { onPlayNext(album) }
                )
            }
        }

        // Tracks section
        if (results.tracks.isNotEmpty()) {
            item(key = "header_tracks") {
                SearchResultsHeader(title = stringResource(R.string.search_section_tracks))
            }
            items(
                items = results.tracks,
                key = { "track_${it.id}" }
            ) { track ->
                SearchResultItem(
                    item = track,
                    onClick = { onItemClick(track) },
                    onAddToPlaylist = { onAddToPlaylist(track) },
                    onAddToQueue = { onAddToQueue(track) },
                    onPlayNext = { onPlayNext(track) }
                )
            }
        }

        // Playlists section
        if (results.playlists.isNotEmpty()) {
            item(key = "header_playlists") {
                SearchResultsHeader(title = stringResource(R.string.search_section_playlists))
            }
            items(
                items = results.playlists,
                key = { "playlist_${it.id}" }
            ) { playlist ->
                SearchResultItem(
                    item = playlist,
                    onClick = { onItemClick(playlist) }
                )
            }
        }

        // Radio section
        if (results.radios.isNotEmpty()) {
            item(key = "header_radio") {
                SearchResultsHeader(title = stringResource(R.string.search_section_radio))
            }
            items(
                items = results.radios,
                key = { "radio_${it.id}" }
            ) { radio ->
                SearchResultItem(
                    item = radio,
                    onClick = { onItemClick(radio) }
                )
            }
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_nav_search),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .alpha(0.3f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.search_empty_state),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoResultsState(
    query: String,
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
            Icon(
                painter = painterResource(R.drawable.ic_nav_search),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .alpha(0.3f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.search_no_results_for, query),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
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
            Icon(
                painter = painterResource(R.drawable.ic_nav_search),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .alpha(0.3f),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SearchScreenEmptyPreview() {
    SendSpinTheme {
        SearchScreenContent(
            state = SearchViewModel.SearchState(),
            onQueryChange = {},
            onFilterToggle = { _, _ -> },
            onItemClick = {},
            onAddToPlaylist = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SearchScreenLoadingPreview() {
    SendSpinTheme {
        SearchScreenContent(
            state = SearchViewModel.SearchState(
                query = "test",
                isLoading = true
            ),
            onQueryChange = {},
            onFilterToggle = { _, _ -> },
            onItemClick = {},
            onAddToPlaylist = {}
        )
    }
}
