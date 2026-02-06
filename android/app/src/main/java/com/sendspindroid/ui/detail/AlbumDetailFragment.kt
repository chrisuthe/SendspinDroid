package com.sendspindroid.ui.detail

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.sendspindroid.MainActivity
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.ui.detail.components.BulkAddState
import com.sendspindroid.ui.detail.components.PlaylistPickerDialog
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.launch

/**
 * Fragment host for the Album Detail Compose screen.
 *
 * Uses ComposeView to embed the Compose-based AlbumDetailScreen inside
 * the existing Fragment-based navigation system.
 *
 * ## Arguments
 * - `albumId`: String - Required. The MA album item_id to display.
 * - `albumName`: String - Optional. Album name for transition animations.
 *
 * ## Navigation
 * - Back navigation returns to the previous fragment
 * - Artist name taps navigate to ArtistDetailFragment (by searching for artist)
 */
class AlbumDetailFragment : Fragment() {

    private val albumId: String by lazy {
        requireArguments().getString(ARG_ALBUM_ID)
            ?: throw IllegalArgumentException("albumId is required")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            // Dispose composition when fragment view is destroyed
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )

            setContent {
                SendSpinTheme {
                    var trackForPlaylist by remember { mutableStateOf<MaTrack?>(null) }
                    var showAlbumPlaylistPicker by remember { mutableStateOf(false) }
                    var albumBulkAddState by remember { mutableStateOf<BulkAddState?>(null) }
                    var selectedPlaylist by remember { mutableStateOf<MaPlaylist?>(null) }
                    val scope = rememberCoroutineScope()

                    AlbumDetailScreen(
                        albumId = albumId,
                        onArtistClick = { artistName ->
                            // TODO: Get artistId from album data instead of search
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Artist: $artistName",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        onAddToPlaylist = { track ->
                            trackForPlaylist = track
                        },
                        onAddAlbumToPlaylist = {
                            showAlbumPlaylistPicker = true
                            albumBulkAddState = null
                        }
                    )

                    // Single track add dialog
                    trackForPlaylist?.let { track ->
                        PlaylistPickerDialog(
                            onDismiss = { trackForPlaylist = null },
                            onPlaylistSelected = { playlist ->
                                trackForPlaylist = null
                                scope.launch {
                                    addTrackToPlaylist(track, playlist)
                                }
                            }
                        )
                    }

                    // Album bulk add dialog
                    if (showAlbumPlaylistPicker) {
                        PlaylistPickerDialog(
                            onDismiss = {
                                showAlbumPlaylistPicker = false
                                albumBulkAddState = null
                                selectedPlaylist = null
                            },
                            onPlaylistSelected = { playlist ->
                                selectedPlaylist = playlist
                                scope.launch {
                                    bulkAddAlbum(albumId, playlist,
                                        onStateChange = { albumBulkAddState = it }
                                    )
                                }
                            },
                            operationState = albumBulkAddState,
                            onRetry = {
                                selectedPlaylist?.let { playlist ->
                                    scope.launch {
                                        bulkAddAlbum(albumId, playlist,
                                            onStateChange = { albumBulkAddState = it }
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

    override fun onResume() {
        super.onResume()
        // Update the Activity toolbar with album name and back button
        val albumName = arguments?.getString(ARG_ALBUM_NAME) ?: ""
        (activity as? MainActivity)?.updateToolbarForDetail(albumName)
    }

    private suspend fun bulkAddAlbum(
        albumId: String,
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
                        val albumName = arguments?.getString(ARG_ALBUM_NAME) ?: "album"
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

    companion object {
        private const val TAG = "AlbumDetailFragment"
        private const val ARG_ALBUM_ID = "albumId"
        private const val ARG_ALBUM_NAME = "albumName"

        /**
         * Create a new instance of AlbumDetailFragment.
         *
         * @param albumId The MA album item_id
         * @param albumName The album name (for transition animations, optional)
         */
        fun newInstance(albumId: String, albumName: String? = null): AlbumDetailFragment {
            return AlbumDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ALBUM_ID, albumId)
                    albumName?.let { putString(ARG_ALBUM_NAME, it) }
                }
            }
        }
    }
}
