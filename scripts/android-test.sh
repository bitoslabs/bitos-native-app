#!/bin/sh

set -eu

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    bitos_java_home=$JAVA_HOME
elif command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1; then
    bitos_java_home=$(/usr/libexec/java_home 2>/dev/null || true)
elif [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" ]; then
    bitos_java_home="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
else
    echo "JDK 17+ was not found; install it or Android Studio." >&2
    exit 2
fi

if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
    bitos_android_sdk=$ANDROID_HOME
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
    bitos_android_sdk=$ANDROID_SDK_ROOT
elif [ -d "${USERPROFILE:-__missing__}/AppData/Local/Android/Sdk" ]; then
    bitos_android_sdk="$USERPROFILE/AppData/Local/Android/Sdk"
elif [ -d "/Users/${USER:-__missing__}/Library/Android/sdk" ]; then
    bitos_android_sdk="/Users/$USER/Library/Android/sdk"
else
    echo "Android SDK 36 was not found; set ANDROID_HOME." >&2
    exit 2
fi

export JAVA_HOME=$bitos_java_home
export ANDROID_HOME=$bitos_android_sdk

exec ./gradlew :shared:business-core:testAndroidHostTest :apps:android:testDebugUnitTest "$@"

