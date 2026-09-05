# BitOS Documentation

## Native iOS and Android build authority

Start with [Native Platform Blueprint](./native/README.md).

- **[Unified app feature spec — single merged source for iOS + Android UI](./native/app-unified-feature-spec.md)** (APP epic: every surface, field, widget, state, model; union of the legacy web + Flutter feature docs)
- **[Native UI build tracker — APP epic task ledger and progress log](./native/native-ui-build-tracker.md)** (update in the same change as UI code)
- [Product system and full feature scope](./native/product-system.md)
- [Current web-to-native parity audit](./native/web-parity-audit.md)
- [Shared Kotlin Multiplatform business core](./native/shared-business-core.md)
- [iOS, Android and shared media-core architecture](./native/mobile-architecture.md)
- [Nostr protocol, Blossom and production infrastructure](./native/nostr-infrastructure.md)
- [Phased implementation plan and executable task ledger](./native/delivery-plan.md)

## Engineering rules

- [Toolchain and version policy](./engineering/toolchains.md)
- [Repository and code architecture](./engineering/architecture.md)
- [Clean code and SRP rules](./engineering/clean-code.md)
- [Native performance and maintainability guide](./engineering/native-performance.md)
- [Development and release workflow](./engineering/development-workflow.md)
- [Testing strategy and quality gates](./engineering/testing.md)
- [Architecture decision records](./adr/README.md)

## Product and experience

- [End-to-end UX/UI flows](./product/ux-ui-flows.md)
- [Studio mass-production system](./product/studio-mass-production.md)
- [MemeEditor UX/UI audit and implementation plan](./product/meme-editor-ux-ui-audit-and-plan.md)

Legacy web/Flutter-era plans and HTML mockups were removed from this repo
(2026-08) after being merged into the unified feature spec and the
`docs/native/` blueprint; git history and the sibling web repo preserve them.
The web client's current feature reference is `../bitos-nostr-web/docs/SYSTEM.md`.
