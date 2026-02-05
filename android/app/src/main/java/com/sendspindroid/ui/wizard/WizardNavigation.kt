package com.sendspindroid.ui.wizard

/**
 * Navigation routes for the Add Server Wizard.
 * Each step in the wizard has a corresponding route.
 */
object WizardRoutes {
    const val WELCOME = "welcome"
    const val FIND_SERVER = "find_server"
    const val TESTING_LOCAL = "testing_local"
    const val MA_LOGIN = "ma_login"
    const val REMOTE_CHOICE = "remote_choice"
    const val REMOTE_ID = "remote_id"
    const val PROXY = "proxy"
    const val TESTING_REMOTE = "testing_remote"
    const val REMOTE_ONLY_WARNING = "remote_only_warning"
    const val SAVE = "save"
}

/**
 * Wizard step enum for internal state management.
 * Mirrors AddServerWizardViewModel.WizardStep for Compose integration.
 */
enum class WizardStep {
    Welcome,
    FindServer,
    TestingLocal,
    MaLogin,
    RemoteChoice,
    RemoteId,
    Proxy,
    TestingRemote,
    RemoteOnlyWarning,
    Save
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
