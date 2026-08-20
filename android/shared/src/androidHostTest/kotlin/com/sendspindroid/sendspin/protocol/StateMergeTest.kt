package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.protocol.message.MessageParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `server/state` merge semantics.
 *
 * `messaging.md#server--client-serverstate`: "Only include fields that have
 * changed. The client will merge these updates into existing state. A leaf
 * field set to `null` should be cleared from the client's state; a whole role
 * object set to `null` clears all of that role's state."
 *
 * Three wire states per field, and collapsing any two of them produces a
 * different bug:
 *
 * | wire            | meaning |
 * |-----------------|---------|
 * | key absent      | keep what we have |
 * | key with `null` | clear it |
 * | key with value  | replace it |
 *
 * The parser used to read role objects with `as? JsonObject`, which makes an
 * explicit `null` indistinguishable from an absent key, and defaulted every
 * missing string to `""`. So a delta carrying only `progress` produced a
 * metadata object with empty title, artist and album - and downstream, where
 * empty means "clear", that blanked the Now Playing screen on every progress
 * tick.
 */
class StateMergeTest {

    // ========== The bug this exists to fix ==========

    @Test
    fun aProgressOnlyDeltaKeepsTheTrackItIsProgressing() {
        val current = metadata(title = "Carry This Picture", artist = "Dashboard", album = "A Mark")

        val merged = merge(current, """{"metadata":{"progress":{"track_progress":42000}}}""")

        assertEquals("Carry This Picture", merged?.title)
        assertEquals("Dashboard", merged?.artist)
        assertEquals("A Mark", merged?.album)
        assertEquals(42000L, merged?.progress?.trackProgress)
    }

    // ========== Leaves ==========

    @Test
    fun anAbsentLeafKeepsItsValue() {
        val merged = merge(metadata(title = "Kept"), """{"metadata":{"artist":"New"}}""")

        assertEquals("Kept", merged?.title)
        assertEquals("New", merged?.artist)
    }

    @Test
    fun aNullLeafClearsIt() {
        val merged = merge(metadata(title = "Gone"), """{"metadata":{"title":null}}""")

        assertNull(merged?.title)
    }

    @Test
    fun aValuedLeafReplacesIt() {
        val merged = merge(metadata(title = "Old"), """{"metadata":{"title":"New"}}""")

        assertEquals("New", merged?.title)
    }

    // ========== Whole role objects ==========

    @Test
    fun anAbsentRoleObjectChangesNothing() {
        val current = metadata(title = "Kept")

        val update = parse("""{"state":"playing"}""").metadata

        assertTrue(update is RoleUpdate.Absent)
        assertEquals("Kept", update.applyTo(current)?.title)
    }

    @Test
    fun aNullRoleObjectClearsTheWholeRole() {
        // "a whole role object set to null clears all of that role's state" -
        // this is what server/activate sends when it removes a state role.
        val update = parse("""{"metadata":null}""").metadata

        assertTrue(update is RoleUpdate.Cleared)
        assertNull(update.applyTo(metadata(title = "Gone")))
    }

    // ========== Nested objects are replaced, never deep-merged ==========

    @Test
    fun aNestedObjectIsReplacedWholesale() {
        // "The merge is shallow: a nested object (e.g., metadata.progress) is
        // replaced or cleared as a whole, never deep-merged, so nested objects
        // are always sent complete."
        val current = metadata(
            title = "T",
            progress = TrackProgress(trackProgress = 5000, trackDuration = 300000, playbackSpeed = 1000),
        )

        val merged = merge(current, """{"metadata":{"progress":{"track_progress":9000}}}""")

        assertEquals(9000L, merged?.progress?.trackProgress)
        // Deep-merging would have kept 300000 here.
        assertEquals(0L, merged?.progress?.trackDuration)
    }

    @Test
    fun aNullProgressClearsItRatherThanFallingBackToLegacyFields() {
        // The old parser fell through to the pre-spec flat fields whenever
        // progress was absent OR null, and fabricated TrackProgress(0,0,1000)
        // when those were missing too. An explicit null means clear.
        val merged = merge(
            metadata(title = "T", progress = TrackProgress(5000, 300000, 1000)),
            """{"metadata":{"progress":null}}""",
        )

        assertNull(merged?.progress)
    }

    @Test
    fun anAbsentProgressStillHonoursLegacyFlatFields() {
        // Pre-spec Music Assistant sent position_ms/duration_ms at the top of
        // the metadata object. Only used when `progress` is absent entirely.
        val merged = merge(
            metadata(title = "T"),
            """{"metadata":{"position_ms":1234,"duration_ms":5678}}""",
        )

        assertEquals(1234L, merged?.progress?.trackProgress)
        assertEquals(5678L, merged?.progress?.trackDuration)
    }

    @Test
    fun anAbsentProgressWithNoLegacyFieldsKeepsTheCurrentProgress() {
        val current = metadata(title = "T", progress = TrackProgress(5000, 300000, 1000))

        val merged = merge(current, """{"metadata":{"title":"T2"}}""")

        assertEquals(5000L, merged?.progress?.trackProgress)
    }

    // ========== Forward compatibility ==========

    @Test
    fun anUnknownFieldIsIgnored() {
        val merged = merge(metadata(title = "T"), """{"metadata":{"future_field":1,"artist":"A"}}""")

        assertEquals("T", merged?.title)
        assertEquals("A", merged?.artist)
    }

    // ========== Controller ==========

    @Test
    fun aNullControllerObjectClearsTheRole() {
        val update = parse("""{"controller":null}""").controller

        assertTrue(update is RoleUpdate.Cleared)
    }

    @Test
    fun anAbsentControllerLeafKeepsItsValue() {
        val current = ControllerState(volume = 40, muted = true)

        val merged = parse("""{"controller":{"volume":80}}""").controller.applyTo(current)

        assertEquals(80, merged?.volume)
        assertEquals(true, merged?.muted)
    }

    @Test
    fun aNullControllerLeafClearsIt() {
        val current = ControllerState(volume = 40, muted = true)

        val merged = parse("""{"controller":{"muted":null}}""").controller.applyTo(current)

        assertNull(merged?.muted)
        assertEquals(40, merged?.volume)
    }

    // ========== Helpers ==========

    private fun parse(json: String) =
        MessageParser.parseServerState(Json.parseToJsonElement(json).jsonObject)

    private fun merge(current: TrackMetadata?, json: String): TrackMetadata? =
        parse(json).metadata.applyTo(current)

    private fun metadata(
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        progress: TrackProgress? = null,
    ) = TrackMetadata(
        timestamp = null,
        title = title,
        artist = artist,
        albumArtist = null,
        album = album,
        artworkUrl = null,
        year = null,
        track = null,
        progress = progress,
    )
}
