package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType

/**
 * Represents a podcast from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters.
 */
data class MaPodcast(
    val podcastId: String,
    override val name: String,
    override val imageUri: String?,
    override val uri: String?,
    val publisher: String?,
    val totalEpisodes: Int
) : MaLibraryItem {
    override val id: String get() = podcastId
    override val mediaType: MaMediaType = MaMediaType.PODCAST
}
