package com.sendspindroid.musicassistant.model

/**
 * Information about a connected Music Assistant server.
 *
 * @property serverId The MA server's unique identifier
 * @property serverVersion The MA server version string
 * @property apiUrl The WebSocket URL used to connect
 * @property maServerId The MA server's own internal ID (if reported)
 */
data class MaServerInfo(
    val serverId: String,
    val serverVersion: String,
    val apiUrl: String,
    val maServerId: String? = null
)
