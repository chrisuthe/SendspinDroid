package com.sendspindroid.ui.server

import androidx.lifecycle.ViewModel
import com.sendspindroid.model.UnifiedServer

/**
 * ViewModel for the Add Server Wizard Activity.
 *
 * Holds all wizard state with proper lifecycle management. This replaces the
 * dialog properties that were previously held in the DialogFragment.
 *
 * State is preserved across configuration changes (rotation, etc.) and
 * survives the Activity being recreated, which improves UX over the dialog approach.
 */
class AddServerWizardViewModel : ViewModel() {

    companion object {
        // Proxy auth modes
        const val AUTH_LOGIN = 0
        const val AUTH_TOKEN = 1
    }

    // Server identification
    var serverName: String = ""
    var setAsDefault: Boolean = false
    var isMusicAssistant: Boolean = false

    // Local connection
    var localAddress: String = ""

    // Remote connection
    var remoteId: String = ""

    // Proxy connection
    var proxyUrl: String = ""
    var proxyAuthMode: Int = AUTH_LOGIN
    var proxyUsername: String = ""
    var proxyPassword: String = ""
    var proxyToken: String = ""

    // Music Assistant login state (for eager auth)
    var maUsername: String = ""
    var maPassword: String = ""
    var maToken: String? = null  // Token obtained from successful MA login

    // Editing mode - non-null when editing an existing server
    var editingServer: UnifiedServer? = null
        private set

    // Loading state
    var isLoading: Boolean = false

    /**
     * Initialize the ViewModel for editing an existing server.
     * Populates all fields with the server's current configuration.
     */
    fun initForEdit(server: UnifiedServer, existingMaToken: String?) {
        editingServer = server
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
            proxyAuthMode = AUTH_TOKEN  // Default to token mode if we have saved proxy
        }

        // Load existing MA token if available
        if (isMusicAssistant) {
            maToken = existingMaToken
        }
    }

    /**
     * Check if we're in edit mode (modifying an existing server).
     */
    val isEditMode: Boolean
        get() = editingServer != null

    /**
     * Get the server ID - either from the server being edited, or generate a new one.
     * Note: This should only be called when actually saving, not for display purposes.
     */
    fun getServerId(): String {
        return editingServer?.id ?: com.sendspindroid.UnifiedServerRepository.generateId()
    }

    /**
     * Check if MA Login step should be shown based on current state.
     * MA Login is shown when:
     * - isMusicAssistant checkbox is checked
     * - Local or Proxy connection is configured (not Remote-only)
     */
    fun shouldShowMaLoginStep(): Boolean {
        val hasLocalOrProxy = localAddress.isNotBlank() || proxyUrl.isNotBlank()
        return isMusicAssistant && hasLocalOrProxy
    }

    /**
     * Clear all state (useful if the ViewModel is reused).
     */
    fun clear() {
        serverName = ""
        setAsDefault = false
        isMusicAssistant = false
        localAddress = ""
        remoteId = ""
        proxyUrl = ""
        proxyAuthMode = AUTH_LOGIN
        proxyUsername = ""
        proxyPassword = ""
        proxyToken = ""
        maUsername = ""
        maPassword = ""
        maToken = null
        editingServer = null
        isLoading = false
    }
}
