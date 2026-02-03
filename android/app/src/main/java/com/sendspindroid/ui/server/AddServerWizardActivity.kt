package com.sendspindroid.ui.server

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.sendspindroid.R
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.databinding.ActivityAddServerWizardBinding
import com.sendspindroid.databinding.WizardStepLocalBinding
import com.sendspindroid.databinding.WizardStepMaLoginBinding
import com.sendspindroid.databinding.WizardStepNameBinding
import com.sendspindroid.databinding.WizardStepProxyBinding
import com.sendspindroid.databinding.WizardStepRemoteBinding
import com.sendspindroid.discovery.NsdDiscoveryManager
import com.sendspindroid.model.ConnectionPreference
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.ProxyConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.MaSettings
import com.sendspindroid.remote.RemoteConnection
import com.sendspindroid.sendspin.MusicAssistantAuth
import com.sendspindroid.ui.remote.QrScannerDialog
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Full-screen wizard Activity for adding or editing unified servers.
 *
 * Replaces AddServerWizardDialog with proper full-screen layout that handles
 * keyboard display correctly via adjustResize window soft input mode.
 *
 * ## Features
 * - Full-screen stepper with progress indicator
 * - Proper keyboard handling (buttons always visible)
 * - ViewModel for state management across configuration changes
 * - ActivityResult for returning data to calling Activity
 *
 * ## Steps
 * 1. **Name** (required) - Server nickname + Music Assistant checkbox
 * 2. **Local** (optional) - IP:port for local network with mDNS discovery
 * 3. **Remote** (optional) - 26-character Remote ID or QR scan
 * 4. **Proxy** (optional) - URL + login/token authentication
 * 5. **MA Login** (conditional) - Only shown if isMusicAssistant AND has local/proxy
 *
 * ## Usage
 * ```kotlin
 * // Launch for new server
 * val intent = Intent(this, AddServerWizardActivity::class.java)
 * addServerLauncher.launch(intent)
 *
 * // Launch for editing
 * val intent = Intent(this, AddServerWizardActivity::class.java).apply {
 *     putExtra(EXTRA_EDIT_SERVER_ID, server.id)
 * }
 * addServerLauncher.launch(intent)
 *
 * // Handle result
 * private val addServerLauncher = registerForActivityResult(
 *     ActivityResultContracts.StartActivityForResult()
 * ) { result ->
 *     if (result.resultCode == RESULT_OK) {
 *         val serverId = result.data?.getStringExtra(RESULT_SERVER_ID)
 *         // Handle new/updated server
 *     }
 * }
 * ```
 */
class AddServerWizardActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AddServerWizardActivity"

        // Intent extras
        const val EXTRA_EDIT_SERVER_ID = "edit_server_id"

        // Result extras
        const val RESULT_SERVER_ID = "server_id"

        // Step indices
        private const val STEP_NAME = 0
        private const val STEP_LOCAL = 1
        private const val STEP_REMOTE = 2
        private const val STEP_PROXY = 3
        private const val STEP_MA_LOGIN = 4
        private const val MAX_STEPS = 5
    }

    private lateinit var binding: ActivityAddServerWizardBinding
    private val viewModel: AddServerWizardViewModel by viewModels()

    // Step fragments (for accessing views)
    private var nameFragment: NameStepFragment? = null
    private var localFragment: LocalStepFragment? = null
    private var remoteFragment: RemoteStepFragment? = null
    private var proxyFragment: ProxyStepFragment? = null
    private var maLoginFragment: MaLoginStepFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAddServerWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle edit mode - load server data into ViewModel
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
        setupViewPager()
        setupButtons()
        setupBackPressHandler()
        updateButtonState()
        updateProgress()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        // Update title for edit mode
        if (viewModel.isEditMode) {
            binding.toolbar.title = getString(R.string.edit_server)
        }
    }

    private fun setupViewPager() {
        val adapter = WizardPagerAdapter(this)
        binding.wizardPager.adapter = adapter

        // Keep all wizard steps in memory so we can read their data at any time
        binding.wizardPager.offscreenPageLimit = MAX_STEPS

        // Sync TabLayout with ViewPager2
        TabLayoutMediator(binding.stepIndicator, binding.wizardPager) { tab, position ->
            tab.text = getStepTitle(position)
        }.attach()

        // Disable swipe (navigation via buttons only)
        binding.wizardPager.isUserInputEnabled = false

        // Update buttons and progress on page change
        binding.wizardPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateButtonState()
                updateProgress()
            }
        })
    }

    private fun getStepTitle(position: Int): String {
        return when (position) {
            STEP_NAME -> getString(R.string.wizard_step_name)
            STEP_LOCAL -> getString(R.string.wizard_step_local)
            STEP_REMOTE -> getString(R.string.wizard_step_remote)
            STEP_PROXY -> getString(R.string.wizard_step_proxy)
            STEP_MA_LOGIN -> getString(R.string.wizard_step_ma_login)
            else -> ""
        }
    }

    private fun setupButtons() {
        binding.backButton.setOnClickListener {
            if (binding.wizardPager.currentItem > 0) {
                binding.wizardPager.currentItem = binding.wizardPager.currentItem - 1
            }
        }

        binding.skipButton.setOnClickListener {
            val currentStep = binding.wizardPager.currentItem

            // Collect current data
            collectAllConnectionData()

            // Handle Proxy step specially - may skip MA Login or go to save
            if (currentStep == STEP_PROXY) {
                if (viewModel.shouldShowMaLoginStep()) {
                    binding.wizardPager.currentItem = STEP_MA_LOGIN
                } else {
                    attemptSave()
                }
            } else if (currentStep < STEP_PROXY) {
                binding.wizardPager.currentItem = currentStep + 1
            }
        }

        binding.nextButton.setOnClickListener {
            val currentStep = binding.wizardPager.currentItem

            // Validate current step before proceeding
            if (!validateCurrentStep()) return@setOnClickListener

            // Collect current data
            collectAllConnectionData()

            when (currentStep) {
                STEP_PROXY -> {
                    if (viewModel.shouldShowMaLoginStep()) {
                        binding.wizardPager.currentItem = STEP_MA_LOGIN
                    } else {
                        attemptSave()
                    }
                }
                STEP_MA_LOGIN -> {
                    attemptSave()
                }
                else -> {
                    binding.wizardPager.currentItem = currentStep + 1
                }
            }
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.wizardPager.currentItem > 0) {
                    binding.wizardPager.currentItem = binding.wizardPager.currentItem - 1
                } else {
                    finish()
                }
            }
        })
    }

    private fun collectAllConnectionData() {
        collectNameData()
        collectLocalData()
        collectRemoteData()
        collectProxyData()
    }

    private fun collectNameData() {
        viewModel.serverName = nameFragment?.getName() ?: viewModel.serverName
        viewModel.setAsDefault = nameFragment?.isSetAsDefault() ?: viewModel.setAsDefault
        viewModel.isMusicAssistant = nameFragment?.isMusicAssistant() ?: viewModel.isMusicAssistant
    }

    private fun collectLocalData() {
        viewModel.localAddress = localFragment?.getAddress() ?: viewModel.localAddress
    }

    private fun collectRemoteData() {
        viewModel.remoteId = remoteFragment?.getRemoteId() ?: viewModel.remoteId
    }

    private fun collectProxyData() {
        proxyFragment?.let { fragment ->
            viewModel.proxyUrl = fragment.getUrl()
            viewModel.proxyAuthMode = fragment.getAuthMode()
            if (viewModel.proxyAuthMode == AddServerWizardViewModel.AUTH_LOGIN) {
                viewModel.proxyUsername = fragment.getUsername()
                viewModel.proxyPassword = fragment.getPassword()
            } else {
                viewModel.proxyToken = fragment.getToken()
            }
        }
    }

    private fun updateButtonState() {
        val currentStep = binding.wizardPager.currentItem
        val isFirstStep = currentStep == 0
        val isMaLoginStep = currentStep == STEP_MA_LOGIN

        // Determine if this is effectively the last step
        val isEffectivelyLastStep = when (currentStep) {
            STEP_MA_LOGIN -> true
            STEP_PROXY -> !viewModel.shouldShowMaLoginStep()
            else -> false
        }

        // Back button: hide on first step
        binding.backButton.visibility = if (isFirstStep) View.INVISIBLE else View.VISIBLE

        // Skip button: show only on optional steps
        val showSkip = currentStep in listOf(STEP_LOCAL, STEP_REMOTE) ||
                       (currentStep == STEP_PROXY && viewModel.shouldShowMaLoginStep())
        binding.skipButton.visibility = if (showSkip) View.VISIBLE else View.GONE

        // Next button: change text on effective final step
        binding.nextButton.text = if (isEffectivelyLastStep) {
            getString(R.string.wizard_save)
        } else {
            getString(R.string.wizard_next)
        }
    }

    private fun updateProgress() {
        val currentStep = binding.wizardPager.currentItem
        val totalSteps = if (viewModel.shouldShowMaLoginStep()) MAX_STEPS else MAX_STEPS - 1
        val progress = ((currentStep + 1) * 100) / totalSteps
        binding.progressIndicator.progress = progress
    }

    private fun validateCurrentStep(): Boolean {
        return when (binding.wizardPager.currentItem) {
            STEP_NAME -> {
                collectNameData()
                if (viewModel.serverName.isBlank()) {
                    nameFragment?.showError(getString(R.string.wizard_name_required))
                    false
                } else {
                    nameFragment?.clearError()
                    true
                }
            }
            STEP_LOCAL -> {
                collectLocalData()
                if (viewModel.localAddress.isNotBlank() && !isValidAddress(viewModel.localAddress)) {
                    localFragment?.showError(getString(R.string.invalid_address))
                    false
                } else {
                    localFragment?.clearError()
                    true
                }
            }
            STEP_REMOTE -> {
                collectRemoteData()
                if (viewModel.remoteId.isNotBlank() && RemoteConnection.parseRemoteId(viewModel.remoteId) == null) {
                    remoteFragment?.showError(getString(R.string.remote_id_invalid))
                    false
                } else {
                    remoteFragment?.clearError()
                    true
                }
            }
            STEP_PROXY -> {
                collectProxyData()
                true
            }
            STEP_MA_LOGIN -> {
                if (viewModel.maToken == null) {
                    showError(getString(R.string.wizard_ma_connection_failed, "Please test connection first"))
                    false
                } else {
                    true
                }
            }
            else -> true
        }
    }

    private fun isValidAddress(address: String): Boolean {
        val parts = address.split(":")
        if (parts.isEmpty() || parts.size > 2) return false
        if (parts[0].isBlank()) return false
        if (parts.size == 2 && parts[1].toIntOrNull() == null) return false
        return true
    }

    private fun attemptSave() {
        collectAllConnectionData()

        val hasLocal = viewModel.localAddress.isNotBlank()
        val hasRemote = viewModel.remoteId.isNotBlank() && RemoteConnection.parseRemoteId(viewModel.remoteId) != null
        val hasProxy = viewModel.proxyUrl.isNotBlank()

        if (!hasLocal && !hasRemote && !hasProxy) {
            showError(getString(R.string.wizard_at_least_one_method))
            return
        }

        // If proxy is configured but requires login, do login first
        if (hasProxy && viewModel.proxyAuthMode == AddServerWizardViewModel.AUTH_LOGIN && viewModel.proxyPassword.isNotBlank()) {
            performProxyLoginAndSave(hasLocal, hasRemote)
        } else if (hasProxy && viewModel.proxyAuthMode == AddServerWizardViewModel.AUTH_TOKEN && viewModel.proxyToken.isBlank()) {
            showError(getString(R.string.auth_token_required))
        } else {
            saveServer(hasLocal, hasRemote, if (hasProxy) viewModel.proxyToken else null)
        }
    }

    private fun performProxyLoginAndSave(hasLocal: Boolean, hasRemote: Boolean) {
        if (viewModel.proxyUsername.isBlank() || viewModel.proxyPassword.isBlank()) {
            showError(getString(R.string.credentials_required))
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            try {
                val normalizedUrl = normalizeProxyUrl(viewModel.proxyUrl)
                val result = MusicAssistantAuth.login(normalizedUrl, viewModel.proxyUsername, viewModel.proxyPassword)

                setLoading(false)
                saveServer(hasLocal, hasRemote, result.accessToken)

            } catch (e: MusicAssistantAuth.AuthenticationException) {
                setLoading(false)
                showError(e.message ?: getString(R.string.login_invalid_credentials))
            } catch (e: MusicAssistantAuth.ServerException) {
                setLoading(false)
                showError(getString(R.string.login_failed, e.message))
            } catch (e: IOException) {
                setLoading(false)
                showError(getString(R.string.error_network))
            } catch (e: Exception) {
                setLoading(false)
                showError(getString(R.string.login_server_error))
            }
        }
    }

    private fun saveServer(hasLocal: Boolean, hasRemote: Boolean, proxyAuthToken: String?) {
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
            proxy = if (proxyAuthToken != null) ProxyConnection(
                url = normalizeProxyUrl(viewModel.proxyUrl),
                authToken = proxyAuthToken,
                username = if (viewModel.proxyAuthMode == AddServerWizardViewModel.AUTH_LOGIN) viewModel.proxyUsername else null
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

    private fun normalizeProxyUrl(url: String): String {
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

    private fun setLoading(loading: Boolean) {
        viewModel.isLoading = loading
        binding.nextButton.isEnabled = !loading
        binding.backButton.isEnabled = !loading
        binding.skipButton.isEnabled = !loading
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    // ========== ViewPager Adapter ==========

    private inner class WizardPagerAdapter(
        activity: FragmentActivity
    ) : FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = MAX_STEPS

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                STEP_NAME -> NameStepFragment().also {
                    nameFragment = it
                    it.viewModel = this@AddServerWizardActivity.viewModel
                }
                STEP_LOCAL -> LocalStepFragment().also {
                    localFragment = it
                    it.viewModel = this@AddServerWizardActivity.viewModel
                }
                STEP_REMOTE -> RemoteStepFragment().also {
                    remoteFragment = it
                    it.viewModel = this@AddServerWizardActivity.viewModel
                }
                STEP_PROXY -> ProxyStepFragment().also {
                    proxyFragment = it
                    it.viewModel = this@AddServerWizardActivity.viewModel
                }
                STEP_MA_LOGIN -> MaLoginStepFragment().also {
                    maLoginFragment = it
                    it.viewModel = this@AddServerWizardActivity.viewModel
                }
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }
    }

    // ========== Step Fragments ==========

    /**
     * Step 1: Server Name + Music Assistant checkbox
     */
    class NameStepFragment : Fragment(R.layout.wizard_step_name) {
        var viewModel: AddServerWizardViewModel? = null
        private var _binding: WizardStepNameBinding? = null
        private val binding get() = _binding!!

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            _binding = WizardStepNameBinding.bind(view)

            viewModel?.let { vm ->
                binding.nameInput.setText(vm.serverName)
                binding.setAsDefaultCheckbox.isChecked = vm.setAsDefault
                binding.isMusicAssistantCheckbox.isChecked = vm.isMusicAssistant
            }
        }

        override fun onDestroyView() {
            super.onDestroyView()
            _binding = null
        }

        fun getName(): String = _binding?.nameInput?.text?.toString()?.trim() ?: ""
        fun isSetAsDefault(): Boolean = _binding?.setAsDefaultCheckbox?.isChecked ?: false
        fun isMusicAssistant(): Boolean = _binding?.isMusicAssistantCheckbox?.isChecked ?: false

        fun showError(message: String) {
            binding.nameInputLayout.error = message
        }

        fun clearError() {
            binding.nameInputLayout.error = null
        }
    }

    /**
     * Step 2: Local Connection with mDNS discovery
     */
    class LocalStepFragment : Fragment(R.layout.wizard_step_local), NsdDiscoveryManager.DiscoveryListener {
        var viewModel: AddServerWizardViewModel? = null
        private var _binding: WizardStepLocalBinding? = null
        private val binding get() = _binding!!

        private var discoveryManager: NsdDiscoveryManager? = null
        private val discoveredServers = mutableMapOf<String, DiscoveredServer>()

        private data class DiscoveredServer(val name: String, val address: String, val path: String)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            _binding = WizardStepLocalBinding.bind(view)

            viewModel?.let { vm ->
                binding.addressInput.setText(vm.localAddress)
            }

            discoveryManager = NsdDiscoveryManager(requireContext(), this)
        }

        override fun onResume() {
            super.onResume()
            startDiscovery()
        }

        override fun onPause() {
            super.onPause()
            stopDiscovery()
        }

        override fun onDestroyView() {
            super.onDestroyView()
            discoveryManager?.cleanup()
            discoveryManager = null
            _binding = null
        }

        private fun startDiscovery() {
            discoveredServers.clear()
            updateDiscoveryUI(isScanning = true)
            discoveryManager?.startDiscovery()
        }

        private fun stopDiscovery() {
            discoveryManager?.stopDiscovery()
        }

        private fun updateDiscoveryUI(isScanning: Boolean) {
            if (_binding == null) return

            if (isScanning) {
                binding.scanningContainer.visibility = View.VISIBLE
                binding.noServersFoundText.visibility = View.GONE
            } else {
                binding.scanningContainer.visibility = View.GONE
                if (discoveredServers.isEmpty()) {
                    binding.noServersFoundText.visibility = View.VISIBLE
                }
            }

            binding.discoveredServersChipGroup.visibility =
                if (discoveredServers.isNotEmpty()) View.VISIBLE else View.GONE
        }

        private fun addServerChip(server: DiscoveredServer) {
            if (_binding == null) return

            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = server.name
                isCheckable = true
                isCheckedIconVisible = true
                tag = server.address

                setOnClickListener {
                    binding.addressInput.setText(server.address)
                    clearError()
                }
            }

            binding.discoveredServersChipGroup.addView(chip)
            binding.discoveredServersChipGroup.visibility = View.VISIBLE
        }

        private fun removeServerChip(serverName: String) {
            if (_binding == null) return

            val chipGroup = binding.discoveredServersChipGroup
            for (i in 0 until chipGroup.childCount) {
                val chip = chipGroup.getChildAt(i) as? com.google.android.material.chip.Chip
                if (chip?.text == serverName) {
                    chipGroup.removeViewAt(i)
                    break
                }
            }

            if (chipGroup.childCount == 0) {
                chipGroup.visibility = View.GONE
            }
        }

        override fun onServerDiscovered(name: String, address: String, path: String) {
            activity?.runOnUiThread {
                val server = DiscoveredServer(name, address, path)
                discoveredServers[name] = server
                addServerChip(server)
            }
        }

        override fun onServerLost(name: String) {
            activity?.runOnUiThread {
                discoveredServers.remove(name)
                removeServerChip(name)
            }
        }

        override fun onDiscoveryStarted() {
            activity?.runOnUiThread {
                updateDiscoveryUI(isScanning = true)
            }
        }

        override fun onDiscoveryStopped() {
            activity?.runOnUiThread {
                updateDiscoveryUI(isScanning = false)
            }
        }

        override fun onDiscoveryError(error: String) {
            activity?.runOnUiThread {
                updateDiscoveryUI(isScanning = false)
            }
        }

        fun getAddress(): String = _binding?.addressInput?.text?.toString()?.trim() ?: ""

        fun showError(message: String) {
            binding.addressInputLayout.error = message
        }

        fun clearError() {
            binding.addressInputLayout.error = null
        }
    }

    /**
     * Step 3: Remote Access
     */
    class RemoteStepFragment : Fragment(R.layout.wizard_step_remote) {
        var viewModel: AddServerWizardViewModel? = null
        private var _binding: WizardStepRemoteBinding? = null
        private val binding get() = _binding!!
        private var isFormatting = false

        private val isTvDevice: Boolean by lazy {
            val uiModeManager = requireContext().getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            _binding = WizardStepRemoteBinding.bind(view)

            viewModel?.let { vm ->
                if (vm.remoteId.isNotBlank()) {
                    binding.remoteIdInput.setText(RemoteConnection.formatRemoteId(vm.remoteId))
                }
            }

            // Auto-format as user types
            binding.remoteIdInput.addTextChangedListener(object : TextWatcher {
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
                        val cursorPos = binding.remoteIdInput.selectionEnd
                        s.replace(0, s.length, formatted)
                        val newPos = minOf(cursorPos + (formatted.length - lastLength), formatted.length)
                        binding.remoteIdInput.setSelection(maxOf(0, newPos))
                    }

                    isFormatting = false
                }
            })

            // QR scanner button - hide on TV devices
            if (isTvDevice) {
                binding.remoteIdInputLayout.isEndIconVisible = false
            } else {
                binding.remoteIdInputLayout.setEndIconOnClickListener {
                    QrScannerDialog.show(childFragmentManager) { scannedId ->
                        binding.remoteIdInput.setText(RemoteConnection.formatRemoteId(scannedId))
                    }
                }
            }
        }

        override fun onDestroyView() {
            super.onDestroyView()
            _binding = null
        }

        fun getRemoteId(): String {
            val input = _binding?.remoteIdInput?.text?.toString() ?: ""
            return RemoteConnection.parseRemoteId(input) ?: input.filter { it.isLetterOrDigit() }
        }

        fun showError(message: String) {
            binding.remoteIdInputLayout.error = message
        }

        fun clearError() {
            binding.remoteIdInputLayout.error = null
        }
    }

    /**
     * Step 4: Proxy Connection
     */
    class ProxyStepFragment : Fragment(R.layout.wizard_step_proxy) {
        var viewModel: AddServerWizardViewModel? = null
        private var _binding: WizardStepProxyBinding? = null
        private val binding get() = _binding!!
        private var currentAuthMode = AddServerWizardViewModel.AUTH_LOGIN

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            _binding = WizardStepProxyBinding.bind(view)

            viewModel?.let { vm ->
                binding.urlInput.setText(vm.proxyUrl)
                binding.usernameInput.setText(vm.proxyUsername)
                binding.tokenInput.setText(vm.proxyToken)
                currentAuthMode = vm.proxyAuthMode
            }

            binding.authModeTabs.selectTab(binding.authModeTabs.getTabAt(currentAuthMode))
            updateAuthModeVisibility()

            binding.authModeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    currentAuthMode = tab?.position ?: AddServerWizardViewModel.AUTH_LOGIN
                    updateAuthModeVisibility()
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
        }

        override fun onDestroyView() {
            super.onDestroyView()
            _binding = null
        }

        private fun updateAuthModeVisibility() {
            when (currentAuthMode) {
                AddServerWizardViewModel.AUTH_LOGIN -> {
                    binding.loginModeContainer.visibility = View.VISIBLE
                    binding.tokenModeContainer.visibility = View.GONE
                }
                AddServerWizardViewModel.AUTH_TOKEN -> {
                    binding.loginModeContainer.visibility = View.GONE
                    binding.tokenModeContainer.visibility = View.VISIBLE
                }
            }
        }

        fun getUrl(): String = _binding?.urlInput?.text?.toString()?.trim() ?: ""
        fun getAuthMode(): Int = currentAuthMode
        fun getUsername(): String = _binding?.usernameInput?.text?.toString()?.trim() ?: ""
        fun getPassword(): String = _binding?.passwordInput?.text?.toString() ?: ""
        fun getToken(): String = _binding?.tokenInput?.text?.toString()?.trim() ?: ""
    }

    /**
     * Step 5: Music Assistant Login (conditional)
     */
    class MaLoginStepFragment : Fragment(R.layout.wizard_step_ma_login) {
        var viewModel: AddServerWizardViewModel? = null
        private var _binding: WizardStepMaLoginBinding? = null
        private val binding get() = _binding!!
        private var isTesting = false

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            _binding = WizardStepMaLoginBinding.bind(view)

            viewModel?.let { vm ->
                binding.maUsernameInput.setText(vm.maUsername)
            }

            binding.maPortInput.setText(MaSettings.getDefaultPort().toString())

            binding.testConnectionButton.setOnClickListener {
                testConnection()
            }
        }

        override fun onDestroyView() {
            super.onDestroyView()
            _binding = null
        }

        private fun testConnection() {
            val username = binding.maUsernameInput.text?.toString()?.trim() ?: ""
            val password = binding.maPasswordInput.text?.toString() ?: ""

            if (username.isBlank() || password.isBlank()) {
                showStatus(isError = true, message = getString(R.string.credentials_required))
                return
            }

            val vm = viewModel ?: return
            val apiUrl = deriveMaApiUrl(vm)

            if (apiUrl == null) {
                showStatus(isError = true, message = getString(R.string.wizard_ma_requires_local_or_proxy))
                return
            }

            setTesting(true)
            showStatus(isLoading = true, message = getString(R.string.wizard_ma_testing))

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = MusicAssistantAuth.login(apiUrl, username, password)

                    setTesting(false)
                    showStatus(isSuccess = true, message = getString(R.string.wizard_ma_connection_success))

                    vm.maToken = result.accessToken
                    vm.maUsername = username

                    val port = getPort()
                    if (port != MaSettings.getDefaultPort()) {
                        MaSettings.setDefaultPort(port)
                    }

                } catch (e: MusicAssistantAuth.AuthenticationException) {
                    setTesting(false)
                    showStatus(isError = true, message = getString(R.string.login_invalid_credentials))
                    vm.maToken = null
                } catch (e: IOException) {
                    setTesting(false)
                    showStatus(isError = true, message = getString(R.string.error_network))
                    vm.maToken = null
                } catch (e: Exception) {
                    setTesting(false)
                    showStatus(isError = true, message = getString(R.string.wizard_ma_connection_failed, e.message ?: "Unknown error"))
                    vm.maToken = null
                }
            }
        }

        private fun deriveMaApiUrl(vm: AddServerWizardViewModel): String? {
            if (vm.localAddress.isNotBlank()) {
                val host = vm.localAddress.substringBefore(":")
                val port = getPort()
                return "ws://$host:$port/ws"
            }

            if (vm.proxyUrl.isNotBlank()) {
                val baseUrl = normalizeProxyUrl(vm.proxyUrl)
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

        private fun normalizeProxyUrl(url: String): String {
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

        private fun setTesting(testing: Boolean) {
            isTesting = testing
            binding.testConnectionButton.isEnabled = !testing
            binding.maUsernameInput.isEnabled = !testing
            binding.maPasswordInput.isEnabled = !testing
            binding.maPortInput.isEnabled = !testing
        }

        private fun showStatus(
            isLoading: Boolean = false,
            isSuccess: Boolean = false,
            isError: Boolean = false,
            message: String = ""
        ) {
            binding.connectionStatusContainer.visibility = View.VISIBLE
            binding.connectionProgress.visibility = if (isLoading) View.VISIBLE else View.GONE

            when {
                isSuccess -> {
                    binding.connectionStatusIcon.visibility = View.VISIBLE
                    binding.connectionStatusIcon.setImageResource(R.drawable.ic_check_circle)
                    binding.connectionStatusIcon.imageTintList =
                        android.content.res.ColorStateList.valueOf(
                            resources.getColor(android.R.color.holo_green_dark, null)
                        )
                }
                isError -> {
                    binding.connectionStatusIcon.visibility = View.VISIBLE
                    binding.connectionStatusIcon.setImageResource(R.drawable.ic_error)
                    binding.connectionStatusIcon.imageTintList =
                        android.content.res.ColorStateList.valueOf(
                            resources.getColor(android.R.color.holo_red_dark, null)
                        )
                }
                else -> {
                    binding.connectionStatusIcon.visibility = View.GONE
                }
            }

            binding.connectionStatusText.text = message
            binding.connectionStatusText.setTextColor(
                when {
                    isSuccess -> resources.getColor(android.R.color.holo_green_dark, null)
                    isError -> resources.getColor(android.R.color.holo_red_dark, null)
                    else -> resources.getColor(android.R.color.darker_gray, null)
                }
            )
        }

        fun getUsername(): String = binding.maUsernameInput.text?.toString()?.trim() ?: ""
        fun getPassword(): String = binding.maPasswordInput.text?.toString() ?: ""
        fun getPort(): Int {
            val portStr = binding.maPortInput.text?.toString()?.trim() ?: ""
            return portStr.toIntOrNull() ?: MaSettings.getDefaultPort()
        }
    }
}
