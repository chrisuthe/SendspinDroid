package com.sendspindroid.ui

import android.util.Log
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.sendspindroid.R
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.main.NavTab
import com.sendspindroid.ui.main.NowPlayingScreen
import com.sendspindroid.ui.main.components.MiniPlayer

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
 * @param serverListContent Composable for the server list (will be native Compose in Phase 1D)
 * @param onPreviousClick Playback: previous track
 * @param onPlayPauseClick Playback: play/pause toggle
 * @param onNextClick Playback: next track
 * @param onSwitchGroupClick Switch playback group
 * @param onFavoriteClick Toggle favorite on current track
 * @param onVolumeChange Volume slider callback (0-1 range)
 * @param onQueueClick Open queue view
 * @param onDisconnectClick Disconnect from server
 * @param onAddServerClick FAB: launch add server wizard
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
 *
 * The browsing tabs (Home, Search, Library, Playlists) currently show placeholders.
 * They will be wired to actual Compose screens when Fragments are eliminated (Phase 1F).
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
    modifier: Modifier = Modifier
) {
    val formFactor = LocalFormFactor.current
    val isMaConnected by viewModel.isMaConnected.collectAsState()

    // null = Now Playing is the active screen (no browse tab selected)
    var selectedNavTab by remember { mutableStateOf<NavTab?>(null) }

    val navTabs = remember {
        listOf(
            NavTab.HOME to Pair(R.drawable.ic_nav_home, R.string.nav_home),
            NavTab.SEARCH to Pair(R.drawable.ic_nav_search, R.string.nav_search),
            NavTab.LIBRARY to Pair(R.drawable.ic_nav_library, R.string.nav_library),
            NavTab.PLAYLISTS to Pair(R.drawable.ic_nav_playlists, R.string.nav_playlists)
        )
    }

    if (!isMaConnected || selectedNavTab == null) {
        // Full Now Playing -- no navigation scaffold needed
        NowPlayingScreen(
            viewModel = viewModel,
            onPreviousClick = onPreviousClick,
            onPlayPauseClick = onPlayPauseClick,
            onNextClick = onNextClick,
            onSwitchGroupClick = onSwitchGroupClick,
            onFavoriteClick = onFavoriteClick,
            onVolumeChange = onVolumeChange,
            onQueueClick = onQueueClick,
            modifier = modifier.fillMaxSize()
        )
    } else {
        // Browsing with NavigationSuiteScaffold
        NavigationSuiteScaffold(
            modifier = modifier,
            navigationSuiteItems = {
                navTabs.forEach { (tab, iconAndLabel) ->
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
                            if (selectedNavTab == tab) {
                                // Tapping already-selected tab returns to Now Playing
                                selectedNavTab = null
                                viewModel.setNavigationContentVisible(false)
                            } else {
                                selectedNavTab = tab
                                viewModel.setCurrentNavTab(tab)
                                viewModel.setNavigationContentVisible(true)
                            }
                        }
                    )
                }
            }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Browse content
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedNavTab) {
                        NavTab.HOME -> BrowseTabPlaceholder("Home")
                        NavTab.SEARCH -> BrowseTabPlaceholder("Search")
                        NavTab.LIBRARY -> BrowseTabPlaceholder("Library")
                        NavTab.PLAYLISTS -> BrowseTabPlaceholder("Playlists")
                        null -> {
                            // Fallback -- should not reach here
                            NowPlayingScreen(
                                viewModel = viewModel,
                                onPreviousClick = onPreviousClick,
                                onPlayPauseClick = onPlayPauseClick,
                                onNextClick = onNextClick,
                                onSwitchGroupClick = onSwitchGroupClick,
                                onFavoriteClick = onFavoriteClick,
                                onVolumeChange = onVolumeChange,
                                onQueueClick = onQueueClick
                            )
                        }
                    }
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

/**
 * Temporary placeholder for browse tabs.
 * Will be replaced with actual Compose screens (HomeScreen, SearchScreen, etc.)
 * when Fragment wrappers are eliminated in Phase 1F.
 */
@Composable
private fun BrowseTabPlaceholder(tabName: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$tabName (migrating...)",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
