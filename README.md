# SendSpinDroid

A simple Android client for [SendSpin](https://www.sendspin-audio.com/) multi-room audio streaming.

## Features

- 🔍 Automatic server discovery via mDNS
- 📝 Manual server entry
- 🎵 PCM audio playback (48kHz, 16-bit, stereo)
- 🎚️ Volume control
- ⏯️ Play/Pause/Stop controls

## Architecture

This app uses **gomobile bind** to create an Android library from Go code:

- **Go Layer** (`go-player/`): SendSpin protocol implementation
  - WebSocket connection handling
  - mDNS service discovery
  - Binary audio chunk parsing
  - Audio data buffering

- **Android Layer** (`android/`): UI and audio playback
  - Material Design UI
  - Android AudioTrack for low-latency PCM playback
  - Kotlin coroutines for async audio processing

## Building

### Prerequisites

- Go 1.21+
- Android SDK with NDK 21.4.7075529
- Java 17 (for Gradle)
- gomobile: `go install golang.org/x/mobile/cmd/gomobile@latest`

### Build Steps

1. Initialize gomobile:
```bash
gomobile init
```

2. Build the Go library:
```bash
cd go-player
gomobile bind -target=android -o ../android/player/player.aar .
```

3. Build the Android app:
```bash
cd ../android
./gradlew assembleDebug
```

4. Install on device:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Protocol Implementation

The app implements the SendSpin protocol as documented at [sendspin-audio.com/spec](https://www.sendspin-audio.com/spec/):

- WebSocket connection to `/sendspin` endpoint
- `client/hello` handshake with player capabilities
- `client/state` synchronization
- Binary audio chunks with 9-byte header (message type + timestamp)
- Automatic header stripping for pure PCM audio data

## Known Limitations

- Only supports PCM codec (48kHz, 16-bit, stereo)
- Basic buffering without timestamp-based synchronization
- No clock sync implementation yet
- Drops audio chunks if Android can't keep up (non-blocking design)

## References

- [SendSpin Protocol Specification](https://www.sendspin-audio.com/spec/)
- [aiosendspin Python SDK](https://github.com/Sendspin/aiosendspin)
- [windowsSpin C# Implementation](https://github.com/chrisuthe/windowsSpin)

## License

MIT
