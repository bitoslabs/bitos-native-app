# Shared Business Core Architecture

Status: required V1 architecture  
Technology: Kotlin Multiplatform (KMP), native UI retained  
Consumers: iOS, Android, tests and selected server fixture tooling

## 1. Decision

BitOS will have two different shared cores because they solve different problems:

| Core           | Technology           | Owns                                                                                                                                    | Must not own                                                                                                    |
| -------------- | -------------------- | --------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `BusinessCore` | Kotlin Multiplatform | Domain models, protocol codecs, validation, reducers, ranking rules, moderation rules, project/publish orchestration and pure use cases | UI, OS lifecycle, camera/player, secure key storage, background scheduler, database engine or websocket objects |
| `MediaCore`    | C++20                | Timeline math, scene/render graph, audio graph, effects and deterministic media evaluation                                              | Nostr, wallets, network, database, identity, navigation or product policy                                       |

The applications remain genuinely native:

- SwiftUI, Swift concurrency, AVFoundation, Metal and Apple security/lifecycle APIs on iOS.
- Jetpack Compose, Kotlin coroutines, CameraX, Media3 and Android security/lifecycle APIs on Android.
- KMP shares deterministic business behavior and typed contracts, not screens.
- Platform adapters connect BusinessCore ports to native database, signer, relay, upload, notification and media implementations.

This closes the largest parity risk in the earlier plan: event rules, ranking, publishing transitions and feature policies will not be independently reimplemented in Swift and Kotlin.

## 2. Why Kotlin Multiplatform

KMP is selected instead of expanding C++ into business logic or adding a Rust business core:

- Android already uses Kotlin, so Android consumes the common code without an FFI boundary.
- Kotlin/Native produces an Apple framework consumable from Swift through the supported Objective-C framework interop. Swift Export remains optional until stable enough for production use.
- Coroutines, immutable data and sealed results suit reducers/state machines and async use-case orchestration.
- Pure common code can run on JVM host tests quickly and on iOS simulator/device tests for boundary verification.
- The existing C++ core remains focused on media; product engineers do not need C++ for social features.
- Avoiding Rust prevents the team from operating Kotlin, Swift, C++ and Rust build/toolchain boundaries simultaneously.

KMP is not permission to share everything. Platform adapters are a deliberate architecture boundary.

## 3. Repository layout

Current tree as shipped (verified against `shared/business-core/src/`):

```text
shared/
└── business-core/
    ├── build.gradle.kts          # KMP: android + iosArm64/iosSimulatorArm64/iosX64 + macosArm64 test
    └── src/
        ├── commonMain/kotlin/space/bitos/core/
        │   ├── crypto/           # Fp256, Scalar256, Secp256k1, BIP-340 Schnorr sign/verify, NIP-44 v2
        │   ├── nostr/            # NostrEventCodec, EventHasher, NIP-27, NIP-36, PoW, DeepLink, EventRef, QrCode
        │   ├── model/            # NostrEvent, ProfileMetadata, contact/block/bookmark/relay lists,
        │   │                     #   MediaMetadata, Blossom, Poll, Stories, Zap/ZapFormat/SentZapLedger,
        │   │                     #   Notification(+filters/grouping), DmGrouping, OriginNote, Identifiers
        │   ├── feed/             # FeedAggregator, FeedRanking, FeedFilters, ThreadAssembly, SearchResults,
        │   │                     #   Bitz timeline/explore/search policy, AlgorithmPrefs, Remix, RepostParser
        │   ├── publish/          # NoteComposer, ComposerDraft, ComposerRules, PublishReducer,
        │   │                     #   SecureDmComposer (NIP-17 wrap), GifPickerContract
        │   ├── identity/         # AccountRegistry, IdentitySigner, KeyImportForm, NostrKeyCodec (nsec/npub)
        │   ├── settings/         # Settings, PrivacyPrefs, AppFacts, OnboardingContent, StaticPagesContent
        │   ├── store/            # EventStoreContract — versioned SQLite DDL port implemented per platform
        │   └── bridge/           # BusinessCoreBridge — narrow String/primitive interop surface for iOS (SBC-002)
        └── commonTest/           # ~55 test files mirroring every package (JVM host + macOS/iOS lanes)
```

