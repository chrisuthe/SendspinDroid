package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.PskCategory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The purposes a server may declare on a connection. */
enum class Activity(val wireName: String) {
    PLAYBACK("playback"),
    PAIRING("pairing"),
    MANAGEMENT("management");

    companion object {
        fun fromWire(value: String): Activity? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * A parsed `server/activate`.
 *
 * @param activeRoles null means the field was ABSENT, which is different from
 *   present-and-empty: an absent value persists whatever the previous
 *   activation set, while an empty list clears the roles.
 */
data class ServerActivate(
    val activities: Set<Activity>,
    val activeRoles: List<String>?,
    val pairingMethod: String?,
    val pinLength: Int?,
    /** Activities the client did not recognise; ignored, but worth logging. */
    val unknownActivities: List<String>,
)

/** What the client must do about an activation. */
sealed interface ActivationOutcome {
    /** Accept, with the roles that are now live. */
    data class Accept(val activeRoles: List<String>) : ActivationOutcome

    /** Close the connection with this `client/goodbye` reason, sending nothing else. */
    data class Close(val goodbyeReason: String) : ActivationOutcome

    /** Reply `pair/abort` with this reason; the connection stays open. */
    data class AbortPairing(val reason: String) : ActivationOutcome
}

/**
 * Parsing and admissibility for `server/activate`.
 *
 * The admissibility table (`messaging.md#server--client-serveractivate`) binds
 * what a server may declare to which PSK admitted the connection:
 *
 * | PSK matched  | Allowed activity sets                                    |
 * |--------------|----------------------------------------------------------|
 * | Sendspin PSK | `['pairing']` or any subset of `{playback, management}`   |
 * | Pairing PSK  | `['pairing']`                                            |
 * | Sentinel PSK | `[]`, `['pairing']`, `['playback']`*                      |
 *
 * \* `['playback']` on the Sentinel only when the client has unpaired access
 * enabled - which is why advertising `unpaired_access` in `client/hello` is what
 * makes unpaired playback possible at all.
 */
object ServerActivateRules {

    const val GOODBYE_UNAUTHORIZED = "unauthorized"
    const val GOODBYE_PAIRING_REQUIRED = "pairing_required"
    const val ABORT_METHOD_NOT_SUPPORTED = "method_not_supported"

    /** A source captures sensitive audio, so it may never run untrusted. */
    const val ROLE_SOURCE_V1 = "source@v1"

    fun parse(payload: JsonObject?): ServerActivate? {
        if (payload == null) return null
        val rawActivities = payload["activities"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: return null   // activities is required
        val known = mutableSetOf<Activity>()
        val unknown = mutableListOf<String>()
        for (raw in rawActivities) {
            val activity = Activity.fromWire(raw)
            if (activity != null) known += activity else unknown += raw
        }
        // Absent stays null; present-but-empty becomes an empty list.
        val roles = payload["active_roles"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        val pairing = payload["pairing"] as? JsonObject
        return ServerActivate(
            activities = known,
            activeRoles = roles,
            // The client ignores `pairing` unless 'pairing' is in activities.
            pairingMethod = pairing?.get("method")?.jsonPrimitive?.contentOrNull,
            pinLength = pairing?.get("pin_length")?.jsonPrimitive?.intOrNull,
            unknownActivities = unknown,
        )
    }

    /** Is [activities] a set this PSK category may declare? */
    fun activitiesAllowed(
        category: PskCategory,
        activities: Set<Activity>,
        unpairedAccessEnabled: Boolean,
    ): Boolean = when (category) {
        // 'pairing' alone, or any subset of {playback, management} - so a set
        // mixing pairing with either of the others is never allowed.
        PskCategory.LONG_TERM ->
            activities == setOf(Activity.PAIRING) ||
                activities.none { it == Activity.PAIRING }

        PskCategory.PAIRING -> activities == setOf(Activity.PAIRING)

        PskCategory.SENTINEL -> when {
            activities.isEmpty() -> true
            activities == setOf(Activity.PAIRING) -> true
            activities == setOf(Activity.PLAYBACK) -> unpairedAccessEnabled
            else -> false
        }
    }

    /**
     * "A connection is playback-capable when its activities extended with
     * 'playback' are an allowed set for the matched PSK."
     *
     * Only a playback-capable connection may carry non-empty `active_roles`,
     * and it may do so even when 'playback' is not currently declared.
     */
    fun playbackCapable(
        category: PskCategory,
        activities: Set<Activity>,
        unpairedAccessEnabled: Boolean,
    ): Boolean = activitiesAllowed(
        category, activities + Activity.PLAYBACK, unpairedAccessEnabled
    )

    /**
     * Decide what to do with an activation.
     *
     * @param previousRoles roles persisted from an earlier activation, used when
     *   [activate] omits the field.
     * @param offeredPairMethods the LIVE pairing configuration, which may have
     *   drifted from what `client/hello` advertised.
     */
    fun evaluate(
        activate: ServerActivate,
        category: PskCategory,
        unpairedAccessEnabled: Boolean,
        previousRoles: List<String>,
        isFirstActivation: Boolean,
        offeredPairMethods: Set<String>,
    ): ActivationOutcome {
        // "A client treats a first server/activate that omits it as carrying an
        // empty active_roles"; later omissions persist the previous value.
        val requestedRoles = activate.activeRoles
            ?: if (isFirstActivation) emptyList() else previousRoles

        val allowed = activitiesAllowed(category, activate.activities, unpairedAccessEnabled)
        val capable = playbackCapable(category, activate.activities, unpairedAccessEnabled)

        // Rule 1: would enabling unpaired access have made this admissible? If
        // so the server is asking for something we could grant but have turned
        // off, and the honest answer is "pair with me first" rather than a flat
        // unauthorized. Only reachable on the Sentinel.
        if (category == PskCategory.SENTINEL && !unpairedAccessEnabled) {
            val allowedIfEnabled =
                activitiesAllowed(category, activate.activities, unpairedAccessEnabled = true)
            val capableIfEnabled =
                playbackCapable(category, activate.activities, unpairedAccessEnabled = true)
            val admissibleIfEnabled = allowedIfEnabled &&
                (requestedRoles.isEmpty() || capableIfEnabled) &&
                !forbidsRoleAtTrustLevel(category, requestedRoles)
            if (!allowed || (requestedRoles.isNotEmpty() && !capable)) {
                if (admissibleIfEnabled) {
                    return ActivationOutcome.Close(GOODBYE_PAIRING_REQUIRED)
                }
            }
        }

        // Rule 2: structurally not permitted for this PSK, whatever we enable.
        if (!allowed) return ActivationOutcome.Close(GOODBYE_UNAUTHORIZED)
        if (requestedRoles.isNotEmpty() && !capable) {
            // The spec is specific here: a LATER activation that drops
            // playback-capability without explicitly sending active_roles
            // treats the persisted roles as empty rather than rejecting.
            if (activate.activeRoles == null && !isFirstActivation) {
                return ActivationOutcome.Accept(emptyList())
            }
            return ActivationOutcome.Close(GOODBYE_UNAUTHORIZED)
        }
        if (forbidsRoleAtTrustLevel(category, requestedRoles)) {
            return ActivationOutcome.Close(GOODBYE_UNAUTHORIZED)
        }

        // Rule 3: a pairing method we cannot or must not run. Connection stays
        // open - the server may re-activate with a different method.
        if (Activity.PAIRING in activate.activities) {
            val method = activate.pairingMethod
            val methodAllowedForPsk = when (category) {
                // "pairing.method MUST be 'pairing_psk' if and only if the
                // matched PSK is the Sendspin Pairing PSK."
                PskCategory.PAIRING -> method == "pairing_psk"
                else -> method != null && method != "pairing_psk"
            }
            if (method == null || !methodAllowedForPsk || method !in offeredPairMethods) {
                return ActivationOutcome.AbortPairing(ABORT_METHOD_NOT_SUPPORTED)
            }
        }

        return ActivationOutcome.Accept(requestedRoles)
    }

    /**
     * `source@v1` MUST NOT be activated at trust level 'none' - it captures
     * microphone or line-in audio, so an unauthenticated server must never get
     * it. Trust is `user` only for a long-term record.
     */
    private fun forbidsRoleAtTrustLevel(category: PskCategory, roles: List<String>): Boolean =
        category != PskCategory.LONG_TERM && roles.contains(ROLE_SOURCE_V1)
}
