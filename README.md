# BitOS Native Platform

Native iOS and Android clients for a Nostr-first short-video and creator platform, with a shared Kotlin Multiplatform business core and a shared C++ media core.

## Repository

```text
apps/ios                 SwiftUI native application
apps/android             Jetpack Compose native application
shared/business-core     Kotlin Multiplatform product/domain logic
native/media-core        C++20 timeline/render/audio core
contracts                Nostr, project and API schemas/fixtures
services                 API, indexer and media-worker processes
infra                    Local Compose and production IaC skeleton
docs                     Product, architecture, UX and engineering rules
scripts                  Reproducible developer checks
```

## Start here

1. Read [the native blueprint](docs/native/README.md).
2. Read [repository architecture](docs/engineering/architecture.md).
3. Read [clean-code rules](docs/engineering/clean-code.md).
4. Read [UX/UI flows](docs/product/ux-ui-flows.md).
5. Read [Studio mass production](docs/product/studio-mass-production.md).

Run `make doctor` to see installed prerequisites and `make check` for every locally available validation.

Current toolchain requirements are documented in [toolchains.md](docs/engineering/toolchains.md).
