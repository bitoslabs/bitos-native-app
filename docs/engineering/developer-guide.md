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

## 5. Run local services and infrastructure

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

## 6. Tests and checks

Run the narrowest relevant command while iterating, then run the available full check before handing work over:

```sh
make native-test      # C++ MediaCore tests
make android-test     # BusinessCore host tests + Android unit tests
make ios-build        # BusinessCore frameworks + unsigned iOS build
make service-test     # Node service tests
make infra-check      # validates Compose configuration
make check            # every locally available lane
```

`make check` skips an unavailable iOS or Android lane, but CI is authoritative. State any skipped lane in the pull request. See [testing.md](testing.md) and [development-workflow.md](development-workflow.md) for the required checks for each change type.

## 7. Where to make changes

- `apps/ios`: SwiftUI presentation and Apple adapters.
- `apps/android`: Compose presentation and Android adapters.
- `shared/business-core`: deterministic cross-platform rules and versioned contracts.
- `native/media-core`: C++ timeline, rendering, and audio processing.
- `contracts`: Nostr, project, and API schemas plus fixtures.
- `services` and `infra`: derived backend projections and local/production operations.

Keep UI and platform APIs native. BusinessCore and MediaCore communicate only through their native adapters and versioned contracts; neither core imports the other. The fuller boundary rules are in [architecture.md](architecture.md).
