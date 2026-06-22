package com.sendspindroid.ui.navigation

import com.sendspindroid.musicassistant.model.MaLibraryItem

/**
 * Stable Compose list key for a Music Assistant library item.
 *
 * This is the single source of truth for the key used by every LazyColumn/LazyRow
 * that renders [MaLibraryItem]s, and for [distinctByItemKey]. They MUST share this
 * function: Compose throws IllegalArgumentException if a lazy list ever sees two
 * items with the same key, so the key used for de-duplication can never be allowed
 * to drift from the key used for rendering.
 */
fun maLibraryItemKey(item: MaLibraryItem): String = "${item.mediaType}_${item.id}"

/**
 * Removes items that would collide on [maLibraryItemKey], keeping the first occurrence.
 *
 * Music Assistant can surface the same album/artist under multiple providers (same
 * id, different provider), and offset-based paging can overlap pages -- either way a
 * list ends up with two items sharing a key. Rendering both in a LazyColumn/LazyRow
 * crashes the UI thread, so lists are de-duplicated here before they reach the screen.
 */
fun <T : MaLibraryItem> List<T>.distinctByItemKey(): List<T> = distinctBy(::maLibraryItemKey)
