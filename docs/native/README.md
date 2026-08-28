# BitOS Native Platform Blueprint

Status: implementation authority for the new iOS and Android applications  
Last reviewed: 2026-08-27  
Source web app audited at: `bitos-nostr` commit `fd4296dd5e42b5ed8224b47fde5654d538dbcfe3`

## Implementation status

Phase 1 foundation slice (read-only verified relay feed) is implemented, and
the shared core is now the single source of truth on both platforms:

- `shared/business-core`: NIP-01 canonical serialization, SHA-256 event-ID
  verification, **BIP-340 Schnorr signature verification (SBC-006: pure
  Kotlin secp256k1 field/group arithmetic in `crypto/`, no platform deps —
  one implementation, one vector set, executed on JVM, Android, iOS and
  native macOS lanes)**, bounded strict decoding, `RelayUrl` value type,
  kind-0 profile projection, feed-note normalization (NIP-10/NIP-22 reply
  parents, hashtags, mentions, media URLs), bounded feed aggregation and
  recency ranking, plus `bridge/BusinessCoreBridge` — the narrow interop
  surface (strings/primitives only) consumed by the iOS Swift facade.
- Display trust: every event passes BOTH stages (ID hash + signature)
  before projection. `NostrEventCodec.verifySignature` is the separate
  second stage; the bridge and both platform repositories enforce it.
  Optimization lane (windowed multiplication, Montgomery reduction) sits
  behind the same vectors.
- `apps/ios` consumes the **BusinessCore.xcframework** (SBC-001/SBC-002):
  `scripts/ios-build.sh` builds release frameworks for iosArm64,
  iosSimulatorArm64 and iosX64, lipo-merges the simulator slices and creates
  `build/BusinessCore.xcframework`, which the Xcode project links. The Swift
  codec/normalizer/aggregator mirror is deleted; `FeedStore` goes through
  the `BusinessCoreClient` seam and only native `RelayURL` transport types
  remain in Swift.
- `apps/android`: OkHttp relay pool with capped jittered reconnects,
  `FeedRepository` (REQ/verify/aggregate/profile batches + cache hydration
  and persistence), `HomeViewModel`, Compose five-tab shell and
  Home/Discover/Create/Inbox/Profile surfaces on the BitOS design tokens;
  adapter-contract tests use a fake transport and in-memory cache.
- Test lanes (SBC-019): common tests run on the JVM host **and natively on
  macOS** (`macosArm64Test`), so Apple-side behavior executes even where no
  iOS simulator runtime is installed. iOS app + test targets compile and
  link against the real framework.
- Protocol fixtures (PRO-001): `contracts/nostr/fixtures/verification-vectors.json`
  holds deterministically generated, reference-verified relay frames
  (nostr-tools/@noble BIP-340) — 9 vectors including wrong-signature,
  tampered-signature, s=n, r=0 and off-curve pubkeys; regenerate with
  `scripts/generate-verification-vectors.mjs`. Both the Kotlin and Swift
  suites lock the same vectors.

- Persistence (DAT-001/002/003, partial): `EventStoreContract` in the shared
  core owns the versioned schema (DDL, bounds, ordered migrations, canonical
  tags encoding); Android (`SqliteEventCache` via SQLiteOpenHelper) and iOS
  (`EventStore` over sqlite3, DDL injected through the bridge) execute the
  same schema. Verified events persist fire-and-forget; cold start hydrates
  the newest 200 cached rows before relays connect, then prunes to 500.
  Deviation note: DAT-002 names Room, but KSP has no Kotlin 2.4.x release;
  raw SQLite with the shared DDL is the parity-stronger interim and the
  `EventCache` port isolates the later Room swap.

- Playback (FED-001/002 foundation + FED-003 partial): kind-22 media
  parsing (NIP-92 `imeta`, positional `url`/`m` pairs, legacy kind-1 video
  links) with hostile-input bounds lives once in the shared core and rides
  on `FeedNote.video`. Home is now a full-screen vertical pager on both
  platforms — text notes render as full-screen cards; video notes play
  through three-slot pools (`PlayerPool` AVQueuePlayer+looper on iOS,
  `VideoPlayerPool` Media3 ExoPlayer on Android) where only the settled page
  plays and slots are keyed by verified event id (id-keyed lifetimes = the
  stale-callback protection). Caption gradient + right action rail,
  minimal poster loading until the media pipeline lands. Rendition/mirror
  selection, bounded player cache and capability fallbacks (FED-004/005)
  are still open.

