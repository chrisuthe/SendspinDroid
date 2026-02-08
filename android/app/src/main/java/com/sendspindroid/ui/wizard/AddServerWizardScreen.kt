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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.wizard.steps.FindServerStep
import com.sendspindroid.ui.wizard.steps.MaLoginStep
import com.sendspindroid.ui.wizard.steps.ProxyStep
import com.sendspindroid.ui.wizard.steps.RemoteChoiceStep
import com.sendspindroid.ui.wizard.steps.RemoteIdStep
import com.sendspindroid.ui.wizard.steps.RemoteOnlyWarningStep
import com.sendspindroid.ui.wizard.steps.SaveStep
import com.sendspindroid.ui.wizard.steps.TestingStep
import com.sendspindroid.ui.wizard.steps.WelcomeStep

/**
 * Main Add Server Wizard screen that hosts all wizard steps.
 * Uses animated content to transition between steps with slide animations.
 *
 * @param state Current wizard state
 * @param onClose Called when wizard is closed (via back or close button)
 * @param onBack Called when back button is pressed within the wizard
 * @param onNext Called when next button is pressed
 * @param onSkip Called when skip button is pressed (on certain steps)
 * @param onSave Called when save button is pressed on final step
 * @param onStepAction Handles step-specific actions (e.g., start discovery, test connection)
 * @param modifier Modifier for the screen
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
        WizardStep.Welcome -> WelcomeStep(
            onSetupMyServer = { onStepAction(WizardStepAction.SetupMyServer) },
            onFindOtherServers = { onStepAction(WizardStepAction.FindOtherServers) }
        )
        WizardStep.FindServer -> FindServerStep(
            discoveredServers = state.discoveredServers,
            localAddress = state.localAddress,
            isSearching = state.isSearching,
            isMusicAssistant = state.isMusicAssistant,
            onAddressChange = { onStepAction(WizardStepAction.UpdateLocalAddress(it)) },
            onServerSelected = { onStepAction(WizardStepAction.SelectDiscoveredServer(it)) },
            onMusicAssistantChange = { onStepAction(WizardStepAction.UpdateIsMusicAssistant(it)) },
            onStartSearch = { onStepAction(WizardStepAction.StartDiscovery) }
        )
        WizardStep.TestingLocal -> TestingStep(
            testState = state.localTestState,
            isLocalTest = true,
            onRetry = { onStepAction(WizardStepAction.RetryLocalTest) }
        )
        WizardStep.MaLogin -> MaLoginStep(
            username = state.maUsername,
            password = state.maPassword,
            port = state.maPort,
            testState = state.maTestState,
            onUsernameChange = { onStepAction(WizardStepAction.UpdateMaUsername(it)) },
            onPasswordChange = { onStepAction(WizardStepAction.UpdateMaPassword(it)) },
            onPortChange = { onStepAction(WizardStepAction.UpdateMaPort(it)) },
            onTestConnection = { onStepAction(WizardStepAction.TestMaConnection) }
        )
        WizardStep.RemoteChoice -> RemoteChoiceStep(
            selectedMethod = state.remoteAccessMethod,
            onMethodSelected = { onStepAction(WizardStepAction.SelectRemoteMethod(it)) }
        )
        WizardStep.RemoteId -> RemoteIdStep(
            remoteId = state.remoteId,
            onRemoteIdChange = { onStepAction(WizardStepAction.UpdateRemoteId(it)) },
            onScanQr = { onStepAction(WizardStepAction.ScanQrCode) }
        )
        WizardStep.Proxy -> ProxyStep(
            proxyUrl = state.proxyUrl,
            authMode = state.proxyAuthMode,
            username = state.proxyUsername,
            password = state.proxyPassword,
            token = state.proxyToken,
            onProxyUrlChange = { onStepAction(WizardStepAction.UpdateProxyUrl(it)) },
            onAuthModeChange = { onStepAction(WizardStepAction.UpdateProxyAuthMode(it)) },
            onUsernameChange = { onStepAction(WizardStepAction.UpdateProxyUsername(it)) },
            onPasswordChange = { onStepAction(WizardStepAction.UpdateProxyPassword(it)) },
            onTokenChange = { onStepAction(WizardStepAction.UpdateProxyToken(it)) }
        )
        WizardStep.TestingRemote -> TestingStep(
            testState = state.remoteTestState,
            isLocalTest = false,
            onRetry = { onStepAction(WizardStepAction.RetryRemoteTest) }
        )
        WizardStep.RemoteOnlyWarning -> RemoteOnlyWarningStep(
            onContinue = { onStepAction(WizardStepAction.AcknowledgeRemoteOnlyWarning) }
        )
        WizardStep.Save -> SaveStep(
            serverName = state.serverName,
            isDefault = state.setAsDefault,
            onNameChange = { onStepAction(WizardStepAction.UpdateServerName(it)) },
            onDefaultChange = { onStepAction(WizardStepAction.UpdateSetAsDefault(it)) }
        )
    }
}

/**
 * Bottom bar with Back, Skip, and Next/Save buttons.
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
    val isFirstStep = step == WizardStep.Welcome
    val isTestingStep = step == WizardStep.TestingLocal || step == WizardStep.TestingRemote
    val isFinalStep = step == WizardStep.Save
    val showSkip = step == WizardStep.FindServer

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button
            if (!isFirstStep && !isTestingStep) {
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.wizard_back))
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            // Skip button
            if (showSkip) {
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.wizard_skip))
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Next/Save button
            if (!isTestingStep) {
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
}

/**
 * Returns the title for the given wizard step.
 */
