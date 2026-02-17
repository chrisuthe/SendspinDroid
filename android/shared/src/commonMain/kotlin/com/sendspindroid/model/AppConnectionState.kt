package com.sendspindroid.model

sealed class AppConnectionState {
    object ServerList : AppConnectionState()
    data class Connecting(val serverName: String, val serverAddress: String) : AppConnectionState()
    data class Connected(val serverName: String, val serverAddress: String) : AppConnectionState()
    data class Reconnecting(
        val serverName: String,
        val serverAddress: String,
        val attempt: Int,
        val nextRetrySeconds: Int
    ) : AppConnectionState()
    data class Error(val message: String, val canRetry: Boolean = true) : AppConnectionState()
}
