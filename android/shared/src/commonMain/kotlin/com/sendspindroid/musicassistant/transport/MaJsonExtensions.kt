package com.sendspindroid.musicassistant.transport

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Extension functions on [JsonObject] and [JsonArray] that mirror the org.json API.
 *
 * These make the conversion from `org.json.JSONObject` to `kotlinx.serialization.json.JsonObject`
 * largely mechanical: replace `json.optString(...)` with the same call and it compiles.
 *
 * All "opt" methods return a default if the key is missing or the value is the wrong type
 * (never throw). This matches org.json behavior.
 */

/** Get a string value, or [default] if missing or null. */
fun JsonObject.optString(key: String, default: String = ""): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: default

/** Get an int value, or [default] if missing or not a number. */
fun JsonObject.optInt(key: String, default: Int = 0): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: default

/** Get a long value, or [default] if missing or not a number. */
fun JsonObject.optLong(key: String, default: Long = 0L): Long =
    this[key]?.jsonPrimitive?.longOrNull ?: default

/** Get a boolean value, or [default] if missing. */
fun JsonObject.optBoolean(key: String, default: Boolean = false): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default

/** Get a nested JsonObject, or null if missing or not an object. */
fun JsonObject.optJsonObject(key: String): JsonObject? =
    try { this[key]?.jsonObject } catch (_: Exception) { null }

/** Get a nested JsonArray, or null if missing or not an array. */
fun JsonObject.optJsonArray(key: String): JsonArray? =
    try { this[key]?.jsonArray } catch (_: Exception) { null }

/** Check if a key exists in the object. */
fun JsonObject.has(key: String): Boolean = key in this

/** Get a double value, or [default] if missing or not a number. */
fun JsonObject.optDouble(key: String, default: Double = 0.0): Double =
    this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: default
