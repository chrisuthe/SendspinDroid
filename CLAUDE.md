# SendSpinDroid Project Memory

## Current Focus: Native Kotlin SendSpin Client

This project is being rebuilt with a **native Kotlin** approach, removing the previous Go-based implementation.

## Application Architecture

SendSpinDroid is a **synchronized audio player** that connects to SendSpin servers:

```
SendSpin Server ──WebSocket──► SendSpinClient ──AAudio──► Audio Output
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
- `stream/stop` - Audio stream ending

### Binary Messages
```
Byte 0:     Message type (0-7 = audio slots, 8-11 = artwork)
Bytes 1-8:  Timestamp (int64, microseconds since server start)
Bytes 9+:   Payload (PCM audio or image data)
```

### Audio Format
- 48kHz sample rate
- 16-bit signed PCM
- Stereo (2 channels)
- Little-endian byte order

## Implementation Phases

1. **Protocol** - WebSocket connection, JSON/binary parsing
2. **Clock Sync** - Kalman filter for time synchronization
3. **Audio Buffer** - Timestamped chunk storage
4. **AAudio Output** - Native audio with sync correction

## Development Environment

- **Platform**: Windows
- **IDE**: Android Studio
- **JAVA_HOME**: `C:\Program Files\Android\Android Studio\jbr`

## Build Notes

Standard Android Gradle build:
```bash
cd android
./gradlew assembleDebug
```

## Release Process

**IMPORTANT**: Before creating a new version tag (e.g., `v1.0.25`):
1. Update `versionCode` and `versionName` in `app/build.gradle.kts`
2. Build and test
3. Commit the version bump
4. Then create and push the tag

## Reference Implementation

Python CLI player location: `C:\Users\chris\Downloads\sendspin-cli-main\sendspin-cli-main`

This is a fully working reference implementation. All features work as expected - use it to verify correct behavior when debugging.

Key files to study:
- `audio.py` - Main audio playback with time sync (~1500 lines)
- `protocol.py` - WebSocket protocol handling
- `clocksync.py` - Clock synchronization algorithm

The CLI shows the correct approach:
- Uses `sounddevice` which provides `outputBufferDacTime` in callback
- State machine: WAITING_FOR_START → PLAYING → REANCHORING
- Sync correction via sample insert/drop (±4% rate adjustment)
- Measures sync error: `expected_play_time - actual_dac_time`
