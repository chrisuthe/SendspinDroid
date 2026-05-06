package com.sendspindroid.coordinator

import com.sendspindroid.model.UnifiedServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorTest {

    @Test
    fun `sessionState combines current server, sendSpin state, and ma state`() = runTest {
        val server = MutableStateFlow<UnifiedServer?>(null)
        val sendSpin = MutableStateFlow<TransportState>(TransportState.Idle)
        val ma = MutableStateFlow<TransportState>(TransportState.Idle)

        val coordinator = ConnectionCoordinator(
            currentServerFlow = server,
            sendSpinStateFlow = sendSpin,
            musicAssistantStateFlow = ma,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            onDisconnectRequested = {},
        )

        // Initial state
        assertEquals(SessionState(), coordinator.sessionState.first())

        // Transitions on each input flow propagate
        sendSpin.value = TransportState.Ready
        ma.value = TransportState.Connecting
        testScheduler.runCurrent()

        val combined = coordinator.sessionState.first()
        assertEquals(TransportState.Ready, combined.sendSpin)
        assertEquals(TransportState.Connecting, combined.musicAssistant)
    }

    @Test
    fun `disconnect forwards to onDisconnectRequested lambda`() = runTest {
        var called = 0
        val coordinator = ConnectionCoordinator(
            currentServerFlow = MutableStateFlow(null),
            sendSpinStateFlow = MutableStateFlow(TransportState.Idle),
            musicAssistantStateFlow = MutableStateFlow(TransportState.Idle),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            onDisconnectRequested = { called++ },
        )

        coordinator.disconnect()
        coordinator.disconnect()

        assertEquals(2, called)
    }
}
