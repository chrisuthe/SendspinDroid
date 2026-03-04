package com.sendspindroid.musicassistant

/**
 * Thrown when the MA server reports that the player is not available.
 *
 * This typically happens when the device temporarily disconnected from the
 * server, causing the server to destroy the player's queue. The command
 * WebSocket may still be alive, but queue operations will fail.
 */
class PlayerUnavailableException(
    val playerId: String
) : Exception("Player $playerId is not available on the server")
