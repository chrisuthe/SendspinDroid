package com.sendspindroid.ui.detail.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sendspindroid.R
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaMediaType
import kotlinx.coroutines.delay

/**
 * Bottom sheet for searching and adding tracks to a playlist.
 *
 * Provides a search field with debounced queries and a list of track results.
 * Each result has an "add" button that calls the API to add the track.
 *
 * @param playlistId The ID of the playlist to add tracks to
 * @param onDismiss Called when the sheet is dismissed
 * @param onTrackAdded Called after a track is successfully added (to refresh parent)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTracksBottomSheet(
    playlistId: String,
    onDismiss: () -> Unit,
    onTrackAdded: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MaTrack>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val addedTracks = remember { mutableStateMapOf<String, Boolean>() }

    // Debounced search
    LaunchedEffect(query) {
        error = null
        if (query.length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        isSearching = true
        delay(300)
        MusicAssistantManager.search(
            query = query,
            mediaTypes = listOf(MaMediaType.TRACK),
            limit = 25
        ).fold(
            onSuccess = {
                results = it.tracks
                isSearching = false
            },
            onFailure = {
                error = it.message
                isSearching = false
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
        ) {
            // Title
            Text(
                text = stringResource(R.string.add_tracks),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Search field
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search_tracks_hint)) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Results
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .height(400.dp)
            ) {
                when {
                    isSearching -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    error != null -> {
                        Text(
                            text = error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp)
                        )
                    }

                    query.length >= 2 && results.isEmpty() && !isSearching -> {
                        Text(
                            text = stringResource(R.string.no_tracks_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp)
                        )
                    }

                    results.isNotEmpty() -> {
                        LazyColumn {
                            items(
                                items = results,
                                key = { it.itemId }
                            ) { track ->
                                AddTrackItem(
                                    track = track,
                                    isAdded = addedTracks[track.itemId] == true,
                                    onAdd = {
                                        if (track.uri != null) {
                                            addedTracks[track.itemId] = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Handle track additions
    addedTracks.forEach { (trackId, added) ->
        if (added) {
            val track = results.find { it.itemId == trackId }
            if (track != null) {
                LaunchedEffect(trackId) {
                    val uri = track.uri ?: return@LaunchedEffect
                    MusicAssistantManager.addPlaylistTracks(playlistId, listOf(uri)).fold(
                        onSuccess = { onTrackAdded() },
                        onFailure = { /* revert icon if needed */ }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddTrackItem(
    track: MaTrack,
    isAdded: Boolean,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        AsyncImage(
            model = track.imageUri ?: R.drawable.placeholder_album,
            contentDescription = track.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Track info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!track.artist.isNullOrEmpty()) {
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Add button (becomes checkmark after adding)
        IconButton(
            onClick = onAdd,
            enabled = !isAdded
        ) {
            Icon(
                imageVector = if (isAdded) Icons.Filled.Check else Icons.Filled.Add,
                contentDescription = if (isAdded) "Added" else "Add to playlist",
                tint = if (isAdded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
