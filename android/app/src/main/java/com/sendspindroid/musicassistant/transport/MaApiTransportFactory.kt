package com.sendspindroid.musicassistant.transport

import android.util.Log
import com.sendspindroid.UserSettings.ConnectionMode
import org.webrtc.DataChannel

/**
 * Factory for creating the appropriate [MaApiTransport] for a given connection mode.
 *
 * | Connection Mode | Transport |
 * |-----------------|-----------|
 * | LOCAL | [MaWebSocketTransport] |
 * | PROXY | [MaWebSocketTransport] |
 * | REMOTE | [MaDataChannelTransport] |
 */
object MaApiTransportFactory {

    private const val TAG = "MaApiTransportFactory"

    /**
     * Create the appropriate transport for the active connection mode.
     *
     * @param connectionMode Active connection mode
     * @param apiUrl WebSocket URL for LOCAL/PROXY (ignored for REMOTE)
     * @param maApiDataChannel WebRTC DataChannel for REMOTE mode (null for LOCAL/PROXY)
     * @return The transport, or null if the mode can't be supported
     */
    fun create(
        connectionMode: ConnectionMode,
        apiUrl: String?,
        maApiDataChannel: DataChannel? = null
    ): MaApiTransport? {
        return when (connectionMode) {
            ConnectionMode.LOCAL, ConnectionMode.PROXY -> {
                if (apiUrl == null) {
                    Log.w(TAG, "No API URL for $connectionMode mode")
                    return null
                }
                Log.d(TAG, "Creating WebSocket transport for $connectionMode: $apiUrl")
                MaWebSocketTransport(apiUrl)
            }
            ConnectionMode.REMOTE -> {
                if (maApiDataChannel != null && maApiDataChannel.state() == DataChannel.State.OPEN) {
                    Log.d(TAG, "Creating DataChannel transport for REMOTE mode")
                    MaDataChannelTransport(maApiDataChannel)
                } else {
                    // Fallback: try WebSocket if a URL is available (e.g., local network accessible)
                    if (apiUrl != null && !apiUrl.startsWith("webrtc://")) {
                        Log.d(TAG, "REMOTE mode fallback: using WebSocket transport at $apiUrl")
                        MaWebSocketTransport(apiUrl)
                    } else {
                        Log.w(TAG, "No DataChannel or fallback URL available for REMOTE mode")
                        null
                    }
                }
            }
        }
    }
}
