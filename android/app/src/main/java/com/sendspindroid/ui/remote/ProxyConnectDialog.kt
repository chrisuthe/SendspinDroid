package com.sendspindroid.ui.remote

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.URLUtil
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.sendspindroid.R
import com.sendspindroid.UserSettings
import com.sendspindroid.databinding.DialogProxyConnectBinding
import com.sendspindroid.databinding.ItemSavedProxyServerBinding
import com.sendspindroid.sendspin.MusicAssistantAuth
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Dialog for connecting to Music Assistant servers via authenticated reverse proxy.
 *
 * Features:
 * - Two authentication modes:
 *   - **Login mode**: Enter username/password, app fetches token automatically
 *   - **Token mode**: Paste a long-lived token directly
 * - Proxy URL input with validation
 * - Optional nickname for saving servers
 * - List of saved proxy servers for quick reconnection
 *
 * ## Security
 * - Passwords are NEVER stored - only the access token is saved
 * - Saved servers store the username for convenience (for re-login prompts)
 * - All connections should use HTTPS
 *
 * ## Usage
 * ```kotlin
 * ProxyConnectDialog.show(supportFragmentManager) { url, authToken, nickname ->
 *     // Connect using the proxy URL and auth token
 * }
 * ```
 */
class ProxyConnectDialog : DialogFragment() {

