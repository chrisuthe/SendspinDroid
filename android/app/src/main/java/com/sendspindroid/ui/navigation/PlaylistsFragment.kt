package com.sendspindroid.ui.navigation

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
import com.sendspindroid.R
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.ui.detail.PlaylistDetailFragment
import com.sendspindroid.ui.navigation.playlists.PlaylistsScreen
import com.sendspindroid.ui.navigation.playlists.PlaylistsViewModel
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Playlists tab fragment.
 *
 * Displays a list of playlists with create/delete controls.
 * Tapping a playlist navigates to PlaylistDetailFragment.
 *
 * Uses Jetpack Compose for UI rendering via ComposeView.
 */
class PlaylistsFragment : Fragment() {

    companion object {
        private const val TAG = "PlaylistsFragment"

        fun newInstance() = PlaylistsFragment()
    }

    private val viewModel: PlaylistsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            setContent {
                SendSpinTheme {
                    PlaylistsScreen(
                        viewModel = viewModel,
                        onPlaylistClick = { playlist -> navigateToPlaylistDetail(playlist) },
                        onDeletePlaylist = { playlist -> handleDeletePlaylist(playlist) }
                    )
                }
            }
        }
    }

    private fun handleDeletePlaylist(playlist: MaPlaylist) {
        val action = viewModel.deletePlaylist(playlist.playlistId)
        if (action == null) {
            Log.w(TAG, "deletePlaylist returned null for ${playlist.playlistId}")
            return
        }
        (activity as? MainActivity)?.showUndoSnackbar(
            message = "Deleted ${action.playlistName}",
            onUndo = { action.undoDelete() },
            onDismissed = { action.executeDelete() }
        )
    }

    private fun navigateToPlaylistDetail(playlist: MaPlaylist) {
        Log.d(TAG, "Navigating to playlist detail: ${playlist.name}")
        val fragment = PlaylistDetailFragment.newInstance(
            playlistId = playlist.playlistId,
            playlistName = playlist.name
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.navFragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }
}
