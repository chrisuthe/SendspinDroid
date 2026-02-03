package com.sendspindroid.ui.server

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.URLUtil
import androidx.fragment.app.DialogFragment
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
import com.sendspindroid.discovery.NsdDiscoveryManager
import com.sendspindroid.databinding.DialogAddServerWizardBinding
import com.sendspindroid.databinding.WizardStepLocalBinding
import com.sendspindroid.databinding.WizardStepMaLoginBinding
import com.sendspindroid.databinding.WizardStepNameBinding
import com.sendspindroid.databinding.WizardStepProxyBinding
import com.sendspindroid.databinding.WizardStepRemoteBinding
import com.sendspindroid.model.*
import com.sendspindroid.musicassistant.MaApiEndpoint
import com.sendspindroid.musicassistant.MaSettings
import com.sendspindroid.remote.RemoteConnection
import com.sendspindroid.sendspin.MusicAssistantAuth
import com.sendspindroid.ui.remote.QrScannerDialog
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Multi-step wizard dialog for adding a unified server.
 *
 * ## Steps
 * 1. **Name** (required) - Server nickname + Music Assistant checkbox
 * 2. **Local** (optional) - IP:port for local network
 * 3. **Remote** (optional) - 26-character Remote ID or QR scan
 * 4. **Proxy** (optional) - URL + login/token authentication
 * 5. **MA Login** (conditional) - Only shown if isMusicAssistant AND has local/proxy
 *
 * At least one connection method (Local, Remote, or Proxy) must be configured.
 *
 * ## Usage
 * ```kotlin
 * AddServerWizardDialog.show(supportFragmentManager) { server ->
 *     // Server was created and saved
 *     connectToServer(server)
 * }
 * ```
 */
class AddServerWizardDialog : DialogFragment() {

    companion object {
        private const val TAG = "AddServerWizardDialog"

        // Base step indices (MA Login step is dynamically inserted)
        private const val STEP_NAME = 0
        private const val STEP_LOCAL = 1
        private const val STEP_REMOTE = 2
        private const val STEP_PROXY = 3
        private const val STEP_MA_LOGIN = 4  // Only present when isMusicAssistant && hasLocalOrProxy
        private const val BASE_STEPS = 4
        private const val MAX_STEPS = 5

        // Proxy auth modes
        private const val AUTH_LOGIN = 0
        private const val AUTH_TOKEN = 1

        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            onServerCreated: (UnifiedServer) -> Unit
        ): AddServerWizardDialog {
            val dialog = AddServerWizardDialog()
            dialog.onServerCreated = onServerCreated
            dialog.show(fragmentManager, TAG)
            return dialog
        }

