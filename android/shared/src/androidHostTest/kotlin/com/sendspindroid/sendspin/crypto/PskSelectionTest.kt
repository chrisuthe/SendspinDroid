package com.sendspindroid.sendspin.crypto

import org.junit.Assert.*
import org.junit.Test

/**
 * PSK selection from the `psk_id` in Noise message 1.
 *
 * KKpsk2 mixes the PSK at the end of message 2, so the client must choose
 * before it can process that message; message 1's payload is decryptable
 * without the PSK precisely so this choice can be made.
 *
 * Selection has three outcomes, not two. "I do not know this psk_id" and "I
 * know it but it belongs to a different server" both close the socket with no
 * application-level message, so the only place they can ever be told apart is a
 * log line - and they mean very different things when one appears in the field.
 */
class PskSelectionTest {

    private val serverA = "OraobU4leb48EaZTGPbLhNug8XjEZsKXv8qiczhEGTU"
    private val serverB = "oGsvjWZwiEn6ivL-57RPbdBxRcGYovNllt9tcjDjtVM"

    private fun psk(fill: Byte) = ByteArray(Psk.PSK_SIZE) { fill }

    private fun setOf(vararg candidates: Psk) = PskCandidateSet.of(candidates.toList()).getOrThrow()

    @Test
    fun theSentinelIsSelectedByItsPublishedPskId() {
        val set = setOf(SentinelPsk.psk)
        val result = set.select(SentinelPsk.EXPECTED_PSK_ID, serverA)
        assertTrue(result is PskCandidateSet.Selection.Matched)
        assertEquals(
            PskCategory.SENTINEL,
            (result as PskCandidateSet.Selection.Matched).candidate.category,
        )
    }

    @Test
    fun aPairingPskIsSelectedByItsDerivedId() {
        val pairing = Psk(psk(7), PskCategory.PAIRING)
        val set = setOf(SentinelPsk.psk, pairing)
        val result = set.select(pairing.pskId, serverA)
        assertTrue(result is PskCandidateSet.Selection.Matched)
        assertEquals(
            PskCategory.PAIRING,
            (result as PskCandidateSet.Selection.Matched).candidate.category,
        )
    }

    @Test
    fun aRecordMatchesWhenTheServerIdAgrees() {
        val record = Psk(psk(1), PskCategory.LONG_TERM, serverA)
        val result = setOf(SentinelPsk.psk, record).select(record.pskId, serverA)
        assertTrue(result is PskCandidateSet.Selection.Matched)
    }

    @Test
    fun aRecordForADifferentServerIsAMismatchNotAMiss() {
        // The headline stored-pubkey case. Reporting this as "unknown psk_id"
        // would send the next person looking for a storage bug, when what
        // actually happened is that a server presented another server's record.
        val record = Psk(psk(1), PskCategory.LONG_TERM, serverA)
        val result = setOf(SentinelPsk.psk, record).select(record.pskId, serverB)
        assertTrue(
            "expected ServerIdMismatch, got $result",
            result is PskCandidateSet.Selection.ServerIdMismatch,
        )
        result as PskCandidateSet.Selection.ServerIdMismatch
        assertEquals(serverA, result.expected)
        assertEquals(serverB, result.actual)
    }

    @Test
    fun anUnknownPskIdIsAMiss() {
        val result = setOf(SentinelPsk.psk).select(Psk(psk(9), PskCategory.PAIRING).pskId, serverA)
        assertTrue(result is PskCandidateSet.Selection.NoMatch)
    }

    @Test
    fun aMalformedPskIdIsAMissRatherThanAThrow() {
        val set = setOf(SentinelPsk.psk)
        assertTrue(set.select("", serverA) is PskCandidateSet.Selection.NoMatch)
        assertTrue(set.select("not-a-psk-id", serverA) is PskCandidateSet.Selection.NoMatch)
        // 43 characters, correct shape, still unknown.
        assertTrue(set.select("A".repeat(43), serverA) is PskCandidateSet.Selection.NoMatch)
    }

    @Test
    fun unboundCandidatesSkipTheServerIdCheck() {
        // The Sentinel and the Pairing PSK are by definition not bound to a
        // server, so they must match whatever server_id turns up.
        val pairing = Psk(psk(7), PskCategory.PAIRING)
        val set = setOf(SentinelPsk.psk, pairing)
        assertTrue(set.select(SentinelPsk.EXPECTED_PSK_ID, serverB) is PskCandidateSet.Selection.Matched)
        assertTrue(set.select(pairing.pskId, serverB) is PskCandidateSet.Selection.Matched)
    }

    @Test
    fun aDisabledPairingMethodMakesItsPskIdAMiss() {
        // "A PSK for a pairing method disabled in the client's pairing config is
        // excluded from the candidate set, so a handshake referencing it fails
        // as a lookup miss." Exercised end to end through PskCandidates.build.
        val config = PairingConfig(psk(7), pairingPskEnabled = false, unpairedAccessEnabled = true, "record-mode-id")
        val built = PskCandidates.build(emptyList(), config)
        val set = PskCandidateSet.of(built).getOrThrow()
        assertTrue(set.select(config.pairingPskId, serverA) is PskCandidateSet.Selection.NoMatch)
    }
}
