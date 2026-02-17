package com.sendspindroid.musicassistant

/**
 * Aggregated search results from Music Assistant.
 *
 * Contains results grouped by media type. Each list may be empty if no
 * matches were found for that type, or if the type was filtered out.
 */
data class SearchResults(
    val artists: List<MaArtist> = emptyList(),
    val albums: List<MaAlbum> = emptyList(),
    val tracks: List<MaTrack> = emptyList(),
    val playlists: List<MaPlaylist> = emptyList(),
    val radios: List<MaRadio> = emptyList(),
    val podcasts: List<MaPodcast> = emptyList()
) {
    /**
     * Check if all result lists are empty.
     */
    fun isEmpty(): Boolean =
        artists.isEmpty() && albums.isEmpty() && tracks.isEmpty() &&
        playlists.isEmpty() && radios.isEmpty() && podcasts.isEmpty()

    /**
     * Get total count of all results.
     */
    fun totalCount(): Int =
        artists.size + albums.size + tracks.size + playlists.size + radios.size + podcasts.size
}
