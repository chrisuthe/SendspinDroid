![GitHub all releases](https://img.shields.io/github/downloads/chrisuthe/SendspinDroid/total?color=blue)

# SendSpin Player for Android

A native Android client for [SendSpin](https://www.sendspin-audio.com/) — synchronized multi-room audio that just works.

Play music in perfect sync across every room in your home. No special hardware required — just your Android phone, tablet, or car stereo.

## Features

### Playback
- Synchronized playback across unlimited rooms
- Background audio with lock screen and notification controls
- Android Auto integration for in-car listening
- Hardware volume buttons with bidirectional sync
- Skip, pause, and group switching from any device
- Adjustable sync offset for speaker delay compensation

### Audio Quality
- **Opus** — efficient compressed streaming, great for cellular
- **FLAC** — lossless quality for critical listening on WiFi
- Network-aware codec selection — automatically choose the best format for your connection
- 48 kHz, stereo output

### Interface
- Material You dynamic colors — matches your wallpaper on Android 12+
- Full dark and light theme support
- Full screen immersive mode
- Keep screen on while playing
- Stats for Nerds — real-time sync diagnostics for the curious

### Reliability
- Automatic server discovery via mDNS/Zeroconf
- Manual server entry when discovery isn't available
- Automatic reconnection on network changes
- Low memory mode for older devices
- Works on WiFi, Ethernet, and cellular networks

## Getting Started

1. **Install** — Download the latest APK from [Releases](https://github.com/chrisuthe/SendSpinDroid/releases)
2. **Open** — The app searches for SendSpin servers on your network
3. **Tap** — Select your server and you're listening

Need to connect manually? Tap "Enter server manually" and type your server address (e.g., `192.168.1.100:7080`).

### Requirements

- Android 8.0 (Oreo) or higher
- A [SendSpin](https://www.sendspin-audio.com/) server on your network

### Installing the APK

Since SendSpin Player isn't on the Play Store, you'll need to allow installation from your browser:

1. Download the APK from [Releases](https://github.com/chrisuthe/SendSpinDroid/releases)
2. Open the downloaded file
3. When prompted, tap **Settings** → enable **Allow from this source**
4. Go back and tap **Install**

## The SendSpin Protocol

SendSpin Player speaks the [SendSpin Protocol](https://www.sendspin-audio.com/) — a WebSocket-based streaming protocol designed for real-time synchronized audio. The server timestamps every audio chunk and each client uses precision clock synchronization to play them at exactly the right moment. The result: every speaker in your home plays the same beat at the same time, whether they're on WiFi, Ethernet, or cellular.

## License

MIT
