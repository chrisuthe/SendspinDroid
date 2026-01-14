package com.sendspindroid.playback

import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import com.sendspindroid.sendspin.SendSpinClient
import com.sendspindroid.sendspin.SyncAudioPlayer
import com.sendspindroid.sendspin.PlaybackState as SyncPlaybackState

/**
 * Custom Player implementation for MediaSession that bridges to SendSpinClient.
 *
 * This replaces ExoPlayer as the underlying player for MediaSession. It:
 * - Reports playback state based on connection/audio player status
 * - Forwards transport commands (play/pause/seek/next/previous) to SendSpinClient
 * - Tracks position and duration from playback state updates
 *
 * Note: This player does NOT actually play audio. Audio playback is handled by
 * SyncAudioPlayer. This player only provides the Player interface that MediaSession
 * requires for state reporting and command handling.
 *
 * ## Architecture
 * ```
 * MediaSession ─── SendSpinPlayer (this) ─── SendSpinClient ─── WebSocket
 *                         │
 *                    Wrapped by
 *                         │
 *                 MetadataForwardingPlayer
 * ```
 */
@UnstableApi
class SendSpinPlayer : Player {

    companion object {
        private const val TAG = "SendSpinPlayer"
    }

    // External components (set after construction)
    private var sendSpinClient: SendSpinClient? = null
    private var syncAudioPlayer: SyncAudioPlayer? = null

    // Listener management
    private val listeners = mutableListOf<Player.Listener>()

    // Playback state tracking
    private var currentPlaybackState = Player.STATE_IDLE
    private var currentlyPlaying = false
    private var playWhenReady = false

    // Position and duration tracking (in milliseconds)
    private var currentPositionMs = 0L
    private var currentDurationMs = 0L
    private var currentBufferedPositionMs = 0L

    // Current media item for notifications
    private var currentMediaItem: MediaItem? = null

    // Timeline for position reporting
    private var currentTimeline: Timeline = Timeline.EMPTY

    // ========================================================================
    // Configuration Methods
    // ========================================================================

    /**
     * Sets the SendSpinClient for command forwarding.
     * Called by PlaybackService after client initialization.
     */
    fun setSendSpinClient(client: SendSpinClient?) {
        sendSpinClient = client
    }

    /**
     * Sets the SyncAudioPlayer for state observation.
     * Called by PlaybackService when audio stream starts.
     */
    fun setSyncAudioPlayer(player: SyncAudioPlayer?) {
        syncAudioPlayer = player
        updateStateFromPlayer()
    }

    /**
     * Updates internal state based on SyncAudioPlayer state.
     * Called when SyncAudioPlayer state changes.
     */
    internal fun updateStateFromPlayer() {
        val player = syncAudioPlayer
        if (player == null) {
            // No audio player - check connection state
            val client = sendSpinClient
            if (client?.isConnected == true) {
                updatePlaybackStateInternal(Player.STATE_BUFFERING, false)
            } else {
                updatePlaybackStateInternal(Player.STATE_IDLE, false)
            }
            return
        }

        // Map SyncAudioPlayer state to Player state
        when (player.getPlaybackState()) {
            SyncPlaybackState.INITIALIZING,
            SyncPlaybackState.WAITING_FOR_START -> {
                updatePlaybackStateInternal(Player.STATE_BUFFERING, playWhenReady)
            }
            SyncPlaybackState.PLAYING,
            SyncPlaybackState.DRAINING -> {
                // DRAINING is still actively playing from buffer, so STATE_READY
                updatePlaybackStateInternal(Player.STATE_READY, true)
            }
            SyncPlaybackState.REANCHORING -> {
                updatePlaybackStateInternal(Player.STATE_BUFFERING, playWhenReady)
            }
        }
    }

