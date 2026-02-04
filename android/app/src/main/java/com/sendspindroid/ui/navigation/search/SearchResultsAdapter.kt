package com.sendspindroid.ui.navigation.search

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
import com.sendspindroid.databinding.ItemSearchHeaderBinding
import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaArtist
import com.sendspindroid.musicassistant.MaPlaylist
import com.sendspindroid.musicassistant.MaRadio
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.MusicAssistantManager
import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType

/**
 * RecyclerView adapter for search results with section headers.
 *
 * Displays grouped search results with headers separating each media type.
 * Reuses the library row layout for consistent appearance.
 *
 * ## Architecture
 * The adapter uses a sealed class hierarchy for list items:
 * - SearchListItem.Header: Section header (e.g., "Artists", "Albums")
 * - SearchListItem.Item: Actual media item
 *
 * This allows mixed view types in a single RecyclerView while maintaining
 * type safety and proper DiffUtil comparisons.
 *
 * @param onItemClick Callback when a media item is tapped
 */
class SearchResultsAdapter(
    private val onItemClick: ((MaLibraryItem) -> Unit)? = null
) : ListAdapter<SearchResultsAdapter.SearchListItem, RecyclerView.ViewHolder>(SearchDiffCallback) {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1
    }

    /**
     * Sealed class representing items in the search results list.
     */
    sealed class SearchListItem {
        /**
         * Section header showing the media type.
         */
        data class Header(
            val mediaType: MaMediaType,
            val title: String
        ) : SearchListItem()

        /**
         * Actual media item (track, album, etc.).
         */
        data class Item(
            val item: MaLibraryItem
        ) : SearchListItem()
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SearchListItem.Header -> VIEW_TYPE_HEADER
            is SearchListItem.Item -> VIEW_TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val binding = ItemSearchHeaderBinding.inflate(inflater, parent, false)
                HeaderViewHolder(binding)
            }
            VIEW_TYPE_ITEM -> {
                val binding = ItemLibraryRowBinding.inflate(inflater, parent, false)
                ItemViewHolder(binding, onItemClick)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SearchListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is SearchListItem.Item -> (holder as ItemViewHolder).bind(item.item)
        }
    }

    /**
     * ViewHolder for section headers.
     */
    class HeaderViewHolder(
        private val binding: ItemSearchHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(header: SearchListItem.Header) {
            binding.headerTitle.text = header.title
        }
    }

    /**
     * ViewHolder for media items.
     * Reuses the same binding logic as LibraryRowAdapter.
     */
    class ItemViewHolder(
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

        private fun formatTrackCount(count: Int): String = when {
            count == 0 -> ""
            count == 1 -> "1 track"
            else -> "$count tracks"
        }

        private fun buildAlbumSubtitle(album: MaAlbum): String {
            return listOfNotNull(
                album.artist,
                album.year?.toString()
            ).joinToString(" - ")
        }
    }

    /**
     * DiffUtil callback for efficient list updates.
     */
    object SearchDiffCallback : DiffUtil.ItemCallback<SearchListItem>() {
        override fun areItemsTheSame(oldItem: SearchListItem, newItem: SearchListItem): Boolean {
            return when {
                oldItem is SearchListItem.Header && newItem is SearchListItem.Header ->
                    oldItem.mediaType == newItem.mediaType
                oldItem is SearchListItem.Item && newItem is SearchListItem.Item ->
                    oldItem.item.id == newItem.item.id &&
                    oldItem.item.mediaType == newItem.item.mediaType
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: SearchListItem, newItem: SearchListItem): Boolean {
            return oldItem == newItem
        }
    }
}

/**
 * Extension function to convert SearchResults into a flat list with headers.
 *
 * Creates a list suitable for the SearchResultsAdapter with section headers
 * appearing before each non-empty group of results.
 *
 * @param artistsHeader Header text for artists section
 * @param albumsHeader Header text for albums section
 * @param tracksHeader Header text for tracks section
 * @param playlistsHeader Header text for playlists section
 * @param radiosHeader Header text for radios section
 * @return Flat list with headers and items interleaved
 */
fun MusicAssistantManager.SearchResults.toFlatList(
    artistsHeader: String = "Artists",
    albumsHeader: String = "Albums",
    tracksHeader: String = "Tracks",
    playlistsHeader: String = "Playlists",
    radiosHeader: String = "Radio"
): List<SearchResultsAdapter.SearchListItem> {
    val list = mutableListOf<SearchResultsAdapter.SearchListItem>()

    // Artists section
    if (artists.isNotEmpty()) {
        list.add(SearchResultsAdapter.SearchListItem.Header(MaMediaType.ARTIST, artistsHeader))
        artists.forEach { list.add(SearchResultsAdapter.SearchListItem.Item(it)) }
    }

    // Albums section
    if (albums.isNotEmpty()) {
        list.add(SearchResultsAdapter.SearchListItem.Header(MaMediaType.ALBUM, albumsHeader))
        albums.forEach { list.add(SearchResultsAdapter.SearchListItem.Item(it)) }
    }

    // Tracks section
    if (tracks.isNotEmpty()) {
        list.add(SearchResultsAdapter.SearchListItem.Header(MaMediaType.TRACK, tracksHeader))
        tracks.forEach { list.add(SearchResultsAdapter.SearchListItem.Item(it)) }
    }

    // Playlists section
    if (playlists.isNotEmpty()) {
        list.add(SearchResultsAdapter.SearchListItem.Header(MaMediaType.PLAYLIST, playlistsHeader))
        playlists.forEach { list.add(SearchResultsAdapter.SearchListItem.Item(it)) }
    }

    // Radio section
    if (radios.isNotEmpty()) {
        list.add(SearchResultsAdapter.SearchListItem.Header(MaMediaType.RADIO, radiosHeader))
        radios.forEach { list.add(SearchResultsAdapter.SearchListItem.Item(it)) }
    }

    return list
}
