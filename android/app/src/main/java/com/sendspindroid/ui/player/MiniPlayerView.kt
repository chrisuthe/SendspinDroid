package com.sendspindroid.ui.player

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Mini player component shown when browsing Home/Search/Library tabs.
 * Displays current track info, playback controls, and volume slider.
 * Tapping expands to full player view.
 *
 * @param state Current playback state
 * @param onExpandClick Called when the mini player is tapped to expand
 * @param onStopClick Called when stop button is clicked (disconnect)
 * @param onPlayPauseClick Called when play/pause button is clicked
 * @param onVolumeChange Called when volume slider value changes
 * @param modifier Modifier for the component
 */
@Composable
fun MiniPlayerView(
    state: MiniPlayerState,
    onExpandClick: () -> Unit,
    onStopClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .clickable(onClick = onExpandClick),
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
            // Top row: Album art + track info + controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album art (48dp square)
                Card(
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    AsyncImage(
                        model = state.artworkUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(R.drawable.placeholder_album_simple),
                        error = painterResource(R.drawable.placeholder_album_simple)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Track info column
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Server name with audio indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.isPlaying) {
                            Icon(
                                painter = painterResource(R.drawable.ic_audio_playing),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = state.serverName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Track title
                    Text(
                        text = state.title.ifEmpty { stringResource(R.string.not_playing) },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Artist
                    if (state.artist.isNotEmpty()) {
                        Text(
                            text = state.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Control buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Stop button
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

                    // Play/Pause button
                    FilledIconButton(
                        onClick = onPlayPauseClick,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            painter = painterResource(
                                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                            ),
                            contentDescription = stringResource(
                                if (state.isPlaying) R.string.accessibility_pause_button
                                else R.string.accessibility_play_button
                            ),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Volume row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Volume down icon (clickable)
                IconButton(
                    onClick = {
                        val newVolume = (state.volume - 0.05f).coerceIn(0f, 1f)
                        onVolumeChange(newVolume)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_volume_down),
                        contentDescription = stringResource(R.string.volume),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Volume slider
                Slider(
                    value = state.volume,
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

                // Volume up icon (clickable)
                IconButton(
                    onClick = {
                        val newVolume = (state.volume + 0.05f).coerceIn(0f, 1f)
                        onVolumeChange(newVolume)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_volume_up),
                        contentDescription = stringResource(R.string.volume),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * State holder for MiniPlayerView.
 */
data class MiniPlayerState(
    val serverName: String = "",
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val volume: Float = 0.75f
)

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true)
@Composable
private fun MiniPlayerViewPreview() {
    SendSpinTheme {
        MiniPlayerView(
            state = MiniPlayerState(
                serverName = "Living Room",
                title = "Bohemian Rhapsody",
                artist = "Queen",
                isPlaying = true,
                volume = 0.75f
            ),
            onExpandClick = {},
            onStopClick = {},
            onPlayPauseClick = {},
            onVolumeChange = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MiniPlayerViewPausedPreview() {
    SendSpinTheme {
        MiniPlayerView(
            state = MiniPlayerState(
                serverName = "Kitchen",
                title = "Hotel California",
                artist = "Eagles",
                isPlaying = false,
                volume = 0.5f
            ),
            onExpandClick = {},
            onStopClick = {},
            onPlayPauseClick = {},
            onVolumeChange = {}
        )
    }
}
