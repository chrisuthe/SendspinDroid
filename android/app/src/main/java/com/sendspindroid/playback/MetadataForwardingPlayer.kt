package com.sendspindroid.playback

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
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

    // Reconnecting overlay: when non-null, the rebuilt metadata substitutes
    // "Reconnecting to {serverName}..." for the title (preserving artwork and
    // subtitle) and getPlaybackState() returns STATE_BUFFERING. This surfaces
    // recovery visibly on the lock screen, Android Auto, and AVRCP instead of
    // leaving the stale track title during reconnect storms. Issue #132.
    @Volatile
    private var reconnectingOverlay: String? = null

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
     *
     * When [reconnectingOverlay] is non-null, the title and displayTitle are
     * replaced with "Reconnecting to {server}..." while subtitle and artwork
     * are preserved; the lock screen keeps the album art but clearly reads as
     * recovering. Issue #132.
     */
    private fun rebuildMetadata() {
        // Build subtitle for Android Auto's DISPLAY_SUBTITLE (e.g. "Artist - Album")
        val subtitle = buildString {
            currentArtist?.let { append(it) }
            currentAlbum?.let {
                if (isNotEmpty()) append(" - ")
                append(it)
            }
        }.ifEmpty { null }

        val overlayServer = reconnectingOverlay
        val displayTitle = if (overlayServer != null) "Reconnecting to $overlayServer..." else currentTitle

        cachedMetadata = MediaMetadata.Builder()
            .setTitle(displayTitle)
            .setDisplayTitle(displayTitle)
            .setSubtitle(subtitle)  // Android Auto uses DISPLAY_SUBTITLE for second line
            .setArtist(currentArtist)
            .setAlbumTitle(currentAlbum)
            .setAlbumArtist(currentArtist)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsPlayable(true)
            .apply {
                // Set both artworkData (for notifications) and artworkUri (for Android Auto)
                currentArtworkData?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                currentArtworkUri?.let { setArtworkUri(it) }
            }
            .build()
    }

    /**
     * Show a "Reconnecting to {serverName}..." overlay on the MediaSession while
     * the client is in ConnectionState.Reconnecting. Also forces
     * [getPlaybackState] to return [Player.STATE_BUFFERING] so lock screen /
     * Auto / AVRCP render the system buffering indicator.
     *
     * Idempotent: calling with the same [serverName] twice is a no-op after
     * the first call, so it is safe to call on every retry attempt.
     *
     * Issue #132.
     */
    fun setReconnectingOverlay(serverName: String) {
        if (reconnectingOverlay == serverName) return
        reconnectingOverlay = serverName
        rebuildMetadata()
        val newState = getPlaybackState()
        listeners.forEach { listener ->
            listener.onMediaMetadataChanged(cachedMetadata)
            listener.onPlaybackStateChanged(newState)
        }
    }

    /**
     * Clear any active reconnecting overlay and restore normal metadata /
     * playback-state behavior. Called on [ConnectionState.Connected],
     * [ConnectionState.Disconnected], or [ConnectionState.Error].
     *
     * Idempotent: no-op if no overlay is active. Issue #132.
     */
    fun clearReconnectingOverlay() {
        if (reconnectingOverlay == null) return
        reconnectingOverlay = null
        rebuildMetadata()
        val newState = getPlaybackState()
        listeners.forEach { listener ->
            listener.onMediaMetadataChanged(cachedMetadata)
            listener.onPlaybackStateChanged(newState)
        }
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
        // The reconnecting overlay always takes precedence: even if no track
        // metadata has ever been set, the user should see "Reconnecting to..."
        // rather than an empty lock screen.
        return if (reconnectingOverlay != null ||
            currentTitle != null ||
            currentArtist != null) {
            cachedMetadata
        } else {
            super.getMediaMetadata()
        }
    }

    /**
     * Forces [Player.STATE_BUFFERING] while a reconnecting overlay is active,
     * so the lock screen / Android Auto / AVRCP render the buffering indicator
     * rather than showing the old "playing" state. Falls through to the
     * underlying player's state otherwise. Issue #132.
     */
    override fun getPlaybackState(): Int =
        if (reconnectingOverlay != null) Player.STATE_BUFFERING
        else super.getPlaybackState()

    /**
     * Returns the current media item with our enhanced metadata injected.
     *
     * Android Auto's legacy compat bridge reads metadata from the MediaItem
     * (not getMediaMetadata()), so we must override this to include artwork
     * and other enhanced fields.
     */
    override fun getCurrentMediaItem(): MediaItem? {
        val baseItem = super.getCurrentMediaItem() ?: return null
        val metadata = getMediaMetadata()
        if (metadata == MediaMetadata.EMPTY) return baseItem

        return baseItem.buildUpon()
            .setMediaMetadata(metadata)
            .build()
    }

    // Track listeners for metadata change notifications
    // CopyOnWriteArrayList is thread-safe for iteration while allowing concurrent modification
    private val listeners = CopyOnWriteArrayList<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners.remove(listener)
    }
}
