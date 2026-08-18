package com.sendspindroid.sendspin.crypto

import org.junit.Assert.*
import org.junit.Test

/**
 * Serialisation of the record store.
 *
 * The load path is the dangerous one: a decode that throws away everything on
 * one bad entry silently unpairs the device from every server it knows, and the
 * only symptom is an `unauthorized` on the next connect. So a broken entry is
 * skipped, never fatal.
 */
class PskRecordCodecTest {

    private fun psk(fill: Byte) = ByteArray(Psk.PSK_SIZE) { fill }

    private fun record(fill: Byte, serverId: String?, used: Boolean = false) =
        PskRecord(PskId.derive(psk(fill)), psk(fill), serverId, used)

    @Test
    fun roundTripsRecordsIncludingASharedPskRecordAndTheUsedFlag() {
        val original = listOf(
            record(1, "server-a"),
            record(2, "server-b", used = true),
            record(3, null),  // shared-PSK record: no server binding
        )
        val decoded = PskRecordCodec.decode(PskRecordCodec.encode(original))

        assertEquals(3, decoded.size)
        for ((a, b) in original.zip(decoded)) {
            assertEquals(a.pskId, b.pskId)
            assertEquals(a.serverId, b.serverId)
            assertEquals(a.used, b.used)
            // Byte equality specifically: a base64 round trip that dropped or
            // added padding would still produce a plausible-looking record.
            assertArrayEquals(a.psk, b.psk)
        }
    }

    @Test
    fun decodePreservesRecordsWhenTheBlobCarriesAnUnknownKey() {
        // Forward compatibility: a later version adding a field must not
        // wipe the store when this version reads it back.
        val id = PskId.derive(psk(1))
        val b64 = Base64Url.encode(psk(1))
        val blob = """[{"pskId":"$id","psk":"$b64","serverId":"server-a","used":false,"future":42}]"""
        val decoded = PskRecordCodec.decode(blob)
        assertEquals(1, decoded.size)
        assertEquals("server-a", decoded[0].serverId)
    }

    @Test
    fun decodeSkipsAStructurallyBrokenEntryAndKeepsTheRest() {
        val id = PskId.derive(psk(1))
        val b64 = Base64Url.encode(psk(1))
        val blob = """[
            {"pskId":"$id","psk":"$b64","serverId":"server-a","used":false},
            {"pskId":"broken","psk":"not-base64!!","serverId":null,"used":false}
        ]"""
        val decoded = PskRecordCodec.decode(blob)
        assertEquals("the good record must survive the bad one", 1, decoded.size)
        assertEquals(id, decoded[0].pskId)
    }

    @Test
    fun decodeSkipsAnEntryWhosePskIsTheWrongLength() {
        val id = PskId.derive(psk(1))
        val b64 = Base64Url.encode(psk(1))
        val short = Base64Url.encode(ByteArray(16))
        val blob = """[
            {"pskId":"$id","psk":"$b64","serverId":"server-a","used":false},
            {"pskId":"short","psk":"$short","serverId":null,"used":false}
        ]"""
        val decoded = PskRecordCodec.decode(blob)
        assertEquals(1, decoded.size)
    }

    @Test
    fun decodeOfEmptyOrGarbageIsAnEmptyListNotAnException() {
        assertTrue(PskRecordCodec.decode("").isEmpty())
        assertTrue(PskRecordCodec.decode("not json").isEmpty())
        assertTrue(PskRecordCodec.decode("{}").isEmpty())
    }

    @Test
    fun encodeOfNoRecordsRoundTripsToNoRecords() {
        assertTrue(PskRecordCodec.decode(PskRecordCodec.encode(emptyList())).isEmpty())
    }
}
