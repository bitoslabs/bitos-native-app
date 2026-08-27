#!/bin/sh

set -eu

echo "Checking C++ MediaCore"
./scripts/native-test.sh

if command -v xcodebuild >/dev/null 2>&1; then
    echo "Checking iOS project"
    ./scripts/ios-build.sh
fi

if { java -version >/dev/null 2>&1 || \
    [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" ]; } && \
    [ -f gradle/wrapper/gradle-wrapper.jar ]; then
    echo "Checking BusinessCore and Android"
    ./scripts/android-test.sh
else
    echo "Skipping Android/KMP: JDK or Gradle wrapper unavailable"
fi

echo "Checking service scaffold"
npm run test:services

if command -v docker >/dev/null 2>&1; then
    echo "Checking local infrastructure configuration"
    docker compose -f infra/compose.yaml config --quiet
fi

echo "All available checks passed"
