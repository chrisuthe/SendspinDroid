package com.sendspindroid

import com.sendspindroid.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for UnifiedServerRepository serialization round-trip.
 *
 * Uses reflection to exercise the private serializeServers/parseServers
 * methods directly, verifying that a server with all fields survives
 * the pipe-delimited persistence format.
 */
class UnifiedServerRepositorySerializationTest {

    @Test
    fun `server with all fields survives serialize and parse round-trip`() {
        val server = UnifiedServer(
            id = "test-uuid-123",
            name = "Living Room Speaker",
            lastConnectedMs = 1700000000000L,
            connectionPreference = ConnectionPreference.LOCAL_ONLY,
            local = LocalConnection(
                address = "192.168.1.100:8927",
                path = "/sendspin"
            ),
            remote = RemoteConnection(
                remoteId = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            ),
            proxy = ProxyConnection(
                url = "https://proxy.example.com",
                authToken = "secret-token-abc",
                username = "testuser"
            ),
            isDiscovered = false,
            isDefaultServer = true,
            isMusicAssistant = true
        )

        val servers = listOf(server)

        // Access private serializeServers method
        val serializeMethod = UnifiedServerRepository::class.java
            .getDeclaredMethod("serializeServers", List::class.java)
        serializeMethod.isAccessible = true
        val serialized = serializeMethod.invoke(UnifiedServerRepository, servers) as String

        // Access private parseServers method
        val parseMethod = UnifiedServerRepository::class.java
            .getDeclaredMethod("parseServers", String::class.java)
        parseMethod.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val parsed = parseMethod.invoke(UnifiedServerRepository, serialized) as List<UnifiedServer>

        assertEquals(1, parsed.size)
        val restored = parsed[0]

        assertEquals(server.id, restored.id)
        assertEquals(server.name, restored.name)
        assertEquals(server.lastConnectedMs, restored.lastConnectedMs)
        assertEquals(server.connectionPreference, restored.connectionPreference)

        // Local connection
        assertNotNull(restored.local)
        assertEquals(server.local!!.address, restored.local!!.address)
        assertEquals(server.local!!.path, restored.local!!.path)

        // Remote connection
        assertNotNull(restored.remote)
        assertEquals(server.remote!!.remoteId, restored.remote!!.remoteId)

        // Proxy connection
        assertNotNull(restored.proxy)
        assertEquals(server.proxy!!.url, restored.proxy!!.url)
        assertEquals(server.proxy!!.authToken, restored.proxy!!.authToken)
        assertEquals(server.proxy!!.username, restored.proxy!!.username)

        // Boolean fields
        assertEquals(server.isDefaultServer, restored.isDefaultServer)
        assertEquals(server.isMusicAssistant, restored.isMusicAssistant)
        assertFalse("Restored server should not be discovered", restored.isDiscovered)
    }

    @Test
    fun `server with null optional fields survives round-trip`() {
        val server = UnifiedServer(
            id = "minimal-id",
            name = "Minimal Server",
            lastConnectedMs = 0L,
            connectionPreference = ConnectionPreference.AUTO,
            local = null,
            remote = null,
            proxy = null,
            isDefaultServer = false,
            isMusicAssistant = false
        )

        val serialized = invokeSerialize(listOf(server))
        val parsed = invokeParse(serialized)

        assertEquals(1, parsed.size)
        val restored = parsed[0]

        assertEquals("minimal-id", restored.id)
        assertEquals("Minimal Server", restored.name)
        assertNull(restored.local)
        assertNull(restored.remote)
        assertNull(restored.proxy)
        assertFalse(restored.isDefaultServer)
        assertFalse(restored.isMusicAssistant)
    }

    @Test
    fun `multiple servers survive round-trip`() {
        val servers = listOf(
            UnifiedServer(
                id = "srv-1", name = "Server A",
                local = LocalConnection("10.0.0.1:8927")
            ),
            UnifiedServer(
                id = "srv-2", name = "Server B",
                remote = RemoteConnection("ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
                isDefaultServer = true
            ),
            UnifiedServer(
                id = "srv-3", name = "Server C",
                proxy = ProxyConnection("https://proxy.test", "token123"),
                isMusicAssistant = true
            )
        )

        val serialized = invokeSerialize(servers)
        val parsed = invokeParse(serialized)

        assertEquals(3, parsed.size)
        assertEquals("srv-1", parsed[0].id)
        assertEquals("srv-2", parsed[1].id)
        assertEquals("srv-3", parsed[2].id)
        assertNotNull(parsed[0].local)
        assertNotNull(parsed[1].remote)
        assertNotNull(parsed[2].proxy)
        assertTrue(parsed[1].isDefaultServer)
        assertTrue(parsed[2].isMusicAssistant)
    }

    @Test
    fun `proxy with null username survives round-trip`() {
        val server = UnifiedServer(
            id = "proxy-test",
            name = "Proxy No User",
            proxy = ProxyConnection(
                url = "https://proxy.example.com",
                authToken = "token",
                username = null
            )
        )

        val serialized = invokeSerialize(listOf(server))
        val parsed = invokeParse(serialized)

        assertEquals(1, parsed.size)
        assertNotNull(parsed[0].proxy)
        assertNull(parsed[0].proxy!!.username)
    }

    // ========== Helpers ==========

    private fun invokeSerialize(servers: List<UnifiedServer>): String {
        val method = UnifiedServerRepository::class.java
            .getDeclaredMethod("serializeServers", List::class.java)
        method.isAccessible = true
        return method.invoke(UnifiedServerRepository, servers) as String
    }

    private fun invokeParse(data: String): List<UnifiedServer> {
        val method = UnifiedServerRepository::class.java
            .getDeclaredMethod("parseServers", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(UnifiedServerRepository, data) as List<UnifiedServer>
    }
}
