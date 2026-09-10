# Developer Guide

This guide gets a local BitOS checkout open, built, and running. Start with the platform you are changing; the other lanes are not required for everyday feature work.

## 1. Prerequisites

Install the toolchain versions in [toolchains.md](toolchains.md): Xcode 26.4 (Swift 6), Android Studio with Android SDK 36, JDK 17 or newer, Node 24 or newer, Docker Compose v2, and either CMake or `clang++` for MediaCore.

Verify the workstation from the repository root:

```sh
make doctor
```

The doctor reports unavailable tools but deliberately does not fail the command. Use the result to decide which local lanes can run.

On macOS, Android Studio's bundled JDK is sufficient. If `/usr/bin/java -version` reports that no Java runtime is installed, select **Settings/Preferences → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK → Embedded JDK** in Android Studio. For terminal commands, either use the repository launcher (which detects the bundled JDK) or set it for the current shell:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

Add those two `export` commands to `~/.zshrc` only if you want that JDK to be the default for all Terminal sessions.

## 2. First checkout

```sh
git clone <repository-url>
cd app-platform-nostr
npm ci
make doctor
```

Install Android SDK Platform 36 and a matching emulator system image in Android Studio's SDK Manager. If Gradle cannot locate the SDK, create an untracked `local.properties` in the repository root with the SDK location:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

Do not add credentials, signing files, `local.properties`, or generated build output to Git.

## 3. Open and run Android

1. Open the repository root (`app-platform-nostr`) in Android Studio. It is the Gradle project root; do not open `apps/android` by itself.
2. Allow Gradle sync to finish and select the `apps:android` run configuration (or create an Android App configuration for module `apps.android`).
3. Start an API 36 emulator or connect an Android device with USB debugging enabled.
4. Press **Run**, or install from the terminal:

```sh
./gradlew :apps:android:installDebug
```

The debug application ID is `space.bitos.app.debug`. Camera and microphone flows need a real device or an emulator that exposes those capabilities; grant the runtime permissions when prompted.

Use these targeted checks while working on Android or shared BusinessCore code:

```sh
make android-test
./gradlew :apps:android:testDebugUnitTest
./gradlew :shared:business-core:testAndroidHostTest
```

## 4. Open and run iOS

The iOS app imports the BusinessCore XCFramework. Generate it before the first Xcode build in a clean checkout:

```sh
make ios-build
```

This also builds the SwiftUI application without signing. It requires a JDK because it compiles the Kotlin Multiplatform framework first.

Then open [BitOS.xcodeproj](../../apps/ios/BitOS.xcodeproj), choose the `BitOS` scheme, select an iOS simulator or a signed device, and press **Run**. For terminal-only validation, rerun `make ios-build`.

When running on a physical device, configure your own development team and signing in Xcode; never commit signing changes, provisioning profiles, or private keys.

## 5. Build application artifacts

Use the repository commands when you need a local app artifact rather than an
IDE run. Both commands build unsigned debug artifacts suitable for local
development; they do not create store-ready, signed releases.

```sh
make build-ios
make build-android-apk
```

`make build-ios` is the preferred spelling of the existing `make ios-build`
command. `make build-android-apk` writes the APK to:

```text
apps/android/build/outputs/apk/debug/android-debug.apk
```

### Android GitHub releases

Android's current app version is `0.1.0` (`versionName`) with install/build
number `1` (`versionCode`), both in
[`gradle.properties`](../../gradle.properties). The
iOS project currently mirrors these as marketing version `0.1.0` and build
number `1`.

Pushing a version tag in the form `v<version>` (for example, `v0.1.0`) runs
the **Android APK Release** workflow. It refuses a tag whose version differs
from Android's `versionName`, builds a minified signed release APK, publishes
the APK and SHA-256 file as workflow artifacts, and attaches both to the
GitHub Release. Increment `versionCode` for every installable Android release;
keep `versionName` and the release tag aligned.

Before the first release, configure these repository Actions secrets. They are
read only by the release job and are never committed:

