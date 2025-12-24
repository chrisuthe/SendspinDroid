# Setup and Testing Guide

Complete guide to set up your development environment and test SendSpinDroid.

## Prerequisites Installation

### 1. Install Go

On Fedora:
```bash
# Install Go
sudo dnf install golang

# Verify installation
go version  # Should be 1.21+

# Set up Go paths (add to ~/.bashrc)
echo 'export PATH=$PATH:$(go env GOPATH)/bin' >> ~/.bashrc
source ~/.bashrc
```

### 2. Install Android Studio & SDK

**Option A: Android Studio (Recommended - includes GUI emulator)**

```bash
# Download Android Studio from https://developer.android.com/studio
# Or use Flatpak
flatpak install flathub com.google.AndroidStudio

# Run Android Studio
flatpak run com.google.AndroidStudio
```

Then in Android Studio:
1. Go to **Tools → SDK Manager**
2. Install:
   - Android SDK Platform 34 (Android 14)
   - Android SDK Platform 26 (minimum for this app)
   - Android SDK Build-Tools
   - Android SDK Platform-Tools
   - Android SDK Command-line Tools
   - Android NDK (for gomobile)
   - Android Emulator

3. Set ANDROID_HOME:
```bash
# Add to ~/.bashrc
echo 'export ANDROID_HOME=$HOME/Android/Sdk' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/platform-tools' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/emulator' >> ~/.bashrc
source ~/.bashrc
```

**Option B: Command-line tools only**

```bash
# Download command-line tools
cd ~/Downloads
wget https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip

# Extract
mkdir -p ~/Android/cmdline-tools
unzip commandlinetools-linux-9477386_latest.zip -d ~/Android/cmdline-tools
mv ~/Android/cmdline-tools/cmdline-tools ~/Android/cmdline-tools/latest

# Set environment variables
echo 'export ANDROID_HOME=$HOME/Android' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/platform-tools' >> ~/.bashrc
source ~/.bashrc

# Install SDK components
sdkmanager "platform-tools" "platforms;android-34" "platforms;android-26" \
           "build-tools;34.0.0" "ndk;25.2.9519653" "emulator" \
           "system-images;android-34;google_apis;x86_64"
```

### 3. Install gomobile

```bash
# Install gomobile
go install golang.org/x/mobile/cmd/gomobile@latest

# Initialize gomobile with NDK
gomobile init -ndk $ANDROID_HOME/ndk/25.2.9519653
```

## Testing Options

### Option 1: Android Emulator (No Physical Device Needed)

#### Create an Emulator

**Using Android Studio:**
1. Open Android Studio
2. Go to **Tools → Device Manager**
3. Click **Create Device**
4. Select a device (e.g., Pixel 5)
5. Select system image (API 34, x86_64)
6. Click Finish

**Using Command Line:**
```bash
# Create AVD (Android Virtual Device)
avdmanager create avd -n SendSpinTest -k "system-images;android-34;google_apis;x86_64" -d pixel_5

# List available AVDs
avdmanager list avd
```

#### Launch Emulator

**From Android Studio:**
- Click the play button next to your device in Device Manager

**From Command Line:**
```bash
# Start emulator in background
emulator -avd SendSpinTest &

# Wait for it to boot
adb wait-for-device
```

### Option 2: Physical Android Device

1. **Enable Developer Options** on your phone:
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times
   - Go back to Settings → System → Developer Options

2. **Enable USB Debugging**:
   - In Developer Options, enable "USB Debugging"

3. **Connect via USB**:
   ```bash
   # Connect phone via USB
   adb devices  # Should show your device
   ```

4. **Or connect via WiFi** (Android 11+):
   ```bash
   # First connect via USB, then:
   adb tcpip 5555
   adb connect <phone-ip>:5555
   # Now you can disconnect USB
   ```

### Option 3: Use Waydroid (Linux Container)

On Fedora, you can use Waydroid to run Android in a container:

```bash
# Install Waydroid
sudo dnf install waydroid

# Initialize
sudo waydroid init

# Start container
sudo systemctl start waydroid-container

# Launch Waydroid
waydroid show-full-ui &

# Wait for boot
adb wait-for-device
```

## Building and Installing

### Step 1: Build the Go Library

```bash
cd /home/chris/Documents/SendSpinDroid

# Build AAR
./build-gomobile.sh
```

Expected output:
```
Building SendSpin Player with gomobile...
Fetching Go dependencies...
Building AAR with gomobile bind...
Build complete! AAR created at: android/app/libs/player.aar
```

If you get errors:
- Ensure Go is installed: `go version`
- Ensure gomobile is installed: `gomobile version`
- Ensure ANDROID_HOME is set: `echo $ANDROID_HOME`

### Step 2: Build the Android App

