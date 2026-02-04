package com.sendspindroid.ui.navigation

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sendspindroid.R
import com.sendspindroid.databinding.FragmentSearchBinding
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType
import com.sendspindroid.ui.navigation.search.SearchResultsAdapter
import com.sendspindroid.ui.navigation.search.SearchViewModel
import com.sendspindroid.ui.navigation.search.toFlatList
import kotlinx.coroutines.launch

/**
 * Search tab fragment providing full-text search of Music Assistant library.
 *
 * Features:
 * - Material SearchView with debounced input (300ms delay)
 * - Filter chips for media type selection
 * - Grouped results with section headers
 * - Empty state, no results state, and error state
 * - Keyboard dismiss on scroll
 *
 * ## Architecture
 * Uses SearchViewModel for state management with unidirectional data flow:
 * 1. User input updates query in ViewModel
 * 2. ViewModel debounces and executes search
 * 3. Results flow back via StateFlow
 * 4. Fragment observes and renders UI
 */
class SearchFragment : Fragment() {

    companion object {
        private const val TAG = "SearchFragment"

        fun newInstance() = SearchFragment()
    }

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SearchViewModel by viewModels()
    private lateinit var adapter: SearchResultsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSearchInput()
        setupFilterChips()
        setupRecyclerView()
        observeState()
    }

    /**
     * Set up the search input with text change listener.
     */
    private fun setupSearchInput() {
        // Text change listener for debounced search
        binding.searchInput.addTextChangedListener { text ->
            viewModel.setQuery(text?.toString() ?: "")
        }

        // Handle IME search action
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // Search is already triggered by text change, just hide keyboard
                binding.searchInput.clearFocus()
                true
            } else {
                false
            }
        }
    }

    /**
     * Set up filter chips for media type filtering.
     *
     * Chips toggle filters - unchecked means include all types,
     * checked chips filter to only those types.
     */
    private fun setupFilterChips() {
        // Map chips to media types
        val chipToType = mapOf(
            binding.chipAlbums to MaMediaType.ALBUM,
            binding.chipArtists to MaMediaType.ARTIST,
            binding.chipTracks to MaMediaType.TRACK,
            binding.chipPlaylists to MaMediaType.PLAYLIST,
            binding.chipRadio to MaMediaType.RADIO
        )

        chipToType.forEach { (chip, type) ->
            chip.setOnCheckedChangeListener { _, isChecked ->
                viewModel.setFilter(type, isChecked)
            }
        }
    }

    /**
     * Set up RecyclerView for search results.
     */
    private fun setupRecyclerView() {
        adapter = SearchResultsAdapter { item ->
            onItemClick(item)
        }

        binding.searchResults.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SearchFragment.adapter

            // Hide keyboard when scrolling
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        binding.searchInput.clearFocus()
                        // Hide keyboard
                        val imm = context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                as? android.view.inputmethod.InputMethodManager
                        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
                    }
                }
            })
        }
    }

    /**
     * Observe ViewModel state and update UI.
     */
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.searchState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    /**
     * Update UI based on current search state.
     */
    private fun updateUI(state: SearchViewModel.SearchState) {
        // Update filter chip states to match ViewModel
        updateFilterChipStates(state.activeFilters)

        when {
            state.isLoading -> {
                // Show loading indicator
                binding.loadingIndicator.visibility = View.VISIBLE
                binding.searchResults.visibility = View.GONE
                binding.emptyState.visibility = View.GONE
                binding.noResultsState.visibility = View.GONE
                binding.errorState.visibility = View.GONE
            }
            state.error != null -> {
                // Show error state
                binding.loadingIndicator.visibility = View.GONE
                binding.searchResults.visibility = View.GONE
                binding.emptyState.visibility = View.GONE
                binding.noResultsState.visibility = View.GONE
                binding.errorState.visibility = View.VISIBLE
                binding.errorText.text = state.error
            }
            state.hasNoResults -> {
                // Show no results state
                binding.loadingIndicator.visibility = View.GONE
                binding.searchResults.visibility = View.GONE
                binding.emptyState.visibility = View.GONE
                binding.noResultsState.visibility = View.VISIBLE
                binding.errorState.visibility = View.GONE
                binding.noResultsText.text = getString(R.string.search_no_results_for, state.query)
            }
            state.showEmptyState -> {
                // Show empty state (no query yet)
                binding.loadingIndicator.visibility = View.GONE
                binding.searchResults.visibility = View.GONE
                binding.emptyState.visibility = View.VISIBLE
                binding.noResultsState.visibility = View.GONE
                binding.errorState.visibility = View.GONE
            }
            state.results != null && !state.results.isEmpty() -> {
                // Show results
                binding.loadingIndicator.visibility = View.GONE
                binding.searchResults.visibility = View.VISIBLE
                binding.emptyState.visibility = View.GONE
                binding.noResultsState.visibility = View.GONE
                binding.errorState.visibility = View.GONE

                // Convert grouped results to flat list with headers
                val flatList = state.results.toFlatList(
                    artistsHeader = getString(R.string.search_section_artists),
                    albumsHeader = getString(R.string.search_section_albums),
                    tracksHeader = getString(R.string.search_section_tracks),
                    playlistsHeader = getString(R.string.search_section_playlists),
                    radiosHeader = getString(R.string.search_section_radio)
                )
                adapter.submitList(flatList)
            }
            else -> {
                // Default to empty state
                binding.loadingIndicator.visibility = View.GONE
                binding.searchResults.visibility = View.GONE
                binding.emptyState.visibility = View.VISIBLE
                binding.noResultsState.visibility = View.GONE
                binding.errorState.visibility = View.GONE
            }
        }
    }

    /**
     * Update filter chip checked states to match ViewModel state.
     *
     * This ensures chips stay in sync if filters are changed programmatically.
     */
    private fun updateFilterChipStates(activeFilters: Set<MaMediaType>) {
        // Temporarily remove listeners to avoid recursion
        binding.chipAlbums.setOnCheckedChangeListener(null)
        binding.chipArtists.setOnCheckedChangeListener(null)
        binding.chipTracks.setOnCheckedChangeListener(null)
        binding.chipPlaylists.setOnCheckedChangeListener(null)
        binding.chipRadio.setOnCheckedChangeListener(null)

        // Update checked states
        binding.chipAlbums.isChecked = activeFilters.contains(MaMediaType.ALBUM)
        binding.chipArtists.isChecked = activeFilters.contains(MaMediaType.ARTIST)
        binding.chipTracks.isChecked = activeFilters.contains(MaMediaType.TRACK)
        binding.chipPlaylists.isChecked = activeFilters.contains(MaMediaType.PLAYLIST)
        binding.chipRadio.isChecked = activeFilters.contains(MaMediaType.RADIO)

        // Re-attach listeners
        binding.chipAlbums.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFilter(MaMediaType.ALBUM, isChecked)
        }
        binding.chipArtists.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFilter(MaMediaType.ARTIST, isChecked)
        }
        binding.chipTracks.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFilter(MaMediaType.TRACK, isChecked)
        }
        binding.chipPlaylists.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFilter(MaMediaType.PLAYLIST, isChecked)
        }
        binding.chipRadio.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFilter(MaMediaType.RADIO, isChecked)
        }
    }

    /**
     * Handle click on a search result item.
     */
    private fun onItemClick(item: MaLibraryItem) {
        val uri = item.uri
        if (uri.isNullOrBlank()) {
            Log.w(TAG, "Item ${item.name} has no URI, cannot play")
            Toast.makeText(context, "Cannot play: no URI available", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Playing ${item.mediaType}: ${item.name} (uri=$uri)")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = MusicAssistantManager.playMedia(uri, item.mediaType.name.lowercase())
            result.fold(
                onSuccess = {
                    Log.d(TAG, "Playback started: ${item.name}")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to play ${item.name}", error)
                    Toast.makeText(
                        context,
                        "Failed to play: ${error.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
