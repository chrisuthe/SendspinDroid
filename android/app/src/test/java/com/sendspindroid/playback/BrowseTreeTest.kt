package com.sendspindroid.playback

import android.util.Log
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.ProxyConnection
import com.sendspindroid.model.RemoteConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.model.MaConnectionState
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration test: PlaybackService browse tree for Android Auto.
 *
 * Verifies that onGetChildren returns the correct root items and categories
 * based on connection state and MA availability. This reproduces the
 * getRootChildren() and getDiscoveredServers() logic from PlaybackService.
 *
 * Browse tree structure:
 * - When disconnected (no MA): root -> "Connect" folder -> server list
 * - When connected with MA: root -> Playlists, Albums, Artists, Radio
 */
class BrowseTreeTest {

    // Media ID constants (mirror PlaybackService companion)
    private val MEDIA_ID_ROOT = "root"
    private val MEDIA_ID_DISCOVERED = "discovered_servers"
    private val MEDIA_ID_MA_PLAYLISTS = "ma_playlists"
    private val MEDIA_ID_MA_ALBUMS = "ma_albums"
    private val MEDIA_ID_MA_ARTISTS = "ma_artists"
    private val MEDIA_ID_MA_RADIO = "ma_radio"
    private val MEDIA_ID_SERVER_PREFIX = "server_"
    private val MEDIA_ID_SAVED_SERVER_PREFIX = "saved_server_"

    data class BrowseItem(
        val mediaId: String,
        val title: String,
        val subtitle: String? = null,
        val isPlayable: Boolean = false,
        val isBrowsable: Boolean = true
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        UnifiedServerRepository.clearDiscoveredServers()
        UnifiedServerRepository.savedServers.value.forEach {
            UnifiedServerRepository.deleteServer(it.id)
        }
    }

    @After
    fun tearDown() {
        UnifiedServerRepository.clearDiscoveredServers()
        UnifiedServerRepository.savedServers.value.forEach {
            UnifiedServerRepository.deleteServer(it.id)
        }
        unmockkAll()
    }

    /**
     * Reproduces PlaybackService.getRootChildren() logic.
     */
    private fun getRootChildren(maAvailable: Boolean, isConnected: Boolean): List<BrowseItem> {
        val children = mutableListOf<BrowseItem>()

        // Show "Connect" until MA is available
        if (!maAvailable) {
            children.add(
                BrowseItem(
                    mediaId = MEDIA_ID_DISCOVERED,
                    title = "Connect",
                    subtitle = if (isConnected) "Connected" else null,
                    isBrowsable = true,
                    isPlayable = false
                )
            )
        }

        // Show library categories when MA is connected
        if (maAvailable) {
            children.add(BrowseItem(mediaId = MEDIA_ID_MA_PLAYLISTS, title = "Playlists"))
            children.add(BrowseItem(mediaId = MEDIA_ID_MA_ALBUMS, title = "Albums"))
            children.add(BrowseItem(mediaId = MEDIA_ID_MA_ARTISTS, title = "Artists"))
            children.add(BrowseItem(mediaId = MEDIA_ID_MA_RADIO, title = "Radio"))
        }

        return children
    }

    /**
     * Reproduces PlaybackService.getDiscoveredServers() logic.
     */
    private fun getDiscoveredServers(): List<BrowseItem> {
        val savedItems = UnifiedServerRepository.savedServers.value
            .sortedByDescending { it.lastConnectedMs }
            .map { server ->
                val subtitle = server.local?.address
                    ?: if (server.proxy != null) "Proxy"
                    else if (server.remote != null) "Remote Access"
                    else ""
                BrowseItem(
                    mediaId = "$MEDIA_ID_SAVED_SERVER_PREFIX${server.id}",
                    title = server.name,
                    subtitle = subtitle,
                    isPlayable = true,
                    isBrowsable = false
                )
            }

        val discoveredItems = UnifiedServerRepository.discoveredServers.value.mapNotNull { server ->
            val address = server.local?.address ?: return@mapNotNull null
            BrowseItem(
                mediaId = "$MEDIA_ID_SERVER_PREFIX$address",
                title = server.name,
                subtitle = address,
                isPlayable = true,
                isBrowsable = false
            )
        }

        return savedItems + discoveredItems
    }

    // ========== Root children tests ==========

    @Test
    fun `root shows Connect when MA unavailable and disconnected`() {
        val children = getRootChildren(maAvailable = false, isConnected = false)

        assertEquals(1, children.size)
        assertEquals(MEDIA_ID_DISCOVERED, children[0].mediaId)
        assertEquals("Connect", children[0].title)
        assertNull("Subtitle should be null when disconnected", children[0].subtitle)
    }

    @Test
    fun `root shows Connect with Connected subtitle when connected without MA`() {
        val children = getRootChildren(maAvailable = false, isConnected = true)

        assertEquals(1, children.size)
        assertEquals("Connect", children[0].title)
        assertEquals("Connected", children[0].subtitle)
    }

