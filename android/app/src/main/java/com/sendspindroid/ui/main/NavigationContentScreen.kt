package com.sendspindroid.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.ui.main.components.MiniPlayer
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Navigation content screen showing mini player at top with content below.
 * The content area is provided via a slot for the FragmentContainerView.
 *
 * @param viewModel The MainActivityViewModel providing playback state
 * @param onMiniPlayerClick Called when mini player album art is tapped (navigate to NowPlaying)
 * @param onStopClick Called when stop button is clicked (disconnect)
 * @param onPlayPauseClick Called when play/pause button is clicked
 * @param content Slot for the fragment content (Home/Search/Library)
 */
@Composable
fun NavigationContentScreen(
    viewModel: MainActivityViewModel,
    onMiniPlayerClick: () -> Unit,
    onStopClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {}
) {
    val metadata by viewModel.metadata.collectAsState()
    val artworkSource by viewModel.artworkSource.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val volume by viewModel.volume.collectAsState()

    NavigationContentScreen(
        metadata = metadata,
        artworkSource = artworkSource,
        isPlaying = isPlaying,
        volume = volume,
        onMiniPlayerClick = onMiniPlayerClick,
        onStopClick = onStopClick,
        onPlayPauseClick = onPlayPauseClick,
        onVolumeChange = { viewModel.updateVolume(it) },
        modifier = modifier,
        content = content
    )
}

/**
 * Stateless version of NavigationContentScreen for previews and testing.
 */
@Composable
fun NavigationContentScreen(
    metadata: TrackMetadata,
    artworkSource: ArtworkSource?,
    isPlaying: Boolean,
    volume: Float,
    onMiniPlayerClick: () -> Unit,
    onStopClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Mini Player at top
        MiniPlayer(
            metadata = metadata,
            artworkSource = artworkSource,
            isPlaying = isPlaying,
            volume = volume,
            onCardClick = onMiniPlayerClick,
            onStopClick = onStopClick,
            onPlayPauseClick = onPlayPauseClick,
            onVolumeChange = onVolumeChange,
            modifier = Modifier.fillMaxWidth()
        )

        // Content area (FragmentContainerView will be placed here)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // Bottom padding for bottom navigation (56dp)
                .padding(bottom = 56.dp)
        ) {
            content()
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun NavigationContentScreenPreview() {
    SendSpinTheme {
        NavigationContentScreen(
            metadata = TrackMetadata(
                title = "Bohemian Rhapsody",
                artist = "Queen",
                album = "A Night at the Opera"
            ),
            artworkSource = null,
            isPlaying = true,
            volume = 0.75f,
            onMiniPlayerClick = {},
            onStopClick = {},
            onPlayPauseClick = {},
            onVolumeChange = {}
        ) {
            // Preview placeholder for fragment content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun NavigationContentScreenPausedPreview() {
    SendSpinTheme {
        NavigationContentScreen(
            metadata = TrackMetadata(
                title = "Another Track",
                artist = "Some Artist",
                album = "Album"
            ),
            artworkSource = null,
            isPlaying = false,
            volume = 0.5f,
            onMiniPlayerClick = {},
            onStopClick = {},
            onPlayPauseClick = {},
            onVolumeChange = {}
        )
    }
}
