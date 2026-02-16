package com.sendspindroid.ui.navigation.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Library browser screen.
 *
 * Manages data loading for each tab with:
 * - Per-tab state (items, loading, error, pagination)
 * - Sort options per content type
 * - Infinite scroll pagination support
 *
 * Uses StateFlow instead of LiveData for better coroutine integration
 * and the ability to share state between tabs via the same ViewModel.
 */
class LibraryViewModel : ViewModel() {

    companion object {
        private const val TAG = "LibraryViewModel"
        private const val PAGE_SIZE = 25
    }

    // ========================================================================
    // Content Types (matching tab order)
    // ========================================================================

    /**
     * Content types for library tabs.
     * Order matches the ViewPager2 tab positions.
     */
    enum class ContentType {
        ALBUMS,
        ARTISTS,
        PLAYLISTS,
        TRACKS,
        RADIO,
        PODCASTS,
        BROWSE
    }

    // ========================================================================
    // Sort Options
    // ========================================================================

    /**
     * Sort options available per content type.
     * @param apiValue The value sent to MA API's order_by parameter
     * @param labelResId Resource ID for display label (not used directly here)
     */
    enum class SortOption(val apiValue: String) {
        NAME("name"),
        DATE_ADDED("timestamp_added_desc"),
        YEAR("year")  // Albums only
    }

    /**
     * Get available sort options for a content type.
     * Albums have Year option; others only have Name and Date Added.
     */
    fun getSortOptionsFor(type: ContentType): List<SortOption> {
        return when (type) {
            ContentType.ALBUMS -> listOf(SortOption.NAME, SortOption.DATE_ADDED, SortOption.YEAR)
            ContentType.TRACKS, ContentType.PODCASTS -> listOf(SortOption.NAME, SortOption.DATE_ADDED)
            else -> listOf(SortOption.NAME)  // Artists, Playlists, Radio - only name sort
        }
    }

    // ========================================================================
    // Tab State
    // ========================================================================

    /**
     * State for a single library tab.
     *
     * @param items Currently loaded items
     * @param isLoading True during initial load or refresh
     * @param isLoadingMore True when loading additional pages
     * @param hasMore True if more items may be available for pagination
     * @param error Error message if load failed
     * @param sortOption Current sort option for this tab
     */
    data class TabState(
        val items: List<MaLibraryItem> = emptyList(),
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val hasMore: Boolean = true,
        val error: String? = null,
        val sortOption: SortOption = SortOption.NAME
    )

    // Per-tab state flows
    private val _albumsState = MutableStateFlow(TabState())
    val albumsState: StateFlow<TabState> = _albumsState.asStateFlow()

    private val _artistsState = MutableStateFlow(TabState())
    val artistsState: StateFlow<TabState> = _artistsState.asStateFlow()

    private val _playlistsState = MutableStateFlow(TabState())
    val playlistsState: StateFlow<TabState> = _playlistsState.asStateFlow()

    private val _tracksState = MutableStateFlow(TabState())
    val tracksState: StateFlow<TabState> = _tracksState.asStateFlow()

    private val _radioState = MutableStateFlow(TabState())
    val radioState: StateFlow<TabState> = _radioState.asStateFlow()

    private val _podcastsState = MutableStateFlow(TabState())
    val podcastsState: StateFlow<TabState> = _podcastsState.asStateFlow()

    private val _browseState = MutableStateFlow(TabState())
    val browseState: StateFlow<TabState> = _browseState.asStateFlow()

    /**
     * Get the StateFlow for a specific content type.
     */
    fun getStateFor(type: ContentType): StateFlow<TabState> {
        return when (type) {
            ContentType.ALBUMS -> albumsState
            ContentType.ARTISTS -> artistsState
            ContentType.PLAYLISTS -> playlistsState
            ContentType.TRACKS -> tracksState
            ContentType.RADIO -> radioState
            ContentType.PODCASTS -> podcastsState
            ContentType.BROWSE -> browseState
        }
    }

