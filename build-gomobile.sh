#!/bin/bash

# Build script for creating gomobile AAR from Go player package

set -e

echo "Building SendSpin Player with gomobile..."

# Check if gomobile is installed
if ! command -v gomobile &> /dev/null; then
    echo "gomobile not found. Installing..."
    go install golang.org/x/mobile/cmd/gomobile@latest
    gomobile init
fi

# Navigate to Go player directory
cd go-player

echo "Fetching Go dependencies..."
go mod tidy

echo "Building AAR with gomobile bind..."
gomobile bind -target=android -androidapi=26 -o ../android/app/libs/player.aar .

echo "Build complete! AAR created at: android/app/libs/player.aar"
echo ""
echo "You can now build the Android app:"
echo "  cd android"
echo "  ./gradlew assembleDebug"
