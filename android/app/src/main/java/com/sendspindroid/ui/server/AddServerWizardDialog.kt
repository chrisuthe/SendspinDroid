package com.sendspindroid.ui.server

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
import com.sendspindroid.databinding.WizardStepNameBinding
import com.sendspindroid.databinding.WizardStepProxyBinding
import com.sendspindroid.databinding.WizardStepRemoteBinding
import com.sendspindroid.model.*
import com.sendspindroid.remote.RemoteConnection
import com.sendspindroid.sendspin.MusicAssistantAuth
import com.sendspindroid.ui.remote.QrScannerDialog
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Multi-step wizard dialog for adding a unified server.
 *
 * ## Steps
 * 1. **Name** (required) - Server nickname
 * 2. **Local** (optional) - IP:port for local network
 * 3. **Remote** (optional) - 26-character Remote ID or QR scan
 * 4. **Proxy** (optional) - URL + login/token authentication
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

        // Step indices
        private const val STEP_NAME = 0
        private const val STEP_LOCAL = 1
        private const val STEP_REMOTE = 2
        private const val STEP_PROXY = 3
        private const val TOTAL_STEPS = 4

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
    private var localAddress: String = ""
    private var remoteId: String = ""
    private var proxyUrl: String = ""
    private var proxyAuthMode: Int = AUTH_LOGIN
    private var proxyUsername: String = ""
    private var proxyPassword: String = ""
    private var proxyToken: String = ""

    // Step fragments (for accessing views)
    private var nameFragment: NameStepFragment? = null
    private var localFragment: LocalStepFragment? = null
    private var remoteFragment: RemoteStepFragment? = null
    private var proxyFragment: ProxyStepFragment? = null

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
        }

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
            tab.text = when (position) {
                STEP_NAME -> getString(R.string.wizard_step_name)
                STEP_LOCAL -> getString(R.string.wizard_step_local)
                STEP_REMOTE -> getString(R.string.wizard_step_remote)
                STEP_PROXY -> getString(R.string.wizard_step_proxy)
                else -> ""
            }
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
            if (binding.wizardPager.currentItem < TOTAL_STEPS - 1) {
                binding.wizardPager.currentItem = binding.wizardPager.currentItem + 1
            }
        }

        binding.nextButton.setOnClickListener {
            val currentStep = binding.wizardPager.currentItem

            if (currentStep == TOTAL_STEPS - 1) {
                // Final step - attempt save
                attemptSave()
            } else {
                // Validate current step before proceeding
                if (validateCurrentStep()) {
                    binding.wizardPager.currentItem = currentStep + 1
                }
            }
        }
    }

    private fun updateButtonState() {
        val currentStep = binding.wizardPager.currentItem
        val isFirstStep = currentStep == 0
        val isLastStep = currentStep == TOTAL_STEPS - 1

        // Back button: hide on first step
        binding.backButton.visibility = if (isFirstStep) View.INVISIBLE else View.VISIBLE

        // Skip button: show only on optional steps (not name, not final)
        binding.skipButton.visibility = if (!isFirstStep && !isLastStep) View.VISIBLE else View.GONE

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
            else -> true
        }
    }

    private fun collectNameData() {
        serverName = nameFragment?.getName() ?: serverName
        setAsDefault = nameFragment?.isSetAsDefault() ?: setAsDefault
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
            isDefaultServer = setAsDefault
        )

        // Save to repository
        UnifiedServerRepository.saveServer(server)

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

        override fun getItemCount(): Int = TOTAL_STEPS

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                STEP_NAME -> NameStepFragment().also {
                    nameFragment = it
                    it.initialName = serverName
                    it.initialSetAsDefault = setAsDefault
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
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }
    }

    // ========== Step Fragments ==========

    /**
     * Step 1: Server Name
     */
    class NameStepFragment : Fragment() {
        var initialName: String = ""
        var initialSetAsDefault: Boolean = false
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
        }

        override fun onDestroyView() {
            super.onDestroyView()
            _binding = null
        }

        fun getName(): String = binding.nameInput.text?.toString()?.trim() ?: ""

        fun isSetAsDefault(): Boolean = binding.setAsDefaultCheckbox.isChecked

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

            // QR scanner button
            binding.remoteIdInputLayout.setEndIconOnClickListener {
                QrScannerDialog.show(childFragmentManager) { scannedId ->
                    binding.remoteIdInput.setText(RemoteConnection.formatRemoteId(scannedId))
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
}
