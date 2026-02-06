package com.sendspindroid.ui.queue

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.musicassistant.MaQueueItem
import com.sendspindroid.musicassistant.MusicAssistantManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the Queue screen.
 */
sealed interface QueueUiState {
    data object Loading : QueueUiState

    data class Success(
        val currentItem: MaQueueItem?,
        val upNextItems: List<MaQueueItem>,
        val shuffleEnabled: Boolean,
        val repeatMode: String  // "off", "one", "all"
    ) : QueueUiState {
        val totalItems: Int
            get() = (if (currentItem != null) 1 else 0) + upNextItems.size

        val isEmpty: Boolean
            get() = currentItem == null && upNextItems.isEmpty()
    }

    data class Error(val message: String) : QueueUiState
}

/**
 * Direction for moving queue items.
 */
enum class MoveDirection {
    UP, DOWN
}

/**
 * ViewModel for the Queue bottom sheet.
 *
 * Manages loading and manipulating the player queue from Music Assistant.
 * All operations are server-side -- this is a remote control.
 */
class QueueViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<QueueUiState>(QueueUiState.Loading)
    val uiState: StateFlow<QueueUiState> = _uiState.asStateFlow()

    /** Tracks whether an action is in progress (for disabling UI) */
    private val _isActionInProgress = MutableStateFlow(false)
    val isActionInProgress: StateFlow<Boolean> = _isActionInProgress.asStateFlow()

    companion object {
        private const val TAG = "QueueViewModel"
    }

    /**
     * Load the queue from Music Assistant.
     */
    fun loadQueue() {
        _uiState.value = QueueUiState.Loading
        viewModelScope.launch {
            Log.d(TAG, "Loading queue items...")
            val result = MusicAssistantManager.getQueueItems()
            result.fold(
                onSuccess = { queueState ->
                    val currentIndex = queueState.currentIndex
                    val allItems = queueState.items

                    // Split into current item and upcoming items
                    val currentItem = if (currentIndex >= 0 && currentIndex < allItems.size) {
                        allItems[currentIndex]
                    } else {
                        allItems.firstOrNull { it.isCurrentItem }
                    }

                    val upNextItems = if (currentIndex >= 0 && currentIndex < allItems.size) {
                        allItems.subList(currentIndex + 1, allItems.size)
                    } else {
                        // If we can't determine current, show all non-current items
                        allItems.filter { !it.isCurrentItem }
                    }

                    _uiState.value = QueueUiState.Success(
                        currentItem = currentItem,
                        upNextItems = upNextItems,
                        shuffleEnabled = queueState.shuffleEnabled,
                        repeatMode = queueState.repeatMode
                    )
                    Log.i(TAG, "Queue loaded: ${allItems.size} items, current index: $currentIndex")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load queue", error)
                    _uiState.value = QueueUiState.Error(
                        error.message ?: "Failed to load queue"
                    )
                }
            )
        }
    }

    /**
     * Refresh the queue (reload from server).
     */
    fun refresh() {
        loadQueue()
    }

    /**
     * Jump to and play a specific item in the queue.
     */
    fun playItem(queueItemId: String) {
        viewModelScope.launch {
            _isActionInProgress.value = true
            Log.d(TAG, "Playing queue item: $queueItemId")
            val result = MusicAssistantManager.playQueueItem(queueItemId)
            result.fold(
                onSuccess = {
                    Log.i(TAG, "Jumped to queue item: $queueItemId")
                    // Reload queue to reflect new current item
                    loadQueue()
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to play queue item", error)
                }
            )
            _isActionInProgress.value = false
        }
    }

    /**
     * Remove an item from the queue.
     *
     * @return true if the removal was initiated (for undo tracking)
     */
    fun removeItem(queueItemId: String) {
        viewModelScope.launch {
            _isActionInProgress.value = true
            Log.d(TAG, "Removing queue item: $queueItemId")

            // Optimistically remove from UI first
            val currentState = _uiState.value
            if (currentState is QueueUiState.Success) {
                _uiState.value = currentState.copy(
                    upNextItems = currentState.upNextItems.filter { it.queueItemId != queueItemId }
                )
            }

            val result = MusicAssistantManager.removeQueueItem(queueItemId)
            result.fold(
                onSuccess = {
                    Log.i(TAG, "Removed queue item: $queueItemId")
                    // Reload to get accurate state
                    loadQueue()
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to remove queue item", error)
                    // Revert by reloading
                    loadQueue()
                }
            )
            _isActionInProgress.value = false
        }
    }

    /**
     * Move an item up or down in the queue.
     */
    fun moveItem(queueItemId: String, direction: MoveDirection) {
        viewModelScope.launch {
            _isActionInProgress.value = true
            val posShift = when (direction) {
                MoveDirection.UP -> -1
                MoveDirection.DOWN -> 1
            }
            Log.d(TAG, "Moving queue item $queueItemId ${direction.name}")

            val result = MusicAssistantManager.moveQueueItem(queueItemId, posShift)
            result.fold(
                onSuccess = {
                    Log.i(TAG, "Moved queue item: $queueItemId ${direction.name}")
                    loadQueue()
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to move queue item", error)
                }
            )
            _isActionInProgress.value = false
        }
    }

    /**
     * Clear the entire queue.
     */
    fun clearQueue() {
        viewModelScope.launch {
            _isActionInProgress.value = true
            Log.d(TAG, "Clearing queue")

            val result = MusicAssistantManager.clearQueue()
            result.fold(
                onSuccess = {
                    Log.i(TAG, "Queue cleared")
                    _uiState.value = QueueUiState.Success(
                        currentItem = null,
                        upNextItems = emptyList(),
                        shuffleEnabled = false,
                        repeatMode = "off"
                    )
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to clear queue", error)
                }
            )
            _isActionInProgress.value = false
        }
    }

    /**
     * Toggle shuffle mode on/off.
     */
    fun toggleShuffle() {
        val currentState = _uiState.value
        if (currentState !is QueueUiState.Success) return

        val newShuffle = !currentState.shuffleEnabled

        viewModelScope.launch {
            // Optimistic update
            _uiState.value = currentState.copy(shuffleEnabled = newShuffle)

            Log.d(TAG, "Setting shuffle: $newShuffle")
            val result = MusicAssistantManager.setQueueShuffle(newShuffle)
            result.fold(
                onSuccess = {
                    Log.i(TAG, "Shuffle set to: $newShuffle")
                    // Reload queue since order may have changed
                    loadQueue()
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to toggle shuffle", error)
                    // Revert
                    _uiState.value = currentState
                }
            )
        }
    }

    /**
     * Cycle through repeat modes: off -> all -> one -> off
     */
    fun cycleRepeatMode() {
        val currentState = _uiState.value
        if (currentState !is QueueUiState.Success) return

        val newMode = when (currentState.repeatMode) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        }

        viewModelScope.launch {
            // Optimistic update
            _uiState.value = currentState.copy(repeatMode = newMode)

            Log.d(TAG, "Setting repeat mode: $newMode")
            val result = MusicAssistantManager.setQueueRepeat(newMode)
            result.fold(
                onSuccess = {
                    Log.i(TAG, "Repeat mode set to: $newMode")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to set repeat mode", error)
                    // Revert
                    _uiState.value = currentState
                }
            )
        }
    }
}
