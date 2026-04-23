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
