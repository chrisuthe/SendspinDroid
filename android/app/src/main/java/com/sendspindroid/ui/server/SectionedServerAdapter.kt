package com.sendspindroid.ui.server

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.sendspindroid.R
import com.sendspindroid.databinding.ItemServerSectionHeaderBinding
import com.sendspindroid.databinding.ItemUnifiedServerBinding
import com.sendspindroid.model.UnifiedServer

/**
 * RecyclerView adapter that displays servers in sections with headers.
 *
 * Sections:
 * 1. "Saved Servers" - Persisted servers from UnifiedServerRepository
 * 2. "Nearby Servers" - Discovered servers via mDNS (with scanning indicator)
 *
 * ## Architecture
 * Uses sealed class [ListItem] to represent both headers and servers in a single list.
 * This approach is simpler than ConcatAdapter and keeps all logic in one place.
 *
 * ## Usage
 * ```kotlin
 * val adapter = SectionedServerAdapter(callback)
 * recyclerView.adapter = adapter
 *
 * // Update sections independently
 * adapter.updateSavedServers(savedList)
 * adapter.updateDiscoveredServers(discoveredList)
 * adapter.setScanning(true)
 * ```
 */
class SectionedServerAdapter(
    private val callback: Callback
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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
     * Represents an item in the list (either a header or a server).
     */
    sealed class ListItem {
        data class Header(
            val title: String,
            val showScanning: Boolean = false,
            val emptyHint: String? = null
        ) : ListItem()

        data class Server(val server: UnifiedServer) : ListItem()
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_SERVER = 1
    }

    /**
     * Status of a server for UI display.
     */
    enum class ServerStatus {
        DISCONNECTED,
        ONLINE,        // Server discovered on network but not connected
        CONNECTING,
        CONNECTED,
        RECONNECTING,  // Auto-reconnect in progress
        ERROR
    }

    /**
     * Reconnection progress info for a server.
     */
    data class ReconnectProgress(
        val attempt: Int,
        val maxAttempts: Int,
        val currentMethod: String? = null
    )

    // Internal data
    private var savedServers: List<UnifiedServer> = emptyList()
    private var discoveredServers: List<UnifiedServer> = emptyList()
    private var isScanning: Boolean = false
    private var items: List<ListItem> = emptyList()

    // Track status per server ID
    private val serverStatuses = mutableMapOf<String, ServerStatus>()

    // Track reconnection progress per server ID
    private val reconnectProgress = mutableMapOf<String, ReconnectProgress>()

    /**
     * Update the saved servers section.
     */
    fun updateSavedServers(servers: List<UnifiedServer>) {
        savedServers = servers
        rebuildItems()
    }

    /**
     * Update the discovered servers section.
     */
    fun updateDiscoveredServers(servers: List<UnifiedServer>) {
        discoveredServers = servers
        rebuildItems()
    }

    /**
     * Set whether mDNS scanning is currently active.
     * Shows spinner on "Nearby Servers" header when true.
     */
    fun setScanning(scanning: Boolean) {
        if (isScanning != scanning) {
            isScanning = scanning
            rebuildItems()
        }
    }

    /**
     * Update the status of a specific server.
     */
    fun setServerStatus(serverId: String, status: ServerStatus) {
        serverStatuses[serverId] = status
        // Clear reconnect progress if status is not RECONNECTING
        if (status != ServerStatus.RECONNECTING) {
            reconnectProgress.remove(serverId)
        }
        notifyDataSetChanged()
    }

    /**
     * Update reconnection progress for a server.
     * Also sets the status to RECONNECTING.
     */
    fun setReconnectProgress(serverId: String, attempt: Int, maxAttempts: Int, currentMethod: String? = null) {
        serverStatuses[serverId] = ServerStatus.RECONNECTING
        reconnectProgress[serverId] = ReconnectProgress(attempt, maxAttempts, currentMethod)
        notifyDataSetChanged()
    }

    /**
     * Clear reconnection state for a server.
     */
    fun clearReconnectProgress(serverId: String) {
        reconnectProgress.remove(serverId)
        // Only reset to DISCONNECTED if currently RECONNECTING
        if (serverStatuses[serverId] == ServerStatus.RECONNECTING) {
            serverStatuses[serverId] = ServerStatus.DISCONNECTED
        }
        notifyDataSetChanged()
    }

    /**
     * Clear all server statuses (e.g., on disconnect).
     */
    fun clearStatuses() {
        serverStatuses.clear()
        reconnectProgress.clear()
        notifyDataSetChanged()
    }

    /**
     * Update online status for saved servers based on discovered servers.
     * Servers in the onlineIds set will show as ONLINE (green indicator).
     */
    fun updateOnlineServers(onlineIds: Set<String>) {
        // Update statuses for all saved servers
        savedServers.forEach { server ->
            val currentStatus = serverStatuses[server.id]
            // Don't override CONNECTING or CONNECTED status
            if (currentStatus != ServerStatus.CONNECTING && currentStatus != ServerStatus.CONNECTED) {
                serverStatuses[server.id] = if (server.id in onlineIds) {
                    ServerStatus.ONLINE
                } else {
                    ServerStatus.DISCONNECTED
                }
            }
        }
        notifyDataSetChanged()
    }

    /**
     * Rebuilds the flat item list from the sectioned data.
     * Uses DiffUtil for efficient updates.
     */
    private fun rebuildItems() {
        val newItems = mutableListOf<ListItem>()

        // Saved Servers section
        val savedEmptyHint = if (savedServers.isEmpty()) {
            // Only show hint if there are no saved servers
            "Add a server using the + button"
        } else null

        newItems.add(ListItem.Header(
            title = "SAVED SERVERS",
            showScanning = false,
            emptyHint = savedEmptyHint
        ))

        savedServers.forEach { server ->
            newItems.add(ListItem.Server(server))
        }

        // Nearby Servers section
        val nearbyEmptyHint = if (discoveredServers.isEmpty() && !isScanning) {
            "No servers found on this network"
        } else null

        newItems.add(ListItem.Header(
            title = "NEARBY SERVERS",
            showScanning = isScanning,
            emptyHint = nearbyEmptyHint
        ))

        discoveredServers.forEach { server ->
            newItems.add(ListItem.Server(server))
        }

        // Calculate diff for efficient updates
        val diffCallback = ItemDiffCallback(items, newItems)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ListItem.Header -> VIEW_TYPE_HEADER
            is ListItem.Server -> VIEW_TYPE_SERVER
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val binding = ItemServerSectionHeaderBinding.inflate(inflater, parent, false)
                HeaderViewHolder(binding)
            }
            VIEW_TYPE_SERVER -> {
                val binding = ItemUnifiedServerBinding.inflate(inflater, parent, false)
                ServerViewHolder(binding)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is ListItem.Server -> {
                val status = serverStatuses[item.server.id] ?: ServerStatus.DISCONNECTED
                val progress = reconnectProgress[item.server.id]
                (holder as ServerViewHolder).bind(item.server, status, progress, callback)
            }
        }
    }

    /**
     * ViewHolder for section headers.
     */
    class HeaderViewHolder(
        private val binding: ItemServerSectionHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(header: ListItem.Header) {
            binding.sectionTitle.text = header.title

            // Show/hide scanning indicator
            if (header.showScanning) {
                binding.scanningIndicator.visibility = View.VISIBLE
                binding.scanningText.visibility = View.VISIBLE
            } else {
                binding.scanningIndicator.visibility = View.GONE
                binding.scanningText.visibility = View.GONE
            }

            // Show/hide empty hint
            if (header.emptyHint != null) {
                binding.emptyHint.text = header.emptyHint
                binding.emptyHint.visibility = View.VISIBLE
            } else {
                binding.emptyHint.visibility = View.GONE
            }
        }
    }

    /**
     * ViewHolder for server items.
     * Reuses the same layout and binding logic as UnifiedServerAdapter.
     */
    class ServerViewHolder(
        private val binding: ItemUnifiedServerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            server: UnifiedServer,
            status: ServerStatus,
            reconnectProgress: ReconnectProgress?,
            callback: Callback
        ) {
            val context = binding.root.context

            // Server name
            binding.serverName.text = server.name

            // Subtitle: show reconnect progress, address for discovered, or last connected for saved
            binding.serverSubtitle.text = when {
                status == ServerStatus.RECONNECTING && reconnectProgress != null -> {
                    if (reconnectProgress.currentMethod != null) {
                        context.getString(R.string.reconnecting_via_method, reconnectProgress.currentMethod)
                    } else {
                        context.getString(
                            R.string.reconnecting_progress,
                            reconnectProgress.attempt,
                            reconnectProgress.maxAttempts
                        )
                    }
                }
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

            // Quick Connect chip (only for discovered servers, hidden during reconnection)
            binding.quickConnectChip.visibility = when {
                status == ServerStatus.RECONNECTING -> View.GONE
                server.isDiscovered -> View.VISIBLE
                else -> View.GONE
            }
            binding.quickConnectChip.setOnClickListener {
                callback.onQuickConnect(server)
            }

            // Status indicator color
            val statusColor = when (status) {
                ServerStatus.DISCONNECTED -> R.color.status_disconnected
                ServerStatus.ONLINE -> R.color.status_discovered  // Green - available on network
                ServerStatus.CONNECTING -> R.color.status_connecting
                ServerStatus.CONNECTED -> R.color.status_connected
                ServerStatus.RECONNECTING -> R.color.status_reconnecting  // Amber - auto-reconnecting
                ServerStatus.ERROR -> R.color.status_error
            }
            binding.statusIndicator.setBackgroundColor(
                ContextCompat.getColor(context, statusColor)
            )

            // For discovered servers (not reconnecting), always show as online (green)
            if (server.isDiscovered && status != ServerStatus.RECONNECTING) {
                binding.statusIndicator.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.status_discovered)
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
                if (server.local != null) add(context.getString(R.string.connection_method_local))
                if (server.remote != null) add(context.getString(R.string.connection_method_remote))
                if (server.proxy != null) add(context.getString(R.string.connection_method_proxy))
            }.joinToString(", ")

            val defaultDesc = if (server.isDefaultServer) {
                ", ${context.getString(R.string.accessibility_default_server)}"
            } else ""

            val statusDesc = if (status == ServerStatus.RECONNECTING && reconnectProgress != null) {
                ", ${context.getString(R.string.accessibility_reconnecting, reconnectProgress.attempt, reconnectProgress.maxAttempts)}"
            } else ""

            binding.root.contentDescription = context.getString(
                R.string.accessibility_server_card,
                server.name,
                methodsDesc.ifEmpty { "no connections" }
            ) + defaultDesc + statusDesc
        }
    }

    /**
     * DiffUtil callback for calculating the difference between two item lists.
     */
    private class ItemDiffCallback(
        private val oldList: List<ListItem>,
        private val newList: List<ListItem>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]

            return when {
                oldItem is ListItem.Header && newItem is ListItem.Header ->
                    oldItem.title == newItem.title
                oldItem is ListItem.Server && newItem is ListItem.Server ->
                    oldItem.server.id == newItem.server.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
