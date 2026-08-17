package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.PskCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The `server/activate` admissibility table.
 *
 * These rules decide whether an unauthenticated server gets to drive this
 * device's audio, so the negative cases matter more than the positive ones.
 */
class ServerActivateRulesTest {

    private fun parse(json: String): ServerActivate? =
        ServerActivateRules.parse(
            Json.parseToJsonElement(json).jsonObject["payload"]?.jsonObject
        )

    private fun activate(
        vararg activities: Activity,
        roles: List<String>? = null,
        method: String? = null,
    ) = ServerActivate(activities.toSet(), roles, method, null, emptyList())

    private fun evaluate(
        activate: ServerActivate,
        category: PskCategory = PskCategory.SENTINEL,
        unpairedAccess: Boolean = true,
        previousRoles: List<String> = emptyList(),
        first: Boolean = true,
        methods: Set<String> = setOf("pairing_psk"),
    ) = ServerActivateRules.evaluate(
        activate, category, unpairedAccess, previousRoles, first, methods
    )

    // ---- the spec's own worked example ----

    @Test
    fun sentinelPlaybackWithUnpairedAccessDisabledAsksForPairing() {
        // "Under a hypothetical unpaired_access: enabled, ['playback'] would be
        // an allowed set ... so the activation would be admissible: the client
        // closes with 'pairing_required'."
        val outcome = evaluate(
            activate(Activity.PLAYBACK, roles = listOf("player@v1")),
            unpairedAccess = false,
        )
        assertEquals(
            ActivationOutcome.Close(ServerActivateRules.GOODBYE_PAIRING_REQUIRED),
            outcome,
        )
    }

    @Test
    fun sentinelPlaybackPlusManagementIsUnauthorizedNotPairingRequired() {
        // "no unpaired-access setting makes that set allowed on the Sentinel
        // PSK, so the reason is 'unauthorized'." The distinction matters: one
        // tells the operator to pair, the other says never.
        val outcome = evaluate(
            activate(Activity.PLAYBACK, Activity.MANAGEMENT, roles = listOf("player@v1")),
            unpairedAccess = false,
        )
        assertEquals(
            ActivationOutcome.Close(ServerActivateRules.GOODBYE_UNAUTHORIZED),
            outcome,
        )
    }

    // ---- allowed sets per PSK category ----

    @Test
    fun sentinelAllowsEmptyPairingAndPlaybackOnly() {
        assertTrue(evaluate(activate()) is ActivationOutcome.Accept)
        assertTrue(evaluate(activate(Activity.PLAYBACK)) is ActivationOutcome.Accept)
        // management is never allowed on an unauthenticated session.
        assertEquals(
            ActivationOutcome.Close(ServerActivateRules.GOODBYE_UNAUTHORIZED),
            evaluate(activate(Activity.MANAGEMENT)),
        )
    }

    @Test
    fun pairingPskAllowsOnlyPairing() {
        val cat = PskCategory.PAIRING
        assertTrue(
            evaluate(activate(Activity.PAIRING, method = "pairing_psk"), category = cat)
                is ActivationOutcome.Accept
        )
        for (bad in listOf(setOf(Activity.PLAYBACK), emptySet(), setOf(Activity.MANAGEMENT))) {
            assertEquals(
                ActivationOutcome.Close(ServerActivateRules.GOODBYE_UNAUTHORIZED),
                evaluate(ServerActivate(bad, null, null, null, emptyList()), category = cat),
                "activities=$bad",
            )
        }
    }

    @Test
    fun longTermAllowsPairingAloneOrSubsetsOfPlaybackAndManagement() {
        val cat = PskCategory.LONG_TERM
        assertTrue(evaluate(activate(Activity.PAIRING, method = "dynamic_pin"),
            category = cat, methods = setOf("dynamic_pin")) is ActivationOutcome.Accept)
        assertTrue(evaluate(activate(Activity.PLAYBACK, Activity.MANAGEMENT),
            category = cat) is ActivationOutcome.Accept)
        assertTrue(evaluate(activate(), category = cat) is ActivationOutcome.Accept)
        // Mixing pairing with the others is not a permitted set.
        assertEquals(
            ActivationOutcome.Close(ServerActivateRules.GOODBYE_UNAUTHORIZED),
            evaluate(activate(Activity.PAIRING, Activity.PLAYBACK), category = cat),
        )
    }

    // ---- active_roles ----

    @Test
    fun rolesRequirePlaybackCapability() {
        // Pairing-only connections are not playback-capable, so they may not
        // carry roles.
        assertEquals(
            ActivationOutcome.Close(ServerActivateRules.GOODBYE_UNAUTHORIZED),
            evaluate(
                activate(Activity.PAIRING, roles = listOf("player@v1"), method = "pairing_psk"),
                category = PskCategory.PAIRING,
            ),
        )
    }

