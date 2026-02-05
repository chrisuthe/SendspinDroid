package com.sendspindroid.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.MaSettings
import com.sendspindroid.sendspin.MusicAssistantAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * ViewModel for the Add Server Wizard Activity.
 *
 * Implements a state machine for the progressive wizard flow:
 *
 * ```
 * WELCOME -> FIND_SERVER -> TEST_LOCAL -> [MA_LOGIN] -> REMOTE_CHOICE ->
 *            [REMOTE_ID | PROXY] -> SAVE
 * ```
 *
 * Key concepts:
 * - WizardStep: Which screen/step is currently showing
 * - ConnectionTestState: Inline connection test results at each step
 * - RemoteAccessMethod: User's choice for remote connectivity
 */
class AddServerWizardViewModel : ViewModel() {

    companion object {
        // Proxy auth modes
        const val AUTH_LOGIN = 0
        const val AUTH_TOKEN = 1
    }

    // ========================================================================
    // Wizard Step State Machine
    // ========================================================================

    /**
     * Represents each step in the wizard flow.
     * Steps are not necessarily sequential - the flow branches based on user choices.
     */
    sealed class WizardStep {
        /** Welcome screen with two paths: "Set up my server" or "Find other servers" */
        data object Welcome : WizardStep()

        /** Find server via mDNS discovery or manual entry */
        data object FindServer : WizardStep()

        /** Testing local connection inline */
        data object TestingLocal : WizardStep()

        /** Music Assistant login - shown after successful local/proxy connection */
        data object MaLogin : WizardStep()

        /** Choose remote access method: Remote ID, Proxy, or None */
        data object RemoteChoice : WizardStep()

        /** Enter Remote ID + QR scan option */
        data object RemoteId : WizardStep()

        /** Configure proxy URL + auth */
        data object Proxy : WizardStep()

        /** Testing remote/proxy connection */
        data object TestingRemote : WizardStep()

        /** Warning when configuring remote-only (no local connection) */
        data object RemoteOnlyWarning : WizardStep()

        /** Final summary screen with name + save */
        data object Save : WizardStep()
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

    // Current wizard step
    private val _currentStep = MutableStateFlow<WizardStep>(WizardStep.Welcome)
    val currentStep: StateFlow<WizardStep> = _currentStep.asStateFlow()

    // Connection test state (for inline testing at Find Server and Remote steps)
    private val _localTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val localTestState: StateFlow<ConnectionTestState> = _localTestState.asStateFlow()

    private val _remoteTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val remoteTestState: StateFlow<ConnectionTestState> = _remoteTestState.asStateFlow()

    // MA login test state
    private val _maTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val maTestState: StateFlow<ConnectionTestState> = _maTestState.asStateFlow()

    // User's selected remote access method
    private val _remoteAccessMethod = MutableStateFlow(RemoteAccessMethod.NONE)
    val remoteAccessMethod: StateFlow<RemoteAccessMethod> = _remoteAccessMethod.asStateFlow()

    // Whether the user explicitly chose to skip local connection
    var localConnectionSkipped: Boolean = false
        private set

    // ========================================================================
    // Server Data Fields
    // ========================================================================

    // Server identification
    var serverName: String = ""
    var setAsDefault: Boolean = false
    var isMusicAssistant: Boolean = false

    // Local connection
    var localAddress: String = ""

    // Remote connection
    var remoteId: String = ""

    // Proxy connection
    var proxyUrl: String = ""
    var proxyAuthMode: Int = AUTH_LOGIN
    var proxyUsername: String = ""
    var proxyPassword: String = ""
    var proxyToken: String = ""

    // Music Assistant login state (for eager auth)
    var maUsername: String = ""
    var maPassword: String = ""
    var maToken: String? = null  // Token obtained from successful MA login
    var maPort: Int = MaSettings.getDefaultPort()

    // Discovered server info (pre-filled from mDNS)
    var discoveredServerName: String? = null
    var discoveredServerAddress: String? = null

    // Editing mode - non-null when editing an existing server
    var editingServer: UnifiedServer? = null
        private set

    // Loading state
    var isLoading: Boolean = false

    // ========================================================================
    // Navigation Methods
    // ========================================================================

    /**
     * Navigate to a specific step. Used for programmatic navigation.
     */
    fun navigateTo(step: WizardStep) {
        _currentStep.value = step
    }

