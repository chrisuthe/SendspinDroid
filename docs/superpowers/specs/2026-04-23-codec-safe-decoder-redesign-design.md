# Codec-Safe Decoder Redesign - Design

**Audit findings addressed:** H-4 (decode on WebSocket IO thread delays clock-sync responses), M-8 (TOCTOU race between main-thread decoder recreate and WS-IO decode call).

**Prior art:** PR #142 landed a similar architecture, field-tested, caused FLAC corruption, reverted in PR #148. This design preserves the parts that worked and replaces the part that failed.

---

## Goal

Move MediaCodec decoding off the OkHttp WebSocket IO thread without corrupting
codec state during bursty input (e.g., a 2-second pre-buffered compressed
chunk burst at stream start).

## Current state (post-revert baseline)

`PlaybackService.kt:1405 onAudioChunk`:

- Runs on the OkHttp WS-IO thread.
- Guards with `if (!decoderReady) return` then captures a local reference to
  `audioDecoder` before calling `decoder.decode(audioData)` (line 1419).
- `onStreamStart` (line 1301) releases+creates the decoder on the **main thread**.
- `onStreamClear` (line 1384) flushes on the **main thread**.
- `onDestroy` (line 3863) releases on the **main thread**.

Consequences:

1. **H-4:** ~5 ms per decoded chunk runs inline on WS-IO. Time-sync responses
   arriving on the same thread are delayed by the decode cost. Kalman jitter.
2. **M-8:** The local-ref capture narrows but doesn't eliminate the window
   between the null-check, the local assignment, and `decode()`. A main-thread
   `audioDecoder?.release()` can still land between those three lines and the
   captured reference can point at freed native resources.

## Why PR #142 failed

PR #142's architecture was correct: single-owner decoder on a dedicated
executor, WS-IO submits and returns quickly. The **backpressure policy**
was wrong.

- `LinkedBlockingQueue(100)` with `DiscardOldestPolicy` as the rejection handler.
- FLAC is not drop-safe mid-frame: dropping compressed chunks corrupts the
  decoder's internal frame-boundary tracking.
- Cold-start (~175 ms) + pre-buffered 2-second burst fills the 100-slot queue;
  drop-oldest kicks in; MediaCodec enters ERROR/Released state; every
  subsequent decode throws `IllegalStateException`.
- ~1 second of audio plays from the AudioTrack buffer tail, then silence.

## Design

### Ownership and threading

- A dedicated **decode coroutine** owns `audioDecoder` for its entire
  lifetime. Creation, configure, flush, and release all happen on this
  coroutine. No other thread touches the field.
- `audioDecoder` becomes a **non-volatile `var`** owned by the coroutine.
  `@Volatile` is removed: single writer, no need.
- `decoderReady` stays `@Volatile` because WS-IO reads it as a fast-path
  gate before enqueueing.
- Lifecycle events (`onStreamStart` / `onStreamClear` / `onDestroy`) post
  their work to the decode coroutine through the same channel they use
  for chunks. This preserves ordering: a `decode(oldChunk)` submitted
  before a `release(oldDecoder)+create(newDecoder)` runs with the old
  decoder; `decode(newChunk)` after runs with the new one.

### Backpressure

- **`Channel<DecodeTask>(capacity = 500)`** between WS-IO and the decode
  coroutine. 500 slots ≈ 10 seconds at the expected 50 chunks/sec.
- **`channel.send(...)`** (suspending) on WS-IO. Never drops.
- Under normal steady-state the channel sits near-empty.
- Under cold-start + 2-second burst: ~100 entries. Well within bound.
- Under thermal throttle or GC pause: decoder pauses for up to a few hundred
  ms, queue fills by a few dozen entries, drains when decoder resumes.
- Pathological sustained stall (decoder permanently slower than realtime):
  channel fills to 500, WS-IO's `send` suspends. This is the only scenario
  where WS-IO pauses. It is acceptable because:
  - It's bounded (the decoder will consume something eventually).
  - It's a real device-capability failure; blocking produces better UX than
    dropping (which would corrupt) or resyncing (which would cut audio for
    seconds).
  - Our Kalman filter handles missed time-sync measurements (we proved this
    with the tick-starvation work; measurements are taken every 500 ms-3 s
    and the filter is robust to gaps).

### DecodeTask shape

```kotlin
private sealed class DecodeTask {
    data class Chunk(val serverTimeMicros: Long, val audioData: ByteArray) : DecodeTask()
    data class StartStream(val codec: String, val sampleRate: Int, val channels: Int,
                           val bitDepth: Int, val codecHeader: ByteArray?) : DecodeTask()
    object Flush : DecodeTask()
    object Release : DecodeTask()
}
```

`StartStream` / `Flush` / `Release` are submitted on the same channel so they
respect FIFO ordering with `Chunk`. This mirrors PR #142's design and is
the property that makes single-owner work correctly.

### Lifecycle

- **onStreamStart:** send `StartStream(...)`. Do NOT touch `audioDecoder`
  from the main thread. Set `decoderReady = true` optimistically; it will
  be reset to false briefly while the decode coroutine tears down the old
  decoder and creates the new one. Race is benign because WS-IO's
  `decoderReady` gate only affects whether we enqueue; a chunk enqueued
  during reconfiguration will be processed with the new decoder after the
  `StartStream` task completes.
