# MA Library Data Architecture - Implementation Plan

## Status: Phase 4 Complete

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

### Phase 3: Home Screen Integration (Done)
- Added Albums, Artists, Radio sections to Home screen
- Updated HomeViewModel with LiveData for all six sections
- All sections load in parallel using async/await
- Updated HomeFragment with adapters and observers for new sections
- Updated fragment_home.xml with new section layouts
- Added string resources for section titles
- Fixed radio endpoint (`music/radios/library_items` - plural)
- Fixed album name parsing bug (optString returning JSON object as string)
- Added media_type filter to exclude standalone artists from Recently Played

### Phase 4: Playback Integration (Done)
- Added `playMedia()` method to MusicAssistantManager
- Uses app's own player ID (`UserSettings.getPlayerId()`) as queue target
- Click handlers in HomeFragment trigger playback via MA API
- Fixed URI construction for albums/artists/radio (construct `library://{type}/{id}` if API returns empty)
- Fixed LibraryItemGrouper to construct URIs for synthetic album cards
- Added player selection storage in MaSettings (for future "Choose Players" feature)

---

## Phase 5: Browse Library Screen (Future)

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

## Phase 6: Choose Players (Future)

### Goal
Let users select which MA player to control from the app.

### Tasks
- Implement player list fetching (`players/all`)
- Create ChoosePlayersBottomSheet UI
- Store selected player per-server in MaSettings
- Show current player name in UI

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
