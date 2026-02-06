package com.sendspindroid.ui.navigation.library

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.ui.navigation.search.components.SearchResultItem
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "BrowseListScreen"

/**
 * Browse list screen for a single library content type.
 *
 * Features:
 * - Sort chips at top (filtered by content type)
 * - Pull-to-refresh
 * - Infinite scroll pagination
 * - Loading/empty/error states
 *
 * @param stateFlow StateFlow for this tab's state
 * @param contentType The content type being displayed
 * @param sortOptions Available sort options for this type
 * @param onSortChange Called when sort option is changed
 * @param onLoadMore Called when more items should be loaded (pagination)
 * @param onRefresh Called when pull-to-refresh is triggered
 * @param onItemClick Called when an item is tapped
 * @param onAddToPlaylist Called when "Add to Playlist" is selected from overflow menu
 * @param onAddToQueue Called when "Add to Queue" is selected from overflow menu
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseListScreen(
    stateFlow: StateFlow<LibraryViewModel.TabState>,
    contentType: LibraryViewModel.ContentType,
    sortOptions: List<LibraryViewModel.SortOption>,
    onSortChange: (LibraryViewModel.SortOption) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onItemClick: (MaLibraryItem) -> Unit,
    onAddToPlaylist: (MaLibraryItem) -> Unit = {},
    onAddToQueue: (MaLibraryItem) -> Unit = {}
) {
    val state by stateFlow.collectAsState()
    val pullToRefreshState = rememberPullToRefreshState()
    val listState = rememberLazyListState()

    // Infinite scroll - load more when near the end
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1

            !state.isLoading &&
            !state.isLoadingMore &&
            state.hasMore &&
            state.items.isNotEmpty() &&
            lastVisibleItemIndex >= totalItems - 5
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            Log.d(TAG, "Loading more items for $contentType")
            onLoadMore()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Sort chips (only show if more than one option)
            if (sortOptions.size > 1) {
                SortChipsRow(
                    sortOptions = sortOptions,
                    selectedSort = state.sortOption,
                    onSortChange = onSortChange
                )
            }

            // Content area with pull-to-refresh
            PullToRefreshBox(
                isRefreshing = state.isLoading && state.items.isEmpty(),
                onRefresh = onRefresh,
                state = pullToRefreshState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    state.isLoading && state.items.isEmpty() -> {
                        LoadingState()
                    }
                    state.error != null && state.items.isEmpty() -> {
                        ErrorState(
                            message = state.error!!,
                            onRetry = onRefresh
                        )
                    }
                    state.items.isEmpty() -> {
                        EmptyState()
                    }
                    else -> {
                        ItemsList(
                            items = state.items,
                            listState = listState,
                            isLoadingMore = state.isLoadingMore,
                            onItemClick = onItemClick,
                            onAddToPlaylist = onAddToPlaylist,
                            onAddToQueue = onAddToQueue
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SortChipsRow(
    sortOptions: List<LibraryViewModel.SortOption>,
    selectedSort: LibraryViewModel.SortOption,
    onSortChange: (LibraryViewModel.SortOption) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sortOptions) { sortOption ->
            FilterChip(
                selected = sortOption == selectedSort,
                onClick = { onSortChange(sortOption) },
                label = { Text(getSortLabel(sortOption)) }
            )
        }
    }
}

@Composable
private fun getSortLabel(sort: LibraryViewModel.SortOption): String {
    return when (sort) {
        LibraryViewModel.SortOption.NAME -> stringResource(R.string.library_sort_name)
        LibraryViewModel.SortOption.DATE_ADDED -> stringResource(R.string.library_sort_date_added)
        LibraryViewModel.SortOption.YEAR -> stringResource(R.string.library_sort_year)
    }
}

@Composable
private fun ItemsList(
    items: List<MaLibraryItem>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isLoadingMore: Boolean,
    onItemClick: (MaLibraryItem) -> Unit,
    onAddToPlaylist: (MaLibraryItem) -> Unit,
    onAddToQueue: (MaLibraryItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        items(
            items = items,
            key = { "${it.mediaType}_${it.id}" }
        ) { item ->
            val isActionable = item is MaTrack || item is MaAlbum || item is MaArtist
            SearchResultItem(
                item = item,
                onClick = { onItemClick(item) },
                onAddToPlaylist = if (isActionable) {{ onAddToPlaylist(item) }} else null,
                onAddToQueue = if (isActionable) {{ onAddToQueue(item) }} else null
            )
        }

        // Loading more indicator
        if (isLoadingMore) {
            item(key = "loading_more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
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
                painter = painterResource(R.drawable.ic_nav_library),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .alpha(0.3f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.library_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorState(
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
            Icon(
                painter = painterResource(R.drawable.ic_nav_library),
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
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Retry")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BrowseListLoadingPreview() {
    SendSpinTheme {
        LoadingState()
    }
}

@Preview(showBackground = true)
@Composable
private fun BrowseListEmptyPreview() {
    SendSpinTheme {
        EmptyState()
    }
}