    /**
     * Called by PlaybackService when playback state updates from the server.
     *
     * @param syncState The SyncAudioPlayer playback state (or null if not available)
     * @param positionMs Current playback position in milliseconds
     * @param durationMs Total duration in milliseconds
     */
    fun updatePlaybackState(syncState: SyncPlaybackState?, positionMs: Long, durationMs: Long) {
        currentPositionMs = positionMs
        currentDurationMs = durationMs
        currentBufferedPositionMs = positionMs // For live streams, buffered = current

        if (syncState != null) {
            when (syncState) {
                SyncPlaybackState.INITIALIZING,
                SyncPlaybackState.WAITING_FOR_START -> {
                    updatePlaybackStateInternal(Player.STATE_BUFFERING, playWhenReady)
                }
                SyncPlaybackState.PLAYING,
                SyncPlaybackState.DRAINING -> {
                    // DRAINING is still actively playing from buffer, so STATE_READY
                    updatePlaybackStateInternal(Player.STATE_READY, true)
                }
                SyncPlaybackState.REANCHORING -> {
                    updatePlaybackStateInternal(Player.STATE_BUFFERING, playWhenReady)
                }
            }
        }

        // Notify listeners of position change
        notifyPositionDiscontinuity(Player.DISCONTINUITY_REASON_INTERNAL)
    }

    /**
     * Called when connection state changes.
     *
     * @param connected Whether we're connected to a server
     * @param serverName Name of the connected server (if connected)
     */
    fun updateConnectionState(connected: Boolean, serverName: String? = null) {
        if (!connected) {
            updatePlaybackStateInternal(Player.STATE_IDLE, false)
            currentPositionMs = 0
            currentDurationMs = 0
            currentBufferedPositionMs = 0
            currentMediaItem = null
            currentTimeline = Timeline.EMPTY
        } else if (syncAudioPlayer == null) {
            // Connected but no audio yet
            updatePlaybackStateInternal(Player.STATE_BUFFERING, playWhenReady)
        }
    }

