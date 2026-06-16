package com.sendspindroid.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the telemetry payload to the collector's strict schema
 * (sendspindroid-website/collector/validate.js, schemaVersion 1). If these keys
 * drift, the collector rejects uploads with 400.
 */
class TelemetryWireTest {

    private fun recovered() = HandoffEpisode(
        startTs = 1718560000000,
        fromTransport = "WIFI",
        toTransport = "CELLULAR",
        wasPlaying = true,
        configuredMethods = listOf("LOCAL", "REMOTE"),
        attempts = listOf(HandoffEpisode.Attempt("PROXY"), HandoffEpisode.Attempt("REMOTE")),
        outcome = HandoffEpisode.Outcome.RECOVERED,
        recoveredMethod = "REMOTE",
        recoveryMs = 4200,
    )

    @Test
    fun `envelope has exactly the collector's keys and values`() {
        val obj = Json.parseToJsonElement(
            TelemetryWire.envelope(recovered(), "12345678-1234-1234-1234-123456789abc", "2.0.0-Beta12", 36)
        ).jsonObject

        assertEquals(
            setOf("schemaVersion", "installId", "appVersion", "androidSdk", "episode"),
            obj.keys
        )
        assertEquals(1, obj["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals("12345678-1234-1234-1234-123456789abc", obj["installId"]!!.jsonPrimitive.content)
        assertEquals("2.0.0-Beta12", obj["appVersion"]!!.jsonPrimitive.content)
        assertEquals(36, obj["androidSdk"]!!.jsonPrimitive.int)

        val e = obj["episode"]!!.jsonObject
        assertEquals(
            setOf(
                "startTs", "fromTransport", "toTransport", "wasPlaying",
                "configuredMethods", "attempts", "outcome", "recoveredMethod", "recoveryMs"
            ),
            e.keys
        )
        assertEquals("WIFI", e["fromTransport"]!!.jsonPrimitive.content)
        assertEquals("CELLULAR", e["toTransport"]!!.jsonPrimitive.content)
        assertEquals(true, e["wasPlaying"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("LOCAL", "REMOTE"), e["configuredMethods"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("PROXY", e["attempts"]!!.jsonArray[0].jsonObject["method"]!!.jsonPrimitive.content)
        assertEquals("RECOVERED", e["outcome"]!!.jsonPrimitive.content)
        assertEquals("REMOTE", e["recoveredMethod"]!!.jsonPrimitive.content)
        assertEquals(4200, e["recoveryMs"]!!.jsonPrimitive.int)
    }

    @Test
    fun `null recoveredMethod and recoveryMs serialize as JSON null`() {
        val episode = recovered().copy(
            outcome = HandoffEpisode.Outcome.EXHAUSTED,
            recoveredMethod = null,
            recoveryMs = null,
        )
        val e = Json.parseToJsonElement(TelemetryWire.envelope(episode, "id", "v", 30))
            .jsonObject["episode"]!!.jsonObject
        assertTrue(e["recoveredMethod"] is JsonNull)
        assertTrue(e["recoveryMs"] is JsonNull)
    }

    @Test
    fun `attempt with null method serializes as null`() {
        val episode = recovered().copy(attempts = listOf(HandoffEpisode.Attempt(null)))
        val e = Json.parseToJsonElement(TelemetryWire.envelope(episode, "id", "v", 30))
            .jsonObject["episode"]!!.jsonObject
        assertTrue(e["attempts"]!!.jsonArray[0].jsonObject["method"] is JsonNull)
    }
}
