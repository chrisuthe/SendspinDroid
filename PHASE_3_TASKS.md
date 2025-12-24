# Phase 3: PlaybackService - Detailed Task Breakdown

This phase creates the MediaSessionService that enables background audio playback. This is the most critical phase and requires careful incremental implementation.

## Architecture Decisions

### Key Questions Resolved

| Question | Decision | Rationale |
|----------|----------|-----------|
| MediaSessionService vs MediaLibraryService? | **MediaSessionService** | Simpler for now; upgrade to MediaLibraryService for Android Auto later |
| Who owns the Go player? | **PlaybackService** | Service lifecycle is independent of Activity; cleaner ownership |
| ExoPlayer or AudioTrack? | **ExoPlayer with AudioTrack fallback** | Try ExoPlayer first for MediaSession integration; AudioTrack as backup |
| How does Activity communicate with Service? | **MediaController + Custom Commands** | Standard pattern for media apps |
| Server discovery location? | **Stays in MainActivity** | Discovery is UI-related; Service handles playback |

### Component Responsibilities

```
┌─────────────────────────────────────────────────────────────────┐
│                        MainActivity                              │
│  - Server discovery (mDNS)                                      │
│  - Server list UI                                               │
│  - Playback controls UI                                         │
│  - Sends commands via MediaController                           │
│  - Receives state updates via MediaController.Listener          │
└────────────────────────────┬────────────────────────────────────┘
                             │ MediaController
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                       PlaybackService                            │
│  - Owns Go player instance                                      │
│  - Owns ExoPlayer instance                                      │
│  - MediaSession for system integration                          │
│  - Foreground service for background playback                   │
│  - Handles custom commands (connect/disconnect)                 │
│  - Updates metadata from Go player callbacks                    │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                     GoPlayerDataSource                           │
│  - Bridges Go player audio to ExoPlayer                         │
│  - Ring buffer for timing synchronization                       │
│  - Called by ExoPlayer on playback thread                       │
└─────────────────────────────────────────────────────────────────┘
```

---

## Risk Analysis

### High Risk: ExoPlayer + Raw PCM

**Problem**: ProgressiveMediaSource expects container formats (MP4, MP3), not raw PCM.

**Symptoms if it fails**:
- ExoPlayer throws "Extractor not found" error
- No audio output despite data flowing
- Immediate playback errors

**Detection**: Task 3.4 will test this explicitly.

