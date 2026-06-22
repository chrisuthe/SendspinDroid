package com.sendspindroid.ui.navigation

import com.sendspindroid.musicassistant.MaBrowseFolder
import com.sendspindroid.musicassistant.SearchResults
import com.sendspindroid.musicassistant.model.MaLibraryItem

/**
 * Stable Compose list key for a Music Assistant library item.
 *
 * This is the single source of truth for the key used by every LazyColumn/LazyRow
 * that renders [MaLibraryItem]s, and for [distinctByItemKey]. They MUST share this
 * function: Compose throws IllegalArgumentException if a lazy list ever sees two
 * items with the same key, so the key used for de-duplication can never be allowed
 * to drift from the key used for rendering.
 *
 * Browse folders are keyed by [MaBrowseFolder.path] (the value the browse UI keys
 * on and navigates with), not by their id.
 */
fun maLibraryItemKey(item: MaLibraryItem): String = when (item) {
    is MaBrowseFolder -> "folder_${item.path}"
    else -> "${item.mediaType}_${item.id}"
}

/**
 * Removes items that would collide on [maLibraryItemKey], keeping the first occurrence.
 *
 * Music Assistant can surface the same album/artist under multiple providers (same
 * id, different provider), and offset-based paging can overlap pages -- either way a
 * list ends up with two items sharing a key. Rendering both in a LazyColumn/LazyRow
 * crashes the UI thread, so lists are de-duplicated here before they reach the screen.
 */
fun <T : MaLibraryItem> List<T>.distinctByItemKey(): List<T> = distinctBy(::maLibraryItemKey)

/**
 * De-duplicates every per-type list in a [SearchResults] by [maLibraryItemKey].
 *
 * The search screen renders each type in its own LazyColumn section, so a duplicate
 * id within one type (e.g. the same album from two providers) would crash that
 * section. Each list holds a single media type, so per-list de-dup is sufficient.
 */
fun SearchResults.distinctByItemKeys(): SearchResults = copy(
    artists = artists.distinctByItemKey(),
    albums = albums.distinctByItemKey(),
    tracks = tracks.distinctByItemKey(),
    playlists = playlists.distinctByItemKey(),
    radios = radios.distinctByItemKey(),
    podcasts = podcasts.distinctByItemKey(),
    audiobooks = audiobooks.distinctByItemKey(),
)
