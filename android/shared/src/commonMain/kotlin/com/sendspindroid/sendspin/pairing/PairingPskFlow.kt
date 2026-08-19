package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.secureRandomBytes

/** What happened. */
sealed interface PairingEvent {
    /**
     * A `server/activate` declaring the `pairing` activity.
     *
     * @param matchedCategory the category of the PSK that authenticated THIS
     *   connection, from the handshake. Never re-derived: it is the only thing
     *   standing between a long-term secret and an unauthenticated channel.
     */
    data class PairingActivation(
        val method: String?,
        val matchedCategory: PskCategory,
    ) : PairingEvent

    /** `server/pair-finalize`: the server has persisted its side. */
    object ServerPairFinalize : PairingEvent

    /** A `server/activate` without `pairing`, which ends any attempt. */
    object NonPairingActivation : PairingEvent

    object AttemptTimeout : PairingEvent

    object ConnectionClosed : PairingEvent
}

/** What the connection should do about it. */
sealed interface PairingAction {
    /** Send `client/pair-finalize` carrying this secret. */
    class SendPairFinalize(psk: ByteArray) : PairingAction {
        private val secret = psk.copyOf()
        val longTermPsk: ByteArray get() = secret.copyOf()
    }

    data class SendPairAbort(val reason: String) : PairingAction

    /** Store the record. Only ever emitted after the server acknowledges. */
    class PersistRecord(psk: ByteArray) : PairingAction {
        private val secret = psk.copyOf()
        val psk: ByteArray get() = secret.copyOf()
    }

    object StartAttemptTimeout : PairingAction

    object ClearAttemptTimeout : PairingAction
}

/**
 * The Pairing PSK flow, as a pure state machine.
 *
 * `pairing.md#pairing-psk-flow`. No I/O and no coroutines, because the two
 * rules that matter are both about *whether* something is emitted, and those
 * are far easier to pin here than through a live connection:
 *
 *  1. The long-term PSK is generated and sent only when the connection is
 *     actually keyed by the Pairing PSK. On any other key the client aborts
 *     with `method_not_supported` and generates nothing at all. Sending it over
 *     a Sentinel-keyed session would hand a fresh long-term secret to an
 *     unauthenticated, MITM-exposed channel.
 *  2. The record is persisted only on `server/pair-finalize`. Persisting
 *     earlier leaves the client holding a credential the server never stored,
 *     which surfaces later as an `unauthorized` with no explanation.
 *
 * Single attempt at a time. The connection owns one of these.
 */
class PairingPskFlow {

    enum class State { IDLE, AWAITING_ACK, DONE, ABORTED }

    var state: State = State.IDLE
        private set

    /** The secret for the attempt in flight. Discarded on every exit path. */
    private var pending: ByteArray? = null

    fun onEvent(event: PairingEvent): List<PairingAction> = when (event) {
        is PairingEvent.PairingActivation -> onPairingActivation(event)

        PairingEvent.ServerPairFinalize -> {
            val psk = pending
            if (state != State.AWAITING_ACK || psk == null) {
                // "A client that has aborted an attempt likewise silently
                // discards pairing messages received before the next
                // server/activate." Silence, not a protocol error.
                emptyList()
            } else {
                // Build the action FIRST: it copies, and discard() zeroes the
                // very array `psk` points at. Zeroing before the copy persists
                // 32 zero bytes - a record that authenticates nothing, stored
                // as though pairing had succeeded.
                val action = PairingAction.PersistRecord(psk)
                discard()
                state = State.DONE
                listOf(action, PairingAction.ClearAttemptTimeout)
            }
        }

        PairingEvent.NonPairingActivation -> {
            // "The same server/activate can also end a pairing attempt without
            // finalizing: sent in place of server/pair-finalize, it persists
            // nothing and discards any received PSK."
            val wasWaiting = state == State.AWAITING_ACK
            discard()
            state = State.IDLE
            if (wasWaiting) listOf(PairingAction.ClearAttemptTimeout) else emptyList()
        }

        PairingEvent.AttemptTimeout -> {
            if (state != State.AWAITING_ACK) {
                emptyList()
            } else {
                discard()
                state = State.ABORTED
                listOf(PairingAction.SendPairAbort(PairAbortReason.ATTEMPT_TIMEOUT))
            }
        }

        PairingEvent.ConnectionClosed -> {
            // Nothing to send: the socket is gone. Just make sure the secret
            // does not outlive the attempt.
            val wasWaiting = state == State.AWAITING_ACK
            discard()
            state = State.IDLE
            if (wasWaiting) listOf(PairingAction.ClearAttemptTimeout) else emptyList()
        }
    }

    private fun onPairingActivation(event: PairingEvent.PairingActivation): List<PairingAction> {
        // Refuse before generating anything. Order matters: a PSK minted and
        // then discarded is a PSK that existed in memory for no reason.
        if (event.matchedCategory != PskCategory.PAIRING) {
            return listOf(PairingAction.SendPairAbort(PairAbortReason.METHOD_NOT_SUPPORTED))
        }
        if (event.method != PairMethod.PAIRING_PSK) {
            // The PIN methods are not offered by this client (audit D2), so any
            // other method is one we cannot run.
            return listOf(PairingAction.SendPairAbort(PairAbortReason.METHOD_NOT_SUPPORTED))
        }
        if (state == State.AWAITING_ACK) {
            // An attempt is already in flight. Minting a second secret would
            // leave the first one un-acknowledgeable.
            return emptyList()
        }

        val psk = secureRandomBytes(Psk.PSK_SIZE)
        pending = psk
        state = State.AWAITING_ACK
        return listOf(
            PairingAction.SendPairFinalize(psk),
            PairingAction.StartAttemptTimeout,
        )
    }

    /** Zero the secret rather than dropping the reference. */
    private fun discard() {
        pending?.fill(0)
        pending = null
    }
}

/** `pair/abort` reasons (`pairing.md#client--server-pairabort`). */
object PairAbortReason {
    const val ATTEMPT_TIMEOUT = "attempt_timeout"
    const val CONCURRENT_ATTEMPT = "concurrent_attempt"
    const val METHOD_NOT_SUPPORTED = "method_not_supported"
    const val PIN_LENGTH_UNACCEPTABLE = "pin_length_unacceptable"
    const val PIN_MISMATCH = "pin_mismatch"
    const val USER_CANCELLED = "user_cancelled"
}

/** Pairing method wire names. */
object PairMethod {
    const val PAIRING_PSK = "pairing_psk"
    const val DYNAMIC_PIN = "dynamic_pin"
    const val STATIC_PIN = "static_pin"
}
