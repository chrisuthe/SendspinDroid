package com.sendspindroid

import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for UnifiedServerRepository public API.
 *
 * All tests run without initialize() -- saved server operations work in-memory
 * because ensurePersistedDataLoaded() is a no-op without a Context, and
 * persistServers() silently no-ops when prefs is null.
 *
 * Discovered server operations don't touch SharedPreferences at all.
 */
class UnifiedServerRepositoryTest {

    @Before
    fun setUp() {
        UnifiedServerRepository.clearDiscoveredServers()
        // Clear saved servers by deleting each one
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
    }

    // ========== saveServer / savedServers ==========

    @Test
    fun `saveServer adds server visible in savedServers`() {
        val server = testServer("id-1", "Living Room", "192.168.1.10:8927")
        UnifiedServerRepository.saveServer(server)

        val saved = UnifiedServerRepository.savedServers.value
        assertEquals(1, saved.size)
        assertEquals("id-1", saved[0].id)
        assertEquals("Living Room", saved[0].name)
        assertFalse("Saved server should not be marked as discovered", saved[0].isDiscovered)
    }

    @Test
    fun `saveServer with same ID updates existing entry`() {
        val server1 = testServer("id-1", "Living Room", "192.168.1.10:8927")
        val server2 = testServer("id-1", "Updated Name", "192.168.1.20:8927")

        UnifiedServerRepository.saveServer(server1)
        UnifiedServerRepository.saveServer(server2)

        val saved = UnifiedServerRepository.savedServers.value
        assertEquals(1, saved.size)
        assertEquals("Updated Name", saved[0].name)
        assertEquals("192.168.1.20:8927", saved[0].local?.address)
    }

    // ========== deleteServer ==========

    @Test
    fun `deleteServer removes by ID`() {
        UnifiedServerRepository.saveServer(testServer("id-1", "Server A", "10.0.0.1:8927"))
        UnifiedServerRepository.saveServer(testServer("id-2", "Server B", "10.0.0.2:8927"))

        UnifiedServerRepository.deleteServer("id-1")

        val saved = UnifiedServerRepository.savedServers.value
        assertEquals(1, saved.size)
        assertEquals("id-2", saved[0].id)
    }

    // ========== getServer (by ID) ==========

    @Test
    fun `getServer finds saved server by ID`() {
        val server = testServer("id-1", "Test", "10.0.0.1:8927")
        UnifiedServerRepository.saveServer(server)

        val found = UnifiedServerRepository.getServer("id-1")
        assertNotNull(found)
        assertEquals("Test", found!!.name)
    }

    @Test
    fun `getServer finds discovered server by ID`() {
        UnifiedServerRepository.addDiscoveredServer("Found", "10.0.0.1:8927")

        val found = UnifiedServerRepository.getServer("discovered-10.0.0.1:8927")
        assertNotNull(found)
        assertEquals("Found", found!!.name)
    }

    @Test
    fun `getServer returns null for unknown ID`() {
        assertNull(UnifiedServerRepository.getServer("nonexistent"))
    }

    // ========== getServerByAddress ==========

    @Test
    fun `getServerByAddress finds saved server by local address`() {
        UnifiedServerRepository.saveServer(testServer("id-1", "Saved", "10.0.0.1:8927"))

        val found = UnifiedServerRepository.getServerByAddress("10.0.0.1:8927")
        assertNotNull(found)
        assertEquals("Saved", found!!.name)
        assertEquals("id-1", found.id)
    }

    @Test
    fun `getServerByAddress finds discovered server by local address`() {
        UnifiedServerRepository.addDiscoveredServer("Found", "10.0.0.1:8927")

        val found = UnifiedServerRepository.getServerByAddress("10.0.0.1:8927")
        assertNotNull(found)
        assertEquals("Found", found!!.name)
    }

    @Test
    fun `getServerByAddress returns null for unknown address`() {
        assertNull(UnifiedServerRepository.getServerByAddress("1.2.3.4:9999"))
    }

    @Test
    fun `getServerByAddress prefers saved over discovered`() {
        UnifiedServerRepository.saveServer(testServer("saved-id", "Saved Version", "10.0.0.1:8927"))
        UnifiedServerRepository.addDiscoveredServer("Discovered Version", "10.0.0.1:8927")

        val found = UnifiedServerRepository.getServerByAddress("10.0.0.1:8927")
        assertNotNull(found)
        assertEquals("saved-id", found!!.id)
        assertEquals("Saved Version", found.name)
    }

    // ========== addDiscoveredServer ==========

    @Test
    fun `addDiscoveredServer creates transient server with discovered prefix ID`() {
        UnifiedServerRepository.addDiscoveredServer("Room A", "192.168.1.10:8927", "/sendspin")

        val discovered = UnifiedServerRepository.discoveredServers.value
        assertEquals(1, discovered.size)
        assertEquals("discovered-192.168.1.10:8927", discovered[0].id)
        assertEquals("Room A", discovered[0].name)
        assertTrue(discovered[0].isDiscovered)
        assertEquals("192.168.1.10:8927", discovered[0].local?.address)
        assertEquals("/sendspin", discovered[0].local?.path)
    }

