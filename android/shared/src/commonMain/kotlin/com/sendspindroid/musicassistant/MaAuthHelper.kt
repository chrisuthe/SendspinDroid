package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.transport.MaApiTransport
import com.sendspindroid.musicassistant.transport.MaWebSocketTransport

/**
 * Standalone MA authentication helper for login flows.
 *
 * Creates a temporary [MaWebSocketTransport], performs username/password login,
 * captures the access token and server metadata, then disconnects. This replaces
 * the old OkHttp-based `MusicAssistantAuth` with the shared Ktor transport.
 *
 * ## Usage
 * ```kotlin
 * val result = MaAuthHelper.loginForToken(
 *     url = "wss://music.home.example.com/ws",
 *     username = "chris",
 *     password = "secret123"
 * )
 * // result.accessToken, result.serverVersion, result.baseUrl, etc.
 * ```
 *
 * @see MaWebSocketTransport.connectWithCredentials
 */
object MaAuthHelper {

    /**
     * Result of a successful login, combining the access token with server metadata
     * captured from the ServerInfoMessage during the WebSocket handshake.
     *
     * @property accessToken The access token for future API authentication
     * @property userId The user's ID in Music Assistant
     * @property userName The user's display name
     * @property serverVersion MA server version from ServerInfoMessage
     * @property maServerId MA server ID from ServerInfoMessage
     * @property baseUrl Server's base URL from ServerInfoMessage (may be empty)
     */
    data class LoginResult(
        val accessToken: String,
        val userId: String,
        val userName: String,
        val serverVersion: String = "unknown",
        val maServerId: String = "",
        val baseUrl: String = ""
    )

    /**
     * Login to Music Assistant with username/password credentials.
     *
     * Creates a temporary WebSocket connection, performs the auth/login handshake,
     * captures the token and server info, then disconnects.
     *
     * @param url The MA API WebSocket URL (e.g., "ws://192.168.1.100:8095/ws")
     *            HTTP/HTTPS URLs are automatically converted to WS/WSS.
     * @param username The MA username
     * @param password The MA password
     * @return [LoginResult] with access token and server metadata
     * @throws MaApiTransport.AuthenticationException if credentials are invalid
     * @throws Exception on connection/network errors
     */
    suspend fun loginForToken(
        url: String,
        username: String,
        password: String
    ): LoginResult {
        // Strip /sendspin suffix if present — login uses /ws endpoint
        val cleanUrl = url.trimEnd('/')
            .removeSuffix("/sendspin")
            .removeSuffix("/ws")
        val wsUrl = "$cleanUrl/ws"

        val transport = MaWebSocketTransport(wsUrl)
        return try {
            val loginResult = transport.connectWithCredentials(username, password)

            LoginResult(
                accessToken = loginResult.accessToken,
                userId = loginResult.userId,
                userName = loginResult.userName,
                serverVersion = transport.serverVersion ?: "unknown",
                maServerId = transport.maServerId ?: "",
                baseUrl = transport.baseUrl ?: ""
            )
        } finally {
            transport.disconnect()
        }
    }
}
