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
import java.net.SocketException
import java.net.UnknownHostException

/**
 * Verifies the unified reconnect-gate policy on `TransportEventListener`
 * introduced by issue #129.
 *
 * Invariant: both `onClosed` and `onFailure` gate on
 *   * `!userInitiatedDisconnect`
 *   * `hasConnectionInfo`
 *   * `!isNormalClosure` (onClosed) / `isRecoverable` (onFailure)
 *
 * `handshakeComplete` is no longer part of the gate. A server that accepts
 * the upgrade and closes abnormally before `server/hello` must be retried --
 * backoff handles the "server is broken" case without spinning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendSpinClientPreHandshakeReconnectTest {

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

        // Seed connection info so canReconnect / hasConnectionInfo checks pass.
        setField("serverAddress", "127.0.0.1:8080")
        setField("serverPath", "/sendspin")
        setField("connectionMode", SendSpinClient.ConnectionMode.LOCAL)

        // Fake transport so onClosed's reconnect path can advance past the
        // hasConnectionInfo check without touching real networking.
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
        setField("transport", fakeTransport)
    }

    @After
    fun tearDown() {
        client.destroy()
        Dispatchers.resetMain()
        unmockkAll()
    }

    // =========================================================================
    // onClosed -- the path whose behavior changes in this PR
    // =========================================================================

    @Test
    fun `onClosed abnormal pre-handshake triggers reconnect`() {
        // handshakeComplete = false (default). Prior to #129 this case was silently
        // blocked; under the unified policy it now reconnects, matching onFailure.
        setHandshakeComplete(false)
        val listener = buildTransportListener()

        listener.onClosed(code = 1006, reason = "abnormal")

        verify(atLeast = 1) { mockCallback.onReconnecting(any(), any()) }
    }

    @Test
    fun `onClosed normal-closure pre-handshake does NOT reconnect`() {
        // Guard against reconnect storms on a server that accepts then immediately
        // closes with code 1000 -- this case must remain a clean disconnect.
        setHandshakeComplete(false)
        val listener = buildTransportListener()

        listener.onClosed(code = 1000, reason = "normal")

        verify(exactly = 0) { mockCallback.onReconnecting(any(), any()) }
        verify(atLeast = 1) { mockCallback.onDisconnected(any(), any()) }
    }

    @Test
    fun `onClosed abnormal post-handshake triggers reconnect`() {
        // Unchanged behavior -- regression guard.
        setHandshakeComplete(true)
        val listener = buildTransportListener()

        listener.onClosed(code = 1006, reason = "abnormal post-handshake")

        verify(atLeast = 1) { mockCallback.onReconnecting(any(), any()) }
    }

    @Test
    fun `onClosed normal-closure post-handshake does NOT reconnect`() {
        // Unchanged behavior -- explicit disconnect from server, session ended.
        setHandshakeComplete(true)
        val listener = buildTransportListener()

        listener.onClosed(code = 1000, reason = "server shutdown")

        verify(exactly = 0) { mockCallback.onReconnecting(any(), any()) }
        verify(atLeast = 1) { mockCallback.onDisconnected(any(), any()) }
    }

    // =========================================================================
    // onFailure -- unchanged, but included to document the symmetry
    // =========================================================================

    @Test
    fun `onFailure recoverable pre-handshake triggers reconnect`() {
        setHandshakeComplete(false)
        val listener = buildTransportListener()

        listener.onFailure(SocketException("connection reset"), isRecoverable = true)

        verify(atLeast = 1) { mockCallback.onReconnecting(any(), any()) }
    }

    @Test
    fun `onFailure non-recoverable pre-handshake does NOT reconnect`() {
        // DNS / SSL / auth errors: retry would loop indefinitely against a
        // deterministic rejection. Existing isRecoverable filter handles these.
        setHandshakeComplete(false)
        val listener = buildTransportListener()

        listener.onFailure(UnknownHostException("no such host"), isRecoverable = false)

        verify(exactly = 0) { mockCallback.onReconnecting(any(), any()) }
        verify(atLeast = 1) { mockCallback.onError(any()) }
    }

    // --- helpers ---

    private fun buildTransportListener(): SendSpinTransport.Listener {
        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(client) as SendSpinTransport.Listener
    }

    private fun setField(name: String, value: Any?) {
        val f = SendSpinClient::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(client, value)
    }

    private fun setHandshakeComplete(value: Boolean) {
        // Declared on the SendSpinProtocolHandler superclass.
        val f = SendSpinClient::class.java.superclass.getDeclaredField("handshakeComplete")
        f.isAccessible = true
        f.set(client, value)
    }
}
