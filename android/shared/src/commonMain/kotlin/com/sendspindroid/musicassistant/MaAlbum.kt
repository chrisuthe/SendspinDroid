package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType

/**
 * Represents an album from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters.
 * Subtitle rendering: "Artist Name" or "Artist Name - 2024"
 */
data class MaAlbum(
    val albumId: String,
    override val name: String,
    override val imageUri: String?,
    override val uri: String?,
    val artist: String?,          // Primary artist name
    val year: Int?,               // Release year
    val trackCount: Int?,         // Number of tracks
    val albumType: String?        // "album", "single", "ep", "compilation"
) : MaLibraryItem {
    override val id: String get() = albumId
    override val mediaType: MaMediaType = MaMediaType.ALBUM
}