    @Test
    fun `addDiscoveredServer is idempotent for same address`() {
        UnifiedServerRepository.addDiscoveredServer("Room A", "192.168.1.10:8927")
        UnifiedServerRepository.addDiscoveredServer("Room A copy", "192.168.1.10:8927")

        assertEquals(1, UnifiedServerRepository.discoveredServers.value.size)
    }

    // ========== removeDiscoveredServer ==========

    @Test
    fun `removeDiscoveredServer removes by address`() {
        UnifiedServerRepository.addDiscoveredServer("A", "10.0.0.1:8927")
        UnifiedServerRepository.addDiscoveredServer("B", "10.0.0.2:8927")

        UnifiedServerRepository.removeDiscoveredServer("10.0.0.1:8927")

        val remaining = UnifiedServerRepository.discoveredServers.value
        assertEquals(1, remaining.size)
        assertEquals("B", remaining[0].name)
    }

    // ========== clearDiscoveredServers ==========

    @Test
    fun `clearDiscoveredServers empties the list`() {
        UnifiedServerRepository.addDiscoveredServer("A", "10.0.0.1:8927")
        UnifiedServerRepository.addDiscoveredServer("B", "10.0.0.2:8927")

        UnifiedServerRepository.clearDiscoveredServers()

        assertTrue(UnifiedServerRepository.discoveredServers.value.isEmpty())
    }

    // ========== promoteDiscoveredServer ==========

    @Test
    fun `promoteDiscoveredServer moves from discovered to saved with new UUID`() {
        UnifiedServerRepository.addDiscoveredServer("Room A", "192.168.1.10:8927")
        val discovered = UnifiedServerRepository.discoveredServers.value.first()

        val promoted = UnifiedServerRepository.promoteDiscoveredServer(discovered)

        // Should be in saved now
        val saved = UnifiedServerRepository.savedServers.value
        assertEquals(1, saved.size)
        assertEquals("Room A", saved[0].name)
        assertFalse(saved[0].isDiscovered)
        assertNotEquals("discovered-192.168.1.10:8927", saved[0].id) // new UUID

        // Should be removed from discovered
        val remainingDiscovered = UnifiedServerRepository.discoveredServers.value
        assertFalse(remainingDiscovered.any { it.id == discovered.id })

        // Returned server should match saved
        assertEquals(promoted.id, saved[0].id)
    }

    // ========== setDefaultServer / getDefaultServer ==========

    @Test
    fun `setDefaultServer sets one and clears others`() {
        UnifiedServerRepository.saveServer(testServer("id-1", "A", "10.0.0.1:8927"))
        UnifiedServerRepository.saveServer(testServer("id-2", "B", "10.0.0.2:8927"))

        UnifiedServerRepository.setDefaultServer("id-1")
        assertEquals("id-1", UnifiedServerRepository.getDefaultServer()?.id)

        // Setting a different server as default should clear the first
        UnifiedServerRepository.setDefaultServer("id-2")
        val saved = UnifiedServerRepository.savedServers.value
        assertFalse(saved.find { it.id == "id-1" }!!.isDefaultServer)
        assertTrue(saved.find { it.id == "id-2" }!!.isDefaultServer)
    }

    @Test
    fun `setDefaultServer null clears default`() {
        UnifiedServerRepository.saveServer(testServer("id-1", "A", "10.0.0.1:8927"))
        UnifiedServerRepository.setDefaultServer("id-1")
        assertNotNull(UnifiedServerRepository.getDefaultServer())

        UnifiedServerRepository.setDefaultServer(null)
        assertNull(UnifiedServerRepository.getDefaultServer())
    }

    @Test
    fun `getDefaultServer returns null when no default set`() {
        UnifiedServerRepository.saveServer(testServer("id-1", "A", "10.0.0.1:8927"))
        assertNull(UnifiedServerRepository.getDefaultServer())
    }

    // ========== updateLastConnected ==========

    @Test
    fun `updateLastConnected updates timestamp on saved server`() {
        val server = testServer("id-1", "Test", "10.0.0.1:8927")
        UnifiedServerRepository.saveServer(server)
        assertEquals(0L, UnifiedServerRepository.savedServers.value[0].lastConnectedMs)

        UnifiedServerRepository.updateLastConnected("id-1")

        val updated = UnifiedServerRepository.savedServers.value[0]
        assertTrue("Timestamp should be updated", updated.lastConnectedMs > 0)
    }

    @Test
    fun `updateLastConnected does nothing for unknown ID`() {
        UnifiedServerRepository.saveServer(testServer("id-1", "Test", "10.0.0.1:8927"))

        // Should not throw
        UnifiedServerRepository.updateLastConnected("nonexistent")

        // Original unchanged
        assertEquals(0L, UnifiedServerRepository.savedServers.value[0].lastConnectedMs)
    }

    // ========== Helper ==========

    private fun testServer(id: String, name: String, address: String) = UnifiedServer(
        id = id,
        name = name,
        local = LocalConnection(address)
    )
}
