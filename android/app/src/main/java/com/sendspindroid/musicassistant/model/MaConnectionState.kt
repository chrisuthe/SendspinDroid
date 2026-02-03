package com.sendspindroid.musicassistant.model

/**
 * Connection state for the Music Assistant API.
 *
 * Tracks the lifecycle of MA API connectivity, from initial availability
 * check through authentication and active connection.
 *
 * ## State Flow
 * ```
 * Server Connected
 *       |
 *       v
 * [Check isMusicAssistant]
 *       |
 *   No  |  Yes
 *   v   |   v
 * Unavailable  [Check API Endpoint]
 *              |
 *          None | Has endpoint
 *           v   |    v
 *     Unavailable   [Check Token]
 *                   |
 *              None | Has token
 *               v   |    v
 *          NeedsAuth   Connecting
 *                           |
 *                     Fail  |  Success
 *                      v    |    v
 *                    Error  | Connected
 * ```
 */
sealed class MaConnectionState {

    /**
     * MA API is not available for this server.
     *
     * Reasons:
     * - Server is not marked as Music Assistant (isMusicAssistant = false)
     * - No reachable API endpoint (Remote-only without local/proxy fallback)
     */
    object Unavailable : MaConnectionState()

    /**
     * MA server detected but no authentication token available.
     *
     * This typically means:
     * - Old server config from before MA feature was added
     * - Token was manually cleared
     * - Token expired and was removed
     *
     * UI should prompt user to re-login.
     */
    object NeedsAuth : MaConnectionState()

    /**
     * Attempting to connect to MA API.
     *
     * May be authenticating with stored token or performing fresh login.
     */
    object Connecting : MaConnectionState()

    /**
     * Successfully connected and authenticated to MA API.
     *
     * @property serverInfo Information about the connected MA server
     */
    data class Connected(val serverInfo: MaServerInfo) : MaConnectionState()

    /**
     * Connection or authentication failed.
     *
     * @property message Human-readable error description
     * @property isAuthError True if this was an authentication failure (token expired, etc.)
     */
    data class Error(
        val message: String,
        val isAuthError: Boolean = false
    ) : MaConnectionState()

    /**
     * Returns true if MA features should be shown in the UI.
     * Only true when fully connected.
     */
    val isAvailable: Boolean
        get() = this is Connected

    /**
     * Returns true if we're in the process of connecting.
     */
    val isConnecting: Boolean
        get() = this is Connecting

    /**
     * Returns true if user action is needed (login required).
     */
    val needsUserAction: Boolean
        get() = this is NeedsAuth || (this is Error && isAuthError)
}

/**
 * Information about a connected Music Assistant server.
 *
 * @property serverId The MA server's unique identifier
 * @property serverVersion The MA server version string
 * @property apiUrl The WebSocket URL used to connect
 */
data class MaServerInfo(
    val serverId: String,
    val serverVersion: String,
    val apiUrl: String
)
