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

    /**
     * The server answered `client/init` with a legacy `server/hello`, so it
     * predates mandatory encryption (spec #84, 2026-06-29) and this client has
     * no unencrypted path to offer it.
     *
     * Separate from [HandshakeFailed] because it is the only connection failure
     * with a remedy the user controls: upgrade the server. Reported as such
     * rather than as a generic "could not connect".
     */
    object ServerLacksEncryption : FailureReason()
}
