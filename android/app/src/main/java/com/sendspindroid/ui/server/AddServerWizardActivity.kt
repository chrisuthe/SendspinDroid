package com.sendspindroid.ui.server

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.sendspindroid.R
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.discovery.NsdDiscoveryManager
import com.sendspindroid.model.ConnectionPreference
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.ProxyConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.MaSettings
import com.sendspindroid.remote.RemoteConnection
import com.sendspindroid.ui.remote.QrScannerDialog
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.wizard.AddServerWizardScreen
import com.sendspindroid.ui.wizard.ConnectionTestState
import com.sendspindroid.ui.wizard.DiscoveredServerUi
import com.sendspindroid.ui.wizard.ProxyAuthMode
import com.sendspindroid.ui.wizard.RemoteAccessMethod
import com.sendspindroid.ui.wizard.WizardStep
import com.sendspindroid.ui.wizard.WizardStepAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Full-screen wizard Activity for adding or editing unified servers.
 *
 * Uses Jetpack Compose for UI via AddServerWizardScreen.
 * Handles lifecycle-aware operations like mDNS discovery and connection testing.
 */
class AddServerWizardActivity : FragmentActivity() {

    companion object {
        private const val TAG = "AddServerWizardActivity"

        // Intent extras
        const val EXTRA_EDIT_SERVER_ID = "edit_server_id"
        const val EXTRA_DISCOVERY_MODE = "discovery_mode"

        // Result extras
        const val RESULT_SERVER_ID = "server_id"
    }

    private val viewModel: AddServerWizardViewModel by viewModels()

    // Discovery manager for mDNS
    private var discoveryManager: NsdDiscoveryManager? = null
    private val discoveredServers = mutableMapOf<String, DiscoveredServer>()

    private data class DiscoveredServer(val name: String, val address: String, val path: String)

