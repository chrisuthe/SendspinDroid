package com.sendspindroid.playback

import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.ProxyConnection
import com.sendspindroid.model.RemoteConnection
import com.sendspindroid.model.UnifiedServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [AutoBrowseTree], the Android Auto browse tree list builder.
 *
 * These exercise the real production code (real Media3 MediaItems via
 * Robolectric), unlike the previous version of this test which re-implemented
 * the service logic.
 *
 * The load-bearing invariant: no browse node ever resolves to an empty list.
 * Google Play's automated Android Auto quality review browses the app with no
 * SendSpin server reachable; an empty children list renders as a blank
 * "unable to load content" screen and fails review (rejection of Jun 26).
 * When there is nothing to show, a non-interactive guidance row is returned
 * instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseTreeTest {

    private fun localServer(
        id: String,
        name: String,
        address: String,
        lastConnectedMs: Long = 0L,
    ) = UnifiedServer(
        id = id,
        name = name,
        local = LocalConnection(address),
        lastConnectedMs = lastConnectedMs,
    )

    // ========== Root children ==========

    @Test
    fun `root shows Connect when MA unavailable and disconnected`() {
        val children = AutoBrowseTree.rootChildren(maAvailable = false, isConnected = false)

        assertEquals(1, children.size)
        assertEquals(AutoBrowseTree.MEDIA_ID_DISCOVERED, children[0].mediaId)
        assertEquals("Connect", children[0].mediaMetadata.title)
        assertNull("Subtitle should be null when disconnected", children[0].mediaMetadata.subtitle)
        assertEquals(true, children[0].mediaMetadata.isBrowsable)
    }

    @Test
    fun `root shows Connect with Connected subtitle when connected without MA`() {
        val children = AutoBrowseTree.rootChildren(maAvailable = false, isConnected = true)

        assertEquals(1, children.size)
        assertEquals("Connect", children[0].mediaMetadata.title)
        assertEquals("Connected", children[0].mediaMetadata.subtitle)
    }

    @Test
    fun `root shows library categories when MA is available`() {
        val children = AutoBrowseTree.rootChildren(maAvailable = true, isConnected = true)

        assertEquals(
            listOf(
                AutoBrowseTree.MEDIA_ID_MA_PLAYLISTS,
                AutoBrowseTree.MEDIA_ID_MA_ALBUMS,
                AutoBrowseTree.MEDIA_ID_MA_ARTISTS,
                AutoBrowseTree.MEDIA_ID_MA_RADIO,
            ),
            children.map { it.mediaId }
        )
        assertEquals(
            listOf("Playlists", "Albums", "Artists", "Radio"),
            children.map { it.mediaMetadata.title.toString() }
        )
    }

    @Test
    fun `root never returns an empty list`() {
        assertTrue(AutoBrowseTree.rootChildren(maAvailable = false, isConnected = false).isNotEmpty())
        assertTrue(AutoBrowseTree.rootChildren(maAvailable = false, isConnected = true).isNotEmpty())
        assertTrue(AutoBrowseTree.rootChildren(maAvailable = true, isConnected = true).isNotEmpty())
    }

    @Test
    fun `library categories are browsable not playable`() {
        val children = AutoBrowseTree.rootChildren(maAvailable = true, isConnected = true)

        children.forEach { item ->
            assertEquals("${item.mediaMetadata.title} should be browsable",
                true, item.mediaMetadata.isBrowsable)
            assertEquals("${item.mediaMetadata.title} should not be playable",
                false, item.mediaMetadata.isPlayable)
        }
    }

    // ========== Server list ("Connect" node) ==========

    @Test
    fun `server list shows saved servers first`() = runTest {
        val discovered = MutableStateFlow(
            listOf(localServer("disc-1", "Discovered Server", "192.168.1.20:8927"))
        )

        val items = AutoBrowseTree.serverListChildren(
            savedServers = listOf(
                localServer("saved-1", "Saved Server", "192.168.1.10:8927", lastConnectedMs = 1000L)
            ),
            discoveredServersFlow = discovered,
            discoveryWaitMs = 3000L,
        )

        assertEquals(2, items.size)
        assertTrue(
            "First item should be saved server",
            items[0].mediaId.startsWith(AutoBrowseTree.MEDIA_ID_SAVED_SERVER_PREFIX)
        )
        assertTrue(
            "Second item should be discovered server",
            items[1].mediaId.startsWith(AutoBrowseTree.MEDIA_ID_SERVER_PREFIX)
        )
    }

    @Test
    fun `saved servers are sorted by last connected time`() = runTest {
        val items = AutoBrowseTree.serverListChildren(
            savedServers = listOf(
                localServer("old", "Old Server", "10.0.0.1:8927", lastConnectedMs = 100L),
                localServer("recent", "Recent Server", "10.0.0.2:8927", lastConnectedMs = 500L),
            ),
            discoveredServersFlow = MutableStateFlow(emptyList()),
            discoveryWaitMs = 0L,
        )

        assertEquals("Recent Server", items[0].mediaMetadata.title)
        assertEquals("Old Server", items[1].mediaMetadata.title)
    }

    @Test
    fun `server items are playable not browsable`() = runTest {
        val items = AutoBrowseTree.serverListChildren(
            savedServers = emptyList(),
            discoveredServersFlow = MutableStateFlow(
                listOf(localServer("d", "Test", "192.168.1.10:8927"))
            ),
            discoveryWaitMs = 0L,
        )

        assertEquals(1, items.size)
        assertEquals(true, items[0].mediaMetadata.isPlayable)
        assertEquals(false, items[0].mediaMetadata.isBrowsable)
    }

    @Test
    fun `discovered server media ID includes address`() = runTest {
        val items = AutoBrowseTree.serverListChildren(
            savedServers = emptyList(),
            discoveredServersFlow = MutableStateFlow(
                listOf(localServer("d", "Test", "192.168.1.10:8927"))
            ),
            discoveryWaitMs = 0L,
        )

        assertEquals("server_192.168.1.10:8927", items[0].mediaId)
    }

    @Test
    fun `saved server media ID includes server UUID`() = runTest {
        val items = AutoBrowseTree.serverListChildren(
            savedServers = listOf(localServer("uuid-123", "Saved", "10.0.0.1:8927")),
            discoveredServersFlow = MutableStateFlow(emptyList()),
            discoveryWaitMs = 0L,
        )

        assertEquals("saved_server_uuid-123", items[0].mediaId)
    }

    @Test
    fun `saved server with proxy shows Proxy subtitle`() = runTest {
        val items = AutoBrowseTree.serverListChildren(
            savedServers = listOf(
                UnifiedServer(
                    id = "proxy-1", name = "Proxy Server",
                    proxy = ProxyConnection(
                        url = "https://ma.example.com/sendspin",
                        authToken = "token123"
                    )
                )
            ),
            discoveredServersFlow = MutableStateFlow(emptyList()),
            discoveryWaitMs = 0L,
        )

        assertEquals(1, items.size)
        assertEquals("Proxy", items[0].mediaMetadata.subtitle)
    }

    @Test
    fun `saved server with remote shows Remote Access subtitle`() = runTest {
        val items = AutoBrowseTree.serverListChildren(
            savedServers = listOf(
                UnifiedServer(
                    id = "remote-1", name = "Remote Server",
                    remote = RemoteConnection(remoteId = "ABCDE12345FGHIJ67890KLMNOP")
                )
            ),
            discoveredServersFlow = MutableStateFlow(emptyList()),
            discoveryWaitMs = 0L,
        )

        assertEquals(1, items.size)
        assertEquals("Remote Access", items[0].mediaMetadata.subtitle)
    }

    // ========== Play rejection regression: never-empty guarantees ==========

    @Test
    fun `empty server list returns No servers found guidance instead of empty`() = runTest {
        // This is what Google's automated Auto review sees: fresh install,
        // no saved servers, no SendSpin server on the network.
        val items = AutoBrowseTree.serverListChildren(
            savedServers = emptyList(),
            discoveredServersFlow = MutableStateFlow(emptyList()),
            discoveryWaitMs = 3000L,
        )

        assertEquals(1, items.size)
        assertEquals(AutoBrowseTree.MEDIA_ID_MESSAGE_NO_SERVERS, items[0].mediaId)
        assertEquals("No servers found", items[0].mediaMetadata.title)
        assertEquals(
            "Open SendSpin Player on your phone to add a server",
            items[0].mediaMetadata.subtitle
        )
    }

    @Test
    fun `guidance row is neither playable nor browsable`() = runTest {
        val items = AutoBrowseTree.serverListChildren(
            savedServers = emptyList(),
            discoveredServersFlow = MutableStateFlow(emptyList()),
            discoveryWaitMs = 0L,
        )

        assertEquals(false, items[0].mediaMetadata.isPlayable)
        assertEquals(false, items[0].mediaMetadata.isBrowsable)
    }

    @Test
    fun `server list waits for first mDNS discovery before answering`() = runTest {
        // First browse races mDNS: the flow is empty at call time and a
        // server appears 1s later, inside the 3s discovery window.
        val discovered = MutableStateFlow<List<UnifiedServer>>(emptyList())
        launch {
            delay(1000L)
            discovered.value = listOf(localServer("d", "Living Room", "192.168.1.30:8927"))
        }

        val items = AutoBrowseTree.serverListChildren(
            savedServers = emptyList(),
            discoveredServersFlow = discovered,
            discoveryWaitMs = 3000L,
        )

        assertEquals(1, items.size)
        assertEquals("Living Room", items[0].mediaMetadata.title)
        assertEquals(true, items[0].mediaMetadata.isPlayable)
    }

    @Test
    fun `server list does not wait when a saved server exists`() = runTest {
        // A saved server means there is content to show immediately; the
        // discovery wait must not delay the browse response.
        val discovered = MutableStateFlow<List<UnifiedServer>>(emptyList())

        val items = AutoBrowseTree.serverListChildren(
            savedServers = listOf(localServer("saved-1", "Saved", "10.0.0.1:8927")),
            discoveredServersFlow = discovered,
            discoveryWaitMs = 3000L,
        )

        assertEquals(1, items.size)
        assertEquals("Saved", items[0].mediaMetadata.title)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `MA category nodes replace empty results with a message row`() {
        val categories = listOf(
            AutoBrowseTree.MEDIA_ID_MA_PLAYLISTS to "No playlists found",
            AutoBrowseTree.MEDIA_ID_MA_ALBUMS to "No albums found",
            AutoBrowseTree.MEDIA_ID_MA_ARTISTS to "No artists found",
            AutoBrowseTree.MEDIA_ID_MA_RADIO to "No radio stations found",
            AutoBrowseTree.MEDIA_ID_MA_PLAYLIST_PREFIX + "id~provider" to "No tracks found",
            AutoBrowseTree.MEDIA_ID_MA_ALBUM_PREFIX + "id~provider" to "No tracks found",
            AutoBrowseTree.MEDIA_ID_MA_ARTIST_PREFIX + "id~provider" to "No albums found",
        )

        categories.forEach { (parentId, expectedTitle) ->
            val items = AutoBrowseTree.withEmptyState(parentId, emptyList())
            assertEquals("$parentId should get exactly one message row", 1, items.size)
            assertEquals(expectedTitle, items[0].mediaMetadata.title)
            assertEquals(false, items[0].mediaMetadata.isPlayable)
            assertEquals(false, items[0].mediaMetadata.isBrowsable)
            assertTrue(items[0].mediaId.startsWith(AutoBrowseTree.MEDIA_ID_MESSAGE_PREFIX))
        }
    }

    @Test
    fun `withEmptyState passes non-empty lists through unchanged`() {
        val item = AutoBrowseTree.playableServerItem("Test", "10.0.0.1:8927")

        val items = AutoBrowseTree.withEmptyState(AutoBrowseTree.MEDIA_ID_MA_PLAYLISTS, listOf(item))

        assertEquals(1, items.size)
        assertEquals(item.mediaId, items[0].mediaId)
        assertFalse(items[0].mediaId.startsWith(AutoBrowseTree.MEDIA_ID_MESSAGE_PREFIX))
    }
}
