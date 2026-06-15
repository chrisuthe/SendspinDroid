package com.sendspindroid.sendspin.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [TrackMetadata.progressAtServerTime], the spec formula for
 * extrapolating track position from a metadata snapshot.
 */
class TrackMetadataProgressTest {

    private fun metadata(
        timestamp: Long,
        progressMs: Long,
        durationMs: Long,
        speed: Int = 1000
    ) = TrackMetadata(
        timestamp = timestamp,
        title = "t", artist = "a", albumArtist = "", album = "",
        artworkUrl = "", year = 0, track = 0,
        progress = TrackProgress(progressMs, durationMs, speed)
    )

    @Test
    fun normalSpeed_advancesByElapsedTime() {
        val m = metadata(timestamp = 1_000_000L, progressMs = 30_000L, durationMs = 180_000L)
        // 5 seconds of server time elapsed at 1.0x
        assertEquals(35_000L, m.progressAtServerTime(6_000_000L))
    }

    @Test
    fun pausedSpeedZero_positionDoesNotAdvance() {
        val m = metadata(1_000_000L, 30_000L, 180_000L, speed = 0)
        assertEquals(30_000L, m.progressAtServerTime(61_000_000L))
    }

    @Test
    fun halfSpeed_advancesAtHalfRate() {
        val m = metadata(1_000_000L, 30_000L, 180_000L, speed = 500)
        // 10s elapsed at 0.5x = +5s
        assertEquals(35_000L, m.progressAtServerTime(11_000_000L))
    }

    @Test
    fun clampedToDuration() {
        val m = metadata(1_000_000L, 175_000L, 180_000L)
        // 60s elapsed would overshoot the 180s duration
        assertEquals(180_000L, m.progressAtServerTime(61_000_000L))
    }

    @Test
    fun unknownDuration_notClampedAbove() {
        // duration 0 = unlimited (live radio): keep counting up
        val m = metadata(1_000_000L, 175_000L, 0L)
        assertEquals(235_000L, m.progressAtServerTime(61_000_000L))
    }

    @Test
    fun clampedToZeroWhenServerTimeBeforeTimestamp() {
        val m = metadata(60_000_000L, 1_000L, 180_000L)
        // Server "now" 50s before the snapshot timestamp (clock glitch)
        assertEquals(0L, m.progressAtServerTime(10_000_000L))
    }

    @Test
    fun missingTimestamp_fallsBackToRawPosition() {
        val m = metadata(timestamp = 0L, progressMs = 30_000L, durationMs = 180_000L)
        assertEquals(30_000L, m.progressAtServerTime(99_000_000L))
    }
}
