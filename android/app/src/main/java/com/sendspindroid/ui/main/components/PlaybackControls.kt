package com.sendspindroid.ui.main.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Main playback controls: Previous, Play/Pause, Next buttons.
 * Plus optional secondary row with Switch Group and Favorite.
 */
@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    isEnabled: Boolean,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier,
    showSecondaryRow: Boolean = true,
    isSwitchGroupEnabled: Boolean = false,
    onSwitchGroupClick: () -> Unit = {},
    showFavorite: Boolean = false,
    isFavorite: Boolean = false,
    onFavoriteClick: () -> Unit = {}
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main Controls Row
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Previous Button
            FilledTonalIconButton(
                onClick = onPreviousClick,
                enabled = isEnabled,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_previous),
                    contentDescription = stringResource(R.string.accessibility_previous_button),
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Play/Pause Button (larger, filled)
            FilledIconButton(
                onClick = onPlayPauseClick,
                enabled = isEnabled,
                modifier = Modifier.size(72.dp),
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
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Next Button
            FilledTonalIconButton(
                onClick = onNextClick,
                enabled = isEnabled,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = stringResource(R.string.accessibility_next_button),
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Secondary Row
        if (showSecondaryRow) {
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Switch Group Button
                FilledTonalIconButton(
                    onClick = onSwitchGroupClick,
                    enabled = isSwitchGroupEnabled,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_swap_horiz),
                        contentDescription = stringResource(R.string.accessibility_switch_group_button),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Favorite Button (conditionally visible)
                if (showFavorite) {
                    Spacer(modifier = Modifier.width(8.dp))

                    FilledTonalIconButton(
                        onClick = onFavoriteClick,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isFavorite) R.drawable.ic_favorite
                                else R.drawable.ic_favorite_border
                            ),
                            contentDescription = stringResource(R.string.accessibility_favorite_track),
                            modifier = Modifier.size(20.dp),
                            tint = if (isFavorite)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaybackControlsPreview() {
    SendSpinTheme {
        PlaybackControls(
            isPlaying = false,
            isEnabled = true,
            onPreviousClick = {},
            onPlayPauseClick = {},
            onNextClick = {},
            isSwitchGroupEnabled = true
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaybackControlsPlayingPreview() {
    SendSpinTheme {
        PlaybackControls(
            isPlaying = true,
            isEnabled = true,
            onPreviousClick = {},
            onPlayPauseClick = {},
            onNextClick = {},
            isSwitchGroupEnabled = true,
            showFavorite = true,
            isFavorite = true
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaybackControlsDisabledPreview() {
    SendSpinTheme {
        PlaybackControls(
            isPlaying = false,
            isEnabled = false,
            onPreviousClick = {},
            onPlayPauseClick = {},
            onNextClick = {}
        )
    }
}
