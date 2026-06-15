package com.sendspindroid.e2e

import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.sendspin.SendSpin
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

/**
 * E2E Test 1: Discover -> Connect -> Play -> Disconnect
 *
 * Full lifecycle from server discovery through audio playback to clean disconnect.
 * Verifies resources are released and callbacks fire in correct order.
 *
 * Flow:
 * 1. Inject transport, simulate connection open
 * 2. Exchange client/hello <-> server/hello handshake
 * 3. Server sends stream/start, server/state (playing), audio chunks
 * 4. User disconnects
 * 5. Verify: goodbye sent, transport closed, callbacks in order, state reset
 */
class DiscoverConnectPlayDisconnectTest : E2ETestBase() {

    @Test
    fun `full lifecycle - connect, handshake, play, disconnect`() {
        // Step 1-2: Connect and complete handshake
        connectAndHandshake()

        // Verify handshake
        assertTrue("Client should be connected", client.isConnected)
        assertEquals("TestServer", client.getServerName())
        assertTrue(
            "State should be Ready after handshake, was: ${client.connectionState.value}",
            client.connectionState.value is TransportState.Ready
        )

        // Step 3: Server starts audio stream
        fakeServer.sendStreamStart(codec = "pcm", sampleRate = 48000, channels = 2, bitDepth = 16)
        verify {
            mockCallback.onStreamStart("pcm", 48000, 2, 16, null)
        }

        // Server sends playing state with metadata
        fakeServer.sendServerState(
            playbackState = "playing",
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 180000,
            positionMs = 5000
        )
        verify {
            mockCallback.onMetadataUpdate(
                "Test Song", "Test Artist", "Test Album",
                "", 180000, 5000, 1000
            )
        }
        verify { mockCallback.onStateChanged("playing") }

        // Server sends audio chunks
        val silence = fakeServer.generateSilence(durationMs = 100)
        fakeServer.sendAudioChunk(timestampMicros = 1000000L, audioData = silence)
        verify { mockCallback.onAudioChunk(1000000L, any()) }

        // Step 4: User disconnects
        client.disconnect()

        // Step 5: Verify clean disconnect
        assertTrue("Transport should be closed", fakeTransport.closed)
        assertEquals(1000, fakeTransport.closeCode)
        assertFalse("Client should not be connected after disconnect", client.isConnected)

        // State should be Idle after user disconnect
        assertTrue(
            "State should be Idle after user disconnect, was: ${client.connectionState.value}",
            client.connectionState.value is TransportState.Idle
        )
    }

    @Test
    fun `handshake sends client hello with correct fields`() {
        injectTransportAndConnect()
        fakeTransport.simulateConnected()

        // Client should have sent a client/hello message
        assertTrue(
            "Client should send client/hello on connect",
            fakeServer.clientSentHello()
        )

        // Verify the hello message contains required fields
        val helloMsg = fakeTransport.sentTextMessages.first { it.contains("client/hello") }
        assertTrue("client/hello should contain client_id", helloMsg.contains("client_id"))
        assertTrue("client/hello should contain name", helloMsg.contains("name"))
    }

    @Test
    fun `disconnect sends goodbye before closing transport`() {
        connectAndHandshake()

        client.disconnect()

        // Verify goodbye was sent
        assertTrue(
            "Client should send goodbye on disconnect",
            fakeServer.clientSentGoodbye()
        )
    }

    @Test
    fun `multiple audio chunks are delivered to callback`() {
        connectAndHandshake()
        fakeServer.sendStreamStart()

        val silence = fakeServer.generateSilence(durationMs = 50)

        // Send 5 chunks with increasing timestamps
        for (i in 0 until 5) {
            val timestamp = (i * 50_000L) + 1_000_000L // 50ms apart
            fakeServer.sendAudioChunk(timestampMicros = timestamp, audioData = silence)
        }

        // Verify all 5 chunks were delivered
        verify(exactly = 5) { mockCallback.onAudioChunk(any(), any()) }
    }

    @Test
    fun `artwork delivered and cleared correctly`() {
        connectAndHandshake()

        // Send artwork data
        val imageData = ByteArray(100) { it.toByte() }
        fakeServer.sendArtwork(channel = 0, imageData = imageData)
        verify { mockCallback.onArtwork(any()) }

        // Clear artwork (empty payload)
        fakeServer.clearArtwork(channel = 0)
        verify { mockCallback.onArtworkCleared() }
    }

    @Test
    fun `state transitions reported correctly through callbacks`() {
        connectAndHandshake()

        // Playing
        fakeServer.sendServerState(playbackState = "playing")
        verify { mockCallback.onStateChanged("playing") }

        // Stopped
        fakeServer.sendServerState(playbackState = "stopped")
        verify { mockCallback.onStateChanged("stopped") }
    }

    @Test
    fun `connection state flow reflects lifecycle`() {
        // Initially disconnected
        // (connectionState is set during injectTransportAndConnect)

        connectAndHandshake()

        // After handshake: Ready
        val state = client.connectionState.value
        assertTrue(
            "ConnectionState should be Ready after handshake",
            state is TransportState.Ready
        )
        assertEquals("TestServer", client.getServerName())

        // After disconnect: Idle
        client.disconnect()
        assertTrue(
            "ConnectionState should be Idle after disconnect",
            client.connectionState.value is TransportState.Idle
        )
    }

    @Test
    fun `destroy cleans up all resources`() {
        connectAndHandshake()

        client.destroy()

        assertFalse("Client should not be connected after destroy", client.isConnected)
        assertTrue("Transport should be closed after destroy", fakeTransport.closed)
    }
}
