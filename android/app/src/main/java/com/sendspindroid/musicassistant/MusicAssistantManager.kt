package com.sendspindroid.musicassistant

import android.content.Context
import android.util.Log
import com.sendspindroid.UserSettings
import com.sendspindroid.UserSettings.ConnectionMode
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.model.MaConnectionState
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType
import com.sendspindroid.musicassistant.model.MaPlaybackState
import com.sendspindroid.musicassistant.model.MaPlayer
import com.sendspindroid.musicassistant.model.MaPlayerFeature
import com.sendspindroid.musicassistant.model.MaPlayerType
import com.sendspindroid.musicassistant.model.MaServerInfo
import com.sendspindroid.musicassistant.transport.MaApiTransport
import com.sendspindroid.musicassistant.transport.MaApiTransportFactory
import com.sendspindroid.musicassistant.transport.MaDataChannelTransport
import com.sendspindroid.musicassistant.transport.MaWebSocketTransport
import org.webrtc.DataChannel
import com.sendspindroid.sendspin.MusicAssistantAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

// ============================================================================
// Data Models for Music Assistant API responses
// ============================================================================

/**
 * Represents a track from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters and generic lists.
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

/**
 * Represents the full queue state including settings.
 */
data class MaQueueState(
    val items: List<MaQueueItem>,
    val currentIndex: Int,
    val shuffleEnabled: Boolean,
    val repeatMode: String     // "off", "one", "all"
)

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

    // Persistent MA API transport (replaces fire-and-forget WebSocket pattern)
    @Volatile
    private var apiTransport: MaApiTransport? = null

    /**
     * Returns the active API transport, if connected.
     * Used by MaImageProxy for HTTP proxy requests in REMOTE mode.
     */
    fun getApiTransport(): MaApiTransport? = apiTransport

    // WebRTC DataChannel for MA API in REMOTE mode
    @Volatile
    private var maApiDataChannel: DataChannel? = null

    // Pre-created DataChannel transport that eagerly captures ServerInfo
    @Volatile
    private var pendingDataChannelTransport: MaDataChannelTransport? = null

    /**
     * Set the MA API DataChannel from WebRTCTransport.
     *
     * Called by PlaybackService when a remote connection establishes the "ma-api"
     * DataChannel. Eagerly creates a [MaDataChannelTransport] and replays any
     * messages buffered by WebRTCTransport (e.g., ServerInfo sent before this
     * method is called).
     *
     * @param channel The open DataChannel, or null to clear
     * @param bufferedMessages Messages buffered by WebRTCTransport before the
     *                         transport observer was registered
     */
    fun setMaApiDataChannel(channel: DataChannel?, bufferedMessages: List<String> = emptyList()) {
        maApiDataChannel = channel
        if (channel != null) {
            // Create transport eagerly so its observer captures future messages
            val transport = MaDataChannelTransport(channel)
            pendingDataChannelTransport = transport
            Log.d(TAG, "MA API DataChannel set, transport created eagerly")

            // Replay any messages buffered by WebRTCTransport before we took over
            if (bufferedMessages.isNotEmpty()) {
                Log.d(TAG, "Replaying ${bufferedMessages.size} buffered MA API message(s)")
                transport.replayBufferedMessages(bufferedMessages)
            }
        } else {
            pendingDataChannelTransport = null
            Log.d(TAG, "MA API DataChannel cleared")
        }
    }

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

        // Check 1: Is this server a Music Assistant server?
        // Detect via: (a) user-declared flag, (b) stored token from prior auth,
        // (c) presence of "ma-api" DataChannel (only MA servers create this).
        val hasStoredToken = MaSettings.getTokenForServer(server.id) != null
        val hasMaApiChannel = maApiDataChannel != null
        if (!server.isMusicAssistant && !hasStoredToken && !hasMaApiChannel) {
            Log.d(TAG, "Server is not marked as Music Assistant, no stored token, no ma-api channel")
            _connectionState.value = MaConnectionState.Unavailable
            return
        }
        if (!server.isMusicAssistant) {
            Log.i(TAG, "Server not flagged as MA but auto-detected (token=$hasStoredToken, channel=$hasMaApiChannel)")
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

        // Disconnect the persistent transport
        apiTransport?.disconnect()
        apiTransport = null

        // Clear DataChannel reference (owned by WebRTCTransport, not us)
        maApiDataChannel = null
        pendingDataChannelTransport = null

        currentServer = null
        currentConnectionMode = null
        currentApiUrl = null
        _connectionState.value = MaConnectionState.Unavailable
    }

    /**
     * Attempt to authenticate with a stored token.
     *
     * Establishes a persistent transport connection (WebSocket for LOCAL/PROXY,
     * DataChannel for REMOTE) and authenticates with the given token.
     * All subsequent API calls are multiplexed over this single connection.
     */
    private fun connectWithToken(apiUrl: String, token: String, serverId: String) {
        _connectionState.value = MaConnectionState.Connecting

        scope.launch {
            try {
                // Disconnect any existing transport
                apiTransport?.disconnect()
                apiTransport = null

                // Create the appropriate transport for this connection mode
                val transport = createTransport(apiUrl)
                    ?: throw IOException("Cannot create MA API transport for mode $currentConnectionMode")

                // Connect and authenticate (handles ServerInfoMessage + auth handshake)
                transport.connect(token)

                // Store the transport for all future commands
                apiTransport = transport

                val serverInfo = MaServerInfo(
                    serverId = serverId,
                    serverVersion = transport.serverVersion ?: "unknown",
                    apiUrl = apiUrl,
                    maServerId = transport.maServerId
                )

                Log.i(TAG, "MA API connected successfully (server ${transport.serverVersion})")
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

            } catch (e: MaApiTransport.AuthenticationException) {
                Log.e(TAG, "Token authentication failed", e)
                apiTransport?.disconnect()
                apiTransport = null
                MaSettings.clearTokenForServer(serverId)
                _connectionState.value = MaConnectionState.Error(
                    message = "Authentication expired. Please log in again.",
                    isAuthError = true
                )
            } catch (e: MusicAssistantAuth.AuthenticationException) {
                Log.e(TAG, "Token authentication failed", e)
                apiTransport?.disconnect()
                apiTransport = null
                // Token expired or invalid - clear it and request re-login
                MaSettings.clearTokenForServer(serverId)
                _connectionState.value = MaConnectionState.Error(
                    message = "Authentication expired. Please log in again.",
                    isAuthError = true
                )
            } catch (e: IOException) {
                Log.e(TAG, "Network error connecting to MA API", e)
                apiTransport?.disconnect()
                apiTransport = null
                _connectionState.value = MaConnectionState.Error(
                    message = "Network error: ${e.message}",
                    isAuthError = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to MA API", e)
                apiTransport?.disconnect()
                apiTransport = null
                _connectionState.value = MaConnectionState.Error(
                    message = e.message ?: "Unknown error",
                    isAuthError = false
                )
            }
        }
    }

    /**
     * Create the appropriate MaApiTransport for the current connection mode.
     *
     * LOCAL/PROXY: MaWebSocketTransport (persistent WebSocket to ws://host:8095/ws)
     * REMOTE: MaDataChannelTransport (WebRTC DataChannel "ma-api")
     *
     * For REMOTE mode, if the DataChannel is not available, falls back to
     * a WebSocket if a local or proxy URL can be derived.
     *
     * @param apiUrl The derived API URL (may be sentinel "webrtc://ma-api" for REMOTE)
     * @return The transport, or null if not available
     */
    private fun createTransport(apiUrl: String): MaApiTransport? {
        val mode = currentConnectionMode ?: return null

        // For REMOTE mode, use the pre-created transport (which already has
        // an observer capturing ServerInfo). This avoids the race where
        // ServerInfo is sent before connect()/login() is called.
        if (mode == ConnectionMode.REMOTE) {
            val pending = pendingDataChannelTransport
            if (pending != null) {
                pendingDataChannelTransport = null  // Consumed; don't reuse
                Log.d(TAG, "Using pre-created DataChannel transport (serverInfo=${pending.serverVersion != null})")
                return pending
            }
            // Fallback: create fresh (may miss ServerInfo if already sent)
            val channel = maApiDataChannel
            if (channel != null) {
                Log.w(TAG, "Creating fresh DataChannel transport (pre-created was null)")
                return MaDataChannelTransport(channel)
            }
            // No DataChannel — try WebSocket fallback
            val wsUrl = currentServer?.let { server ->
                MaApiEndpoint.deriveFromLocal(server) ?: MaApiEndpoint.deriveFromProxy(server)
            }
            return if (wsUrl != null) MaWebSocketTransport(wsUrl) else null
        }

        // LOCAL/PROXY: WebSocket transport
        return MaWebSocketTransport(apiUrl)
    }

    /**
     * Perform fresh login with username/password credentials.
     *
     * Uses the persistent transport layer (WebSocket for LOCAL/PROXY, DataChannel
     * for REMOTE) to authenticate. This means login works even when only a WebRTC
     * connection is available (no direct HTTP/WS access to the server).
     *
     * On success, stores the token for future connections and leaves the transport
     * connected for immediate API use.
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
            // Disconnect any existing transport
            apiTransport?.disconnect()
            apiTransport = null

            // Create a fresh transport for this connection mode
            val transport = createTransport(apiUrl)
                ?: throw IOException("Cannot create MA API transport for mode $currentConnectionMode")

            // Login via transport (sends auth/login over WebSocket or DataChannel)
            val loginResult = transport.connectWithCredentials(username, password)

            // Store the transport for all future commands (it's already connected)
            apiTransport = transport

            // Store the token for future connections
            MaSettings.setTokenForServer(server.id, loginResult.accessToken)

            val serverInfo = MaServerInfo(
                serverId = server.id,
                serverVersion = transport.serverVersion ?: "unknown",
                apiUrl = apiUrl,
                maServerId = transport.maServerId
            )

            Log.i(TAG, "MA login successful for user: ${loginResult.userName} (server ${transport.serverVersion})")
            _connectionState.value = MaConnectionState.Connected(serverInfo)

            // Auto-select a player for playback commands
            autoSelectPlayer().fold(
                onSuccess = { playerId ->
                    Log.i(TAG, "Auto-selected player for playback: $playerId")
                },
                onFailure = { error ->
                    Log.w(TAG, "No players available for auto-selection: ${error.message}")
                }
            )

            true

        } catch (e: MaApiTransport.AuthenticationException) {
            Log.e(TAG, "Login failed: invalid credentials", e)
            apiTransport?.disconnect()
            apiTransport = null
            _connectionState.value = MaConnectionState.Error(
                message = "Invalid username or password",
                isAuthError = true
            )
            false
        } catch (e: IOException) {
            Log.e(TAG, "Login failed: network error", e)
            apiTransport?.disconnect()
            apiTransport = null
            _connectionState.value = MaConnectionState.Error(
                message = "Network error: ${e.message}",
                isAuthError = false
            )
            false
        } catch (e: Exception) {
            Log.e(TAG, "Login failed", e)
            apiTransport?.disconnect()
            apiTransport = null
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
     * Delegates to connectWithToken which establishes a persistent transport.
     *
     * @param token The stored access token
     * @return true if authentication succeeded
     */
    suspend fun authWithToken(token: String): Boolean {
        val server = currentServer ?: return false
        val apiUrl = currentApiUrl ?: return false

        _connectionState.value = MaConnectionState.Connecting

        return try {
            // Disconnect any existing transport
            apiTransport?.disconnect()
            apiTransport = null

            // Create and connect transport
            val transport = createTransport(apiUrl)
                ?: throw IOException("Cannot create MA API transport")

            transport.connect(token)
            apiTransport = transport

            val serverInfo = MaServerInfo(
                serverId = server.id,
                serverVersion = transport.serverVersion ?: "unknown",
                apiUrl = apiUrl,
                maServerId = transport.maServerId
            )

            Log.i(TAG, "MA token auth successful (server ${transport.serverVersion})")
            _connectionState.value = MaConnectionState.Connected(serverInfo)
            true

        } catch (e: MaApiTransport.AuthenticationException) {
            Log.e(TAG, "Token auth failed: invalid/expired token", e)
            apiTransport?.disconnect()
            apiTransport = null
            MaSettings.clearTokenForServer(server.id)
            _connectionState.value = MaConnectionState.Error(
                message = "Authentication expired. Please log in again.",
                isAuthError = true
            )
            false
        } catch (e: MusicAssistantAuth.AuthenticationException) {
            Log.e(TAG, "Token auth failed: invalid/expired token", e)
            apiTransport?.disconnect()
            apiTransport = null
            // Token expired or invalid - clear it and request re-login
            MaSettings.clearTokenForServer(server.id)
            _connectionState.value = MaConnectionState.Error(
                message = "Authentication expired. Please log in again.",
                isAuthError = true
            )
            false
        } catch (e: IOException) {
            Log.e(TAG, "Token auth network error", e)
            apiTransport?.disconnect()
            apiTransport = null
            _connectionState.value = MaConnectionState.Error(
                message = "Network error: ${e.message}",
                isAuthError = false
            )
            false
        } catch (e: Exception) {
            Log.e(TAG, "Token auth failed", e)
            apiTransport?.disconnect()
            apiTransport = null
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
    /**
     * Enqueue mode for playMedia().
     */
    enum class EnqueueMode(val apiValue: String?) {
        /** Replace queue and start playing immediately */
        PLAY(null),
        /** Append to end of queue */
        ADD("add"),
        /** Insert after currently playing track */
        NEXT("next"),
        /** Replace queue but don't start playing */
        REPLACE("replace")
    }

    suspend fun playMedia(
        uri: String,
        mediaType: String? = null,
        enqueue: Boolean = false,
        enqueueMode: EnqueueMode? = null
    ): Result<Unit> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        // Resolve the effective enqueue mode
        val effectiveMode = enqueueMode ?: if (enqueue) EnqueueMode.ADD else EnqueueMode.PLAY

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "${effectiveMode.name} media: $uri")

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
                effectiveMode.apiValue?.let { args["enqueue"] = it }

                // Send play command - play_media replaces queue unless enqueue is set
                sendMaCommand(apiUrl, token, "player_queues/play_media", args)

                Log.i(TAG, "Successfully ${effectiveMode.name}: $uri")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to ${effectiveMode.name} media: $uri", e)
                Result.failure(e)
            }
        }
    }

    // ========================================================================
    // Queue Management
    // ========================================================================

    /**
     * Get the current queue items for the player.
     *
     * @param limit Maximum number of items to return (default 200, max 500)
     * @param offset Starting position in the queue
     * @return Result with the queue state including items and settings
     */
    suspend fun getQueueItems(limit: Int = 200, offset: Int = 0): Result<MaQueueState> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                val playerId = UserSettings.getPlayerId()
                Log.d(TAG, "Fetching queue items for player: $playerId (limit=$limit, offset=$offset)")

                // First get the queue state (shuffle, repeat, current index)
                val queueResponse = sendMaCommand(
                    apiUrl, token, "player_queues/get",
                    mapOf("queue_id" to playerId)
                )

                // Then get the queue items
                val itemsResponse = sendMaCommand(
                    apiUrl, token, "player_queues/items",
                    mapOf("queue_id" to playerId, "limit" to limit, "offset" to offset)
                )

                val queueState = parseQueueState(queueResponse, itemsResponse)
                Log.i(TAG, "Fetched ${queueState.items.size} queue items (current index: ${queueState.currentIndex})")
                Result.success(queueState)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch queue items", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Clear all items from the queue.
     */
    suspend fun clearQueue(): Result<Unit> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                val playerId = UserSettings.getPlayerId()
                Log.d(TAG, "Clearing queue for player: $playerId")

                sendMaCommand(apiUrl, token, "player_queues/clear", mapOf("queue_id" to playerId))

                Log.i(TAG, "Queue cleared")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear queue", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Jump to and play a specific item in the queue.
     *
     * @param queueItemId The queue_item_id to play
     */
    suspend fun playQueueItem(queueItemId: String): Result<Unit> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                val playerId = UserSettings.getPlayerId()
                Log.d(TAG, "Playing queue item: $queueItemId on player: $playerId")

                sendMaCommand(
                    apiUrl, token, "player_queues/play_index",
                    mapOf("queue_id" to playerId, "index" to queueItemId)
                )

                Log.i(TAG, "Jumped to queue item: $queueItemId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play queue item: $queueItemId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Remove a specific item from the queue.
     *
     * @param queueItemId The queue_item_id to remove
     */
    suspend fun removeQueueItem(queueItemId: String): Result<Unit> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                val playerId = UserSettings.getPlayerId()
                Log.d(TAG, "Removing queue item: $queueItemId from player: $playerId")

                sendMaCommand(
                    apiUrl, token, "player_queues/delete_item",
                    mapOf("queue_id" to playerId, "item_id_or_index" to queueItemId)
                )

                Log.i(TAG, "Removed queue item: $queueItemId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove queue item: $queueItemId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Move a queue item to a new position.
     *
     * @param queueItemId The queue_item_id to move
     * @param newIndex The target position index
     */
    suspend fun moveQueueItem(queueItemId: String, newIndex: Int): Result<Unit> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                val playerId = UserSettings.getPlayerId()
                Log.d(TAG, "Moving queue item $queueItemId to position $newIndex")

                sendMaCommand(
                    apiUrl, token, "player_queues/move_item",
                    mapOf(
                        "queue_id" to playerId,
                        "queue_item_id" to queueItemId,
                        "pos_shift" to newIndex
                    )
                )

                Log.i(TAG, "Moved queue item: $queueItemId to position $newIndex")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move queue item: $queueItemId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Toggle shuffle mode for the queue.
     *
     * @param enabled Whether shuffle should be enabled
     */
    suspend fun setQueueShuffle(enabled: Boolean): Result<Unit> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                val playerId = UserSettings.getPlayerId()
                Log.d(TAG, "Setting shuffle ${if (enabled) "ON" else "OFF"} for player: $playerId")

                sendMaCommand(
                    apiUrl, token, "player_queues/shuffle",
                    mapOf("queue_id" to playerId, "shuffle_enabled" to enabled)
                )

                Log.i(TAG, "Shuffle set to: $enabled")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set shuffle", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Set repeat mode for the queue.
     *
     * @param mode Repeat mode: "off", "one", or "all"
     */
    suspend fun setQueueRepeat(mode: String): Result<Unit> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                val playerId = UserSettings.getPlayerId()
                Log.d(TAG, "Setting repeat mode '$mode' for player: $playerId")

                sendMaCommand(
                    apiUrl, token, "player_queues/repeat",
                    mapOf("queue_id" to playerId, "repeat_mode" to mode)
                )

                Log.i(TAG, "Repeat mode set to: $mode")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set repeat mode", e)
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
     * Get this device's player ID — the UUID registered with the SendSpin server.
     *
     * This is the MA player_id for the SendSpinDroid app itself, as opposed to
     * [getSelectedPlayer] which returns whichever player was auto-selected for
     * playback commands.
     *
     * @return This device's player ID
     */
    fun getThisDevicePlayerId(): String = UserSettings.getPlayerId()

    // ======== Player Management (Group / Multi-Room) ========

    /**
     * Fetch all players from Music Assistant.
     *
     * Returns the full, unfiltered list of players. The caller is responsible
     * for filtering by [MaPlayer.available], [MaPlayer.enabled], etc.
     *
     * @return Result with the list of all players, or failure on error
     */
    suspend fun getAllPlayers(): Result<List<MaPlayer>> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                val response = sendMaCommand(apiUrl, token, "players/all", emptyMap())
                val players = parsePlayers(response)
                Log.i(TAG, "Fetched ${players.size} players")
                Result.success(players)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch players", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Add or remove players from a group.
     *
     * Uses the `players/cmd/set_members` MA API command to modify
     * the group membership of the target player.
     *
     * @param targetPlayerId The group leader (target player) whose group is being modified
     * @param playerIdsToAdd List of player IDs to add to the group (can be empty)
     * @param playerIdsToRemove List of player IDs to remove from the group (can be empty)
     * @return Result.success on success, or failure on error
     */
    suspend fun setGroupMembers(
        targetPlayerId: String,
        playerIdsToAdd: List<String>,
        playerIdsToRemove: List<String>
    ): Result<Unit> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                val args = mutableMapOf<String, Any>(
                    "target_player" to targetPlayerId
                )
                if (playerIdsToAdd.isNotEmpty()) {
                    args["player_ids_to_add"] = playerIdsToAdd
                }
                if (playerIdsToRemove.isNotEmpty()) {
                    args["player_ids_to_remove"] = playerIdsToRemove
                }

                sendMaCommand(apiUrl, token, "players/cmd/set_members", args)
                Log.i(TAG, "Group members updated for $targetPlayerId: +${playerIdsToAdd.size} -${playerIdsToRemove.size}")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set group members", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Power on a player.
     *
     * Uses the `players/cmd/power` MA API command to turn on a player
     * (e.g., a Chromecast) before adding it to a group.
     *
     * @param playerId The player to power on
     * @return Result.success on success, or failure on error
     */
    suspend fun powerOnPlayer(playerId: String): Result<Unit> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                sendMaCommand(apiUrl, token, "players/cmd/power", mapOf(
                    "player_id" to playerId,
                    "powered" to true
                ))
                Log.i(TAG, "Player powered on: $playerId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to power on player", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Remove a player from all groups.
     *
     * @param playerId The player to ungroup
     * @return Result.success on success, or failure on error
     */
    suspend fun ungroupPlayer(playerId: String): Result<Unit> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                sendMaCommand(apiUrl, token, "players/cmd/ungroup", mapOf("player_id" to playerId))
                Log.i(TAG, "Player ungrouped: $playerId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to ungroup player", e)
                Result.failure(e)
            }
        }
    }

    // ======== Player JSON Parsing ========

    /**
     * Parse the players/all response into a list of [MaPlayer] objects.
     */
    private fun parsePlayers(response: JSONObject): List<MaPlayer> {
        val playersArray = response.optJSONArray("result")
            ?: response.optJSONObject("result")?.optJSONArray("players")
            ?: return emptyList()

        return (0 until playersArray.length()).mapNotNull { i ->
            playersArray.optJSONObject(i)?.let { parsePlayer(it) }
        }
    }

    /**
     * Parse a single player JSON object into an [MaPlayer].
     */
    private fun parsePlayer(json: JSONObject): MaPlayer {
        val playerId = json.optString("player_id", "")
            .ifEmpty { json.optString("id", "") }

        // Parse group_members array
        val groupMembersArray = json.optJSONArray("group_members")
        val groupMembers = if (groupMembersArray != null) {
            (0 until groupMembersArray.length()).mapNotNull { i ->
                groupMembersArray.optString(i).takeIf { it.isNotEmpty() }
            }
        } else {
            emptyList()
        }

        // Parse can_group_with array
        val canGroupWithArray = json.optJSONArray("can_group_with")
        val canGroupWith = if (canGroupWithArray != null) {
            (0 until canGroupWithArray.length()).mapNotNull { i ->
                canGroupWithArray.optString(i).takeIf { it.isNotEmpty() }
            }
        } else {
            emptyList()
        }

        // Parse supported_features array
        val featuresArray = json.optJSONArray("supported_features")
        val features = if (featuresArray != null) {
            (0 until featuresArray.length()).mapNotNull { i ->
                val featureValue = featuresArray.optString(i, "")
                if (featureValue.isNotEmpty()) parsePlayerFeature(featureValue) else null
            }.toSet()
        } else {
            emptySet()
        }

        return MaPlayer(
            playerId = playerId,
            name = json.optString("display_name", "")
                .ifEmpty { json.optString("name", "Unknown Player") },
            type = parsePlayerType(json.optString("type", "unknown")),
            provider = json.optString("provider", ""),
            available = json.optBoolean("available", false),
            powered = if (json.has("powered")) json.optBoolean("powered") else null,
            playbackState = parsePlaybackState(
                json.optString("state", "")
                    .ifEmpty { json.optString("playback_state", "unknown") }
            ),
            volumeLevel = if (json.has("volume_level")) json.optInt("volume_level", 0) else null,
            volumeMuted = if (json.has("volume_muted")) json.optBoolean("volume_muted") else null,
            groupMembers = groupMembers,
            canGroupWith = canGroupWith,
            syncedTo = json.optString("synced_to", "").takeIf { it.isNotEmpty() },
            activeGroup = json.optString("active_group", "").takeIf { it.isNotEmpty() },
            supportedFeatures = features,
            icon = json.optString("icon", "mdi-speaker"),
            enabled = json.optBoolean("enabled", true),
            hideInUi = json.optBoolean("hide_in_ui", false)
        )
    }

    /**
     * Parse a player type string from the MA API.
     */
    private fun parsePlayerType(value: String): MaPlayerType = when (value.lowercase()) {
        "player" -> MaPlayerType.PLAYER
        "stereo_pair" -> MaPlayerType.STEREO_PAIR
        "group" -> MaPlayerType.GROUP
        "protocol" -> MaPlayerType.PROTOCOL
        else -> MaPlayerType.UNKNOWN
    }

    /**
     * Parse a playback state string from the MA API.
     */
    private fun parsePlaybackState(value: String): MaPlaybackState = when (value.lowercase()) {
        "idle" -> MaPlaybackState.IDLE
        "paused" -> MaPlaybackState.PAUSED
        "playing" -> MaPlaybackState.PLAYING
        else -> MaPlaybackState.UNKNOWN
    }

    /**
     * Parse a player feature string from the MA API.
     */
    private fun parsePlayerFeature(value: String): MaPlayerFeature = when (value.lowercase()) {
        "power" -> MaPlayerFeature.POWER
        "volume_set" -> MaPlayerFeature.VOLUME_SET
        "volume_mute" -> MaPlayerFeature.VOLUME_MUTE
        "pause" -> MaPlayerFeature.PAUSE
        "set_members" -> MaPlayerFeature.SET_MEMBERS
        "seek" -> MaPlayerFeature.SEEK
        "next_previous" -> MaPlayerFeature.NEXT_PREVIOUS
        "play_announcement" -> MaPlayerFeature.PLAY_ANNOUNCEMENT
        "enqueue" -> MaPlayerFeature.ENQUEUE
        "select_source" -> MaPlayerFeature.SELECT_SOURCE
        "gapless_playback" -> MaPlayerFeature.GAPLESS_PLAYBACK
        "play_media" -> MaPlayerFeature.PLAY_MEDIA
        else -> MaPlayerFeature.UNKNOWN
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
     * Send a command to the Music Assistant API via the persistent transport.
     *
     * All 40+ API methods funnel through this single method. The transport
     * handles connection persistence, authentication, and message_id correlation.
     *
     * Note: The apiUrl and token parameters are kept for signature compatibility
     * with all existing call sites but are not used - the transport is already
     * authenticated at connect time.
     *
     * @param apiUrl (unused) The MA WebSocket URL - kept for call site compatibility
     * @param token (unused) The auth token - kept for call site compatibility
     * @param command The MA command to execute (e.g., "players/all")
     * @param args Command arguments as a map
     * @return The JSON response object
     * @throws IOException if transport is disconnected
     * @throws MaApiTransport.MaCommandException if server returns an error
     */
    private suspend fun sendMaCommand(
        @Suppress("UNUSED_PARAMETER") apiUrl: String,
        @Suppress("UNUSED_PARAMETER") token: String,
        command: String,
        args: Map<String, Any>
    ): JSONObject {
        val transport = apiTransport
            ?: throw IOException("MA API transport not connected")
        return transport.sendCommand(command, args, COMMAND_TIMEOUT_MS)
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
     * Get a single playlist by ID.
     *
     * @param playlistId The MA playlist item_id
     * @return Result with the playlist
     */
    suspend fun getPlaylist(playlistId: String): Result<MaPlaylist> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching playlist: $playlistId")

                val response = sendMaCommand(
                    apiUrl, token, "music/playlists/get",
                    mapOf(
                        "item_id" to playlistId,
                        "provider_instance_id_or_domain" to "library"
                    )
                )
                val item = response.optJSONObject("result")
                    ?: return@withContext Result.failure(Exception("Playlist not found"))

                val id = item.optString("item_id", "")
                    .ifEmpty { item.optString("playlist_id", "") }
                    .ifEmpty { return@withContext Result.failure(Exception("Playlist has no ID")) }

                val name = item.optString("name", "")
                if (name.isEmpty()) return@withContext Result.failure(Exception("Playlist has no name"))

                val imageUri = extractImageUri(item).ifEmpty { null }
                val trackCount = item.optInt("track_count", 0)
                val owner = item.optString("owner", "").ifEmpty { null }
                val uri = item.optString("uri", "").ifEmpty { null }

                val playlist = MaPlaylist(
                    playlistId = id,
                    name = name,
                    imageUri = imageUri,
                    trackCount = trackCount,
                    owner = owner,
                    uri = uri
                )

                Log.d(TAG, "Got playlist: ${playlist.name}")
                Result.success(playlist)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch playlist: $playlistId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get tracks in a playlist.
     *
     * @param playlistId The MA playlist item_id
     * @return Result with list of tracks
     */
    suspend fun getPlaylistTracks(playlistId: String): Result<List<MaTrack>> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching playlist tracks for: $playlistId")

                val response = sendMaCommand(
                    apiUrl, token, "music/playlists/playlist_tracks",
                    mapOf(
                        "item_id" to playlistId,
                        "provider_instance_id_or_domain" to "library"
                    )
                )
                val tracks = parseAlbumTracks(response.optJSONArray("result"))

                Log.d(TAG, "Got ${tracks.size} tracks for playlist $playlistId")
                Result.success(tracks)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch playlist tracks: $playlistId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Create a new playlist.
     *
     * @param name The playlist name
     * @return Result with the created playlist
     */
    suspend fun createPlaylist(name: String): Result<MaPlaylist> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Creating playlist: $name")

                val response = sendMaCommand(
                    apiUrl, token, "music/playlists/create_playlist",
                    mapOf("name" to name)
                )
                val item = response.optJSONObject("result")
                    ?: return@withContext Result.failure(Exception("Failed to create playlist"))

                val id = item.optString("item_id", "")
                    .ifEmpty { item.optString("playlist_id", "") }
                    .ifEmpty { return@withContext Result.failure(Exception("Created playlist has no ID")) }

                val imageUri = extractImageUri(item).ifEmpty { null }
                val uri = item.optString("uri", "").ifEmpty { null }

                val playlist = MaPlaylist(
                    playlistId = id,
                    name = item.optString("name", name),
                    imageUri = imageUri,
                    trackCount = 0,
                    owner = item.optString("owner", "").ifEmpty { null },
                    uri = uri
                )

                Log.d(TAG, "Created playlist: ${playlist.name} (id=${playlist.playlistId})")
                Result.success(playlist)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create playlist: $name", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Delete a playlist from the library.
     *
     * @param playlistId The MA playlist item_id
     * @return Result indicating success or failure
     */
    suspend fun deletePlaylist(playlistId: String): Result<Unit> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Deleting playlist: $playlistId")

                sendMaCommand(
                    apiUrl, token, "music/playlists/remove",
                    mapOf(
                        "item_id" to playlistId,
                        "provider_instance_id_or_domain" to "library"
                    )
                )

                Log.d(TAG, "Deleted playlist: $playlistId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete playlist: $playlistId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Add tracks to a playlist.
     *
     * @param playlistId The MA playlist item_id
     * @param trackUris List of track URIs to add
     * @return Result indicating success or failure
     */
    suspend fun addPlaylistTracks(playlistId: String, trackUris: List<String>): Result<Unit> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Adding ${trackUris.size} tracks to playlist: $playlistId")

                sendMaCommand(
                    apiUrl, token, "music/playlists/add_playlist_tracks",
                    mapOf(
                        "db_playlist_id" to playlistId,
                        "uris" to trackUris
                    )
                )

                Log.d(TAG, "Added tracks to playlist: $playlistId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add tracks to playlist: $playlistId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Remove tracks from a playlist by position.
     *
     * @param playlistId The MA playlist item_id
     * @param positions List of 1-based track positions to remove
     * @return Result indicating success or failure
     */
    suspend fun removePlaylistTracks(playlistId: String, positions: List<Int>): Result<Unit> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Removing ${positions.size} tracks from playlist: $playlistId")

                sendMaCommand(
                    apiUrl, token, "music/playlists/remove_playlist_tracks",
                    mapOf(
                        "db_playlist_id" to playlistId,
                        "positions_to_remove" to positions
                    )
                )

                Log.d(TAG, "Removed tracks from playlist: $playlistId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove tracks from playlist: $playlistId", e)
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
        // webrtc://ma-api -> ma-proxy:// (proxied over DataChannel)
        val apiUrl = currentApiUrl ?: ""
        val isRemoteMode = apiUrl == MaApiEndpoint.WEBRTC_SENTINEL_URL
        val baseUrl = if (isRemoteMode) {
            // REMOTE mode: use ma-proxy:// scheme for Coil MaProxyImageFetcher
            // URLs become: ma-proxy:///imageproxy?provider=x&path=y (proxied over DataChannel)
            "${MaProxyImageFetcher.SCHEME}://"
        } else {
            apiUrl
                .replace("/ws", "")
                .replace("wss://", "https://")
                .replace("ws://", "http://")
        }

        // Try direct image field - can be a URL string or a JSONObject with path/provider
        val imageField = json.opt("image")
        when (imageField) {
            is String -> {
                if (imageField.startsWith("http")) {
                    return maybeProxyImageUrl(imageField, isRemoteMode, baseUrl)
                }
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
                    if (albumImageField.startsWith("http")) {
                        return maybeProxyImageUrl(albumImageField, isRemoteMode, baseUrl)
                    }
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
     * In REMOTE mode, rewrite server-local image URLs to use the ma-proxy:// scheme.
     *
     * The MA server may return fully-qualified URLs like:
     *   https://music.example.com/imageproxy?provider=x&path=y
     * These point to the server's internal network and aren't reachable from
     * a remote client. We extract the path+query and rewrite as:
     *   ma-proxy:///imageproxy?provider=x&path=y
     *
     * URLs that don't contain "/imageproxy" are assumed to be truly remote
     * (e.g., CDN URLs from Spotify/YouTube) and are returned as-is.
     */
    private fun maybeProxyImageUrl(url: String, isRemoteMode: Boolean, baseUrl: String): String {
        if (!isRemoteMode) return url

        // Check if this is a server-local imageproxy URL
        val proxyIndex = url.indexOf("/imageproxy")
        if (proxyIndex >= 0) {
            // Extract path+query: /imageproxy?provider=x&path=y
            val pathAndQuery = url.substring(proxyIndex)
            return "$baseUrl$pathAndQuery"
        }

        // Not an imageproxy URL — likely a CDN URL that's accessible from anywhere
        return url
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
     * Get ALL tracks for an artist (no truncation).
     *
     * Unlike getArtistDetails() which returns only top 10 tracks,
     * this returns the full list for bulk operations like "Add to Playlist".
     *
     * @param artistId The MA artist item_id
     * @return Result with complete list of artist tracks
     */
    suspend fun getArtistTracks(artistId: String): Result<List<MaTrack>> {
        val apiUrl = currentApiUrl ?: return Result.failure(Exception("Not connected to MA"))
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        val token = MaSettings.getTokenForServer(server.id)
            ?: return Result.failure(Exception("No auth token available"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching all tracks for artist: $artistId")

                val tracksResponse = sendMaCommand(
                    apiUrl, token, "music/artists/artist_tracks",
                    mapOf(
                        "item_id" to artistId,
                        "provider_instance_id_or_domain" to "library",
                        "in_library_only" to false
                    )
                )
                val tracks = parseTracksArray(
                    tracksResponse.optJSONArray("result")
                )

                Log.d(TAG, "Got ${tracks.size} tracks for artist $artistId")
                Result.success(tracks)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch artist tracks: $artistId", e)
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

    // ========================================================================
    // Queue Response Parsing
    // ========================================================================

    /**
     * Parse queue state from the queue/get and queue/items responses.
     *
     * The queue/get response contains queue metadata (shuffle, repeat, current_index).
     * The queue/items response contains the actual track items in the queue.
     */
    private fun parseQueueState(
        queueResponse: JSONObject,
        itemsResponse: JSONObject
    ): MaQueueState {
        // Parse queue settings from queue/get response
        val queueResult = queueResponse.optJSONObject("result") ?: queueResponse
        val shuffleEnabled = queueResult.optBoolean("shuffle_enabled", false)
        val repeatModeRaw = queueResult.optString("repeat_mode", "off")
        val repeatMode = when {
            repeatModeRaw.contains("one", ignoreCase = true) -> "one"
            repeatModeRaw.contains("all", ignoreCase = true) -> "all"
            else -> "off"
        }
        val currentIndex = queueResult.optInt("current_index", -1)
        val currentItemId = queueResult.optString("current_item", "")

        // Parse queue items from queue/items response
        val items = mutableListOf<MaQueueItem>()
        val resultArray = itemsResponse.optJSONArray("result")
            ?: itemsResponse.optJSONObject("result")?.optJSONArray("items")
            ?: JSONArray()

        for (i in 0 until resultArray.length()) {
            val item = resultArray.optJSONObject(i) ?: continue

            val queueItemId = item.optString("queue_item_id", "")
                .ifEmpty { item.optString("item_id", "") }
                .ifEmpty { item.optString("id", "") }

            if (queueItemId.isEmpty()) continue

            val name = item.optString("name", "")
                .ifEmpty {
                    // Try nested media_item for track name
                    item.optJSONObject("media_item")?.optString("name", "") ?: ""
                }

            // Try to get artist/album from the item or its nested media_item
            val mediaItem = item.optJSONObject("media_item")
            val artist = extractQueueItemArtist(item, mediaItem)
            val album = extractQueueItemAlbum(item, mediaItem)
            val imageUri = extractQueueItemImage(item, mediaItem)
            val duration = item.optLong("duration", 0L).let { if (it > 0) it else null }
            val uri = item.optString("uri", "")
                .ifEmpty { mediaItem?.optString("uri", "") ?: "" }
                .ifEmpty { null }

            // Determine if this is the currently playing item
            val isCurrentItem = (i == currentIndex) ||
                (currentItemId.isNotEmpty() && queueItemId == currentItemId)

            items.add(
                MaQueueItem(
                    queueItemId = queueItemId,
                    name = name.ifEmpty { "Unknown Track" },
                    artist = artist,
                    album = album,
                    imageUri = imageUri,
                    duration = duration,
                    uri = uri,
                    isCurrentItem = isCurrentItem
                )
            )
        }

        return MaQueueState(
            items = items,
            currentIndex = currentIndex,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode
        )
    }

    /**
     * Extract artist name from a queue item JSON.
     *
     * The "artist" field may be a string (simple name) or a JSON object/array
     * (structured reference). We must check the type before using optString,
     * which would serialize an object to its JSON representation.
     */
    private fun extractQueueItemArtist(item: JSONObject, mediaItem: JSONObject?): String? {
        // Direct artist field (only if it's actually a string, not an object/array)
        if (item.has("artist") && item.opt("artist") is String) {
            val directArtist = item.optString("artist", "")
            if (directArtist.isNotEmpty()) return directArtist
        }

        // From media_item
        if (mediaItem != null) {
            if (mediaItem.has("artist") && mediaItem.opt("artist") is String) {
                val mediaArtist = mediaItem.optString("artist", "")
                if (mediaArtist.isNotEmpty()) return mediaArtist
            }

            // From media_item.artists array
            val artists = mediaItem.optJSONArray("artists")
            if (artists != null && artists.length() > 0) {
                val firstArtist = artists.optJSONObject(0)
                val artistName = firstArtist?.optString("name", "")
                if (!artistName.isNullOrEmpty()) return artistName
            }
        }

        // From item.artists array
        val artists = item.optJSONArray("artists")
        if (artists != null && artists.length() > 0) {
            val firstArtist = artists.optJSONObject(0)
            val artistName = firstArtist?.optString("name", "")
            if (!artistName.isNullOrEmpty()) return artistName
        }

        // artist field might be an object with a name property
        item.optJSONObject("artist")?.let { artistObj ->
            val name = artistObj.optString("name", "")
            if (name.isNotEmpty()) return name
        }
        mediaItem?.optJSONObject("artist")?.let { artistObj ->
            val name = artistObj.optString("name", "")
            if (name.isNotEmpty()) return name
        }

        return null
    }

    /**
     * Extract album name from a queue item JSON.
     *
     * The "album" field may be a string (simple name) or a JSON object
     * (structured reference with item_id, provider, etc.). We must check
     * the type before using optString, which would serialize an object.
     */
    private fun extractQueueItemAlbum(item: JSONObject, mediaItem: JSONObject?): String? {
        // Direct album field (only if it's actually a string, not an object)
        if (item.has("album") && item.opt("album") is String) {
            val directAlbum = item.optString("album", "")
            if (directAlbum.isNotEmpty()) return directAlbum
        }

        // From media_item
        if (mediaItem != null) {
            if (mediaItem.has("album") && mediaItem.opt("album") is String) {
                val mediaAlbum = mediaItem.optString("album", "")
                if (mediaAlbum.isNotEmpty()) return mediaAlbum
            }

            // From media_item.album object
            val albumObj = mediaItem.optJSONObject("album")
            if (albumObj != null) {
                val albumName = albumObj.optString("name", "")
                if (albumName.isNotEmpty()) return albumName
            }
        }

        // From item.album object
        val albumObj = item.optJSONObject("album")
        if (albumObj != null) {
            val albumName = albumObj.optString("name", "")
            if (albumName.isNotEmpty()) return albumName
        }

        return null
    }

    /**
     * Extract image URI from a queue item JSON.
     */
    private fun extractQueueItemImage(item: JSONObject, mediaItem: JSONObject?): String? {
        // Try item-level image first
        val itemImage = extractImageUri(item)
        if (itemImage.isNotEmpty()) return itemImage

        // Try media_item image
        if (mediaItem != null) {
            val mediaImage = extractImageUri(mediaItem)
            if (mediaImage.isNotEmpty()) return mediaImage

            // Try media_item.album image
            val albumObj = mediaItem.optJSONObject("album")
            if (albumObj != null) {
                val albumImage = extractImageUri(albumObj)
                if (albumImage.isNotEmpty()) return albumImage
            }
        }

        // Try item.album image
        val albumObj = item.optJSONObject("album")
        if (albumObj != null) {
            val albumImage = extractImageUri(albumObj)
            if (albumImage.isNotEmpty()) return albumImage
        }

        return null
    }
}