- `ANDROID_RELEASE_KEYSTORE_BASE64` — base64 encoding of the upload keystore.
- `ANDROID_RELEASE_KEYSTORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

The workflow deliberately fails if any signing secret is missing, so it can
never publish an unsigned APK as a release.

Debug builds emit native lifecycle diagnostics without logging user content,
keys, Nostr events, or deep-link values. View them with:

```sh
adb logcat BitOS.Activity:D BitOS.Process:D '*:S'
```

On iOS, use Xcode's debug console or the macOS Console app, filtering for the
app's subsystem and the `activity` category.

## 6. Clean generated build caches

When an interrupted build or branch switch leaves stale local artifacts, run:

```sh
make clean-cache
```

This removes only repository-generated Gradle, native, iOS, and BusinessCore
framework outputs. It intentionally preserves global tool and package caches,
including the Android SDK, `~/.gradle`, and npm's cache. `make clean` remains
an alias for this command.

## 7. Run local services and infrastructure

The native clients can be developed independently. When work needs the local service scaffold, make a local-only environment file and start Compose:

```sh
cp infra/.env.example infra/.env
# Replace the example local credentials in infra/.env.
docker compose -f infra/compose.yaml up --build
```

This starts PostgreSQL, Valkey, MinIO, and the API/indexer/worker scaffolds. The API listens on `http://localhost:8080`; MinIO exposes S3 on port 9000 and its console on port 9001. Stop the stack with `Ctrl-C`; use `docker compose -f infra/compose.yaml down` when you want to remove containers while preserving named volumes.

For a disposable local relay, add the Nostr profile:

```sh
docker compose -f infra/compose.yaml --profile nostr up --build
```

The default relay image is only for local use. Review [the infrastructure README](../../infra/README.md) before using optional Blossom or changing infrastructure configuration.

You can also run the scaffold service processes directly after `npm ci`:

```sh
npm run api
npm run indexer
npm run worker
```

Run each command in its own terminal. These processes currently provide the service scaffold; use Compose when the change needs its local dependencies.

## 8. Tests and checks

Run the narrowest relevant command while iterating, then run the available full check before handing work over:

```sh
make native-test      # C++ MediaCore tests
make android-test     # BusinessCore host tests + Android unit tests
make build-ios        # BusinessCore frameworks + unsigned iOS build
make build-android-apk # unsigned Android debug APK
make service-test     # Node service tests
make infra-check      # validates Compose configuration
make check            # every locally available lane
```

`make check` skips an unavailable iOS or Android lane, but CI is authoritative. State any skipped lane in the pull request. See [testing.md](testing.md) and [development-workflow.md](development-workflow.md) for the required checks for each change type.

## 9. Where to make changes

- `apps/ios`: SwiftUI presentation and Apple adapters.
- `apps/android`: Compose presentation and Android adapters.
- `shared/business-core`: deterministic cross-platform rules and versioned contracts.
- `native/media-core`: C++ timeline, rendering, and audio processing.
- `contracts`: Nostr, project, and API schemas plus fixtures.
- `services` and `infra`: derived backend projections and local/production operations.

Keep UI and platform APIs native. BusinessCore and MediaCore communicate only through their native adapters and versioned contracts; neither core imports the other. The fuller boundary rules are in [architecture.md](architecture.md).

## 10. Troubleshooting builds

### App installs but crashes on launch

**Symptom.** `./gradlew :apps:android:installDebug` succeeds, but the app
crashes on launch with `java.lang.NoSuchMethodError` (for example a
`BitOSApp(...)` parameter mismatch) or `java.lang.NoClassDefFoundError`
(for example a missing `space.bitos.core.model.RelayUrl`).

**Cause.** Stale incremental-compilation and dexing state — typically after
an interrupted build or a branch switch that changed Kotlin/Compose
signatures. The corruption can span two layers at once: the app module's dex
archive (call sites compiled against old signatures, seen 2026-08-28 after
commit `b4d1544` added the `composerDraftStore` parameter) and
`shared/business-core` intermediates (the dex pipeline packaging zero shared
classes even though its compile/runtime jars are complete).

**Fix.** Clean the whole repository. A module-scoped `:apps:android:clean`
only re-dexes the app; it does not touch the shared module's stale
artifacts, which is why the second failure only appears after the first is
fixed:

```sh
./gradlew clean :apps:android:installDebug
```

**Verify.** Launch the app and watch for fatal exceptions (no output = clean):

```sh
adb shell am start -n space.bitos.app.debug/space.bitos.app.MainActivity
adb logcat AndroidRuntime:E '*:S'
```

When a `NoClassDefFoundError` names a `space.bitos.core.*` class, the APK
itself is missing the shared classes. Confirm before reinstalling by
counting shared class definitions in the packaged dex files — a healthy
build reports hundreds; zero means the stale state is back:

```sh
cd $(mktemp -d) && unzip -q "$REPO/apps/android/build/outputs/apk/debug/android-debug.apk" 'classes*.dex'
for d in classes*.dex; do
  "$HOME/Library/Android/sdk/build-tools/36.0.0/dexdump" "$d" 2>/dev/null |
    grep -c "Class descriptor.*'Lspace/bitos/core/"
done
```

```bash

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; export PATH="$JAVA_HOME/bin:$PATH"; ./scripts/ios-build.sh > /tmp/ios-build.log 2>&1; echo "final_exit=$?"

```
