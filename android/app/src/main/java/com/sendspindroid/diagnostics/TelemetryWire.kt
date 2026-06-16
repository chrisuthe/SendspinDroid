package com.sendspindroid.diagnostics

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the telemetry wire payload for a [HandoffEpisode]. Pure (kotlinx JSON),
 * so it's unit-tested to stay byte-compatible with the collector's strict
 * allow-list validator (sendspindroid-website/collector/validate.js, schemaVersion 1).
 */
object TelemetryWire {

    const val SCHEMA_VERSION = 1

    fun envelope(
        episode: HandoffEpisode,
        installId: String,
        appVersion: String,
        androidSdk: Int,
    ): String = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("installId", installId)
        put("appVersion", appVersion)
        put("androidSdk", androidSdk)
        putJsonObject("episode") {
            put("startTs", episode.startTs)
            put("fromTransport", episode.fromTransport)
            put("toTransport", episode.toTransport)
            put("wasPlaying", episode.wasPlaying)
            putJsonArray("configuredMethods") { episode.configuredMethods.forEach { add(it) } }
            putJsonArray("attempts") {
                episode.attempts.forEach { attempt -> addJsonObject { put("method", attempt.method) } }
            }
            put("outcome", episode.outcome.name)
            put("recoveredMethod", episode.recoveredMethod)
            put("recoveryMs", episode.recoveryMs)
        }
    }.toString()
}
