package com.sendspindroid.musicassistant

/**
 * Aggregated artist details including top tracks and discography.
 *
 * Used by the Artist Detail screen to display complete artist information
 * in a single request.
 */
data class ArtistDetails(
    val artist: MaArtist,
    val topTracks: List<MaTrack>,
    val albums: List<MaAlbum>
)
