package com.sendspindroid.sendspin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.TransportState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
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
 * Tests that the time filter is frozen on disconnect (first reconnect attempt)
 * and thawed on successful reconnection (handshake complete).
 *
 * This ensures clock synchronization state is preserved across brief network
 * drops so playback can continue from buffer without losing sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendSpinClientTimeFilterFreezeTest {

    private lateinit var mockContext: Context
    private lateinit var mockCallback: SendSpinClient.Callback
    private lateinit var client: SendSpinClient

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkObject(UserSettings)
        every { UserSettings.getPlayerId() } returns "test-player-id"
        every { UserSettings.getPreferredCodec() } returns "opus"
        every { UserSettings.lowMemoryMode } returns false
        every { UserSettings.highPowerMode } returns false

        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.isCodecSupported(any()) } returns true

        mockkStatic(PreferenceManager::class)
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        every { PreferenceManager.getDefaultSharedPreferences(any()) } returns mockPrefs

        mockContext = mockk(relaxed = true)
        mockCallback = mockk(relaxed = true)

        client = SendSpinClient(mockContext, "TestDevice", mockCallback)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun getTimeFilter(): SendspinTimeFilter {
        return client.getTimeFilter()
    }

    /**
     * Seed the time filter with enough measurements so freeze() actually saves state.
     * freeze() is a no-op if !isReady (measurementCount < MIN_MEASUREMENTS).
     */
    private fun seedTimeFilter() {
        val tf = getTimeFilter()
        val now = System.nanoTime() / 1000
        // Need at least 2 measurements for isReady
        tf.addMeasurement(1000L, 500L, now)
        tf.addMeasurement(1010L, 500L, now + 500_000)
        assertTrue("Time filter should be ready after 2 measurements", tf.isReady)
    }

    private fun setupForReconnection(): SendSpinTransport.Listener {
        val fakeTransport = object : SendSpinTransport {
            override val state = TransportState.Connected
            override val isConnected = true
            override fun connect() {}
            override fun send(text: String) = true
            override fun send(bytes: ByteArray) = true
            override fun setListener(listener: SendSpinTransport.Listener?) {}
            override fun close(code: Int, reason: String) {}
            override fun destroy() {}
        }

        val addrField = SendSpinClient::class.java.getDeclaredField("serverAddress")
        addrField.isAccessible = true
        addrField.set(client, "127.0.0.1:8080")

        val pathField = SendSpinClient::class.java.getDeclaredField("serverPath")
        pathField.isAccessible = true
        pathField.set(client, "/sendspin")

        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, fakeTransport)

        val handshakeField = SendSpinClient::class.java.superclass.getDeclaredField("handshakeComplete")
        handshakeField.isAccessible = true
        handshakeField.set(client, true)

        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(client) as SendSpinTransport.Listener
    }

    @Test
    fun `time filter is frozen on first reconnect attempt`() {
        seedTimeFilter()
        setupForReconnection()

        val tf = getTimeFilter()
        assertFalse("Time filter should not be frozen initially", tf.isFrozen)

        // Trigger first reconnect attempt
        val attemptReconnect = SendSpinClient::class.java.getDeclaredMethod("attemptReconnect")
        attemptReconnect.isAccessible = true
        attemptReconnect.invoke(client)

        assertTrue(
            "Time filter should be frozen after first reconnect attempt",
            tf.isFrozen
        )
    }

    @Test
    fun `time filter stays frozen on subsequent reconnect attempts`() {
        seedTimeFilter()
        setupForReconnection()

        val tf = getTimeFilter()
        val attemptReconnect = SendSpinClient::class.java.getDeclaredMethod("attemptReconnect")
        attemptReconnect.isAccessible = true

        // First attempt freezes
        attemptReconnect.invoke(client)
        assertTrue("Frozen after attempt 1", tf.isFrozen)

        // Second attempt - freeze is only called on attempt 1; isFrozen should still be true
        attemptReconnect.invoke(client)
        assertTrue("Still frozen after attempt 2", tf.isFrozen)

        // Third attempt
        attemptReconnect.invoke(client)
        assertTrue("Still frozen after attempt 3", tf.isFrozen)
    }

    @Test
    fun `time filter is thawed on handshake complete after reconnection`() {
        seedTimeFilter()
        setupForReconnection()

        val tf = getTimeFilter()
        val attemptReconnect = SendSpinClient::class.java.getDeclaredMethod("attemptReconnect")
        attemptReconnect.isAccessible = true

        // Freeze via reconnect attempt
        attemptReconnect.invoke(client)
        assertTrue("Should be frozen after reconnect attempt", tf.isFrozen)

        // Now simulate successful reconnection by injecting a new transport
        // and receiving server/hello
        val fakeTransport2 = object : SendSpinTransport {
            override val state = TransportState.Connected
            override val isConnected = true
            override fun connect() {}
            override fun send(text: String) = true
            override fun send(bytes: ByteArray) = true
            override fun setListener(listener: SendSpinTransport.Listener?) {}
            override fun close(code: Int, reason: String) {}
            override fun destroy() {}
        }

        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, fakeTransport2)

        // Create new listener
        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        val listener = constructor.newInstance(client) as SendSpinTransport.Listener

        // Simulate server/hello - this triggers onHandshakeComplete which calls thaw()
        val serverHello = """{"type":"server/hello","payload":{"name":"TestServer","server_id":"srv-1","protocol_version":1,"active_roles":["player"]}}"""
        listener.onMessage(serverHello)

        assertFalse(
            "Time filter should be thawed after successful reconnection handshake",
            tf.isFrozen
        )
    }

    @Test
    fun `time filter is reset and discarded when reconnect attempts exhausted`() {
        seedTimeFilter()
        setupForReconnection()
        every { UserSettings.highPowerMode } returns false

        val tf = getTimeFilter()
        val attemptReconnect = SendSpinClient::class.java.getDeclaredMethod("attemptReconnect")
        attemptReconnect.isAccessible = true

        // Exhaust all 5 attempts
        for (i in 1..5) {
            attemptReconnect.invoke(client)
        }
        assertTrue("Should be frozen during reconnection", tf.isFrozen)

        // 6th attempt should exhaust and call resetAndDiscard
        attemptReconnect.invoke(client)

        assertFalse(
            "Time filter should not be frozen after exhaustion (resetAndDiscard clears frozen state)",
            tf.isFrozen
        )
    }

    @Test
    fun `onReconnected callback fires when handshake completes after freeze`() {
        seedTimeFilter()
        setupForReconnection()

        val tf = getTimeFilter()
        val attemptReconnect = SendSpinClient::class.java.getDeclaredMethod("attemptReconnect")
        attemptReconnect.isAccessible = true

        // Trigger freeze
        attemptReconnect.invoke(client)
        assertTrue(tf.isFrozen)

        // Inject new transport and simulate reconnection
        val fakeTransport2 = object : SendSpinTransport {
            override val state = TransportState.Connected
            override val isConnected = true
            override fun connect() {}
            override fun send(text: String) = true
            override fun send(bytes: ByteArray) = true
            override fun setListener(listener: SendSpinTransport.Listener?) {}
            override fun close(code: Int, reason: String) {}
            override fun destroy() {}
        }

        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, fakeTransport2)

        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        val listener = constructor.newInstance(client) as SendSpinTransport.Listener

        val serverHello = """{"type":"server/hello","payload":{"name":"TestServer","server_id":"srv-1","protocol_version":1,"active_roles":["player"]}}"""
        listener.onMessage(serverHello)

        io.mockk.verify(exactly = 1) { mockCallback.onReconnected() }
    }
}
