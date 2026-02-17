package com.sendspindroid.model

import android.os.Bundle
import com.sendspindroid.sendspin.PlaybackState
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatsTest {

    // --- fromBundle: PlaybackState parsing (C-17) ---

    @Test
    fun fromBundle_knownPlaybackState_parsesCorrectly() {
        val bundle = mockBundle("playback_state" to "PLAYING")

        val stats = SyncStats.fromBundle(bundle)

        assertEquals(PlaybackState.PLAYING, stats.playbackState)
    }

    @Test
    fun fromBundle_unknownPlaybackState_fallsBackToInitializing() {
        val bundle = mockBundle("playback_state" to "TOTALLY_UNKNOWN_STATE")

        val stats = SyncStats.fromBundle(bundle)

        assertEquals(PlaybackState.INITIALIZING, stats.playbackState)
    }

    @Test
    fun fromBundle_missingPlaybackState_fallsBackToInitializing() {
        // No playback_state key in bundle at all
        val bundle = mockBundle()

        val stats = SyncStats.fromBundle(bundle)

        assertEquals(PlaybackState.INITIALIZING, stats.playbackState)
    }

    @Test
    fun fromBundle_emptyPlaybackState_fallsBackToInitializing() {
        val bundle = mockBundle("playback_state" to "")

        val stats = SyncStats.fromBundle(bundle)

        assertEquals(PlaybackState.INITIALIZING, stats.playbackState)
    }

    @Test
    fun fromBundle_allPlaybackStatesRoundTrip() {
        for (state in PlaybackState.entries) {
            val bundle = mockBundle("playback_state" to state.name)

            val stats = SyncStats.fromBundle(bundle)

            assertEquals(
                "PlaybackState.${state.name} should survive round-trip",
                state,
                stats.playbackState
            )
        }
    }

    // --- fromBundle: basic field parsing ---

    @Test
    fun fromBundle_parsesConnectionFields() {
        val bundle = mockBundle(
            "server_name" to "Living Room",
            "server_address" to "192.168.1.10:4040",
            "connection_state" to "Connected"
        )

        val stats = SyncStats.fromBundle(bundle)

        assertEquals("Living Room", stats.serverName)
        assertEquals("192.168.1.10:4040", stats.serverAddress)
        assertEquals("Connected", stats.connectionState)
    }

    // --- Helper ---

    /**
     * Creates a mock Bundle that returns values for the given key-value pairs.
     * Keys not in the map return null/0/false (matching Bundle defaults).
     */
    private fun mockBundle(vararg entries: Pair<String, Any>): Bundle {
        val map = entries.toMap()
        return mockk<Bundle> {
            // getString(key) - returns value or null
            every { getString(any()) } answers {
                map[firstArg()] as? String
            }
            // getString(key, default) - returns value or default
            every { getString(any(), any()) } answers {
                (map[firstArg()] as? String) ?: secondArg()
            }
            // getBoolean(key, default)
            every { getBoolean(any(), any()) } answers {
                (map[firstArg()] as? Boolean) ?: secondArg()
            }
            // getLong(key, default)
            every { getLong(any(), any()) } answers {
                (map[firstArg()] as? Long) ?: secondArg()
            }
            // getLong(key) - for containsKey path
            every { getLong(any()) } answers {
                (map[firstArg()] as? Long) ?: 0L
            }
            // getInt(key, default)
            every { getInt(any(), any()) } answers {
                (map[firstArg()] as? Int) ?: secondArg()
            }
            // getDouble(key, default)
            every { getDouble(any(), any()) } answers {
                (map[firstArg()] as? Double) ?: secondArg()
            }
            // containsKey(key)
            every { containsKey(any()) } answers {
                firstArg<String>() in map
            }
        }
    }
}
