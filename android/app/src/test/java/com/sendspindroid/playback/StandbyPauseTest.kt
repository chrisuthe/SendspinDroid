package com.sendspindroid.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every state a pause can arrive in, see [StandbyPause]. */
class StandbyPauseTest {

    private var standby = true
    private var group: String? = "shared"
    private var canLeave = true
    private var leaves = 0
    private var pauses = 0
    private val scheduled = mutableListOf<() -> Unit>()

    private val policy = StandbyPause(
        inStandby = { standby },
        playingGroup = { group },
        leaveGroup = { if (canLeave) leaves++; canLeave },
        pauseGroup = { pauses++ },
        schedule = { _, action -> scheduled += action },
    )

    private fun runTimeout() = scheduled.toList().also { scheduled.clear() }.forEach { it() }

    @Test
    fun `awake and playing - the pause is the user's`() {
        standby = false
        assertFalse(policy.handlePause())
        assertEquals(0, leaves)
    }

    @Test
    fun `awake and not playing - the pause is the user's`() {
        standby = false
        group = null
        assertFalse(policy.handlePause())
        assertEquals(0, leaves)
    }

    @Test
    fun `standby and playing - leaves instead of pausing the group`() {
        assertTrue(policy.handlePause())
        assertEquals(1, leaves)
        assertEquals(0, pauses)
    }

    @Test
    fun `standby and not playing - nothing is sent`() {
        group = null
        assertTrue(policy.handlePause())
        assertEquals(0, leaves + pauses)
    }

    @Test
    fun `standby and cannot leave - the pause goes out`() {
        canLeave = false
        assertFalse(policy.handlePause())
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `a burst of pauses leaves once`() {
        repeat(4) { assertTrue(policy.handlePause()) }
        assertEquals(1, leaves)
        assertEquals(0, pauses)
    }

    @Test
    fun `a pause after the leave took effect does not reach the group`() {
        policy.handlePause()
        group = null
        assertTrue(policy.handlePause())
        assertEquals(1, leaves)
        assertEquals(0, pauses)
    }

    @Test
    fun `leave took effect - no fallback pause`() {
        assertTrue(policy.handlePause())
        group = null
        runTimeout()
        assertEquals(0, pauses)
    }

    @Test
    fun `moved to another group - that group is not paused`() {
        assertTrue(policy.handlePause())
        group = "other"
        runTimeout()
        assertEquals(0, pauses)
    }

    @Test
    fun `server ignored the leave - falls back to pause`() {
        assertTrue(policy.handlePause())
        runTimeout()
        assertEquals(1, pauses)
    }

    @Test
    fun `woken before the timeout - an ignored leave still pauses`() {
        assertTrue(policy.handlePause())
        standby = false
        runTimeout()
        assertEquals(1, pauses)
    }

    @Test
    fun `the next standby leaves again`() {
        policy.handlePause()
        group = null
        runTimeout()
        group = "shared"
        assertTrue(policy.handlePause())
        assertEquals(2, leaves)
    }
}
