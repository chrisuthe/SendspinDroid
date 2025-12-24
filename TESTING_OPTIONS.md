# Testing Options - Choose Your Path

Three ways to test SendSpinDroid, from easiest to most complete.

## 🎯 Recommended Paths

### Path 1: Fastest - Skip Build, Just Review Code ⚡
**Time: 5 minutes**

Just review the code structure without building:
```bash
cd /home/chris/Documents/SendSpinDroid

# Browse the code
cat go-player/player.go
cat android/app/src/main/java/com/sendspindroid/MainActivity.kt

# Review the structure
tree -L 3
```

**Best for:** Understanding the architecture before committing to full setup.

### Path 2: Quick Test - Physical Device 📱
**Time: 30 minutes**

Use your Android phone (no emulator needed):

```bash
# 1. Install dependencies
./quick-setup.sh

# 2. Set up Android SDK (minimal)
# Download just the command-line tools
cd ~/Downloads
wget https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip
mkdir -p ~/Android/cmdline-tools
unzip commandlinetools-linux-9477386_latest.zip -d ~/Android/cmdline-tools
mv ~/Android/cmdline-tools/cmdline-tools ~/Android/cmdline-tools/latest

# 3. Set environment
export ANDROID_HOME=$HOME/Android
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
export PATH=$PATH:$ANDROID_HOME/platform-tools

# 4. Install SDK components
sdkmanager "platform-tools" "platforms;android-34" "platforms;android-26" \
           "build-tools;34.0.0" "ndk;25.2.9519653"

# 5. Initialize gomobile
gomobile init

# 6. Build
./build-gomobile.sh
cd android
./gradlew assembleDebug

# 7. Connect your phone via USB and enable USB debugging
# Then install:
adb devices
./gradlew installDebug
```

**Best for:** Quick testing with hardware you already have.

### Path 3: Full Setup - Android Studio + Emulator 🖥️
**Time: 1-2 hours**

Complete development environment:

```bash
# 1. Run quick setup
./quick-setup.sh

# 2. Launch Android Studio
flatpak run com.google.AndroidStudio

# 3. In Android Studio:
#    - Tools → SDK Manager
#    - Install all recommended components
#    - Tools → Device Manager
#    - Create a Pixel 5 emulator (API 34)

# 4. Set environment (add to ~/.bashrc)
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
export PATH=$PATH:$ANDROID_HOME/emulator
source ~/.bashrc

# 5. Initialize gomobile
gomobile init

# 6. Build everything
./build-gomobile.sh
cd android
./gradlew assembleDebug

# 7. Start emulator and install
emulator -avd Pixel_5_API_34 &
adb wait-for-device
./gradlew installDebug
```

**Best for:** Full development with debugging and UI tools.

## 🔥 Ultra-Quick Option: Docker (Experimental)

If you want to try building without installing everything locally:

```dockerfile
# Create Dockerfile
FROM ubuntu:22.04

RUN apt-get update && apt-get install -y \
    golang-go \
    wget \
    unzip \
    openjdk-17-jdk

# Set up Android SDK
ENV ANDROID_HOME=/opt/android-sdk
RUN mkdir -p $ANDROID_HOME
# ... (would need full setup)

# This is complex - stick with native install
```

## 📊 Comparison

| Method | Time | Complexity | What You Get |
|--------|------|------------|--------------|
| Path 1 | 5 min | ⭐ | Code review only |
| Path 2 | 30 min | ⭐⭐ | Real device testing |
| Path 3 | 1-2 hrs | ⭐⭐⭐ | Full dev environment |

## 🎬 Recommended: Start with Path 2

1. Run the quick setup script:
   ```bash
   cd /home/chris/Documents/SendSpinDroid
   ./quick-setup.sh
   ```

2. Follow the minimal Android SDK setup from Path 2

3. Enable USB debugging on your phone:
   - Settings → About Phone → Tap "Build Number" 7 times
   - Settings → System → Developer Options → Enable "USB Debugging"
   - Connect phone via USB

4. Build and install:
   ```bash
   ./build-gomobile.sh
   cd android
   ./gradlew installDebug
   ```

## ❓ What If I Don't Have a Phone?

Use **Waydroid** (Android in a container on Linux):

```bash
# Install Waydroid
sudo dnf install waydroid

# Initialize
sudo waydroid init

# Start
sudo systemctl start waydroid-container
waydroid show-full-ui &

# Wait for boot
adb wait-for-device

# Install app
cd android
./gradlew installDebug
```

## 🆘 Need Help?

See [SETUP_AND_TEST.md](SETUP_AND_TEST.md) for detailed instructions and troubleshooting.

## ✅ Quick Status Check

Run this to see what you have:

```bash
echo "=== Current Status ==="
echo "Go: $(command -v go && go version || echo 'NOT INSTALLED')"
echo "gomobile: $(command -v gomobile && echo 'INSTALLED' || echo 'NOT INSTALLED')"
echo "ADB: $(command -v adb && adb version || echo 'NOT INSTALLED')"
echo "Android Studio: $(flatpak list | grep AndroidStudio || echo 'NOT INSTALLED')"
echo "ANDROID_HOME: ${ANDROID_HOME:-'NOT SET'}"
```

Choose your path and let's get testing! 🚀
