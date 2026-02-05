package com.sendspindroid.ui.remote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.DialogFragment
import com.sendspindroid.R
import com.sendspindroid.UserSettings
import com.sendspindroid.remote.RemoteConnection
import com.sendspindroid.ui.dialogs.RemoteConnectDialog as RemoteConnectDialogContent
import com.sendspindroid.ui.dialogs.SavedRemoteServer
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Dialog for connecting to Music Assistant servers via Remote Access.
 *
 * Features:
 * - Manual Remote ID input with validation
 * - QR code scanning via camera
 * - List of saved remote servers for quick reconnection
 * - Optional nickname for saving servers
 *
 * ## Usage
 * ```kotlin
 * RemoteConnectDialog.show(supportFragmentManager) { remoteId, nickname ->
 *     // Connect using the Remote ID
 * }
 * ```
 */
class RemoteConnectDialog : DialogFragment() {

    companion object {
        private const val TAG = "RemoteConnectDialog"

        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            onConnect: (remoteId: String, nickname: String?) -> Unit
        ): RemoteConnectDialog {
            val dialog = RemoteConnectDialog()
            dialog.onConnect = onConnect
            dialog.show(fragmentManager, TAG)
            return dialog
        }
    }

    private var onConnect: ((String, String?) -> Unit)? = null

    // Compose state
    private var savedServers by mutableStateOf<List<SavedRemoteServer>>(emptyList())
    private var scannedRemoteId by mutableStateOf("")

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
                    RemoteConnectDialogContent(
                        savedServers = savedServers,
                        initialRemoteId = scannedRemoteId,
                        onConnect = { remoteId, nickname ->
                            handleConnect(remoteId, nickname)
                        },
                        onScanQr = { openQrScanner() },
                        onDeleteSavedServer = { server ->
                            UserSettings.removeRemoteServer(server.remoteId)
                            loadSavedServers()
                        },
                        onDismiss = { dismiss() }
                    )
                }
            }
        }
    }

    private fun loadSavedServers() {
        savedServers = UserSettings.getSavedRemoteServers().map { server ->
            SavedRemoteServer(
                id = server.remoteId,
                remoteId = server.remoteId,
                nickname = server.nickname
            )
        }
    }

    private fun handleConnect(remoteId: String, nickname: String?) {
        // Parse and validate the Remote ID
        val parsed = RemoteConnection.parseRemoteId(remoteId)
        if (parsed == null) {
            return
        }

        // Save the server for future use
        UserSettings.saveRemoteServer(parsed, nickname ?: "Remote Server")

        // Invoke callback and dismiss
        onConnect?.invoke(parsed, nickname)
        dismiss()
    }

    private fun openQrScanner() {
        QrScannerDialog.show(childFragmentManager) { result ->
            // Update the scanned ID state - Compose will recompose with this value
            scannedRemoteId = result
        }
    }
}
