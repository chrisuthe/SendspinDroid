package com.sendspindroid.playback

import com.sendspindroid.musicassistant.SearchResults

/**
 * Decision logic for Android Auto voice search ("OK Google, play X on
 * SendSpin Player").
 *
 * Extracted from PlaybackService so the outcome selection and user-facing
 * feedback messages are unit-testable. Companion to [AutoBrowseTree], which
 * covers the browse side of the same Play Auto quality requirement: an
 * external request must never end in a silent no-op. Voice searches that
 * cannot play anything surface an actionable message on the car screen
 * instead of doing nothing.
 */
object AutoVoiceSearch {

    /** What a voice search should play, chosen from MA search results. */
    sealed class Pick {
        data class Track(val uri: String, val name: String) : Pick()
        data class Playlist(val playlistId: String, val name: String) : Pick()
        data class Album(val albumId: String, val name: String) : Pick()
        object NoResults : Pick()
    }

    /**
     * Chooses what to play from search results, preferring tracks, then
     * playlists, then albums (matching in-app search ranking).
     */
    fun pickFromResults(results: SearchResults?): Pick {
        if (results == null) return Pick.NoResults

        val track = results.tracks.firstOrNull { it.uri != null }
        if (track != null) return Pick.Track(track.uri!!, track.name)

        val playlist = results.playlists.firstOrNull()
        if (playlist != null) return Pick.Playlist(playlist.playlistId, playlist.name)

        val album = results.albums.firstOrNull()
        if (album != null) return Pick.Album(album.albumId, album.name)

        return Pick.NoResults
    }

    /**
     * Feedback when voice search cannot run at all because Music Assistant
     * is not available. The disconnected variant tells the user how to fix
     * it; the connected variant explains why search doesn't work on a plain
     * SendSpin server.
     */
    fun unavailableMessage(isConnectedToServer: Boolean): String =
        if (isConnectedToServer) {
            "Search is not available on this server"
        } else {
            "No server connected. Open SendSpin Player on your phone to connect."
        }

    /** Feedback when a search ran but nothing playable matched. */
    fun noResultsMessage(query: String): String =
        "No results for \"$query\""

    /** Feedback when "play music" (blank query) found nothing recently played. */
    fun noRecentTracksMessage(): String =
        "Nothing has been played recently. Pick something from the library."

    /** Feedback when the search request itself failed. */
    fun searchFailedMessage(): String =
        "Search failed. Check the connection to your server."
}
