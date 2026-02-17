package com.sendspindroid.model

import com.sendspindroid.shared.platform.Platform
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackStateTest {

    @Before
    fun setUp() {
        mockkObject(Platform)
        every { Platform.elapsedRealtimeMs() } returns 10_000L
        every { Platform.currentTimeMillis() } returns 1_000_000L
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- Default state ---

    @Test
    fun defaultState_displayTitleIsUnknownTrack() {
        val state = PlaybackState()
        assertEquals("Unknown Track", state.displayTitle)
    }

    @Test
    fun defaultState_displayArtistIsUnknownArtist() {
        val state = PlaybackState()
        assertEquals("Unknown Artist", state.displayArtist)
    }

    @Test
    fun defaultState_hasMetadataIsFalse() {
        val state = PlaybackState()
        assertFalse(state.hasMetadata)
    }

    @Test
    fun defaultState_playbackStateIsIdle() {
        val state = PlaybackState()
        assertEquals(PlaybackStateType.IDLE, state.playbackState)
    }

    // --- displayString ---

    @Test
    fun displayString_bothArtistAndTitle_returnsArtistDashTitle() {
        val state = PlaybackState(artist = "Beatles", title = "Hey Jude")
        assertEquals("Beatles - Hey Jude", state.displayString)
    }

    @Test
    fun displayString_onlyTitle_returnsTitleAlone() {
        val state = PlaybackState(title = "Hey Jude")
        assertEquals("Hey Jude", state.displayString)
    }

    @Test
    fun displayString_neither_returnsUnknownTrack() {
        val state = PlaybackState()
        assertEquals("Unknown Track", state.displayString)
    }

    // --- hasMetadata ---

    @Test
    fun hasMetadata_withTitle_isTrue() {
        assertTrue(PlaybackState(title = "Song").hasMetadata)
    }

    @Test
    fun hasMetadata_withArtist_isTrue() {
        assertTrue(PlaybackState(artist = "Artist").hasMetadata)
    }

    @Test
    fun hasMetadata_withAlbum_isTrue() {
        assertTrue(PlaybackState(album = "Album").hasMetadata)
    }

    // --- PlaybackStateType.fromString ---

    @Test
    fun playbackStateType_fromString_playing() {
        assertEquals(PlaybackStateType.PLAYING, PlaybackStateType.fromString("playing"))
    }

    @Test
    fun playbackStateType_fromString_paused() {
        assertEquals(PlaybackStateType.PAUSED, PlaybackStateType.fromString("paused"))
    }

    @Test
    fun playbackStateType_fromString_buffering() {
        assertEquals(PlaybackStateType.BUFFERING, PlaybackStateType.fromString("buffering"))
    }

    @Test
    fun playbackStateType_fromString_stopped() {
        assertEquals(PlaybackStateType.STOPPED, PlaybackStateType.fromString("stopped"))
    }

    @Test
    fun playbackStateType_fromString_unknown_returnsIdle() {
        assertEquals(PlaybackStateType.IDLE, PlaybackStateType.fromString("garbage"))
    }

    @Test
    fun playbackStateType_fromString_caseInsensitive() {
        assertEquals(PlaybackStateType.PLAYING, PlaybackStateType.fromString("PLAYING"))
        assertEquals(PlaybackStateType.PAUSED, PlaybackStateType.fromString("Paused"))
    }

    // --- withMetadata ---

    @Test
    fun withMetadata_updatesFields() {
        val state = PlaybackState(title = "Old Song", artist = "Old Artist")
        val updated = state.withMetadata(
            title = "New Song",
            artist = "New Artist",
            album = "New Album",
            artworkUrl = "https://art.jpg",
            durationMs = 180000,
            positionMs = 5000
        )
        assertEquals("New Song", updated.title)
        assertEquals("New Artist", updated.artist)
        assertEquals("New Album", updated.album)
        assertEquals(180000, updated.durationMs)
        assertEquals(5000, updated.positionMs)
    }

    @Test
    fun withMetadata_nullFieldsPreserveExisting() {
        val state = PlaybackState(title = "Keep This", artist = "Keep Artist")
        val updated = state.withMetadata(
            title = null, artist = null, album = null, artworkUrl = null,
            durationMs = 0, positionMs = 0
        )
        assertEquals("Keep This", updated.title)
        assertEquals("Keep Artist", updated.artist)
    }

    @Test
    fun withMetadata_emptyStringsClearFields() {
        val state = PlaybackState(title = "Song", artist = "Artist", album = "Album")
        val updated = state.withMetadata(
            title = "", artist = "", album = "", artworkUrl = "",
            durationMs = 0, positionMs = 0
        )
        assertNull(updated.title)
        assertNull(updated.artist)
        assertNull(updated.album)
    }

    // --- withClearedMetadata ---

    @Test
    fun withClearedMetadata_clearsAllMetadata() {
        val state = PlaybackState(
            title = "Song", artist = "Artist", album = "Album",
            artworkUrl = "url", durationMs = 180000, positionMs = 5000,
            groupId = "group1"
        )
        val cleared = state.withClearedMetadata()

        assertNull(cleared.title)
        assertNull(cleared.artist)
        assertNull(cleared.album)
        assertNull(cleared.artworkUrl)
        assertEquals(0, cleared.durationMs)
        assertEquals(0, cleared.positionMs)
        assertEquals("group1", cleared.groupId)
    }

    // --- withGroupUpdate ---

    @Test
    fun withGroupUpdate_updatesGroupFields() {
        val state = PlaybackState(title = "Song")
        val updated = state.withGroupUpdate("g1", "Living Room", PlaybackStateType.PLAYING)

        assertEquals("g1", updated.groupId)
        assertEquals("Living Room", updated.groupName)
        assertEquals(PlaybackStateType.PLAYING, updated.playbackState)
        assertEquals("Song", updated.title)
    }

    // --- interpolatedPositionMs ---

    @Test
    fun interpolatedPositionMs_whenPaused_returnsPositionMs() {
        val state = PlaybackState(
            playbackState = PlaybackStateType.PAUSED,
            positionMs = 5000,
            positionUpdatedAt = 8_000L,
            durationMs = 180000
        )
        assertEquals(5000L, state.interpolatedPositionMs)
    }

    @Test
    fun interpolatedPositionMs_whenPlaying_addsElapsedTime() {
        val state = PlaybackState(
            playbackState = PlaybackStateType.PLAYING,
            positionMs = 5000,
            positionUpdatedAt = 8_000L,
            durationMs = 180000
        )
        assertEquals(7000L, state.interpolatedPositionMs)
    }

    @Test
    fun interpolatedPositionMs_whenPlaying_cappedAtDuration() {
        val state = PlaybackState(
            playbackState = PlaybackStateType.PLAYING,
            positionMs = 179999,
            positionUpdatedAt = 5_000L,
            durationMs = 180000
        )
        assertEquals(180000L, state.interpolatedPositionMs)
    }
}
