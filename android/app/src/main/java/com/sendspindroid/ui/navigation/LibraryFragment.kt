package com.sendspindroid.ui.navigation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayoutMediator
import com.sendspindroid.R
import com.sendspindroid.databinding.FragmentLibraryBinding
import com.sendspindroid.ui.navigation.library.LibraryPagerAdapter
import com.sendspindroid.ui.navigation.library.LibraryViewModel

/**
 * Library tab fragment displaying tabbed content browser.
 *
 * Provides full library browsing with:
 * - TabLayout with scrollable tabs (Albums, Artists, Playlists, Tracks, Radio)
 * - ViewPager2 hosting BrowseListFragment for each tab
 * - Shared LibraryViewModel for state management across tabs
 *
 * Each tab displays a vertical scrolling list with:
 * - Sort options (where applicable)
 * - Pull-to-refresh
 * - Infinite scroll pagination
 */
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private var pagerAdapter: LibraryPagerAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewPager()
        setupTabs()
    }

    /**
     * Set up ViewPager2 with the pager adapter.
     */
    private fun setupViewPager() {
        pagerAdapter = LibraryPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter

        // Disable swipe between tabs if desired (uncomment below)
        // binding.viewPager.isUserInputEnabled = false
    }

    /**
     * Set up TabLayout and connect to ViewPager2.
     */
    private fun setupTabs() {
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = getTabTitle(position)
        }.attach()
    }

    /**
     * Get the display title for a tab position.
     */
    private fun getTabTitle(position: Int): String {
        return when (LibraryViewModel.ContentType.entries[position]) {
            LibraryViewModel.ContentType.ALBUMS -> getString(R.string.library_tab_albums)
            LibraryViewModel.ContentType.ARTISTS -> getString(R.string.library_tab_artists)
            LibraryViewModel.ContentType.PLAYLISTS -> getString(R.string.library_tab_playlists)
            LibraryViewModel.ContentType.TRACKS -> getString(R.string.library_tab_tracks)
            LibraryViewModel.ContentType.RADIO -> getString(R.string.library_tab_radio)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clear adapter to prevent memory leaks
        binding.viewPager.adapter = null
        pagerAdapter = null
        _binding = null
    }

    companion object {
        fun newInstance() = LibraryFragment()
    }
}
