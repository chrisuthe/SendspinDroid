# Development Checklist

Use this checklist to track your progress from skeleton to fully functional SendSpin player.

## ✅ Phase 1: Project Setup (COMPLETE)

- [x] Create project structure
- [x] Set up Go module
- [x] Create Android project with Gradle
- [x] Add build scripts
- [x] Write documentation

## 🔄 Phase 2: Basic Integration (NEXT STEPS)

- [ ] Add sendspin-go to go.mod
  ```bash
  cd go-player
  go get github.com/Sendspin/sendspin-go@latest
  go mod tidy
  ```

- [ ] Update imports in player.go
  ```go
  import (
      "github.com/Sendspin/sendspin-go/pkg/client"
      "github.com/Sendspin/sendspin-go/pkg/discovery"
  )
  ```

- [ ] Test build with dependencies
  ```bash
  ./build-gomobile.sh
  ```

## ⏳ Phase 3: mDNS Discovery

- [ ] Implement real mDNS browser in `discoverServers()`
- [ ] Handle server announcements
- [ ] Update callbacks with discovered servers
- [ ] Test discovery on local network
- [ ] Add error handling for network issues

## ⏳ Phase 4: Server Connection

- [ ] Implement WebSocket connection
- [ ] Add handshake logic (client/hello)
- [ ] Handle server responses
- [ ] Implement reconnection logic
- [ ] Add connection timeout handling
- [ ] Test with real SendSpin server

## ⏳ Phase 5: Playback Controls

- [ ] Wire up Play command
- [ ] Wire up Pause command
- [ ] Wire up Stop command
- [ ] Implement volume control
- [ ] Add mute functionality
- [ ] Test all controls

## ⏳ Phase 6: Metadata

- [ ] Receive metadata events
- [ ] Parse track information
- [ ] Update UI with metadata
- [ ] Handle album artwork (future)
- [ ] Test with various tracks

## ⏳ Phase 7: Audio Playback

Choose one approach:

### Option A: Go Audio (Recommended)
- [ ] Add oto or beep audio library
- [ ] Configure audio context (48kHz, stereo)
- [ ] Receive audio frames from SendSpin
- [ ] Feed frames to audio player
- [ ] Handle buffer underruns

### Option B: Android AudioTrack
- [ ] Create bridge to pass audio to Kotlin
- [ ] Set up AudioTrack in MainActivity
- [ ] Handle audio format negotiation
- [ ] Implement buffer management

## ⏳ Phase 8: Clock Synchronization

- [ ] Implement time sync protocol
- [ ] Calculate clock offset
- [ ] Adjust playback timing
- [ ] Test multi-room sync accuracy
- [ ] Monitor and log sync drift

## ⏳ Phase 9: Polish

- [ ] Add proper error messages
- [ ] Implement loading indicators
- [ ] Add settings screen
- [ ] Support manual server entry
- [ ] Add codec selection
- [ ] Improve UI/UX
- [ ] Add app icon
- [ ] Test on multiple devices

## ⏳ Phase 10: Advanced Features

- [ ] Background playback
- [ ] Notification controls
- [ ] Lock screen controls
- [ ] Bluetooth audio output
- [ ] Cast to other devices
- [ ] Playlist support
- [ ] Favorite servers
- [ ] Sleep timer

## Testing Checklist

### Unit Tests
- [ ] Test player initialization
- [ ] Test connection handling
- [ ] Test playback controls
- [ ] Test volume control
- [ ] Test error handling

### Integration Tests
- [ ] Test with real server
- [ ] Test network disconnection
- [ ] Test server restart
- [ ] Test multiple servers
- [ ] Test long running session

### Device Tests
- [ ] Test on phone (various sizes)
- [ ] Test on tablet
- [ ] Test on Android TV
- [ ] Test with headphones
- [ ] Test with Bluetooth speaker

## Performance Checklist

- [ ] Monitor battery usage
- [ ] Check network bandwidth
- [ ] Measure audio latency
- [ ] Test memory leaks
- [ ] Profile CPU usage
- [ ] Optimize buffer sizes

## Documentation Checklist

- [ ] Update README with real examples
- [ ] Add screenshots
- [ ] Create demo video
- [ ] Write troubleshooting guide
- [ ] Document known issues
- [ ] Add contribution guidelines

## Release Checklist

- [ ] Version bump
- [ ] Update changelog
- [ ] Test release build
- [ ] Sign APK
- [ ] Create GitHub release
- [ ] Share with SendSpin community
- [ ] Submit to Play Store (optional)

---

## Quick Commands Reference

```bash
# Build everything
./build-gomobile.sh && cd android && ./gradlew assembleDebug

# Install on device
cd android && ./gradlew installDebug

# View logs
adb logcat | grep -E "(SendSpinDroid|GoLog)"

# Clean build
cd android && ./gradlew clean && cd .. && rm -f android/app/libs/player.aar

# Check Go code
cd go-player && go vet && go fmt

# Check Kotlin code
cd android && ./gradlew ktlintCheck
```

## Current Status

**You are here:** ✅ Phase 1 complete

**Next step:** Add sendspin-go dependency and start Phase 2

**Estimated time to functional player:** 4-8 hours of focused work

**Estimated time to polished app:** 2-3 days

Good luck! 🚀
