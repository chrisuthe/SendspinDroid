package com.sendspindroid.ui.wizard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.wizard.steps.ClientTypeStep
import com.sendspindroid.ui.wizard.steps.FindServerStep
import com.sendspindroid.ui.wizard.steps.FinishStep
import com.sendspindroid.ui.wizard.steps.MaLoginStep
import com.sendspindroid.ui.wizard.steps.NetworkQuestionStep
import com.sendspindroid.ui.wizard.steps.RemoteQuestionStep
import com.sendspindroid.ui.wizard.steps.RemoteSetupStep
import com.sendspindroid.ui.wizard.steps.TestingStep

/**
 * Main Add Server Wizard screen that hosts all wizard steps.
 * Uses animated content to transition between steps with slide animations.
 *
 * The wizard branches based on user intent (SendSpin vs Music Assistant)
 * and network situation (same network vs remote-only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerWizardScreen(
    state: WizardState,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onSave: () -> Unit,
    onStepAction: (WizardStepAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(getStepTitle(state.currentStep)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.wizard_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            WizardBottomBar(
                step = state.currentStep,
                onBack = onBack,
                onNext = onNext,
                onSkip = onSkip,
                onSave = onSave,
                isNextEnabled = state.isNextEnabled
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Progress indicator
            LinearProgressIndicator(
                progress = { getStepProgress(state.currentStep) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            // Animated step content
            AnimatedContent(
                targetState = state.currentStep,
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally { width -> width * direction } + fadeIn()).togetherWith(
                        slideOutHorizontally { width -> -width * direction } + fadeOut()
                    )
                },
                label = "wizard_step_transition",
                modifier = Modifier.fillMaxSize()
            ) { step ->
                WizardStepContent(
                    step = step,
                    state = state,
                    onStepAction = onStepAction
                )
            }
        }
    }
}

/**
 * Renders the content for the current wizard step.
 */