    /**
     * Updates the current media item with track metadata.
     * This is required for lock screen and notification display.
     */
    fun updateMediaItem(title: String?, artist: String?, album: String?, durationMs: Long) {
        android.util.Log.d(TAG, "updateMediaItem: $title / $artist / $album")

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .build()

        currentMediaItem = MediaItem.Builder()
            .setMediaId("sendspin_current")
            .setMediaMetadata(metadata)
            .build()

        currentDurationMs = durationMs

        // Create a simple single-item timeline
        currentTimeline = SingleItemTimeline(currentMediaItem!!, durationMs)

        // Notify listeners of timeline change
        listeners.forEach { listener ->
            listener.onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
            listener.onMediaItemTransition(currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        }
    }

    /**
     * Internal method to update state and notify listeners.
     */
    private fun updatePlaybackStateInternal(newState: Int, newIsPlaying: Boolean) {
        val stateChanged = currentPlaybackState != newState
        val playingChanged = currentlyPlaying != newIsPlaying

        currentPlaybackState = newState
        currentlyPlaying = newIsPlaying

        if (stateChanged) {
            listeners.forEach { it.onPlaybackStateChanged(newState) }
        }
        if (playingChanged) {
            listeners.forEach { it.onIsPlayingChanged(newIsPlaying) }
        }
        if (stateChanged || playingChanged) {
            @Suppress("DEPRECATION")
            listeners.forEach { it.onPlayerStateChanged(playWhenReady, newState) }
        }
    }

    private fun notifyPositionDiscontinuity(reason: Int) {
        val oldPosition = Player.PositionInfo(
            /* windowUid= */ null,
            /* mediaItemIndex= */ 0,
            /* mediaItem= */ null,
            /* periodUid= */ null,
            /* periodIndex= */ 0,
            /* positionMs= */ currentPositionMs,
            /* contentPositionMs= */ currentPositionMs,
            /* adGroupIndex= */ C.INDEX_UNSET,
            /* adIndexInAdGroup= */ C.INDEX_UNSET
        )
        val newPosition = Player.PositionInfo(
            /* windowUid= */ null,
            /* mediaItemIndex= */ 0,
            /* mediaItem= */ null,
            /* periodUid= */ null,
            /* periodIndex= */ 0,
            /* positionMs= */ currentPositionMs,
            /* contentPositionMs= */ currentPositionMs,
            /* adGroupIndex= */ C.INDEX_UNSET,
            /* adIndexInAdGroup= */ C.INDEX_UNSET
        )
        listeners.forEach { it.onPositionDiscontinuity(oldPosition, newPosition, reason) }
    }

    // ========================================================================
    // Player Interface - Listener Management
    // ========================================================================

    override fun addListener(listener: Player.Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
    }

    // ========================================================================
    // Player Interface - Playback State
    // ========================================================================

    override fun getPlaybackState(): Int = currentPlaybackState

    override fun isPlaying(): Boolean = currentlyPlaying

    override fun getPlayWhenReady(): Boolean = playWhenReady

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        android.util.Log.i(TAG, "setPlayWhenReady: $playWhenReady, sendSpinClient=${sendSpinClient != null}")
        this.playWhenReady = playWhenReady
        if (playWhenReady) {
            sendSpinClient?.play()
        } else {
            sendSpinClient?.pause()
        }
        listeners.forEach { it.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
    }

    /**
     * Updates playWhenReady state from server without sending a command back.
     * Called when server pushes playback state changes (e.g., via group/update).
     *
     * This prevents the feedback loop where:
     * 1. Server sends PLAYING state
     * 2. We set playWhenReady = true
     * 3. We send play() command back to server (unnecessary)
     *
     * @param playing Whether the server says playback is active
     */
    fun updatePlayWhenReadyFromServer(playing: Boolean) {
        if (this.playWhenReady != playing) {
            android.util.Log.i(TAG, "updatePlayWhenReadyFromServer: $playing (was ${this.playWhenReady})")
            this.playWhenReady = playing
            listeners.forEach { it.onPlayWhenReadyChanged(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE) }
        }
    }

    override fun getPlaybackSuppressionReason(): Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE

    override fun getPlayerError(): PlaybackException? = null

    // ========================================================================
    // Player Interface - Transport Controls
    // ========================================================================

    override fun prepare() {
        // No-op - preparation happens when connecting to server
    }

    override fun play() {
        setPlayWhenReady(true)
    }

    override fun pause() {
        setPlayWhenReady(false)
    }

    override fun stop() {
        setPlayWhenReady(false)
        sendSpinClient?.pause()
    }

    override fun release() {
        listeners.clear()
        sendSpinClient = null
        syncAudioPlayer = null
    }

    override fun seekTo(positionMs: Long) {
        // SendSpin doesn't support arbitrary seek positions
        // This is a no-op for now
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        seekTo(positionMs)
    }

    override fun seekToDefaultPosition() {
        seekTo(0)
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        seekTo(0)
    }

    override fun seekBack() {
        sendSpinClient?.previous()
    }

    override fun seekForward() {
        sendSpinClient?.next()
    }

    override fun seekToNext() {
        sendSpinClient?.next()
    }

    override fun seekToNextMediaItem() {
        sendSpinClient?.next()
    }

    override fun seekToPrevious() {
        sendSpinClient?.previous()
    }

    override fun seekToPreviousMediaItem() {
        sendSpinClient?.previous()
    }

    override fun hasPreviousMediaItem(): Boolean = true

    override fun hasNextMediaItem(): Boolean = true

    // ========================================================================
    // Player Interface - Position and Duration
    // ========================================================================

    override fun getCurrentPosition(): Long = currentPositionMs

    override fun getDuration(): Long = currentDurationMs

    override fun getBufferedPosition(): Long = currentBufferedPositionMs

    override fun getBufferedPercentage(): Int {
        return if (currentDurationMs > 0) {
            ((currentBufferedPositionMs * 100) / currentDurationMs).toInt().coerceIn(0, 100)
        } else {
            0
        }
    }

    override fun getTotalBufferedDuration(): Long = currentBufferedPositionMs

    override fun isCurrentMediaItemDynamic(): Boolean = true // Live stream

    override fun isCurrentMediaItemLive(): Boolean = true // Live stream

    override fun isCurrentMediaItemSeekable(): Boolean = false // No seeking in live stream

    override fun getContentPosition(): Long = currentPositionMs

    override fun getContentDuration(): Long = currentDurationMs

    override fun getContentBufferedPosition(): Long = currentBufferedPositionMs

    override fun getCurrentLiveOffset(): Long = 0

    // ========================================================================
    // Player Interface - Media Items and Timeline
    // ========================================================================

    override fun getCurrentTimeline(): Timeline = currentTimeline

    override fun getCurrentPeriodIndex(): Int = 0

    override fun getCurrentMediaItemIndex(): Int = 0

    @Deprecated("Deprecated in Java")
    override fun getCurrentWindowIndex(): Int = currentMediaItemIndex

    override fun getNextMediaItemIndex(): Int = C.INDEX_UNSET

    @Deprecated("Deprecated in Java")
    override fun getNextWindowIndex(): Int = nextMediaItemIndex

    override fun getPreviousMediaItemIndex(): Int = C.INDEX_UNSET

    @Deprecated("Deprecated in Java")
    override fun getPreviousWindowIndex(): Int = previousMediaItemIndex

    override fun getCurrentMediaItem(): MediaItem? = currentMediaItem

    override fun getMediaItemCount(): Int = if (currentMediaItem != null) 1 else 0

    override fun getMediaItemAt(index: Int): MediaItem {
        if (index == 0 && currentMediaItem != null) {
            return currentMediaItem!!
        }
        throw IndexOutOfBoundsException("No media item at index $index")
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        // No-op
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        // No-op
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        // No-op
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        // No-op
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        // No-op
    }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        // No-op
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        // No-op
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        // No-op
    }

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
        // No-op
    }

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        // No-op
    }

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        // No-op
    }

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
        // No-op
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        // No-op
    }

    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: MutableList<MediaItem>
    ) {
        // No-op
    }

    override fun removeMediaItem(index: Int) {
        // No-op
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        // No-op
    }

    override fun clearMediaItems() {
        // No-op
    }

    // ========================================================================
    // Player Interface - Repeat and Shuffle
    // ========================================================================

    override fun getRepeatMode(): Int = Player.REPEAT_MODE_OFF

    override fun setRepeatMode(repeatMode: Int) {
        // No-op - SendSpin controls repeat mode server-side
    }

    override fun getShuffleModeEnabled(): Boolean = false

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        // No-op - SendSpin controls shuffle server-side
    }

    // ========================================================================
    // Player Interface - Playback Parameters
    // ========================================================================

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        // No-op - speed changes not supported for synced playback
    }

    override fun setPlaybackSpeed(speed: Float) {
        // No-op
    }

    // ========================================================================
    // Player Interface - Audio
    // ========================================================================

    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) {
        // No-op - audio attributes are handled by SyncAudioPlayer
    }

    override fun getVolume(): Float = 1.0f

    override fun setVolume(volume: Float) {
        sendSpinClient?.setVolume(volume.toDouble())
    }

    // ========================================================================
    // Player Interface - Video (Not Used)
    // ========================================================================

    override fun getVideoSize(): VideoSize = VideoSize.UNKNOWN

    override fun getSurfaceSize(): Size = Size.UNKNOWN

    override fun clearVideoSurface() {
        // No-op - no video
    }

    override fun clearVideoSurface(surface: Surface?) {
        // No-op - no video
    }

    override fun setVideoSurface(surface: Surface?) {
        // No-op - no video
    }

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        // No-op - no video
    }

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        // No-op - no video
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        // No-op - no video
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        // No-op - no video
    }

    override fun setVideoTextureView(textureView: TextureView?) {
        // No-op - no video
    }

    override fun clearVideoTextureView(textureView: TextureView?) {
        // No-op - no video
    }

    override fun getCurrentCues(): CueGroup = CueGroup.EMPTY_TIME_ZERO

    // ========================================================================
    // Player Interface - Device Info
    // ========================================================================

    override fun getDeviceInfo(): DeviceInfo = DeviceInfo.UNKNOWN

    override fun getDeviceVolume(): Int = 0

    override fun isDeviceMuted(): Boolean = false

    @Deprecated("Deprecated in Java")
    override fun setDeviceVolume(volume: Int) {
        // No-op
    }

    override fun setDeviceVolume(volume: Int, flags: Int) {
        // No-op
    }

    @Deprecated("Deprecated in Java")
    override fun increaseDeviceVolume() {
        // No-op
    }

    override fun increaseDeviceVolume(flags: Int) {
        // No-op
    }

    @Deprecated("Deprecated in Java")
    override fun decreaseDeviceVolume() {
        // No-op
    }

    override fun decreaseDeviceVolume(flags: Int) {
        // No-op
    }

    @Deprecated("Deprecated in Java")
    override fun setDeviceMuted(muted: Boolean) {
        // No-op
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        // No-op
    }

    override fun mute() {
        // No-op
    }

    override fun unmute() {
        // No-op
    }

    // ========================================================================
    // Player Interface - Tracks and Metadata
    // ========================================================================

    override fun getCurrentTracks(): Tracks = Tracks.EMPTY

    @Suppress("DEPRECATION")
    override fun getTrackSelectionParameters(): TrackSelectionParameters {
        return TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
    }

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        // No-op
    }

    override fun getMediaMetadata(): MediaMetadata {
        return currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY
    }

    override fun getPlaylistMetadata(): MediaMetadata = MediaMetadata.EMPTY

    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) {
        // No-op
    }

    // ========================================================================
    // Player Interface - Commands
    // ========================================================================

    @Suppress("DEPRECATION")
    override fun getAvailableCommands(): Player.Commands {
        return Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_MEDIA_ITEMS_METADATA,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Player.COMMAND_GET_VOLUME
            )
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        return availableCommands.contains(command)
    }

    // ========================================================================
    // Player Interface - Looper
    // ========================================================================

    override fun getApplicationLooper(): Looper = Looper.getMainLooper()

    // ========================================================================
    // Player Interface - Session Advertising
    // ========================================================================

    override fun canAdvertiseSession(): Boolean = true

    // ========================================================================
    // Player Interface - Manifest
    // ========================================================================

    override fun getCurrentManifest(): Any? = null

    // ========================================================================
    // Player Interface - Ads
    // ========================================================================

    override fun isPlayingAd(): Boolean = false

    override fun getCurrentAdGroupIndex(): Int = C.INDEX_UNSET

    override fun getCurrentAdIndexInAdGroup(): Int = C.INDEX_UNSET

    // ========================================================================
    // Player Interface - Seeking (additional methods)
    // ========================================================================

    override fun getSeekBackIncrement(): Long = 10000 // 10 seconds

    override fun getSeekForwardIncrement(): Long = 10000 // 10 seconds

    override fun getMaxSeekToPreviousPosition(): Long = 3000 // 3 seconds

    override fun isLoading(): Boolean = currentPlaybackState == Player.STATE_BUFFERING

    // ========================================================================
    // Player Interface - Deprecated/Compat methods
    // ========================================================================

    @Deprecated("Deprecated in Java")
    override fun isCurrentWindowDynamic(): Boolean = isCurrentMediaItemDynamic

    @Deprecated("Deprecated in Java")
    override fun isCurrentWindowLive(): Boolean = isCurrentMediaItemLive

    @Deprecated("Deprecated in Java")
    override fun isCurrentWindowSeekable(): Boolean = isCurrentMediaItemSeekable
}

