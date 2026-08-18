package com.sendspindroid.sendspin.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serialises the record store to a single string for the preferences layer.
 *
 * JSON rather than the `::`/`|` concatenation used elsewhere in `UserSettings`:
 * a `server_id` is peer-controlled and a base64url PSK contains `-` and `_`, so
 * a separator scheme is one unlucky value away from splitting a record in half.
 *
 * The PSK is stored base64url unpadded, the same encoding the wire uses, so a
 * value seen in storage and a value seen in a log line are directly comparable.
 */
object PskRecordCodec {

    @Serializable
    private data class Wire(
        val pskId: String,
        val psk: String,
        val serverId: String? = null,
        val used: Boolean = false,
    )

    // ignoreUnknownKeys so a field added by a later version does not make an
    // otherwise-valid store unreadable and silently unpair the device.
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(records: List<PskRecord>): String =
        json.encodeToString(
            records.map { Wire(it.pskId, Base64Url.encode(it.psk), it.serverId, it.used) }
        )

    /**
     * Never throws.
     *
     * A single unreadable entry is skipped rather than failing the whole load:
     * discarding every record because one is corrupt would unpair the device
     * from every server it knows, and the only symptom would be an
     * `unauthorized` on the next connect.
     */
    fun decode(blob: String): List<PskRecord> {
        if (blob.isBlank()) return emptyList()
        val wire = runCatching { json.decodeFromString<List<Wire>>(blob) }.getOrNull()
            ?: return emptyList()
        return wire.mapNotNull { entry ->
            val bytes = Base64Url.decodeOrNull(entry.psk) ?: return@mapNotNull null
            if (bytes.size != Psk.PSK_SIZE) return@mapNotNull null
            PskRecord(entry.pskId, bytes, entry.serverId, entry.used)
        }
    }
}
