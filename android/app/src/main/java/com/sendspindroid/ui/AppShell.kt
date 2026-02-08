package com.sendspindroid.ui

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sendspindroid.R
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.detail.components.BulkAddState
import com.sendspindroid.ui.detail.components.PlaylistPickerDialog
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.main.NavTab
import com.sendspindroid.ui.main.NowPlayingScreen
import com.sendspindroid.ui.main.components.MiniPlayer
import com.sendspindroid.ui.navigation.home.HomeScreen
import com.sendspindroid.ui.navigation.home.HomeViewModel
import com.sendspindroid.ui.navigation.library.LibraryScreen
import com.sendspindroid.ui.navigation.library.LibraryViewModel
import com.sendspindroid.ui.navigation.playlists.PlaylistsScreen
import com.sendspindroid.ui.navigation.playlists.PlaylistsViewModel
import com.sendspindroid.ui.navigation.search.SearchScreen
import com.sendspindroid.ui.navigation.search.SearchViewModel
import kotlinx.coroutines.launch

private const val TAG = "AppShell"

/**
 * Root Compose shell for the entire app.
 *
 * Manages the top-level state machine:
 * - ServerList/Error: shows server list
 * - Connecting/Connected/Reconnecting: shows connected shell with Now Playing + browse tabs
 *
 * This replaces `activity_main.xml` and all Fragment-based navigation.
 *
 * @param viewModel The main activity ViewModel (shared with MainActivity)
 * @param serverListContent Composable for the server list
 * @param onPreviousClick Playback: previous track
 * @param onPlayPauseClick Playback: play/pause toggle
 * @param onNextClick Playback: next track
 * @param onSwitchGroupClick Switch playback group
 * @param onFavoriteClick Toggle favorite on current track
 * @param onVolumeChange Volume slider callback (0-1 range)
 * @param onQueueClick Open queue view
 * @param onDisconnectClick Disconnect from server
 * @param onAddServerClick FAB: launch add server wizard
 * @param onAlbumClick Navigate to album detail
 * @param onArtistClick Navigate to artist detail
 * @param onPlaylistDetailClick Navigate to playlist detail
 * @param onShowSuccess Show success snackbar message
 * @param onShowError Show error snackbar message
 * @param onShowUndoSnackbar Show undo snackbar (for playlist deletion)
 */
