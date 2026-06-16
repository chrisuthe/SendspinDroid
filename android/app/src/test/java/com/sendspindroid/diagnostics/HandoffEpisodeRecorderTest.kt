package com.sendspindroid.diagnostics

import com.sendspindroid.diagnostics.HandoffEpisodeRecorder.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffEpisodeRecorderTest {

    private var now = 1_000L
    private val methods = listOf("LOCAL", "REMOTE")
    private val recorder = HandoffEpisodeRecorder(capacity = 3, clock = { now })

    private fun attempt(method: String, transport: String = "CELLULAR", playing: Boolean = true) =
        recorder.onReconnect(Phase.ATTEMPTING, method, transport, methods, playing)

    @Test
    fun `attempting then succeeded records a RECOVERED episode with timing`() {
        now = 1_000L
        attempt("PROXY", transport = "CELLULAR")
        now = 1_500L
        recorder.onReconnect(Phase.SUCCEEDED, null, "CELLULAR", methods, true)

        val episodes = recorder.recent()
        assertEquals(1, episodes.size)
        val e = episodes[0]
        assertEquals(HandoffEpisode.Outcome.RECOVERED, e.outcome)
        assertEquals(500L, e.recoveryMs)
        assertEquals("PROXY", e.recoveredMethod)
        assertEquals("CELLULAR", e.fromTransport)
        assertEquals(methods, e.configuredMethods)
        assertTrue(e.wasPlaying)
        assertEquals(1, e.attempts.size)
    }

    @Test
    fun `multiple attempts then failed records an EXHAUSTED episode`() {
        attempt("PROXY")
        attempt("REMOTE")
        attempt("LOCAL")
        recorder.onReconnect(Phase.FAILED, null, "CELLULAR", methods, true)

        val e = recorder.recent().single()
        assertEquals(HandoffEpisode.Outcome.EXHAUSTED, e.outcome)
        assertEquals(3, e.attempts.size)
        assertNull(e.recoveryMs)
        assertNull(e.recoveredMethod)
    }

    @Test
    fun `idle while an episode is open records ABANDONED`() {
        attempt("LOCAL")
        recorder.onReconnect(Phase.IDLE, null, "WIFI", methods, true)
        assertEquals(HandoffEpisode.Outcome.ABANDONED, recorder.recent().single().outcome)
    }

    @Test
    fun `idle with no open episode is ignored`() {
        recorder.onReconnect(Phase.IDLE, null, "WIFI", methods, false)
        assertTrue(recorder.recent().isEmpty())
    }

    @Test
    fun `ring buffer drops the oldest beyond capacity`() {
        repeat(4) {
            attempt("LOCAL")
            recorder.onReconnect(Phase.SUCCEEDED, null, "WIFI", methods, true)
        }
        assertEquals(3, recorder.recent().size) // capacity = 3
    }
}
