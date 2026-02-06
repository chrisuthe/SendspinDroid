package com.sendspindroid.ui.queue

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sendspindroid.R
import com.sendspindroid.musicassistant.MaQueueItem

/**
 * Bottom sheet displaying the player queue with playback controls.
 *
 * Shows the currently playing track, upcoming tracks, and provides
 * controls for shuffle, repeat, clear queue, and per-item actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    viewModel: QueueViewModel,
    onDismiss: () -> Unit,
    onBrowseLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // Load queue when sheet opens
    LaunchedEffect(Unit) {
        viewModel.loadQueue()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier
    ) {
        when (val state = uiState) {
            is QueueUiState.Loading -> {
                QueueLoadingContent()
            }
            is QueueUiState.Error -> {
                QueueErrorContent(
                    message = state.message,
                    onRetry = { viewModel.refresh() }
                )
            }
            is QueueUiState.Success -> {
                if (state.isEmpty) {
                    QueueEmptyContent(onBrowseLibrary = onBrowseLibrary)
                } else {
                    QueueContent(
                        state = state,
                        onPlayItem = { viewModel.playItem(it) },
                        onRemoveItem = { viewModel.removeItem(it) },
                        onMoveUp = { viewModel.moveItem(it, MoveDirection.UP) },
                        onMoveDown = { viewModel.moveItem(it, MoveDirection.DOWN) },
                        onToggleShuffle = { viewModel.toggleShuffle() },
                        onCycleRepeat = { viewModel.cycleRepeatMode() },
                        onClearQueue = { viewModel.clearQueue() }
                    )
                }
            }
        }
    }
}

// ============================================================================
// Queue Content (main view with items)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueContent(
    state: QueueUiState.Success,
    onPlayItem: (String) -> Unit,
    onRemoveItem: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onClearQueue: () -> Unit
) {
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        // Header
        QueueHeader(
            itemCount = state.totalItems,
            shuffleEnabled = state.shuffleEnabled,
            repeatMode = state.repeatMode,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat,
            onClearQueue = onClearQueue
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Now Playing section
            state.currentItem?.let { current ->
                item(key = "now_playing_header") {
                    Text(
                        text = stringResource(R.string.queue_now_playing),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                item(key = "now_playing_${current.queueItemId}") {
                    NowPlayingQueueItem(item = current)
                }

                // Divider between current and up next
                if (state.upNextItems.isNotEmpty()) {
                    item(key = "divider") {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    item(key = "up_next_header") {
                        Text(
                            text = stringResource(R.string.queue_up_next),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Up Next items with swipe to dismiss
            items(
                items = state.upNextItems,
                key = { it.queueItemId }
            ) { item ->
                SwipeToDismissQueueItem(
                    item = item,
                    onPlay = { onPlayItem(item.queueItemId) },
                    onRemove = { onRemoveItem(item.queueItemId) },
                    onMoveUp = { onMoveUp(item.queueItemId) },
                    onMoveDown = { onMoveDown(item.queueItemId) }
                )
            }
        }
    }
}

// ============================================================================
// Header
// ============================================================================

@Composable
private fun QueueHeader(
    itemCount: Int,
    shuffleEnabled: Boolean,
    repeatMode: String,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onClearQueue: () -> Unit
) {
    var showOverflowMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Title and count
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.queue_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (itemCount == 1) {
                    stringResource(R.string.queue_item_count_one)
                } else {
                    stringResource(R.string.queue_item_count, itemCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Shuffle toggle
        val shuffleColor by animateColorAsState(
            targetValue = if (shuffleEnabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            label = "shuffleColor"
        )
        IconButton(
            onClick = onToggleShuffle,
            modifier = Modifier.semantics {
                contentDescription = "Toggle shuffle"
            }
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_shuffle),
                contentDescription = stringResource(R.string.accessibility_shuffle_toggle),
                tint = shuffleColor,
                modifier = Modifier.size(24.dp)
            )
        }

        // Repeat toggle
        val repeatColor by animateColorAsState(
            targetValue = if (repeatMode != "off") {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            label = "repeatColor"
        )
        IconButton(
            onClick = onCycleRepeat,
            modifier = Modifier.semantics {
                contentDescription = "Cycle repeat mode"
            }
        ) {
            Icon(
                painter = painterResource(
                    when (repeatMode) {
                        "one" -> R.drawable.ic_repeat_one
                        "all" -> R.drawable.ic_repeat
                        else -> R.drawable.ic_repeat
                    }
                ),
                contentDescription = stringResource(R.string.accessibility_repeat_toggle),
                tint = repeatColor,
                modifier = Modifier.size(24.dp)
            )
        }

        // Overflow menu
        Box {
            IconButton(onClick = { showOverflowMenu = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = showOverflowMenu,
                onDismissRequest = { showOverflowMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.queue_clear)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                    },
                    onClick = {
                        showOverflowMenu = false
                        onClearQueue()
                    }
                )
            }
        }
    }
}

// ============================================================================
// Now Playing Item (highlighted)
// ============================================================================

@Composable
private fun NowPlayingQueueItem(item: MaQueueItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Playing indicator
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Album art
        AsyncImage(
            model = item.imageUri ?: R.drawable.placeholder_album,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Track info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = buildQueueItemSubtitle(item.artist, item.album)
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Duration
        item.duration?.let { seconds ->
            Text(
                text = formatDuration(seconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// Queue List Item (with swipe to dismiss)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissQueueItem(
    item: MaQueueItem,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onRemove()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // Red background for swipe-to-remove
            val color by animateColorAsState(
                targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                label = "dismissBg"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.accessibility_queue_remove),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true
    ) {
        QueueListItem(
            item = item,
            onPlay = onPlay,
            onRemove = onRemove,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown
        )
    }
}

@Composable
private fun QueueListItem(
    item: MaQueueItem,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Album art
        AsyncImage(
            model = item.imageUri ?: R.drawable.placeholder_album,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Track info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = buildQueueItemSubtitle(item.artist, item.album)
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Duration
        item.duration?.let { seconds ->
            Text(
                text = formatDuration(seconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp)
            )
        }

        // Overflow menu
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.play)) },
                    leadingIcon = {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        onPlay()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.queue_move_up)) },
                    leadingIcon = {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        onMoveUp()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.queue_move_down)) },
                    leadingIcon = {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        onMoveDown()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.accessibility_queue_remove)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        onRemove()
                    }
                )
            }
        }
    }
}

// ============================================================================
// Empty State
// ============================================================================

@Composable
private fun QueueEmptyContent(onBrowseLibrary: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_queue_music),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.queue_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.queue_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(onClick = onBrowseLibrary) {
            Text(stringResource(R.string.queue_browse_library))
        }
    }
}

// ============================================================================
// Loading State
// ============================================================================

@Composable
private fun QueueLoadingContent() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

// ============================================================================
// Error State
// ============================================================================

@Composable
private fun QueueErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.queue_load_failed),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(onClick = onRetry) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Retry")
        }
    }
}

// ============================================================================
// Utilities
// ============================================================================

/**
 * Build a subtitle string from artist and album.
 */
private fun buildQueueItemSubtitle(artist: String?, album: String?): String {
    return buildString {
        if (!artist.isNullOrEmpty()) append(artist)
        if (!album.isNullOrEmpty()) {
            if (isNotEmpty()) append(" \u2022 ") // bullet separator
            append(album)
        }
    }
}

/**
 * Format duration in seconds to MM:SS string.
 */
private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}
