package com.sendspindroid.ui.navigation.playlists

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MusicAssistantManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the Playlists list screen.
 */
sealed interface PlaylistsUiState {
    data object Loading : PlaylistsUiState

    data class Success(
        val playlists: List<MaPlaylist>,
        val isRefreshing: Boolean = false
    ) : PlaylistsUiState

    data class Error(val message: String) : PlaylistsUiState
}

/**
 * ViewModel for the Playlists tab.
 *
 * Loads and manages the list of playlists from Music Assistant.
 */
class PlaylistsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<PlaylistsUiState>(PlaylistsUiState.Loading)
    val uiState: StateFlow<PlaylistsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false

    companion object {
        private const val TAG = "PlaylistsViewModel"
    }

    /**
     * Load playlists from the server.
     * Only loads once unless refresh() is called.
     */
    fun loadPlaylists() {
        if (hasLoaded && _uiState.value is PlaylistsUiState.Success) return
        forceLoad()
    }

    /**
     * Pull-to-refresh: reload playlists from the server.
     */
    fun refresh() {
        val current = _uiState.value
        if (current is PlaylistsUiState.Success) {
            _uiState.value = current.copy(isRefreshing = true)
        }
        forceLoad()
    }

    private fun forceLoad() {
        viewModelScope.launch {
            if (_uiState.value !is PlaylistsUiState.Success) {
                _uiState.value = PlaylistsUiState.Loading
            }

            Log.d(TAG, "Loading playlists...")
            val result = MusicAssistantManager.getPlaylists()

            result.fold(
                onSuccess = { playlists ->
                    Log.d(TAG, "Loaded ${playlists.size} playlists")
                    hasLoaded = true
                    _uiState.value = PlaylistsUiState.Success(
                        playlists = playlists,
                        isRefreshing = false
                    )
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load playlists", error)
                    _uiState.value = PlaylistsUiState.Error(
                        error.message ?: "Failed to load playlists"
                    )
                }
            )
        }
    }

    /**
     * Create a new playlist with the given name.
     */
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            Log.d(TAG, "Creating playlist: $name")
            val result = MusicAssistantManager.createPlaylist(name)
            result.fold(
                onSuccess = {
                    Log.d(TAG, "Playlist created: ${it.name}")
                    refresh()
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to create playlist", error)
                }
            )
        }
    }

    /**
     * Data class representing a pending playlist deletion that can be undone.
     */
    data class DeleteAction(
        val playlistName: String,
        val executeDelete: () -> Unit,
        val undoDelete: () -> Unit
    )

    /**
     * Delete a playlist with undo support.
     *
     * Optimistically removes the playlist from the UI list.
     * Returns a DeleteAction:
     * - Call executeDelete() when the undo window expires
     * - Call undoDelete() if the user taps Undo
     */
    fun deletePlaylist(playlistId: String): DeleteAction? {
        val current = _uiState.value
        if (current !is PlaylistsUiState.Success) return null

        val playlist = current.playlists.find { it.playlistId == playlistId } ?: return null
        val playlistName = playlist.name

        // Optimistic: remove from in-memory list
        val updatedPlaylists = current.playlists.filter { it.playlistId != playlistId }
        _uiState.value = current.copy(playlists = updatedPlaylists)

        Log.d(TAG, "Optimistically removed playlist '$playlistName'")

        return DeleteAction(
            playlistName = playlistName,
            executeDelete = {
                viewModelScope.launch {
                    Log.d(TAG, "Executing server-side deletion of playlist: $playlistId")
                    val result = MusicAssistantManager.deletePlaylist(playlistId)
                    result.fold(
                        onSuccess = {
                            Log.d(TAG, "Playlist deleted from server")
                            refresh()
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Failed to delete playlist from server", error)
                            // Restore on server failure
                            _uiState.value = current
                        }
                    )
                }
            },
            undoDelete = {
                Log.d(TAG, "Undoing deletion of playlist '$playlistName'")
                _uiState.value = current
            }
        )
    }
}
