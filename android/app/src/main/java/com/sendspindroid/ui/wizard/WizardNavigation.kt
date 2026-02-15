package com.sendspindroid.ui.wizard

/**
 * Client mode — determines whether the app shows full Music Assistant features
 * or basic SendSpin playback controls.
 */
enum class ClientMode {
    SENDSPIN,           // Basic playback: album art, play/pause/next/back
    MUSIC_ASSISTANT     // Full MA: library browsing, search, playlists, queue management
}

/**
 * Wizard step enum for the branching Add Server flow.
 *
 * The wizard branches at ClientType based on user intent,
 * then further at MA_NetworkQuestion based on network situation.
 *
 * SendSpin path:
 *   ClientType → SS_FindServer → SS_TestLocal → SS_Finish
 *
 * MA local path:
 *   ClientType → MA_NetworkQuestion → MA_FindServer → MA_TestLocal →
 *   MA_Login → MA_RemoteQuestion → [MA_RemoteSetup → MA_TestRemote →] MA_Finish
 *
 * MA remote-only path:
 *   ClientType → MA_NetworkQuestion → MA_RemoteOnlySetup →
 *   MA_TestRemoteOnly → MA_LoginRemote → MA_FinishRemoteOnly
 */
enum class WizardStep {
    // Entry point (all paths)
    ClientType,

    // SendSpin path
    SS_FindServer,
    SS_TestLocal,
    SS_Finish,

    // Music Assistant local path
    MA_NetworkQuestion,
    MA_FindServer,
    MA_TestLocal,
    MA_Login,
    MA_RemoteQuestion,
    MA_RemoteSetup,
    MA_TestRemote,
    MA_Finish,

    // Music Assistant remote-only path
    MA_RemoteOnlySetup,
    MA_TestRemoteOnly,
    MA_LoginRemote,
    MA_FinishRemoteOnly
}

/**
 * User's choice for how to access the server remotely.
 */
enum class RemoteAccessMethod {
    NONE,       // Local only, no remote access
    REMOTE_ID,  // Via Music Assistant Remote Access ID
    PROXY       // Via authenticated reverse proxy
}

/**
 * State of inline connection testing.
 */
sealed class ConnectionTestState {
    data object Idle : ConnectionTestState()
    data object Testing : ConnectionTestState()
    data class Success(val message: String) : ConnectionTestState()
    data class Failed(val error: String) : ConnectionTestState()
}
