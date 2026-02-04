package com.sendspindroid.ui.navigation.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.sendspindroid.R
import com.sendspindroid.databinding.ItemLibraryRowBinding
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaRadio
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.model.MaLibraryItem

/**
 * RecyclerView adapter for vertical library lists.
 *
 * Displays library items as compact rows (72dp height) suitable for
 * vertical scrolling lists in the Library browser tabs.
 *
 * Reuses the same DiffUtil pattern as LibraryItemAdapter for efficient
 * list updates, but uses a row layout instead of card layout.
 *
 * @param onItemClick Callback when a row is tapped
 */
class LibraryRowAdapter(
    private val onItemClick: ((MaLibraryItem) -> Unit)? = null
) : ListAdapter<MaLibraryItem, LibraryRowAdapter.LibraryRowViewHolder>(LibraryRowDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LibraryRowViewHolder {
        val binding = ItemLibraryRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LibraryRowViewHolder(binding, onItemClick)
    }

    override fun onBindViewHolder(holder: LibraryRowViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * ViewHolder for library row items.
     *
     * Handles conditional subtitle rendering based on item type,
     * matching the logic in LibraryItemAdapter.
     */
    class LibraryRowViewHolder(
        private val binding: ItemLibraryRowBinding,
        private val onItemClick: ((MaLibraryItem) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MaLibraryItem) {
            binding.title.text = item.name

            // Render subtitle based on item type
            val subtitle = when (item) {
                is MaTrack -> item.artist ?: ""
                is MaPlaylist -> formatTrackCount(item.trackCount)
                is MaAlbum -> buildAlbumSubtitle(item)
                is MaArtist -> ""  // No subtitle for artists
                is MaRadio -> item.provider ?: ""
                else -> ""
            }
            binding.subtitle.text = subtitle
            binding.subtitle.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE

            // Load artwork with Coil
            if (!item.imageUri.isNullOrEmpty()) {
                binding.thumbnail.load(item.imageUri) {
                    placeholder(R.drawable.placeholder_album)
                    error(R.drawable.placeholder_album)
                    crossfade(true)
                    transformations(RoundedCornersTransformation(8f))
                }
            } else {
                binding.thumbnail.setImageResource(R.drawable.placeholder_album)
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
     * Compares items using the common `id` property from MaLibraryItem interface.
     */
    companion object LibraryRowDiffCallback : DiffUtil.ItemCallback<MaLibraryItem>() {
        override fun areItemsTheSame(oldItem: MaLibraryItem, newItem: MaLibraryItem): Boolean {
            return oldItem.id == newItem.id && oldItem.mediaType == newItem.mediaType
        }

        override fun areContentsTheSame(oldItem: MaLibraryItem, newItem: MaLibraryItem): Boolean {
            return oldItem == newItem
        }
    }
}
