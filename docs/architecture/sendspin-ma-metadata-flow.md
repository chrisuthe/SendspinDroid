# SendSpin + Music Assistant — Metadata and Artwork Flow

**Status:** working reference, 2026-04-22. Based on observed on-device log captures and a read of the MA server source at `C:/codeprojects/server/music_assistant/providers/sendspin/` plus the `aiosendspin` library at `/tmp/aiosendspin/`.

**Audience:** SendSpinDroid maintainers diagnosing "metadata lags the audio" complaints, "widget shows wrong artwork" complaints, or reasoning about where a fix belongs (client, server, or protocol).

---

## 1. Actors

1. **MA server** — Python, `music_assistant/providers/sendspin/player.py` + `aiosendspin` library. Owns the queue, knows the true "current track." Produces metadata and artwork.
2. **SendSpin WebSocket protocol** — text JSON messages (`server/state`, `stream/start`, etc.) + binary frames (audio, artwork, visualizer). Defined in `aiosendspin`.
3. **SendSpinClient (Android)** — `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt`. Terminates the WebSocket, parses frames, invokes callback interface.
4. **PlaybackService (Android)** — `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`. Holds the MediaSession, dispatches metadata to Android media surfaces. Owns `currentArtwork: Bitmap?`.
5. **`_playbackState: MutableStateFlow<PlaybackState>`** — app-internal state, observed by the Compose UI for in-app rendering.
6. **Android MediaSession** — via `forwardingPlayer.updateMetadata(artwork=bitmap, artworkUri=uri, ...)`. Drives lock-screen widget, notification shade media, Android Auto, Bluetooth AVRCP.
7. **MA command channel (separate WebSocket)** — `MaCommandClient.kt`, used for queue fetch and player-level commands. Independent of the SendSpin WebSocket.

---

## 2. Flow at connect (first track)

```
Client                                             Server
  |-- TCP + WS upgrade ----------------------------->|
  |-- client/hello (artwork: channels=[{source:album,...}]) ----->|
  |<--- server/hello --------------------------------|
  |<--- server/state (full metadata: title/artist/album/artwork_url/progress/...) |
  |<--- server/state (controller: commands, volume, muted) ----|
  |<--- group/update (playback_state: "stopped" | "playing") --|
  |<--- stream/start (artwork: channels config, with codec/sample rate/header) --|
  |<--- binary type 8 (channel 0 = album artwork, JPEG payload) -|
  |<--- binary type 4 (audio chunks, start streaming) ----------|
  ...
```

Client handling for each inbound message:

| Message | `SendSpinClient` dispatch | `PlaybackService` handler | Side effects |
|---|---|---|---|
| `server/state` metadata | `onMetadataUpdate(title, artist, album, artworkUrl, ...)` | `_playbackState.withMetadata(...)`, `sendSpinPlayer.updateMediaItem(...)`, `fetchArtwork(url)` *if URL changed* | UI (app) sees new fields; MediaSession gets title/artist; Coil starts URL fetch |
| `stream/start` | `onStreamStart(codec, ...)` | Decoder release + recreate; `SyncAudioPlayer` reuse-or-create | Audio pipeline prepared |
| Binary type 8 (channel 0, payload non-empty) | `onArtwork(channel=0, payload)` → `callback.onArtwork(payload)` — **channel argument dropped** | Decode JPEG → `currentArtwork = scaled`; `updateMediaSessionArtwork(bitmap)` | MediaSession artwork set |
| Binary type 8 (channel 0, payload empty) | `onArtwork(channel=0, payload=empty)` → `callback.onArtworkCleared()` | `currentArtwork = null`; `updateMediaMetadata(...)` | MediaSession artwork cleared |
| Binary type 4 | `onAudioChunk(timestamp, data)` | Submit to decoder; queue PCM; AudioTrack.write | Audio output |

Fetched-URL path (client-side only):

```
PlaybackService.fetchArtwork(url)
  ↓ serviceScope.launch(Dispatchers.IO)
  ↓ Coil ImageLoader.execute(request) [cached if URL seen before]
  ↓ on SuccessResult
  ↓ mainHandler.post: currentArtwork = scaled
  ↓                  updateMediaSessionArtwork(scaled)
```

