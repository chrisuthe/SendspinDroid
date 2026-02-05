package com.sendspindroid

/**
 * Constants for sync offset broadcast.
 * Used by SettingsViewModel to notify PlaybackService of changes.
 */
object SyncOffsetPreference {
    const val ACTION_SYNC_OFFSET_CHANGED = "com.sendspindroid.ACTION_SYNC_OFFSET_CHANGED"
    const val EXTRA_OFFSET_MS = "offset_ms"
}
