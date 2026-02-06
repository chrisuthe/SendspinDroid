package com.sendspindroid.ui.navigation.library

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.sendspindroid.R
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.launch

private const val TAG = "LibraryScreen"

/**
 * Library screen with tabbed content browser.
 *
 * Displays tabs for:
 * - Albums
 * - Artists
 * - Playlists
 * - Tracks
 * - Radio
 *
 * Uses HorizontalPager for swipeable tab content.
 *
 * @param viewModel The shared LibraryViewModel
 * @param onAlbumClick Called when an album is tapped (navigates to detail)
 * @param onArtistClick Called when an artist is tapped (navigates to detail)
 * @param onItemClick Called when any other item is tapped (plays immediately)
 * @param onAddToPlaylist Called when "Add to Playlist" is selected from item overflow menu
 * @param onAddToQueue Called when "Add to Queue" is selected from item overflow menu
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onAlbumClick: (MaAlbum) -> Unit,
    onArtistClick: (MaArtist) -> Unit,
    onItemClick: (MaLibraryItem) -> Unit,
    onAddToPlaylist: (MaLibraryItem) -> Unit = {},
    onAddToQueue: (MaLibraryItem) -> Unit = {}
) {
    val tabs = LibraryViewModel.ContentType.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    // Load initial data when tab becomes visible
    LaunchedEffect(pagerState.currentPage) {
        val contentType = tabs[pagerState.currentPage]
        viewModel.loadItems(contentType)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Tab row
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    if (pagerState.currentPage < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage])
                        )
                    }
                }
            ) {
                tabs.forEachIndexed { index, contentType ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = { Text(getTabTitle(contentType)) }
                    )
                }
            }

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                val contentType = tabs[page]

                BrowseListScreen(
                    stateFlow = viewModel.getStateFor(contentType),
                    contentType = contentType,
                    sortOptions = viewModel.getSortOptionsFor(contentType),
                    onSortChange = { sort -> viewModel.setSortOption(contentType, sort) },
                    onLoadMore = { viewModel.loadMore(contentType) },
                    onRefresh = { viewModel.refresh(contentType) },
                    onItemClick = { item ->
                        handleItemClick(item, onAlbumClick, onArtistClick, onItemClick)
                    },
                    onAddToPlaylist = onAddToPlaylist,
                    onAddToQueue = onAddToQueue
                )
            }
        }
    }
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
private fun getTabTitle(contentType: LibraryViewModel.ContentType): String {
    return when (contentType) {
        LibraryViewModel.ContentType.ALBUMS -> stringResource(R.string.library_tab_albums)
        LibraryViewModel.ContentType.ARTISTS -> stringResource(R.string.library_tab_artists)
        LibraryViewModel.ContentType.PLAYLISTS -> stringResource(R.string.library_tab_playlists)
        LibraryViewModel.ContentType.TRACKS -> stringResource(R.string.library_tab_tracks)
        LibraryViewModel.ContentType.RADIO -> stringResource(R.string.library_tab_radio)
    }
}

@Preview(showBackground = true)
@Composable
private fun LibraryScreenPreview() {
    SendSpinTheme {
        // Preview would require a mock ViewModel
        Text("Library Screen Preview")
    }
}