/**
 * A simple Timeline with a single media item.
 * Used to provide timeline information for notifications and lock screen.
 */
@UnstableApi
private class SingleItemTimeline(
    private val mediaItem: MediaItem,
    private val durationMs: Long
) : Timeline() {

    override fun getWindowCount(): Int = 1

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        window.set(
            /* uid= */ 0,
            /* mediaItem= */ mediaItem,
            /* manifest= */ null,
            /* presentationStartTimeMs= */ C.TIME_UNSET,
            /* windowStartTimeMs= */ C.TIME_UNSET,
            /* elapsedRealtimeEpochOffsetMs= */ C.TIME_UNSET,
            /* isSeekable= */ false,
            /* isDynamic= */ true,
            /* liveConfiguration= */ null,
            /* defaultPositionUs= */ 0,
            /* durationUs= */ if (durationMs > 0) durationMs * 1000 else C.TIME_UNSET,
            /* firstPeriodIndex= */ 0,
            /* lastPeriodIndex= */ 0,
            /* positionInFirstPeriodUs= */ 0
        )
        return window
    }

    override fun getPeriodCount(): Int = 1

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        period.set(
            /* id= */ 0,
            /* uid= */ 0,
            /* windowIndex= */ 0,
            /* durationUs= */ if (durationMs > 0) durationMs * 1000 else C.TIME_UNSET,
            /* positionInWindowUs= */ 0
        )
        return period
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        return if (uid == 0) 0 else C.INDEX_UNSET
    }

    override fun getUidOfPeriod(periodIndex: Int): Any = 0
}
