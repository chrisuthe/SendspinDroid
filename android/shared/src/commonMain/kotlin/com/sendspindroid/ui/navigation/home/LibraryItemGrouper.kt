package com.sendspindroid.ui.navigation.home

import com.sendspindroid.musicassistant.MaAlbum
import com.sendspindroid.musicassistant.MaTrack
import com.sendspindroid.musicassistant.model.MaLibraryItem

/**
 * Groups tracks intelligently for home carousels.
 *
 * ## Grouping Rules
 * - 2+ tracks from same album -> collapse to album card
 * - 1 track that's a "single" release -> show as album card
 * - 1 track from regular album -> show as track
 *
 * ## Grouping Key
 * Uses (album, artist) tuple to avoid cross-artist collisions
 * (e.g., "Greatest Hits" by different artists won't merge).
 *
 * ## Order Preservation
 * The album card appears at the position of the first track
 * from that album in the original list.
 */
object LibraryItemGrouper {

    /**
     * Key for grouping tracks by album and artist.
     *
     * Using both fields prevents collisions like multiple
     * "Greatest Hits" albums by different artists.
     */
    data class GroupingKey(val album: String, val artist: String)

    /**
     * Groups tracks by album, collapsing multiple tracks into album cards.
     *
     * @param tracks List of tracks to group
     * @param albumLookup Optional map of GroupingKey -> MaAlbum for:
     *   - Detecting singles (albumType == "single")
     *   - Using proper album artwork instead of track artwork
     *   - Getting correct album metadata (year, track count)
     * @return Mixed list of MaTrack and MaAlbum items
     */
    fun groupTracks(
        tracks: List<MaTrack>,
        albumLookup: Map<GroupingKey, MaAlbum>? = null
    ): List<MaLibraryItem> {
        // Group tracks with non-null albums by (album, artist)
        val grouped = tracks
            .filter { it.album != null }
            .groupBy { GroupingKey(it.album!!, it.artist ?: "") }

        val result = mutableListOf<MaLibraryItem>()
        val processedKeys = mutableSetOf<GroupingKey>()

        // Iterate through original tracks to preserve order
        for (track in tracks) {
            // Tracks without album info pass through unchanged
            if (track.album == null) {
                result.add(track)
                continue
            }

            val key = GroupingKey(track.album, track.artist ?: "")

            // Skip if we already processed this album
            if (key in processedKeys) continue
            processedKeys.add(key)

            val albumTracks = grouped[key] ?: continue
            val matchedAlbum = albumLookup?.get(key)

            when {
                // 2+ tracks from same album: always show as album card
                albumTracks.size >= 2 -> {
                    result.add(matchedAlbum ?: createAlbumFromTracks(albumTracks))
                }
                // Single track that's a "single" release: show as album
                track.albumType == "single" || matchedAlbum?.albumType == "single" -> {
                    result.add(matchedAlbum ?: createAlbumFromTracks(albumTracks))
                }
                // Single track from regular album: show as track
                else -> {
                    result.add(track)
                }
            }
        }

        return result
    }

    /**
     * Creates a synthetic MaAlbum from a list of tracks.
     *
     * Used when the album isn't in the lookup map (not fetched yet).
     * Uses the first track's metadata as the album info source.
     *
     * @param tracks Tracks from the same album (must not be empty)
     * @return Album card with aggregated data
     */
    private fun createAlbumFromTracks(tracks: List<MaTrack>): MaAlbum {
        val first = tracks.first()

        // Generate a stable ID based on album/artist
        // This allows proper diffing when the real album data loads later
        // Uses the raw string directly to avoid hashCode() collision risk
        val syntheticId = "grouped_${first.album}_${first.artist}"
        val albumId = first.albumId ?: syntheticId

        // Construct the URI for playback - MA uses format "library://album/{id}"
        val uri = if (first.albumId != null) {
            "library://album/${first.albumId}"
        } else {
            null  // Can't construct URI without a real album ID
        }

        return MaAlbum(
            albumId = albumId,
            name = first.album ?: "Unknown Album",
            imageUri = first.imageUri,  // Use first track's image initially
            uri = uri,
            artist = first.artist,
            year = null,
            trackCount = tracks.size,
            albumType = first.albumType
        )
    }
}
