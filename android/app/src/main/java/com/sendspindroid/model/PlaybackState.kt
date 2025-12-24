package com.sendspindroid.model

import android.os.SystemClock

/**
 * Represents the current playback state, mirrored from the server.
 *
 * This follows the pattern from the Python CLI's AppState dataclass,
 * providing a clean separation between protocol state and UI state.
 *
 * ## State Sources
 * - `group/update` messages: groupId, groupName, playbackState
 * - `server/state` messages: title, artist, album, artworkUrl, duration, position
 * - `server/command` messages: volume, muted (player-specific)
 *
 * ## Progress Interpolation
 * The [positionUpdatedAt] field enables smooth progress bar updates between
 * server position updates. Use [interpolatedPositionMs] to get the current
 * interpolated position when playback is active.
 *
 * @see PlaybackStateType for playback state values
 */
data class PlaybackState(
    // Group info (from group/update)
    val groupId: String? = null,
    val groupName: String? = null,

    // Playback state (from group/update)
    val playbackState: PlaybackStateType = PlaybackStateType.IDLE,

    // Track metadata (from server/state with metadata)
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,

    // Duration and position in milliseconds
    val durationMs: Long = 0,
    val positionMs: Long = 0,

    // Timestamp (SystemClock.elapsedRealtime) when position was last updated
    // Used for progress interpolation like Python CLI does
    val positionUpdatedAt: Long = 0,

    // Volume state (from server/command player commands)
    val volume: Int = 100,
    val muted: Boolean = false
) {
    /**
     * Returns the display title, falling back to "Unknown Track" if null.
     */
    val displayTitle: String
        get() = title ?: "Unknown Track"

    /**
     * Returns the display artist, falling back to "Unknown Artist" if null.
     */
    val displayArtist: String
        get() = artist ?: "Unknown Artist"

    /**
     * Returns a formatted string for display: "Artist - Title" or just "Title".
     */
    val displayString: String
        get() = when {
            artist != null && title != null -> "$artist - $title"
            title != null -> title
            else -> "Unknown Track"
        }

    /**
     * Returns whether there is track metadata to display.
     */
    val hasMetadata: Boolean
        get() = title != null || artist != null || album != null

    /**
     * Returns the interpolated position in milliseconds.
     *
     * When playback is active, this adds elapsed time since the last position
     * update to provide smooth progress bar updates between server ticks.
     * This follows the pattern from Python CLI's ui.py progress interpolation.
     *
     * @return Interpolated position, capped at duration
     */
    val interpolatedPositionMs: Long
        get() {
            if (playbackState != PlaybackStateType.PLAYING || positionUpdatedAt == 0L) {
                return positionMs
            }
            val elapsedMs = SystemClock.elapsedRealtime() - positionUpdatedAt
            return minOf(durationMs, positionMs + elapsedMs)
        }

    /**
     * Returns a copy with updated metadata from a server/state message.
     *
     * Preserves existing values when new values are null (partial updates).
     * This matches the C# reference implementation which uses null-coalescing:
     * `Title = meta.Title ?? existing.Title`
     *
     * The server may send partial updates (e.g., just artwork_url) and we
     * don't want to lose the title/artist when that happens.
     */
    fun withMetadata(
        title: String?,
        artist: String?,
        album: String?,
        artworkUrl: String?,
        durationMs: Long,
        positionMs: Long
    ): PlaybackState = copy(
        title = title ?: this.title,
        artist = artist ?: this.artist,
        album = album ?: this.album,
        artworkUrl = artworkUrl ?: this.artworkUrl,
        durationMs = if (durationMs > 0) durationMs else this.durationMs,
        positionMs = positionMs,
        positionUpdatedAt = SystemClock.elapsedRealtime()
    )

    /**
     * Returns a copy with cleared metadata (for group switches).
     */
    fun withClearedMetadata(): PlaybackState = copy(
        title = null,
        artist = null,
        album = null,
        artworkUrl = null,
        durationMs = 0,
        positionMs = 0,
        positionUpdatedAt = 0
    )

    /**
     * Returns a copy with updated group info from a group/update message.
     */
    fun withGroupUpdate(
        groupId: String?,
        groupName: String?,
        playbackState: PlaybackStateType
    ): PlaybackState = copy(
        groupId = groupId,
        groupName = groupName,
        playbackState = playbackState
    )
}

/**
 * Playback state types matching the SendSpin protocol.
 */
enum class PlaybackStateType {
    IDLE,
    PLAYING,
    PAUSED,
    BUFFERING,
    STOPPED;

    companion object {
        /**
         * Parses a playback state string from the protocol.
         */
        fun fromString(value: String): PlaybackStateType = when (value.lowercase()) {
            "playing" -> PLAYING
            "paused" -> PAUSED
            "buffering" -> BUFFERING
            "stopped" -> STOPPED
            else -> IDLE
        }
    }
}
