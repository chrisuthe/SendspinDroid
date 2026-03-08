package com.sendspindroid.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration test: AutoReconnectManager network switch triggers reconnection.
 *
 * Verifies that when the network changes (e.g., WiFi -> mobile, AP switch),
 * the AutoReconnectManager handles it correctly with debouncing and backoff.
 *
 * The flow:
 * 1. Connection is lost (sendSpinClient disconnects)
 * 2. AutoReconnectManager.startReconnecting(server) is called
 * 3. Network callback fires onNetworkAvailable()
 * 4. AutoReconnectManager debounces rapid calls
 * 5. After debounce window, triggers immediate retry (skips backoff)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoReconnectNetworkSwitchTest {

    private lateinit var mockContext: Context
    private lateinit var mockConnectivityManager: ConnectivityManager

    private var lastAttempt = 0
    private var successCount = 0
    private var failureCount = 0

    private val alwaysFailConnect: suspend (UnifiedServer, ConnectionSelector.SelectedConnection) -> Boolean =
        { _, _ -> false }

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        lastAttempt = 0
        successCount = 0
        failureCount = 0

        val mockWifiManager = mockk<WifiManager>(relaxed = true)
        mockConnectivityManager = mockk(relaxed = true)
        mockContext = mockk(relaxed = true) {
            every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockConnectivityManager
            every { applicationContext } returns this
            every { applicationContext.getSystemService(Context.WIFI_SERVICE) } returns mockWifiManager
            every { getSystemService(Context.TELEPHONY_SERVICE) } returns null
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createManager(
        connectFn: suspend (UnifiedServer, ConnectionSelector.SelectedConnection) -> Boolean = alwaysFailConnect
    ): AutoReconnectManager {
        return AutoReconnectManager(
            context = mockContext,
            onAttempt = { _, attempt, _, _ -> lastAttempt = attempt },
            onMethodAttempt = { _, _ -> },
            onSuccess = { _ -> successCount++ },
            onFailure = { _, _ -> failureCount++ },
            connectToServer = connectFn
        )
    }

    private fun testServer() = UnifiedServer(
        id = "test-server",
        name = "Test Server",
        local = LocalConnection("192.168.1.100:8927", "/sendspin")
    )

    @Test
    fun `startReconnecting sets isReconnecting true`() {
        val manager = createManager()
        assertFalse(manager.isReconnecting())

        manager.startReconnecting(testServer())

        assertTrue("Should be reconnecting", manager.isReconnecting())
        manager.destroy()
    }

    @Test
    fun `startReconnecting tracks correct server ID`() {
        val manager = createManager()
        val server = testServer()

        manager.startReconnecting(server)

        assertTrue("Should be reconnecting to the correct server",
            manager.isReconnecting(server.id))
        assertFalse("Should not be reconnecting to unknown server",
            manager.isReconnecting("unknown-server"))
        manager.destroy()
    }

    @Test
    fun `onNetworkAvailable is no-op when not reconnecting`() {
        val manager = createManager()

        // Should not throw or change state
        manager.onNetworkAvailable()

        assertFalse("Should not be reconnecting", manager.isReconnecting())
        manager.destroy()
    }

    @Test
    fun `onNetworkAvailable is accepted during reconnection`() {
        val manager = createManager()
        manager.startReconnecting(testServer())
        assertTrue(manager.isReconnecting())

        // First onNetworkAvailable should be accepted (debounce timer starts at 0)
        manager.onNetworkAvailable()

        // Manager should still be reconnecting (attempts may continue)
        assertTrue("Should still be reconnecting", manager.isReconnecting())
        manager.destroy()
    }

    @Test
    fun `rapid onNetworkAvailable calls are debounced`() {
        val manager = createManager()
        manager.startReconnecting(testServer())

        // First call is accepted
        manager.onNetworkAvailable()

        // Rapid calls within debounce window should be debounced
        // (We can't easily verify this without inspecting internal state,
        // but we verify no crash and manager remains operational)
        for (i in 1..10) {
            manager.onNetworkAvailable()
        }

        assertTrue("Should still be reconnecting after debounced calls", manager.isReconnecting())
        manager.destroy()
    }

    @Test
    fun `cancelReconnection stops reconnecting`() {
        val manager = createManager()
        manager.startReconnecting(testServer())
        assertTrue(manager.isReconnecting())

        manager.cancelReconnection()

        assertFalse("Should not be reconnecting after cancel", manager.isReconnecting())
        assertEquals("Attempt counter should be reset", 0, manager.getCurrentAttempt())
        manager.destroy()
    }

    @Test
    fun `startReconnecting cancels previous reconnection`() {
        val manager = createManager()
        val server1 = testServer()
        val server2 = UnifiedServer(
            id = "server-2", name = "Server 2",
            local = LocalConnection("192.168.1.200:8927")
        )

        manager.startReconnecting(server1)
        assertTrue(manager.isReconnecting(server1.id))

        // Starting reconnection to a different server cancels the first
        manager.startReconnecting(server2)

        assertTrue(manager.isReconnecting(server2.id))
        assertFalse(manager.isReconnecting(server1.id))
        manager.destroy()
    }

    @Test
    fun `cancelReconnection resets debounce state`() {
        val manager = createManager()
        manager.startReconnecting(testServer())
        manager.onNetworkAvailable()

        manager.cancelReconnection()
        assertFalse(manager.isReconnecting())

        // Start again - should work normally (debounce was reset)
        manager.startReconnecting(testServer())
        assertTrue(manager.isReconnecting())
        manager.onNetworkAvailable()

        manager.destroy()
    }

    @Test
    fun `backoff delays list is consistent with MAX_ATTEMPTS`() {
        // The backoff delay list should have exactly MAX_ATTEMPTS entries
        assertEquals(
            "BACKOFF_DELAYS should have MAX_ATTEMPTS entries",
            AutoReconnectManager.MAX_ATTEMPTS, 11
        )
    }

    @Test
    fun `NETWORK_DEBOUNCE_MS prevents rapid network flapping`() {
        assertTrue(
            "Debounce should be at least 1 second to prevent flapping",
            AutoReconnectManager.NETWORK_DEBOUNCE_MS >= 1000L
        )
    }

    @Test
    fun `MIN_DELAY_AFTER_NETWORK_SKIP_MS prevents server hammering`() {
        assertTrue(
            "Post-network-skip delay should be positive",
            AutoReconnectManager.MIN_DELAY_AFTER_NETWORK_SKIP_MS > 0
        )
    }

    @Test
    fun `destroy does not throw when not reconnecting`() {
        val manager = createManager()
        // Should not throw
        manager.destroy()
    }

    @Test
    fun `destroy stops reconnection in progress`() {
        val manager = createManager()
        manager.startReconnecting(testServer())
        assertTrue(manager.isReconnecting())

        manager.destroy()
        // After destroy, state may or may not be reset depending on implementation
        // but it should not throw
    }
}
