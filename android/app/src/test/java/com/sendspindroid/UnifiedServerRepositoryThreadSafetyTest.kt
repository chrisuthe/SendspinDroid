package com.sendspindroid

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * Tests for UnifiedServerRepository thread-safety (migrated from ServerRepositoryThreadSafetyTest).
 *
 * Verifies:
 * 1. addDiscoveredServer() -- concurrent calls never lose entries.
 * 2. removeDiscoveredServer() -- concurrent removes are consistent.
 * 3. Mixed add/remove from multiple threads converges to a correct final state.
 *
 * Note: Discovered server methods do not require initialize() -- they operate
 * on the transient _discoveredServers MutableStateFlow without SharedPreferences.
 */
class UnifiedServerRepositoryThreadSafetyTest {

    @Before
    fun setUp() {
        UnifiedServerRepository.clearDiscoveredServers()
    }

    @After
    fun tearDown() {
        UnifiedServerRepository.clearDiscoveredServers()
    }

    @Test
    fun `addDiscoveredServer is idempotent for same address`() {
        UnifiedServerRepository.addDiscoveredServer("Room A", "192.168.1.10:8927")
        UnifiedServerRepository.addDiscoveredServer("Room A", "192.168.1.10:8927")
        UnifiedServerRepository.addDiscoveredServer("Room A", "192.168.1.10:8927")

        val servers = UnifiedServerRepository.discoveredServers.value
        assertEquals(
            "Same address added multiple times should appear once",
            1, servers.size
        )
    }

    @Test
    fun `concurrent addDiscoveredServer calls do not lose entries`() {
        val threadCount = 50
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    barrier.await()
                    UnifiedServerRepository.addDiscoveredServer(
                        "Server-$i", "192.168.1.$i:8927"
                    )
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val servers = UnifiedServerRepository.discoveredServers.value
        assertEquals(
            "All $threadCount concurrent adds must be preserved (got ${servers.size})",
            threadCount, servers.size
        )

        val addresses = servers.mapNotNull { it.local?.address }.toSet()
        for (i in 0 until threadCount) {
            assertTrue(
                "Server at 192.168.1.$i:8927 must be present",
                "192.168.1.$i:8927" in addresses
            )
        }
    }

    @Test
    fun `concurrent removeDiscoveredServer does not throw or corrupt state`() {
        for (i in 0 until 20) {
            UnifiedServerRepository.addDiscoveredServer("Server-$i", "10.0.0.$i:8927")
        }
        assertEquals(20, UnifiedServerRepository.discoveredServers.value.size)

        val threadCount = 20
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    barrier.await()
                    UnifiedServerRepository.removeDiscoveredServer("10.0.0.$i:8927")
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val remaining = UnifiedServerRepository.discoveredServers.value
        assertTrue(
            "All servers should be removed, but ${remaining.size} remain",
            remaining.isEmpty()
        )
    }

    @Test
    fun `concurrent add and remove converge to correct state`() {
        // Add servers 0-29. Then concurrently remove even-numbered ones and
        // add servers 30-39. The final state should be: odd 0-29 + all 30-39.
        for (i in 0 until 30) {
            UnifiedServerRepository.addDiscoveredServer("Server-$i", "10.0.0.$i:8927")
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
                    UnifiedServerRepository.removeDiscoveredServer("10.0.0.$i:8927")
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
                    UnifiedServerRepository.addDiscoveredServer("Server-$i", "10.0.0.$i:8927")
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val servers = UnifiedServerRepository.discoveredServers.value
        val addresses = servers.mapNotNull { it.local?.address }.toSet()

        // Expected: odd numbers 1,3,5,...,29 (15 servers) + 30-39 (10 servers) = 25
        assertEquals("Expected 25 servers, got ${servers.size}", 25, servers.size)

        for (i in 0 until 30 step 2) {
            assertFalse(
                "Even-indexed server 10.0.0.$i:8927 should have been removed",
                "10.0.0.$i:8927" in addresses
            )
        }

        for (i in 1 until 30 step 2) {
            assertTrue(
                "Odd-indexed server 10.0.0.$i:8927 should still be present",
                "10.0.0.$i:8927" in addresses
            )
        }

        for (i in 30 until 40) {
            assertTrue(
                "Newly added server 10.0.0.$i:8927 should be present",
                "10.0.0.$i:8927" in addresses
            )
        }
    }
}