@Composable
fun AppShell(
    viewModel: MainActivityViewModel,
    serverListContent: @Composable () -> Unit,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onQueueClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onAddServerClick: () -> Unit,
    onAlbumClick: (albumId: String, albumName: String) -> Unit,
    onArtistClick: (artistId: String, artistName: String) -> Unit,
    onPlaylistDetailClick: (playlistId: String, playlistName: String) -> Unit,
    onShowSuccess: (String) -> Unit,
    onShowError: (String) -> Unit,
    onShowUndoSnackbar: (message: String, onUndo: () -> Unit, onDismissed: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsState()

    when (connectionState) {
        is AppConnectionState.ServerList,
        is AppConnectionState.Error -> {
            ServerListShell(
                serverListContent = serverListContent,
                modifier = modifier
            )
        }

        is AppConnectionState.Connecting,
        is AppConnectionState.Connected,
        is AppConnectionState.Reconnecting -> {
            ConnectedShell(
                viewModel = viewModel,
                onPreviousClick = onPreviousClick,
                onPlayPauseClick = onPlayPauseClick,
                onNextClick = onNextClick,
                onSwitchGroupClick = onSwitchGroupClick,
                onFavoriteClick = onFavoriteClick,
                onVolumeChange = onVolumeChange,
                onQueueClick = onQueueClick,
                onDisconnectClick = onDisconnectClick,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                onPlaylistDetailClick = onPlaylistDetailClick,
                onShowSuccess = onShowSuccess,
                onShowError = onShowError,
                onShowUndoSnackbar = onShowUndoSnackbar,
                modifier = modifier
            )
        }
    }
}

/**
 * Shell for the server list (disconnected) state.
 * Shows toolbar + server list content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerListShell(
    serverListContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name)) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        )

        Box(modifier = Modifier.weight(1f)) {
            serverListContent()
        }
    }
}

/**
 * Shell for the connected state.
 *
 * Uses NavigationSuiteScaffold to auto-switch between BottomNav / NavigationRail / Drawer
 * based on window size class.
 *
 * State machine:
 * - No MA connection or no tab selected -> full Now Playing screen (no nav UI)
 * - MA connected + tab selected -> NavigationSuiteScaffold with browse content + mini player
 */
@Composable
private fun ConnectedShell(
    viewModel: MainActivityViewModel,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onQueueClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onAlbumClick: (albumId: String, albumName: String) -> Unit,
    onArtistClick: (artistId: String, artistName: String) -> Unit,
    onPlaylistDetailClick: (playlistId: String, playlistName: String) -> Unit,
    onShowSuccess: (String) -> Unit,
    onShowError: (String) -> Unit,
    onShowUndoSnackbar: (message: String, onUndo: () -> Unit, onDismissed: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val formFactor = LocalFormFactor.current
    val isMaConnected by viewModel.isMaConnected.collectAsState()

    // Which screen to show. null = Now Playing (default when connected)
    var selectedNavTab by remember { mutableStateOf<NavTab?>(null) }

    // All navigation items: Now Playing + browse tabs (browse tabs only if MA is connected)
    val browseNavTabs = remember {
        listOf(
            NavTab.HOME to Pair(R.drawable.ic_nav_home, R.string.nav_home),
            NavTab.SEARCH to Pair(R.drawable.ic_nav_search, R.string.nav_search),
            NavTab.LIBRARY to Pair(R.drawable.ic_nav_library, R.string.nav_library),
            NavTab.PLAYLISTS to Pair(R.drawable.ic_nav_playlists, R.string.nav_playlists)
        )
    }

    // Always show NavigationSuiteScaffold so user has navigation controls
    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteItems = {
            // Now Playing tab (always present)
            item(
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_audio_playing),
                        contentDescription = stringResource(R.string.now_playing)
                    )
                },
                label = { Text(stringResource(R.string.now_playing)) },
                selected = selectedNavTab == null,
                onClick = {
                    selectedNavTab = null
                    viewModel.setNavigationContentVisible(false)
                }
            )

            // Browse tabs (only when Music Assistant is connected)
            if (isMaConnected) {
                browseNavTabs.forEach { (tab, iconAndLabel) ->
                    val (iconRes, labelRes) = iconAndLabel
                    item(
                        icon = {
                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = stringResource(labelRes)
                            )
                        },
                        label = { Text(stringResource(labelRes)) },
                        selected = selectedNavTab == tab,
                        onClick = {
                            selectedNavTab = tab
                            viewModel.setCurrentNavTab(tab)
                            viewModel.setNavigationContentVisible(true)
                        }
                    )
                }
            }
        }
    ) {
        if (selectedNavTab == null) {
            // Now Playing (full screen within scaffold)
            NowPlayingScreen(
                viewModel = viewModel,
                onPreviousClick = onPreviousClick,
                onPlayPauseClick = onPlayPauseClick,
                onNextClick = onNextClick,
                onSwitchGroupClick = onSwitchGroupClick,
                onFavoriteClick = onFavoriteClick,
                onVolumeChange = onVolumeChange,
                onQueueClick = onQueueClick,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Browsing mode: browse content + mini player
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    BrowseContent(
                        selectedNavTab = selectedNavTab,
                        onAlbumClick = onAlbumClick,
                        onArtistClick = onArtistClick,
                        onPlaylistDetailClick = onPlaylistDetailClick,
                        onShowSuccess = onShowSuccess,
                        onShowError = onShowError,
                        onShowUndoSnackbar = onShowUndoSnackbar
                    )
                }

                // Mini player (phone/tablet only -- TV gets no mini player)
                if (AdaptiveDefaults.showMiniPlayer(formFactor)) {
                    MiniPlayerBar(
                        viewModel = viewModel,
                        onPlayPauseClick = onPlayPauseClick,
                        onVolumeChange = onVolumeChange,
                        onReturnToNowPlaying = {
                            selectedNavTab = null
                            viewModel.setNavigationContentVisible(false)
                        },
                        onDisconnectClick = onDisconnectClick
                    )
                }
            }
        }
    }
}

