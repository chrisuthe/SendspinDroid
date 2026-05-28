package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType

/**
 * Represents a playlist from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters.
 *
 * @param provider Provider instance ID or domain (e.g., "library", "spotify")
 *                 Used for API calls to fetch playlist tracks from the correct provider.
 */
data class MaPlaylist(
    val playlistId: String,
    override val name: String,
    override val imageUri: String?,
    val trackCount: Int,
    val owner: String?,
    override val uri: String?,
    val provider: String = "library"  // Default to library for backward compatibility
) : MaLibraryItem {
    override val id: String get() = playlistId
    override val mediaType: MaMediaType = MaMediaType.PLAYLIST
}
