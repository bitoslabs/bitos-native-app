#!/bin/sh

# Build the iOS app for a connected physical iOS device and install it via devicectl.
# Set IOS_UDID=<udid> to target a specific device.

set -eu

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

if ! xcodebuild -version >/dev/null 2>&1; then
    if [ -d "/Applications/Xcode.app/Contents/Developer" ]; then
        export DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer"
    else
        echo "error: Xcode.app is required to build and install onto an iOS device." >&2
        exit 2
    fi
fi

if [ -n "${IOS_UDID:-}" ]; then
    udid="$IOS_UDID"
else
    # Pick the first connected iPhone/iPad from devicectl.
    udid=$(xcrun devicectl list devices 2>/dev/null | awk '/connected/ && $3 ~ /^[0-9A-Fa-f-]+$/ { print $3; exit }')
    if [ -z "$udid" ]; then
        echo "error: no connected iOS device found (unlock device, trust this Mac, or set IOS_UDID=<udid>)." >&2
        exit 1
    fi
fi

if [ ! -d "$repo_root/build/BusinessCore.xcframework" ]; then
    echo "==> Building BusinessCore.xcframework"
    ./scripts/ios-build.sh || true
fi

derived_data="${TMPDIR:-/tmp}/bitos-ios-device-derived"
echo "==> Building BitOS (Debug) for iOS device $udid"
xcodebuild \
    -project apps/ios/BitOS.xcodeproj \
    -scheme BitOS \
    -configuration Debug \
    -destination "id=$udid" \
    -derivedDataPath "$derived_data" \
    -allowProvisioningUpdates \
    build "$@"

app_path="$derived_data/Build/Products/Debug-iphoneos/BitOS.app"
if [ ! -d "$app_path" ]; then
    echo "error: built BitOS.app not found at $app_path" >&2
    exit 1
fi

echo "==> Installing $app_path onto $udid"
xcrun devicectl device install app --device "$udid" "$app_path"
