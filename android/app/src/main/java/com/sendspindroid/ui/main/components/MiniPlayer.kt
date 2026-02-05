package com.sendspindroid.ui.main.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sendspindroid.R
import com.sendspindroid.ui.main.ArtworkSource
import com.sendspindroid.ui.main.TrackMetadata
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Mini player card shown when navigating to Home/Search/Library tabs.
 * Displays current track info with compact controls.
 */
@Composable
fun MiniPlayer(
    serverName: String,
    metadata: TrackMetadata,
    artworkSource: ArtworkSource?,
    isPlaying: Boolean,
    volume: Float,
    onCardClick: () -> Unit,
    onStopClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .clickable(onClick = onCardClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album Art
                Card(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
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

                    AsyncImage(
                        model = imageRequest,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(R.drawable.placeholder_album_simple),
                        error = painterResource(R.drawable.placeholder_album_simple),
                        fallback = painterResource(R.drawable.placeholder_album_simple)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Track Info
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Server name with playing indicator
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
                            text = serverName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Track title
                    Text(
                        text = metadata.title.ifEmpty { stringResource(R.string.not_playing) },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

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

                // Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Stop Button
                    IconButton(
                        onClick = onStopClick,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_stop),
                            contentDescription = stringResource(R.string.disconnect),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Play/Pause Button
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

            Spacer(modifier = Modifier.height(6.dp))

            // Volume Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_volume_down),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
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
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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
            serverName = "Living Room",
            metadata = TrackMetadata(
                title = "Track Title Goes Here",
                artist = "Artist Name",
                album = "Album Name"
            ),
            artworkSource = null,
            isPlaying = true,
            volume = 0.75f,
            onCardClick = {},
            onStopClick = {},
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
            serverName = "Kitchen",
            metadata = TrackMetadata.EMPTY,
            artworkSource = null,
            isPlaying = false,
            volume = 0.5f,
            onCardClick = {},
            onStopClick = {},
            onPlayPauseClick = {},
            onVolumeChange = {}
        )
    }
}
