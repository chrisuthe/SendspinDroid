package com.sendspindroid.musicassistant

import android.content.Context
import android.util.Log
import com.sendspindroid.UserSettings
import com.sendspindroid.UserSettings.ConnectionMode
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.model.MaConnectionState
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType
import com.sendspindroid.musicassistant.model.MaPlayer
import com.sendspindroid.musicassistant.model.MaServerInfo
import com.sendspindroid.musicassistant.transport.MaApiTransport
import com.sendspindroid.musicassistant.transport.MaDataChannelTransport
import com.sendspindroid.musicassistant.transport.MaWebSocketTransport
import org.webrtc.DataChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sendspindroid.musicassistant.transport.MaTransportException
import java.io.IOException

/** Map app ConnectionMode to shared MaConnectionMode. */
private fun ConnectionMode.toMaMode(): MaConnectionMode = when (this) {
    ConnectionMode.LOCAL -> MaConnectionMode.LOCAL
    ConnectionMode.REMOTE -> MaConnectionMode.REMOTE
    ConnectionMode.PROXY -> MaConnectionMode.PROXY
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
 * ## Architecture
 * All command methods and JSON parsing are delegated to the shared
 * [MaCommandClient]. This manager handles only Android-specific concerns:
 * - Context initialization
 * - DataChannel transport management (WebRTC)
 * - Connection lifecycle
 * - Token/credential authentication flow
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

    // Shared command client — all command/parsing logic lives here
    private val commandClient = MaCommandClient(MaSettings)

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
        val apiUrl = MaApiEndpoint.deriveUrl(server, connectionMode.toMaMode(), MaSettings.getDefaultPort())
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

        // Clear command client transport
        commandClient.setTransport(null, null, false)

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
                val isRemote = apiUrl == MaApiEndpoint.WEBRTC_SENTINEL_URL
                commandClient.setTransport(transport, apiUrl, isRemote)

                val serverInfo = MaServerInfo(
                    serverId = serverId,
                    serverVersion = transport.serverVersion ?: "unknown",
                    apiUrl = apiUrl,
                    maServerId = transport.maServerId
                )

                Log.i(TAG, "MA API connected successfully (server ${transport.serverVersion})")
                _connectionState.value = MaConnectionState.Connected(serverInfo)

                // Auto-select a player for playback commands
                commandClient.autoSelectPlayer(serverId).fold(
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
                commandClient.setTransport(null, null, false)
                MaSettings.clearTokenForServer(serverId)
                _connectionState.value = MaConnectionState.Error(
                    message = "Authentication expired. Please log in again.",
                    isAuthError = true
                )
            } catch (e: MaTransportException) {
                Log.e(TAG, "Network error connecting to MA API", e)
                apiTransport?.disconnect()
                apiTransport = null
                commandClient.setTransport(null, null, false)
                _connectionState.value = MaConnectionState.Error(
                    message = "Network error: ${e.message}",
                    isAuthError = false
                )
            } catch (e: IOException) {
                Log.e(TAG, "Network error connecting to MA API", e)
                apiTransport?.disconnect()
                apiTransport = null
                commandClient.setTransport(null, null, false)
                _connectionState.value = MaConnectionState.Error(
                    message = "Network error: ${e.message}",
                    isAuthError = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to MA API", e)
                apiTransport?.disconnect()
                apiTransport = null
                commandClient.setTransport(null, null, false)
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
     */
    private fun createTransport(apiUrl: String): MaApiTransport? {
        val mode = currentConnectionMode ?: return null

        if (mode == ConnectionMode.REMOTE) {
            val pending = pendingDataChannelTransport
            if (pending != null) {
                pendingDataChannelTransport = null  // Consumed; don't reuse
                Log.d(TAG, "Using pre-created DataChannel transport (serverInfo=${pending.serverVersion != null})")
                return pending
            }
            val channel = maApiDataChannel
            if (channel != null) {
                Log.w(TAG, "Creating fresh DataChannel transport (pre-created was null)")
                return MaDataChannelTransport(channel)
            }
            // No DataChannel — try WebSocket fallback
            val wsUrl = currentServer?.let { server ->
                MaApiEndpoint.deriveFromLocal(server, MaSettings.getDefaultPort()) ?: MaApiEndpoint.deriveFromProxy(server)
            }
            return if (wsUrl != null) MaWebSocketTransport(wsUrl) else null
        }

        // LOCAL/PROXY: WebSocket transport
        return MaWebSocketTransport(apiUrl)
    }

    /**
     * Perform fresh login with username/password credentials.
     */
    suspend fun login(username: String, password: String): Boolean {
        val server = currentServer ?: return false
        val apiUrl = currentApiUrl ?: return false

        _connectionState.value = MaConnectionState.Connecting

        return try {
            apiTransport?.disconnect()
            apiTransport = null

            val transport = createTransport(apiUrl)
                ?: throw IOException("Cannot create MA API transport for mode $currentConnectionMode")

            val loginResult = transport.connectWithCredentials(username, password)

            apiTransport = transport
            val isRemote = apiUrl == MaApiEndpoint.WEBRTC_SENTINEL_URL
            commandClient.setTransport(transport, apiUrl, isRemote)

            MaSettings.setTokenForServer(server.id, loginResult.accessToken)

            val serverInfo = MaServerInfo(
                serverId = server.id,
                serverVersion = transport.serverVersion ?: "unknown",
                apiUrl = apiUrl,
                maServerId = transport.maServerId
            )

            Log.i(TAG, "MA login successful for user: ${loginResult.userName} (server ${transport.serverVersion})")
            _connectionState.value = MaConnectionState.Connected(serverInfo)

            commandClient.autoSelectPlayer(server.id).fold(
                onSuccess = { playerId -> Log.i(TAG, "Auto-selected player for playback: $playerId") },
                onFailure = { error -> Log.w(TAG, "No players available for auto-selection: ${error.message}") }
            )

            true

        } catch (e: MaApiTransport.AuthenticationException) {
            Log.e(TAG, "Login failed: invalid credentials", e)
            apiTransport?.disconnect()
            apiTransport = null
            commandClient.setTransport(null, null, false)
            _connectionState.value = MaConnectionState.Error(
                message = "Invalid username or password",
                isAuthError = true
            )
            false
        } catch (e: MaTransportException) {
            Log.e(TAG, "Login failed: network error", e)
            apiTransport?.disconnect()
            apiTransport = null
            commandClient.setTransport(null, null, false)
            _connectionState.value = MaConnectionState.Error(
                message = "Network error: ${e.message}",
                isAuthError = false
            )
            false
        } catch (e: IOException) {
            Log.e(TAG, "Login failed: network error", e)
            apiTransport?.disconnect()
            apiTransport = null
            commandClient.setTransport(null, null, false)
            _connectionState.value = MaConnectionState.Error(
                message = "Network error: ${e.message}",
                isAuthError = false
            )
            false
        } catch (e: Exception) {
            Log.e(TAG, "Login failed", e)
            apiTransport?.disconnect()
            apiTransport = null
            commandClient.setTransport(null, null, false)
            _connectionState.value = MaConnectionState.Error(
                message = e.message ?: "Login failed",
                isAuthError = false
            )
            false
        }
    }

    /**
     * Authenticate with an existing token.
     */
    suspend fun authWithToken(token: String): Boolean {
        val server = currentServer ?: return false
        val apiUrl = currentApiUrl ?: return false

        _connectionState.value = MaConnectionState.Connecting

        return try {
            apiTransport?.disconnect()
            apiTransport = null

            val transport = createTransport(apiUrl)
                ?: throw IOException("Cannot create MA API transport")

            transport.connect(token)
            apiTransport = transport
            val isRemote = apiUrl == MaApiEndpoint.WEBRTC_SENTINEL_URL
            commandClient.setTransport(transport, apiUrl, isRemote)

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
            commandClient.setTransport(null, null, false)
            MaSettings.clearTokenForServer(server.id)
            _connectionState.value = MaConnectionState.Error(
                message = "Authentication expired. Please log in again.",
                isAuthError = true
            )
            false
        } catch (e: MaTransportException) {
            Log.e(TAG, "Token auth network error", e)
            apiTransport?.disconnect()
            apiTransport = null
            commandClient.setTransport(null, null, false)
            _connectionState.value = MaConnectionState.Error(
                message = "Network error: ${e.message}",
                isAuthError = false
            )
            false
        } catch (e: IOException) {
            Log.e(TAG, "Token auth network error", e)
            apiTransport?.disconnect()
            apiTransport = null
            commandClient.setTransport(null, null, false)
            _connectionState.value = MaConnectionState.Error(
                message = "Network error: ${e.message}",
                isAuthError = false
            )
            false
        } catch (e: Exception) {
            Log.e(TAG, "Token auth failed", e)
            apiTransport?.disconnect()
            apiTransport = null
            commandClient.setTransport(null, null, false)
            _connectionState.value = MaConnectionState.Error(
                message = "Authentication failed: ${e.message}",
                isAuthError = true
            )
            false
        }
    }

    /**
     * Clear authentication state and request re-login.
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
    // Player Selection (thin wrappers for local state)
    // ========================================================================

    /**
     * Get the currently selected player for the connected server.
     */
    fun getSelectedPlayer(): String? {
        val server = currentServer ?: return null
        return MaSettings.getSelectedPlayerForServer(server.id)
    }

    /**
     * Set the player to use for playback commands.
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
     */
    fun getThisDevicePlayerId(): String = UserSettings.getPlayerId()

    // ========================================================================
    // Delegated Command Methods
    // ========================================================================

    // Helper to resolve the effective queue ID through the command client
    private suspend fun resolveQueueId(): String {
        val playerId = UserSettings.getPlayerId()
        return commandClient.getEffectiveQueueId(playerId)
    }

    /**
     * Add the currently playing track to MA favorites.
     */
    suspend fun favoriteCurrentTrack(): Result<String> {
        return withContext(Dispatchers.IO) {
            commandClient.favoriteCurrentTrack()
        }
    }

    /**
     * Play a media item on the active MA player.
     */
    suspend fun playMedia(
        uri: String,
        mediaType: String? = null,
        enqueue: Boolean = false,
        enqueueMode: EnqueueMode? = null
    ): Result<Unit> {
        val effectiveMode = enqueueMode ?: if (enqueue) EnqueueMode.ADD else EnqueueMode.PLAY
        return withContext(Dispatchers.IO) {
            val queueId = resolveQueueId()
            commandClient.playMedia(uri, queueId, mediaType, effectiveMode)
        }
    }

    /**
     * Get the current queue items for the player.
     */
    suspend fun getQueueItems(limit: Int = 200, offset: Int = 0): Result<MaQueueState> {
        return withContext(Dispatchers.IO) {
            val queueId = resolveQueueId()
            commandClient.getQueueItems(queueId, limit, offset)
        }
    }

    /**
     * Clear all items from the queue.
     */
    suspend fun clearQueue(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val queueId = resolveQueueId()
            commandClient.clearQueue(queueId)
        }
    }

    /**
     * Jump to and play a specific item in the queue.
     */
    suspend fun playQueueItem(queueItemId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val queueId = resolveQueueId()
            commandClient.playQueueItem(queueId, queueItemId)
        }
    }

    /**
     * Remove a specific item from the queue.
     */
    suspend fun removeQueueItem(queueItemId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val queueId = resolveQueueId()
            commandClient.removeQueueItem(queueId, queueItemId)
        }
    }

    /**
     * Move a queue item to a new position.
     */
    suspend fun moveQueueItem(queueItemId: String, newIndex: Int): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val queueId = resolveQueueId()
            commandClient.moveQueueItem(queueId, queueItemId, newIndex)
        }
    }

    /**
     * Toggle shuffle mode for the queue.
     */
    suspend fun setQueueShuffle(enabled: Boolean): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val queueId = resolveQueueId()
            commandClient.setQueueShuffle(queueId, enabled)
        }
    }

    /**
     * Set repeat mode for the queue.
     */
    suspend fun setQueueRepeat(mode: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val queueId = resolveQueueId()
            commandClient.setQueueRepeat(queueId, mode)
        }
    }

    /**
     * Auto-detect and store the active player for the current server.
     */
    suspend fun autoSelectPlayer(): Result<String> {
        val server = currentServer ?: return Result.failure(Exception("No server connected"))
        return withContext(Dispatchers.IO) {
            commandClient.autoSelectPlayer(server.id)
        }
    }

    /**
     * Fetch all players from Music Assistant.
     */
    suspend fun getAllPlayers(): Result<List<MaPlayer>> {
        return withContext(Dispatchers.IO) {
            commandClient.getAllPlayers()
        }
    }

    /**
     * Set group members for a player.
     */
    suspend fun setGroupMembers(
        targetPlayerId: String,
        playerIdsToAdd: List<String>,
        playerIdsToRemove: List<String>
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            commandClient.setGroupMembers(targetPlayerId, playerIdsToAdd, playerIdsToRemove)
        }
    }

    /**
     * Power on a player.
     */
    suspend fun powerOnPlayer(playerId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            commandClient.powerOnPlayer(playerId)
        }
    }

    /**
     * Remove a player from all groups.
     */
    suspend fun ungroupPlayer(playerId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            commandClient.ungroupPlayer(playerId)
        }
    }

    /**
     * Get recently played items.
     */
    suspend fun getRecentlyPlayed(limit: Int = 15): Result<List<MaTrack>> {
        return withContext(Dispatchers.IO) {
            commandClient.getRecentlyPlayed(limit)
        }
    }

    /**
     * Get recently added library items.
     */
    suspend fun getRecentlyAdded(limit: Int = 15): Result<List<MaTrack>> {
        return withContext(Dispatchers.IO) {
            commandClient.getRecentlyAdded(limit)
        }
    }

    /**
     * Get tracks from the library.
     */
    suspend fun getTracks(limit: Int = 25, offset: Int = 0, orderBy: String = "name"): Result<List<MaTrack>> {
        return withContext(Dispatchers.IO) {
            commandClient.getTracks(limit, offset, orderBy)
        }
    }

    /**
     * Get playlists from the library.
     */
    suspend fun getPlaylists(limit: Int = 15, offset: Int = 0, orderBy: String = "name"): Result<List<MaPlaylist>> {
        return withContext(Dispatchers.IO) {
            commandClient.getPlaylists(limit, offset, orderBy)
        }
    }

    /**
     * Get a single playlist by ID.
     */
    suspend fun getPlaylist(playlistId: String): Result<MaPlaylist> {
        return withContext(Dispatchers.IO) {
            commandClient.getPlaylist(playlistId)
        }
    }

    /**
     * Get tracks in a playlist.
     */
    suspend fun getPlaylistTracks(playlistId: String): Result<List<MaTrack>> {
        return withContext(Dispatchers.IO) {
            commandClient.getPlaylistTracks(playlistId)
        }
    }

    /**
     * Create a new playlist.
     */
    suspend fun createPlaylist(name: String): Result<MaPlaylist> {
        return withContext(Dispatchers.IO) {
            commandClient.createPlaylist(name)
        }
    }

    /**
     * Delete a playlist.
     */
    suspend fun deletePlaylist(playlistId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            commandClient.deletePlaylist(playlistId)
        }
    }

    /**
     * Add tracks to a playlist.
     */
    suspend fun addPlaylistTracks(playlistId: String, trackUris: List<String>): Result<Unit> {
        return withContext(Dispatchers.IO) {
            commandClient.addPlaylistTracks(playlistId, trackUris)
        }
    }

    /**
     * Remove tracks from a playlist by position.
     */
    suspend fun removePlaylistTracks(playlistId: String, positions: List<Int>): Result<Unit> {
        return withContext(Dispatchers.IO) {
            commandClient.removePlaylistTracks(playlistId, positions)
        }
    }

    /**
     * Get albums from the library.
     */
    suspend fun getAlbums(limit: Int = 15, offset: Int = 0, orderBy: String = "name"): Result<List<MaAlbum>> {
        return withContext(Dispatchers.IO) {
            commandClient.getAlbums(limit, offset, orderBy)
        }
    }

    /**
     * Get a single album by ID.
     */
    suspend fun getAlbum(albumId: String): Result<MaAlbum> {
        return withContext(Dispatchers.IO) {
            commandClient.getAlbum(albumId)
        }
    }

    /**
     * Get tracks for an album.
     */
    suspend fun getAlbumTracks(albumId: String): Result<List<MaTrack>> {
        return withContext(Dispatchers.IO) {
            commandClient.getAlbumTracks(albumId)
        }
    }

    /**
     * Get artists from the library.
     */
    suspend fun getArtists(limit: Int = 15, offset: Int = 0, orderBy: String = "name"): Result<List<MaArtist>> {
        return withContext(Dispatchers.IO) {
            commandClient.getArtists(limit, offset, orderBy)
        }
    }

    /**
     * Get a single artist by ID.
     */
    suspend fun getArtist(artistId: String): Result<MaArtist> {
        return withContext(Dispatchers.IO) {
            commandClient.getArtist(artistId)
        }
    }

    /**
     * Get complete artist details including top tracks and discography.
     */
    suspend fun getArtistDetails(artistId: String): Result<ArtistDetails> {
        return withContext(Dispatchers.IO) {
            commandClient.getArtistDetails(artistId)
        }
    }

    /**
     * Get ALL tracks for an artist (no truncation).
     */
    suspend fun getArtistTracks(artistId: String): Result<List<MaTrack>> {
        return withContext(Dispatchers.IO) {
            commandClient.getArtistTracks(artistId)
        }
    }

    /**
     * Get radio stations from the library.
     */
    suspend fun getRadioStations(limit: Int = 15, offset: Int = 0, orderBy: String = "name"): Result<List<MaRadio>> {
        return withContext(Dispatchers.IO) {
            commandClient.getRadioStations(limit, offset, orderBy)
        }
    }

    /**
     * Get podcasts from the library.
     */
    suspend fun getPodcasts(limit: Int = 15, offset: Int = 0, orderBy: String = "name"): Result<List<MaPodcast>> {
        return withContext(Dispatchers.IO) {
            commandClient.getPodcasts(limit, offset, orderBy)
        }
    }

    /**
     * Get podcast episodes.
     */
    suspend fun getPodcastEpisodes(podcastId: String): Result<List<MaPodcastEpisode>> {
        return withContext(Dispatchers.IO) {
            commandClient.getPodcastEpisodes(podcastId)
        }
    }

    /**
     * Browse Music Assistant providers for folder-based content.
     */
    suspend fun browse(path: String? = null): Result<List<MaLibraryItem>> {
        return withContext(Dispatchers.IO) {
            commandClient.browse(path)
        }
    }

    /**
     * Search Music Assistant library.
     */
    suspend fun search(
        query: String,
        mediaTypes: List<MaMediaType>? = null,
        limit: Int = 25,
        libraryOnly: Boolean = true
    ): Result<SearchResults> {
        return withContext(Dispatchers.IO) {
            commandClient.search(query, mediaTypes, limit, libraryOnly)
        }
    }
}
