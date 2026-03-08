package com.sendspindroid.ui.main

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [TrackMetadata] value semantics.
 *
 * Covers the isEmpty property for blank, empty, and populated fields.
 */
class TrackMetadataTest {

    @Test
    fun `isEmpty is true for all empty strings`() {
        val metadata = TrackMetadata("", "", "")
        assertTrue(metadata.isEmpty)
    }

    @Test
    fun `isEmpty is true for all blank strings`() {
        val metadata = TrackMetadata("   ", "  ", " ")
        assertTrue(metadata.isEmpty)
    }

    @Test
    fun `isEmpty is true for EMPTY constant`() {
        assertTrue(TrackMetadata.EMPTY.isEmpty)
    }

    @Test
    fun `isEmpty is false when title is set`() {
        val metadata = TrackMetadata("Song Title", "", "")
        assertFalse(metadata.isEmpty)
    }

    @Test
    fun `isEmpty is false when artist is set`() {
        val metadata = TrackMetadata("", "Artist Name", "")
        assertFalse(metadata.isEmpty)
    }

    @Test
    fun `isEmpty is false when album is set`() {
        val metadata = TrackMetadata("", "", "Album Name")
        assertFalse(metadata.isEmpty)
    }

    @Test
    fun `isEmpty is false when all fields are set`() {
        val metadata = TrackMetadata("Song", "Artist", "Album")
        assertFalse(metadata.isEmpty)
    }

    @Test
    fun `isEmpty is true for mixed empty and blank`() {
        val metadata = TrackMetadata("", " ", "  ")
        assertTrue(metadata.isEmpty)
    }
}
