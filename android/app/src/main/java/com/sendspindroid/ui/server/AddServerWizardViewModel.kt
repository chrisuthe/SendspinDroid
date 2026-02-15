package com.sendspindroid.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.MaSettings
import com.sendspindroid.sendspin.MusicAssistantAuth
import com.sendspindroid.ui.wizard.ClientMode
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
 * Implements a branching state machine for the wizard flow:
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
class AddServerWizardViewModel : ViewModel() {

    companion object {
        // Proxy auth modes (for compatibility)
        const val AUTH_LOGIN = 0
        const val AUTH_TOKEN = 1
    }

    // Current wizard step
    private val _currentStep = MutableStateFlow(WizardStep.ClientType)
    val currentStep: StateFlow<WizardStep> = _currentStep.asStateFlow()

    // Client mode (SendSpin vs Music Assistant)
    private val _clientMode = MutableStateFlow(ClientMode.SENDSPIN)
    var clientMode: ClientMode
        get() = _clientMode.value
        set(value) { _clientMode.value = value }

    // Transient routing state (not persisted in WizardState)
    var isOnLocalNetwork: Boolean = true
        private set
    var wantsRemoteAccess: Boolean = false
        private set

    // Connection test state
    private val _localTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val localTestState: StateFlow<ConnectionTestState> = _localTestState.asStateFlow()

    private val _remoteTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val remoteTestState: StateFlow<ConnectionTestState> = _remoteTestState.asStateFlow()

    private val _maTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val maTestState: StateFlow<ConnectionTestState> = _maTestState.asStateFlow()

    // Remote access method (within RemoteSetup tabs)
    private val _remoteAccessMethod = MutableStateFlow(RemoteAccessMethod.REMOTE_ID)
    val remoteAccessMethod: StateFlow<RemoteAccessMethod> = _remoteAccessMethod.asStateFlow()

    // Discovered servers from mDNS
    private val _discoveredServers = MutableStateFlow<List<DiscoveredServerUi>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServerUi>> = _discoveredServers.asStateFlow()

    // Whether mDNS discovery is in progress
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Network hint (auto-detected, set by Activity)
    private val _networkHint = MutableStateFlow("")

    // ========================================================================
    // Server Data Fields (reactive for Compose)
    // ========================================================================

    private val _serverName = MutableStateFlow("")
    var serverName: String
        get() = _serverName.value
        set(value) { _serverName.value = value }

    private val _setAsDefault = MutableStateFlow(false)
    var setAsDefault: Boolean
        get() = _setAsDefault.value
        set(value) { _setAsDefault.value = value }

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

    // Music Assistant login
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

    // Editing mode
    private val _editingServer = MutableStateFlow<UnifiedServer?>(null)
    var editingServer: UnifiedServer?
        get() = _editingServer.value
        private set(value) { _editingServer.value = value }

    var isLoading: Boolean = false

    // Derived: isMusicAssistant for backward compatibility with save logic
    val isMusicAssistant: Boolean
        get() = _clientMode.value == ClientMode.MUSIC_ASSISTANT

