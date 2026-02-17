package com.sendspindroid.musicassistant.transport

import com.sendspindroid.shared.log.Log
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for MaCommandMultiplexer routing, partial results, and error handling.
 */
class MaCommandMultiplexerTest {

    private lateinit var multiplexer: MaCommandMultiplexer
    private val receivedEvents = mutableListOf<JsonObject>()

    @Before
    fun setUp() {
        mockkObject(Log)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        multiplexer = MaCommandMultiplexer()
        receivedEvents.clear()
        multiplexer.eventListener = object : MaApiTransport.EventListener {
            override fun onEvent(event: JsonObject) {
                receivedEvents.add(event)
            }
            override fun onDisconnected(reason: String) {}
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========================================================================
    // Response routing
    // ========================================================================

    @Test
    fun `registerCommand creates unique message IDs`() {
        val (id1, _) = multiplexer.registerCommand()
        val (id2, _) = multiplexer.registerCommand()
        assertNotEquals(id1, id2)
    }

    @Test
    fun `onMessage routes response to correct pending command`() = runBlocking {
        val (messageId, deferred) = multiplexer.registerCommand()

        multiplexer.onMessage("""
            {"message_id": "$messageId", "result": {"test": "value"}}
        """)

        val result = withTimeout(1000) { deferred.await() }
        assertNotNull(result)
        assertEquals(0, multiplexer.pendingCommandCount)
    }

    @Test
    fun `onMessage completes with error for error_code responses`() = runBlocking {
        val (messageId, deferred) = multiplexer.registerCommand()

        multiplexer.onMessage("""
            {"message_id": "$messageId", "error_code": "invalid_command", "details": "Unknown command"}
        """)

        try {
            withTimeout(1000) { deferred.await() }
            fail("Should have thrown")
        } catch (e: MaApiTransport.MaCommandException) {
            assertEquals("invalid_command", e.errorCode)
            assertEquals("Unknown command", e.details)
        }
        assertEquals(0, multiplexer.pendingCommandCount)
    }

    @Test
    fun `onMessage forwards events to listener for unmatched messages`() {
        multiplexer.onMessage("""
            {"event": "player_updated", "data": {"player_id": "p1"}}
        """)

        assertEquals(1, receivedEvents.size)
    }

    @Test
    fun `onMessage forwards unknown message_ids as events`() {
        multiplexer.onMessage("""
            {"message_id": "nonexistent_id", "result": {}}
        """)

        // Unknown message_id falls through to event listener
        assertEquals(1, receivedEvents.size)
    }

    // ========================================================================
    // Partial results
    // ========================================================================

    @Test
    fun `onMessage accumulates partial results`() = runBlocking {
        val (messageId, deferred) = multiplexer.registerCommand()

        // Partial batch 1
        multiplexer.onMessage("""
            {"message_id": "$messageId", "partial": true, "result": ["item1", "item2"]}
        """)
        assertFalse(deferred.isCompleted)

        // Partial batch 2
        multiplexer.onMessage("""
            {"message_id": "$messageId", "partial": true, "result": ["item3"]}
        """)
        assertFalse(deferred.isCompleted)

        // Final batch (no partial flag)
        multiplexer.onMessage("""
            {"message_id": "$messageId", "result": ["item4"]}
        """)
        assertTrue(deferred.isCompleted)

        val result = withTimeout(1000) { deferred.await() }
        // Check merged array has all 4 items
        val resultArray = result.optJsonArray("result")
        assertNotNull(resultArray)
        assertEquals(4, resultArray!!.size)
    }

    @Test
    fun `partial results cleaned up on error`() = runBlocking {
        val (messageId, deferred) = multiplexer.registerCommand()

        // Send partial
        multiplexer.onMessage("""
            {"message_id": "$messageId", "partial": true, "result": ["item1"]}
        """)

        // Error response
        multiplexer.onMessage("""
            {"message_id": "$messageId", "error_code": "server_error", "details": "Oops"}
        """)

        try {
            withTimeout(1000) { deferred.await() }
            fail("Should have thrown")
        } catch (e: MaApiTransport.MaCommandException) {
            assertEquals("server_error", e.errorCode)
        }
    }

    // ========================================================================
    // HTTP proxy responses
    // ========================================================================

    @Test
    fun `onMessage routes proxy responses`() = runBlocking {
        val (requestId, deferred) = multiplexer.registerProxyRequest()

        // Hex-encode "OK" = "4f4b"
        multiplexer.onMessage("""
            {"type": "http-proxy-response", "id": "$requestId", "status": 200, "headers": {"Content-Type": "text/plain"}, "body": "4f4b"}
        """)

        val response = withTimeout(1000) { deferred.await() }
        assertEquals(200, response.status)
        assertEquals("text/plain", response.headers["Content-Type"])
        assertEquals("OK", String(response.body, Charsets.UTF_8))
    }

    @Test
    fun `onMessage proxy response with empty body`() = runBlocking {
        val (requestId, deferred) = multiplexer.registerProxyRequest()

        multiplexer.onMessage("""
            {"type": "http-proxy-response", "id": "$requestId", "status": 204, "headers": {}, "body": ""}
        """)

        val response = withTimeout(1000) { deferred.await() }
        assertEquals(204, response.status)
        assertEquals(0, response.body.size)
    }

    // ========================================================================
    // cancelAll
    // ========================================================================

    @Test
    fun `cancelAll completes all pending commands with error`() = runBlocking {
        val (_, d1) = multiplexer.registerCommand()
        val (_, d2) = multiplexer.registerCommand()
        val (_, d3) = multiplexer.registerProxyRequest()

        multiplexer.cancelAll("test disconnect")

        assertTrue(d1.isCompleted)
        assertTrue(d2.isCompleted)
        assertTrue(d3.isCompleted)

        try {
            withTimeout(1000) { d1.await() }
            fail("Should have thrown")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("test disconnect"))
        }

        assertEquals(0, multiplexer.pendingCommandCount)
        assertEquals(0, multiplexer.pendingProxyRequestCount)
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    fun `onMessage handles malformed JSON gracefully`() {
        // Should not throw
        multiplexer.onMessage("not valid json")
        multiplexer.onMessage("")
        multiplexer.onMessage("{}")
    }

    @Test
    fun `pendingCommandCount reflects registered commands`() {
        assertEquals(0, multiplexer.pendingCommandCount)

        val (id1, _) = multiplexer.registerCommand()
        assertEquals(1, multiplexer.pendingCommandCount)

        val (id2, _) = multiplexer.registerCommand()
        assertEquals(2, multiplexer.pendingCommandCount)

        // Complete one
        multiplexer.onMessage("""{"message_id": "$id1", "result": {}}""")
        assertEquals(1, multiplexer.pendingCommandCount)
    }

    // ========================================================================
    // unregisterCommand / unregisterProxyRequest (C-11 fix)
    // ========================================================================

    @Test
    fun `unregisterCommand removes pending command from map`() {
        val (messageId, _) = multiplexer.registerCommand()
        assertEquals(1, multiplexer.pendingCommandCount)

        val removed = multiplexer.unregisterCommand(messageId)
        assertTrue("Should return true when command was found", removed)
        assertEquals(0, multiplexer.pendingCommandCount)
    }

    @Test
    fun `unregisterCommand returns false for unknown messageId`() {
        val removed = multiplexer.unregisterCommand("nonexistent-id")
        assertFalse("Should return false when command was not found", removed)
    }

    @Test
    fun `unregisterCommand returns false if command already completed`() = runBlocking {
        val (messageId, deferred) = multiplexer.registerCommand()

        // Complete the command normally
        multiplexer.onMessage("""{"message_id": "$messageId", "result": {}}""")
        assertTrue(deferred.isCompleted)

        // Now try to unregister -- should return false since already removed
        val removed = multiplexer.unregisterCommand(messageId)
        assertFalse("Should return false when command already completed", removed)
        assertEquals(0, multiplexer.pendingCommandCount)
    }

    @Test
    fun `unregisterCommand cleans up partial results`() {
        val (messageId, _) = multiplexer.registerCommand()

        // Accumulate some partial results
        multiplexer.onMessage("""
            {"message_id": "$messageId", "partial": true, "result": ["item1", "item2"]}
        """)
        assertEquals(1, multiplexer.pendingCommandCount)

        // Unregister (simulating timeout) -- should clean up partials too
        val removed = multiplexer.unregisterCommand(messageId)
        assertTrue(removed)
        assertEquals(0, multiplexer.pendingCommandCount)

        // A late-arriving response for this messageId should not crash
        // and should be forwarded to the event listener instead
        multiplexer.onMessage("""{"message_id": "$messageId", "result": ["item3"]}""")
        assertEquals(1, receivedEvents.size)
    }

    @Test
    fun `unregisterProxyRequest removes pending proxy request from map`() {
        val (requestId, _) = multiplexer.registerProxyRequest()
        assertEquals(1, multiplexer.pendingProxyRequestCount)

        val removed = multiplexer.unregisterProxyRequest(requestId)
        assertTrue("Should return true when proxy request was found", removed)
        assertEquals(0, multiplexer.pendingProxyRequestCount)
    }

    @Test
    fun `unregisterProxyRequest returns false for unknown requestId`() {
        val removed = multiplexer.unregisterProxyRequest("nonexistent-id")
        assertFalse("Should return false when proxy request was not found", removed)
    }

    @Test
    fun `simulated timeout cleans up pending command`() = runBlocking {
        // This simulates the exact pattern used in sendCommand():
        // register -> timeout -> unregister
        val (messageId, deferred) = multiplexer.registerCommand()
        assertEquals(1, multiplexer.pendingCommandCount)

        // Simulate the timeout path: withTimeout throws, then we clean up
        try {
            withTimeout(50) {
                deferred.await()  // Will never complete -> times out
            }
            fail("Should have timed out")
        } catch (_: TimeoutCancellationException) {
            // This is what the transport's catch block does:
            multiplexer.unregisterCommand(messageId)
        }

        assertEquals(
            "Pending command should be cleaned up after timeout",
            0,
            multiplexer.pendingCommandCount
        )
    }

    @Test
    fun `simulated timeout with partial results cleans up everything`() = runBlocking {
        val (messageId, deferred) = multiplexer.registerCommand()

        // Receive some partial results before timeout
        multiplexer.onMessage("""
            {"message_id": "$messageId", "partial": true, "result": ["item1"]}
        """)
        assertEquals(1, multiplexer.pendingCommandCount)

        // Simulate timeout
        try {
            withTimeout(50) {
                deferred.await()
            }
            fail("Should have timed out")
        } catch (_: TimeoutCancellationException) {
            multiplexer.unregisterCommand(messageId)
        }

        assertEquals(0, multiplexer.pendingCommandCount)

        // Late-arriving final response should not crash and goes to event listener
        multiplexer.onMessage("""{"message_id": "$messageId", "result": ["item2"]}""")
        assertEquals(1, receivedEvents.size)
    }

    @Test
    fun `multiple commands with one timeout only cleans up timed-out command`() = runBlocking {
        val (id1, d1) = multiplexer.registerCommand()
        val (id2, d2) = multiplexer.registerCommand()
        assertEquals(2, multiplexer.pendingCommandCount)

        // Command 1 times out
        try {
            withTimeout(50) { d1.await() }
            fail("Should have timed out")
        } catch (_: TimeoutCancellationException) {
            multiplexer.unregisterCommand(id1)
        }

        // Only command 1 removed; command 2 still pending
        assertEquals(1, multiplexer.pendingCommandCount)

        // Command 2 completes normally
        multiplexer.onMessage("""{"message_id": "$id2", "result": {"ok": true}}""")
        assertTrue(d2.isCompleted)
        assertEquals(0, multiplexer.pendingCommandCount)
    }
}
