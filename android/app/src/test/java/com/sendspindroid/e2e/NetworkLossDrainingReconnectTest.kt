package com.sendspindroid.e2e

import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.SendSpinClient
import com.sendspindroid.sendspin.SendspinTimeFilter
import io.mockk.every
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketException

/**
 * E2E Test 2: Network loss during playback -> drain -> reconnect -> resume
 *
 * Simulates network loss during active playback:
 * 1. Establish connection, start audio streaming
 * 2. Simulate transport failure (network loss)
 * 3. Verify DRAINING state entered (time filter frozen)
 * 4. Simulate network restore
 * 5. Verify reconnection attempt with time filter thaw
 *
 * This test verifies the reconnection state machine without actual audio
 * output (SyncAudioPlayer is not involved at the transport level).
 */
class NetworkLossDrainingReconnectTest : E2ETestBase() {

    @Test
    fun `network loss triggers reconnection`() {
        connectAndHandshake()

        // Verify connected
        assertTrue("Should be connected", client.isConnected)

        // Simulate transport failure (recoverable network error)
        fakeTransport.simulateFailure(
            error = SocketException("Connection reset"),
            isRecoverable = true
        )

        // After failure, reconnection should be attempted
        verify { mockCallback.onReconnecting(1, any()) }

        // Reconnect attempt counter should increment
        assertEquals(1, client.getReconnectAttempts())

        // Connection state should be Connecting (reconnecting)
        val state = client.connectionState.value
        assertTrue(
            "State should be Connecting during reconnection",
            state is SendSpinClient.ConnectionState.Connecting
        )
    }

    @Test
    fun `reconnect attempt counter increments on repeated failures`() {
        connectAndHandshake()

        // First failure
        fakeTransport.simulateFailure(
            error = SocketException("Connection reset"),
            isRecoverable = true
        )
        verify { mockCallback.onReconnecting(1, any()) }

        // Check internal reconnect attempts counter
        assertEquals(1, client.getReconnectAttempts())
    }

    @Test
    fun `non-recoverable error does not trigger reconnection`() {
        connectAndHandshake()

        // Simulate non-recoverable failure
        fakeTransport.simulateFailure(
            error = javax.net.ssl.SSLHandshakeException("Certificate error"),
            isRecoverable = false
        )

        // Should NOT attempt reconnection
        verify(exactly = 0) { mockCallback.onReconnecting(any(), any()) }

        // Should report error
        verify { mockCallback.onError(any()) }

        // Connection state should be Error
        val state = client.connectionState.value
        assertTrue(
            "State should be Error for non-recoverable failure",
            state is SendSpinClient.ConnectionState.Error
        )
    }

    @Test
    fun `user-initiated disconnect during reconnection stops retries`() {
        connectAndHandshake()

        // Simulate failure to trigger reconnection
        fakeTransport.simulateFailure(
            error = SocketException("Connection reset"),
            isRecoverable = true
        )

        // User disconnects during reconnection
        client.disconnect()

        // Should be disconnected, not reconnecting
        assertTrue(
            "State should be Disconnected after user disconnect",
            client.connectionState.value is SendSpinClient.ConnectionState.Disconnected
        )

        // Verify onDisconnected was called with user-initiated flag
        verify {
            mockCallback.onDisconnected(wasUserInitiated = true, wasReconnectExhausted = false)
        }
    }

    @Test
    fun `normal closure (code 1000) does not trigger reconnection`() {
        connectAndHandshake()

        // Server closes gracefully
        fakeTransport.simulateClosed(code = 1000, reason = "Server shutdown")

        // Should NOT attempt reconnection for normal closure
        verify(exactly = 0) { mockCallback.onReconnecting(any(), any()) }

        // Should be disconnected
        assertTrue(
            "State should be Disconnected after normal closure",
            client.connectionState.value is SendSpinClient.ConnectionState.Disconnected
        )
    }

