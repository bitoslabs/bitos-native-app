# BitOS Native Platform Blueprint

Status: implementation authority for the new iOS and Android applications  
Last reviewed: 2026-08-27  
Source web app audited at: `bitos-nostr` commit `fd4296dd5e42b5ed8224b47fde5654d538dbcfe3`

## Decision

BitOS mobile will be built as two native products:

- iOS: Swift 6, SwiftUI, AVFoundation, VideoToolbox, Metal and Background URLSession.
- Android: Kotlin, Jetpack Compose, CameraX, Media3/ExoPlayer, MediaCodec, OpenGL ES/Vulkan and WorkManager.
- Shared business core: Kotlin Multiplatform domain models, Nostr codecs, validation, reducers, ranking, Studio rules and publish orchestration.
- Shared editor core: C++20 timeline, scene graph, deterministic compositor rules, audio graph and effect runtime, with Metal and Vulkan/OpenGL backends.
- Shared contracts, not shared UI: JSON Schema, Nostr event fixtures, render golden files and API definitions are common to BusinessCore, MediaCore, iOS, Android, web and backend.

Flutter and React Native are explicitly not selected. They would still require native camera, codec, GPU, player and background-upload modules for the product we are building. The native shells own navigation, accessibility, lifecycle, camera, playback and platform security. BusinessCore owns cross-platform product rules. The C++ MediaCore owns only performance-sensitive timeline and deterministic rendering behavior.

## Product position

BitOS is not a closed TikTok clone. It is a Nostr-native short-media and creator platform whose core loop is:

```text
Watch -> create or remix -> edit a Bitz/MEM -> publish -> zap -> discover provenance -> repeat
```

Nostr signed events are the canonical identity and social state. Blossom-compatible, content-addressed storage is the media layer. Lightning is the value layer. BitOS services provide rebuildable indexes, ranking candidates, search, moderation assistance, processing and analytics; they never become the exclusive owner of an account or post.

## Documentation map

1. [Product system and feature scope](./product-system.md)
2. [Web-to-native parity audit](./web-parity-audit.md)
3. [Shared business-core architecture](./shared-business-core.md)
4. [Native mobile and editor architecture](./mobile-architecture.md)
5. [Nostr protocol and infrastructure](./nostr-infrastructure.md)
6. [Delivery plan and executable backlog](./delivery-plan.md)

Read all six before implementation. `delivery-plan.md` is the task ledger; the other documents define what the tasks mean.

## Source audit and authority

The design was derived from:

- `QA.md`, which chooses native iOS + native Android + a shared C++ media core.
- Existing plans under `docs/source/`, especially the Bitz system plan, Studio production plan, mobile Studio UX, meme remix and Draw & Record specifications.
- The sibling `../bitos-nostr` web product: routes, Nostr codecs, feed/ranking signals, media upload and publish state machine, Meme Studio schema, templates, sounds, remix, zaps, messaging and settings.
- Current primary Nostr and Blossom specifications linked in `nostr-infrastructure.md`.

When documents disagree, use this order:

1. Current published protocol specification and checked-in protocol fixtures.
2. This `docs/native/` blueprint.
3. `QA.md` for the native-platform decision.
4. Current web behavior as a product reference.
5. Older `docs/source/` plans as research, not implementation authority.

The older Flutter recommendation in `docs/source/plan-bitz-implelemt.md` is superseded for this repository. Browser-only implementation details such as Canvas, MediaRecorder, Web Audio, IndexedDB, WebLN and `localStorage` are also not native architecture.

## Non-negotiable principles

1. A private key never reaches BitOS servers, logs, analytics or crash reports.
2. The app verifies every event ID and signature before projection or display.
3. Media must be uploaded, publicly retrievable and hash-verified before its Nostr event is signed.
4. Core Following/Latest feeds continue through direct relay access when BitOS APIs fail.
5. Drafts, watch history and detailed interest state are device-local by default.
6. Every custom BitOS schema is namespaced, versioned, size-bounded and tolerant of unknown fields.
7. Preview and export evaluate one timeline model and the same effect parameters.
8. No remote template can execute arbitrary native, shader or script code.
9. Every asynchronous publish stage is durable, idempotent, cancellable and recoverable.
10. Accessibility, moderation, battery, heat, storage and poor-network behavior are launch requirements.
11. Cross-platform product rules exist once in BusinessCore; native apps own presentation and platform effects, not parallel business implementations.

## Definition of a production vertical slice

A feature is not complete when only its screen exists. A production slice includes:

- native iOS and Android behavior;
- local persistence and schema migration;
- loading, empty, offline, permission-denied, cancellation, retry and recovery states;
- protocol encode/decode fixtures where it touches Nostr;
- BusinessCore common tests plus iOS and Android adapter-contract tests where it touches shared behavior;
- performance budgets and representative real-device tests;
- accessibility labels, focus order, dynamic type/font scaling and reduced-motion behavior;
- privacy classification, telemetry allowlist and redaction tests;
- backend degradation behavior and an operational runbook when a service is involved.

## Initial release boundary

Public V1 ships the reliable short-video loop, a strong single-project editor and the fast meme workflow. It does not wait for live streaming, commerce, public executable effects, collaborative editing, AI dubbing or a third-party algorithm marketplace. Those extension points remain in the model but cannot delay an interoperable, crash-safe publisher.
