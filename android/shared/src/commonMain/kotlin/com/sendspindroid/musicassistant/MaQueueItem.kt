package com.sendspindroid.musicassistant

/**
 * Represents an item in the player queue from Music Assistant.
 *
 * Queue items have their own queue_item_id which is distinct from the
 * media item's library ID. The queue_item_id is needed for operations
 * like remove, reorder, and jump-to.
 */
data class MaQueueItem(
    val queueItemId: String,
    val name: String,
    val artist: String?,
    val album: String?,
    val imageUri: String?,
    val duration: Long?,       // seconds
    val uri: String?,          // media URI (e.g., "library://track/123")
    val isCurrentItem: Boolean // is this the currently playing track
)
