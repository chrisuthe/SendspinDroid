package com.sendspindroid.ui.remote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.URLUtil
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.sendspindroid.R
import com.sendspindroid.UserSettings
import com.sendspindroid.musicassistant.MaAuthHelper
import com.sendspindroid.musicassistant.transport.MaApiTransport
import com.sendspindroid.ui.dialogs.ProxyAuthModeDialog
import com.sendspindroid.ui.dialogs.ProxyConnectDialog as ProxyConnectDialogContent
import com.sendspindroid.ui.dialogs.ProxyCredentials
import com.sendspindroid.ui.dialogs.SavedProxyServer
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.launch
import com.sendspindroid.musicassistant.transport.MaTransportException
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

    private var onConnect: ((String, String, String?) -> Unit)? = null

    // Compose state
    private var isLoading by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private var savedServers by mutableStateOf<List<SavedProxyServer>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_SendSpinDroid_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        loadSavedServers()

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SendSpinTheme {
                    ProxyConnectDialogContent(
                        savedServers = savedServers,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onConnect = { url, authMode, credentials ->
                            handleConnect(url, authMode, credentials)
                        },
                        onDeleteSavedServer = { server ->
                            UserSettings.removeProxyServer(server.url)
                            loadSavedServers()
                        },
                        onDismiss = { dismiss() }
                    )
                }
            }
        }
    }

    private fun loadSavedServers() {
        savedServers = UserSettings.getSavedProxyServers().map { server ->
            SavedProxyServer(
                id = server.url,
                url = server.url,
                nickname = server.nickname,
                username = server.username ?: ""
            )
        }
    }

    /**
     * Handle connection request from Compose UI.
     */
    private fun handleConnect(url: String, authMode: ProxyAuthModeDialog, credentials: ProxyCredentials) {
        if (isLoading) return

        // Clear previous error on new attempt
        errorMessage = null

        if (!isValidProxyUrl(url)) {
            errorMessage = "Invalid proxy URL format"
            return
        }

        val normalizedUrl = normalizeUrl(url)

        when (credentials) {
            is ProxyCredentials.Login -> attemptLoginConnect(normalizedUrl, credentials)
            is ProxyCredentials.Token -> attemptTokenConnect(normalizedUrl, credentials)
        }
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

    /**
     * Connect using username/password login.
     * This will call the MA login API to get an access token.
     */
    private fun attemptLoginConnect(url: String, credentials: ProxyCredentials.Login) {
        isLoading = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Call the login API
                val result = MaAuthHelper.loginForToken(url, credentials.username, credentials.password)

                // Determine nickname: user-provided > MA user name > URL
                val serverNickname = credentials.nickname
                    ?: result.userName.takeIf { it.isNotBlank() }
                    ?: "Proxy Server"

                // Save the server with token (NOT password!) and username for convenience
                UserSettings.saveProxyServer(
                    url = url,
                    nickname = serverNickname,
                    authToken = result.accessToken,
                    username = credentials.username
                )

                isLoading = false

                // Invoke callback and dismiss
                onConnect?.invoke(url, result.accessToken, serverNickname)
                dismiss()

            } catch (e: MaApiTransport.AuthenticationException) {
                isLoading = false
                errorMessage = "Authentication failed: invalid username or password"
            } catch (e: MaTransportException) {
                isLoading = false
                errorMessage = "Connection error: ${e.message ?: "could not reach server"}"
            } catch (e: IOException) {
                isLoading = false
                errorMessage = "Network error: ${e.message ?: "check your connection"}"
            } catch (e: Exception) {
                isLoading = false
                errorMessage = "Unexpected error: ${e.message ?: "unknown failure"}"
            }
        }
    }

    /**
     * Connect using a pasted auth token (original flow).
     */
    private fun attemptTokenConnect(url: String, credentials: ProxyCredentials.Token) {
        val serverNickname = credentials.nickname ?: "Proxy Server"

        // Save the server for future use
        UserSettings.saveProxyServer(url, serverNickname, credentials.token)

        // Invoke callback and dismiss
        onConnect?.invoke(url, credentials.token, serverNickname)
        dismiss()
    }
}
