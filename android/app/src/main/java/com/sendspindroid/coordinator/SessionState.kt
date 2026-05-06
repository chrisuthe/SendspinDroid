package com.sendspindroid.coordinator

import com.sendspindroid.model.UnifiedServer

/**
 * Top-level published state of the connection session.
 *
 * UI consumers observe ConnectionCoordinator.sessionState and derive whatever
 * roll-up they need. The matrix of (sendSpin, musicAssistant) state combinations
 * is too combinatorial to enumerate; this flat record exposes exactly what is true.
 */
data class SessionState(
    val server: UnifiedServer? = null,
    val sendSpin: TransportState = TransportState.Idle,
    val musicAssistant: TransportState = TransportState.Idle,
)
