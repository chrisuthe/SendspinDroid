package com.sendspindroid.e2e

import com.sendspindroid.remote.RemoteConnection
import com.sendspindroid.sendspin.SendSpinClient
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

/**
 * E2E Test 5: Remote connect via WebRTC
 *
 * Tests the remote connection flow: Remote ID -> signaling -> DataChannel ->
 * handshake -> audio.
 *
 * What IS testable here (unit test level):
 * - Remote ID validation and parsing (various formats)
 * - SendSpinClient.connectRemote() state transitions
 * - Transport injection with REMOTE mode
 * - Full protocol handshake over fake transport
 * - Audio chunk delivery through remote transport
 *
 * What requires instrumented/manual testing:
 * - WebRTC PeerConnection and DataChannel creation
 * - ICE candidate exchange via signaling server
 * - SDP offer/answer negotiation
 * - Real network traversal (STUN/TURN)
 * - MA API DataChannel establishment
 *
 * Manual test steps for full remote connect:
 * 1. Get Remote ID from Music Assistant Settings -> Remote Access
 * 2. Enter Remote ID in SendSpinDroid (either typed or QR scanned)
 * 3. Verify signaling connection to app.music-assistant.io
 * 4. Verify DataChannel opens (logs: "Remote connection established")
 * 5. Verify handshake completes (logs: "Connected to <server>")
 * 6. Start playback -> verify audio streams over DataChannel
 * 7. Verify MA API features available (browse library, queue)
 */
class RemoteConnectWebRTCTest : E2ETestBase() {

    // ========== Remote ID Validation Tests ==========

    @Test
    fun `valid 26-char alphanumeric Remote ID accepted`() {
        assertTrue(RemoteConnection.isValidRemoteId("VVPN3TLP34YMGIZDINCEKQKSIR"))
    }

    @Test
    fun `too short Remote ID rejected`() {
        assertFalse(RemoteConnection.isValidRemoteId("VVPN3TLP34"))
    }

    @Test
    fun `too long Remote ID rejected`() {
        assertFalse(RemoteConnection.isValidRemoteId("VVPN3TLP34YMGIZDINCEKQKSIRX"))
    }

    @Test
    fun `lowercase Remote ID rejected`() {
        assertFalse(RemoteConnection.isValidRemoteId("vvpn3tlp34ymgizdincekqksir"))
    }

    @Test
    fun `Remote ID with special chars rejected`() {
        assertFalse(RemoteConnection.isValidRemoteId("VVPN3-TLP34-YMGIZ-DINCE-QK"))
    }

    // ========== Remote ID Parsing Tests ==========

    @Test
    fun `parse raw 26-char ID`() {
        val result = RemoteConnection.parseRemoteId("VVPN3TLP34YMGIZDINCEKQKSIR")
        assertEquals("VVPN3TLP34YMGIZDINCEKQKSIR", result)
    }

    @Test
    fun `parse formatted ID with dashes`() {
        val result = RemoteConnection.parseRemoteId("VVPN3-TLP34-YMGIZ-DINCE-KQKSI-R")
        assertEquals("VVPN3TLP34YMGIZDINCEKQKSIR", result)
    }

    @Test
    fun `parse lowercase ID (case insensitive)`() {
        val result = RemoteConnection.parseRemoteId("vvpn3tlp34ymgizdincekqksir")
        assertEquals("VVPN3TLP34YMGIZDINCEKQKSIR", result)
    }

    @Test
    fun `parse Remote ID from MA URL`() {
        val result = RemoteConnection.parseRemoteId(
            "https://app.music-assistant.io/remote/VVPN3TLP34YMGIZDINCEKQKSIR"
        )
        assertEquals("VVPN3TLP34YMGIZDINCEKQKSIR", result)
    }

    @Test
    fun `parse Remote ID from URL with query param`() {
        val result = RemoteConnection.parseRemoteId(
            "https://example.com/?remote_id=VVPN3TLP34YMGIZDINCEKQKSIR"
        )
        assertEquals("VVPN3TLP34YMGIZDINCEKQKSIR", result)
    }

