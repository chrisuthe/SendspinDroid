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
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class SendSpinClientDisconnectForReselectionTest {

    private lateinit var mockContext: Context
    private lateinit var mockCallback: SendSpinClient.Callback
    private lateinit var client: SendSpinClient
    private lateinit var fakeTransport: FakeTransport

    private class FakeTransport : SendSpinTransport {
        var closeCalled = false
        var closeCode: Int = -1
        var listenerCleared = false
        override val state = TransportState.Connected
        override val isConnected = true
        override fun connect() {}
        override fun send(text: String) = true
        override fun send(bytes: ByteArray) = true
        override fun setListener(listener: SendSpinTransport.Listener?) {
            if (listener == null) listenerCleared = true
        }
        override fun close(code: Int, reason: String) {
            closeCalled = true
            closeCode = code
        }
        override fun destroy() {}
    }

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
        fakeTransport = FakeTransport()

        // Seed connected-state so disconnectForReselection has something to tear down.
        val addrField = SendSpinClient::class.java.getDeclaredField("serverAddress")
        addrField.isAccessible = true
        addrField.set(client, "127.0.0.1:8080")

        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, fakeTransport)

        val handshakeField = SendSpinClient::class.java.superclass.getDeclaredField("handshakeComplete")
        handshakeField.isAccessible = true
        handshakeField.set(client, true)
    }

    @After
    fun tearDown() {
        client.destroy()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `disconnectForReselection fires onDisconnected with wasUserInitiated false and wasReconnectExhausted false`() {
        client.disconnectForReselection()

        verify(exactly = 1) {
            mockCallback.onDisconnected(
                wasUserInitiated = false,
                wasReconnectExhausted = false
            )
        }
    }

    @Test
    fun `disconnectForReselection closes the transport`() {
        client.disconnectForReselection()

        assertTrue("Transport close should be called", fakeTransport.closeCalled)
        assertEquals(1000, fakeTransport.closeCode)
    }

    @Test
    fun `disconnectForReselection clears transport listener before closing`() {
        client.disconnectForReselection()

        assertTrue("Transport listener should be cleared", fakeTransport.listenerCleared)
    }

    @Test
    fun `disconnectForReselection cancels in-flight reconnect coroutine`() {
        val reconnectingField = SendSpinClient::class.java.getDeclaredField("reconnecting")
        reconnectingField.isAccessible = true
        (reconnectingField.get(client) as AtomicBoolean).set(true)

        client.disconnectForReselection()

        assertFalse("Reconnecting flag should be cleared",
            (reconnectingField.get(client) as AtomicBoolean).get())
    }

    @Test
    fun `disconnectForReselection does not set userInitiatedDisconnect`() {
        client.disconnectForReselection()

        val userInitiatedField = SendSpinClient::class.java.getDeclaredField("userInitiatedDisconnect")
        userInitiatedField.isAccessible = true
        assertFalse("userInitiatedDisconnect must stay false so MainActivity auto-reconnects",
            (userInitiatedField.get(client) as AtomicBoolean).get())
    }
}
