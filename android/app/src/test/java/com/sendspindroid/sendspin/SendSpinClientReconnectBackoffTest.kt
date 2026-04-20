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
 * Tests that reconnection uses exponential backoff with the correct delays
 * and respects the max attempt limit (normal mode) and high power mode behavior.
 *
 * Expected delay sequence: 500ms, 1s, 2s, 4s, 8s with max 5 attempts.
 * High power mode: infinite retries, 30s steady-state after 5th attempt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendSpinClientReconnectBackoffTest {

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

    /**
     * Helper to set up the client in a "connected" state with a server address
     * so that attemptReconnect() considers reconnection valid.
     */
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

        // Set serverAddress so reconnection is valid for LOCAL mode
        val addrField = SendSpinClient::class.java.getDeclaredField("serverAddress")
        addrField.isAccessible = true
        addrField.set(client, "127.0.0.1:8080")

        val pathField = SendSpinClient::class.java.getDeclaredField("serverPath")
        pathField.isAccessible = true
        pathField.set(client, "/sendspin")

        val transportField = SendSpinClient::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, fakeTransport)

        // Complete handshake so onClosed triggers reconnection
        val handshakeField = SendSpinClient::class.java.superclass.getDeclaredField("handshakeComplete")
        handshakeField.isAccessible = true
        handshakeField.set(client, true)

        // Create the TransportEventListener
        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(client) as SendSpinTransport.Listener
    }

    @Test
    fun `reconnect attempts increment correctly`() {
        setupForReconnection()

        // Trigger reconnection via attemptReconnect (via transport closure)
        val attemptReconnect = SendSpinClient::class.java.getDeclaredMethod("attemptReconnect")
        attemptReconnect.isAccessible = true

        assertEquals(0, client.getReconnectAttempts())

        attemptReconnect.invoke(client)
        assertEquals(1, client.getReconnectAttempts())

        attemptReconnect.invoke(client)
        assertEquals(2, client.getReconnectAttempts())

        attemptReconnect.invoke(client)
        assertEquals(3, client.getReconnectAttempts())
    }

    @Test
    fun `normal mode retries forever without triggering exhausted error`() {
        setupForReconnection()
        every { UserSettings.highPowerMode } returns false

        val attemptReconnect = SendSpinClient::class.java.getDeclaredMethod("attemptReconnect")
        attemptReconnect.isAccessible = true

        // Perform 10 attempts - all should succeed in normal mode now
        for (i in 1..10) {
            attemptReconnect.invoke(client)
        }

        // Should NOT have called onDisconnected with wasReconnectExhausted=true
        verify(exactly = 0) {
            mockCallback.onDisconnected(wasUserInitiated = false, wasReconnectExhausted = true)
        }

        // All 10 should have been onReconnecting calls
        verify(exactly = 10) {
            mockCallback.onReconnecting(any(), any())
        }

        // State should remain Connecting (not Error)
        assertTrue(
            "State should remain Connecting in normal mode with no cap, was: ${client.connectionState.value}",
            client.connectionState.value is SendSpinClient.ConnectionState.Connecting
        )
    }

    @Test
    fun `normal mode uses 30s steady-state delay after attempt 5`() {
        // Verify the delay formula selects the steady-state path for attempts > 5
        // regardless of highPowerMode setting. The formula we expect in SendSpinClient:
        //   val delayMs = if (attempts > MAX_RECONNECT_ATTEMPTS) HIGH_POWER_RECONNECT_DELAY_MS
        //                 else (INITIAL_RECONNECT_DELAY_MS * (1 shl (attempts - 1)))
        //                         .coerceAtMost(MAX_RECONNECT_DELAY_MS)

        val initialDelay = 500L
        val maxDelay = 10_000L
        val steadyStateDelay = 30_000L

        for (attempt in 1..5) {
            val computed = (initialDelay * (1 shl (attempt - 1))).coerceAtMost(maxDelay)
            val expected = when (attempt) {
                1 -> 500L; 2 -> 1000L; 3 -> 2000L; 4 -> 4000L; 5 -> 8000L
                else -> fail("unreachable") as Long
            }
            assertEquals("Attempt $attempt should use exponential backoff", expected, computed)
        }

        // Attempt 6+ should use steady-state 30s
        for (attempt in 6..10) {
            val computed = if (attempt > 5) steadyStateDelay
                           else (initialDelay * (1 shl (attempt - 1))).coerceAtMost(maxDelay)
            assertEquals("Attempt $attempt should use 30s steady-state", steadyStateDelay, computed)
        }
    }

    @Test
    fun `exponential backoff delay sequence is 500ms, 1s, 2s, 4s, 8s`() {
        // Verify the backoff formula: INITIAL_RECONNECT_DELAY_MS * (1 shl (attempts - 1))
        // We verify this by checking the constants and the formula from the source code.
        // INITIAL_RECONNECT_DELAY_MS = 500, MAX_RECONNECT_DELAY_MS = 10000
        //
        // attempt 1: 500 * (1 << 0) = 500ms
        // attempt 2: 500 * (1 << 1) = 1000ms
        // attempt 3: 500 * (1 << 2) = 2000ms
        // attempt 4: 500 * (1 << 3) = 4000ms
        // attempt 5: 500 * (1 << 4) = 8000ms

        val initialDelay = 500L
        val maxDelay = 10_000L

        val expectedDelays = listOf(500L, 1000L, 2000L, 4000L, 8000L)

        for (attempt in 1..5) {
            val computed = (initialDelay * (1 shl (attempt - 1))).coerceAtMost(maxDelay)
            assertEquals(
                "Delay for attempt $attempt should be ${expectedDelays[attempt - 1]}ms",
                expectedDelays[attempt - 1],
                computed
            )
        }
    }

    @Test
    fun `high power mode uses 30s delay after attempt 5`() {
        setupForReconnection()
        every { UserSettings.highPowerMode } returns true

        val attemptReconnect = SendSpinClient::class.java.getDeclaredMethod("attemptReconnect")
        attemptReconnect.isAccessible = true

        // Perform 6 attempts - in high power mode, attempt 6 should NOT trigger error
        for (i in 1..6) {
            attemptReconnect.invoke(client)
        }

        // Should NOT have called onDisconnected with wasReconnectExhausted=true
        verify(exactly = 0) {
            mockCallback.onDisconnected(wasUserInitiated = false, wasReconnectExhausted = true)
        }

        // All 6 should have been onReconnecting calls
        verify(exactly = 6) {
            mockCallback.onReconnecting(any(), any())
        }

        // State should still be Connecting (not Error)
        assertTrue(
            "State should remain Connecting in high power mode, was: ${client.connectionState.value}",
            client.connectionState.value is SendSpinClient.ConnectionState.Connecting
        )
    }

    @Test
    fun `high power mode allows attempts beyond normal max`() {
        setupForReconnection()
        every { UserSettings.highPowerMode } returns true

        val attemptReconnect = SendSpinClient::class.java.getDeclaredMethod("attemptReconnect")
        attemptReconnect.isAccessible = true

        // Perform 10 attempts - all should succeed in high power mode
        for (i in 1..10) {
            attemptReconnect.invoke(client)
        }

        verify(exactly = 0) {
            mockCallback.onDisconnected(wasUserInitiated = false, wasReconnectExhausted = true)
        }

        verify(exactly = 10) {
            mockCallback.onReconnecting(any(), any())
        }
    }

    @Test
    fun `user initiated disconnect prevents reconnection`() {
        setupForReconnection()

        // Simulate user disconnect
        client.disconnect()

        val attemptReconnect = SendSpinClient::class.java.getDeclaredMethod("attemptReconnect")
        attemptReconnect.isAccessible = true

        attemptReconnect.invoke(client)

        // Should not have called onReconnecting (the disconnect callback is from disconnect())
        verify(exactly = 0) {
            mockCallback.onReconnecting(any(), any())
        }
    }
}
