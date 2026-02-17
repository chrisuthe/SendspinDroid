package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType

/**
 * Represents a podcast episode from Music Assistant.
 *
 * Implements MaLibraryItem for display in the podcast detail screen.
 * Tracks playback status (fully played, resume position).
 */
data class MaPodcastEpisode(
    val episodeId: String,
    override val name: String,
    override val imageUri: String?,
    override val uri: String?,
    val position: Int,             // Episode number
    val duration: Long,            // Duration in seconds
    val fullyPlayed: Boolean = false,
    val resumePositionMs: Long = 0 // Resume position in milliseconds
) : MaLibraryItem {
    override val id: String get() = episodeId
    override val mediaType: MaMediaType = MaMediaType.PODCAST
}
