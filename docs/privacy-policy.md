# Privacy Policy for SendSpin Player

**Last updated:** January 2026

## Overview

SendSpin Player is an audio player app that connects to SendSpin servers for synchronized music playback. This privacy policy explains what data the app accesses, stores, and transmits.

## Data Collection

**SendSpin Player does not collect any personal data.**

We do not:
- Collect personal information
- Use analytics or tracking services
- Store data on external servers
- Share any data with third parties
- Display advertisements

## Data Storage

The app stores the following data **locally on your device only**:

- **Server addresses:** The SendSpin server URLs you connect to
- **Playback preferences:** Settings like volume, buffer size, and display options
- **Connection history:** Recently connected servers for convenience

This data never leaves your device and is not accessible to us or any third party.

## Network Connections

SendSpin Player connects to:

1. **SendSpin servers you specify:** The app connects via WebSocket to servers you manually enter or discover on your local network. These connections transmit:
   - Audio stream data
   - Playback synchronization signals
   - Track metadata (title, artist, album artwork)

2. **Local network discovery:** The app uses mDNS/Zeroconf to discover SendSpin servers on your local network. This does not transmit any data outside your local network.

**Important:** All server connections are initiated by you. The app does not connect to any servers without your explicit action.

## Permissions

The app requests the following Android permissions:

| Permission | Purpose |
|------------|---------|
| Internet | Connect to SendSpin servers |
| Network State | Check connectivity before connecting |
| WiFi State | Detect local network for server discovery |
| Foreground Service | Continue playback when app is in background |
| Notifications | Show playback controls on lock screen |
| Wake Lock | Keep audio playing when screen is off |

## Data Security

- Server addresses are stored in Android's SharedPreferences
- No encryption is applied to locally stored preferences (they contain no sensitive data)
- Network connections support both secure (wss://) and standard (ws://) WebSocket protocols

## Children's Privacy

SendSpin Player does not collect any personal information and is safe for users of all ages. The app does not contain content directed at children under 13.

## Changes to This Policy

We may update this privacy policy from time to time. Any changes will be posted on this page with an updated revision date.

## Contact

If you have questions about this privacy policy, please open an issue on our GitHub repository:

[https://github.com/chrisuthe/SendSpinDroid/issues](https://github.com/chrisuthe/SendSpinDroid/issues)

## Open Source

SendSpin Player is open source software. You can review the complete source code to verify our privacy practices:

[https://github.com/chrisuthe/SendSpinDroid](https://github.com/chrisuthe/SendSpinDroid)
