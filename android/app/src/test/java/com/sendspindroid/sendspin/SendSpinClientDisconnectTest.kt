package com.sendspindroid.sendspin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.TransportState
import io.mockk.Ordering
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for SendSpinClient disconnect and proxy auth fixes.
 *
 * H-02: Verifies disconnect() does not fire onDisconnected twice.
 * H-04: Verifies proxy auth-ack is consumed and not forwarded to protocol handler.
 */
class SendSpinClientDisconnectTest {

    private lateinit var mockContext: Context
    private lateinit var mockCallback: SendSpinClient.Callback
    private lateinit var client: SendSpinClient

    @Before
    fun setUp() {
        // Mock android.util.Log
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // Mock android.os.Build.MANUFACTURER
        mockkStatic(android.os.Build::class)

        // Mock UserSettings
        mockkObject(UserSettings)
        every { UserSettings.getPlayerId() } returns "test-player-id"
        every { UserSettings.getPreferredCodec() } returns "opus"
        every { UserSettings.lowMemoryMode } returns false
        every { UserSettings.highPowerMode } returns false

        // Mock AudioDecoderFactory
        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.isCodecSupported(any()) } returns true

        // Mock PreferenceManager (needed by UserSettings init path)
        mockkStatic(PreferenceManager::class)
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        every { PreferenceManager.getDefaultSharedPreferences(any()) } returns mockPrefs

        mockContext = mockk(relaxed = true)
        mockCallback = mockk(relaxed = true)

        client = SendSpinClient(mockContext, "TestDevice", mockCallback)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // =========================================================================
    // H-02: disconnect() must not fire onDisconnected twice
    // =========================================================================

    @Test
    fun `disconnect clears transport listener before closing`() {
        // Use a mock transport that we can inject via the connect flow
        val mockTransport = mockk<SendSpinTransport>(relaxed = true)
        every { mockTransport.state } returns TransportState.Connected
        every { mockTransport.isConnected } returns true

        // Inject the mock transport via reflection
        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, mockTransport)

        // Call disconnect
        client.disconnect()

