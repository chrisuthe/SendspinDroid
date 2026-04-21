# SendSpinDroid Project Memory

## Overview

SendSpinDroid is a native Kotlin Android client for SendSpin. It acts as a **Player**-role client: it connects to a SendSpin server over WebSocket, synchronizes its local clock to the server, and renders timestamped PCM audio with continuous sync correction.

## Application Architecture

SendSpinDroid is a **synchronized audio player** that connects to SendSpin servers:

```
SendSpin Server ──WebSocket──► SendSpinClient ──► SyncAudioPlayer (AudioTrack) ──► Audio Output
                    │
                    ├── JSON messages (metadata, state)
                    └── Binary messages (timestamped audio PCM)
```

## Key Components

### SendSpinClient (`sendspin/SendSpinClient.kt`)
- WebSocket connection via OkHttp
- Protocol parsing (JSON + binary)
- Clock synchronization
- Audio buffering with timestamps

### PlaybackService (`playback/PlaybackService.kt`)
- Android MediaLibraryService for background playback
- MediaSession for lock screen/notification controls
- Android Auto browse tree support

## SendSpin Protocol

### Text Messages (JSON)
- `server/state` - Server state and track metadata
- `group/update` - Group playback state changes
- `stream/start` - Audio stream beginning
- `stream/end` - Audio stream ending

### Binary Messages
```
Header format: struct ">Bq" (big-endian: 1 byte type + 8 byte int64 timestamp)
Byte 0:     Message type
Bytes 1-8:  Timestamp (big-endian int64, microseconds since server start)
Bytes 9+:   Payload (PCM audio or image data)

Types:
  4:     Audio data
  8-11:  Artwork channels 0-3 (empty payload = clear artwork)
  16:    Visualizer data
```

### Audio Format
- 48kHz sample rate
- 16-bit, 24-bit, or 32-bit signed PCM (negotiated via client hello)
- Stereo (2 channels) or Mono (1 channel)
- Audio sample data is little-endian; header timestamps are big-endian

### Client State (`client/state`)
Reports player state to server:
- `state`: "synchronized" or "error"
- `volume`: 0-100
- `muted`: boolean
- `static_delay_ms`: device audio output latency compensation (milliseconds)

## Audio Pipeline

The shipped audio pipeline uses Android's `AudioTrack` (Java API) in `MODE_STREAM` (push mode). Incoming binary WebSocket frames are decoded to PCM (if encoded), anchored against the synchronized server clock via a Kalman time filter, and written to `AudioTrack` with sample insert/drop correction to maintain sync.

Key modules:
- **Protocol** - WebSocket connection, JSON/binary frame parsing (`sendspin/protocol/`)
- **Clock Sync** - 2D Kalman time filter (`SendspinTimeFilter.kt`)
- **Audio Buffer** - Timestamped chunk queue and state machine (`SyncAudioPlayer.kt`)
- **Audio Output** - `AudioTrack` in `SyncAudioPlayer.kt` with DAC-aware start gating and sample insert/drop sync correction

AAudio/Oboe would provide callback-precise hardware latency and lower-latency writes, and is a **possible future migration** — but is not the currently shipped path. Code and docs should describe `AudioTrack` as the present audio output.

## Development Environment

- **Platform**: Windows
- **IDE**: Android Studio
- **JAVA_HOME**: `C:\Program Files\Android\Android Studio\jbr`
- **ADB**: `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe` (not in PATH)

## Build Notes

Standard Android Gradle build:
```bash
cd android
./gradlew assembleDebug
```

## Debugging Utilities

### ZTE Logging Toggle (`android/zte-logging.bat`)
Nubia/ZTE devices have verbose system logging disabled by default. Use this script to toggle it:

```batch
zte-logging.bat on      # Enable logging (for debugging)
zte-logging.bat off     # Disable logging (saves battery)
zte-logging.bat status  # Show log buffer sizes
```

**Note**: Always disable logging when done debugging - it impacts battery and performance.

## Code Style

- **No emojis**: Do not use emojis in code, logs, or UI strings unless explicitly approved by the user.
- Use ASCII alternatives: `us` instead of `μs`, `->` instead of `→`, `+/-` instead of `±`
- **No self-citation**: Never cite yourself (e.g., "Co-Authored-By: Claude") in commits, comments, or release notes.

## Release Process

**IMPORTANT**: Before creating a new version tag (e.g., `v2.1.3`):
1. Update `versionCode` and `versionName` in `app/build.gradle.kts`
2. Build and test
3. Commit the version bump
4. Then create and push the tag

### versionCode Scheme

Encoded semantic version: `MAJOR * 10000 + MINOR * 100 + PATCH`

| Segment | Digits | Range |
|---------|--------|-------|
| MAJOR   | 4      | 0-9999 |
| MINOR   | 2      | 0-99   |
| PATCH   | 2      | 0-99   |

Examples: `2.0.0` = 20000, `2.1.3` = 20103, `10.5.22` = 100522

Pre-release suffixes (alpha, beta, rc) do NOT affect the versionCode -- they only appear in versionName. A pre-release shares the same versionCode as its eventual stable release.

## License

MIT License (see `LICENSE` in repo root).

## Reference Implementation

Python CLI player location: `C:\Users\chris\Downloads\sendspin-cli-main\sendspin-cli-main`

This is a fully working reference implementation. All features work as expected - use it to verify correct behavior when debugging.

Key files to study:
- `audio.py` - Main audio playback with time sync (~1500 lines)
- `protocol.py` - WebSocket protocol handling
- `clocksync.py` - Clock synchronization algorithm

The CLI shows the canonical approach:
- Uses `sounddevice` which provides `outputBufferDacTime` in callback
- State machine: WAITING_FOR_START -> PLAYING -> REANCHORING
- Sync correction via sample insert/drop (+/-4% rate adjustment)
- Measures sync error: `expected_play_time - actual_dac_time`

### Android Deviations from the Python Reference

- **Speed-correction cap**: `SyncAudioPlayer.MAX_SPEED_CORRECTION = 0.02` (+/-2%), vs the reference's +/-4%. The tighter cap is a safety margin against over-correction given that `AudioTrack` DAC-timing jitter on Android can be larger than desktop `sounddevice`. Revisit if high-drift scenarios fail to converge.
- **Audio output API**: `AudioTrack` in `MODE_STREAM` instead of `sounddevice`. DAC position is read via `AudioTrack.getTimestamp()` for sync-error measurement and start-gating.
