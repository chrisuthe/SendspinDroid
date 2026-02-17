package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType

/**
 * Represents a browseable folder from Music Assistant.
 *
 * Used in the Browse tab to navigate provider content hierarchies.
 * The [path] field is used as the parameter to the next browse() call
 * when the user taps this folder.
 */
data class MaBrowseFolder(
    val folderId: String,
    override val name: String,
    override val imageUri: String?,
    override val uri: String?,
    val path: String,
    val isPlayable: Boolean = false
) : MaLibraryItem {
    override val id: String get() = folderId
    override val mediaType: MaMediaType = MaMediaType.FOLDER
}