@Composable
private fun WizardStepContent(
    step: WizardStep,
    state: WizardState,
    onStepAction: (WizardStepAction) -> Unit
) {
    when (step) {
        // Entry point
        WizardStep.ClientType -> ClientTypeStep(
            onClientModeSelected = { onStepAction(WizardStepAction.SelectClientMode(it)) }
        )

        // SendSpin path
        WizardStep.SS_FindServer -> FindServerStep(
            discoveredServers = state.discoveredServers,
            localAddress = state.localAddress,
            isSearching = state.isSearching,
            onAddressChange = { onStepAction(WizardStepAction.UpdateLocalAddress(it)) },
            onServerSelected = { onStepAction(WizardStepAction.SelectDiscoveredServer(it)) },
            onStartSearch = { onStepAction(WizardStepAction.StartDiscovery) }
        )
        WizardStep.SS_TestLocal -> TestingStep(
            testState = state.localTestState,
            isLocalTest = true,
            onRetry = { onStepAction(WizardStepAction.RetryLocalTest) }
        )
        WizardStep.SS_Finish -> FinishStep(
            serverName = state.serverName,
            isDefault = state.setAsDefault,
            connectionSummary = state.connectionSummary,
            onNameChange = { onStepAction(WizardStepAction.UpdateServerName(it)) },
            onDefaultChange = { onStepAction(WizardStepAction.UpdateSetAsDefault(it)) }
        )

        // MA path — network question
        WizardStep.MA_NetworkQuestion -> NetworkQuestionStep(
            networkHint = state.networkHint,
            onSameNetwork = { onStepAction(WizardStepAction.SelectNetworkLocation(isLocal = true)) },
            onRemote = { onStepAction(WizardStepAction.SelectNetworkLocation(isLocal = false)) }
        )

        // MA local path
        WizardStep.MA_FindServer -> FindServerStep(
            discoveredServers = state.discoveredServers,
            localAddress = state.localAddress,
            isSearching = state.isSearching,
            onAddressChange = { onStepAction(WizardStepAction.UpdateLocalAddress(it)) },
            onServerSelected = { onStepAction(WizardStepAction.SelectDiscoveredServer(it)) },
            onStartSearch = { onStepAction(WizardStepAction.StartDiscovery) }
        )
        WizardStep.MA_TestLocal -> TestingStep(
            testState = state.localTestState,
            isLocalTest = true,
            onRetry = { onStepAction(WizardStepAction.RetryLocalTest) }
        )
        WizardStep.MA_Login -> MaLoginStep(
            username = state.maUsername,
            password = state.maPassword,
            port = state.maPort,
            testState = state.maTestState,
            onUsernameChange = { onStepAction(WizardStepAction.UpdateMaUsername(it)) },
            onPasswordChange = { onStepAction(WizardStepAction.UpdateMaPassword(it)) },
            onPortChange = { onStepAction(WizardStepAction.UpdateMaPort(it)) },
            onTestConnection = { onStepAction(WizardStepAction.TestMaConnection) }
        )
        WizardStep.MA_RemoteQuestion -> RemoteQuestionStep(
            onYesRemote = { onStepAction(WizardStepAction.SelectWantsRemote(wantsRemote = true)) },
            onNoLocalOnly = { onStepAction(WizardStepAction.SelectWantsRemote(wantsRemote = false)) }
        )
        WizardStep.MA_RemoteSetup -> RemoteSetupStep(
            remoteAccessMethod = state.remoteAccessMethod,
            remoteId = state.remoteId,
            proxyUrl = state.proxyUrl,
            proxyAuthMode = state.proxyAuthMode,
            proxyUsername = state.proxyUsername,
            proxyPassword = state.proxyPassword,
            proxyToken = state.proxyToken,
            onMethodChange = { onStepAction(WizardStepAction.SelectRemoteMethod(it)) },
            onRemoteIdChange = { onStepAction(WizardStepAction.UpdateRemoteId(it)) },
            onScanQr = { onStepAction(WizardStepAction.ScanQrCode) },
            onProxyUrlChange = { onStepAction(WizardStepAction.UpdateProxyUrl(it)) },
            onAuthModeChange = { onStepAction(WizardStepAction.UpdateProxyAuthMode(it)) },
            onProxyUsernameChange = { onStepAction(WizardStepAction.UpdateProxyUsername(it)) },
            onProxyPasswordChange = { onStepAction(WizardStepAction.UpdateProxyPassword(it)) },
            onProxyTokenChange = { onStepAction(WizardStepAction.UpdateProxyToken(it)) }
        )
        WizardStep.MA_TestRemote -> TestingStep(
            testState = state.remoteTestState,
            isLocalTest = false,
            onRetry = { onStepAction(WizardStepAction.RetryRemoteTest) }
        )
        WizardStep.MA_Finish -> FinishStep(
            serverName = state.serverName,
            isDefault = state.setAsDefault,
            connectionSummary = state.connectionSummary,
            onNameChange = { onStepAction(WizardStepAction.UpdateServerName(it)) },
            onDefaultChange = { onStepAction(WizardStepAction.UpdateSetAsDefault(it)) }
        )

        // MA remote-only path
        WizardStep.MA_RemoteOnlySetup -> RemoteSetupStep(
            remoteAccessMethod = state.remoteAccessMethod,
            remoteId = state.remoteId,
            proxyUrl = state.proxyUrl,
            proxyAuthMode = state.proxyAuthMode,
            proxyUsername = state.proxyUsername,
            proxyPassword = state.proxyPassword,
            proxyToken = state.proxyToken,
            onMethodChange = { onStepAction(WizardStepAction.SelectRemoteMethod(it)) },
            onRemoteIdChange = { onStepAction(WizardStepAction.UpdateRemoteId(it)) },
            onScanQr = { onStepAction(WizardStepAction.ScanQrCode) },
            onProxyUrlChange = { onStepAction(WizardStepAction.UpdateProxyUrl(it)) },
            onAuthModeChange = { onStepAction(WizardStepAction.UpdateProxyAuthMode(it)) },
            onProxyUsernameChange = { onStepAction(WizardStepAction.UpdateProxyUsername(it)) },
            onProxyPasswordChange = { onStepAction(WizardStepAction.UpdateProxyPassword(it)) },
            onProxyTokenChange = { onStepAction(WizardStepAction.UpdateProxyToken(it)) }
        )
        WizardStep.MA_TestRemoteOnly -> TestingStep(
            testState = state.remoteTestState,
            isLocalTest = false,
            onRetry = { onStepAction(WizardStepAction.RetryRemoteTest) }
        )
        WizardStep.MA_LoginRemote -> MaLoginStep(
            username = state.maUsername,
            password = state.maPassword,
            port = state.maPort,
            testState = state.maTestState,
            onUsernameChange = { onStepAction(WizardStepAction.UpdateMaUsername(it)) },
            onPasswordChange = { onStepAction(WizardStepAction.UpdateMaPassword(it)) },
            onPortChange = { onStepAction(WizardStepAction.UpdateMaPort(it)) },
            onTestConnection = { onStepAction(WizardStepAction.TestMaConnection) }
        )
        WizardStep.MA_FinishRemoteOnly -> FinishStep(
            serverName = state.serverName,
            isDefault = state.setAsDefault,
            connectionSummary = state.connectionSummary,
            onNameChange = { onStepAction(WizardStepAction.UpdateServerName(it)) },
            onDefaultChange = { onStepAction(WizardStepAction.UpdateSetAsDefault(it)) }
        )
    }
}

