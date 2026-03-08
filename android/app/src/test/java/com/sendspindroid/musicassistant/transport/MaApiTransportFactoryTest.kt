package com.sendspindroid.musicassistant.transport

import android.util.Log
import com.sendspindroid.UserSettings.ConnectionMode
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MaApiTransportFactory].
 *
 * Covers:
 * - LOCAL mode with null URL returns null (no crash)
 * - REMOTE mode fallback to WebSocket for non-webrtc URLs
 */
class MaApiTransportFactoryTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // =========================================================================
    // LOCAL with null URL returns null
    // =========================================================================

    @Test
    fun `LOCAL mode with null URL returns null`() {
        val transport = MaApiTransportFactory.create(
            connectionMode = ConnectionMode.LOCAL,
            apiUrl = null
        )
        assertNull(transport)
    }

    @Test
    fun `PROXY mode with null URL returns null`() {
        val transport = MaApiTransportFactory.create(
            connectionMode = ConnectionMode.PROXY,
            apiUrl = null
        )
        assertNull(transport)
    }

    // =========================================================================
    // REMOTE fallback to WebSocket
    // =========================================================================

    @Test
    fun `REMOTE mode with no DataChannel and non-webrtc URL falls back to WebSocket`() {
        val transport = MaApiTransportFactory.create(
            connectionMode = ConnectionMode.REMOTE,
            apiUrl = "ws://192.168.1.100:8095/ws",
            maApiDataChannel = null
        )
        assertNotNull(transport)
        assertTrue(
            "Expected MaWebSocketTransport fallback",
            transport is MaWebSocketTransport
        )
    }

    @Test
    fun `REMOTE mode with no DataChannel and webrtc URL returns null`() {
        val transport = MaApiTransportFactory.create(
            connectionMode = ConnectionMode.REMOTE,
            apiUrl = "webrtc://signaling.example.com",
            maApiDataChannel = null
        )
        assertNull(transport)
    }

    @Test
    fun `REMOTE mode with no DataChannel and null URL returns null`() {
        val transport = MaApiTransportFactory.create(
            connectionMode = ConnectionMode.REMOTE,
            apiUrl = null,
            maApiDataChannel = null
        )
        assertNull(transport)
    }

    @Test
    fun `LOCAL mode with valid URL returns MaWebSocketTransport`() {
        val transport = MaApiTransportFactory.create(
            connectionMode = ConnectionMode.LOCAL,
            apiUrl = "ws://192.168.1.100:8095/ws"
        )
        assertNotNull(transport)
        assertTrue(transport is MaWebSocketTransport)
    }
}
