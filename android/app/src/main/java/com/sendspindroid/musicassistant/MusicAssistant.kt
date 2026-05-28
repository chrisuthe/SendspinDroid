package com.sendspindroid.musicassistant

import android.content.Context
import android.util.Log
import com.sendspindroid.UserSettings
import com.sendspindroid.UserSettings.ConnectionMode
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.coordinator.FailureReason
import com.sendspindroid.coordinator.TransportState
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sendspindroid.musicassistant.transport.MaTransportException
import java.io.IOException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Map app ConnectionMode to shared MaConnectionMode. */
private fun ConnectionMode.toMaMode(): MaConnectionMode = when (this) {
    ConnectionMode.LOCAL -> MaConnectionMode.LOCAL
    ConnectionMode.REMOTE -> MaConnectionMode.REMOTE
    ConnectionMode.PROXY -> MaConnectionMode.PROXY
}

/**
 * Derive the MA API WebSocket URL for this endpoint.
 *
 * LOCAL  -> ws://<host>:<port>/ws  (via WebSocketUrlBuilder for correct IPv6 handling)
 * PROXY  -> strips /sendspin suffix, converts http->ws, appends /ws
 * REMOTE -> sentinel URL "webrtc://ma-api" (signals DataChannel transport)
 */
private fun MaEndpoint.toApiUrl(): String = when (this) {
    is MaEndpoint.Local -> {
        val host = com.sendspindroid.network.WebSocketUrlBuilder.extractHost(address)
        com.sendspindroid.network.WebSocketUrlBuilder.buildFromHostPort(host, port, "/ws")
    }
    is MaEndpoint.Proxy -> {
        val stripped = baseUrl
            .removeSuffix("/sendspin")
            .trimEnd('/')
        val ws = when {
            stripped.startsWith("https://") -> stripped.replaceFirst("https://", "wss://")
            stripped.startsWith("http://") -> stripped.replaceFirst("http://", "ws://")
            stripped.startsWith("wss://") || stripped.startsWith("ws://") -> stripped
            else -> "wss://$stripped"
        }
        "$ws/ws"
    }
    is MaEndpoint.Remote -> MaApiEndpoint.WEBRTC_SENTINEL_URL
}

/**
 * Credentials for a Music Assistant authentication attempt.
 *
 * Phase 6 of the ConnectionCoordinator design.
 */
sealed class MaCredentials {
    data class Token(val token: String) : MaCredentials()
    data class UsernamePassword(val username: String, val password: String) : MaCredentials()
}

/**
 * Stateless one-shot helper for wizard tests. Creates a temporary MaApiTransport,
 * authenticates with the provided credentials, and returns success or failure.
 * Does NOT touch MusicAssistant singleton state -- safe to call while a live MA
 * session is active.
 *
 * Returns [MaAuthHelper.LoginResult] which carries the access token and server
 * metadata needed by the wizard to store the token and pre-populate server name.
 *
 * Phase 6 of the ConnectionCoordinator design.
 */
