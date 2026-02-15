package com.sendspindroid.sendspin

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Handles Music Assistant authentication via WebSocket API.
 *
 * Music Assistant uses WebSocket for authentication, not HTTP REST.
 * The login command is sent as a JSON message over the `/ws` endpoint.
 *
 * ## Usage
 * ```kotlin
 * try {
 *     val result = MusicAssistantAuth.login(
 *         baseUrl = "https://music.home.example.com",
 *         username = "chris",
 *         password = "secret123"
 *     )
 *     // Use result.accessToken for sendspin WebSocket authentication
 * } catch (e: AuthenticationException) {
 *     // Invalid credentials
 * } catch (e: IOException) {
 *     // Network error
 * }
 * ```
 *
 * ## Security Notes
 * - Passwords are only transmitted over WSS (enforced by URL conversion)
 * - Passwords are never stored - only the returned access token is saved
 * - Access tokens may expire; handle 401 responses by prompting re-login
 */
object MusicAssistantAuth {

    private const val TAG = "MusicAssistantAuth"
    private const val LOGIN_TIMEOUT_MS = 15000L

    /**
     * Result of a successful login operation.
     *
     * @property accessToken The access token for API/WebSocket authentication
     * @property userId The user's ID in Music Assistant (may be empty)
     * @property userName The user's display name
     * @property baseUrl The server's base URL from the server info message
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
     * Exception thrown when authentication fails due to invalid credentials.
     */
    class AuthenticationException(message: String) : Exception(message)

    /**
     * Exception thrown when the server returns an unexpected response.
     */
    class ServerException(message: String) : Exception(message)

    // Shared OkHttp client with reasonable timeouts
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Required for WebSocket
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    /**
     * Login to Music Assistant with username/password credentials.
     *
     * Connects to the MA WebSocket at `/ws` and sends an `auth/login` command.
     * On success, returns a [LoginResult] containing the access token.
     *
     * @param baseUrl The MA server base URL (e.g., "https://music.home.example.com")
     * @param username The MA username
     * @param password The MA password
     * @return [LoginResult] with access token on success
     * @throws AuthenticationException if credentials are invalid
     * @throws ServerException if server returns an unexpected error
     * @throws IOException if network request fails
     */
    suspend fun login(baseUrl: String, username: String, password: String): LoginResult {
        return withContext(Dispatchers.IO) {
            // Strip /sendspin suffix if present - login uses /ws endpoint
            val cleanBaseUrl = baseUrl.trimEnd('/')
                .removeSuffix("/sendspin")
                .removeSuffix("/ws")
            val wsUrl = convertToWebSocketUrl("$cleanBaseUrl/ws")
            Log.d(TAG, "Connecting to MA WebSocket for login: $wsUrl")

            val result = CompletableDeferred<LoginResult>()
            var webSocket: WebSocket? = null
            val messageId = UUID.randomUUID().toString()

            val listener = object : WebSocketListener() {
                private var serverInfoReceived = false
                private var capturedServerVersion = "unknown"
                private var capturedMaServerId = ""
                private var capturedBaseUrl = ""

                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected, waiting for server info...")
                    webSocket = ws
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    Log.d(TAG, "Received: ${text.take(200)}...")

                    try {
                        val json = JSONObject(text)

                        // First message should be server info
                        if (!serverInfoReceived && json.has("server_id")) {
                            serverInfoReceived = true
                            capturedServerVersion = json.optString("server_version", "unknown")
                            capturedMaServerId = json.optString("server_id", "")
                            capturedBaseUrl = json.optString("base_url", "")
                            Log.d(TAG, "Server info received (version=$capturedServerVersion), sending login command")

                            // Send auth/login command
                            val loginMsg = JSONObject().apply {
                                put("message_id", messageId)
                                put("command", "auth/login")
                                put("args", JSONObject().apply {
                                    put("username", username)
                                    put("password", password)
                                    put("device_name", "SendSpinDroid")
                                })
                            }
                            ws.send(loginMsg.toString())
                            return
                        }

                        // Check if this is our login response
                        if (json.optString("message_id") == messageId) {
                            // Check for error
                            if (json.has("error_code")) {
                                val errorCode = json.getString("error_code")
                                val details = json.optString("details", "Authentication failed")
                                Log.e(TAG, "Login failed: $errorCode - $details")
                                ws.close(1000, "Login failed")
                                result.completeExceptionally(AuthenticationException(details))
                                return
                            }

                            // Parse success response
                            val resultObj = json.optJSONObject("result")
                            if (resultObj != null) {
                                // Token might be in 'access_token' or 'token'
                                val token = resultObj.optString("access_token", "")
                                    .ifEmpty { resultObj.optString("token", "") }

                                if (token.isBlank()) {
                                    ws.close(1000, "No token in response")
                                    result.completeExceptionally(
                                        ServerException("Server response missing access token")
                                    )
                                    return
                                }

                                val userObj = resultObj.optJSONObject("user")
                                val userId = userObj?.optString("user_id", "") ?: ""
                                val userName = userObj?.optString("display_name", "")
                                    ?: userObj?.optString("username", username)
                                    ?: username

                                Log.d(TAG, "Login successful for user: $userName")
                                ws.close(1000, "Login complete")
                                result.complete(LoginResult(
                                    accessToken = token,
                                    userId = userId,
                                    userName = userName,
                                    serverVersion = capturedServerVersion,
                                    maServerId = capturedMaServerId,
                                    baseUrl = capturedBaseUrl
                                ))
                            } else {
                                ws.close(1000, "Invalid response")
                                result.completeExceptionally(
                                    ServerException("Invalid server response format")
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing message", e)
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure", t)
                    if (!result.isCompleted) {
                        result.completeExceptionally(
                            IOException("Connection failed: ${t.message}", t)
                        )
                    }
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code $reason")
                    if (!result.isCompleted) {
                        result.completeExceptionally(
                            IOException("Connection closed before login complete")
                        )
                    }
                }
            }

            val request = Request.Builder()
                .url(wsUrl)
                .build()

            httpClient.newWebSocket(request, listener)

            try {
                withTimeout(LOGIN_TIMEOUT_MS) {
                    result.await()
                }
            } catch (e: TimeoutCancellationException) {
                webSocket?.close(1000, "Login timeout")
                throw IOException("Login timed out after ${LOGIN_TIMEOUT_MS / 1000} seconds")
            }
        }
    }

    /**
     * Convert HTTP/HTTPS URL to WebSocket URL.
     */
    private fun convertToWebSocketUrl(url: String): String {
        return when {
            url.startsWith("https://") -> url.replaceFirst("https://", "wss://")
            url.startsWith("http://") -> url.replaceFirst("http://", "ws://")
            url.startsWith("wss://") || url.startsWith("ws://") -> url
            else -> "wss://$url"
        }
    }
}