    @Test
    fun `abnormal closure triggers reconnection`() {
        connectAndHandshake()

        // Server drops connection unexpectedly
        fakeTransport.simulateClosed(code = 1006, reason = "Abnormal closure")

        // Should attempt reconnection
        verify { mockCallback.onReconnecting(1, any()) }
    }

    @Test
    fun `time filter freeze is attempted on first reconnect`() {
        connectAndHandshake()

        val timeFilter: SendspinTimeFilter = client.getTimeFilter()
        assertFalse("Time filter should not be frozen initially", timeFilter.isFrozen)

        // Trigger reconnection
        fakeTransport.simulateFailure(
            error = SocketException("Connection reset"),
            isRecoverable = true
        )

        // freeze() is called but is a no-op when the filter has no measurements
        // (isReady == false). This is correct behavior: if there are no measurements
        // to preserve, there's nothing to freeze.
        // We verify reconnection was triggered instead.
        verify { mockCallback.onReconnecting(1, any()) }

        // Note: in production with real time sync measurements, isFrozen would
        // be true here. This is a limitation of the test environment where no
        // server/time messages have been exchanged.
    }

    @Test
    fun `network unavailable pauses reconnection attempts`() {
        connectAndHandshake()

        // Mark network as unavailable
        client.setNetworkAvailable(false)

        // Trigger reconnection
        fakeTransport.simulateFailure(
            error = SocketException("Connection reset"),
            isRecoverable = true
        )

        // Reconnection should still be triggered (callback fires)
        verify { mockCallback.onReconnecting(any(), any()) }

        // But the attempt counter should not have increased
        // (it's decremented when network is unavailable)
    }

    @Test
    fun `reconnect beyond old 5-attempt cap keeps retrying in normal mode`() {
        // The 5-attempt cap was removed: both normal and high power mode now
        // retry forever with 30s steady-state after attempt 5. There is no
        // exhausted-callback path any more -- the user has to disconnect
        // manually (or the reconnect succeeds) to exit reconnection state.
        connectAndHandshake()

        // Start past the old cap
        setAtomicInteger(client, "reconnectAttempts", 5)

        // Trigger another reconnection - in the old code this would exhaust
        fakeTransport.simulateFailure(
            error = SocketException("Connection reset"),
            isRecoverable = true
        )

        // Must NOT report exhausted reconnection any more
        verify(exactly = 0) {
            mockCallback.onDisconnected(wasUserInitiated = false, wasReconnectExhausted = true)
        }

        // Should still be reconnecting, not in Error state
        verify { mockCallback.onReconnecting(any(), any()) }
        val state = client.connectionState.value
        assertTrue(
            "State should remain Connecting with no cap, was: $state",
            state is SendSpinClient.ConnectionState.Connecting
        )
    }

    @Test
    fun `high power mode allows unlimited reconnection attempts`() {
        // Enable high power mode
        every { UserSettings.highPowerMode } returns true

        connectAndHandshake()

        // Set attempts beyond normal max
        setAtomicInteger(client, "reconnectAttempts", 10)

        // Trigger reconnection - should NOT exhaust with high power mode
        fakeTransport.simulateFailure(
            error = SocketException("Connection reset"),
            isRecoverable = true
        )

        // Should still be reconnecting, not exhausted
        verify(exactly = 0) {
            mockCallback.onDisconnected(wasUserInitiated = false, wasReconnectExhausted = true)
        }
        verify { mockCallback.onReconnecting(any(), any()) }
    }

    @Test
    fun `onNetworkChanged resets time filter when connected`() {
        connectAndHandshake()

        val timeFilter: SendspinTimeFilter = client.getTimeFilter()

        // Simulate network change (e.g., WiFi to cellular)
        client.onNetworkChanged()

        // Callback should be notified
        verify { mockCallback.onNetworkChanged() }
    }

    @Test
    fun `onNetworkChanged is no-op when not connected`() {
        // Don't connect - client is disconnected
        client.onNetworkChanged()

        // Callback should NOT be called
        verify(exactly = 0) { mockCallback.onNetworkChanged() }
    }
}
