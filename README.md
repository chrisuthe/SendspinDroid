# SendSpinDroid

A native Android client for [SendSpin](https://www.sendspin-audio.com/) multi-room synchronized audio streaming.

## Features

- Automatic server discovery via mDNS
- Manual server entry
- Time-synchronized PCM audio playback (48kHz, 16-bit, stereo)
- Volume control
- Play/Pause/Skip controls
- Background playback with lock screen controls
- Android Auto support

## Architecture

Native Kotlin implementation with AAudio for low-latency synchronized playback:

```
SendSpin Server ──WebSocket──► SendSpinClient ──AAudio──► Audio Output
                    │
                    ├── JSON (metadata, state)
                    └── Binary (timestamped audio)
```

- **SendSpinClient** - WebSocket protocol, clock sync, audio buffering
- **PlaybackService** - MediaSession for background playback & notifications
- **AAudio/Oboe** - Native audio output with DAC timing feedback

## Building

### Prerequisites

- Android Studio
- Android SDK (API 26+)
- Java 21 (bundled with Android Studio)

### Build

```bash
cd android
./gradlew assembleDebug
```

### Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Protocol

Implements the SendSpin protocol:

- WebSocket connection with JSON control messages
- Binary audio chunks: `[type:1][timestamp:8][pcm_data:N]`
- Clock synchronization for multi-room sync
- 48kHz stereo 16-bit PCM audio

## References

- [SendSpin Protocol Specification](https://www.sendspin-audio.com/spec/)
- [Python CLI Reference](https://github.com/Sendspin/sendspin-cli)

## License

MIT
