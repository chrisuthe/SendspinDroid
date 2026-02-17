package com.sendspindroid.ui.navigation.home

import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.model.MaMediaType
import org.junit.Assert.*
import org.junit.Test

class LibraryItemGrouperTest {

    private fun track(
        id: String = "t1",
        name: String = "Track",
        artist: String? = "Artist",
        album: String? = "Album",
        albumId: String? = null,
        albumType: String? = null,
        imageUri: String? = null
    ) = MaTrack(
        itemId = id,
        name = name,
        artist = artist,
        album = album,
        imageUri = imageUri,
        uri = "library://track/$id",
        albumId = albumId,
        albumType = albumType
    )

    private fun album(
        id: String = "a1",
        name: String = "Album",
        artist: String? = "Artist",
        albumType: String? = "album"
    ) = MaAlbum(
        albumId = id,
        name = name,
        imageUri = null,
        uri = "library://album/$id",
        artist = artist,
        year = 2024,
        trackCount = 10,
        albumType = albumType
    )

    // --- Empty list ---

    @Test
    fun groupTracks_emptyList_returnsEmpty() {
        val result = LibraryItemGrouper.groupTracks(emptyList())
        assertTrue(result.isEmpty())
    }

    // --- 2+ tracks from same album collapse to album card ---

    @Test
    fun groupTracks_twoTracksFromSameAlbum_collapsesToAlbum() {
        val tracks = listOf(
            track(id = "t1", album = "Abbey Road", artist = "Beatles"),
            track(id = "t2", album = "Abbey Road", artist = "Beatles")
        )
        val result = LibraryItemGrouper.groupTracks(tracks)

        assertEquals(1, result.size)
        assertEquals(MaMediaType.ALBUM, result[0].mediaType)
        assertEquals("Abbey Road", result[0].name)
    }

    @Test
    fun groupTracks_threeTracksFromSameAlbum_collapsesToSingleAlbum() {
        val tracks = listOf(
            track(id = "t1", album = "Abbey Road", artist = "Beatles"),
            track(id = "t2", album = "Abbey Road", artist = "Beatles"),
            track(id = "t3", album = "Abbey Road", artist = "Beatles")
        )
        val result = LibraryItemGrouper.groupTracks(tracks)
        assertEquals(1, result.size)
        assertEquals(MaMediaType.ALBUM, result[0].mediaType)
    }

    // --- Single track from regular album stays as track ---

    @Test
    fun groupTracks_singleTrackFromRegularAlbum_staysAsTrack() {
        val tracks = listOf(
            track(id = "t1", album = "Abbey Road", artist = "Beatles")
        )
        val result = LibraryItemGrouper.groupTracks(tracks)

        assertEquals(1, result.size)
        assertEquals(MaMediaType.TRACK, result[0].mediaType)
    }

    // --- Single release shown as album ---

    @Test
    fun groupTracks_singleRelease_showsAsAlbum() {
        val tracks = listOf(
            track(id = "t1", album = "My Single", artist = "Artist", albumType = "single")
        )
        val result = LibraryItemGrouper.groupTracks(tracks)

        assertEquals(1, result.size)
        assertEquals(MaMediaType.ALBUM, result[0].mediaType)
    }

    @Test
    fun groupTracks_singleViaLookup_showsAsAlbum() {
        val tracks = listOf(
            track(id = "t1", album = "My Single", artist = "Artist")
        )
        val lookup = mapOf(
            LibraryItemGrouper.GroupingKey("My Single", "Artist") to
                    album(name = "My Single", artist = "Artist", albumType = "single")
        )
        val result = LibraryItemGrouper.groupTracks(tracks, lookup)

        assertEquals(1, result.size)
        assertEquals(MaMediaType.ALBUM, result[0].mediaType)
    }

    // --- Tracks without album pass through ---

    @Test
    fun groupTracks_trackWithoutAlbum_passesThrough() {
        val tracks = listOf(
            track(id = "t1", album = null, artist = "Artist")
        )
        val result = LibraryItemGrouper.groupTracks(tracks)

        assertEquals(1, result.size)
        assertEquals(MaMediaType.TRACK, result[0].mediaType)
    }

    // --- Order preservation ---

    @Test
    fun groupTracks_albumCardAppearsAtFirstTrackPosition() {
        val tracks = listOf(
            track(id = "t1", album = "First Album", artist = "A"),
            track(id = "t2", album = "Second Album", artist = "B"),
            track(id = "t3", album = "First Album", artist = "A"),
            track(id = "t4", album = "Second Album", artist = "B")
        )
        val result = LibraryItemGrouper.groupTracks(tracks)

        assertEquals(2, result.size)
        assertEquals("First Album", result[0].name)
        assertEquals("Second Album", result[1].name)
    }

    // --- Cross-artist collision prevention ---

    @Test
    fun groupTracks_sameAlbumNameDifferentArtists_staysSeparate() {
        val tracks = listOf(
            track(id = "t1", album = "Greatest Hits", artist = "Beatles"),
            track(id = "t2", album = "Greatest Hits", artist = "Queen")
        )
        val result = LibraryItemGrouper.groupTracks(tracks)

        // Should be 2 separate tracks, NOT collapsed into 1 album
        assertEquals(2, result.size)
        assertEquals(MaMediaType.TRACK, result[0].mediaType)
        assertEquals(MaMediaType.TRACK, result[1].mediaType)
    }

    // --- albumLookup provides real album data ---

    @Test
    fun groupTracks_withAlbumLookup_usesRealAlbumData() {
        val realAlbum = album(id = "real-album-id", name = "Abbey Road", artist = "Beatles")
        val tracks = listOf(
            track(id = "t1", album = "Abbey Road", artist = "Beatles"),
            track(id = "t2", album = "Abbey Road", artist = "Beatles")
        )
        val lookup = mapOf(
            LibraryItemGrouper.GroupingKey("Abbey Road", "Beatles") to realAlbum
        )
        val result = LibraryItemGrouper.groupTracks(tracks, lookup)

        assertEquals(1, result.size)
        assertEquals("real-album-id", result[0].id)
    }

    // --- Mixed scenario ---

    @Test
    fun groupTracks_mixedScenario_handlesCorrectly() {
        val tracks = listOf(
            track(id = "t1", album = "Album A", artist = "Artist 1"),       // Only track from A -> track
            track(id = "t2", album = "Album B", artist = "Artist 1"),       // 2 tracks from B -> album
            track(id = "t3", album = null, artist = "Artist 2"),            // No album -> pass through
            track(id = "t4", album = "Album B", artist = "Artist 1"),       // 2nd from B -> collapsed
            track(id = "t5", album = "My Single", artist = "Artist 3", albumType = "single") // Single -> album
        )
        val result = LibraryItemGrouper.groupTracks(tracks)

        assertEquals(4, result.size)
        assertEquals(MaMediaType.TRACK, result[0].mediaType)  // Track from Album A
        assertEquals(MaMediaType.ALBUM, result[1].mediaType)  // Album B (collapsed)
        assertEquals(MaMediaType.TRACK, result[2].mediaType)  // No-album track
        assertEquals(MaMediaType.ALBUM, result[3].mediaType)  // Single
    }
}
