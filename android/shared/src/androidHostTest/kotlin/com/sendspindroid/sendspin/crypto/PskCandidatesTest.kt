package com.sendspindroid.sendspin.crypto

import org.junit.Assert.*
import org.junit.Test

/**
 * Composition of the handshake candidate set.
 *
 * This is the one place that decides which secrets a handshake may match, and
 * it must be answerable with no reference to UI state: the server re-handshakes
 * to the Pairing PSK unprompted, so a candidate list assembled only while a
 * pairing screen is open would fail that handshake as a bare socket close with
 * nothing to diagnose.
 */
class PskCandidatesTest {

    private fun psk(fill: Byte) = ByteArray(Psk.PSK_SIZE) { fill }

    private fun record(fill: Byte, serverId: String?) =
        PskRecord(PskId.derive(psk(fill)), psk(fill), serverId)

    private fun config(enabled: Boolean = true, pairing: Byte = 7) =
        PairingConfig(
            pairingPsk = psk(pairing),
            pairingPskEnabled = enabled,
            unpairedAccessEnabled = true,
        )

    @Test
    fun theEnabledPairingPskAppearsExactlyOnceWithItsDerivedId() {
        val built = PskCandidates.build(emptyList(), config(enabled = true))
        val pairing = built.filter { it.category == PskCategory.PAIRING }
        assertEquals(1, pairing.size)
        assertEquals(PskId.derive(psk(7)), pairing.single().pskId)
    }

    @Test
    fun aDisabledPairingPskIsExcludedButNothingElseChanges() {
        // "A PSK for a pairing method disabled in the client's pairing config is
        // excluded from the candidate set, so a handshake referencing it fails
        // as a lookup miss."
        val records = listOf(record(1, "server-a"))
        val enabled = PskCandidates.build(records, config(enabled = true))
        val disabled = PskCandidates.build(records, config(enabled = false))

        assertTrue(disabled.none { it.category == PskCategory.PAIRING })
        assertEquals(
            enabled.filter { it.category != PskCategory.PAIRING }.map { it.pskId },
            disabled.map { it.pskId },
        )
    }

    @Test
    fun theSentinelIsAlwaysPresentWhateverTheRecordCount() {
        for (records in listOf(
            emptyList(),
            listOf(record(1, "server-a")),
            listOf(record(1, "server-a"), record(2, "server-b")),
        )) {
            val built = PskCandidates.build(records, config())
            assertEquals(
                "sentinel missing for ${records.size} records",
                1,
                built.count { it.category == PskCategory.SENTINEL },
            )
            assertEquals(
                SentinelPsk.EXPECTED_PSK_ID,
                built.single { it.category == PskCategory.SENTINEL }.pskId,
            )
        }
    }

    @Test
    fun everyRecordBecomesALongTermCandidateCarryingItsServerBinding() {
        val built = PskCandidates.build(
            listOf(record(1, "server-a"), record(2, null)),
            config(),
        )
        val longTerm = built.filter { it.category == PskCategory.LONG_TERM }
        assertEquals(2, longTerm.size)
        assertEquals("server-a", longTerm.single { it.pskId == PskId.derive(psk(1)) }.serverId)
        assertNull(longTerm.single { it.pskId == PskId.derive(psk(2)) }.serverId)
    }

    @Test
    fun noInputCombinationProducesADuplicatePskId() {
        // A duplicate would make one wire psk_id map to two trust levels, and
        // PskCandidateSet.of would refuse the whole set - leaving the client
        // unable to handshake with anything at all.
        for (enabled in listOf(true, false)) {
            for (records in listOf(
                emptyList(),
                listOf(record(1, "server-a")),
                listOf(record(1, "server-a"), record(2, "server-b")),
            )) {
                val built = PskCandidates.build(records, config(enabled = enabled))
                assertEquals(
                    "duplicate psk_id for enabled=$enabled records=${records.size}",
                    built.size,
                    built.map { it.pskId }.toSet().size,
                )
                assertTrue(PskCandidateSet.of(built).isSuccess)
            }
        }
    }

    @Test
    fun pairingPskIsCandidateWithNoPairingActivityRunning() {
        // The standing obligation. "The client MUST keep its Pairing PSK among
        // its handshake PSK candidates whenever the method is enabled, not only
        // while a pairing activity is running: the server's re-handshake to the
        // Pairing PSK succeeds only if the client already recognizes its
        // psk_id."
        //
        // Nothing in build()'s signature can express "a pairing screen is open",
        // which is the point: there is no way to make this conditional without
        // deliberately adding a parameter for it.
        val built = PskCandidates.build(listOf(record(1, "server-a")), config(enabled = true))
        assertTrue(
            "the Pairing PSK must be a candidate with no pairing in progress",
            built.any { it.category == PskCategory.PAIRING },
        )
    }
}
