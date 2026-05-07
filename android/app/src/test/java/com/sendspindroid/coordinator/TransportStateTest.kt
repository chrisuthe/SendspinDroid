package com.sendspindroid.coordinator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportStateTest {
    @Test
    fun `Failed carries a FailureReason`() {
        val state: TransportState = TransportState.Failed(FailureReason.TransientNetwork)
        assertTrue(state is TransportState.Failed)
        assertEquals(FailureReason.TransientNetwork, (state as TransportState.Failed).reason)
    }

    @Test
    fun `when expression is exhaustive across all states`() {
        val states: List<TransportState> = listOf(
            TransportState.Idle,
            TransportState.Connecting,
            TransportState.Ready,
            TransportState.Failed(FailureReason.AuthRejected),
        )
        val labels = states.map {
            when (it) {
                TransportState.Idle -> "idle"
                TransportState.Connecting -> "connecting"
                TransportState.Ready -> "ready"
                is TransportState.Failed -> "failed"
            }
        }
        assertEquals(listOf("idle", "connecting", "ready", "failed"), labels)
    }
}
