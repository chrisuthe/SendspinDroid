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
import com.sendspindroid.musicassistant.MaMediaItem
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.ui.navigation.home.HomeViewModel
import com.sendspindroid.ui.navigation.home.HomeViewModel.SectionState
import com.sendspindroid.ui.navigation.home.MediaCardAdapter
import com.sendspindroid.ui.navigation.home.PlaylistCardAdapter

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

    // Adapters for each section
    private lateinit var recentlyPlayedAdapter: MediaCardAdapter
    private lateinit var recentlyAddedAdapter: MediaCardAdapter
    private lateinit var playlistsAdapter: PlaylistCardAdapter

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
     * Initialize the three horizontal RecyclerViews with adapters and snap helpers.
     */
    private fun setupRecyclerViews() {
        // Recently Played
        recentlyPlayedAdapter = MediaCardAdapter { item ->
            onMediaItemClick(item)
        }
        binding.recentlyPlayedRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = recentlyPlayedAdapter
            // Disable nested scrolling for smooth behavior inside NestedScrollView
            isNestedScrollingEnabled = false
        }

        // Recently Added
        recentlyAddedAdapter = MediaCardAdapter { item ->
            onMediaItemClick(item)
        }
        binding.recentlyAddedRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = recentlyAddedAdapter
            isNestedScrollingEnabled = false
        }

        // Playlists
        playlistsAdapter = PlaylistCardAdapter { playlist ->
            onPlaylistClick(playlist)
        }
        binding.playlistsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = playlistsAdapter
            isNestedScrollingEnabled = false
        }
    }

    /**
     * Observe ViewModel LiveData and update UI accordingly.
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

        // Playlists
        viewModel.playlists.observe(viewLifecycleOwner) { state ->
            updatePlaylistsSectionState(state)
        }
    }

    /**
     * Update a media section's UI based on its state.
     */
    private fun updateSectionState(
        state: SectionState<MaMediaItem>,
        recyclerView: View,
        loadingView: View,
        emptyView: View,
        adapter: MediaCardAdapter
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
     * Update the playlists section UI (uses different adapter type).
     */
    private fun updatePlaylistsSectionState(state: SectionState<MaPlaylist>) {
        when (state) {
            is SectionState.Loading -> {
                binding.playlistsRecyclerView.visibility = View.GONE
                binding.playlistsLoading.visibility = View.VISIBLE
                binding.playlistsEmpty.visibility = View.GONE
            }
            is SectionState.Success -> {
                binding.playlistsLoading.visibility = View.GONE
                if (state.items.isEmpty()) {
                    binding.playlistsRecyclerView.visibility = View.GONE
                    binding.playlistsEmpty.visibility = View.VISIBLE
                } else {
                    binding.playlistsRecyclerView.visibility = View.VISIBLE
                    binding.playlistsEmpty.visibility = View.GONE
                    playlistsAdapter.submitList(state.items)
                }
            }
            is SectionState.Error -> {
                binding.playlistsLoading.visibility = View.GONE
                binding.playlistsRecyclerView.visibility = View.GONE
                binding.playlistsEmpty.visibility = View.VISIBLE
                Log.e(TAG, "Playlists error: ${state.message}")
            }
        }
    }

    /**
     * Handle click on a media item (track, album, etc.).
     * Future: Start playback or navigate to detail view.
     */
    private fun onMediaItemClick(item: MaMediaItem) {
        Log.d(TAG, "Media item clicked: ${item.name} (${item.mediaType})")
        // TODO: Implement playback or navigation
    }

    /**
     * Handle click on a playlist.
     * Future: Navigate to playlist detail view.
     */
    private fun onPlaylistClick(playlist: MaPlaylist) {
        Log.d(TAG, "Playlist clicked: ${playlist.name}")
        // TODO: Implement playlist navigation
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
