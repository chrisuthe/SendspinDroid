package com.sendspindroid.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the Player / Speaker Group sheet.
 */
sealed interface PlayerUiState {
    data object Loading : PlayerUiState

    data class Success(
        val currentPlayer: MaPlayer,
        val groupablePlayers: List<GroupablePlayer>,
        val groupMemberCount: Int
    ) : PlayerUiState

    data class Error(val message: String) : PlayerUiState
}

/**
 * A player that can be toggled into/out of the current player's group.
 */
data class GroupablePlayer(
    val player: MaPlayer,
    val isInGroup: Boolean
)

/**
 * ViewModel for the Speaker Group management sheet.
 *
 * Manages loading the player list, determining which speakers can be
 * grouped with the current (locked) player, and toggling group membership.
 *
 * Follows the same pattern as [com.sendspindroid.ui.queue.QueueViewModel].
 */
class PlayerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** ID of the player currently being toggled (for per-row loading indicator). */
    private val _togglingPlayerId = MutableStateFlow<String?>(null)
    val togglingPlayerId: StateFlow<String?> = _togglingPlayerId.asStateFlow()

    companion object {
        private const val TAG = "PlayerViewModel"
    }

    /**
     * Load all players and build the group management state.
     */
    fun loadPlayers() {
        _uiState.value = PlayerUiState.Loading
        viewModelScope.launch {
            Log.d(TAG, "Loading players...")
            val activePlayerId = MusicAssistantManager.getSelectedPlayer()
            if (activePlayerId == null) {
                Log.e(TAG, "No active player selected")
                _uiState.value = PlayerUiState.Error("No active player selected")
                return@launch
            }

            val result = MusicAssistantManager.getAllPlayers()
            result.fold(
                onSuccess = { allPlayers ->
                    buildSuccessState(activePlayerId, allPlayers)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load players", error)
                    _uiState.value = PlayerUiState.Error(
                        error.message ?: "Failed to load players"
                    )
                }
            )
        }
    }

    /**
     * Toggle a player into or out of the current player's group.
     */
    fun togglePlayer(playerId: String) {
        val currentState = _uiState.value
        if (currentState !is PlayerUiState.Success) return

        val groupable = currentState.groupablePlayers.find { it.player.playerId == playerId }
            ?: return

        viewModelScope.launch {
            _togglingPlayerId.value = playerId

            val targetPlayerId = currentState.currentPlayer.playerId
            val isCurrentlyInGroup = groupable.isInGroup

            Log.d(TAG, "Toggling player $playerId: ${if (isCurrentlyInGroup) "remove" else "add"}")

            // Optimistic update
            val optimisticPlayers = currentState.groupablePlayers.map {
                if (it.player.playerId == playerId) {
                    it.copy(isInGroup = !isCurrentlyInGroup)
                } else it
            }
            val optimisticCount = 1 + optimisticPlayers.count { it.isInGroup } // +1 for current player
            _uiState.value = currentState.copy(
                groupablePlayers = optimisticPlayers,
                groupMemberCount = optimisticCount
            )

            val apiResult = if (isCurrentlyInGroup) {
                MusicAssistantManager.setGroupMembers(
                    targetPlayerId = targetPlayerId,
                    playerIdsToAdd = emptyList(),
                    playerIdsToRemove = listOf(playerId)
                )
            } else {
                MusicAssistantManager.setGroupMembers(
                    targetPlayerId = targetPlayerId,
                    playerIdsToAdd = listOf(playerId),
                    playerIdsToRemove = emptyList()
                )
            }

            apiResult.fold(
                onSuccess = {
                    Log.i(TAG, "Player $playerId ${if (isCurrentlyInGroup) "removed from" else "added to"} group")
                    // Reload to get fresh state from server
                    reloadSilently()
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to toggle player $playerId", error)
                    // Revert optimistic update
                    _uiState.value = currentState
                }
            )

            _togglingPlayerId.value = null
        }
    }

    /**
     * Refresh the player list from the server.
     */
    fun refresh() {
        loadPlayers()
    }

    /**
     * Build the Success UI state from the active player and full player list.
     */
    private fun buildSuccessState(activePlayerId: String, allPlayers: List<MaPlayer>) {
        val currentPlayer = allPlayers.find { it.playerId == activePlayerId }
        if (currentPlayer == null) {
            Log.e(TAG, "Active player $activePlayerId not found in player list")
            _uiState.value = PlayerUiState.Error("Active player not found")
            return
        }

        // Find players that can be grouped with the current player
        val compatibleIds = currentPlayer.canGroupWith.toSet()
        val currentGroupIds = currentPlayer.groupMembers.toSet()

        val groupablePlayers = allPlayers
            .filter { player ->
                player.playerId in compatibleIds &&
                player.playerId != activePlayerId &&
                player.available &&
                player.enabled &&
                !player.hideInUi
            }
            .map { player ->
                GroupablePlayer(
                    player = player,
                    isInGroup = player.playerId in currentGroupIds
                )
            }
            .sortedBy { it.player.name.lowercase() }

        val groupMemberCount = 1 + groupablePlayers.count { it.isInGroup } // +1 for current player

        _uiState.value = PlayerUiState.Success(
            currentPlayer = currentPlayer,
            groupablePlayers = groupablePlayers,
            groupMemberCount = groupMemberCount
        )

        Log.i(TAG, "Player state built: current=${currentPlayer.name}, " +
            "groupable=${groupablePlayers.size}, inGroup=${groupMemberCount - 1}")
    }

    /**
     * Silently reload player state without showing the loading indicator.
     */
    private fun reloadSilently() {
        viewModelScope.launch {
            val activePlayerId = MusicAssistantManager.getSelectedPlayer() ?: return@launch
            val result = MusicAssistantManager.getAllPlayers()
            result.fold(
                onSuccess = { allPlayers ->
                    buildSuccessState(activePlayerId, allPlayers)
                },
                onFailure = { error ->
                    Log.e(TAG, "Silent reload failed", error)
                    // Keep current state on silent reload failure
                }
            )
        }
    }
}
