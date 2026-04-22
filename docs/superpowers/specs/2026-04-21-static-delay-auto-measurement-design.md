# Design: auto-measurement of `static_delay_ms`

**Date:** 2026-04-21
**Status:** Approved (design); implementation pending
**Audit IDs addressed:** H-2 (primary), L-3 (folded in)

## Background

SendSpinDroid is the Android client for SendSpin, a synchronized multi-room audio protocol. The server schedules audio for a specific server-time moment, and each client must compensate for its own hardware output latency (the delay from `AudioTrack.write()` to sound leaving the speaker) or different rooms drift out of sync.

The compensation value, `static_delay_ms`, is reported to the server in each `client/state` message. Today it is initialized to `0` and is only populated by:

- a user-facing **settings slider** ("Sync Offset", integer ms, range −5000..+5000, default 0), or
- an explicit **`client/sync_offset`** message pushed from the server.

There is no automatic measurement of the device's actual output latency. On every fresh install this means multi-room sync requires manual per-device calibration via the slider. Audit finding **H-2** flagged this as a high-severity feature gap: the core product promise (synchronized playback) depends on a value that the product does not populate.

## Goals

1. Auto-measure output latency on startup so `static_delay_ms` reflects the device's real hardware pipeline without user intervention.
2. Keep the existing user slider intact as an additive correction on top of the auto-measured value.
3. Report the auto-measured value to the server **before** the first audible chunk plays, so sync is correct from the first note.
4. Be observable in the existing stats surface so you can verify in the field without shipping new UI.

Non-goals:

- Replacing the existing slider or changing its UX.
- Continuously adapting the value during playback.
- Building an automated multi-device sync test (requires real hardware; validation is manual).
- Migrating to AAudio/Oboe (tracked separately as the parked H-1 code-side work).

## Design decisions

Five design questions were resolved during brainstorming:

| # | Question | Decision |
|---|----------|----------|
| 1 | What happens to the existing user slider? | **Two separate values, added together.** Slider UI unchanged; new "auto-measured" value reported as a stats-visible sibling. |
| 2 | When is the measurement triggered? | **On `AudioTrack` recreate** (new sample rate / channels / bit depth). One measurement session per AudioTrack lifetime; reused across stream boundaries when format matches. |
| 3 | How/when is the updated value sent to the server? | **During pre-playback (`WAITING_FOR_START`)**, with `client/state` pushed to the server before the first audible chunk plays. |
| 4 | Convergence algorithm? | **Fixed-window arithmetic mean of 20 samples** (~400 ms at 48 kHz / 20 ms chunks). Simple, predictable duration. |
| 5 | Testability? | **Unit tests** for the estimator, **stats surface** for field observability, **logcat** session log. No automated multi-device test. |

Fallback: if 20 samples are not collected within a 2-second timeout (e.g., `getTimestamp()` failing consistently), auto-measured delay remains `0` for this AudioTrack lifetime. Playback proceeds with `staticDelayMicros = userSyncOffsetMicros + 0`. The existing sync-error correction (sample insert/drop) handles drift. The timeout reason is logged.

## Architecture

### Data model

`SendspinTimeFilter.staticDelayMicros` is decomposed into two independent fields plus a computed sum:

| Field | Writer | Meaning | Persisted? |
|-------|--------|---------|------------|
| `autoMeasuredDelayMicros: AtomicLong` | `OutputLatencyEstimator` (measurement subsystem) | Best-effort estimate of device hardware output latency | No (per-AudioTrack lifetime) |
| `userSyncOffsetMicros: AtomicLong` | User settings slider (existing path) | User correction, applied on top | Yes (SharedPrefs `sync_offset_ms`) |

The existing public API `staticDelayMs: Double` is preserved as a computed getter returning `(autoMeasuredDelayMicros + userSyncOffsetMicros) / 1000.0`. All existing callers (the two `serverToClient`/`clientToServer` math sites and the `client/state` reporter in `SendSpinProtocolHandler.sendPlayerStateUpdate`) continue to read the sum, unchanged.

A new `staticDelaySource` enum (`NONE`, `AUTO`, `USER`, `SERVER`) is tracked purely for the stats surface. `AUTO` means auto-measurement converged successfully; `USER` means only the slider contributed; `SERVER` means `client/sync_offset` was received; `NONE` means all three are zero.

L-3 fix (folded in): the existing `@Volatile private var offset: Double` inside the Kalman filter path is replaced with `AtomicLong` storage (bit-cast via `Double.toRawBits`/`Double.fromBits`) so that 32-bit JVM torn-read risk is eliminated. The Kalman covariance fields (`p00`, `p11`, `p01`, etc.) are already updated together and will be consolidated under a single `ReentrantLock`-guarded section to preserve their pairwise consistency.

### Components

