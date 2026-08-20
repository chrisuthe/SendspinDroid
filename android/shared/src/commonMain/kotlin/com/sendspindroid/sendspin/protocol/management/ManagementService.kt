package com.sendspindroid.sendspin.protocol.management

import com.sendspindroid.sendspin.crypto.PairingConfigStore
import com.sendspindroid.sendspin.crypto.TrustStore
import kotlinx.serialization.json.JsonObject

/**
 * What the connection should do with a management request.
 *
 * @param closeAfterReply a `client/goodbye` reason to send *after* the result
 *   frame, or null to leave the connection open. Only 3.3 (#229) produces one:
 *   removing the record that authenticated this very session.
 */
data class ManagementOutcome(
    val code: ManagementResultCode,
    val data: JsonObject? = null,
    val closeAfterReply: String? = null,
)

/**
 * The session facts a management request is judged against.
 *
 * Deliberately not the whole connection: the service is pure, so the rules can
 * be tested without a socket, a store, or a handshake.
 */
data class ManagementSessionContext(
    val hasManagementActivity: Boolean,
    val pinMethodEnabled: Boolean,
    /** The record that authenticated this session, if it was a record at all. */
    val matchedPskId: String? = null,
)

/**
 * Answers `management/` requests.
 *
 * `management.md#management`: "If a `management/...` message arrives on a
 * connection without `'management'` in activities, the client replies with
 * `management/result` `permission_denied`."
 *
 * Note what that does *not* say: it does not close the connection. The other
 * management gate - the server declaring `'management'` in `activities` on a
 * connection whose matched PSK does not permit it - does close, and lives in
 * `ServerActivateRules`. Keeping the two apart is the point of this class;
 * conflating them either drops a usable connection or leaves an unauthorised
 * one alive.
 */
class ManagementService(
    trustStore: TrustStore?,
    configStore: PairingConfigStore?,
) {

    /**
     * Null only on the legacy path, where no storage is wired up.
     *
     * Nullable rather than required so the permission gate below can run
     * without storage: whether a session may issue management commands is a
     * property of the session, not of what we can read. Requiring a store to
     * answer `permission_denied` would report `invalid` to an unauthorised
     * caller - the wrong reason, and one that invites a retry.
     */
    private val pairingConfig =
        if (trustStore != null && configStore != null) {
            PairingConfigOperations(trustStore, configStore)
        } else {
            null
        }

    private val records =
        if (trustStore != null && configStore != null) {
            RecordOperations(trustStore, configStore)
        } else {
            null
        }

    fun handle(
        request: ManagementRequest,
        session: ManagementSessionContext,
    ): ManagementOutcome {
        if (!session.hasManagementActivity) {
            // Answered, never closed, and never with data - a denial that
            // carried state would leak exactly what the denial withholds.
            return ManagementOutcome(ManagementResultCode.PERMISSION_DENIED)
        }

        return when (request) {
            is ManagementRequest.Unrecognized ->
                ManagementOutcome(ManagementResultCode.INVALID)

            ManagementRequest.OpenPairingWindow ->
                // "rejected as `invalid` when no PIN method is enabled". This
                // client implements neither PIN method (audit D2), so no PIN
                // method can be enabled and the answer is always invalid.
                ManagementOutcome(ManagementResultCode.INVALID)

            ManagementRequest.GetPairingConfig ->
                pairingConfig?.get() ?: ManagementOutcome(ManagementResultCode.INVALID)

            is ManagementRequest.SetPairingConfig ->
                ManagementOutcome(
                    pairingConfig?.set(request.patch) ?: ManagementResultCode.INVALID
                )

            ManagementRequest.ListRecords ->
                records?.list() ?: ManagementOutcome(ManagementResultCode.INVALID)

            is ManagementRequest.AddRecord ->
                records?.add(request) ?: ManagementOutcome(ManagementResultCode.INVALID)

            is ManagementRequest.RemoveRecord ->
                records?.remove(request.pskId, session.matchedPskId)
                    ?: ManagementOutcome(ManagementResultCode.INVALID)
        }
    }
}
