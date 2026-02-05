package com.sendspindroid.ui.navigation

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sendspindroid.R
import com.sendspindroid.databinding.FragmentHomeBinding
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaRadio
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.ui.detail.AlbumDetailFragment
import com.sendspindroid.ui.detail.ArtistDetailFragment
import com.sendspindroid.ui.navigation.home.HomeViewModel
import com.sendspindroid.ui.navigation.home.HomeViewModel.SectionState
import com.sendspindroid.ui.navigation.home.LibraryItemAdapter
import kotlinx.coroutines.launch

/**
 * Home tab fragment displaying horizontal carousels:
 * - Recently Played
 * - Recently Added
 * - Albums
 * - Artists
 * - Playlists
 * - Radio Stations
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
    private lateinit var albumsAdapter: LibraryItemAdapter
    private lateinit var artistsAdapter: LibraryItemAdapter
    private lateinit var playlistsAdapter: LibraryItemAdapter
    private lateinit var radioAdapter: LibraryItemAdapter

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
     * Initialize horizontal RecyclerViews with unified adapters.
     *
     * All sections use LibraryItemAdapter which handles any MaLibraryItem type.
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

        // Albums
        albumsAdapter = LibraryItemAdapter { item ->
            onLibraryItemClick(item)
        }
        binding.albumsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = albumsAdapter
            isNestedScrollingEnabled = false
        }

        // Artists
        artistsAdapter = LibraryItemAdapter { item ->
            onLibraryItemClick(item)
        }
        binding.artistsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = artistsAdapter
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

        // Radio Stations
        radioAdapter = LibraryItemAdapter { item ->
            onLibraryItemClick(item)
        }
        binding.radioRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = radioAdapter
            isNestedScrollingEnabled = false
        }
    }

    /**
     * Observe ViewModel LiveData and update UI accordingly.
     *
     * All sections use the same updateSectionState method since
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

        // Albums
        viewModel.albums.observe(viewLifecycleOwner) { state ->
            updateSectionState(
                state = state,
                recyclerView = binding.albumsRecyclerView,
                loadingView = binding.albumsLoading,
                emptyView = binding.albumsEmpty,
                adapter = albumsAdapter
            )
        }

        // Artists
        viewModel.artists.observe(viewLifecycleOwner) { state ->
            updateSectionState(
                state = state,
                recyclerView = binding.artistsRecyclerView,
                loadingView = binding.artistsLoading,
                emptyView = binding.artistsEmpty,
                adapter = artistsAdapter
            )
        }

        // Playlists
        viewModel.playlists.observe(viewLifecycleOwner) { state ->
            updateSectionState(
                state = state,
                recyclerView = binding.playlistsRecyclerView,
                loadingView = binding.playlistsLoading,
                emptyView = binding.playlistsEmpty,
                adapter = playlistsAdapter
            )
        }

        // Radio Stations
        viewModel.radioStations.observe(viewLifecycleOwner) { state ->
            updateSectionState(
                state = state,
                recyclerView = binding.radioRecyclerView,
                loadingView = binding.radioLoading,
                emptyView = binding.radioEmpty,
                adapter = radioAdapter
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
     * Artists and albums navigate to detail screens.
     * Tracks, playlists, and radio stations start playback immediately.
     */
    private fun onLibraryItemClick(item: MaLibraryItem) {
        when (item) {
            // Navigate to detail screens for artists and albums
            is MaArtist -> {
                Log.d(TAG, "Navigating to artist detail: ${item.name}")
                navigateToArtistDetail(item)
            }
            is MaAlbum -> {
                Log.d(TAG, "Navigating to album detail: ${item.name}")
                navigateToAlbumDetail(item)
            }
            // Play other items immediately
            else -> {
                playItem(item)
            }
        }
    }

    /**
     * Navigate to artist detail screen.
     */
    private fun navigateToArtistDetail(artist: MaArtist) {
        val fragment = ArtistDetailFragment.newInstance(
            artistId = artist.artistId,
            artistName = artist.name
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.navFragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    /**
     * Navigate to album detail screen.
     */
    private fun navigateToAlbumDetail(album: MaAlbum) {
        val fragment = AlbumDetailFragment.newInstance(
            albumId = album.albumId,
            albumName = album.name
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.navFragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    /**
     * Play an item immediately.
     *
     * Used for tracks, playlists, and radio stations.
     */
    private fun playItem(item: MaLibraryItem) {
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
