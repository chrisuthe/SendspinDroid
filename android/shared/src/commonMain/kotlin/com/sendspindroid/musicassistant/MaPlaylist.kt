package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType

/**
 * Represents a playlist from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters.
 */
data class MaPlaylist(
    val playlistId: String,
    override val name: String,
    override val imageUri: String?,
    val trackCount: Int,
    val owner: String?,
    override val uri: String?
) : MaLibraryItem {
    override val id: String get() = playlistId
    override val mediaType: MaMediaType = MaMediaType.PLAYLIST
}
