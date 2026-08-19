package com.sendspindroid.sendspin.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `pair/abort` reason set.
 *
 * `pairing.md#client--server-pairabort` fixes both the vocabulary and one
 * behavioural split: "With reason `concurrent_attempt` the sender closes the
 * connection after sending, otherwise the connection stays open."
 *
 * Pinned rather than merely declared, because both halves drift silently. An
 * extra reason is one a peer will not recognise; a missing one leaves a real
 * situation with no way to say what happened. And getting the close set wrong
 * either hangs a connection the spec says to close, or drops one the peer still
 * expects to use.
 */
class PairAbortReasonTest {

    @Test
    fun theReasonSetIsExactlyTheSpecSet() {
        assertEquals(
            setOf(
                "attempt_timeout",
                "concurrent_attempt",
                "method_not_supported",
                "pin_length_unacceptable",
                "pin_mismatch",
                "user_cancelled",
            ),
            PairAbortReason.ALL,
        )
    }

    @Test
    fun onlyConcurrentAttemptClosesTheConnection() {
        assertEquals(setOf("concurrent_attempt"), PairAbortReason.CLOSES_CONNECTION)
    }

    @Test
    fun everyClosingReasonIsAReason() {
        // Guards the copy-paste failure where CLOSES_CONNECTION names a string
        // that is not in ALL, which would make the close branch unreachable.
        assertEquals(emptySet<String>(), PairAbortReason.CLOSES_CONNECTION - PairAbortReason.ALL)
    }
}
