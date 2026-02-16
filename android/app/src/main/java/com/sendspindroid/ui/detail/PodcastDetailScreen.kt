package com.sendspindroid.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sendspindroid.musicassistant.MaPodcastEpisode
import com.sendspindroid.ui.detail.components.ActionRow
import com.sendspindroid.ui.detail.components.HeroHeader

/**
 * Podcast detail screen showing podcast info and episode listing.
 *
 * Follows the same pattern as AlbumDetailScreen with HeroHeader,
 * action buttons, and a sortable list of episodes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(
    podcastId: String,
    podcastName: String = "",
    podcastImageUri: String? = null,
    podcastPublisher: String? = null,
    totalEpisodes: Int = 0,
    viewModel: PodcastDetailViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val pullToRefreshState = rememberPullToRefreshState()

    LaunchedEffect(podcastId) {
        viewModel.loadPodcast(podcastId)
    }

    // Update podcast info from navigation params
    LaunchedEffect(podcastName, podcastImageUri, podcastPublisher, totalEpisodes) {
        if (podcastName.isNotEmpty()) {
            viewModel.updatePodcastInfo(podcastName, podcastImageUri, podcastPublisher, totalEpisodes)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        PullToRefreshBox(
            isRefreshing = uiState is PodcastDetailUiState.Loading,
            onRefresh = { viewModel.refresh() },
            state = pullToRefreshState,
            modifier = Modifier.fillMaxSize()
        ) {
            when (val state = uiState) {
                is PodcastDetailUiState.Loading -> {
                    // Pull-to-refresh indicator handles loading state
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item { Spacer(modifier = Modifier.height(200.dp)) }
                    }
                }

                is PodcastDetailUiState.Error -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(100.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                is PodcastDetailUiState.Success -> {
                    PodcastDetailContent(
                        state = state,
                        onPlayAll = { viewModel.playAll() },
                        onAddToQueue = { viewModel.addToQueue() },
                        onSortChange = { viewModel.setSortOption(it) },
                        onEpisodeClick = { viewModel.playEpisode(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PodcastDetailContent(
    state: PodcastDetailUiState.Success,
    onPlayAll: () -> Unit,
    onAddToQueue: () -> Unit,
    onSortChange: (EpisodeSortOption) -> Unit,
    onEpisodeClick: (MaPodcastEpisode) -> Unit
) {
    val episodes = state.sortedEpisodes

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        // Hero header
        item {
            HeroHeader(
                title = state.podcast.name,
                subtitle = buildPodcastSubtitle(
                    publisher = state.podcast.publisher,
                    episodeCount = state.episodeCount,
                    totalEpisodeCount = state.totalEpisodeCount
                ),
                imageUri = state.podcast.imageUri,
                placeholderIcon = Icons.Filled.PlayArrow
            )
        }

        // Action row
        item {
            ActionRow(
                onShuffle = onPlayAll,
                onAddToPlaylist = onAddToQueue,
                secondButtonLabel = "Add to Queue",
                secondButtonIcon = Icons.Filled.Add
            )
        }

        // Sort chips
        item {
            EpisodeSortRow(
                currentSort = state.sortOption,
                onSortChange = onSortChange
            )
        }

        // Divider
        item {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }

        // Episodes
        itemsIndexed(
            items = episodes,
            key = { _, episode -> "episode_${episode.episodeId}" }
        ) { _, episode ->
            EpisodeListItem(
                episode = episode,
                onClick = { onEpisodeClick(episode) }
            )
        }

        // Empty state
        if (episodes.isEmpty()) {
            item {
                Text(
                    text = "No episodes found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun EpisodeSortRow(
    currentSort: EpisodeSortOption,
    onSortChange: (EpisodeSortOption) -> Unit
) {
    val sortOptions = listOf(
        EpisodeSortOption.POSITION_DESC to "Newest",
        EpisodeSortOption.POSITION to "Oldest",
        EpisodeSortOption.NAME to "Name",
        EpisodeSortOption.DURATION to "Duration"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sortOptions.forEach { (option, label) ->
            FilterChip(
                selected = currentSort == option,
                onClick = { onSortChange(option) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@Composable
private fun EpisodeListItem(
    episode: MaPodcastEpisode,
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
        // Episode number
        Text(
            text = episode.position.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(36.dp)
        )

        // Episode info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (episode.duration > 0) {
                Text(
                    text = formatEpisodeDuration(episode.duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Play status icon
        if (episode.fullyPlayed) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Played",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        } else if (episode.resumePositionMs > 0) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "In progress",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

private fun buildPodcastSubtitle(publisher: String?, episodeCount: Int, totalEpisodeCount: Int = 0): String {
    val parts = mutableListOf<String>()
    if (!publisher.isNullOrEmpty()) {
        parts.add(publisher)
    }
    if (totalEpisodeCount > episodeCount) {
        // Server returned fewer episodes than exist — show "X of Y episodes"
        parts.add("$episodeCount of $totalEpisodeCount episodes")
    } else if (episodeCount > 0) {
        parts.add(if (episodeCount == 1) "1 episode" else "$episodeCount episodes")
    }
    return parts.joinToString(" - ")
}

private fun formatEpisodeDuration(seconds: Long): String {
    val hours = seconds / 3600
    val mins = (seconds % 3600) / 60
    return if (hours > 0) {
        "${hours}h ${mins}m"
    } else {
        "${mins} min"
    }
}
