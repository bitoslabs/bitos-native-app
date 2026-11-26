#!/bin/sh

# Build the debug APK and install it onto a connected Android device.
# Set ANDROID_SERIAL=<id> when multiple devices are attached.
# Set REINSTALL=1 to uninstall an incompatible signature build first.

set -eu

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

if command -v adb >/dev/null 2>&1; then
    adb_bin=$(command -v adb)
elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    adb_bin="$ANDROID_HOME/platform-tools/adb"
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then
    adb_bin="$ANDROID_SDK_ROOT/platform-tools/adb"
elif [ -x "/Users/${USER:-__missing__}/Library/Android/sdk/platform-tools/adb" ]; then
    adb_bin="/Users/$USER/Library/Android/sdk/platform-tools/adb"
else
    echo "error: adb not found; install Android platform-tools or set ANDROID_HOME." >&2
    exit 2
fi

if [ -n "${ANDROID_SERIAL:-}" ]; then
    serial="$ANDROID_SERIAL"
else
    devices=$("$adb_bin" devices | awk 'NR>1 && $2=="device" { print $1 }')
    count=$(printf '%s\n' "$devices" | sed '/^$/d' | wc -l | tr -d ' ')
    if [ "$count" -eq 0 ]; then
        echo "error: no connected Android device found (check USB debugging / adb devices)." >&2
        exit 1
    elif [ "$count" -gt 1 ]; then
        echo "error: multiple Android devices connected; set ANDROID_SERIAL=<id>:" >&2
        printf '%s\n' "$devices" >&2
        exit 1
    fi
    serial=$(printf '%s\n' "$devices" | head -n 1)
fi

./scripts/android-apk.sh "$@"
apk_path="$repo_root/apps/android/build/outputs/apk/debug/android-debug.apk"

echo "==> Installing $apk_path onto $serial"
if [ "${REINSTALL:-0}" = "1" ]; then
    "$adb_bin" -s "$serial" uninstall space.bitos.app.debug >/dev/null 2>&1 || true
fi

if ! install_out=$("$adb_bin" -s "$serial" install -r "$apk_path" 2>&1); then
    printf '%s\n' "$install_out" >&2
    if printf '%s' "$install_out" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
        echo "hint: existing space.bitos.app.debug has a different signing key. Re-run with REINSTALL=1 make android-install" >&2
    fi
    exit 1
fi
printf '%s\n' "$install_out"

echo "==> Launching space.bitos.app.debug on $serial"
"$adb_bin" -s "$serial" shell monkey -p space.bitos.app.debug -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
