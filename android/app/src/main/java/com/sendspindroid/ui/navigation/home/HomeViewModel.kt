package com.sendspindroid.ui.navigation.home

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaLibraryItem
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * ViewModel for the Home screen.
 *
 * Manages data loading for horizontal carousels:
 * - Recently Played
 * - Recently Added
 * - Albums
 * - Artists
 * - Playlists
 * - Radio Stations
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

    private val _albums = MutableLiveData<SectionState<MaLibraryItem>>(SectionState.Loading)
    val albums: LiveData<SectionState<MaLibraryItem>> = _albums

    private val _artists = MutableLiveData<SectionState<MaLibraryItem>>(SectionState.Loading)
    val artists: LiveData<SectionState<MaLibraryItem>> = _artists

    private val _playlists = MutableLiveData<SectionState<MaLibraryItem>>(SectionState.Loading)
    val playlists: LiveData<SectionState<MaLibraryItem>> = _playlists

    private val _radioStations = MutableLiveData<SectionState<MaLibraryItem>>(SectionState.Loading)
    val radioStations: LiveData<SectionState<MaLibraryItem>> = _radioStations

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
        _albums.value = SectionState.Loading
        _artists.value = SectionState.Loading
        _playlists.value = SectionState.Loading
        _radioStations.value = SectionState.Loading

        viewModelScope.launch {
            // Launch all fetches in parallel for optimal performance
            val recentlyPlayedDeferred = async { loadRecentlyPlayed() }
            val recentlyAddedDeferred = async { loadRecentlyAdded() }
            val albumsDeferred = async { loadAlbums() }
            val artistsDeferred = async { loadArtists() }
            val playlistsDeferred = async { loadPlaylists() }
            val radioDeferred = async { loadRadioStations() }

            // Wait for all to complete (each updates its own LiveData)
            recentlyPlayedDeferred.await()
            recentlyAddedDeferred.await()
            albumsDeferred.await()
            artistsDeferred.await()
            playlistsDeferred.await()
            radioDeferred.await()

            hasLoadedData = true
            Log.d(TAG, "Home screen data load complete")
        }
    }

    /**
     * Load recently played items with smart album grouping.
     *
     * Fetches extra items to account for grouping, then applies
     * LibraryItemGrouper to collapse multiple tracks from the same album.
     */
    private suspend fun loadRecentlyPlayed() {
        try {
            // Fetch more items than needed to account for grouping
            val fetchLimit = ITEMS_PER_SECTION * 2
            val result = MusicAssistantManager.getRecentlyPlayed(fetchLimit)
            result.fold(
                onSuccess = { tracks ->
                    Log.d(TAG, "Recently played: ${tracks.size} tracks fetched")

                    // Build album lookup and apply grouping
                    val albumLookup = buildAlbumLookup(tracks)
                    val grouped = LibraryItemGrouper.groupTracks(tracks, albumLookup)
                        .take(ITEMS_PER_SECTION)

                    Log.d(TAG, "Recently played after grouping: ${grouped.size} items")
                    _recentlyPlayed.postValue(SectionState.Success(grouped))
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
     * Load recently added items with smart album grouping.
     *
     * Fetches extra items to account for grouping, then applies
     * LibraryItemGrouper to collapse multiple tracks from the same album.
     */
    private suspend fun loadRecentlyAdded() {
        try {
            // Fetch more items than needed to account for grouping
            val fetchLimit = ITEMS_PER_SECTION * 2
            val result = MusicAssistantManager.getRecentlyAdded(fetchLimit)
            result.fold(
                onSuccess = { tracks ->
                    Log.d(TAG, "Recently added: ${tracks.size} tracks fetched")

                    // Build album lookup and apply grouping
                    val albumLookup = buildAlbumLookup(tracks)
                    val grouped = LibraryItemGrouper.groupTracks(tracks, albumLookup)
                        .take(ITEMS_PER_SECTION)

                    Log.d(TAG, "Recently added after grouping: ${grouped.size} items")
                    _recentlyAdded.postValue(SectionState.Success(grouped))
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
     * Build album lookup map for grouping logic.
     *
     * This enables:
     * - Detection of "single" releases (show as album even with 1 track)
     * - Using proper album artwork instead of track artwork
     * - Getting correct album metadata (year, track count)
     *
     * @param tracks List of tracks to extract unique albums from
     * @return Map of (album, artist) -> MaAlbum
     */
    private suspend fun buildAlbumLookup(
        tracks: List<MaTrack>
    ): Map<LibraryItemGrouper.GroupingKey, MaAlbum> {
        // Extract unique album/artist pairs from tracks
        val uniqueKeys = tracks
            .filter { it.album != null }
            .map { LibraryItemGrouper.GroupingKey(it.album!!, it.artist ?: "") }
            .distinct()

        if (uniqueKeys.isEmpty()) return emptyMap()

        // Fetch albums from library (limit 100 should cover most cases)
        val albumsResult = MusicAssistantManager.getAlbums(limit = 100)
        val albums = albumsResult.getOrNull() ?: return emptyMap()

        // Build lookup map - album name + artist must both match
        return albums.associateBy {
            LibraryItemGrouper.GroupingKey(it.name, it.artist ?: "")
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
     * Load albums from library.
     */
    private suspend fun loadAlbums() {
        try {
            val result = MusicAssistantManager.getAlbums(ITEMS_PER_SECTION)
            result.fold(
                onSuccess = { items ->
                    Log.d(TAG, "Albums: ${items.size} items")
                    _albums.postValue(SectionState.Success(items))
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load albums", error)
                    _albums.postValue(SectionState.Error(error.message ?: "Failed to load"))
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading albums", e)
            _albums.postValue(SectionState.Error(e.message ?: "Failed to load"))
        }
    }

    /**
     * Load artists from library.
     */
    private suspend fun loadArtists() {
        try {
            val result = MusicAssistantManager.getArtists(ITEMS_PER_SECTION)
            result.fold(
                onSuccess = { items ->
                    Log.d(TAG, "Artists: ${items.size} items")
                    _artists.postValue(SectionState.Success(items))
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load artists", error)
                    _artists.postValue(SectionState.Error(error.message ?: "Failed to load"))
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading artists", e)
            _artists.postValue(SectionState.Error(e.message ?: "Failed to load"))
        }
    }

    /**
     * Load radio stations from library.
     */
    private suspend fun loadRadioStations() {
        try {
            val result = MusicAssistantManager.getRadioStations(ITEMS_PER_SECTION)
            result.fold(
                onSuccess = { items ->
                    Log.d(TAG, "Radio stations: ${items.size} items")
                    _radioStations.postValue(SectionState.Success(items))
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load radio stations", error)
                    _radioStations.postValue(SectionState.Error(error.message ?: "Failed to load"))
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading radio stations", e)
            _radioStations.postValue(SectionState.Error(e.message ?: "Failed to load"))
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
