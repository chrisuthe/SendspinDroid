package com.sendspindroid.ui.main

import android.graphics.Bitmap

/**
 * Track metadata for display in player UI.
 */
data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String
) {
    companion object {
        val EMPTY = TrackMetadata("", "", "")
    }

    val isEmpty: Boolean get() = title.isBlank() && artist.isBlank() && album.isBlank()
}

/**
 * Source for album artwork - can be binary data, URI, or URL.
 */
sealed class ArtworkSource {
    data class ByteArray(val data: kotlin.ByteArray) : ArtworkSource() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ByteArray) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }

    data class Uri(val uri: android.net.Uri) : ArtworkSource()
    data class Url(val url: String) : ArtworkSource()
}

/**
 * State for reconnection indicator.
 */
data class ReconnectingState(
    val serverName: String,
    val attempt: Int,
    val bufferMs: Long
) {
    val bufferSeconds: Long get() = bufferMs / 1000
}

/**
 * Navigation tab for bottom navigation.
 */
enum class NavTab {
    HOME, SEARCH, LIBRARY
}

/**
 * Server status for display in server list.
 */
sealed class ServerStatus {
    object Online : ServerStatus()
    object Offline : ServerStatus()
    data class Connecting(val progress: Float = 0f) : ServerStatus()
    data class Reconnecting(val attempt: Int, val nextRetrySeconds: Int) : ServerStatus()
}

/**
 * Player colors extracted from album artwork.
 */
data class PlayerColors(
    val backgroundColor: Int,
    val accentColor: Int,
    val textColor: Int
)
