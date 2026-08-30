# BitOS Native Platform

Native iOS and Android clients for a Nostr-first short-video and creator platform, with a shared Kotlin Multiplatform business core and a shared C++ media core.

## Current status (v0.1.0, 2026-08-30)

Both apps share one feature spec ([docs/native/app-unified-feature-spec.md](docs/native/app-unified-feature-spec.md)) and one task ledger ([docs/native/native-ui-build-tracker.md](docs/native/native-ui-build-tracker.md)) — the ledger is the live status; this is the snapshot.

**Live on both platforms** — onboarding + local identity (Keystore/Keychain, multi-account), Home feed (For You/Following, ranked via shared algorithm settings), Bitz reels (Explore/Following/For You, rendition failover, double-tap like), note composer (mentions, polls, GIF picker, PoW option), threads/comments, NIP-17/44 E2EE DMs, stories (bar + viewer), notifications, zap flow (LNURL → 9734 → invoice QR → paid match + sent ledger), discover (NIP-50 search), profile (view + edit), bookmarks, camera → trim → Blossom upload (hash-verified) → kind-22 publish, settings (12 sections incl. full relay manager with NIP-65 publish), static legal pages.

**Not yet built** — communities (NIP-29), the studio/MEM editor (capture-to-publish is live; the editor is W4), trending sounds, NWC wallet, calls/groups, i18n tables.

**Scaffolding present but unwired** — `native/media-core` (C++ timeline math + tests; render/audio backends are README stubs; apps use Media3/AVFoundation), `services/platform` (health + capabilities API only), `infra/terraform` (creates nothing by design).

## Repository

```text
apps/ios                 SwiftUI native application (iOS 17+, Swift 6)
apps/android             Jetpack Compose native application (minSdk 29)
shared/business-core     Kotlin Multiplatform product/domain logic (space.bitos.core)
native/media-core        C++20 timeline core (render/audio backends pending)
contracts                Nostr, project and API schemas/fixtures
services                 API, indexer and media-worker processes (scaffold)
infra                    Local Compose and production IaC skeleton
docs                     Product, architecture, UX and engineering rules
scripts                  Reproducible developer checks
```

Android consumes BusinessCore directly as a Gradle module; iOS consumes a
prebuilt `BusinessCore.xcframework` through the narrow `BusinessCoreBridge`
interop surface (`make build-ios` produces it — run once from a clean
checkout before opening Xcode).

## Start here

1. Follow the [Developer Guide](docs/engineering/developer-guide.md) to install prerequisites, open the Android/iOS projects, run the local stack, and validate changes.
2. Read [the native blueprint](docs/native/README.md).
3. Read [repository architecture](docs/engineering/architecture.md).
4. Read [clean-code rules](docs/engineering/clean-code.md).
5. Read [UX/UI flows](docs/product/ux-ui-flows.md).
6. Read [Studio mass production](docs/product/studio-mass-production.md).

The web client this platform keeps parity with lives in the sibling repo
`../bitos-nostr-web` (feature reference: its `docs/SYSTEM.md`; decisions:
[docs/native/web-parity-audit.md](docs/native/web-parity-audit.md)).

Run `make doctor` to see installed prerequisites and `make check` for every locally available validation. Common targets: `make android-apk`, `make android-test`, `make build-ios`, `make native-test`, `make service-test`.

Current toolchain requirements are documented in [toolchains.md](docs/engineering/toolchains.md).
