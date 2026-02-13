![GitHub all releases](https://img.shields.io/github/downloads/chrisuthe/SendspinDroid/total?color=blue)

# SendSpin Player & Music Assitant Client for Android

A native Android client for [SendSpin](https://www.sendspin-audio.com/)


## Features

### Synchronized Playback
- Precision-synced audio across unlimited rooms
- Background audio with lock screen and notification controls
- Android Auto integration
- Android TV support
- Hardware volume buttons with bidirectional sync
- Skip, pause, seek, and group switching from any device
- Adjustable sync offset for speaker delay compensation

### Music Assistant Integration
- Browse your full music library -- albums, artists, tracks, playlists, and radio
- Full-text search across your entire collection
- Album and artist detail screens with cover art
- Create and manage playlists, add or remove tracks
- Queue management -- view upcoming tracks, reorder, remove, clear
- Play Next and Add to Queue from any browse or search screen
- Shuffle and repeat mode controls

### Audio Quality
- **Opus** -- efficient compressed streaming, great for cellular
- **FLAC** -- lossless quality for critical listening on WiFi
- **PCM** -- uncompressed raw audio
- Network-aware codec selection -- automatically choose the best format per connection type
- Separate WiFi and cellular codec preferences
- 48 kHz stereo output

### Interface
- Material You dynamic colors -- matches your wallpaper on Android 12+
- Full dark and light theme support
- Bottom navigation with Home, Library, Search, and Playlists tabs
- Mini player bar with configurable position (top or bottom)
- Full-screen now playing view with album art and playback controls
- Queue bottom sheet accessible from the now playing screen
- Full-screen immersive mode
- Keep screen on while playing
- Portrait and landscape support

### Connectivity
- Automatic server discovery via mDNS/Zeroconf on local networks
- Manual server entry for direct connections
- Remote access via Music Assistant Remote ID (WebRTC)
- QR code scanner for quick Remote ID input
- HTTP proxy support for routed connections
- Automatic reconnection on network changes
- Works on WiFi, Ethernet, and cellular networks

### Server Management
- Multi-server support with saved server list
- Add Server wizard with guided setup (discover, login, test, save)
- Music Assistant authentication with token-based login
- Per-server connection configuration
- Server status monitoring

### Settings
- Custom player name
- Display preferences (full-screen mode, keep screen on, mini-player position)
- Audio sync offset tuning
- Preferred codec selection (per WiFi and cellular)
- Low memory mode for older devices
- Debug logging with log export
- Stats for Nerds -- real-time sync diagnostics

## Getting Started

1. **Install** -- Download the latest APK from [Releases](https://github.com/chrisuthe/SendSpinDroid/releases)
2. **Open** -- The app searches for SendSpin servers on your network
3. **Tap** -- Select your server and you're listening

### Connecting to a Server

- **Local network**: Servers are discovered automatically via mDNS
- **Manual entry**: Tap "Enter server manually" and type your server address (e.g., `192.168.1.100:7080`)
- **Remote access**: Enter a Music Assistant Remote ID or scan a QR code to connect from anywhere
- **Proxy**: Configure an HTTP proxy for routed connections

### Requirements

- Android 8.0 (Oreo) or higher
- A [SendSpin](https://www.sendspin-audio.com/) server on your network (or a Remote ID for remote access)

### Installing the APK

Since SendSpin Player isn't on the Play Store, you'll need to allow installation from your browser:

1. Download the APK from [Releases](https://github.com/chrisuthe/SendSpinDroid/releases)
2. Open the downloaded file
3. When prompted, tap **Settings** -> enable **Allow from this source**
4. Go back and tap **Install**

## Architecture

SendSpin Player is built with native Kotlin and Jetpack Compose, using a hybrid architecture:

- **Jetpack Compose** for library browsing, search, playlists, detail screens, queue, and settings
- **XML layouts with ViewBinding** for the main activity shell, now playing screen, and server management
- **Fragments** for navigation within the bottom navigation tabs
- **ViewModels** for state management across configuration changes
- **Coroutines** for async operations and WebSocket communication
- **Media3 MediaSession** for system integration, notifications, and Android Auto

### The SendSpin Protocol

SendSpin Player speaks the [SendSpin Protocol](https://www.sendspin-audio.com/) -- a WebSocket-based streaming protocol designed for real-time synchronized audio. The server timestamps every audio chunk and each client uses precision clock synchronization to play them at exactly the right moment. The result: every speaker in your home plays the same beat at the same time.

## License

MIT -- see [LICENSE](LICENSE) for the full text.

## Third-Party Acknowledgments

SendSpin Player uses the following open-source libraries:

| Library | License | Copyright |
|---------|---------|-----------|
| [AndroidX](https://developer.android.com/jetpack/androidx) (Core, AppCompat, Lifecycle, Media3, Compose, CameraX, Palette, Preference, Fragment, ViewPager2, SwipeRefreshLayout, ConstraintLayout) | Apache 2.0 | The Android Open Source Project |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Apache 2.0 | The Android Open Source Project |
| [Kotlin & Kotlinx Coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache 2.0 | JetBrains s.r.o. and contributors |
| [OkHttp](https://github.com/square/okhttp) | Apache 2.0 | Square, Inc. |
| [Coil](https://github.com/coil-kt/coil) | Apache 2.0 | Coil Contributors |
| [Stream WebRTC Android](https://github.com/nicobatty/webrtc-android) | Apache 2.0 | Stream.io Inc. |
| [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) | MIT | Nathan Rajlich |

This project also uses [Google ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning), which is governed by the [Google APIs Terms of Service](https://developers.google.com/terms).
