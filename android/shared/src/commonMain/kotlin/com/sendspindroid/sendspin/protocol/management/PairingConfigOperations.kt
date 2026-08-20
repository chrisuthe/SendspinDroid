package com.sendspindroid.sendspin.protocol.management

import com.sendspindroid.sendspin.crypto.Base64Url
import com.sendspindroid.sendspin.crypto.PairingConfigStore
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskId
import com.sendspindroid.sendspin.crypto.SentinelPsk
import com.sendspindroid.sendspin.crypto.TrustStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * `get-pairing-config` and `set-pairing-config`.
 *
 * Split from [ManagementService] because the patch rules are where the subtlety
 * lives and they deserve to be readable on their own.
 */
internal class PairingConfigOperations(
    private val trustStore: TrustStore,
    private val configStore: PairingConfigStore,
) {

    fun get(): ManagementOutcome {
        val config = configStore.load()
        val data = buildJsonObject {
            put("pairing_psk", buildJsonObject { put("enabled", config.pairingPskEnabled) })
            put("record_mode", buildJsonObject { put("psk_id", config.recordModePskId) })
            put("unpaired_access", buildJsonObject { put("enabled", config.unpairedAccessEnabled) })
            // static_pin and dynamic_pin are absent, not disabled: "A PIN-method
            // object is absent if the client does not implement that method."
            // Emitting {"enabled": false} would advertise a method a server
            // could then ask us to turn on.
        }
        // Never the Pairing PSK itself. "Configured secrets ... are not
        // returned; use management/set-pairing-config to rotate them."
        return ManagementOutcome(ManagementResultCode.OK, data)
    }

    /**
     * Apply a patch, or nothing at all.
     *
     * Validation runs over the whole patch before a single write, because a
     * partially-applied rejected patch leaves the server's view of this client
     * disagreeing with the client's own, with no message that says so.
     */
    fun set(patch: JsonObject): ManagementResultCode {
        val plan = plan(patch) ?: return ManagementResultCode.INVALID
        if (plan is Rejected) return plan.code
        return apply(plan as Accepted)
    }

    // ========== Validation ==========

    private sealed interface Plan
    private data class Rejected(val code: ManagementResultCode) : Plan
    private data class Accepted(
        val pairingPskEnabled: Boolean? = null,
        val unpairedAccessEnabled: Boolean? = null,
        val recordModePskId: String? = null,
        val rotateTo: ByteArray? = null,
    ) : Plan

    /** @return null for a malformed patch; otherwise the decision. */
    private fun plan(patch: JsonObject): Plan? {
        // A method we do not implement may appear, but may not set anything.
        // "Fields set on a method the client does not implement are rejected as
        // invalid" - an empty object sets no fields, so it is a no-op.
        for (method in UNIMPLEMENTED_METHODS) {
            val obj = patch.presentObject(method) ?: if (patch.containsKey(method)) return null else continue
            if (obj.isNotEmpty()) return Rejected(ManagementResultCode.INVALID)
        }

        var accepted = Accepted()

        patch.presentObjectOrFail("pairing_psk")?.let { result ->
            val obj = result.getOrElse { return null }

            obj.presentBoolean("enabled")?.let { enabled ->
                accepted = accepted.copy(pairingPskEnabled = enabled.getOrElse { return null })
            }

            if (obj.containsKey("psk")) {
                val encoded = (obj["psk"] as? JsonPrimitive)?.contentOrNull ?: return null
                val psk = Base64Url.decodeOrNull(encoded) ?: return null
                if (psk.size != Psk.PSK_SIZE) return null

                val id = PskId.derive(psk)
                val config = configStore.load()
                if (id != config.pairingPskId && id in claimedPskIds()) {
                    // One psk_id must not map to two trust levels.
                    return Rejected(ManagementResultCode.ALREADY_EXISTS)
                }
                accepted = accepted.copy(rotateTo = psk)
            }
        }

        patch.presentObjectOrFail("unpaired_access")?.let { result ->
            val obj = result.getOrElse { return null }
            obj.presentBoolean("enabled")?.let { enabled ->
                accepted = accepted.copy(unpairedAccessEnabled = enabled.getOrElse { return null })
            }
        }

        patch.presentObjectOrFail("record_mode")?.let { result ->
            val obj = result.getOrElse { return null }
            if (obj.containsKey("psk_id")) {
                val pskId = (obj["psk_id"] as? JsonPrimitive)?.contentOrNull ?: return null
                // "psk_id MUST reference a shared-PSK record ... any management
                // request that would set psk_id to a missing or stored-pubkey
                // record is rejected." The Sentinel and the Pairing PSK are not
                // records at all, so they fail the same lookup.
                val record = trustStore.findByPskId(pskId)
                    ?: return Rejected(ManagementResultCode.INVALID)
                if (record.serverId != null) return Rejected(ManagementResultCode.INVALID)
                accepted = accepted.copy(recordModePskId = pskId)
            }
        }

        return accepted
    }

    // ========== Application ==========

    private fun apply(plan: Accepted): ManagementResultCode {
        plan.rotateTo?.let { psk ->
            when (configStore.rotatePairingPsk(psk, claimedPskIds())) {
                PairingConfigStore.RotateResult.Ok -> Unit
                PairingConfigStore.RotateResult.AlreadyExists ->
                    return ManagementResultCode.ALREADY_EXISTS
                PairingConfigStore.RotateResult.Invalid ->
                    return ManagementResultCode.INVALID
                PairingConfigStore.RotateResult.StorageFailed ->
                    return ManagementResultCode.STORAGE_EXHAUSTED
            }
        }
        plan.pairingPskEnabled?.let {
            if (!configStore.setEnabled(it)) return ManagementResultCode.STORAGE_EXHAUSTED
        }
        plan.unpairedAccessEnabled?.let {
            if (!configStore.setUnpairedAccess(it)) return ManagementResultCode.STORAGE_EXHAUSTED
        }
        plan.recordModePskId?.let {
            if (!configStore.setRecordModePskId(it)) return ManagementResultCode.STORAGE_EXHAUSTED
        }
        return ManagementResultCode.OK
    }

    private fun claimedPskIds(): Set<String> =
        trustStore.listRecords().map { it.pskId }.toSet() + SentinelPsk.psk.pskId

    // ========== Presence helpers ==========

    /**
     * Presence, not nullness.
     *
     * An absent key means "leave this alone"; a key present with a null or
     * non-object value is malformed. Collapsing the two - as `?.jsonObject`
     * would - silently accepts `{"pairing_psk": null}` as a no-op, so a
     * malformed patch reports success.
     */
    private fun JsonObject.presentObject(key: String): JsonObject? =
        if (containsKey(key)) this[key] as? JsonObject else null

    /** null when absent, Result.failure when present but not an object. */
    private fun JsonObject.presentObjectOrFail(key: String): Result<JsonObject>? {
        if (!containsKey(key)) return null
        val obj = this[key] as? JsonObject ?: return Result.failure(MalformedPatch)
        return Result.success(obj)
    }

    private fun JsonObject.presentBoolean(key: String): Result<Boolean>? {
        if (!containsKey(key)) return null
        val value = (this[key] as? JsonPrimitive)?.booleanOrNull ?: return Result.failure(MalformedPatch)
        return Result.success(value)
    }

    private companion object {
        val UNIMPLEMENTED_METHODS = listOf("static_pin", "dynamic_pin")
        val MalformedPatch = IllegalArgumentException("malformed patch")
    }
}