    /**
     * Handle "Next" action based on current step.
     * Returns true if navigation succeeded, false if validation failed.
     */
    fun onNext(): Boolean {
        return when (_currentStep.value) {
            WizardStep.Welcome -> {
                _currentStep.value = WizardStep.FindServer
                true
            }
            WizardStep.FindServer -> {
                // Validate address is entered
                if (localAddress.isBlank()) {
                    return false
                }
                // Start connection test
                _currentStep.value = WizardStep.TestingLocal
                true
            }
            WizardStep.TestingLocal -> {
                // Should not be called directly - test completion handles navigation
                true
            }
            WizardStep.MaLogin -> {
                // Validate MA login was successful
                if (maToken == null) {
                    return false
                }
                _currentStep.value = WizardStep.RemoteChoice
                true
            }
            WizardStep.RemoteChoice -> {
                when (_remoteAccessMethod.value) {
                    RemoteAccessMethod.NONE -> _currentStep.value = WizardStep.Save
                    RemoteAccessMethod.REMOTE_ID -> _currentStep.value = WizardStep.RemoteId
                    RemoteAccessMethod.PROXY -> _currentStep.value = WizardStep.Proxy
                }
                true
            }
            WizardStep.RemoteId -> {
                // Validate remote ID if entered
                if (remoteId.isNotBlank()) {
                    _currentStep.value = WizardStep.TestingRemote
                } else {
                    _currentStep.value = WizardStep.Save
                }
                true
            }
            WizardStep.Proxy -> {
                // Validate proxy if entered
                if (proxyUrl.isNotBlank()) {
                    _currentStep.value = WizardStep.TestingRemote
                } else {
                    _currentStep.value = WizardStep.Save
                }
                true
            }
            WizardStep.TestingRemote -> {
                // Should not be called directly - test completion handles navigation
                true
            }
            WizardStep.RemoteOnlyWarning -> {
                // User acknowledged warning, proceed to remote choice
                _currentStep.value = WizardStep.RemoteChoice
                true
            }
            WizardStep.Save -> {
                // Final step - handled by Activity
                true
            }
        }
    }

    /**
     * Handle "Back" action based on current step.
     * Returns the previous step, or null if at the beginning.
     */
    fun onBack(): WizardStep? {
        val previous = when (_currentStep.value) {
            WizardStep.Welcome -> null
            WizardStep.FindServer -> WizardStep.Welcome
            WizardStep.TestingLocal -> WizardStep.FindServer
            WizardStep.MaLogin -> WizardStep.FindServer
            WizardStep.RemoteChoice -> {
                // Go back to MA Login if we showed it, otherwise Find Server
                if (isMusicAssistant && localAddress.isNotBlank()) {
                    WizardStep.MaLogin
                } else {
                    WizardStep.FindServer
                }
            }
            WizardStep.RemoteId -> WizardStep.RemoteChoice
            WizardStep.Proxy -> WizardStep.RemoteChoice
            WizardStep.TestingRemote -> {
                when (_remoteAccessMethod.value) {
                    RemoteAccessMethod.REMOTE_ID -> WizardStep.RemoteId
                    RemoteAccessMethod.PROXY -> WizardStep.Proxy
                    RemoteAccessMethod.NONE -> WizardStep.RemoteChoice
                }
            }
            WizardStep.RemoteOnlyWarning -> WizardStep.FindServer
            WizardStep.Save -> {
                when (_remoteAccessMethod.value) {
                    RemoteAccessMethod.REMOTE_ID -> WizardStep.RemoteId
                    RemoteAccessMethod.PROXY -> WizardStep.Proxy
                    RemoteAccessMethod.NONE -> WizardStep.RemoteChoice
                }
            }
        }
        previous?.let { _currentStep.value = it }
        return previous
    }

    /**
     * Handle "Skip" action for optional steps.
     */
    fun onSkipLocal() {
        localConnectionSkipped = true
        localAddress = ""
        _localTestState.value = ConnectionTestState.Idle

        // Show warning that remote-only has limitations
        _currentStep.value = WizardStep.RemoteOnlyWarning
    }

    /**
     * Set the remote access method choice.
     */
    fun setRemoteMethod(method: RemoteAccessMethod) {
        _remoteAccessMethod.value = method
    }

    // ========================================================================
    // Connection Testing
    // ========================================================================

    /**
     * Called when local connection test completes successfully.
     */
    fun onLocalTestSuccess(message: String = "Connection successful") {
        _localTestState.value = ConnectionTestState.Success(message)

        // If this is a Music Assistant server, show MA login next
        if (isMusicAssistant) {
            _currentStep.value = WizardStep.MaLogin
        } else {
            // Otherwise go to remote choice
            _currentStep.value = WizardStep.RemoteChoice
        }
    }

