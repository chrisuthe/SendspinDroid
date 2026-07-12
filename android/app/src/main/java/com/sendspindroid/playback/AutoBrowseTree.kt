package com.sendspindroid.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.sendspindroid.R
import com.sendspindroid.model.UnifiedServer
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Builds the media item lists for the Android Auto / MediaLibraryService
 * browse tree.
 *
 * Extracted from PlaybackService so the list-shaping rules are unit-testable
 * without a running service. The critical invariant enforced here:
 *
 * **A browsable node must never resolve to an empty list.** Android Auto
 * renders an empty children list as a blank "unable to load content" screen,
 * which fails Google Play's automated Auto quality review (the app was
 * rejected for exactly this when no SendSpin server was reachable). Every
 * list-producing function therefore falls back to a non-interactive message
 * item that tells the user what to do next.
 */
object AutoBrowseTree {

    // Browse tree media IDs
    const val MEDIA_ID_ROOT = "root"
    const val MEDIA_ID_DISCOVERED = "discovered_servers"
    const val MEDIA_ID_SERVER_PREFIX = "server_"
    const val MEDIA_ID_SAVED_SERVER_PREFIX = "saved_server_"

    // Music Assistant category nodes
    const val MEDIA_ID_MA_PLAYLISTS = "ma_playlists"
    const val MEDIA_ID_MA_ALBUMS = "ma_albums"
    const val MEDIA_ID_MA_ARTISTS = "ma_artists"
    const val MEDIA_ID_MA_RADIO = "ma_radio"

    // Music Assistant drill-down prefixes
    const val MEDIA_ID_MA_PLAYLIST_PREFIX = "ma_playlist_"
    const val MEDIA_ID_MA_ALBUM_PREFIX = "ma_album_"
    const val MEDIA_ID_MA_ARTIST_PREFIX = "ma_artist_"

    // Non-interactive informational rows (neither playable nor browsable)
    const val MEDIA_ID_MESSAGE_PREFIX = "message_"
    const val MEDIA_ID_MESSAGE_NO_SERVERS = "${MEDIA_ID_MESSAGE_PREFIX}no_servers"

    // Android Auto content style hint keys
    const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    const val CONTENT_STYLE_SINGLE_ITEM = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT"
    const val CONTENT_STYLE_GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
    const val CONTENT_STYLE_LIST = 1
    const val CONTENT_STYLE_GRID = 2

    /**
     * Root tabs. Mirrors the previous PlaybackService.getRootChildren():
     * a single "Connect" node until Music Assistant is available, then the
     * four MA library categories.
     */
    fun rootChildren(maAvailable: Boolean, isConnected: Boolean): List<MediaItem> {
        if (!maAvailable) {
            return listOf(
                browsableItem(
                    mediaId = MEDIA_ID_DISCOVERED,
                    title = "Connect",
                    subtitle = if (isConnected) "Connected" else null
                )
            )
        }
        return listOf(
            browsableItem(
                mediaId = MEDIA_ID_MA_PLAYLISTS,
                title = "Playlists",
                iconRes = R.drawable.ic_auto_playlists
            ),
            browsableItem(
                mediaId = MEDIA_ID_MA_ALBUMS,
                title = "Albums",
                extras = Bundle().apply {
                    putInt(CONTENT_STYLE_PLAYABLE, CONTENT_STYLE_GRID)
                },
                iconRes = R.drawable.ic_auto_albums
            ),
            browsableItem(
                mediaId = MEDIA_ID_MA_ARTISTS,
                title = "Artists",
                extras = Bundle().apply {
                    putInt(CONTENT_STYLE_BROWSABLE, CONTENT_STYLE_GRID)
                },
                iconRes = R.drawable.ic_auto_artists
            ),
            browsableItem(
                mediaId = MEDIA_ID_MA_RADIO,
                title = "Radio",
                iconRes = R.drawable.ic_auto_radio
            ),
        )
    }