suspend fun testMaAuth(
    endpoint: MaEndpoint,
    credentials: MaCredentials,
): Result<MaAuthHelper.LoginResult> {
    val apiUrl = endpoint.toApiUrl()
    return try {
        val result = when (credentials) {
            is MaCredentials.Token -> {
                // Token auth: connect and derive a LoginResult from transport metadata.
                val transport = MaWebSocketTransport(apiUrl)
                try {
                    transport.connect(credentials.token)
                    MaAuthHelper.LoginResult(
                        accessToken = credentials.token,
                        userId = "",
                        userName = "",
                        serverVersion = transport.serverVersion ?: "unknown",
                        maServerId = transport.maServerId ?: "",
                        baseUrl = transport.baseUrl ?: ""
                    )
                } finally {
                    try { transport.disconnect() } catch (_: Exception) { /* swallow on cleanup */ }
                }
            }
            is MaCredentials.UsernamePassword ->
                MaAuthHelper.loginForToken(apiUrl, credentials.username, credentials.password)
        }
        Result.success(result)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Global singleton managing Music Assistant API availability.
 *
 * Provides a single source of truth for whether MA features should be
 * shown in the UI. Components observe [connectionState] to conditionally
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
/**
 * Metadata extracted from a Music Assistant `queue_updated` event.
 *
 * Null fields mean "no information available"; callers should preserve existing
 * state for null fields (matching the null-preserves-existing semantics of
 * [com.sendspindroid.model.PlaybackState.withMetadata]).
 */
data class QueueUpdate(
    val queueId: String?,
    val currentItemId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?
)

object MusicAssistant {

    private const val TAG = "MusicAssistant"

    // Internal mutable state
    private val _connectionState = MutableStateFlow<TransportState>(TransportState.Idle)

    /**
     * Transport connection state, unified with SendSpin's state type.
     *
     * Idle     - MA not applicable to this server, or no token yet
     * Connecting - authentication in progress
     * Ready    - successfully connected and authenticated; see [currentServerInfo]
     * Failed   - connection/auth failed; reason distinguishes auth vs transient
     */
    val connectionState: StateFlow<TransportState> = _connectionState.asStateFlow()

    /**
     * Emits once each time user authentication is required (no token stored for
     * an MA server, or a token was rejected). Observers should show a login UI.
     */
    private val _loginRequired = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val loginRequired: SharedFlow<Unit> = _loginRequired.asSharedFlow()

    /**
     * Server info for the currently connected MA session.
     * Non-null only when [connectionState] is [TransportState.Ready].
     */
    var currentServerInfo: MaServerInfo? = null
        private set

    // Queue update events from the MA command-channel (fix 8a).
    // Emitted when a `queue_updated` event arrives, roughly 1 second before
    // the equivalent SendSpin server/state broadcast with the same metadata.
    private val _queueUpdates = MutableSharedFlow<QueueUpdate>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val queueUpdates: SharedFlow<QueueUpdate> = _queueUpdates.asSharedFlow()

    // Current server info (when connected)
    private var currentServer: UnifiedServer? = null
    private var currentConnectionMode: ConnectionMode? = null
    private var currentApiUrl: String? = null

    // Persistent MA API transport (replaces fire-and-forget WebSocket pattern)
    @Volatile
    private var apiTransport: MaApiTransport? = null

    // Tracked coroutine for connectWithToken; cancelled on re-entry to prevent races (H-22)
    @Volatile
    private var connectJob: Job? = null

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
     * Initialize MusicAssistant with application context.
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
     * Connect to the given Music Assistant endpoint, optionally with a stored token.
     *
     * Single entry point that internally derives the apiUrl and dispatches to
     * connectWithToken when a token is present. With no token, transitions to
     * Idle and fires loginRequired so the UI prompts the user.
     *
     * The server must already be set on [currentServer] before calling this
     * (onServerConnected does this). Callers external to onServerConnected
     * (e.g., the setup wizard test path in Phase 6) must also ensure
     * [currentServer] and [currentConnectionMode] are set.
     */
    suspend fun connect(endpoint: MaEndpoint, token: String?) {
        val apiUrl = endpoint.toApiUrl()
        currentApiUrl = apiUrl
        Log.d(TAG, "MA API URL derived: $apiUrl")

        val server = currentServer
        if (token != null && server != null) {
            connectWithToken(apiUrl, token, server.id)
        } else {
            Log.w(TAG, "No token stored for MA server - user needs to re-authenticate")
            currentServerInfo = null
            _connectionState.value = TransportState.Idle
            _loginRequired.tryEmit(Unit)
        }
    }

    /**
     * Map a UnifiedServer + ConnectionMode to an MaEndpoint.
     * Returns null if the server has no configuration for the given mode.
     */
    private fun serverToMaEndpoint(server: UnifiedServer, mode: ConnectionMode): MaEndpoint? =
        when (mode) {
            ConnectionMode.LOCAL -> server.local?.let {
                MaEndpoint.Local(it.address, MaSettings.getDefaultPort())
            }
            ConnectionMode.PROXY -> server.proxy?.let {
                MaEndpoint.Proxy(it.url)
            }
            ConnectionMode.REMOTE -> {
                val remote = server.remote
                if (remote != null) {
                    MaEndpoint.Remote(remote.remoteId)
                } else {
                    // No remote config: fall back to local or proxy if available
                    server.local?.let { MaEndpoint.Local(it.address, MaSettings.getDefaultPort()) }
                        ?: server.proxy?.let { MaEndpoint.Proxy(it.url) }
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
            _connectionState.value = TransportState.Idle
            return
        }
        if (!server.isMusicAssistant) {
            Log.i(TAG, "Server not flagged as MA but auto-detected (token=$hasStoredToken, channel=$hasMaApiChannel)")
        }

        // Check 2: Can we reach the MA API?
        val endpoint = serverToMaEndpoint(server, connectionMode)
        if (endpoint == null) {
            Log.d(TAG, "No MA API endpoint available for connection mode $connectionMode")
            _connectionState.value = TransportState.Idle
            return
        }

        // Check 3: Do we have a stored token? Facade handles the token/no-token split.
        val token = MaSettings.getTokenForServer(server.id)
        scope.launch {
            connect(endpoint, token)
        }
    }

    /**
     * Called by PlaybackService when disconnecting from a server.
     */
    fun onServerDisconnected() {
        Log.d(TAG, "Server disconnected")

        // Cancel any in-flight connect attempt (H-22)
        connectJob?.cancel()
        connectJob = null

        // Disconnect the persistent transport
        apiTransport?.setEventListener(null)
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
        currentServerInfo = null
        _connectionState.value = TransportState.Idle
    }

    /**
     * Handle a connection/auth failure by tearing down the transport and
     * updating [_connectionState].
     *
     * Classifies the exception internally: [MaApiTransport.AuthenticationException]
     * maps to [FailureReason.AuthRejected] and clears the stored token; all other
     * exceptions map to [FailureReason.TransientNetwork] and preserve the token.
     *
     * @param e The exception that caused the failure
     * @param logPrefix Label for the log message (e.g. "Token auth", "Login")
     */
    private fun handleConnectionFailure(
        e: Exception,
        logPrefix: String,
    ) {
        Log.e(TAG, "$logPrefix failed", e)
        apiTransport?.setEventListener(null)
        apiTransport?.disconnect()
        apiTransport = null
        commandClient.setTransport(null, null, false)

        val reason: FailureReason = when (e) {
            is MaApiTransport.AuthenticationException -> FailureReason.AuthRejected
            is MaTransportException, is IOException -> FailureReason.TransientNetwork
            else -> FailureReason.TransientNetwork
        }

        // Single token-clearing site: ONLY on confirmed auth rejection.
        val server = currentServer
        if (reason == FailureReason.AuthRejected && server != null) {
            MaSettings.clearTokenForServer(server.id)
            _loginRequired.tryEmit(Unit)
        }

        currentServerInfo = null
        _connectionState.value = TransportState.Failed(reason)
    }

    // ========================================================================
    // Event listener: queue_updated fast metadata path (fix 8a)
    // ========================================================================

    /**
     * Receives server-push events from the MA command channel and emits
     * [QueueUpdate] for `queue_updated` events.
     *
     * This provides title/artist/album roughly 1 second before the equivalent
     * SendSpin `server/state` broadcast arrives. See fix 8a in
     * docs/architecture/sendspin-ma-metadata-flow.md.
     */
    private val queueEventListener = object : MaApiTransport.EventListener {
        override fun onEvent(event: kotlinx.serialization.json.JsonObject) {
            try {
                val eventType = event["event"]?.jsonPrimitive?.contentOrNull
                if (eventType != "queue_updated") return

                val data = event["data"]?.jsonObject ?: return
                val currentItem = data["current_item"]?.jsonObject ?: return

                val queueId = data["queue_id"]?.jsonPrimitive?.contentOrNull
                val currentItemId = data["current_item_id"]?.jsonPrimitive?.contentOrNull
                val durationMs = currentItem["duration"]?.jsonPrimitive?.longOrNull?.let { it * 1000L }

                val mediaItem = currentItem["media_item"]?.jsonObject
                val title = mediaItem?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val artist = mediaItem?.get("artists")?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("name")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                val album = mediaItem?.get("album")?.jsonObject
                    ?.get("name")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }

                val update = QueueUpdate(
                    queueId = queueId,
                    currentItemId = currentItemId,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs
                )
                Log.v(TAG, "queue_updated: title=$title artist=$artist album=$album durationMs=$durationMs")
                _queueUpdates.tryEmit(update)
            } catch (e: Exception) {
                Log.v(TAG, "Failed to parse queue_updated event, ignoring", e)
            }
        }

        override fun onDisconnected(reason: String) {
            Log.d(TAG, "Event transport disconnected: $reason")
        }
    }

    /**
     * Connect a transport and store it for subsequent API calls.
     *
     * Shared setup used by [connectWithToken], [login], and [authWithToken].
     *
     * @return The server info on success
     */
    private suspend fun connectTransport(
        apiUrl: String,
        serverId: String,
        authenticate: suspend (MaApiTransport) -> Unit
    ): MaServerInfo {
        apiTransport?.setEventListener(null)
        apiTransport?.disconnect()
        apiTransport = null

        val transport = createTransport(apiUrl)
            ?: throw IOException("Cannot create MA API transport for mode $currentConnectionMode")

        authenticate(transport)

        apiTransport = transport
        transport.setEventListener(queueEventListener)
        val isRemote = apiUrl == MaApiEndpoint.WEBRTC_SENTINEL_URL
        commandClient.setTransport(transport, apiUrl, isRemote)

        val serverInfo = MaServerInfo(
            serverId = serverId,
            serverVersion = transport.serverVersion ?: "unknown",
            apiUrl = apiUrl,
            maServerId = transport.maServerId
        )

        Log.i(TAG, "MA API connected successfully (server ${transport.serverVersion})")
        currentServerInfo = serverInfo
        _connectionState.value = TransportState.Ready

        commandClient.autoSelectPlayer(serverId).fold(
            onSuccess = { playerId -> Log.i(TAG, "Auto-selected player for playback: $playerId") },
            onFailure = { error -> Log.w(TAG, "No players available for auto-selection: ${error.message}") }
        )

        return serverInfo
    }

    /**
     * Attempt to authenticate with a stored token.
     *
     * Establishes a persistent transport connection (WebSocket for LOCAL/PROXY,
     * DataChannel for REMOTE) and authenticates with the given token.
     * All subsequent API calls are multiplexed over this single connection.
     */
    private fun connectWithToken(apiUrl: String, token: String, serverId: String) {
        _connectionState.value = TransportState.Connecting

        // Cancel any in-flight connect attempt to prevent racing on apiTransport (H-22)
        connectJob?.cancel()
        connectJob = scope.launch {
            runCatching {
                connectTransport(apiUrl, serverId) { transport -> transport.connect(token) }
            }.onFailure { e ->
                handleConnectionFailure(
                    e as? Exception ?: Exception(e),
                    "Token authentication",
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
    suspend fun login(username: String, password: String): Result<Unit> {
        val server = currentServer
            ?: return Result.failure(IllegalStateException("No server connected"))
        val apiUrl = currentApiUrl
            ?: return Result.failure(IllegalStateException("No MA API URL available"))

        _connectionState.value = TransportState.Connecting

        return withContext(Dispatchers.IO) {
            runCatching {
                connectTransport(apiUrl, server.id) { transport ->
                    val loginResult = transport.connectWithCredentials(username, password)
                    MaSettings.setTokenForServer(server.id, loginResult.accessToken)
                    Log.i(TAG, "MA login successful for user: ${loginResult.userName}")
                }
                Unit
            }.onFailure { e ->
                handleConnectionFailure(e as? Exception ?: Exception(e), "Login")
            }
        }
    }

    /**
     * Authenticate with an existing token.
     */
    suspend fun authWithToken(token: String): Result<Unit> {
        val server = currentServer
            ?: return Result.failure(IllegalStateException("No server connected"))
        val apiUrl = currentApiUrl
            ?: return Result.failure(IllegalStateException("No MA API URL available"))

        _connectionState.value = TransportState.Connecting

        return withContext(Dispatchers.IO) {
            runCatching {
                connectTransport(apiUrl, server.id) { transport ->
                    transport.connect(token)
                }
                Unit
            }.onFailure { e ->
                handleConnectionFailure(
                    e as? Exception ?: Exception(e),
                    "Token auth",
                )
            }
        }
    }

    /**
     * Clear authentication state and request re-login.
     */
    fun clearAuth() {
        currentServer?.let { server ->
            MaSettings.clearTokenForServer(server.id)
        }
        currentServerInfo = null
        _connectionState.value = TransportState.Idle
        _loginRequired.tryEmit(Unit)
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

    /**
     * Resolve this device's id to Music Assistant's internal player id.
     *
     * MA registers SendSpin-provider players under a transformed id
     * (e.g. `up` + UUID-with-dashes-stripped), which means the raw UUID
     * we registered with the SendSpin server will not match entries
     * returned by `players/all`. Ask the server to do the mapping
     * authoritatively via `player_queues/get_active_queue`.
     *
     * Returns `null` if the server could not resolve. Callers can
     * fall back to the raw UUID (via [getThisDevicePlayerId]).
     *
     * Note: when this device is synced to another player in a group,
     * the resolved id may be the group's queue id rather than this
     * device's own player id. That is intentional — for UI purposes
     * ("show me the active player we are participating with") the
     * queue id is usually the right entity.
     */
    suspend fun resolveThisDeviceMaPlayerId(): String? {
        val playerId = UserSettings.getPlayerId()
        return try {
            commandClient.getEffectiveQueueId(playerId)
        } catch (e: Exception) {
            null
        }
    }

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
            runCatching { resolveQueueId() }.fold(
                onSuccess = { queueId -> commandClient.playMedia(uri, queueId, mediaType, effectiveMode) },
                onFailure = { Result.failure(it) }
            )
        }
    }

    /**
     * Get the current queue items for the player.
     */
    suspend fun getQueueItems(limit: Int = 200, offset: Int = 0): Result<MaQueueState> {
        return withContext(Dispatchers.IO) {
            runCatching { resolveQueueId() }.fold(
                onSuccess = { queueId -> commandClient.getQueueItems(queueId, limit, offset) },
                onFailure = { Result.failure(it) }
            )
        }
    }

    /**
     * Clear all items from the queue.
     */
    suspend fun clearQueue(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching { resolveQueueId() }.fold(
                onSuccess = { queueId -> commandClient.clearQueue(queueId) },
                onFailure = { Result.failure(it) }
            )
        }
    }

    /**
     * Jump to and play a specific item in the queue.
     */
    suspend fun playQueueItem(queueItemId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching { resolveQueueId() }.fold(
                onSuccess = { queueId -> commandClient.playQueueItem(queueId, queueItemId) },
                onFailure = { Result.failure(it) }
            )
        }
    }

    /**
     * Remove a specific item from the queue.
     */
    suspend fun removeQueueItem(queueItemId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching { resolveQueueId() }.fold(
                onSuccess = { queueId -> commandClient.removeQueueItem(queueId, queueItemId) },
                onFailure = { Result.failure(it) }
            )
        }
    }

    /**
     * Move a queue item to a new position.
     */
    suspend fun moveQueueItem(queueItemId: String, newIndex: Int): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching { resolveQueueId() }.fold(
                onSuccess = { queueId -> commandClient.moveQueueItem(queueId, queueItemId, newIndex) },
                onFailure = { Result.failure(it) }
            )
        }
    }

    /**
     * Toggle shuffle mode for the queue.
     */
    suspend fun setQueueShuffle(enabled: Boolean): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching { resolveQueueId() }.fold(
                onSuccess = { queueId -> commandClient.setQueueShuffle(queueId, enabled) },
                onFailure = { Result.failure(it) }
            )
        }
    }

    /**
     * Set repeat mode for the queue.
     */
    suspend fun setQueueRepeat(mode: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching { resolveQueueId() }.fold(
                onSuccess = { queueId -> commandClient.setQueueRepeat(queueId, mode) },
                onFailure = { Result.failure(it) }
            )
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
    suspend fun getPlaylists(limit: Int = 25, offset: Int = 0, orderBy: String = "name"): Result<List<MaPlaylist>> {
        return withContext(Dispatchers.IO) {
            commandClient.getPlaylists(limit, offset, orderBy)
        }
    }

    /**
     * Get a single playlist by ID.
     * 
     * @param playlistId The playlist ID
     * @param providerInstanceId The provider instance ID or domain (default: "library")
     */
    suspend fun getPlaylist(playlistId: String, providerInstanceId: String = "library"): Result<MaPlaylist> {
        return withContext(Dispatchers.IO) {
            commandClient.getPlaylist(playlistId, providerInstanceId)
        }
    }

    /**
     * Get tracks in a playlist.
     * 
     * @param playlistId The playlist ID
     * @param providerInstanceId The provider instance ID or domain (default: "library")
     */
    suspend fun getPlaylistTracks(playlistId: String, providerInstanceId: String = "library"): Result<List<MaTrack>> {
        return withContext(Dispatchers.IO) {
            commandClient.getPlaylistTracks(playlistId, providerInstanceId)
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
    suspend fun getAlbums(limit: Int = 25, offset: Int = 0, orderBy: String = "name"): Result<List<MaAlbum>> {
        return withContext(Dispatchers.IO) {
            commandClient.getAlbums(limit, offset, orderBy)
        }
    }

    /**
     * Get a single album by ID.
     * 
     * @param albumId The album ID
     * @param providerInstanceId The provider instance ID or domain (default: "library")
     */
    suspend fun getAlbum(albumId: String, providerInstanceId: String = "library"): Result<MaAlbum> {
        return withContext(Dispatchers.IO) {
            commandClient.getAlbum(albumId, providerInstanceId)
        }
    }

    /**
     * Get tracks for an album.
     * 
     * @param albumId The album ID
     * @param providerInstanceId The provider instance ID or domain (default: "library")
     */
    suspend fun getAlbumTracks(albumId: String, providerInstanceId: String = "library"): Result<List<MaTrack>> {
        return withContext(Dispatchers.IO) {
            commandClient.getAlbumTracks(albumId, providerInstanceId)
        }
    }

    /**
     * Get artists from the library.
     */
    suspend fun getArtists(
        limit: Int = 25,
        offset: Int = 0,
        orderBy: String = "name",
        albumArtistsOnly: Boolean = false
    ): Result<List<MaArtist>> {
        return withContext(Dispatchers.IO) {
            commandClient.getArtists(limit, offset, orderBy, albumArtistsOnly)
        }
    }

    /**
     * Get a single artist by ID.
     * 
     * @param artistId The artist ID
     * @param providerInstanceId The provider instance ID or domain (default: "library")
     */
    suspend fun getArtist(artistId: String, providerInstanceId: String = "library"): Result<MaArtist> {
        return withContext(Dispatchers.IO) {
            commandClient.getArtist(artistId, providerInstanceId)
        }
    }

    /**
     * Get complete artist details including top tracks and discography.
     * 
     * @param artistId The artist ID
     * @param providerInstanceId The provider instance ID or domain (default: "library")
     */
    suspend fun getArtistDetails(artistId: String, providerInstanceId: String = "library"): Result<ArtistDetails> {
        return withContext(Dispatchers.IO) {
            commandClient.getArtistDetails(artistId, providerInstanceId)
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
    suspend fun getRadioStations(limit: Int = 25, offset: Int = 0, orderBy: String = "name"): Result<List<MaRadio>> {
        return withContext(Dispatchers.IO) {
            commandClient.getRadioStations(limit, offset, orderBy)
        }
    }

    /**
     * Get podcasts from the library.
     */
    suspend fun getPodcasts(limit: Int = 25, offset: Int = 0, orderBy: String = "name"): Result<List<MaPodcast>> {
        return withContext(Dispatchers.IO) {
            commandClient.getPodcasts(limit, offset, orderBy)
        }
    }

    /**
     * Get podcast episodes.
     *
     * @param podcastId The MA podcast item_id
     * @param providerInstanceId The provider instance ID (e.g., "library", "spotify")
     */
    suspend fun getPodcastEpisodes(podcastId: String, providerInstanceId: String = "library"): Result<List<MaPodcastEpisode>> {
        return withContext(Dispatchers.IO) {
            commandClient.getPodcastEpisodes(podcastId, providerInstanceId)
        }
    }

    /**
     * Get audiobooks from the library.
     */
    suspend fun getAudiobooks(limit: Int = 15, offset: Int = 0, orderBy: String = "name"): Result<List<MaAudiobook>> {
        return withContext(Dispatchers.IO) {
            commandClient.getAudiobooks(limit, offset, orderBy)
        }
    }

    /**
     * Get a single audiobook by ID.
     *
     * @param providerInstanceId The provider instance ID or domain (default: "library")
     */
    suspend fun getAudiobook(audiobookId: String, providerInstanceId: String = "library"): Result<MaAudiobook> {
        return withContext(Dispatchers.IO) {
            commandClient.getAudiobook(audiobookId, providerInstanceId)
        }
    }

    /**
     * Get in-progress audiobooks and podcast episodes.
     */
    suspend fun getInProgressItems(limit: Int = 20): Result<List<MaLibraryItem>> {
        return withContext(Dispatchers.IO) {
            commandClient.getInProgressItems(limit)
        }
    }

    /**
     * Mark a media item as fully played.
     */
    suspend fun markPlayed(itemUri: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            commandClient.markPlayed(itemUri)
        }
    }

    /**
     * Mark a media item as not played (reset progress).
     */
    suspend fun markUnplayed(itemUri: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            commandClient.markUnplayed(itemUri)
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
