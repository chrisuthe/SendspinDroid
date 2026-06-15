package com.sendspindroid.musicassistant

import com.sendspindroid.coordinator.FailureReason
import com.sendspindroid.coordinator.TransportState
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
 * Tests for TransportState availability semantics used by MusicAssistant (C-10).
 *
 * Verifies that:
 * 1. TransportState.Ready is the sole "available" state
 * 2. A StateFlow<Boolean> derived via map { it is Ready } correctly
 *    reflects connection state transitions -- the exact pattern used in
 *    MusicAssistant.connectionState consumers
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaConnectionStateIsAvailableTest {

    // -- TransportState availability property tests --

    @Test
    fun idle_isNotAvailable() {
        val state: TransportState = TransportState.Idle
        assertFalse(state is TransportState.Ready)
    }

    @Test
    fun connecting_isNotAvailable() {
        val state: TransportState = TransportState.Connecting
        assertFalse(state is TransportState.Ready)
    }

    @Test
    fun ready_isAvailable() {
        val state: TransportState = TransportState.Ready
        assertTrue(state is TransportState.Ready)
    }

    @Test
    fun failedTransient_isNotAvailable() {
        val state: TransportState = TransportState.Failed(FailureReason.TransientNetwork)
        assertFalse(state is TransportState.Ready)
    }

    @Test
    fun failedAuthRejected_isNotAvailable() {
        val state: TransportState = TransportState.Failed(FailureReason.AuthRejected)
        assertFalse(state is TransportState.Ready)
    }

    // -- Derived StateFlow tests (mirrors MusicAssistant consumers) --

    @Test
    fun derivedFlow_initiallyFalse() = runTest {
        val connectionState = MutableStateFlow<TransportState>(TransportState.Idle)
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        val isAvailable = connectionState
            .map { it is TransportState.Ready }
            .stateIn(testScope, SharingStarted.Eagerly, false)

        assertFalse(isAvailable.value)
    }

    @Test
    fun derivedFlow_becomesTrueOnReady() = runTest {
        val connectionState = MutableStateFlow<TransportState>(TransportState.Idle)
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        val isAvailable = connectionState
            .map { it is TransportState.Ready }
            .stateIn(testScope, SharingStarted.Eagerly, false)

        // Transition to Ready
        connectionState.value = TransportState.Ready

        assertTrue("isAvailable should be true when Ready", isAvailable.value)
    }

    @Test
    fun derivedFlow_becomesFalseOnDisconnect() = runTest {
        val connectionState = MutableStateFlow<TransportState>(TransportState.Ready)
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        val isAvailable = connectionState
            .map { it is TransportState.Ready }
            .stateIn(testScope, SharingStarted.Eagerly, false)

        assertTrue("should start as true when Ready", isAvailable.value)

        // Disconnect
        connectionState.value = TransportState.Idle

        assertFalse("isAvailable should be false after disconnect", isAvailable.value)
    }

    @Test
    fun derivedFlow_fullLifecycle() = runTest {
        val connectionState = MutableStateFlow<TransportState>(TransportState.Idle)
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        val isAvailable = connectionState
            .map { it is TransportState.Ready }
            .stateIn(testScope, SharingStarted.Eagerly, false)

        // Idle -> false
        assertFalse("Idle -> false", isAvailable.value)

        // Connecting -> false
        connectionState.value = TransportState.Connecting
        assertFalse("Connecting -> false", isAvailable.value)

        // Ready -> true
        connectionState.value = TransportState.Ready
        assertTrue("Ready -> true", isAvailable.value)

        // Failed (transient) -> false
        connectionState.value = TransportState.Failed(FailureReason.TransientNetwork)
        assertFalse("Failed(TransientNetwork) -> false", isAvailable.value)

        // Reconnect -> true
        connectionState.value = TransportState.Connecting
        assertFalse("Connecting again -> false", isAvailable.value)

        connectionState.value = TransportState.Ready
        assertTrue("Reconnected -> true", isAvailable.value)

        // Final disconnect
        connectionState.value = TransportState.Idle
        assertFalse("Idle again -> false", isAvailable.value)
    }

    @Test
    fun derivedFlow_remainsFalseThroughNonReadyTransitions() = runTest {
        val connectionState = MutableStateFlow<TransportState>(TransportState.Idle)
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        val isAvailable = connectionState
            .map { it is TransportState.Ready }
            .stateIn(testScope, SharingStarted.Eagerly, false)

        // Cycle through all non-Ready states
        val nonReadyStates = listOf(
            TransportState.Idle,
            TransportState.Connecting,
            TransportState.Failed(FailureReason.TransientNetwork),
            TransportState.Failed(FailureReason.AuthRejected),
        )

        for (state in nonReadyStates) {
            connectionState.value = state
            assertFalse(
                "isAvailable should be false for ${state::class.simpleName}",
                isAvailable.value
            )
        }
    }
}
