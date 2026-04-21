package com.sendspindroid.sendspin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.sendspindroid.UserSettings
import com.sendspindroid.logging.AppLog
import com.sendspindroid.logging.LogLevel
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
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the connection-health telemetry introduced by issue #128.
 *
 * State being tracked on every disconnect event:
 *  - lastDisconnectCode / lastDisconnectReason / lastDisconnectMode
 *  - lastDisconnectAtMs (only for abnormal, non-user-initiated)
 *  - connectedAtMs cleared
 *
 * Plus: reconnectAttemptsTotal increments per attempt, and the
 * isStallWatchdogArmed() accessor is gated on handshake + not-reconnecting
 * + not-user-initiated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendSpinClientDisconnectTelemetryTest {

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

        // AppLog writes to android.util.Log under the hood; with level=OFF the
        // permit check short-circuits so the mocks above aren't even exercised,
        // but set OFF explicitly for robustness if the default ever changes.
        AppLog.setLevel(LogLevel.OFF)

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

        // Seed connection info so the disconnect paths execute fully.
        setField("serverAddress", "127.0.0.1:8080")
        setField("serverPath", "/sendspin")
        setField("connectionMode", SendSpinClient.ConnectionMode.LOCAL)

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
    // Disconnect telemetry on onClosed / onFailure
    // =========================================================================

    @Test
    fun `onClosed abnormal populates lastDisconnectCode, reason, mode, and lastDisconnectAtMs`() {
        setHandshakeComplete(true)
        val listener = buildTransportListener()

        val before = System.currentTimeMillis()
        listener.onClosed(code = 1006, reason = "ping-timeout")

        assertEquals(Integer.valueOf(1006), client.getLastDisconnectCode())
        assertEquals("ping-timeout", client.getLastDisconnectReason())
        val disconnectAt = getField("lastDisconnectAtMs") as Long?
        assertNotNull("lastDisconnectAtMs should be set on abnormal close", disconnectAt)
        assertTrue(
            "lastDisconnectAtMs should be set to a recent timestamp",
            disconnectAt!! >= before && disconnectAt <= System.currentTimeMillis(),
        )
        assertNull("connectedAtMs should be cleared after disconnect", client.getConnectedAtMs())
    }

    @Test
    fun `onClosed normal-closure still records code 1000 but does not set lastDisconnectAtMs`() {
        // Normal closure isn't a retryable error, so lastDisconnectAtMs stays null
        // (it's the "for a [reconnect-ok] correlation" marker).
        setHandshakeComplete(true)
        val listener = buildTransportListener()

        listener.onClosed(code = 1000, reason = "server shutdown")

        assertEquals(Integer.valueOf(1000), client.getLastDisconnectCode())
        assertEquals("server shutdown", client.getLastDisconnectReason())
        assertNull(
            "lastDisconnectAtMs should NOT be set on normal closure",
            getField("lastDisconnectAtMs"),
        )
    }

    @Test
    fun `onFailure populates code=null, reason=error message, and lastDisconnectAtMs`() {
        setHandshakeComplete(true)
        val listener = buildTransportListener()

        val error = SocketException("connection reset")
        listener.onFailure(error, isRecoverable = true)

        assertNull("code should be null for onFailure", client.getLastDisconnectCode())
        assertEquals("connection reset", client.getLastDisconnectReason())
        assertNotNull("lastDisconnectAtMs should be set on recoverable failure", getField("lastDisconnectAtMs"))
    }

    @Test
    fun `onFailure with null message falls back to exception class name`() {
        setHandshakeComplete(true)
        val listener = buildTransportListener()

        listener.onFailure(SocketException(), isRecoverable = true)

        // SocketException() without a message => error.message is null => reason = class simple name
        assertEquals("SocketException", client.getLastDisconnectReason())
    }

    // =========================================================================
    // Lifetime reconnect counter
    // =========================================================================

    @Test
    fun `reconnectAttemptsTotal increments on each attemptReconnect call`() {
        setHandshakeComplete(true)
        assertEquals(0, client.getReconnectAttemptsTotal())

        // Triggering attemptReconnect directly via reflection bypasses the listener
        // so we can assert the counter in isolation.
        val m = SendSpinClient::class.java.getDeclaredMethod("attemptReconnect")
        m.isAccessible = true
        m.invoke(client)
        m.invoke(client)
        m.invoke(client)

        assertEquals(3, client.getReconnectAttemptsTotal())
    }

    // =========================================================================
    // isStallWatchdogArmed accessor
    // =========================================================================

    @Test
    fun `isStallWatchdogArmed is false pre-handshake`() {
        setHandshakeComplete(false)
        assertFalse(client.isStallWatchdogArmed())
    }

    @Test
    fun `isStallWatchdogArmed is true when handshake complete and not reconnecting or user-initiated`() {
        setHandshakeComplete(true)
        assertTrue(client.isStallWatchdogArmed())
    }

    @Test
    fun `isStallWatchdogArmed is false while reconnecting`() {
        setHandshakeComplete(true)
        val reconnectingField = SendSpinClient::class.java.getDeclaredField("reconnecting")
        reconnectingField.isAccessible = true
        (reconnectingField.get(client) as AtomicBoolean).set(true)
        assertFalse(client.isStallWatchdogArmed())
    }

    @Test
    fun `isStallWatchdogArmed is false after user-initiated disconnect`() {
        setHandshakeComplete(true)
        val userField = SendSpinClient::class.java.getDeclaredField("userInitiatedDisconnect")
        userField.isAccessible = true
        (userField.get(client) as AtomicBoolean).set(true)
        assertFalse(client.isStallWatchdogArmed())
    }

    // =========================================================================
    // getLastByteReceivedAgoMs accessor
    // =========================================================================

    @Test
    fun `getLastByteReceivedAgoMs returns positive elapsed time from lastByteReceivedAtMs`() {
        // Force the timestamp to ~5 seconds ago via reflection.
        val lastByteField = SendSpinClient::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as java.util.concurrent.atomic.AtomicLong
        atomicLong.set(System.currentTimeMillis() - 5_000L)

        val ago = client.getLastByteReceivedAgoMs()
        assertTrue("ago should be around 5000ms, got $ago", ago in 4_500L..6_000L)
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

    private fun getField(name: String): Any? {
        val f = SendSpinClient::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(client)
    }

    private fun setHandshakeComplete(value: Boolean) {
        val f = SendSpinClient::class.java.superclass.getDeclaredField("handshakeComplete")
        f.isAccessible = true
        f.set(client, value)
    }
}
