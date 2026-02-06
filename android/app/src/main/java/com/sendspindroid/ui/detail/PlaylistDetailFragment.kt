package com.sendspindroid.ui.detail

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.sendspindroid.MainActivity
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Fragment host for the Playlist Detail Compose screen.
 *
 * Uses ComposeView to embed the Compose-based PlaylistDetailScreen inside
 * the existing Fragment-based navigation system.
 *
 * ## Arguments
 * - `playlistId`: String - Required. The MA playlist item_id to display.
 * - `playlistName`: String - Optional. Playlist name for toolbar title.
 *
 * ## Navigation
 * - Back navigation returns to the previous fragment (Playlists list)
 */
class PlaylistDetailFragment : Fragment() {

    private val playlistId: String by lazy {
        requireArguments().getString(ARG_PLAYLIST_ID)
            ?: throw IllegalArgumentException("playlistId is required")
    }

    private val viewModel: PlaylistDetailViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )

            setContent {
                SendSpinTheme {
                    PlaylistDetailScreen(
                        playlistId = playlistId,
                        onRemoveTrack = { position, trackName ->
                            handleRemoveTrack(position, trackName)
                        },
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    private fun handleRemoveTrack(position: Int, trackName: String) {
        val action = viewModel.removeTrack(position)
        if (action == null) {
            Log.w(TAG, "removeTrack returned null for position $position")
            return
        }
        (activity as? MainActivity)?.showUndoSnackbar(
            message = "Track removed",
            onUndo = { action.undoRemove() },
            onDismissed = { action.executeRemove() }
        )
    }

    override fun onResume() {
        super.onResume()
        val playlistName = arguments?.getString(ARG_PLAYLIST_NAME) ?: ""
        (activity as? MainActivity)?.updateToolbarForDetail(playlistName)
    }

    companion object {
        private const val TAG = "PlaylistDetailFragment"
        private const val ARG_PLAYLIST_ID = "playlistId"
        private const val ARG_PLAYLIST_NAME = "playlistName"

        /**
         * Create a new instance of PlaylistDetailFragment.
         *
         * @param playlistId The MA playlist item_id
         * @param playlistName The playlist name (for toolbar title, optional)
         */
        fun newInstance(playlistId: String, playlistName: String? = null): PlaylistDetailFragment {
            return PlaylistDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PLAYLIST_ID, playlistId)
                    playlistName?.let { putString(ARG_PLAYLIST_NAME, it) }
                }
            }
        }
    }
}