    /**
     * Children of the "Connect" node: saved servers (most recently connected
     * first) followed by mDNS-discovered servers not already saved.
     *
     * When there is nothing to show yet, waits up to [discoveryWaitMs] for the
     * first mDNS result so the initial browse on Android Auto doesn't race
     * discovery and come back empty. If still nothing after the wait, returns
     * a "No servers found" guidance row instead of an empty list. Later
     * discoveries refresh the node via notifyChildrenChanged.
     */
    suspend fun serverListChildren(
        savedServers: List<UnifiedServer>,
        discoveredServersFlow: StateFlow<List<UnifiedServer>>,
        discoveryWaitMs: Long,
    ): List<MediaItem> {
        var discovered = discoveredServersFlow.value
        if (savedServers.isEmpty() && discovered.isEmpty() && discoveryWaitMs > 0) {
            withTimeoutOrNull(discoveryWaitMs) {
                discoveredServersFlow.first { it.isNotEmpty() }
            }
            discovered = discoveredServersFlow.value
        }

        val savedItems = savedServers
            .sortedByDescending { it.lastConnectedMs }
            .map { savedServerItem(it) }
        val discoveredItems = discovered.mapNotNull { server ->
            val address = server.local?.address ?: return@mapNotNull null
            playableServerItem(server.name, address)
        }

        val items = savedItems + discoveredItems
        return items.ifEmpty {
            listOf(
                messageItem(
                    mediaId = MEDIA_ID_MESSAGE_NO_SERVERS,
                    title = "No servers found",
                    subtitle = "Open SendSpin Player on your phone to add a server"
                )
            )
        }
    }

    /**
     * Guards a Music Assistant children list against the empty case: an empty
     * fetch result (no content, or a failed request) is replaced with a
     * non-interactive message row appropriate for the node.
     */
    fun withEmptyState(parentId: String, items: List<MediaItem>): List<MediaItem> {
        if (items.isNotEmpty()) return items
        val title = when {
            parentId == MEDIA_ID_MA_PLAYLISTS -> "No playlists found"
            parentId == MEDIA_ID_MA_ALBUMS -> "No albums found"
            parentId == MEDIA_ID_MA_ARTISTS -> "No artists found"
            parentId == MEDIA_ID_MA_RADIO -> "No radio stations found"
            parentId.startsWith(MEDIA_ID_MA_PLAYLIST_PREFIX) -> "No tracks found"
            parentId.startsWith(MEDIA_ID_MA_ALBUM_PREFIX) -> "No tracks found"
            parentId.startsWith(MEDIA_ID_MA_ARTIST_PREFIX) -> "No albums found"
            else -> "Nothing to show"
        }
        return listOf(
            messageItem(
                mediaId = "$MEDIA_ID_MESSAGE_PREFIX$parentId",
                title = title,
                subtitle = "Check Music Assistant on your phone"
            )
        )
    }

    /** A browsable (folder) node. */
    fun browsableItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        extras: Bundle? = null,
        iconRes: Int = 0
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .apply {
                        if (extras != null) setExtras(extras)
                        if (iconRes != 0) {
                            setArtworkUri(Uri.parse("android.resource://com.sendspindroid/$iconRes"))
                        }
                    }
                    .build()
            )
            .build()
    }

    /** A playable row for an mDNS-discovered server (media ID keyed by address). */
    fun playableServerItem(name: String, address: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId("$MEDIA_ID_SERVER_PREFIX$address")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setSubtitle(address)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    /** A playable row for a saved server (media ID keyed by server UUID). */
    fun savedServerItem(server: UnifiedServer): MediaItem {
        val subtitle = server.local?.address
            ?: if (server.proxy != null) "Proxy"
            else if (server.remote != null) "Remote Access"
            else ""
        return MediaItem.Builder()
            .setMediaId("$MEDIA_ID_SAVED_SERVER_PREFIX${server.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(server.name)
                    .setSubtitle(subtitle)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    /**
     * A non-interactive informational row. Neither playable nor browsable, so
     * Android Auto renders it as plain text the user can read but not tap.
     */
    fun messageItem(mediaId: String, title: String, subtitle: String? = null): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsPlayable(false)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()
    }
}
