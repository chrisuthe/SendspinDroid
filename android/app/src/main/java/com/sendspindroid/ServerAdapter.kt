package com.sendspindroid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for displaying discovered servers.
 *
 * Design decision: Using built-in android.R.layout.two_line_list_item for simplicity.
 * For v2, consider custom layout with Material Design styling and server status indicators.
 *
 * Best practice: Accepts callback as constructor parameter (dependency injection pattern)
 * TODO: Implement DiffUtil for efficient list updates instead of notifyItemInserted
 * TODO: Add view state for selected server (highlight current connection)
 */
class ServerAdapter(
    private val servers: List<ServerInfo>,
    private val onServerClick: (ServerInfo) -> Unit
) : RecyclerView.Adapter<ServerAdapter.ServerViewHolder>() {

    /**
     * ViewHolder pattern for efficient view recycling.
     * Caches view references to avoid repeated findViewById calls.
     */
    class ServerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // Using Android's built-in text1/text2 IDs from two_line_list_item layout
        val nameText: TextView = view.findViewById(android.R.id.text1)
        val addressText: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        // Inflate using parent's context (not application context) to preserve theme
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.two_line_list_item, parent, false)
        return ServerViewHolder(view)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        val server = servers[position]
        holder.nameText.text = server.name
        holder.addressText.text = server.address

        // Click listener for server selection
        // Note: Click listener is set in onBindViewHolder, so it gets the correct position
        // even if the list changes (important for RecyclerView's view recycling)
        holder.itemView.setOnClickListener {
            onServerClick(server)
        }
    }

    override fun getItemCount() = servers.size
}
