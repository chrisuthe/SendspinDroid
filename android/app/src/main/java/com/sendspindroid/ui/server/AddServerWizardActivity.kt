package com.sendspindroid.ui.server

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.sendspindroid.R
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.databinding.ActivityAddServerWizardBinding
import com.sendspindroid.databinding.ItemConnectionMethodBinding
import com.sendspindroid.databinding.ItemDiscoveredServerBinding
import com.sendspindroid.databinding.WizardStepFindServerBinding
import com.sendspindroid.databinding.WizardStepMaLoginBinding
import com.sendspindroid.databinding.WizardStepProxyConfigBinding
import com.sendspindroid.databinding.WizardStepRemoteChoiceBinding
import com.sendspindroid.databinding.WizardStepRemoteIdBinding
import com.sendspindroid.databinding.WizardStepRemoteOnlyWarningBinding
import com.sendspindroid.databinding.WizardStepSaveBinding
import com.sendspindroid.databinding.WizardStepTestingBinding
import com.sendspindroid.databinding.WizardStepWelcomeBinding
import com.sendspindroid.discovery.NsdDiscoveryManager
import com.sendspindroid.model.ConnectionPreference
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.ProxyConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.MaSettings
import com.sendspindroid.remote.RemoteConnection
import com.sendspindroid.sendspin.MusicAssistantAuth
import com.sendspindroid.ui.remote.QrScannerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Full-screen wizard Activity for adding or editing unified servers.
 *
 * Uses a state-machine based flow that progressively shows relevant steps:
 *
 * WELCOME -> FIND_SERVER -> [MA_LOGIN] -> REMOTE_CHOICE -> [REMOTE_ID | PROXY] -> SAVE
 *
 * Key features:
 * - Inline connection testing at each step
 * - Contextual MA Login (only shown when needed)
 * - Remote-only warning when skipping local connection
 * - Dynamic step visibility based on user choices
 */
class AddServerWizardActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AddServerWizardActivity"

        // Intent extras
        const val EXTRA_EDIT_SERVER_ID = "edit_server_id"
        const val EXTRA_DISCOVERY_MODE = "discovery_mode"

        // Result extras
        const val RESULT_SERVER_ID = "server_id"
    }

    private lateinit var binding: ActivityAddServerWizardBinding
    private val viewModel: AddServerWizardViewModel by viewModels()

    // Discovery manager for mDNS
    private var discoveryManager: NsdDiscoveryManager? = null
    private val discoveredServers = mutableMapOf<String, DiscoveredServer>()

    private data class DiscoveredServer(val name: String, val address: String, val path: String)

    // Current step bindings (only one active at a time)
    private var welcomeBinding: WizardStepWelcomeBinding? = null
    private var findServerBinding: WizardStepFindServerBinding? = null
    private var testingBinding: WizardStepTestingBinding? = null
    private var maLoginBinding: WizardStepMaLoginBinding? = null
    private var remoteChoiceBinding: WizardStepRemoteChoiceBinding? = null
    private var remoteIdBinding: WizardStepRemoteIdBinding? = null
    private var proxyBinding: WizardStepProxyConfigBinding? = null
    private var remoteOnlyWarningBinding: WizardStepRemoteOnlyWarningBinding? = null
    private var saveBinding: WizardStepSaveBinding? = null

    // TV device detection (for QR scanner)
    private val isTvDevice: Boolean by lazy {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAddServerWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle edit mode
        if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_EDIT_SERVER_ID)?.let { serverId ->
                UnifiedServerRepository.getServer(serverId)?.let { server ->
                    val existingMaToken = if (server.isMusicAssistant) {
                        MaSettings.getTokenForServer(server.id)
                    } else null
                    viewModel.initForEdit(server, existingMaToken)
                }
            }
        }

        setupToolbar()
        setupBackPressHandler()
        observeWizardState()

        // Initialize discovery manager
        discoveryManager = NsdDiscoveryManager(this, discoveryListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryManager?.cleanup()
        discoveryManager = null
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        if (viewModel.isEditMode) {
            binding.toolbar.title = getString(R.string.edit_server)
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.onBack() == null) {
                    finish()
                }
            }
        })
    }

    private fun observeWizardState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.currentStep.collect { step ->
                    showStep(step)
                    updateNavigation(step)
                }
            }
        }
    }

    private fun showStep(step: AddServerWizardViewModel.WizardStep) {
        // Clear existing step view
        binding.stepContainer.removeAllViews()

        // Inflate and bind the appropriate step
        when (step) {
            AddServerWizardViewModel.WizardStep.Welcome -> showWelcomeStep()
            AddServerWizardViewModel.WizardStep.FindServer -> showFindServerStep()
            AddServerWizardViewModel.WizardStep.TestingLocal -> showTestingStep(isLocal = true)
            AddServerWizardViewModel.WizardStep.MaLogin -> showMaLoginStep()
            AddServerWizardViewModel.WizardStep.RemoteChoice -> showRemoteChoiceStep()
            AddServerWizardViewModel.WizardStep.RemoteId -> showRemoteIdStep()
            AddServerWizardViewModel.WizardStep.Proxy -> showProxyStep()
            AddServerWizardViewModel.WizardStep.TestingRemote -> showTestingStep(isLocal = false)
            AddServerWizardViewModel.WizardStep.RemoteOnlyWarning -> showRemoteOnlyWarningStep()
            AddServerWizardViewModel.WizardStep.Save -> showSaveStep()
        }
    }

    private fun updateNavigation(step: AddServerWizardViewModel.WizardStep) {
        // Update toolbar title based on step
        binding.toolbar.title = when (step) {
            AddServerWizardViewModel.WizardStep.Welcome -> getString(R.string.wizard_title_add_server)
            AddServerWizardViewModel.WizardStep.FindServer -> getString(R.string.wizard_find_server_title)
            AddServerWizardViewModel.WizardStep.TestingLocal,
            AddServerWizardViewModel.WizardStep.TestingRemote -> getString(R.string.wizard_testing_title)
            AddServerWizardViewModel.WizardStep.MaLogin -> getString(R.string.wizard_ma_login_title)
            AddServerWizardViewModel.WizardStep.RemoteChoice -> getString(R.string.wizard_remote_choice_title)
            AddServerWizardViewModel.WizardStep.RemoteId -> getString(R.string.wizard_remote_title)
            AddServerWizardViewModel.WizardStep.Proxy -> getString(R.string.wizard_proxy_title)
            AddServerWizardViewModel.WizardStep.RemoteOnlyWarning -> getString(R.string.wizard_remote_only_title)
            AddServerWizardViewModel.WizardStep.Save -> getString(R.string.wizard_save_title)
        }

        // Update bottom navigation buttons
        val isFirstStep = step == AddServerWizardViewModel.WizardStep.Welcome
        val isTestingStep = step == AddServerWizardViewModel.WizardStep.TestingLocal ||
                           step == AddServerWizardViewModel.WizardStep.TestingRemote
        val isFinalStep = step == AddServerWizardViewModel.WizardStep.Save

        // Back button
        binding.backButton.visibility = if (isFirstStep || isTestingStep) View.INVISIBLE else View.VISIBLE

        // Skip button - show on Find Server step
        binding.skipButton.visibility = if (step == AddServerWizardViewModel.WizardStep.FindServer) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.skipButton.setOnClickListener {
            viewModel.onSkipLocal()
        }

        // Next button
        binding.nextButton.visibility = if (isTestingStep) View.GONE else View.VISIBLE
        binding.nextButton.text = if (isFinalStep) {
            getString(R.string.wizard_save)
        } else {
            getString(R.string.wizard_next)
        }
        binding.nextButton.setOnClickListener {
            onNextClicked()
        }

        binding.backButton.setOnClickListener {
            viewModel.onBack()
        }

        // Update progress indicator
        updateProgress(step)
    }

    private fun updateProgress(step: AddServerWizardViewModel.WizardStep) {
        val progress = when (step) {
            AddServerWizardViewModel.WizardStep.Welcome -> 10
            AddServerWizardViewModel.WizardStep.FindServer -> 25
            AddServerWizardViewModel.WizardStep.TestingLocal -> 30
            AddServerWizardViewModel.WizardStep.MaLogin -> 45
            AddServerWizardViewModel.WizardStep.RemoteChoice -> 55
            AddServerWizardViewModel.WizardStep.RemoteOnlyWarning -> 50
            AddServerWizardViewModel.WizardStep.RemoteId -> 70
            AddServerWizardViewModel.WizardStep.Proxy -> 70
            AddServerWizardViewModel.WizardStep.TestingRemote -> 80
            AddServerWizardViewModel.WizardStep.Save -> 100
        }
        binding.progressIndicator.progress = progress
    }

    private fun onNextClicked() {
        when (viewModel.currentStep.value) {
            AddServerWizardViewModel.WizardStep.Welcome -> {
                viewModel.onNext()
            }
            AddServerWizardViewModel.WizardStep.FindServer -> {
                collectFindServerData()
                if (validateFindServer()) {
                    startLocalConnectionTest()
                }
            }
            AddServerWizardViewModel.WizardStep.MaLogin -> {
                collectMaLoginData()
                // If already authenticated, proceed directly
                if (viewModel.maToken != null) {
                    viewModel.onNext()
                } else {
                    // Otherwise, test connection automatically when clicking Next
                    startMaConnectionTest()
                }
            }
            AddServerWizardViewModel.WizardStep.RemoteChoice -> {
                collectRemoteChoiceData()
                viewModel.onNext()
            }
            AddServerWizardViewModel.WizardStep.RemoteId -> {
                collectRemoteIdData()
                if (validateRemoteId()) {
                    if (viewModel.remoteId.isNotBlank()) {
                        startRemoteConnectionTest()
                    } else {
                        viewModel.navigateTo(AddServerWizardViewModel.WizardStep.Save)
                    }
                }
            }
            AddServerWizardViewModel.WizardStep.Proxy -> {
                collectProxyData()
                if (validateProxy()) {
                    if (viewModel.proxyUrl.isNotBlank()) {
                        startProxyConnectionTest()
                    } else {
                        viewModel.navigateTo(AddServerWizardViewModel.WizardStep.Save)
                    }
                }
            }
            AddServerWizardViewModel.WizardStep.RemoteOnlyWarning -> {
                viewModel.onNext()
            }
            AddServerWizardViewModel.WizardStep.Save -> {
                collectSaveData()
                if (validateSave()) {
                    attemptSave()
                }
            }
            else -> { /* Handled by testing step */ }
        }
    }

    // ========================================================================
    // Step: Welcome
    // ========================================================================

    private fun showWelcomeStep() {
        val view = layoutInflater.inflate(R.layout.wizard_step_welcome, binding.stepContainer, false)
        welcomeBinding = WizardStepWelcomeBinding.bind(view)
        binding.stepContainer.addView(view)

        welcomeBinding?.setupServerCard?.setOnClickListener {
            viewModel.onNext()
        }

        welcomeBinding?.findServersCard?.setOnClickListener {
            // Discovery mode - skip to find server
            viewModel.onNext()
        }

        // Hide bottom navigation on welcome step (use cards instead)
        binding.bottomNavigationBar.visibility = View.GONE
    }

    // ========================================================================
    // Step: Find Server
    // ========================================================================

    private fun showFindServerStep() {
        binding.bottomNavigationBar.visibility = View.VISIBLE

        val view = layoutInflater.inflate(R.layout.wizard_step_find_server, binding.stepContainer, false)
        findServerBinding = WizardStepFindServerBinding.bind(view)
        binding.stepContainer.addView(view)

        // Restore existing data
        findServerBinding?.addressInput?.setText(viewModel.localAddress)
        findServerBinding?.isMusicAssistantCheckbox?.isChecked = viewModel.isMusicAssistant

        // Start discovery
        startDiscovery()

        // Handle retry button in status card
        findServerBinding?.retryButton?.setOnClickListener {
            viewModel.resetLocalTest()
            findServerBinding?.connectionStatusCard?.visibility = View.GONE
        }
    }

    private fun startDiscovery() {
        discoveredServers.clear()
        findServerBinding?.scanningContainer?.visibility = View.VISIBLE
        findServerBinding?.noServersFoundText?.visibility = View.GONE
        findServerBinding?.discoveredServersContainer?.removeAllViews()
        discoveryManager?.startDiscovery()

        // Show "no servers" after timeout
        lifecycleScope.launch {
            delay(5000)
            if (discoveredServers.isEmpty() && findServerBinding != null) {
                findServerBinding?.scanningContainer?.visibility = View.GONE
                findServerBinding?.noServersFoundText?.visibility = View.VISIBLE
            }
        }
    }

    private val discoveryListener = object : NsdDiscoveryManager.DiscoveryListener {
        override fun onServerDiscovered(name: String, address: String, path: String) {
            runOnUiThread {
                val server = DiscoveredServer(name, address, path)
                discoveredServers[name] = server
                addDiscoveredServerCard(server)
            }
        }

        override fun onServerLost(name: String) {
            runOnUiThread {
                discoveredServers.remove(name)
                rebuildDiscoveredServersList()
            }
        }

        override fun onDiscoveryStarted() {
            runOnUiThread {
                findServerBinding?.scanningContainer?.visibility = View.VISIBLE
            }
        }

        override fun onDiscoveryStopped() {
            runOnUiThread {
                findServerBinding?.scanningContainer?.visibility = View.GONE
                if (discoveredServers.isEmpty()) {
                    findServerBinding?.noServersFoundText?.visibility = View.VISIBLE
                }
            }
        }

        override fun onDiscoveryError(error: String) {
            runOnUiThread {
                findServerBinding?.scanningContainer?.visibility = View.GONE
            }
        }
    }

    private fun addDiscoveredServerCard(server: DiscoveredServer) {
        val container = findServerBinding?.discoveredServersContainer ?: return

        val itemBinding = ItemDiscoveredServerBinding.inflate(layoutInflater, container, false)
        itemBinding.serverName.text = server.name
        itemBinding.serverAddress.text = server.address

        itemBinding.root.setOnClickListener {
            // Select this server
            viewModel.applyDiscoveredServer(server.name, server.address)
            findServerBinding?.addressInput?.setText(server.address)

            // Update selection indicators
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                val childCard = child as? MaterialCardView
                val indicator = child.findViewById<View>(R.id.selectedIndicator)
                indicator?.visibility = View.GONE
                childCard?.strokeColor = ContextCompat.getColor(this, com.google.android.material.R.color.material_on_surface_stroke)
            }
            itemBinding.selectedIndicator.visibility = View.VISIBLE
            itemBinding.root.strokeColor = ContextCompat.getColor(this, com.google.android.material.R.color.design_default_color_primary)
        }

        container.addView(itemBinding.root)
        container.visibility = View.VISIBLE
    }

    private fun rebuildDiscoveredServersList() {
        findServerBinding?.discoveredServersContainer?.removeAllViews()
        discoveredServers.values.forEach { server ->
            addDiscoveredServerCard(server)
        }
        if (discoveredServers.isEmpty()) {
            findServerBinding?.discoveredServersContainer?.visibility = View.GONE
        }
    }

    private fun collectFindServerData() {
        viewModel.localAddress = findServerBinding?.addressInput?.text?.toString()?.trim() ?: ""
        viewModel.isMusicAssistant = findServerBinding?.isMusicAssistantCheckbox?.isChecked ?: false
    }

    private fun validateFindServer(): Boolean {
        val address = viewModel.localAddress
        if (address.isBlank()) {
            findServerBinding?.addressInputLayout?.error = getString(R.string.wizard_name_required)
            return false
        }
        if (!isValidAddress(address)) {
            findServerBinding?.addressInputLayout?.error = getString(R.string.invalid_address)
            return false
        }
        findServerBinding?.addressInputLayout?.error = null
        return true
    }

    private fun isValidAddress(address: String): Boolean {
        val parts = address.split(":")
        if (parts.isEmpty() || parts.size > 2) return false
        if (parts[0].isBlank()) return false
        if (parts.size == 2 && parts[1].toIntOrNull() == null) return false
        return true
    }

    // ========================================================================
    // Step: Testing Connection
    // ========================================================================

    private fun showTestingStep(isLocal: Boolean) {
        binding.bottomNavigationBar.visibility = View.GONE

        val view = layoutInflater.inflate(R.layout.wizard_step_testing, binding.stepContainer, false)
        testingBinding = WizardStepTestingBinding.bind(view)
        binding.stepContainer.addView(view)

        // Set initial state
        testingBinding?.testingProgressIndicator?.visibility = View.VISIBLE
        testingBinding?.testingSuccessIcon?.visibility = View.GONE
        testingBinding?.testingErrorIcon?.visibility = View.GONE
        testingBinding?.testingButtonsContainer?.visibility = View.GONE
        testingBinding?.testingErrorDetails?.visibility = View.GONE

        val statusText = if (isLocal) {
            getString(R.string.wizard_testing_local) + "\n" + viewModel.localAddress
        } else {
            when (viewModel.remoteAccessMethod.value) {
                AddServerWizardViewModel.RemoteAccessMethod.REMOTE_ID ->
                    getString(R.string.wizard_testing_remote)
                AddServerWizardViewModel.RemoteAccessMethod.PROXY ->
                    getString(R.string.wizard_testing_proxy)
                else -> getString(R.string.wizard_testing_local)
            }
        }
        testingBinding?.testingStatusText?.text = statusText
    }

    private fun startLocalConnectionTest() {
        viewModel.navigateTo(AddServerWizardViewModel.WizardStep.TestingLocal)

        lifecycleScope.launch {
            delay(500) // Brief delay for UI to show

            val result = testLocalConnection(viewModel.localAddress)

            result.fold(
                onSuccess = { responseCode ->
                    showTestSuccess()
                    delay(1000) // Brief success display
                    viewModel.onLocalTestSuccess()
                },
                onFailure = { error ->
                    Log.e(TAG, "Local connection test failed", error)
                    showTestFailure(error.message ?: "Unknown error")
                }
            )
        }
    }

    /**
     * Test connection to a local server address.
     * Returns Result.success with HTTP response code on success,
     * or Result.failure with the actual exception on failure.
     */
    private suspend fun testLocalConnection(address: String): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val url = if (address.contains(":")) {
                    "http://$address/sendspin"
                } else {
                    "http://$address:8927/sendspin"
                }

                Log.d(TAG, "Testing connection to: $url")

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = okhttp3.Request.Builder()
                    .url(url)
                    .head()
                    .build()

                val response = client.newCall(request).execute()
                val code = response.code
                Log.d(TAG, "Connection test response: $code")

                // Any HTTP response means the server is reachable.
                // The /sendspin endpoint is a WebSocket endpoint, so it may return
                // various codes (426 Upgrade Required, 500, etc.) for plain HTTP requests.
                // What matters is that we got a response - the actual WebSocket
                // connection will handle the proper upgrade negotiation.
                Result.success(code)
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "Connection refused", e)
                Result.failure(Exception("Connection refused - is the server running?"))
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "Unknown host", e)
                Result.failure(Exception("Cannot resolve hostname: ${e.message}"))
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Connection timeout", e)
                Result.failure(Exception("Connection timed out - check the address"))
            } catch (e: Exception) {
                Log.e(TAG, "Connection test error", e)
                Result.failure(e)
            }
        }
    }

    private fun startRemoteConnectionTest() {
        viewModel.navigateTo(AddServerWizardViewModel.WizardStep.TestingRemote)

        lifecycleScope.launch {
            delay(500)

            // For Remote ID, we just validate the format for now
            // Real connection test would require connecting to relay service
            val parsedId = RemoteConnection.parseRemoteId(viewModel.remoteId)
            if (parsedId != null) {
                showTestSuccess()
                delay(1000)
                viewModel.onRemoteTestSuccess()
            } else {
                showTestFailure("Invalid Remote ID format")
            }
        }
    }

    private fun startProxyConnectionTest() {
        viewModel.navigateTo(AddServerWizardViewModel.WizardStep.TestingRemote)

        lifecycleScope.launch {
            delay(500)

            try {
                val normalizedUrl = viewModel.normalizeProxyUrl(viewModel.proxyUrl)

                if (viewModel.proxyAuthMode == AddServerWizardViewModel.AUTH_LOGIN) {
                    // Test login
                    val result = MusicAssistantAuth.login(
                        normalizedUrl,
                        viewModel.proxyUsername,
                        viewModel.proxyPassword
                    )
                    viewModel.proxyToken = result.accessToken
                }

                showTestSuccess()
                delay(1000)
                viewModel.onRemoteTestSuccess()
            } catch (e: MusicAssistantAuth.AuthenticationException) {
                showTestFailure(getString(R.string.login_invalid_credentials))
            } catch (e: IOException) {
                showTestFailure(getString(R.string.error_network))
            } catch (e: Exception) {
                showTestFailure(e.message ?: "Connection failed")
            }
        }
    }

    private fun showTestSuccess() {
        testingBinding?.testingProgressIndicator?.visibility = View.GONE
        testingBinding?.testingSuccessIcon?.visibility = View.VISIBLE
        testingBinding?.testingStatusText?.text = getString(R.string.wizard_find_server_success)
        testingBinding?.testingStatusText?.setTextColor(
            ContextCompat.getColor(this, android.R.color.holo_green_dark)
        )
    }

    private fun showTestFailure(error: String) {
        testingBinding?.testingProgressIndicator?.visibility = View.GONE
        testingBinding?.testingErrorIcon?.visibility = View.VISIBLE
        testingBinding?.testingStatusText?.text = getString(R.string.wizard_find_server_failed, "")
        testingBinding?.testingStatusText?.setTextColor(
            ContextCompat.getColor(this, android.R.color.holo_red_dark)
        )
        testingBinding?.testingErrorDetails?.visibility = View.VISIBLE
        testingBinding?.testingErrorDetails?.text = error

        // Show retry buttons - stays on this step, user can choose to go back or skip
        testingBinding?.testingButtonsContainer?.visibility = View.VISIBLE
        testingBinding?.testingRetryButton?.setOnClickListener {
            // Go back to Find Server to try a different address
            viewModel.onBack()
        }
        testingBinding?.testingSkipButton?.setOnClickListener {
            // Skip local connection and proceed to remote-only warning
            viewModel.onSkipLocal()
        }

        // Note: We do NOT call viewModel.onLocalTestFailed() here because that would
        // immediately navigate away. Instead, we stay on this step and let the user
        // decide what to do via the buttons above.
    }

    // ========================================================================
    // Step: MA Login
    // ========================================================================

    private fun showMaLoginStep() {
        binding.bottomNavigationBar.visibility = View.VISIBLE

        val view = layoutInflater.inflate(R.layout.wizard_step_ma_login, binding.stepContainer, false)
        maLoginBinding = WizardStepMaLoginBinding.bind(view)
        binding.stepContainer.addView(view)

        // Restore existing data
        maLoginBinding?.maUsernameInput?.setText(viewModel.maUsername)
        maLoginBinding?.maPortInput?.setText(viewModel.maPort.toString())

        // Setup test button
        maLoginBinding?.testConnectionButton?.setOnClickListener {
            collectMaLoginData()
            testMaConnection()
        }

        // Observe test state
        lifecycleScope.launch {
            viewModel.maTestState.collect { state ->
                updateMaTestStatus(state)
            }
        }
    }

    private fun collectMaLoginData() {
        viewModel.maUsername = maLoginBinding?.maUsernameInput?.text?.toString()?.trim() ?: ""
        viewModel.maPassword = maLoginBinding?.maPasswordInput?.text?.toString() ?: ""
        viewModel.maPort = maLoginBinding?.maPortInput?.text?.toString()?.toIntOrNull()
            ?: MaSettings.getDefaultPort()
    }

    private fun testMaConnection() {
        viewModel.testMaConnection { success ->
            // State is updated via Flow, no action needed here
        }
    }

    /**
     * Test MA connection triggered by clicking Next button.
     * Shows loading state and auto-proceeds on success.
     */
    private fun startMaConnectionTest() {
        // Disable Next button during test
        binding.nextButton.isEnabled = false
        binding.nextButton.text = getString(R.string.wizard_ma_testing)

        viewModel.testMaConnection { success ->
            runOnUiThread {
                // Re-enable Next button
                binding.nextButton.isEnabled = true
                binding.nextButton.text = getString(R.string.wizard_next)

                if (success) {
                    // Auto-proceed to next step on success
                    viewModel.onNext()
                }
                // On failure, the error is shown via the maTestState observer
            }
        }
    }

    private fun updateMaTestStatus(state: AddServerWizardViewModel.ConnectionTestState) {
        maLoginBinding?.connectionStatusContainer?.visibility = View.VISIBLE

        when (state) {
            AddServerWizardViewModel.ConnectionTestState.Idle -> {
                maLoginBinding?.connectionStatusContainer?.visibility = View.GONE
            }
            AddServerWizardViewModel.ConnectionTestState.Testing -> {
                maLoginBinding?.connectionProgress?.visibility = View.VISIBLE
                maLoginBinding?.connectionStatusIcon?.visibility = View.GONE
                maLoginBinding?.connectionStatusText?.text = getString(R.string.wizard_ma_testing)
                maLoginBinding?.connectionStatusText?.setTextColor(
                    ContextCompat.getColor(this, android.R.color.darker_gray)
                )
            }
            is AddServerWizardViewModel.ConnectionTestState.Success -> {
                maLoginBinding?.connectionProgress?.visibility = View.GONE
                maLoginBinding?.connectionStatusIcon?.visibility = View.VISIBLE
                maLoginBinding?.connectionStatusIcon?.setImageResource(R.drawable.ic_check_circle)
                maLoginBinding?.connectionStatusIcon?.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                maLoginBinding?.connectionStatusText?.text = state.message
                maLoginBinding?.connectionStatusText?.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_green_dark)
                )
            }
            is AddServerWizardViewModel.ConnectionTestState.Failed -> {
                maLoginBinding?.connectionProgress?.visibility = View.GONE
                maLoginBinding?.connectionStatusIcon?.visibility = View.VISIBLE
                maLoginBinding?.connectionStatusIcon?.setImageResource(R.drawable.ic_error)
                maLoginBinding?.connectionStatusIcon?.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                maLoginBinding?.connectionStatusText?.text = state.error
                maLoginBinding?.connectionStatusText?.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_red_dark)
                )
            }
        }
    }

    // ========================================================================
    // Step: Remote Choice
    // ========================================================================

    private fun showRemoteChoiceStep() {
        binding.bottomNavigationBar.visibility = View.VISIBLE

        val view = layoutInflater.inflate(R.layout.wizard_step_remote_choice, binding.stepContainer, false)
        remoteChoiceBinding = WizardStepRemoteChoiceBinding.bind(view)
        binding.stepContainer.addView(view)

        // Restore existing selection
        when (viewModel.remoteAccessMethod.value) {
            AddServerWizardViewModel.RemoteAccessMethod.NONE -> {
                remoteChoiceBinding?.radioNone?.isChecked = true
                updateRemoteChoiceCardSelection(remoteChoiceBinding?.choiceNoneCard)
            }
            AddServerWizardViewModel.RemoteAccessMethod.REMOTE_ID -> {
                remoteChoiceBinding?.radioRemoteId?.isChecked = true
                updateRemoteChoiceCardSelection(remoteChoiceBinding?.choiceRemoteIdCard)
            }
            AddServerWizardViewModel.RemoteAccessMethod.PROXY -> {
                remoteChoiceBinding?.radioProxy?.isChecked = true
                updateRemoteChoiceCardSelection(remoteChoiceBinding?.choiceProxyCard)
            }
        }

        // Setup card click handlers - each selection must uncheck the others
        remoteChoiceBinding?.choiceNoneCard?.setOnClickListener {
            selectRemoteChoice(AddServerWizardViewModel.RemoteAccessMethod.NONE)
        }
        remoteChoiceBinding?.choiceRemoteIdCard?.setOnClickListener {
            selectRemoteChoice(AddServerWizardViewModel.RemoteAccessMethod.REMOTE_ID)
        }
        remoteChoiceBinding?.choiceProxyCard?.setOnClickListener {
            selectRemoteChoice(AddServerWizardViewModel.RemoteAccessMethod.PROXY)
        }

        // Radio buttons should also trigger selection (for accessibility)
        remoteChoiceBinding?.radioNone?.setOnClickListener {
            selectRemoteChoice(AddServerWizardViewModel.RemoteAccessMethod.NONE)
        }
        remoteChoiceBinding?.radioRemoteId?.setOnClickListener {
            selectRemoteChoice(AddServerWizardViewModel.RemoteAccessMethod.REMOTE_ID)
        }
        remoteChoiceBinding?.radioProxy?.setOnClickListener {
            selectRemoteChoice(AddServerWizardViewModel.RemoteAccessMethod.PROXY)
        }
    }

    /**
     * Select a remote access method, ensuring mutual exclusivity.
     */
    private fun selectRemoteChoice(method: AddServerWizardViewModel.RemoteAccessMethod) {
        // Uncheck all radio buttons first
        remoteChoiceBinding?.radioNone?.isChecked = false
        remoteChoiceBinding?.radioRemoteId?.isChecked = false
        remoteChoiceBinding?.radioProxy?.isChecked = false

        // Check the selected one and update card styling
        when (method) {
            AddServerWizardViewModel.RemoteAccessMethod.NONE -> {
                remoteChoiceBinding?.radioNone?.isChecked = true
                updateRemoteChoiceCardSelection(remoteChoiceBinding?.choiceNoneCard)
            }
            AddServerWizardViewModel.RemoteAccessMethod.REMOTE_ID -> {
                remoteChoiceBinding?.radioRemoteId?.isChecked = true
                updateRemoteChoiceCardSelection(remoteChoiceBinding?.choiceRemoteIdCard)
            }
            AddServerWizardViewModel.RemoteAccessMethod.PROXY -> {
                remoteChoiceBinding?.radioProxy?.isChecked = true
                updateRemoteChoiceCardSelection(remoteChoiceBinding?.choiceProxyCard)
            }
        }
    }

    private fun updateRemoteChoiceCardSelection(selectedCard: MaterialCardView?) {
        val primaryColor = ContextCompat.getColor(this, com.google.android.material.R.color.design_default_color_primary)
        val outlineColor = ContextCompat.getColor(this, com.google.android.material.R.color.material_on_surface_stroke)

        remoteChoiceBinding?.choiceNoneCard?.strokeColor = outlineColor
        remoteChoiceBinding?.choiceNoneCard?.strokeWidth = 1
        remoteChoiceBinding?.choiceRemoteIdCard?.strokeColor = outlineColor
        remoteChoiceBinding?.choiceRemoteIdCard?.strokeWidth = 1
        remoteChoiceBinding?.choiceProxyCard?.strokeColor = outlineColor
        remoteChoiceBinding?.choiceProxyCard?.strokeWidth = 1

        selectedCard?.strokeColor = primaryColor
        selectedCard?.strokeWidth = 2
    }

    private fun collectRemoteChoiceData() {
        viewModel.setRemoteMethod(
            when {
                remoteChoiceBinding?.radioRemoteId?.isChecked == true ->
                    AddServerWizardViewModel.RemoteAccessMethod.REMOTE_ID
                remoteChoiceBinding?.radioProxy?.isChecked == true ->
                    AddServerWizardViewModel.RemoteAccessMethod.PROXY
                else -> AddServerWizardViewModel.RemoteAccessMethod.NONE
            }
        )
    }

    // ========================================================================
    // Step: Remote ID
    // ========================================================================

    private fun showRemoteIdStep() {
        binding.bottomNavigationBar.visibility = View.VISIBLE

        val view = layoutInflater.inflate(R.layout.wizard_step_remote_id, binding.stepContainer, false)
        remoteIdBinding = WizardStepRemoteIdBinding.bind(view)
        binding.stepContainer.addView(view)

        // Restore existing data
        if (viewModel.remoteId.isNotBlank()) {
            remoteIdBinding?.remoteIdInput?.setText(RemoteConnection.formatRemoteId(viewModel.remoteId))
        }

        // Auto-format as user types
        var isFormatting = false
        remoteIdBinding?.remoteIdInput?.addTextChangedListener(object : TextWatcher {
            private var lastLength = 0

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                lastLength = s?.length ?: 0
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || s == null) return

                isFormatting = true

                val cleaned = s.toString().uppercase().filter { it.isLetterOrDigit() }
                val formatted = cleaned.chunked(5).joinToString("-")

                if (formatted != s.toString()) {
                    val cursorPos = remoteIdBinding?.remoteIdInput?.selectionEnd ?: 0
                    s.replace(0, s.length, formatted)
                    val newPos = minOf(cursorPos + (formatted.length - lastLength), formatted.length)
                    remoteIdBinding?.remoteIdInput?.setSelection(maxOf(0, newPos))
                }

                isFormatting = false
            }
        })

        // QR scanner button - hide on TV
        if (isTvDevice) {
            remoteIdBinding?.remoteIdInputLayout?.isEndIconVisible = false
        } else {
            remoteIdBinding?.remoteIdInputLayout?.setEndIconOnClickListener {
                QrScannerDialog.show(supportFragmentManager) { scannedId ->
                    remoteIdBinding?.remoteIdInput?.setText(RemoteConnection.formatRemoteId(scannedId))
                }
            }
        }
    }

    private fun collectRemoteIdData() {
        val input = remoteIdBinding?.remoteIdInput?.text?.toString() ?: ""
        viewModel.remoteId = RemoteConnection.parseRemoteId(input) ?: input.filter { it.isLetterOrDigit() }
    }

    private fun validateRemoteId(): Boolean {
        if (viewModel.remoteId.isNotBlank() && RemoteConnection.parseRemoteId(viewModel.remoteId) == null) {
            remoteIdBinding?.remoteIdInputLayout?.error = getString(R.string.remote_id_invalid)
            return false
        }
        remoteIdBinding?.remoteIdInputLayout?.error = null
        return true
    }

    // ========================================================================
    // Step: Proxy
    // ========================================================================

    private fun showProxyStep() {
        binding.bottomNavigationBar.visibility = View.VISIBLE

        val view = layoutInflater.inflate(R.layout.wizard_step_proxy_config, binding.stepContainer, false)
        proxyBinding = WizardStepProxyConfigBinding.bind(view)
        binding.stepContainer.addView(view)

        // Restore existing data
        proxyBinding?.urlInput?.setText(viewModel.proxyUrl)
        proxyBinding?.usernameInput?.setText(viewModel.proxyUsername)
        proxyBinding?.tokenInput?.setText(viewModel.proxyToken)

        proxyBinding?.authModeTabs?.selectTab(
            proxyBinding?.authModeTabs?.getTabAt(viewModel.proxyAuthMode)
        )
        updateProxyAuthModeVisibility()

        proxyBinding?.authModeTabs?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                viewModel.proxyAuthMode = tab?.position ?: AddServerWizardViewModel.AUTH_LOGIN
                updateProxyAuthModeVisibility()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun updateProxyAuthModeVisibility() {
        when (viewModel.proxyAuthMode) {
            AddServerWizardViewModel.AUTH_LOGIN -> {
                proxyBinding?.loginModeContainer?.visibility = View.VISIBLE
                proxyBinding?.tokenModeContainer?.visibility = View.GONE
            }
            AddServerWizardViewModel.AUTH_TOKEN -> {
                proxyBinding?.loginModeContainer?.visibility = View.GONE
                proxyBinding?.tokenModeContainer?.visibility = View.VISIBLE
            }
        }
    }

    private fun collectProxyData() {
        viewModel.proxyUrl = proxyBinding?.urlInput?.text?.toString()?.trim() ?: ""
        viewModel.proxyAuthMode = proxyBinding?.authModeTabs?.selectedTabPosition
            ?: AddServerWizardViewModel.AUTH_LOGIN

        if (viewModel.proxyAuthMode == AddServerWizardViewModel.AUTH_LOGIN) {
            viewModel.proxyUsername = proxyBinding?.usernameInput?.text?.toString()?.trim() ?: ""
            viewModel.proxyPassword = proxyBinding?.passwordInput?.text?.toString() ?: ""
        } else {
            viewModel.proxyToken = proxyBinding?.tokenInput?.text?.toString()?.trim() ?: ""
        }
    }

    private fun validateProxy(): Boolean {
        if (viewModel.proxyUrl.isNotBlank()) {
            if (viewModel.proxyAuthMode == AddServerWizardViewModel.AUTH_TOKEN &&
                viewModel.proxyToken.isBlank()) {
                showError(getString(R.string.auth_token_required))
                return false
            }
            if (viewModel.proxyAuthMode == AddServerWizardViewModel.AUTH_LOGIN &&
                (viewModel.proxyUsername.isBlank() || viewModel.proxyPassword.isBlank())) {
                showError(getString(R.string.credentials_required))
                return false
            }
        }
        return true
    }

    // ========================================================================
    // Step: Remote Only Warning
    // ========================================================================

    private fun showRemoteOnlyWarningStep() {
        binding.bottomNavigationBar.visibility = View.VISIBLE

        val view = layoutInflater.inflate(R.layout.wizard_step_remote_only_warning, binding.stepContainer, false)
        remoteOnlyWarningBinding = WizardStepRemoteOnlyWarningBinding.bind(view)
        binding.stepContainer.addView(view)
    }

    // ========================================================================
    // Step: Save
    // ========================================================================

    private fun showSaveStep() {
        binding.bottomNavigationBar.visibility = View.VISIBLE

        val view = layoutInflater.inflate(R.layout.wizard_step_save, binding.stepContainer, false)
        saveBinding = WizardStepSaveBinding.bind(view)
        binding.stepContainer.addView(view)

        // Pre-fill name from discovery or existing
        saveBinding?.nameInput?.setText(
            viewModel.serverName.ifBlank { viewModel.discoveredServerName ?: "" }
        )
        saveBinding?.setAsDefaultCheckbox?.isChecked = viewModel.setAsDefault

        // Populate connection methods summary
        populateConnectionMethodsSummary()

        // Show MA status if applicable
        if (viewModel.isMusicAssistant) {
            saveBinding?.maStatusContainer?.visibility = View.VISIBLE
            if (viewModel.maToken != null) {
                saveBinding?.maStatusIcon?.setImageResource(R.drawable.ic_check_circle)
                saveBinding?.maStatusIcon?.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                saveBinding?.maStatusText?.text = getString(R.string.wizard_save_ma_authenticated)
                saveBinding?.maStatusText?.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_green_dark)
                )
            } else {
                saveBinding?.maStatusIcon?.setImageResource(R.drawable.ic_info)
                saveBinding?.maStatusIcon?.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.darker_gray))
                saveBinding?.maStatusText?.text = getString(R.string.wizard_save_ma_not_configured)
                saveBinding?.maStatusText?.setTextColor(
                    ContextCompat.getColor(this, android.R.color.darker_gray)
                )
            }
        } else {
            saveBinding?.maStatusContainer?.visibility = View.GONE
        }
    }

    private fun populateConnectionMethodsSummary() {
        val container = saveBinding?.connectionMethodsContainer ?: return
        container.removeAllViews()

        if (viewModel.localAddress.isNotBlank()) {
            addConnectionMethodItem(container, R.drawable.ic_wifi, "Local", viewModel.localAddress)
        }

        if (viewModel.remoteId.isNotBlank()) {
            val formatted = RemoteConnection.formatRemoteId(viewModel.remoteId)
            addConnectionMethodItem(container, R.drawable.ic_cloud_connected, "Remote ID", formatted)
        }

        if (viewModel.proxyUrl.isNotBlank()) {
            addConnectionMethodItem(container, R.drawable.ic_vpn_key, "Proxy", viewModel.proxyUrl)
        }

        if (container.childCount == 0) {
            // No connection methods configured - should not happen
            val textView = android.widget.TextView(this).apply {
                text = getString(R.string.no_connection_available)
                setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
            }
            container.addView(textView)
        }
    }

    private fun addConnectionMethodItem(container: LinearLayout, iconRes: Int, label: String, value: String) {
        val itemBinding = ItemConnectionMethodBinding.inflate(layoutInflater, container, false)
        itemBinding.methodIcon.setImageResource(iconRes)
        itemBinding.methodLabel.text = "$label:"
        itemBinding.methodValue.text = value
        container.addView(itemBinding.root)
    }

    private fun collectSaveData() {
        viewModel.serverName = saveBinding?.nameInput?.text?.toString()?.trim() ?: ""
        viewModel.setAsDefault = saveBinding?.setAsDefaultCheckbox?.isChecked ?: false
    }

    private fun validateSave(): Boolean {
        if (viewModel.serverName.isBlank()) {
            saveBinding?.nameInputLayout?.error = getString(R.string.wizard_name_required)
            return false
        }
        saveBinding?.nameInputLayout?.error = null

        if (!viewModel.hasValidConnectionMethod()) {
            showError(getString(R.string.wizard_at_least_one_method))
            return false
        }

        return true
    }

    private fun attemptSave() {
        val hasLocal = viewModel.localAddress.isNotBlank()
        val hasRemote = viewModel.remoteId.isNotBlank() &&
                        RemoteConnection.parseRemoteId(viewModel.remoteId) != null
        val hasProxy = viewModel.proxyUrl.isNotBlank()

        val parsedRemoteId = if (hasRemote) RemoteConnection.parseRemoteId(viewModel.remoteId) else null
        val serverId = viewModel.getServerId()

        val server = UnifiedServer(
            id = serverId,
            name = viewModel.serverName,
            lastConnectedMs = viewModel.editingServer?.lastConnectedMs ?: 0L,
            local = if (hasLocal) LocalConnection(
                address = viewModel.localAddress,
                path = "/sendspin"
            ) else null,
            remote = if (parsedRemoteId != null) com.sendspindroid.model.RemoteConnection(
                remoteId = parsedRemoteId
            ) else null,
            proxy = if (hasProxy) ProxyConnection(
                url = viewModel.normalizeProxyUrl(viewModel.proxyUrl),
                authToken = viewModel.proxyToken,
                username = if (viewModel.proxyAuthMode == AddServerWizardViewModel.AUTH_LOGIN)
                    viewModel.proxyUsername else null
            ) else null,
            connectionPreference = ConnectionPreference.AUTO,
            isDiscovered = false,
            isDefaultServer = viewModel.setAsDefault,
            isMusicAssistant = viewModel.isMusicAssistant
        )

        // Save to repository
        UnifiedServerRepository.saveServer(server)

        // Save MA token if we have one
        if (viewModel.isMusicAssistant && viewModel.maToken != null) {
            MaSettings.setTokenForServer(serverId, viewModel.maToken!!)
        } else if (!viewModel.isMusicAssistant) {
            MaSettings.clearTokenForServer(serverId)
        }

        // Update default server if needed
        if (viewModel.setAsDefault) {
            UnifiedServerRepository.setDefaultServer(serverId)
        } else if (viewModel.editingServer?.isDefaultServer == true) {
            UnifiedServerRepository.setDefaultServer(null)
        }

        // Return result and finish
        val resultIntent = Intent().apply {
            putExtra(RESULT_SERVER_ID, serverId)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun setLoading(loading: Boolean) {
        viewModel.isLoading = loading
        binding.nextButton.isEnabled = !loading
        binding.backButton.isEnabled = !loading
        binding.skipButton.isEnabled = !loading
    }
}
