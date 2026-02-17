package com.sendspindroid.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the Playlist Detail screen.
 */
sealed interface PlaylistDetailUiState {
    data object Loading : PlaylistDetailUiState

    data class Success(
        val playlist: MaPlaylist,
        val tracks: List<MaTrack>
    ) : PlaylistDetailUiState {
        /** Total duration in seconds */
        val totalDuration: Long
            get() = tracks.sumOf { it.duration ?: 0L }
    }

    data class Error(val message: String) : PlaylistDetailUiState
}

/**
 * ViewModel for the Playlist Detail screen.
 *
 * Manages loading and displaying playlist information and track listing.
 */
class PlaylistDetailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<PlaylistDetailUiState>(PlaylistDetailUiState.Loading)
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    private var currentPlaylistId: String? = null

    companion object {
        private const val TAG = "PlaylistDetailVM"
    }

    /**
     * Load playlist details for the given playlist ID.
     */
    fun loadPlaylist(playlistId: String) {
        if (playlistId == currentPlaylistId && _uiState.value is PlaylistDetailUiState.Success) {
            return
        }

        currentPlaylistId = playlistId
        _uiState.value = PlaylistDetailUiState.Loading

        viewModelScope.launch {
            Log.d(TAG, "Loading playlist details: $playlistId")

            // Load playlist metadata and tracks in parallel
            val playlistDeferred = async { MusicAssistantManager.getPlaylist(playlistId) }
            val tracksDeferred = async { MusicAssistantManager.getPlaylistTracks(playlistId) }

            val playlistResult = playlistDeferred.await()
            val tracksResult = tracksDeferred.await()

            when {
                playlistResult.isFailure -> {
                    Log.e(TAG, "Failed to load playlist: $playlistId", playlistResult.exceptionOrNull())
                    _uiState.value = PlaylistDetailUiState.Error(
                        playlistResult.exceptionOrNull()?.message ?: "Failed to load playlist"
                    )
                }

                tracksResult.isFailure -> {
                    Log.e(TAG, "Failed to load playlist tracks: $playlistId", tracksResult.exceptionOrNull())
                    _uiState.value = PlaylistDetailUiState.Error(
                        tracksResult.exceptionOrNull()?.message ?: "Failed to load tracks"
                    )
                }

                else -> {
                    val playlist = playlistResult.getOrThrow()
                    val tracks = tracksResult.getOrThrow()
                    Log.d(TAG, "Loaded playlist: ${playlist.name} with ${tracks.size} tracks")
                    _uiState.value = PlaylistDetailUiState.Success(
                        playlist = playlist,
                        tracks = tracks
                    )
                }
            }
        }
    }

    /**
     * Play all tracks in this playlist in order.
     */
    fun playAll() {
        val current = _uiState.value
        if (current !is PlaylistDetailUiState.Success) return

        val uri = current.playlist.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Playing all tracks for playlist: ${current.playlist.name}")
            MusicAssistantManager.playMedia(uri, "playlist").fold(
                onSuccess = {
                    Log.d(TAG, "Started playback for playlist")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to play playlist", error)
                }
            )
        }
    }

    /**
     * Shuffle all tracks in this playlist.
     */
    fun shuffleAll() {
        val current = _uiState.value
        if (current !is PlaylistDetailUiState.Success) return

        val uri = current.playlist.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Shuffling tracks for playlist: ${current.playlist.name}")
            MusicAssistantManager.playMedia(uri, "playlist").fold(
                onSuccess = {
                    Log.d(TAG, "Started shuffle playback for playlist")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to shuffle playlist", error)
                }
            )
        }
    }

    /**
     * Play a specific track from the playlist.
     */
    fun playTrack(track: MaTrack) {
        val uri = track.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Playing track: ${track.name}")
            MusicAssistantManager.playMedia(uri, "track").fold(
                onSuccess = {
                    Log.d(TAG, "Started track playback")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to play track", error)
                }
            )
        }
    }

    /**
     * Data class representing a pending track removal that can be undone.
     */
    data class RemoveAction(
        val trackName: String,
        val executeRemove: () -> Unit,
        val undoRemove: () -> Unit
    )

    /**
     * Remove a track from the playlist by position with undo support.
     *
     * Returns a RemoveAction with optimistic UI update.
     * Call executeRemove() when the undo window expires.
     * Call undoRemove() if the user taps Undo.
     */
    fun removeTrack(position: Int): RemoveAction? {
        val current = _uiState.value
        if (current !is PlaylistDetailUiState.Success) return null

        if (position < 0 || position >= current.tracks.size) return null
        val removedTrack = current.tracks[position]
        val trackName = removedTrack.name ?: "Track"

        // Optimistic: remove track from in-memory list
        val updatedTracks = current.tracks.toMutableList().apply {
            removeAt(position)
        }
        _uiState.value = current.copy(tracks = updatedTracks)

        Log.d(TAG, "Optimistically removed track '$trackName' at position $position")

        return RemoveAction(
            trackName = trackName,
            executeRemove = {
                viewModelScope.launch {
                    Log.d(TAG, "Executing server-side removal of track at position $position")
                    val result = MusicAssistantManager.removePlaylistTracks(
                        current.playlist.playlistId,
                        listOf(position)
                    )
                    result.fold(
                        onSuccess = {
                            Log.d(TAG, "Track removed from server, refreshing")
                            refresh()
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Failed to remove track from server", error)
                            // Restore on server failure
                            _uiState.value = current
                        }
                    )
                }
            },
            undoRemove = {
                Log.d(TAG, "Undoing removal of track '$trackName'")
                _uiState.value = current
            }
        )
    }

    /**
     * Refresh playlist data from the server.
     */
    fun refresh() {
        currentPlaylistId?.let { playlistId ->
            currentPlaylistId = null
            loadPlaylist(playlistId)
        }
    }
}
