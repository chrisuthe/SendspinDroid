package com.sendspindroid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ServerAdapter(
    private val servers: List<ServerInfo>,
    private val onServerClick: (ServerInfo) -> Unit
) : RecyclerView.Adapter<ServerAdapter.ServerViewHolder>() {

    class ServerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(android.R.id.text1)
        val addressText: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.two_line_list_item, parent, false)
        return ServerViewHolder(view)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        val server = servers[position]
        holder.nameText.text = server.name
        holder.addressText.text = server.address
        holder.itemView.setOnClickListener {
            onServerClick(server)
        }
    }

    override fun getItemCount() = servers.size
}
