package com.sendspindroid.ui.navigation.library

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * FragmentStateAdapter for the Library screen's ViewPager2.
 *
 * Creates BrowseListFragment instances for each content type tab:
 * - Albums (position 0)
 * - Artists (position 1)
 * - Playlists (position 2)
 * - Tracks (position 3)
 * - Radio (position 4)
 *
 * Uses FragmentStateAdapter for proper fragment lifecycle management
 * and state restoration across configuration changes.
 *
 * @param fragment The parent LibraryFragment hosting this adapter
 */
class LibraryPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    companion object {
        const val TAB_COUNT = 5
    }

    override fun getItemCount(): Int = TAB_COUNT

    override fun createFragment(position: Int): Fragment {
        val contentType = LibraryViewModel.ContentType.entries[position]
        return BrowseListFragment.newInstance(contentType)
    }
}