    /**
     * Get the MutableStateFlow for a specific content type (internal use).
     */
    private fun getMutableStateFor(type: ContentType): MutableStateFlow<TabState> {
        return when (type) {
            ContentType.ALBUMS -> _albumsState
            ContentType.ARTISTS -> _artistsState
            ContentType.PLAYLISTS -> _playlistsState
            ContentType.TRACKS -> _tracksState
            ContentType.RADIO -> _radioState
            ContentType.PODCASTS -> _podcastsState
            ContentType.BROWSE -> _browseState
        }
    }

    // Track which tabs have loaded initial data
    private val loadedTabs = mutableSetOf<ContentType>()

    // ========================================================================
    // Browse Navigation
    // ========================================================================

    /** Current browse path (null = root level showing providers) */
    private val _browsePath = MutableStateFlow<String?>(null)
    val browsePath: StateFlow<String?> = _browsePath.asStateFlow()

    /** Navigation stack for back button support */
    private val browsePathStack = mutableListOf<String?>()

    // ========================================================================
    // Data Loading
    // ========================================================================

    /**
     * Load items for a content type.
     *
     * @param type The content type to load
     * @param refresh If true, forces reload even if already loaded
     */
    fun loadItems(type: ContentType, refresh: Boolean = false) {
        // Browse tab uses its own loading path
        if (type == ContentType.BROWSE) {
            loadBrowse(refresh)
            return
        }

        // Skip if already loaded and not refreshing
        if (!refresh && type in loadedTabs) {
            Log.d(TAG, "Tab $type already loaded, skipping")
            return
        }

        val stateFlow = getMutableStateFor(type)
        val currentState = stateFlow.value

        // Set loading state (preserve sort option)
        stateFlow.value = TabState(
            isLoading = true,
            sortOption = currentState.sortOption
        )

        viewModelScope.launch {
            try {
                val items = fetchItems(type, 0, currentState.sortOption)
                stateFlow.value = TabState(
                    items = items,
                    isLoading = false,
                    hasMore = items.size >= PAGE_SIZE,
                    sortOption = currentState.sortOption
                )
                loadedTabs.add(type)
                Log.d(TAG, "Loaded ${items.size} items for $type")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load $type", e)
                stateFlow.value = TabState(
                    isLoading = false,
                    error = e.message ?: "Failed to load",
                    sortOption = currentState.sortOption
                )
            }
        }
    }

    /**
     * Load more items for pagination (infinite scroll).
     *
     * @param type The content type to load more of
     */
    fun loadMore(type: ContentType) {
        val stateFlow = getMutableStateFor(type)
        val currentState = stateFlow.value

        // Don't load if already loading or no more items
        if (currentState.isLoading || currentState.isLoadingMore || !currentState.hasMore) {
            return
        }

        val offset = currentState.items.size
        Log.d(TAG, "Loading more $type items at offset $offset")

        stateFlow.value = currentState.copy(isLoadingMore = true)

        viewModelScope.launch {
            try {
                val newItems = fetchItems(type, offset, currentState.sortOption)
                stateFlow.value = currentState.copy(
                    items = currentState.items + newItems,
                    isLoadingMore = false,
                    hasMore = newItems.size >= PAGE_SIZE
                )
                Log.d(TAG, "Loaded ${newItems.size} more items for $type (total: ${currentState.items.size + newItems.size})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load more $type", e)
                stateFlow.value = currentState.copy(
                    isLoadingMore = false,
                    error = e.message
                )
            }
        }
    }

