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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.adaptive.tvFocusable
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
    compactLayout: Boolean = false,
    isSwitchGroupEnabled: Boolean = false,
    onSwitchGroupClick: () -> Unit = {},
    showFavorite: Boolean = false,
    isFavorite: Boolean = false,
    onFavoriteClick: () -> Unit = {},
    showPlayerButton: Boolean = false,
    onPlayerClick: () -> Unit = {},
    playButtonSize: Dp = 72.dp,
    controlButtonSize: Dp = 56.dp,
    buttonGap: Dp = 16.dp,
    playFocusRequester: FocusRequester? = null
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
            // Derived icon sizes
            val playIconSize = playButtonSize * 0.67f
            val controlIconSize = controlButtonSize * 0.5f

            // Switch Group (inline when compact)
            if (compactLayout) {
                FilledTonalIconButton(
                    onClick = onSwitchGroupClick,
                    enabled = isSwitchGroupEnabled,
                    modifier = Modifier
                        .size(44.dp)
                        .tvFocusable()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_swap_horiz),
                        contentDescription = stringResource(R.string.accessibility_switch_group_button),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Previous Button
            FilledTonalIconButton(
                onClick = onPreviousClick,
                enabled = isEnabled,
                modifier = Modifier
                    .size(controlButtonSize)
                    .tvFocusable()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_previous),
                    contentDescription = stringResource(R.string.accessibility_previous_button),
                    modifier = Modifier.size(controlIconSize)
                )
            }

            Spacer(modifier = Modifier.width(buttonGap))

            // Play/Pause Button (larger, filled)
            FilledIconButton(
                onClick = onPlayPauseClick,
                enabled = isEnabled,
                modifier = Modifier
                    .size(playButtonSize)
                    .tvFocusable(focusRequester = playFocusRequester),
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
                    modifier = Modifier.size(playIconSize)
                )
            }

            Spacer(modifier = Modifier.width(buttonGap))

            // Next Button
            FilledTonalIconButton(
                onClick = onNextClick,
                enabled = isEnabled,
                modifier = Modifier
                    .size(controlButtonSize)
                    .tvFocusable()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = stringResource(R.string.accessibility_next_button),
                    modifier = Modifier.size(controlIconSize)
                )
            }

            // Favorite (inline when compact)
            if (compactLayout && showFavorite) {
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = onFavoriteClick,
                    modifier = Modifier
                        .size(44.dp)
                        .tvFocusable()
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

            // Speaker / Group button (inline when compact, MA only)
            if (compactLayout && showPlayerButton) {
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = onPlayerClick,
                    modifier = Modifier
                        .size(44.dp)
                        .tvFocusable()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_speaker_group),
                        contentDescription = stringResource(R.string.accessibility_player_button),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Secondary Row (only when not compact and enabled)
        if (showSecondaryRow && !compactLayout) {
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Switch Group Button
                FilledTonalIconButton(
                    onClick = onSwitchGroupClick,
                    enabled = isSwitchGroupEnabled,
                    modifier = Modifier
                        .size(48.dp)
                        .tvFocusable()
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
                        modifier = Modifier
                            .size(48.dp)
                            .tvFocusable()
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

                // Speaker / Group button (MA only)
                if (showPlayerButton) {
                    Spacer(modifier = Modifier.width(8.dp))

                    FilledTonalIconButton(
                        onClick = onPlayerClick,
                        modifier = Modifier
                            .size(48.dp)
                            .tvFocusable()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_speaker_group),
                            contentDescription = stringResource(R.string.accessibility_player_button),
                            modifier = Modifier.size(20.dp)
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
