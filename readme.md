# SendSpinDroid - SendSpin Audio Player for Android

A gomobile-based Android application for the SendSpin multi-room audio protocol.

## Quick Links

- **[Quick Start Guide](QUICKSTART.md)** - Get building in 5 minutes
- **[Full README](README.md)** - Complete documentation
- **[Integration Guide](INTEGRATION.md)** - Connect to real SendSpin servers

## What is This?

SendSpinDroid is an Android player client for [SendSpin](https://www.sendspin-audio.com/), an open protocol for synchronized multi-room audio streaming. It uses:

- **Go** for networking and protocol implementation
- **gomobile** to create Android bindings
- **Kotlin** for the native Android UI

## Current Status

### ✅ Complete
- Full Android UI with Material Design
- Go wrapper structure for gomobile
- Build system and scripts
- Comprehensive documentation

### ⚠️ Needs Integration
- Actual SendSpin protocol implementation (skeleton provided)
- Real mDNS server discovery
- Audio stream playback

See [INTEGRATION.md](INTEGRATION.md) for step-by-step integration instructions.

## Quick Build

```bash
# 1. Build Go library
./build-gomobile.sh

# 2. Build Android app
cd android
./gradlew assembleDebug

# 3. Install
./gradlew installDebug
```

## Project Structure

```
SendSpinDroid/
├── go-player/              # Go player implementation
│   ├── player.go           # Player wrapper for gomobile
│   └── go.mod
├── android/                # Android app
│   └── app/
│       ├── src/main/java/com/sendspindroid/
│       │   ├── MainActivity.kt
│       │   ├── ServerAdapter.kt
│       │   └── ServerInfo.kt
│       └── build.gradle.kts
├── build-gomobile.sh       # Build script
├── QUICKSTART.md          # Quick start guide
├── README.md              # Full documentation
└── INTEGRATION.md         # Integration guide
```

## Learn More

- [SendSpin Protocol](https://www.sendspin-audio.com/)
- [sendspin-go Repository](https://github.com/Sendspin/sendspin-go)
- [gomobile Documentation](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile)

## License

Apache-2.0
