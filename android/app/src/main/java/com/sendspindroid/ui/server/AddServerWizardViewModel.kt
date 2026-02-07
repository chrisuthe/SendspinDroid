package com.sendspindroid.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.MaSettings
import com.sendspindroid.sendspin.MusicAssistantAuth
import com.sendspindroid.ui.wizard.ConnectionTestState
import com.sendspindroid.ui.wizard.DiscoveredServerUi
import com.sendspindroid.ui.wizard.ProxyAuthMode
import com.sendspindroid.ui.wizard.RemoteAccessMethod
import com.sendspindroid.ui.wizard.WizardState
import com.sendspindroid.ui.wizard.WizardStep
import com.sendspindroid.ui.wizard.WizardStepAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
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
 *
 * Uses types from com.sendspindroid.ui.wizard package for Compose integration.
 */
class AddServerWizardViewModel : ViewModel() {

    companion object {
        // Proxy auth modes (for compatibility)
        const val AUTH_LOGIN = 0
        const val AUTH_TOKEN = 1
    }

    // Current wizard step
    private val _currentStep = MutableStateFlow(WizardStep.Welcome)
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

    // Discovered servers from mDNS
    private val _discoveredServers = MutableStateFlow<List<DiscoveredServerUi>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServerUi>> = _discoveredServers.asStateFlow()

    // Whether mDNS discovery is in progress
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Whether the user explicitly chose to skip local connection
    var localConnectionSkipped: Boolean = false
        private set

    // ========================================================================
    // Server Data Fields (reactive for Compose)
    // ========================================================================

    // Server identification
    private val _serverName = MutableStateFlow("")
    var serverName: String
        get() = _serverName.value
        set(value) { _serverName.value = value }

    private val _setAsDefault = MutableStateFlow(false)
    var setAsDefault: Boolean
        get() = _setAsDefault.value
        set(value) { _setAsDefault.value = value }

    private val _isMusicAssistant = MutableStateFlow(false)
    var isMusicAssistant: Boolean
        get() = _isMusicAssistant.value
        set(value) { _isMusicAssistant.value = value }

    // Local connection
    private val _localAddress = MutableStateFlow("")
    var localAddress: String
        get() = _localAddress.value
        set(value) { _localAddress.value = value }

    // Remote connection
    private val _remoteId = MutableStateFlow("")
    var remoteId: String
        get() = _remoteId.value
        set(value) { _remoteId.value = value }

    // Proxy connection
    private val _proxyUrl = MutableStateFlow("")
    var proxyUrl: String
        get() = _proxyUrl.value
        set(value) { _proxyUrl.value = value }

    private val _proxyAuthMode = MutableStateFlow(ProxyAuthMode.LOGIN)
    var proxyAuthMode: Int
        get() = if (_proxyAuthMode.value == ProxyAuthMode.LOGIN) AUTH_LOGIN else AUTH_TOKEN
        set(value) { _proxyAuthMode.value = if (value == AUTH_LOGIN) ProxyAuthMode.LOGIN else ProxyAuthMode.TOKEN }

    private val _proxyUsername = MutableStateFlow("")
    var proxyUsername: String
        get() = _proxyUsername.value
        set(value) { _proxyUsername.value = value }

    private val _proxyPassword = MutableStateFlow("")
    var proxyPassword: String
        get() = _proxyPassword.value
        set(value) { _proxyPassword.value = value }

    private val _proxyToken = MutableStateFlow("")
    var proxyToken: String
        get() = _proxyToken.value
        set(value) { _proxyToken.value = value }

    // Music Assistant login state (for eager auth)
    private val _maUsername = MutableStateFlow("")
    var maUsername: String
        get() = _maUsername.value
        set(value) { _maUsername.value = value }

    private val _maPassword = MutableStateFlow("")
    var maPassword: String
        get() = _maPassword.value
        set(value) { _maPassword.value = value }

    private val _maToken = MutableStateFlow<String?>(null)
    var maToken: String?
        get() = _maToken.value
        set(value) { _maToken.value = value }

    private val _maPort = MutableStateFlow(MaSettings.getDefaultPort())
    var maPort: Int
        get() = _maPort.value
        set(value) { _maPort.value = value }

    // Discovered server info (pre-filled from mDNS)
    var discoveredServerName: String? = null
    var discoveredServerAddress: String? = null

    // Editing mode - non-null when editing an existing server
    private val _editingServer = MutableStateFlow<UnifiedServer?>(null)
    var editingServer: UnifiedServer?
        get() = _editingServer.value
        private set(value) { _editingServer.value = value }

    // Loading state
    var isLoading: Boolean = false

    // ========================================================================
    // Combined Wizard State (for Compose)
    // ========================================================================

