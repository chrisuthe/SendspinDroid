package com.sendspindroid.sendspin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for the LOCAL -> PROXY internal fallback in SendSpinClient.attemptReconnect.
 *
 * Covers issue #126: when LOCAL reconnect has failed [LOCAL_RECONNECT_FALLBACK_THRESHOLD]
 * times in a row and a PROXY fallback has been configured via setProxyFallback(),
 * the client switches connectionMode to PROXY internally rather than retrying a
 * LAN address that's apparently unreachable on the current network.
 *
 * These tests drive attemptReconnect() via reflection and inspect the private
 * connectionMode / serverAddress / authToken fields to confirm the switch happens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendSpinClientLocalFallbackTest {

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

        // Seed connection info so canReconnect passes and attemptReconnect proceeds
        setField("serverAddress", "192.168.1.42:8927")
        setField("serverPath", "/sendspin")
        setField("connectionMode", SendSpinClient.ConnectionMode.LOCAL)
    }

    @After
    fun tearDown() {
        client.destroy()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `switches to PROXY after threshold LOCAL failures when fallback is set`() {
        client.setProxyFallback("wss://proxy.example.com/sendspin", "token-abc")

        // Simulate 3 prior failed attempts. The threshold check fires when attempts > 3,
        // so the 4th call should trigger the switch.
        getAtomicInt("reconnectAttempts").set(3)

        invokeAttemptReconnect()

        assertEquals(
            "connectionMode should switch to PROXY after exceeding threshold",
            SendSpinClient.ConnectionMode.PROXY,
            getField("connectionMode")
        )
        assertEquals(
            "serverAddress should be set to the fallback PROXY URL",
            "wss://proxy.example.com/sendspin",
            getField("serverAddress")
        )
        assertEquals(
            "authToken should be set to the fallback token",
            "token-abc",
            getField("authToken")
        )
        assertNull(
            "serverPath should be cleared for PROXY mode (path lives in URL)",
            getField("serverPath")
        )
    }

    @Test
    fun `does not switch before threshold`() {
        client.setProxyFallback("wss://proxy.example.com/sendspin", "token-abc")

        // Two prior attempts; third call hits attempts=3 which is NOT > threshold=3.
        getAtomicInt("reconnectAttempts").set(2)

        invokeAttemptReconnect()

        assertEquals(
            "connectionMode should remain LOCAL below threshold",
            SendSpinClient.ConnectionMode.LOCAL,
            getField("connectionMode")
        )
    }

    @Test
    fun `does not switch when no fallback is configured`() {
        // setProxyFallback was not called — both fields are null.
        getAtomicInt("reconnectAttempts").set(10)  // well past threshold

        invokeAttemptReconnect()

        assertEquals(
            "connectionMode should remain LOCAL when no PROXY fallback is available",
            SendSpinClient.ConnectionMode.LOCAL,
            getField("connectionMode")
        )
    }

    @Test
    fun `does not switch when only one fallback field is set (incomplete config)`() {
        client.setProxyFallback("wss://proxy.example.com/sendspin", null)
        getAtomicInt("reconnectAttempts").set(10)

        invokeAttemptReconnect()

        assertEquals(
            "connectionMode should remain LOCAL when fallback is incomplete",
            SendSpinClient.ConnectionMode.LOCAL,
            getField("connectionMode")
        )
    }

    @Test
    fun `setProxyFallback with null clears a previously configured fallback`() {
        client.setProxyFallback("wss://proxy.example.com/sendspin", "token-abc")
        client.setProxyFallback(null, null)

        getAtomicInt("reconnectAttempts").set(10)
        invokeAttemptReconnect()

        assertEquals(
            "connectionMode should remain LOCAL after fallback is cleared",
            SendSpinClient.ConnectionMode.LOCAL,
            getField("connectionMode")
        )
    }

    @Test
    fun `does not switch when already in PROXY mode`() {
        // Simulating a stuck PROXY retry; fallback logic is LOCAL-only.
        setField("connectionMode", SendSpinClient.ConnectionMode.PROXY)
        setField("authToken", "existing-token")
        client.setProxyFallback("wss://other-proxy.example.com/sendspin", "token-xyz")
        getAtomicInt("reconnectAttempts").set(10)

        invokeAttemptReconnect()

        assertEquals(
            "connectionMode should stay PROXY; fallback does not override an in-progress PROXY connection",
            SendSpinClient.ConnectionMode.PROXY,
            getField("connectionMode")
        )
    }

    // --- helpers ---

    private fun invokeAttemptReconnect() {
        val m = SendSpinClient::class.java.getDeclaredMethod("attemptReconnect")
        m.isAccessible = true
        m.invoke(client)
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

    private fun getAtomicInt(name: String): AtomicInteger {
        val f = SendSpinClient::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(client) as AtomicInteger
    }
}
