package com.sendspindroid.musicassistant.model

/**
 * Common interface for all Music Assistant library items.
 *
 * This interface provides a unified contract for different media types
 * (tracks, albums, artists, playlists, radio), enabling:
 * - Unified adapters that can display any library item type
 * - Generic search results and favorites
 * - Consistent item handling across the app
 *
 * ## Design Decision
 * Using an interface + sealed hierarchy allows type-safe handling while
 * maintaining the ability to add new item types without modifying existing code.
 */
interface MaLibraryItem {
    /** Unique identifier from Music Assistant */
    val id: String

    /** Display name of the item */
    val name: String

    /** Image URL for album art, cover, or avatar (null if no image) */
    val imageUri: String?

    /** Music Assistant URI for playback (e.g., "library://track/123") */
    val uri: String?

    /** Type discriminator for conditional rendering */
    val mediaType: MaMediaType
}

/**
 * Enumeration of supported Music Assistant media types.
 *
 * Used for:
 * - Conditional UI rendering (e.g., showing artist vs track count)
 * - API endpoint selection (different endpoints per type)
 * - Type-safe when-expressions in adapters
 */
enum class MaMediaType {
    TRACK,
    ALBUM,
    ARTIST,
    PLAYLIST,
    RADIO,
    PODCAST,
    FOLDER
}