- Identity (ID-001–ID-004 foundation): BIP-340 **signing** in the shared
  core (deterministic nonces, mod-n scalar arithmetic, @noble-locked
  vectors) plus strict NIP-19 npub/nsec bech32 codecs. `IdentitySigner`
  port + `DeterministicTestSigner`; secrets transit function arguments
  only, never state. Android: `SecureKeyStore` (Android Keystore
  AES-256-GCM sealing) + `LocalKeySigner` + create/import flow with
  visible npub confirmation and destructive-replace warnings. iOS:
  Keychain (`AfterFirstUnlockThisDeviceOnly`) + `IdentityStore` + the same
  confirmation flow. Browse-first: no identity is ever created silently.
  NIP-46/NIP-55 remote signers arrive later behind the same port.

- Publishing (PUB-001/007/008 note path): shared `NoteComposer` — bounded
  content, canonical ID, strict signature format, `OK` receipt parsing —
  with the client→relay `[["EVENT", event]]` frame form added to the codec
  so our own frames pass our own verified gate. Both apps compose → sign →
  targeted write-relay fan-out → first-acceptance-completes receipt UI
  (per-relay accepted/rejected/waiting with reasons); signer refusal and
  rejection reasons are surfaced, never swallowed. Relay echo of our own
  note returns through the subscription and passes the same ID+signature
  gate — reconciliation falls out of the verified pipeline. Media upload
  (Blossom), kind-22 descriptors and durable job checkpoints (PUB-002..006)
  remain open.

- Social (SOC-001/003 partial): the Following timeline is live on both
  apps — the account's newest verified kind-3 contact list (bounded,
  deduped `ContactList` in the shared core) drives an authors-targeted
  subscription and a dedicated feed window. Likes from signed accounts
  publish real NIP-25 kind-7 reactions through the publish machine
  (composeReaction → sign → write-relay fan-out → receipts); unlikes stay
  local until kind-5 deletion lands. Follow/unfollow actions, reposts,
  bookmarks-as-events, comments (NIP-22) and zaps remain open.

- Comments (SOC-002 partial): NIP-10 replies live on both apps.
  `composeReply` in the shared core builds kind-1 notes with the `e`/`reply`
  marker (optional relay hint) + `p` tag, ID-committed through the codec;
  comment threads load via NIP-01 tagged `#e` filters and group verified
  replies per target (bounded 16 targets × 100 replies); the comment sheet
  publishes through the same receipt machine, and relay echoes reconcile
  through the verified gate. NIP-22 comment kinds, reposts, bookmarks-as-
  events and zaps remain open.

- Follows + reposts (SOC-001 write path, SOC-003): `composeFollowList`
  (NIP-02 kind-3, deduped/bounded, self-follow filtered) and
  `composeRepost` (NIP-18 kind-6, `e`+`p` tags, empty content) in the shared
  core. Follow/unfollow flips the optimistic set + Following window
  immediately, then publishes the resulting kind-3 — the relay echo
  (verified, newer) reconciles it. Repost buttons publish kind-6 through
  the receipt machine. Follow chips on note captions; follow state exposed
  through feed state on both apps.

- Bookmarks as events (NIP-51): `BookmarkList` (kind-30003, addressable
  at the empty `d` coordinate) + `composeBookmarkList` in the shared core —
  the write path and read projection share one rule set (4 new tests incl.
  frame round-trip and newest-head semantics). Bookmarks are now
  relay-backed for signed accounts: optimistic set flip + kind-30003
  publish through the receipt machine; the relay's newer verified head
  reconciles. Signed-out users keep local-only bookmarks. Addressable
  coordinate REQs and 30003 routing wired on both apps.

