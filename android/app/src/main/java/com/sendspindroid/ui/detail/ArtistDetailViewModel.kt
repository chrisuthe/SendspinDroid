package com.sendspindroid.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the Artist Detail screen.
 */
sealed interface ArtistDetailUiState {
    data object Loading : ArtistDetailUiState

    data class Success(
        val artist: MaArtist,
        val topTracks: List<MaTrack>,
        val albums: List<MaAlbum>,
        val showAllTracks: Boolean = false
    ) : ArtistDetailUiState {
        /** Tracks to display based on showAllTracks flag */
        val displayedTracks: List<MaTrack>
            get() = if (showAllTracks) topTracks else topTracks.take(5)

        /** Total track count across all albums */
        val totalTrackCount: Int
            get() = albums.sumOf { it.trackCount ?: 0 }
    }

    data class Error(val message: String) : ArtistDetailUiState
}

/**
 * ViewModel for the Artist Detail screen.
 *
 * Manages loading and displaying complete artist information including
 * top tracks and discography.
 */
class ArtistDetailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<ArtistDetailUiState>(ArtistDetailUiState.Loading)
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    private var currentArtistId: String? = null

    companion object {
        private const val TAG = "ArtistDetailViewModel"
    }

    /**
     * Load artist details for the given artist ID.
     *
     * @param artistId The MA artist item_id
     */
    fun loadArtist(artistId: String) {
        // Don't reload if same artist
        if (artistId == currentArtistId && _uiState.value is ArtistDetailUiState.Success) {
            return
        }

        currentArtistId = artistId
        _uiState.value = ArtistDetailUiState.Loading

        viewModelScope.launch {
            Log.d(TAG, "Loading artist details: $artistId")

            MusicAssistantManager.getArtistDetails(artistId).fold(
                onSuccess = { details ->
                    Log.d(TAG, "Loaded artist: ${details.artist.name} " +
                            "with ${details.topTracks.size} tracks, ${details.albums.size} albums")
                    _uiState.value = ArtistDetailUiState.Success(
                        artist = details.artist,
                        topTracks = details.topTracks,
                        albums = details.albums
                    )
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load artist: $artistId", error)
                    _uiState.value = ArtistDetailUiState.Error(
                        error.message ?: "Failed to load artist"
                    )
                }
            )
        }
    }

    /**
     * Toggle showing all tracks vs. top 5.
     */
    fun toggleShowAllTracks() {
        val current = _uiState.value
        if (current is ArtistDetailUiState.Success) {
            _uiState.value = current.copy(showAllTracks = !current.showAllTracks)
        }
    }

    /**
     * Play all tracks by this artist.
     */
    fun playAll() {
        val current = _uiState.value
        if (current !is ArtistDetailUiState.Success) return

        val uri = current.artist.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Playing all tracks for artist: ${current.artist.name}")
            MusicAssistantManager.playMedia(uri, "artist").fold(
                onSuccess = {
                    Log.d(TAG, "Started playback for artist")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to play artist", error)
                }
            )
        }
    }

    /**
     * Shuffle all tracks by this artist.
     */
    fun shuffleAll() {
        val current = _uiState.value
        if (current !is ArtistDetailUiState.Success) return

        val uri = current.artist.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Shuffling tracks for artist: ${current.artist.name}")
            // TODO: Add shuffle parameter when MA supports it
            MusicAssistantManager.playMedia(uri, "artist").fold(
                onSuccess = {
                    Log.d(TAG, "Started shuffle playback for artist")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to shuffle artist", error)
                }
            )
        }
    }

    /**
     * Add all tracks to queue.
     */
    fun addToQueue() {
        val current = _uiState.value
        if (current !is ArtistDetailUiState.Success) return

        val uri = current.artist.uri
        if (uri.isNullOrBlank()) {
            Log.w(TAG, "Artist ${current.artist.name} has no URI, cannot add to queue")
            return
        }

        viewModelScope.launch {
            Log.d(TAG, "Adding artist to queue: ${current.artist.name}")
            val result = MusicAssistantManager.playMedia(uri, "artist", enqueue = true)
            result.fold(
                onSuccess = {
                    Log.d(TAG, "Artist added to queue: ${current.artist.name}")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to add artist to queue", error)
                }
            )
        }
    }

    /**
     * Play a specific track, queuing the rest of the top tracks.
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
     * Refresh artist data from the server.
     */
    fun refresh() {
        currentArtistId?.let { artistId ->
            currentArtistId = null // Force reload
            loadArtist(artistId)
        }
    }
}
