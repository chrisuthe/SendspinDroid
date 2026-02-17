package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType

/**
 * Represents an artist from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters.
 */
data class MaArtist(
    val artistId: String,
    override val name: String,
    override val imageUri: String?,
    override val uri: String?
) : MaLibraryItem {
    override val id: String get() = artistId
    override val mediaType: MaMediaType = MaMediaType.ARTIST
}
