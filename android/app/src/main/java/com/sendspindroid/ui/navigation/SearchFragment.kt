package com.sendspindroid.ui.navigation

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.sendspindroid.MainActivity
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.ui.detail.components.BulkAddState
import com.sendspindroid.ui.detail.components.PlaylistPickerDialog
import com.sendspindroid.ui.navigation.search.SearchScreen
import com.sendspindroid.ui.navigation.search.SearchViewModel
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.launch

/**
 * Search tab fragment providing full-text search of Music Assistant library.
 *
 * Features:
 * - Material SearchView with debounced input (300ms delay)
 * - Filter chips for media type selection
 * - Grouped results with section headers
 * - Empty state, no results state, and error state
 *
 * Uses Jetpack Compose for UI rendering via ComposeView.
 * The ViewModel manages search state with unidirectional data flow.
 */
class SearchFragment : Fragment() {

    companion object {
        private const val TAG = "SearchFragment"

        fun newInstance() = SearchFragment()
    }

    private val viewModel: SearchViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            // Dispose composition when fragment's view is destroyed
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            setContent {
                SendSpinTheme {
                    var itemForPlaylist by remember { mutableStateOf<MaLibraryItem?>(null) }
                    var bulkAddState by remember { mutableStateOf<BulkAddState?>(null) }
                    var selectedPlaylist by remember { mutableStateOf<MaPlaylist?>(null) }
                    val scope = rememberCoroutineScope()

                    SearchScreen(
                        viewModel = viewModel,
                        onItemClick = { item -> onItemClick(item) },
                        onAddToPlaylist = { item ->
                            itemForPlaylist = item
                            bulkAddState = null
                        },
                        onAddToQueue = { item -> addToQueue(item) }
                    )

                    itemForPlaylist?.let { item ->
                        PlaylistPickerDialog(
                            onDismiss = {
                                itemForPlaylist = null
                                bulkAddState = null
                                selectedPlaylist = null
                            },
                            onPlaylistSelected = { playlist ->
                                when (item) {
                                    is MaTrack -> {
                                        // Single track - dismiss immediately and add
                                        itemForPlaylist = null
                                        scope.launch {
                                            addTrackToPlaylist(item, playlist)
                                        }
                                    }
                                    is MaAlbum, is MaArtist -> {
                                        // Bulk add - show progress in dialog
                                        selectedPlaylist = playlist
                                        scope.launch {
                                            bulkAddItems(item, playlist,
                                                onStateChange = { bulkAddState = it },
                                                onComplete = {
                                                    // Will auto-dismiss via BulkAddState.Success
                                                }
                                            )
                                        }
                                    }
                                    else -> {
                                        itemForPlaylist = null
                                    }
                                }
                            },
                            operationState = bulkAddState,
                            onRetry = {
                                selectedPlaylist?.let { playlist ->
                                    scope.launch {
                                        bulkAddItems(item, playlist,
                                            onStateChange = { bulkAddState = it },
                                            onComplete = {}
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private suspend fun bulkAddItems(
        item: MaLibraryItem,
        playlist: MaPlaylist,
        onStateChange: (BulkAddState) -> Unit,
        onComplete: () -> Unit
    ) {
        onStateChange(BulkAddState.Loading("Fetching tracks..."))

        // Fetch tracks based on item type
        val tracksResult = when (item) {
            is MaAlbum -> MusicAssistantManager.getAlbumTracks(item.albumId)
            is MaArtist -> MusicAssistantManager.getArtistTracks(item.id)
            else -> return
        }

        tracksResult.fold(
            onSuccess = { tracks ->
                val uris = tracks.mapNotNull { it.uri }
                if (uris.isEmpty()) {
                    onStateChange(BulkAddState.Error("No tracks found"))
                    return
                }

                onStateChange(BulkAddState.Loading("Adding ${uris.size} tracks..."))

                val addResult = MusicAssistantManager.addPlaylistTracks(
                    playlist.playlistId,
                    uris
                )
                addResult.fold(
                    onSuccess = {
                        val message = when (item) {
                            is MaAlbum -> "Added ${item.name} to ${playlist.name}"
                            else -> "Added ${uris.size} tracks to ${playlist.name}"
                        }
                        Log.d(TAG, message)
                        onStateChange(BulkAddState.Success(message))
                        (activity as? MainActivity)?.showSuccessSnackbar(message)
                        onComplete()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to add to playlist", error)
                        onStateChange(BulkAddState.Error("Failed to add to playlist"))
                    }
                )
            },
            onFailure = { error ->
                Log.e(TAG, "Failed to fetch tracks", error)
                onStateChange(BulkAddState.Error("Failed to fetch tracks"))
            }
        )
    }

    private suspend fun addTrackToPlaylist(track: MaTrack, playlist: MaPlaylist) {
        val uri = track.uri ?: return
        Log.d(TAG, "Adding track '${track.name}' to playlist '${playlist.name}'")
        val result = MusicAssistantManager.addPlaylistTracks(
            playlist.playlistId,
            listOf(uri)
        )
        result.fold(
            onSuccess = {
                Log.d(TAG, "Track added to playlist")
                (activity as? MainActivity)?.showSuccessSnackbar(
                    "Added to ${playlist.name}"
                )
            },
            onFailure = { error ->
                Log.e(TAG, "Failed to add track to playlist", error)
                (activity as? MainActivity)?.showErrorSnackbar(
                    "Failed to add track"
                )
            }
        )
    }

    /**
     * Handle click on a search result item.
     */
    private fun addToQueue(item: MaLibraryItem) {
        val uri = item.uri
        if (uri.isNullOrBlank()) {
            Log.w(TAG, "Item ${item.name} has no URI, cannot add to queue")
            Toast.makeText(context, "Cannot add to queue: no URI available", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Adding to queue: ${item.name} (uri=$uri)")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = MusicAssistantManager.playMedia(
                uri,
                item.mediaType.name.lowercase(),
                enqueue = true
            )
            result.fold(
                onSuccess = {
                    Log.d(TAG, "Added to queue: ${item.name}")
                    (activity as? MainActivity)?.showSuccessSnackbar(
                        "Added to queue: ${item.name}"
                    )
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to add to queue: ${item.name}", error)
                    Toast.makeText(
                        context,
                        "Failed to add to queue: ${error.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    private fun onItemClick(item: MaLibraryItem) {
        val uri = item.uri
        if (uri.isNullOrBlank()) {
            Log.w(TAG, "Item ${item.name} has no URI, cannot play")
            Toast.makeText(context, "Cannot play: no URI available", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Playing ${item.mediaType}: ${item.name} (uri=$uri)")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = MusicAssistantManager.playMedia(uri, item.mediaType.name.lowercase())
            result.fold(
                onSuccess = {
                    Log.d(TAG, "Playback started: ${item.name}")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to play ${item.name}", error)
                    Toast.makeText(
                        context,
                        "Failed to play: ${error.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }
}