    @Test
    fun rolesMayBeCarriedWithoutPlaybackBeingDeclared() {
        // "it may do so even when 'playback' is not currently in activities."
        val outcome = evaluate(
            activate(roles = listOf("player@v1")),
            category = PskCategory.LONG_TERM,
        )
        assertEquals(ActivationOutcome.Accept(listOf("player@v1")), outcome)
    }

    @Test
    fun absentRolesOnTheFirstActivationMeanEmpty() {
        assertEquals(
            ActivationOutcome.Accept(emptyList()),
            evaluate(activate(Activity.PLAYBACK), first = true),
        )
    }

    @Test
    fun absentRolesLaterPersistThePreviousValue() {
        assertEquals(
            ActivationOutcome.Accept(listOf("player@v1")),
            evaluate(
                activate(Activity.PLAYBACK),
                previousRoles = listOf("player@v1"),
                first = false,
            ),
        )
    }

    @Test
    fun losingPlaybackCapabilityLaterClearsRolesRatherThanRejecting() {
        // "the persisted roles are treated as empty rather than the message
        // rejected" - a subtle rule that would otherwise drop the connection.
        val outcome = evaluate(
            activate(Activity.PAIRING, method = "pairing_psk"),
            category = PskCategory.PAIRING,
            previousRoles = listOf("player@v1"),
            first = false,
        )
        assertEquals(ActivationOutcome.Accept(emptyList()), outcome)
    }

    @Test
    fun sourceRoleIsRefusedWhenUntrusted() {
        // A source captures microphone or line-in audio; an unauthenticated
        // server must never be handed it.
        for (category in listOf(PskCategory.SENTINEL, PskCategory.PAIRING)) {
            val outcome = evaluate(
                activate(Activity.PLAYBACK, roles = listOf("source@v1")),
                category = category,
            )
            assertEquals(
                ActivationOutcome.Close(ServerActivateRules.GOODBYE_UNAUTHORIZED),
                outcome,
                category.name,
            )
        }
        // Permitted once paired.
        assertTrue(
            evaluate(
                activate(Activity.PLAYBACK, roles = listOf("source@v1")),
                category = PskCategory.LONG_TERM,
            ) is ActivationOutcome.Accept
        )
    }

    // ---- pairing method ----

    @Test
    fun aMethodWeDoNotOfferAbortsButKeepsTheConnection() {
        val outcome = evaluate(
            activate(Activity.PAIRING, method = "static_pin"),
            methods = setOf("pairing_psk"),
        )
        assertEquals(
            ActivationOutcome.AbortPairing(ServerActivateRules.ABORT_METHOD_NOT_SUPPORTED),
            outcome,
        )
    }

    @Test
    fun pairingPskIsOnlyValidOnAPairingPskSession() {
        // "MUST be 'pairing_psk' if and only if the matched PSK is the Pairing PSK."
        assertEquals(
            ActivationOutcome.AbortPairing(ServerActivateRules.ABORT_METHOD_NOT_SUPPORTED),
            evaluate(activate(Activity.PAIRING, method = "pairing_psk"),
                category = PskCategory.SENTINEL),
        )
        assertEquals(
            ActivationOutcome.AbortPairing(ServerActivateRules.ABORT_METHOD_NOT_SUPPORTED),
            evaluate(activate(Activity.PAIRING, method = "dynamic_pin"),
                category = PskCategory.PAIRING, methods = setOf("dynamic_pin")),
        )
    }

    // ---- parsing ----

    @Test
    fun parsesActivitiesRolesAndPairing() {
        val a = parse(
            """{"type":"server/activate","payload":{"activities":["playback"],
               "active_roles":["player@v1"],"pairing":{"method":"dynamic_pin","pin_length":6}}}"""
        )!!
        assertEquals(setOf(Activity.PLAYBACK), a.activities)
        assertEquals(listOf("player@v1"), a.activeRoles)
        assertEquals("dynamic_pin", a.pairingMethod)
        assertEquals(6, a.pinLength)
    }

    @Test
    fun distinguishesAbsentRolesFromEmptyRoles() {
        assertNull(parse("""{"payload":{"activities":[]}}""")!!.activeRoles)
        assertEquals(
            emptyList(),
            parse("""{"payload":{"activities":[],"active_roles":[]}}""")!!.activeRoles,
        )
    }

    @Test
    fun ignoresUnknownActivitiesRatherThanFailing() {
        // Forward compatibility: "MUST ignore unrecognized payload fields".
        val a = parse("""{"payload":{"activities":["playback","teleport"]}}""")!!
        assertEquals(setOf(Activity.PLAYBACK), a.activities)
        assertEquals(listOf("teleport"), a.unknownActivities)
    }

    @Test
    fun missingActivitiesIsUnparseable() {
        assertNull(parse("""{"payload":{"active_roles":[]}}"""))
        assertNull(ServerActivateRules.parse(null))
    }
}