    // TV device detection (for QR scanner)
    private val isTvDevice: Boolean by lazy {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        // Initialize discovery manager
        discoveryManager = NsdDiscoveryManager(this, discoveryListener)

        setContent {
            SendSpinTheme {
                val state by viewModel.wizardState.collectAsState()

                // Auto-start discovery when entering FindServer step
                androidx.compose.runtime.LaunchedEffect(state.currentStep) {
                    if (state.currentStep == WizardStep.FindServer && !state.isSearching) {
                        startDiscovery()
                    }
                }

                AddServerWizardScreen(
                    state = state,
                    onClose = { finish() },
                    onBack = { handleBack() },
                    onNext = { handleNext() },
                    onSkip = { viewModel.onSkipLocal() },
                    onSave = { attemptSave() },
                    onStepAction = { action -> handleStepAction(action) }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryManager?.cleanup()
        discoveryManager = null
    }

    // ========================================================================
    // Navigation Handlers
    // ========================================================================

    private fun handleBack() {
        if (viewModel.onBack() == null) {
            finish()
        }
    }

    private fun handleNext() {
        when (viewModel.currentStep.value) {
            WizardStep.Welcome -> {
                viewModel.onNext()
            }
            WizardStep.FindServer -> {
                if (viewModel.localAddress.isBlank()) {
                    showToast(getString(R.string.wizard_local_address_hint))
                    return
                }
                startLocalConnectionTest()
            }
            WizardStep.MaLogin -> {
                if (viewModel.maToken != null) {
                    viewModel.onNext()
                } else {
                    startMaConnectionTest()
                }
            }
            WizardStep.RemoteChoice -> {
                viewModel.onNext()
            }
            WizardStep.RemoteId -> {
                if (viewModel.remoteId.isNotBlank()) {
                    if (!validateRemoteId()) return
                    startRemoteConnectionTest()
                } else {
                    viewModel.navigateTo(WizardStep.Save)
                }
            }
            WizardStep.Proxy -> {
                if (viewModel.proxyUrl.isNotBlank()) {
                    if (!validateProxy()) return
                    startProxyConnectionTest()
                } else {
                    viewModel.navigateTo(WizardStep.Save)
                }
            }
            WizardStep.RemoteOnlyWarning -> {
                viewModel.onNext()
            }
            WizardStep.Save -> {
                // Handled by onSave
            }
            else -> { /* Testing steps handled by test completion */ }
        }
    }

    // ========================================================================
    // Step Action Handler
    // ========================================================================

    private fun handleStepAction(action: WizardStepAction) {
        val needsActivityHandling = viewModel.handleStepAction(action)

        if (needsActivityHandling) {
            when (action) {
                WizardStepAction.FindOtherServers,
                WizardStepAction.StartDiscovery -> {
                    startDiscovery()
                }
                WizardStepAction.TestMaConnection -> {
                    startMaConnectionTest()
                }
                WizardStepAction.ScanQrCode -> {
                    openQrScanner()
                }
                else -> { /* Handled by ViewModel */ }
            }
        }
    }

    // ========================================================================
    // mDNS Discovery
    // ========================================================================

    private val discoveryListener = object : NsdDiscoveryManager.DiscoveryListener {
        override fun onServerDiscovered(name: String, address: String, path: String) {
            runOnUiThread {
                val server = DiscoveredServer(name, address, path)
                discoveredServers[name] = server
                updateDiscoveredServersInViewModel()
            }
        }

        override fun onServerLost(name: String) {
            runOnUiThread {
                discoveredServers.remove(name)
                updateDiscoveredServersInViewModel()
            }
        }

        override fun onDiscoveryStarted() {
            runOnUiThread {
                viewModel.setSearching(true)
            }
        }

        override fun onDiscoveryStopped() {
            runOnUiThread {
                viewModel.setSearching(false)
            }
        }

        override fun onDiscoveryError(error: String) {
            runOnUiThread {
                viewModel.setSearching(false)
                showToast(error)
            }
        }
    }

    private fun startDiscovery() {
        discoveredServers.clear()
        viewModel.updateDiscoveredServers(emptyList())
        discoveryManager?.startDiscovery()
    }

    private fun stopDiscovery() {
        discoveryManager?.stopDiscovery()
    }

    private fun updateDiscoveredServersInViewModel() {
        val servers = discoveredServers.values.map { server ->
            DiscoveredServerUi(
                id = server.name,
                name = server.name,
                address = server.address
            )
        }
        viewModel.updateDiscoveredServers(servers)
    }

    // ========================================================================
    // QR Scanner
    // ========================================================================

    private fun openQrScanner() {
        if (isTvDevice) {
            showToast(getString(R.string.qr_scanner_invalid))
            return
        }

        QrScannerDialog.show(supportFragmentManager) { result ->
            val cleanedId = result.replace("-", "").uppercase()
            viewModel.remoteId = cleanedId
        }
    }

    // ========================================================================
    // Connection Testing
    // ========================================================================

    private fun startLocalConnectionTest() {
        viewModel.navigateTo(WizardStep.TestingLocal)

        lifecycleScope.launch {
            delay(500) // Brief delay for UI to show

            val result = testLocalConnection(viewModel.localAddress)

            result.fold(
                onSuccess = { responseCode ->
                    delay(500) // Brief success display
                    viewModel.onLocalTestSuccess("Connected (HTTP $responseCode)")
                },
                onFailure = { error ->
                    Log.e(TAG, "Local connection test failed", error)
                    viewModel.onLocalTestFailed(error.message ?: "Unknown error")
                }
            )
        }
    }

    private suspend fun testLocalConnection(address: String): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                // Build WebSocket URL
                val wsUrl = if (address.contains(":")) {
                    "ws://$address/sendspin"
                } else {
                    "ws://$address:8927/sendspin"
                }

                Log.d(TAG, "Testing WebSocket connection to: $wsUrl")

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                // Use OkHttp's WebSocket to attempt a proper handshake
                val request = okhttp3.Request.Builder()
                    .url(wsUrl)
                    .build()

                var resultCode = 0
                var connectionSuccess = false
                var errorMessage: String? = null
                val latch = java.util.concurrent.CountDownLatch(1)

                val listener = object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        Log.d(TAG, "WebSocket connection opened, code: ${response.code}")
                        resultCode = response.code
                        connectionSuccess = true
                        webSocket.close(1000, "Test complete")
                        latch.countDown()
                    }

                    override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                        Log.d(TAG, "WebSocket connection failed: ${t.message}, response code: ${response?.code}")
                        resultCode = response?.code ?: 0
                        errorMessage = t.message
                        // If we got a response code, the server is reachable even if WebSocket failed
                        connectionSuccess = response != null
                        latch.countDown()
                    }
                }

                client.newWebSocket(request, listener)
                latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
                client.dispatcher.executorService.shutdown()

                if (connectionSuccess) {
                    Result.success(resultCode)
                } else {
                    Result.failure(IOException(errorMessage ?: "Connection failed"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection test exception", e)
                Result.failure(e)
            }
        }
    }

    private fun startMaConnectionTest() {
        viewModel.testMaConnection { success ->
            if (success) {
                viewModel.navigateTo(WizardStep.RemoteChoice)
            }
        }
    }

