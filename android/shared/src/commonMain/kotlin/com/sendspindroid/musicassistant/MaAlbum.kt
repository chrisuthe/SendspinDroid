package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType

/**
 * Represents an album from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters.
 * Subtitle rendering: "Artist Name" or "Artist Name - 2024"
 *
 * @param provider Provider instance ID or domain (e.g., "library", "spotify")
 *                 Used for API calls to fetch album tracks from the correct provider.
 */
data class MaAlbum(
    val albumId: String,
    override val name: String,
    override val imageUri: String?,
    override val uri: String?,
    val artist: String?,          // Primary artist name
    val year: Int?,               // Release year
    val trackCount: Int?,         // Number of tracks
    val albumType: String?,       // "album", "single", "ep", "compilation"
    val provider: String = "library"  // Default to library for backward compatibility
) : MaLibraryItem {
    override val id: String get() = albumId
    override val mediaType: MaMediaType = MaMediaType.ALBUM
}
