#!/bin/sh

set -u

status=0

check() {
    label=$1
    command_name=$2
    if command -v "$command_name" >/dev/null 2>&1; then
        printf '%-20s %s\n' "$label" "$(command -v "$command_name")"
    else
        printf '%-20s %s\n' "$label" "MISSING"
        status=1
    fi
}

echo "BitOS toolchain doctor"
check "Xcode" xcodebuild
check "Swift" swift
check "C++ compiler" clang++
check "Java" java
check "Node" node
check "npm" npm
check "Docker" docker

if ! java -version >/dev/null 2>&1; then
    if [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" ]; then
        echo "Java runtime         Android Studio bundled JDK"
    else
        echo "Java runtime         MISSING (JDK 17+ required)"
        status=1
    fi
fi
if ! command -v cmake >/dev/null 2>&1; then
    echo "CMake                optional; clang++ fallback active"
fi
if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
    echo "Gradle wrapper JAR is missing; follow docs/engineering/toolchains.md."
    status=1
fi

if [ "$status" -ne 0 ]; then
    echo "Some targets are unavailable. Other installed target checks can still run via make check."
fi

exit 0
