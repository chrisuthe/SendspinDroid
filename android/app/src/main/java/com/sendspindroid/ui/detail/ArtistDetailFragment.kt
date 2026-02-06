package com.sendspindroid.ui.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.sendspindroid.MainActivity
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Fragment host for the Artist Detail Compose screen.
 *
 * Uses ComposeView to embed the Compose-based ArtistDetailScreen inside
 * the existing Fragment-based navigation system. This allows incremental
 * migration to Compose while reusing existing navigation patterns.
 *
 * ## Arguments
 * - `artistId`: String - Required. The MA artist item_id to display.
 *
 * ## Navigation
 * - Back navigation returns to the previous fragment
 * - Album taps navigate to AlbumDetailFragment
 */
class ArtistDetailFragment : Fragment() {

    private val artistId: String by lazy {
        requireArguments().getString(ARG_ARTIST_ID)
            ?: throw IllegalArgumentException("artistId is required")
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
                    ArtistDetailScreen(
                        artistId = artistId,
                        onAlbumClick = { album ->
                            // Navigate to album detail
                            parentFragmentManager.beginTransaction()
                                .replace(
                                    id,
                                    AlbumDetailFragment.newInstance(
                                        albumId = album.albumId,
                                        albumName = album.name
                                    )
                                )
                                .addToBackStack(null)
                                .commit()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Update the Activity toolbar with artist name and back button
        val artistName = arguments?.getString(ARG_ARTIST_NAME) ?: ""
        (activity as? MainActivity)?.updateToolbarForDetail(artistName)
    }

    companion object {
        private const val ARG_ARTIST_ID = "artistId"
        private const val ARG_ARTIST_NAME = "artistName"

        /**
         * Create a new instance of ArtistDetailFragment.
         *
         * @param artistId The MA artist item_id
         * @param artistName The artist name (for transition animations, optional)
         */
        fun newInstance(artistId: String, artistName: String? = null): ArtistDetailFragment {
            return ArtistDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ARTIST_ID, artistId)
                    artistName?.let { putString(ARG_ARTIST_NAME, it) }
                }
            }
        }
    }
}
