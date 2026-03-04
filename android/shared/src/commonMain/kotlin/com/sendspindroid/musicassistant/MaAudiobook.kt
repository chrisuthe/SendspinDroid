package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType

/**
 * Represents an audiobook from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters.
 * Tracks playback progress (fully played, resume position).
 */
data class MaAudiobook(
    val audiobookId: String,
    override val name: String,
    override val imageUri: String?,
    override val uri: String?,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val publisher: String? = null,
    val duration: Long = 0,           // Total duration in seconds
    val fullyPlayed: Boolean? = null,
    val resumePositionMs: Long? = null,
    val chapters: List<MaAudiobookChapter> = emptyList()
) : MaLibraryItem {
    override val id: String get() = audiobookId
    override val mediaType: MaMediaType = MaMediaType.AUDIOBOOK

    /** Primary author for subtitle display */
    val primaryAuthor: String? get() = authors.firstOrNull()
}
