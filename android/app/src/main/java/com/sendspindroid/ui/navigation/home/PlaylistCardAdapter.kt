package com.sendspindroid.ui.navigation.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.sendspindroid.R
import com.sendspindroid.databinding.ItemMediaCardBinding
import com.sendspindroid.musicassistant.MaPlaylist

/**
 * RecyclerView adapter for displaying playlists in horizontal carousels.
 *
 * Uses the same item_media_card layout as MediaCardAdapter but binds
 * MaPlaylist data (shows track count instead of artist).
 *
 * @param onItemClick Callback when a playlist card is tapped (future: open playlist)
 */
class PlaylistCardAdapter(
    private val onItemClick: ((MaPlaylist) -> Unit)? = null
) : ListAdapter<MaPlaylist, PlaylistCardAdapter.PlaylistCardViewHolder>(PlaylistDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistCardViewHolder {
        val binding = ItemMediaCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlaylistCardViewHolder(binding, onItemClick)
    }

    override fun onBindViewHolder(holder: PlaylistCardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * ViewHolder for playlist card items.
     */
    class PlaylistCardViewHolder(
        private val binding: ItemMediaCardBinding,
        private val onItemClick: ((MaPlaylist) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: MaPlaylist) {
            binding.mediaTitle.text = playlist.name

            // Show track count as subtitle
            val trackText = when {
                playlist.trackCount == 0 -> ""
                playlist.trackCount == 1 -> "1 track"
                else -> "${playlist.trackCount} tracks"
            }
            binding.mediaArtist.text = trackText
            binding.mediaArtist.visibility = if (trackText.isEmpty()) View.GONE else View.VISIBLE

            // Load playlist cover art
            if (!playlist.imageUri.isNullOrEmpty()) {
                binding.albumArt.load(playlist.imageUri) {
                    placeholder(R.drawable.placeholder_album)
                    error(R.drawable.placeholder_album)
                    crossfade(true)
                }
            } else {
                binding.albumArt.setImageResource(R.drawable.placeholder_album)
            }

            // Play overlay animation on focus
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                binding.playOverlay.animate()
                    .alpha(if (hasFocus) 0.9f else 0f)
                    .setDuration(150)
                    .start()
            }

            // Click handler (future: open playlist)
            binding.root.setOnClickListener {
                onItemClick?.invoke(playlist)
            }
        }
    }

    /**
     * DiffUtil callback for efficient list updates.
     */
    companion object PlaylistDiffCallback : DiffUtil.ItemCallback<MaPlaylist>() {
        override fun areItemsTheSame(oldItem: MaPlaylist, newItem: MaPlaylist): Boolean {
            return oldItem.playlistId == newItem.playlistId
        }

        override fun areContentsTheSame(oldItem: MaPlaylist, newItem: MaPlaylist): Boolean {
            return oldItem == newItem
        }
    }
}
