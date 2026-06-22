package com.sendspindroid.ui.navigation

import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MaBrowseFolder
import com.sendspindroid.musicassistant.SearchResults
import com.sendspindroid.musicassistant.model.MaLibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the LazyColumn/LazyRow duplicate-key crash: Compose throws
 * IllegalArgumentException ("Key ... was already used") if a lazy list ever sees
 * two items with the same key. The reported field crash was key "ALBUM_1014".
 */
class MaLibraryItemKeysTest {

    private fun album(id: String, provider: String = "library") =
        MaAlbum(
            albumId = id,
            name = "Album $id",
            imageUri = null,
            uri = null,
            artist = "Artist",
            year = 2024,
            trackCount = 10,
            albumType = "album",
            provider = provider,
        )

    private fun artist(id: String) =
        MaArtist(artistId = id, name = "Artist $id", imageUri = null, uri = null)

    private fun folder(id: String, path: String) =
        MaBrowseFolder(folderId = id, name = "Folder", imageUri = null, uri = null, path = path)

    @Test
    fun `key matches the format that crashed in the field`() {
        // The crash report showed key "ALBUM_1014"; the key fn must reproduce it.
        assertEquals("ALBUM_1014", maLibraryItemKey(album("1014")))
    }

    @Test
    fun `same album id from different providers collapses to one item`() {
        // Music Assistant surfaces the same album under multiple providers: same
        // albumId, different provider -> identical key -> would crash the UI.
        val items: List<MaLibraryItem> =
            listOf(album("1014", "library"), album("1014", "spotify"))
        val deduped = items.distinctByItemKey()
        assertEquals(1, deduped.size)
        assertEquals("library", (deduped[0] as MaAlbum).provider) // first wins
    }

    @Test
    fun `de-dup preserves order and keeps first occurrence`() {
        val items = listOf(album("1"), album("2"), album("1"), album("3"))
        val deduped = items.distinctByItemKey()
        assertEquals(listOf("1", "2", "3"), deduped.map { it.id })
    }

    @Test
    fun `same id across different media types does not collide`() {
        // ALBUM_1 and ARTIST_1 are distinct keys; both must survive.
        val items: List<MaLibraryItem> = listOf(album("1"), artist("1"))
        assertEquals(2, items.distinctByItemKey().size)
    }

    @Test
    fun `de-dup output never contains a duplicate key`() {
        val items = listOf(album("1"), album("1"), album("2"), album("2"), album("2"))
        val keys = items.distinctByItemKey().map { maLibraryItemKey(it) }
        assertTrue(keys.size == keys.toSet().size)
    }

    @Test
    fun `browse folder is keyed by path, not id`() {
        // The browse UI keys and navigates by path; the key must match so de-dup
        // and rendering agree. Same id + different path must NOT collide.
        assertEquals("folder_/library/albums", maLibraryItemKey(folder("7", "/library/albums")))
        val items = listOf(folder("7", "/a"), folder("7", "/b"))
        assertEquals(2, items.distinctByItemKey().size)
    }

    @Test
    fun `search results de-dup each type list independently`() {
        val results = SearchResults(
            artists = listOf(artist("1"), artist("1")),
            albums = listOf(album("9"), album("9"), album("10")),
        )
        val deduped = results.distinctByItemKeys()
        assertEquals(1, deduped.artists.size)
        assertEquals(listOf("9", "10"), deduped.albums.map { it.id })
    }
}
