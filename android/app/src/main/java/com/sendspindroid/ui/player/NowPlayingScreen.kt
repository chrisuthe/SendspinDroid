package com.sendspindroid.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Full now playing screen with album art, track info, playback controls, and volume slider.
 *
 * This screen is shown when connected to a SendSpin server and displays:
 * - Large album artwork (with buffering overlay)
 * - Track title and artist/album info
 * - Optional group name
 * - Playback controls (previous, play/pause, next)
 * - Secondary controls (switch group, favorite)
 * - Volume slider
 *
 * @param state Current playback state
 * @param onPlayPauseClick Called when play/pause button is clicked
 * @param onPreviousClick Called when previous button is clicked
 * @param onNextClick Called when next button is clicked
 * @param onSwitchGroupClick Called when switch group button is clicked
 * @param onFavoriteClick Called when favorite button is clicked (null hides button)
 * @param onVolumeChange Called when volume slider value changes
 * @param modifier Modifier for the screen
 */
@Composable
fun NowPlayingScreen(
    state: NowPlayingState,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onFavoriteClick: (() -> Unit)?,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Album Art with buffering indicator
            AlbumArtwork(
                artworkUrl = state.artworkUrl,
                isBuffering = state.isBuffering,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .widthIn(max = 400.dp)
                    .aspectRatio(1f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Track Info
            TrackInfo(
                title = state.title,
                artist = state.artist,
                album = state.album,
                groupName = state.groupName
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Playback Controls
            PlaybackControls(
                isPlaying = state.isPlaying,
                controlsEnabled = state.controlsEnabled,
                showFavorite = onFavoriteClick != null,
                isFavorite = state.isFavorite,
                onPlayPauseClick = onPlayPauseClick,
                onPreviousClick = onPreviousClick,
                onNextClick = onNextClick,
                onSwitchGroupClick = onSwitchGroupClick,
                onFavoriteClick = onFavoriteClick ?: {}
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Volume Slider
            VolumeSlider(
                volume = state.volume,
                enabled = state.controlsEnabled,
                onVolumeChange = onVolumeChange
            )
        }
    }
}

/**
 * Album artwork with optional buffering indicator overlay.
 */
@Composable
fun AlbumArtwork(
    artworkUrl: String?,
    isBuffering: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = stringResource(R.string.album_art),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
                placeholder = painterResource(R.drawable.placeholder_album_simple),
                error = painterResource(R.drawable.placeholder_album_simple)
            )

            // Buffering indicator overlay
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(64.dp),
                    strokeWidth = 4.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Track information display with title, artist/album, and optional group name.
 */
@Composable
fun TrackInfo(
    title: String,
    artist: String,
    album: String,
    groupName: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Track title - large and bold
        Text(
            text = title.ifEmpty { stringResource(R.string.not_playing) },
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.02).sp,
                lineHeight = 28.sp
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        // Artist and album - secondary text
        val metadata = buildString {
            if (artist.isNotEmpty()) append(artist)
            if (album.isNotEmpty()) {
                if (isNotEmpty()) append(" - ")
                append(album)
            }
        }
        if (metadata.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = metadata,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        // Group name - accent color
        if (!groupName.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = groupName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Main playback controls with previous, play/pause, next buttons and secondary controls.
 */
@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    controlsEnabled: Boolean,
    showFavorite: Boolean,
    isFavorite: Boolean,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main controls row: Previous, Play/Pause, Next
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Previous button
            FilledTonalIconButton(
                onClick = onPreviousClick,
                enabled = controlsEnabled,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_previous),
                    contentDescription = stringResource(R.string.accessibility_previous_button),
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Play/Pause button - larger and filled
            FilledIconButton(
                onClick = onPlayPauseClick,
                enabled = controlsEnabled,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    painter = painterResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    ),
                    contentDescription = stringResource(
                        if (isPlaying) R.string.accessibility_pause_button
                        else R.string.accessibility_play_button
                    ),
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Next button
            FilledTonalIconButton(
                onClick = onNextClick,
                enabled = controlsEnabled,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = stringResource(R.string.accessibility_next_button),
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Secondary controls row: Switch Group + Favorite
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Switch group button
            FilledTonalIconButton(
                onClick = onSwitchGroupClick,
                enabled = controlsEnabled,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_swap_horiz),
                    contentDescription = stringResource(R.string.accessibility_switch_group_button),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Favorite button (only when connected to MA)
            if (showFavorite) {
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = onFavoriteClick,
                    modifier = Modifier.size(48.dp),
                    colors = if (isFavorite) {
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    } else {
                        IconButtonDefaults.filledTonalIconButtonColors()
                    }
                ) {
                    Icon(
                        painter = painterResource(
                            if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
                        ),
                        contentDescription = stringResource(R.string.accessibility_favorite_track),
                        modifier = Modifier.size(20.dp),
                        tint = if (isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

/**
 * Volume slider with speaker icons.
 */
@Composable
fun VolumeSlider(
    volume: Float,
    enabled: Boolean,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_volume_down),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            valueRange = 0f..1f
        )

        Icon(
            painter = painterResource(R.drawable.ic_volume_up),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * State holder for NowPlayingScreen.
 */
data class NowPlayingState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUrl: String? = null,
    val groupName: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isFavorite: Boolean = false,
    val controlsEnabled: Boolean = false,
    val volume: Float = 0.75f
)

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true)
@Composable
private fun NowPlayingScreenPreview() {
    SendSpinTheme {
        NowPlayingScreen(
            state = NowPlayingState(
                title = "Bohemian Rhapsody",
                artist = "Queen",
                album = "A Night at the Opera",
                isPlaying = true,
                controlsEnabled = true,
                volume = 0.75f
            ),
            onPlayPauseClick = {},
            onPreviousClick = {},
            onNextClick = {},
            onSwitchGroupClick = {},
            onFavoriteClick = {},
            onVolumeChange = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NowPlayingScreenBufferingPreview() {
    SendSpinTheme {
        NowPlayingScreen(
            state = NowPlayingState(
                title = "Loading...",
                isBuffering = true,
                controlsEnabled = false
            ),
            onPlayPauseClick = {},
            onPreviousClick = {},
            onNextClick = {},
            onSwitchGroupClick = {},
            onFavoriteClick = null,
            onVolumeChange = {}
        )
    }
}
