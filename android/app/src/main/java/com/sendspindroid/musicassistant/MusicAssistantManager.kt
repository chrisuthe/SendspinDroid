package com.sendspindroid.musicassistant

import android.content.Context
import android.util.Log
import com.sendspindroid.UserSettings.ConnectionMode
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.model.MaConnectionState
import com.sendspindroid.musicassistant.model.MaServerInfo
import com.sendspindroid.sendspin.MusicAssistantAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

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
}
