package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.PskCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ending a pairing attempt short of success.
 *
 * `pairing.md#client--server-pairabort`: "A `pair/abort` received after the
 * receiver has itself ended the attempt has no effect."
 *
 * `pairing.md#entering-and-leaving-pairing`: "On receipt the client abandons
 * the attempt, discarding all pairing state ... an abandoned attempt is not an
 * inner-authentication failure and does not touch the failure counter."
 */
class PairingAbortFlowTest {

    private fun startedAttempt(): PairingPskFlow {
        val flow = PairingPskFlow()
        val actions = flow.onEvent(
            PairingEvent.PairingActivation(PairMethod.PAIRING_PSK, PskCategory.PAIRING)
        )
        assertTrue(actions.any { it is PairingAction.SendPairFinalize })
        return flow
    }

    // ========== Receiving an abort ==========

    @Test
    fun aReceivedAbortEndsTheAttemptAndSendsNothingBack() {
        val flow = startedAttempt()

        val actions = flow.onEvent(PairingEvent.PairAbortReceived(PairAbortReason.USER_CANCELLED))

        // Answering an abort with an abort would have the two sides bouncing
        // the attempt back and forth; the spec has the receiver simply stop.
        assertEquals(emptyList<PairingAction>(), actions.filterIsInstance<PairingAction.SendPairAbort>())
        assertEquals(listOf(PairingAction.ClearAttemptTimeout), actions)
    }

    @Test
    fun aReceivedAbortPersistsNothingAfterwards() {
        val flow = startedAttempt()
        flow.onEvent(PairingEvent.PairAbortReceived(PairAbortReason.PIN_MISMATCH))

        // The server changing its mind and acknowledging anyway must not
        // resurrect a record for a secret we have already discarded.
        val actions = flow.onEvent(PairingEvent.ServerPairFinalize)

        assertEquals(emptyList<PairingAction>(), actions)
    }

    @Test
    fun anAbortWithNoAttemptInFlightHasNoEffect() {
        val flow = PairingPskFlow()

        for (reason in PairAbortReason.ALL) {
            assertEquals(
                "reason $reason produced actions with no attempt in flight",
                emptyList<PairingAction>(),
                flow.onEvent(PairingEvent.PairAbortReceived(reason)),
            )
        }
    }

    @Test
    fun aSecondAbortIsANoOp() {
        val flow = startedAttempt()
        flow.onEvent(PairingEvent.PairAbortReceived(PairAbortReason.USER_CANCELLED))

        assertEquals(
            emptyList<PairingAction>(),
            flow.onEvent(PairingEvent.PairAbortReceived(PairAbortReason.USER_CANCELLED)),
        )
    }

    @Test
    fun anAbortAfterSuccessHasNoEffect() {
        // The race that actually happens: the server's abort and its
        // pair-finalize cross on the wire. The record is already persisted and
        // must stay.
        val flow = startedAttempt()
        flow.onEvent(PairingEvent.ServerPairFinalize)

        assertEquals(
            emptyList<PairingAction>(),
            flow.onEvent(PairingEvent.PairAbortReceived(PairAbortReason.ATTEMPT_TIMEOUT)),
        )
    }

    @Test
    fun anAttemptCanStartAgainAfterTheNextActivation() {
        val flow = startedAttempt()
        flow.onEvent(PairingEvent.PairAbortReceived(PairAbortReason.USER_CANCELLED))

        val actions = flow.onEvent(
            PairingEvent.PairingActivation(PairMethod.PAIRING_PSK, PskCategory.PAIRING)
        )

        assertTrue(
            "a new server/activate must be able to start a fresh attempt",
            actions.any { it is PairingAction.SendPairFinalize },
        )
    }

    // ========== Operator cancellation ==========

    @Test
    fun theOperatorCancellingSendsUserCancelled() {
        val flow = startedAttempt()

        val actions = flow.onEvent(PairingEvent.UserCancelled)

        val abort = actions.filterIsInstance<PairingAction.SendPairAbort>().single()
        assertEquals(PairAbortReason.USER_CANCELLED, abort.reason)
        assertTrue(actions.contains(PairingAction.ClearAttemptTimeout))
    }

    @Test
    fun cancellingWithNoAttemptInFlightSendsNothing() {
        val flow = PairingPskFlow()

        assertEquals(emptyList<PairingAction>(), flow.onEvent(PairingEvent.UserCancelled))
    }

    @Test
    fun aCancelledAttemptPersistsNothingAfterwards() {
        val flow = startedAttempt()
        flow.onEvent(PairingEvent.UserCancelled)

        assertEquals(emptyList<PairingAction>(), flow.onEvent(PairingEvent.ServerPairFinalize))
    }
}
