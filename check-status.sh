#!/bin/bash
# Check development environment status

echo "========================================="
echo "  SendSpinDroid Environment Status"
echo "========================================="
echo ""

# Check Go
echo -n "Go:           "
if command -v go &> /dev/null; then
    echo "✓ $(go version)"
else
    echo "✗ NOT INSTALLED"
    echo "  Install: sudo dnf install golang"
fi

# Check gomobile
echo -n "gomobile:     "
if command -v gomobile &> /dev/null; then
    echo "✓ INSTALLED"
else
    echo "✗ NOT INSTALLED"
    echo "  Install: go install golang.org/x/mobile/cmd/gomobile@latest"
fi

# Check ADB
echo -n "ADB:          "
if command -v adb &> /dev/null; then
    echo "✓ $(adb --version 2>&1 | head -1)"
else
    echo "✗ NOT INSTALLED"
    echo "  Install Android SDK or use: sudo dnf install android-tools"
fi

# Check Android Studio
echo -n "Android Studio: "
if flatpak list 2>/dev/null | grep -q AndroidStudio; then
    echo "✓ INSTALLED (flatpak)"
else
    echo "✗ NOT INSTALLED"
    echo "  Install: flatpak install flathub com.google.AndroidStudio"
fi

# Check ANDROID_HOME
echo -n "ANDROID_HOME: "
if [ -n "$ANDROID_HOME" ]; then
    echo "✓ $ANDROID_HOME"
else
    echo "✗ NOT SET"
    echo "  Set: export ANDROID_HOME=\$HOME/Android/Sdk"
fi

# Check if Android SDK exists
echo -n "Android SDK:  "
if [ -d "$HOME/Android/Sdk" ] || [ -d "$HOME/Android" ]; then
    echo "✓ Found at $HOME/Android"
elif [ -d "$ANDROID_HOME" ]; then
    echo "✓ Found at $ANDROID_HOME"
else
    echo "✗ NOT FOUND"
fi

# Check for NDK
echo -n "Android NDK:  "
if [ -d "$HOME/Android/Sdk/ndk" ] || [ -d "$ANDROID_HOME/ndk" ]; then
    echo "✓ INSTALLED"
else
    echo "✗ NOT FOUND"
    echo "  Install via Android Studio SDK Manager"
fi

# Check if AAR is built
echo -n "Go AAR:       "
if [ -f "android/app/libs/player.aar" ]; then
    size=$(du -h android/app/libs/player.aar | cut -f1)
    echo "✓ Built ($size)"
else
    echo "✗ NOT BUILT"
    echo "  Build: ./build-gomobile.sh"
fi

# Check for connected devices
echo -n "Devices:      "
if command -v adb &> /dev/null; then
    device_count=$(adb devices | grep -v "List" | grep "device$" | wc -l)
    if [ $device_count -gt 0 ]; then
        echo "✓ $device_count connected"
        adb devices | grep "device$"
    else
        echo "✗ No devices connected"
        echo "  Connect a device or start an emulator"
    fi
else
    echo "? ADB not installed"
fi

echo ""
echo "========================================="
echo "Recommended next steps:"
echo ""

# Determine what to do next
if ! command -v go &> /dev/null; then
    echo "1. Install Go: sudo dnf install golang"
elif ! command -v gomobile &> /dev/null; then
    echo "1. Install gomobile: go install golang.org/x/mobile/cmd/gomobile@latest"
elif [ -z "$ANDROID_HOME" ]; then
    echo "1. Install Android SDK (see TESTING_OPTIONS.md)"
elif [ ! -f "android/app/libs/player.aar" ]; then
    echo "1. Build the Go library: ./build-gomobile.sh"
else
    echo "✅ Ready to build!"
    echo "   Run: cd android && ./gradlew assembleDebug"
fi

echo ""
echo "For detailed setup: see TESTING_OPTIONS.md"
echo "========================================="
