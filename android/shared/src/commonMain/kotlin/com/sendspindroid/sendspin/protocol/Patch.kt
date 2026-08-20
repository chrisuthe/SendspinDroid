package com.sendspindroid.sendspin.protocol

import com.sendspindroid.shared.log.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * One field of a `server/state` delta.
 *
 * `messaging.md#server--client-serverstate`: "Only include fields that have
 * changed ... A leaf field set to `null` should be cleared from the client's
 * state."
 *
 * Three wire states, and collapsing any two of them is a different bug:
 *
 * | wire            | this type  | effect |
 * |-----------------|------------|--------|
 * | key absent      | [Absent]   | keep what we have |
 * | key with `null` | [Cleared]  | remove what we have |
 * | key with value  | [Set]      | replace what we have |
 *
 * Kotlin's `T?` only has room for two of those, which is why this type exists
 * rather than a nullable field: `as? JsonObject` and `?: ""` both quietly turn
 * "absent" and "cleared" into the same thing.
 */
sealed interface Patch<out T> {

    /** The key was not in the payload. */
    object Absent : Patch<Nothing>

    /** The key was present with a JSON `null`. */
    object Cleared : Patch<Nothing>

    /** The key was present with a value. */
    data class Set<T>(val value: T) : Patch<T>
}

/**
 * @return the field's new value, given its current one.
 *
 * An extension rather than a member: [Patch.Absent] and [Patch.Cleared] are
 * `Patch<Nothing>`, and a member function would make the compiler emit a bridge
 * that casts the return to `Void` - which throws ClassCastException the moment
 * a real value passes through it.
 */
fun <T> Patch<T>.applyTo(current: T?): T? = when (this) {
    Patch.Absent -> current
    Patch.Cleared -> null
    is Patch.Set -> value
}

/**
 * A whole role object in a `server/state`.
 *
 * `messaging.md#server--client-serverstate`: "a whole role object set to `null`
 * clears all of that role's state." That is what `server/activate` sends when
 * it removes `metadata`, `color` or `controller` from `active_roles`, "so the
 * client never holds live data for an inactive role".
 */
sealed interface RoleUpdate<out S> {

    object Absent : RoleUpdate<Nothing>

    object Cleared : RoleUpdate<Nothing>

    data class Delta<S>(val patch: StatePatch<S>) : RoleUpdate<S>
}

/** @return the role's new state, given its current one. See [Patch.applyTo]. */
fun <S> RoleUpdate<S>.applyTo(current: S?): S? = when (this) {
    RoleUpdate.Absent -> current
    RoleUpdate.Cleared -> null
    is RoleUpdate.Delta -> patch.applyTo(current)
}

/** A delta over one role's state object. */
interface StatePatch<S> {
    fun applyTo(current: S?): S
}

/**
 * Reads [key] as a [Patch].
 *
 * A [decode] that returns null - a value of the wrong JSON type - degrades to
 * [Patch.Absent] rather than throwing or clearing. "Clients and servers MUST
 * ignore unrecognized `payload` fields", and a malformed field is closer to
 * unrecognized than to a deliberate clear: guessing "clear" would delete state
 * the server never asked us to touch.
 */
fun <T> JsonObject.patch(key: String, decode: (JsonElement) -> T?): Patch<T> {
    val element = this[key] ?: return Patch.Absent
    if (element is JsonNull) return Patch.Cleared
    val decoded = decode(element)
    if (decoded == null) {
        Log.w("Patch", "Ignoring $key: unexpected JSON shape")
        return Patch.Absent
    }
    return Patch.Set(decoded)
}