    /**
     * Called when local connection test fails.
     */
    fun onLocalTestFailed(error: String) {
        _localTestState.value = ConnectionTestState.Failed(error)
        // Stay on FindServer step so user can try again or skip
        _currentStep.value = WizardStep.FindServer
    }

    /**
     * Reset local test state for retry.
     */
    fun resetLocalTest() {
        _localTestState.value = ConnectionTestState.Idle
    }

    /**
     * Called when remote/proxy connection test completes successfully.
     */
    fun onRemoteTestSuccess(message: String = "Connection successful") {
        _remoteTestState.value = ConnectionTestState.Success(message)
        _currentStep.value = WizardStep.Save
    }

    /**
     * Called when remote/proxy connection test fails.
     */
    fun onRemoteTestFailed(error: String) {
        _remoteTestState.value = ConnectionTestState.Failed(error)
        // Go back to the appropriate configuration step
        when (_remoteAccessMethod.value) {
            RemoteAccessMethod.REMOTE_ID -> _currentStep.value = WizardStep.RemoteId
            RemoteAccessMethod.PROXY -> _currentStep.value = WizardStep.Proxy
            RemoteAccessMethod.NONE -> _currentStep.value = WizardStep.RemoteChoice
        }
    }

    /**
     * Reset remote test state for retry.
     */
    fun resetRemoteTest() {
        _remoteTestState.value = ConnectionTestState.Idle
    }

    // ========================================================================
    // MA Login Testing
    // ========================================================================

    /**
     * Test Music Assistant connection with provided credentials.
     */
    fun testMaConnection(onComplete: (Boolean) -> Unit) {
        val apiUrl = deriveMaApiUrl()
        if (apiUrl == null) {
            _maTestState.value = ConnectionTestState.Failed("No MA endpoint available")
            onComplete(false)
            return
        }

        if (maUsername.isBlank() || maPassword.isBlank()) {
            _maTestState.value = ConnectionTestState.Failed("Username and password required")
            onComplete(false)
            return
        }

        _maTestState.value = ConnectionTestState.Testing

        viewModelScope.launch {
            try {
                val result = MusicAssistantAuth.login(apiUrl, maUsername, maPassword)
                maToken = result.accessToken
                _maTestState.value = ConnectionTestState.Success("Connected to Music Assistant")

                // Save port if different from default
                if (maPort != MaSettings.getDefaultPort()) {
                    MaSettings.setDefaultPort(maPort)
                }

                onComplete(true)
            } catch (e: MusicAssistantAuth.AuthenticationException) {
                maToken = null
                _maTestState.value = ConnectionTestState.Failed("Invalid credentials")
                onComplete(false)
            } catch (e: IOException) {
                maToken = null
                _maTestState.value = ConnectionTestState.Failed("Network error")
                onComplete(false)
            } catch (e: Exception) {
                maToken = null
                _maTestState.value = ConnectionTestState.Failed(e.message ?: "Unknown error")
                onComplete(false)
            }
        }
    }

    /**
     * Derive the Music Assistant API URL from configured endpoints.
     */
    private fun deriveMaApiUrl(): String? {
        // Prefer local connection for MA API
        if (localAddress.isNotBlank()) {
            val host = localAddress.substringBefore(":")
            return "ws://$host:$maPort/ws"
        }

        // Fall back to proxy if configured
        if (proxyUrl.isNotBlank()) {
            val baseUrl = normalizeProxyUrl(proxyUrl)
                .removeSuffix("/sendspin")
                .trimEnd('/')

            val wsUrl = when {
                baseUrl.startsWith("https://") -> baseUrl.replaceFirst("https://", "wss://")
                baseUrl.startsWith("http://") -> baseUrl.replaceFirst("http://", "ws://")
                else -> "wss://$baseUrl"
            }

            return "$wsUrl/ws"
        }

        return null
    }

    /**
     * Reset MA test state.
     */
    fun resetMaTest() {
        _maTestState.value = ConnectionTestState.Idle
    }

    /**
     * Skip MA login and proceed without authentication.
     */
    fun skipMaLogin() {
        maToken = null
        maUsername = ""
        maPassword = ""
        _currentStep.value = WizardStep.RemoteChoice
    }

    // ========================================================================
    // Edit Mode
    // ========================================================================

