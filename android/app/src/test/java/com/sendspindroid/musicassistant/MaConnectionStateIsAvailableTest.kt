package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaConnectionState
import com.sendspindroid.musicassistant.model.MaServerInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the isAvailable derivation from MaConnectionState (C-10).
 *
 * Verifies that:
 * 1. MaConnectionState.isAvailable returns true only for Connected
 * 2. A StateFlow<Boolean> derived via map { it.isAvailable } correctly
 *    reflects connection state transitions -- the exact pattern used
 *    in MusicAssistantManager.isAvailable
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaConnectionStateIsAvailableTest {

    // -- MaConnectionState.isAvailable property tests --

    @Test
    fun unavailable_isNotAvailable() {
        assertFalse(MaConnectionState.Unavailable.isAvailable)
    }

    @Test
    fun needsAuth_isNotAvailable() {
        assertFalse(MaConnectionState.NeedsAuth.isAvailable)
    }

    @Test
    fun connecting_isNotAvailable() {
        assertFalse(MaConnectionState.Connecting.isAvailable)
    }

    @Test
    fun connected_isAvailable() {
        val state = MaConnectionState.Connected(
            MaServerInfo(
                serverId = "test-server",
                serverVersion = "1.0.0",
                apiUrl = "ws://localhost:8095/ws"
            )
        )
        assertTrue(state.isAvailable)
    }

    @Test
    fun error_isNotAvailable() {
        assertFalse(MaConnectionState.Error("test error").isAvailable)
    }

    @Test
    fun authError_isNotAvailable() {
        assertFalse(MaConnectionState.Error("auth failed", isAuthError = true).isAvailable)
    }

    // -- Derived StateFlow tests (mirrors MusicAssistantManager.isAvailable pattern) --

    @Test
    fun derivedFlow_initiallyFalse() = runTest {
        val connectionState = MutableStateFlow<MaConnectionState>(MaConnectionState.Unavailable)
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        val isAvailable = connectionState
            .map { it.isAvailable }
            .stateIn(testScope, SharingStarted.Eagerly, false)

        assertFalse(isAvailable.value)
    }

    @Test
    fun derivedFlow_becomesTrueOnConnected() = runTest {
        val connectionState = MutableStateFlow<MaConnectionState>(MaConnectionState.Unavailable)
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        val isAvailable = connectionState
            .map { it.isAvailable }
            .stateIn(testScope, SharingStarted.Eagerly, false)

        // Transition to Connected
        connectionState.value = MaConnectionState.Connected(
            MaServerInfo(
                serverId = "test-server",
                serverVersion = "1.0.0",
                apiUrl = "ws://localhost:8095/ws"
            )
        )

        assertTrue("isAvailable should be true when Connected", isAvailable.value)
    }

    @Test
    fun derivedFlow_becomesFalseOnDisconnect() = runTest {
        val serverInfo = MaServerInfo(
            serverId = "test-server",
            serverVersion = "1.0.0",
            apiUrl = "ws://localhost:8095/ws"
        )
        val connectionState = MutableStateFlow<MaConnectionState>(
            MaConnectionState.Connected(serverInfo)
        )
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        val isAvailable = connectionState
            .map { it.isAvailable }
            .stateIn(testScope, SharingStarted.Eagerly, false)

        assertTrue("should start as true when Connected", isAvailable.value)

        // Disconnect
        connectionState.value = MaConnectionState.Unavailable

        assertFalse("isAvailable should be false after disconnect", isAvailable.value)
    }

    @Test
    fun derivedFlow_fullLifecycle() = runTest {
        val connectionState = MutableStateFlow<MaConnectionState>(MaConnectionState.Unavailable)
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        val isAvailable = connectionState
            .map { it.isAvailable }
            .stateIn(testScope, SharingStarted.Eagerly, false)

        val serverInfo = MaServerInfo(
            serverId = "test-server",
            serverVersion = "1.0.0",
            apiUrl = "ws://localhost:8095/ws"
        )

        // Unavailable -> false
        assertFalse("Unavailable -> false", isAvailable.value)

        // NeedsAuth -> false
        connectionState.value = MaConnectionState.NeedsAuth
        assertFalse("NeedsAuth -> false", isAvailable.value)

        // Connecting -> false
        connectionState.value = MaConnectionState.Connecting
        assertFalse("Connecting -> false", isAvailable.value)

        // Connected -> true
        connectionState.value = MaConnectionState.Connected(serverInfo)
        assertTrue("Connected -> true", isAvailable.value)

        // Error -> false
        connectionState.value = MaConnectionState.Error("network error")
        assertFalse("Error -> false", isAvailable.value)

        // Reconnect -> true
        connectionState.value = MaConnectionState.Connecting
        assertFalse("Connecting again -> false", isAvailable.value)

        connectionState.value = MaConnectionState.Connected(serverInfo)
        assertTrue("Reconnected -> true", isAvailable.value)

        // Final disconnect
        connectionState.value = MaConnectionState.Unavailable
        assertFalse("Unavailable again -> false", isAvailable.value)
    }

    @Test
    fun derivedFlow_remainsFalseThroughNonConnectedTransitions() = runTest {
        val connectionState = MutableStateFlow<MaConnectionState>(MaConnectionState.Unavailable)
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        val isAvailable = connectionState
            .map { it.isAvailable }
            .stateIn(testScope, SharingStarted.Eagerly, false)

        // Cycle through all non-Connected states
        val nonConnectedStates = listOf(
            MaConnectionState.Unavailable,
            MaConnectionState.NeedsAuth,
            MaConnectionState.Connecting,
            MaConnectionState.Error("test"),
            MaConnectionState.Error("auth", isAuthError = true),
        )

        for (state in nonConnectedStates) {
            connectionState.value = state
            assertFalse(
                "isAvailable should be false for ${state::class.simpleName}",
                isAvailable.value
            )
        }
    }
}
