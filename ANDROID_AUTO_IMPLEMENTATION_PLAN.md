# Android Auto Integration Implementation Plan for SendSpinDroid

## Executive Summary

This document provides a comprehensive plan for integrating Android Auto support into SendSpinDroid, an audio streaming app that uses a custom Go-based streaming protocol via gomobile. The implementation will build on the existing Media3 MediaSessionService background playback plan to add browsable content for Android Auto.

**Key Finding**: Media3's `MediaLibraryService` provides automatic Android Auto integration with minimal additional code beyond the background playback implementation.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Android Auto Requirements (2025)](#android-auto-requirements-2025)
3. [Architecture Overview](#architecture-overview)
4. [Implementation Roadmap](#implementation-roadmap)
5. [Testing Strategy](#testing-strategy)
6. [Complexity Assessment](#complexity-assessment)
7. [Potential Challenges](#potential-challenges)
8. [Code Examples](#code-examples)
9. [Resources](#resources)

---

## Prerequisites

### Must Be Completed First

Before implementing Android Auto support, the following must be completed:

1. **Background Playback Implementation** (from `wild-wishing-spark.md` plan)
   - Media3 MediaSessionService with ExoPlayer
   - Custom GoPlayerDataSource bridging Go audio to ExoPlayer
   - Foreground service with MediaStyle notifications
   - MediaSession with playback controls
   - Lock screen controls working

2. **API 35 Compliance** (already complete)
   - Target API 35 (Android 15)
   - Required for Play Store submission August 2025

3. **Critical Bug Fixes** (already addressed)
   - AudioTrack memory leak fixed
   - Race condition preventing multiple AudioTrack instances
   - Proper lifecycle management

**Timeline**: Implement background playback first, then add Android Auto browsing ~1-2 weeks later.

---

## Android Auto Requirements (2025)

### Minimum Requirements

| Requirement | Status | Notes |
|------------|--------|-------|
| **API Level** | Min API 26, Target API 35 | Already configured |
| **MediaSession** | Required | Part of background playback plan |
| **MediaBrowserService** | Required | Use `MediaLibraryService` (Media3) |
| **Browse Tree** | Required | Must implement content hierarchy |
| **Manifest Configuration** | Required | Add Android Auto metadata |
| **Distraction Safeguards** | Required | No video playback while driving |
| **Testing** | Recommended | DHU (Desktop Head Unit) available |

### Key Architecture Decision

**Use `MediaLibraryService` instead of `MediaSessionService`**

The background playback plan uses `MediaSessionService`, but for Android Auto we need `MediaLibraryService` which:
- Extends `MediaSessionService` (all background playback features included)
- Adds `onGetLibraryRoot()` and `onGetChildren()` for browse tree
- **Automatically exposes content to Android Auto** (no extra integration needed)
- Backward compatible with existing MediaController/MediaBrowser clients

**Impact**: Minor refactoring of PlaybackService.kt to extend `MediaLibraryService` instead of `MediaSessionService`.

---

## Architecture Overview

### SendSpinDroid Content Hierarchy

Android Auto requires a browsable tree structure. For SendSpinDroid, we'll organize content as:

```
Root
├── Servers (FLAG_BROWSABLE)
│   ├── Server 1 (FLAG_BROWSABLE + FLAG_PLAYABLE)
│   │   ├── Stream 1 (FLAG_PLAYABLE)
│   │   ├── Stream 2 (FLAG_PLAYABLE)
│   │   └── Stream 3 (FLAG_PLAYABLE)
│   ├── Server 2 (FLAG_BROWSABLE + FLAG_PLAYABLE)
│   └── Server 3 (FLAG_BROWSABLE + FLAG_PLAYABLE)
├── Recent (FLAG_BROWSABLE)
│   ├── Last Played Stream (FLAG_PLAYABLE)
│   └── Recently Connected Server (FLAG_PLAYABLE)
└── Manual Servers (FLAG_BROWSABLE)
    └── User Added Servers (FLAG_BROWSABLE + FLAG_PLAYABLE)
```

### Content Organization Rationale

1. **Servers**: Discovered or manually added SendSpin servers
   - Each server is both browsable (can view streams) and playable (connect and start default stream)
   - Individual streams within a server are playable only

2. **Recent**: Last played content for quick access
   - Supports Android Auto's media resumption feature
   - Shows most recent playback session

3. **Manual Servers**: User-added servers (from manual input dialog)
   - Separate category to distinguish from auto-discovered servers
   - Helps users find their manually configured servers quickly

### Media Item Structure

Each item needs:
- **Media ID**: Unique identifier (e.g., `server_10.0.2.8:8927` or `stream_1_server_xyz`)
- **Title**: Display name (e.g., "Living Room Server", "Jazz Radio")
- **Subtitle**: Additional info (e.g., server address, stream description)
- **Icon URI**: Server/stream artwork (optional, can use placeholder)
- **Flags**: `FLAG_BROWSABLE` and/or `FLAG_PLAYABLE`

---

## Implementation Roadmap

### Phase 1: Extend Background Playback Service (2-3 hours)

**Goal**: Upgrade `PlaybackService` from `MediaSessionService` to `MediaLibraryService`.

#### 1.1 Update PlaybackService.kt

**Current** (from background playback plan):
```kotlin
class PlaybackService : MediaSessionService() {
    // ... existing implementation
}
```

**Updated**:
```kotlin
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private var goPlayer: Player_? = null

    override fun onCreate() {
        super.onCreate()

        // Initialize Go player
        goPlayer = Player.newPlayer(
            android.os.Build.MODEL,
            PlaybackCallback()
        )

        // Initialize ExoPlayer with custom data source
        player = ExoPlayer.Builder(this).build().apply {
            setMediaSource(GoPlayerMediaSource(goPlayer!!))
            prepare()
        }

        // Create MediaLibrarySession (instead of MediaSession)
        mediaSession = MediaLibrarySession.Builder(this, player!!, LibraryCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player?.release()
            release()
            mediaSession = null
        }
        goPlayer?.cleanup()
        super.onDestroy()
    }
}
```

#### 1.2 Key Changes

- Change parent class from `MediaSessionService` to `MediaLibraryService`
- Use `MediaLibrarySession` instead of `MediaSession`
- Implement `MediaLibrarySession.Callback` (see Phase 2)

---

### Phase 2: Implement Browse Tree (4-6 hours)

**Goal**: Implement content hierarchy callbacks for Android Auto browsing.

#### 2.1 Create LibraryCallback Class

```kotlin
private inner class LibraryCallback : MediaLibrarySession.Callback {

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {

        // Root item represents the top level of the browse tree
        val rootItem = MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("SendSpinDroid")
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

        return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {

        val children = when (parentId) {
            ROOT_ID -> getRootChildren()
            SERVERS_ID -> getServersList()
            RECENT_ID -> getRecentPlayback()
            MANUAL_SERVERS_ID -> getManualServers()
            else -> {
                // Check if it's a specific server (to show streams)
                if (parentId.startsWith("server_")) {
                    getServerStreams(parentId)
                } else {
                    emptyList()
                }
            }
        }

        return Futures.immediateFuture(
            LibraryResult.ofItemList(children, params)
        )
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {

        // Retrieve specific item by ID
        val item = findItemById(mediaId)

        return if (item != null) {
            Futures.immediateFuture(LibraryResult.ofItem(item, null))
        } else {
            Futures.immediateFuture(
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }
    }

    // Handle search requests from Android Auto
    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {

        // Store search query for later retrieval via onGetSearchResult
        searchQueries[browser] = query

        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {

        // Search servers and streams by name
        val results = searchContent(query)

        return Futures.immediateFuture(
            LibraryResult.ofItemList(results, params)
        )
    }

    // Handle when user selects a media item to play
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {

        // Convert media IDs to playable MediaItems with proper URIs
        val resolvedItems = mediaItems.map { item ->
            resolveMediaItem(item)
        }

        return Futures.immediateFuture(resolvedItems)
    }
}
```

#### 2.2 Implement Helper Methods

```kotlin
companion object {
    private const val ROOT_ID = "root"
    private const val SERVERS_ID = "servers"
    private const val RECENT_ID = "recent"
    private const val MANUAL_SERVERS_ID = "manual_servers"
}

private val searchQueries = mutableMapOf<MediaSession.ControllerInfo, String>()

private fun getRootChildren(): List<MediaItem> {
    return listOf(
        createBrowsableItem(
            mediaId = SERVERS_ID,
            title = "Discovered Servers",
            subtitle = "Auto-discovered SendSpin servers"
        ),
        createBrowsableItem(
            mediaId = RECENT_ID,
            title = "Recent",
            subtitle = "Recently played streams"
        ),
        createBrowsableItem(
            mediaId = MANUAL_SERVERS_ID,
            title = "Manual Servers",
            subtitle = "Manually added servers"
        )
    )
}

private fun getServersList(): List<MediaItem> {
    // Query ServerRepository or shared state for discovered servers
    // This would come from the mDNS discovery already implemented
    return discoveredServers.map { server ->
        MediaItem.Builder()
            .setMediaId("server_${server.address}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(server.name)
                    .setSubtitle(server.address)
                    .setIsPlayable(true)  // Can connect directly
                    .setIsBrowsable(true)  // Can browse streams
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MUSIC)
                    .build()
            )
            .build()
    }
}

private fun getServerStreams(serverMediaId: String): List<MediaItem> {
    // Parse server address from media ID
    val serverAddress = serverMediaId.removePrefix("server_")

    // Query Go player for available streams on this server
    // This may require adding a new Go method: listStreams(address)
    val streams = goPlayer?.listStreams(serverAddress) ?: emptyList()

    return streams.map { stream ->
        MediaItem.Builder()
            .setMediaId("stream_${stream.id}_${serverAddress}")
            .setUri("sendspin://${serverAddress}/stream/${stream.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(stream.title)
                    .setArtist(stream.artist ?: "")
                    .setAlbumTitle(stream.album ?: "")
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }
}

private fun getRecentPlayback(): List<MediaItem> {
    // Load from SharedPreferences or Room database
    // Store last N played streams with timestamp
    return recentPlaybackRepository.getRecent(limit = 10).map { recent ->
        MediaItem.Builder()
            .setMediaId("recent_${recent.streamId}")
            .setUri(recent.streamUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(recent.title)
                    .setSubtitle("Last played ${recent.formattedTime}")
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }
}

private fun searchContent(query: String): List<MediaItem> {
    val results = mutableListOf<MediaItem>()

    // Search servers by name
    results.addAll(
        discoveredServers
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { server -> createServerItem(server) }
    )

    // Search streams (if we have stream metadata cached)
    results.addAll(
        cachedStreams
            .filter {
                it.title.contains(query, ignoreCase = true) ||
                it.artist?.contains(query, ignoreCase = true) == true
            }
            .map { stream -> createStreamItem(stream) }
    )

    return results
}

private fun resolveMediaItem(item: MediaItem): MediaItem {
    // Add proper URI and metadata for playback
    // Convert from browse item to playable item

    val mediaId = item.mediaId

    return when {
        mediaId.startsWith("server_") -> {
            // Connect to server and start default stream
            val address = mediaId.removePrefix("server_")
            item.buildUpon()
                .setUri("sendspin://${address}")
                .build()
        }
        mediaId.startsWith("stream_") -> {
            // Already has proper URI from getServerStreams()
            item
        }
        mediaId.startsWith("recent_") -> {
            // Already has URI from getRecentPlayback()
            item
        }
        else -> item
    }
}

private fun createBrowsableItem(
    mediaId: String,
    title: String,
    subtitle: String? = null
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
                .build()
        )
        .build()
}
```

#### 2.3 Server State Management

**Challenge**: The browse tree needs access to discovered servers, which are currently managed in MainActivity.

**Solution**: Create a shared ServerRepository using singleton pattern or dependency injection.

**New File**: `android/app/src/main/java/com/sendspindroid/ServerRepository.kt`

```kotlin
object ServerRepository {

    private val _servers = MutableStateFlow<List<ServerInfo>>(emptyList())
    val servers: StateFlow<List<ServerInfo>> = _servers.asStateFlow()

    private val _recentPlayback = MutableStateFlow<List<RecentPlayback>>(emptyList())
    val recentPlayback: StateFlow<List<RecentPlayback>> = _recentPlayback.asStateFlow()

    fun addServer(server: ServerInfo) {
        val current = _servers.value.toMutableList()
        if (!current.any { it.address == server.address }) {
            current.add(server)
            _servers.value = current
        }
    }

    fun removeServer(address: String) {
        _servers.value = _servers.value.filter { it.address != address }
    }

    fun addToRecent(playback: RecentPlayback) {
        val current = _recentPlayback.value.toMutableList()
        // Add to front, remove duplicates, limit to 20
        current.removeIf { it.streamId == playback.streamId }
        current.add(0, playback)
        _recentPlayback.value = current.take(20)
    }

    fun clearServers() {
        _servers.value = emptyList()
    }
}

data class RecentPlayback(
    val streamId: String,
    val title: String,
    val artist: String?,
    val streamUri: String,
    val timestamp: Long
) {
    val formattedTime: String
        get() = formatRelativeTime(timestamp)
}
```

**Update MainActivity.kt** to use ServerRepository:

```kotlin
// Replace direct servers list management
private fun addServer(server: ServerInfo) {
    ServerRepository.addServer(server)
}

// Collect servers for UI updates
lifecycleScope.launch {
    ServerRepository.servers.collect { servers ->
        serverAdapter.updateServers(servers)
    }
}
```

---

### Phase 3: Update Android Manifest (30 minutes)

#### 3.1 Add Android Auto Metadata

Add to `AndroidManifest.xml` within `<application>` tag:

```xml
<!-- Android Auto support -->
<meta-data
    android:name="com.google.android.gms.car.application"
    android:resource="@xml/automotive_app_desc"/>
```

#### 3.2 Update Service Declaration

Replace MediaSessionService declaration with MediaLibraryService:

```xml
<service
    android:name=".playback.PlaybackService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="true">
    <intent-filter>
        <!-- Media3 MediaLibraryService -->
        <action android:name="androidx.media3.session.MediaLibraryService"/>

        <!-- Backward compatibility with MediaBrowserService -->
        <action android:name="android.media.browse.MediaBrowserService"/>
    </intent-filter>
</service>

<!-- Media button receiver for playback resumption -->
<receiver
    android:name="androidx.media3.session.MediaButtonReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MEDIA_BUTTON"/>
    </intent-filter>
</receiver>
```

#### 3.3 Create automotive_app_desc.xml

Create file: `android/app/src/main/res/xml/automotive_app_desc.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="media"/>
</automotiveApp>
```

#### 3.4 Verify Existing Permissions

Ensure these are already in manifest (from background playback plan):

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
```

---

### Phase 4: Enhanced Go Player Interface (3-4 hours)

**Challenge**: Android Auto browse tree needs to list available streams on a server. Current Go player only supports connecting and playing.

**Solution**: Add new methods to Go player for stream enumeration.

#### 4.1 Update go-player/player.go

Add new methods:

```go
type Stream struct {
    ID       string
    Title    string
    Artist   string
    Album    string
    Duration int64
}

// ListStreams returns available streams on a server
func (p *Player) ListStreams(serverAddress string) ([]Stream, error) {
    // Connect to server and query available streams
    // This may require extending SendSpin protocol

    // Placeholder: Return dummy data for now
    return []Stream{
        {
            ID:     "stream1",
            Title:  "Main Stream",
            Artist: "",
            Album:  "",
        },
    }, nil
}

// ConnectToStream connects to a specific stream on a server
func (p *Player) ConnectToStream(serverAddress string, streamID string) error {
    // Connect to specific stream instead of default
    // May need protocol updates

    return p.Connect(serverAddress) // Use existing connection for now
}
```

**Note**: This may require coordination with SendSpin protocol. If protocol doesn't support stream enumeration:
- **Fallback**: Each server only shows "Connect" option (no streams to browse)
- **Future Enhancement**: Add stream discovery to protocol spec

#### 4.2 Update gomobile Bindings

Regenerate bindings after updating Go code:

```bash
./build-gomobile.sh
```

---

### Phase 5: Handle Playback from Browse Tree (2-3 hours)

**Goal**: When user selects an item in Android Auto, connect to the server and start playback.

#### 5.1 Implement onAddMediaItems Callback

```kotlin
override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>
): ListenableFuture<List<MediaItem>> {

    val updatedItems = mediaItems.map { item ->
        when {
            item.mediaId.startsWith("server_") -> {
                // User selected a server
                val serverAddress = item.mediaId.removePrefix("server_")
                handleServerConnection(serverAddress)

                // Return item with custom URI for Go player
                item.buildUpon()
                    .setUri("sendspin://${serverAddress}")
                    .build()
            }

            item.mediaId.startsWith("stream_") -> {
                // User selected a specific stream
                val parts = item.mediaId.removePrefix("stream_").split("_")
                val streamId = parts[0]
                val serverAddress = parts.drop(1).joinToString("_")

                handleStreamConnection(serverAddress, streamId)

                item.buildUpon()
                    .setUri("sendspin://${serverAddress}/stream/${streamId}")
                    .build()
            }

            else -> item
        }
    }

    return Futures.immediateFuture(updatedItems)
}

private fun handleServerConnection(serverAddress: String) {
    // Trigger connection to server
    lifecycleScope.launch {
        try {
            goPlayer?.connect(serverAddress)
            // Connection status will be reported via onConnected callback
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to server: $serverAddress", e)
        }
    }
}

private fun handleStreamConnection(serverAddress: String, streamId: String) {
    lifecycleScope.launch {
        try {
            goPlayer?.connectToStream(serverAddress, streamId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to stream: $streamId on $serverAddress", e)
        }
    }
}
```

#### 5.2 Update Custom MediaSource

Modify `GoPlayerMediaSource` to handle different stream URIs:

```kotlin
class GoPlayerMediaSource(
    private val goPlayer: Player_,
    private val connectionUri: String?
) : BaseMediaSource() {

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)

        // Parse connection URI to determine server and stream
        connectionUri?.let { uri ->
            val parsedUri = Uri.parse(uri)
            // sendspin://10.0.2.8:8927/stream/stream1

            if (parsedUri.scheme == "sendspin") {
                val serverAddress = parsedUri.authority // 10.0.2.8:8927
                val streamPath = parsedUri.path // /stream/stream1

                // Connection already initiated by onAddMediaItems
                // This just prepares the MediaSource
            }
        }

        // Rest of implementation from background playback plan
        // ...
    }
}
```

---

### Phase 6: Testing & Validation (4-6 hours)

#### 6.1 Desktop Head Unit (DHU) Setup

**Install DHU**:
1. Open Android Studio SDK Manager
2. Go to SDK Tools tab
3. Install "Android Auto Desktop Head Unit Emulator"
4. DHU location: `$ANDROID_SDK/extras/google/auto/`

**Run DHU**:
```bash
cd $ANDROID_SDK/extras/google/auto/
./desktop-head-unit --usb
```

Or use ADB tunneling:
```bash
# On device: Android Auto > Settings > Developer Settings > Start head unit server
adb forward tcp:5277 tcp:5277
./desktop-head-unit
```

#### 6.2 Testing Checklist

**Browse Tree Tests**:
- [ ] DHU shows SendSpinDroid in media apps list
- [ ] Can navigate to "Discovered Servers" category
- [ ] Servers discovered via mDNS appear in list
- [ ] Manual servers appear in "Manual Servers" category
- [ ] Recent playback shows in "Recent" category
- [ ] Server names and addresses display correctly
- [ ] Stream items appear when browsing into a server

**Playback Tests**:
- [ ] Selecting a server connects and starts playback
- [ ] Selecting a stream connects and plays that stream
- [ ] Metadata updates in Android Auto UI
- [ ] Play/pause controls work from DHU
- [ ] Volume control works from DHU
- [ ] Stop button disconnects properly
- [ ] Playback continues when switching between DHU and phone

**Error Handling**:
- [ ] Server connection failures show appropriate error
- [ ] Network disconnection handled gracefully
- [ ] Service starts when no Activity is running
- [ ] Service handles user not signed in (if applicable)

**Advanced Tests**:
- [ ] Search functionality finds servers/streams
- [ ] Voice commands work ("Play on Living Room Server")
- [ ] Resumption works after service restart
- [ ] Multiple controller connections work (phone + DHU)

#### 6.3 DHU Testing Commands

Useful DHU console commands:

```bash
# Day/night mode
day
night

# Simulate rotary controller
dpad up
dpad down
dpad click

# Voice input
mic begin
mic play $ANDROID_SDK/extras/google/auto/voice/pause.wav

# Screenshot for documentation
screenshot android_auto_browse.png
```

#### 6.4 Media Controller Test App

Install Google's Media Controller Test app to verify MediaSession implementation:
- GitHub: https://github.com/googlesamples/android-media-controller
- Tests all MediaSession features independent of Android Auto
- Useful for debugging browse tree issues

---

## Complexity Assessment

### Overall Complexity: **Medium-High (7/10)**

### Time Estimates

| Phase | Task | Time | Complexity |
|-------|------|------|------------|
| 1 | Extend PlaybackService to MediaLibraryService | 2-3 hours | Medium |
| 2 | Implement browse tree callbacks | 4-6 hours | High |
| 2a | Create ServerRepository | 1-2 hours | Low-Medium |
| 3 | Update AndroidManifest | 30 min | Low |
| 4 | Enhance Go player interface | 3-4 hours | Medium-High |
| 5 | Handle playback from browse tree | 2-3 hours | Medium |
| 6 | Testing with DHU | 4-6 hours | Medium |
| **Total** | | **17-24 hours** | |

### Complexity Breakdown

**Low Complexity (3/10)**:
- Manifest updates
- Creating automotive_app_desc.xml
- Adding Android Auto metadata

**Medium Complexity (5/10)**:
- Extending MediaSessionService to MediaLibraryService
- Creating ServerRepository for state sharing
- Implementing basic browse tree structure
- Testing with DHU

**High Complexity (8/10)**:
- Implementing all MediaLibrarySession.Callback methods
- Handling state synchronization between MainActivity and Service
- Go player stream enumeration (may require protocol changes)
- Custom MediaSource URI handling for different streams
- Search functionality implementation

**Very High Complexity (9/10)**:
- ExoPlayer integration with custom streaming (already planned)
- Thread safety between Go player callbacks and MediaSession
- Proper error propagation from Go to MediaSession to Android Auto UI

---

## Potential Challenges

### Challenge 1: Go Player Stream Enumeration

**Problem**: SendSpin protocol may not support listing available streams on a server.

**Impact**: Browse tree can't show individual streams, only servers.

**Solutions**:
1. **Minimal**: Each server is single playable item (no stream browsing)
2. **Medium**: Cache stream metadata from previous connections
3. **Full**: Update SendSpin protocol to support stream discovery (requires Go library changes)

**Recommendation**: Start with minimal approach, add stream browsing as v2 feature.

---

### Challenge 2: State Management Between Activity and Service

**Problem**: MainActivity manages server discovery, but PlaybackService needs access for browse tree.

**Impact**: Servers discovered in MainActivity won't appear in Android Auto.

**Solution**: Implement ServerRepository (covered in Phase 2) using StateFlow for reactive updates.

**Implementation Details**:
- Singleton object accessible from both MainActivity and PlaybackService
- Use StateFlow for reactive updates
- Persist to SharedPreferences for cold starts
- Alternative: Room database for full offline persistence

---

### Challenge 3: Service Cold Start

**Problem**: Android Auto may start PlaybackService when app has never been opened.

**Impact**: No servers discovered yet, browse tree is empty.

**Solutions**:
1. Show "Open app to discover servers" message
2. Trigger mDNS discovery from service (requires multicast lock in service)
3. Cache previously discovered servers from SharedPreferences

**Recommendation**: Implement caching (#3) + helpful message (#1) for best UX.

---

### Challenge 4: Connection State Management

**Problem**: User selects server in Android Auto, but connection takes time.

**Impact**: User may select item before connection completes.

**Solution**: Use MediaSession playback state transitions:
```kotlin
// When user selects server
player.playbackState = STATE_CONNECTING

// When Go player calls onConnected()
player.playbackState = STATE_BUFFERING

// When audio starts flowing
player.playbackState = STATE_PLAYING
```

Android Auto will show appropriate loading indicators automatically.

---

### Challenge 5: Custom Audio Source with ExoPlayer

**Problem**: Already identified in background playback plan - bridging Go player to ExoPlayer.

**Impact**: Complex custom DataSource implementation required.

**Status**: This is prerequisite (background playback plan), must be solved before Android Auto.

**Note**: If ExoPlayer bridging proves too difficult, fallback to AudioTrack service approach won't work well with Android Auto (Media3 MediaLibraryService expects ExoPlayer).

---

### Challenge 6: Testing Without Real Car

**Problem**: DHU may not perfectly replicate real Android Auto behavior.

**Solution**: Multi-layered testing approach:
1. **DHU** - Primary testing tool (readily available)
2. **Media Controller Test App** - Verify MediaSession independently
3. **Manual testing on phone** - Ensure notification controls work
4. **Firebase Test Lab** - Test on real car hardware (if needed for release)

---

## Code Examples

### Complete PlaybackService with Android Auto Support

```kotlin
package com.sendspindroid.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sendspindroid.MainActivity
import com.sendspindroid.ServerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import player.Player

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private var goPlayer: Player? = null

    companion object {
        private const val TAG = "PlaybackService"
        private const val ROOT_ID = "root"
        private const val SERVERS_ID = "servers"
        private const val RECENT_ID = "recent"
        private const val MANUAL_SERVERS_ID = "manual_servers"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize Go player
        goPlayer = Player.newPlayer(
            android.os.Build.MODEL,
            PlaybackCallback()
        )

        // Initialize ExoPlayer
        player = ExoPlayer.Builder(this).build()

        // Create session activity PendingIntent
        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Create MediaLibrarySession
        mediaSession = MediaLibrarySession.Builder(this, player!!, LibraryCallback())
            .setSessionActivity(pendingIntent)
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player?.release()
            release()
            mediaSession = null
        }
        goPlayer?.cleanup()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {

            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("SendSpinDroid")
                        .setIsPlayable(false)
                        .setIsBrowsable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()

            return Futures.immediateFuture(
                LibraryResult.ofItem(rootItem, params)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {

            val children = when (parentId) {
                ROOT_ID -> getRootChildren()
                SERVERS_ID -> getServersList()
                RECENT_ID -> getRecentPlayback()
                MANUAL_SERVERS_ID -> getManualServers()
                else -> {
                    if (parentId.startsWith("server_")) {
                        getServerStreams(parentId)
                    } else {
                        emptyList()
                    }
                }
            }

            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {

            // Find item by ID
            val item = findItemById(mediaId)

            return if (item != null) {
                Futures.immediateFuture(LibraryResult.ofItem(item, null))
            } else {
                Futures.immediateFuture(
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {

            // Search will be implemented in v2
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {

            val updatedItems = mediaItems.map { item ->
                when {
                    item.mediaId.startsWith("server_") -> {
                        val serverAddress = item.mediaId.removePrefix("server_")
                        goPlayer?.connect(serverAddress)

                        item.buildUpon()
                            .setUri("sendspin://${serverAddress}")
                            .build()
                    }
                    else -> item
                }
            }

            return Futures.immediateFuture(updatedItems)
        }
    }

    private fun getRootChildren(): List<MediaItem> {
        return listOf(
            createBrowsableItem(
                mediaId = SERVERS_ID,
                title = "Discovered Servers",
                subtitle = "Auto-discovered SendSpin servers"
            ),
            createBrowsableItem(
                mediaId = RECENT_ID,
                title = "Recent",
                subtitle = "Recently played"
            ),
            createBrowsableItem(
                mediaId = MANUAL_SERVERS_ID,
                title = "Manual Servers",
                subtitle = "Manually added servers"
            )
        )
    }

    private fun getServersList(): List<MediaItem> {
        // Get servers from repository (blocking for simplicity)
        val servers = runBlocking {
            ServerRepository.servers.first()
        }

        return servers.map { server ->
            MediaItem.Builder()
                .setMediaId("server_${server.address}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(server.name)
                        .setSubtitle(server.address)
                        .setIsPlayable(true)
                        .setIsBrowsable(false) // No streams to browse yet
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
                )
                .build()
        }
    }

    private fun getServerStreams(serverMediaId: String): List<MediaItem> {
        // Future enhancement: list streams on server
        return emptyList()
    }

    private fun getRecentPlayback(): List<MediaItem> {
        // Future enhancement: show recent playback
        return emptyList()
    }

    private fun getManualServers(): List<MediaItem> {
        // Same as discovered servers for now
        return getServersList()
    }

    private fun findItemById(mediaId: String): MediaItem? {
        return when {
            mediaId == ROOT_ID -> {
                MediaItem.Builder()
                    .setMediaId(ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("SendSpinDroid")
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .build()
                    )
                    .build()
            }
            mediaId.startsWith("server_") -> {
                val address = mediaId.removePrefix("server_")
                val servers = runBlocking { ServerRepository.servers.first() }
                servers.find { it.address == address }?.let { server ->
                    MediaItem.Builder()
                        .setMediaId(mediaId)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(server.name)
                                .setSubtitle(server.address)
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .build()
                        )
                        .build()
                }
            }
            else -> null
        }
    }

    private fun createBrowsableItem(
        mediaId: String,
        title: String,
        subtitle: String? = null
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
                    .build()
            )
            .build()
    }

    private inner class PlaybackCallback : player.PlayerCallback {
        override fun onConnected(serverName: String) {
            // Update metadata when connected
            updateMetadata(serverName, "", "")
        }

        override fun onMetadata(title: String?, artist: String?, album: String?) {
            updateMetadata(title, artist, album)
        }

        override fun onDisconnected() {
            player?.stop()
        }

        override fun onStateChanged(state: String) {
            // Update player state
        }

        override fun onError(message: String) {
            // Handle error
        }

        override fun onServerDiscovered(name: String, address: String) {
            // Server discovery handled by MainActivity
        }
    }

    private fun updateMetadata(title: String?, artist: String?, album: String?) {
        val metadata = MediaMetadata.Builder()
            .setTitle(title ?: "SendSpinDroid")
            .setArtist(artist ?: "")
            .setAlbumTitle(album ?: "")
            .build()

        player?.setMediaMetadata(metadata)
    }
}
```

---

## Resources

### Official Documentation

- [Android for Cars - Media Apps](https://developer.android.com/training/cars/media)
- [Media3 Library Overview](https://developer.android.com/media/media3)
- [Background Playback with MediaSessionService](https://developer.android.com/media/media3/session/background-playback)
- [Testing with Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu)

### Sample Projects

- [Universal Android Music Player (UAMP)](https://github.com/android/uamp)
- [Media3 Session Sample](https://github.com/androidx/media/tree/release/demos/session)
- [Media Controller Test App](https://github.com/googlesamples/android-media-controller)

### SendSpinDroid Specific

- Background Playback Plan: `/home/chris/.claude/plans/wild-wishing-spark.md`
- Current MainActivity: `/home/chris/Documents/SendSpinDroid/android/app/src/main/java/com/sendspindroid/MainActivity.kt`
- Build Configuration: `/home/chris/Documents/SendSpinDroid/android/app/build.gradle.kts`

---

## Implementation Checklist

### Prerequisites
- [ ] Background playback with MediaSessionService working
- [ ] ExoPlayer integration with Go player complete
- [ ] Foreground service with notifications functional
- [ ] Lock screen controls tested

### Phase 1: Service Extension
- [ ] Change PlaybackService to extend MediaLibraryService
- [ ] Replace MediaSession with MediaLibrarySession
- [ ] Update build.gradle.kts dependencies if needed
- [ ] Verify service still compiles and runs

### Phase 2: Browse Tree
- [ ] Create ServerRepository singleton
- [ ] Implement onGetLibraryRoot()
- [ ] Implement onGetChildren() for root level
- [ ] Implement getServersList()
- [ ] Implement onGetItem()
- [ ] Implement onAddMediaItems()
- [ ] Update MainActivity to use ServerRepository

### Phase 3: Manifest
- [ ] Add com.google.android.gms.car.application metadata
- [ ] Create automotive_app_desc.xml
- [ ] Update service intent-filter
- [ ] Add MediaButtonReceiver
- [ ] Verify all permissions present

### Phase 4: Go Player (Optional)
- [ ] Add listStreams() method to Go player
- [ ] Add connectToStream() method
- [ ] Rebuild gomobile bindings
- [ ] Update browse tree to show streams

### Phase 5: Playback Handling
- [ ] Test server connection from browse tree
- [ ] Verify playback starts correctly
- [ ] Handle connection errors gracefully
- [ ] Update MediaSession state during connection

### Phase 6: Testing
- [ ] Install DHU on development machine
- [ ] Test browse tree appears in DHU
- [ ] Test server selection and playback
- [ ] Test play/pause controls
- [ ] Test metadata display
- [ ] Test error scenarios
- [ ] Screenshot all browse levels for documentation

### Polish
- [ ] Add server icons/artwork (optional)
- [ ] Implement search functionality
- [ ] Implement recent playback
- [ ] Add stream enumeration (if protocol supports)
- [ ] Persist servers to SharedPreferences
- [ ] Add loading states in browse tree

---

## Success Criteria

### Minimum Viable Product (MVP)

1. **Browse Tree Appears**: SendSpinDroid shows up in Android Auto media apps
2. **Server List Visible**: Discovered servers appear in browse tree
3. **Connection Works**: Selecting a server connects and starts playback
4. **Controls Work**: Play/pause/stop controls function from Android Auto
5. **Metadata Displays**: Track info shows in Android Auto UI
6. **Service Survives**: Service starts without app being opened

### Full Feature Set (v2)

1. All MVP criteria met
2. Stream enumeration (individual streams browsable)
3. Search functionality (find servers by name)
4. Recent playback history
5. Custom artwork for servers/streams
6. Voice command support ("Play on Living Room Server")
7. Playback resumption after service restart
8. Manual server management from Android Auto (future enhancement)

---

## Timeline Estimate

### Conservative Estimate (22-24 hours)
- Week 1: Complete background playback implementation (prerequisite)
- Week 2: Implement Android Auto integration (Phases 1-5)
- Week 3: Testing and polish (Phase 6)

### Aggressive Estimate (15-18 hours)
- Background playback already complete
- Phases 1-3: 1 day
- Phases 4-5: 1 day
- Phase 6: Half day

### Realistic Estimate for Production Ready
- Background playback: 10-15 hours (from prerequisite plan)
- Android Auto integration: 17-24 hours (this plan)
- Testing and bug fixes: 5-10 hours
- **Total: 32-49 hours** (1-1.5 weeks full-time)

---

## Next Steps

1. **Complete Background Playback** (prerequisite)
   - Follow `wild-wishing-spark.md` plan
   - Get MediaSessionService working with ExoPlayer
   - Test foreground service and notifications

2. **Begin Android Auto Integration**
   - Start with Phase 1 (service extension)
   - Implement minimal browse tree (Phase 2)
   - Add manifest configuration (Phase 3)

3. **Test Early and Often**
   - Set up DHU as soon as Phase 3 is complete
   - Test each browse tree level as it's implemented
   - Use Media Controller Test app to verify MediaSession

4. **Iterate on User Experience**
   - Gather feedback on browse tree organization
   - Add polish features (artwork, search, recent)
   - Consider user requests for stream organization

---

## Conclusion

Android Auto integration for SendSpinDroid is **highly feasible** and builds naturally on the background playback implementation. The Media3 `MediaLibraryService` provides automatic Android Auto support with minimal additional code.

**Key Success Factors**:
1. Complete background playback first (prerequisite)
2. Use MediaLibraryService instead of MediaSessionService
3. Implement simple browse tree initially (servers only)
4. Test with DHU early and iteratively
5. Enhance with streams/search as v2 features

**Estimated Development Time**: 17-24 hours for full Android Auto integration (after background playback is complete).

**Risk Level**: Medium - Most complexity is in prerequisite background playback, Android Auto integration is relatively straightforward.

**Recommendation**: Proceed with implementation after background playback is stable and tested.
