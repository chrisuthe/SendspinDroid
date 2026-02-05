package com.sendspindroid.ui.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Fragment host for the Album Detail Compose screen.
 *
 * Uses ComposeView to embed the Compose-based AlbumDetailScreen inside
 * the existing Fragment-based navigation system.
 *
 * ## Arguments
 * - `albumId`: String - Required. The MA album item_id to display.
 * - `albumName`: String - Optional. Album name for transition animations.
 *
 * ## Navigation
 * - Back navigation returns to the previous fragment
 * - Artist name taps navigate to ArtistDetailFragment (by searching for artist)
 */
class AlbumDetailFragment : Fragment() {

    private val albumId: String by lazy {
        requireArguments().getString(ARG_ALBUM_ID)
            ?: throw IllegalArgumentException("albumId is required")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            // Dispose composition when fragment view is destroyed
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )

            setContent {
                SendSpinTheme {
                    AlbumDetailScreen(
                        albumId = albumId,
                        onBackClick = {
                            parentFragmentManager.popBackStack()
                        },
                        onArtistClick = { artistName ->
                            // For now, search for artist and navigate
                            // In the future, we can look up artist ID from album data
                            // TODO: Get artistId from album data instead of search
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Artist: $artistName",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        }
    }

    companion object {
        private const val ARG_ALBUM_ID = "albumId"
        private const val ARG_ALBUM_NAME = "albumName"

        /**
         * Create a new instance of AlbumDetailFragment.
         *
         * @param albumId The MA album item_id
         * @param albumName The album name (for transition animations, optional)
         */
        fun newInstance(albumId: String, albumName: String? = null): AlbumDetailFragment {
            return AlbumDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ALBUM_ID, albumId)
                    albumName?.let { putString(ARG_ALBUM_NAME, it) }
                }
            }
        }
    }
}
