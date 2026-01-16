package com.sendspindroid.playback

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A ForwardingPlayer that provides dynamic metadata for lock screen and notifications.
 *
 * The problem: ExoPlayer's metadata comes from the MediaItem, which is set once when
 * playback starts. For live streams like SendSpin, track metadata changes dynamically
 * but the lock screen shows stale "no title" text.
 *
 * The solution: This wrapper intercepts [getMediaMetadata] calls and returns our
 * current track metadata. The MediaSession uses this player, so lock screen and
 * notifications always show the current track.
 *
 * ## Usage
 * ```kotlin
 * val forwardingPlayer = MetadataForwardingPlayer(exoPlayer)
 * mediaSession = MediaSession.Builder(this, forwardingPlayer).build()
 *
 * // Update metadata when track changes:
 * forwardingPlayer.updateMetadata("Song Title", "Artist Name", "Album", bitmap)
 * ```
 *
 * @param player The underlying ExoPlayer instance
 */
@UnstableApi
class MetadataForwardingPlayer(player: Player) : ForwardingPlayer(player) {

    // Current metadata (updated from Go player callbacks)
    @Volatile
    private var currentTitle: String? = null

    @Volatile
    private var currentArtist: String? = null

    @Volatile
    private var currentAlbum: String? = null

    @Volatile
    private var currentArtworkData: ByteArray? = null

    @Volatile
    private var currentArtworkUri: Uri? = null

    // Cached MediaMetadata object (rebuilt when metadata changes)
    @Volatile
    private var cachedMetadata: MediaMetadata = MediaMetadata.EMPTY

    /**
     * Updates the current track metadata.
     *
     * Handles three cases for each string field:
     * - null: Don't update (partial update - preserve existing value)
     * - empty string: Clear the field (track has no data for this field)
     * - non-empty string: Update with new value
     *
     * Call this when receiving metadata updates from the server.
     * The new metadata will be returned by [getMediaMetadata] for
     * lock screen and notification display.
     *
     * @param title Track title (null = preserve, empty = clear)
     * @param artist Track artist (null = preserve, empty = clear)
     * @param album Album name (null = preserve, empty = clear)
     * @param artwork Album artwork bitmap (null = preserve existing)
     * @param artworkUri Artwork URL (null = preserve, empty = clear)
     * @param clearArtwork If true, clears artwork even when bitmap is null
     */
    fun updateMetadata(
        title: String?,
        artist: String?,
        album: String?,
        artwork: Bitmap? = null,
        artworkUri: Uri? = null,
        clearArtwork: Boolean = false
    ) {
        // null = preserve, empty = clear, value = update
        if (title != null) {
            currentTitle = title.ifEmpty { null }
        }
        if (artist != null) {
            currentArtist = artist.ifEmpty { null }
        }
        if (album != null) {
            currentAlbum = album.ifEmpty { null }
        }
        if (artworkUri != null) {
            currentArtworkUri = if (artworkUri.toString().isEmpty()) null else artworkUri
        }

        // Handle artwork bitmap
        if (artwork != null) {
            currentArtworkData = ByteArrayOutputStream().use { stream ->
                artwork.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                stream.toByteArray()
            }
        } else if (clearArtwork) {
            // Explicitly clear artwork (new track has no artwork)
            currentArtworkData = null
            currentArtworkUri = null
        }

        // Rebuild cached metadata
        rebuildMetadata()

        // Notify listeners that metadata changed
        // This triggers the MediaSession to update notifications
        listeners.forEach { listener ->
            listener.onMediaMetadataChanged(cachedMetadata)
        }
    }

    /**
     * Rebuilds the cached MediaMetadata from current values.
     */
    private fun rebuildMetadata() {
        cachedMetadata = MediaMetadata.Builder()
            .setTitle(currentTitle)
            .setDisplayTitle(currentTitle)  // Some systems use displayTitle for notifications
            .setArtist(currentArtist)
            .setAlbumTitle(currentAlbum)
            .setAlbumArtist(currentArtist)  // Also set album artist
            .apply {
                // Prefer artwork data over URI (more reliable for notifications)
                currentArtworkData?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                    ?: currentArtworkUri?.let { setArtworkUri(it) }
            }
            .build()
    }

    /**
     * Clears all metadata (e.g., on disconnect).
     */
    fun clearMetadata() {
        currentTitle = null
        currentArtist = null
        currentAlbum = null
        currentArtworkData = null
        currentArtworkUri = null
        cachedMetadata = MediaMetadata.EMPTY

        listeners.forEach { listener ->
            listener.onMediaMetadataChanged(cachedMetadata)
        }
    }

    /**
     * Returns our dynamic metadata instead of the static MediaItem metadata.
     *
     * This is the key override - MediaSession calls this to get metadata
     * for lock screen and notifications.
     */
    override fun getMediaMetadata(): MediaMetadata {
        // If we have metadata, return it; otherwise fall back to underlying player
        return if (currentTitle != null || currentArtist != null) {
            cachedMetadata
        } else {
            super.getMediaMetadata()
        }
    }

    // Track listeners for metadata change notifications
    // CopyOnWriteArrayList is thread-safe for iteration while allowing concurrent modification
    private val listeners = CopyOnWriteArrayList<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners.remove(listener)
    }
}
