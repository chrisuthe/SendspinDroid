package com.sendspindroid

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * Tests for ServerRepository thread-safety fix (H-26).
 *
 * Verifies:
 * 1. addDiscoveredServer() uses atomic update -- concurrent calls never lose entries.
 * 2. removeDiscoveredServer() uses atomic update -- concurrent removes are consistent.
 * 3. Mixed add/remove from multiple threads converges to a correct final state.
 */
class ServerRepositoryThreadSafetyTest {

    @Before
    fun setUp() {
        // Reset discovered servers to a clean state before each test.
        // ServerRepository is a singleton, so we clear transient state.
        ServerRepository.clearDiscoveredServers()
    }

    @After
    fun tearDown() {
        ServerRepository.clearDiscoveredServers()
    }

    @Test
    fun `addDiscoveredServer is idempotent for same address`() {
        val server = ServerInfo("Room A", "192.168.1.10:8927")

        ServerRepository.addDiscoveredServer(server)
        ServerRepository.addDiscoveredServer(server)
        ServerRepository.addDiscoveredServer(server)

        val servers = ServerRepository.discoveredServers.value
        assertEquals(
            "Same address added multiple times should appear once",
            1, servers.size
        )
    }

    @Test
    fun `concurrent addDiscoveredServer calls do not lose entries`() {
        // This is the core H-26 scenario: without atomic update{}, two concurrent
        // add calls could read the same snapshot and one overwrites the other.
        val threadCount = 50
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    barrier.await() // all threads start simultaneously
                    val server = ServerInfo("Server-$i", "192.168.1.$i:8927")
                    ServerRepository.addDiscoveredServer(server)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val servers = ServerRepository.discoveredServers.value
        assertEquals(
            "All $threadCount concurrent adds must be preserved (got ${servers.size})",
            threadCount, servers.size
        )

        // Verify all unique addresses are present
        val addresses = servers.map { it.address }.toSet()
        for (i in 0 until threadCount) {
            assertTrue(
                "Server at 192.168.1.$i:8927 must be present",
                "192.168.1.$i:8927" in addresses
            )
        }
    }

    @Test
    fun `concurrent removeDiscoveredServer does not throw or corrupt state`() {
        // Pre-populate with servers
        for (i in 0 until 20) {
            ServerRepository.addDiscoveredServer(ServerInfo("Server-$i", "10.0.0.$i:8927"))
        }
        assertEquals(20, ServerRepository.discoveredServers.value.size)

        // Concurrently remove all of them
        val threadCount = 20
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    barrier.await()
                    ServerRepository.removeDiscoveredServer("10.0.0.$i:8927")
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val remaining = ServerRepository.discoveredServers.value
        assertTrue(
            "All servers should be removed, but ${remaining.size} remain: ${remaining.map { it.address }}",
            remaining.isEmpty()
        )
    }

    @Test
    fun `concurrent add and remove converge to correct state`() {
        // Add servers 0-29. Then concurrently remove even-numbered ones and
        // add servers 30-39. The final state should be: odd 0-29 + all 30-39.
        for (i in 0 until 30) {
            ServerRepository.addDiscoveredServer(ServerInfo("Server-$i", "10.0.0.$i:8927"))
        }

        val threadCount = 25 // 15 removals + 10 adds
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        // Remove even-indexed servers (0, 2, 4, ... 28) = 15 threads
        for (i in 0 until 30 step 2) {
            executor.submit {
                try {
                    barrier.await()
                    ServerRepository.removeDiscoveredServer("10.0.0.$i:8927")
                } finally {
                    latch.countDown()
                }
            }
        }

        // Add servers 30-39 = 10 threads
        for (i in 30 until 40) {
            executor.submit {
                try {
                    barrier.await()
                    ServerRepository.addDiscoveredServer(ServerInfo("Server-$i", "10.0.0.$i:8927"))
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val servers = ServerRepository.discoveredServers.value
        val addresses = servers.map { it.address }.toSet()

        // Expected: odd numbers 1,3,5,...,29 (15 servers) + 30-39 (10 servers) = 25
        assertEquals("Expected 25 servers, got ${servers.size}", 25, servers.size)

        // Verify even-numbered originals are removed
        for (i in 0 until 30 step 2) {
            assertFalse(
                "Even-indexed server 10.0.0.$i:8927 should have been removed",
                "10.0.0.$i:8927" in addresses
            )
        }

        // Verify odd-numbered originals remain
        for (i in 1 until 30 step 2) {
            assertTrue(
                "Odd-indexed server 10.0.0.$i:8927 should still be present",
                "10.0.0.$i:8927" in addresses
            )
        }

        // Verify new servers 30-39 are present
        for (i in 30 until 40) {
            assertTrue(
                "Newly added server 10.0.0.$i:8927 should be present",
                "10.0.0.$i:8927" in addresses
            )
        }
    }
}