    /**
     * Combined wizard state for Compose UI consumption.
     * Merges all individual state flows into a single WizardState.
     * Note: We combine all user-editable fields to ensure UI updates when any field changes.
     */
    val wizardState: StateFlow<WizardState> = combine(
        _currentStep,
        _localTestState,
        _remoteTestState,
        _maTestState,
        _remoteAccessMethod,
        _localAddress,
        _serverName,
        _remoteId,
        _proxyUrl,
        _proxyAuthMode,
        _proxyUsername,
        _proxyPassword,
        _proxyToken,
        _discoveredServers,
        _isSearching,
        _maUsername,
        _maPassword,
        _maToken,
        _isMusicAssistant
    ) { values ->
        // Destructure the array of values
        val step = values[0] as WizardStep
        val localTest = values[1] as ConnectionTestState
        val remoteTest = values[2] as ConnectionTestState
        val maTest = values[3] as ConnectionTestState
        val remoteMethod = values[4] as RemoteAccessMethod
        val localAddr = values[5] as String
        val name = values[6] as String
        val remote = values[7] as String
        val proxyUrlVal = values[8] as String
        val proxyAuthModeVal = values[9] as ProxyAuthMode
        val proxyUsernameVal = values[10] as String
        val proxyPasswordVal = values[11] as String
        val proxyTokenVal = values[12] as String
        @Suppress("UNCHECKED_CAST")
        val discovered = values[13] as List<DiscoveredServerUi>
        val searching = values[14] as Boolean
        val maUser = values[15] as String
        val maPass = values[16] as String
        val maTokenVal = values[17] as String?
        val isMusicAssistantVal = values[18] as Boolean

        WizardState(
            currentStep = step,
            isEditMode = _editingServer.value != null,
            isNextEnabled = computeNextEnabled(step),
            serverName = name,
            setAsDefault = _setAsDefault.value,
            isMusicAssistant = isMusicAssistantVal,
            localAddress = localAddr,
            discoveredServers = discovered,
            isSearching = searching,
            localTestState = localTest,
            remoteAccessMethod = remoteMethod,
            remoteId = remote,
            remoteTestState = remoteTest,
            proxyUrl = proxyUrlVal,
            proxyAuthMode = proxyAuthModeVal,
            proxyUsername = proxyUsernameVal,
            proxyPassword = proxyPasswordVal,
            proxyToken = proxyTokenVal,
            maUsername = maUser,
            maPassword = maPass,
            maPort = _maPort.value,
            maToken = maTokenVal,
            maTestState = maTest
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WizardState()
    )

    /**
     * Compute whether the "Next" button should be enabled based on current step.
     */
    private fun computeNextEnabled(step: WizardStep): Boolean {
        return when (step) {
            WizardStep.Welcome -> true
            WizardStep.FindServer -> _localAddress.value.isNotBlank()
            WizardStep.TestingLocal, WizardStep.TestingRemote -> false
            WizardStep.MaLogin -> _maToken.value != null
            WizardStep.RemoteChoice -> true
            WizardStep.RemoteId -> true  // Can proceed even without remote ID
            WizardStep.Proxy -> true  // Can proceed even without proxy
            WizardStep.RemoteOnlyWarning -> true
            WizardStep.Save -> _serverName.value.isNotBlank() && hasValidConnectionMethod()
        }
    }

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
     *
     * In edit mode, we don't allow going back past the Save step since
     * the user started there and shouldn't see the initial setup wizard.
     */
    fun onBack(): WizardStep? {
        val previous = when (_currentStep.value) {
            WizardStep.Welcome -> null
            WizardStep.FindServer -> {
                // In edit mode, don't go back to Welcome
                if (isEditMode) null else WizardStep.Welcome
            }
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
                // In edit mode, don't allow going back from Save - close the wizard instead
                if (isEditMode) {
                    null
                } else {
                    when (_remoteAccessMethod.value) {
                        RemoteAccessMethod.REMOTE_ID -> WizardStep.RemoteId
                        RemoteAccessMethod.PROXY -> WizardStep.Proxy
                        RemoteAccessMethod.NONE -> WizardStep.RemoteChoice
                    }
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
            // If we have a username, prefer login mode (token will be re-obtained);
            // if we have a token but no username, use token mode
            proxyAuthMode = if (it.username != null) AUTH_LOGIN else AUTH_TOKEN
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
    // Step Action Handling (for Compose)
    // ========================================================================

    /**
     * Handle step-specific actions from the Compose UI.
     * Returns true if the action requires additional handling by the Activity
     * (e.g., starting mDNS discovery, launching QR scanner).
     */
    fun handleStepAction(action: WizardStepAction): Boolean {
        return when (action) {
            // Welcome step
            WizardStepAction.SetupMyServer -> {
                _currentStep.value = WizardStep.FindServer
                false
            }
            WizardStepAction.FindOtherServers -> {
                _currentStep.value = WizardStep.FindServer
                true // Activity should start mDNS discovery
            }

            // Find server step
            is WizardStepAction.UpdateLocalAddress -> {
                localAddress = action.address
                false
            }
            is WizardStepAction.SelectDiscoveredServer -> {
                applyDiscoveredServer(action.server.name, action.server.address)
                false
            }
            is WizardStepAction.UpdateIsMusicAssistant -> {
                isMusicAssistant = action.isMusicAssistant
                false
            }
            WizardStepAction.StartDiscovery -> {
                true // Activity should start mDNS discovery
            }
            WizardStepAction.RetryLocalTest -> {
                resetLocalTest()
                _currentStep.value = WizardStep.FindServer
                false
            }

            // MA Login step
            is WizardStepAction.UpdateMaUsername -> {
                maUsername = action.username
                false
            }
            is WizardStepAction.UpdateMaPassword -> {
                maPassword = action.password
                false
            }
            is WizardStepAction.UpdateMaPort -> {
                maPort = action.port
                false
            }
            WizardStepAction.TestMaConnection -> {
                true // Activity should trigger MA connection test
            }

            // Remote choice step
            is WizardStepAction.SelectRemoteMethod -> {
                setRemoteMethod(action.method)
                false
            }

            // Remote ID step
            is WizardStepAction.UpdateRemoteId -> {
                remoteId = action.id.uppercase().take(26)
                false
            }
            WizardStepAction.ScanQrCode -> {
                true // Activity should launch QR scanner
            }
            WizardStepAction.RetryRemoteTest -> {
                resetRemoteTest()
                when (_remoteAccessMethod.value) {
                    RemoteAccessMethod.REMOTE_ID -> _currentStep.value = WizardStep.RemoteId
                    RemoteAccessMethod.PROXY -> _currentStep.value = WizardStep.Proxy
                    RemoteAccessMethod.NONE -> _currentStep.value = WizardStep.RemoteChoice
                }
                false
            }

            // Proxy step
            is WizardStepAction.UpdateProxyUrl -> {
                proxyUrl = action.url
                false
            }
            is WizardStepAction.UpdateProxyAuthMode -> {
                _proxyAuthMode.value = action.mode
                false
            }
            is WizardStepAction.UpdateProxyUsername -> {
                proxyUsername = action.username
                false
            }
            is WizardStepAction.UpdateProxyPassword -> {
                proxyPassword = action.password
                false
            }
            is WizardStepAction.UpdateProxyToken -> {
                proxyToken = action.token
                false
            }

            // Remote only warning
            WizardStepAction.AcknowledgeRemoteOnlyWarning -> {
                _currentStep.value = WizardStep.RemoteChoice
                false
            }

            // Save step
            is WizardStepAction.UpdateServerName -> {
                serverName = action.name
                false
            }
            is WizardStepAction.UpdateSetAsDefault -> {
                setAsDefault = action.isDefault
                false
            }
        }
    }

    /**
     * Update the list of discovered servers (called from Activity during mDNS discovery).
     */
    fun updateDiscoveredServers(servers: List<DiscoveredServerUi>) {
        _discoveredServers.value = servers
    }

    /**
     * Update the searching state (called from Activity during mDNS discovery).
     */
    fun setSearching(searching: Boolean) {
        _isSearching.value = searching
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
        // Clear reactive state flows
        _serverName.value = ""
        _setAsDefault.value = false
        _isMusicAssistant.value = false
        _localAddress.value = ""
        _remoteId.value = ""
        _proxyUrl.value = ""
        _proxyAuthMode.value = ProxyAuthMode.LOGIN
        _proxyUsername.value = ""
        _proxyPassword.value = ""
        _proxyToken.value = ""
        _maUsername.value = ""
        _maPassword.value = ""
        _maToken.value = null
        _maPort.value = MaSettings.getDefaultPort()
        _editingServer.value = null
        _discoveredServers.value = emptyList()
        _isSearching.value = false

        // Clear non-reactive fields
        localConnectionSkipped = false
        discoveredServerName = null
        discoveredServerAddress = null
        isLoading = false

        // Clear step and test state flows
        _currentStep.value = WizardStep.Welcome
        _localTestState.value = ConnectionTestState.Idle
        _remoteTestState.value = ConnectionTestState.Idle
        _maTestState.value = ConnectionTestState.Idle
        _remoteAccessMethod.value = RemoteAccessMethod.NONE
    }
}
