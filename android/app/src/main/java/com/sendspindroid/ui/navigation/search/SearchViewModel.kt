package com.sendspindroid.ui.navigation.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.SearchResults
import com.sendspindroid.musicassistant.model.MaMediaType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Search screen.
 *
 * Manages search state including:
 * - Debounced search execution (300ms delay)
 * - Filter management for media types
 * - Loading and error states
 *
 * ## Debouncing Strategy
 * Search is debounced to avoid excessive API calls while typing. The strategy:
 * 1. Cancel any pending search when query changes
 * 2. Wait 300ms before executing search
 * 3. If query changes during delay, restart the timer
 *
 * This provides responsive UX while minimizing server load.
 */
class SearchViewModel : ViewModel() {

    companion object {
        private const val TAG = "SearchViewModel"
        private const val DEBOUNCE_MS = 300L
        private const val MIN_QUERY_LENGTH = 2
        private const val RESULTS_PER_TYPE = 25
    }

    /**
     * UI state for the search screen.
     *
     * @param query Current search query string
     * @param results Search results grouped by type (null if no search performed)
     * @param isLoading True while search is in progress
     * @param error Error message if search failed
     * @param activeFilters Set of enabled type filters. Empty means search all types.
     */
    data class SearchState(
        val query: String = "",
        val results: SearchResults? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        val activeFilters: Set<MaMediaType> = emptySet()
    ) {
        /**
         * True if we have performed a search and have no results.
         */
        val hasNoResults: Boolean
            get() = query.length >= MIN_QUERY_LENGTH &&
                    !isLoading &&
                    error == null &&
                    results != null &&
                    results.isEmpty()

        /**
         * True if showing empty state (no query entered yet).
         */
        val showEmptyState: Boolean
            get() = query.length < MIN_QUERY_LENGTH &&
                    !isLoading &&
                    results == null
    }

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    // Job for the debounced search, allowing cancellation
    private var searchJob: Job? = null

    /**
     * Update the search query and trigger debounced search.
     *
     * @param query The new search query
     */
    fun setQuery(query: String) {
        // Update query immediately for UI
        _searchState.value = _searchState.value.copy(query = query)

        // Cancel any pending search
        searchJob?.cancel()

        // Don't search if query is too short
        if (query.length < MIN_QUERY_LENGTH) {
            _searchState.value = _searchState.value.copy(
                results = null,
                isLoading = false,
                error = null
            )
            return
        }

        // Start debounced search
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            executeSearch()
        }
    }

    /**
     * Toggle a media type filter.
     *
     * When a filter is enabled, search results will only include that type.
     * Multiple filters can be active simultaneously.
     * When no filters are active, all types are searched.
     *
     * @param type The media type to toggle
     * @param enabled True to enable filter, false to disable
     */
    fun setFilter(type: MaMediaType, enabled: Boolean) {
        val currentFilters = _searchState.value.activeFilters.toMutableSet()

        if (enabled) {
            currentFilters.add(type)
        } else {
            currentFilters.remove(type)
        }

        _searchState.value = _searchState.value.copy(activeFilters = currentFilters)

        // Re-search with new filters if we have a valid query
        if (_searchState.value.query.length >= MIN_QUERY_LENGTH) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch {
                executeSearch()
            }
        }
    }

    /**
     * Check if a specific filter is currently active.
     */
    fun isFilterActive(type: MaMediaType): Boolean {
        return _searchState.value.activeFilters.contains(type)
    }

    /**
     * Clear all active filters.
     */
    fun clearFilters() {
        if (_searchState.value.activeFilters.isEmpty()) return

        _searchState.value = _searchState.value.copy(activeFilters = emptySet())

        // Re-search with all types
        if (_searchState.value.query.length >= MIN_QUERY_LENGTH) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch {
                executeSearch()
            }
        }
    }

    /**
     * Clear the search query and results.
     */
    fun clearSearch() {
        searchJob?.cancel()
        _searchState.value = SearchState(
            activeFilters = _searchState.value.activeFilters  // Preserve filters
        )
    }

    /**
     * Execute the search against Music Assistant API.
     */
    private suspend fun executeSearch() {
        val currentState = _searchState.value
        val query = currentState.query

        if (query.length < MIN_QUERY_LENGTH) return

        Log.d(TAG, "Executing search: '$query' with filters: ${currentState.activeFilters}")

        _searchState.value = currentState.copy(
            isLoading = true,
            error = null
        )

        // Convert filter set to list (null if empty, meaning search all)
        val mediaTypes = if (currentState.activeFilters.isEmpty()) {
            null
        } else {
            currentState.activeFilters.toList()
        }

        val result = MusicAssistantManager.search(
            query = query,
            mediaTypes = mediaTypes,
            limit = RESULTS_PER_TYPE,
            libraryOnly = true
        )

        result.fold(
            onSuccess = { results ->
                Log.d(TAG, "Search successful: ${results.totalCount()} results")
                _searchState.value = _searchState.value.copy(
                    results = results,
                    isLoading = false,
                    error = null
                )
            },
            onFailure = { error ->
                Log.e(TAG, "Search failed", error)
                _searchState.value = _searchState.value.copy(
                    results = null,
                    isLoading = false,
                    error = error.message ?: "Search failed"
                )
            }
        )
    }
}
