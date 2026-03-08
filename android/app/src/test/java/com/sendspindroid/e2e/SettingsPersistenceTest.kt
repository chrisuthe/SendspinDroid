package com.sendspindroid.e2e

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.UserSettings
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.ProxyConnection
import com.sendspindroid.model.RemoteConnection as RemoteConn
import com.sendspindroid.model.UnifiedServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * E2E Test 7: Settings persist across process death
 *
 * Tests that saved servers and user preferences survive process kill and
 * relaunch. Since Android SharedPreferences is not available in JVM unit
 * tests, this test validates the persistence logic through the repository
 * and settings APIs.
 *
 * What IS testable here:
 * - UnifiedServerRepository save/load cycle (in-memory without prefs)
 * - Server serialization round-trip (all connection types)
 * - UserSettings default values and preference key definitions
 * - Server repository merge logic (saved + discovered)
 * - Default server selection persistence
 *
 * What requires instrumented/manual testing:
 * - SharedPreferences actual persistence to disk
 * - EncryptedSharedPreferences for auth tokens
 * - Process death simulation (adb shell am kill)
 * - Activity recreation with saved instance state
 * - Preference fragment UI binding
 *
 * Manual test steps:
 * 1. Configure a server (local + remote + proxy connections)
 * 2. Set preferences (sync offset, codec, low memory mode)
 * 3. Force-stop app: adb shell am force-stop com.sendspindroid
 * 4. Relaunch app
 * 5. Verify server list is intact (all connection methods preserved)
 * 6. Verify preferences are intact (sync offset, codec choice)
 * 7. Verify auto-connect to default server works
 */
class SettingsPersistenceTest {