/**
 * Bottom bar with Back, Skip, and Next/Save buttons.
 *
 * Card-selection steps (ClientType, NetworkQuestion, RemoteQuestion) have no buttons —
 * the user taps a card to navigate. Testing steps also have no buttons (auto-advance).
 * Finish steps show Back + Save. Config steps show Back + Next.
 */
@Composable
private fun WizardBottomBar(
    step: WizardStep,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onSave: () -> Unit,
    isNextEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    // Card-selection steps — no bottom bar at all
    val isCardSelectionStep = step in setOf(
        WizardStep.ClientType,
        WizardStep.MA_NetworkQuestion,
        WizardStep.MA_RemoteQuestion
    )

    // Testing steps — no bottom bar (auto-advance on completion)
    val isTestingStep = step in setOf(
        WizardStep.SS_TestLocal,
        WizardStep.MA_TestLocal,
        WizardStep.MA_TestRemote,
        WizardStep.MA_TestRemoteOnly
    )

    // Finish/save steps
    val isFinalStep = step in setOf(
        WizardStep.SS_Finish,
        WizardStep.MA_Finish,
        WizardStep.MA_FinishRemoteOnly
    )

    // Show skip on MA_Login steps (user can proceed without authenticating)
    val showSkip = step == WizardStep.MA_Login || step == WizardStep.MA_LoginRemote

    // Hide bottom bar entirely for card-selection and testing steps
    if (isCardSelectionStep || isTestingStep) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.wizard_back))
            }

            Spacer(modifier = Modifier.weight(1f))

            // Skip button (MA Login steps)
            if (showSkip) {
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.wizard_skip))
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Next/Save button
            Button(
                onClick = if (isFinalStep) onSave else onNext,
                enabled = isNextEnabled
            ) {
                Text(
                    if (isFinalStep) stringResource(R.string.wizard_save)
                    else stringResource(R.string.wizard_next)
                )
            }
        }
    }
}

/**
 * Returns the title for the given wizard step.
 */
@Composable
private fun getStepTitle(step: WizardStep): String {
    return when (step) {
        WizardStep.ClientType -> stringResource(R.string.wizard_title_add_server)

        WizardStep.SS_FindServer,
        WizardStep.MA_FindServer -> stringResource(R.string.wizard_find_server_title)

        WizardStep.SS_TestLocal,
        WizardStep.MA_TestLocal,
        WizardStep.MA_TestRemote,
        WizardStep.MA_TestRemoteOnly -> stringResource(R.string.wizard_testing_title)

        WizardStep.MA_NetworkQuestion -> stringResource(R.string.wizard_title_add_server)
        WizardStep.MA_Login,
        WizardStep.MA_LoginRemote -> stringResource(R.string.wizard_ma_login_title)

        WizardStep.MA_RemoteQuestion -> stringResource(R.string.wizard_title_add_server)

        WizardStep.MA_RemoteSetup,
        WizardStep.MA_RemoteOnlySetup -> stringResource(R.string.wizard_remote_title)

        WizardStep.SS_Finish,
        WizardStep.MA_Finish,
        WizardStep.MA_FinishRemoteOnly -> stringResource(R.string.wizard_save_title)
    }
}

/**
 * Returns the progress (0-1) for the given wizard step.
 * Progress is per-path to give users accurate feedback.
 */
private fun getStepProgress(step: WizardStep): Float {
    return when (step) {
        // Entry point
        WizardStep.ClientType -> 0.05f

        // SendSpin path (3 steps after ClientType)
        WizardStep.SS_FindServer -> 0.33f
        WizardStep.SS_TestLocal -> 0.50f
        WizardStep.SS_Finish -> 1.0f

        // MA local path (up to 8 steps after ClientType)
        WizardStep.MA_NetworkQuestion -> 0.12f
        WizardStep.MA_FindServer -> 0.25f
        WizardStep.MA_TestLocal -> 0.35f
        WizardStep.MA_Login -> 0.50f
        WizardStep.MA_RemoteQuestion -> 0.62f
        WizardStep.MA_RemoteSetup -> 0.75f
        WizardStep.MA_TestRemote -> 0.87f
        WizardStep.MA_Finish -> 1.0f

        // MA remote-only path (5 steps after ClientType)
        WizardStep.MA_RemoteOnlySetup -> 0.25f
        WizardStep.MA_TestRemoteOnly -> 0.50f
        WizardStep.MA_LoginRemote -> 0.75f
        WizardStep.MA_FinishRemoteOnly -> 1.0f
    }
}