/**
 * Browse content for navigation tabs.
 *
 * Renders the actual Compose screens (HomeScreen, SearchScreen, LibraryScreen, PlaylistsScreen)
 * directly, replacing the Fragment wrappers. Handles shared concerns:
 * - Playing items via MusicAssistantManager
 * - Playlist picker dialog with bulk add support
 * - Navigation to detail screens via callbacks
 */
@Composable
private fun BrowseContent(
    selectedNavTab: NavTab?,
    onAlbumClick: (albumId: String, albumName: String) -> Unit,
    onArtistClick: (artistId: String, artistName: String) -> Unit,
    onPlaylistDetailClick: (playlistId: String, playlistName: String) -> Unit,
    onShowSuccess: (String) -> Unit,
    onShowError: (String) -> Unit,
    onShowUndoSnackbar: (message: String, onUndo: () -> Unit, onDismissed: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Shared playlist picker state
    var itemForPlaylist by remember { mutableStateOf<MaLibraryItem?>(null) }
    var bulkAddState by remember { mutableStateOf<BulkAddState?>(null) }
    var selectedPlaylist by remember { mutableStateOf<MaPlaylist?>(null) }

    // Shared action: play an item immediately
    val playItem: (MaLibraryItem) -> Unit = remember {
        { item ->
            val uri = item.uri
            if (uri.isNullOrBlank()) {
                Log.w(TAG, "Item ${item.name} has no URI, cannot play")
                Toast.makeText(context, "Cannot play: no URI available", Toast.LENGTH_SHORT).show()
            } else {
                Log.d(TAG, "Playing ${item.mediaType}: ${item.name} (uri=$uri)")
                scope.launch {
                    MusicAssistantManager.playMedia(uri, item.mediaType.name.lowercase()).fold(
                        onSuccess = { Log.d(TAG, "Playback started: ${item.name}") },
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
    }

    // Shared action: add to queue
    val addToQueue: (MaLibraryItem) -> Unit = remember {
        { item ->
            val uri = item.uri
            if (uri.isNullOrBlank()) {
                Log.w(TAG, "Item ${item.name} has no URI, cannot add to queue")
                Toast.makeText(context, "Cannot add to queue: no URI available", Toast.LENGTH_SHORT)
                    .show()
            } else {
                Log.d(TAG, "Adding to queue: ${item.name} (uri=$uri)")
                scope.launch {
                    MusicAssistantManager.playMedia(
                        uri,
                        item.mediaType.name.lowercase(),
                        enqueue = true
                    ).fold(
                        onSuccess = {
                            Log.d(TAG, "Added to queue: ${item.name}")
                            onShowSuccess("Added to queue: ${item.name}")
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Failed to add to queue: ${item.name}", error)
                            Toast.makeText(
                                context,
                                "Failed to add to queue: ${error.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        }
    }

    // Shared action: play next
    val playNext: (MaLibraryItem) -> Unit = remember {
        { item ->
            val uri = item.uri
            if (uri.isNullOrBlank()) {
                Log.w(TAG, "Item ${item.name} has no URI, cannot play next")
                Toast.makeText(context, "Cannot play next: no URI available", Toast.LENGTH_SHORT)
                    .show()
            } else {
                Log.d(TAG, "Play next: ${item.name} (uri=$uri)")
                scope.launch {
                    MusicAssistantManager.playMedia(
                        uri,
                        item.mediaType.name.lowercase(),
                        enqueueMode = MusicAssistantManager.EnqueueMode.NEXT
                    ).fold(
                        onSuccess = {
                            Log.d(TAG, "Playing next: ${item.name}")
                            onShowSuccess("Playing next: ${item.name}")
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Failed to play next: ${item.name}", error)
                            Toast.makeText(
                                context,
                                "Failed to play next: ${error.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        }
    }

    // Shared action: open playlist picker
    val addToPlaylist: (MaLibraryItem) -> Unit = remember {
        { item ->
            itemForPlaylist = item
            bulkAddState = null
            selectedPlaylist = null
        }
    }

    // Tab content
    when (selectedNavTab) {
        NavTab.HOME -> {
            val homeViewModel: HomeViewModel = viewModel()
            HomeScreen(
                viewModel = homeViewModel,
                onAlbumClick = { album ->
                    onAlbumClick(album.albumId, album.name)
                },
                onArtistClick = { artist ->
                    onArtistClick(artist.artistId, artist.name)
                },
                onItemClick = playItem
            )
        }

        NavTab.SEARCH -> {
            val searchViewModel: SearchViewModel = viewModel()
            SearchScreen(
                viewModel = searchViewModel,
                onItemClick = playItem,
                onAddToPlaylist = addToPlaylist,
                onAddToQueue = addToQueue,
                onPlayNext = playNext
            )
        }

        NavTab.LIBRARY -> {
            val libraryViewModel: LibraryViewModel = viewModel()
            LibraryScreen(
                viewModel = libraryViewModel,
                onAlbumClick = { album ->
                    onAlbumClick(album.albumId, album.name)
                },
                onArtistClick = { artist ->
                    onArtistClick(artist.artistId, artist.name)
                },
                onItemClick = playItem,
                onAddToPlaylist = addToPlaylist,
                onAddToQueue = addToQueue,
                onPlayNext = playNext
            )
        }

        NavTab.PLAYLISTS -> {
            val playlistsViewModel: PlaylistsViewModel = viewModel()
            PlaylistsScreen(
                viewModel = playlistsViewModel,
                onPlaylistClick = { playlist ->
                    onPlaylistDetailClick(playlist.playlistId, playlist.name)
                },
                onDeletePlaylist = { playlist ->
                    val action = playlistsViewModel.deletePlaylist(playlist.playlistId)
                    if (action != null) {
                        onShowUndoSnackbar(
                            "Deleted ${playlist.name}",
                            { action.undoDelete() },
                            { action.executeDelete() }
                        )
                    }
                }
            )
        }

        null -> {
            // Should not reach here -- Now Playing handled by parent
        }
    }

    // Playlist picker dialog (shared across all browse tabs)
    itemForPlaylist?.let { item ->
        PlaylistPickerDialog(
            onDismiss = {
                itemForPlaylist = null
                bulkAddState = null
                selectedPlaylist = null
            },
            onPlaylistSelected = { playlist ->
                when (item) {
                    is MaTrack -> {
                        itemForPlaylist = null
                        scope.launch {
                            addTrackToPlaylist(item, playlist, onShowSuccess, onShowError)
                        }
                    }
                    is MaAlbum -> {
                        selectedPlaylist = playlist
                        scope.launch {
                            bulkAddAlbum(
                                item.albumId,
                                item.name,
                                playlist,
                                onShowSuccess
                            ) { bulkAddState = it }
                        }
                    }
                    is MaArtist -> {
                        selectedPlaylist = playlist
                        scope.launch {
                            bulkAddArtist(
                                item.artistId,
                                item.name,
                                playlist,
                                onShowSuccess
                            ) { bulkAddState = it }
                        }
                    }
                    else -> {
                        itemForPlaylist = null
                    }
                }
            },
            operationState = bulkAddState,
            onRetry = {
                selectedPlaylist?.let { playlist ->
                    scope.launch {
                        when (item) {
                            is MaAlbum -> bulkAddAlbum(
                                item.albumId,
                                item.name,
                                playlist,
                                onShowSuccess
                            ) { bulkAddState = it }
                            is MaArtist -> bulkAddArtist(
                                item.artistId,
                                item.name,
                                playlist,
                                onShowSuccess
                            ) { bulkAddState = it }
                            else -> {}
                        }
                    }
                }
            }
        )
    }
}

/**
 * Add a single track to a playlist.
 */
private suspend fun addTrackToPlaylist(
    track: MaTrack,
    playlist: MaPlaylist,
    onShowSuccess: (String) -> Unit,
    onShowError: (String) -> Unit
) {
    val uri = track.uri ?: return
    Log.d(TAG, "Adding track '${track.name}' to playlist '${playlist.name}'")
    MusicAssistantManager.addPlaylistTracks(
        playlist.playlistId,
        listOf(uri)
    ).fold(
        onSuccess = {
            Log.d(TAG, "Track added to playlist")
            onShowSuccess("Added to ${playlist.name}")
        },
        onFailure = { error ->
            Log.e(TAG, "Failed to add track to playlist", error)
            onShowError("Failed to add track")
        }
    )
}

/**
 * Bulk add all album tracks to a playlist.
 */
private suspend fun bulkAddAlbum(
    albumId: String,
    albumName: String,
    playlist: MaPlaylist,
    onShowSuccess: (String) -> Unit,
    onStateChange: (BulkAddState) -> Unit
) {
    onStateChange(BulkAddState.Loading("Fetching tracks..."))

    MusicAssistantManager.getAlbumTracks(albumId).fold(
        onSuccess = { tracks ->
            val uris = tracks.mapNotNull { it.uri }
            if (uris.isEmpty()) {
                onStateChange(BulkAddState.Error("No tracks found"))
                return
            }

            onStateChange(BulkAddState.Loading("Adding ${uris.size} tracks..."))

            MusicAssistantManager.addPlaylistTracks(playlist.playlistId, uris).fold(
                onSuccess = {
                    val message = "Added $albumName to ${playlist.name}"
                    Log.d(TAG, message)
                    onStateChange(BulkAddState.Success(message))
                    onShowSuccess(message)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to add album to playlist", error)
                    onStateChange(BulkAddState.Error("Failed to add to playlist"))
                }
            )
        },
        onFailure = { error ->
            Log.e(TAG, "Failed to fetch album tracks", error)
            onStateChange(BulkAddState.Error("Failed to fetch tracks"))
        }
    )
}

/**
 * Bulk add all artist tracks to a playlist.
 */
private suspend fun bulkAddArtist(
    artistId: String,
    artistName: String,
    playlist: MaPlaylist,
    onShowSuccess: (String) -> Unit,
    onStateChange: (BulkAddState) -> Unit
) {
    onStateChange(BulkAddState.Loading("Fetching tracks..."))

    MusicAssistantManager.getArtistTracks(artistId).fold(
        onSuccess = { tracks ->
            val uris = tracks.mapNotNull { it.uri }
            if (uris.isEmpty()) {
                onStateChange(BulkAddState.Error("No tracks found"))
                return
            }

            onStateChange(BulkAddState.Loading("Adding ${uris.size} tracks..."))

            MusicAssistantManager.addPlaylistTracks(playlist.playlistId, uris).fold(
                onSuccess = {
                    val message = "Added $artistName to ${playlist.name}"
                    Log.d(TAG, message)
                    onStateChange(BulkAddState.Success(message))
                    onShowSuccess(message)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to add artist to playlist", error)
                    onStateChange(BulkAddState.Error("Failed to add to playlist"))
                }
            )
        },
        onFailure = { error ->
            Log.e(TAG, "Failed to fetch artist tracks", error)
            onStateChange(BulkAddState.Error("Failed to fetch tracks"))
        }
    )
}

/**
 * Animated mini player bar shown while browsing.
 * Appears when track metadata is available, hides when empty.
 */
@Composable
private fun MiniPlayerBar(
    viewModel: MainActivityViewModel,
    onPlayPauseClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onReturnToNowPlaying: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    val metadata by viewModel.metadata.collectAsState()
    val artworkSource by viewModel.artworkSource.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val volume by viewModel.volume.collectAsState()

    AnimatedVisibility(
        visible = !metadata.isEmpty,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        MiniPlayer(
            metadata = metadata,
            artworkSource = artworkSource,
            isPlaying = isPlaying,
            volume = volume,
            onCardClick = {
                Log.d(TAG, "Mini player tapped - returning to full player")
                onReturnToNowPlaying()
            },
            onStopClick = {
                Log.d(TAG, "Mini player: Stop/disconnect pressed")
                onDisconnectClick()
            },
            onPlayPauseClick = onPlayPauseClick,
            onVolumeChange = onVolumeChange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
