package com.sendspindroid.sendspin.protocol.management

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A management request as it arrived, before any field validation. */
sealed interface ManagementRequest {

    object ListRecords : ManagementRequest

    data class AddRecord(val psk: String, val serverId: String?) : ManagementRequest

    data class RemoveRecord(val pskId: String) : ManagementRequest

    object GetPairingConfig : ManagementRequest

    /** The raw patch. Absent keys mean "leave alone", so it cannot be flattened here. */
    data class SetPairingConfig(val patch: JsonObject) : ManagementRequest

    object OpenPairingWindow : ManagementRequest

    /**
     * A `management/` type this client does not implement.
     *
     * Modelled rather than dropped: every management request is answered, and
     * a request that falls through to the generic unhandled-message log leaves
     * the server waiting for a reply that never comes.
     */
    data class Unrecognized(val type: String) : ManagementRequest
}

/**
 * Turns a wire type and payload into a [ManagementRequest].
 *
 * Never throws and never rejects. Field-level validation belongs to
 * [ManagementService], which can answer `invalid` - a parser that threw would
 * take out the whole connection over one malformed field.
 */
object ManagementRequestParser {

    /** @return null when [type] is not a management request at all. */
    fun parse(type: String, payload: JsonObject?): ManagementRequest? {
        if (!type.startsWith(PREFIX)) return null

        return when (type) {
            SendSpinProtocol.MessageType.MANAGEMENT_LIST_RECORDS ->
                ManagementRequest.ListRecords

            SendSpinProtocol.MessageType.MANAGEMENT_ADD_RECORD ->
                ManagementRequest.AddRecord(
                    psk = payload.string("psk") ?: "",
                    serverId = payload.string("server_id"),
                )

            SendSpinProtocol.MessageType.MANAGEMENT_REMOVE_RECORD ->
                ManagementRequest.RemoveRecord(pskId = payload.string("psk_id") ?: "")

            SendSpinProtocol.MessageType.MANAGEMENT_GET_PAIRING_CONFIG ->
                ManagementRequest.GetPairingConfig

            SendSpinProtocol.MessageType.MANAGEMENT_SET_PAIRING_CONFIG ->
                ManagementRequest.SetPairingConfig(payload ?: JsonObject(emptyMap()))

            SendSpinProtocol.MessageType.MANAGEMENT_OPEN_PAIRING_WINDOW ->
                ManagementRequest.OpenPairingWindow

            else -> ManagementRequest.Unrecognized(type)
        }
    }

    private const val PREFIX = "management/"

    /** Null for a missing key or a non-string value; the service decides what that means. */
    private fun JsonObject?.string(key: String): String? =
        runCatching { this?.get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()
}
