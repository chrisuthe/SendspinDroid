package com.sendspindroid.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sendspindroid.musicassistant.MaAudiobookChapter
import com.sendspindroid.ui.detail.components.ActionRow
import com.sendspindroid.ui.detail.components.HeroHeader

/**
 * Audiobook detail screen showing audiobook info and chapter listing.
 *
 * Follows the same pattern as PodcastDetailScreen with HeroHeader,
 * action buttons, and a sortable list of chapters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookDetailScreen(
    audiobookId: String,
    audiobookName: String = "",
    audiobookImageUri: String? = null,
    audiobookAuthor: String? = null,
    viewModel: AudiobookDetailViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pullToRefreshState = rememberPullToRefreshState()

    LaunchedEffect(audiobookId) {
        viewModel.loadAudiobook(audiobookId)
    }

    // Update audiobook info from navigation params
    LaunchedEffect(audiobookName, audiobookImageUri, audiobookAuthor) {
        if (audiobookName.isNotEmpty()) {
            viewModel.updateAudiobookInfo(audiobookName, audiobookImageUri, audiobookAuthor)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        PullToRefreshBox(
            isRefreshing = uiState is AudiobookDetailUiState.Loading,
            onRefresh = { viewModel.refresh() },
            state = pullToRefreshState,
            modifier = Modifier.fillMaxSize()
        ) {
            when (val state = uiState) {
                is AudiobookDetailUiState.Loading -> {
                    // Pull-to-refresh indicator handles loading state
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item { Spacer(modifier = Modifier.height(200.dp)) }
                    }
                }

                is AudiobookDetailUiState.Error -> {
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

                is AudiobookDetailUiState.Success -> {
                    AudiobookDetailContent(
                        state = state,
                        onPlay = { viewModel.playAudiobook() },
                        onAddToQueue = { viewModel.addToQueue() },
                        onSortChange = { viewModel.setSortOption(it) },
                        onMarkPlayed = { viewModel.markPlayed() },
                        onMarkUnplayed = { viewModel.markUnplayed() },
                        onChapterClick = { chapter -> viewModel.playChapter(chapter) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudiobookDetailContent(
    state: AudiobookDetailUiState.Success,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit,
    onSortChange: (ChapterSortOption) -> Unit,
    onMarkPlayed: () -> Unit,
    onMarkUnplayed: () -> Unit,
    onChapterClick: (MaAudiobookChapter) -> Unit
) {
    val audiobook = state.audiobook
    val chapters = state.sortedChapters

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        // Hero header
        item {
            HeroHeader(
                title = audiobook.name,
                subtitle = buildAudiobookSubtitle(audiobook),
                imageUri = audiobook.imageUri,
                placeholderIcon = Icons.Filled.PlayArrow
            )
        }

        // Narrator chips
        if (audiobook.narrators.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Narrated by",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        audiobook.narrators.forEach { narrator ->
                            AssistChip(
                                onClick = { },
                                label = { Text(narrator) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // Progress indicator
        val resumeMs = audiobook.resumePositionMs
        if (resumeMs != null && audiobook.duration > 0) {
            item {
                val totalMs = audiobook.duration * 1000
                val progress = resumeMs.toFloat() / totalMs.toFloat()
                val remainingSeconds = (totalMs - resumeMs) / 1000

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "${formatDuration(remainingSeconds)} remaining",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val progressClamped = progress.coerceIn(0f, 1f)
                    val progressPercent = (progressClamped * 100).toInt()
                    LinearProgressIndicator(
                        progress = { progressClamped },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .semantics {
                                contentDescription =
                                    "Audiobook progress: $progressPercent percent complete, ${formatDuration(remainingSeconds)} remaining"
                            },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Text(
                        text = "${formatDuration(resumeMs / 1000)} of ${formatDuration(audiobook.duration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        }

        // Action row with audiobook-appropriate labels
        item {
            val hasResume = resumeMs != null && resumeMs > 0
            ActionRow(
                onShuffle = onPlay,
                onAddToPlaylist = onAddToQueue,
                firstButtonLabel = if (hasResume) "Resume" else "Play",
                firstButtonIcon = Icons.Filled.PlayArrow,
                secondButtonLabel = "Add to Queue",
                secondButtonIcon = Icons.Filled.Add
            )
        }

        // Played status toggle
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (audiobook.fullyPlayed == true) {
                    FilterChip(
                        selected = true,
                        onClick = onMarkUnplayed,
                        label = { Text("Played") },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Audiobook marked as played. Tap to mark as unplayed."
                        }
                    )
                } else {
                    FilterChip(
                        selected = false,
                        onClick = onMarkPlayed,
                        label = { Text("Mark as Played") },
                        modifier = Modifier.semantics {
                            contentDescription = "Mark audiobook as fully played."
                        }
                    )
                }
            }
        }

        // Chapter sort chips (only if there are chapters)
        if (chapters.isNotEmpty()) {
            item {
                ChapterSortRow(
                    currentSort = state.sortOption,
                    onSortChange = onSortChange
                )
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            // Chapters
            itemsIndexed(
                items = chapters,
                key = { index, _ -> "chapter_$index" }
            ) { _, chapter ->
                ChapterListItem(
                    chapter = chapter,
                    isCurrentChapter = chapter.position == state.currentChapterPosition,
                    onClick = { onChapterClick(chapter) }
                )
            }
        }

        // Empty chapters message
        if (chapters.isEmpty() && audiobook.duration > 0) {
            item {
                Text(
                    text = "No chapter information available",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ChapterSortRow(
    currentSort: ChapterSortOption,
    onSortChange: (ChapterSortOption) -> Unit
) {
    val sortOptions = listOf(
        ChapterSortOption.POSITION to "Chapter Order",
        ChapterSortOption.POSITION_DESC to "Reverse Order",
        ChapterSortOption.NAME to "A\u2013Z",
        ChapterSortOption.DURATION to "Shortest First"
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
private fun ChapterListItem(
    chapter: MaAudiobookChapter,
    isCurrentChapter: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick,
                onClickLabel = "Play chapter ${chapter.name}"
            )
            .background(
                if (isCurrentChapter)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else
                    Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Chapter number
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = chapter.position.toString(),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isCurrentChapter) FontWeight.Bold else FontWeight.Normal
                ),
                color = if (isCurrentChapter)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }

        // Chapter info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isCurrentChapter) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isCurrentChapter)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (chapter.duration > 0.0) {
                Text(
                    text = formatDuration(chapter.duration.toLong()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Now playing indicator
        if (isCurrentChapter) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Currently playing",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun buildAudiobookSubtitle(audiobook: com.sendspindroid.musicassistant.MaAudiobook): String {
    val parts = mutableListOf<String>()
    audiobook.primaryAuthor?.let { parts.add(it) }
    if (audiobook.chapters.isNotEmpty()) {
        val count = audiobook.chapters.size
        parts.add(if (count == 1) "1 chapter" else "$count chapters")
    }
    if (audiobook.duration > 0) {
        parts.add(formatDuration(audiobook.duration))
    }
    return parts.joinToString(" \u2022 ")
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val mins = (seconds % 3600) / 60
    return if (hours > 0) {
        "${hours}h ${mins}m"
    } else {
        "${mins} min"
    }
}
