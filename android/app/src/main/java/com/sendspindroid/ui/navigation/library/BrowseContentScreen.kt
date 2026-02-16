package com.sendspindroid.ui.navigation.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sendspindroid.R
import com.sendspindroid.musicassistant.MaBrowseFolder
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.ui.navigation.search.components.SearchResultItem

/**
 * Browse tab content screen with folder navigation.
 *
 * Displays a hierarchical view of Music Assistant provider content:
 * - Root level: one folder per provider (SiriusXM, filesystem, etc.)
 * - Provider/subfolder level: folders and media items
 *
 * Back button navigates up the folder hierarchy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseContentScreen(
    viewModel: LibraryViewModel,
    onItemClick: (MaLibraryItem) -> Unit,
    onAddToPlaylist: (MaLibraryItem) -> Unit = {},
    onAddToQueue: (MaLibraryItem) -> Unit = {},
    onPlayNext: (MaLibraryItem) -> Unit = {}
) {
    val state by viewModel.browseState.collectAsState()
    val pullToRefreshState = rememberPullToRefreshState()

    // Intercept back button when not at root
    BackHandler(enabled = !viewModel.isAtBrowseRoot()) {
        viewModel.navigateBack()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        PullToRefreshBox(
            isRefreshing = state.isLoading && state.items.isEmpty(),
            onRefresh = { viewModel.refresh(LibraryViewModel.ContentType.BROWSE) },
            state = pullToRefreshState,
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                state.isLoading && state.items.isEmpty() -> {
                    BrowseLoadingState()
                }
                state.error != null && state.items.isEmpty() -> {
                    BrowseErrorState(
                        message = state.error!!,
                        onRetry = { viewModel.refresh(LibraryViewModel.ContentType.BROWSE) }
                    )
                }
                state.items.isEmpty() -> {
                    BrowseEmptyState()
                }
                else -> {
                    BrowseItemsList(
                        items = state.items,
                        isAtRoot = viewModel.isAtBrowseRoot(),
                        onNavigateUp = { viewModel.navigateBack() },
                        onFolderClick = { folder -> viewModel.navigateToFolder(folder.path) },
                        onItemClick = onItemClick,
                        onAddToPlaylist = onAddToPlaylist,
                        onAddToQueue = onAddToQueue,
                        onPlayNext = onPlayNext
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowseItemsList(
    items: List<MaLibraryItem>,
    isAtRoot: Boolean,
    onNavigateUp: () -> Unit,
    onFolderClick: (MaBrowseFolder) -> Unit,
    onItemClick: (MaLibraryItem) -> Unit,
    onAddToPlaylist: (MaLibraryItem) -> Unit,
    onAddToQueue: (MaLibraryItem) -> Unit,
    onPlayNext: (MaLibraryItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        if (!isAtRoot) {
            item(key = "navigate_up") {
                BrowseUpItem(onClick = onNavigateUp)
            }
        }
        items(
            items = items,
            key = { item ->
                if (item is MaBrowseFolder) "folder_${item.path}" else "${item.mediaType}_${item.id}"
            }
        ) { item ->
            if (item is MaBrowseFolder) {
                BrowseFolderItem(
                    folder = item,
                    onClick = { onFolderClick(item) }
                )
            } else {
                SearchResultItem(
                    item = item,
                    onClick = { onItemClick(item) },
                    onAddToPlaylist = { onAddToPlaylist(item) },
                    onAddToQueue = { onAddToQueue(item) },
                    onPlayNext = { onPlayNext(item) }
                )
            }
        }
    }
}

/**
 * A folder row item showing folder icon (or thumbnail), name, and chevron.
 */
@Composable
private fun BrowseFolderItem(
    folder: MaBrowseFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Folder thumbnail or icon
        if (folder.imageUri != null) {
            AsyncImage(
                model = folder.imageUri,
                contentDescription = folder.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Folder name
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Chevron
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BrowseUpItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = "Up",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BrowseLoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun BrowseEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .alpha(0.3f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.browse_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BrowseErrorState(
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
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .alpha(0.3f),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(16.dp))
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
