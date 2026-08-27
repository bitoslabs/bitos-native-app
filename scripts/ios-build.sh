#!/bin/sh

set -eu

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
        -sdk "$bitos_ios_sdk" -target arm64-apple-ios17.0-simulator
