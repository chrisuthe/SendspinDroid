package com.sendspindroid.model

/**
 * Represents the app's connection state machine.
 *
 * Flow:
 * ```
 * SEARCHING ──[server found]──► CONNECTING ──[success]──► CONNECTED
 *     │                              │
 *     │                              └─[failed]──► SEARCHING (retry)
 *     │
 *     └─[timeout 10s]──► MANUAL_ENTRY
 *
 * CONNECTED ──[disconnect]──► MANUAL_ENTRY (user chose to disconnect)
 *          ──[error]──► RECONNECTING ──► CONNECTED
 * ```
 */
sealed class AppConnectionState {
    /**
     * Initial state - searching for servers via mDNS.
     * Auto-connects to first discovered server.
     */
    object Searching : AppConnectionState()

    /**
     * Attempting to connect to a server.
     */
    data class Connecting(val serverName: String, val serverAddress: String) : AppConnectionState()

    /**
     * Successfully connected to a server.
     */
    data class Connected(val serverName: String, val serverAddress: String) : AppConnectionState()

    /**
     * Connection lost, attempting to reconnect with backoff.
     */
    data class Reconnecting(
        val serverName: String,
        val serverAddress: String,
        val attempt: Int,
        val nextRetrySeconds: Int
    ) : AppConnectionState()

    /**
     * Manual entry mode - shown when:
     * - Discovery times out without finding servers
     * - User manually disconnects
     * - User wants to enter a server address manually
     */
    object ManualEntry : AppConnectionState()

    /**
     * Error state with message.
     */
    data class Error(val message: String, val canRetry: Boolean = true) : AppConnectionState()
}
