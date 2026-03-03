package com.sendspindroid.musicassistant

/**
 * Represents a chapter within an audiobook from Music Assistant.
 *
 * Chapters are stored in the audiobook's metadata and provide
 * navigation points for seeking within the audiobook.
 */
data class MaAudiobookChapter(
    val position: Int,        // Sort position / chapter number
    val name: String,         // Chapter name
    val start: Double,        // Start position in seconds
    val end: Double? = null   // End position in seconds (null if unknown)
) {
    /** Chapter duration in seconds, or 0 if end is unknown */
    val duration: Double get() = if (end != null) end - start else 0.0
}
