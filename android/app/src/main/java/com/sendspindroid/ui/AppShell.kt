package com.sendspindroid.ui

import android.content.res.Configuration
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sendspindroid.R
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.EnqueueMode
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.detail.components.BulkAddState
import com.sendspindroid.ui.detail.components.PlaylistPickerDialog
import com.sendspindroid.ui.detail.AlbumDetailScreen
import com.sendspindroid.ui.detail.ArtistDetailScreen
import com.sendspindroid.ui.detail.PlaylistDetailScreen
import com.sendspindroid.ui.detail.PlaylistDetailViewModel
import com.sendspindroid.ui.detail.PodcastDetailScreen
import com.sendspindroid.ui.main.DetailDestination
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.main.NavTab
import com.sendspindroid.ui.main.NowPlayingScreen
import com.sendspindroid.ui.main.components.MiniPlayer
import com.sendspindroid.ui.main.components.MiniPlayerSide
import com.sendspindroid.ui.navigation.home.HomeScreen
import com.sendspindroid.ui.navigation.home.HomeViewModel
import com.sendspindroid.ui.navigation.library.LibraryScreen
import com.sendspindroid.ui.navigation.library.LibraryViewModel
import com.sendspindroid.ui.navigation.playlists.PlaylistsScreen
import com.sendspindroid.ui.navigation.playlists.PlaylistsViewModel
import com.sendspindroid.ui.navigation.search.SearchScreen
import com.sendspindroid.ui.navigation.search.SearchViewModel
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.tvFocusable
import com.sendspindroid.ui.player.PlayerBottomSheet
import com.sendspindroid.ui.player.PlayerViewModel
import com.sendspindroid.ui.queue.QueueSheetContent
import com.sendspindroid.ui.queue.QueueViewModel
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
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEditServerClick: () -> Unit,
    onExitAppClick: () -> Unit,
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
                onStatsClick = onStatsClick,
                onSettingsClick = onSettingsClick,
                onEditServerClick = onEditServerClick,
                onExitAppClick = onExitAppClick,
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
 * Uses Scaffold (TopAppBar + overflow menu) nested inside NavigationSuiteScaffold
 * (auto-switches BottomNav / NavigationRail / Drawer based on window size class).
 *
 * Navigation tabs:
 * - Now Playing (always present, default)
 * - Home, Search, Library, Playlists (only when MA is connected)
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEditServerClick: () -> Unit,
    onExitAppClick: () -> Unit,
    onShowSuccess: (String) -> Unit,
    onShowError: (String) -> Unit,
    onShowUndoSnackbar: (message: String, onUndo: () -> Unit, onDismissed: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val formFactor = LocalFormFactor.current
    val isMaConnected by viewModel.isMaConnected.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    // Which screen to show. null = Now Playing, non-null = browse tab.
    // Starts as HOME when MA is connected, null otherwise.
    var selectedNavTab by remember { mutableStateOf<NavTab?>(if (isMaConnected) NavTab.HOME else null) }

    // M-22: React to MA connection state transitions via LaunchedEffect
    // (replaces composition-phase side effects that violated Compose rules)
    LaunchedEffect(isMaConnected) {
        if (isMaConnected) {
            // MA just connected -- switch to Home tab
            selectedNavTab = NavTab.HOME
            viewModel.setCurrentNavTab(NavTab.HOME)
            viewModel.setNavigationContentVisible(true)
        } else {
            // MA disconnected (or initial state) -- return to Now Playing
            selectedNavTab = null
            viewModel.setNavigationContentVisible(false)
        }
    }

    // Overflow menu state
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Browse queue sidebar visibility (tablet/TV)
    var browseQueueVisible by rememberSaveable { mutableStateOf(false) }

    // Now Playing queue sidebar visibility (tablet)
    var nowPlayingQueueVisible by rememberSaveable { mutableStateOf(true) }

    // Player / Speaker Group bottom sheet state
    var showPlayerSheet by remember { mutableStateOf(false) }
    // M-24: Always call viewModel() unconditionally (Compose rule: composable calls must not be conditional)
    val playerViewModel: PlayerViewModel = viewModel()

    // Detail navigation state
    val currentDetail by viewModel.currentDetail.collectAsState()

    val browseNavTabs = remember {
        listOf(
            NavTab.HOME to Pair(R.drawable.ic_nav_home, R.string.nav_home),
            NavTab.SEARCH to Pair(R.drawable.ic_nav_search, R.string.nav_search),
            NavTab.LIBRARY to Pair(R.drawable.ic_nav_library, R.string.nav_library),
            NavTab.PLAYLISTS to Pair(R.drawable.ic_nav_playlists, R.string.nav_playlists)
        )
    }

    // Detail navigation callbacks (push onto ViewModel back stack)
    val onAlbumClick: (String, String) -> Unit = { albumId, albumName ->
        viewModel.navigateToDetail(DetailDestination.Album(albumId, albumName))
    }
    val onArtistClick: (String, String) -> Unit = { artistId, artistName ->
        viewModel.navigateToDetail(DetailDestination.Artist(artistId, artistName))
    }
    val onPlaylistDetailClick: (String, String) -> Unit = { playlistId, playlistName ->
        viewModel.navigateToDetail(DetailDestination.Playlist(playlistId, playlistName))
    }
    val onPodcastDetailClick: (String, String, String?, String?, Int) -> Unit = { podcastId, podcastName, imageUri, publisher, totalEpisodes ->
        viewModel.navigateToDetail(DetailDestination.Podcast(podcastId, podcastName, imageUri, publisher, totalEpisodes))
    }

    // Server name for the toolbar subtitle
    val serverName = when (val state = connectionState) {
        is AppConnectionState.Connected -> state.serverName
        is AppConnectionState.Connecting -> state.serverName
        is AppConnectionState.Reconnecting -> state.serverName
        else -> null
    }

    // Title for the top bar -- detail title takes priority
    val topBarTitle = if (currentDetail != null) {
        currentDetail!!.title
    } else {
        when (selectedNavTab) {
            NavTab.HOME -> stringResource(R.string.nav_home)
            NavTab.SEARCH -> stringResource(R.string.nav_search)
            NavTab.LIBRARY -> stringResource(R.string.nav_library)
            NavTab.PLAYLISTS -> stringResource(R.string.nav_playlists)
            null -> stringResource(R.string.now_playing)
        }
    }

    // Shared top bar composable
    val topBar: @Composable () -> Unit = {
        TopAppBar(
            navigationIcon = {
                if (currentDetail != null) {
                    IconButton(onClick = { viewModel.navigateDetailBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            },
            title = {
                Column {
                    Text(
                        text = topBarTitle,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (currentDetail == null && serverName != null) {
                        Text(
                            text = serverName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            actions = {
                // Queue toggle button (tablet/TV)
                val isBrowsing = selectedNavTab != null || currentDetail != null
                val isNowPlaying = selectedNavTab == null && currentDetail == null
                val showQueueToggle = AdaptiveDefaults.showBrowseQueueSidebar(formFactor) &&
                    (isBrowsing || (isNowPlaying && isMaConnected))
                if (showQueueToggle) {
                    val queueModifier = if (formFactor == FormFactor.TV) {
                        Modifier.tvFocusable()
                    } else {
                        Modifier
                    }
                    val isQueueActive = if (isNowPlaying) nowPlayingQueueVisible else browseQueueVisible
                    IconButton(
                        onClick = {
                            if (isNowPlaying) {
                                nowPlayingQueueVisible = !nowPlayingQueueVisible
                            } else {
                                browseQueueVisible = !browseQueueVisible
                            }
                        },
                        modifier = queueModifier
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_queue_music),
                            contentDescription = stringResource(R.string.queue_view),
                            tint = if (isQueueActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                if (currentDetail == null) {
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.action_menu)
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_stats)) },
                                onClick = {
                                    showOverflowMenu = false
                                    onStatsClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_edit_server)) },
                                onClick = {
                                    showOverflowMenu = false
                                    onEditServerClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_switch_server)) },
                                onClick = {
                                    showOverflowMenu = false
                                    onDisconnectClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_app_settings)) },
                                onClick = {
                                    showOverflowMenu = false
                                    onSettingsClick()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_exit_app)) },
                                onClick = {
                                    showOverflowMenu = false
                                    onExitAppClick()
                                }
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        )
    }

    // QueueViewModel for tablet inline queue panel, TV queue sidebar, and browse queue sidebar
    // M-24: Always call viewModel() unconditionally; gate usage on the condition instead
    val queueViewModel: QueueViewModel = viewModel()
    val showQueueViewModel = (AdaptiveDefaults.showInlineQueuePanel(formFactor) ||
         AdaptiveDefaults.hasTvQueueSidebar(formFactor) ||
         AdaptiveDefaults.showBrowseQueueSidebar(formFactor)) && isMaConnected

    // Content composable shared between both layouts
    val contentArea: @Composable (PaddingValues) -> Unit = { innerPadding ->
        if (selectedNavTab == null && currentDetail == null) {
            // Now Playing (reached via mini player tap)
            NowPlayingScreen(
                viewModel = viewModel,
                onPreviousClick = onPreviousClick,
                onPlayPauseClick = onPlayPauseClick,
                onNextClick = onNextClick,
                onSwitchGroupClick = onSwitchGroupClick,
                onFavoriteClick = onFavoriteClick,
                onVolumeChange = onVolumeChange,
                onQueueClick = onQueueClick,
                queueViewModel = if (showQueueViewModel) queueViewModel else null,
                showPlayerButton = isMaConnected,
                onPlayerClick = { showPlayerSheet = true },
                onBrowseLibrary = {
                    viewModel.clearDetailNavigation()
                    selectedNavTab = NavTab.LIBRARY
                    viewModel.setCurrentNavTab(NavTab.LIBRARY)
                    viewModel.setNavigationContentVisible(true)
                },
                inlineQueueVisible = nowPlayingQueueVisible,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            // Browsing mode (with optional detail overlay): content + queue sidebar + mini player
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val useSideMiniPlayer = AdaptiveDefaults.showSideMiniPlayer(configuration.smallestScreenWidthDp, isLandscape)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    // Main browse/detail content
                    Box(modifier = Modifier.weight(1f)) {
                        if (currentDetail != null) {
                            DetailContent(
                                detail = currentDetail!!,
                                onAlbumClick = onAlbumClick,
                                onArtistClick = onArtistClick,
                                onShowSuccess = onShowSuccess,
                                onShowError = onShowError,
                                onShowUndoSnackbar = onShowUndoSnackbar
                            )
                        } else {
                            BrowseContent(
                                selectedNavTab = selectedNavTab,
                                onAlbumClick = onAlbumClick,
                                onArtistClick = onArtistClick,
                                onPlaylistDetailClick = onPlaylistDetailClick,
                                onPodcastDetailClick = onPodcastDetailClick,
                                onShowSuccess = onShowSuccess,
                                onShowError = onShowError,
                                onShowUndoSnackbar = onShowUndoSnackbar
                            )
                        }
                    }

                    // Queue sidebar (tablet/TV, toggleable)
                    if (AdaptiveDefaults.showBrowseQueueSidebar(formFactor) && showQueueViewModel) {
                        AnimatedVisibility(
                            visible = browseQueueVisible,
                            enter = slideInHorizontally { it },
                            exit = slideOutHorizontally { it }
                        ) {
                            Row(modifier = Modifier.fillMaxHeight()) {
                                VerticalDivider()
                                Box(
                                    modifier = Modifier
                                        .width(AdaptiveDefaults.browseQueueSidebarWidth(formFactor))
                                        .fillMaxHeight()
                                ) {
                                    val metadata by viewModel.metadata.collectAsState()
                                    QueueSheetContent(
                                        viewModel = queueViewModel,
                                        onBrowseLibrary = {
                                            // Already on a browse screen -- close the sidebar
                                            browseQueueVisible = false
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        currentTrackTitle = metadata.title
                                    )
                                }
                            }
                        }
                    }

                    // Side mini player (phone landscape only)
                    if (useSideMiniPlayer && AdaptiveDefaults.showMiniPlayer(formFactor)) {
                        SideMiniPlayerBar(
                            viewModel = viewModel,
                            onPlayPauseClick = onPlayPauseClick,
                            onReturnToNowPlaying = {
                                viewModel.clearDetailNavigation()
                                browseQueueVisible = false
                                selectedNavTab = null
                                viewModel.setNavigationContentVisible(false)
                            }
                        )
                    }
                }

                // Bottom mini player (portrait only -- not shown when side mini player is active)
                if (!useSideMiniPlayer && AdaptiveDefaults.showMiniPlayer(formFactor)) {
                    MiniPlayerBar(
                        viewModel = viewModel,
                        onPlayPauseClick = onPlayPauseClick,
                        onPreviousClick = onPreviousClick,
                        onNextClick = onNextClick,
                        onReturnToNowPlaying = {
                            viewModel.clearDetailNavigation()
                            browseQueueVisible = false
                            selectedNavTab = null
                            viewModel.setNavigationContentVisible(false)
                        }
                    )
                }
            }

            // Close queue sidebar on Back press (TV)
            if (browseQueueVisible && formFactor == FormFactor.TV) {
                BackHandler { browseQueueVisible = false }
            }
        }
    }

    if (!isMaConnected) {
        // No MA -> just Scaffold with top bar, no bottom nav
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            content = contentArea
        )
    } else {
        // MA connected -> NavigationSuiteScaffold with browse tabs + Now Playing
        // Force NavigationRail on phone landscape (auto-detect sometimes stays on BottomNav)
        val configuration = LocalConfiguration.current
        val isPhoneLandscape = configuration.smallestScreenWidthDp < 600 &&
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val navSuiteType = if (isPhoneLandscape) {
            NavigationSuiteType.NavigationRail
        } else {
            NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
        }

        NavigationSuiteScaffold(
            modifier = modifier,
            layoutType = navSuiteType,
            navigationSuiteItems = {
                // Now Playing tab (replaces mini player on TV; not needed on phone/tablet
                // since the mini player handles returning to the now playing screen)
                if (!AdaptiveDefaults.showMiniPlayer(formFactor)) {
                    item(
                        icon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nav_now_playing),
                                contentDescription = stringResource(R.string.nav_now_playing)
                            )
                        },
                        label = { Text(stringResource(R.string.nav_now_playing)) },
                        selected = selectedNavTab == null && currentDetail == null,
                        onClick = {
                            viewModel.clearDetailNavigation()
                            browseQueueVisible = false
                            selectedNavTab = null
                            viewModel.setNavigationContentVisible(false)
                        }
                    )
                }
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
                            viewModel.clearDetailNavigation()
                            selectedNavTab = tab
                            viewModel.setCurrentNavTab(tab)
                            viewModel.setNavigationContentVisible(true)
                        }
                    )
                }
            }
        ) {
            Scaffold(
                topBar = topBar,
                content = contentArea
            )
        }
    }

    // Player / Speaker Group bottom sheet
    if (showPlayerSheet && isMaConnected) {
        PlayerBottomSheet(
            viewModel = playerViewModel,
            onDismiss = { showPlayerSheet = false }
        )
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
    onPodcastDetailClick: (podcastId: String, podcastName: String, imageUri: String?, publisher: String?, totalEpisodes: Int) -> Unit,
    onShowSuccess: (String) -> Unit,
    onShowError: (String) -> Unit,
    onShowUndoSnackbar: (message: String, onUndo: () -> Unit, onDismissed: () -> Unit) -> Unit
) {
    // M-36: Use rememberUpdatedState so remembered lambdas always see the current context
    // after configuration changes (e.g., rotation), not a stale captured reference.
    val currentContext by rememberUpdatedState(LocalContext.current)
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
                Toast.makeText(currentContext, currentContext.getString(R.string.error_no_uri_play), Toast.LENGTH_SHORT).show()
            } else {
                Log.d(TAG, "Playing ${item.mediaType}: ${item.name} (uri=$uri)")
                scope.launch {
                    MusicAssistantManager.playMedia(uri, item.mediaType.name.lowercase()).fold(
                        onSuccess = { Log.d(TAG, "Playback started: ${item.name}") },
                        onFailure = { error ->
                            Log.e(TAG, "Failed to play ${item.name}", error)
                            Toast.makeText(
                                currentContext,
                                currentContext.getString(R.string.error_play_failed, error.message ?: ""),
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
                Toast.makeText(currentContext, currentContext.getString(R.string.error_no_uri_queue), Toast.LENGTH_SHORT)
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
                            onShowSuccess(currentContext.getString(R.string.success_added_to_queue, item.name))
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Failed to add to queue: ${item.name}", error)
                            Toast.makeText(
                                currentContext,
                                currentContext.getString(R.string.error_queue_failed, error.message ?: ""),
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
                Toast.makeText(currentContext, currentContext.getString(R.string.error_no_uri_play_next), Toast.LENGTH_SHORT)
                    .show()
            } else {
                Log.d(TAG, "Play next: ${item.name} (uri=$uri)")
                scope.launch {
                    MusicAssistantManager.playMedia(
                        uri,
                        item.mediaType.name.lowercase(),
                        enqueueMode = EnqueueMode.NEXT
                    ).fold(
                        onSuccess = {
                            Log.d(TAG, "Playing next: ${item.name}")
                            onShowSuccess(currentContext.getString(R.string.success_playing_next, item.name))
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Failed to play next: ${item.name}", error)
                            Toast.makeText(
                                currentContext,
                                currentContext.getString(R.string.error_play_next_failed, error.message ?: ""),
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
                onAlbumClick = { album ->
                    onAlbumClick(album.albumId, album.name)
                },
                onArtistClick = { artist ->
                    onArtistClick(artist.artistId, artist.name)
                },
                onPodcastClick = { podcast ->
                    onPodcastDetailClick(podcast.podcastId, podcast.name, podcast.imageUri, podcast.publisher, podcast.totalEpisodes)
                },
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
                onPodcastClick = { podcast ->
                    onPodcastDetailClick(podcast.podcastId, podcast.name, podcast.imageUri, podcast.publisher, podcast.totalEpisodes)
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
                            currentContext.getString(R.string.snackbar_deleted, playlist.name),
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
 * Detail content for album, artist, and playlist screens.
 *
 * Renders the Compose detail screen directly inside AppShell's content area,
 * so it sits between the top bar and bottom nav/mini player.
 * Handles playlist picker dialogs and all navigation callbacks.
 */
@Composable
private fun DetailContent(
    detail: DetailDestination,
    onAlbumClick: (albumId: String, albumName: String) -> Unit,
    onArtistClick: (artistId: String, artistName: String) -> Unit,
    onShowSuccess: (String) -> Unit,
    onShowError: (String) -> Unit,
    onShowUndoSnackbar: (message: String, onUndo: () -> Unit, onDismissed: () -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    val detailContext = LocalContext.current

    // Shared playlist picker state for detail screens
    var trackForPlaylist by remember { mutableStateOf<MaTrack?>(null) }
    var bulkAddTarget by remember { mutableStateOf<Any?>(null) } // albumId:String or MaAlbum or "artist"
    var bulkAddState by remember { mutableStateOf<BulkAddState?>(null) }
    var selectedPlaylist by remember { mutableStateOf<MaPlaylist?>(null) }

    when (detail) {
        is DetailDestination.Album -> {
            AlbumDetailScreen(
                albumId = detail.albumId,
                onArtistClick = { artistName ->
                    // TODO: Look up artistId by name; for now show toast
                    Log.d(TAG, "Artist click from album detail: $artistName")
                },
                onAddToPlaylist = { track ->
                    trackForPlaylist = track
                },
                onAddAlbumToPlaylist = {
                    bulkAddTarget = detail.albumId
                    bulkAddState = null
                    selectedPlaylist = null
                }
            )
        }

        is DetailDestination.Artist -> {
            ArtistDetailScreen(
                artistId = detail.artistId,
                onAlbumClick = { album ->
                    onAlbumClick(album.albumId, album.name)
                },
                onAddToPlaylist = { track ->
                    trackForPlaylist = track
                },
                onAddArtistToPlaylist = {
                    bulkAddTarget = "artist"
                    bulkAddState = null
                    selectedPlaylist = null
                },
                onAddAlbumToPlaylist = { album ->
                    bulkAddTarget = album
                    bulkAddState = null
                    selectedPlaylist = null
                }
            )
        }

        is DetailDestination.Playlist -> {
            val playlistViewModel: PlaylistDetailViewModel = viewModel()
            PlaylistDetailScreen(
                playlistId = detail.playlistId,
                onTrackRemoved = { action ->
                    onShowUndoSnackbar(
                        detailContext.getString(R.string.snackbar_track_removed),
                        { action.undoRemove() },
                        { action.executeRemove() }
                    )
                },
                viewModel = playlistViewModel
            )
        }

        is DetailDestination.Podcast -> {
            PodcastDetailScreen(
                podcastId = detail.podcastId,
                podcastName = detail.podcastName,
                podcastImageUri = detail.podcastImageUri,
                podcastPublisher = detail.podcastPublisher,
                totalEpisodes = detail.totalEpisodes
            )
        }
    }

    // Single track add dialog
    trackForPlaylist?.let { track ->
        PlaylistPickerDialog(
            onDismiss = { trackForPlaylist = null },
            onPlaylistSelected = { playlist ->
                trackForPlaylist = null
                scope.launch {
                    addTrackToPlaylist(track, playlist, onShowSuccess, onShowError)
                }
            }
        )
    }

    // Bulk add dialog (album or artist)
    if (bulkAddTarget != null) {
        PlaylistPickerDialog(
            onDismiss = {
                bulkAddTarget = null
                bulkAddState = null
                selectedPlaylist = null
            },
            onPlaylistSelected = { playlist ->
                selectedPlaylist = playlist
                scope.launch {
                    when (val target = bulkAddTarget) {
                        is String -> {
                            if (target == "artist" && detail is DetailDestination.Artist) {
                                bulkAddArtist(
                                    detail.artistId,
                                    detail.artistName,
                                    playlist,
                                    onShowSuccess
                                ) { bulkAddState = it }
                            } else {
                                // target is albumId string (from album detail)
                                val albumName = if (detail is DetailDestination.Album) detail.albumName else ""
                                bulkAddAlbum(
                                    target,
                                    albumName,
                                    playlist,
                                    onShowSuccess
                                ) { bulkAddState = it }
                            }
                        }
                        is MaAlbum -> {
                            bulkAddAlbum(
                                target.albumId,
                                target.name,
                                playlist,
                                onShowSuccess
                            ) { bulkAddState = it }
                        }
                    }
                }
            },
            operationState = bulkAddState,
            onRetry = {
                selectedPlaylist?.let { playlist ->
                    scope.launch {
                        when (val target = bulkAddTarget) {
                            is String -> {
                                if (target == "artist" && detail is DetailDestination.Artist) {
                                    bulkAddArtist(
                                        detail.artistId,
                                        detail.artistName,
                                        playlist,
                                        onShowSuccess
                                    ) { bulkAddState = it }
                                } else {
                                    val albumName = if (detail is DetailDestination.Album) detail.albumName else ""
                                    bulkAddAlbum(
                                        target,
                                        albumName,
                                        playlist,
                                        onShowSuccess
                                    ) { bulkAddState = it }
                                }
                            }
                            is MaAlbum -> {
                                bulkAddAlbum(
                                    target.albumId,
                                    target.name,
                                    playlist,
                                    onShowSuccess
                                ) { bulkAddState = it }
                            }
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
    onShowError: (String) -> Unit,
    context: android.content.Context? = null
) {
    val uri = track.uri ?: return
    Log.d(TAG, "Adding track '${track.name}' to playlist '${playlist.name}'")
    MusicAssistantManager.addPlaylistTracks(
        playlist.playlistId,
        listOf(uri)
    ).fold(
        onSuccess = {
            Log.d(TAG, "Track added to playlist")
            onShowSuccess(context?.getString(R.string.success_added_to_playlist, playlist.name) ?: "Added to ${playlist.name}")
        },
        onFailure = { error ->
            Log.e(TAG, "Failed to add track to playlist", error)
            onShowError(context?.getString(R.string.error_add_track_failed) ?: "Failed to add track")
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
    context: android.content.Context? = null,
    onStateChange: (BulkAddState) -> Unit,
) {
    onStateChange(BulkAddState.Loading(context?.getString(R.string.bulk_fetching_tracks) ?: "Fetching tracks..."))

    MusicAssistantManager.getAlbumTracks(albumId).fold(
        onSuccess = { tracks ->
            val uris = tracks.mapNotNull { it.uri }
            if (uris.isEmpty()) {
                onStateChange(BulkAddState.Error(context?.getString(R.string.bulk_no_tracks_found) ?: "No tracks found"))
                return
            }

            onStateChange(BulkAddState.Loading(context?.getString(R.string.bulk_adding_tracks, uris.size) ?: "Adding ${uris.size} tracks..."))

            MusicAssistantManager.addPlaylistTracks(playlist.playlistId, uris).fold(
                onSuccess = {
                    val message = context?.getString(R.string.bulk_added_to_playlist, albumName, playlist.name) ?: "Added $albumName to ${playlist.name}"
                    Log.d(TAG, message)
                    onStateChange(BulkAddState.Success(message))
                    onShowSuccess(message)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to add album to playlist", error)
                    onStateChange(BulkAddState.Error(context?.getString(R.string.bulk_failed_add_playlist) ?: "Failed to add to playlist"))
                }
            )
        },
        onFailure = { error ->
            Log.e(TAG, "Failed to fetch album tracks", error)
            onStateChange(BulkAddState.Error(context?.getString(R.string.bulk_failed_fetch_tracks) ?: "Failed to fetch tracks"))
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
    context: android.content.Context? = null,
    onStateChange: (BulkAddState) -> Unit,
) {
    onStateChange(BulkAddState.Loading(context?.getString(R.string.bulk_fetching_tracks) ?: "Fetching tracks..."))

    MusicAssistantManager.getArtistTracks(artistId).fold(
        onSuccess = { tracks ->
            val uris = tracks.mapNotNull { it.uri }
            if (uris.isEmpty()) {
                onStateChange(BulkAddState.Error(context?.getString(R.string.bulk_no_tracks_found) ?: "No tracks found"))
                return
            }

            onStateChange(BulkAddState.Loading(context?.getString(R.string.bulk_adding_tracks, uris.size) ?: "Adding ${uris.size} tracks..."))

            MusicAssistantManager.addPlaylistTracks(playlist.playlistId, uris).fold(
                onSuccess = {
                    val message = context?.getString(R.string.bulk_added_to_playlist, artistName, playlist.name) ?: "Added $artistName to ${playlist.name}"
                    Log.d(TAG, message)
                    onStateChange(BulkAddState.Success(message))
                    onShowSuccess(message)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to add artist to playlist", error)
                    onStateChange(BulkAddState.Error(context?.getString(R.string.bulk_failed_add_playlist) ?: "Failed to add to playlist"))
                }
            )
        },
        onFailure = { error ->
            Log.e(TAG, "Failed to fetch artist tracks", error)
            onStateChange(BulkAddState.Error(context?.getString(R.string.bulk_failed_fetch_tracks) ?: "Failed to fetch tracks"))
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
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onReturnToNowPlaying: () -> Unit
) {
    val metadata by viewModel.metadata.collectAsState()
    val artworkSource by viewModel.artworkSource.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val positionUpdatedAt by viewModel.positionUpdatedAt.collectAsState()

    AnimatedVisibility(
        visible = !metadata.isEmpty,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        MiniPlayer(
            metadata = metadata,
            artworkSource = artworkSource,
            isPlaying = isPlaying,
            onCardClick = {
                Log.d(TAG, "Mini player tapped - returning to full player")
                onReturnToNowPlaying()
            },
            onPlayPauseClick = onPlayPauseClick,
            onPreviousClick = onPreviousClick,
            onNextClick = onNextClick,
            positionMs = positionMs,
            durationMs = durationMs,
            positionUpdatedAt = positionUpdatedAt,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Animated side mini player for phone landscape.
 * Slides in from the right, no volume slider.
 */
@Composable
private fun SideMiniPlayerBar(
    viewModel: MainActivityViewModel,
    onPlayPauseClick: () -> Unit,
    onReturnToNowPlaying: () -> Unit
) {
    val metadata by viewModel.metadata.collectAsState()
    val artworkSource by viewModel.artworkSource.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val positionUpdatedAt by viewModel.positionUpdatedAt.collectAsState()

    AnimatedVisibility(
        visible = !metadata.isEmpty,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
    ) {
        Row(modifier = Modifier.fillMaxHeight()) {
            VerticalDivider()
            MiniPlayerSide(
                metadata = metadata,
                artworkSource = artworkSource,
                isPlaying = isPlaying,
                onCardClick = {
                    Log.d(TAG, "Side mini player tapped - returning to full player")
                    onReturnToNowPlaying()
                },
                onPlayPauseClick = onPlayPauseClick,
                positionMs = positionMs,
                durationMs = durationMs,
                positionUpdatedAt = positionUpdatedAt,
                modifier = Modifier.width(AdaptiveDefaults.sideMiniPlayerWidth())
            )
        }
    }
}
