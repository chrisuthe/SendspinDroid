package com.sendspindroid.ui.navigation

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.sendspindroid.databinding.FragmentHomeBinding
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.ui.navigation.home.HomeViewModel
import com.sendspindroid.ui.navigation.home.HomeViewModel.SectionState
import com.sendspindroid.ui.navigation.home.LibraryItemAdapter

/**
 * Home tab fragment displaying three horizontal carousels:
 * - Recently Played
 * - Recently Added
 * - Playlists
 *
 * Uses ViewModel to manage data loading and survive configuration changes.
 * RecyclerViews use horizontal LinearLayoutManager with snap-to-item behavior.
 */
class HomeFragment : Fragment() {

    companion object {
        private const val TAG = "HomeFragment"

        fun newInstance() = HomeFragment()
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    // Unified adapters for all sections (using MaLibraryItem interface)
    private lateinit var recentlyPlayedAdapter: LibraryItemAdapter
    private lateinit var recentlyAddedAdapter: LibraryItemAdapter
    private lateinit var playlistsAdapter: LibraryItemAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerViews()
        observeViewModel()

        // Load data if not already loaded
        viewModel.loadHomeData()
    }

    /**
     * Initialize the three horizontal RecyclerViews with unified adapters.
     *
     * All sections now use LibraryItemAdapter which handles any MaLibraryItem type.
     * The adapter renders different subtitles based on the item's concrete type.
     */
    private fun setupRecyclerViews() {
        // Recently Played
        recentlyPlayedAdapter = LibraryItemAdapter { item ->
            onLibraryItemClick(item)
        }
        binding.recentlyPlayedRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = recentlyPlayedAdapter
            // Disable nested scrolling for smooth behavior inside NestedScrollView
            isNestedScrollingEnabled = false
        }

        // Recently Added
        recentlyAddedAdapter = LibraryItemAdapter { item ->
            onLibraryItemClick(item)
        }
        binding.recentlyAddedRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = recentlyAddedAdapter
            isNestedScrollingEnabled = false
        }

        // Playlists
        playlistsAdapter = LibraryItemAdapter { item ->
            onLibraryItemClick(item)
        }
        binding.playlistsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = playlistsAdapter
            isNestedScrollingEnabled = false
        }
    }

    /**
     * Observe ViewModel LiveData and update UI accordingly.
     *
     * All sections now use the same updateSectionState method since
     * they all work with MaLibraryItem through the unified adapter.
     */
    private fun observeViewModel() {
        // Recently Played
        viewModel.recentlyPlayed.observe(viewLifecycleOwner) { state ->
            updateSectionState(
                state = state,
                recyclerView = binding.recentlyPlayedRecyclerView,
                loadingView = binding.recentlyPlayedLoading,
                emptyView = binding.recentlyPlayedEmpty,
                adapter = recentlyPlayedAdapter
            )
        }

        // Recently Added
        viewModel.recentlyAdded.observe(viewLifecycleOwner) { state ->
            updateSectionState(
                state = state,
                recyclerView = binding.recentlyAddedRecyclerView,
                loadingView = binding.recentlyAddedLoading,
                emptyView = binding.recentlyAddedEmpty,
                adapter = recentlyAddedAdapter
            )
        }

        // Playlists - now uses the same unified method
        viewModel.playlists.observe(viewLifecycleOwner) { state ->
            updateSectionState(
                state = state,
                recyclerView = binding.playlistsRecyclerView,
                loadingView = binding.playlistsLoading,
                emptyView = binding.playlistsEmpty,
                adapter = playlistsAdapter
            )
        }
    }

    /**
     * Update a section's UI based on its state.
     *
     * Now handles all item types through the unified MaLibraryItem interface.
     */
    private fun updateSectionState(
        state: SectionState<MaLibraryItem>,
        recyclerView: View,
        loadingView: View,
        emptyView: View,
        adapter: LibraryItemAdapter
    ) {
        when (state) {
            is SectionState.Loading -> {
                recyclerView.visibility = View.GONE
                loadingView.visibility = View.VISIBLE
                emptyView.visibility = View.GONE
            }
            is SectionState.Success -> {
                loadingView.visibility = View.GONE
                if (state.items.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyView.visibility = View.GONE
                    adapter.submitList(state.items)
                }
            }
            is SectionState.Error -> {
                loadingView.visibility = View.GONE
                recyclerView.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
                Log.e(TAG, "Section error: ${state.message}")
            }
        }
    }

    /**
     * Handle click on any library item.
     *
     * Uses smart casting to determine the item type and take appropriate action.
     * Future: Start playback for tracks, navigate to detail for playlists/albums.
     */
    private fun onLibraryItemClick(item: MaLibraryItem) {
        when (item) {
            is MaTrack -> {
                Log.d(TAG, "Track clicked: ${item.name} by ${item.artist}")
                // TODO: Implement track playback
            }
            is MaPlaylist -> {
                Log.d(TAG, "Playlist clicked: ${item.name} (${item.trackCount} tracks)")
                // TODO: Implement playlist navigation
            }
            else -> {
                Log.d(TAG, "Library item clicked: ${item.name} (${item.mediaType})")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
