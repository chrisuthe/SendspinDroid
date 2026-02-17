package com.sendspindroid.ui.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.ui.detail.components.BulkAddState
import kotlinx.coroutines.delay

/**
 * Steps in the Save Queue as Playlist dialog flow.
 */
private enum class DialogStep {
    PICKER,
    CONFIRM_OVERWRITE,
    CREATE_NEW_INPUT,
    OPERATION
}

/**
 * Whether the operation is an append or a full replacement.
 */
private enum class SaveMode {
    APPEND, OVERWRITE, CREATE_NEW
}

/**
 * Unified dialog for saving the current queue as a playlist.
 *
 * Shows a playlist picker with inline Add/Replace actions per row,
 * plus a "Create new playlist" option at the top. Handles the full
 * flow including overwrite confirmation, new playlist name input,
 * and operation progress/success/error states.
 *
 * @param trackUris URIs of queue tracks to save (pre-filtered for non-null)
 * @param onDismiss Called when the dialog is dismissed
 * @param onSuccess Called with a success message when the operation completes
 */
@Composable
fun SaveQueueAsPlaylistDialog(
    trackUris: List<String>,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    var step by remember { mutableStateOf(DialogStep.PICKER) }
    var saveMode by remember { mutableStateOf(SaveMode.APPEND) }
    var selectedPlaylist by remember { mutableStateOf<MaPlaylist?>(null) }
    var existingTrackCount by remember { mutableIntStateOf(0) }
    var operationState by remember { mutableStateOf<BulkAddState?>(null) }
    var newPlaylistName by remember { mutableStateOf("") }
    var retryCount by remember { mutableIntStateOf(0) }

    // Playlist loading state
    var playlists by remember { mutableStateOf<List<MaPlaylist>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadRetryCount by remember { mutableIntStateOf(0) }

    // Load playlists (re-triggers on retry)
    LaunchedEffect(loadRetryCount) {
        playlists = null
        loadError = null
        MusicAssistantManager.getPlaylists().fold(
            onSuccess = { playlists = it },
            onFailure = { loadError = it.message ?: "" }
        )
    }

    // Auto-dismiss on success
    if (operationState is BulkAddState.Success) {
        LaunchedEffect(operationState) {
            delay(1500)
            onSuccess((operationState as BulkAddState.Success).message)
            onDismiss()
        }
    }

    when (step) {
        DialogStep.PICKER -> PickerDialog(
            playlists = playlists,
            loadError = loadError,
            trackCount = trackUris.size,
            onRetryLoad = { loadRetryCount++ },
            onCreateNew = {
                newPlaylistName = ""
                step = DialogStep.CREATE_NEW_INPUT
            },
            onAppend = { playlist ->
                selectedPlaylist = playlist
                saveMode = SaveMode.APPEND
                operationState = null
                step = DialogStep.OPERATION
            },
            onReplace = { playlist ->
                selectedPlaylist = playlist
                existingTrackCount = playlist.trackCount
                saveMode = SaveMode.OVERWRITE
                step = DialogStep.CONFIRM_OVERWRITE
            },
            onDismiss = onDismiss
        )

        DialogStep.CONFIRM_OVERWRITE -> ConfirmOverwriteDialog(
            playlistName = selectedPlaylist?.name ?: "",
            existingTrackCount = existingTrackCount,
            queueTrackCount = trackUris.size,
            onConfirm = {
                operationState = null
                step = DialogStep.OPERATION
            },
            onDismiss = { step = DialogStep.PICKER }
        )

        DialogStep.CREATE_NEW_INPUT -> CreateNewDialog(
            name = newPlaylistName,
            onNameChange = { newPlaylistName = it },
            onConfirm = {
                saveMode = SaveMode.CREATE_NEW
                operationState = null
                step = DialogStep.OPERATION
            },
            onDismiss = { step = DialogStep.PICKER }
        )

        DialogStep.OPERATION -> {
            // Launch the operation (retryCount as key allows re-triggering)
            LaunchedEffect(saveMode, selectedPlaylist, newPlaylistName, retryCount) {
                when (saveMode) {
                    SaveMode.APPEND -> executeAppend(
                        playlist = selectedPlaylist!!,
                        trackUris = trackUris,
                        onStateChange = { operationState = it }
                    )
                    SaveMode.OVERWRITE -> executeOverwrite(
                        playlist = selectedPlaylist!!,
                        trackUris = trackUris,
                        onStateChange = { operationState = it }
                    )
                    SaveMode.CREATE_NEW -> executeCreateNew(
                        playlistName = newPlaylistName,
                        trackUris = trackUris,
                        onStateChange = { operationState = it }
                    )
                }
            }

            OperationDialog(
                operationState = operationState,
                onRetry = {
                    operationState = null
                    retryCount++
                },
                onDismiss = onDismiss
            )
        }
    }
}

// ============================================================================
// Picker Dialog (main view)
// ============================================================================

@Composable
private fun PickerDialog(
    playlists: List<MaPlaylist>?,
    loadError: String?,
    trackCount: Int,
    onRetryLoad: () -> Unit,
    onCreateNew: () -> Unit,
    onAppend: (MaPlaylist) -> Unit,
    onReplace: (MaPlaylist) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.queue_save_as_playlist)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 400.dp)
            ) {
                when {
                    loadError != null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = loadError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = onRetryLoad) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }

                    playlists == null -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    else -> {
                        LazyColumn {
                            // "Create new playlist" at top
                            item(key = "create_new") {
                                CreateNewPlaylistRow(onClick = onCreateNew)
                                if (playlists.isNotEmpty()) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }

                            // Existing playlists
                            items(
                                items = playlists,
                                key = { it.playlistId }
                            ) { playlist ->
                                PlaylistActionRow(
                                    playlist = playlist,
                                    onAppend = { onAppend(playlist) },
                                    onReplace = { onReplace(playlist) }
                                )
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun CreateNewPlaylistRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.queue_save_create_new),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PlaylistActionRow(
    playlist: MaPlaylist,
    onAppend: () -> Unit,
    onReplace: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Playlist info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (playlist.trackCount > 0) {
                Text(
                    text = if (playlist.trackCount == 1) stringResource(R.string.track_count_one) else stringResource(R.string.track_count_other, playlist.trackCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Add button (tonal)
        FilledTonalIconButton(
            onClick = onAppend,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.accessibility_add_to_playlist),
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Replace button (error-tinted)
        IconButton(
            onClick = onReplace,
            modifier = Modifier.size(36.dp),
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.accessibility_replace_playlist),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ============================================================================
// Confirm Overwrite Dialog
// ============================================================================

@Composable
private fun ConfirmOverwriteDialog(
    playlistName: String,
    existingTrackCount: Int,
    queueTrackCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.queue_save_confirm_replace_title)) },
        text = {
            Text(
                stringResource(
                    R.string.queue_save_confirm_replace_message,
                    existingTrackCount,
                    playlistName,
                    queueTrackCount
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.queue_save_replace_button),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

// ============================================================================
// Create New Playlist Dialog
// ============================================================================

@Composable
private fun CreateNewDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.queue_save_create_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.playlist_create_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

// ============================================================================
// Operation Dialog (Loading / Success / Error)
// ============================================================================

@Composable
private fun OperationDialog(
    operationState: BulkAddState?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            // Don't dismiss during loading
            if (operationState !is BulkAddState.Loading) {
                onDismiss()
            }
        },
        title = { Text(stringResource(R.string.queue_save_as_playlist)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp)
            ) {
                when (operationState) {
                    is BulkAddState.Loading -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = operationState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    is BulkAddState.Success -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = operationState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    is BulkAddState.Error -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = operationState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(onClick = onDismiss) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                                TextButton(onClick = onRetry) {
                                    Text(stringResource(R.string.action_retry))
                                }
                            }
                        }
                    }

                    null -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (operationState !is BulkAddState.Loading && operationState !is BulkAddState.Success) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

// ============================================================================
// Operations
// ============================================================================

/**
 * Append queue tracks to an existing playlist.
 */
private suspend fun executeAppend(
    playlist: MaPlaylist,
    trackUris: List<String>,
    onStateChange: (BulkAddState) -> Unit
) {
    onStateChange(BulkAddState.Loading("Adding ${trackUris.size} tracks\u2026"))

    MusicAssistantManager.addPlaylistTracks(playlist.playlistId, trackUris).fold(
        onSuccess = {
            onStateChange(BulkAddState.Success("Saved ${trackUris.size} tracks to ${playlist.name}"))
        },
        onFailure = {
            onStateChange(BulkAddState.Error("Failed to add tracks to playlist"))
        }
    )
}

/**
 * Replace all tracks in an existing playlist with queue tracks.
 */
private suspend fun executeOverwrite(
    playlist: MaPlaylist,
    trackUris: List<String>,
    onStateChange: (BulkAddState) -> Unit
) {
    // 1. Get existing tracks to know how many to remove
    onStateChange(BulkAddState.Loading("Loading existing tracks\u2026"))

    val existingTracks = MusicAssistantManager.getPlaylistTracks(playlist.playlistId).getOrElse {
        onStateChange(BulkAddState.Error("Failed to load existing tracks"))
        return
    }

    // 2. Remove all existing tracks (1-based positions)
    if (existingTracks.isNotEmpty()) {
        onStateChange(BulkAddState.Loading("Removing existing tracks\u2026"))
        val positions = (1..existingTracks.size).toList()
        MusicAssistantManager.removePlaylistTracks(playlist.playlistId, positions).getOrElse {
            onStateChange(BulkAddState.Error("Failed to remove existing tracks"))
            return
        }
    }

    // 3. Add queue tracks
    onStateChange(BulkAddState.Loading("Adding ${trackUris.size} tracks\u2026"))

    MusicAssistantManager.addPlaylistTracks(playlist.playlistId, trackUris).fold(
        onSuccess = {
            onStateChange(BulkAddState.Success("Saved ${trackUris.size} tracks to ${playlist.name}"))
        },
        onFailure = {
            onStateChange(BulkAddState.Error("Failed to add tracks to playlist"))
        }
    )
}

/**
 * Create a new playlist and add queue tracks to it.
 */
private suspend fun executeCreateNew(
    playlistName: String,
    trackUris: List<String>,
    onStateChange: (BulkAddState) -> Unit
) {
    // 1. Create the playlist
    onStateChange(BulkAddState.Loading("Creating playlist\u2026"))

    val newPlaylist = MusicAssistantManager.createPlaylist(playlistName).getOrElse {
        onStateChange(BulkAddState.Error("Failed to create playlist"))
        return
    }

    // 2. Add tracks
    onStateChange(BulkAddState.Loading("Adding ${trackUris.size} tracks\u2026"))

    MusicAssistantManager.addPlaylistTracks(newPlaylist.playlistId, trackUris).fold(
        onSuccess = {
            onStateChange(BulkAddState.Success("Saved ${trackUris.size} tracks to $playlistName"))
        },
        onFailure = {
            onStateChange(BulkAddState.Error("Failed to add tracks to playlist"))
        }
    )
}