Wave-scope packages that are *planned but not yet implemented* (relay
transport, social graph services, studio, wallet beyond zap models, messaging
beyond DM grouping, moderation, analytics, api) will be added under the same
`space.bitos.core` root when their epics land — do not create the
`com.bitos.core` namespace from earlier drafts.

The media core remains at `native/media-core/`. Native apps depend on both cores, but BusinessCore does not directly depend on MediaCore. The iOS/Android adapter invokes MediaCore and returns typed results to BusinessCore. This avoids nested KMP -> C++ -> platform callbacks and makes cancellation/ownership understandable.

## 4. What is shared

### 4.1 Foundation models

- `Pubkey`, `EventId`, `EventKind`, `EventRef`, `AddressableRef`, `RelayUrl` and `MediaHash` value types.
- Verified versus unverified event types so unsafe data cannot enter projections accidentally.
- Domain errors with stable codes, retry classification and safe user-message keys.
- Clock, random ID and hash ports for deterministic tests.
- Pagination cursor and capability value types.

Raw strings are accepted only at parsing boundaries. Validated value objects cross feature modules.

### 4.2 Nostr protocol

- Canonical event serialization, event ID calculation and structural validation.
- Signature verification through an audited multiplatform crypto implementation or a narrow platform crypto port selected during the Phase-0 spike.
- NIP-19/NIP-21 parsing/encoding.
- Kind 0/1/3/5/6/7/16/20/21/22/1111/34235/34236 models required by BitOS.
- NIP-22 comments, NIP-65 relay lists, NIP-71 video and NIP-92 `imeta` codecs.
- NIP-17/44/59 envelope business rules, while encryption keys remain behind native signer/crypto ports.
- NIP-46 request/response state machine; relay transport and authorization UI remain native.
- NIP-47/NIP-57 request/receipt models and validation.
- Replaceable/addressable head selection, deletion, reaction/repost/comment target resolution.
- BitOS versioned template, sound, remix and value-graph codecs.

Draft/optional NIPs stay in isolated packages with read/write feature flags. Protocol code never imports a UI, database or HTTP library.

### 4.3 Feed and recommendation

- Candidate normalization and deduplication.
- Scoring signals: recency, author affinity, topic match, engagement quality, novelty, web of trust and verified zaps.
- Negative feedback penalties, content filter decisions and diversity passes.
- Chronological Latest policy and deterministic For You score breakdown.
- Local interest-profile update rules using coarse events passed by the native app.
- “Why this video?” explanation model.

Playback position, device telemetry and raw touch/watch events remain native/private. BusinessCore receives only the minimum local signal required to update the local profile.

### 4.4 Social behavior

- Follow/mute/block/bookmark optimistic-operation reducers.
- Reaction/repost/comment/zap target construction and reconciliation.
- Notification classification, grouping, deduplication and preference evaluation.
- Profile/stat aggregation over known verified observations.
- Remix DAG/value split validation and attribution decisions.

The native app renders state and runs effects; reducers decide valid transitions.

### 4.5 Studio business model

- Project header, status, revision metadata and capability requirements.
- Shared project schema normalization and migration orchestration.
- Edit command model used for undo/redo history and invalidation rules.
- Layer/track limits, time/spatial validation and output policy.
- Template/sound/license/provenance validation.
- Preflight blocker/warning generation.
- Batch item state, storage estimate inputs and bounded concurrency policy.

BusinessCore owns project semantics. MediaCore consumes a validated render snapshot. Native code owns files, thumbnails, proxy creation, user interaction and MediaCore session lifetime.

### 4.6 Publish orchestration

- Durable publish state and legal transition table.
- Input/output hash dependency graph and downstream invalidation.
- Retry/final/cancel/block classification.
- Media descriptor verification rules.
- Unsigned kind-22 construction after verified media readiness.
- Signer request intent and relay publish/reconciliation decisions.
- Partial relay-ack receipt reduction.

The state machine emits effects such as `RenderRequested`, `UploadRequested`, `SignatureRequested` and `RelayPublishRequested`. Native coordinators perform them, persist results and dispatch typed completion/failure actions.

### 4.7 Moderation, privacy and analytics policy

