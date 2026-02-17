package com.sendspindroid.sendspin.transport

import com.sendspindroid.shared.log.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests for WebSocketTransport lifecycle, specifically verifying the
 * close() vs destroy() contract that is critical for avoiding HttpClient leaks.
 *
 * Bug C-02: disconnect() was calling close() which does NOT release the
 * underlying Ktor HttpClient. Only destroy() performs full cleanup.
 */
class WebSocketTransportTest {

    @Before
    fun setUp() {
        mockkObject(Log)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Verifies that close() does NOT close the HttpClient.
     * This is intentional -- close() only cancels the WebSocket session coroutine,
     * allowing the HttpClient to be reused if needed.
     */
    @Test
    fun `close does not close HttpClient`() {
        val client = spyk(HttpClient { install(WebSockets) })
        val transport = WebSocketTransport(
            address = "127.0.0.1:8927",
            path = "/sendspin",
            httpClient = client
        )

        transport.close(1000, "test close")

        verify(exactly = 0) { client.close() }
    }

    /**
     * Verifies that destroy() DOES close the HttpClient, releasing the
     * underlying engine's connection pool and threads.
     *
     * This is the fix for bug C-02: SendSpinClient.disconnect() must call
     * destroy() (not close()) to avoid leaking the HttpClient.
     */
    @Test
    fun `destroy closes HttpClient`() {
        val client = spyk(HttpClient { install(WebSockets) })
        val transport = WebSocketTransport(
            address = "127.0.0.1:8927",
            path = "/sendspin",
            httpClient = client
        )

        transport.destroy()

        verify(exactly = 1) { client.close() }
    }

    /**
     * Verifies that destroy() transitions state to Closed.
     */
    @Test
    fun `destroy sets state to Closed`() {
        val client = spyk(HttpClient { install(WebSockets) })
        val transport = WebSocketTransport(
            address = "127.0.0.1:8927",
            path = "/sendspin",
            httpClient = client
        )

        transport.destroy()

        assert(transport.state == TransportState.Closed) {
            "Expected state Closed but got ${transport.state}"
        }
    }

    /**
     * Verifies that close() is safe to call before connect() is ever called.
     * The outgoing channel is null in this state so the Close sentinel trySend
     * is simply skipped (M-02 fix must not crash on null channel).
     */
    @Test
    fun `close before connect does not crash`() {
        val client = spyk(HttpClient { install(WebSockets) })
        val transport = WebSocketTransport(
            address = "127.0.0.1:8927",
            path = "/sendspin",
            httpClient = client
        )

        // Should not throw
        transport.close(1000, "pre-connect close")
    }

    /**
     * Verifies that close() can be called multiple times without crashing.
     * The second call has a null channel and null job, both handled gracefully.
     */
    @Test
    fun `close is idempotent`() {
        val client = spyk(HttpClient { install(WebSockets) })
        val transport = WebSocketTransport(
            address = "127.0.0.1:8927",
            path = "/sendspin",
            httpClient = client
        )

        transport.close(1000, "first close")
        transport.close(1001, "second close")
        // No exception means success
    }
}
