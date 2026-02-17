package com.sendspindroid.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for NetworkEvaluator VPN transport detection fix (M-14).
 *
 * Verifies that VPN is correctly detected when the network has both
 * TRANSPORT_VPN and an underlying transport (TRANSPORT_WIFI or TRANSPORT_CELLULAR).
 * Previously, WIFI was checked before VPN in the `when` chain, making VPN
 * unreachable for VPN-over-WiFi connections.
 */
class NetworkEvaluatorVpnTest {

    private lateinit var mockContext: Context
    private lateinit var mockConnectivityManager: ConnectivityManager
    private lateinit var evaluator: NetworkEvaluator

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.w(any(), any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockConnectivityManager = mockk(relaxed = true)
        val mockWifiManager = mockk<WifiManager>(relaxed = true)

        mockContext = mockk(relaxed = true) {
            every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockConnectivityManager
            every { applicationContext } returns this
            every { applicationContext.getSystemService(Context.WIFI_SERVICE) } returns mockWifiManager
            every { getSystemService(Context.TELEPHONY_SERVICE) } returns null
        }

        evaluator = NetworkEvaluator(mockContext)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun mockCapabilities(vararg transports: Int): NetworkCapabilities {
        val caps = mockk<NetworkCapabilities>(relaxed = true)
        for (t in transports) {
            every { caps.hasTransport(t) } returns true
        }
        // Ensure transports NOT in the list return false
        val allTransports = listOf(
            NetworkCapabilities.TRANSPORT_VPN,
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET
        )
        for (t in allTransports) {
            if (t !in transports) {
                every { caps.hasTransport(t) } returns false
            }
        }
        every { caps.linkDownstreamBandwidthKbps } returns 50_000
        every { caps.linkUpstreamBandwidthKbps } returns 10_000
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns true
        return caps
    }

    // ---- VPN-over-WiFi: the key M-14 fix ----

    @Test
    fun `VPN over WiFi is detected as VPN not WiFi`() {
        // Android reports both TRANSPORT_VPN and TRANSPORT_WIFI for VPN-over-WiFi
        val mockNetwork = mockk<Network>(relaxed = true)
        val caps = mockCapabilities(
            NetworkCapabilities.TRANSPORT_VPN,
            NetworkCapabilities.TRANSPORT_WIFI
        )
        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns caps

        evaluator.evaluateCurrentNetwork()

        val state = evaluator.networkState.value
        assertEquals(
            "VPN-over-WiFi should be detected as VPN",
            TransportType.VPN,
            state.transportType
        )
    }

    @Test
    fun `VPN over cellular is detected as VPN not cellular`() {
        val mockNetwork = mockk<Network>(relaxed = true)
        val caps = mockCapabilities(
            NetworkCapabilities.TRANSPORT_VPN,
            NetworkCapabilities.TRANSPORT_CELLULAR
        )
        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns caps

        evaluator.evaluateCurrentNetwork()

        val state = evaluator.networkState.value
        assertEquals(
            "VPN-over-cellular should be detected as VPN",
            TransportType.VPN,
            state.transportType
        )
    }

    // ---- Pure transports (no VPN) still work ----

    @Test
    fun `pure WiFi is detected as WiFi`() {
        val mockNetwork = mockk<Network>(relaxed = true)
        val caps = mockCapabilities(NetworkCapabilities.TRANSPORT_WIFI)
        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns caps

        evaluator.evaluateCurrentNetwork()

        val state = evaluator.networkState.value
        assertEquals(TransportType.WIFI, state.transportType)
    }

    @Test
    fun `pure cellular is detected as cellular`() {
        val mockNetwork = mockk<Network>(relaxed = true)
        val caps = mockCapabilities(NetworkCapabilities.TRANSPORT_CELLULAR)
        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns caps

        evaluator.evaluateCurrentNetwork()

        val state = evaluator.networkState.value
        assertEquals(TransportType.CELLULAR, state.transportType)
    }

    @Test
    fun `pure ethernet is detected as ethernet`() {
        val mockNetwork = mockk<Network>(relaxed = true)
        val caps = mockCapabilities(NetworkCapabilities.TRANSPORT_ETHERNET)
        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns caps

        evaluator.evaluateCurrentNetwork()

        val state = evaluator.networkState.value
        assertEquals(TransportType.ETHERNET, state.transportType)
    }

    @Test
    fun `VPN only (no underlying transport) is detected as VPN`() {
        val mockNetwork = mockk<Network>(relaxed = true)
        val caps = mockCapabilities(NetworkCapabilities.TRANSPORT_VPN)
        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns caps

        evaluator.evaluateCurrentNetwork()

        val state = evaluator.networkState.value
        assertEquals(TransportType.VPN, state.transportType)
    }

    @Test
    fun `no active network reports disconnected`() {
        every { mockConnectivityManager.activeNetwork } returns null

        evaluator.evaluateCurrentNetwork()

        val state = evaluator.networkState.value
        assertFalse(state.isConnected)
        assertEquals(TransportType.UNKNOWN, state.transportType)
    }
}