- **onStreamClear:** send `Flush`.
- **onDestroy / destroy:** send `Release`, close the channel, join the
  decode coroutine with a 500 ms timeout (same as PR #142).

### H-4 + M-8 structural outcomes

- H-4: WS-IO does constant-time work (`channel.send`, which is fast when
  not full). Decode cost is off-thread. Time-sync responses no longer
  queue behind decodes.
- M-8: the TOCTOU window is gone. Only the decode coroutine mutates
  `audioDecoder`. WS-IO never touches it.

## Non-goals

- **Post-decode PCM backpressure.** `SyncAudioPlayer.chunkQueue` is already
  unbounded; sync drift recovery is handled by existing REANCHORING
  machinery. Not changing that.
- **Codec-specific framing awareness.** No FLAC frame-sync scanning, no
  Opus self-delimited frame handling. We don't drop compressed chunks,
  so we don't need resync-from-frame-boundary logic.
- **Async-mode MediaCodec.** Keeping sync mode (`dequeueInputBuffer` /
  `queueInputBuffer`) - it's what the existing `AudioDecoder` interface
  uses and it works. Async mode is a larger refactor.
- **Changes to the `AudioDecoder` interface.** We're re-threading the
  owner, not changing the API.

## Testing strategy

The lesson from PR #142: unit tests passed on JVM, corruption only showed
up on real device. We must close this gap.

### Primary regression test (would fail against PR #142's design)

A JVM unit test using a **FakeAudioDecoder** that models MediaCodec's
sensitivity to contiguity:

- `FakeAudioDecoder.decode(bytes)` tracks a running "next expected
  chunk sequence number" in the input. If a chunk is missing (i.e., any
  input chunk did not arrive in producer order), subsequent calls throw
  `IllegalStateException("non-contiguous input at sequence N")`.
- The test then:
  1. Builds PlaybackService with the fake decoder
  2. Submits 200 chunks in rapid succession (simulating the 2-second
     pre-buffer burst that killed PR #142)
  3. Asserts `chunkQueue` on `SyncAudioPlayer` received all 200 PCM
     outputs in order
  4. Asserts the fake decoder never saw non-contiguous input

This test would fail against `Channel(capacity = 100).send` with any
dropping behavior; it must pass against `Channel(capacity = 500).send`
with suspend-on-full.

### Secondary tests

- `onStreamStart` → `onAudioChunk` → `onStreamEnd` → `onStreamStart` cycle:
  decoder released and recreated cleanly; chunks after the cycle only go
  to the new decoder.
- `onStreamClear` flush ordering: a chunk submitted before `Flush` is
  decoded; a chunk submitted after is decoded with cleared decoder state.
- `onDestroy` path: pending chunks drain or are abandoned cleanly within
  the 500 ms teardown timeout.
- Thread-name test (like PR #140's): decode runs on a coroutine dispatcher
  named `SendSpinDecode`, WS-IO threads don't run `decoder.decode()`.

### Not testing

- MediaCodec itself under burst. That's an instrumented-test concern and
  PR #142's failure mode is already understood; we don't need to
  re-validate the codec's behavior, only our pipeline around it.

## Risks

1. **WS-IO suspension under pathological decoder stall.** Mitigated by
   500-slot queue sizing (bigger than any realistic stall) and Kalman
   robustness to missed sync measurements.
2. **DecodeTask ordering assumption.** The channel is ordered, but
   writers on WS-IO and main thread both call `channel.trySend` /
   `channel.send`. We need to verify that cross-thread sends to a
   single channel preserve FIFO. Kotlin channels guarantee this per
   the docs, but we'll add a test.
3. **StartStream during an in-flight decode burst.** If WS-IO is
   currently suspended on `send` with a queue full of old-stream
   chunks, a `StartStream` from the main thread also suspends.
   Acceptable - the main thread posting to the channel isn't
   time-sensitive, and the stale chunks drain quickly once the
   decoder consumes them. Alternative (drop-on-stream-start) adds
   complexity for a transient scenario.
4. **Test fidelity.** The FakeAudioDecoder models contiguity but not
   all MediaCodec quirks (state transitions on flush, buffer-tracking
   limits). Acceptable - we're testing the pipeline, not MediaCodec.

## File layout

- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`
  (the only file with decoder logic)
- Add: `android/app/src/test/java/com/sendspindroid/playback/FakeAudioDecoder.kt`
  (test double that enforces contiguity)
- Add: `android/app/src/test/java/com/sendspindroid/playback/DecoderPipelineIntegrationTest.kt`
  (burst, cycle, flush, teardown tests)
- Update: `android/app/src/test/java/com/sendspindroid/playback/StreamStartDecoderPipelineTest.kt`
  if existing tests observe decoder lifecycle directly

## Success criteria

- Burst test: 200 chunks in <50 ms wall-clock, all decoded contiguously, no drops.
- H-4 structural test: WS-IO callback returns in <1 ms for chunk handoff
  (measured; excludes the `Channel.send` suspend case which only fires
  under pathological stall).
- M-8 structural guarantee: only the decode coroutine mutates `audioDecoder`
  (enforced by code structure; no lock needed).
- No FLAC corruption on device across stream start, track change, seek, pause/resume.
- All existing app tests still pass.
