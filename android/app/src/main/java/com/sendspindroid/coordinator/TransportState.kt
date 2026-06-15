package com.sendspindroid.coordinator

/**
 * Lifecycle of a single transport (SendSpin or MusicAssistant).
 *
 * The transport reports state changes; it does not decide retry. The
 * ConnectionCoordinator owns retry decisions based on Failed.reason.
 */
sealed class TransportState {
    object Idle : TransportState()
    object Connecting : TransportState()
    object Ready : TransportState()
    data class Failed(val reason: FailureReason) : TransportState()
}

/**
 * Why a transport ended up in TransportState.Failed.
 *
 * The Coordinator inspects this to decide retry/fallback/token-clear policy.
 * AuthRejected is the only reason that clears a stored Music Assistant token,
 * and by construction it requires a completed transport handshake.
 */
sealed class FailureReason {
    object TransientNetwork : FailureReason()
    object HandshakeFailed : FailureReason()
    object AuthRejected : FailureReason()
    object ProtocolError : FailureReason()
    object Exhausted : FailureReason()
}