        /**
         * Show wizard with pre-filled data (for editing).
         */
        fun showForEdit(
            fragmentManager: androidx.fragment.app.FragmentManager,
            server: UnifiedServer,
            onServerUpdated: (UnifiedServer) -> Unit
        ): AddServerWizardDialog {
            val dialog = AddServerWizardDialog()
            dialog.editingServer = server
            dialog.onServerCreated = onServerUpdated
            dialog.show(fragmentManager, TAG)
            return dialog
        }
    }

    private var _binding: DialogAddServerWizardBinding? = null
    private val binding get() = _binding!!

    private var onServerCreated: ((UnifiedServer) -> Unit)? = null
    private var editingServer: UnifiedServer? = null
    private var isLoading = false

    // Wizard state (shared across steps)
    private var serverName: String = ""
    private var setAsDefault: Boolean = false
    private var isMusicAssistant: Boolean = false
    private var localAddress: String = ""
    private var remoteId: String = ""
    private var proxyUrl: String = ""
    private var proxyAuthMode: Int = AUTH_LOGIN
    private var proxyUsername: String = ""
    private var proxyPassword: String = ""
    private var proxyToken: String = ""

    // MA Login state (for eager auth)
    private var maUsername: String = ""
    private var maPassword: String = ""
    private var maToken: String? = null  // Token obtained from successful MA login

    // Step fragments (for accessing views)
    private var nameFragment: NameStepFragment? = null
    private var localFragment: LocalStepFragment? = null
    private var remoteFragment: RemoteStepFragment? = null
    private var proxyFragment: ProxyStepFragment? = null
    private var maLoginFragment: MaLoginStepFragment? = null

    // Dynamic step count (changes based on isMusicAssistant checkbox)
    private var currentStepCount: Int = BASE_STEPS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_SendSpinDroid_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAddServerWizardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Pre-fill if editing
        editingServer?.let { server ->
            serverName = server.name
            setAsDefault = server.isDefaultServer
            isMusicAssistant = server.isMusicAssistant
            server.local?.let {
                localAddress = it.address
            }
            server.remote?.let {
                remoteId = it.remoteId
            }
            server.proxy?.let {
                proxyUrl = it.url
                proxyToken = it.authToken
                proxyUsername = it.username ?: ""
                proxyAuthMode = AUTH_TOKEN // Default to token mode if we have saved proxy
            }
            // Load existing MA token if available
            if (isMusicAssistant) {
                maToken = MaSettings.getTokenForServer(server.id)
            }
        }

        // Calculate initial step count
        updateStepCount()

        setupViewPager()
        setupButtons()
        updateButtonState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupViewPager() {
        val adapter = WizardPagerAdapter(requireActivity())
        binding.wizardPager.adapter = adapter

        // Sync TabLayout with ViewPager2
        TabLayoutMediator(binding.stepIndicator, binding.wizardPager) { tab, position ->
            tab.text = getStepTitle(position)
        }.attach()

        // Disable swipe (navigation via buttons only)
        binding.wizardPager.isUserInputEnabled = false

        // Update buttons on page change
        binding.wizardPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateButtonState()
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

    /**
     * Updates the step count based on current wizard state.
     * MA Login step is only shown when isMusicAssistant is true AND
     * at least local or proxy is configured.
     */
    private fun updateStepCount() {
        val hasLocalOrProxy = localAddress.isNotBlank() || proxyUrl.isNotBlank()
        currentStepCount = if (isMusicAssistant && hasLocalOrProxy) MAX_STEPS else BASE_STEPS
    }

    /**
     * Checks if MA Login step should be shown based on current state.
     */
    private fun shouldShowMaLoginStep(): Boolean {
        val hasLocalOrProxy = localAddress.isNotBlank() || proxyUrl.isNotBlank()
        return isMusicAssistant && hasLocalOrProxy
    }

    private fun setupButtons() {
        binding.closeButton.setOnClickListener {
            dismiss()
        }

        binding.backButton.setOnClickListener {
            if (binding.wizardPager.currentItem > 0) {
                binding.wizardPager.currentItem = binding.wizardPager.currentItem - 1
            }
        }

        binding.skipButton.setOnClickListener {
            // Skip to next step (for optional steps)
            if (binding.wizardPager.currentItem < currentStepCount - 1) {
                binding.wizardPager.currentItem = binding.wizardPager.currentItem + 1
            }
        }

        binding.nextButton.setOnClickListener {
            val currentStep = binding.wizardPager.currentItem
            val isLastStep = currentStep == currentStepCount - 1

            if (isLastStep) {
                // Final step - attempt save
                attemptSave()
            } else {
                // Validate current step before proceeding
                if (validateCurrentStep()) {
                    // After Proxy step, check if MA Login step should be shown
                    if (currentStep == STEP_PROXY) {
                        collectAllConnectionData()
                        updateStepCount()
                        // Refresh adapter if step count changed
                        refreshViewPagerIfNeeded()
                    }
                    binding.wizardPager.currentItem = currentStep + 1
                }
            }
        }
    }

    /**
     * Refreshes the ViewPager adapter when step count changes.
     */
    private fun refreshViewPagerIfNeeded() {
        val adapter = binding.wizardPager.adapter as? WizardPagerAdapter
        if (adapter != null && adapter.itemCount != currentStepCount) {
            adapter.notifyDataSetChanged()
            // Re-attach TabLayoutMediator
            TabLayoutMediator(binding.stepIndicator, binding.wizardPager) { tab, position ->
                tab.text = getStepTitle(position)
            }.attach()
        }
    }

    private fun collectAllConnectionData() {
        collectNameData()
        collectLocalData()
        collectRemoteData()
        collectProxyData()
    }

    private fun updateButtonState() {
        val currentStep = binding.wizardPager.currentItem
        val isFirstStep = currentStep == 0
        val isLastStep = currentStep == currentStepCount - 1
        val isMaLoginStep = currentStep == STEP_MA_LOGIN && shouldShowMaLoginStep()

        // Back button: hide on first step
        binding.backButton.visibility = if (isFirstStep) View.INVISIBLE else View.VISIBLE

        // Skip button: show only on optional steps (not name, not final, not MA login if required)
        // MA Login step is NOT skippable when shown - credentials must be validated
        binding.skipButton.visibility = if (!isFirstStep && !isLastStep && !isMaLoginStep) View.VISIBLE else View.GONE

        // Next button: change text on final step
        binding.nextButton.text = if (isLastStep) {
            getString(R.string.wizard_save)
        } else {
            getString(R.string.wizard_next)
        }
    }

    private fun validateCurrentStep(): Boolean {
        return when (binding.wizardPager.currentItem) {
            STEP_NAME -> {
                collectNameData()
                if (serverName.isBlank()) {
                    nameFragment?.showError(getString(R.string.wizard_name_required))
                    false
                } else {
                    nameFragment?.clearError()
                    true
                }
            }
            STEP_LOCAL -> {
                collectLocalData()
                if (localAddress.isNotBlank() && !isValidAddress(localAddress)) {
                    localFragment?.showError(getString(R.string.invalid_address))
                    false
                } else {
                    localFragment?.clearError()
                    true
                }
            }
            STEP_REMOTE -> {
                collectRemoteData()
                if (remoteId.isNotBlank() && RemoteConnection.parseRemoteId(remoteId) == null) {
                    remoteFragment?.showError(getString(R.string.remote_id_invalid))
                    false
                } else {
                    remoteFragment?.clearError()
                    true
                }
            }
            STEP_PROXY -> {
                collectProxyData()
                // Proxy validation happens during save (may involve login)
                true
            }
            STEP_MA_LOGIN -> {
                // MA Login step validation - must have successfully tested connection
                if (maToken == null) {
                    showError(getString(R.string.wizard_ma_connection_failed, "Please test connection first"))
                    false
                } else {
                    true
                }
            }
            else -> true
        }
    }

    private fun collectNameData() {
        serverName = nameFragment?.getName() ?: serverName
        setAsDefault = nameFragment?.isSetAsDefault() ?: setAsDefault
        isMusicAssistant = nameFragment?.isMusicAssistant() ?: isMusicAssistant
    }

    private fun collectLocalData() {
        localAddress = localFragment?.getAddress() ?: localAddress
    }

    private fun collectRemoteData() {
        remoteId = remoteFragment?.getRemoteId() ?: remoteId
    }

    private fun collectProxyData() {
        proxyFragment?.let { fragment ->
            proxyUrl = fragment.getUrl()
            proxyAuthMode = fragment.getAuthMode()
            if (proxyAuthMode == AUTH_LOGIN) {
                proxyUsername = fragment.getUsername()
                proxyPassword = fragment.getPassword()
            } else {
                proxyToken = fragment.getToken()
            }
        }
    }

    private fun isValidAddress(address: String): Boolean {
        // Simple validation: contains host:port or just host
        val parts = address.split(":")
        if (parts.isEmpty() || parts.size > 2) return false
        if (parts[0].isBlank()) return false
        if (parts.size == 2 && parts[1].toIntOrNull() == null) return false
        return true
    }

    private fun attemptSave() {
        // Collect all data from steps
        collectNameData()
        collectLocalData()
        collectRemoteData()
        collectProxyData()

        // Validate at least one connection method
        val hasLocal = localAddress.isNotBlank()
        val hasRemote = remoteId.isNotBlank() && RemoteConnection.parseRemoteId(remoteId) != null
        val hasProxy = proxyUrl.isNotBlank()

        if (!hasLocal && !hasRemote && !hasProxy) {
            showError(getString(R.string.wizard_at_least_one_method))
            return
        }

        // If proxy is configured but requires login, do login first
        if (hasProxy && proxyAuthMode == AUTH_LOGIN && proxyPassword.isNotBlank()) {
            performProxyLoginAndSave(hasLocal, hasRemote)
        } else if (hasProxy && proxyAuthMode == AUTH_TOKEN && proxyToken.isBlank()) {
            showError(getString(R.string.auth_token_required))
        } else {
            // Direct save (no proxy login needed)
            saveServer(hasLocal, hasRemote, if (hasProxy) proxyToken else null)
        }
    }

    private fun performProxyLoginAndSave(hasLocal: Boolean, hasRemote: Boolean) {
        if (proxyUsername.isBlank() || proxyPassword.isBlank()) {
            showError(getString(R.string.credentials_required))
            return
        }

        setLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val normalizedUrl = normalizeProxyUrl(proxyUrl)
                val result = MusicAssistantAuth.login(normalizedUrl, proxyUsername, proxyPassword)

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
        val parsedRemoteId = if (hasRemote) RemoteConnection.parseRemoteId(remoteId) else null
        val serverId = editingServer?.id ?: UnifiedServerRepository.generateId()

        val server = UnifiedServer(
            id = serverId,
            name = serverName,
            lastConnectedMs = editingServer?.lastConnectedMs ?: 0L,
            local = if (hasLocal) LocalConnection(
                address = localAddress,
                path = "/sendspin"
            ) else null,
            remote = if (parsedRemoteId != null) com.sendspindroid.model.RemoteConnection(
                remoteId = parsedRemoteId
            ) else null,
            proxy = if (proxyAuthToken != null) ProxyConnection(
                url = normalizeProxyUrl(proxyUrl),
                authToken = proxyAuthToken,
                username = if (proxyAuthMode == AUTH_LOGIN) proxyUsername else null
            ) else null,
            connectionPreference = ConnectionPreference.AUTO,
            isDiscovered = false,
            isDefaultServer = setAsDefault,
            isMusicAssistant = isMusicAssistant
        )

        // Save to repository
        UnifiedServerRepository.saveServer(server)

        // Save MA token if we have one (from eager auth during wizard)
        if (isMusicAssistant && maToken != null) {
            MaSettings.setTokenForServer(serverId, maToken!!)
        } else if (!isMusicAssistant) {
            // Clear any existing MA token if user unchecked Music Assistant
            MaSettings.clearTokenForServer(serverId)
        }

        // Update default server if needed
        if (setAsDefault) {
            UnifiedServerRepository.setDefaultServer(serverId)
        } else if (editingServer?.isDefaultServer == true) {
            // Was default before, now unchecked - clear default
            UnifiedServerRepository.setDefaultServer(null)
        }

        // Invoke callback and dismiss
        onServerCreated?.invoke(server)
        dismiss()
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
        isLoading = loading
        binding.nextButton.isEnabled = !loading
        binding.backButton.isEnabled = !loading
        binding.skipButton.isEnabled = !loading
    }

    private fun showError(message: String) {
        view?.let { v ->
            Snackbar.make(v, message, Snackbar.LENGTH_LONG).show()
        }
    }

    // ========== ViewPager Adapter ==========

    private inner class WizardPagerAdapter(
        activity: FragmentActivity
    ) : FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = currentStepCount

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                STEP_NAME -> NameStepFragment().also {
                    nameFragment = it
                    it.initialName = serverName
                    it.initialSetAsDefault = setAsDefault
                    it.initialIsMusicAssistant = isMusicAssistant
                }
                STEP_LOCAL -> LocalStepFragment().also {
                    localFragment = it
                    it.initialAddress = localAddress
                }
                STEP_REMOTE -> RemoteStepFragment().also {
                    remoteFragment = it
                    it.initialRemoteId = remoteId
                    it.parentDialog = this@AddServerWizardDialog
                }
                STEP_PROXY -> ProxyStepFragment().also {
                    proxyFragment = it
                    it.initialUrl = proxyUrl
                    it.initialUsername = proxyUsername
                    it.initialToken = proxyToken
                    it.initialAuthMode = proxyAuthMode
                }
                STEP_MA_LOGIN -> MaLoginStepFragment().also {
                    maLoginFragment = it
                    it.parentDialog = this@AddServerWizardDialog
                    it.initialUsername = maUsername
                }
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }
    }

    // ========== Step Fragments ==========

    /**
     * Step 1: Server Name + Music Assistant checkbox
     */
    class NameStepFragment : Fragment() {
        var initialName: String = ""
        var initialSetAsDefault: Boolean = false
        var initialIsMusicAssistant: Boolean = false
        private var _binding: WizardStepNameBinding? = null
        private val binding get() = _binding!!

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            _binding = WizardStepNameBinding.inflate(inflater, container, false)
            return binding.root
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            binding.nameInput.setText(initialName)
            binding.setAsDefaultCheckbox.isChecked = initialSetAsDefault
            binding.isMusicAssistantCheckbox.isChecked = initialIsMusicAssistant
        }

        override fun onDestroyView() {
            super.onDestroyView()
            _binding = null
        }

        fun getName(): String = binding.nameInput.text?.toString()?.trim() ?: ""

        fun isSetAsDefault(): Boolean = binding.setAsDefaultCheckbox.isChecked

        fun isMusicAssistant(): Boolean = binding.isMusicAssistantCheckbox.isChecked

        fun showError(message: String) {
            binding.nameInputLayout.error = message
        }

        fun clearError() {
            binding.nameInputLayout.error = null
        }
    }

    /**
     * Step 2: Local Connection
     *
     * Uses mDNS (NsdDiscoveryManager) to scan for SendSpin servers on the local network.
     * Discovered servers are shown as selectable chips - tapping one populates the address field.
     */
    class LocalStepFragment : Fragment(), NsdDiscoveryManager.DiscoveryListener {
        var initialAddress: String = ""
        private var _binding: WizardStepLocalBinding? = null
        private val binding get() = _binding!!

        private var discoveryManager: NsdDiscoveryManager? = null
        private val discoveredServers = mutableMapOf<String, DiscoveredServer>()

        /** Holds discovered server info for chip creation */
        private data class DiscoveredServer(val name: String, val address: String, val path: String)

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            _binding = WizardStepLocalBinding.inflate(inflater, container, false)
            return binding.root
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            binding.addressInput.setText(initialAddress)

            // Initialize mDNS discovery
            discoveryManager = NsdDiscoveryManager(requireContext(), this)
        }

        override fun onResume() {
            super.onResume()
            // Start discovery when fragment becomes visible
            startDiscovery()
        }

        override fun onPause() {
            super.onPause()
            // Stop discovery when fragment is not visible
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

            // Show chip group only if we have servers
            binding.discoveredServersChipGroup.visibility =
                if (discoveredServers.isNotEmpty()) View.VISIBLE else View.GONE
        }

        private fun addServerChip(server: DiscoveredServer) {
            if (_binding == null) return

            // Create a new chip for the discovered server
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = server.name
                isCheckable = true
                isCheckedIconVisible = true
                tag = server.address

                setOnClickListener {
                    // Populate address field when chip is selected
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

        // ========== NsdDiscoveryManager.DiscoveryListener ==========

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

        fun getAddress(): String = binding.addressInput.text?.toString()?.trim() ?: ""

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
    class RemoteStepFragment : Fragment() {
        var initialRemoteId: String = ""
        var parentDialog: AddServerWizardDialog? = null
        private var _binding: WizardStepRemoteBinding? = null
        private val binding get() = _binding!!
        private var isFormatting = false

        // Android TV detection - cameras on TV devices point away from user
        private val isTvDevice: Boolean by lazy {
            val uiModeManager = requireContext().getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            _binding = WizardStepRemoteBinding.inflate(inflater, container, false)
            return binding.root
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            // Pre-fill initial value
            if (initialRemoteId.isNotBlank()) {
                binding.remoteIdInput.setText(RemoteConnection.formatRemoteId(initialRemoteId))
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

            // QR scanner button - hide on TV devices where cameras point away from user
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
            val input = binding.remoteIdInput.text?.toString() ?: ""
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
    class ProxyStepFragment : Fragment() {
        var initialUrl: String = ""
        var initialUsername: String = ""
        var initialToken: String = ""
        var initialAuthMode: Int = AUTH_LOGIN

        private var _binding: WizardStepProxyBinding? = null
        private val binding get() = _binding!!
        private var currentAuthMode = AUTH_LOGIN

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            _binding = WizardStepProxyBinding.inflate(inflater, container, false)
            return binding.root
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            // Pre-fill initial values
            binding.urlInput.setText(initialUrl)
            binding.usernameInput.setText(initialUsername)
            binding.tokenInput.setText(initialToken)

            // Select initial auth mode tab
            currentAuthMode = initialAuthMode
            binding.authModeTabs.selectTab(binding.authModeTabs.getTabAt(currentAuthMode))
            updateAuthModeVisibility()

            // Tab switching
            binding.authModeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    currentAuthMode = tab?.position ?: AUTH_LOGIN
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
                AUTH_LOGIN -> {
                    binding.loginModeContainer.visibility = View.VISIBLE
                    binding.tokenModeContainer.visibility = View.GONE
                }
                AUTH_TOKEN -> {
                    binding.loginModeContainer.visibility = View.GONE
                    binding.tokenModeContainer.visibility = View.VISIBLE
                }
            }
        }

        fun getUrl(): String = binding.urlInput.text?.toString()?.trim() ?: ""
        fun getAuthMode(): Int = currentAuthMode
        fun getUsername(): String = binding.usernameInput.text?.toString()?.trim() ?: ""
        fun getPassword(): String = binding.passwordInput.text?.toString() ?: ""
        fun getToken(): String = binding.tokenInput.text?.toString()?.trim() ?: ""
    }

    /**
     * Step 5: Music Assistant Login (conditional)
     *
     * Only shown when:
     * - isMusicAssistant checkbox is checked
     * - Local or Proxy connection is configured
     *
     * Performs eager authentication to validate credentials before saving.
     */
    class MaLoginStepFragment : Fragment() {
        var parentDialog: AddServerWizardDialog? = null
        var initialUsername: String = ""
        private var _binding: WizardStepMaLoginBinding? = null
        private val binding get() = _binding!!
        private var isTesting = false

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            _binding = WizardStepMaLoginBinding.inflate(inflater, container, false)
            return binding.root
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            // Pre-fill username if available
            binding.maUsernameInput.setText(initialUsername)

            // Test Connection button
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
                showStatus(
                    isError = true,
                    message = getString(R.string.credentials_required)
                )
                return
            }

            // Derive MA API URL from parent's connection data
            val dialog = parentDialog ?: return
            val apiUrl = deriveMaApiUrl(dialog)

            if (apiUrl == null) {
                showStatus(
                    isError = true,
                    message = getString(R.string.wizard_ma_requires_local_or_proxy)
                )
                return
            }

            // Show testing state
            setTesting(true)
            showStatus(isLoading = true, message = getString(R.string.wizard_ma_testing))

            // Perform login
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = MusicAssistantAuth.login(apiUrl, username, password)

                    setTesting(false)
                    showStatus(
                        isSuccess = true,
                        message = getString(R.string.wizard_ma_connection_success)
                    )

                    // Store token in parent dialog's state
                    dialog.maToken = result.accessToken
                    dialog.maUsername = username

                } catch (e: MusicAssistantAuth.AuthenticationException) {
                    setTesting(false)
                    showStatus(
                        isError = true,
                        message = getString(R.string.login_invalid_credentials)
                    )
                    dialog.maToken = null
                } catch (e: IOException) {
                    setTesting(false)
                    showStatus(
                        isError = true,
                        message = getString(R.string.error_network)
                    )
                    dialog.maToken = null
                } catch (e: Exception) {
                    setTesting(false)
                    showStatus(
                        isError = true,
                        message = getString(R.string.wizard_ma_connection_failed, e.message ?: "Unknown error")
                    )
                    dialog.maToken = null
                }
            }
        }

        /**
         * Derives the MA API URL from the parent dialog's connection data.
         * Uses local address (port 8095) or proxy URL.
         */
        private fun deriveMaApiUrl(dialog: AddServerWizardDialog): String? {
            // Try local first
            if (dialog.localAddress.isNotBlank()) {
                val host = dialog.localAddress.substringBefore(":")
                val port = MaSettings.getDefaultPort()
                return "ws://$host:$port/ws"
            }

            // Try proxy
            if (dialog.proxyUrl.isNotBlank()) {
                val baseUrl = dialog.normalizeProxyUrl(dialog.proxyUrl)
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

        private fun setTesting(testing: Boolean) {
            isTesting = testing
            binding.testConnectionButton.isEnabled = !testing
            binding.maUsernameInput.isEnabled = !testing
            binding.maPasswordInput.isEnabled = !testing
        }

        private fun showStatus(
            isLoading: Boolean = false,
            isSuccess: Boolean = false,
            isError: Boolean = false,
            message: String = ""
        ) {
            binding.connectionStatusContainer.visibility = View.VISIBLE

            // Progress indicator
            binding.connectionProgress.visibility = if (isLoading) View.VISIBLE else View.GONE

            // Status icon
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

            // Status text
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
    }
}
