package com.sendspindroid.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * Factory for creating MediaSource instances that use the Go player audio bridge.
 *
 * This factory creates MediaSource instances suitable for ExoPlayer playback
 * using our custom GoPlayerDataSource as the data provider.
 *
 * ## Architecture
 * ```
 * ExoPlayer
 *     ↓
 * MediaSource (created by this factory)
 *     ↓
 * GoPlayerDataSourceFactory
 *     ↓
 * GoPlayerDataSource
 *     ↓
 * Go Player (readAudioData)
 * ```
 *
 * ## Audio Format
 * The Go player provides raw PCM audio:
 * - Sample rate: 48,000 Hz
 * - Channels: 2 (stereo)
 * - Encoding: PCM 16-bit signed little-endian
 *
 * ## Usage
 * ```kotlin
 * val mediaSource = GoPlayerMediaSourceFactory.create(goPlayer)
 * exoPlayer.setMediaSource(mediaSource)
 * exoPlayer.prepare()
 * ```
 *
 * @see GoPlayerDataSource
 * @see GoPlayerDataSourceFactory
 */
object GoPlayerMediaSourceFactory {

    /** Custom URI scheme for SendSpin audio streams */
    private const val SENDSPIN_SCHEME = "sendspin"

    /** Default stream path */
    private const val DEFAULT_STREAM_PATH = "/stream"

    /**
     * Creates a MediaSource for Go player audio playback.
     *
     * Uses ProgressiveMediaSource with our custom DataSource factory.
     * The MediaItem is configured with a custom sendspin:// URI.
     *
     * @param goPlayer The Go player instance providing audio data
     * @param serverAddress Optional server address for the URI (for logging/debugging)
     * @return A MediaSource ready to be set on ExoPlayer
     */
    fun create(
        goPlayer: player.Player_,
        serverAddress: String? = null
    ): MediaSource {
        // Build a custom URI for this stream
        // Format: sendspin://[serverAddress]/stream
        val uri = Uri.Builder()
            .scheme(SENDSPIN_SCHEME)
            .authority(serverAddress ?: "localhost")
            .path(DEFAULT_STREAM_PATH)
            .build()

        // Create the MediaItem with our custom URI and audio MIME type
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.AUDIO_RAW)
            .build()

        // Create our custom DataSource factory
        val dataSourceFactory = GoPlayerDataSourceFactory(goPlayer)

        // Use ProgressiveMediaSource which supports streaming formats
        // This wraps our DataSource and handles buffering/timeline
        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)
    }

    /**
     * Creates a MediaSource with custom MediaItem metadata.
     *
     * Use this when you want to provide custom title/artist for the notification.
     *
     * @param goPlayer The Go player instance providing audio data
     * @param title Title to display in notifications
     * @param artist Artist to display in notifications
     * @param serverAddress Optional server address for the URI
     * @return A MediaSource ready to be set on ExoPlayer
     */
    fun createWithMetadata(
        goPlayer: player.Player_,
        title: String,
        artist: String? = null,
        serverAddress: String? = null
    ): MediaSource {
        val uri = Uri.Builder()
            .scheme(SENDSPIN_SCHEME)
            .authority(serverAddress ?: "localhost")
            .path(DEFAULT_STREAM_PATH)
            .build()

        // Build MediaItem with metadata for notifications
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.AUDIO_RAW)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .build()
            )
            .build()

        val dataSourceFactory = GoPlayerDataSourceFactory(goPlayer)

        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)
    }
}