- Zaps (SOC-008 client path): NIP-57 zap requests (kind 9734) —
  composed and signed by the payer in the shared core (`composeZapRequest`;
  `p`/`e`/`relays`/`amount`/`lnurl` tags; the client never publishes them,
  they go to the recipient's LNURL server) — plus pure LNURL-pay parsing
  (HTTPS-only callbacks, millisat range enforcement, hostile-input
  tolerance) and kind-9735 receipt counting via tagged `#e` REQs. Zap
  sheets on both apps: amount presets → signed request → invoice fetched
  and handed to the user for payment in any external wallet. Zap counts
  surface per note. NWC wallet connection and bolt11 amount decoding stay
  out (separate safety-gated slices).

- Media publishing (PUB media path, infrastructure): Blossom BUD-02 in the
  shared core — kind-24242 upload auth (`t`/`expiration`/`x`/`size` tags,
  challenge-honored expiration, HTTPS-only with dev-loopback exception),
  `UploadedMedia` descriptor + NIP-92 `imeta` building, and kind-22
  composition whose frame round-trips back through the existing
  `MediaMetadata` parser (write path and read path agree). Android
  `BlossomUploader` implements the challenge-response flow with
  **mandatory remote-hash verification** — a mismatching server hash is
  blocking and security-visible (PUB-002/006), proven by a hand-rolled
  JDK HTTP-server test. Bridge exposes the full media path for iOS; the
  capture/import UI that feeds it arrives with the CAP epic.

- Media import → publish (CAP-005): gallery pick (ActivityResultContracts on
  Android, PhotosPicker on iOS) → bounded byte read (64MB) → caption →
  hash-verified Blossom upload → kind-22 through the receipt machine, on
  both apps. The published frame parses back through `MediaMetadata` (repo
  test), closing the creator loop end-to-end: import → upload → publish →
  play in your own feed. Camera capture (CAP-001/002) remains open.

- Camera capture (CAP-001/002 foundation): CameraX preview + HD recording
  on Android and AVCaptureSession + movie-file output on iOS, both wired
  into the Create tab's Record fast path and handed to the tested
  publish pipeline as bounded bytes. Permission flows with truthful denied
  states (import remains available). Segment/pause/retake (CAP-003) and
  countdown/speed modes (CAP-004) remain open. The camera layer is
  deliberately thin: record → (bytes, mime) → the fully tested upload →
  kind-22 path.

- Notification inbox (SOC-005 partial): `NotificationExtractor` in the
  shared core maps verified events targeting the account to bounded
  notification items — replies (kind 1 + e tag), mentions (kind 1 + p tag),
  reactions (kind 7), reposts (kind 6) and zap receipts (kind 9735) — with
  own-event and untargeted-event rejection. Both apps subscribe a `#p`
  tagged filter over those kinds, dedupe by event id, and render a
  newest-first bounded window with kind-badged rows. Own events never
  notify. Notification-preference filtering (SOC-005 remainder) is open.

- Search (SOC-004): NIP-50 full-text search live in Discover on both
  apps — debounced queries, npub creator resolution (targeted profile REQ),
  hashtag topic chips feeding the same pipeline, and verified results in a
  bounded window with kind-badged cards (video notes marked). The empty
  result state honestly notes relay-dependent NIP-50 support.

- Author profiles (SOC-001/SOC-004): tapping any author (video caption,
  follow chip) opens their profile — name/NIP-05/about/lud16 from verified
  kind-0, their notes via a targeted authors REQ, and follow/unfollow
  inline. One author sheet at a time; state clears on dismiss.

- Profile editing (ID-008 partial): `composeProfileMetadata` (kind 0 with
  escaped JSON content, bounded fields, HTTPS-only pictures, lud16 shape
  validation) + edit sheets on both apps publishing through the receipt
  machine. The relay echo (verified, newer) updates the profile projection
  everywhere — feed captions, author sheets, search — since they all share
  the same kind-0 fan-in.

- Repost display (SOC-003): kind-6 reposts now render in the feed.
  `RepostParser` in the shared core extracts the embedded original event
  from the repost content (the common NIP-18 client pattern) and
  **verifies its own ID commitment** — a reposted forged event is as
  dangerous as a forged event. `FeedNote.repostedBy` carries attribution;
  both apps show a ↻ "Reposted" badge above the content. Unresolvable
  reposts (empty content, no embedding) render as invisible protocol
  payloads. Kind 6 added to the feed subscription.

- Moderation (SOC-001): **report** (NIP-56 kind-1984 with `e`/`p`/`L`/`l`
  tags — event or author-level, spam/illicit/harassment) through the
  receipt machine, and **mute** (device-local persisted set filtering all
  feed windows + Following). More-options dialogs on both apps;
  3 new report-composition tests including verified frame round-trip.

- Recorded-take preview (CAP-003 partial): the camera flow now stops at a
  playback preview (looping ExoPlayer on Android, AVQueuePlayer+looper on
  iOS) with **Use this** / **Retake** before the video enters the publish
  pipeline. Users review what they recorded before committing; retake
  returns to the live camera. The bytes flow unchanged to the tested
  upload path on Use.

- Trim controls (CAP-004): start/end trim sliders on the recorded-take
  preview with **actual export** — Media3 Transformer (ClippingConfiguration)
  on Android, AVAssetExportSession with timeRange on iOS. Trimmed bytes
  replace the original for upload; export failures fall back to the
  untrimmed original with a visible message. The quick-edit path is
  complete: record → preview → trim → publish.

Not yet done (see ledger): NIP-46/55 remote signers (ID-006/007), NWC
wallet (SOC-007), MEM Studio (Phase 6).

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
