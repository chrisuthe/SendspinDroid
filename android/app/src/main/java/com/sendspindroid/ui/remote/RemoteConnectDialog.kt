package com.sendspindroid.ui.remote

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sendspindroid.R
import com.sendspindroid.UserSettings
import com.sendspindroid.databinding.DialogRemoteConnectBinding
import com.sendspindroid.databinding.ItemSavedRemoteServerBinding
import com.sendspindroid.remote.RemoteConnection

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

    private var _binding: DialogRemoteConnectBinding? = null
    private val binding get() = _binding!!

    private var onConnect: ((String, String?) -> Unit)? = null
    private lateinit var savedServersAdapter: SavedServersAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_SendSpinDroid_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogRemoteConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRemoteIdInput()
        setupSavedServersList()
        setupButtons()
        loadSavedServers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRemoteIdInput() {
        // Format input as user types (add dashes)
        binding.remoteIdInput.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            private var lastLength = 0

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                lastLength = s?.length ?: 0
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || s == null) return

                isFormatting = true

                // Remove existing dashes and non-alphanumeric chars
                val cleaned = s.toString().uppercase().filter { it.isLetterOrDigit() }

                // Add dashes every 5 characters
                val formatted = cleaned.chunked(5).joinToString("-")

                // Only update if different (avoids cursor jumping)
                if (formatted != s.toString()) {
                    val cursorPos = binding.remoteIdInput.selectionEnd
                    s.replace(0, s.length, formatted)

                    // Adjust cursor position
                    val newPos = minOf(cursorPos + (formatted.length - lastLength), formatted.length)
                    binding.remoteIdInput.setSelection(maxOf(0, newPos))
                }

                isFormatting = false
                validateInput()
            }
        })

        // Handle keyboard done action
        binding.remoteIdInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptConnect()
                true
            } else {
                false
            }
        }

        // QR code scanner icon
        binding.remoteIdInputLayout.setEndIconOnClickListener {
            openQrScanner()
        }
    }

    private fun setupSavedServersList() {
        savedServersAdapter = SavedServersAdapter(
            onServerClick = { server ->
                // Fill in the Remote ID and nickname
                binding.remoteIdInput.setText(RemoteConnection.formatRemoteId(server.remoteId))
                binding.nicknameInput.setText(server.nickname)
                validateInput()
            },
            onDeleteClick = { server ->
                UserSettings.removeRemoteServer(server.remoteId)
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
        val servers = UserSettings.getSavedRemoteServers()

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
        val input = binding.remoteIdInput.text?.toString() ?: ""
        val remoteId = RemoteConnection.parseRemoteId(input)

        val isValid = remoteId != null
        binding.connectButton.isEnabled = isValid

        // Show/hide error
        if (input.isNotEmpty() && !isValid) {
            binding.remoteIdInputLayout.error = getString(R.string.remote_id_invalid)
        } else {
            binding.remoteIdInputLayout.error = null
        }

        return isValid
    }

    private fun attemptConnect() {
        val input = binding.remoteIdInput.text?.toString() ?: ""
        val remoteId = RemoteConnection.parseRemoteId(input)

        if (remoteId == null) {
            binding.remoteIdInputLayout.error = getString(R.string.remote_id_invalid)
            return
        }

        val nickname = binding.nicknameInput.text?.toString()?.takeIf { it.isNotBlank() }

        // Save the server for future use
        UserSettings.saveRemoteServer(remoteId, nickname ?: "Remote Server")

        // Invoke callback and dismiss
        onConnect?.invoke(remoteId, nickname)
        dismiss()
    }

    private fun openQrScanner() {
        QrScannerDialog.show(childFragmentManager) { scannedId ->
            // Fill in the scanned Remote ID
            binding.remoteIdInput.setText(RemoteConnection.formatRemoteId(scannedId))
            validateInput()
        }
    }

    /**
     * Adapter for saved remote servers list.
     */
    private class SavedServersAdapter(
        private val onServerClick: (UserSettings.SavedRemoteServer) -> Unit,
        private val onDeleteClick: (UserSettings.SavedRemoteServer) -> Unit
    ) : RecyclerView.Adapter<SavedServersAdapter.ViewHolder>() {

        private var servers: List<UserSettings.SavedRemoteServer> = emptyList()

        fun submitList(list: List<UserSettings.SavedRemoteServer>) {
            servers = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSavedRemoteServerBinding.inflate(
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
            private val binding: ItemSavedRemoteServerBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(server: UserSettings.SavedRemoteServer) {
                binding.serverNickname.text = server.nickname
                binding.serverRemoteId.text = server.formattedId
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
