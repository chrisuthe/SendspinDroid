package com.sendspindroid.ui.server

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sendspindroid.R
import com.sendspindroid.databinding.ItemUnifiedServerBinding
import com.sendspindroid.model.UnifiedServer

/**
 * RecyclerView adapter for displaying unified servers with Material 3 design.
 *
 * Features:
 * - ListAdapter with DiffUtil for efficient list updates
 * - ViewBinding for type-safe view access
 * - Connection status indicator (colored dot)
 * - Connection method icons (WiFi, Cloud, VPN)
 * - Quick Connect chip for discovered servers
 * - Ripple effect for touch feedback
 *
 * ## Callback Interface
 * Use [Callback] to handle user interactions:
 * - `onServerClick`: User tapped the server card
 * - `onQuickConnect`: User tapped the Quick Connect chip on discovered server
 * - `onServerLongClick`: User long-pressed for context menu
 */
class UnifiedServerAdapter(
    private val callback: Callback
) : ListAdapter<UnifiedServer, UnifiedServerAdapter.ServerViewHolder>(ServerDiffCallback()) {

    /**
     * Callback interface for server interactions.
     */
    interface Callback {
        /** Called when user taps a server card */
        fun onServerClick(server: UnifiedServer)

        /** Called when user taps Quick Connect on a discovered server */
        fun onQuickConnect(server: UnifiedServer)

        /** Called when user long-presses a server (for context menu) */
        fun onServerLongClick(server: UnifiedServer): Boolean
    }

    /**
     * Status of a server for UI display.
     */
    enum class ServerStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    // Track status per server ID
    private val serverStatuses = mutableMapOf<String, ServerStatus>()
    private var connectedServerId: String? = null

    /**
     * Update the status of a specific server.
     */
    fun setServerStatus(serverId: String, status: ServerStatus) {
        serverStatuses[serverId] = status
        if (status == ServerStatus.CONNECTED) {
            connectedServerId = serverId
        } else if (connectedServerId == serverId && status != ServerStatus.CONNECTING) {
            connectedServerId = null
        }
        notifyDataSetChanged() // Simple approach for status updates
    }

    /**
     * Clear all server statuses (e.g., on disconnect).
     */
    fun clearStatuses() {
        serverStatuses.clear()
        connectedServerId = null
        notifyDataSetChanged()
    }

    class ServerViewHolder(
        private val binding: ItemUnifiedServerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            server: UnifiedServer,
            status: ServerStatus,
            callback: Callback
        ) {
            // Server name
            binding.serverName.text = server.name

            // Subtitle: show address for discovered, last connected for saved
            binding.serverSubtitle.text = when {
                server.isDiscovered && server.local != null -> server.local.address
                server.lastConnectedMs > 0 -> server.formattedLastConnected
                server.local != null -> server.local.address
                server.remote != null -> "Remote Access"
                server.proxy != null -> "Proxy"
                else -> ""
            }

            // Connection method icons
            binding.iconLocal.visibility = if (server.local != null) View.VISIBLE else View.GONE
            binding.iconRemote.visibility = if (server.remote != null) View.VISIBLE else View.GONE
            binding.iconProxy.visibility = if (server.proxy != null) View.VISIBLE else View.GONE

            // Default server indicator (star icon)
            binding.defaultIndicator.visibility = if (server.isDefaultServer) View.VISIBLE else View.GONE

            // Quick Connect chip (only for discovered servers)
            binding.quickConnectChip.visibility = if (server.isDiscovered) View.VISIBLE else View.GONE
            binding.quickConnectChip.setOnClickListener {
                callback.onQuickConnect(server)
            }

            // Status indicator color
            val statusColor = when (status) {
                ServerStatus.DISCONNECTED -> R.color.status_disconnected
                ServerStatus.CONNECTING -> R.color.status_connecting
                ServerStatus.CONNECTED -> R.color.status_connected
                ServerStatus.ERROR -> R.color.status_error
            }
            binding.statusIndicator.setBackgroundColor(
                ContextCompat.getColor(binding.root.context, statusColor)
            )

            // For discovered servers without explicit status, show as discovered (green)
            if (server.isDiscovered && status == ServerStatus.DISCONNECTED) {
                binding.statusIndicator.setBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.status_discovered)
                )
            }

            // Click listener
            binding.root.setOnClickListener {
                callback.onServerClick(server)
            }

            // Long click listener
            binding.root.setOnLongClickListener {
                callback.onServerLongClick(server)
            }

            // Content description for accessibility
            val methodsDesc = buildList {
                if (server.local != null) add(binding.root.context.getString(R.string.connection_method_local))
                if (server.remote != null) add(binding.root.context.getString(R.string.connection_method_remote))
                if (server.proxy != null) add(binding.root.context.getString(R.string.connection_method_proxy))
            }.joinToString(", ")

            val defaultDesc = if (server.isDefaultServer) {
                ", ${binding.root.context.getString(R.string.accessibility_default_server)}"
            } else ""

            binding.root.contentDescription = binding.root.context.getString(
                R.string.accessibility_server_card,
                server.name,
                methodsDesc.ifEmpty { "no connections" }
            ) + defaultDesc
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val binding = ItemUnifiedServerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ServerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        val server = getItem(position)
        val status = serverStatuses[server.id] ?: ServerStatus.DISCONNECTED
        holder.bind(server, status, callback)
    }

    /**
     * DiffUtil callback for calculating the difference between two server lists.
     */
    private class ServerDiffCallback : DiffUtil.ItemCallback<UnifiedServer>() {
        /**
         * Checks if two items represent the same server.
         * Uses ID as the unique identifier.
         */
        override fun areItemsTheSame(oldItem: UnifiedServer, newItem: UnifiedServer): Boolean {
            return oldItem.id == newItem.id
        }

        /**
         * Checks if two items have the same content.
         * Since UnifiedServer is a data class, equals() compares all properties.
         */
        override fun areContentsTheSame(oldItem: UnifiedServer, newItem: UnifiedServer): Boolean {
            return oldItem == newItem
        }
    }
}