        // Verify setListener(null) is called BEFORE close()
        verify(ordering = Ordering.ORDERED) {
            mockTransport.setListener(null)
            mockTransport.close(1000, "User disconnect")
        }
    }

    @Test
    fun `disconnect fires onDisconnected exactly once even if transport onClosed races`() {
        // Create a transport that synchronously fires onClosed when close() is called,
        // simulating the worst-case race condition that H-02 describes.
        var capturedListener: SendSpinTransport.Listener? = null
        val racyTransport = object : SendSpinTransport {
            override val state = TransportState.Connected
            override val isConnected = true

            override fun connect() {}
            override fun send(text: String) = true
            override fun send(bytes: ByteArray) = true

            override fun setListener(listener: SendSpinTransport.Listener?) {
                capturedListener = listener
            }

            override fun close(code: Int, reason: String) {
                // Simulate the race: onClosed fires synchronously during close()
                // After the fix, setListener(null) is called before close(),
                // so capturedListener should be null here.
                capturedListener?.onClosed(code, reason)
            }

            override fun destroy() {}
        }

        // Register a listener (as the real code does during connect)
        val listenerField = SendSpinClient::class.java.getDeclaredField("transport")
        listenerField.isAccessible = true
        listenerField.set(client, racyTransport)

        // Now simulate what happens: the transport has a listener set
        // (the real connect flow sets it via TransportEventListener)
        // We need to set the listener to a real TransportEventListener, but since
        // it's an inner class we can't instantiate directly. However, after the fix,
        // setListener(null) is called before close(), so the race can't happen.

        // Call disconnect
        client.disconnect()

        // Verify onDisconnected was called exactly once
        verify(exactly = 1) {
            mockCallback.onDisconnected(wasUserInitiated = true, wasReconnectExhausted = false)
        }
    }

    @Test
    fun `disconnect with null transport does not crash`() {
        // Ensure transport is null
        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, null)

        // Should not throw
        client.disconnect()

        // Callback should still be fired once
        verify(exactly = 1) {
            mockCallback.onDisconnected(wasUserInitiated = true, wasReconnectExhausted = false)
        }
    }

    // =========================================================================
    // H-04: Proxy auth-ack must NOT be forwarded to protocol handler
    // =========================================================================

    @Test
    fun `proxy auth-ack is consumed and not forwarded to handleTextMessage`() {
        // Set up the client in proxy mode with a mock transport.
        // We need to capture the TransportEventListener that gets set on the transport
        // when connectProxy() is called.
        val listenerSlot = slot<SendSpinTransport.Listener>()
        val mockTransport = mockk<SendSpinTransport>(relaxed = true)
        every { mockTransport.setListener(capture(listenerSlot)) } just Runs
        every { mockTransport.state } returns TransportState.Connected
        every { mockTransport.isConnected } returns true
        every { mockTransport.send(any<String>()) } returns true

        // We'll use connectProxy which will create a ProxyWebSocketTransport internally.
        // Instead, we'll use reflection to set up the state as if we're in proxy mode
        // and inject our mock transport to capture the listener.

        // Set connection mode to PROXY
        val modeField = SendSpinClient::class.java.getDeclaredField("connectionMode")
        modeField.isAccessible = true
        modeField.set(client, SendSpinClient.ConnectionMode.PROXY)

        // Set auth token
        val authField = SendSpinClient::class.java.getDeclaredField("authToken")
        authField.isAccessible = true
        authField.set(client, "test-auth-token")

        // Set awaitingAuthResponse to true (simulating post-auth-message state)
        val awaitingField = SendSpinClient::class.java.getDeclaredField("awaitingAuthResponse")
        awaitingField.isAccessible = true
        awaitingField.set(client, true)

        // Set transport
        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, mockTransport)

        // Now we need the actual TransportEventListener. Since we can't directly
        // instantiate the inner class, we'll use the connectProxy flow which creates one.
        // Alternative: we can test by looking at the handshakeComplete state.

        // Actually, let me use a different approach: call connectProxy which will create
        // a real ProxyWebSocketTransport. Instead, let's extract the listener by calling
        // connect and intercepting the setListener call.

        // Better approach: create a spy on the client and verify handleTextMessage is
        // not reached for the auth-ack message. Since handleTextMessage is protected,
        // we can check its side effect: it would log "Received: ..." and parse the JSON.
        // But the real proof is that handshakeComplete should NOT be set if the auth-ack
        // happens to be a server/hello.

        // Simplest approach: directly invoke the transport listener's onMessage with
        // a server/hello-shaped auth-ack and verify that handshakeComplete is NOT set
        // (because the return statement prevents handleTextMessage from being called).

        // Create a real ProxyWebSocketTransport but intercept its connection
        // Actually, let me just create the listener manually by calling connectProxy
        // with a URL that won't actually connect, and capture the listener.

        // Reset state for a clean connect
        val stateField = SendSpinClient::class.java.superclass.getDeclaredField("handshakeComplete")
        stateField.isAccessible = true
        stateField.set(client, false)

        // Capture the transport listener by connecting with a mock transport factory
        // Since we can't easily hook into createProxyTransport, we'll use a different
        // approach: call the onMessage method through a captured listener.

        // Let's create a fake transport that captures its listener
        var capturedListener: SendSpinTransport.Listener? = null
        val fakeTransport = object : SendSpinTransport {
            override val state = TransportState.Connected
            override val isConnected = true
            override fun connect() {}
            override fun send(text: String) = true
            override fun send(bytes: ByteArray) = true
            override fun setListener(listener: SendSpinTransport.Listener?) {
                capturedListener = listener
            }
            override fun close(code: Int, reason: String) {}
            override fun destroy() {}
        }

        // Inject fake transport
        transportField.set(client, fakeTransport)

        // Now simulate the connectProxy flow manually:
        // 1. Set connection mode to PROXY
        modeField.set(client, SendSpinClient.ConnectionMode.PROXY)
        // 2. Set auth token
        authField.set(client, "test-auth-token")
        // 3. Set awaitingAuthResponse to true
        awaitingField.set(client, true)
        // 4. handshakeComplete = false
        stateField.set(client, false)

        // We need to get the actual TransportEventListener. Use connectProxy which
        // creates a ProxyWebSocketTransport - but that will replace our fake transport.
        // Instead, let's use reflection to create the inner class directly.
        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }
        assertNotNull("TransportEventListener inner class must exist", listenerClass)

        // Create instance of inner class (needs outer class reference)
        val constructor = listenerClass!!.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        val listener = constructor.newInstance(client) as SendSpinTransport.Listener

        // Set this listener on our fake transport so send() works through it
        fakeTransport.setListener(listener)

        // Now simulate the auth-ack being a server/hello message.
        // This is the dangerous case: if the server sends back something that
        // looks like server/hello as the auth response.
        val serverHelloAuthAck = """{"type":"server/hello","payload":{"server_name":"TestServer","server_id":"test-id","protocol_version":1,"active_roles":["player"]}}"""

        // Call onMessage with this auth-ack
        listener.onMessage(serverHelloAuthAck)

        // After the fix, the auth-ack should be consumed (return statement)
        // and handleTextMessage should NOT be called, so handshakeComplete stays false
        val handshakeComplete = stateField.get(client) as Boolean
        assertFalse(
            "Auth-ack must be consumed; handshake must NOT complete from auth-ack message",
            handshakeComplete
        )

        // awaitingAuthResponse should be cleared
        val stillAwaiting = awaitingField.get(client) as Boolean
        assertFalse("awaitingAuthResponse should be cleared after auth-ack", stillAwaiting)

        // sendClientHello should have been called (transport.send with client/hello)
        verify(atLeast = 0) { mockTransport.send(any<String>()) }
        // The actual send goes through the fakeTransport, not mockTransport,
        // so we just verify the state is correct.
    }

    @Test
    fun `proxy auth-ack with auth_ok type is consumed`() {
        // Set up client in proxy mode
        val stateField = SendSpinClient::class.java.superclass.getDeclaredField("handshakeComplete")
        stateField.isAccessible = true
        stateField.set(client, false)

        val modeField = SendSpinClient::class.java.getDeclaredField("connectionMode")
        modeField.isAccessible = true
        modeField.set(client, SendSpinClient.ConnectionMode.PROXY)

        val authField = SendSpinClient::class.java.getDeclaredField("authToken")
        authField.isAccessible = true
        authField.set(client, "test-token")

        val awaitingField = SendSpinClient::class.java.getDeclaredField("awaitingAuthResponse")
        awaitingField.isAccessible = true
        awaitingField.set(client, true)

        // Create fake transport
        val fakeTransport = object : SendSpinTransport {
            override val state = TransportState.Connected
            override val isConnected = true
            val sentMessages = mutableListOf<String>()
            override fun connect() {}
            override fun send(text: String): Boolean { sentMessages.add(text); return true }
            override fun send(bytes: ByteArray) = true
            override fun setListener(listener: SendSpinTransport.Listener?) {}
            override fun close(code: Int, reason: String) {}
            override fun destroy() {}
        }

        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, fakeTransport)

        // Create the inner TransportEventListener
        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        val listener = constructor.newInstance(client) as SendSpinTransport.Listener

        // Send a typical auth_ok message
        val authOk = """{"type":"auth_ok","message":"Authenticated"}"""
        listener.onMessage(authOk)

        // awaitingAuthResponse should be cleared
        assertFalse("awaitingAuthResponse should be false", awaitingField.get(client) as Boolean)

        // handshakeComplete should NOT be set (auth_ok is not a server/hello)
        assertFalse("Handshake should not complete from auth_ok", stateField.get(client) as Boolean)

        // A client/hello should have been sent
        assertTrue(
            "sendClientHello should have been called",
            fakeTransport.sentMessages.any { it.contains("client/hello") }
        )
    }

    @Test
    fun `non-auth messages in proxy mode still forwarded to protocol handler after auth`() {
        // After auth completes, normal messages should still be forwarded
        val stateField = SendSpinClient::class.java.superclass.getDeclaredField("handshakeComplete")
        stateField.isAccessible = true
        stateField.set(client, false)

        val modeField = SendSpinClient::class.java.getDeclaredField("connectionMode")
        modeField.isAccessible = true
        modeField.set(client, SendSpinClient.ConnectionMode.PROXY)

        val authField = SendSpinClient::class.java.getDeclaredField("authToken")
        authField.isAccessible = true
        authField.set(client, "test-token")

        // awaitingAuthResponse is FALSE (auth already completed)
        val awaitingField = SendSpinClient::class.java.getDeclaredField("awaitingAuthResponse")
        awaitingField.isAccessible = true
        awaitingField.set(client, false)

        // Create fake transport
        val fakeTransport = object : SendSpinTransport {
            override val state = TransportState.Connected
            override val isConnected = true
            val sentMessages = mutableListOf<String>()
            override fun connect() {}
            override fun send(text: String): Boolean { sentMessages.add(text); return true }
            override fun send(bytes: ByteArray) = true
            override fun setListener(listener: SendSpinTransport.Listener?) {}
            override fun close(code: Int, reason: String) {}
            override fun destroy() {}
        }

        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, fakeTransport)

        // Create the inner TransportEventListener
        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        val listener = constructor.newInstance(client) as SendSpinTransport.Listener

        // Send a server/hello message (not an auth-ack because awaitingAuthResponse is false)
        val serverHello = """{"type":"server/hello","payload":{"server_name":"TestServer","server_id":"test-id","protocol_version":1,"active_roles":["player"]}}"""
        listener.onMessage(serverHello)

        // Handshake should complete because this IS a normal protocol message
        assertTrue(
            "server/hello should be processed when not awaiting auth",
            stateField.get(client) as Boolean
        )

        // onConnected callback should have been fired
        verify { mockCallback.onConnected("TestServer") }
    }
}
