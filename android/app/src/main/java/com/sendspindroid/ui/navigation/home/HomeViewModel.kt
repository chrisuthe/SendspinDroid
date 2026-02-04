package com.sendspindroid.ui.navigation.home

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaLibraryItem
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * ViewModel for the Home screen.
 *
 * Manages data loading for three horizontal carousels:
 * - Recently Played
 * - Recently Added
 * - Playlists
 *
 * Uses sealed class for UI state to cleanly handle loading, success, and error states.
 * Loads all sections in parallel for optimal performance.
 */
class HomeViewModel : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
        private const val ITEMS_PER_SECTION = 15
    }

    // ========================================================================
    // UI State sealed classes
    // ========================================================================

    /**
     * Represents the state of a data section (loading, loaded, or error).
     */
    sealed class SectionState<out T> {
        object Loading : SectionState<Nothing>()
        data class Success<T>(val items: List<T>) : SectionState<T>()
        data class Error(val message: String) : SectionState<Nothing>()
    }

    // ========================================================================
    // LiveData for each section - all use MaLibraryItem for unified adapter
    // ========================================================================

    private val _recentlyPlayed = MutableLiveData<SectionState<MaLibraryItem>>(SectionState.Loading)
    val recentlyPlayed: LiveData<SectionState<MaLibraryItem>> = _recentlyPlayed

    private val _recentlyAdded = MutableLiveData<SectionState<MaLibraryItem>>(SectionState.Loading)
    val recentlyAdded: LiveData<SectionState<MaLibraryItem>> = _recentlyAdded

    private val _playlists = MutableLiveData<SectionState<MaLibraryItem>>(SectionState.Loading)
    val playlists: LiveData<SectionState<MaLibraryItem>> = _playlists

    // Track if initial load has been done
    private var hasLoadedData = false

    // ========================================================================
    // Data Loading
    // ========================================================================

    /**
     * Load all home screen data.
     *
     * Fetches all three sections in parallel using async/await.
     * Can be called on fragment creation or pull-to-refresh.
     *
     * @param forceRefresh If true, reloads even if data was already loaded
     */
    fun loadHomeData(forceRefresh: Boolean = false) {
        if (hasLoadedData && !forceRefresh) {
            Log.d(TAG, "Home data already loaded, skipping")
            return
        }

        Log.d(TAG, "Loading home screen data (forceRefresh=$forceRefresh)")

        // Set all sections to loading state
        _recentlyPlayed.value = SectionState.Loading
        _recentlyAdded.value = SectionState.Loading
        _playlists.value = SectionState.Loading

        viewModelScope.launch {
            // Launch all three fetches in parallel
            val recentlyPlayedDeferred = async { loadRecentlyPlayed() }
            val recentlyAddedDeferred = async { loadRecentlyAdded() }
            val playlistsDeferred = async { loadPlaylists() }

            // Wait for all to complete (each updates its own LiveData)
            recentlyPlayedDeferred.await()
            recentlyAddedDeferred.await()
            playlistsDeferred.await()

            hasLoadedData = true
            Log.d(TAG, "Home screen data load complete")
        }
    }

    /**
     * Load recently played items.
     */
    private suspend fun loadRecentlyPlayed() {
        try {
            val result = MusicAssistantManager.getRecentlyPlayed(ITEMS_PER_SECTION)
            result.fold(
                onSuccess = { items ->
                    Log.d(TAG, "Recently played: ${items.size} items")
                    // Cast to MaLibraryItem list for unified adapter
                    _recentlyPlayed.postValue(SectionState.Success(items))
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load recently played", error)
                    _recentlyPlayed.postValue(SectionState.Error(error.message ?: "Failed to load"))
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading recently played", e)
            _recentlyPlayed.postValue(SectionState.Error(e.message ?: "Failed to load"))
        }
    }

    /**
     * Load recently added items.
     */
    private suspend fun loadRecentlyAdded() {
        try {
            val result = MusicAssistantManager.getRecentlyAdded(ITEMS_PER_SECTION)
            result.fold(
                onSuccess = { items ->
                    Log.d(TAG, "Recently added: ${items.size} items")
                    _recentlyAdded.postValue(SectionState.Success(items))
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load recently added", error)
                    _recentlyAdded.postValue(SectionState.Error(error.message ?: "Failed to load"))
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading recently added", e)
            _recentlyAdded.postValue(SectionState.Error(e.message ?: "Failed to load"))
        }
    }

    /**
     * Load playlists.
     */
    private suspend fun loadPlaylists() {
        try {
            val result = MusicAssistantManager.getPlaylists(ITEMS_PER_SECTION)
            result.fold(
                onSuccess = { items ->
                    Log.d(TAG, "Playlists: ${items.size} items")
                    _playlists.postValue(SectionState.Success(items))
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load playlists", error)
                    _playlists.postValue(SectionState.Error(error.message ?: "Failed to load"))
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading playlists", e)
            _playlists.postValue(SectionState.Error(e.message ?: "Failed to load"))
        }
    }

    /**
     * Refresh all home screen data.
     * Alias for loadHomeData(forceRefresh = true).
     */
    fun refresh() {
        loadHomeData(forceRefresh = true)
    }
}
