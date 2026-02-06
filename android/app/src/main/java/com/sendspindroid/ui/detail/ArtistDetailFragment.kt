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
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.ui.detail.components.BulkAddState
import com.sendspindroid.ui.detail.components.PlaylistPickerDialog
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.launch

/**
 * Fragment host for the Artist Detail Compose screen.
 *
 * Uses ComposeView to embed the Compose-based ArtistDetailScreen inside
 * the existing Fragment-based navigation system. This allows incremental
 * migration to Compose while reusing existing navigation patterns.
 *
 * ## Arguments
 * - `artistId`: String - Required. The MA artist item_id to display.
 *
 * ## Navigation
 * - Back navigation returns to the previous fragment
 * - Album taps navigate to AlbumDetailFragment
 */
class ArtistDetailFragment : Fragment() {

    private val artistId: String by lazy {
        requireArguments().getString(ARG_ARTIST_ID)
            ?: throw IllegalArgumentException("artistId is required")
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
                    var showArtistPlaylistPicker by remember { mutableStateOf(false) }
                    var albumForPlaylist by remember { mutableStateOf<MaAlbum?>(null) }
                    var bulkAddState by remember { mutableStateOf<BulkAddState?>(null) }
                    var selectedPlaylist by remember { mutableStateOf<MaPlaylist?>(null) }
                    val scope = rememberCoroutineScope()

                    ArtistDetailScreen(
                        artistId = artistId,
                        onAlbumClick = { album ->
                            // Navigate to album detail
                            parentFragmentManager.beginTransaction()
                                .replace(
                                    id,
                                    AlbumDetailFragment.newInstance(
                                        albumId = album.albumId,
                                        albumName = album.name
                                    )
                                )
                                .addToBackStack(null)
                                .commit()
                        },
                        onAddToPlaylist = { track ->
                            trackForPlaylist = track
                        },
                        onAddArtistToPlaylist = {
                            showArtistPlaylistPicker = true
                            bulkAddState = null
                        },
                        onAddAlbumToPlaylist = { album ->
                            albumForPlaylist = album
                            bulkAddState = null
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

                    // Artist bulk add dialog
                    if (showArtistPlaylistPicker) {
                        PlaylistPickerDialog(
                            onDismiss = {
                                showArtistPlaylistPicker = false
                                bulkAddState = null
                                selectedPlaylist = null
                            },
                            onPlaylistSelected = { playlist ->
                                selectedPlaylist = playlist
                                scope.launch {
                                    bulkAddArtist(artistId, playlist,
                                        onStateChange = { bulkAddState = it }
                                    )
                                }
                            },
                            operationState = bulkAddState,
                            onRetry = {
                                selectedPlaylist?.let { playlist ->
                                    scope.launch {
                                        bulkAddArtist(artistId, playlist,
                                            onStateChange = { bulkAddState = it }
                                        )
                                    }
                                }
                            }
                        )
                    }

                    // Album (from discography grid) bulk add dialog
                    albumForPlaylist?.let { album ->
                        PlaylistPickerDialog(
                            onDismiss = {
                                albumForPlaylist = null
                                bulkAddState = null
                                selectedPlaylist = null
                            },
                            onPlaylistSelected = { playlist ->
                                selectedPlaylist = playlist
                                scope.launch {
                                    bulkAddAlbum(album, playlist,
                                        onStateChange = { bulkAddState = it }
                                    )
                                }
                            },
                            operationState = bulkAddState,
                            onRetry = {
                                selectedPlaylist?.let { playlist ->
                                    scope.launch {
                                        bulkAddAlbum(album, playlist,
                                            onStateChange = { bulkAddState = it }
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
        // Update the Activity toolbar with artist name and back button
        val artistName = arguments?.getString(ARG_ARTIST_NAME) ?: ""
        (activity as? MainActivity)?.updateToolbarForDetail(artistName)
    }

    private suspend fun bulkAddArtist(
        artistId: String,
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
                        val message = "Added ${uris.size} tracks to ${playlist.name}"
                        Log.d(TAG, message)
                        onStateChange(BulkAddState.Success(message))
                        (activity as? MainActivity)?.showSuccessSnackbar(message)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to add artist tracks to playlist", error)
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

    private suspend fun bulkAddAlbum(
        album: MaAlbum,
        playlist: MaPlaylist,
        onStateChange: (BulkAddState) -> Unit
    ) {
        onStateChange(BulkAddState.Loading("Fetching tracks..."))

        val tracksResult = MusicAssistantManager.getAlbumTracks(album.albumId)
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
                        val message = "Added ${album.name} to ${playlist.name}"
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
        private const val TAG = "ArtistDetailFragment"
        private const val ARG_ARTIST_ID = "artistId"
        private const val ARG_ARTIST_NAME = "artistName"

        /**
         * Create a new instance of ArtistDetailFragment.
         *
         * @param artistId The MA artist item_id
         * @param artistName The artist name (for transition animations, optional)
         */
        fun newInstance(artistId: String, artistName: String? = null): ArtistDetailFragment {
            return ArtistDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ARTIST_ID, artistId)
                    artistName?.let { putString(ARG_ARTIST_NAME, it) }
                }
            }
        }
    }
}
