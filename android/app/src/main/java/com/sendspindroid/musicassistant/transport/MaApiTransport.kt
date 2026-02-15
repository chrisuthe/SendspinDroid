package com.sendspindroid.musicassistant.transport

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Persistent transport for Music Assistant API commands.
 *
 * Supports both WebSocket (LOCAL/PROXY) and WebRTC DataChannel (REMOTE)
 * backends. Handles the MA protocol: ServerInfoMessage -> auth handshake,
 * then command multiplexing via message_id correlation.
 *
 * ## Connection Flow
 * ```
 * [Disconnected] -> connect(token) -> [Connecting]
 *                                         |
 *                      receive ServerInfo  |  send auth command
 *                                         v
 *                                   [Authenticating]
 *                                         |
 *                            auth success  |  auth failure
 *                                v         |       v
 *                           [Connected]    |    [Error]
 * ```
 *
 * ## Usage
 * ```kotlin
 * val transport: MaApiTransport = MaWebSocketTransport(apiUrl)
 * transport.connect(token)
 *
 * val result = transport.sendCommand("players/all")
 * val tracks = transport.sendCommand("music/search", mapOf("query" to "Beatles"))
 *
 * transport.disconnect()
 * ```
 */
interface MaApiTransport {

    /**
     * Transport connection state.
     */
    sealed class State {
        object Disconnected : State()
        object Connecting : State()
        object Authenticating : State()
        object Connected : State()
        data class Error(val message: String, val isAuthError: Boolean = false) : State()
    }

    /**
     * Current connection state. Observable via StateFlow.
     */
    val state: StateFlow<State>

    /**
     * Whether the transport is connected and authenticated.
     */
    val isConnected: Boolean
        get() = state.value is State.Connected

    /**
     * Server version string, available after successful authentication.
     */
    val serverVersion: String?

    /**
     * MA server ID, available after successful authentication.
     */
    val maServerId: String?

    /**
     * Connect and authenticate to the MA API.
     *
     * Handles the full handshake:
     * 1. Establish connection (WebSocket or DataChannel already open)
     * 2. Receive ServerInfoMessage
     * 3. Send auth command with token
     * 4. Wait for auth response
     *
     * @param token MA API access token (long-lived or short-lived)
     * @throws AuthenticationException if token is invalid or expired
     * @throws java.io.IOException on connection/network errors
     */
    suspend fun connect(token: String)

    /**
     * Connect and authenticate using username/password credentials.
     *
     * Performs the same handshake as [connect] but sends `auth/login` instead
     * of `auth`, which returns a fresh access token. This is used for:
     * - First-time login when no token exists (especially REMOTE mode)
     * - Re-authentication after token expiry
     *
     * @param username MA username
     * @param password MA password
     * @return [LoginResult] containing the access token and user info
     * @throws AuthenticationException if credentials are invalid
     * @throws java.io.IOException on connection/network errors
     */
    suspend fun connectWithCredentials(username: String, password: String): LoginResult

    /**
     * Send a command and wait for its response, correlated by message_id.
     *
     * The transport must be connected and authenticated before calling this.
     *
     * @param command MA API command path (e.g., "players/all", "music/search")
     * @param args Command arguments as key-value pairs
     * @param timeoutMs Maximum time to wait for the response
     * @return The full JSON response (includes message_id, result, etc.)
     * @throws java.io.IOException if transport is disconnected
     * @throws kotlinx.coroutines.TimeoutCancellationException if response not received in time
     * @throws MaCommandException if the server returns an error response
     */
    suspend fun sendCommand(
        command: String,
        args: Map<String, Any> = emptyMap(),
        timeoutMs: Long = 15000L
    ): JSONObject

    /**
     * Send an HTTP proxy request over the transport.
     *
     * Used in REMOTE mode to fetch images, previews, and other HTTP resources
     * that can't be accessed directly when only a WebRTC connection is available.
     *
     * The MA server gateway handles these by making local HTTP requests and
     * returning the response over the DataChannel.
     *
     * @param method HTTP method (typically "GET")
     * @param path Relative path (e.g., "/imageproxy?provider=plex&path=/library/...")
     * @param headers Optional HTTP headers
     * @param timeoutMs Maximum time to wait for the response
     * @return The proxy response including decoded body bytes
     * @throws UnsupportedOperationException if the transport doesn't support HTTP proxy
     */
    suspend fun httpProxy(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 15000L
    ): HttpProxyResponse

    /**
     * Set a listener for server-push events (unsolicited messages without message_id).
     *
     * Events include player state updates, queue changes, provider sync status, etc.
     */
    fun setEventListener(listener: EventListener?)

    /**
     * Disconnect and release resources.
     *
     * Cancels all pending commands and transitions to Disconnected state.
     */
    fun disconnect()

    // ========================================================================
    // Supporting types
    // ========================================================================

    /**
     * Listener for server-push events.
     */
    interface EventListener {
        /** Called when a server-push event is received (no message_id). */
        fun onEvent(event: JSONObject)

        /** Called when the transport disconnects unexpectedly. */
        fun onDisconnected(reason: String)
    }

    /**
     * Response from an HTTP proxy request.
     */
    data class HttpProxyResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HttpProxyResponse) return false
            return status == other.status && headers == other.headers && body.contentEquals(other.body)
        }

        override fun hashCode(): Int {
            var result = status
            result = 31 * result + headers.hashCode()
            result = 31 * result + body.contentHashCode()
            return result
        }
    }

    /**
     * Result of a successful login via [connectWithCredentials].
     *
     * @property accessToken The access token for future API authentication
     * @property userId The user's ID in Music Assistant
     * @property userName The user's display name
     */
    data class LoginResult(
        val accessToken: String,
        val userId: String,
        val userName: String
    )

    /**
     * Exception thrown when an MA API command returns an error response.
     */
    class MaCommandException(
        val errorCode: String,
        val details: String
    ) : Exception("MA API error $errorCode: $details")

    /**
     * Exception thrown when authentication fails.
     */
    class AuthenticationException(message: String) : Exception(message)
}