Both the binary path and the URL path write to the same `currentArtwork` sink. **Last write wins.**

---

## 3. Flow at track change (same session, server advances the queue)

This is the "metadata lags the audio" case. Here's what MA's server actually does.

### 3a. MA server internals

The track-change trigger is `SendspinPlayer._on_player_media_updated()` at `providers/sendspin/player.py:782`. This fires when MA's queue controller has set a new `current_media` on the player.

```python
def _on_player_media_updated(self) -> None:
    if self.synced_to is not None:
        return                                       # Only leader sends metadata
    if self.state.current_media is None:
        metadata_role.set_metadata(Metadata())       # Clear
        return
    self.mass.create_task(self.send_current_media_metadata())
```

`send_current_media_metadata()` (player.py:795) then:

1. Looks up the queue and queue_item by id (line 802-809).
2. `await self._send_album_artwork(queue_item)` — line 813.
3. `await self._send_artist_artwork(queue_item)` — line 814.
4. Builds a **full** `Metadata` object with all fields (line 837-850), including `artwork_url=current_media.image_url`.
5. `metadata_role.set_metadata(metadata)` — line 854.

### 3b. Server-side delta suppression

`set_metadata()` in `aiosendspin/server/roles/metadata/group.py:111-114` says:

> Only sends updates for fields that have changed.

And `state.py:106`:

> Build a SessionUpdateMetadata containing only changed fields compared to last.

**This is why client logs show partial metadata on every track change.** The server always builds the *full* Metadata object, but the role's send layer emits only a delta. Within a single album, typical deltas are `{title, progress}` because artist/album/artwork_url are unchanged.

### 3c. Server-side artwork dedupe (the important part)

`_send_album_artwork(queue_item)` at player.py:721:

```python
artwork_url = None
if current_item.image is not None:
    artwork_url = self.mass.metadata.get_image_url(current_item.image)

if artwork_url != self.last_sent_artwork_url:
    # Image changed, resend the artwork
    self.last_sent_artwork_url = artwork_url
    if artwork_url is not None and current_item.media_item is not None:
        image_data = await self.mass.metadata.get_image_data_for_item(
            current_item.media_item
        )
        ...
```

**Dedupe rule:** if `get_image_url(current_item.image)` hasn't changed since the last track, the server does **not** re-send binary album art. Same for artist (`_send_artist_artwork` at player.py:749, matches same pattern on `last_sent_artist_artwork_url`).

### 3d. Server-side inconsistency: URL vs binary source

Look carefully at player.py:

- **Line 730** (dedupe check): `get_image_url(current_item.image)` — queue-item-level image.
- **Line 736** (binary data fetch): `get_image_data_for_item(current_item.media_item)` — media-item-level image.
- **Line 842** (metadata URL sent to client): `current_media.image_url` — current-media-level image.

