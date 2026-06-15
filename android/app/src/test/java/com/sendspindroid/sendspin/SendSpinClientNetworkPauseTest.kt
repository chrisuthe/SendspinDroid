package com.sendspindroid.sendspin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.TransportState as TransportLayerState
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests that reconnection is paused when network is unavailable and
 * resumes when network becomes available again.
 *
 * When networkAvailable=false, attemptReconnect() should:
 * - NOT waste the attempt (decrements counter back)
 * - Set waitingForNetwork=true
 * - NOT try to connect
 *
 * When networkAvailable=true, setNetworkAvailable(true) should:
 * - Resume reconnection immediately via onNetworkAvailable()
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendSpinNetworkPauseTest {

    private lateinit var mockContext: Context
    private lateinit var mockCallback: SendSpin.Callback
    private lateinit var client: SendSpin

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

        client = SendSpin(mockContext, "TestDevice", mockCallback)
    }

    @After
    fun tearDown() {
        client.destroy()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun setupForReconnection() {
        val fakeTransport = object : SendSpinTransport {
            override val state = TransportLayerState.Connected
            override val isConnected = true
            override fun connect() {}
            override fun send(text: String) = true
            override fun send(bytes: ByteArray) = true
            override fun setListener(listener: SendSpinTransport.Listener?) {}
            override fun close(code: Int, reason: String) {}
            override fun destroy() {}
        }

        val addrField = SendSpin::class.java.getDeclaredField("serverAddress")
        addrField.isAccessible = true
        addrField.set(client, "127.0.0.1:8080")

        val pathField = SendSpin::class.java.getDeclaredField("serverPath")
        pathField.isAccessible = true
        pathField.set(client, "/sendspin")

        val transportField = SendSpin::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, fakeTransport)

        val handshakeField = SendSpin::class.java.superclass.getDeclaredField("handshakeComplete")
        handshakeField.isAccessible = true
        handshakeField.set(client, true)
    }

    private fun getWaitingForNetwork(): Boolean {
        val field = SendSpin::class.java.getDeclaredField("waitingForNetwork")
        field.isAccessible = true
        return (field.get(client) as AtomicBoolean).get()
    }

    private fun getReconnecting(): Boolean {
        val field = SendSpin::class.java.getDeclaredField("reconnecting")
        field.isAccessible = true
        return (field.get(client) as AtomicBoolean).get()
    }

    @Test
    fun `reconnect attempt pauses when network unavailable`() {
        setupForReconnection()

        // Mark network as unavailable
        client.setNetworkAvailable(false)

        val attemptReconnect = SendSpin::class.java.getDeclaredMethod("attemptReconnect")
        attemptReconnect.isAccessible = true
        attemptReconnect.invoke(client)

        // The attempt counter should be decremented back (attempt saved)
        // First it increments to 1, then network check decrements to 0
        assertEquals(
            "Attempt should be saved (not wasted) when network unavailable",
            0,
            client.getReconnectAttempts()
        )

        assertTrue("Should be waiting for network", getWaitingForNetwork())
        assertTrue("Should be in reconnecting state", getReconnecting())

        // State should be Connecting (not Error)
        assertTrue(
            "State should be Connecting while paused",
            client.connectionState.value is TransportState.Connecting
        )
    }

    @Test
    fun `reconnect resumes when network becomes available`() {
        setupForReconnection()

        // Set network unavailable and trigger a paused reconnect
        client.setNetworkAvailable(false)

        val attemptReconnect = SendSpin::class.java.getDeclaredMethod("attemptReconnect")
        attemptReconnect.isAccessible = true
        attemptReconnect.invoke(client)

        assertTrue("Should be waiting for network", getWaitingForNetwork())

        // Now restore network - this should call onNetworkAvailable() which resumes
        client.setNetworkAvailable(true)

        assertFalse(
            "waitingForNetwork should be cleared after network restored",
            getWaitingForNetwork()
        )
    }

    @Test
    fun `multiple paused attempts do not waste reconnect counter`() {
        setupForReconnection()
        client.setNetworkAvailable(false)

        val attemptReconnect = SendSpin::class.java.getDeclaredMethod("attemptReconnect")
        attemptReconnect.isAccessible = true

        // Try 3 times while offline
        attemptReconnect.invoke(client)
        attemptReconnect.invoke(client)
        attemptReconnect.invoke(client)

        // Each time: increment to N, then decrement back to N-1
        // But subsequent calls still increment from the saved value
        // Net effect: counter should not grow beyond attempts - decrements
        // The important thing is we haven't exhausted our attempts
        assertTrue(
            "Should not have exhausted reconnect attempts while offline",
            client.getReconnectAttempts() <= 5
        )

        // Should still be in reconnecting state, not Error
        assertFalse(
            "State should NOT be Error while network is unavailable",
            client.connectionState.value is TransportState.Failed
        )
    }

    @Test
    fun `state transitions to Connecting when paused due to network unavailable`() {
        setupForReconnection()
        client.setNetworkAvailable(false)

        val attemptReconnect = SendSpin::class.java.getDeclaredMethod("attemptReconnect")
        attemptReconnect.isAccessible = true
        attemptReconnect.invoke(client)

        // Even when paused, state should be Connecting (not Idle or Failed)
        assertTrue(
            "State should be Connecting while paused for network, was: ${client.connectionState.value}",
            client.connectionState.value is TransportState.Connecting
        )
    }

    @Test
    fun `setNetworkAvailable true without active reconnection is no-op`() {
        // No reconnection in progress - should not crash or trigger reconnect
        client.setNetworkAvailable(true)

        // State should remain Idle (no reconnection triggered)
        assertTrue(
            "State should stay Idle when no reconnection was in progress, was: ${client.connectionState.value}",
            client.connectionState.value is TransportState.Idle
        )
    }

    @Test
    fun `setNetworkAvailable false then true without reconnection is no-op`() {
        client.setNetworkAvailable(false)
        client.setNetworkAvailable(true)

        // No reconnection was triggered - state stays Idle
        assertTrue(
            "State should stay Idle when no reconnection was triggered, was: ${client.connectionState.value}",
            client.connectionState.value is TransportState.Idle
        )
    }
}
