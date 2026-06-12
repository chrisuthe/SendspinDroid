# Sendspin Spec Compliance Audit - 2026-06-12

Audit of SendSpinDroid against the current Sendspin protocol spec
(github.com/Sendspin/spec @ 2026-06-11, "Add visualizer@v1 role"), plus an
evaluation of the official JVM library (github.com/Sendspin/sendspin-jvm v0.3.0).

> **Status update (same day):** All P1, P2, and P4 issues plus P3 items
> 6/7/8/10 are fixed on branch `task/spec-compliance` (one commit per issue).
> The official Sendspin/conformance harness now runs in CI
> (`.github/workflows/conformance.yml`); the `client-initiated-pcm` scenario
> passes against aiosendspin ("PCM hashes match exactly"). Remaining open
> items: P3.9 server-initiated connections (build-vs-library decision) and
> the optional visualizer/color roles.

## Recent spec changes that affect us

| Date | Change | Impact on us |
|------|--------|--------------|
| 2026-06-11 | `visualizer@v1` role finalized: binary types 16-20 (loudness, beat, f_peak, spectrum, peak); types 21-23 reserved | Optional role; we don't claim it (compliant). Opportunity for UI visualizations |
| 2026-06-10 | `pitch` visualizer type removed | n/a |
| 2026-06-01 | **`required_lead_time_ms` and `min_buffer_ms` added to `client/state` player object - "always required for players"** (#69) | **We don't send them - now non-compliant** |
| 2026-06-01 | `mac_address` added to `device_info` (optional) (#87) | Not feasible on Android 11+ (MAC randomization, `getHardwareAddress()` returns null for apps targeting API 30+). Skip |
| 2026-04-27 | Repeat/shuffle states moved into `server/state` controller object (#81) | We claim `controller@v1` but never parse controller state |
| 2026-04-27 | `color@v1` role added (#70) | Optional; not claimed (compliant). Could power UI theming |
| 2026-04-10 | Kalman time filter algorithm now required (#73) | Already compliant (`SendspinTimeFilter`) |

## Issues to fix

### P1 - Wire-format bugs

**1. `client/state`: `state` field is nested in the wrong place.**
`MessageBuilder.buildPlayerState()` (`android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/protocol/message/MessageBuilder.kt:99-112`)
sends:

```json
{"payload": {"player": {"state": "synchronized", "volume": 50, "muted": false, "static_delay_ms": 12.5}}}
```

The spec defines `state` as a **top-level payload field**, sibling to `player`:

```json
{"payload": {"state": "synchronized", "player": {"volume": 50, "muted": false, "static_delay_ms": 12}}}
```

A strict server sees no operational state from us at all (including our
`error`/`synchronized` transitions, which drive server-side awareness of sync
loss). Works today only because Music Assistant's parser is lenient.
Fix: move `state` to top level. Keeping a duplicate inside `player` during a
transition period is harmless if needed for old servers.

**2. Missing `required_lead_time_ms` and `min_buffer_ms` in `client/state` player object.**
Spec marks both "always required for players" (added 2026-06-01). Servers use
them to compute per-player send-ahead (`max(required_lead_time_ms,
min_buffer_ms) + static_delay_ms`) - especially important for live streams.
Without them, an updated server has to guess our startup warmup and jitter
buffer needs.
We already have the data to populate these honestly:
- `required_lead_time_ms`: AudioTrack warmup + decoder init + measured DAC
  latency (we measure this for `static_delay_ms` auto-compensation already).
- `min_buffer_ms`: derived from our buffer config (35 s normal / 10 s
  low-memory is the capacity; the *minimum* we need to absorb jitter is much
  smaller - start conservative, e.g. 500-1000 ms, then tune).
Spec also allows updating them at runtime (debounced) - a later refinement.

**3. Invalid `client/goodbye` reason `"network_type_changed"`.**
`SendSpin.kt:832` (`disconnectForReselection()`). Spec enum is
`another_server | shutdown | restart | user_request`. Our network-reselection
disconnect maps cleanly to `restart` (client will reconnect; server should
auto-reconnect too). `user_request` at `SendSpin.kt:857` is already valid.

**4. `static_delay_ms` type and range.**
Spec: integer, 0-5000, negative not supported. We send a double
(`MessageBuilder.kt:107`) computed as `autoMeasuredDelayMicros +
userSyncOffsetMicros` (`SendspinTimeFilter.kt:241-242`), which can go negative
with a negative user sync offset. Round to Int and clamp to 0-5000 on the wire
(keep applying the real signed value locally - the spec only constrains the
reported field).

### P2 - Role compliance

**5. `controller@v1` is claimed but only partially implemented.**
Spec: "Every client which lists the controller role ... needs to implement all
messages in this section." Gaps:
- `server/state` controller object is never parsed (no `controller` handling in
  `MessageParser.kt`): `supported_commands`, group `volume`, `muted`, `repeat`,
  `shuffle`. Repeat/shuffle state moved here from metadata on 2026-04-27 -
  if our UI shows repeat/shuffle from metadata, it's reading a removed field.
- Commands sent are limited to play/pause/next/previous/switch
  (`SendSpin.kt:867-871`); spec also defines stop, volume, mute, repeat_off,
  repeat_one, repeat_all, shuffle, unshuffle.
- We don't gate sent commands on the server's `supported_commands`.
Options: (a) implement controller state parsing + the missing commands, or
(b) stop claiming `controller@v1` if we only need transport controls via
another path. (a) is right for us - group volume/mute UI needs it.

### P3 - Missing optional features worth adopting

**6. `external_source` client state.** New tri-state
(`synchronized | error | external_source`). Natural Android mapping: when we
lose audio focus to another app (or the user plays local media), report
`external_source` instead of just going silent; the server then parks us in a
solo group and the `switch` command prioritizes rejoining our previous group.
Good UX win for a phone client, where focus loss is routine.

**7. `server/command` `set_static_delay`.** We neither declare
`supported_commands: ["set_static_delay"]` in the `client/state` player object
nor handle the command (`MessageParser.kt:122-148` parses volume/mute only).
Letting the server/UI tune device delay remotely is useful for speaker-pairing
calibration flows.

**8. `stream/request-format`.** Not implemented. It's the only way to change
audio format (e.g. drop opus -> pcm on CPU pressure) or toggle artwork channels
(`source: "none"`) without reconnecting.

**9. Server-initiated connections.** The spec's recommended method (client
advertises `_sendspin._tcp.` on port 8928, runs a WebSocket server; server
connects in). We only do client-initiated (browse `_sendspin-server._tcp`,
`NsdDiscoveryManager.kt:31`), which is permitted but second-class: it lacks the
standardized multi-server reclaim/arbitration behavior. Large work item
(embedded WS server + mDNS advertise + multi-server arbitration incl. last
played server persistence + `client/goodbye 'another_server'`). Note our
remote/proxy connection modes already fall under the spec's "custom connection
methods" allowance.

**10. Metadata progress extrapolation.** Spec gives a formula to compute live
track position from `timestamp` + `progress{track_progress, track_duration,
playback_speed}`. We report position as-is from the last `server/state`
(`MessageParser.kt:70-119`), so the seekbar/`MediaSession` position only moves
when the server pushes. Implement the formula (clamped per spec) for smooth
position in notification/Auto.

### P4 - Minor hygiene

**11. `device_info.software_version` is hardcoded `"1.0.0"`**
(`MessageBuilder.kt:43`). Should be the real `versionName` (2.0.0-Beta11).
**12. Binary messages are not rejected when no stream is active**
(`BinaryMessageParser.kt`); spec says they "should be rejected". Low risk -
the buffer state machine mostly handles it - but cheap to add.
**13. `group/update.playback_state`** accepts any string; spec enum is
`playing | stopped`. Keep lenient parsing, but don't branch on undocumented
values.
**14. `client/sync_offset`** (handled at `MessageParser.kt:183-190`) is not in
the spec - it's a Music Assistant extension. Keep, but mark it as such in code
comments. Same for the legacy `position_ms`/`duration_ms` metadata fallback.

## JVM library evaluation: Sendspin/sendspin-jvm v0.3.0

Kotlin/JVM, Apache-2.0 (GitHub shows "Other" only because the LICENSE file
appends the notice block - it is plain Apache 2.0, MIT-app compatible).
Single module `sendspin-protocol`, ~2,000 lines main source, OkHttp 4.12 +
Java-WebSocket + Moshi + coroutines. No Android dependencies; platform seams
(`AudioPlayer`, `NsdBrowser`, `NsdRegistrar`) are interfaces clearly designed
for an Android consumer (comments reference an `AndroidNsdBrowser`; default
client name is "SendSpin TV" - it was factored out of a TV app).

### What it covers that we lack

- Server-initiated connection mode: embedded WS server on 8928 +
  `_sendspin._tcp.` advertising + full spec multi-server arbitration
  (playback > discovery, last-played persistence, goodbye `another_server`).
- Correct `client/state` shape including `required_lead_time_ms` /
  `min_buffer_ms` / `set_static_delay`.
- Full controller role (incl. repeat/shuffle merge logic for old vs new
  servers), color role, visualizer role (typed frames).
- Clock-sync refinements: burst-then-best (only lowest-RTT sample per burst
  enters the filter), adaptive forgetting, drift gated on SNR, and live
  re-scheduling of every buffered chunk when the clock estimate moves.
- Real test suite (~1,900 lines, ~100 cases) + CI run against the official
  Sendspin/conformance harness.

### What it does NOT cover (we'd keep our code)

- No codec decoders - FLAC/Opus decode stays ours (MediaCodec).
- No audio output - `AudioPlayer` is an interface; `SyncAudioPlayer`
  (AudioTrack, DAC-gated start, insert/drop correction) stays ours entirely.
  Its `PcmDriftCorrector` is a passive utility capped at +/-0.2% by default
  (vs our +/-2%) and is never invoked by the library itself.
- No `stream/request-format`, no `external_source`, `client/state.state`
  hardcoded to "synchronized" (no error reporting).
- None of our app-level machinery: proxy/WebRTC remote modes, LOCAL->PROXY
  fallback, stall watchdog, adaptive time-sync bursts, low-memory mode,
  freeze/thaw of the time filter across reconnects.

### Pros of switching

1. Spec compliance maintained upstream: the P1/P2 fixes above (state shape,
   timing fields, controller role) are already correct there, and conformance
   CI catches regressions as the spec moves.
2. Server-initiated connections + multi-server arbitration for free - our
   biggest missing feature, and the spec-recommended connection method.
3. New roles (color, visualizer) arrive as typed APIs without us writing
   parsers.
4. Shared maintenance with other official SDKs; documented workarounds for
   known aiosendspin/MA server quirks (seek handoff, stale post-seek chunks)
   that we otherwise discover the hard way.
5. Same stack we already use (OkHttp, coroutines, Kotlin); clean StateFlow
   surface that maps well onto our service architecture.

### Cons of switching

1. v0.3.0, days old, effectively one squashed commit; data-class APIs already
   churned once. Expect breaking changes for months.
2. It replaces exactly the layer of ours that is most battle-tested (protocol
   handler, time filter, buffer state machine) while leaving the hard parts
   (AudioTrack sync engine, decoders) untouched - high migration risk, low
   code-deletion payoff in the short term.
3. Feature regressions for us today: no `client/state` error reporting (we
   rely on error -> mute -> rebuffer behavior per spec), no
   `stream/request-format`, no `external_source`.
4. Our connection coordinator (local/proxy/WebRTC, fallback, watchdog) would
   need rework to wrap the library's connection model; the library assumes
   it owns the WebSocket lifecycle.
5. Library scopes are never torn down (no `close()`); fine as a singleton,
   but a papercut for our reconnect-heavy lifecycle.
6. Slight spec drift inside the library itself: it still implements the
   removed `pitch` visualizer type (16-21 instead of 16-20).
7. Packaging papercut: depends on `org.json` (needs `exclude(group="org.json")`
   to avoid duplicate classes on Android).

### Recommendation

Do not switch now. Fix P1/P2 in our code (small, well-understood diffs), and
revisit the library at ~v1.0 / once the spec leaves "public preview". Two
cheaper ways to get its benefits meanwhile:
- Run the official Sendspin/conformance harness against SendSpinDroid in CI
  (the harness is implementation-agnostic; the JVM repo shows the pattern).
- Port specific ideas: burst-then-best time-sync sampling and live buffer
  re-scheduling on clock updates are both directly applicable to
  `TimeSyncManager`/`SyncAudioPlayer`.

If/when server-initiated connections become a requirement (e.g. MA stops
polling `_sendspin-server._tcp` clients), reconsider: that subsystem is the
strongest single argument for adopting the library rather than building it.

## Suggested fix order

1. P1.1 client/state shape (top-level `state`) - small, test against MA
2. P1.2 add `required_lead_time_ms` + `min_buffer_ms` - small
3. P1.3 goodbye reason `restart` - one-liner
4. P1.4 static_delay_ms int + clamp - one-liner
5. P4.11 real software_version - one-liner
6. P2.5 controller state parsing + missing commands - medium
7. P3.10 metadata progress extrapolation - small/medium, visible UX win
8. P3.6 external_source on audio-focus loss - medium, nice UX win
9. P3.7 set_static_delay command - small
10. P3.8 stream/request-format - medium
11. P3.9 server-initiated connections - large, decide build-vs-library first