- Mute/block/content-warning/local-keyword decision ordering.
- Safety reason codes and reveal/report eligibility.
- Analytics consent state and event allowlist.
- Coarse analytics batch construction with prohibited-field checks.
- Diagnostics redaction helpers for domain errors and relay/job receipts.

Server moderation models may reuse schemas, but client policy stays functional without the server.

## 5. What remains platform-native

| Concern                     | iOS                                   | Android                             |
| --------------------------- | ------------------------------------- | ----------------------------------- |
| UI/navigation/accessibility | SwiftUI/UIKit                         | Compose/Android Views where needed  |
| State lifetime              | Swift observation/actors              | ViewModel/StateFlow                 |
| Key storage/unlock          | Keychain/LocalAuthentication          | Keystore/BiometricPrompt            |
| Signer integration          | local + NIP-46 adapter                | local + NIP-46 + NIP-55 adapter     |
| Relay transport             | native URLSession/WebSocket adapter   | OkHttp-compatible WebSocket adapter |
| Database                    | native SQLite-backed repository       | Room/SQLite repository              |
| File/media catalog          | FileManager/file protection           | app files/MediaStore/SAF            |
| HTTP/background transfer    | URLSession background tasks           | WorkManager/foreground service      |
| Camera                      | AVFoundation                          | CameraX                             |
| Player                      | AVPlayer                              | Media3 ExoPlayer                    |
| Media edit/export           | AVFoundation/VideoToolbox/Metal + C++ | Media3/MediaCodec/Vulkan-GLES + C++ |
| Push                        | APNs                                  | FCM                                 |
| System share/deep links     | Apple frameworks                      | Android intents/app links           |

BusinessCore interfaces use domain types and suspend functions/flows only where asynchronous behavior is truly part of a use case. They never expose platform objects.

## 6. Ports and adapters

Representative common interfaces:

```kotlin
interface EventStore {
    suspend fun putVerified(events: List<VerifiedEvent>)
    suspend fun event(ref: EventRef): VerifiedEvent?
    fun observeFeed(surface: FeedSurface): Flow<List<FeedCandidate>>
}

interface RelayGateway {
    fun subscribe(request: RelayQuery): Flow<RelayMessage>
    suspend fun publish(event: SignedEvent, targets: List<RelayUrl>): List<RelayReceipt>
}

interface IdentitySigner {
    suspend fun publicKey(): Pubkey
    suspend fun sign(unsigned: UnsignedEvent, reason: SignReason): SignedEvent
}

interface MediaGateway {
    suspend fun render(request: RenderRequest): RenderResult
    suspend fun upload(request: UploadRequest): UploadResult
    suspend fun verify(descriptor: MediaDescriptor): VerificationResult
}

interface JobStore {
    suspend fun load(jobId: JobId): PublishJob?
    suspend fun compareAndSet(expectedRevision: Long, next: PublishJob): Boolean
}
```

Rules:

- A port expresses a business capability, not a platform API wrapper.
- Adapter methods must be cancellable and declare idempotency behavior.
- Database transactions needed for correctness appear as a single repository method, not a sequence controlled from common code.
- Platform errors map to stable domain errors at the adapter boundary.
- Flows are cold/bounded or explicitly lifecycle-managed; BusinessCore must not create immortal scopes.

## 7. State and reducer model

Feature business state uses pure reducers:

```kotlin
data class PublishState(
    val stage: PublishStage,
    val revision: Long,
    val projectHash: MediaHash?,
    val output: RenderDescriptor?,
    val upload: MediaDescriptor?,
    val signedEvent: SignedEvent?,
    val receipts: List<RelayReceipt>
)

sealed interface PublishAction
data class Reduction<S, E>(val state: S, val effects: List<E>)

fun reduce(state: PublishState, action: PublishAction): Reduction<PublishState, PublishEffect>
```

Native feature stores own coroutine/task scopes, invoke the reducer, persist state and execute effects. This preserves SwiftUI/Compose lifecycle control while producing identical product rules.

Do not put every screen state in KMP. Transient selection, sheet visibility, scroll position, focus, gestures, animations and platform permission UI remain native.

## 8. Swift integration