@Composable
private fun getStepTitle(step: WizardStep): String {
    return when (step) {
        WizardStep.Welcome -> stringResource(R.string.wizard_title_add_server)
        WizardStep.FindServer -> stringResource(R.string.wizard_find_server_title)
        WizardStep.TestingLocal, WizardStep.TestingRemote -> stringResource(R.string.wizard_testing_title)
        WizardStep.MaLogin -> stringResource(R.string.wizard_ma_login_title)
        WizardStep.RemoteChoice -> stringResource(R.string.wizard_remote_choice_title)
        WizardStep.RemoteId -> stringResource(R.string.wizard_remote_title)
        WizardStep.Proxy -> stringResource(R.string.wizard_proxy_title)
        WizardStep.RemoteOnlyWarning -> stringResource(R.string.wizard_remote_only_title)
        WizardStep.Save -> stringResource(R.string.wizard_save_title)
    }
}

/**
 * Returns the progress (0-1) for the given wizard step.
 */
private fun getStepProgress(step: WizardStep): Float {
    return when (step) {
        WizardStep.Welcome -> 0.1f
        WizardStep.FindServer -> 0.25f
        WizardStep.TestingLocal -> 0.30f
        WizardStep.MaLogin -> 0.45f
        WizardStep.RemoteChoice -> 0.55f
        WizardStep.RemoteOnlyWarning -> 0.50f
        WizardStep.RemoteId, WizardStep.Proxy -> 0.70f
        WizardStep.TestingRemote -> 0.80f
        WizardStep.Save -> 1.0f
    }
}

// ============================================================================
// State and Actions
// ============================================================================

/**
 * Complete state for the wizard.
 */
data class WizardState(
    val currentStep: WizardStep = WizardStep.Welcome,
    val isEditMode: Boolean = false,
    val isNextEnabled: Boolean = true,

    // Server data
    val serverName: String = "",
    val setAsDefault: Boolean = false,
    val isMusicAssistant: Boolean = false,

    // Local connection
    val localAddress: String = "",
    val discoveredServers: List<DiscoveredServerUi> = emptyList(),
    val isSearching: Boolean = false,
    val localTestState: ConnectionTestState = ConnectionTestState.Idle,

    // Remote access
    val remoteAccessMethod: RemoteAccessMethod = RemoteAccessMethod.NONE,
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
    // Welcome step
    data object SetupMyServer : WizardStepAction()
    data object FindOtherServers : WizardStepAction()

    // Find server step
    data class UpdateLocalAddress(val address: String) : WizardStepAction()
    data class SelectDiscoveredServer(val server: DiscoveredServerUi) : WizardStepAction()
    data class UpdateIsMusicAssistant(val isMusicAssistant: Boolean) : WizardStepAction()
    data object StartDiscovery : WizardStepAction()
    data object RetryLocalTest : WizardStepAction()

    // MA Login step
    data class UpdateMaUsername(val username: String) : WizardStepAction()
    data class UpdateMaPassword(val password: String) : WizardStepAction()
    data class UpdateMaPort(val port: Int) : WizardStepAction()
    data object TestMaConnection : WizardStepAction()

    // Remote choice step
    data class SelectRemoteMethod(val method: RemoteAccessMethod) : WizardStepAction()

    // Remote ID step
    data class UpdateRemoteId(val id: String) : WizardStepAction()
    data object ScanQrCode : WizardStepAction()
    data object RetryRemoteTest : WizardStepAction()

    // Proxy step
    data class UpdateProxyUrl(val url: String) : WizardStepAction()
    data class UpdateProxyAuthMode(val mode: ProxyAuthMode) : WizardStepAction()
    data class UpdateProxyUsername(val username: String) : WizardStepAction()
    data class UpdateProxyPassword(val password: String) : WizardStepAction()
    data class UpdateProxyToken(val token: String) : WizardStepAction()

    // Remote only warning
    data object AcknowledgeRemoteOnlyWarning : WizardStepAction()

    // Save step
    data class UpdateServerName(val name: String) : WizardStepAction()
    data class UpdateSetAsDefault(val isDefault: Boolean) : WizardStepAction()
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true)
@Composable
private fun WizardWelcomePreview() {
    SendSpinTheme {
        AddServerWizardScreen(
            state = WizardState(currentStep = WizardStep.Welcome),
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
                currentStep = WizardStep.FindServer,
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