// ============================================================================
// State and Actions
// ============================================================================

/**
 * Complete state for the wizard.
 */
data class WizardState(
    val currentStep: WizardStep = WizardStep.ClientType,
    val isEditMode: Boolean = false,
    val isNextEnabled: Boolean = true,

    // Client mode (SendSpin vs Music Assistant)
    val clientMode: ClientMode = ClientMode.SENDSPIN,

    // Server data
    val serverName: String = "",
    val setAsDefault: Boolean = false,

    // Network hint (auto-detected)
    val networkHint: String = "",

    // Connection summary (for Finish steps)
    val connectionSummary: List<String> = emptyList(),

    // Local connection
    val localAddress: String = "",
    val discoveredServers: List<DiscoveredServerUi> = emptyList(),
    val isSearching: Boolean = false,
    val localTestState: ConnectionTestState = ConnectionTestState.Idle,

    // Remote access
    val remoteAccessMethod: RemoteAccessMethod = RemoteAccessMethod.REMOTE_ID,
    val remoteId: String = "",
    val remoteTestState: ConnectionTestState = ConnectionTestState.Idle,

    // Proxy
    val proxyUrl: String = "",
    val proxyAuthMode: ProxyAuthMode = ProxyAuthMode.LOGIN,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val proxyToken: String = "",

    // Music Assistant login
    val maUsername: String = "",
    val maPassword: String = "",
    val maPort: Int = 8095,
    val maToken: String? = null,
    val maTestState: ConnectionTestState = ConnectionTestState.Idle
)

/**
 * Proxy authentication mode.
 */
enum class ProxyAuthMode {
    LOGIN, TOKEN
}

/**
 * Discovered server UI model.
 */
data class DiscoveredServerUi(
    val id: String,
    val name: String,
    val address: String
)

/**
 * Actions that can be triggered from wizard steps.
 */
sealed class WizardStepAction {
    // ClientType step — card tap
    data class SelectClientMode(val mode: ClientMode) : WizardStepAction()

    // NetworkQuestion step — card tap
    data class SelectNetworkLocation(val isLocal: Boolean) : WizardStepAction()

    // RemoteQuestion step — card tap
    data class SelectWantsRemote(val wantsRemote: Boolean) : WizardStepAction()

    // Find server step
    data class UpdateLocalAddress(val address: String) : WizardStepAction()
    data class SelectDiscoveredServer(val server: DiscoveredServerUi) : WizardStepAction()
    data object StartDiscovery : WizardStepAction()
    data object RetryLocalTest : WizardStepAction()

    // MA Login step
    data class UpdateMaUsername(val username: String) : WizardStepAction()
    data class UpdateMaPassword(val password: String) : WizardStepAction()
    data class UpdateMaPort(val port: Int) : WizardStepAction()
    data object TestMaConnection : WizardStepAction()

    // Remote setup step (method selection within tabbed UI)
    data class SelectRemoteMethod(val method: RemoteAccessMethod) : WizardStepAction()

    // Remote ID
    data class UpdateRemoteId(val id: String) : WizardStepAction()
    data object ScanQrCode : WizardStepAction()
    data object RetryRemoteTest : WizardStepAction()

    // Proxy
    data class UpdateProxyUrl(val url: String) : WizardStepAction()
    data class UpdateProxyAuthMode(val mode: ProxyAuthMode) : WizardStepAction()
    data class UpdateProxyUsername(val username: String) : WizardStepAction()
    data class UpdateProxyPassword(val password: String) : WizardStepAction()
    data class UpdateProxyToken(val token: String) : WizardStepAction()

    // Finish step
    data class UpdateServerName(val name: String) : WizardStepAction()
    data class UpdateSetAsDefault(val isDefault: Boolean) : WizardStepAction()
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true)
@Composable
private fun WizardClientTypePreview() {
    SendSpinTheme {
        AddServerWizardScreen(
            state = WizardState(currentStep = WizardStep.ClientType),
            onClose = {},
            onBack = {},
            onNext = {},
            onSkip = {},
            onSave = {},
            onStepAction = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WizardFindServerPreview() {
    SendSpinTheme {
        AddServerWizardScreen(
            state = WizardState(
                currentStep = WizardStep.SS_FindServer,
                discoveredServers = listOf(
                    DiscoveredServerUi("1", "Living Room", "192.168.1.100:8927"),
                    DiscoveredServerUi("2", "Office", "192.168.1.101:8927")
                ),
                isSearching = true
            ),
            onClose = {},
            onBack = {},
            onNext = {},
            onSkip = {},
            onSave = {},
            onStepAction = {}
        )
    }
}
