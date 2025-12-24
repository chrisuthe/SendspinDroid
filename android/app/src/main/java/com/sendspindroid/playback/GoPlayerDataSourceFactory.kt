package com.sendspindroid.playback

import androidx.media3.datasource.DataSource

/**
 * Factory for creating GoPlayerDataSource instances.
 *
 * ExoPlayer uses the factory pattern to create DataSource instances on-demand.
 * This allows:
 * - Fresh instances for each stream/segment
 * - Dependency injection of the Go player
 * - Resource pooling (not currently used, but possible)
 *
 * ## Usage
 * ```kotlin
 * val factory = GoPlayerDataSourceFactory(goPlayer)
 * val mediaSource = ProgressiveMediaSource.Factory(factory)
 *     .createMediaSource(mediaItem)
 * ```
 *
 * @param goPlayer The Go player instance that provides audio data
 */
class GoPlayerDataSourceFactory(
    private val goPlayer: player.Player_
) : DataSource.Factory {

    /**
     * Creates a new GoPlayerDataSource instance.
     *
     * Called by ExoPlayer each time it needs to read from the data source.
     * For our use case, this typically happens once per stream connection.
     *
     * @return A new GoPlayerDataSource configured with the Go player
     */
    override fun createDataSource(): DataSource {
        return GoPlayerDataSource(goPlayer)
    }
}
