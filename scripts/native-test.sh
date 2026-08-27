#!/bin/sh

set -eu

if command -v cmake >/dev/null 2>&1; then
    cmake -S . -B build-native -DBITOS_BUILD_TESTS=ON
    cmake --build build-native --parallel
    exec ctest --test-dir build-native --output-on-failure
fi

if command -v clang++ >/dev/null 2>&1; then
    media_test_binary="${TMPDIR:-/tmp}/bitos-media-core-tests"
    clang++ -std=c++20 -Wall -Wextra -Wpedantic -Werror \
        -I native/media-core/include \
        native/media-core/src/timeline.cpp \
        native/media-core/src/bitos_media.cpp \
        native/media-core/tests/timeline_test.cpp \
        -o "$media_test_binary"
    exec "$media_test_binary"
fi

echo "C++ MediaCore requires CMake or clang++." >&2
exit 2

