package com.sendspindroid.ui.detail.components

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.delay

/**
 * State for bulk add-to-playlist operations (albums/artists).
 *
 * When non-null, the dialog shows this state instead of the playlist list.
 */
sealed class BulkAddState {
    data class Loading(val message: String) : BulkAddState()
    data class Success(val message: String) : BulkAddState()
    data class Error(val message: String) : BulkAddState()
}

/**
 * Dialog for picking a playlist to add items to.
 *
 * Loads playlists from the server and displays them in a scrollable list.
 * Tapping a playlist calls [onPlaylistSelected] with the chosen playlist.
 *
 * When [operationState] is non-null, the dialog shows loading/success/error
 * instead of the playlist list. This is used for bulk operations (adding
 * albums or artists) where the fetch+add takes time.
 *
 * @param onDismiss Called when the dialog is dismissed
 * @param onPlaylistSelected Called with the selected playlist
 * @param operationState Optional bulk operation state (null = show playlist picker)
 * @param onRetry Called when the user taps Retry on an error state
 */
@Composable
fun PlaylistPickerDialog(
    onDismiss: () -> Unit,
    onPlaylistSelected: (MaPlaylist) -> Unit,
    operationState: BulkAddState? = null,
    onRetry: () -> Unit = {}
) {
    var playlists by remember { mutableStateOf<List<MaPlaylist>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Load playlists only when showing the picker (no operation state)
    LaunchedEffect(operationState) {
        if (operationState == null && playlists == null && error == null) {
            MusicAssistantManager.getPlaylists().fold(
                onSuccess = { playlists = it },
                onFailure = { error = it.message ?: "Failed to load playlists" }
            )
        }
    }

    // Auto-dismiss on success after delay
    if (operationState is BulkAddState.Success) {
        LaunchedEffect(operationState) {
            delay(800)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = {
            // Don't allow dismissal during loading
            if (operationState !is BulkAddState.Loading) {
                onDismiss()
            }
        },
        title = { Text(stringResource(R.string.add_to_playlist)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 400.dp)
            ) {
                when {
                    // Bulk operation states take priority
                    operationState is BulkAddState.Loading -> {
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

                    operationState is BulkAddState.Success -> {
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

                    operationState is BulkAddState.Error -> {
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
                                    Text("Cancel")
                                }
                                TextButton(onClick = onRetry) {
                                    Text("Retry")
                                }
                            }
                        }
                    }

                    // Original playlist picker states
                    error != null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = error ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = {
                                error = null
                                playlists = null
                            }) {
                                Text("Retry")
                            }
                        }
                    }

                    playlists == null -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    playlists!!.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.playlist_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    else -> {
                        LazyColumn {
                            items(
                                items = playlists!!,
                                key = { it.playlistId }
                            ) { playlist ->
                                PlaylistPickerItem(
                                    playlist = playlist,
                                    onClick = { onPlaylistSelected(playlist) }
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
            // Hide cancel button during loading and success states
            if (operationState !is BulkAddState.Loading && operationState !is BulkAddState.Success) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun PlaylistPickerItem(
    playlist: MaPlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
                    text = if (playlist.trackCount == 1) "1 track" else "${playlist.trackCount} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
