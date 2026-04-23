# SendSpinDroid Technical Backlog

Items we've identified as worth doing but deliberately deferred. Not a roadmap
commitment — a register so we don't lose them.

## Architecture

### Split SyncAudioPlayer into focused units

**Size:** Large (week+ of work, not a side-quest).

**Current state:** `SyncAudioPlayer.kt` is 3126 lines doing six jobs: the
state machine, AudioTrack I/O, sync correction, latency-measurement wiring,
pre-calibration/keep-alive silence, and stats logging. New gates added to the
state machine have non-obvious interactions with existing gates (the
tick-starvation bug from PR #153 is an example).

**Target decomposition (sketch):**
- A pure, testable `PlaybackStateMachine` — states, transitions, guards, no
  I/O or timing.
- An `AudioSink` layer (already introduced by the integration-harness work) —
  AudioTrack adapter.
- A `SyncCorrectionPolicy` — sample insert/drop rate decisions given sync
  error.
- A `LatencyCalibration` subsystem — owns the pre-cal silence pump, DAC
  stability tracking, and `OutputLatencyEstimator` wiring.
- An orchestrator that wires these together (`SyncAudioPlayer` becomes thin).

**Prerequisites:**
- The integration harness (see `docs/superpowers/plans/2026-04-23-audio-integration-harness-and-watchdog.md`).
  That harness makes refactoring safe.
- A few weeks of stability on the current code so we can tell regressions
  apart from "this is how it's always behaved."

**Why deferred:** it's the right call eventually but touches too much at
once. Do it after we have the integration harness + a few cycles of
stable on-device use.

## Reliability (observed, not yet tracked)

### Rapid consecutive skip schedules first chunk ~12 s in the future

**Symptom:** skip once (fine) -> skip again within a few seconds -> second track
has a multi-second wait before audio starts, then resumes with sample-insert/drop
glitches as the client catches up to server position.

**Evidence (device log 2026-04-23, post-AudioSink-refactor + tick-starvation fix merged):**
- First skip: first chunk `scheduled start` 336 ms in the future from `enterIdle`
  timestamp. Transition to PLAYING within 365 ms total.
- Second skip: first chunk `scheduled start` 12,052 ms in the future. State
  machine correctly holds in WAITING_FOR_START and plays silence for 12 s until
  `startErr` comes down to 42 ms, then transitions. 30 s of buffered audio
  accumulated in the meantime.
- Kalman offset drifted only 2 ms across both skips (measurements 296 -> 315),
  so time sync is not the cause.

**Likely location:** MA server, `music_assistant/providers/sendspin/player.py`.
The server's first-chunk timestamp after `stream/end` -> `stream/start` appears
to carry stale position from the previous track, or its `playback_start` offset
accumulates rather than resets on rapid re-skip.

**Client behaviour is correct:** holding for the scheduled start time preserves
multi-room sync. Ignoring the startErr would desync groups. Fix must be
server-side or protocol-level.

**UX priority:** medium-high. Breaks user expectation that skip is instant.

### DAC-aware start-gating log spam at every track change

`handleStartGatingDacAware` logs `DAC-aware start: waiting for alignment,
startErr=...ms > 50ms` on every 10 ms poll of the playback loop while
waiting for the DAC to catch up. For a normal ~2 s alignment wait that's
~200 debug lines per track change; for the rapid-second-skip 12 s wait it's
~1200. Harmless at INFO/WARN level (these are DEBUG) but noisy when debug
logging is on. Rate-limit to once per 100 ms or once per second.

Location: `SyncAudioPlayer.kt` around the `DAC-aware start: waiting for
alignment` emission in `handleStartGatingDacAware`.

## Protocol / Upstream

### File an MA upstream issue on three-image-source inconsistency

`music_assistant/providers/sendspin/player.py` uses three different image
sources for the same conceptual "current track image":
- Line 730: `queue_item.image`
- Line 736: `get_image_data_for_item(media_item)`
- Line 842: `current_media.image_url`

Captured in detail in `docs/architecture/sendspin-ma-metadata-flow.md` under
"observed bugs." Needs a GitHub issue filed against
`music-assistant/server`.

## Reliability (tracked in task list)

- **#16 L-5:** `isRecoverableError` default-true + no retry cap leads to
  infinite reconnect. Fix needs a bounded retry strategy.
- **#47 H-4 + M-8:** Codec-safe decoder redesign. PR #142's drop-oldest
  rejection policy corrupted MediaCodec internal state. A clean redesign
  needs to understand the decoder's state-machine contract better than the
  previous attempt did.

## Process

### Before major plans, require an integration test that would fail today

Several recent PRs passed unit tests and failed on-device (PR #142 FLAC
corruption, PR #144 tick starvation, PR #146 wizard duplicate-key crash).
The pattern: unit tests cover the pieces, nothing covers the composed
system. When writing a new multi-task plan, make task 1 "add a failing
integration test that motivates this plan" — forces the integration story
up front.
