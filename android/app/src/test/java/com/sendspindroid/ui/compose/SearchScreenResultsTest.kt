package com.sendspindroid.ui.compose

import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.SearchResults
import com.sendspindroid.musicassistant.model.MaMediaType
import com.sendspindroid.ui.navigation.search.SearchViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that SearchResults correctly groups items by type and that
 * SearchState correctly reports empty/no-results states.
 *
 * Note: SearchScreenContent is private in SearchScreen.kt, so we test
 * the SearchResults data model and SearchState logic directly. The
 * composable rendering of section headers uses these results to decide
 * which sections to show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchScreenResultsTest {

    @Test
    fun searchResults_groupedByType_hasCorrectCounts() {
        val results = SearchResults(
            artists = listOf(
                MaArtist(artistId = "a1", name = "Queen", imageUri = null, uri = null),
                MaArtist(artistId = "a2", name = "Queensryche", imageUri = null, uri = null)
            ),
            albums = listOf(
                MaAlbum(
                    albumId = "al1", name = "A Night at the Opera",
                    imageUri = null, uri = null, artist = "Queen",
                    year = 1975, trackCount = 12, albumType = "album"
                )
            ),
            tracks = listOf(
                MaTrack(
                    itemId = "t1", name = "Bohemian Rhapsody",
                    artist = "Queen", album = "A Night at the Opera",
                    imageUri = null, uri = null, duration = 355
                ),
                MaTrack(
                    itemId = "t2", name = "Somebody to Love",
                    artist = "Queen", album = "A Day at the Races",
                    imageUri = null, uri = null, duration = 296
                ),
                MaTrack(
                    itemId = "t3", name = "We Will Rock You",
                    artist = "Queen", album = "News of the World",
                    imageUri = null, uri = null, duration = 122
                )
            )
        )

        assertEquals(2, results.artists.size)
        assertEquals(1, results.albums.size)
        assertEquals(3, results.tracks.size)
        assertEquals(0, results.playlists.size)
        assertEquals(0, results.radios.size)
        assertEquals(6, results.totalCount())
        assertFalse(results.isEmpty())
    }

    @Test
    fun searchResults_empty_isEmptyReturnsTrue() {
        val results = SearchResults()
        assertTrue(results.isEmpty())
        assertEquals(0, results.totalCount())
    }

    @Test
    fun searchState_shortQuery_showsEmptyState() {
        val state = SearchViewModel.SearchState(query = "a")
        assertTrue(state.showEmptyState)
        assertFalse(state.hasNoResults)
    }

    @Test
    fun searchState_longQuery_noResults_showsNoResults() {
        val state = SearchViewModel.SearchState(
            query = "xyz",
            results = SearchResults()
        )
        assertTrue(state.hasNoResults)
        assertFalse(state.showEmptyState)
    }

    @Test
    fun searchState_longQuery_withResults_showsNeither() {
        val state = SearchViewModel.SearchState(
            query = "queen",
            results = SearchResults(
                artists = listOf(
                    MaArtist(artistId = "a1", name = "Queen", imageUri = null, uri = null)
                )
            )
        )
        assertFalse(state.hasNoResults)
        assertFalse(state.showEmptyState)
    }

    @Test
    fun searchState_loading_showsNeitherState() {
        val state = SearchViewModel.SearchState(
            query = "test",
            isLoading = true
        )
        assertFalse(state.hasNoResults)
        assertFalse(state.showEmptyState)
    }

    @Test
    fun searchResults_onlyArtists_otherSectionsEmpty() {
        val results = SearchResults(
            artists = listOf(
                MaArtist(artistId = "a1", name = "Queen", imageUri = null, uri = null)
            )
        )

        assertFalse(results.isEmpty())
        assertEquals(1, results.artists.size)
        assertTrue(results.albums.isEmpty())
        assertTrue(results.tracks.isEmpty())
        assertTrue(results.playlists.isEmpty())
    }

    @Test
    fun searchResults_mediaType_isCorrectForEachType() {
        val artist = MaArtist(artistId = "a1", name = "Test", imageUri = null, uri = null)
        val album = MaAlbum(
            albumId = "al1", name = "Test Album",
            imageUri = null, uri = null, artist = null,
            year = null, trackCount = null, albumType = null
        )
        val track = MaTrack(
            itemId = "t1", name = "Test Track",
            artist = null, album = null,
            imageUri = null, uri = null
        )

        assertEquals(MaMediaType.ARTIST, artist.mediaType)
        assertEquals(MaMediaType.ALBUM, album.mediaType)
        assertEquals(MaMediaType.TRACK, track.mediaType)
    }
}
