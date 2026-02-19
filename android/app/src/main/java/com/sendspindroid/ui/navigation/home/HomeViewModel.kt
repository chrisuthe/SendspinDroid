package com.sendspindroid.ui.navigation.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaLibraryItem
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    // StateFlow for each section - all use MaLibraryItem for unified adapter
    // ========================================================================

    private val _recentlyPlayed = MutableStateFlow<SectionState<MaLibraryItem>>(SectionState.Loading)
    val recentlyPlayed: StateFlow<SectionState<MaLibraryItem>> = _recentlyPlayed.asStateFlow()

    private val _recentlyAdded = MutableStateFlow<SectionState<MaLibraryItem>>(SectionState.Loading)
    val recentlyAdded: StateFlow<SectionState<MaLibraryItem>> = _recentlyAdded.asStateFlow()

    private val _albums = MutableStateFlow<SectionState<MaLibraryItem>>(SectionState.Loading)
    val albums: StateFlow<SectionState<MaLibraryItem>> = _albums.asStateFlow()

    private val _artists = MutableStateFlow<SectionState<MaLibraryItem>>(SectionState.Loading)
    val artists: StateFlow<SectionState<MaLibraryItem>> = _artists.asStateFlow()

    private val _playlists = MutableStateFlow<SectionState<MaLibraryItem>>(SectionState.Loading)
    val playlists: StateFlow<SectionState<MaLibraryItem>> = _playlists.asStateFlow()

    private val _radioStations = MutableStateFlow<SectionState<MaLibraryItem>>(SectionState.Loading)
    val radioStations: StateFlow<SectionState<MaLibraryItem>> = _radioStations.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Track if initial load has been done
    private var hasLoadedData = false

    private fun hasAnyError(): Boolean {
        return _recentlyPlayed.value is SectionState.Error ||
               _recentlyAdded.value is SectionState.Error ||
               _albums.value is SectionState.Error ||
               _artists.value is SectionState.Error ||
               _playlists.value is SectionState.Error ||
               _radioStations.value is SectionState.Error
    }

    // ========================================================================
    // Data Loading
    // ========================================================================

    /**
     * Load all home screen data.
     *
     * Fetches all sections in parallel using async/await.
     * Can be called on screen creation or pull-to-refresh.
     *
     * @param forceRefresh If true, reloads even if data was already loaded
     */
    fun loadHomeData(forceRefresh: Boolean = false) {
        if (hasLoadedData && !forceRefresh && !hasAnyError()) {
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

            // Wait for all to complete (each updates its own StateFlow)
            recentlyPlayedDeferred.await()
            recentlyAddedDeferred.await()
            albumsDeferred.await()
            artistsDeferred.await()
            playlistsDeferred.await()
            radioDeferred.await()

            hasLoadedData = true
            _isRefreshing.value = false
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
                    _recentlyPlayed.value = SectionState.Success(grouped)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load recently played", error)
                    _recentlyPlayed.value = SectionState.Error(error.message ?: "Failed to load")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading recently played", e)
            _recentlyPlayed.value = SectionState.Error(e.message ?: "Failed to load")
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
                    _recentlyAdded.value = SectionState.Success(grouped)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load recently added", error)
                    _recentlyAdded.value = SectionState.Error(error.message ?: "Failed to load")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading recently added", e)
            _recentlyAdded.value = SectionState.Error(e.message ?: "Failed to load")
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
                    _playlists.value = SectionState.Success(items)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load playlists", error)
                    _playlists.value = SectionState.Error(error.message ?: "Failed to load")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading playlists", e)
            _playlists.value = SectionState.Error(e.message ?: "Failed to load")
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
                    _albums.value = SectionState.Success(items)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load albums", error)
                    _albums.value = SectionState.Error(error.message ?: "Failed to load")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading albums", e)
            _albums.value = SectionState.Error(e.message ?: "Failed to load")
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
                    _artists.value = SectionState.Success(items)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load artists", error)
                    _artists.value = SectionState.Error(error.message ?: "Failed to load")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading artists", e)
            _artists.value = SectionState.Error(e.message ?: "Failed to load")
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
                    _radioStations.value = SectionState.Success(items)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load radio stations", error)
                    _radioStations.value = SectionState.Error(error.message ?: "Failed to load")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading radio stations", e)
            _radioStations.value = SectionState.Error(e.message ?: "Failed to load")
        }
    }

    /**
     * Refresh all home screen data.
     * Alias for loadHomeData(forceRefresh = true).
     */
    fun refresh() {
        _isRefreshing.value = true
        loadHomeData(forceRefresh = true)
    }
}