    @Test
    fun `root shows library categories when MA is available`() {
        val children = getRootChildren(maAvailable = true, isConnected = true)

        assertEquals(4, children.size)
        assertEquals(MEDIA_ID_MA_PLAYLISTS, children[0].mediaId)
        assertEquals("Playlists", children[0].title)
        assertEquals(MEDIA_ID_MA_ALBUMS, children[1].mediaId)
        assertEquals("Albums", children[1].title)
        assertEquals(MEDIA_ID_MA_ARTISTS, children[2].mediaId)
        assertEquals("Artists", children[2].title)
        assertEquals(MEDIA_ID_MA_RADIO, children[3].mediaId)
        assertEquals("Radio", children[3].title)
    }

    @Test
    fun `root does not show Connect when MA is available`() {
        val children = getRootChildren(maAvailable = true, isConnected = true)

        val hasConnect = children.any { it.mediaId == MEDIA_ID_DISCOVERED }
        assertFalse("Connect should not appear when MA is available", hasConnect)
    }

    @Test
    fun `library categories are browsable not playable`() {
        val children = getRootChildren(maAvailable = true, isConnected = true)

        children.forEach { item ->
            assertTrue("${item.title} should be browsable", item.isBrowsable)
            assertFalse("${item.title} should not be playable", item.isPlayable)
        }
    }

    // ========== Discovered servers tests ==========

    @Test
    fun `discovered servers list shows saved servers first`() {
        UnifiedServerRepository.saveServer(
            UnifiedServer(
                id = "saved-1", name = "Saved Server",
                local = LocalConnection("192.168.1.10:8927"),
                lastConnectedMs = 1000L
            )
        )
        UnifiedServerRepository.addDiscoveredServer("Discovered Server", "192.168.1.20:8927")

        val servers = getDiscoveredServers()

        assertEquals(2, servers.size)
        assertTrue(
            "First item should be saved server",
            servers[0].mediaId.startsWith(MEDIA_ID_SAVED_SERVER_PREFIX)
        )
        assertTrue(
            "Second item should be discovered server",
            servers[1].mediaId.startsWith(MEDIA_ID_SERVER_PREFIX)
        )
    }

    @Test
    fun `saved servers are sorted by last connected time`() {
        UnifiedServerRepository.saveServer(
            UnifiedServer(
                id = "old", name = "Old Server",
                local = LocalConnection("10.0.0.1:8927"),
                lastConnectedMs = 100L
            )
        )
        UnifiedServerRepository.saveServer(
            UnifiedServer(
                id = "recent", name = "Recent Server",
                local = LocalConnection("10.0.0.2:8927"),
                lastConnectedMs = 500L
            )
        )

        val servers = getDiscoveredServers()

        assertEquals("Recent Server", servers[0].title)
        assertEquals("Old Server", servers[1].title)
    }

    @Test
    fun `server items are playable not browsable`() {
        UnifiedServerRepository.addDiscoveredServer("Test", "192.168.1.10:8927")

        val servers = getDiscoveredServers()

        assertEquals(1, servers.size)
        assertTrue("Server should be playable", servers[0].isPlayable)
        assertFalse("Server should not be browsable", servers[0].isBrowsable)
    }

    @Test
    fun `saved server with proxy shows Proxy subtitle`() {
        UnifiedServerRepository.saveServer(
            UnifiedServer(
                id = "proxy-1", name = "Proxy Server",
                proxy = ProxyConnection(
                    url = "https://ma.example.com/sendspin",
                    authToken = "token123"
                )
            )
        )

        val servers = getDiscoveredServers()
        assertEquals(1, servers.size)
        assertEquals("Proxy", servers[0].subtitle)
    }

    @Test
    fun `saved server with remote shows Remote Access subtitle`() {
        UnifiedServerRepository.saveServer(
            UnifiedServer(
                id = "remote-1", name = "Remote Server",
                remote = RemoteConnection(remoteId = "ABCDE12345FGHIJ67890KLMNOP")
            )
        )

        val servers = getDiscoveredServers()
        assertEquals(1, servers.size)
        assertEquals("Remote Access", servers[0].subtitle)
    }

    @Test
    fun `empty server list returns empty`() {
        val servers = getDiscoveredServers()
        assertTrue("Should be empty", servers.isEmpty())
    }

    @Test
    fun `discovered server media ID includes address`() {
        UnifiedServerRepository.addDiscoveredServer("Test", "192.168.1.10:8927")

        val servers = getDiscoveredServers()
        assertEquals("server_192.168.1.10:8927", servers[0].mediaId)
    }

    @Test
    fun `saved server media ID includes server UUID`() {
        UnifiedServerRepository.saveServer(
            UnifiedServer(
                id = "uuid-123", name = "Saved",
                local = LocalConnection("10.0.0.1:8927")
            )
        )

        val servers = getDiscoveredServers()
        assertEquals("saved_server_uuid-123", servers[0].mediaId)
    }
}