- **`OutputLatencyEstimator`** (new, package `com.sendspindroid.sendspin.latency`). Ring-buffered sampling, convergence detection, timeout. Completely decoupled from `AudioTrack`: takes `(framesWritten, writeTimeNs)` pairs in and `(framePosition, dacTimeNs)` pairs in, emits a single converged `latencyMicros: Long` value or a timeout result. Unit-testable without any Android dependency.
- **`SendspinTimeFilter`** (modified). Splits `staticDelayMicros` as described above. Exposes `setAutoMeasuredDelayMicros(us: Long, source: StaticDelaySource)` for the estimator to call. The existing `staticDelayMs` setter (used by the user slider and `client/sync_offset` paths) is redirected to write `userSyncOffsetMicros`.
- **`SyncAudioPlayer`** (modified). Constructs an `OutputLatencyEstimator` when it creates a new `AudioTrack`. Feeds the estimator `recordWrite(framesWritten, writeTimeNs)` on each `AudioTrack.write()`, and `recordDacTimestamp(framePosition, dacTimeNs)` on each successful `AudioTrack.getTimestamp()`. When the estimator emits a result (converged or timeout), calls back into `SendspinTimeFilter.setAutoMeasuredDelayMicros(...)` and then triggers a `client/state` push via a new `SendSpinClient` wrapper.
- **`SendSpinProtocolHandler`** (modified). Exposes `fun sendClientStateSnapshot()` as a public wrapper around the existing protected `sendPlayerStateUpdate()` so `SyncAudioPlayer` (via `SendSpinClient`) can request a state push when the measurement completes.
- **`PlaybackService`** (modified). Stats bundle gains `auto_measured_delay_ms`, `user_sync_offset_ms`, and `static_delay_source` (string). The existing `static_delay_ms` key continues to report the sum.
- **`UserSettings.kt`** — no change. The slider's existing write path continues to flow through `updateSyncOffset` → `timeFilter.staticDelayMs = ...`, which now lands in `userSyncOffsetMicros` after the split.

### Data flow

Timeline for a fresh service lifetime:

1. User connects to a server.
2. WebSocket handshake completes → `sendPlayerStateUpdate()` fires with `static_delay_ms = 0` (no measurement has run yet). Expected on first connect.
3. Server sends `stream/start` → `PlaybackService.onStreamStart` creates a new `SyncAudioPlayer` (or reuses one if format matches). On reuse, steps 4–8 are **skipped entirely** and the gate described in step 9 treats the measurement clause as already satisfied from the previous session; the previously-measured `autoMeasuredDelayMicros` is still correct for this AudioTrack.
4. `SyncAudioPlayer` constructs a new `AudioTrack` and a new `OutputLatencyEstimator`.
5. `SyncAudioPlayer` enters `WAITING_FOR_START`; starts writing silence to the `AudioTrack`. Each write calls `estimator.recordWrite(framesWritten, writeTimeNs)`.
6. `SyncAudioPlayer` periodically polls `AudioTrack.getTimestamp()` (already in place for sync correction). Each successful poll calls `estimator.recordDacTimestamp(framePosition, dacTimeNs)`. The estimator looks up `writeTimeNs` for the given `framePosition` in its ring buffer, computes `latency = dacTimeNs - writeTimeNs`, and accumulates.
7. After 20 accepted samples, the estimator computes the arithmetic mean and calls its callback with a `Converged(latencyMicros)` result.
8. Callback: `SendspinTimeFilter.setAutoMeasuredDelayMicros(latencyMicros, StaticDelaySource.AUTO)` and `sendSpinClient.sendClientStateSnapshot()`. Server now has the correct offset.
9. The DAC-aware start-gating logic in `SyncAudioPlayer` already holds `WAITING_FOR_START → PLAYING` until the DAC position reaches a scheduled start time. This spec adds a second condition to that gate: the measurement session must be **complete** (converged *or* timed out). When both conditions hold, `SyncAudioPlayer` transitions to `PLAYING`.
10. First audible chunk plays with the server having already received the auto-measured value.

### `WAITING_FOR_START → PLAYING` gate

The transition gate becomes a conjunction:

```
canTransition = dacReachedScheduledStart AND measurementSessionComplete
```

Where `measurementSessionComplete` is true if either:

- `OutputLatencyEstimator.status == Converged`, or
- `OutputLatencyEstimator.status == TimedOut` (2 s elapsed without 20 samples).

**Happy case** (~99% of real usage): measurement completes in ~400 ms, which is shorter than the natural WAITING_FOR_START duration driven by server-scheduled start time. The gate's measurement clause is already satisfied by the time the DAC reaches the scheduled start, so no extra silence is introduced.

**Worst case**: measurement times out (2 s) before the DAC clause is satisfied, or the DAC clause becomes satisfied before measurement converges. In the latter case we hold the transition for up to the 2 s timeout — up to ~1.6 s of extra silence. This only happens on the first connect per AudioTrack lifetime (once per service start, plus any sample-rate/channel change), and only in the specific case where the server-scheduled start time is unusually early. Acceptable tradeoff for clean first-note sync.

**Why not just always wait for the 2 s timeout?** Because on ~99% of devices we converge far sooner and there's no reason to pad the startup with silence we don't need.

### Fallback on timeout

If the 2 s timeout elapses without 20 samples:

