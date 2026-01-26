package com.sendspindroid.remote

import android.content.Context
import android.util.Log
import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.TransportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-level manager for remote connections via Music Assistant.
 *
 * This class provides a simplified API for establishing remote connections,
 * handling the complexity of WebRTC setup internally.
 *
 * ## Usage
 * ```kotlin
 * val remote = RemoteConnection(context)
 * remote.connect("VVPN3TLP34YMGIZDINCEKQKSIR") { transport ->
 *     // Use transport for SendSpin protocol
 * }
 * ```
 *
 * @param context Android context for WebRTC initialization
 */
class RemoteConnection(private val context: Context) {

    companion object {
        private const val TAG = "RemoteConnection"

        /**
         * Validate a Remote ID format.
         *
         * Remote IDs are 26 uppercase alphanumeric characters, generated
         * by Music Assistant for each server instance.
         *
         * @param remoteId The ID to validate
         * @return true if the format is valid
         */
        fun isValidRemoteId(remoteId: String): Boolean {
            return remoteId.length == 26 && remoteId.all { it.isLetterOrDigit() && it.isUpperCase() }
        }

        /**
         * Format a Remote ID for display (add dashes for readability).
         *
         * @param remoteId The raw 26-character ID
         * @return Formatted ID like "VVPN3-TLP34-YMGIZ-DINCE-KQKSI-R"
         */
        fun formatRemoteId(remoteId: String): String {
            if (remoteId.length != 26) return remoteId
            return remoteId.chunked(5).joinToString("-")
        }

        /**
         * Parse a potentially formatted Remote ID back to raw format.
         *
         * @param input User input (may contain dashes, spaces, lowercase)
         * @return Cleaned 26-character ID or null if invalid
         */
        fun parseRemoteId(input: String): String? {
            val cleaned = input.uppercase().filter { it.isLetterOrDigit() }
            return if (isValidRemoteId(cleaned)) cleaned else null
        }
    }

    /**
     * Connection state for remote access.
     */
    sealed class State {
        object Idle : State()
        data class Connecting(val remoteId: String) : State()
        data class Connected(val remoteId: String, val transport: SendSpinTransport) : State()
        data class Failed(val remoteId: String, val error: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var currentTransport: WebRTCTransport? = null

    /**
     * Connect to a Music Assistant server via Remote ID.
     *
     * @param remoteId The 26-character Remote ID
     * @param onTransportReady Callback when transport is ready for use
     */
    fun connect(
        remoteId: String,
        onTransportReady: ((SendSpinTransport) -> Unit)? = null
    ) {
        val cleanedId = parseRemoteId(remoteId)
        if (cleanedId == null) {
            Log.e(TAG, "Invalid Remote ID: $remoteId")
            _state.value = State.Failed(remoteId, "Invalid Remote ID format")
            return
        }

        // Disconnect any existing connection
        disconnect()

        Log.i(TAG, "Connecting to Remote ID: ${formatRemoteId(cleanedId)}")
        _state.value = State.Connecting(cleanedId)

        val transport = WebRTCTransport(context, cleanedId)
        currentTransport = transport

        transport.setListener(object : SendSpinTransport.Listener {
            override fun onConnected() {
                Log.i(TAG, "Remote connection established")
                _state.value = State.Connected(cleanedId, transport)
                onTransportReady?.invoke(transport)
            }

            override fun onMessage(text: String) {
                // Forwarded to whoever is using the transport
            }

            override fun onMessage(bytes: okio.ByteString) {
                // Forwarded to whoever is using the transport
            }

            override fun onClosing(code: Int, reason: String) {
                Log.d(TAG, "Remote connection closing: $code $reason")
            }

            override fun onClosed(code: Int, reason: String) {
                Log.i(TAG, "Remote connection closed: $code $reason")
                _state.value = State.Idle
            }

            override fun onFailure(error: Throwable, isRecoverable: Boolean) {
                Log.e(TAG, "Remote connection failed: ${error.message}")
                _state.value = State.Failed(cleanedId, error.message ?: "Connection failed")
            }
        })

        transport.connect()
    }

    /**
     * Disconnect from the current remote connection.
     */
    fun disconnect() {
        currentTransport?.destroy()
        currentTransport = null
        _state.value = State.Idle
    }

    /**
     * Get the current transport if connected.
     */
    fun getTransport(): SendSpinTransport? {
        val current = _state.value
        return if (current is State.Connected) current.transport else null
    }

    /**
     * Check if currently connected.
     */
    val isConnected: Boolean
        get() = _state.value is State.Connected

    /**
     * Clean up all resources.
     */
    fun destroy() {
        disconnect()
    }
}

/**
 * Saved remote server information.
 */
data class RemoteServerInfo(
    val remoteId: String,
    val nickname: String,
    val lastConnectedMs: Long = System.currentTimeMillis()
) {
    val formattedId: String get() = RemoteConnection.formatRemoteId(remoteId)
}
