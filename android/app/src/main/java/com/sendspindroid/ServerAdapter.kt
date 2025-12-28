package com.sendspindroid

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sendspindroid.databinding.ItemServerBinding

/**
 * RecyclerView adapter for displaying discovered servers with Material 3 design.
 *
 * Features:
 * - ListAdapter with DiffUtil for efficient list updates
 * - ViewBinding for type-safe view access
 * - Custom Material 3 card layout with elevation
 * - Connection status indicator (colored dot)
 * - Ripple effect for touch feedback
 *
 * Best practice: Uses ListAdapter which automatically calculates list diffs
 * on a background thread and dispatches minimal update operations.
 *
 * TODO: Add view state for selected server (highlight current connection)
 * TODO: Add connection status to ServerInfo model and bind to status indicator
 */
class ServerAdapter(
    private val onServerClick: (ServerInfo) -> Unit
) : ListAdapter<ServerInfo, ServerAdapter.ServerViewHolder>(ServerDiffCallback()) {

    /**
     * ViewHolder pattern for efficient view recycling.
     * Uses ViewBinding to access views in a type-safe manner.
     */
    class ServerViewHolder(
        private val binding: ItemServerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * Binds server data to the layout views.
         *
         * @param server The server information to display
         * @param onServerClick Callback invoked when the server item is clicked
         */
        fun bind(server: ServerInfo, onServerClick: (ServerInfo) -> Unit) {
            // Set server name and address
            binding.serverName.text = server.name
            binding.serverAddress.text = server.address

            // Set status indicator color
            // Default to "discovered" (green) for now
            // TODO: When ServerInfo gets a status field, update this to reflect actual status
            val statusColor = ContextCompat.getColor(
                binding.root.context,
                R.color.status_discovered
            )
            binding.statusIndicator.setBackgroundColor(statusColor)

            // Set click listener for server selection
            // MaterialCardView already has ripple effect defined in layout
            binding.root.setOnClickListener {
                onServerClick(server)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        // Inflate using parent's context (not application context) to preserve theme
        val binding = ItemServerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ServerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        holder.bind(getItem(position), onServerClick)
    }

    /**
     * DiffUtil callback for calculating the difference between two ServerInfo lists.
     *
     * This enables efficient RecyclerView updates by:
     * - Only updating items that have changed
     * - Calculating diffs on a background thread
     * - Animating changes automatically
     */
    private class ServerDiffCallback : DiffUtil.ItemCallback<ServerInfo>() {
        /**
         * Checks if two items represent the same server.
         * Uses address as the unique identifier since it should be unique per server.
         */
        override fun areItemsTheSame(oldItem: ServerInfo, newItem: ServerInfo): Boolean {
            return oldItem.address == newItem.address
        }

        /**
         * Checks if two items have the same content.
         * Since ServerInfo is a data class, equals() compares all properties.
         */
        override fun areContentsTheSame(oldItem: ServerInfo, newItem: ServerInfo): Boolean {
            return oldItem == newItem
        }
    }
}
