package com.sendspindroid.musicassistant

import com.sendspindroid.UserSettings.ConnectionMode
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.transport.MaApiTransport
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression tests for the MA "forced re-login on network drop" race.
 *
 * The car scenario: WiFi drops to cellular mid-connect. Previously,
 * [MusicAssistant.onServerConnected] launched an untracked coroutine that
 * re-read `currentServer`; a concurrent [MusicAssistant.onServerDisconnected]
 * could null it before that coroutine ran, firing a false `loginRequired` for a
 * server whose token was still valid -- unrecoverable on Android Auto.
 *
 * Uses the [MusicAssistant.scope] and [MusicAssistant.transportFactoryOverride]
 * test seams so connect work is deterministic and never touches the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicAssistantReloginRaceTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        MaSettings.initialize(RuntimeEnvironment.getApplication())
        // Deterministic, cancellable scope and a no-network transport.
        MusicAssistant.scope = CoroutineScope(SupervisorJob() + dispatcher)
        MusicAssistant.transportFactoryOverride = { mockk(relaxed = true) }
        MaSettings.clearTokenForServer(SERVER_ID)
    }

    @After
    fun tearDown() {
        MusicAssistant.onServerDisconnected() // clears currentServer / connectJob / transport
        MusicAssistant.transportFactoryOverride = null
        MaSettings.clearTokenForServer(SERVER_ID)
        Dispatchers.resetMain()
    }

    @Test
    fun `transient disconnect during connect does not force re-login when token stored`() =
        runTest(dispatcher) {
            MaSettings.setTokenForServer(SERVER_ID, "valid-token")

            val emissions = mutableListOf<Unit>()
            val collector = launch { MusicAssistant.loginRequired.collect { emissions += it } }
            runCurrent() // ensure the collector is subscribed before we emit

            // Race: server connects, then immediately disconnects (WiFi -> cellular).
            MusicAssistant.onServerConnected(testServer(), ConnectionMode.LOCAL)
            MusicAssistant.onServerDisconnected()
            advanceUntilIdle()

            assertTrue(
                "A transient disconnect must not force re-login when the token is still stored",
                emissions.isEmpty()
            )
            assertNotNull(
                "Token must be preserved across a transient disconnect",
                MaSettings.getTokenForServer(SERVER_ID)
            )
            collector.cancel()
        }

    @Test
    fun `connect with no stored token requires login`() = runTest(dispatcher) {
        // No token stored for this server.
        val emissions = mutableListOf<Unit>()
        val collector = launch { MusicAssistant.loginRequired.collect { emissions += it } }
        runCurrent()

        MusicAssistant.onServerConnected(testServer(), ConnectionMode.LOCAL)
        advanceUntilIdle()

        assertEquals(
            "A genuinely missing token must still surface loginRequired",
            1,
            emissions.size
        )
        collector.cancel()
    }

    private fun testServer() = UnifiedServer(
        id = SERVER_ID,
        name = "MA Home",
        local = LocalConnection("10.0.0.1:8095"),
        isMusicAssistant = true
    )

    companion object {
        private const val SERVER_ID = "srv-relogin-race-test"
    }
}
