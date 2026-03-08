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
import io.mockk.verify
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
 * Tests that SendSpinClient.ConnectionState transitions follow the expected
 * lifecycle: Disconnected -> Connecting -> Connected -> Error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendSpinClientConnectionStateTest {

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
        client.destroy()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `initial state is Disconnected`() {
        assertTrue(
            "Client should start in Disconnected state",
            client.connectionState.value is SendSpinClient.ConnectionState.Disconnected
        )
    }

    @Test
    fun `connectLocal transitions to Connecting`() {
        // connectLocal will call prepareForConnection() which sets Connecting,
        // then try to create a WebSocketTransport (which will fail in test, but
        // the state should already be Connecting before that).
        try {
            client.connectLocal("127.0.0.1:8080")
        } catch (_: Exception) {
            // Transport creation may fail in unit test - that is expected
        }

        // After prepareForConnection(), state should be Connecting
        // (may have moved to Error if transport creation failed synchronously)
        val state = client.connectionState.value
        assertTrue(
            "State should be Connecting or Error after connectLocal, was: $state",
            state is SendSpinClient.ConnectionState.Connecting ||
                    state is SendSpinClient.ConnectionState.Error
        )
    }

    @Test
    fun `onHandshakeComplete transitions to Connected`() {
        // Inject a mock transport so the client thinks it has a connection
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

        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, fakeTransport)

        // Create the inner TransportEventListener and send a server/hello
        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        val listener = constructor.newInstance(client) as SendSpinTransport.Listener

        // Simulate receiving server/hello
        val serverHello = """{"type":"server/hello","payload":{"name":"TestServer","server_id":"srv-1","protocol_version":1,"active_roles":["player"]}}"""
        listener.onMessage(serverHello)

        val state = client.connectionState.value
        assertTrue(
            "State should be Connected after handshake, was: $state",
            state is SendSpinClient.ConnectionState.Connected
        )
        assertEquals(
            "TestServer",
            (state as SendSpinClient.ConnectionState.Connected).serverName
        )
    }

    @Test
    fun `non-recoverable transport failure transitions to Error`() {
        // Inject transport and set up connection info
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

        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, fakeTransport)

        // Create the TransportEventListener
        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        val listener = constructor.newInstance(client) as SendSpinTransport.Listener

        // Simulate a non-recoverable failure
        listener.onFailure(java.net.ConnectException("Connection refused"), isRecoverable = false)

        val state = client.connectionState.value
        assertTrue(
            "State should be Error after non-recoverable failure, was: $state",
            state is SendSpinClient.ConnectionState.Error
        )
    }

    @Test
    fun `disconnect transitions from Connected back to Disconnected`() {
        // First get to Connected state
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

        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, fakeTransport)

        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        val listener = constructor.newInstance(client) as SendSpinTransport.Listener

        // Get to Connected
        val serverHello = """{"type":"server/hello","payload":{"name":"TestServer","server_id":"srv-1","protocol_version":1,"active_roles":["player"]}}"""
        listener.onMessage(serverHello)
        assertTrue(client.connectionState.value is SendSpinClient.ConnectionState.Connected)

        // Disconnect
        client.disconnect()

        assertTrue(
            "State should be Disconnected after disconnect",
            client.connectionState.value is SendSpinClient.ConnectionState.Disconnected
        )
    }

    @Test
    fun `full lifecycle Disconnected to Connecting to Connected to Error`() {
        // Verify initial state
        assertTrue(client.connectionState.value is SendSpinClient.ConnectionState.Disconnected)

        // Set up for connecting
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

        // Manually set state to Connecting (as prepareForConnection does)
        val stateField = SendSpinClient::class.java.getDeclaredField("_connectionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(client) as kotlinx.coroutines.flow.MutableStateFlow<SendSpinClient.ConnectionState>
        stateFlow.value = SendSpinClient.ConnectionState.Connecting
        assertTrue(client.connectionState.value is SendSpinClient.ConnectionState.Connecting)

        // Inject transport
        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, fakeTransport)

        // Create listener
        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        val listener = constructor.newInstance(client) as SendSpinTransport.Listener

        // Transition to Connected
        val serverHello = """{"type":"server/hello","payload":{"name":"TestServer","server_id":"srv-1","protocol_version":1,"active_roles":["player"]}}"""
        listener.onMessage(serverHello)
        assertTrue(client.connectionState.value is SendSpinClient.ConnectionState.Connected)

        // Transition to Error via non-recoverable failure
        listener.onFailure(java.net.ConnectException("Connection refused"), isRecoverable = false)
        assertTrue(
            "State should be Error at end of lifecycle",
            client.connectionState.value is SendSpinClient.ConnectionState.Error
        )
    }
}