```bash
cd android

# First time: make gradlew executable
chmod +x gradlew

# Build debug APK
./gradlew assembleDebug
```

The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

### Step 3: Install on Emulator/Device

```bash
# Make sure device is connected
adb devices

# Install
./gradlew installDebug
```

Or manually:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Testing the App

### 1. Launch the App

**On Emulator/Device:**
- Find "SendSpin Player" in app drawer
- Tap to launch

**From Command Line:**
```bash
adb shell am start -n com.sendspindroid/.MainActivity
```

### 2. Test the UI

The app currently shows a skeleton/demo UI:

✅ **What works:**
- App launches
- "Discover Servers" button (adds test server)
- Server list displays
- Tap server to "connect" (UI only)
- Playback controls (UI state only)
- Volume slider (UI only)
- All UI elements render correctly

⚠️ **What doesn't work yet:**
- Real server discovery (needs sendspin-go integration)
- Actual connection to servers
- Real audio playback

### 3. View Logs

```bash
# Watch all app logs
adb logcat | grep -E "SendSpinDroid|MainActivity"

# Clear logs first
adb logcat -c

# Then watch
adb logcat -s SendSpinDroid:* MainActivity:*
```

### 4. Test Scenarios

1. **Launch Test:**
   - App should open without crashing
   - Status should show "Not Connected"

2. **Discovery Test:**
   - Tap "Discover Servers"
   - A test server should appear in the list
   - Check logs for: "Starting discovery"

3. **Connection Test:**
   - Tap on the test server
   - Status should change to "Connected to Test Server"
   - Playback controls should enable
   - Check logs for: "Server selected: Test Server"

4. **Playback Controls Test:**
   - Tap Play → "Now Playing" should show "Playing"
   - Tap Pause → Should show "Paused"
   - Tap Stop → Should show "Stopped"

5. **Volume Test:**
   - Move volume slider
   - Check logs for volume changes

## Troubleshooting

### Build Issues

**Go not found:**
```bash
sudo dnf install golang
go version
```

**gomobile not found:**
```bash
go install golang.org/x/mobile/cmd/gomobile@latest
export PATH=$PATH:$(go env GOPATH)/bin
gomobile version
```

**ANDROID_HOME not set:**
```bash
export ANDROID_HOME=$HOME/Android/Sdk
# Add to ~/.bashrc for persistence
```

**NDK not found:**
```bash
# Install via SDK Manager or:
sdkmanager "ndk;25.2.9519653"
gomobile init -ndk $ANDROID_HOME/ndk/25.2.9519653
```

### Emulator Issues

**Emulator won't start:**
```bash
# Check virtualization
sudo dnf install qemu-kvm libvirt
sudo systemctl start libvirtd

# Or use ARM image (slower)
sdkmanager "system-images;android-34;google_apis;arm64-v8a"
```

**Emulator is slow:**
- Enable KVM acceleration (Linux only)
- Reduce RAM in AVD settings
- Use x86_64 images instead of ARM

### Device Issues

**Device not detected:**
```bash
# Check connection
adb devices

# Restart ADB server
adb kill-server
adb start-server

# Check USB rules (Linux)
sudo usermod -aG plugdev $USER
```

**Permission denied:**
- Make sure USB debugging is enabled
- Accept authorization prompt on device

### App Issues

**App crashes on launch:**
```bash
# Check crash logs
adb logcat -s AndroidRuntime:E

# Common issues:
# - AAR not built (run ./build-gomobile.sh)
# - Incompatible API level (check minSdk in build.gradle)
```

**Can't find app after install:**
```bash
# List installed packages
adb shell pm list packages | grep sendspindroid

# Uninstall and reinstall
adb uninstall com.sendspindroid
cd android
./gradlew installDebug
```

## Quick Test Script

Save this as `test.sh`:

```bash
#!/bin/bash
set -e

echo "🔨 Building Go library..."
./build-gomobile.sh

echo "📱 Building Android app..."
cd android
./gradlew assembleDebug

echo "🚀 Installing on device..."
./gradlew installDebug

echo "📋 Watching logs..."
adb logcat -c
adb shell am start -n com.sendspindroid/.MainActivity
adb logcat -s SendSpinDroid:* MainActivity:*
```

Then:
```bash
chmod +x test.sh
./test.sh
```

## Next Steps After Testing UI

Once you confirm the skeleton app works:

1. **Follow [INTEGRATION.md](INTEGRATION.md)** to add sendspin-go
2. **Set up a SendSpin server** for real testing
3. **Implement audio playback**
4. **Test on real network**

## Resources

- [Android Emulator Guide](https://developer.android.com/studio/run/emulator)
- [ADB Documentation](https://developer.android.com/studio/command-line/adb)
- [gomobile Documentation](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile)
- [Waydroid Setup](https://waydro.id/)
