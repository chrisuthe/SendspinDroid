package com.sendspindroid.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.model.UnifiedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for MainActivity.
 *
 * Manages all UI state for the main activity, including:
 * - Connection state machine
 * - Playback state and metadata
 * - Volume control
 * - Navigation state
 * - Reconnection tracking
 *
 * This ViewModel survives configuration changes, ensuring playback state
 * is preserved during rotation.
 */
class MainActivityViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainActivityViewModel"
    }

    // ========================================================================
    // Connection State
    // ========================================================================

    private val _connectionState = MutableStateFlow<AppConnectionState>(AppConnectionState.ServerList)
    val connectionState: StateFlow<AppConnectionState> = _connectionState.asStateFlow()

    private val _currentConnectedServerId = MutableStateFlow<String?>(null)
    val currentConnectedServerId: StateFlow<String?> = _currentConnectedServerId.asStateFlow()

    private val _userManuallyDisconnected = MutableStateFlow(false)
    val userManuallyDisconnected: StateFlow<Boolean> = _userManuallyDisconnected.asStateFlow()

    // ========================================================================
    // Playback State
    // ========================================================================

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _metadata = MutableStateFlow(TrackMetadata.EMPTY)
    val metadata: StateFlow<TrackMetadata> = _metadata.asStateFlow()

    private val _groupName = MutableStateFlow("")
    val groupName: StateFlow<String> = _groupName.asStateFlow()

    // Track progress (position and duration in milliseconds)
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    // Monotonic timestamp (SystemClock.elapsedRealtime()) when position was last updated.
    // Used by TrackProgressBar to correctly interpolate from a stale position after recomposition.
    private val _positionUpdatedAt = MutableStateFlow(0L)
    val positionUpdatedAt: StateFlow<Long> = _positionUpdatedAt.asStateFlow()

    // ========================================================================
    // Artwork State
    // ========================================================================

    private val _artworkSource = MutableStateFlow<ArtworkSource?>(null)
    val artworkSource: StateFlow<ArtworkSource?> = _artworkSource.asStateFlow()

    private val _playerColors = MutableStateFlow<PlayerColors?>(null)
    val playerColors: StateFlow<PlayerColors?> = _playerColors.asStateFlow()

    // ========================================================================
    // Volume State
    // ========================================================================

    private val _volume = MutableStateFlow(0.75f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    // ========================================================================
    // Navigation State
    // ========================================================================

    private val _isNavigationContentVisible = MutableStateFlow(false)
    val isNavigationContentVisible: StateFlow<Boolean> = _isNavigationContentVisible.asStateFlow()

    private val _currentNavTab = MutableStateFlow(NavTab.HOME)
    val currentNavTab: StateFlow<NavTab> = _currentNavTab.asStateFlow()

    // Detail navigation back stack (supports nested navigation e.g. Artist -> Album)
    private val _detailBackStack = MutableStateFlow<List<DetailDestination>>(emptyList())
    val detailBackStack: StateFlow<List<DetailDestination>> = _detailBackStack.asStateFlow()

    // M-23: Derive via map + stateIn instead of a leaked coroutine with stale-read risk.
    // The previous pattern used MutableStateFlow + viewModelScope.launch { collect {} }
    // which could read stale values during rapid updates. The map/stateIn approach is
    // declarative and guaranteed to stay in sync.
    /** The currently visible detail destination, or null if browsing. */
    val currentDetail: StateFlow<DetailDestination?> = _detailBackStack
        .map { it.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _detailBackStack.value.lastOrNull())

    // ========================================================================
    // Reconnection State
    // ========================================================================

    private val _reconnectingState = MutableStateFlow<ReconnectingState?>(null)
    val reconnectingState: StateFlow<ReconnectingState?> = _reconnectingState.asStateFlow()

    private val _reconnectingToServer = MutableStateFlow<UnifiedServer?>(null)
    val reconnectingToServer: StateFlow<UnifiedServer?> = _reconnectingToServer.asStateFlow()

    // ========================================================================
    // UI State
    // ========================================================================

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _isConnectionLoading = MutableStateFlow(false)
    val isConnectionLoading: StateFlow<Boolean> = _isConnectionLoading.asStateFlow()

    // Music Assistant state
    private val _isMaConnected = MutableStateFlow(false)
    val isMaConnected: StateFlow<Boolean> = _isMaConnected.asStateFlow()

    // ========================================================================
    // Connection State Updates
    // ========================================================================

    fun updateConnectionState(state: AppConnectionState) {
        Log.d(TAG, "Connection state: $state")
        _connectionState.value = state

        // Update loading state based on connection state
        _isConnectionLoading.value = state is AppConnectionState.Connecting
    }

    fun setCurrentConnectedServerId(serverId: String?) {
        _currentConnectedServerId.value = serverId
    }

    fun setUserManuallyDisconnected(disconnected: Boolean) {
        _userManuallyDisconnected.value = disconnected
    }

    // ========================================================================
    // Playback State Updates
    // ========================================================================

    fun updatePlaybackState(isPlaying: Boolean, state: PlaybackState) {
        _isPlaying.value = isPlaying
        _playbackState.value = state
        _isBuffering.value = state == PlaybackState.BUFFERING
    }

    fun updateMetadata(title: String, artist: String, album: String) {
        _metadata.value = TrackMetadata(title, artist, album)
    }

    fun updateGroupName(name: String) {
        _groupName.value = name
    }

    fun updateTrackProgress(positionMs: Long, durationMs: Long, positionUpdatedAt: Long) {
        _positionMs.value = positionMs
        _durationMs.value = durationMs
        _positionUpdatedAt.value = positionUpdatedAt
    }

    // ========================================================================
    // Artwork Updates
    // ========================================================================

    fun updateArtwork(source: ArtworkSource?) {
        _artworkSource.value = source
    }

    fun updatePlayerColors(colors: PlayerColors?) {
        _playerColors.value = colors
    }

    fun clearArtwork() {
        _artworkSource.value = null
        _playerColors.value = null
    }

    // ========================================================================
    // Volume Updates
    // ========================================================================

    fun updateVolume(volume: Float) {
        _volume.value = volume.coerceIn(0f, 1f)
    }

    // ========================================================================
    // Navigation Updates
    // ========================================================================

    fun setNavigationContentVisible(visible: Boolean) {
        _isNavigationContentVisible.value = visible
    }

    fun setCurrentNavTab(tab: NavTab) {
        _currentNavTab.value = tab
    }

    /** Push a detail destination onto the navigation stack. */
    fun navigateToDetail(destination: DetailDestination) {
        Log.d(TAG, "Navigate to detail: $destination")
        _detailBackStack.value = _detailBackStack.value + destination
    }

    /**
     * Pop the top detail destination off the stack.
     * @return true if a destination was popped, false if the stack was already empty.
     */
    fun navigateDetailBack(): Boolean {
        val current = _detailBackStack.value
        if (current.isEmpty()) return false
        Log.d(TAG, "Navigate detail back (stack depth: ${current.size})")
        _detailBackStack.value = current.dropLast(1)
        return true
    }

    /** Clear the entire detail navigation stack (e.g. on disconnect). */
    fun clearDetailNavigation() {
        _detailBackStack.value = emptyList()
    }

    // ========================================================================
    // Reconnection Updates
    // ========================================================================

    fun updateReconnectingState(serverName: String, attempt: Int, bufferMs: Long) {
        _reconnectingState.value = ReconnectingState(serverName, attempt, bufferMs)
    }

    fun clearReconnectingState() {
        _reconnectingState.value = null
    }

    fun setReconnectingToServer(server: UnifiedServer?) {
        _reconnectingToServer.value = server
    }

    // ========================================================================
    // Music Assistant Updates
    // ========================================================================

    fun setMaConnected(connected: Boolean) {
        _isMaConnected.value = connected
    }

    // ========================================================================
    // Reset State
    // ========================================================================

    /**
     * Reset all playback-related state when disconnecting.
     */
    fun resetPlaybackState() {
        _isPlaying.value = false
        _playbackState.value = PlaybackState.IDLE
        _metadata.value = TrackMetadata.EMPTY
        _groupName.value = ""
        _artworkSource.value = null
        _playerColors.value = null
        _positionMs.value = 0
        _durationMs.value = 0
        _positionUpdatedAt.value = 0L
        _isBuffering.value = false
        _isMaConnected.value = false
    }

    /**
     * Reset all state to initial values.
     */
    fun resetToServerList() {
        _connectionState.value = AppConnectionState.ServerList
        _currentConnectedServerId.value = null
        _isNavigationContentVisible.value = false
        _reconnectingState.value = null
        _reconnectingToServer.value = null
        _isConnectionLoading.value = false
        resetPlaybackState()
    }
}

/**
 * Playback state from MediaPlayer.
 */
enum class PlaybackState {
    IDLE,
    BUFFERING,
    READY,
    ENDED
}
