# MA Library Data Architecture - Implementation Plan

## Status: Phase 2 Complete

Last updated: 2026-02-04

---

## Completed Phases

### Phase 1: Core Infrastructure (Done)
- Created `MaLibraryItem` interface in `musicassistant/model/MaLibraryItem.kt`
- Created `MaMediaType` enum (TRACK, ALBUM, ARTIST, PLAYLIST, RADIO)
- Implemented `MaTrack` and `MaPlaylist` data classes
- Created `LibraryItemAdapter` unified adapter

### Phase 2: Additional Library Types (Done)
- Added `MaAlbum` data class with artist, year, trackCount, albumType fields
- Added `MaArtist` data class
- Added `MaRadio` data class with provider field
- Added API methods: `getAlbums()`, `getArtists()`, `getRadioStations()`
- Added parse methods: `parseAlbums()`, `parseArtists()`, `parseRadioStations()`
- Updated `LibraryItemAdapter` to render all five types with appropriate subtitles

---

## Phase 3: Home Screen Integration

### Goal
Add Albums, Artists, and Radio sections to the Home screen using the new data types.

### Tasks

#### 3.1 Update HomeViewModel
**File**: `ui/navigation/home/HomeViewModel.kt`

Add StateFlows for new content types:
```kotlin
private val _albums = MutableStateFlow<List<MaAlbum>>(emptyList())
val albums: StateFlow<List<MaAlbum>> = _albums.asStateFlow()

private val _artists = MutableStateFlow<List<MaArtist>>(emptyList())
val artists: StateFlow<List<MaArtist>> = _artists.asStateFlow()

private val _radioStations = MutableStateFlow<List<MaRadio>>(emptyList())
val radioStations: StateFlow<List<MaRadio>> = _radioStations.asStateFlow()
```

Add fetch methods:
```kotlin
private fun loadAlbums() {
    viewModelScope.launch {
        MusicAssistantManager.getAlbums().onSuccess { _albums.value = it }
    }
}
// Similar for artists, radioStations
```

#### 3.2 Update HomeFragment
**File**: `ui/navigation/home/HomeFragment.kt`

- Add RecyclerViews for Albums, Artists, Radio sections
- Create adapters using `LibraryItemAdapter`
- Collect from new StateFlows and update adapters
- Consider section visibility based on content availability

#### 3.3 Update Home Layout
**File**: `res/layout/fragment_home.xml` (or wherever home layout is)

Add section containers:
```xml
<!-- Albums Section -->
<include layout="@layout/view_home_section" android:id="@+id/albumsSection" />

<!-- Artists Section -->
<include layout="@layout/view_home_section" android:id="@+id/artistsSection" />

<!-- Radio Section -->
<include layout="@layout/view_home_section" android:id="@+id/radioSection" />
```

### Verification
1. Build: `./gradlew assembleDebug`
2. Test on device with MA server connected
3. Verify all sections load and display correctly
4. Verify images load through imageproxy

---

## Phase 4: Playback Integration (Future)

### Goal
Enable playing items when tapped in Home screen carousels.

### Tasks

#### 4.1 Add Play Commands to MusicAssistantManager
```kotlin
suspend fun playItem(uri: String): Result<Unit>
suspend fun playAlbum(albumId: String): Result<Unit>
suspend fun playArtist(artistId: String): Result<Unit>
suspend fun playPlaylist(playlistId: String): Result<Unit>
suspend fun playRadio(radioId: String): Result<Unit>
```

#### 4.2 Wire Up Click Handlers
- Update `onItemClick` callbacks in HomeFragment
- Route to appropriate play command based on item type
- Show loading/error feedback

---

## Phase 5: Browse Library Screen (Future)

### Goal
Full library browsing with search, filters, and pagination.

### Tasks
- Create BrowseLibraryFragment
- Implement tabbed view (Tracks, Albums, Artists, Playlists, Radio)
- Add search functionality
- Implement infinite scroll / pagination
- Add sorting options (name, date added, etc.)

---

## API Endpoints Reference

| Type | Endpoint | Order Options |
|------|----------|---------------|
| Tracks | `music/tracks/library_items` | `timestamp_added_desc`, `name` |
| Albums | `music/albums/library_items` | `timestamp_added_desc`, `name`, `year` |
| Artists | `music/artists/library_items` | `name` |
| Playlists | `music/playlists/library_items` | `name` |
| Radio | `music/radio/library_items` | `name` |
| Recent | `music/recently_played_items` | N/A (pre-sorted) |

---

## JSON Field Mapping Reference

| Kotlin Field | JSON Field(s) | Fallbacks |
|--------------|---------------|-----------|
| itemId (track) | `item_id` | `track_id`, `uri` |
| albumId | `item_id` | `album_id`, `uri` |
| artistId | `item_id` | `artist_id`, `uri` |
| playlistId | `item_id` | `playlist_id`, `uri` |
| radioId | `item_id` | `radio_id`, `uri` |
| artist (on album) | `artists[0].name` | `artist` string |
| year | `year` | - |
| trackCount | `track_count` | - |
| albumType | `album_type` | - |
| provider (radio) | `provider` | `provider_mappings[0].provider_domain` |

---

## Files Modified in Phase 2

| File | Location |
|------|----------|
| MusicAssistantManager.kt | `app/src/main/java/com/sendspindroid/musicassistant/` |
| LibraryItemAdapter.kt | `app/src/main/java/com/sendspindroid/ui/navigation/home/` |

## Files to Modify in Phase 3

| File | Location |
|------|----------|
| HomeViewModel.kt | `app/src/main/java/com/sendspindroid/ui/navigation/home/` |
| HomeFragment.kt | `app/src/main/java/com/sendspindroid/ui/navigation/` |
| fragment_home.xml | `app/src/main/res/layout/` |
