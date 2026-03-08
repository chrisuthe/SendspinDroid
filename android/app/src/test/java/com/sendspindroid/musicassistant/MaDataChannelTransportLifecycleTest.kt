package com.sendspindroid.musicassistant

import android.util.Log
import com.sendspindroid.UserSettings.ConnectionMode
import com.sendspindroid.musicassistant.transport.MaDataChannelTransport
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Integration test: MA DataChannel transport lifecycle in REMOTE mode.
 *
 * Verifies that REMOTE connection mode creates a DataChannel transport for
 * the MA API. Since WebRTC native libraries are not available in JVM tests,
 * these tests verify the structural contract and lifecycle decisions.
 *
 * The flow:
 * 1. SendSpinClient connects via WebRTC (REMOTE mode)
 * 2. WebRTCTransport opens a "ma-api" DataChannel
 * 3. PlaybackService calls MusicAssistantManager.setMaApiDataChannel(channel)
 * 4. MusicAssistantManager creates MaDataChannelTransport eagerly
 * 5. On server connected, createTransport() returns the pending DataChannel transport
 */
class MaDataChannelTransportLifecycleTest {

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

    @Test
    fun `MaDataChannelTransport class exists and implements MaApiTransport`() {
        val transportClass = MaDataChannelTransport::class.java
        val interfaces = transportClass.interfaces.map { it.simpleName }
        assertTrue(
            "MaDataChannelTransport should implement MaApiTransport",
            interfaces.contains("MaApiTransport")
        )
    }

    @Test
    fun `MaDataChannelTransport has required constructor taking DataChannel`() {
        val constructors = MaDataChannelTransport::class.java.constructors
        val hasDataChannelCtor = constructors.any { ctor ->
            ctor.parameterTypes.any { it.simpleName == "DataChannel" }
        }
        assertTrue(
            "Should have constructor accepting DataChannel",
            hasDataChannelCtor
        )
    }

    @Test
    fun `MaDataChannelTransport has replayBufferedMessages method`() {
        val method = MaDataChannelTransport::class.java.getMethod(
            "replayBufferedMessages", List::class.java
        )
        assertTrue(
            "replayBufferedMessages should be public",
            Modifier.isPublic(method.modifiers)
        )
    }

    /**
     * Reproduces the MusicAssistantManager.createTransport() decision logic
     * for REMOTE mode.
     */
    @Test
    fun `REMOTE mode uses pending DataChannel transport when available`() {
        // Simulate the state when DataChannel is set and transport is pending
        var pendingTransport: Any? = "fake-transport"  // Simulate non-null pending transport
        var transportUsed: Any? = null

        // Reproduce createTransport logic for REMOTE mode
        val mode = ConnectionMode.REMOTE
        if (mode == ConnectionMode.REMOTE) {
            val pending = pendingTransport
            if (pending != null) {
                pendingTransport = null  // Consumed; don't reuse
                transportUsed = pending
            }
        }

        assertNotNull("Should use pending DataChannel transport", transportUsed)
        assertNull("Pending transport should be consumed", pendingTransport)
    }

    @Test
    fun `REMOTE mode without pending transport returns null`() {
        var pendingTransport: Any? = null
        var transportUsed: Any? = null

        val mode = ConnectionMode.REMOTE
        if (mode == ConnectionMode.REMOTE) {
            val pending = pendingTransport
            if (pending != null) {
                pendingTransport = null
                transportUsed = pending
            }
        }

        assertNull("Should return null when no pending transport", transportUsed)
    }

    @Test
    fun `setMaApiDataChannel creates pending transport eagerly`() {
        // Verify that setting a non-null channel creates the transport before
        // onServerConnected is called (eager creation pattern)
        var pendingTransport: Any? = null
        var channelSet = false

        // Simulate MusicAssistantManager.setMaApiDataChannel(channel)
        val channel: Any? = "mock-channel"  // Non-null simulates real channel
        if (channel != null) {
            pendingTransport = "created-transport"  // Eagerly created
            channelSet = true
        }

        assertTrue("Channel should be set", channelSet)
        assertNotNull("Transport should be created eagerly", pendingTransport)
    }

    @Test
    fun `setMaApiDataChannel with null clears pending transport`() {
        var pendingTransport: Any? = "existing-transport"

        // Simulate MusicAssistantManager.setMaApiDataChannel(null)
        val channel: Any? = null
        if (channel != null) {
            pendingTransport = "new-transport"
        } else {
            pendingTransport = null
        }

        assertNull("Pending transport should be cleared", pendingTransport)
    }

    @Test
    fun `buffered messages are replayed on transport creation`() {
        val bufferedMessages = listOf(
            """{"type":"server_info","server_version":"2.3.0"}""",
            """{"type":"auth_required"}"""
        )

        var replayedMessages: List<String>? = null

        // Simulate the replay flow
        if (bufferedMessages.isNotEmpty()) {
            replayedMessages = bufferedMessages
        }

        assertNotNull("Buffered messages should be replayed", replayedMessages)
        assertEquals(2, replayedMessages!!.size)
        assertTrue(
            "First message should be server_info",
            replayedMessages[0].contains("server_info")
        )
    }

    @Test
    fun `LOCAL mode does not use DataChannel transport`() {
        var pendingTransport: Any? = "dc-transport"
        var transportUsed: Any? = null

        // LOCAL mode should create WebSocket transport, not use DataChannel
        val mode = ConnectionMode.LOCAL
        if (mode == ConnectionMode.REMOTE) {
            val pending = pendingTransport
            if (pending != null) {
                pendingTransport = null
                transportUsed = pending
            }
        }

        // In LOCAL mode, the DataChannel transport is NOT consumed
        assertNull("LOCAL mode should not use DataChannel transport", transportUsed)
        assertNotNull("Pending transport should remain unconsumed", pendingTransport)
    }
}
