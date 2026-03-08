package com.sendspindroid.e2e

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.SendSpinClient
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.transport.SendSpinTransport
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
import org.junit.Before

/**
 * Base class for E2E tests providing common setup:
 * - Mocked Android framework statics (Log, PreferenceManager)
 * - Mocked UserSettings singleton
 * - SendSpinClient with FakeTransport injection
 * - FakeSendSpinServer for protocol simulation
 *
 * Subclasses can override [configureUserSettings] to customize settings for
 * specific test scenarios (e.g., high power mode, low memory mode).
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class E2ETestBase {

    protected lateinit var mockContext: Context
    protected lateinit var mockCallback: SendSpinClient.Callback
    protected lateinit var client: SendSpinClient
    protected lateinit var fakeTransport: FakeTransport
    protected lateinit var fakeServer: FakeSendSpinServer

    @Before
    open fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        // Mock android.util.Log (not available in JVM unit tests)
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // Mock UserSettings
        mockkObject(UserSettings)
        every { UserSettings.getPlayerId() } returns "test-player-e2e"
        every { UserSettings.getPreferredCodec() } returns "pcm"
        every { UserSettings.lowMemoryMode } returns false
        every { UserSettings.highPowerMode } returns false

        // Allow subclasses to override settings
        configureUserSettings()

        // Mock AudioDecoderFactory
        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.isCodecSupported(any()) } returns true
        every { AudioDecoderFactory.getSupportedPcmBitDepths() } returns listOf(16, 24, 32)

        // Mock PreferenceManager
        mockkStatic(PreferenceManager::class)
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        every { PreferenceManager.getDefaultSharedPreferences(any()) } returns mockPrefs

        mockContext = mockk(relaxed = true)
        mockCallback = mockk(relaxed = true)

        client = SendSpinClient(mockContext, "E2ETestDevice", mockCallback)

        // Create fake transport and server
        fakeTransport = FakeTransport()
        fakeServer = FakeSendSpinServer(fakeTransport)
    }

    @After
    open fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /**
     * Override in subclasses to customize UserSettings mocks before client creation.
     */
    protected open fun configureUserSettings() {
        // Default configuration - subclasses can override
    }

    /**
     * Inject the FakeTransport into the SendSpinClient and set up the
     * TransportEventListener so the client processes messages from the
     * fake server.
     *
     * This simulates what happens during a real connect() call:
     * 1. Sets the transport field
     * 2. Creates and registers a TransportEventListener
     * 3. Sets connection mode and related state
     */
    protected fun injectTransportAndConnect(
        mode: SendSpinClient.ConnectionMode = SendSpinClient.ConnectionMode.LOCAL,
        serverAddress: String? = "192.168.1.100:8927",
        serverPath: String? = "/sendspin",
        remoteId: String? = null,
        authToken: String? = null
    ) {
        // Set connection state to Connecting
        setField(client, "_connectionState",
            kotlinx.coroutines.flow.MutableStateFlow<SendSpinClient.ConnectionState>(
                SendSpinClient.ConnectionState.Connecting
            )
        )

        // Set connection mode
        setField(client, "connectionMode", mode)

        // Set connection info for reconnection
        if (serverAddress != null) setField(client, "serverAddress", serverAddress)
        if (serverPath != null) setField(client, "serverPath", serverPath)
        if (remoteId != null) setField(client, "remoteId", remoteId)
        if (authToken != null) setField(client, "authToken", authToken)

        // Reset disconnect flags
        setAtomicBoolean(client, "userInitiatedDisconnect", false)

        // Set handshakeComplete to false
        setField(client, "handshakeComplete", false,
            clazz = SendSpinClient::class.java.superclass)

        // Inject transport
        setField(client, "transport", fakeTransport)

        // Create and register the TransportEventListener
        val innerClasses = SendSpinClient::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }
            ?: throw IllegalStateException("TransportEventListener not found")
        val constructor = listenerClass.getDeclaredConstructor(SendSpinClient::class.java)
        constructor.isAccessible = true
        val listener = constructor.newInstance(client) as SendSpinTransport.Listener
        fakeTransport.setListener(listener)
    }

    /**
     * Perform a full handshake: inject transport, simulate connect, exchange hello.
     */
    protected fun connectAndHandshake(
        mode: SendSpinClient.ConnectionMode = SendSpinClient.ConnectionMode.LOCAL,
        serverAddress: String? = "192.168.1.100:8927",
        serverPath: String? = "/sendspin",
        remoteId: String? = null,
        authToken: String? = null
    ) {
        injectTransportAndConnect(mode, serverAddress, serverPath, remoteId, authToken)
        fakeServer.completeHandshake()
    }

    // ========== Reflection Helpers ==========

    protected fun setField(
        obj: Any,
        fieldName: String,
        value: Any?,
        clazz: Class<*> = obj::class.java
    ) {
        val field = clazz.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(obj, value)
    }

    protected fun <T> getField(
        obj: Any,
        fieldName: String,
        clazz: Class<*> = obj::class.java
    ): T {
        val field = clazz.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(obj) as T
    }

    protected fun setAtomicBoolean(obj: Any, fieldName: String, value: Boolean) {
        val field = obj::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        (field.get(obj) as java.util.concurrent.atomic.AtomicBoolean).set(value)
    }

    protected fun getAtomicBoolean(obj: Any, fieldName: String): Boolean {
        val field = obj::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return (field.get(obj) as java.util.concurrent.atomic.AtomicBoolean).get()
    }

    protected fun setAtomicInteger(obj: Any, fieldName: String, value: Int) {
        val field = obj::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        (field.get(obj) as java.util.concurrent.atomic.AtomicInteger).set(value)
    }

    protected fun getAtomicInteger(obj: Any, fieldName: String): Int {
        val field = obj::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return (field.get(obj) as java.util.concurrent.atomic.AtomicInteger).get()
    }
}
