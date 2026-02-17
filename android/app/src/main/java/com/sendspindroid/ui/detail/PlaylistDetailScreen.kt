package com.sendspindroid.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sendspindroid.R
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.ui.detail.components.ActionRow
import com.sendspindroid.ui.detail.components.AddTracksBottomSheet
import com.sendspindroid.ui.detail.components.HeroHeader
import com.sendspindroid.ui.detail.components.TrackListItem
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Playlist Detail screen displaying playlist info and track listing.
 * Back navigation is handled by the Activity toolbar.
 *
 * Track removal is handled internally via the ViewModel's removeTrack().
 * The [onTrackRemoved] callback reports the [PlaylistDetailViewModel.RemoveAction]
 * so the parent can show an undo snackbar.
 *
 * @param playlistId The MA playlist item_id to display
 * @param onTrackRemoved Called with the RemoveAction after a track is optimistically removed,
 *        allowing the parent to show undo UI. Defaults to auto-executing the removal immediately.
 * @param viewModel The ViewModel managing playlist state
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onTrackRemoved: (PlaylistDetailViewModel.RemoveAction) -> Unit = { it.executeRemove() },
    viewModel: PlaylistDetailViewModel = viewModel()
) {
    LaunchedEffect(playlistId) {
        viewModel.loadPlaylist(playlistId)
    }

    val uiState by viewModel.uiState.collectAsState()
    var showAddTracks by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is PlaylistDetailUiState.Loading -> {
                LoadingContent()
            }

            is PlaylistDetailUiState.Error -> {
                ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.refresh() }
                )
            }

            is PlaylistDetailUiState.Success -> {
                PlaylistDetailContent(
                    state = state,
                    onTrackClick = { viewModel.playTrack(it) },
                    onShuffle = { viewModel.shuffleAll() },
                    onAddTracks = { showAddTracks = true },
                    onRemoveTrack = { position, _ ->
                        val action = viewModel.removeTrack(position)
                        if (action != null) {
                            onTrackRemoved(action)
                        }
                    }
                )
            }
        }
    }

    if (showAddTracks) {
        AddTracksBottomSheet(
            playlistId = playlistId,
            onDismiss = { showAddTracks = false },
            onTrackAdded = { viewModel.refresh() }
        )
    }
}

@Composable
private fun PlaylistDetailContent(
    state: PlaylistDetailUiState.Success,
    onTrackClick: (MaTrack) -> Unit,
    onShuffle: () -> Unit,
    onAddTracks: () -> Unit,
    onRemoveTrack: (position: Int, trackName: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize()
    ) {
        // Hero header with playlist artwork and metadata
        item {
            PlaylistHeroHeader(
                playlist = state.playlist,
                totalDuration = state.totalDuration,
                trackCount = state.tracks.size
            )
        }

        // Action row - "Add Tracks" instead of "Add to Queue"
        item {
            ActionRow(
                onShuffle = onShuffle,
                onAddToPlaylist = onAddTracks,
                secondButtonLabel = stringResource(R.string.add_tracks),
                secondButtonIcon = Icons.Filled.Add
            )
        }

        // Divider
        item {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        if (state.tracks.isEmpty()) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                ) {
                    Text(
                        text = stringResource(R.string.playlist_tracks_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = onAddTracks) {
                        Text(stringResource(R.string.add_tracks))
                    }
                }
            }
        } else {
            // Track listing with remove option
            itemsIndexed(
                items = state.tracks,
                key = { index, track -> "${track.itemId}_$index" }
            ) { index, track ->
                TrackListItem(
                    track = track,
                    trackNumber = index + 1,
                    showArtist = true,
                    onClick = { onTrackClick(track) },
                    onRemoveFromPlaylist = {
                        onRemoveTrack(index, track.name ?: "Track")
                    }
                )
            }
        }

        // Bottom spacing for mini player
        item {
            Spacer(modifier = Modifier.height(88.dp))
        }
    }
}

/**
 * Playlist hero header with metadata.
 */
@Composable
private fun PlaylistHeroHeader(
    playlist: MaPlaylist,
    totalDuration: Long,
    trackCount: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeroHeader(
            title = playlist.name,
            subtitle = buildPlaylistSubtitle(
                owner = playlist.owner,
                trackCount = trackCount,
                totalDuration = totalDuration
            ),
            imageUri = playlist.imageUri,
            placeholderIcon = Icons.Filled.Add
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Build subtitle for the playlist hero header.
 */
private fun buildPlaylistSubtitle(
    owner: String?,
    trackCount: Int?,
    totalDuration: Long? = null
): String {
    val parts = mutableListOf<String>()

    if (!owner.isNullOrEmpty()) {
        parts.add(owner)
    }
    if (trackCount != null && trackCount > 0) {
        val trackText = if (trackCount == 1) "1 track" else "$trackCount tracks"
        parts.add(trackText)
    }
    if (totalDuration != null && totalDuration > 0) {
        val minutes = totalDuration / 60
        parts.add("$minutes min")
    }

    return parts.joinToString(" - ")
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
private fun PlaylistDetailScreenPreview() {
    SendSpinTheme {
        PlaylistDetailContent(
            state = PlaylistDetailUiState.Success(
                playlist = MaPlaylist(
                    playlistId = "1",
                    name = "My Playlist",
                    imageUri = null,
                    trackCount = 5,
                    owner = "admin",
                    uri = "library://playlist/1"
                ),
                tracks = listOf(
                    MaTrack("1", "First Track", "Artist One", "Album A", null, null, 225),
                    MaTrack("2", "Second Track", "Artist Two", "Album B", null, null, 312),
                    MaTrack("3", "Third Track", "Artist One", "Album A", null, null, 198),
                    MaTrack("4", "Fourth Track", "Artist Three", "Album C", null, null, 285),
                    MaTrack("5", "Fifth Track", "Artist One", "Album A", null, null, 420)
                )
            ),
            onTrackClick = {},
            onShuffle = {},
            onAddTracks = {},
            onRemoveTrack = { _, _ -> }
        )
    }
}
