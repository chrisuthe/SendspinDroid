package com.sendspindroid.musicassistant.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

/**
 * Temporary bridge utilities for converting between org.json.JSONObject
 * and kotlinx.serialization.json.JsonObject.
 *
 * This exists during the migration period while the shared MaApiTransport
 * interface uses JsonObject but the app-side transports (MaWebSocketTransport,
 * MaDataChannelTransport) still use org.json internally.
 *
 * Will be removed in Phase 12 when MaWebSocketTransport is rewritten with Ktor.
 */

/** Convert kotlinx JsonObject to org.json JSONObject. */
fun JsonObject.toOrgJson(): JSONObject = JSONObject(this.toString())

/** Convert org.json JSONObject to kotlinx JsonObject. */
fun JSONObject.toKotlinxJson(): JsonObject =
    Json.parseToJsonElement(this.toString()).jsonObject
