package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType

/**
 * Represents an artist from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters.
 *
 * @param provider Provider instance ID or domain (e.g., "library", "spotify")
 *                 Used for API calls to fetch artist albums from the correct provider.
 */
data class MaArtist(
    val artistId: String,
    override val name: String,
    override val imageUri: String?,
    override val uri: String?,
    val provider: String = "library"  // Default to library for backward compatibility
) : MaLibraryItem {
    override val id: String get() = artistId
    override val mediaType: MaMediaType = MaMediaType.ARTIST
}
