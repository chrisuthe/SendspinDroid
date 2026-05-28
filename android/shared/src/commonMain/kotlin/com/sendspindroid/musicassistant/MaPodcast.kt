package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType

/**
 * Represents a podcast from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters.
 *
 * @param provider Provider instance ID or domain (e.g., "library", "spotify")
 *                 Used for API calls to fetch podcast episodes from the correct provider.
 */
data class MaPodcast(
    val podcastId: String,
    override val name: String,
    override val imageUri: String?,
    override val uri: String?,
    val publisher: String?,
    val totalEpisodes: Int,
    val provider: String = "library"  // Default to library for backward compatibility
) : MaLibraryItem {
    override val id: String get() = podcastId
    override val mediaType: MaMediaType = MaMediaType.PODCAST
}