    companion object {
        private const val TAG = "ProxyConnectDialog"

        // Tab indices
        private const val TAB_LOGIN = 0
        private const val TAB_TOKEN = 1

        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            onConnect: (url: String, authToken: String, nickname: String?) -> Unit
        ): ProxyConnectDialog {
            val dialog = ProxyConnectDialog()
            dialog.onConnect = onConnect
            dialog.show(fragmentManager, TAG)
            return dialog
        }
    }

    private var _binding: DialogProxyConnectBinding? = null
    private val binding get() = _binding!!

    private var onConnect: ((String, String, String?) -> Unit)? = null
    private lateinit var savedServersAdapter: SavedServersAdapter

    // Current authentication mode
    private var currentAuthMode = TAB_LOGIN
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_SendSpinDroid_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogProxyConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAuthModeTabs()
        setupUrlInput()
        setupLoginFields()
        setupTokenInput()
        setupSavedServersList()
        setupButtons()
        loadSavedServers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Setup the Login/Token tab switching.
     */
    private fun setupAuthModeTabs() {
        binding.authModeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentAuthMode = tab?.position ?: TAB_LOGIN
                updateAuthModeVisibility()
                validateInput()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Initialize visibility based on default tab (Login)
        updateAuthModeVisibility()
    }

    /**
     * Show/hide fields based on the selected auth mode.
     */
    private fun updateAuthModeVisibility() {
        when (currentAuthMode) {
            TAB_LOGIN -> {
                binding.loginModeFields.visibility = View.VISIBLE
                binding.tokenModeFields.visibility = View.GONE
            }
            TAB_TOKEN -> {
                binding.loginModeFields.visibility = View.GONE
                binding.tokenModeFields.visibility = View.VISIBLE
            }
        }
    }

    private fun setupUrlInput() {
        binding.proxyUrlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateInput()
            }
        })
    }

    private fun setupLoginFields() {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateInput()
            }
        }

        binding.usernameInput.addTextChangedListener(textWatcher)
        binding.passwordInput.addTextChangedListener(textWatcher)

        // Handle keyboard done action on password field
        binding.passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && !isLoading) {
                attemptConnect()
                true
            } else {
                false
            }
        }
    }

    private fun setupTokenInput() {
        binding.authTokenInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateInput()
            }
        })

        // Handle keyboard done action
        binding.authTokenInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && !isLoading) {
                attemptConnect()
                true
            } else {
                false
            }
        }
    }

    private fun setupSavedServersList() {
        savedServersAdapter = SavedServersAdapter(
            onServerClick = { server ->
                // Fill in the URL and nickname
                binding.proxyUrlInput.setText(server.url)
                binding.nicknameInput.setText(server.nickname)

                // If server has a saved token, switch to token mode and fill it
                if (server.authToken.isNotBlank()) {
                    binding.authModeTabs.selectTab(binding.authModeTabs.getTabAt(TAB_TOKEN))
                    binding.authTokenInput.setText(server.authToken)
                }

                // If server has a saved username, fill it in login mode
                if (!server.username.isNullOrBlank()) {
                    binding.usernameInput.setText(server.username)
                }

                validateInput()
            },
            onDeleteClick = { server ->
                UserSettings.removeProxyServer(server.url)
                loadSavedServers()
            }
        )

        binding.savedServersList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = savedServersAdapter
        }
    }

    private fun setupButtons() {
        binding.cancelButton.setOnClickListener {
            dismiss()
        }

        binding.connectButton.setOnClickListener {
            attemptConnect()
        }

        // Initially disable connect button
        binding.connectButton.isEnabled = false
    }

    private fun loadSavedServers() {
        val servers = UserSettings.getSavedProxyServers()

        if (servers.isEmpty()) {
            binding.savedServersList.visibility = View.GONE
            binding.noSavedServersText.visibility = View.VISIBLE
        } else {
            binding.savedServersList.visibility = View.VISIBLE
            binding.noSavedServersText.visibility = View.GONE
            savedServersAdapter.submitList(servers)
        }
    }

    private fun validateInput(): Boolean {
        if (isLoading) {
            binding.connectButton.isEnabled = false
            return false
        }

        val url = binding.proxyUrlInput.text?.toString()?.trim() ?: ""

        // Validate URL
        val isUrlValid = url.isNotEmpty() && isValidProxyUrl(url)

        // Validate auth based on mode
        val isAuthValid = when (currentAuthMode) {
            TAB_LOGIN -> {
                val username = binding.usernameInput.text?.toString() ?: ""
                val password = binding.passwordInput.text?.toString() ?: ""
                username.isNotBlank() && password.isNotBlank()
            }
            TAB_TOKEN -> {
                val token = binding.authTokenInput.text?.toString() ?: ""
                token.isNotBlank()
            }
            else -> false
        }

        val isValid = isUrlValid && isAuthValid
        binding.connectButton.isEnabled = isValid

        // Show/hide URL error
        if (url.isNotEmpty() && !isUrlValid) {
            binding.proxyUrlInputLayout.error = getString(R.string.proxy_url_invalid)
        } else {
            binding.proxyUrlInputLayout.error = null
        }

        // Clear other errors
        binding.usernameInputLayout.error = null
        binding.passwordInputLayout.error = null
        binding.authTokenInputLayout.error = null

        return isValid
    }

    /**
     * Validate proxy URL format.
     * Accepts: https://..., http://..., wss://..., ws://..., or domain/path format
     */
    private fun isValidProxyUrl(url: String): Boolean {
        // Add scheme if missing for validation
        val urlWithScheme = when {
            url.startsWith("https://") || url.startsWith("http://") -> url
            url.startsWith("wss://") || url.startsWith("ws://") -> url
            else -> "https://$url"
        }

        return URLUtil.isValidUrl(urlWithScheme) && urlWithScheme.contains(".")
    }

    /**
     * Normalize URL to include scheme and /sendspin path if missing.
     */
    private fun normalizeUrl(url: String): String {
        var normalized = when {
            url.startsWith("https://") || url.startsWith("http://") -> url
            url.startsWith("wss://") || url.startsWith("ws://") -> url
            else -> "https://$url"
        }

        // Ensure URL ends with /sendspin (the WebSocket endpoint)
        if (!normalized.contains("/sendspin")) {
            normalized = normalized.trimEnd('/') + "/sendspin"
        }

        return normalized
    }

    private fun attemptConnect() {
        if (isLoading) return

        val url = binding.proxyUrlInput.text?.toString()?.trim() ?: ""

        if (!isValidProxyUrl(url)) {
            binding.proxyUrlInputLayout.error = getString(R.string.proxy_url_invalid)
            return
        }

        val normalizedUrl = normalizeUrl(url)
        val nickname = binding.nicknameInput.text?.toString()?.takeIf { it.isNotBlank() }

        when (currentAuthMode) {
            TAB_LOGIN -> attemptLoginConnect(normalizedUrl, nickname)
            TAB_TOKEN -> attemptTokenConnect(normalizedUrl, nickname)
        }
    }

    /**
     * Connect using username/password login.
     * This will call the MA login API to get an access token.
     */
    private fun attemptLoginConnect(url: String, nickname: String?) {
        val username = binding.usernameInput.text?.toString()?.trim() ?: ""
        val password = binding.passwordInput.text?.toString() ?: ""

        if (username.isBlank() || password.isBlank()) {
            if (username.isBlank()) {
                binding.usernameInputLayout.error = getString(R.string.credentials_required)
            }
            if (password.isBlank()) {
                binding.passwordInputLayout.error = getString(R.string.credentials_required)
            }
            return
        }

        setLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Call the login API
                val result = MusicAssistantAuth.login(url, username, password)

                // Determine nickname: user-provided > MA user name > URL
                val serverNickname = nickname
                    ?: result.userName.takeIf { it.isNotBlank() }
                    ?: "Proxy Server"

                // Save the server with token (NOT password!) and username for convenience
                UserSettings.saveProxyServer(
                    url = url,
                    nickname = serverNickname,
                    authToken = result.accessToken,
                    username = username
                )

                setLoading(false)

                // Invoke callback and dismiss
                onConnect?.invoke(url, result.accessToken, serverNickname)
                dismiss()

            } catch (e: MusicAssistantAuth.AuthenticationException) {
                setLoading(false)
                showError(e.message ?: getString(R.string.login_invalid_credentials))
                binding.passwordInputLayout.error = e.message
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

    /**
     * Connect using a pasted auth token (original flow).
     */
    private fun attemptTokenConnect(url: String, nickname: String?) {
        val token = binding.authTokenInput.text?.toString() ?: ""

        if (token.isBlank()) {
            binding.authTokenInputLayout.error = getString(R.string.auth_token_required)
            return
        }

        val serverNickname = nickname ?: "Proxy Server"

        // Save the server for future use
        UserSettings.saveProxyServer(url, serverNickname, token)

        // Invoke callback and dismiss
        onConnect?.invoke(url, token, serverNickname)
        dismiss()
    }

    /**
     * Show/hide loading state during login.
     */
    private fun setLoading(loading: Boolean) {
        isLoading = loading

        binding.loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
        binding.connectButton.isEnabled = !loading
        binding.cancelButton.isEnabled = !loading

        // Disable input fields during loading
        binding.proxyUrlInput.isEnabled = !loading
        binding.usernameInput.isEnabled = !loading
        binding.passwordInput.isEnabled = !loading
        binding.authTokenInput.isEnabled = !loading
        binding.nicknameInput.isEnabled = !loading

        // Disable tab switching during loading
        for (i in 0 until binding.authModeTabs.tabCount) {
            binding.authModeTabs.getTabAt(i)?.view?.isClickable = !loading
        }
    }

    /**
     * Show an error message.
     */
    private fun showError(message: String) {
        view?.let { v ->
            Snackbar.make(v, message, Snackbar.LENGTH_LONG).show()
        }
    }

    /**
     * Adapter for saved proxy servers list.
     */
    private class SavedServersAdapter(
        private val onServerClick: (UserSettings.SavedProxyServer) -> Unit,
        private val onDeleteClick: (UserSettings.SavedProxyServer) -> Unit
    ) : RecyclerView.Adapter<SavedServersAdapter.ViewHolder>() {

        private var servers: List<UserSettings.SavedProxyServer> = emptyList()

        fun submitList(list: List<UserSettings.SavedProxyServer>) {
            servers = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSavedProxyServerBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(servers[position])
        }

        override fun getItemCount(): Int = servers.size

        inner class ViewHolder(
            private val binding: ItemSavedProxyServerBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(server: UserSettings.SavedProxyServer) {
                binding.serverNickname.text = server.nickname
                binding.serverUrl.text = server.displayUrl
                binding.lastConnected.text = server.lastConnectedAgo

                binding.root.setOnClickListener {
                    onServerClick(server)
                }

                binding.deleteButton.setOnClickListener {
                    onDeleteClick(server)
                }
            }
        }
    }
}
