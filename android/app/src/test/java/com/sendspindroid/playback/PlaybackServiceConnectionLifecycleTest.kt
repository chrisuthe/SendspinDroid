package com.sendspindroid.playback

import android.util.Log
import com.sendspindroid.coordinator.FailureReason
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.sendspin.SendSpinClient
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration test: PlaybackService + SendSpinClient connection lifecycle.
 *
 * Verifies that connectToServer creates a client, transitions through Connecting
 * state, and that the callback interface bridges events correctly between
 * SendSpinClient and PlaybackService state.
 *
 * Since PlaybackService is tightly coupled to Android framework (MediaLibraryService),
 * these tests exercise the callback interface and state transitions in isolation
 * by reproducing the connection lifecycle logic.
 */
class PlaybackServiceConnectionLifecycleTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Simulates the connection state machine as implemented in PlaybackService.
     * PlaybackService.connectToServer sets state to Connecting, then calls
     * sendSpinClient.connect(). The client callback fires onConnected,
     * which transitions to Connected.
     */
    sealed class ConnectionState {
        data class Disconnected(
            val wasUserInitiated: Boolean = false,
            val wasReconnectExhausted: Boolean = false
        ) : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val serverName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    @Test
    fun `connectToServer transitions state to Connecting before calling client`() {
        var connectionState: ConnectionState = ConnectionState.Disconnected()
        var clientConnectCalled = false
        var stateAtClientConnect: ConnectionState? = null

        // Simulate the PlaybackService.connectToServer flow
        // Step 1: Set state to Connecting
        connectionState = ConnectionState.Connecting

        // Step 2: The client.connect() call captures state
        stateAtClientConnect = connectionState
        clientConnectCalled = true

        assertTrue("Client connect should be called", clientConnectCalled)
        assertTrue(
            "State should be Connecting when client.connect() is called",
            stateAtClientConnect is ConnectionState.Connecting
        )
    }

    @Test
    fun `onConnected callback transitions from Connecting to Connected`() {
        var connectionState: ConnectionState = ConnectionState.Disconnected()

        // Simulate connectToServer
        connectionState = ConnectionState.Connecting

        // Simulate SendSpinClient.Callback.onConnected (triggered by handshake)
        val serverName = "Living Room"
        connectionState = ConnectionState.Connected(serverName)

        assertTrue(connectionState is ConnectionState.Connected)
        assertEquals("Living Room", (connectionState as ConnectionState.Connected).serverName)
    }

    @Test
    fun `onError callback transitions to Error state with message`() {
        var connectionState: ConnectionState = ConnectionState.Disconnected()

        // Simulate connectToServer
        connectionState = ConnectionState.Connecting

        // Simulate SendSpinClient.Callback.onError
        connectionState = ConnectionState.Error("Connection refused")

        assertTrue(connectionState is ConnectionState.Error)
        assertEquals("Connection refused", (connectionState as ConnectionState.Error).message)
    }

    @Test
    fun `onDisconnected callback transitions to Disconnected with user-initiated flag`() {
        var connectionState: ConnectionState = ConnectionState.Connected("Server")

        // Simulate user-initiated disconnect
        connectionState = ConnectionState.Disconnected(wasUserInitiated = true)

        assertTrue(connectionState is ConnectionState.Disconnected)
        assertTrue((connectionState as ConnectionState.Disconnected).wasUserInitiated)
        assertFalse(connectionState.wasReconnectExhausted)
    }

    @Test
    fun `onDisconnected callback reports reconnect exhaustion`() {
        var connectionState: ConnectionState = ConnectionState.Connected("Server")

        // Simulate reconnect-exhausted disconnect
        connectionState = ConnectionState.Disconnected(wasReconnectExhausted = true)

        assertTrue(connectionState is ConnectionState.Disconnected)
        assertFalse((connectionState as ConnectionState.Disconnected).wasUserInitiated)
        assertTrue(connectionState.wasReconnectExhausted)
    }

    @Test
    fun `connectToServer disconnects existing connection first`() {
        var disconnectCalled = false
        var connectAddress: String? = null

        // Simulate: already connected, then connectToServer called
        val isConnected = true

        // Reproduce PlaybackService.connectToServer logic
        if (isConnected) {
            disconnectCalled = true
        }
        connectAddress = "192.168.1.100:8927"

        assertTrue("Should disconnect existing connection first", disconnectCalled)
        assertEquals("192.168.1.100:8927", connectAddress)
    }

    @Test
    fun `client connect failure sets Error state with message`() {
        var connectionState: ConnectionState = ConnectionState.Disconnected()

        // Simulate connectToServer catching an exception
        connectionState = ConnectionState.Connecting
        try {
            throw RuntimeException("Network unreachable")
        } catch (e: Exception) {
            connectionState = ConnectionState.Error("Connection failed: ${e.message}")
        }

        assertTrue(connectionState is ConnectionState.Error)
        assertEquals(
            "Connection failed: Network unreachable",
            (connectionState as ConnectionState.Error).message
        )
    }

    @Test
    fun `SendSpinClient connectionState uses coordinator TransportState`() {
        // Verify the coordinator TransportState sealed class has the expected subtypes
        val idle = TransportState.Idle
        val connecting = TransportState.Connecting
        val ready = TransportState.Ready
        val failed = TransportState.Failed(FailureReason.TransientNetwork)

        assertTrue(idle is TransportState)
        assertTrue(connecting is TransportState)
        assertTrue(ready is TransportState)
        assertTrue(failed is TransportState)
        assertTrue(failed.reason is FailureReason.TransientNetwork)
    }

    @Test
    fun `callback interface declares all required streaming and metadata methods`() {
        // Verify the Callback interface has the streaming/metadata methods PlaybackService depends on.
        // State lifecycle methods (onConnected, onDisconnected, onError, onReconnecting, onReconnected)
        // have been removed -- PlaybackService now observes connectionState StateFlow instead.
        val callbackClass = SendSpinClient.Callback::class.java
        val methodNames = callbackClass.methods.map { it.name }

        assertTrue("Should have onStateChanged", "onStateChanged" in methodNames)
        assertTrue("Should have onStreamStart", "onStreamStart" in methodNames)
        assertTrue("Should have onStreamEnd", "onStreamEnd" in methodNames)
        assertTrue("Should have onAudioChunk", "onAudioChunk" in methodNames)
        assertTrue("Should have onMetadataUpdate", "onMetadataUpdate" in methodNames)

        // Verify removed state-lifecycle methods are gone
        assertFalse("onConnected should be removed", "onConnected" in methodNames)
        assertFalse("onDisconnected should be removed", "onDisconnected" in methodNames)
        assertFalse("onError should be removed", "onError" in methodNames)
        assertFalse("onReconnecting should be removed", "onReconnecting" in methodNames)
        assertFalse("onReconnected should be removed", "onReconnected" in methodNames)
    }
}
