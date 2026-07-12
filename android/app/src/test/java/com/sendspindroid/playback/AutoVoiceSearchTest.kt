package com.sendspindroid.playback

import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.SearchResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AutoVoiceSearch], the Android Auto voice search decision logic.
 *
 * Voice search is part of the surface Google's automated Auto quality review
 * exercises. The invariant mirrors AutoBrowseTree's: a voice request must
 * never end in a silent no-op - it either picks something to play or produces
 * an actionable user-facing message.
 */
class AutoVoiceSearchTest {

    private fun track(name: String, uri: String?) = MaTrack(
        itemId = "track-$name",
        name = name,
        artist = "Artist",
        album = "Album",
        imageUri = null,
        uri = uri,
    )

    private fun playlist(name: String) = MaPlaylist(
        playlistId = "playlist-$name",
        name = name,
        imageUri = null,
        trackCount = 10,
        owner = null,
        uri = null,
    )

    private fun album(name: String) = MaAlbum(
        albumId = "album-$name",
        name = name,
        imageUri = null,
        uri = null,
        artist = "Artist",
        year = 2020,
        trackCount = 12,
        albumType = "album",
    )

    // ========== Pick ordering ==========

    @Test
    fun `prefers first track with a uri`() {
        val results = SearchResults(
            tracks = listOf(track("Song A", uri = "library://track/1")),
            playlists = listOf(playlist("Mix")),
            albums = listOf(album("LP")),
        )

        val pick = AutoVoiceSearch.pickFromResults(results)

        assertEquals(
            AutoVoiceSearch.Pick.Track(uri = "library://track/1", name = "Song A"),
            pick
        )
    }

    @Test
    fun `skips tracks without uri and picks the next playable one`() {
        val results = SearchResults(
            tracks = listOf(
                track("Broken", uri = null),
                track("Playable", uri = "library://track/2"),
            ),
        )

        val pick = AutoVoiceSearch.pickFromResults(results)

        assertEquals(
            AutoVoiceSearch.Pick.Track(uri = "library://track/2", name = "Playable"),
            pick
        )
    }

    @Test
    fun `falls back to playlist when no track is playable`() {
        val results = SearchResults(
            tracks = listOf(track("Broken", uri = null)),
            playlists = listOf(playlist("Mix")),
            albums = listOf(album("LP")),
        )

        val pick = AutoVoiceSearch.pickFromResults(results)

        assertEquals(
            AutoVoiceSearch.Pick.Playlist(playlistId = "playlist-Mix", name = "Mix"),
            pick
        )
    }

    @Test
    fun `falls back to album when no track or playlist matches`() {
        val results = SearchResults(albums = listOf(album("LP")))

        val pick = AutoVoiceSearch.pickFromResults(results)

        assertEquals(
            AutoVoiceSearch.Pick.Album(albumId = "album-LP", name = "LP"),
            pick
        )
    }

    @Test
    fun `empty results yield NoResults`() {
        assertEquals(AutoVoiceSearch.Pick.NoResults, AutoVoiceSearch.pickFromResults(SearchResults()))
    }

    @Test
    fun `null results yield NoResults`() {
        assertEquals(AutoVoiceSearch.Pick.NoResults, AutoVoiceSearch.pickFromResults(null))
    }

    // ========== Feedback messages: every dead end has a user-facing message ==========

    @Test
    fun `disconnected message tells the user how to connect`() {
        val message = AutoVoiceSearch.unavailableMessage(isConnectedToServer = false)

        assertEquals("No server connected. Open SendSpin Player on your phone to connect.", message)
    }

    @Test
    fun `connected without MA message explains search is unavailable`() {
        val message = AutoVoiceSearch.unavailableMessage(isConnectedToServer = true)

        assertEquals("Search is not available on this server", message)
    }

    @Test
    fun `no results message includes the query`() {
        val message = AutoVoiceSearch.noResultsMessage("obscure band")

        assertTrue("Message should include the query", message.contains("obscure band"))
    }

    @Test
    fun `all failure messages are non-blank`() {
        listOf(
            AutoVoiceSearch.unavailableMessage(true),
            AutoVoiceSearch.unavailableMessage(false),
            AutoVoiceSearch.noResultsMessage("x"),
            AutoVoiceSearch.noRecentTracksMessage(),
            AutoVoiceSearch.searchFailedMessage(),
        ).forEach { assertTrue("Feedback message must not be blank", it.isNotBlank()) }
    }
}
