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
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaRadio
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.model.MaLibraryItem

/**
 * Unified RecyclerView adapter for displaying any Music Assistant library item.
 *
 * This adapter replaces the separate MediaCardAdapter and PlaylistCardAdapter,
 * providing a single implementation that handles all MaLibraryItem types through
 * the common interface.
 *
 * ## Subtitle Rendering
 * The subtitle line displays different information based on item type:
 * - MaTrack: Artist name (or empty)
 * - MaPlaylist: Track count (e.g., "42 tracks")
 * - Future types (Album, Artist, Radio): Will be handled with when-expressions
 *
 * ## Performance
 * Uses ListAdapter with DiffUtil for efficient partial updates. The DiffCallback
 * compares items by their unique `id` from the MaLibraryItem interface.
 *
 * @param onItemClick Callback when a library item card is tapped
 */
class LibraryItemAdapter(
    private val onItemClick: ((MaLibraryItem) -> Unit)? = null
) : ListAdapter<MaLibraryItem, LibraryItemAdapter.LibraryItemViewHolder>(LibraryItemDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LibraryItemViewHolder {
        val binding = ItemMediaCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LibraryItemViewHolder(binding, onItemClick)
    }

    override fun onBindViewHolder(holder: LibraryItemViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * ViewHolder for library item cards.
     *
     * Handles conditional rendering based on item type using when-expressions
     * on the sealed hierarchy (via smart casting from MaLibraryItem).
     */
    class LibraryItemViewHolder(
        private val binding: ItemMediaCardBinding,
        private val onItemClick: ((MaLibraryItem) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MaLibraryItem) {
            binding.mediaTitle.text = item.name

            // Render subtitle based on item type
            val subtitle = when (item) {
                is MaTrack -> item.artist ?: ""
                is MaPlaylist -> formatTrackCount(item.trackCount)
                is MaAlbum -> buildAlbumSubtitle(item)
                is MaArtist -> ""  // No subtitle for artists
                is MaRadio -> item.provider ?: ""
                else -> ""
            }
            binding.mediaArtist.text = subtitle
            binding.mediaArtist.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE

            // Load artwork with Coil
            if (!item.imageUri.isNullOrEmpty()) {
                binding.albumArt.load(item.imageUri) {
                    placeholder(R.drawable.placeholder_album)
                    error(R.drawable.placeholder_album)
                    crossfade(true)
                    transformations(RoundedCornersTransformation(0f)) // Card handles corners
                }
            } else {
                binding.albumArt.setImageResource(R.drawable.placeholder_album)
            }

            // Play overlay animation on focus (TV/keyboard navigation)
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                binding.playOverlay.animate()
                    .alpha(if (hasFocus) 0.9f else 0f)
                    .setDuration(150)
                    .start()
            }

            // Click handler
            binding.root.setOnClickListener {
                onItemClick?.invoke(item)
            }
        }

        /**
         * Format track count for playlist subtitle.
         */
        private fun formatTrackCount(count: Int): String = when {
            count == 0 -> ""
            count == 1 -> "1 track"
            else -> "$count tracks"
        }

        /**
         * Build subtitle for album items.
         *
         * Shows "Artist Name" or "Artist Name - 2024" if year is available.
         */
        private fun buildAlbumSubtitle(album: MaAlbum): String {
            return listOfNotNull(
                album.artist,
                album.year?.toString()
            ).joinToString(" - ")
        }
    }

    /**
     * DiffUtil callback for efficient list updates.
     *
     * Compares items using the common `id` property from MaLibraryItem interface,
     * enabling proper change detection across all item types.
     */
    companion object LibraryItemDiffCallback : DiffUtil.ItemCallback<MaLibraryItem>() {
        override fun areItemsTheSame(oldItem: MaLibraryItem, newItem: MaLibraryItem): Boolean {
            // Items are the same if they have the same ID and type
            return oldItem.id == newItem.id && oldItem.mediaType == newItem.mediaType
        }

        override fun areContentsTheSame(oldItem: MaLibraryItem, newItem: MaLibraryItem): Boolean {
            // Full equality check - data classes implement this correctly
            return oldItem == newItem
        }
    }
}