    // ========================================================================
    // Combined Wizard State (for Compose)
    // ========================================================================

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
        _clientMode,
        _setAsDefault
    ) { values ->
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
        val mode = values[18] as ClientMode
        val isDefaultVal = values[19] as Boolean

        WizardState(
            currentStep = step,
            isEditMode = _editingServer.value != null,
            isNextEnabled = computeNextEnabled(step),
            clientMode = mode,
            serverName = name,
            setAsDefault = isDefaultVal,
            networkHint = _networkHint.value,
            connectionSummary = getConnectionMethodSummary(),
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
            // Card-selection steps — no Next button, but always "enabled"
            WizardStep.ClientType,
            WizardStep.MA_NetworkQuestion,
            WizardStep.MA_RemoteQuestion -> true

            // FindServer steps — need an address
            WizardStep.SS_FindServer,
            WizardStep.MA_FindServer -> _localAddress.value.isNotBlank()

            // Testing steps — disabled (auto-advance)
            WizardStep.SS_TestLocal,
            WizardStep.MA_TestLocal,
            WizardStep.MA_TestRemote,
            WizardStep.MA_TestRemoteOnly -> false

            // MA Login — enabled when token obtained
            WizardStep.MA_Login,
            WizardStep.MA_LoginRemote -> _maToken.value != null

            // Remote setup — always enabled (user can proceed)
            WizardStep.MA_RemoteSetup,
            WizardStep.MA_RemoteOnlySetup -> true

            // Finish steps — need a name and at least one connection
            WizardStep.SS_Finish,
            WizardStep.MA_Finish,
            WizardStep.MA_FinishRemoteOnly -> _serverName.value.isNotBlank() && hasValidConnectionMethod()
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
     * Returns true if navigation succeeded.
     */
    fun onNext(): Boolean {
        return when (_currentStep.value) {
            // Card-selection steps — handled by handleStepAction, not onNext
            WizardStep.ClientType,
            WizardStep.MA_NetworkQuestion,
            WizardStep.MA_RemoteQuestion -> true

            // SendSpin path
            WizardStep.SS_FindServer -> {
                if (localAddress.isBlank()) return false
                _currentStep.value = WizardStep.SS_TestLocal
                true
            }
            WizardStep.SS_TestLocal -> true // Handled by test completion
            WizardStep.SS_Finish -> true // Handled by Activity save

            // MA local path
            WizardStep.MA_FindServer -> {
                if (localAddress.isBlank()) return false
                _currentStep.value = WizardStep.MA_TestLocal
                true
            }
            WizardStep.MA_TestLocal -> true // Handled by test completion
            WizardStep.MA_Login -> {
                _currentStep.value = WizardStep.MA_RemoteQuestion
                true
            }
            WizardStep.MA_RemoteSetup -> {
                // Start remote test
                _currentStep.value = WizardStep.MA_TestRemote
                true
            }
            WizardStep.MA_TestRemote -> true // Handled by test completion
            WizardStep.MA_Finish -> true // Handled by Activity save

            // MA remote-only path
            WizardStep.MA_RemoteOnlySetup -> {
                _currentStep.value = WizardStep.MA_TestRemoteOnly
                true
            }
            WizardStep.MA_TestRemoteOnly -> true // Handled by test completion
            WizardStep.MA_LoginRemote -> {
                _currentStep.value = WizardStep.MA_FinishRemoteOnly
                true
            }
            WizardStep.MA_FinishRemoteOnly -> true // Handled by Activity save
        }
    }

    /**
     * Handle "Back" action based on current step.
     * Returns the previous step, or null if at the beginning (close wizard).
     */
    fun onBack(): WizardStep? {
        val previous = when (_currentStep.value) {
            WizardStep.ClientType -> null // Close wizard

            // SendSpin path
            WizardStep.SS_FindServer -> WizardStep.ClientType
            WizardStep.SS_TestLocal -> WizardStep.SS_FindServer
            WizardStep.SS_Finish -> WizardStep.SS_FindServer

            // MA path
            WizardStep.MA_NetworkQuestion -> WizardStep.ClientType
            WizardStep.MA_FindServer -> WizardStep.MA_NetworkQuestion
            WizardStep.MA_TestLocal -> WizardStep.MA_FindServer
            WizardStep.MA_Login -> WizardStep.MA_FindServer
            WizardStep.MA_RemoteQuestion -> WizardStep.MA_Login
            WizardStep.MA_RemoteSetup -> WizardStep.MA_RemoteQuestion
            WizardStep.MA_TestRemote -> WizardStep.MA_RemoteSetup
            WizardStep.MA_Finish -> {
                if (wantsRemoteAccess) WizardStep.MA_RemoteSetup
                else WizardStep.MA_RemoteQuestion
            }

            // MA remote-only path
            WizardStep.MA_RemoteOnlySetup -> WizardStep.MA_NetworkQuestion
            WizardStep.MA_TestRemoteOnly -> WizardStep.MA_RemoteOnlySetup
            WizardStep.MA_LoginRemote -> WizardStep.MA_RemoteOnlySetup
            WizardStep.MA_FinishRemoteOnly -> WizardStep.MA_LoginRemote
        }
        previous?.let { _currentStep.value = it }
        return previous
    }

    /**
     * Handle "Skip" action for optional steps (MA Login).
     */
    fun onSkipMaLogin() {
        maToken = null
        maUsername = ""
        maPassword = ""
        when (_currentStep.value) {
            WizardStep.MA_Login -> _currentStep.value = WizardStep.MA_RemoteQuestion
            WizardStep.MA_LoginRemote -> _currentStep.value = WizardStep.MA_FinishRemoteOnly
            else -> { /* Unexpected */ }
        }
    }

    /**
     * Set the remote access method (tab selection in RemoteSetupStep).
     */
    fun setRemoteMethod(method: RemoteAccessMethod) {
        _remoteAccessMethod.value = method
    }

    /**
     * Set the network hint text (called from Activity based on NetworkEvaluator).
     */
    fun setNetworkHint(hint: String) {
        _networkHint.value = hint
    }

    // ========================================================================
    // Connection Testing
    // ========================================================================

    /**
     * Called when local connection test completes successfully.
     * Routes to the correct next step based on path.
     */
    fun onLocalTestSuccess(message: String = "Connection successful") {
        _localTestState.value = ConnectionTestState.Success(message)

        when (_currentStep.value) {
            WizardStep.SS_TestLocal -> _currentStep.value = WizardStep.SS_Finish
            WizardStep.MA_TestLocal -> _currentStep.value = WizardStep.MA_Login
            else -> { /* Unexpected */ }
        }
    }

    /**
     * Called when local connection test fails.
     */
    fun onLocalTestFailed(error: String) {
        _localTestState.value = ConnectionTestState.Failed(error)

        when (_currentStep.value) {
            WizardStep.SS_TestLocal -> _currentStep.value = WizardStep.SS_FindServer
            WizardStep.MA_TestLocal -> _currentStep.value = WizardStep.MA_FindServer
            else -> { /* Unexpected */ }
        }
    }

    fun resetLocalTest() {
        _localTestState.value = ConnectionTestState.Idle
    }

    /**
     * Called when remote/proxy connection test completes successfully.
     */
    fun onRemoteTestSuccess(message: String = "Connection successful") {
        _remoteTestState.value = ConnectionTestState.Success(message)

        when (_currentStep.value) {
            WizardStep.MA_TestRemote -> _currentStep.value = WizardStep.MA_Finish
            WizardStep.MA_TestRemoteOnly -> _currentStep.value = WizardStep.MA_LoginRemote
            else -> { /* Unexpected */ }
        }
    }

    /**
     * Called when remote/proxy connection test fails.
     */
    fun onRemoteTestFailed(error: String) {
        _remoteTestState.value = ConnectionTestState.Failed(error)

        when (_currentStep.value) {
            WizardStep.MA_TestRemote -> _currentStep.value = WizardStep.MA_RemoteSetup
            WizardStep.MA_TestRemoteOnly -> _currentStep.value = WizardStep.MA_RemoteOnlySetup
            else -> { /* Unexpected */ }
        }
    }

    fun resetRemoteTest() {
        _remoteTestState.value = ConnectionTestState.Idle
    }

    // ========================================================================
    // MA Login Testing
    // ========================================================================

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

                // Auto-populate server name from the MA server's base URL if still blank
                if (serverName.isBlank() && result.baseUrl.isNotBlank()) {
                    serverName = extractServerNameFromUrl(result.baseUrl)
                }

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

    private fun deriveMaApiUrl(): String? {
        if (localAddress.isNotBlank()) {
            val host = localAddress.substringBefore(":")
            return "ws://$host:$maPort/ws"
        }

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

    fun resetMaTest() {
        _maTestState.value = ConnectionTestState.Idle
    }

    /**
     * Extracts a human-friendly server name from a base URL.
     *
     * Examples:
     * - "http://192.168.1.100:8095" → "Music Assistant"
     * - "https://music.home.example.com" → "music.home.example.com"
     * - "https://ma.local:8095" → "ma.local"
     */
    private fun extractServerNameFromUrl(baseUrl: String): String {
        val host = try {
            java.net.URI(baseUrl).host ?: baseUrl
        } catch (_: Exception) {
            baseUrl
        }

        // If it's a raw IP address, just use a generic name
        if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
            return "Music Assistant"
        }

        // Strip port if still present and return the hostname
        return host.substringBefore(":")
    }

    // ========================================================================
    // Edit Mode
    // ========================================================================

    /**
     * Initialize the ViewModel for editing an existing server.
     * Routes to the appropriate Finish step based on server configuration.
     */
    fun initForEdit(server: UnifiedServer, existingMaToken: String?) {
        editingServer = server
        serverName = server.name
        setAsDefault = server.isDefaultServer

        clientMode = if (server.isMusicAssistant) ClientMode.MUSIC_ASSISTANT else ClientMode.SENDSPIN

        server.local?.let {
            localAddress = it.address
            isOnLocalNetwork = true
        }

        server.remote?.let {
            remoteId = it.remoteId
            _remoteAccessMethod.value = RemoteAccessMethod.REMOTE_ID
            wantsRemoteAccess = true
        }

        server.proxy?.let {
            proxyUrl = it.url
            proxyToken = it.authToken
            proxyUsername = it.username ?: ""
            proxyAuthMode = if (it.username != null) AUTH_LOGIN else AUTH_TOKEN
            _remoteAccessMethod.value = RemoteAccessMethod.PROXY
            wantsRemoteAccess = true
        }

        if (server.isMusicAssistant) {
            maToken = existingMaToken
        }

        // Route to the correct Finish step
        _currentStep.value = when {
            !server.isMusicAssistant -> WizardStep.SS_Finish
            server.local != null -> WizardStep.MA_Finish
            else -> WizardStep.MA_FinishRemoteOnly
        }
    }

    val isEditMode: Boolean
        get() = editingServer != null

    fun getServerId(): String {
        return editingServer?.id ?: com.sendspindroid.UnifiedServerRepository.generateId()
    }

    // ========================================================================
    // Validation Helpers
    // ========================================================================

    fun hasValidConnectionMethod(): Boolean {
        return localAddress.isNotBlank() ||
               (remoteId.isNotBlank() && com.sendspindroid.remote.RemoteConnection.parseRemoteId(remoteId) != null) ||
               proxyUrl.isNotBlank()
    }

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

        if (isMusicAssistant && maToken != null) {
            methods.add("Music Assistant: Authenticated")
        }

        return methods
    }

    // ========================================================================
    // Step Action Handling (for Compose)
    // ========================================================================

    /**
     * Handle step-specific actions from the Compose UI.
     * Returns true if the action requires additional handling by the Activity.
     */
    fun handleStepAction(action: WizardStepAction): Boolean {
        return when (action) {
            // ClientType step — card tap navigates
            is WizardStepAction.SelectClientMode -> {
                clientMode = action.mode
                when (action.mode) {
                    ClientMode.SENDSPIN -> _currentStep.value = WizardStep.SS_FindServer
                    ClientMode.MUSIC_ASSISTANT -> _currentStep.value = WizardStep.MA_NetworkQuestion
                }
                false
            }

            // NetworkQuestion step — card tap navigates
            is WizardStepAction.SelectNetworkLocation -> {
                isOnLocalNetwork = action.isLocal
                if (action.isLocal) {
                    _currentStep.value = WizardStep.MA_FindServer
                } else {
                    // Default to REMOTE_ID for remote-only setup
                    _remoteAccessMethod.value = RemoteAccessMethod.REMOTE_ID
                    _currentStep.value = WizardStep.MA_RemoteOnlySetup
                }
                false
            }

            // RemoteQuestion step — card tap navigates
            is WizardStepAction.SelectWantsRemote -> {
                wantsRemoteAccess = action.wantsRemote
                if (action.wantsRemote) {
                    // Default to REMOTE_ID tab
                    _remoteAccessMethod.value = RemoteAccessMethod.REMOTE_ID
                    _currentStep.value = WizardStep.MA_RemoteSetup
                } else {
                    _currentStep.value = WizardStep.MA_Finish
                }
                false
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
            WizardStepAction.StartDiscovery -> {
                true // Activity should start mDNS discovery
            }
            WizardStepAction.RetryLocalTest -> {
                resetLocalTest()
                when (_currentStep.value) {
                    WizardStep.SS_TestLocal -> _currentStep.value = WizardStep.SS_FindServer
                    WizardStep.MA_TestLocal -> _currentStep.value = WizardStep.MA_FindServer
                    else -> { /* Unexpected */ }
                }
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

            // Remote setup step
            is WizardStepAction.SelectRemoteMethod -> {
                setRemoteMethod(action.method)
                false
            }

            // Remote ID
            is WizardStepAction.UpdateRemoteId -> {
                remoteId = action.id.uppercase().take(26)
                false
            }
            WizardStepAction.ScanQrCode -> {
                true // Activity should launch QR scanner
            }
            WizardStepAction.RetryRemoteTest -> {
                resetRemoteTest()
                when (_currentStep.value) {
                    WizardStep.MA_TestRemote -> _currentStep.value = WizardStep.MA_RemoteSetup
                    WizardStep.MA_TestRemoteOnly -> _currentStep.value = WizardStep.MA_RemoteOnlySetup
                    else -> { /* Unexpected */ }
                }
                false
            }

            // Proxy
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

            // Finish step
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

    // ========================================================================
    // Discovery
    // ========================================================================

    fun updateDiscoveredServers(servers: List<DiscoveredServerUi>) {
        _discoveredServers.value = servers
    }

    fun setSearching(searching: Boolean) {
        _isSearching.value = searching
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

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

    fun applyDiscoveredServer(name: String, address: String) {
        discoveredServerName = name
        discoveredServerAddress = address
        serverName = name
        localAddress = address
    }

    fun clear() {
        _serverName.value = ""
        _setAsDefault.value = false
        _clientMode.value = ClientMode.SENDSPIN
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
        _networkHint.value = ""

        isOnLocalNetwork = true
        wantsRemoteAccess = false
        discoveredServerName = null
        discoveredServerAddress = null
        isLoading = false

        _currentStep.value = WizardStep.ClientType
        _localTestState.value = ConnectionTestState.Idle
        _remoteTestState.value = ConnectionTestState.Idle
        _maTestState.value = ConnectionTestState.Idle
        _remoteAccessMethod.value = RemoteAccessMethod.REMOTE_ID
    }
}
