package com.sendspindroid.coordinator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStateTest {
    @Test
    fun `default SessionState has no server and both transports Idle`() {
        val state = SessionState()
        assertNull(state.server)
        assertEquals(TransportState.Idle, state.sendSpin)
        assertEquals(TransportState.Idle, state.musicAssistant)
    }

    @Test
    fun `SessionState holds independent per-transport states`() {
        val state = SessionState(
            server = null,
            sendSpin = TransportState.Ready,
            musicAssistant = TransportState.Failed(FailureReason.TransientNetwork),
        )
        assertEquals(TransportState.Ready, state.sendSpin)
        assertEquals(
            TransportState.Failed(FailureReason.TransientNetwork),
            state.musicAssistant
        )
    }
}
