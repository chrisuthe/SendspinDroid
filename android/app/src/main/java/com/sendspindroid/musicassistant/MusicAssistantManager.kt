package com.sendspindroid.musicassistant

import android.content.Context
import android.util.Log
import com.sendspindroid.UserSettings
import com.sendspindroid.UserSettings.ConnectionMode
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.model.MaConnectionState
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType
import com.sendspindroid.musicassistant.model.MaServerInfo
import com.sendspindroid.sendspin.MusicAssistantAuth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

// ============================================================================
// Data Models for Music Assistant API responses
// ============================================================================

/**
 * Represents a track from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters and generic lists.
 * Renamed from MaMediaItem to better reflect its specific purpose as tracks.
 */
data class MaTrack(
    val itemId: String,
    override val name: String,
    val artist: String?,
    val album: String?,
    override val imageUri: String?,
    override val uri: String?,
    val duration: Long? = null,
    // Album reference fields for grouping
    val albumId: String? = null,
    val albumType: String? = null  // "album", "single", "ep", "compilation"
) : MaLibraryItem {
    override val id: String get() = itemId
    override val mediaType: MaMediaType = MaMediaType.TRACK
}

/**
 * Type alias for backward compatibility during migration.
 * TODO: Remove after all usages are migrated to MaTrack.
 */
@Deprecated("Use MaTrack instead", ReplaceWith("MaTrack"))
typealias MaMediaItem = MaTrack

/**
 * Represents a playlist from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters.
 */
data class MaPlaylist(
    val playlistId: String,
    override val name: String,
    override val imageUri: String?,
    val trackCount: Int,
    val owner: String?,
    override val uri: String?
) : MaLibraryItem {
    override val id: String get() = playlistId
    override val mediaType: MaMediaType = MaMediaType.PLAYLIST
}

/**
 * Represents an album from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters.
 * Subtitle rendering: "Artist Name" or "Artist Name - 2024"
 */
data class MaAlbum(
    val albumId: String,
    override val name: String,
    override val imageUri: String?,
    override val uri: String?,
    val artist: String?,          // Primary artist name
    val year: Int?,               // Release year
    val trackCount: Int?,         // Number of tracks
    val albumType: String?        // "album", "single", "ep", "compilation"
) : MaLibraryItem {
    override val id: String get() = albumId
    override val mediaType: MaMediaType = MaMediaType.ALBUM
}

/**
 * Represents an artist from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters.
 * Subtitle rendering: Empty (or genre if available later)
 */
data class MaArtist(
    val artistId: String,
    override val name: String,
    override val imageUri: String?,
    override val uri: String?
) : MaLibraryItem {
    override val id: String get() = artistId
    override val mediaType: MaMediaType = MaMediaType.ARTIST
}

/**
 * Represents a radio station from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters.
 * Subtitle rendering: Provider name (e.g., "TuneIn")
 */
data class MaRadio(
    val radioId: String,
    override val name: String,
    override val imageUri: String?,
    override val uri: String?,
    val provider: String?         // "tunein", "radiobrowser", etc.
) : MaLibraryItem {
    override val id: String get() = radioId
    override val mediaType: MaMediaType = MaMediaType.RADIO
}

/**
 * Global singleton managing Music Assistant API availability.
 *
 * Provides a single source of truth for whether MA features should be
 * shown in the UI. Components observe [isAvailable] to conditionally
 * show features like:
 * - Browse Library
 * - Queue Management
 * - Choose Players
 *
 * ## Lifecycle
 * - Called by PlaybackService when connecting/disconnecting from servers
 * - Automatically handles token authentication when available
 * - Exposes connection state for UI feedback
 *
 * ## Usage
 * ```kotlin
 * // Simple availability check in Activity/Fragment
 * lifecycleScope.launch {
 *     MusicAssistantManager.isAvailable.collect { available ->
 *         choosePlayersButton.isVisible = available
 *     }
 * }
 *
 * // Detailed state for error handling
 * MusicAssistantManager.connectionState.collect { state ->
 *     when (state) {
 *         is MaConnectionState.NeedsAuth -> showReLoginDialog()
 *         is MaConnectionState.Connected -> showMaFeatures()
 *         is MaConnectionState.Error -> showError(state.message)
 *         else -> hideMaFeatures()
 *     }
 * }
 * ```
 */
object MusicAssistantManager {

    private const val TAG = "MusicAssistantManager"

    // Internal mutable state
    private val _connectionState = MutableStateFlow<MaConnectionState>(MaConnectionState.Unavailable)

    /**
     * Detailed connection state for UI components that need to handle
     * different scenarios (error messages, re-login prompts, etc.).
     */
    val connectionState: StateFlow<MaConnectionState> = _connectionState.asStateFlow()

    /**
     * Simple boolean availability check.
     * True only when fully connected and authenticated to MA API.
     * Use this for simple visibility toggles.
     */
    val isAvailable: StateFlow<Boolean> = MutableStateFlow(false).also { flow ->
        // This is a derived flow - updated when connectionState changes
        // Actual implementation uses connectionState.map { it.isAvailable }
    }

    // Current server info (when connected)
    private var currentServer: UnifiedServer? = null
    private var currentConnectionMode: ConnectionMode? = null
    private var currentApiUrl: String? = null

    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var applicationContext: Context? = null

    /**
     * Initialize MusicAssistantManager with application context.
     * Called during app startup.
     */
    fun initialize(context: Context) {
        if (applicationContext == null) {
            synchronized(this) {
                if (applicationContext == null) {
                    applicationContext = context.applicationContext
                    // Also initialize MaSettings
                    MaSettings.initialize(context.applicationContext)
                }
            }
        }
    }

    /**
     * Called by PlaybackService when a server connection is established.
     *
     * Checks if MA API should be available for this server and connection mode,
     * then attempts authentication if a token is stored.
     *
     * @param server The connected UnifiedServer
     * @param connectionMode The active connection mode (LOCAL, REMOTE, or PROXY)
     */
    fun onServerConnected(server: UnifiedServer, connectionMode: ConnectionMode) {
        Log.d(TAG, "Server connected: ${server.name}, mode=$connectionMode, isMusicAssistant=${server.isMusicAssistant}")

        currentServer = server
        currentConnectionMode = connectionMode

        // Check 1: Is this server marked as Music Assistant?
        if (!server.isMusicAssistant) {
            Log.d(TAG, "Server is not marked as Music Assistant")
            _connectionState.value = MaConnectionState.Unavailable
            return
        }

        // Check 2: Can we reach the MA API?
        val apiUrl = MaApiEndpoint.deriveUrl(server, connectionMode)
        if (apiUrl == null) {
            Log.d(TAG, "No MA API endpoint available for connection mode $connectionMode")
            _connectionState.value = MaConnectionState.Unavailable
            return
        }

        currentApiUrl = apiUrl
        Log.d(TAG, "MA API URL derived: $apiUrl")

        // Check 3: Do we have a stored token?
        val token = MaSettings.getTokenForServer(server.id)
        if (token != null) {
            Log.d(TAG, "Found stored token, attempting authentication")
            connectWithToken(apiUrl, token, server.id)
        } else {
            // No token stored - shouldn't happen with eager auth in wizard,
            // but handle gracefully for old server configs
            Log.w(TAG, "No token stored for MA server - user needs to re-authenticate")
            _connectionState.value = MaConnectionState.NeedsAuth
        }
    }

