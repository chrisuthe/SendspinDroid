package com.sendspindroid.model

/**
 * Represents the app's connection state machine.
 *
 * Flow:
 * ```
 * SERVER_LIST ──[user taps server]──► CONNECTING ──[success]──► CONNECTED
 *     ▲                                    │
 *     │                                    └─[failed]──► SERVER_LIST + Error
 *     │
 * CONNECTED ──[disconnect]──► SERVER_LIST
 *          ──[error]──► RECONNECTING ──► CONNECTED
 * ```
 *
 * The ServerList state shows both saved servers and real-time mDNS discovered servers.
 * If a default server is configured, auto-connect happens after a brief delay.
 */
sealed class AppConnectionState {
    /**
     * Initial state - shows unified server list.
     * Displays saved servers and discovered servers in sections.
     * mDNS discovery runs in the background, updating the discovered list.
     */
    object ServerList : AppConnectionState()

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
     * Error state with message.
     */
    data class Error(val message: String, val canRetry: Boolean = true) : AppConnectionState()
}
