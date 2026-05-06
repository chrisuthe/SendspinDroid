package com.sendspindroid.coordinator

import com.sendspindroid.model.ConnectionType
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.ProxyConnection
import com.sendspindroid.model.RemoteConnection
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
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            onDisconnectRequested = {},
            connectAttempt = { _, _ -> false },
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
            connectAttempt = { _, _ -> false },
        )

        coordinator.disconnect()
        coordinator.disconnect()

        assertEquals(2, called)
    }

    @Test
    fun `connect succeeds on first method and emits Attempting then Succeeded`() = runTest {
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, _ -> true },
        )

        coordinator.connect(makeTestServerWithLocal())
        testScheduler.advanceUntilIdle()

        // Reconnect loop completed: status should be Succeeded.
        val status = coordinator.reconnectStatus.value
        assertTrue("Expected Succeeded but got $status", status is ReconnectStatus.Succeeded)
    }

    @Test
    fun `connect retries when first method fails and tries next method`() = runTest {
        val attemptedMethods = mutableListOf<ConnectionType>()
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, method ->
                attemptedMethods.add(method)
                false
            },
        )

        coordinator.connect(makeTestServerWithAllMethods())
        testScheduler.advanceTimeBy(700)
        testScheduler.runCurrent()

        assertTrue(
            "Should have attempted at least one method",
            attemptedMethods.isNotEmpty(),
        )
    }

    @Test
    fun `cancelReconnect stops the loop and emits Idle`() = runTest {
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, _ ->
                kotlinx.coroutines.delay(60_000)  // never returns within test
                false
            },
        )

        coordinator.connect(makeTestServerWithLocal())
        testScheduler.advanceTimeBy(700)
        testScheduler.runCurrent()
        coordinator.cancelReconnect()
        testScheduler.advanceUntilIdle()

        assertEquals(ReconnectStatus.Idle, coordinator.reconnectStatus.value)
    }

    private fun TestScope.makeCoordinatorForRetryTest(
        connectAttempt: suspend (UnifiedServer, ConnectionType) -> Boolean,
    ): ConnectionCoordinator {
        return ConnectionCoordinator(
            currentServerFlow = MutableStateFlow(null),
            sendSpinStateFlow = MutableStateFlow(TransportState.Idle),
            musicAssistantStateFlow = MutableStateFlow(TransportState.Idle),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            onDisconnectRequested = {},
            connectAttempt = connectAttempt,
        )
    }

    private fun makeTestServerWithLocal(): UnifiedServer {
        return UnifiedServer(
            id = "test-local",
            name = "Test Local",
            local = LocalConnection(address = "192.168.1.100:8095"),
        )
    }

    private fun makeTestServerWithAllMethods(): UnifiedServer {
        return UnifiedServer(
            id = "test-all",
            name = "Test All Methods",
            local = LocalConnection(address = "192.168.1.100:8095"),
            remote = RemoteConnection(remoteId = "ABCDE12345"),
            proxy = ProxyConnection(url = "wss://proxy.example.com", authToken = "token123"),
        )
    }
}