    /**
     * Called by PlaybackService when disconnecting from a server.
     */
    fun onServerDisconnected() {
        Log.d(TAG, "Server disconnected")
        currentServer = null
        currentConnectionMode = null
        currentApiUrl = null
        _connectionState.value = MaConnectionState.Unavailable
    }

    /**
     * Attempt to authenticate with a stored token.
     *
     * For now, this validates the token by making a simple API call.
     * In the future, this will establish a persistent WebSocket connection.
     */
    private fun connectWithToken(apiUrl: String, token: String, serverId: String) {
        _connectionState.value = MaConnectionState.Connecting

        scope.launch {
            try {
                // For now, we assume the token is valid if it exists
                // In Phase 2 (MusicAssistantClient), we'll validate by connecting
                // and making an API call like fetching players

                // TODO: Replace with actual MusicAssistantClient connection
                // val client = MusicAssistantClient()
                // client.connect(apiUrl)
                // client.authWithToken(token)
                // val serverInfo = client.getServerInfo()

                // For now, create a placeholder server info
                val serverInfo = MaServerInfo(
                    serverId = serverId,
                    serverVersion = "unknown", // Will be populated by actual API call
                    apiUrl = apiUrl
                )

                Log.i(TAG, "MA API connected successfully")
                _connectionState.value = MaConnectionState.Connected(serverInfo)

                // Auto-select a player for playback commands
                // This ensures we have a consistent target for play commands
                autoSelectPlayer().fold(
                    onSuccess = { playerId ->
                        Log.i(TAG, "Auto-selected player for playback: $playerId")
                    },
                    onFailure = { error ->
                        Log.w(TAG, "No players available for auto-selection: ${error.message}")
                    }
                )

            } catch (e: MusicAssistantAuth.AuthenticationException) {
                Log.e(TAG, "Token authentication failed", e)
                // Token expired or invalid - clear it and request re-login
                MaSettings.clearTokenForServer(serverId)
                _connectionState.value = MaConnectionState.Error(
                    message = "Authentication expired. Please log in again.",
                    isAuthError = true
                )
            } catch (e: IOException) {
                Log.e(TAG, "Network error connecting to MA API", e)
                _connectionState.value = MaConnectionState.Error(
                    message = "Network error: ${e.message}",
                    isAuthError = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to MA API", e)
                _connectionState.value = MaConnectionState.Error(
                    message = e.message ?: "Unknown error",
                    isAuthError = false
                )
            }
        }
    }

    /**
     * Perform fresh login with username/password credentials.
     *
     * Called when user needs to re-authenticate (token expired, new setup, etc.).
     * On success, stores the token for future use.
     *
     * @param username MA username
     * @param password MA password
     * @return true if login succeeded
     */
    suspend fun login(username: String, password: String): Boolean {
        val server = currentServer ?: return false
        val apiUrl = currentApiUrl ?: return false

        _connectionState.value = MaConnectionState.Connecting

        return try {
            val result = MusicAssistantAuth.login(apiUrl, username, password)

            // Store the token for future connections
            MaSettings.setTokenForServer(server.id, result.accessToken)

            val serverInfo = MaServerInfo(
                serverId = server.id,
                serverVersion = "unknown",
                apiUrl = apiUrl
            )

            Log.i(TAG, "MA login successful for user: ${result.userName}")
            _connectionState.value = MaConnectionState.Connected(serverInfo)
            true

        } catch (e: MusicAssistantAuth.AuthenticationException) {
            Log.e(TAG, "Login failed: invalid credentials", e)
            _connectionState.value = MaConnectionState.Error(
                message = "Invalid username or password",
                isAuthError = true
            )
            false
        } catch (e: IOException) {
            Log.e(TAG, "Login failed: network error", e)
            _connectionState.value = MaConnectionState.Error(
                message = "Network error: ${e.message}",
                isAuthError = false
            )
            false
        } catch (e: Exception) {
            Log.e(TAG, "Login failed", e)
            _connectionState.value = MaConnectionState.Error(
                message = e.message ?: "Login failed",
                isAuthError = false
            )
            false
        }
    }

    /**
     * Authenticate with an existing token.
     *
     * Called when re-establishing connection after app restart.
     *
     * @param token The stored access token
     * @return true if authentication succeeded
     */
    suspend fun authWithToken(token: String): Boolean {
        val server = currentServer ?: return false
        val apiUrl = currentApiUrl ?: return false

        _connectionState.value = MaConnectionState.Connecting

        return try {
            // TODO: Implement actual token validation via MusicAssistantClient
            val serverInfo = MaServerInfo(
                serverId = server.id,
                serverVersion = "unknown",
                apiUrl = apiUrl
            )

            Log.i(TAG, "MA token auth successful")
            _connectionState.value = MaConnectionState.Connected(serverInfo)
            true

        } catch (e: Exception) {
            Log.e(TAG, "Token auth failed", e)
            _connectionState.value = MaConnectionState.Error(
                message = "Authentication failed: ${e.message}",
                isAuthError = true
            )
            false
        }
    }

    /**
     * Clear authentication state and request re-login.
     *
     * Call this when the user wants to switch accounts or when
     * authentication errors occur.
     */
    fun clearAuth() {
        currentServer?.let { server ->
            MaSettings.clearTokenForServer(server.id)
        }
        _connectionState.value = MaConnectionState.NeedsAuth
    }

    /**
     * Returns the current MA API URL if connected.
     */
    fun getApiUrl(): String? = currentApiUrl

    /**
     * Returns the current server if connected.
     */
    fun getCurrentServer(): UnifiedServer? = currentServer

    // ========================================================================
    // Music Assistant API Commands
    // ========================================================================

    // Shared OkHttp client for WebSocket connections
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Required for WebSocket
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private const val COMMAND_TIMEOUT_MS = 15000L

    /**
     * Add the currently playing track to MA favorites.
     *
     * This is a two-step operation:
     * 1. Query MA players to find the currently playing track's URI
     * 2. Call the favorites/add_item API with that URI
     *
     * @return Result with success message or failure exception
     */
    suspend fun favoriteCurrentTrack(): Result<String> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                // Step 1: Get players to find the currently playing item
                Log.d(TAG, "Querying MA players to find current track...")
                val playersResponse = sendMaCommand(apiUrl, token, "players/all", emptyMap())

                val currentItemUri = parseCurrentItemUri(playersResponse)
                if (currentItemUri == null) {
                    Log.w(TAG, "No currently playing track found in MA players")
                    return@withContext Result.failure(Exception("No track currently playing"))
                }

                Log.d(TAG, "Found current track URI: $currentItemUri")

                // Step 2: Add to favorites
                Log.d(TAG, "Adding track to favorites...")
                sendMaCommand(apiUrl, token, "music/favorites/add_item", mapOf("item" to currentItemUri))

                Log.i(TAG, "Successfully added track to favorites")
                Result.success("Added to favorites")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to favorite track", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Play a media item on the active MA player.
     *
     * This sends a play command to Music Assistant with the item's URI.
     * Works for tracks, albums, artists, playlists, and radio stations.
     *
     * @param uri The Music Assistant URI (e.g., "library://track/123")
     * @param mediaType Optional media type hint for the API
     * @return Result with success or failure
     */
    suspend fun playMedia(uri: String, mediaType: String? = null): Result<Unit> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Playing media: $uri")

                // Use THIS app's player ID - the same ID we registered with SendSpin
                // This ensures playback goes to OUR queue, not some other player
                val playerId = UserSettings.getPlayerId()
                Log.d(TAG, "Using our player ID: $playerId")

                // Build play command arguments
                val args = mutableMapOf<String, Any>(
                    "queue_id" to playerId,
                    "media" to uri
                )
                if (mediaType != null) {
                    args["media_type"] = mediaType
                }

                // Send play command - play_media replaces queue and starts playback
                sendMaCommand(apiUrl, token, "player_queues/play_media", args)

                Log.i(TAG, "Successfully started playback: $uri")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play media: $uri", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Auto-detect and store the active player for the current server.
     *
     * Call this after MA connection is established to set a default player
     * for playback commands. This ensures consistent behavior - the same player
     * will be used for all playback until changed.
     *
     * @return Result with the selected player ID, or failure if no players found
     */
    suspend fun autoSelectPlayer(): Result<String> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                // Check if we already have a selected player
                val existingPlayer = MaSettings.getSelectedPlayerForServer(server.id)
                if (existingPlayer != null) {
                    Log.d(TAG, "Player already selected: $existingPlayer")
                    return@withContext Result.success(existingPlayer)
                }

                // Fetch all players and select one
                val playersResponse = sendMaCommand(apiUrl, token, "players/all", emptyMap())
                val playerId = parseActivePlayerId(playersResponse)
                if (playerId == null) {
                    Log.w(TAG, "No available players found")
                    return@withContext Result.failure(Exception("No players available"))
                }

                // Store for future use
                Log.i(TAG, "Auto-selected player: $playerId")
                MaSettings.setSelectedPlayerForServer(server.id, playerId)
                Result.success(playerId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-select player", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get the currently selected player for the connected server.
     *
     * @return The selected player ID, or null if none selected
     */
    fun getSelectedPlayer(): String? {
        val server = currentServer ?: return null
        return MaSettings.getSelectedPlayerForServer(server.id)
    }

    /**
     * Set the player to use for playback commands.
     *
     * @param playerId The MA player_id to use
     */
    fun setSelectedPlayer(playerId: String) {
        val server = currentServer ?: return
        Log.i(TAG, "Selected player set to: $playerId")
        MaSettings.setSelectedPlayerForServer(server.id, playerId)
    }

    /**
     * Clear the selected player, reverting to auto-detection.
     */
    fun clearSelectedPlayer() {
        val server = currentServer ?: return
        Log.i(TAG, "Selected player cleared")
        MaSettings.clearSelectedPlayerForServer(server.id)
    }

    /**
     * Parse the active player ID from the players/all response.
     *
     * Returns the first player that is currently playing, or if none are playing,
     * returns the first available player.
     *
     * @param response The JSON response from players/all command
     * @return The player ID, or null if no players found
     */
    private fun parseActivePlayerId(response: JSONObject): String? {
        val players = response.optJSONArray("result")
            ?: response.optJSONObject("result")?.optJSONArray("players")
            ?: return null

        var firstPlayerId: String? = null

        for (i in 0 until players.length()) {
            val player = players.optJSONObject(i) ?: continue
            val playerId = player.optString("player_id", "")
                .ifEmpty { player.optString("id", "") }
            val state = player.optString("state", "")
            val available = player.optBoolean("available", true)

            if (playerId.isEmpty() || !available) continue

            // Remember first available player as fallback
            if (firstPlayerId == null) {
                firstPlayerId = playerId
            }

            // Prefer currently playing player
            if (state == "playing" || state == "paused") {
                return playerId
            }
        }

        return firstPlayerId
    }

    /**
     * Parse the currently playing item's URI from the players/all response.
     *
     * Searches through all players to find one with state="playing" and
     * extracts the current_item.uri field.
     *
     * @param response The JSON response from players/all command
     * @return The URI of the currently playing item, or null if none found
     */
    private fun parseCurrentItemUri(response: JSONObject): String? {
        // Result can be an array of players or a map with players
        val players = response.optJSONArray("result")
            ?: response.optJSONObject("result")?.let { result ->
                // Some responses wrap players in a "players" field
                result.optJSONArray("players")
            }
            ?: return null

        for (i in 0 until players.length()) {
            val player = players.optJSONObject(i) ?: continue
            val state = player.optString("state", "")

            // Look for a playing player
            if (state == "playing") {
                // Try current_item first (queue-based playback)
                val currentItem = player.optJSONObject("current_item")
                    ?: player.optJSONObject("current_media")

                if (currentItem != null) {
                    val uri = currentItem.optString("uri", "")
                    if (uri.isNotBlank()) {
                        return uri
                    }

                    // Fallback: try media_item.uri
                    val mediaItem = currentItem.optJSONObject("media_item")
                    if (mediaItem != null) {
                        val mediaUri = mediaItem.optString("uri", "")
                        if (mediaUri.isNotBlank()) {
                            return mediaUri
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * Send a command to the Music Assistant WebSocket API.
     *
     * Opens a one-shot WebSocket connection, authenticates with token,
     * sends the command, waits for the response, and closes.
     *
     * @param apiUrl The MA WebSocket base URL (http/https, will be converted to ws/wss)
     * @param token The authentication token
     * @param command The MA command to execute (e.g., "players/all")
     * @param args Command arguments as a map
     * @return The JSON response object
     * @throws IOException on network errors
     * @throws MusicAssistantAuth.AuthenticationException on auth failures
     */
    private suspend fun sendMaCommand(
        apiUrl: String,
        token: String,
        command: String,
        args: Map<String, Any>
    ): JSONObject {
        return withContext(Dispatchers.IO) {
            // apiUrl from MaApiEndpoint already includes /ws path
            // Just ensure it's a WebSocket URL (ws:// or wss://)
            val wsUrl = convertToWebSocketUrl(apiUrl)
            Log.d(TAG, "Connecting to MA API: $wsUrl for command: $command")

            val result = CompletableDeferred<JSONObject>()
            var socketRef: WebSocket? = null
            val commandMessageId = UUID.randomUUID().toString()
            val authMessageId = UUID.randomUUID().toString()

            val listener = object : WebSocketListener() {
                private var serverInfoReceived = false
                private var authenticated = false

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "MA WebSocket connected, waiting for server info...")
                    socketRef = webSocket
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)

                        // First message: server info
                        if (!serverInfoReceived && json.has("server_id")) {
                            serverInfoReceived = true
                            Log.d(TAG, "Server info received, authenticating...")

                            // Send auth command with token (MA uses "auth" command)
                            val authMsg = JSONObject().apply {
                                put("message_id", authMessageId)
                                put("command", "auth")
                                put("args", JSONObject().apply {
                                    put("token", token)
                                })
                            }
                            webSocket.send(authMsg.toString())
                            return
                        }

                        // Auth response
                        if (json.optString("message_id") == authMessageId) {
                            if (json.has("error_code")) {
                                val errorCode = json.getString("error_code")
                                val details = json.optString("details", "Authentication failed")
                                Log.e(TAG, "Auth failed: $errorCode - $details")
                                webSocket.close(1000, "Auth failed")
                                result.completeExceptionally(
                                    MusicAssistantAuth.AuthenticationException(details)
                                )
                                return
                            }

                            authenticated = true
                            Log.d(TAG, "Authenticated, sending command: $command")

                            // Send the actual command
                            val cmdMsg = JSONObject().apply {
                                put("message_id", commandMessageId)
                                put("command", command)
                                if (args.isNotEmpty()) {
                                    put("args", JSONObject(args))
                                }
                            }
                            webSocket.send(cmdMsg.toString())
                            return
                        }

                        // Command response
                        if (json.optString("message_id") == commandMessageId) {
                            if (json.has("error_code")) {
                                val errorCode = json.getString("error_code")
                                val details = json.optString("details", "Command failed")
                                Log.e(TAG, "Command failed: $errorCode - $details")
                                webSocket.close(1000, "Command failed")
                                result.completeExceptionally(Exception("$errorCode: $details"))
                                return
                            }

                            Log.d(TAG, "Command response received")
                            webSocket.close(1000, "Done")
                            result.complete(json)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing MA message", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "MA WebSocket failure", t)
                    if (!result.isCompleted) {
                        result.completeExceptionally(IOException("Connection failed: ${t.message}", t))
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "MA WebSocket closed: $code $reason")
                    if (!result.isCompleted) {
                        result.completeExceptionally(IOException("Connection closed before command complete"))
                    }
                }
            }

            val request = Request.Builder()
                .url(wsUrl)
                .build()

            httpClient.newWebSocket(request, listener)

            try {
                withTimeout(COMMAND_TIMEOUT_MS) {
                    result.await()
                }
            } catch (e: TimeoutCancellationException) {
                socketRef?.close(1000, "Timeout")
                throw IOException("Command timed out after ${COMMAND_TIMEOUT_MS / 1000} seconds")
            }
        }
    }

    /**
     * Convert HTTP/HTTPS URL to WebSocket URL (ws/wss).
     */
    private fun convertToWebSocketUrl(url: String): String {
        return when {
            url.startsWith("https://") -> url.replaceFirst("https://", "wss://")
            url.startsWith("http://") -> url.replaceFirst("http://", "ws://")
            url.startsWith("wss://") || url.startsWith("ws://") -> url
            else -> "wss://$url"
        }
    }

    // ========================================================================
    // Home Screen API Methods
    // ========================================================================

    /**
     * Get recently played items from Music Assistant.
     *
     * Calls the music/recent endpoint which returns tracks, albums, etc.
     * that were recently played on this MA instance.
     *
     * @param limit Maximum number of items to return (default 15)
     * @return Result with list of recently played tracks
     */
    suspend fun getRecentlyPlayed(limit: Int = 15): Result<List<MaTrack>> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching recently played items (limit=$limit)")
                val response = sendMaCommand(
                    apiUrl, token, "music/recently_played_items",
                    mapOf("limit" to limit)
                )
                // Parse minimal track data - grouping won't work but tracks will display
                val items = parseMediaItems(response)
                Log.d(TAG, "Got ${items.size} recently played items")
                Result.success(items)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch recently played", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get recently added items from Music Assistant library.
     *
     * Calls music/library/items with ordering by timestamp_added DESC
     * to get the newest additions to the library.
     *
     * @param limit Maximum number of items to return (default 15)
     * @return Result with list of recently added tracks
     */
    suspend fun getRecentlyAdded(limit: Int = 15): Result<List<MaTrack>> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching recently added items (limit=$limit)")
                // Use music/tracks/library_items ordered by timestamp_added
                val response = sendMaCommand(
                    apiUrl, token, "music/tracks/library_items",
                    mapOf(
                        "limit" to limit,
                        "order_by" to "timestamp_added_desc"
                    )
                )
                val items = parseMediaItems(response)
                Log.d(TAG, "Got ${items.size} recently added items")
                Result.success(items)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch recently added", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get playlists from Music Assistant.
     *
     * @param limit Maximum number of playlists to return (default 15)
     * @param offset Number of items to skip for pagination (default 0)
     * @param orderBy Sort order (default "name")
     * @return Result with list of playlists
     */
    suspend fun getPlaylists(
        limit: Int = 15,
        offset: Int = 0,
        orderBy: String = "name"
    ): Result<List<MaPlaylist>> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching playlists (limit=$limit, offset=$offset, orderBy=$orderBy)")
                val response = sendMaCommand(
                    apiUrl, token, "music/playlists/library_items",
                    mapOf("limit" to limit, "offset" to offset, "order_by" to orderBy)
                )
                val playlists = parsePlaylists(response)
                Log.d(TAG, "Got ${playlists.size} playlists")
                Result.success(playlists)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch playlists", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get albums from Music Assistant library.
     *
     * @param limit Maximum number of albums to return (default 15)
     * @param offset Number of items to skip for pagination (default 0)
     * @param orderBy Sort order: "name", "timestamp_added_desc", "year" (default "name")
     * @return Result with list of albums
     */
    suspend fun getAlbums(
        limit: Int = 15,
        offset: Int = 0,
        orderBy: String = "name"
    ): Result<List<MaAlbum>> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching albums (limit=$limit, offset=$offset, orderBy=$orderBy)")
                val response = sendMaCommand(
                    apiUrl, token, "music/albums/library_items",
                    mapOf("limit" to limit, "offset" to offset, "order_by" to orderBy)
                )
                val albums = parseAlbums(response)
                Log.d(TAG, "Got ${albums.size} albums")
                Result.success(albums)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch albums", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get artists from Music Assistant library.
     *
     * @param limit Maximum number of artists to return (default 15)
     * @param offset Number of items to skip for pagination (default 0)
     * @param orderBy Sort order (default "name")
     * @return Result with list of artists
     */
    suspend fun getArtists(
        limit: Int = 15,
        offset: Int = 0,
        orderBy: String = "name"
    ): Result<List<MaArtist>> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching artists (limit=$limit, offset=$offset, orderBy=$orderBy)")
                val response = sendMaCommand(
                    apiUrl, token, "music/artists/library_items",
                    mapOf("limit" to limit, "offset" to offset, "order_by" to orderBy)
                )
                val artists = parseArtists(response)
                Log.d(TAG, "Got ${artists.size} artists")
                Result.success(artists)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch artists", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get radio stations from Music Assistant.
     *
     * @param limit Maximum number of radio stations to return (default 15)
     * @param offset Number of items to skip for pagination (default 0)
     * @param orderBy Sort order (default "name")
     * @return Result with list of radio stations
     */
    suspend fun getRadioStations(
        limit: Int = 15,
        offset: Int = 0,
        orderBy: String = "name"
    ): Result<List<MaRadio>> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching radio stations (limit=$limit, offset=$offset, orderBy=$orderBy)")
                // Note: MA API uses plural "radios" for the endpoint
                val response = sendMaCommand(
                    apiUrl, token, "music/radios/library_items",
                    mapOf("limit" to limit, "offset" to offset, "order_by" to orderBy)
                )
                val radios = parseRadioStations(response)
                Log.d(TAG, "Got ${radios.size} radio stations")
                Result.success(radios)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch radio stations", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get tracks from Music Assistant library.
     *
     * @param limit Maximum number of tracks to return (default 25)
     * @param offset Number of items to skip for pagination (default 0)
     * @param orderBy Sort order: "name", "timestamp_added_desc" (default "name")
     * @return Result with list of tracks
     */
    suspend fun getTracks(
        limit: Int = 25,
        offset: Int = 0,
        orderBy: String = "name"
    ): Result<List<MaTrack>> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching tracks (limit=$limit, offset=$offset, orderBy=$orderBy)")
                val response = sendMaCommand(
                    apiUrl, token, "music/tracks/library_items",
                    mapOf("limit" to limit, "offset" to offset, "order_by" to orderBy)
                )
                val tracks = parseMediaItems(response)
                Log.d(TAG, "Got ${tracks.size} tracks")
                Result.success(tracks)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch tracks", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Parse media items from MA API response.
     *
     * MA returns items in different formats depending on the endpoint.
     * This handles both array responses and paginated responses.
     */
    private fun parseMediaItems(response: JSONObject): List<MaTrack> {
        val items = mutableListOf<MaTrack>()

        // Try to get result as array (direct response)
        val resultArray = response.optJSONArray("result")
            ?: response.optJSONObject("result")?.optJSONArray("items")
            ?: return items

        for (i in 0 until resultArray.length()) {
            val item = resultArray.optJSONObject(i) ?: continue
            val mediaItem = parseMediaItem(item)
            if (mediaItem != null) {
                items.add(mediaItem)
            }
        }

        return items
    }

    /**
     * Parse a single media item from JSON.
     *
     * Only parses track items - filters out artists, albums, playlists, etc.
     * This ensures Recently Played only shows playable track items.
     */
    private fun parseMediaItem(json: JSONObject): MaTrack? {
        // Check media_type - only process tracks
        val mediaType = json.optString("media_type", "track")
        if (mediaType != "track") {
            Log.d(TAG, "Skipping non-track item: media_type=$mediaType, name=${json.optString("name")}")
            return null
        }

        // Item ID can be in different fields
        val itemId = json.optString("item_id", "")
            .ifEmpty { json.optString("track_id", "") }
            .ifEmpty { json.optString("album_id", "") }
            .ifEmpty { json.optString("uri", "") }

        if (itemId.isEmpty()) return null

        val name = json.optString("name", "")
            .ifEmpty { json.optString("title", "") }

        if (name.isEmpty()) return null

        // Artist can be a string or an object with name
        val artist = json.optString("artist", "")
            .ifEmpty {
                json.optJSONObject("artist")?.optString("name", "") ?: ""
            }
            .ifEmpty {
                // Try artists array
                json.optJSONArray("artists")?.let { artists ->
                    if (artists.length() > 0) {
                        artists.optJSONObject(0)?.optString("name", "")
                    } else null
                } ?: ""
            }

        // Album can be string or object - extract both name and metadata
        // IMPORTANT: Check for object FIRST - optString returns JSON string when field is object!
        val albumObj = json.optJSONObject("album")
        val album = if (albumObj != null) {
            // Album is a JSON object - extract the name field
            albumObj.optString("name", "")
        } else {
            // Album might be a simple string
            json.optString("album", "")
        }

        // Extract album ID and type for grouping
        val albumId = albumObj?.optString("item_id", "")?.ifEmpty { null }
            ?: albumObj?.optString("album_id", "")?.ifEmpty { null }
        val albumType = albumObj?.optString("album_type", "")?.ifEmpty { null }

        // Image URI - MA stores images in metadata.images array
        val imageUri = extractImageUri(json)

        val uri = json.optString("uri", "")
        val duration = json.optLong("duration", 0L).takeIf { it > 0 }

        return MaTrack(
            itemId = itemId,
            name = name,
            artist = artist.ifEmpty { null },
            album = album.ifEmpty { null },
            imageUri = imageUri.ifEmpty { null },
            uri = uri.ifEmpty { null },
            duration = duration,
            albumId = albumId,
            albumType = albumType
        )
    }

    /**
     * Extract image URI from MA item JSON.
     *
     * MA stores images in metadata.images array with objects like:
     * {"type": "thumb", "path": "/library/metadata/123/thumb/456", "provider": "plex--xxx"}
     *
     * We need to construct a full URL using the MA API base URL + the path.
     */
    private fun extractImageUri(json: JSONObject): String {
        // Convert WebSocket URL to HTTP URL for imageproxy endpoint
        // ws://host:port/ws -> http://host:port
        // wss://host:port/ws -> https://host:port
        val baseUrl = currentApiUrl
            ?.replace("/ws", "")
            ?.replace("wss://", "https://")
            ?.replace("ws://", "http://")
            ?: ""

        // Try direct image field - can be a URL string or a JSONObject with path/provider
        val imageField = json.opt("image")
        when (imageField) {
            is String -> {
                if (imageField.startsWith("http")) return imageField
            }
            is JSONObject -> {
                // Image is an object with path, provider, type
                val url = buildImageProxyUrl(imageField, baseUrl)
                if (url.isNotEmpty()) return url
            }
        }

        if (baseUrl.isEmpty()) return ""

        // Try metadata.images array - need to use imageproxy endpoint
        val metadata = json.optJSONObject("metadata")
        if (metadata != null) {
            val imageUrl = extractImageFromMetadata(metadata, baseUrl)
            if (imageUrl.isNotEmpty()) return imageUrl
        }

        // Try album.image as fallback
        val album = json.optJSONObject("album")
        if (album != null) {
            // Album image can also be object or string
            val albumImageField = album.opt("image")
            when (albumImageField) {
                is String -> {
                    if (albumImageField.startsWith("http")) return albumImageField
                }
                is JSONObject -> {
                    val url = buildImageProxyUrl(albumImageField, baseUrl)
                    if (url.isNotEmpty()) return url
                }
            }

            // Also check album's metadata.images
            val albumMetadata = album.optJSONObject("metadata")
            if (albumMetadata != null) {
                val imageUrl = extractImageFromMetadata(albumMetadata, baseUrl)
                if (imageUrl.isNotEmpty()) return imageUrl
            }
        }

        return ""
    }

    /**
     * Build imageproxy URL from an image object with path/provider fields.
     */
    private fun buildImageProxyUrl(imageObj: JSONObject, baseUrl: String): String {
        val path = imageObj.optString("path", "")
        val provider = imageObj.optString("provider", "")

        if (path.isEmpty() || baseUrl.isEmpty()) return ""

        // If path is already a URL, proxy it
        if (path.startsWith("http")) {
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            return "$baseUrl/imageproxy?size=300&fmt=jpeg&path=$encodedPath" +
                    if (provider.isNotEmpty()) "&provider=$provider" else ""
        }

        // Local path - must have provider
        if (provider.isNotEmpty()) {
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            return "$baseUrl/imageproxy?provider=$provider&size=300&fmt=jpeg&path=$encodedPath"
        }

        return ""
    }

    /**
     * Extract image URL from metadata.images array using the imageproxy endpoint.
     *
     * MA images marked with "remotely_accessible": false need to go through
     * the /imageproxy endpoint with provider, size, and path parameters.
     *
     * Example URL: http://192.168.1.100:8095/imageproxy?provider=plex--xxx&size=300&fmt=jpeg&path=%2Flibrary%2Fmetadata%2F123%2Fthumb%2F456
     */
    private fun extractImageFromMetadata(metadata: JSONObject, baseUrl: String): String {
        val images = metadata.optJSONArray("images")
        if (images == null || images.length() == 0) return ""

        // Find a thumb image, or use the last one as fallback
        for (i in 0 until images.length()) {
            val img = images.optJSONObject(i) ?: continue
            val imgType = img.optString("type", "")
            val path = img.optString("path", "")
            val provider = img.optString("provider", "")

            if (path.isNotEmpty() && (imgType == "thumb" || i == images.length() - 1)) {
                // If path is a remote URL, proxy it
                if (path.startsWith("http")) {
                    val encodedPath = URLEncoder.encode(path, "UTF-8")
                    return "$baseUrl/imageproxy?size=300&fmt=jpeg&path=$encodedPath" +
                            if (provider.isNotEmpty()) "&provider=$provider" else ""
                }

                // Local path - requires provider
                if (provider.isNotEmpty()) {
                    val encodedPath = URLEncoder.encode(path, "UTF-8")
                    return "$baseUrl/imageproxy?provider=$provider&size=300&fmt=jpeg&path=$encodedPath"
                }
            }
        }

        return ""
    }

    /**
     * Parse playlists from MA API response.
     */
    private fun parsePlaylists(response: JSONObject): List<MaPlaylist> {
        val playlists = mutableListOf<MaPlaylist>()

        val resultArray = response.optJSONArray("result")
            ?: response.optJSONObject("result")?.optJSONArray("items")
            ?: return playlists

        for (i in 0 until resultArray.length()) {
            val item = resultArray.optJSONObject(i) ?: continue

            val playlistId = item.optString("item_id", "")
                .ifEmpty { item.optString("playlist_id", "") }
                .ifEmpty { item.optString("uri", "") }

            if (playlistId.isEmpty()) continue

            val name = item.optString("name", "")
            if (name.isEmpty()) continue

            // Use same image extraction logic as media items
            val imageUri = extractImageUri(item).ifEmpty { null }

            val trackCount = item.optInt("track_count", 0)
            val owner = item.optString("owner", "").ifEmpty { null }
            val uri = item.optString("uri", "").ifEmpty { null }

            playlists.add(
                MaPlaylist(
                    playlistId = playlistId,
                    name = name,
                    imageUri = imageUri,
                    trackCount = trackCount,
                    owner = owner,
                    uri = uri
                )
            )
        }

        return playlists
    }

    /**
     * Parse albums from MA API response.
     */
    private fun parseAlbums(response: JSONObject): List<MaAlbum> {
        val albums = mutableListOf<MaAlbum>()

        val resultArray = response.optJSONArray("result")
            ?: response.optJSONObject("result")?.optJSONArray("items")
            ?: return albums

        for (i in 0 until resultArray.length()) {
            val item = resultArray.optJSONObject(i) ?: continue

            val albumId = item.optString("item_id", "")
                .ifEmpty { item.optString("album_id", "") }
                .ifEmpty { item.optString("uri", "") }

            if (albumId.isEmpty()) continue

            val name = item.optString("name", "")
            if (name.isEmpty()) continue

            // Artist can be a string, object, or array
            val artist = item.optString("artist", "")
                .ifEmpty {
                    item.optJSONObject("artist")?.optString("name", "") ?: ""
                }
                .ifEmpty {
                    // Try artists array - get first artist
                    item.optJSONArray("artists")?.let { artists ->
                        if (artists.length() > 0) {
                            artists.optJSONObject(0)?.optString("name", "")
                        } else null
                    } ?: ""
                }

            val imageUri = extractImageUri(item).ifEmpty { null }
            // URI may be returned from API, or we construct it from item_id
            val uri = item.optString("uri", "").ifEmpty {
                // Construct URI for album - format is "library://album/{item_id}"
                "library://album/$albumId"
            }
            val year = item.optInt("year", 0).takeIf { it > 0 }
            val trackCount = item.optInt("track_count", 0).takeIf { it > 0 }
            val albumType = item.optString("album_type", "").ifEmpty { null }

            albums.add(
                MaAlbum(
                    albumId = albumId,
                    name = name,
                    imageUri = imageUri,
                    uri = uri,  // Now always has a value
                    artist = artist.ifEmpty { null },
                    year = year,
                    trackCount = trackCount,
                    albumType = albumType
                )
            )
        }

        return albums
    }

    /**
     * Parse artists from MA API response.
     */
    private fun parseArtists(response: JSONObject): List<MaArtist> {
        val artists = mutableListOf<MaArtist>()

        val resultArray = response.optJSONArray("result")
            ?: response.optJSONObject("result")?.optJSONArray("items")
            ?: return artists

        for (i in 0 until resultArray.length()) {
            val item = resultArray.optJSONObject(i) ?: continue

            val artistId = item.optString("item_id", "")
                .ifEmpty { item.optString("artist_id", "") }
                .ifEmpty { item.optString("uri", "") }

            if (artistId.isEmpty()) continue

            val name = item.optString("name", "")
            if (name.isEmpty()) continue

            val imageUri = extractImageUri(item).ifEmpty { null }
            // URI may be returned from API, or we construct it from item_id
            val uri = item.optString("uri", "").ifEmpty {
                // Construct URI for artist - format is "library://artist/{item_id}"
                "library://artist/$artistId"
            }

            artists.add(
                MaArtist(
                    artistId = artistId,
                    name = name,
                    imageUri = imageUri,
                    uri = uri  // Now always has a value
                )
            )
        }

        return artists
    }

    // ========================================================================
    // Search API
    // ========================================================================

    /**
     * Aggregated search results from Music Assistant.
     *
     * Contains results grouped by media type. Each list may be empty if no
     * matches were found for that type, or if the type was filtered out.
     */
    data class SearchResults(
        val artists: List<MaArtist> = emptyList(),
        val albums: List<MaAlbum> = emptyList(),
        val tracks: List<MaTrack> = emptyList(),
        val playlists: List<MaPlaylist> = emptyList(),
        val radios: List<MaRadio> = emptyList()
    ) {
        /**
         * Check if all result lists are empty.
         */
        fun isEmpty(): Boolean =
            artists.isEmpty() && albums.isEmpty() && tracks.isEmpty() &&
            playlists.isEmpty() && radios.isEmpty()

        /**
         * Get total count of all results.
         */
        fun totalCount(): Int =
            artists.size + albums.size + tracks.size + playlists.size + radios.size
    }

    /**
     * Search Music Assistant library.
     *
     * Calls the music/search endpoint which returns results grouped by media type.
     *
     * @param query The search query string (minimum 2 characters)
     * @param mediaTypes Optional filter to specific types. Null means search all types.
     * @param limit Maximum results per type (default 25)
     * @param libraryOnly If true, only search local library. If false, includes providers.
     * @return Result with grouped search results
     */
    suspend fun search(
        query: String,
        mediaTypes: List<MaMediaType>? = null,
        limit: Int = 25,
        libraryOnly: Boolean = true
    ): Result<SearchResults> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        if (query.length < 2) {
            return Result.failure(Exception("Query too short (minimum 2 characters)"))
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Searching for: '$query' (mediaTypes=$mediaTypes, limit=$limit, libraryOnly=$libraryOnly)")

                // Build args map
                val args = mutableMapOf<String, Any>(
                    "search_query" to query,
                    "limit" to limit,
                    "library_only" to libraryOnly
                )

                // Add media type filter if specified
                if (mediaTypes != null && mediaTypes.isNotEmpty()) {
                    val typeStrings = mediaTypes.map { type ->
                        when (type) {
                            MaMediaType.TRACK -> "track"
                            MaMediaType.ALBUM -> "album"
                            MaMediaType.ARTIST -> "artist"
                            MaMediaType.PLAYLIST -> "playlist"
                            MaMediaType.RADIO -> "radio"
                        }
                    }
                    args["media_types"] = typeStrings
                }

                val response = sendMaCommand(apiUrl, token, "music/search", args)
                val results = parseSearchResults(response)

                Log.d(TAG, "Search returned ${results.totalCount()} results " +
                        "(${results.artists.size} artists, ${results.albums.size} albums, " +
                        "${results.tracks.size} tracks, ${results.playlists.size} playlists, " +
                        "${results.radios.size} radios)")

                Result.success(results)
            } catch (e: Exception) {
                Log.e(TAG, "Search failed for query: '$query'", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Parse search results from MA API response.
     *
     * The response contains grouped results by media type:
     * {
     *   "result": {
     *     "artists": [...],
     *     "albums": [...],
     *     "tracks": [...],
     *     "playlists": [...],
     *     "radios": [...]
     *   }
     * }
     */
    private fun parseSearchResults(response: JSONObject): SearchResults {
        val result = response.optJSONObject("result") ?: return SearchResults()

        return SearchResults(
            artists = parseArtistsArray(result.optJSONArray("artists")),
            albums = parseAlbumsArray(result.optJSONArray("albums")),
            tracks = parseTracksArray(result.optJSONArray("tracks")),
            playlists = parsePlaylistsArray(result.optJSONArray("playlists")),
            radios = parseRadiosArray(result.optJSONArray("radios"))
        )
    }

    /**
     * Parse an array of artists from JSON.
     */
    private fun parseArtistsArray(array: JSONArray?): List<MaArtist> {
        if (array == null) return emptyList()
        val artists = mutableListOf<MaArtist>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue

            val artistId = item.optString("item_id", "")
                .ifEmpty { item.optString("artist_id", "") }
                .ifEmpty { item.optString("uri", "") }

            if (artistId.isEmpty()) continue

            val name = item.optString("name", "")
            if (name.isEmpty()) continue

            val imageUri = extractImageUri(item).ifEmpty { null }
            val uri = item.optString("uri", "").ifEmpty {
                "library://artist/$artistId"
            }

            artists.add(MaArtist(
                artistId = artistId,
                name = name,
                imageUri = imageUri,
                uri = uri
            ))
        }

        return artists
    }

    /**
     * Parse an array of albums from JSON.
     */
    private fun parseAlbumsArray(array: JSONArray?): List<MaAlbum> {
        if (array == null) return emptyList()
        val albums = mutableListOf<MaAlbum>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue

            val albumId = item.optString("item_id", "")
                .ifEmpty { item.optString("album_id", "") }
                .ifEmpty { item.optString("uri", "") }

            if (albumId.isEmpty()) continue

            val name = item.optString("name", "")
            if (name.isEmpty()) continue

            val artist = item.optString("artist", "")
                .ifEmpty {
                    item.optJSONObject("artist")?.optString("name", "") ?: ""
                }
                .ifEmpty {
                    item.optJSONArray("artists")?.let { artists ->
                        if (artists.length() > 0) {
                            artists.optJSONObject(0)?.optString("name", "")
                        } else null
                    } ?: ""
                }

            val imageUri = extractImageUri(item).ifEmpty { null }
            val uri = item.optString("uri", "").ifEmpty {
                "library://album/$albumId"
            }
            val year = item.optInt("year", 0).takeIf { it > 0 }
            val trackCount = item.optInt("track_count", 0).takeIf { it > 0 }
            val albumType = item.optString("album_type", "").ifEmpty { null }

            albums.add(MaAlbum(
                albumId = albumId,
                name = name,
                imageUri = imageUri,
                uri = uri,
                artist = artist.ifEmpty { null },
                year = year,
                trackCount = trackCount,
                albumType = albumType
            ))
        }

        return albums
    }

    /**
     * Parse an array of tracks from JSON.
     */
    private fun parseTracksArray(array: JSONArray?): List<MaTrack> {
        if (array == null) return emptyList()
        val tracks = mutableListOf<MaTrack>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val track = parseMediaItem(item)
            if (track != null) {
                tracks.add(track)
            }
        }

        return tracks
    }

    /**
     * Parse an array of playlists from JSON.
     */
    private fun parsePlaylistsArray(array: JSONArray?): List<MaPlaylist> {
        if (array == null) return emptyList()
        val playlists = mutableListOf<MaPlaylist>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue

            val playlistId = item.optString("item_id", "")
                .ifEmpty { item.optString("playlist_id", "") }
                .ifEmpty { item.optString("uri", "") }

            if (playlistId.isEmpty()) continue

            val name = item.optString("name", "")
            if (name.isEmpty()) continue

            val imageUri = extractImageUri(item).ifEmpty { null }
            val trackCount = item.optInt("track_count", 0)
            val owner = item.optString("owner", "").ifEmpty { null }
            val uri = item.optString("uri", "").ifEmpty { null }

            playlists.add(MaPlaylist(
                playlistId = playlistId,
                name = name,
                imageUri = imageUri,
                trackCount = trackCount,
                owner = owner,
                uri = uri
            ))
        }

        return playlists
    }

    /**
     * Parse an array of radio stations from JSON.
     */
    private fun parseRadiosArray(array: JSONArray?): List<MaRadio> {
        if (array == null) return emptyList()
        val radios = mutableListOf<MaRadio>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue

            val radioId = item.optString("item_id", "")
                .ifEmpty { item.optString("radio_id", "") }
                .ifEmpty { item.optString("uri", "") }

            if (radioId.isEmpty()) continue

            val name = item.optString("name", "")
            if (name.isEmpty()) continue

            val imageUri = extractImageUri(item).ifEmpty { null }
            val uri = item.optString("uri", "").ifEmpty {
                "library://radio/$radioId"
            }
            val provider = item.optString("provider", "")
                .ifEmpty {
                    item.optJSONArray("provider_mappings")?.let { mappings ->
                        if (mappings.length() > 0) {
                            mappings.optJSONObject(0)?.optString("provider_domain", "")
                        } else null
                    } ?: ""
                }

            radios.add(MaRadio(
                radioId = radioId,
                name = name,
                imageUri = imageUri,
                uri = uri,
                provider = provider.ifEmpty { null }
            ))
        }

        return radios
    }

    // ========================================================================
    // Detail Screen API Methods
    // ========================================================================

    /**
     * Aggregated artist details including top tracks and discography.
     *
     * Used by the Artist Detail screen to display complete artist information
     * in a single request.
     */
    data class ArtistDetails(
        val artist: MaArtist,
        val topTracks: List<MaTrack>,
        val albums: List<MaAlbum>
    )

    /**
     * Get complete artist details including top tracks and discography.
     *
     * Makes multiple API calls to fetch:
     * 1. Artist metadata
     * 2. Top tracks (sorted by play count or popularity)
     * 3. All albums/singles/EPs by the artist
     *
     * @param artistId The MA artist item_id
     * @return Result with complete artist details
     */
    suspend fun getArtistDetails(artistId: String): Result<ArtistDetails> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching artist details for: $artistId")

                // Fetch artist metadata
                val artistResponse = sendMaCommand(
                    apiUrl, token, "music/artists/get",
                    mapOf(
                        "item_id" to artistId,
                        "provider_instance_id_or_domain" to "library"
                    )
                )
                val artist = parseArtistFromResult(artistResponse)
                    ?: return@withContext Result.failure(Exception("Artist not found"))

                // Fetch artist's tracks (top tracks by play count)
                val tracksResponse = sendMaCommand(
                    apiUrl, token, "music/artists/artist_tracks",
                    mapOf(
                        "item_id" to artistId,
                        "provider_instance_id_or_domain" to "library",
                        "in_library_only" to false
                    )
                )
                val topTracks = parseTracksArray(
                    tracksResponse.optJSONArray("result")
                ).take(10)

                // Fetch artist's albums
                val albumsResponse = sendMaCommand(
                    apiUrl, token, "music/artists/artist_albums",
                    mapOf(
                        "item_id" to artistId,
                        "provider_instance_id_or_domain" to "library",
                        "in_library_only" to false
                    )
                )
                val albums = parseAlbumsArray(
                    albumsResponse.optJSONArray("result")
                ).sortedByDescending { it.year ?: 0 }

                Log.d(TAG, "Got artist details: ${artist.name} - " +
                        "${topTracks.size} top tracks, ${albums.size} albums")

                Result.success(ArtistDetails(
                    artist = artist,
                    topTracks = topTracks,
                    albums = albums
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch artist details: $artistId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get album details with full track listing.
     *
     * @param albumId The MA album item_id
     * @return Result with album and its tracks
     */
    suspend fun getAlbumTracks(albumId: String): Result<List<MaTrack>> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching album tracks for: $albumId")

                // Fetch album tracks
                val response = sendMaCommand(
                    apiUrl, token, "music/albums/album_tracks",
                    mapOf(
                        "item_id" to albumId,
                        "provider_instance_id_or_domain" to "library",
                        "in_library_only" to false
                    )
                )
                val tracks = parseAlbumTracks(response.optJSONArray("result"))

                Log.d(TAG, "Got ${tracks.size} tracks for album $albumId")
                Result.success(tracks)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch album tracks: $albumId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get a single album by ID.
     *
     * @param albumId The MA album item_id
     * @return Result with the album
     */
    suspend fun getAlbum(albumId: String): Result<MaAlbum> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching album: $albumId")

                val response = sendMaCommand(
                    apiUrl, token, "music/albums/get",
                    mapOf(
                        "item_id" to albumId,
                        "provider_instance_id_or_domain" to "library"
                    )
                )
                val album = parseAlbumFromResult(response)
                    ?: return@withContext Result.failure(Exception("Album not found"))

                Log.d(TAG, "Got album: ${album.name}")
                Result.success(album)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch album: $albumId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get a single artist by ID.
     *
     * @param artistId The MA artist item_id
     * @return Result with the artist
     */
    suspend fun getArtist(artistId: String): Result<MaArtist> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching artist: $artistId")

                val response = sendMaCommand(
                    apiUrl, token, "music/artists/get",
                    mapOf(
                        "item_id" to artistId,
                        "provider_instance_id_or_domain" to "library"
                    )
                )
                val artist = parseArtistFromResult(response)
                    ?: return@withContext Result.failure(Exception("Artist not found"))

                Log.d(TAG, "Got artist: ${artist.name}")
                Result.success(artist)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch artist: $artistId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Parse a single artist from a get result.
     */
    private fun parseArtistFromResult(response: JSONObject): MaArtist? {
        val item = response.optJSONObject("result") ?: return null

        val artistId = item.optString("item_id", "")
            .ifEmpty { item.optString("artist_id", "") }
            .ifEmpty { return null }

        val name = item.optString("name", "")
        if (name.isEmpty()) return null

        val imageUri = extractImageUri(item).ifEmpty { null }
        val uri = item.optString("uri", "").ifEmpty {
            "library://artist/$artistId"
        }

        return MaArtist(
            artistId = artistId,
            name = name,
            imageUri = imageUri,
            uri = uri
        )
    }

    /**
     * Parse a single album from a get result.
     */
    private fun parseAlbumFromResult(response: JSONObject): MaAlbum? {
        val item = response.optJSONObject("result") ?: return null

        val albumId = item.optString("item_id", "")
            .ifEmpty { item.optString("album_id", "") }
            .ifEmpty { return null }

        val name = item.optString("name", "")
        if (name.isEmpty()) return null

        val artist = item.optString("artist", "")
            .ifEmpty {
                item.optJSONObject("artist")?.optString("name", "") ?: ""
            }
            .ifEmpty {
                item.optJSONArray("artists")?.let { artists ->
                    if (artists.length() > 0) {
                        artists.optJSONObject(0)?.optString("name", "")
                    } else null
                } ?: ""
            }

        val imageUri = extractImageUri(item).ifEmpty { null }
        val uri = item.optString("uri", "").ifEmpty {
            "library://album/$albumId"
        }
        val year = item.optInt("year", 0).takeIf { it > 0 }
        val trackCount = item.optInt("track_count", 0).takeIf { it > 0 }
        val albumType = item.optString("album_type", "").ifEmpty { null }

        return MaAlbum(
            albumId = albumId,
            name = name,
            imageUri = imageUri,
            uri = uri,
            artist = artist.ifEmpty { null },
            year = year,
            trackCount = trackCount,
            albumType = albumType
        )
    }

    /**
     * Parse album tracks with track numbers preserved.
     */
    private fun parseAlbumTracks(array: JSONArray?): List<MaTrack> {
        if (array == null) return emptyList()
        val tracks = mutableListOf<MaTrack>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue

            val itemId = item.optString("item_id", "")
                .ifEmpty { item.optString("track_id", "") }
                .ifEmpty { item.optString("uri", "") }

            if (itemId.isEmpty()) continue

            val name = item.optString("name", "")
                .ifEmpty { item.optString("title", "") }

            if (name.isEmpty()) continue

            // Artist for track
            val artist = item.optString("artist", "")
                .ifEmpty {
                    item.optJSONObject("artist")?.optString("name", "") ?: ""
                }
                .ifEmpty {
                    item.optJSONArray("artists")?.let { artists ->
                        if (artists.length() > 0) {
                            artists.optJSONObject(0)?.optString("name", "")
                        } else null
                    } ?: ""
                }

            // Album info
            val albumObj = item.optJSONObject("album")
            val album = albumObj?.optString("name", "")
            val albumId = albumObj?.optString("item_id", "")?.ifEmpty { null }
                ?: albumObj?.optString("album_id", "")?.ifEmpty { null }
            val albumType = albumObj?.optString("album_type", "")?.ifEmpty { null }

            val imageUri = extractImageUri(item).ifEmpty { null }
            val uri = item.optString("uri", "")
            val duration = item.optLong("duration", 0L).takeIf { it > 0 }

            tracks.add(MaTrack(
                itemId = itemId,
                name = name,
                artist = artist.ifEmpty { null },
                album = album?.ifEmpty { null },
                imageUri = imageUri,
                uri = uri.ifEmpty { null },
                duration = duration,
                albumId = albumId,
                albumType = albumType
            ))
        }

        return tracks
    }

    /**
     * Parse radio stations from MA API response.
     */
    private fun parseRadioStations(response: JSONObject): List<MaRadio> {
        val radios = mutableListOf<MaRadio>()

        val resultArray = response.optJSONArray("result")
            ?: response.optJSONObject("result")?.optJSONArray("items")
            ?: return radios

        for (i in 0 until resultArray.length()) {
            val item = resultArray.optJSONObject(i) ?: continue

            val radioId = item.optString("item_id", "")
                .ifEmpty { item.optString("radio_id", "") }
                .ifEmpty { item.optString("uri", "") }

            if (radioId.isEmpty()) continue

            val name = item.optString("name", "")
            if (name.isEmpty()) continue

            val imageUri = extractImageUri(item).ifEmpty { null }
            // URI may be returned from API, or we construct it from item_id
            val uri = item.optString("uri", "").ifEmpty {
                // Construct URI for radio - format is "library://radio/{item_id}"
                "library://radio/$radioId"
            }

            // Provider can be direct field or from provider_mappings
            val provider = item.optString("provider", "")
                .ifEmpty {
                    item.optJSONArray("provider_mappings")?.let { mappings ->
                        if (mappings.length() > 0) {
                            mappings.optJSONObject(0)?.optString("provider_domain", "")
                        } else null
                    } ?: ""
                }

            radios.add(
                MaRadio(
                    radioId = radioId,
                    name = name,
                    imageUri = imageUri,
                    uri = uri,  // Now always has a value
                    provider = provider.ifEmpty { null }
                )
            )
        }

        return radios
    }
}