BusinessCore ships as an XCFramework built in CI. iOS adds a small handwritten Swift facade:

```text
SwiftUI feature
    -> Swift feature store
        -> BusinessCore use case/reducer
        -> Swift protocol adapter
            -> Keychain / SQLite / URLSession / AVFoundation
```

Interop rules:

- Expose simple immutable data, enums, result wrappers and interfaces.
- Avoid leaking generic-heavy Kotlin APIs, mutable collections or exceptions to Swift.
- Convert Kotlin `Flow` to `AsyncSequence` through one maintained bridge with cancellation tests.
- Map Kotlin suspend methods to Swift async through a generated/supported bridge and wrap errors explicitly.
- Hide KMP naming/boxing details behind the Swift facade so views remain idiomatic Swift.
- Treat Swift Export as experimental until its official stability and toolchain behavior meet the release gate; standard framework interop is the baseline.
- Verify memory/cancellation behavior with Instruments and repeated feature open/close tests.

## 9. Android integration

Android consumes the common Gradle module directly:

```text
Compose feature
    -> Android ViewModel
        -> BusinessCore use case/reducer
        -> Android repository adapter
            -> Room / Keystore / WebSocket / WorkManager / Media3
```

Android rules:

- ViewModels own scopes and convert BusinessCore results into immutable UI state.
- Do not bypass common reducers for “small” Android-only behavior that changes protocol or product semantics.
- Platform-only capabilities are represented by capability input, not hard-coded branches in shared code.
- Keep Android framework imports out of `commonMain`.

## 10. BusinessCore and MediaCore bridge

The bridge is platform-owned:

```text
BusinessCore RenderRequested(project revision + validated snapshot)
        ↓
native RenderAdapter resolves files and capabilities
        ↓
C++ MediaCore evaluates/renders
        ↓
native adapter hashes/probes the output
        ↓
BusinessCore RenderCompleted(descriptor) or RenderFailed(error)
```

BusinessCore never passes secret keys or network credentials to C++. MediaCore never parses a Nostr event. The shared project schema is the only shared data boundary, with a compact validated native representation used after parsing.

## 11. API compatibility and evolution

- Semantic version the BusinessCore artifact independently from the app.
- A mobile release pins one exact BusinessCore and MediaCore version.
- Public Swift/Kotlin APIs follow additive-first evolution; removals require one release of deprecation where possible.
- Persisted schema versions are independent of library versions.
- NIP codec behavior changes require old/new fixture coverage and release notes.
- Generate an API surface dump and fail CI on unreviewed binary/public API changes.
- iOS framework names and Objective-C-visible symbols remain stable within a supported app branch.

BusinessCore is linked into the app; it is not downloaded or remotely updated.

## 12. Testing strategy

### Common tests

- Value-object and hostile parser property tests.
- Nostr golden encode/decode and invalid-signature/event cases.
- Replaceable/deletion/comment/repost/reaction target rules.
- Feed score and diversity golden fixtures.
- Social optimistic/reconciliation reducer tables.
- Project normalization/migration/preflight and command invalidation.
- Publish transition model with every success/failure/cancel/retry sequence.
- Template/sound/remix/license/value-split limits.
- Moderation ordering and analytics prohibited-field tests.

### Platform adapter contract tests

Run the same abstract suite against iOS and Android adapters:

- EventStore transaction and account isolation.
- Relay subscribe cancellation, dedupe and publish receipts.
- Signer unavailable/rejected/locked/timeout behavior.
- Media render/upload/verify idempotency and hash mismatch.
- Job compare-and-set and app-kill restoration.
- Flow/async cancellation and memory release.

### Cross-core conformance

- BusinessCore validates and produces a render snapshot fixture.
- Both native adapters pass it to MediaCore.
- Both outputs pass timing, structure, pixel and audio tolerances.
- BusinessCore accepts only the correctly hashed descriptor into publish state.

## 13. Build and CI

Required lanes:

```text
business-core-common      JVM host unit/property/fixture tests
business-core-android     Android unit + adapter instrumentation tests
business-core-ios         iOS simulator framework + adapter tests
business-core-api         public API/binary compatibility check
business-core-sanitizers  native boundary/memory checks where applicable
ios-app                   consumes release-mode XCFramework
android-app               consumes common Gradle artifact
```

