# BitOS repository rules

These rules apply to the entire repository.

## Architecture

- Keep SwiftUI and Compose UI native. Do not put views, navigation, permissions, player, camera, secure storage or background scheduling in shared code.
- Put deterministic cross-platform product rules in `shared/business-core`.
- Put timeline, scene, render and audio processing in `native/media-core`.
- BusinessCore and MediaCore communicate through native adapters and versioned contracts; neither core may import the other.
- Nostr signed events and content hashes are canonical. Backend records are derived projections.
- Views never access databases, relays, signers, uploaders or C++ handles directly.

## Clean code and SRP

- One type has one reason to change. Split protocol parsing, persistence, orchestration and presentation.
- Prefer small explicit interfaces at trust and platform boundaries.
- Business rules are pure where possible; side effects are emitted as typed commands and executed by adapters.
- Never use global mutable state, service locators, generic “Manager” classes or boolean-heavy APIs.
- Do not expose raw strings for event IDs, pubkeys, hashes, relay URLs, money or timeline time when a value type exists.
- All persisted/network schemas are versioned and size-bounded.

## Safety

- Never log or serialize `nsec`, wallet secrets, DM plaintext, raw authorization events or unpublished media.
- Never sign before media is uploaded and hash-verified.
- Never execute remote scripts, native code, shaders or untrusted SVG.
- Do not weaken TLS or commit secrets to make local development easier.

## Tests and delivery

- Protocol changes require fixtures.
- Shared business changes require common tests and native adapter-contract tests.
- Media changes require deterministic/golden or timing tests.
- Every background job must be durable, idempotent, cancellable and recoverable.
- Update the relevant architecture and flow document in the same change.

Read `docs/engineering/` and `docs/product/` before implementing features.
