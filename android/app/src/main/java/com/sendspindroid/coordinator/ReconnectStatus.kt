package com.sendspindroid.coordinator

import com.sendspindroid.model.ConnectionType

/**
 * Status of an in-progress (or recently completed) auto-reconnection attempt.
 *
 * Phase 2A: emitted by PlaybackService translating AutoReconnectManager's
 * five callbacks into a single StateFlow. UI observers read this flow via
 * ConnectionCoordinator.reconnectStatus to render the "Reconnecting..."
 * overlay, success snackbar, or failure dialog.
 *
 * The retry logic that drives these transitions is unchanged from today --
 * Phase 2B is where Coordinator takes ownership of that loop and the
 * dueling-timer problem is finally killed.
 */
sealed class ReconnectStatus {
    object Idle : ReconnectStatus()

    data class Attempting(
        val serverId: String,
        val attempt: Int,
        val maxAttempts: Int,
        val method: ConnectionType?,
    ) : ReconnectStatus()

    data class Succeeded(val serverId: String) : ReconnectStatus()

    data class Failed(val serverId: String, val error: String) : ReconnectStatus()
}