CI publishes an internal immutable artifact identified by source commit. App builds cannot use a mutable `latest`. Release provenance records BusinessCore, MediaCore, fixture and schema versions.

## 14. Performance budgets

- Common parsing/scoring/reducer work never runs on the UI thread when input is unbounded.
- A 100-item feed candidate score/diversity pass targets under 10 ms on median supported devices after warm-up.
- Kind-22 parse/validation targets under 1 ms for normal bounded events.
- Reducers allocate proportionally to changed state, not full databases or media payloads.
- BusinessCore never holds decoded media, large byte buffers or native player/camera objects.
- Startup initializes modules lazily; KMP initialization must not materially regress cold launch budgets.
- iOS interop crossing is batched: pass domain batches, not per-frame, per-pixel or per-scroll-callback messages.

Measure on release builds and real devices. If a rule violates budget, optimize the algorithm/data boundary before duplicating behavior natively.

## 15. Security rules

- No raw `nsec`, wallet secret, DM key or authorization token in common state, serialization, equality output or logs.
- Signer ports accept typed requests and return signed/encrypted results; secrets stay in the adapter.
- All common parsers have byte/count/depth/string/time bounds.
- Redacted `toString` behavior for sensitive domain values is tested.
- KMP dependencies undergo the same security, license, provenance and update review as native dependencies.
- No reflection-based arbitrary class/template loading.
- No dynamic BusinessCore code or remote scripts.
- Exceptions crossing the Swift boundary are converted into bounded typed errors.

## 16. Migration from the web reference

Extract behavior in this order:

1. Golden fixtures from `nostr/bitz-codec`, `event-ref`, protocol tests and public-shaped media events.
2. Publish-machine transition tables and native durability extensions.
3. Algorithm signal/diversity fixtures.
4. Meme/project/timeline/sound/template/remix/split normalization fixtures.
5. Privacy, content classification and consent cases.
6. Wallet/zap/DM vectors updated to current NIPs.

Do not translate Svelte stores or browser APIs. Reimplement pure behavior from fixtures and the native architecture contracts.

## 17. Rollout plan

### Core 0 — technical proof

- Produce Android library and iOS XCFramework.
- Swift calls a pure event parser and publish reducer.
- Android calls the same APIs directly.
- Cancellation, error mapping, memory and release build work in CI.

### Core 1 — protocol foundation

- Move value objects, event validation, NIP-19/21 and video/comment codecs into common code.
- Native relay and signer adapters consume shared types.
- Delete parallel Swift/Kotlin protocol builders after fixture parity.

### Core 2 — feed/social

- Move candidate scoring/diversity and social reducers.
- UI remains native; compare behavior through golden scenarios.

### Core 3 — Studio/publish

- Move project semantics, preflight, command invalidation and publish state machine.
- Bridge native render/upload/signer/relay effects.
- Complete app-kill recovery tests before removing temporary native orchestration.

### Core 4 — hardening

- API compatibility checks, dependency/SBOM, fuzzing, performance budgets and cross-version schema corpus.

## 18. Exit gate

BusinessCore is ready for public V1 only when:

1. Both apps consume the same release artifact in release configuration.
2. Protocol, feed, project and publish rules have no parallel platform implementation.
3. iOS facade/async cancellation and Android ViewModel integration pass leak/process-death tests.
4. App launch and hot-path performance remain within the native budgets.
5. Every persisted schema has migration fixtures.
6. Signer/media/database/relay adapters pass the shared contract suite.
7. Public API compatibility and dependency/security checks are release gates.
8. BusinessCore can be upgraded or rolled back with a normal app release without corrupting drafts/jobs.

## 19. Primary reference

Kotlin’s official multiplatform guidance supports sharing common business logic while using expected/actual or platform implementations where platform APIs are required: [Share code on platforms](https://kotlinlang.org/docs/multiplatform/multiplatform-share-on-platforms.html). Kotlin-to-Swift direct export remains evolving, so BitOS uses the established Apple framework interoperability as the release baseline and treats [Swift Export](https://kotlinlang.org/docs/native-swift-export.html) as optional until stable.
