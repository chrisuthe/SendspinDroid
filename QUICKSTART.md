# Quick Start Guide

Get SendSpinDroid up and running in 5 minutes.

## Prerequisites Check

```bash
# Check Go installation
go version  # Should be 1.21+

# Check Android SDK
echo $ANDROID_HOME  # Should point to Android SDK

# Install gomobile
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
```

## Build in 3 Steps

### 1. Build Go Library

```bash
./build-gomobile.sh
```

Expected output:
```
Building SendSpin Player with gomobile...
Fetching Go dependencies...
Building AAR with gomobile bind...
Build complete! AAR created at: android/app/libs/player.aar
```

### 2. Build Android App

```bash
cd android
./gradlew assembleDebug
```

### 3. Install on Device

```bash
./gradlew installDebug
```

Or manually:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## First Run

1. Open "SendSpin Player" app
2. Tap "Discover Servers"
3. (Currently shows test server - see INTEGRATION.md for real server setup)
4. Tap server to connect
5. Use playback controls

## What's Working

✅ Android UI
✅ Build system
✅ Basic app structure
✅ UI controls and layout

## What Needs Integration

⚠️ Actual SendSpin protocol (see [INTEGRATION.md](INTEGRATION.md))
⚠️ Real server discovery
⚠️ Audio playback

## Next Steps

- Read [INTEGRATION.md](INTEGRATION.md) to connect real SendSpin server
- Check [README.md](README.md) for full documentation
- Review code in `go-player/` and `android/app/src/`

## Troubleshooting

**Build fails?**
```bash
# Clean and retry
cd android
./gradlew clean
cd ..
./build-gomobile.sh
cd android
./gradlew assembleDebug
```

**gomobile not found?**
```bash
export PATH=$PATH:$(go env GOPATH)/bin
gomobile version
```

**Need help?**
- Check [README.md](README.md) troubleshooting section
- Review [SendSpin documentation](https://www.sendspin-audio.com/)