**Fallback Plan (Task 3.4b)**:
If ExoPlayer doesn't work with raw PCM:
1. Keep ExoPlayer for MediaSession integration only (don't use for actual playback)
2. Use AudioTrack directly in service (like current MainActivity)
3. Manually sync AudioTrack state with ExoPlayer state

### Medium Risk: Go Player Thread Safety

**Problem**: Go player callbacks come from Go runtime threads, not Android threads.

**Mitigation**:
- Use Handler to post to main thread
- Don't call Go player methods from callback context
- Use StateFlow for thread-safe state sharing

### Medium Risk: Foreground Service Requirements

**Problem**: Android requires notification immediately when service starts.

**Mitigation**:
- Call startForeground() in onCreate() or within 5 seconds of onStartCommand()
- Use ServiceCompat.startForeground() for API compatibility
- Have default notification ready before any async operations

---

## Task Breakdown

### Task 3.1: Create PlaybackService Skeleton
**Time Estimate:** 45 minutes
**Complexity:** Medium (5/10)
**Breaks App:** ❌ No - Service exists but isn't started yet

**Goal**: Create minimal MediaSessionService that compiles and can be registered.

**Files to Create:**
- `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`

**Files to Modify:**
- `android/app/src/main/AndroidManifest.xml` - Register service

**Implementation:**
```kotlin
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    companion object {
        private const val TAG = "PlaybackService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlaybackService created")

        // Create notification channel (use our helper)
        NotificationHelper.createNotificationChannel(this)
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        Log.d(TAG, "PlaybackService destroyed")
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
```

**Manifest Addition:**
```xml
<service
    android:name=".playback.PlaybackService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```

**Success Criteria:**
- [ ] Service class compiles
- [ ] Service registered in manifest
- [ ] App builds successfully
- [ ] No runtime changes (service not started yet)

---

### Task 3.2: Add ExoPlayer Initialization
**Time Estimate:** 1 hour
**Complexity:** Medium (6/10)
**Breaks App:** ❌ No - Still not started yet
**Depends On:** Task 3.1

**Goal**: Initialize ExoPlayer with proper audio configuration.

**Implementation:**
```kotlin
private var player: ExoPlayer? = null

override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "PlaybackService created")

    NotificationHelper.createNotificationChannel(this)

    // Configure audio attributes for music playback
    val audioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

    // Create ExoPlayer with audio focus handling
    player = ExoPlayer.Builder(this)
        .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
        .setHandleAudioBecomingNoisy(true) // Pause when headphones unplugged
        .build()

    Log.d(TAG, "ExoPlayer initialized")
}

override fun onDestroy() {
    Log.d(TAG, "PlaybackService destroyed")
    mediaSession?.run {
        player.release()
        release()
    }
    player?.release()
    player = null
    mediaSession = null
    super.onDestroy()
}
```

**Success Criteria:**
- [ ] ExoPlayer created with correct audio attributes
- [ ] Audio focus handling enabled
- [ ] Headphone unplug handling enabled
- [ ] Proper cleanup in onDestroy

---

### Task 3.3: Add MediaSession
**Time Estimate:** 1 hour
**Complexity:** Medium-High (7/10)
**Breaks App:** ❌ No - Still not connected
**Depends On:** Task 3.2

**Goal**: Create MediaSession that wraps ExoPlayer for system integration.

**Implementation:**
```kotlin
override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "PlaybackService created")

    NotificationHelper.createNotificationChannel(this)

    // ... ExoPlayer setup from Task 3.2 ...

    // Create MediaSession wrapping ExoPlayer
    // MediaSessionService automatically handles foreground notification
    mediaSession = MediaSession.Builder(this, player!!)
        .setCallback(PlaybackSessionCallback())
        .build()

    Log.d(TAG, "MediaSession created")
}

/**
 * Callback for MediaSession events.
 * Handles custom commands and playback actions.
 */
private inner class PlaybackSessionCallback : MediaSession.Callback {

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        // Accept all connections for now
        // Could add package verification for security
        Log.d(TAG, "Controller connected: ${controller.packageName}")
        return MediaSession.ConnectionResult.accept(
            session.token.availableSessionCommands,
            session.token.availablePlayerCommands
        )
    }

    override fun onDisconnected(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ) {
        Log.d(TAG, "Controller disconnected: ${controller.packageName}")
    }
}
```

**Success Criteria:**
- [ ] MediaSession created successfully
- [ ] Callback receives connect/disconnect events
- [ ] No foreground service errors
- [ ] App builds and runs

---

### Task 3.4: Test ExoPlayer with GoPlayerMediaSource
**Time Estimate:** 2 hours
**Complexity:** High (8/10)
**Breaks App:** ⚠️ Potentially - This is where we discover if ExoPlayer works
**Depends On:** Task 3.3

**Goal**: Wire GoPlayerMediaSource to ExoPlayer and test if raw PCM playback works.

**This task is investigative** - we need to find out if our approach works.

**Test Steps:**
1. Add temporary Go player initialization
2. Wire GoPlayerMediaSourceFactory
3. Attempt playback
4. Observe results

**Possible Outcomes:**

**Outcome A: It Works! ✅**
- Continue with planned implementation
- Go player audio flows through ExoPlayer
- MediaSession shows playback state

**Outcome B: Extractor Error ❌**
- ProgressiveMediaSource can't parse raw PCM
- Need to implement custom Extractor
- Or fall back to AudioTrack (Task 3.4b)

**Outcome C: Format Mismatch ❌**
- Audio plays but sounds wrong (wrong sample rate, etc.)
- Need to configure format explicitly
- May need RawResourceDataSource approach

**Implementation for Testing:**
```kotlin
// Temporary test code - will be refactored
private var goPlayer: player.Player_? = null

private fun testPlayback(serverAddress: String) {
    try {
        // Create Go player with minimal callback
        goPlayer = player.Player.newPlayer("Test", object : player.PlayerCallback {
            override fun onServerDiscovered(name: String, address: String) {}
            override fun onConnected(serverName: String) {
                Log.d(TAG, "TEST: Connected to $serverName")
                startExoPlayerPlayback()
            }
            override fun onDisconnected() {
                Log.d(TAG, "TEST: Disconnected")
            }
            override fun onStateChanged(state: String) {
                Log.d(TAG, "TEST: State = $state")
            }
            override fun onMetadata(title: String, artist: String, album: String) {
                Log.d(TAG, "TEST: Metadata = $title / $artist / $album")
            }
            override fun onError(message: String) {
                Log.e(TAG, "TEST: Error = $message")
            }
        })

        goPlayer?.connect(serverAddress)
    } catch (e: Exception) {
        Log.e(TAG, "TEST: Failed to connect", e)
    }
}

private fun startExoPlayerPlayback() {
    val mediaSource = GoPlayerMediaSourceFactory.create(goPlayer!!)
    player?.setMediaSource(mediaSource)
    player?.prepare()
    player?.play()
}
```

**Success Criteria:**
- [ ] Determine if ExoPlayer works with our MediaSource
- [ ] Document results and any errors
- [ ] Decide on next steps based on outcome

---

### Task 3.4b: AudioTrack Fallback (ONLY IF 3.4 FAILS)
**Time Estimate:** 2-3 hours
**Complexity:** High (8/10)
**Conditional:** Only implement if Task 3.4 shows ExoPlayer doesn't work

**Goal**: Use AudioTrack for actual playback while keeping ExoPlayer for MediaSession.

**Approach:**
- ExoPlayer runs but doesn't output audio (silent mode)
- AudioTrack runs separately (like current MainActivity)
- Manually sync play/pause/stop state between them

This is more complex but guarantees audio works.

**Implementation sketch:**
```kotlin
// ExoPlayer for MediaSession only
private var player: ExoPlayer? = null

// AudioTrack for actual audio
private var audioTrack: AudioTrack? = null
private var audioJob: Job? = null

private fun startAudioTrackPlayback() {
    val sampleRate = 48000
    val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    audioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(audioFormat)
                .build()
        )
        .setBufferSizeInBytes(bufferSize)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    audioTrack?.play()

    // Pump audio data
    audioJob = serviceScope.launch(Dispatchers.IO) {
        val buffer = ByteArray(8192)
        while (isActive) {
            val bytesRead = goPlayer?.readAudioData(buffer)?.toInt() ?: 0
            if (bytesRead > 0) {
                audioTrack?.write(buffer, 0, bytesRead)
            }
        }
    }
}
```

---

### Task 3.5: Add Go Player to Service
**Time Estimate:** 1.5 hours
**Complexity:** Medium-High (7/10)
**Breaks App:** ⚠️ Partial - Service now manages Go player
**Depends On:** Task 3.4 (or 3.4b)

**Goal**: Service owns Go player lifecycle, handles callbacks properly.

**Key Considerations:**
- Go player callbacks come from Go runtime threads
- Need Handler or coroutine dispatcher for thread safety
- State should be exposed via StateFlow for observers

**Implementation:**
```kotlin
// Coroutine scope tied to service lifecycle
private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

// Thread-safe state
private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

private var goPlayer: player.Player_? = null

override fun onCreate() {
    super.onCreate()

    // ... ExoPlayer and MediaSession setup ...

    initializeGoPlayer()
}

private fun initializeGoPlayer() {
    goPlayer = player.Player.newPlayer(
        android.os.Build.MODEL,
        GoPlayerCallback()
    )
    Log.d(TAG, "Go player initialized")
}

private inner class GoPlayerCallback : player.PlayerCallback {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServerDiscovered(name: String, address: String) {
        // Server discovery stays in MainActivity
        // Service doesn't need this callback
    }

    override fun onConnected(serverName: String) {
        mainHandler.post {
            Log.d(TAG, "Connected to: $serverName")
            _connectionState.value = ConnectionState.Connected(serverName)
            startPlayback()
        }
    }

    override fun onDisconnected() {
        mainHandler.post {
            Log.d(TAG, "Disconnected")
            _connectionState.value = ConnectionState.Disconnected
            stopPlayback()
        }
    }

    override fun onStateChanged(state: String) {
        mainHandler.post {
            Log.d(TAG, "State: $state")
            updatePlaybackState(state)
        }
    }

    override fun onMetadata(title: String, artist: String, album: String) {
        mainHandler.post {
            Log.d(TAG, "Metadata: $title / $artist / $album")
            updateMediaMetadata(title, artist, album)
        }
    }

    override fun onError(message: String) {
        mainHandler.post {
            Log.e(TAG, "Error: $message")
            _connectionState.value = ConnectionState.Error(message)
        }
    }
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val serverName: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

override fun onDestroy() {
    serviceScope.cancel()
    goPlayer?.cleanup()
    goPlayer = null
    // ... rest of cleanup ...
    super.onDestroy()
}
```

**Success Criteria:**
- [ ] Go player initialized in service
- [ ] Callbacks handled on main thread
- [ ] State exposed via StateFlow
- [ ] Proper cleanup on destroy

---

### Task 3.6: Implement Custom Session Commands
**Time Estimate:** 1.5 hours
**Complexity:** Medium-High (7/10)
**Breaks App:** ❌ No - Adds commands, doesn't change existing behavior
**Depends On:** Task 3.5

**Goal**: Allow MainActivity to send connect/disconnect commands to service.

**Custom Commands:**
- `COMMAND_CONNECT` - Connect to server (args: serverAddress)
- `COMMAND_DISCONNECT` - Disconnect from current server
- `COMMAND_SET_VOLUME` - Set volume (args: volume float)

**Implementation:**
```kotlin
companion object {
    const val COMMAND_CONNECT = "com.sendspindroid.CONNECT"
    const val COMMAND_DISCONNECT = "com.sendspindroid.DISCONNECT"
    const val COMMAND_SET_VOLUME = "com.sendspindroid.SET_VOLUME"

    const val ARG_SERVER_ADDRESS = "server_address"
    const val ARG_VOLUME = "volume"
}

private inner class PlaybackSessionCallback : MediaSession.Callback {

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        // Define available custom commands
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            .add(SessionCommand(COMMAND_CONNECT, Bundle.EMPTY))
            .add(SessionCommand(COMMAND_DISCONNECT, Bundle.EMPTY))
            .add(SessionCommand(COMMAND_SET_VOLUME, Bundle.EMPTY))
            .build()

        return MediaSession.ConnectionResult.accept(
            sessionCommands,
            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {

        return when (customCommand.customAction) {
            COMMAND_CONNECT -> {
                val address = args.getString(ARG_SERVER_ADDRESS)
                if (address != null) {
                    connectToServer(address)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                } else {
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                }
            }

            COMMAND_DISCONNECT -> {
                disconnectFromServer()
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            COMMAND_SET_VOLUME -> {
                val volume = args.getFloat(ARG_VOLUME, 1.0f)
                setVolume(volume)
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            else -> {
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }
    }
}

private fun connectToServer(address: String) {
    Log.d(TAG, "Connecting to: $address")
    _connectionState.value = ConnectionState.Connecting
    goPlayer?.connect(address)
}

private fun disconnectFromServer() {
    Log.d(TAG, "Disconnecting")
    goPlayer?.disconnect()
}

private fun setVolume(volume: Float) {
    Log.d(TAG, "Setting volume: $volume")
    goPlayer?.setVolume(volume)
    player?.volume = volume
}
```

**Success Criteria:**
- [ ] Custom commands defined
- [ ] Commands available to connected controllers
- [ ] Connect/disconnect works via command
- [ ] Volume control works via command

---

### Task 3.7: Handle Metadata Updates
**Time Estimate:** 45 minutes
**Complexity:** Medium (5/10)
**Depends On:** Task 3.5

**Goal**: Update MediaSession metadata when Go player reports track changes.

**Implementation:**
```kotlin
private fun updateMediaMetadata(title: String, artist: String, album: String) {
    val metadata = MediaMetadata.Builder()
        .setTitle(title.ifEmpty { "SendSpinDroid" })
        .setArtist(artist.ifEmpty { null })
        .setAlbumTitle(album.ifEmpty { null })
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .build()

    // Update the media item with new metadata
    val currentItem = player?.currentMediaItem
    val updatedItem = currentItem?.buildUpon()
        ?.setMediaMetadata(metadata)
        ?.build()
        ?: MediaItem.Builder()
            .setMediaMetadata(metadata)
            .build()

    // This updates what shows in notifications and lock screen
    player?.replaceMediaItem(0, updatedItem)

    Log.d(TAG, "Updated metadata: $title / $artist / $album")
}
```

**Success Criteria:**
- [ ] Metadata updates when Go player reports changes
- [ ] Notification shows current track info
- [ ] Lock screen shows current track info

---

### Task 3.8: Service Lifecycle Edge Cases
**Time Estimate:** 1 hour
**Complexity:** Medium (6/10)
**Depends On:** All previous tasks

**Goal**: Handle edge cases for robust service behavior.

**Edge Cases:**
1. Service killed by system (low memory)
2. Service started multiple times
3. Playback in progress when app closed
4. Network disconnection during playback

**Implementation:**
```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.d(TAG, "onStartCommand: action=${intent?.action}")

    // Let parent handle MediaSession lifecycle
    super.onStartCommand(intent, flags, startId)

    // Don't restart automatically if killed
    // User must explicitly start playback again
    return START_NOT_STICKY
}

override fun onTaskRemoved(rootIntent: Intent?) {
    Log.d(TAG, "Task removed - checking if should stop")

    // If not playing, stop the service
    if (player?.isPlaying != true) {
        Log.d(TAG, "Not playing, stopping service")
        stopSelf()
    }

    // If playing, service continues in background (this is the goal!)
    super.onTaskRemoved(rootIntent)
}
```

**Success Criteria:**
- [ ] Service handles being killed gracefully
- [ ] Multiple starts don't cause issues
- [ ] Playback continues when app closed
- [ ] Clean stop when not playing and app closed

---

## Phase 3 Execution Order

```
Task 3.1 (Skeleton)
    ↓
Task 3.2 (ExoPlayer)
    ↓
Task 3.3 (MediaSession)
    ↓
Task 3.4 (Test ExoPlayer+GoPlayer) ─── DECISION POINT
    ↓                                        ↓
    ├─[Works]──────────────────────────────→ Continue
    │                                        ↓
    └─[Fails]─→ Task 3.4b (AudioTrack) ────→ Continue
                                             ↓
Task 3.5 (Go Player in Service)
    ↓
Task 3.6 (Custom Commands)
    ↓
Task 3.7 (Metadata)
    ↓
Task 3.8 (Edge Cases)
```

---

## Testing Strategy

After each task, verify:
1. App builds successfully
2. App runs without crashes
3. Existing functionality still works
4. New functionality works as expected

After Phase 3 complete:
1. Service starts when connect is requested
2. Audio plays in background
3. Notification shows with controls
4. Lock screen shows controls
5. Play/pause from notification works
6. Disconnect stops playback
7. Closing app doesn't stop playback (if playing)
8. Phone call pauses playback

---

## Time Estimate Summary

| Task | Time | Risk |
|------|------|------|
| 3.1 Skeleton | 45 min | Low |
| 3.2 ExoPlayer | 1 hr | Low |
| 3.3 MediaSession | 1 hr | Medium |
| 3.4 Test Integration | 2 hr | **High** |
| 3.4b Fallback (if needed) | 2-3 hr | Medium |
| 3.5 Go Player | 1.5 hr | Medium |
| 3.6 Custom Commands | 1.5 hr | Medium |
| 3.7 Metadata | 45 min | Low |
| 3.8 Edge Cases | 1 hr | Low |

**Total (if ExoPlayer works):** ~9.5 hours
**Total (if fallback needed):** ~12 hours

---

## Success Metrics

Phase 3 is complete when:
- ✅ Service starts and runs in foreground
- ✅ Audio plays when connected to server
- ✅ Audio continues in background (app minimized)
- ✅ Audio continues with screen locked
- ✅ Notification shows with play/pause/stop
- ✅ Lock screen shows playback controls
- ✅ Phone calls pause playback (audio focus)
- ✅ Headphone unplug pauses playback
- ✅ Closing app doesn't stop active playback
- ✅ Disconnect command works from MainActivity

**NOT in scope for Phase 3:**
- MainActivity UI changes (Phase 4)
- Removing old AudioTrack code from MainActivity (Phase 4)
- Bluetooth headset buttons (may work automatically)
- Android Auto (future enhancement)
