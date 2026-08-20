package com.sendspindroid.sendspin.protocol.management

import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The management routing spine: parsing requests, the result vocabulary, and
 * the per-message permission gate.
 *
 * `management.md#management`: "All `management/...` requests are answered by a
 * single `management/result` message. At most one management request may be in
 * flight per connection; in-order WebSocket delivery makes the reply
 * unambiguous."
 *
 * The distinction this file exists to protect: an unauthorised *activation*
 * closes the connection, while an unauthorised *message* is answered with
 * `permission_denied` and the connection stays open. Conflating them either
 * drops a usable connection or leaves an unauthorised one alive.
 */
class ManagementRoutingTest {

    // ========== The result vocabulary ==========

    @Test
    fun theResultCodesAreExactlyTheSpecSet() {
        assertEquals(
            setOf(
                "ok",
                "permission_denied",
                "already_exists",
                "invalid",
                "not_found",
                "storage_exhausted",
            ),
            ManagementResultCode.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun aResultCarriesTheCodeAndNothingElse() {
        val json = parse(MessageBuilder.buildManagementResult(ManagementResultCode.OK))

        assertEquals("management/result", json["type"]?.jsonPrimitive?.content)
        assertEquals(setOf("result"), json["payload"]!!.jsonObject.keys)
        assertEquals("ok", json["payload"]!!.jsonObject["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun noResultCarriesAStorageObject() {
        // "a client whose storage is effectively unbounded or of unknown size
        // omits the key, and the server relies on storage_exhausted alone."
        // Records are ~100 bytes in EncryptedSharedPreferences on a filesystem
        // measured in gigabytes, so any capacity we invented would corrupt the
        // server's free/cost arithmetic.
        for (code in ManagementResultCode.entries) {
            val payload = parse(MessageBuilder.buildManagementResult(code))["payload"]!!.jsonObject
            assertFalse("$code carried a storage key", payload.containsKey("storage"))
        }
    }

    @Test
    fun noResultCarriesARequestIdentifier() {
        // "The at-most-one-in-flight rule lets the server match each reply to
        // its request by ordering alone, so no request-identifier field is
        // carried." An invented id would be ignored at best.
        val payload = parse(
            MessageBuilder.buildManagementResult(
                ManagementResultCode.OK,
                buildJsonObject { put("records", "[]") },
            )
        )["payload"]!!.jsonObject

        for (key in listOf("id", "request_id", "seq")) {
            assertFalse("payload carried $key", payload.containsKey(key))
        }
    }

    @Test
    fun dataRidesAlongOnlyWithOk() {
        val data = buildJsonObject { put("pairing_psk_enabled", true) }

        val ok = parse(MessageBuilder.buildManagementResult(ManagementResultCode.OK, data))
        assertEquals(true, ok["payload"]!!.jsonObject["pairing_psk_enabled"]?.jsonPrimitive?.content?.toBoolean())

        // A failure carrying data invites the server to read state out of a
        // reply that says the operation did not happen.
        val denied = parse(
            MessageBuilder.buildManagementResult(ManagementResultCode.PERMISSION_DENIED, data)
        )
        assertEquals(setOf("result"), denied["payload"]!!.jsonObject.keys)
    }

    // ========== Parsing ==========

    @Test
    fun nonManagementTypesAreNotOurs() {
        for (type in listOf("server/state", "client/hello", "stream/start", "management")) {
            assertNull(type, ManagementRequestParser.parse(type, null))
        }
    }

    @Test
    fun everyImplementedTypeParses() {
        assertTrue(ManagementRequestParser.parse("management/list-records", null) is ManagementRequest.ListRecords)
        assertTrue(ManagementRequestParser.parse("management/get-pairing-config", null) is ManagementRequest.GetPairingConfig)
        assertTrue(ManagementRequestParser.parse("management/open-pairing-window", null) is ManagementRequest.OpenPairingWindow)

        val add = ManagementRequestParser.parse(
            "management/add-record",
            buildJsonObject { put("psk", "AAAA"); put("server_id", "srv1") },
        )
        assertEquals(ManagementRequest.AddRecord("AAAA", "srv1"), add)

        val remove = ManagementRequestParser.parse(
            "management/remove-record",
            buildJsonObject { put("psk_id", "abc") },
        )
        assertEquals(ManagementRequest.RemoveRecord("abc"), remove)

        val set = ManagementRequestParser.parse(
            "management/set-pairing-config",
            buildJsonObject { put("pairing_psk_enabled", false) },
        )
        assertTrue(set is ManagementRequest.SetPairingConfig)
    }

    @Test
    fun anUnknownManagementTypeIsStillOursToAnswer() {
        // It must reach the service and come back as `invalid`, not fall
        // through to the generic unhandled-message log where the server would
        // wait for a reply that never comes.
        val request = ManagementRequestParser.parse("management/does-not-exist", null)

        assertEquals(ManagementRequest.Unrecognized("management/does-not-exist"), request)
    }

    @Test
    fun theParserNeverThrowsOnBadPayloads() {
        val nonsense = listOf(
            null,
            buildJsonObject { },
            buildJsonObject { put("psk", 42) },
            buildJsonObject { put("psk_id", true) },
        )

        for (payload in nonsense) {
            for (type in MANAGEMENT_TYPES) {
                // Field validation belongs to the service, which answers
                // `invalid`. A throwing parser would take out the connection
                // over a malformed field.
                assertNotNull("$type with $payload", ManagementRequestParser.parse(type, payload))
            }
        }
    }

    // ========== The permission gate ==========

    @Test
    fun everyRequestOutsideAManagementSessionIsDenied() {
        val service = service()

        for (request in ALL_REQUESTS) {
            val outcome = service.handle(request, session(management = false))

            assertEquals("$request", ManagementResultCode.PERMISSION_DENIED, outcome.code)
            assertNull("$request carried data", outcome.data)
            assertNull("$request asked to close", outcome.closeAfterReply)
        }
    }

    @Test
    fun anUnknownRequestInsideASessionIsInvalid() {
        val outcome = service()
            .handle(ManagementRequest.Unrecognized("management/nope"), session(management = true))

        assertEquals(ManagementResultCode.INVALID, outcome.code)
    }

    @Test
    fun openingAPairingWindowIsInvalidWithNoPinMethod() {
        // "rejected as `invalid` when no PIN method is enabled." This client
        // implements neither PIN method (audit D2), so it is always invalid.
        val outcome = service()
            .handle(ManagementRequest.OpenPairingWindow, session(management = true))

        assertEquals(ManagementResultCode.INVALID, outcome.code)
    }

    @Test
    fun aDeniedRequestNeverClosesTheConnection() {
        // The single most important behaviour here: an unauthorised *message*
        // is answered and the connection continues. Only an unauthorised
        // *activation* closes, and that is decided in ServerActivateRules.
        for (request in ALL_REQUESTS) {
            assertNull(service().handle(request, session(management = false)).closeAfterReply)
        }
    }

    // ========== Helpers ==========

    /** The gating tests do not touch storage; any consistent pair will do. */
    private fun service() = ManagementService(
        PairingConfigManagementTest.FakeTrust(),
        PairingConfigManagementTest.FakeConfigStore(),
    )

    private fun session(management: Boolean) = ManagementSessionContext(
        hasManagementActivity = management,
        pinMethodEnabled = false,
    )

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private companion object {
        val MANAGEMENT_TYPES = listOf(
            "management/list-records",
            "management/add-record",
            "management/remove-record",
            "management/get-pairing-config",
            "management/set-pairing-config",
            "management/open-pairing-window",
        )

        val ALL_REQUESTS = listOf(
            ManagementRequest.ListRecords,
            ManagementRequest.AddRecord("AAAA", "srv1"),
            ManagementRequest.RemoveRecord("abc"),
            ManagementRequest.GetPairingConfig,
            ManagementRequest.SetPairingConfig(buildJsonObject { }),
            ManagementRequest.OpenPairingWindow,
            ManagementRequest.Unrecognized("management/nope"),
        )
    }
}
