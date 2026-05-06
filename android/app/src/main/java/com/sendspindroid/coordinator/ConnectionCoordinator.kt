package com.sendspindroid.coordinator

import com.sendspindroid.model.UnifiedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Single authority for "what server is active, and which of its transports are up."
 *
 * Phase 1: Thin adapter that combines today's per-component state into one
 * SessionState flow and forwards a single disconnect entry point. Internals
 * (retry, fallback, network observation) still live in SendSpinClient,
 * MusicAssistantManager, AutoReconnectManager, and the per-component network
 * callbacks. Subsequent phases migrate those responsibilities here.
 *
 * See docs/superpowers/specs/2026-05-05-connection-coordinator-design.md
 */
class ConnectionCoordinator(
    currentServerFlow: Flow<UnifiedServer?>,
    sendSpinStateFlow: Flow<TransportState>,
    musicAssistantStateFlow: Flow<TransportState>,
    scope: CoroutineScope,
    private val onDisconnectRequested: () -> Unit,
) {
    val sessionState: StateFlow<SessionState> = combine(
        currentServerFlow,
        sendSpinStateFlow,
        musicAssistantStateFlow,
    ) { server, sendSpin, ma ->
        SessionState(server = server, sendSpin = sendSpin, musicAssistant = ma)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SessionState(),
    )

    /**
     * User-initiated disconnect. Forwards to PlaybackService.disconnectFromServer().
     */
    fun disconnect() {
        onDisconnectRequested()
    }
}
