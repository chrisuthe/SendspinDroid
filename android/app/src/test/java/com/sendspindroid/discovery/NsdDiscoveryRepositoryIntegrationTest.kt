package com.sendspindroid.discovery

import android.util.Log
import com.sendspindroid.UnifiedServerRepository
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration test: NSD discovery -> UnifiedServerRepository.
 *
 * Verifies that when NsdDiscoveryManager discovers or loses servers,
 * the corresponding UnifiedServerRepository add/remove calls work correctly.
 * This tests the bridge between the mDNS discovery layer and the server
 * repository used by both the UI and the Android Auto browse tree.
 *
 * The flow in PlaybackService:
 * 1. NsdDiscoveryManager.DiscoveryListener.onServerDiscovered fires
 * 2. Callback calls UnifiedServerRepository.addDiscoveredServer(name, address, path)
 * 3. Browse tree is notified of children changed
 *
 * These tests run against the real UnifiedServerRepository (no SharedPreferences
 * needed for discovered servers -- they're in-memory only).
 */
class NsdDiscoveryRepositoryIntegrationTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // Clear any pre-existing discovered servers
        UnifiedServerRepository.clearDiscoveredServers()
    }

    @After
    fun tearDown() {
        UnifiedServerRepository.clearDiscoveredServers()
        unmockkAll()
    }

    /**
     * Simulates the NsdDiscoveryManager.DiscoveryListener.onServerDiscovered callback
     * as implemented in PlaybackService.ensureBrowseDiscoveryRunning.
     */
    private fun simulateServerDiscovered(
        name: String,
        address: String,
        path: String = "/sendspin",
        friendlyName: String = name
    ) {
        UnifiedServerRepository.addDiscoveredServer(friendlyName, address, path)
    }

    /**
     * Simulates the NsdDiscoveryManager.DiscoveryListener.onServerLost callback.
     */
    private fun simulateServerLost(address: String) {
        UnifiedServerRepository.removeDiscoveredServer(address)
    }

    @Test
    fun `discovered server appears in repository`() {
        simulateServerDiscovered("Living Room", "192.168.1.100:8927", "/sendspin")

        val discovered = UnifiedServerRepository.discoveredServers.value
        assertEquals(1, discovered.size)
        assertEquals("Living Room", discovered[0].name)
        assertEquals("192.168.1.100:8927", discovered[0].local?.address)
        assertEquals("/sendspin", discovered[0].local?.path)
        assertTrue("Should be marked as discovered", discovered[0].isDiscovered)
    }

    @Test
    fun `discovered server uses friendlyName not mDNS service name`() {
        // mDNS service name is a host identifier; friendlyName is from TXT "name" record
        simulateServerDiscovered(
            name = "sendspin-abc123._sendspin-server._tcp",
            address = "192.168.1.100:8927",
            friendlyName = "Chris's Music Server"
        )

        val discovered = UnifiedServerRepository.discoveredServers.value
        assertEquals(1, discovered.size)
        assertEquals("Chris's Music Server", discovered[0].name)
    }

    @Test
    fun `multiple servers discovered appear in repository`() {
        simulateServerDiscovered("Living Room", "192.168.1.100:8927")
        simulateServerDiscovered("Kitchen", "192.168.1.101:8927")
        simulateServerDiscovered("Bedroom", "192.168.1.102:8927")

        val discovered = UnifiedServerRepository.discoveredServers.value
        assertEquals(3, discovered.size)
        val names = discovered.map { it.name }.toSet()
        assertTrue("Living Room" in names)
        assertTrue("Kitchen" in names)
        assertTrue("Bedroom" in names)
    }

    @Test
    fun `duplicate discovery is idempotent`() {
        simulateServerDiscovered("Living Room", "192.168.1.100:8927")
        simulateServerDiscovered("Living Room", "192.168.1.100:8927")
        simulateServerDiscovered("Living Room", "192.168.1.100:8927")

        assertEquals(1, UnifiedServerRepository.discoveredServers.value.size)
    }

    @Test
    fun `lost server is removed from repository`() {
        simulateServerDiscovered("Living Room", "192.168.1.100:8927")
        simulateServerDiscovered("Kitchen", "192.168.1.101:8927")
        assertEquals(2, UnifiedServerRepository.discoveredServers.value.size)

        simulateServerLost("192.168.1.100:8927")

        val remaining = UnifiedServerRepository.discoveredServers.value
        assertEquals(1, remaining.size)
        assertEquals("Kitchen", remaining[0].name)
    }

    @Test
    fun `lost server not in repository is no-op`() {
        simulateServerDiscovered("Living Room", "192.168.1.100:8927")

        // Remove a server that doesn't exist
        simulateServerLost("10.0.0.99:8927")

        assertEquals(1, UnifiedServerRepository.discoveredServers.value.size)
    }

    @Test
    fun `discovered server ID uses address-based prefix`() {
        simulateServerDiscovered("Living Room", "192.168.1.100:8927")

        val discovered = UnifiedServerRepository.discoveredServers.value[0]
        assertEquals(
            "Discovered server ID should use address-based prefix",
            "discovered-192.168.1.100:8927",
            discovered.id
        )
    }

    @Test
    fun `discovered server is findable by getServer`() {
        simulateServerDiscovered("Living Room", "192.168.1.100:8927")

        val found = UnifiedServerRepository.getServer("discovered-192.168.1.100:8927")
        assertNotNull("Should find discovered server by ID", found)
        assertEquals("Living Room", found!!.name)
    }

    @Test
    fun `discovered server is findable by getServerByAddress`() {
        simulateServerDiscovered("Living Room", "192.168.1.100:8927")

        val found = UnifiedServerRepository.getServerByAddress("192.168.1.100:8927")
        assertNotNull("Should find discovered server by address", found)
        assertEquals("Living Room", found!!.name)
    }

    @Test
    fun `clearDiscoveredServers removes all discovered servers`() {
        simulateServerDiscovered("Living Room", "192.168.1.100:8927")
        simulateServerDiscovered("Kitchen", "192.168.1.101:8927")

        UnifiedServerRepository.clearDiscoveredServers()

        assertTrue(UnifiedServerRepository.discoveredServers.value.isEmpty())
    }

    @Test
    fun `custom path from TXT record is preserved`() {
        simulateServerDiscovered("Server", "192.168.1.100:8927", "/custom/path")

        val discovered = UnifiedServerRepository.discoveredServers.value[0]
        assertEquals("/custom/path", discovered.local?.path)
    }

    @Test
    fun `NsdDiscoveryManager DiscoveryListener interface has required methods`() {
        // Verify the callback interface has all methods that PlaybackService implements
        val listenerClass = NsdDiscoveryManager.DiscoveryListener::class.java
        val methodNames = listenerClass.methods.map { it.name }

        assertTrue("Should have onServerDiscovered", "onServerDiscovered" in methodNames)
        assertTrue("Should have onServerLost", "onServerLost" in methodNames)
        assertTrue("Should have onDiscoveryStarted", "onDiscoveryStarted" in methodNames)
        assertTrue("Should have onDiscoveryStopped", "onDiscoveryStopped" in methodNames)
        assertTrue("Should have onDiscoveryError", "onDiscoveryError" in methodNames)
    }
}
