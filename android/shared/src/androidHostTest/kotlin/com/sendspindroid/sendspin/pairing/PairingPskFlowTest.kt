package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCategory
import org.junit.Assert.*
import org.junit.Test

/**
 * The Pairing PSK flow: one message out, one message back.
 *
 * Two rules are easy to skip and impossible to notice when skipped:
 *
 *  - the connection must actually be keyed by the Pairing PSK before the
 *    long-term secret is sent. Skipping it leaks a fresh long-term PSK onto a
 *    Sentinel-keyed connection, which is unauthenticated and MITM-exposed.
 *  - the record is persisted only after the server acknowledges. Skipping that
 *    leaves the client holding a record the server never stored, surfacing much
 *    later as an unexplained `unauthorized`.
 *
 * Both are pure state, so both are pinned here rather than in an integration
 * test that would only exercise the happy path.
 */
class PairingPskFlowTest {

    private fun flow() = PairingPskFlow()

    private fun activation(method: String?, category: PskCategory) =
        PairingEvent.PairingActivation(method, category)

    private inline fun <reified T> List<PairingAction>.only(): T {
        assertEquals("expected exactly one action, got $this", 1, size)
        assertTrue("expected ${T::class.simpleName}, got ${first()}", first() is T)
        return first() as T
    }

    @Test
    fun theHappyPathSendsThePskThenPersistsTheSameBytes() {
        val flow = flow()
        val actions = flow.onEvent(activation("pairing_psk", PskCategory.PAIRING))

        val send = actions.filterIsInstance<PairingAction.SendPairFinalize>().single()
        assertEquals(Psk.PSK_SIZE, send.longTermPsk.size)
        assertTrue(actions.any { it is PairingAction.StartAttemptTimeout })

        val persisted = flow.onEvent(PairingEvent.ServerPairFinalize)
            .filterIsInstance<PairingAction.PersistRecord>().single()
        assertArrayEquals(
            "the persisted record must be the bytes we actually sent",
            send.longTermPsk, persisted.psk,
        )
    }

    @Test
    fun aSentinelKeyedConnectionNeverReceivesTheLongTermPsk() {
        // The headline security test. "Before sending client/pair-finalize, the
        // client MUST verify that the connection's matched PSK is the Pairing
        // PSK ...; on mismatch it aborts with pair/abort reason
        // method_not_supported."
        val actions = flow().onEvent(activation("pairing_psk", PskCategory.SENTINEL))

        assertTrue(
            "no PSK may be generated or sent on a non-Pairing-PSK connection: $actions",
            actions.none { it is PairingAction.SendPairFinalize },
        )
        val abort = actions.only<PairingAction.SendPairAbort>()
        assertEquals("method_not_supported", abort.reason)
    }

    @Test
    fun aLongTermKeyedConnectionNeverReceivesTheLongTermPskEither() {
        // Already paired is still not the Pairing PSK. The rule is about which
        // key authenticated this connection, not about how trusted it feels.
        val actions = flow().onEvent(activation("pairing_psk", PskCategory.LONG_TERM))
        assertTrue(actions.none { it is PairingAction.SendPairFinalize })
        assertEquals("method_not_supported", actions.only<PairingAction.SendPairAbort>().reason)
    }

    @Test
    fun aPinMethodIsRefusedBecauseThisClientDoesNotOfferOne() {
        for (method in listOf("dynamic_pin", "static_pin", null)) {
            val actions = flow().onEvent(activation(method, PskCategory.PAIRING))
            assertTrue(
                "method $method must not produce a PSK: $actions",
                actions.none { it is PairingAction.SendPairFinalize },
            )
            assertEquals(
                "method_not_supported",
                actions.only<PairingAction.SendPairAbort>().reason,
            )
        }
    }

    @Test
    fun anActivationInsteadOfAnAckPersistsNothing() {
        // "A client that, after sending client/pair-finalize, receives
        // server/activate likewise persists nothing." The server changed its
        // mind; we must discard the PSK we generated.
        val flow = flow()
        flow.onEvent(activation("pairing_psk", PskCategory.PAIRING))

        val onActivation = flow.onEvent(PairingEvent.NonPairingActivation)
        assertTrue(onActivation.none { it is PairingAction.PersistRecord })

        // And the attempt is genuinely over: a late ack must not resurrect it.
        val late = flow.onEvent(PairingEvent.ServerPairFinalize)
        assertTrue(
            "a late ack after the attempt ended must persist nothing: $late",
            late.none { it is PairingAction.PersistRecord },
        )
    }

    @Test
    fun theAttemptTimesOutAndPersistsNothingAfterwards() {
        val flow = flow()
        flow.onEvent(activation("pairing_psk", PskCategory.PAIRING))

        val onTimeout = flow.onEvent(PairingEvent.AttemptTimeout)
        assertEquals("attempt_timeout", onTimeout.only<PairingAction.SendPairAbort>().reason)

        assertTrue(
            flow.onEvent(PairingEvent.ServerPairFinalize)
                .none { it is PairingAction.PersistRecord }
        )
    }

    @Test
    fun aClosedConnectionPersistsNothing() {
        val flow = flow()
        flow.onEvent(activation("pairing_psk", PskCategory.PAIRING))
        assertTrue(
            flow.onEvent(PairingEvent.ConnectionClosed)
                .none { it is PairingAction.PersistRecord }
        )
        assertTrue(
            flow.onEvent(PairingEvent.ServerPairFinalize)
                .none { it is PairingAction.PersistRecord }
        )
    }

    @Test
    fun anUnexpectedAckIsSilentlyDiscarded() {
        // "A client that has aborted an attempt likewise silently discards
        // pairing messages received before the next server/activate." Silence,
        // not a protocol error - the connection stays usable.
        val flow = flow()
        assertTrue(flow.onEvent(PairingEvent.ServerPairFinalize).isEmpty())
    }

    @Test
    fun eachAttemptGeneratesAFreshPsk() {
        // A reused secret across attempts would mean a token shown once could
        // pair a second server the operator never saw.
        val first = flow().onEvent(activation("pairing_psk", PskCategory.PAIRING))
            .filterIsInstance<PairingAction.SendPairFinalize>().single().longTermPsk
        val second = flow().onEvent(activation("pairing_psk", PskCategory.PAIRING))
            .filterIsInstance<PairingAction.SendPairFinalize>().single().longTermPsk
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun aSecondActivationDuringAnAttemptDoesNotStartASecondOne() {
        val flow = flow()
        val first = flow.onEvent(activation("pairing_psk", PskCategory.PAIRING))
            .filterIsInstance<PairingAction.SendPairFinalize>().single()

        val again = flow.onEvent(activation("pairing_psk", PskCategory.PAIRING))
        assertTrue(
            "a repeated activation must not mint a second PSK: $again",
            again.none { it is PairingAction.SendPairFinalize },
        )

        // The original attempt still completes with its original bytes.
        val persisted = flow.onEvent(PairingEvent.ServerPairFinalize)
            .filterIsInstance<PairingAction.PersistRecord>().single()
        assertArrayEquals(first.longTermPsk, persisted.psk)
    }
}
