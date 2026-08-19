package com.sendspindroid.sendspin.protocol.management

import com.sendspindroid.sendspin.crypto.Base64Url
import com.sendspindroid.sendspin.crypto.PairingConfigStore
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskId
import com.sendspindroid.sendspin.crypto.SentinelPsk
import com.sendspindroid.sendspin.crypto.TrustStore
import com.sendspindroid.sendspin.protocol.GoodbyeReason
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `list-records`, `add-record` and `remove-record`.
 *
 * `management.md#records`. The delicate part is not the CRUD, it is that all
 * three PSK categories share one `psk_id` namespace: "Two categories sharing
 * one would make a single wire `psk_id` map to two trust levels." So the
 * collision check covers records, the Sentinel, and our own Pairing PSK, and it
 * lives in exactly one place.
 */
internal class RecordOperations(
    private val trustStore: TrustStore,
    private val configStore: PairingConfigStore,
) {

    fun list(): ManagementOutcome {
        val records = buildJsonArray {
            for (record in trustStore.listRecords()) {
                add(
                    buildJsonObject {
                        put("psk_id", record.pskId)
                        // Absent, not null, for a shared-PSK record: "present
                        // for stored-pubkey records, absent for shared-PSK
                        // records". A null would read as "bound to nothing"
                        // rather than "not bound", and the two behave
                        // differently at handshake time.
                        record.serverId?.let { put("server_id", it) }
                        put("used", record.used)
                    }
                )
            }
        }
        return ManagementOutcome(
            ManagementResultCode.OK,
            buildJsonObject { put("records", records) },
        )
    }

    fun add(request: ManagementRequest.AddRecord): ManagementOutcome {
        val psk = decodePsk(request.psk)
            ?: return ManagementOutcome(ManagementResultCode.INVALID)

        // A server_id is a Curve25519 public key, so it is the same shape as a
        // psk_id: 43 base64url characters over 32 bytes.
        request.serverId?.let { serverId ->
            val decoded = Base64Url.decodeOrNull(serverId)
            if (decoded == null || decoded.size != KEY_SIZE) {
                return ManagementOutcome(ManagementResultCode.INVALID)
            }
        }

        if (PskId.derive(psk) in claimedPskIds()) {
            return ManagementOutcome(ManagementResultCode.ALREADY_EXISTS)
        }

        return when (val result = trustStore.addRecord(psk, request.serverId)) {
            is TrustStore.AddRecordResult.Ok -> ManagementOutcome(ManagementResultCode.OK)
            TrustStore.AddRecordResult.AlreadyExists ->
                ManagementOutcome(ManagementResultCode.ALREADY_EXISTS)
            TrustStore.AddRecordResult.Invalid ->
                ManagementOutcome(ManagementResultCode.INVALID)
            TrustStore.AddRecordResult.StorageFailed ->
                ManagementOutcome(ManagementResultCode.STORAGE_EXHAUSTED)
        }
    }

    /** Order matters here; see the inline notes. */
    fun remove(pskId: String, matchedPskId: String?): ManagementOutcome {
        if (pskId.isEmpty()) return ManagementOutcome(ManagementResultCode.INVALID)

        // Not-found comes before the pinned check, and covers the Sentinel and
        // the Pairing PSK: neither is a record, so removing one is `not_found`
        // rather than `invalid`.
        trustStore.findByPskId(pskId)
            ?: return ManagementOutcome(ManagementResultCode.NOT_FOUND)

        if (pskId == configStore.load().recordModePskId) {
            // "the referenced shared-PSK record cannot be removed while the
            // reference exists".
            return ManagementOutcome(ManagementResultCode.INVALID)
        }

        if (!trustStore.removeRecord(pskId)) {
            // `invalid` rather than inventing a code the outcomes line for this
            // request does not list.
            return ManagementOutcome(ManagementResultCode.INVALID)
        }

        // "Removing the requester's own record closes the management session
        // with client/goodbye reason 'unauthorized' AFTER the response." The
        // reply has to go first: it is the only thing that tells the server the
        // removal happened, and a close alone reads as a failure.
        val ourOwn = matchedPskId != null && matchedPskId == pskId
        return ManagementOutcome(
            ManagementResultCode.OK,
            closeAfterReply = if (ourOwn) GoodbyeReason.UNAUTHORIZED.wire else null,
        )
    }

    /** Every `psk_id` already spoken for, across all three categories. */
    private fun claimedPskIds(): Set<String> =
        trustStore.listRecords().map { it.pskId }.toSet() +
            SentinelPsk.psk.pskId +
            configStore.load().pairingPskId

    /** 43 base64url characters over exactly 32 bytes, no padding. */
    private fun decodePsk(encoded: String): ByteArray? {
        if (encoded.length != ENCODED_PSK_LENGTH) return null
        val bytes = Base64Url.decodeOrNull(encoded) ?: return null
        return bytes.takeIf { it.size == Psk.PSK_SIZE }
    }

    private companion object {
        const val KEY_SIZE = 32
        const val ENCODED_PSK_LENGTH = 43
    }
}
