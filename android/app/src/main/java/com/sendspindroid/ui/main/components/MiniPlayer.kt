package com.sendspindroid.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sendspindroid.R
import com.sendspindroid.ui.main.ArtworkSource
import com.sendspindroid.ui.main.TrackMetadata
import com.sendspindroid.ui.preview.AllDevicePreviews
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Mini player card shown when navigating to Home/Search/Library tabs.
 * Displays current track info with compact controls.
 *
 * Only tapping the album art opens the full player view.
 */
@Composable
fun MiniPlayer(
    metadata: TrackMetadata,
    artworkSource: ArtworkSource?,
    isPlaying: Boolean,
    volume: Float,
    onCardClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    positionMs: Long = 0,
    durationMs: Long = 0,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box {
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Album Art - tappable to open full player, with up-arrow overlay
            val context = LocalContext.current
            val imageRequest = when (artworkSource) {
                is ArtworkSource.ByteArray -> {
                    ImageRequest.Builder(context)
                        .data(artworkSource.data)
                        .crossfade(true)
                        .build()
                }
                is ArtworkSource.Uri -> {
                    ImageRequest.Builder(context)
                        .data(artworkSource.uri)
                        .crossfade(true)
                        .build()
                }
                is ArtworkSource.Url -> {
                    ImageRequest.Builder(context)
                        .data(artworkSource.url)
                        .crossfade(true)
                        .build()
                }
                null -> null
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .clickable(onClick = onCardClick)
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = stringResource(R.string.accessibility_open_full_player),
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(R.drawable.placeholder_album_simple),
                    error = painterResource(R.drawable.placeholder_album_simple),
                    fallback = painterResource(R.drawable.placeholder_album_simple)
                )
                // Up-arrow hint overlay at bottom-center
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_up),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Right side content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top row: Track info + controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Track Info (not clickable -- only album art opens full player)
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        // Track title with playing indicator
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isPlaying) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_audio_playing),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = metadata.title.ifEmpty { stringResource(R.string.not_playing) },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Artist
                        if (metadata.artist.isNotEmpty()) {
                            Text(
                                text = metadata.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Play/Pause Button
                    FilledIconButton(
                        onClick = onPlayPauseClick,
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                            ),
                            contentDescription = stringResource(
                                if (isPlaying) R.string.accessibility_pause_button
                                else R.string.accessibility_play_button
                            ),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Bottom row: Volume slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_volume_down),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Slider(
                        value = volume,
                        onValueChange = onVolumeChange,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Icon(
                        painter = painterResource(R.drawable.ic_volume_up),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Thin progress bar at bottom edge
        if (durationMs > 0) {
            LinearProgressIndicator(
                progress = { (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            )
        }
        } // Box
    }
}

/**
 * Side mini player for phone landscape mode.
 * Vertical layout: album art on top, track info, controls, progress bar.
 * No volume slider — landscape phone uses hardware volume.
 */
@Composable
fun MiniPlayerSide(
    metadata: TrackMetadata,
    artworkSource: ArtworkSource?,
    isPlaying: Boolean,
    onCardClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    positionMs: Long = 0,
    durationMs: Long = 0,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box {
            Column(
                modifier = Modifier.fillMaxHeight()
            ) {
                // Album art — tappable to open full player
                val context = LocalContext.current
                val imageRequest = when (artworkSource) {
                    is ArtworkSource.ByteArray -> {
                        ImageRequest.Builder(context)
                            .data(artworkSource.data)
                            .crossfade(true)
                            .build()
                    }
                    is ArtworkSource.Uri -> {
                        ImageRequest.Builder(context)
                            .data(artworkSource.uri)
                            .crossfade(true)
                            .build()
                    }
                    is ArtworkSource.Url -> {
                        ImageRequest.Builder(context)
                            .data(artworkSource.url)
                            .crossfade(true)
                            .build()
                    }
                    null -> null
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(onClick = onCardClick)
                ) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = stringResource(R.string.accessibility_open_full_player),
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(R.drawable.placeholder_album_simple),
                        error = painterResource(R.drawable.placeholder_album_simple),
                        fallback = painterResource(R.drawable.placeholder_album_simple)
                    )
                    // Up-arrow hint overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 2.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_expand_up),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Track info
                Column(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isPlaying) {
                            Icon(
                                painter = painterResource(R.drawable.ic_audio_playing),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = metadata.title.ifEmpty { stringResource(R.string.not_playing) },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (metadata.artist.isNotEmpty()) {
                        Text(
                            text = metadata.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = onPlayPauseClick,
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                            ),
                            contentDescription = stringResource(
                                if (isPlaying) R.string.accessibility_pause_button
                                else R.string.accessibility_play_button
                            ),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Progress bar at bottom edge
            if (durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MiniPlayerPreview() {
    SendSpinTheme {
        MiniPlayer(
            metadata = TrackMetadata(
                title = "Track Title Goes Here",
                artist = "Artist Name",
                album = "Album Name"
            ),
            artworkSource = null,
            isPlaying = true,
            volume = 0.75f,
            onCardClick = {},
            onPlayPauseClick = {},
            onVolumeChange = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MiniPlayerNotPlayingPreview() {
    SendSpinTheme {
        MiniPlayer(
            metadata = TrackMetadata.EMPTY,
            artworkSource = null,
            isPlaying = false,
            volume = 0.5f,
            onCardClick = {},
            onPlayPauseClick = {},
            onVolumeChange = {}
        )
    }
}

// -- Multi-Device Previews --

@AllDevicePreviews
@Composable
private fun MiniPlayerAllDevicesPreview() {
    SendSpinTheme {
        MiniPlayer(
            metadata = TrackMetadata(
                title = "Bohemian Rhapsody",
                artist = "Queen",
                album = "A Night at the Opera"
            ),
            artworkSource = null,
            isPlaying = true,
            volume = 0.75f,
            onCardClick = {},
            onPlayPauseClick = {},
            onVolumeChange = {}
        )
    }
}