    private fun startRemoteConnectionTest() {
        viewModel.navigateTo(WizardStep.TestingRemote)

        lifecycleScope.launch {
            delay(500)

            val result = testRemoteConnection(viewModel.remoteId)

            result.fold(
                onSuccess = { message ->
                    delay(500)
                    viewModel.onRemoteTestSuccess(message)
                },
                onFailure = { error ->
                    Log.e(TAG, "Remote connection test failed", error)
                    viewModel.onRemoteTestFailed(error.message ?: "Unknown error")
                }
            )
        }
    }

    private suspend fun testRemoteConnection(remoteId: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val parsed = RemoteConnection.parseRemoteId(remoteId)
                    ?: return@withContext Result.failure(IllegalArgumentException("Invalid Remote ID format"))

                // Validate the remote ID format - actual connection will be tested when used
                if (RemoteConnection.isValidRemoteId(parsed)) {
                    Result.success("Remote ID format valid")
                } else {
                    Result.failure(IllegalArgumentException("Invalid Remote ID format"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Remote test exception", e)
                Result.failure(e)
            }
        }
    }

    private fun startProxyConnectionTest() {
        viewModel.navigateTo(WizardStep.TestingRemote)

        lifecycleScope.launch {
            delay(500)

            val result = testProxyConnection()

            result.fold(
                onSuccess = { message ->
                    delay(500)
                    viewModel.onRemoteTestSuccess(message)
                },
                onFailure = { error ->
                    Log.e(TAG, "Proxy connection test failed", error)
                    viewModel.onRemoteTestFailed(error.message ?: "Unknown error")
                }
            )
        }
    }

    private suspend fun testProxyConnection(): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = viewModel.normalizeProxyUrl(viewModel.proxyUrl)

                // Convert HTTP URL to WebSocket URL for proper testing
                val wsUrl = normalizedUrl
                    .replace("https://", "wss://")
                    .replace("http://", "ws://")

                Log.d(TAG, "Testing WebSocket proxy connection to: $wsUrl")

                val clientBuilder = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)

                val client = clientBuilder.build()

                val requestBuilder = okhttp3.Request.Builder()
                    .url(wsUrl)

                // Add authentication header if using token mode
                if (viewModel.proxyAuthMode == AddServerWizardViewModel.AUTH_TOKEN &&
                    viewModel.proxyToken.isNotBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer ${viewModel.proxyToken}")
                }

                var connectionSuccess = false
                var errorMessage: String? = null
                val latch = java.util.concurrent.CountDownLatch(1)

                val listener = object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        Log.d(TAG, "Proxy WebSocket connection opened, code: ${response.code}")
                        connectionSuccess = true
                        webSocket.close(1000, "Test complete")
                        latch.countDown()
                    }

                    override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                        Log.d(TAG, "Proxy WebSocket connection failed: ${t.message}, response code: ${response?.code}")
                        errorMessage = t.message
                        // If we got a response, the proxy is reachable even if WebSocket upgrade failed
                        connectionSuccess = response != null
                        latch.countDown()
                    }
                }

                client.newWebSocket(requestBuilder.build(), listener)
                latch.await(11, java.util.concurrent.TimeUnit.SECONDS)
                client.dispatcher.executorService.shutdown()

                if (connectionSuccess) {
                    Result.success("Proxy connection successful")
                } else {
                    Result.failure(IOException(errorMessage ?: "Proxy connection failed"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proxy test exception", e)
                Result.failure(e)
            }
        }
    }

    // ========================================================================
    // Validation
    // ========================================================================

    private fun validateRemoteId(): Boolean {
        if (viewModel.remoteId.isNotBlank()) {
            val parsed = RemoteConnection.parseRemoteId(viewModel.remoteId)
            if (parsed == null) {
                showToast(getString(R.string.remote_id_invalid))
                return false
            }
        }
        return true
    }

    private fun validateProxy(): Boolean {
        if (viewModel.proxyUrl.isNotBlank()) {
            if (viewModel.proxyAuthMode == AddServerWizardViewModel.AUTH_TOKEN &&
                viewModel.proxyToken.isBlank()) {
                showToast(getString(R.string.auth_token_required))
                return false
            }
            if (viewModel.proxyAuthMode == AddServerWizardViewModel.AUTH_LOGIN &&
                (viewModel.proxyUsername.isBlank() || viewModel.proxyPassword.isBlank())) {
                showToast(getString(R.string.credentials_required))
                return false
            }
        }
        return true
    }

    // ========================================================================
    // Save
    // ========================================================================

    private fun attemptSave() {
        // Validate
        if (viewModel.serverName.isBlank()) {
            showToast(getString(R.string.wizard_name_required))
            return
        }

        if (!viewModel.hasValidConnectionMethod()) {
            showToast(getString(R.string.wizard_at_least_one_method))
            return
        }

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
    // Utility
    // ========================================================================

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
