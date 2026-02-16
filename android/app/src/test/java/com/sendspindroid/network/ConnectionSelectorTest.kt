package com.sendspindroid.network

import android.util.Log
import com.sendspindroid.model.*
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConnectionSelectorTest {

    private val localConn = LocalConnection("192.168.1.100", "/sendspin")
    private val remoteConn = RemoteConnection("abc123")
    private val proxyConn = ProxyConnection("https://proxy.example.com", "token123")

    private fun server(
        local: LocalConnection? = null,
        remote: RemoteConnection? = null,
        proxy: ProxyConnection? = null,
        preference: ConnectionPreference = ConnectionPreference.AUTO
    ) = UnifiedServer(
        id = "test-server",
        name = "Test Server",
        local = local,
        remote = remote,
        proxy = proxy,
        connectionPreference = preference
    )

    private fun networkState(transport: TransportType) = NetworkState(
        transportType = transport,
        isConnected = true
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- Priority order ---

    @Test
    fun getPriorityOrder_wifi_localFirst() {
        val order = ConnectionSelector.getPriorityOrder(TransportType.WIFI)
        assertEquals(listOf(ConnectionType.LOCAL, ConnectionType.PROXY, ConnectionType.REMOTE), order)
    }

    @Test
    fun getPriorityOrder_ethernet_localFirst() {
        val order = ConnectionSelector.getPriorityOrder(TransportType.ETHERNET)
        assertEquals(listOf(ConnectionType.LOCAL, ConnectionType.PROXY, ConnectionType.REMOTE), order)
    }

    @Test
    fun getPriorityOrder_cellular_noLocal() {
        val order = ConnectionSelector.getPriorityOrder(TransportType.CELLULAR)
        assertEquals(listOf(ConnectionType.PROXY, ConnectionType.REMOTE), order)
        assertFalse(order.contains(ConnectionType.LOCAL))
    }

    @Test
    fun getPriorityOrder_vpn_proxyFirst() {
        val order = ConnectionSelector.getPriorityOrder(TransportType.VPN)
        assertEquals(listOf(ConnectionType.PROXY, ConnectionType.REMOTE, ConnectionType.LOCAL), order)
    }

    @Test
    fun getPriorityOrder_unknown_proxyFirst() {
        val order = ConnectionSelector.getPriorityOrder(TransportType.UNKNOWN)
        assertEquals(listOf(ConnectionType.PROXY, ConnectionType.REMOTE, ConnectionType.LOCAL), order)
    }

    // --- Auto selection ---

    @Test
    fun selectConnection_wifiWithAllMethods_selectsLocal() {
        val result = ConnectionSelector.selectConnection(
            server(local = localConn, remote = remoteConn, proxy = proxyConn),
            networkState(TransportType.WIFI)
        )
        assertTrue(result is ConnectionSelector.SelectedConnection.Local)
        assertEquals("192.168.1.100", (result as ConnectionSelector.SelectedConnection.Local).address)
    }

    @Test
    fun selectConnection_cellularWithAllMethods_selectsProxy() {
        val result = ConnectionSelector.selectConnection(
            server(local = localConn, remote = remoteConn, proxy = proxyConn),
            networkState(TransportType.CELLULAR)
        )
        assertTrue(result is ConnectionSelector.SelectedConnection.Proxy)
    }

    @Test
    fun selectConnection_wifiNoLocal_selectsProxy() {
        val result = ConnectionSelector.selectConnection(
            server(remote = remoteConn, proxy = proxyConn),
            networkState(TransportType.WIFI)
        )
        assertTrue(result is ConnectionSelector.SelectedConnection.Proxy)
    }

    @Test
    fun selectConnection_noMethodsConfigured_returnsNull() {
        val result = ConnectionSelector.selectConnection(
            server(),
            networkState(TransportType.WIFI)
        )
        assertNull(result)
    }

    // --- Preference overrides ---

    @Test
    fun selectConnection_localOnlyPreference_selectsLocal() {
        val result = ConnectionSelector.selectConnection(
            server(local = localConn, remote = remoteConn, proxy = proxyConn,
                preference = ConnectionPreference.LOCAL_ONLY),
            networkState(TransportType.CELLULAR) // Would normally skip local
        )
        assertTrue(result is ConnectionSelector.SelectedConnection.Local)
    }

    @Test
    fun selectConnection_localOnlyPreference_noLocal_returnsNull() {
        val result = ConnectionSelector.selectConnection(
            server(remote = remoteConn, proxy = proxyConn,
                preference = ConnectionPreference.LOCAL_ONLY),
            networkState(TransportType.WIFI)
        )
        assertNull(result)
    }

    @Test
    fun selectConnection_remoteOnlyPreference_selectsRemote() {
        val result = ConnectionSelector.selectConnection(
            server(local = localConn, remote = remoteConn, proxy = proxyConn,
                preference = ConnectionPreference.REMOTE_ONLY),
            networkState(TransportType.WIFI)
        )
        assertTrue(result is ConnectionSelector.SelectedConnection.Remote)
    }

    @Test
    fun selectConnection_proxyOnlyPreference_selectsProxy() {
        val result = ConnectionSelector.selectConnection(
            server(local = localConn, remote = remoteConn, proxy = proxyConn,
                preference = ConnectionPreference.PROXY_ONLY),
            networkState(TransportType.WIFI)
        )
        assertTrue(result is ConnectionSelector.SelectedConnection.Proxy)
    }

    // --- shouldAttemptLocal ---

    @Test
    fun shouldAttemptLocal_wifi_true() {
        assertTrue(ConnectionSelector.shouldAttemptLocal(TransportType.WIFI))
    }

    @Test
    fun shouldAttemptLocal_cellular_false() {
        assertFalse(ConnectionSelector.shouldAttemptLocal(TransportType.CELLULAR))
    }

    @Test
    fun shouldAttemptLocal_ethernet_true() {
        assertTrue(ConnectionSelector.shouldAttemptLocal(TransportType.ETHERNET))
    }

    @Test
    fun shouldAttemptLocal_vpn_true() {
        assertTrue(ConnectionSelector.shouldAttemptLocal(TransportType.VPN))
    }

    // --- getConnectionDescription ---

    @Test
    fun getConnectionDescription_local_containsAddress() {
        val desc = ConnectionSelector.getConnectionDescription(
            ConnectionSelector.SelectedConnection.Local("192.168.1.50", "/sendspin")
        )
        assertTrue(desc.contains("192.168.1.50"))
        assertTrue(desc.contains("Local"))
    }

    @Test
    fun getConnectionDescription_remote_returnsRemoteAccess() {
        val desc = ConnectionSelector.getConnectionDescription(
            ConnectionSelector.SelectedConnection.Remote("abc123")
        )
        assertEquals("Remote Access", desc)
    }

    @Test
    fun getConnectionDescription_proxy_returnsProxy() {
        val desc = ConnectionSelector.getConnectionDescription(
            ConnectionSelector.SelectedConnection.Proxy("https://example.com", "token")
        )
        assertEquals("Proxy", desc)
    }
}