These three are resolved from **three different source objects** and are not guaranteed to agree. In typical library playback they converge, but in some queue contexts (radio, file-on-remote-provider, dynamic playlists) they can point to different images. This is the most plausible explanation for the observed "app UI shows correct album cover, widget shows unrelated playlist/context image" case: the binary image_data path picked up a queue-item image (e.g. the playlist's cover) while the `artwork_url` in metadata resolved to the actual track's album cover.

Worth confirming by capturing the raw MA player state during the bug repro; the fix may be on MA's side (unify the three resolution paths) or on our side (prefer URL).

### 3e. Track-change timeline on the wire

Ordering based on log observation (10:36:45.880 to 10:36:46.170 in `b51oxqsjr.output`):

```
t=0:   MA queue advances to new track (internal)
t+?:   _on_player_media_updated() fires
t+?:   send_current_media_metadata() scheduled as task
t+?:   _send_album_artwork() runs — NO binary send if URL unchanged
t+?:   _send_artist_artwork() runs — NO binary send if URL unchanged
t+?:   set_metadata() builds delta and sends over the wire as server/state
t+?:   stream/start sent over the wire (codec config)
t+?:   binary audio chunks start arriving

Observed client-side inter-message gap:
  server/state (new title, partial) → stream/start = ~290 ms
```

Critically, **the audio transition is driven by a separate mechanism** (the queue player's corrected_elapsed_time, buffer consumption, etc.) that can be offset from when `_on_player_media_updated` fires. Audio can already be playing the new track by the time the server emits the new-title metadata — this is the reported "metadata lags the audio" UX.

---

## 4. Why the lock-screen widget stays stale while the app UI is fresh

Two separate data sources feed the two surfaces:

| Surface | Data source | Result |
|---|---|---|
| App UI (mini-player, now-playing screen) | Observes `_playbackState.artworkUrl` via Compose, fetches via Coil | Always the current URL's image. Because `artworkUrl` is preserved across partial metadata updates and the URL is track-accurate, the app UI displays correct art. |
| Lock screen / notification / Android Auto | Reads the Bitmap from MediaSession via `mediaMetadata.artworkData` | Shows whatever bitmap we last set via `forwardingPlayer.updateMetadata(artwork=...)`. That bitmap is `currentArtwork`, which is the most recent arrival from either binary-artwork OR URL-fetch — whichever wrote last. |

If the URL fetch happens once (at connect, when `lastArtworkUrl` changes from null to the first URL) and then never re-runs because subsequent tracks share the same URL (same album), and the binary artwork also doesn't re-send (MA's dedupe), then `currentArtwork` never updates. If the **first** binary image MA sent was actually a playlist-context image (the server-side inconsistency above), that wrong image persists as the lock-screen art for the whole same-album sequence.

---

## 5. Client-side metadata data sources (with latency characteristics)

| Source | Freshness trigger | Latency on track change | Completeness |
|---|---|---|---|
| SendSpin `server/state` metadata | Delta from MA when `_on_player_media_updated` fires | Whatever gap MA has between queue advance and that callback. Observed sub-second but not reliably instantaneous. | Partial (delta). Client merges via `withMetadata`. |
| MA `player_queues/queue_items` | Fetched once on connect via `MaCommandClient.kt`. Can be re-fetched on demand. | Zero marginal latency if locally cached AND we know the current index. Any network fetch adds round-trip. | Full (all track fields per item). |
| MA event stream (command WebSocket) | Pushed by MA, separate from SendSpin WebSocket | Unknown — needs investigation. Likely fires at or before `_on_player_media_updated`. | Depends on event type. |
| Binary audio chunk header | Arrives with audio | Real-time with audio. | Only timestamp, no track identity. |

Two plausible avenues to reduce the UX lag, given these sources:

1. **Subscribe to MA's command-channel events** that fire at queue advance, and use those to kick a metadata refresh in parallel with the SendSpin `server/state` arrival. Likely earliest signal.
2. **On any queue-index change (from any source), immediately derive metadata from the local queue cache.** No server round-trip. Instant UI update. Needs the local queue to be kept fresh across track changes, which the app already does via `populatePlayerQueue()`.

---

## 6. Observed client-side bugs (orthogonal to server latency)

These are actual client-code issues surfaced during this session's investigation. Each is a candidate for a small follow-up PR independent of the larger flow redesign.

### B1: `onArtwork` drops the `channel` parameter

`SendSpinClient.kt:444`:

```kotlin
override fun onArtwork(channel: Int, payload: ByteArray) {
    if (payload.isEmpty()) {
        callback.onArtworkCleared()
    } else {
        callback.onArtwork(payload)   // channel discarded
    }
}
```

Our `client/hello` requests only channel 0 (`source=album`), so in current deployments this is dormant. But if the client ever requests multiple channels (e.g. to show artist art on a secondary UI element), or if a server mis-sends on a non-configured channel, we would blindly overwrite the album bitmap with whatever arrived. Fix: propagate channel and route album vs non-album to different sinks.

### B2: Last-writer-wins between binary and URL artwork

Both paths target `currentArtwork`. No priority logic. When the server's binary image is stale or inconsistent with the URL (see §3d), whichever arrived last wins. In practice the binary path usually wins because URL fetch takes longer.

Fix candidates:
- Maintain `urlArtwork: Bitmap?` and `binaryArtwork: Bitmap?` as separate fields; prefer URL when available. *This formalizes URL as the authoritative source.*
- On title change, unconditionally re-fetch the URL even if URL is unchanged (cheap via Coil cache, overrides stale binary).

### B3: `MainActivity: Metadata update: <title> /  / ` log is misleading

The log emits incoming parameters (which are empty-string for unchanged fields) rather than the resulting `_playbackState` values. Harmless UX but noisy when reading logs. Minor.

### B4: Encrypted prefs fallback silently loses auth tokens

Not metadata/artwork, but found while reading other parts of the log: when `EncryptedSharedPreferences` decryption fails (seen after device keystore reset), `UserSettings.initialize` falls back to plain prefs with only a `W` log line. Saved MA credentials on the device are effectively lost without UI notification. Future-work item.

---

## 7. Open questions — now with answers

Original questions retained for traceability. Research findings follow each.

### Q1. Does MA's command-channel emit a track-advance event before `server/state`?

**Yes.** MA publishes `QUEUE_UPDATED` on its command-channel WebSocket, and the event fires substantially earlier than the SendSpin `server/state` broadcast.

Timeline on track advance, cross-referenced to MA source:

```
T+0ms     Player detects current_media changed
          — models/player.py:1232 update_state()
T+0ms     PLAYER_UPDATED fires on command channel
          — controllers/players/controller.py:1770
T+500ms   on_player_update fires, _update_queue_from_player detects current_item_id change
          — controllers/player_queues.py:1134
T+500ms   QUEUE_UPDATED fires on command channel (full PlayerQueue payload)
          — controllers/player_queues.py:1790
T+1000ms  _on_player_media_updated invoked (1-second debounce)
          — providers/sendspin/player.py:1232
T+1000ms+ send_current_media_metadata() scheduled; binary artwork fetched async; server/state broadcast
```

`QUEUE_UPDATED` payload includes the full new `PlayerQueue` object with `current_item.media_item` populated (title, artist, album, image as `MediaItemImage`). No follow-up fetch required. Forwarded to command-channel clients immediately via `controllers/webserver/websocket_client.py:468`.

**Client infrastructure to consume this already exists but is never wired up:**

- `MaApiTransport.EventListener` interface: `MaApiTransport.kt:170-175`
- Multiplexer dispatches to it: `MaCommandMultiplexer.kt:186` (`eventListener?.onEvent(json)`)
- But `MaCommandMultiplexer.eventListener: MaApiTransport.EventListener? = null` (line 61) is never set anywhere in the app. No `setEventListener(...)` call.

**Verdict — Q1 is directly actionable.** Registering an `EventListener` in `MusicAssistantManager` that filters for `type == "queue_updated"`, extracts `data.current_item`, and pushes to `_playbackState` gets the client a ~1 second lead on the SendSpin `server/state` broadcast for every track change. That closes the "metadata lags audio" complaint without any server-side change.

### Q2. Can we compute the current queue-item id from audio-chunk timestamps?

Not researched in depth this session. Deferred — Q1's fix (subscribing to `queue_updated`) renders Q2 unnecessary for normal flows. Keep as fallback for pure-SendSpin deployments without MA command-channel access.

### Q3. On cross-album track changes, is binary artwork ordered vs `server/state`?

**Binary always arrives first, on the same sequential task.** No race, no window where old binary + new URL coexist.

From `providers/sendspin/player.py:795-854`, `send_current_media_metadata()` is one coroutine:

```
await _send_album_artwork(queue_item)        # includes fetch + encode + binary send
await _send_artist_artwork(queue_item)        # same shape
metadata_role.set_metadata(metadata)          # synchronous, fires LAST
```

The `set_metadata` call is not awaited — it's a plain method that dispatches the `server/state` over the WebSocket. It is guaranteed to execute after both artwork sends resolve.

Edge case: if `_on_player_media_updated` fires twice within the 1-second debounce window on separate tasks, the two `send_current_media_metadata` coroutines could interleave during the `to_thread(Image.open, ...)` await, producing out-of-order frames. Low probability; not the primary bug.

### Q4. What resolves `current_item.image` vs `current_media.image_url` to different sources?

**Three different image fields are read by three different call sites in `providers/sendspin/player.py`:**

| Path | Call site | Resolution | Used for |
|---|---|---|---|
| A | `player.py:730` — `get_image_url(current_item.image)` | Queue item's snapshot `.image` field (set once at `QueueItem.from_media_item`, never updated). Walks `Track.image` property which prefers `album.image` via `ItemMapping`. | Dedupe check: should we re-send binary? |
| B | `player.py:736` — `get_image_data_for_item(current_item.media_item)` | Calls `get_image_url_for_item(media_item)` which iterates `media_item.metadata.images` for THUMB, falls back to `album`, then `artist`. `_prepare_next_item` (`controllers/player_queues.py:1594-1602`) can prepend `album.image` to this list, potentially making it diverge from `queue_item.image`. | Fetch binary JPEG bytes for channel 0. |
| C | `player.py:842` — `current_media.image_url` | `PlayerMedia.image_url`, set from `queue_item.image` in `_create_player_media` (`controllers/player_queues.py:1828-1854`, line 1851). Same underlying `queue_item.image` as Path A, at a different resolution. | `artwork_url` field in `server/state` metadata. |

**Important: the direction of divergence depends on the track's data shape at queue-construction time vs after `_prepare_next_item`.**

For the specific bug the user reported — "app UI shows correct Chicago album cover from URL, lock-screen widget shows unrelated pixel-character image from binary" — the likely resolution was:

- `queue_item.image` (Paths A, C) → `Track.image` property → `album.image` of the `ItemMapping` → **correct Chicago album cover URL** (because at enqueue the library track's album was present).
- `media_item.metadata.images[0]` (Path B) → THUMB from the provider-returned metadata, which for tracks sourced through a playlist often contains the **playlist context image** rather than the album cover. Binary send fetches this image's bytes → wrong.

In other deployments the opposite direction is possible: `queue_item.image` snapshotted something stale while `_prepare_next_item` enriched `media_item.metadata.images` with the correct album cover. Either way, **the two paths can disagree**, and which is "correct" varies per track.

Canonical fix upstream would unify all three call sites to a single resolver. The most semantically correct candidate is `get_image_url_for_item(media_item)` (the one Path B uses), applied consistently to the dedupe URL and the `artwork_url` metadata field — but only after verifying that path produces the track album cover reliably for non-library / non-enriched tracks (see the "other direction" caveat above).

### Q5. Does the SendSpin spec require `artwork_url` == binary channel 0 content?

**No. The spec is silent; the two streams are architecturally independent.**

From `aiosendspin/server/roles/artwork/v1.py:37-38`:

> "Unlike player, artwork streams are independent of playback — they start on connect and don't clear on pause/stop."

("Independent" describes artwork-vs-audio, not artwork-vs-metadata, but the architectural pattern is the same: separate role families, separate group roles, no shared state.)

- Metadata role: owns `artwork_url` as an opaque string. No validation. `/tmp/aiosendspin/aiosendspin/models/metadata.py`.
- Artwork role: owns binary channels. Images passed directly as `PIL.Image`, encoded and sent. Never consults `artwork_url`. `/tmp/aiosendspin/aiosendspin/server/roles/artwork/group.py`.
- Conformance suite (`conformance/src/conformance/scenarios.py`): tests `server-initiated-metadata` and `server-initiated-artwork` as **separate scenarios with unrelated fixture data**. No scenario asserts cross-channel consistency.

**Verdict — spec-silent, MA behavior is an inadvertent app-level bug.** The protocol permits the divergence; no reasonable client expects it.

**Responsibility split:**

- **Upstream (MA):** real fix is to unify the three image resolution paths — see Q4. Report as a bug upstream.
- **Client-side workaround:** since there's no protocol contract to rely on, the client should formalize its preference. The two research agents disagreed on which path is "usually right" — one said binary, one said URL — consistent with the observation that the divergence direction varies per track. **Practically we have user evidence that in the specific observed case URL was correct and binary was wrong, so "prefer URL when both available" is the pragmatic client default.** That's fix candidate B2 from §6.
- **Spec clarification:** worth adding a non-normative SHOULD statement that servers "ensure `artwork_url` in `server/state` and the binary payload on the album artwork channel refer to the same image for the same track." Prevents other server implementations from drifting into the same shape.

---

## 8. Implications for a client-side fix — informed by the research above

Two client-side fixes are now clearly scoped. Both are small; they address different complaints.

### 8a. Fix for "metadata lags audio" — wire up the MA command-channel event listener

Single change: register a `MaApiTransport.EventListener` via `MaCommandMultiplexer.eventListener = ...` inside `MusicAssistantManager` initialization. The listener filters incoming events for `type == "queue_updated"`, extracts `data.current_item.media_item` (title, artist, album, image), and pushes a synthesized metadata update into `_playbackState` via a new helper on `PlaybackService`.

This beats the SendSpin `server/state` broadcast by roughly 1 second per Q1's timing breakdown. No new network calls, no new permissions. The infrastructure (interface, multiplexer dispatch) already exists and is simply unused.

Risk: double-updates if the later `server/state` broadcast arrives and re-applies stale or conflicting data. Mitigation: `withMetadata`'s preserve-on-null semantics already handle this gracefully — both sources produce the same end state.

### 8b. Fix for "widget shows wrong artwork" — prefer URL over binary

Per Q5, the protocol permits the divergence, so client policy is our call. The user's specific observation (URL correct, binary wrong) plus the Q4 note that the divergence direction can vary per track means the client should pick a default, apply it consistently, and move on.

```kotlin
@Volatile private var urlArtwork: Bitmap? = null
@Volatile private var binaryArtwork: Bitmap? = null

private val effectiveArtwork: Bitmap? get() = urlArtwork ?: binaryArtwork

// In fetchArtwork success path: urlArtwork = scaled; updateMediaSessionArtwork(effectiveArtwork)
// In onArtwork: binaryArtwork = scaled; updateMediaSessionArtwork(effectiveArtwork)
// In onArtworkCleared: binaryArtwork = null; updateMediaSessionArtwork(effectiveArtwork)
// On track change (title differs): urlArtwork = null (force re-fetch via fetchArtwork)
```

URL is preferred when available because the app UI already uses URL and is observably correct. Binary stays as a bridge for the pre-URL-fetch window (solves the "Android Auto grey box" concern). On track change we invalidate `urlArtwork` so the fetch re-runs even if the URL string is unchanged — Coil caches the bytes so it's near-free.

### 8c. Upstream fix (MA) — not ours, but worth filing

MA server should unify the three image-resolution paths in `providers/sendspin/player.py` so the dedupe URL, the binary byte source, and the `artwork_url` field all come from one canonical helper. See Q4 for details. Filing an issue against `music-assistant/server` with the Q4 analysis inlined would be appropriate.

The client-side fix in 8b renders us resilient regardless of whether or when MA fixes this. Landing 8b first + filing upstream is the right order.

Additional notes on 8b:
- Preserves the "grey box on Android Auto" concern: when URL isn't fetched yet but binary is, we still show binary.
- Eliminates the last-writer race.
- Gives URL structural priority once available.
- Pairs well with B1 if we ever expand beyond channel 0.
- The per-track-change "invalidate `urlArtwork`" step is what fixes the same-album-but-stale case: Coil's cache returns instantly if the URL repeats, so no extra network cost.

---

## 9. Reference map

- Client hello build (artwork channel request): `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/protocol/message/MessageBuilder.kt:63-73`
- Client binary artwork parse: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/protocol/message/BinaryMessageParser.kt:121-138`
- Client artwork callbacks: `android/app/src/main/java/com/sendspindroid/sendspin/SendSpinClient.kt:444-450`
- Client metadata handler: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt:1137-1200`
- Client URL fetch: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt:1478-1509`
- Client MediaSession update: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt:1563-1583`
- Client `withMetadata` semantics: `android/shared/src/commonMain/kotlin/com/sendspindroid/model/PlaybackState.kt:45-81`
- Client queue fetch + id resolution: `android/shared/src/commonMain/kotlin/com/sendspindroid/musicassistant/MaCommandClient.kt:171-194`

- MA SendSpin provider player lifecycle: `music_assistant/providers/sendspin/player.py`
  - `_on_player_media_updated`: line 782
  - `send_current_media_metadata`: line 795
  - `_send_album_artwork` (dedupe): line 721
  - `_send_artist_artwork` (dedupe): line 749
- aiosendspin metadata role (delta semantics): `aiosendspin/server/roles/metadata/group.py:111-114`, `aiosendspin/server/roles/metadata/state.py:106-138`
- aiosendspin artwork role (channel config): `aiosendspin/server/roles/artwork/v1.py:82-115`, `aiosendspin/server/roles/artwork/group.py:45-92`
