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
 * Phase 1: combined sessionState flow + disconnect() forward.
 * Phase 2A: adds reconnectStatus flow + connect / cancelReconnect / onNetworkAvailable
 *           forwards. Underlying retry logic still lives in AutoReconnectManager
 *           and SendSpinClient -- Phase 2B kills the dueling-timer problem.
 *
 * See docs/superpowers/specs/2026-05-05-connection-coordinator-design.md
 */
class ConnectionCoordinator(
    currentServerFlow: Flow<UnifiedServer?>,
    sendSpinStateFlow: Flow<TransportState>,
    musicAssistantStateFlow: Flow<TransportState>,
    reconnectStatusFlow: Flow<ReconnectStatus>,
    scope: CoroutineScope,
    private val onDisconnectRequested: () -> Unit,
    private val onConnectRequested: (UnifiedServer) -> Unit,
    private val onCancelReconnectRequested: () -> Unit,
    private val onNetworkAvailableSignaled: () -> Unit,
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

    val reconnectStatus: StateFlow<ReconnectStatus> = reconnectStatusFlow.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ReconnectStatus.Idle,
    )

    /** User-initiated disconnect. Forwards to PlaybackService.disconnectFromServer(). */
    fun disconnect() {
        onDisconnectRequested()
    }

    /** Request auto-reconnection to the given server. */
    fun connect(server: UnifiedServer) {
        onConnectRequested(server)
    }

    /** Cancel any in-progress auto-reconnection. */
    fun cancelReconnect() {
        onCancelReconnectRequested()
    }

    /** Network became available -- wake any backoff-delayed reconnect. */
    fun onNetworkAvailable() {
        onNetworkAvailableSignaled()
    }
}
