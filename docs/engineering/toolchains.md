# Toolchains and bootstrap

## Pinned build window

| Target | Required | Repository pin |
|---|---|---|
| Kotlin/KMP | Kotlin Multiplatform plugin | 2.4.10 |
| Gradle | Compatible with Kotlin 2.4.10 | 9.5.0 |
| Android | AGP with built-in Kotlin and Android-KMP plugin | 9.1.0 |
| Android UI | Compose BOM (latest API 36-compatible line) | 2026.06.00 |
| Android SDK | compile/target SDK | 36 |
| Java | Gradle/AGP runtime | JDK 17 or newer supported by AGP |
| Apple | KMP 2.4.10 compatibility | Xcode 26.4 |
| Swift app | Swift language mode | Swift 6 |
| C++ | MediaCore language level | C++20 |
| Node | Service development | Node 24 or later |
| Container | Local infrastructure | Docker Compose v2 |

The Kotlin compatibility table, not “latest wins,” controls the Kotlin/Gradle/AGP/Xcode combination. Upgrade these four together in one dedicated pull request with a release-build matrix.

## Current workstation note

At scaffold time this machine had Xcode 16.1, Android SDK 36, and Android Studio's bundled JDK. The Swift shell, Android app, Android-hosted BusinessCore tests, C++ MediaCore, and Kotlin/Native compile/link lanes work locally. Running Kotlin iOS simulator tests is unavailable in the restricted environment; CI pins macOS 26 and Xcode 26.4 for that lane.

## Bootstrap

1. Install the required Xcode and accept its license.
2. Install JDK 17+ and ensure `java -version` succeeds in the same shell.
3. Install Android Studio and SDK 36; create `local.properties` with `sdk.dir` if Android Studio does not.
4. Validate the committed official wrapper with Gradle's wrapper-validation action or the published checksum when updating it.
5. Run `./scripts/android-test.sh`; CI additionally runs `iosSimulatorArm64Test` under Xcode 26.4.
6. Run `npm ci` from the checked lockfile.
7. Run `make doctor`, then `make check`.

The wrapper JAR is generated from the official Gradle 9.5.0 source/distribution and validated in CI. Never replace it with a third-party binary.

## Upgrade rules

- Use stable releases only in production branches.
- Read Kotlin/Native and AGP compatibility notes before changing versions.
- Never use dynamic Gradle/Maven/npm versions or mutable production container tags.
- Upgrade one toolchain family per pull request unless compatibility requires a set.
- Capture before/after cold build, incremental build, app launch and binary size.
- Keep the previous release branch buildable until staged rollout finishes.

## Useful commands

```text
make doctor          report missing tools
make native-test     compile/test C++ MediaCore
make ios-build       compile the SwiftUI app without signing
make android-test    test BusinessCore and Android
make service-test    run Node service tests without external services
make infra-check     validate Docker Compose structure
make check           run every available local lane
```

Primary version references:

- [Kotlin Multiplatform compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
- [AGP built-in Kotlin migration](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Android-KMP AGP 9 migration](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html)
- [Gradle releases](https://gradle.org/releases/)
