package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType

/**
 * Represents a track from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters and generic lists.
 */
data class MaTrack(
    val itemId: String,
    override val name: String,
    val artist: String?,
    val album: String?,
    override val imageUri: String?,
    override val uri: String?,
    val duration: Long? = null,
    // Album reference fields for grouping
    val albumId: String? = null,
    val albumType: String? = null  // "album", "single", "ep", "compilation"
) : MaLibraryItem {
    override val id: String get() = itemId
    override val mediaType: MaMediaType = MaMediaType.TRACK
}
