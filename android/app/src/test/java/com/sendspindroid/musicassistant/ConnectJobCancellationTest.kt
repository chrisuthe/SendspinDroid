package com.sendspindroid.musicassistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the connectJob cancellation pattern used in MusicAssistantManager (H-22).
 *
 * MusicAssistantManager.connectWithToken() now stores its launched coroutine in
 * a `connectJob` field and cancels any previous job on re-entry. These tests verify
 * that the pattern correctly prevents two concurrent connect attempts from racing.
 *
 * We test the pattern in isolation (not via the MusicAssistantManager singleton)
 * because the manager requires Android Context, MaSettings, and real transports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectJobCancellationTest {

    /**
     * Simulates the connect-with-cancel pattern as implemented in MusicAssistantManager.
     * The "winner" is whichever coroutine completes last (since earlier ones get cancelled).
     */
    private class ConnectSimulator(scope: CoroutineScope) {
        @Volatile
        var connectJob: Job? = null

        @Volatile
        var activeTransportId: String? = null

        @Volatile
        var connectAttemptCount = 0

        @Volatile
        var cancellationCount = 0

        private val scope = scope

        /**
         * Mirrors the MusicAssistantManager.connectWithToken() pattern:
         * cancel previous job, then launch a new one that "connects" a transport.
         */
        fun connectWithToken(transportId: String, connectDelay: CompletableDeferred<Unit>? = null) {
            connectJob?.cancel()
            connectJob = scope.launch {
                connectAttemptCount++
                try {
                    // Simulate the async connect work (network call, auth handshake, etc.)
                    connectDelay?.await() ?: delay(100)

                    // If we get here, this connect "won" -- store the transport
                    activeTransportId = transportId
                } catch (e: CancellationException) {
                    cancellationCount++
                    throw e
                }
            }
        }

        fun disconnect() {
            connectJob?.cancel()
            connectJob = null
            activeTransportId = null
        }
    }

    @Test
    fun singleConnect_completesNormally() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val simulator = ConnectSimulator(scope)
        val ready = CompletableDeferred<Unit>()

        simulator.connectWithToken("transport-1", ready)
        ready.complete(Unit)
        advanceUntilIdle()

        assertEquals("transport-1", simulator.activeTransportId)
        assertEquals(1, simulator.connectAttemptCount)
        assertEquals(0, simulator.cancellationCount)
    }

    @Test
    fun rapidDoubleConnect_cancelsFirst() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val simulator = ConnectSimulator(scope)
        val ready1 = CompletableDeferred<Unit>()
        val ready2 = CompletableDeferred<Unit>()

        // First connect starts but does not complete yet
        simulator.connectWithToken("transport-1", ready1)

        // Second connect fires before first completes -- cancels first
        simulator.connectWithToken("transport-2", ready2)

        // Complete the second connect
        ready2.complete(Unit)
        advanceUntilIdle()

        // The second connect should win; first should have been cancelled
        assertEquals("transport-2", simulator.activeTransportId)
        assertEquals(1, simulator.cancellationCount)
    }

    @Test
    fun tripleConnect_onlyLastSurvives() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val simulator = ConnectSimulator(scope)
        val ready1 = CompletableDeferred<Unit>()
        val ready2 = CompletableDeferred<Unit>()
        val ready3 = CompletableDeferred<Unit>()

        simulator.connectWithToken("transport-1", ready1)
        simulator.connectWithToken("transport-2", ready2)
        simulator.connectWithToken("transport-3", ready3)

        // Only complete the third
        ready3.complete(Unit)
        advanceUntilIdle()

        assertEquals("transport-3", simulator.activeTransportId)
        assertEquals(2, simulator.cancellationCount)
    }

    @Test
    fun disconnectCancelsInFlightConnect() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val simulator = ConnectSimulator(scope)
        val ready = CompletableDeferred<Unit>()

        simulator.connectWithToken("transport-1", ready)

        // Disconnect before connect completes
        simulator.disconnect()
        advanceUntilIdle()

        // Transport should be cleared, connect should have been cancelled
        assertEquals(null, simulator.activeTransportId)
        assertEquals(1, simulator.cancellationCount)
    }

    @Test
    fun completedConnectJob_doesNotAffectNextConnect() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val simulator = ConnectSimulator(scope)
        val ready1 = CompletableDeferred<Unit>()
        val ready2 = CompletableDeferred<Unit>()

        // First connect completes normally
        simulator.connectWithToken("transport-1", ready1)
        ready1.complete(Unit)
        advanceUntilIdle()
        assertEquals("transport-1", simulator.activeTransportId)

        // Second connect (cancels the already-completed job -- no-op cancellation)
        simulator.connectWithToken("transport-2", ready2)
        ready2.complete(Unit)
        advanceUntilIdle()

        assertEquals("transport-2", simulator.activeTransportId)
        // Cancelling an already-completed job doesn't increment cancellationCount
        assertEquals(0, simulator.cancellationCount)
    }

    @Test
    fun cancelledFirstConnect_doesNotOverwriteSecond() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val simulator = ConnectSimulator(scope)
        val ready1 = CompletableDeferred<Unit>()
        val ready2 = CompletableDeferred<Unit>()

        // Start first connect
        simulator.connectWithToken("transport-1", ready1)

        // Start second connect (cancels first)
        simulator.connectWithToken("transport-2", ready2)

        // Complete second
        ready2.complete(Unit)
        advanceUntilIdle()
        assertEquals("transport-2", simulator.activeTransportId)

        // Now complete first (it was already cancelled, so this should be a no-op)
        ready1.complete(Unit)
        advanceUntilIdle()

        // The active transport should still be transport-2, NOT overwritten by transport-1
        assertEquals("transport-2", simulator.activeTransportId)
    }

    @Test
    fun connectJobFieldIsNull_afterDisconnect() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val simulator = ConnectSimulator(scope)

        simulator.connectWithToken("transport-1")
        advanceUntilIdle()

        simulator.disconnect()

        // connectJob should be null after disconnect
        assertEquals(null, simulator.connectJob)
        assertEquals(null, simulator.activeTransportId)
    }

    @Test
    fun connectJobFieldIsSet_duringConnect() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val simulator = ConnectSimulator(scope)
        val ready = CompletableDeferred<Unit>()

        simulator.connectWithToken("transport-1", ready)

        // Job should be set and active
        assertTrue("connectJob should be active", simulator.connectJob?.isActive == true)

        ready.complete(Unit)
        advanceUntilIdle()

        // Job should be completed
        assertFalse("connectJob should not be active after completion", simulator.connectJob?.isActive == true)
    }
}
