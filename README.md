# SendSpinDroid

A native Android client for [SendSpin](https://www.sendspin-audio.com/) multi-room synchronized audio streaming.

## Features

- **Automatic server discovery** via mDNS/Zeroconf
- **Manual server entry** for networks where discovery doesn't work
- **Time-synchronized playback** with sub-millisecond accuracy across multiple rooms
- **Background playback** with lock screen and notification controls
- **Android Auto support** for in-car listening
- **Hardware volume buttons** control playback and sync with other clients
- **Multi-room sync** with Kalman-filtered clock synchronization

## Installation

### Requirements

- Android 8.0 (Oreo) or higher

### Download

Download the latest APK from [GitHub Releases](https://github.com/chrisuthe/SendSpinDroid/releases).

### Enable Sideloading

Before installing, you need to allow app installation from unknown sources:

**Android 8.0+:**
1. Download the APK file
2. Open the APK - Android will prompt you to allow installation from your browser/file manager
3. Tap "Settings" when prompted
4. Enable "Allow from this source"
5. Go back and tap "Install"

**Older method (if needed):**
1. Go to Settings → Security
2. Enable "Unknown sources"
3. Open the APK and tap "Install"

### Permissions

The app requires:
- **Internet access** - To connect to SendSpin servers
- **WiFi multicast** - For automatic server discovery

## Getting Started

1. **Open the app** - It will automatically search for SendSpin servers on your network
2. **Wait for discovery** - Found servers appear automatically within a few seconds
3. **Tap to connect** - Select a server to start streaming

**Manual connection:** If automatic discovery doesn't find your server, tap "Enter server manually" and enter the server address (e.g., `192.168.1.100:7080`).

## Architecture

Native Kotlin implementation with precision time synchronization:

```
SendSpin Server ──WebSocket──► SendSpinClient ──AudioTrack──► Audio Output
                    │
                    ├── JSON (metadata, state, commands)
                    └── Binary (timestamped PCM audio)
```

### Components

| Component | Responsibility |
|-----------|----------------|
| **SendSpinClient** | WebSocket protocol, clock synchronization, audio buffering |
| **PlaybackService** | Background playback, MediaSession, notifications, Android Auto |
| **SyncAudioPlayer** | Synchronized audio output with real-time drift correction |
| **MainActivity** | Server discovery UI, playback controls, volume slider |

### Synchronization

SendSpinDroid achieves multi-room sync through:

- **NTP-style clock sync** - Sends burst measurements, selects lowest-latency sample
- **Kalman filtering** - Rejects network jitter, tracks clock drift over time
- **Sample-level correction** - Inserts or drops individual audio samples to maintain sync (imperceptible at 48kHz)

Audio format: 48kHz, 16-bit, stereo PCM

## Protocol

Implements the [SendSpin Protocol Specification](https://www.sendspin-audio.com/spec/):

- WebSocket connection with JSON control messages
- Binary audio chunks: `[type:1][timestamp:8][pcm_data:N]`
- Bidirectional volume synchronization
- Supported codecs: PCM, FLAC, Opus

## License

MIT
