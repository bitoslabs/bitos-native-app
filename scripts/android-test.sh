#!/bin/sh

set -eu

exec ./scripts/android-gradle.sh \
    :shared:business-core:testAndroidHostTest \
    :apps:android:testDebugUnitTest \
    "$@"