    /**
     * Set the sort option for a content type and reload.
     *
     * @param type The content type
     * @param sort The new sort option
     */
    fun setSortOption(type: ContentType, sort: SortOption) {
        val stateFlow = getMutableStateFor(type)
        val currentState = stateFlow.value

        // Skip if same sort option
        if (currentState.sortOption == sort) return

        Log.d(TAG, "Changing sort for $type to ${sort.apiValue}")

        // Clear loaded flag to force reload
        loadedTabs.remove(type)

        // Update sort option and trigger reload
        stateFlow.value = currentState.copy(sortOption = sort)
        loadItems(type, refresh = true)
    }

    /**
     * Load browse items for the current path.
     */
    private fun loadBrowse(refresh: Boolean = false) {
        val currentPath = _browsePath.value

        // Skip if already loaded at this path and not refreshing
        if (!refresh && ContentType.BROWSE in loadedTabs && _browseState.value.items.isNotEmpty()) {
            return
        }

        _browseState.value = TabState(isLoading = true)

        viewModelScope.launch {
            try {
                val items = MusicAssistantManager.browse(currentPath).getOrThrow()
                _browseState.value = TabState(
                    items = items,
                    isLoading = false,
                    hasMore = false  // Browse returns all items at once
                )
                loadedTabs.add(ContentType.BROWSE)
                Log.d(TAG, "Loaded ${items.size} browse items for path: ${currentPath ?: "root"}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to browse path: ${currentPath ?: "root"}", e)
                _browseState.value = TabState(
                    isLoading = false,
                    error = e.message ?: "Failed to browse"
                )
            }
        }
    }

    /**
     * Navigate into a browse folder.
     */
    fun navigateToFolder(path: String) {
        browsePathStack.add(_browsePath.value)
        _browsePath.value = path
        loadedTabs.remove(ContentType.BROWSE)
        loadBrowse()
    }

    /**
     * Navigate back one level in the browse hierarchy.
     * @return true if navigated back, false if already at root
     */
    fun navigateBack(): Boolean {
        if (browsePathStack.isEmpty()) return false
        _browsePath.value = browsePathStack.removeAt(browsePathStack.lastIndex)
        loadedTabs.remove(ContentType.BROWSE)
        loadBrowse()
        return true
    }

    /**
     * Whether the browse tab is at the root level (showing providers).
     */
    fun isAtBrowseRoot(): Boolean = _browsePath.value == null

    /**
     * Refresh a tab's data.
     *
     * @param type The content type to refresh
     */
    fun refresh(type: ContentType) {
        loadedTabs.remove(type)
        loadItems(type, refresh = true)
    }

    /**
     * Fetch items from Music Assistant API.
     *
     * @param type Content type to fetch
     * @param offset Pagination offset
     * @param sort Sort option
     * @return List of library items
     */
    private suspend fun fetchItems(
        type: ContentType,
        offset: Int,
        sort: SortOption
    ): List<MaLibraryItem> {
        val result = when (type) {
            ContentType.ALBUMS -> MusicAssistantManager.getAlbums(
                limit = PAGE_SIZE,
                offset = offset,
                orderBy = sort.apiValue
            )
            ContentType.ARTISTS -> MusicAssistantManager.getArtists(
                limit = PAGE_SIZE,
                offset = offset,
                orderBy = sort.apiValue
            )
            ContentType.PLAYLISTS -> MusicAssistantManager.getPlaylists(
                limit = PAGE_SIZE,
                offset = offset,
                orderBy = sort.apiValue
            )
            ContentType.TRACKS -> MusicAssistantManager.getTracks(
                limit = PAGE_SIZE,
                offset = offset,
                orderBy = sort.apiValue
            )
            ContentType.RADIO -> MusicAssistantManager.getRadioStations(
                limit = PAGE_SIZE,
                offset = offset,
                orderBy = sort.apiValue
            )
            ContentType.PODCASTS -> MusicAssistantManager.getPodcasts(
                limit = PAGE_SIZE,
                offset = offset,
                orderBy = sort.apiValue
            )
            ContentType.BROWSE -> return emptyList()
        }

        return result.getOrThrow()
    }
}