    @Test
    fun `parse Remote ID from URL with id param`() {
        val result = RemoteConnection.parseRemoteId(
            "https://example.com/?id=VVPN3TLP34YMGIZDINCEKQKSIR"
        )
        assertEquals("VVPN3TLP34YMGIZDINCEKQKSIR", result)
    }

    @Test
    fun `parse returns null for invalid input`() {
        assertNull(RemoteConnection.parseRemoteId("not-a-valid-id"))
        assertNull(RemoteConnection.parseRemoteId(""))
        assertNull(RemoteConnection.parseRemoteId("12345"))
    }

    @Test
    fun `formatRemoteId adds dashes for readability`() {
        val formatted = RemoteConnection.formatRemoteId("VVPN3TLP34YMGIZDINCEKQKSIR")
        assertEquals("VVPN3-TLP34-YMGIZ-DINCE-KQKSI-R", formatted)
    }

    // ========== Remote Connection Flow Tests ==========

    @Test
    fun `remote connect sets mode to REMOTE and stores remote ID`() {
        injectTransportAndConnect(
            mode = SendSpinClient.ConnectionMode.REMOTE,
            remoteId = "VVPN3TLP34YMGIZDINCEKQKSIR",
            serverAddress = null,
            serverPath = null
        )

        assertEquals(SendSpinClient.ConnectionMode.REMOTE, client.getConnectionMode())
        assertEquals("VVPN3TLP34YMGIZDINCEKQKSIR", client.getRemoteId())
    }

    @Test
    fun `remote handshake completes with server hello`() {
        injectTransportAndConnect(
            mode = SendSpinClient.ConnectionMode.REMOTE,
            remoteId = "VVPN3TLP34YMGIZDINCEKQKSIR",
            serverAddress = null,
            serverPath = null
        )

        // Simulate transport becoming connected
        fakeTransport.simulateConnected()

        // Client should send hello (no auth needed for remote mode)
        assertTrue("Should send client/hello in remote mode",
            fakeServer.clientSentHello())

        // Server responds with hello
        fakeServer.sendServerHello()

        // Should be connected
        assertTrue("Should be connected after remote handshake", client.isConnected)
        verify { mockCallback.onConnected("TestServer") }
    }

    @Test
    fun `remote audio chunk delivery works through fake transport`() {
        injectTransportAndConnect(
            mode = SendSpinClient.ConnectionMode.REMOTE,
            remoteId = "VVPN3TLP34YMGIZDINCEKQKSIR",
            serverAddress = null,
            serverPath = null
        )
        fakeServer.completeHandshake()

        // Start stream
        fakeServer.sendStreamStart()
        verify { mockCallback.onStreamStart(any(), any(), any(), any(), any()) }

        // Send audio chunk
        val audioData = fakeServer.generateSilence(100)
        fakeServer.sendAudioChunk(1_000_000L, audioData)

        verify { mockCallback.onAudioChunk(1_000_000L, any()) }
    }

    @Test
    fun `remote disconnect is clean`() {
        injectTransportAndConnect(
            mode = SendSpinClient.ConnectionMode.REMOTE,
            remoteId = "VVPN3TLP34YMGIZDINCEKQKSIR",
            serverAddress = null,
            serverPath = null
        )
        fakeServer.completeHandshake()

        client.disconnect()

        assertFalse("Should be disconnected", client.isConnected)
        verify {
            mockCallback.onDisconnected(wasUserInitiated = true, wasReconnectExhausted = false)
        }
    }

    @Test
    fun `remote reconnection stores remote ID for retry`() {
        injectTransportAndConnect(
            mode = SendSpinClient.ConnectionMode.REMOTE,
            remoteId = "VVPN3TLP34YMGIZDINCEKQKSIR",
            serverAddress = null,
            serverPath = null
        )
        fakeServer.completeHandshake()

        // Trigger failure
        fakeTransport.simulateFailure(
            error = java.net.SocketException("Connection reset"),
            isRecoverable = true
        )

        // Should attempt reconnection (remote ID is saved)
        verify { mockCallback.onReconnecting(1, any()) }
    }
}
