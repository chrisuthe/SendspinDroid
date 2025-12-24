# SendSpin Integration Guide

This guide explains how to integrate the actual sendspin-go library into this project.

## Current State

The project currently has:
- ✅ Complete Android UI
- ✅ gomobile binding structure
- ✅ Build system
- ⚠️ **Placeholder** Go player implementation

## Integration Steps

### 1. Update Go Dependencies

Edit `go-player/go.mod` to add the actual sendspin-go dependency:

```go
module github.com/sendspindroid/player

go 1.21

require (
    github.com/Sendspin/sendspin-go v0.1.0 // Use latest version
)
```

Then run:
```bash
cd go-player
go mod tidy
```

### 2. Implement Discovery

Update `go-player/player.go` to use sendspin-go's discovery:

```go
import (
    "github.com/Sendspin/sendspin-go/pkg/discovery"
)

func (p *Player) discoverServers() {
    // Create mDNS browser
    browser := discovery.NewBrowser()

    // Listen for server announcements
    for server := range browser.Servers() {
        if p.callback != nil {
            p.callback.OnServerDiscovered(
                server.Name,
                server.Address,
            )
        }
    }
}
```

### 3. Implement Player Connection

Add actual WebSocket connection:

```go
import (
    "github.com/Sendspin/sendspin-go/pkg/client"
    "github.com/Sendspin/sendspin-go/pkg/protocol"
)

type Player struct {
    // ... existing fields ...
    client *client.Client
}

func (p *Player) Connect(serverAddress string) error {
    p.mu.Lock()
    defer p.mu.Unlock()

    // Create SendSpin client
    cfg := &client.Config{
        ServerAddress: serverAddress,
        ClientID:      "sendspindroid",
        Roles:         []string{"player@v1"},
    }

    var err error
    p.client, err = client.New(cfg)
    if err != nil {
        return fmt.Errorf("failed to create client: %w", err)
    }

    // Connect to server
    if err := p.client.Connect(p.ctx); err != nil {
        return fmt.Errorf("failed to connect: %w", err)
    }

    p.isConnected = true

    // Start receiving events
    go p.handleEvents()

    if p.callback != nil {
        p.callback.OnConnected(serverAddress)
    }

    return nil
}
```

### 4. Handle Protocol Events

```go
func (p *Player) handleEvents() {
    for {
        select {
        case <-p.ctx.Done():
            return

        case event := <-p.client.Events():
            switch e := event.(type) {
            case *protocol.MetadataEvent:
                if p.callback != nil {
                    p.callback.OnMetadata(
                        e.Title,
                        e.Artist,
                        e.Album,
                    )
                }

            case *protocol.StateEvent:
                if p.callback != nil {
                    p.callback.OnStateChanged(e.State)
                }

            case *protocol.ErrorEvent:
                if p.callback != nil {
                    p.callback.OnError(e.Message)
                }
            }
        }
    }
}
```

### 5. Implement Playback Controls

```go
func (p *Player) Play() error {
    p.mu.Lock()
    defer p.mu.Unlock()

    if !p.isConnected || p.client == nil {
        return fmt.Errorf("not connected")
    }

    // Send play command to server
    return p.client.SendCommand(&protocol.PlayCommand{})
}

func (p *Player) Pause() error {
    p.mu.Lock()
    defer p.mu.Unlock()

    if !p.isConnected || p.client == nil {
        return fmt.Errorf("not connected")
    }

    return p.client.SendCommand(&protocol.PauseCommand{})
}

func (p *Player) Stop() error {
    p.mu.Lock()
    defer p.mu.Unlock()

    if !p.isConnected || p.client == nil {
        return fmt.Errorf("not connected")
    }

    return p.client.SendCommand(&protocol.StopCommand{})
}

func (p *Player) SetVolume(volume float64) error {
    p.mu.Lock()
    defer p.mu.Unlock()

    if !p.isConnected || p.client == nil {
        return fmt.Errorf("not connected")
    }

    return p.client.SendCommand(&protocol.VolumeCommand{
        Level: volume,
    })
}
```

### 6. Handle Audio Playback

This is the most complex part. You have two options:

#### Option A: Use Go Audio Libraries

```go
import (
    "github.com/Sendspin/sendspin-go/pkg/audio"
    "github.com/hajimehoshi/oto/v2" // Pure Go audio
)

func (p *Player) setupAudio() error {
    // Create audio context
    ctx, ready, err := oto.NewContext(48000, 2, 2)
    if err != nil {
        return err
    }
    <-ready

    // Create player
    audioPlayer := ctx.NewPlayer()

    // Receive audio frames from SendSpin
    go func() {
        for frame := range p.client.AudioFrames() {
            audioPlayer.Write(frame.Data)
        }
    }()

    return nil
}
```

#### Option B: Bridge to Android AudioTrack

This requires JNI integration, which is more complex with gomobile. You would need to:

1. Expose audio data through gomobile interface
2. Receive it in Kotlin
3. Feed to Android AudioTrack

```kotlin
// In MainActivity.kt
private lateinit var audioTrack: AudioTrack

private fun setupAudio() {
    val bufferSize = AudioTrack.getMinBufferSize(
        48000,
        AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    audioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build())
        .setAudioFormat(AudioFormat.Builder()
            .setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build())
        .setBufferSizeInBytes(bufferSize)
        .build()

    audioTrack.play()
}
```

### 7. Update Android Code

Uncomment the player initialization and callback code in `MainActivity.kt`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setupUI()
    initializePlayer() // Uncomment this
}
```

## Testing

### 1. Set up a SendSpin Server

You can use the Go implementation:

```bash
git clone https://github.com/Sendspin/sendspin-go
cd sendspin-go
go build -o sendspin-server ./cmd/server
./sendspin-server --media-dir ./music
```

Or use Music Assistant with SendSpin support.

### 2. Build and Install

```bash
./build-gomobile.sh
cd android
./gradlew installDebug
```

### 3. Run and Connect

1. Ensure your Android device and SendSpin server are on the same network
2. Launch the app
3. Tap "Discover Servers"
4. Select your server from the list
5. Try playback controls

## Debugging

### Enable Go Logging

Add logging to see what's happening in the Go layer:

```go
import "log"

func init() {
    log.SetPrefix("[SendSpin] ")
}
```

### View Logs

```bash
# Android logs
adb logcat | grep -E "(SendSpinDroid|GoLog)"

# All logs
adb logcat
```

### Common Issues

**mDNS not working**
- Ensure multicast is enabled on your network
- Check firewall settings
- Try manual server entry instead of discovery

**Audio not playing**
- Check codec support
- Verify sample rate compatibility (48kHz is standard)
- Test with PCM format first (uncompressed)

**Connection timeout**
- Verify server is running and accessible
- Check network connectivity
- Try using IP address directly

## Performance Considerations

1. **Audio Buffer Size**: Tune for your latency requirements
2. **Clock Sync**: SendSpin uses NTP-style synchronization
3. **Network**: WiFi is recommended over mobile data
4. **Battery**: Audio processing is CPU-intensive

## Next Steps

After integration:

1. Add proper error handling
2. Implement reconnection logic
3. Add UI for server configuration
4. Support multiple audio codecs
5. Add equalizer/audio effects
6. Implement gapless playback
7. Add notification controls
8. Support background playback

## References

- [sendspin-go Documentation](https://github.com/Sendspin/sendspin-go)
- [SendSpin Protocol Spec](https://github.com/Sendspin/spec)
- [gomobile Wiki](https://github.com/golang/go/wiki/Mobile)
- [Android Audio Guide](https://developer.android.com/guide/topics/media/mediaplayer)
