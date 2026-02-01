package com.sendspindroid.ui.server

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.network.ConnectionSelector
import com.sendspindroid.network.NetworkEvaluator
import com.sendspindroid.network.NetworkState
import com.sendspindroid.playback.PlaybackService

/**
 * Helper class for connecting to unified servers.
 *
 * Encapsulates the logic of selecting the appropriate connection method
 * based on network state and sending the correct command to PlaybackService.
 *
 * ## Usage
 * ```kotlin
 * val connector = UnifiedServerConnector(context) { method ->
 *     onConnectionMethodSelected(method)
 * }
 *
 * // Connect using auto-selection
 * connector.connect(server, mediaController)
 *
 * // Or connect using a specific method
 * connector.connectLocal(server, mediaController)
 * ```
 */
class UnifiedServerConnector(
    context: Context,
    private val onConnectionStarted: ((ConnectionSelector.SelectedConnection) -> Unit)? = null
) {
    companion object {
        private const val TAG = "UnifiedServerConnector"
    }

    private val networkEvaluator = NetworkEvaluator(context)

    init {
        // Initialize network state
        networkEvaluator.evaluateCurrentNetwork()
    }

    /**
     * Gets the current network state.
     */
    val networkState: NetworkState
        get() = networkEvaluator.networkState.value

    /**
     * Connect to a unified server using auto-selection based on network type.
     *
     * @param server The unified server to connect to
     * @param controller MediaController for sending commands
     * @return The selected connection method, or null if no method available
     */
    fun connect(
        server: UnifiedServer,
        controller: MediaController
    ): ConnectionSelector.SelectedConnection? {
        // Get current network state
        networkEvaluator.evaluateCurrentNetwork()
        val state = networkEvaluator.networkState.value

        // Select best connection method
        val selected = ConnectionSelector.selectConnection(server, state)
        if (selected == null) {
            Log.w(TAG, "No connection method available for ${server.name}")
            return null
        }

        Log.d(TAG, "Auto-selected ${ConnectionSelector.getConnectionDescription(selected)} for ${server.name}")

        // Execute connection
        executeConnection(selected, controller)
        onConnectionStarted?.invoke(selected)

        // Update last connected timestamp
        UnifiedServerRepository.updateLastConnected(server.id)

        return selected
    }

    /**
     * Connect using local connection method.
     */
    fun connectLocal(
        server: UnifiedServer,
        controller: MediaController
    ): Boolean {
        val local = server.local ?: return false

        val selected = ConnectionSelector.SelectedConnection.Local(local.address, local.path)
        executeConnection(selected, controller)
        onConnectionStarted?.invoke(selected)

        UnifiedServerRepository.updateLastConnected(server.id)
        return true
    }

    /**
     * Connect using remote access method.
     */
    fun connectRemote(
        server: UnifiedServer,
        controller: MediaController
    ): Boolean {
        val remote = server.remote ?: return false

        val selected = ConnectionSelector.SelectedConnection.Remote(remote.remoteId)
        executeConnection(selected, controller)
        onConnectionStarted?.invoke(selected)

        UnifiedServerRepository.updateLastConnected(server.id)
        return true
    }

    /**
     * Connect using proxy method.
     */
    fun connectProxy(
        server: UnifiedServer,
        controller: MediaController
    ): Boolean {
        val proxy = server.proxy ?: return false

        val selected = ConnectionSelector.SelectedConnection.Proxy(proxy.url, proxy.authToken)
        executeConnection(selected, controller)
        onConnectionStarted?.invoke(selected)

        UnifiedServerRepository.updateLastConnected(server.id)
        return true
    }

    /**
     * Sends the appropriate command to PlaybackService based on connection type.
     */
    private fun executeConnection(
        selected: ConnectionSelector.SelectedConnection,
        controller: MediaController
    ) {
        when (selected) {
            is ConnectionSelector.SelectedConnection.Local -> {
                val args = Bundle().apply {
                    putString(PlaybackService.ARG_SERVER_ADDRESS, selected.address)
                    putString(PlaybackService.ARG_SERVER_PATH, selected.path)
                }
                val command = SessionCommand(PlaybackService.COMMAND_CONNECT, Bundle.EMPTY)
                controller.sendCustomCommand(command, args)
                Log.d(TAG, "Sent local connect: ${selected.address}")
            }

            is ConnectionSelector.SelectedConnection.Remote -> {
                val args = Bundle().apply {
                    putString(PlaybackService.ARG_REMOTE_ID, selected.remoteId)
                }
                val command = SessionCommand(PlaybackService.COMMAND_CONNECT_REMOTE, Bundle.EMPTY)
                controller.sendCustomCommand(command, args)
                Log.d(TAG, "Sent remote connect: ${selected.remoteId}")
            }

            is ConnectionSelector.SelectedConnection.Proxy -> {
                val args = Bundle().apply {
                    putString(PlaybackService.ARG_PROXY_URL, selected.url)
                    putString(PlaybackService.ARG_AUTH_TOKEN, selected.authToken)
                }
                val command = SessionCommand(PlaybackService.COMMAND_CONNECT_PROXY, Bundle.EMPTY)
                controller.sendCustomCommand(command, args)
                Log.d(TAG, "Sent proxy connect: ${selected.url}")
            }
        }
    }

    /**
     * Refresh the network state evaluation.
     */
    fun refreshNetworkState() {
        networkEvaluator.evaluateCurrentNetwork()
    }
}
