#!/bin/sh

# Assemble the installable debug APK without signing it for distribution.

set -eu

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

./scripts/android-gradle.sh :apps:android:assembleDebug "$@"

apk_path=apps/android/build/outputs/apk/debug/android-debug.apk
if [ ! -f "$apk_path" ]; then
    echo "Android build completed but the debug APK was not produced: $apk_path" >&2
    exit 1
fi

printf 'Debug APK: %s/%s\n' "$repo_root" "$apk_path"
