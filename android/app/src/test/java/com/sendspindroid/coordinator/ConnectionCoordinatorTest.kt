package com.sendspindroid.coordinator

import com.sendspindroid.model.UnifiedServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            reconnectStatusFlow = MutableStateFlow(ReconnectStatus.Idle),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            onDisconnectRequested = {},
            onConnectRequested = {},
            onCancelReconnectRequested = {},
            onNetworkAvailableSignaled = {},
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
            reconnectStatusFlow = MutableStateFlow(ReconnectStatus.Idle),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            onDisconnectRequested = { called++ },
            onConnectRequested = {},
            onCancelReconnectRequested = {},
            onNetworkAvailableSignaled = {},
        )

        coordinator.disconnect()
        coordinator.disconnect()

        assertEquals(2, called)
    }

    @Test
    fun `reconnectStatus reflects upstream flow`() = runTest {
        val recon = MutableStateFlow<ReconnectStatus>(ReconnectStatus.Idle)
        val coordinator = ConnectionCoordinator(
            currentServerFlow = MutableStateFlow(null),
            sendSpinStateFlow = MutableStateFlow(TransportState.Idle),
            musicAssistantStateFlow = MutableStateFlow(TransportState.Idle),
            reconnectStatusFlow = recon,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            onDisconnectRequested = {},
            onConnectRequested = {},
            onCancelReconnectRequested = {},
            onNetworkAvailableSignaled = {},
        )

        assertEquals(ReconnectStatus.Idle, coordinator.reconnectStatus.first())

        recon.value = ReconnectStatus.Attempting("s1", 1, 11, null)
        testScheduler.runCurrent()

        val v = coordinator.reconnectStatus.first()
        assertTrue(v is ReconnectStatus.Attempting)
        assertEquals("s1", (v as ReconnectStatus.Attempting).serverId)
    }

    @Test
    fun `connect cancelReconnect onNetworkAvailable forward to lambdas`() = runTest {
        val connectCalls = mutableListOf<UnifiedServer>()
        var cancelCount = 0
        var networkCount = 0

        val coordinator = ConnectionCoordinator(
            currentServerFlow = MutableStateFlow(null),
            sendSpinStateFlow = MutableStateFlow(TransportState.Idle),
            musicAssistantStateFlow = MutableStateFlow(TransportState.Idle),
            reconnectStatusFlow = MutableStateFlow(ReconnectStatus.Idle),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            onDisconnectRequested = {},
            onConnectRequested = { connectCalls.add(it) },
            onCancelReconnectRequested = { cancelCount++ },
            onNetworkAvailableSignaled = { networkCount++ },
        )

        val server = makeTestServer()
        coordinator.connect(server)
        coordinator.cancelReconnect()
        coordinator.cancelReconnect()
        coordinator.onNetworkAvailable()

        assertEquals(listOf(server), connectCalls)
        assertEquals(2, cancelCount)
        assertEquals(1, networkCount)
    }

    private fun makeTestServer(): UnifiedServer {
        return UnifiedServer(id = "test", name = "Test")
    }
}
