package com.sendspindroid.ui.navigation.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.sendspindroid.R
import com.sendspindroid.databinding.ItemMediaCardBinding
import com.sendspindroid.musicassistant.MaMediaItem

/**
 * RecyclerView adapter for displaying media items in horizontal carousels.
 *
 * Uses ListAdapter with DiffUtil for efficient updates when the data changes.
 * Loads album art asynchronously using Coil with placeholder fallback.
 *
 * @param onItemClick Callback when a media card is tapped (future: playback)
 */
class MediaCardAdapter(
    private val onItemClick: ((MaMediaItem) -> Unit)? = null
) : ListAdapter<MaMediaItem, MediaCardAdapter.MediaCardViewHolder>(MediaItemDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaCardViewHolder {
        val binding = ItemMediaCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MediaCardViewHolder(binding, onItemClick)
    }

    override fun onBindViewHolder(holder: MediaCardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * ViewHolder for media card items.
     * Handles view binding and album art loading.
     */
    class MediaCardViewHolder(
        private val binding: ItemMediaCardBinding,
        private val onItemClick: ((MaMediaItem) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MaMediaItem) {
            binding.mediaTitle.text = item.name
            binding.mediaArtist.text = item.artist ?: ""
            binding.mediaArtist.visibility = if (item.artist.isNullOrEmpty()) View.GONE else View.VISIBLE

            // Load album art with Coil
            if (!item.imageUri.isNullOrEmpty()) {
                binding.albumArt.load(item.imageUri) {
                    placeholder(R.drawable.placeholder_album)
                    error(R.drawable.placeholder_album)
                    crossfade(true)
                    transformations(RoundedCornersTransformation(0f)) // Card already has corner radius
                }
            } else {
                binding.albumArt.setImageResource(R.drawable.placeholder_album)
            }

            // Play overlay animation on focus (for TV/keyboard navigation)
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                binding.playOverlay.animate()
                    .alpha(if (hasFocus) 0.9f else 0f)
                    .setDuration(150)
                    .start()
            }

            // Click handler (future: play this item)
            binding.root.setOnClickListener {
                onItemClick?.invoke(item)
            }
        }
    }

    /**
     * DiffUtil callback for efficient list updates.
     * Compares items by ID and content for minimal RecyclerView rebinds.
     */
    companion object MediaItemDiffCallback : DiffUtil.ItemCallback<MaMediaItem>() {
        override fun areItemsTheSame(oldItem: MaMediaItem, newItem: MaMediaItem): Boolean {
            return oldItem.itemId == newItem.itemId
        }

        override fun areContentsTheSame(oldItem: MaMediaItem, newItem: MaMediaItem): Boolean {
            return oldItem == newItem
        }
    }
}
