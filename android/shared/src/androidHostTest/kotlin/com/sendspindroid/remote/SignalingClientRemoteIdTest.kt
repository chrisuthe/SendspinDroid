package com.sendspindroid.remote

import com.sendspindroid.shared.log.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for Remote ID normalization in [SignalingClient].
 *
 * MA registers the 26-char base32 wire form (no hyphens); the 8-5-5-8
 * hyphenated form a user copies from the MA UI (e.g.
 * "4OPRUGEE-37BPK-SURPJ-BTU5N6SM") is display-only. The client must normalize
 * to the wire form before validating and sending, so a pasted/stored display
 * ID — from any path — connects rather than being rejected as malformed.
 */
class SignalingClientRemoteIdTest {

    // Real MA Remote ID in display form (8-5-5-8 hyphen grouping).
    private val displayId = "4OPRUGEE-37BPK-SURPJ-BTU5N6SM"
    private val wireId = "4OPRUGEE37BPKSURPJBTU5N6SM" // 26 chars, hyphens stripped

    private lateinit var httpClient: HttpClient

    @Before
    fun setUp() {
        mockkObject(Log)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any(), any()) } returns 0
        httpClient = HttpClient { install(WebSockets) }
    }

    @After
    fun tearDown() {
        httpClient.close()
        unmockkAll()
    }

    // --- normalizeRemoteId (pure) ---

    @Test
    fun `strips hyphens from display id`() {
        assertEquals(wireId, SignalingClient.normalizeRemoteId(displayId))
    }

    @Test
    fun `display id normalizes to exactly 26 chars`() {
        assertEquals(26, SignalingClient.normalizeRemoteId(displayId).length)
    }

    @Test
    fun `uppercases lowercase input`() {
        assertEquals("ABC123", SignalingClient.normalizeRemoteId("abc123"))
    }

    @Test
    fun `strips surrounding and internal whitespace`() {
        assertEquals("ABCDEF", SignalingClient.normalizeRemoteId("  ab cd ef "))
    }

    @Test
    fun `leaves an already-canonical id unchanged`() {
        assertEquals(wireId, SignalingClient.normalizeRemoteId(wireId))
    }

    // --- validation through the constructor/connect path ---

    @Test
    fun `hyphenated display id is not rejected as invalid format`() {
        val client = SignalingClient(
            remoteId = displayId,
            signalingUrl = "wss://localhost:0/ws", // unreachable; connection will fail later, that's fine
            httpClient = httpClient,
            ownsHttpClient = false
        )
        client.connect()
        val state = client.state.value
        // The only synchronous failure in connect() is the malformed-ID check.
        // A later async connection failure carries a different message, so this
        // assertion is race-free.
        assertFalse(
            "Hyphenated Remote ID must pass validation after normalization",
            state is SignalingClient.State.Error &&
                (state as SignalingClient.State.Error).message.contains("Invalid Remote ID")
        )
        client.destroy()
    }

    @Test
    fun `genuinely malformed id is still rejected`() {
        val client = SignalingClient(
            remoteId = "TOO-SHORT",
            signalingUrl = "wss://localhost:0/ws",
            httpClient = httpClient,
            ownsHttpClient = false
        )
        client.connect()
        val state = client.state.value
        assertTrue(
            "A short/malformed ID must be rejected as invalid format",
            state is SignalingClient.State.Error &&
                (state as SignalingClient.State.Error).message.contains("Invalid Remote ID")
        )
        client.destroy()
    }
}
