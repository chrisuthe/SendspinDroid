package com.sendspindroid.e2e

import com.sendspindroid.sendspin.SendSpinClient
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

/**
 * E2E Test 6: Proxy connect with auth token
 *
 * Tests the full proxy connection flow:
 * 1. Transport connects to proxy URL (wss://domain.com/sendspin)
 * 2. Client sends auth message with token and client_id
 * 3. Server responds with auth_ok
 * 4. Auth-ack consumed (not forwarded to protocol handler)
 * 5. Client sends client/hello
 * 6. Server responds with server/hello
 * 7. Handshake complete, audio streams
 *
 * What IS testable here:
 * - Auth message construction and sending
 * - Auth-ack consumption (not forwarded as protocol message)
 * - Auth failure handling
 * - Post-auth handshake flow
 * - Audio delivery through proxy transport
 *
 * What requires instrumented/manual testing:
 * - TLS/SSL connection to real proxy server
 * - Nginx/Traefik/Caddy reverse proxy behavior
 * - Bearer token header injection by ProxyWebSocketTransport
 * - Real certificate validation
 */
class ProxyConnectAuthTest : E2ETestBase() {

    @Test
    fun `proxy connect sends auth message on transport open`() {
        injectTransportAndConnect(
            mode = SendSpinClient.ConnectionMode.PROXY,
            serverAddress = "https://ma.example.com/sendspin",
            serverPath = null,
            authToken = "test-auth-token-12345"
        )

        // Simulate transport connected
        fakeTransport.simulateConnected()

        // Client should send auth message (not client/hello yet)
        val authMessages = fakeTransport.findSentMessages { it.contains("\"type\":\"auth\"") }
        assertTrue("Should send auth message on connect", authMessages.isNotEmpty())

        // Auth message should contain token and client_id
        val authMsg = authMessages.first()
        assertTrue("Auth message should contain token", authMsg.contains("test-auth-token-12345"))
        assertTrue("Auth message should contain client_id", authMsg.contains("client_id"))

        // Should NOT have sent client/hello yet (waiting for auth_ok)
        assertFalse("Should not send client/hello before auth_ok",
            fakeServer.clientSentHello())
    }

    @Test
    fun `auth-ack triggers client hello`() {
        injectTransportAndConnect(
            mode = SendSpinClient.ConnectionMode.PROXY,
            serverAddress = "https://ma.example.com/sendspin",
            serverPath = null,
            authToken = "test-auth-token"
        )
        fakeTransport.simulateConnected()

        // Clear sent messages to track what happens after auth_ok
        fakeTransport.clearRecordedMessages()

        // Server sends auth_ok
        fakeServer.sendAuthOk()

        // Now client should send client/hello
        assertTrue("Should send client/hello after auth_ok",
            fakeServer.clientSentHello())
    }

    @Test
    fun `auth-ack is consumed and not forwarded to protocol handler`() {
        injectTransportAndConnect(
            mode = SendSpinClient.ConnectionMode.PROXY,
            serverAddress = "https://ma.example.com/sendspin",
            serverPath = null,
            authToken = "test-auth-token"
        )
        fakeTransport.simulateConnected()

        // Send auth_ok - this should be consumed
        fakeServer.sendAuthOk()

        // Handshake should NOT be complete yet (auth_ok is not server/hello)
        val handshakeComplete: Boolean = getField(
            client, "handshakeComplete",
            clazz = SendSpinClient::class.java.superclass
        )
        assertFalse("Handshake should not complete from auth_ok alone", handshakeComplete)
    }

    @Test
    fun `full proxy handshake completes after auth and hello exchange`() {
        injectTransportAndConnect(
            mode = SendSpinClient.ConnectionMode.PROXY,
            serverAddress = "https://ma.example.com/sendspin",
            serverPath = null,
            authToken = "test-auth-token"
        )
        fakeTransport.simulateConnected()

        // Step 1: auth_ok
        fakeServer.sendAuthOk()

        // Step 2: server/hello (after client/hello was sent)
        fakeServer.sendServerHello()

        // Should be connected now
        assertTrue("Should be connected after proxy handshake", client.isConnected)
        verify { mockCallback.onConnected("TestServer") }
    }

    @Test
    fun `auth failure triggers error and disconnect`() {
        injectTransportAndConnect(
            mode = SendSpinClient.ConnectionMode.PROXY,
            serverAddress = "https://ma.example.com/sendspin",
            serverPath = null,
            authToken = "bad-token"
        )
        fakeTransport.simulateConnected()

        // Server rejects auth
        fakeServer.sendAuthFailed("Invalid token")

        // Should report error
        verify { mockCallback.onError(any()) }
    }

    @Test
    fun `proxy audio delivery works after successful auth`() {
        injectTransportAndConnect(
            mode = SendSpinClient.ConnectionMode.PROXY,
            serverAddress = "https://ma.example.com/sendspin",
            serverPath = null,
            authToken = "good-token"
        )
        fakeTransport.simulateConnected()
        fakeServer.sendAuthOk()
        fakeServer.sendServerHello()

        // Start audio stream
        fakeServer.sendStreamStart()
        verify { mockCallback.onStreamStart(any(), any(), any(), any(), any()) }

        // Send audio
        val audioData = fakeServer.generateSilence(100)
        fakeServer.sendAudioChunk(2_000_000L, audioData)
        verify { mockCallback.onAudioChunk(2_000_000L, any()) }
    }

    @Test
    fun `proxy reconnection preserves auth token`() {
        injectTransportAndConnect(
            mode = SendSpinClient.ConnectionMode.PROXY,
            serverAddress = "https://ma.example.com/sendspin",
            serverPath = null,
            authToken = "persistent-token"
        )
        fakeTransport.simulateConnected()
        fakeServer.sendAuthOk()
        fakeServer.sendServerHello()

        // Verify connected
        assertTrue(client.isConnected)

        // Simulate failure to trigger reconnection
        fakeTransport.simulateFailure(
            error = java.net.SocketException("Connection reset"),
            isRecoverable = true
        )

        // Should attempt reconnection (auth token and address are preserved)
        verify { mockCallback.onReconnecting(1, any()) }

        // Connection mode should still be PROXY
        assertEquals(SendSpinClient.ConnectionMode.PROXY, client.getConnectionMode())
    }

    @Test
    fun `proxy mode is set correctly`() {
        injectTransportAndConnect(
            mode = SendSpinClient.ConnectionMode.PROXY,
            serverAddress = "https://ma.example.com/sendspin",
            serverPath = null,
            authToken = "test-token"
        )

        assertEquals(SendSpinClient.ConnectionMode.PROXY, client.getConnectionMode())
    }

    @Test
    fun `metadata delivery works through proxy connection`() {
        // Full flow: auth -> handshake -> metadata
        injectTransportAndConnect(
            mode = SendSpinClient.ConnectionMode.PROXY,
            serverAddress = "https://ma.example.com/sendspin",
            serverPath = null,
            authToken = "good-token"
        )
        fakeTransport.simulateConnected()
        fakeServer.sendAuthOk()
        fakeServer.sendServerHello()

        // Server sends metadata
        fakeServer.sendServerState(
            playbackState = "playing",
            title = "Proxy Track",
            artist = "Remote Artist",
            album = "Cloud Album",
            durationMs = 200000
        )

        verify {
            mockCallback.onMetadataUpdate(
                "Proxy Track", "Remote Artist", "Cloud Album",
                "", 200000, 0, 1000
            )
        }
    }
}