- Callback: `SendspinTimeFilter.setAutoMeasuredDelayMicros(0, StaticDelaySource.NONE)` (if no user slider) or `source = USER` (if the slider is non-zero).
- The measurement-session-complete clause of the transition gate is now true (status = TimedOut), so `WAITING_FOR_START → PLAYING` transitions as soon as the DAC clause is also satisfied.
- Logcat records the reason and number of samples collected.

Server-pushed `client/sync_offset`: still routed to `userSyncOffsetMicros` (replaces the current behavior, since the server message is semantically a "correction" like the user slider), with `source = SERVER`. No conflict with auto-measurement because they write different fields.

### Sample rejection

Cheap safety checks the estimator applies before accepting a sample:

- Drop if the lookup of `framePosition` in the ring buffer fails (sample arrived for a frame written before the earliest recorded write).
- Drop if computed latency is negative (measurement error; impossible physically).
- Drop if computed latency exceeds 1000 ms (pathological: probably Bluetooth routing or a device in a bad state; don't poison the mean).
- Drop if `getTimestamp()` returned failure (already handled in `SyncAudioPlayer`; no sample emitted).

Dropped samples do not count toward the 20-sample window. If sustained rejection prevents convergence, the 2 s timeout catches it and falls back to `0`.

### Ring buffer

Capacity: 64 entries. At 48 kHz with 20 ms chunks, 64 entries is ~1.3 s of write history. Larger than the 400 ms sampling window gives headroom for the DAC-write lag on devices with slow hardware pipelines.

Entry format: `(framesWritten: Long, writeTimeNs: Long)`. Insert on each write; lookup is a linear scan (64 entries, negligible cost). If the ring buffer wraps before a sample resolves, the lookup fails and the sample is dropped (counts under the rejection path above).

## Error handling

| Failure mode | Handling |
|--------------|----------|
| `getTimestamp()` returns failure indefinitely | Estimator hits 2 s timeout. Source = `NONE` or `USER`. Logged. |
| Fewer than 20 samples within 2 s | Same as above. |
| `AudioTrack.write()` ordering inversion (unlikely but possible under severe scheduling pressure) | Negative latency rejected; dropped sample. |
| AudioTrack released mid-measurement | Estimator cancelled; state resets on next AudioTrack creation. |
| App killed mid-measurement | No persistence; next service lifetime starts fresh. |
| Multiple concurrent measurement sessions | Cannot happen; `SyncAudioPlayer` owns one estimator per AudioTrack, and AudioTrack creation is serialized. |

## Testing

Unit tests (`OutputLatencyEstimatorTest`, new):

- Converges to the mean on clean input.
- Handles `getTimestamp` returning failures between successes without affecting the sample window.
- Drops out-of-range latencies (< 0 ms and > 1000 ms).
- Drops samples where `framePosition` is before the ring-buffer start.
- Hits the 2 s timeout when only 10 samples arrive; reports timeout with sample count.
- Ring-buffer wrap-around correctness (65 writes, query for frame 32 still resolves).
- Cancel-before-convergence leaves no lingering callbacks.
- Gate logic: measurement converges before DAC-scheduled start → no extra silence; DAC-scheduled start arrives before measurement converges → silence held until measurement completes or times out; measurement already complete (reused AudioTrack) → gate passes immediately.

Regression tests (`SendspinTimeFilterTest`):

- `staticDelayMs` getter returns `autoMeasured + userSyncOffset` sum.
- Writes to the user slider path do not clobber auto-measured value and vice versa.
- L-3 torn-read fix: concurrent reader/writer stress on `offset` + `staticDelayMicros` produces no observable tearing.

Observability:

- Stats bundle (already surfaced in the existing stats sheet) gains `auto_measured_delay_ms`, `user_sync_offset_ms`, `static_delay_source`.
- Logcat: new tag `[delay-cal]`. Logs session start (AudioTrack config), each accepted sample value, convergence or timeout, final value written.

Out of scope:

- Automated end-to-end multi-device sync test (requires real hardware).
- On-device integration test of the full measurement loop.

## Rollout

Single PR against `main`. Branch: `task/static-delay-auto-measurement`. The behavior change is backwards-compatible: devices that never converge (or where measurement is disabled by a hypothetical future flag) fall back to exactly the current behavior (`staticDelayMicros = userSyncOffsetMicros + 0`).

No server-side changes required. The server already accepts `client/state` messages at any time; auto-measurement just makes the reported value more accurate.

## Risks

- **Bluetooth devices**: output latency can be 200+ ms and highly variable. The 1000 ms upper-bound check should keep them in the "accept" range, but samples will be noisier. Fixed-window mean over 20 samples is adequate.
- **Device quirks**: some Android devices have buggy `getTimestamp()` implementations that return stale values or fail persistently. The estimator drops samples on failed reads; if `getTimestamp()` fails continuously the 2 s timeout catches it and `source = NONE/USER`, which is the same as the current state of the world.
- **Sample bias near pipeline start**: the first few `getTimestamp()` returns after `AudioTrack.play()` can report pre-start frame positions. The ring-buffer lookup will reject these (sample dropped, doesn't count).
