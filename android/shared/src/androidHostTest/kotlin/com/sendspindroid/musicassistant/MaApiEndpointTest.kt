package com.sendspindroid.musicassistant

import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.ProxyConnection
import com.sendspindroid.model.RemoteConnection
import com.sendspindroid.model.UnifiedServer
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for MaApiEndpoint URL derivation.
 */
class MaApiEndpointTest {

    // ========================================================================
    // LOCAL mode
    // ========================================================================

    @Test
    fun `deriveUrl LOCAL mode with local connection`() {
        val server = makeServer(local = LocalConnection("192.168.1.100:4421"))
        val url = MaApiEndpoint.deriveUrl(server, MaConnectionMode.LOCAL)
        assertEquals("ws://192.168.1.100:8095/ws", url)
    }

    @Test
    fun `deriveUrl LOCAL mode with custom port`() {
        val server = makeServer(local = LocalConnection("192.168.1.100:4421"))
        val url = MaApiEndpoint.deriveUrl(server, MaConnectionMode.LOCAL, defaultPort = 9090)
        assertEquals("ws://192.168.1.100:9090/ws", url)
    }

    @Test
    fun `deriveUrl LOCAL mode strips port from address`() {
        val server = makeServer(local = LocalConnection("10.0.0.5:4421"))
        val url = MaApiEndpoint.deriveUrl(server, MaConnectionMode.LOCAL)
        assertEquals("ws://10.0.0.5:8095/ws", url)
    }

    @Test
    fun `deriveUrl LOCAL mode returns null without local connection`() {
        val server = makeServer(proxy = ProxyConnection("https://proxy.example.com/sendspin", "token"))
        val url = MaApiEndpoint.deriveUrl(server, MaConnectionMode.LOCAL)
        assertNull(url)
    }

    // ========================================================================
    // PROXY mode
    // ========================================================================

    @Test
    fun `deriveUrl PROXY mode with https URL`() {
        val server = makeServer(proxy = ProxyConnection("https://music.example.com/sendspin", "token"))
        val url = MaApiEndpoint.deriveUrl(server, MaConnectionMode.PROXY)
        assertEquals("wss://music.example.com/ws", url)
    }

    @Test
    fun `deriveUrl PROXY mode with http URL`() {
        val server = makeServer(proxy = ProxyConnection("http://local.proxy.com/sendspin", "token"))
        val url = MaApiEndpoint.deriveUrl(server, MaConnectionMode.PROXY)
        assertEquals("ws://local.proxy.com/ws", url)
    }

    @Test
    fun `deriveUrl PROXY mode strips trailing slash`() {
        // Trailing slash (no /sendspin suffix) — trimEnd('/') strips the slash
        val server = makeServer(proxy = ProxyConnection("https://music.example.com/", "token"))
        val url = MaApiEndpoint.deriveUrl(server, MaConnectionMode.PROXY)
        assertEquals("wss://music.example.com/ws", url)
    }

    @Test
    fun `deriveUrl PROXY mode URL without sendspin suffix`() {
        val server = makeServer(proxy = ProxyConnection("https://music.example.com", "token"))
        val url = MaApiEndpoint.deriveUrl(server, MaConnectionMode.PROXY)
        assertEquals("wss://music.example.com/ws", url)
    }

    @Test
    fun `deriveUrl PROXY mode returns null without proxy connection`() {
        val server = makeServer(local = LocalConnection("192.168.1.100:4421"))
        val url = MaApiEndpoint.deriveUrl(server, MaConnectionMode.PROXY)
        assertNull(url)
    }

    @Test
    fun `deriveUrl PROXY mode with wss URL passes through`() {
        val server = makeServer(proxy = ProxyConnection("wss://music.example.com", "token"))
        val url = MaApiEndpoint.deriveUrl(server, MaConnectionMode.PROXY)
        assertEquals("wss://music.example.com/ws", url)
    }

    // ========================================================================
    // REMOTE mode
    // ========================================================================

    @Test
    fun `deriveUrl REMOTE mode with remote connection returns sentinel`() {
        val server = makeServer(remote = RemoteConnection("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
        val url = MaApiEndpoint.deriveUrl(server, MaConnectionMode.REMOTE)
        assertEquals(MaApiEndpoint.WEBRTC_SENTINEL_URL, url)
    }

    @Test
    fun `deriveUrl REMOTE mode without remote falls back to local`() {
        val server = makeServer(local = LocalConnection("192.168.1.100:4421"))
        val url = MaApiEndpoint.deriveUrl(server, MaConnectionMode.REMOTE)
        assertEquals("ws://192.168.1.100:8095/ws", url)
    }

    @Test
    fun `deriveUrl REMOTE mode without remote falls back to proxy`() {
        val server = makeServer(proxy = ProxyConnection("https://music.example.com/sendspin", "token"))
        val url = MaApiEndpoint.deriveUrl(server, MaConnectionMode.REMOTE)
        assertEquals("wss://music.example.com/ws", url)
    }

    @Test
    fun `deriveUrl REMOTE mode without any connection returns null`() {
        val server = makeServer()
        val url = MaApiEndpoint.deriveUrl(server, MaConnectionMode.REMOTE)
        assertNull(url)
    }

    // ========================================================================
    // hasApiEndpoint
    // ========================================================================

    @Test
    fun `hasApiEndpoint returns true with any connection type`() {
        assertTrue(MaApiEndpoint.hasApiEndpoint(
            makeServer(local = LocalConnection("192.168.1.1:4421"))
        ))
        assertTrue(MaApiEndpoint.hasApiEndpoint(
            makeServer(remote = RemoteConnection("REMOTE_ID_1234567890ABCDEF"))
        ))
        assertTrue(MaApiEndpoint.hasApiEndpoint(
            makeServer(proxy = ProxyConnection("https://proxy.com/sendspin", "token"))
        ))
    }

    @Test
    fun `hasApiEndpoint returns false with no connections`() {
        assertFalse(MaApiEndpoint.hasApiEndpoint(makeServer()))
    }

    // ========================================================================
    // isApiAccessible
    // ========================================================================

    @Test
    fun `isApiAccessible true for valid combination`() {
        val server = makeServer(local = LocalConnection("192.168.1.1:4421"))
        assertTrue(MaApiEndpoint.isApiAccessible(server, MaConnectionMode.LOCAL))
    }

    @Test
    fun `isApiAccessible false for invalid combination`() {
        val server = makeServer(local = LocalConnection("192.168.1.1:4421"))
        assertFalse(MaApiEndpoint.isApiAccessible(server, MaConnectionMode.PROXY))
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun makeServer(
        local: LocalConnection? = null,
        remote: RemoteConnection? = null,
        proxy: ProxyConnection? = null
    ) = UnifiedServer(
        id = "test-server",
        name = "Test Server",
        local = local,
        remote = remote,
        proxy = proxy
    )
}
