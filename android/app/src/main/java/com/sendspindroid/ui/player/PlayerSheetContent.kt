package com.sendspindroid.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.musicassistant.model.MaPlaybackState
import com.sendspindroid.musicassistant.model.MaPlayer
import com.sendspindroid.ui.adaptive.tvFocusable

/**
 * Main composable content for the Player / Speaker Group management sheet.
 *
 * Shows the locked current player at the top, followed by a list of compatible
 * speakers with toggles to add/remove them from the current player's group.
 *
 * Used inside both [PlayerBottomSheet] (phone ModalBottomSheet) and can be
 * reused in other containers (dialog, side panel) for larger form factors.
 */
@Composable
fun PlayerSheetContent(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val togglingPlayerId by viewModel.togglingPlayerId.collectAsState()

    // Load players when sheet opens
    LaunchedEffect(Unit) {
        viewModel.loadPlayers()
    }

    when (val state = uiState) {
        is PlayerUiState.Loading -> {
            PlayerLoadingContent(modifier)
        }
        is PlayerUiState.Error -> {
            PlayerErrorContent(
                message = state.message,
                onRetry = { viewModel.refresh() },
                modifier = modifier
            )
        }
        is PlayerUiState.Success -> {
            PlayerSuccessContent(
                state = state,
                togglingPlayerId = togglingPlayerId,
                onTogglePlayer = { viewModel.togglePlayer(it) },
                onRefresh = { viewModel.refresh() },
                modifier = modifier
            )
        }
    }
}

// ============================================================================
// Success Content
// ============================================================================

@Composable
private fun PlayerSuccessContent(
    state: PlayerUiState.Success,
    togglingPlayerId: String?,
    onTogglePlayer: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        // Header
        PlayerSheetHeader(
            groupMemberCount = state.groupMemberCount,
            onRefresh = onRefresh
        )

        // Locked current player
        CurrentPlayerItem(player = state.currentPlayer)

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (state.groupablePlayers.isNotEmpty()) {
            // Section label
            Text(
                text = stringResource(R.string.player_available_speakers),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Groupable player list
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(
                    items = state.groupablePlayers,
                    key = { it.player.playerId }
                ) { groupable ->
                    GroupablePlayerItem(
                        player = groupable.player,
                        isInGroup = groupable.isInGroup,
                        isToggling = togglingPlayerId == groupable.player.playerId,
                        onToggle = { onTogglePlayer(groupable.player.playerId) }
                    )
                }
            }
        } else {
            // Empty state
            PlayerEmptyContent()
        }
    }
}

// ============================================================================
// Header
// ============================================================================

@Composable
private fun PlayerSheetHeader(
    groupMemberCount: Int,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.player_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.player_group_count, groupMemberCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = onRefresh,
            modifier = Modifier.tvFocusable()
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.accessibility_refresh),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// Current Player (Locked)
// ============================================================================

@Composable
private fun CurrentPlayerItem(player: MaPlayer) {
    val description = "${player.name}, ${stringResource(R.string.player_current_speaker)}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Speaker icon (primary tint for current)
        Icon(
            painter = painterResource(R.drawable.ic_speaker_group),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Player name + "Current speaker" subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = player.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.player_current_speaker),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Lock icon
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ============================================================================
// Groupable Player Item (with Toggle)
// ============================================================================

@Composable
private fun GroupablePlayerItem(
    player: MaPlayer,
    isInGroup: Boolean,
    isToggling: Boolean,
    onToggle: () -> Unit
) {
    val stateText = when (player.playbackState) {
        MaPlaybackState.PLAYING -> stringResource(R.string.player_state_playing)
        MaPlaybackState.PAUSED -> stringResource(R.string.player_state_paused)
        MaPlaybackState.IDLE -> stringResource(R.string.player_state_idle)
        MaPlaybackState.UNKNOWN -> ""
    }

    val groupStatus = if (isInGroup) "in group" else "not in group"
    val description = stringResource(R.string.accessibility_player_toggle, player.name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isToggling, onClick = onToggle)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .tvFocusable()
            .semantics { contentDescription = "${player.name}, $stateText, $groupStatus" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Speaker icon
        Icon(
            painter = painterResource(R.drawable.ic_speaker_group),
            contentDescription = null,
            tint = if (isInGroup)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Player name + state subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = player.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (stateText.isNotEmpty()) {
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Toggle or loading indicator
        if (isToggling) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Switch(
                checked = isInGroup,
                onCheckedChange = { onToggle() },
                modifier = Modifier.semantics {
                    contentDescription = description
                }
            )
        }
    }
}

// ============================================================================
// Empty State
// ============================================================================

@Composable
private fun PlayerEmptyContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_speaker_group),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.player_no_speakers),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.player_no_speakers_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================================
// Loading State
// ============================================================================

@Composable
private fun PlayerLoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

// ============================================================================
// Error State
// ============================================================================

@Composable
private fun PlayerErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.player_load_failed),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(onClick = onRetry) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Retry")
        }
    }
}