    /**
     * Initialize the ViewModel for editing an existing server.
     * Populates all fields with the server's current configuration.
     */
    fun initForEdit(server: UnifiedServer, existingMaToken: String?) {
        editingServer = server
        serverName = server.name
        setAsDefault = server.isDefaultServer
        isMusicAssistant = server.isMusicAssistant

        server.local?.let {
            localAddress = it.address
        }

        server.remote?.let {
            remoteId = it.remoteId
            _remoteAccessMethod.value = RemoteAccessMethod.REMOTE_ID
        }

        server.proxy?.let {
            proxyUrl = it.url
            proxyToken = it.authToken
            proxyUsername = it.username ?: ""
            proxyAuthMode = AUTH_TOKEN  // Default to token mode if we have saved proxy
            _remoteAccessMethod.value = RemoteAccessMethod.PROXY
        }

        // Load existing MA token if available
        if (isMusicAssistant) {
            maToken = existingMaToken
        }

        // For edit mode, skip welcome and go directly to save/summary
        _currentStep.value = WizardStep.Save
    }

    /**
     * Check if we're in edit mode (modifying an existing server).
     */
    val isEditMode: Boolean
        get() = editingServer != null

    /**
     * Get the server ID - either from the server being edited, or generate a new one.
     * Note: This should only be called when actually saving, not for display purposes.
     */
    fun getServerId(): String {
        return editingServer?.id ?: com.sendspindroid.UnifiedServerRepository.generateId()
    }

    // ========================================================================
    // Validation Helpers
    // ========================================================================

    /**
     * Check if MA Login step should be shown based on current state.
     * MA Login is shown when:
     * - isMusicAssistant checkbox is checked
     * - Local or Proxy connection is configured (not Remote-only)
     */
    fun shouldShowMaLoginStep(): Boolean {
        val hasLocalOrProxy = localAddress.isNotBlank() || proxyUrl.isNotBlank()
        return isMusicAssistant && hasLocalOrProxy
    }

    /**
     * Check if the wizard has at least one valid connection method configured.
     */
    fun hasValidConnectionMethod(): Boolean {
        return localAddress.isNotBlank() ||
               (remoteId.isNotBlank() && com.sendspindroid.remote.RemoteConnection.parseRemoteId(remoteId) != null) ||
               proxyUrl.isNotBlank()
    }

    /**
     * Get a summary of configured connection methods for the Save step.
     */
    fun getConnectionMethodSummary(): List<String> {
        val methods = mutableListOf<String>()

        if (localAddress.isNotBlank()) {
            methods.add("Local: $localAddress")
        }

        if (remoteId.isNotBlank()) {
            val formatted = com.sendspindroid.remote.RemoteConnection.formatRemoteId(remoteId)
            methods.add("Remote ID: $formatted")
        }

        if (proxyUrl.isNotBlank()) {
            methods.add("Proxy: $proxyUrl")
        }

        return methods
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Normalize proxy URL to ensure proper format.
     */
    fun normalizeProxyUrl(url: String): String {
        var normalized = when {
            url.startsWith("https://") || url.startsWith("http://") -> url
            url.startsWith("wss://") || url.startsWith("ws://") -> url
            else -> "https://$url"
        }

        if (!normalized.contains("/sendspin")) {
            normalized = normalized.trimEnd('/') + "/sendspin"
        }

        return normalized
    }

    /**
     * Apply discovered server data (from mDNS) to pre-fill fields.
     */
    fun applyDiscoveredServer(name: String, address: String) {
        discoveredServerName = name
        discoveredServerAddress = address
        serverName = name
        localAddress = address
    }

    /**
     * Clear all state (useful if the ViewModel is reused).
     */
    fun clear() {
        serverName = ""
        setAsDefault = false
        isMusicAssistant = false
        localAddress = ""
        localConnectionSkipped = false
        remoteId = ""
        proxyUrl = ""
        proxyAuthMode = AUTH_LOGIN
        proxyUsername = ""
        proxyPassword = ""
        proxyToken = ""
        maUsername = ""
        maPassword = ""
        maToken = null
        maPort = MaSettings.getDefaultPort()
        discoveredServerName = null
        discoveredServerAddress = null
        editingServer = null
        isLoading = false
        _currentStep.value = WizardStep.Welcome
        _localTestState.value = ConnectionTestState.Idle
        _remoteTestState.value = ConnectionTestState.Idle
        _maTestState.value = ConnectionTestState.Idle
        _remoteAccessMethod.value = RemoteAccessMethod.NONE
    }
}
