#!/bin/sh

# Remove repository-generated build outputs only. Global tool and package caches
# (for example ~/.gradle, Android SDKs, and npm's cache) are intentionally kept.

set -eu

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

if [ -f gradle/wrapper/gradle-wrapper.jar ] && \
    { java -version >/dev/null 2>&1 || \
      [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" ]; }; then
    echo "==> Cleaning Gradle build outputs"
    ./scripts/android-gradle.sh clean
else
    echo "==> Skipping Gradle clean: JDK or Gradle wrapper unavailable"
fi

echo "==> Removing generated native and iOS framework outputs"
rm -rf build-native build

if command -v xcodebuild >/dev/null 2>&1; then
    echo "==> Cleaning Xcode build outputs"
    xcodebuild -project apps/ios/BitOS.xcodeproj -scheme BitOS clean >/dev/null
else
    echo "==> Skipping Xcode clean: xcodebuild unavailable"
fi
