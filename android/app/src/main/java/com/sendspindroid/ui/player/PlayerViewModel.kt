package com.sendspindroid.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaPlayer
import com.sendspindroid.musicassistant.model.MaPlayerType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the Player Group management sheet.
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
 * ViewModel for the Player Group management sheet.
 *
 * Manages loading the player list, determining which players can be
 * grouped with this device, and toggling group membership.
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
            // Use THIS device's player ID (the UUID we registered with SendSpin),
            // not the "selected player" which may be a different speaker.
            val thisDevicePlayerId = MusicAssistantManager.getThisDevicePlayerId()
            Log.d(TAG, "This device player ID: $thisDevicePlayerId")

            val result = MusicAssistantManager.getAllPlayers()
            result.fold(
                onSuccess = { allPlayers ->
                    buildSuccessState(thisDevicePlayerId, allPlayers)
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

            // Power on the player first if it's off and we're adding it
            if (!isCurrentlyInGroup && groupable.player.powered == false) {
                Log.d(TAG, "Player $playerId is powered off, powering on first")
                val powerResult = MusicAssistantManager.powerOnPlayer(playerId)
                if (powerResult.isFailure) {
                    Log.e(TAG, "Failed to power on player $playerId", powerResult.exceptionOrNull())
                    _uiState.value = currentState
                    _togglingPlayerId.value = null
                    return@launch
                }
                // Give the player time to come alive before adding to group
                delay(500)
            }

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
    private fun buildSuccessState(thisDevicePlayerId: String, allPlayers: List<MaPlayer>) {
        val currentPlayer = allPlayers.find { it.playerId == thisDevicePlayerId }
        if (currentPlayer == null) {
            Log.e(TAG, "This device player $thisDevicePlayerId not found in player list " +
                "(${allPlayers.size} players: ${allPlayers.map { "${it.playerId}=${it.name}" }})")
            _uiState.value = PlayerUiState.Error("This device not found in player list")
            return
        }

        val currentGroupIds = currentPlayer.groupMembers.toSet()

        // Debug: log all players and their filter-relevant fields
        Log.d(TAG, "Current player: id=${currentPlayer.playerId}, name=${currentPlayer.name}, " +
            "provider=${currentPlayer.provider}, type=${currentPlayer.type}, " +
            "canGroupWith=${currentPlayer.canGroupWith}, groupMembers=${currentPlayer.groupMembers}")
        allPlayers.forEach { player ->
            Log.d(TAG, "  Player: id=${player.playerId}, name=${player.name}, " +
                "provider=${player.provider}, type=${player.type}, " +
                "available=${player.available}, enabled=${player.enabled}, " +
                "hideInUi=${player.hideInUi}, canGroupWith=${player.canGroupWith}")
        }

        // Find players that can be grouped with the current player.
        // Only show players whose ID or provider appears in the current player's
        // canGroupWith list — matching the official MA frontend behavior.
        // Also skip GROUP-type players (only individual players can be grouped).
        val groupablePlayers = allPlayers
            .filter { player ->
                val notSelf = player.playerId != thisDevicePlayerId
                val isAvailable = player.available
                val isEnabled = player.enabled
                val notHidden = !player.hideInUi
                val notGroup = player.type != MaPlayerType.GROUP
                val canGroup = player.playerId in currentPlayer.canGroupWith ||
                    player.provider in currentPlayer.canGroupWith

                // Log why each player is included or excluded
                if (notSelf) {
                    Log.d(TAG, "  Filter ${player.name}: available=$isAvailable, " +
                        "enabled=$isEnabled, notHidden=$notHidden, notGroup=$notGroup, " +
                        "canGroup=$canGroup, provider=${player.provider}")
                }

                notSelf && isAvailable && isEnabled && notHidden && notGroup && canGroup
            }
            .map { player ->
                GroupablePlayer(
                    player = player,
                    isInGroup = player.playerId in currentGroupIds
                )
            }
            .sortedWith(compareByDescending<GroupablePlayer> { it.isInGroup }.thenBy { it.player.name.lowercase() })

        val groupMemberCount = 1 + groupablePlayers.count { it.isInGroup } // +1 for current player

        _uiState.value = PlayerUiState.Success(
            currentPlayer = currentPlayer,
            groupablePlayers = groupablePlayers,
            groupMemberCount = groupMemberCount
        )

        Log.i(TAG, "Player state built: current=${currentPlayer.name} (provider=${currentPlayer.provider}), " +
            "groupable=${groupablePlayers.size}, inGroup=${groupMemberCount - 1}")
    }

    /**
     * Silently reload player state without showing the loading indicator.
     */
    private fun reloadSilently() {
        viewModelScope.launch {
            val thisDevicePlayerId = MusicAssistantManager.getThisDevicePlayerId()
            val result = MusicAssistantManager.getAllPlayers()
            result.fold(
                onSuccess = { allPlayers ->
                    buildSuccessState(thisDevicePlayerId, allPlayers)
                },
                onFailure = { error ->
                    Log.e(TAG, "Silent reload failed", error)
                    // Keep current state on silent reload failure
                }
            )
        }
    }
}
