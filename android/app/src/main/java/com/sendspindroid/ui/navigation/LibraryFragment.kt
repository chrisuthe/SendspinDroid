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
import com.sendspindroid.R
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.ui.detail.AlbumDetailFragment
import com.sendspindroid.ui.detail.ArtistDetailFragment
import com.sendspindroid.ui.detail.components.BulkAddState
import com.sendspindroid.ui.detail.components.PlaylistPickerDialog
import com.sendspindroid.ui.navigation.library.LibraryScreen
import com.sendspindroid.ui.navigation.library.LibraryViewModel
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.launch

/**
 * Library tab fragment displaying tabbed content browser.
 *
 * Provides full library browsing with:
 * - Tabs (Albums, Artists, Playlists, Tracks, Radio)
 * - HorizontalPager for swipeable content
 * - Shared LibraryViewModel for state management across tabs
 *
 * Each tab displays a vertical scrolling list with:
 * - Sort options (where applicable)
 * - Pull-to-refresh
 * - Infinite scroll pagination
 *
 * Uses Jetpack Compose for UI rendering via ComposeView.
 */
class LibraryFragment : Fragment() {

    private val viewModel: LibraryViewModel by viewModels()

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

                    LibraryScreen(
                        viewModel = viewModel,
                        onAlbumClick = { album -> navigateToAlbumDetail(album) },
                        onArtistClick = { artist -> navigateToArtistDetail(artist) },
                        onItemClick = { item -> playItem(item) },
                        onAddToPlaylist = { item ->
                            itemForPlaylist = item
                            bulkAddState = null
                            selectedPlaylist = null
                        },
                        onAddToQueue = { item -> addToQueue(item) },
                        onPlayNext = { item -> playNext(item) }
                    )

                    // Playlist picker dialog
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
                                        itemForPlaylist = null
                                        scope.launch {
                                            addTrackToPlaylist(item, playlist)
                                        }
                                    }
                                    is MaAlbum -> {
                                        selectedPlaylist = playlist
                                        scope.launch {
                                            bulkAddAlbum(
                                                item.albumId,
                                                item.name,
                                                playlist
                                            ) { bulkAddState = it }
                                        }
                                    }
                                    is MaArtist -> {
                                        selectedPlaylist = playlist
                                        scope.launch {
                                            bulkAddArtist(
                                                item.artistId,
                                                item.name,
                                                playlist
                                            ) { bulkAddState = it }
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
                                    val item = itemForPlaylist
                                    scope.launch {
                                        when (item) {
                                            is MaAlbum -> bulkAddAlbum(
                                                item.albumId,
                                                item.name,
                                                playlist
                                            ) { bulkAddState = it }
                                            is MaArtist -> bulkAddArtist(
                                                item.artistId,
                                                item.name,
                                                playlist
                                            ) { bulkAddState = it }
                                            else -> {}
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * Navigate to artist detail screen.
     */
    private fun navigateToArtistDetail(artist: MaArtist) {
        Log.d(TAG, "Navigating to artist detail: ${artist.name}")
        val fragment = ArtistDetailFragment.newInstance(
            artistId = artist.artistId,
            artistName = artist.name
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.navFragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    /**
     * Navigate to album detail screen.
     */
    private fun navigateToAlbumDetail(album: MaAlbum) {
        Log.d(TAG, "Navigating to album detail: ${album.name}")
        val fragment = AlbumDetailFragment.newInstance(
            albumId = album.albumId,
            albumName = album.name
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.navFragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    /**
     * Add an item to the play queue.
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

    /**
     * Insert an item to play next in the queue (after the current track).
     */
    private fun playNext(item: MaLibraryItem) {
        val uri = item.uri
        if (uri.isNullOrBlank()) {
            Log.w(TAG, "Item ${item.name} has no URI, cannot play next")
            Toast.makeText(context, "Cannot play next: no URI available", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Play next: ${item.name} (uri=$uri)")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = MusicAssistantManager.playMedia(
                uri,
                item.mediaType.name.lowercase(),
                enqueueMode = MusicAssistantManager.EnqueueMode.NEXT
            )
            result.fold(
                onSuccess = {
                    Log.d(TAG, "Playing next: ${item.name}")
                    (activity as? MainActivity)?.showSuccessSnackbar(
                        "Playing next: ${item.name}"
                    )
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to play next: ${item.name}", error)
                    Toast.makeText(
                        context,
                        "Failed to play next: ${error.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    /**
     * Play an item immediately.
     *
     * Used for tracks, playlists, and radio stations.
     */
    private fun playItem(item: MaLibraryItem) {
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

    /**
     * Add a single track to a playlist.
     */
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
     * Bulk add all album tracks to a playlist.
     */
    private suspend fun bulkAddAlbum(
        albumId: String,
        albumName: String,
        playlist: MaPlaylist,
        onStateChange: (BulkAddState) -> Unit
    ) {
        onStateChange(BulkAddState.Loading("Fetching tracks..."))

        val tracksResult = MusicAssistantManager.getAlbumTracks(albumId)
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
                        val message = "Added $albumName to ${playlist.name}"
                        Log.d(TAG, message)
                        onStateChange(BulkAddState.Success(message))
                        (activity as? MainActivity)?.showSuccessSnackbar(message)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to add album to playlist", error)
                        onStateChange(BulkAddState.Error("Failed to add to playlist"))
                    }
                )
            },
            onFailure = { error ->
                Log.e(TAG, "Failed to fetch album tracks", error)
                onStateChange(BulkAddState.Error("Failed to fetch tracks"))
            }
        )
    }

    /**
     * Bulk add all artist tracks to a playlist.
     */
    private suspend fun bulkAddArtist(
        artistId: String,
        artistName: String,
        playlist: MaPlaylist,
        onStateChange: (BulkAddState) -> Unit
    ) {
        onStateChange(BulkAddState.Loading("Fetching tracks..."))

        val tracksResult = MusicAssistantManager.getArtistTracks(artistId)
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
                        val message = "Added $artistName to ${playlist.name}"
                        Log.d(TAG, message)
                        onStateChange(BulkAddState.Success(message))
                        (activity as? MainActivity)?.showSuccessSnackbar(message)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to add artist to playlist", error)
                        onStateChange(BulkAddState.Error("Failed to add to playlist"))
                    }
                )
            },
            onFailure = { error ->
                Log.e(TAG, "Failed to fetch artist tracks", error)
                onStateChange(BulkAddState.Error("Failed to fetch tracks"))
            }
        )
    }

    companion object {
        private const val TAG = "LibraryFragment"

        fun newInstance() = LibraryFragment()
    }
}
