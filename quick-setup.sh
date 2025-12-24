#!/bin/bash
# Quick setup script for SendSpinDroid development

set -e

echo "🚀 SendSpinDroid Quick Setup"
echo "============================"
echo ""

# Check if running on Fedora
if [ -f /etc/fedora-release ]; then
    echo "✓ Detected Fedora"
else
    echo "⚠️  Warning: This script is optimized for Fedora"
fi

# Install Go
echo ""
echo "📦 Installing Go..."
if ! command -v go &> /dev/null; then
    sudo dnf install -y golang
    echo "✓ Go installed"
else
    echo "✓ Go already installed: $(go version)"
fi

# Set up Go paths
export PATH=$PATH:$(go env GOPATH)/bin
if ! grep -q 'GOPATH' ~/.bashrc; then
    echo 'export PATH=$PATH:$(go env GOPATH)/bin' >> ~/.bashrc
    echo "✓ Added Go to PATH in ~/.bashrc"
fi

# Install gomobile
echo ""
echo "📱 Installing gomobile..."
if ! command -v gomobile &> /dev/null; then
    go install golang.org/x/mobile/cmd/gomobile@latest
    export PATH=$PATH:$(go env GOPATH)/bin
    echo "✓ gomobile installed"
else
    echo "✓ gomobile already installed"
fi

# Check for Android Studio via flatpak
echo ""
echo "🤖 Checking for Android Studio..."
if flatpak list | grep -q AndroidStudio; then
    echo "✓ Android Studio already installed"
else
    echo "📥 Installing Android Studio via Flatpak..."
    flatpak install -y flathub com.google.AndroidStudio
    echo "✓ Android Studio installed"
fi

echo ""
echo "✅ Basic setup complete!"
echo ""
echo "Next steps:"
echo "1. Launch Android Studio: flatpak run com.google.AndroidStudio"
echo "2. In Android Studio, go to Tools → SDK Manager"
echo "3. Install:"
echo "   - Android SDK Platform 34"
echo "   - Android SDK Platform 26"
echo "   - Android SDK Build-Tools"
echo "   - Android SDK Platform-Tools"
echo "   - Android NDK (Side by side)"
echo "   - Android Emulator"
echo ""
echo "4. Set ANDROID_HOME in a new terminal:"
echo "   export ANDROID_HOME=\$HOME/Android/Sdk"
echo "   export PATH=\$PATH:\$ANDROID_HOME/platform-tools"
echo ""
echo "5. Run: gomobile init"
echo ""
echo "6. Then build: ./build-gomobile.sh"
echo ""
echo "Or for quick testing without full setup, use option 2 in SETUP_AND_TEST.md"
