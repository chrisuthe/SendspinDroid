package com.sendspindroid.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the Album Detail screen.
 */
sealed interface AlbumDetailUiState {
    data object Loading : AlbumDetailUiState

    data class Success(
        val album: MaAlbum,
        val tracks: List<MaTrack>
    ) : AlbumDetailUiState {
        /** Total duration in seconds */
        val totalDuration: Long
            get() = tracks.sumOf { it.duration ?: 0L }
    }

    data class Error(val message: String) : AlbumDetailUiState
}

/**
 * ViewModel for the Album Detail screen.
 *
 * Manages loading and displaying album information and track listing.
 */
class AlbumDetailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<AlbumDetailUiState>(AlbumDetailUiState.Loading)
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    private var currentAlbumId: String? = null

    companion object {
        private const val TAG = "AlbumDetailViewModel"
    }

    /**
     * Load album details for the given album ID.
     *
     * @param albumId The MA album item_id
     */
    fun loadAlbum(albumId: String) {
        // Don't reload if same album
        if (albumId == currentAlbumId && _uiState.value is AlbumDetailUiState.Success) {
            return
        }

        currentAlbumId = albumId
        _uiState.value = AlbumDetailUiState.Loading

        viewModelScope.launch {
            Log.d(TAG, "Loading album details: $albumId")

            // Load album metadata and tracks in parallel
            val albumResult = MusicAssistantManager.getAlbum(albumId)
            val tracksResult = MusicAssistantManager.getAlbumTracks(albumId)

            when {
                albumResult.isFailure -> {
                    Log.e(TAG, "Failed to load album: $albumId", albumResult.exceptionOrNull())
                    _uiState.value = AlbumDetailUiState.Error(
                        albumResult.exceptionOrNull()?.message ?: "Failed to load album"
                    )
                }

                tracksResult.isFailure -> {
                    Log.e(TAG, "Failed to load album tracks: $albumId", tracksResult.exceptionOrNull())
                    _uiState.value = AlbumDetailUiState.Error(
                        tracksResult.exceptionOrNull()?.message ?: "Failed to load tracks"
                    )
                }

                else -> {
                    val album = albumResult.getOrThrow()
                    val tracks = tracksResult.getOrThrow()
                    Log.d(TAG, "Loaded album: ${album.name} with ${tracks.size} tracks")
                    _uiState.value = AlbumDetailUiState.Success(
                        album = album,
                        tracks = tracks
                    )
                }
            }
        }
    }

    /**
     * Play all tracks on this album in order.
     */
    fun playAll() {
        val current = _uiState.value
        if (current !is AlbumDetailUiState.Success) return

        val uri = current.album.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Playing all tracks for album: ${current.album.name}")
            MusicAssistantManager.playMedia(uri, "album").fold(
                onSuccess = {
                    Log.d(TAG, "Started playback for album")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to play album", error)
                }
            )
        }
    }

    /**
     * Shuffle all tracks on this album.
     */
    fun shuffleAll() {
        val current = _uiState.value
        if (current !is AlbumDetailUiState.Success) return

        val uri = current.album.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Shuffling tracks for album: ${current.album.name}")
            // TODO: Add shuffle parameter when MA supports it
            MusicAssistantManager.playMedia(uri, "album").fold(
                onSuccess = {
                    Log.d(TAG, "Started shuffle playback for album")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to shuffle album", error)
                }
            )
        }
    }

    /**
     * Add all tracks to queue.
     */
    fun addToQueue() {
        val current = _uiState.value
        if (current !is AlbumDetailUiState.Success) return

        val uri = current.album.uri
        if (uri.isNullOrBlank()) {
            Log.w(TAG, "Album ${current.album.name} has no URI, cannot add to queue")
            return
        }

        viewModelScope.launch {
            Log.d(TAG, "Adding album to queue: ${current.album.name}")
            val result = MusicAssistantManager.playMedia(uri, "album", enqueue = true)
            result.fold(
                onSuccess = {
                    Log.d(TAG, "Album added to queue: ${current.album.name}")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to add album to queue", error)
                }
            )
        }
    }

    /**
     * Play a specific track from the album.
     *
     * Plays the selected track and queues the rest of the album.
     *
     * @param track The track to start playing
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
     * Refresh album data from the server.
     */
    fun refresh() {
        currentAlbumId?.let { albumId ->
            currentAlbumId = null // Force reload
            loadAlbum(albumId)
        }
    }
}
