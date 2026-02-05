package com.sendspindroid.ui.navigation

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.sendspindroid.R
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.ui.detail.AlbumDetailFragment
import com.sendspindroid.ui.detail.ArtistDetailFragment
import com.sendspindroid.ui.navigation.library.LibraryScreen
import com.sendspindroid.ui.navigation.library.LibraryViewModel
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.launch

/**
 * Library tab fragment displaying tabbed content browser.
 *
 * Provides full library browsing with:
 * - Tabs (Albums, Artists, Playlists, Tracks, Radio)
 * - HorizontalPager for swipeable content
 * - Shared LibraryViewModel for state management across tabs
 *
 * Each tab displays a vertical scrolling list with:
 * - Sort options (where applicable)
 * - Pull-to-refresh
 * - Infinite scroll pagination
 *
 * Uses Jetpack Compose for UI rendering via ComposeView.
 */
class LibraryFragment : Fragment() {

    companion object {
        private const val TAG = "LibraryFragment"

        fun newInstance() = LibraryFragment()
    }

    private val viewModel: LibraryViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            // Dispose composition when fragment's view is destroyed
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            setContent {
                SendSpinTheme {
                    LibraryScreen(
                        viewModel = viewModel,
                        onAlbumClick = { album -> navigateToAlbumDetail(album) },
                        onArtistClick = { artist -> navigateToArtistDetail(artist) },
                        onItemClick = { item -> playItem(item) }
                    )
                }
            }
        }
    }

    /**
     * Navigate to artist detail screen.
     */
    private fun navigateToArtistDetail(artist: MaArtist) {
        Log.d(TAG, "Navigating to artist detail: ${artist.name}")
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
        Log.d(TAG, "Navigating to album detail: ${album.name}")
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
}
