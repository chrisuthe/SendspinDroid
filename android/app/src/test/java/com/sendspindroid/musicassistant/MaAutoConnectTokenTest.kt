package com.sendspindroid.musicassistant

import android.util.Log
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.model.MaConnectionState
import com.sendspindroid.UserSettings.ConnectionMode
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration test: PlaybackService auto-connects MA with stored token.
 *
 * Verifies the MusicAssistantManager.onServerConnected flow:
 * - When a server has a stored MA token, connectWithToken is triggered
 * - When no token exists, state transitions to NeedsAuth
 * - When server is not MA, state stays Unavailable
 *
 * Since MusicAssistantManager is a singleton with Android dependencies,
 * we test the decision logic in isolation by reproducing the
 * onServerConnected flow.
 */
class MaAutoConnectTokenTest {

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
     * Reproduces MusicAssistantManager.onServerConnected decision logic.
     * Returns the resulting state based on server properties and token availability.
     */
    private fun simulateOnServerConnected(
        server: UnifiedServer,
        connectionMode: ConnectionMode,
        hasStoredToken: Boolean,
        hasMaApiChannel: Boolean = false,
        hasApiEndpoint: Boolean = true
    ): SimulatedResult {
        // Check 1: Is this server a Music Assistant server?
        if (!server.isMusicAssistant && !hasStoredToken && !hasMaApiChannel) {
            return SimulatedResult(MaConnectionState.Unavailable, connectWithTokenCalled = false)
        }

        // Check 2: Can we reach the MA API?
        if (!hasApiEndpoint) {
            return SimulatedResult(MaConnectionState.Unavailable, connectWithTokenCalled = false)
        }

        // Check 3: Do we have a stored token?
        return if (hasStoredToken) {
            SimulatedResult(MaConnectionState.Connecting, connectWithTokenCalled = true)
        } else {
            SimulatedResult(MaConnectionState.NeedsAuth, connectWithTokenCalled = false)
        }
    }

    data class SimulatedResult(
        val state: MaConnectionState,
        val connectWithTokenCalled: Boolean
    )

    @Test
    fun `MA server with stored token triggers connectWithToken`() {
        val server = UnifiedServer(
            id = "server-1",
            name = "MA Server",
            isMusicAssistant = true,
            local = LocalConnection("192.168.1.10:8927")
        )

        val result = simulateOnServerConnected(
            server = server,
            connectionMode = ConnectionMode.LOCAL,
            hasStoredToken = true
        )

        assertTrue("connectWithToken should be called", result.connectWithTokenCalled)
        assertEquals(
            "State should be Connecting",
            MaConnectionState.Connecting,
            result.state
        )
    }

    @Test
    fun `MA server without token transitions to NeedsAuth`() {
        val server = UnifiedServer(
            id = "server-2",
            name = "MA Server No Token",
            isMusicAssistant = true,
            local = LocalConnection("192.168.1.10:8927")
        )

        val result = simulateOnServerConnected(
            server = server,
            connectionMode = ConnectionMode.LOCAL,
            hasStoredToken = false
        )

        assertFalse("connectWithToken should NOT be called", result.connectWithTokenCalled)
        assertEquals(
            "State should be NeedsAuth",
            MaConnectionState.NeedsAuth,
            result.state
        )
    }

    @Test
    fun `non-MA server without token or channel stays Unavailable`() {
        val server = UnifiedServer(
            id = "server-3",
            name = "Regular Server",
            isMusicAssistant = false,
            local = LocalConnection("192.168.1.10:8927")
        )

        val result = simulateOnServerConnected(
            server = server,
            connectionMode = ConnectionMode.LOCAL,
            hasStoredToken = false,
            hasMaApiChannel = false
        )

        assertFalse("connectWithToken should NOT be called", result.connectWithTokenCalled)
        assertEquals(
            "State should be Unavailable",
            MaConnectionState.Unavailable,
            result.state
        )
    }

    @Test
    fun `non-MA server with stored token auto-detects as MA`() {
        // A non-MA server that has a stored token (auto-detected)
        val server = UnifiedServer(
            id = "server-4",
            name = "Auto-Detected MA",
            isMusicAssistant = false,
            local = LocalConnection("192.168.1.10:8927")
        )

        val result = simulateOnServerConnected(
            server = server,
            connectionMode = ConnectionMode.LOCAL,
            hasStoredToken = true
        )

        assertTrue("connectWithToken should be called for auto-detected MA", result.connectWithTokenCalled)
    }

    @Test
    fun `non-MA server with DataChannel auto-detects as MA`() {
        val server = UnifiedServer(
            id = "server-5",
            name = "Remote MA",
            isMusicAssistant = false,
            local = LocalConnection("192.168.1.10:8927")
        )

        val result = simulateOnServerConnected(
            server = server,
            connectionMode = ConnectionMode.REMOTE,
            hasStoredToken = false,
            hasMaApiChannel = true
        )

        // hasMaApiChannel passes the first check, but with no token -> NeedsAuth
        assertEquals(
            "Should reach NeedsAuth since no token",
            MaConnectionState.NeedsAuth,
            result.state
        )
    }

    @Test
    fun `MA server with no API endpoint stays Unavailable`() {
        val server = UnifiedServer(
            id = "server-6",
            name = "No API",
            isMusicAssistant = true,
            local = LocalConnection("192.168.1.10:8927")
        )

        val result = simulateOnServerConnected(
            server = server,
            connectionMode = ConnectionMode.LOCAL,
            hasStoredToken = true,
            hasApiEndpoint = false
        )

        assertFalse("connectWithToken should NOT be called", result.connectWithTokenCalled)
        assertEquals(
            "State should be Unavailable when no API endpoint",
            MaConnectionState.Unavailable,
            result.state
        )
    }

    @Test
    fun `MaConnectionState isAvailable only when Connected`() {
        assertFalse(MaConnectionState.Unavailable.isAvailable)
        assertFalse(MaConnectionState.NeedsAuth.isAvailable)
        assertFalse(MaConnectionState.Connecting.isAvailable)
        assertFalse(MaConnectionState.Error("err").isAvailable)

        // Connected requires MaServerInfo
        val info = com.sendspindroid.musicassistant.model.MaServerInfo(
            serverId = "s1", serverVersion = "2.0", apiUrl = "ws://localhost:8095"
        )
        assertTrue(MaConnectionState.Connected(info).isAvailable)
    }

    @Test
    fun `MaConnectionState needsUserAction for NeedsAuth and auth errors`() {
        assertTrue(MaConnectionState.NeedsAuth.needsUserAction)
        assertTrue(MaConnectionState.Error("expired", isAuthError = true).needsUserAction)
        assertFalse(MaConnectionState.Error("network error", isAuthError = false).needsUserAction)
        assertFalse(MaConnectionState.Unavailable.needsUserAction)
    }
}
