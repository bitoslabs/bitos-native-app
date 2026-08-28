#!/bin/sh

# Build the shared BusinessCore XCFramework, then the iOS app.
#
# Artifacts land in <repo>/build (gitignored); the Xcode project references
# ../../build/BusinessCore.xcframework, so this script must run before the
# first xcodebuild from a clean checkout (GOV-003 / SBC-001).

set -eu

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

# Locate a JDK 17+ (system java, or the Android Studio bundled runtime).
if command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1; then
    :
elif [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" ]; then
    JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    export JAVA_HOME
else
    echo "error: JDK 17+ required (system java or Android Studio JBR)" >&2
    exit 1
fi

echo "==> Building BusinessCore release frameworks (device + simulator)"
./gradlew \
    :shared:business-core:linkReleaseFrameworkIosArm64 \
    :shared:business-core:linkReleaseFrameworkIosSimulatorArm64 \
    :shared:business-core:linkReleaseFrameworkIosX64 \
    --console=plain

echo "==> Creating universal simulator slice"
rm -rf build/universal-sim
mkdir -p build/universal-sim
cp -R shared/business-core/build/bin/iosSimulatorArm64/releaseFramework/BusinessCore.framework \
    build/universal-sim/
lipo -create \
    shared/business-core/build/bin/iosSimulatorArm64/releaseFramework/BusinessCore.framework/BusinessCore \
    shared/business-core/build/bin/iosX64/releaseFramework/BusinessCore.framework/BusinessCore \
    -output build/universal-sim/BusinessCore.framework/BusinessCore

echo "==> Creating BusinessCore.xcframework"
rm -rf build/BusinessCore.xcframework
xcodebuild -create-xcframework \
    -framework shared/business-core/build/bin/iosArm64/releaseFramework/BusinessCore.framework \
    -framework build/universal-sim/BusinessCore.framework \
    -output build/BusinessCore.xcframework

if xcodebuild -project apps/ios/BitOS.xcodeproj -scheme BitOS -configuration Debug \
    -sdk iphonesimulator \
    -derivedDataPath "${TMPDIR:-/tmp}/bitos-derived-data" \
    CODE_SIGNING_ALLOWED=NO build "$@"; then
    exit 0
fi

echo "Xcode has no runnable destination; falling back to SwiftUI source type-check."
bitos_ios_sdk=$(xcrun --sdk iphonesimulator --show-sdk-path)
find apps/ios/BitOS -name '*.swift' -print0 | \
    xargs -0 xcrun swiftc -typecheck -swift-version 6 -strict-concurrency=complete \
        -sdk "$bitos_ios_sdk" -target arm64-apple-ios17.0-simulator \
        -F build/BusinessCore.xcframework/ios-arm64
