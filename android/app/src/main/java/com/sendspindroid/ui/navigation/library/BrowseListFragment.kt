package com.sendspindroid.ui.navigation.library

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.sendspindroid.R
import com.sendspindroid.databinding.FragmentBrowseListBinding
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.ui.detail.AlbumDetailFragment
import com.sendspindroid.ui.detail.ArtistDetailFragment
import kotlinx.coroutines.launch

/**
 * Reusable fragment for browsing library content in a vertical list.
 *
 * Used by LibraryFragment's ViewPager2 to display each content type tab:
 * - Albums, Artists, Playlists, Tracks, Radio
 *
 * Features:
 * - Sort chips at top (filtered by content type)
 * - Pull-to-refresh
 * - Infinite scroll pagination
 * - Loading/empty/error states
 *
 * Shares LibraryViewModel with parent LibraryFragment via activityViewModels().
 */
class BrowseListFragment : Fragment() {

    companion object {
        private const val TAG = "BrowseListFragment"
        private const val ARG_CONTENT_TYPE = "content_type"

        /**
         * Create a new instance for a specific content type.
         *
         * @param contentType The content type this tab will display
         * @return New BrowseListFragment instance
         */
        fun newInstance(contentType: LibraryViewModel.ContentType): BrowseListFragment {
            return BrowseListFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_CONTENT_TYPE, contentType.ordinal)
                }
            }
        }
    }

    private var _binding: FragmentBrowseListBinding? = null
    private val binding get() = _binding!!

    // Share ViewModel with parent LibraryFragment
    private val viewModel: LibraryViewModel by activityViewModels()

    private lateinit var contentType: LibraryViewModel.ContentType
    private lateinit var adapter: LibraryRowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Get content type from arguments
        val ordinal = arguments?.getInt(ARG_CONTENT_TYPE, 0) ?: 0
        contentType = LibraryViewModel.ContentType.entries[ordinal]
        Log.d(TAG, "Created BrowseListFragment for $contentType")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowseListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSortChips()
        setupRecyclerView()
        setupSwipeRefresh()
        observeState()

        // Load initial data
        viewModel.loadItems(contentType)
    }

    /**
     * Set up sort option chips based on content type.
     *
     * Different content types have different sort options:
     * - Albums: Name, Date Added, Year
     * - Tracks: Name, Date Added
     * - Others: Name only
     */
    private fun setupSortChips() {
        val sortOptions = viewModel.getSortOptionsFor(contentType)
        val chipGroup = binding.sortChipGroup

        // Hide sort chips if only one option (just Name)
        if (sortOptions.size <= 1) {
            binding.sortScrollView.visibility = View.GONE
            return
        }

        // Create chips for each sort option
        sortOptions.forEach { sortOption ->
            val chip = Chip(requireContext()).apply {
                text = getSortLabel(sortOption)
                isCheckable = true
                isCheckedIconVisible = false
                chipBackgroundColor = context.getColorStateList(R.color.chip_background_color)
                setTextColor(context.getColorStateList(R.color.chip_text_color))

                // Mark current selection
                isChecked = sortOption == viewModel.getStateFor(contentType).value.sortOption
            }

            chip.setOnClickListener {
                viewModel.setSortOption(contentType, sortOption)
            }

            chipGroup.addView(chip)
        }
    }

    /**
     * Get display label for a sort option.
     */
    private fun getSortLabel(sort: LibraryViewModel.SortOption): String {
        return when (sort) {
            LibraryViewModel.SortOption.NAME -> getString(R.string.library_sort_name)
            LibraryViewModel.SortOption.DATE_ADDED -> getString(R.string.library_sort_date_added)
            LibraryViewModel.SortOption.YEAR -> getString(R.string.library_sort_year)
        }
    }

    /**
     * Set up RecyclerView with adapter and infinite scroll listener.
     */
    private fun setupRecyclerView() {
        adapter = LibraryRowAdapter { item ->
            onItemClick(item)
        }

        binding.browseList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@BrowseListFragment.adapter

            // Infinite scroll listener
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    // Only check when scrolling down
                    if (dy <= 0) return

                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItem = layoutManager.findFirstVisibleItemPosition()

                    // Load more when nearing the end (5 items from bottom)
                    if (visibleItemCount + firstVisibleItem + 5 >= totalItemCount) {
                        viewModel.loadMore(contentType)
                    }
                }
            })
        }
    }

    /**
     * Set up pull-to-refresh.
     */
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh(contentType)
        }

        // Use theme colors for refresh indicator
        binding.swipeRefresh.setColorSchemeResources(
            R.color.md_theme_light_primary
        )
    }

    /**
     * Observe ViewModel state and update UI.
     */
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getStateFor(contentType).collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    /**
     * Update UI based on current state.
     */
    private fun updateUI(state: LibraryViewModel.TabState) {
        // Stop refresh indicator
        binding.swipeRefresh.isRefreshing = false

        when {
            state.isLoading -> {
                // Show loading, hide everything else
                binding.loadingContainer.visibility = View.VISIBLE
                binding.browseList.visibility = View.GONE
                binding.emptyContainer.visibility = View.GONE
                binding.errorContainer.visibility = View.GONE
            }
            state.error != null && state.items.isEmpty() -> {
                // Show error (only if no items to show)
                binding.loadingContainer.visibility = View.GONE
                binding.browseList.visibility = View.GONE
                binding.emptyContainer.visibility = View.GONE
                binding.errorContainer.visibility = View.VISIBLE
                binding.errorText.text = state.error

                binding.retryButton.setOnClickListener {
                    viewModel.refresh(contentType)
                }
            }
            state.items.isEmpty() -> {
                // Show empty state
                binding.loadingContainer.visibility = View.GONE
                binding.browseList.visibility = View.GONE
                binding.emptyContainer.visibility = View.VISIBLE
                binding.errorContainer.visibility = View.GONE
            }
            else -> {
                // Show list
                binding.loadingContainer.visibility = View.GONE
                binding.browseList.visibility = View.VISIBLE
                binding.emptyContainer.visibility = View.GONE
                binding.errorContainer.visibility = View.GONE

                adapter.submitList(state.items)
            }
        }

        // Update sort chip selection
        updateSortChipSelection(state.sortOption)
    }

    /**
     * Update sort chip selection to match current state.
     */
    private fun updateSortChipSelection(currentSort: LibraryViewModel.SortOption) {
        val sortOptions = viewModel.getSortOptionsFor(contentType)
        val chipGroup = binding.sortChipGroup

        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            chip.isChecked = sortOptions.getOrNull(i) == currentSort
        }
    }

    /**
     * Handle click on a library item.
     *
     * Artists and albums navigate to detail screens.
     * Tracks, playlists, and radio stations start playback immediately.
     */
    private fun onItemClick(item: MaLibraryItem) {
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