    @Before
    fun setUp() {
        // Mock android statics
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // Clear repository state
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

    // ========== UnifiedServerRepository Persistence Tests ==========

    @Test
    fun `saved server survives save and retrieve cycle`() {
        val server = UnifiedServer(
            id = "server-1",
            name = "Living Room",
            local = LocalConnection("192.168.1.10:8927", "/sendspin")
        )

        UnifiedServerRepository.saveServer(server)

        val saved = UnifiedServerRepository.savedServers.value
        assertEquals(1, saved.size)
        assertEquals("server-1", saved[0].id)
        assertEquals("Living Room", saved[0].name)
        assertEquals("192.168.1.10:8927", saved[0].local?.address)
        assertEquals("/sendspin", saved[0].local?.path)
    }

    @Test
    fun `server with all connection types round-trips correctly`() {
        val server = UnifiedServer(
            id = "multi-conn",
            name = "Office",
            local = LocalConnection("10.0.0.5:8927", "/sendspin"),
            remote = RemoteConn("VVPN3TLP34YMGIZDINCEKQKSIR"),
            proxy = ProxyConnection(
                url = "https://ma.example.com/sendspin",
                authToken = "secret-token-123",
                username = "admin"
            ),
            isDefaultServer = true,
            isMusicAssistant = true
        )

        UnifiedServerRepository.saveServer(server)

        val saved = UnifiedServerRepository.savedServers.value.first()
        assertEquals("multi-conn", saved.id)
        assertEquals("Office", saved.name)

        // Local
        assertNotNull(saved.local)
        assertEquals("10.0.0.5:8927", saved.local?.address)

        // Remote
        assertNotNull(saved.remote)
        assertEquals("VVPN3TLP34YMGIZDINCEKQKSIR", saved.remote?.remoteId)

        // Proxy
        assertNotNull(saved.proxy)
        assertEquals("https://ma.example.com/sendspin", saved.proxy?.url)
        assertEquals("secret-token-123", saved.proxy?.authToken)
        assertEquals("admin", saved.proxy?.username)

        // Flags
        assertTrue(saved.isDefaultServer)
        assertTrue(saved.isMusicAssistant)
    }

    @Test
    fun `multiple servers persist independently`() {
        val server1 = UnifiedServer(
            id = "s1", name = "Kitchen",
            local = LocalConnection("192.168.1.20:8927")
        )
        val server2 = UnifiedServer(
            id = "s2", name = "Bedroom",
            remote = RemoteConn("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        )
        val server3 = UnifiedServer(
            id = "s3", name = "Garage",
            proxy = ProxyConnection("https://proxy.test.com/ss", "tok123")
        )

        UnifiedServerRepository.saveServer(server1)
        UnifiedServerRepository.saveServer(server2)
        UnifiedServerRepository.saveServer(server3)

        val saved = UnifiedServerRepository.savedServers.value
        assertEquals(3, saved.size)
        assertEquals(setOf("s1", "s2", "s3"), saved.map { it.id }.toSet())
    }

    @Test
    fun `updating server preserves ID and replaces data`() {
        val original = UnifiedServer(
            id = "s1", name = "Kitchen",
            local = LocalConnection("192.168.1.20:8927")
        )
        UnifiedServerRepository.saveServer(original)

        // Update with new info
        val updated = original.copy(
            name = "Updated Kitchen",
            remote = RemoteConn("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        )
        UnifiedServerRepository.saveServer(updated)

        val saved = UnifiedServerRepository.savedServers.value
        assertEquals(1, saved.size) // Still 1, not 2
        assertEquals("Updated Kitchen", saved[0].name)
        assertNotNull("Remote should be added", saved[0].remote)
    }

    @Test
    fun `deleting server removes it from repository`() {
        val server = UnifiedServer(
            id = "to-delete", name = "Temp",
            local = LocalConnection("192.168.1.30:8927")
        )
        UnifiedServerRepository.saveServer(server)
        assertEquals(1, UnifiedServerRepository.savedServers.value.size)

        UnifiedServerRepository.deleteServer("to-delete")

        assertEquals(0, UnifiedServerRepository.savedServers.value.size)
    }

    @Test
    fun `discovered servers are not persisted`() {
        val discovered = UnifiedServer(
            id = "disc-1", name = "Found Server",
            local = LocalConnection("192.168.1.50:8927"),
            isDiscovered = true
        )

        UnifiedServerRepository.updateDiscoveredServer(discovered)

        // Should appear in discovered list
        val disc = UnifiedServerRepository.discoveredServers.value
        assertEquals(1, disc.size)

        // Should NOT appear in saved list
        val saved = UnifiedServerRepository.savedServers.value
        assertTrue(saved.none { it.id == "disc-1" })
    }

    @Test
    fun `clearing discovered servers does not affect saved servers`() {
        // Save a server
        UnifiedServerRepository.saveServer(
            UnifiedServer("saved-1", "Saved",
                local = LocalConnection("192.168.1.10:8927"))
        )

        // Add a discovered server
        UnifiedServerRepository.updateDiscoveredServer(
            UnifiedServer("disc-1", "Discovered",
                local = LocalConnection("192.168.1.50:8927"),
                isDiscovered = true)
        )

        // Clear discovered
        UnifiedServerRepository.clearDiscoveredServers()

        // Saved should be intact
        assertEquals(1, UnifiedServerRepository.savedServers.value.size)
        assertEquals(0, UnifiedServerRepository.discoveredServers.value.size)
    }

    // ========== UserSettings Key/Default Tests ==========

    @Test
    fun `preference keys are defined and unique`() {
        val keys = listOf(
            UserSettings.KEY_PLAYER_ID,
            UserSettings.KEY_PLAYER_NAME,
            UserSettings.KEY_SYNC_OFFSET_MS,
            UserSettings.KEY_LOW_MEMORY_MODE,
            UserSettings.KEY_PREFERRED_CODEC,
            UserSettings.KEY_FULL_SCREEN_MODE,
            UserSettings.KEY_KEEP_SCREEN_ON,
            UserSettings.KEY_HIGH_POWER_MODE,
            UserSettings.KEY_REMOTE_SERVERS,
            UserSettings.KEY_PROXY_SERVERS
        )

        // All keys should be unique
        assertEquals("All preference keys should be unique",
            keys.size, keys.toSet().size)

        // No key should be blank
        keys.forEach { key ->
            assertTrue("Preference key should not be blank", key.isNotBlank())
        }
    }

    @Test
    fun `sync offset range constants are sensible`() {
        assertEquals(-5000, UserSettings.SYNC_OFFSET_MIN)
        assertEquals(5000, UserSettings.SYNC_OFFSET_MAX)
        assertEquals(0, UserSettings.SYNC_OFFSET_DEFAULT)
        assertTrue(UserSettings.SYNC_OFFSET_MIN < UserSettings.SYNC_OFFSET_DEFAULT)
        assertTrue(UserSettings.SYNC_OFFSET_DEFAULT < UserSettings.SYNC_OFFSET_MAX)
    }

    // ========== UnifiedServer Model Tests ==========

    @Test
    fun `configuredMethods returns all configured connection types`() {
        val server = UnifiedServer(
            id = "s1", name = "Full",
            local = LocalConnection("10.0.0.1:8927"),
            remote = RemoteConn("ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
            proxy = ProxyConnection("https://proxy.test.com/ss", "tok")
        )

        val methods = server.configuredMethods
        assertEquals(3, methods.size)
        assertTrue(methods.contains(com.sendspindroid.model.ConnectionType.LOCAL))
        assertTrue(methods.contains(com.sendspindroid.model.ConnectionType.REMOTE))
        assertTrue(methods.contains(com.sendspindroid.model.ConnectionType.PROXY))
    }

    @Test
    fun `hasAnyConnection is false for empty server`() {
        val server = UnifiedServer(id = "empty", name = "Empty")
        assertFalse(server.hasAnyConnection)
    }

    @Test
    fun `hasAnyConnection is true with any connection`() {
        val server = UnifiedServer(
            id = "s1", name = "Local Only",
            local = LocalConnection("10.0.0.1:8927")
        )
        assertTrue(server.hasAnyConnection)
    }

    @Test
    fun `remote connection formatted ID has dashes`() {
        val remote = RemoteConn("VVPN3TLP34YMGIZDINCEKQKSIR")
        assertTrue(remote.formattedId.contains("-"))
        assertEquals("VVPN3-TLP34-YMGIZ-DINCE-KQKSI-R", remote.formattedId)
    }
}
