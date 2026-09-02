# BitOS Native Delivery Plan and Task Ledger

## Progress log

- 2026-08-27 — Foundation slice: NIP-01 codec + ID verification, bounded
  decode, feed normalization/aggregation, relay pools and five-tab UI on
  both apps; verified read-only feed.
- 2026-08-27 — SBC-001/SBC-002 done: `BusinessCore.xcframework` (device +
  universal simulator) built by `scripts/ios-build.sh`, linked into the iOS
  app; Swift mirror deleted, `BusinessCoreClient` facade is the seam.
  SBC-019 partial: `macosArm64Test` native lane runs the common suite on
  Apple; public-API dump/binary-compatibility gates still pending.
- 2026-08-27 — SBC-006 done (spike complete): pure-Kotlin BIP-340 Schnorr
  verification in `shared/business-core` (`crypto/Fp256.kt`, `Secp256k1.kt`,
  `SchnorrVerification.kt`); enforced in both platform display paths.
  PRO-001 done: 9-vector fixture set (valid across two keys; wrong-sig,
  tampered-sig, s=n, r=0, off-curve pubkey) generated deterministically by
  `scripts/generate-verification-vectors.mjs` with nostr-tools/@noble and
  reference-verified; suites run on JVM host and native macOS. Signing
  stays out of shared code (ID epic owns it); audited/native-accelerated
  verifier swap is an optimization behind the same vectors.

- 2026-08-27 — SBC-006 done (spike complete): pure-Kotlin BIP-340 Schnorr
  verification in `shared/business-core` (`crypto/Fp256.kt`, `Secp256k1.kt`,
  `SchnorrVerification.kt`); enforced in both platform display paths.
  PRO-001 done: 9-vector fixture set (valid across two keys; wrong-sig,
  tampered-sig, s=n, r=0, off-curve pubkey) generated deterministically by
  `scripts/generate-verification-vectors.mjs` with nostr-tools/@noble and
  reference-verified; suites run on JVM host and native macOS. Signing
  stays out of shared code (ID epic owns it); audited/native-accelerated
  verifier swap is an optimization behind the same vectors.
- 2026-08-27 — DAT-001/003 done, DAT-002 partial: versioned event-store
  contract in the shared core (DDL, bounds, migrations, tags codec);
  Android `SqliteEventCache` (SQLiteOpenHelper) and iOS `EventStore`
  (sqlite3, DDL via bridge) execute the same schema; both apps persist
  verified events and hydrate the feed on cold start (repo contract test
  proves restart hydration; iOS store host-verified on macOS). Room
  deferred: no KSP release for Kotlin 2.4.x — documented deviation, swap
  isolated behind the `EventCache` port.

- 2026-08-27 — DAT-001/003 done, DAT-002 partial: versioned event-store
  contract in the shared core (DDL, bounds, migrations, tags codec);
  Android `SqliteEventCache` (SQLiteOpenHelper) and iOS `EventStore`
  (sqlite3, DDL via bridge) execute the same schema; both apps persist
  verified events and hydrate the feed on cold start (repo contract test
  proves restart hydration; iOS store host-verified on macOS). Room
  deferred: no KSP release for Kotlin 2.4.x — documented deviation, swap
  isolated behind the `EventCache` port.
- 2026-08-27 — FED-001/002 foundation, FED-003 partial: shared kind-22
  media parsing (imeta / url+m / legacy links, bounded) on `FeedNote.video`;
  feed subscriptions now include kind 22. Home is a vertical pager on both
  apps with three-slot playback pools (AVQueuePlayer / Media3 ExoPlayer),
  only-settled-page playback, id-keyed slot lifetimes, caption overlay +
  action rail, minimal poster loading. Rendition policy, player cache
  bounds and codec fallbacks (FED-004/005) remain open.

- 2026-08-27 — FED-001/002 foundation, FED-003 partial: shared kind-22
  media parsing (imeta / url+m / legacy links, bounded) on `FeedNote.video`;
  feed subscriptions now include kind 22. Home is a vertical pager on both
  apps with three-slot playback pools (AVQueuePlayer / Media3 ExoPlayer),
  only-settled-page playback, id-keyed slot lifetimes, caption overlay +
  action rail, minimal poster loading. Rendition policy, player cache
  bounds and codec fallbacks (FED-004/005) remain open.
- 2026-08-27 — ID-001–ID-004 foundation: BIP-340 signing + NIP-19 codecs in
  the shared core (66-test suite incl. @noble-locked signing and bech32
  vectors on JVM + native macOS); `IdentitySigner` port with secrets
  confined to function arguments; Android Keystore-sealed `SecureKeyStore`
  + Keychain on iOS; create/import flows with visible-npub confirmation
  and replace warnings on both apps. Found+fixed an infinite loop in mod-n
  reduction (carry limb never cleared) exposed by the signing tests.

- 2026-08-27 — ID-001–ID-004 foundation: BIP-340 signing + NIP-19 codecs in
  the shared core (66-test suite incl. @noble-locked signing and bech32
  vectors on JVM + native macOS); `IdentitySigner` port with secrets
  confined to function arguments; Android Keystore-sealed `SecureKeyStore`
  + Keychain on iOS; create/import flows with visible-npub confirmation
  and replace warnings on both apps. Found+fixed an infinite loop in mod-n
  reduction (carry limb never cleared) exposed by the signing tests.
- 2026-08-27 — PUB-001/007/008 note path: shared `NoteComposer` + client
  EVENT-frame decode in the codec (own frames pass the verified gate);
  Android `NotePublisher` + composer sheet, iOS `NotePublisher` + sheet;
  write-relay targeting, per-relay receipts, first-acceptance semantics,
  signer-refusal and rejection surfacing. Publisher/repo contract tests
  run on JVM. Found+fixed a structural race in the Android relay pool
  (no-replay merged flow dropped frames between launch and subscription —
  now a 32-frame replay window, harmless because consumers dedupe by id)
  and a corrupted pbxproj children line (extra-zero IDs orphaning two
  source files).

- 2026-08-27 — SOC-001/003 partial: `ContactList` (kind-3) + `composeReaction`
  (NIP-25 kind-7) in the shared core; `UnsignedNote` generalized to
  kind+tags so reactions reuse the exact publish machinery (own frames pass
  the verified gate, incl. tag serialization). Following timeline live on
  both apps (kind-3 → authors-targeted window); like buttons publish real
  reactions for signed accounts. Kind-3 fixture added to the vector set
  (reference-verified). Repository/publisher contract tests cover the
  contact→follow→fan-in flow and the kind-7 publish round trip.

- 2026-08-27 — SOC-002 partial: `composeReply` (NIP-10 `e`+reply marker,
  `p` tag) + tagged `#e` comment REQ in the shared core; comment threads
  (bounded) in both repositories/stores; comment sheets on both apps with
  identity-gated reply publishing through the receipt machine. Contract
  tests: reply composition/frame round-trip (common), comment loading +
  grouping + fan-in (Android repo), reply publish (publisher suite). Found
  +fixed a test race (state committed before sendTo — tests now wait for
  the transport write, not the state flip) and a client-frame vs
  relay-echo shape mismatch in the comment test.

- 2026-08-27 — SOC-001 write path + SOC-003 reposts: `composeFollowList`
  (kind-3, deduped, self-follow filtered, ContactList-bounded) and
  `composeRepost` (kind-6 NIP-18) in the shared core with frame round-trip
  tests; `applyFollowChange` optimistic deltas on both repositories/stores
  returning the new set for the kind-3 publish; follow chips + repost rail
  buttons on both apps. Follow-delta contract test on Android (optimistic
  flip, restore, no-account no-op).

- 2026-08-27 — SOC bookmarks (NIP-51): `BookmarkList` + `composeBookmarkList`
  (kind-30003, empty `d` coordinate, bounded/deduped) in the shared core;
  relay-backed bookmark state on both apps (optimistic flip + publish,
  newer verified head reconciles; signed-out stays local). Bookmark-delta
  contract test on Android.

- 2026-08-27 — SOC-008 client path: `composeZapRequest` (NIP-57 9734) +
  `LnurlPay` pure parsing/building in the shared core (5 new tests: tag
  shape/ID recompute, input rejection, pay-request parse, callback URL +
  invoice, receipt target); `LnurlPayClient` (Android, OkHttp) + zap flow in
  the ViewModel; zap sheets on both apps; kind-9735 counting via `#e` REQs
  (repo contract test: two signed receipts → count 2). Found+fixed a range
  check hole in the bridge callback builder (fake PayRequest bypassed
  min/max — now takes the real bounds explicitly).

- 2026-08-27 — PUB media path infrastructure: Blossom BUD-02 (kind-24242
  upload auth, challenge parsing, percent-encoding) + `UploadedMedia`/
  `imeta`/kind-22 composition in the shared core (5 new tests: auth tags +
  frame round-trip, input rejection incl. expiration window, kind-22 imeta
  round-trip through MediaMetadata, descriptor bounds, challenge/encoding);
  Android `BlossomUploader` with hash-mismatch blocking verified against a
  hand-rolled JDK HTTP server (2 tests). Bridge media seam for iOS.
  Capture/import UI (CAP) feeds it next.

- 2026-08-31 — Fixed Blossom uploads failing against every production
  server (profile avatar/banner, note media, comments — all call sites):
  the client PUT the server root instead of `PUT {server}/upload` (BUD-02)
  and sent the auth token as a percent-encoded `["EVENT",{...}]` relay
  frame instead of BUD-11 `Authorization: Nostr <base64url(event JSON)>`
  (padded). Also removed the unauthenticated-probe/401-challenge round
  trip (real servers answer the probe with 400 + `X-Reason`) — the
  kind-24242 token is now signed upfront with `t/expiration/x/size/server`
  tags and human-readable content, mirroring the working web client; iOS
  now accepts 200 and 201. Shared core gained `Blossom.uploadUrl`,
  `Blossom.authorizationHeaderValue`, Base64url codec and tolerant
  challenge parsing (base64url or legacy percent-encoded); Android/iOS
  uploaders + tests updated, full app typechecks (Swift 6 strict) and all
  tests green. Verified live against blossom.primal.net: 200 +
  hash parity.

- 2026-08-27 — CAP-005 + PUB media path end-to-end: import → publish sheets
  on both apps (GetContent / PhotosPicker, bounded reads, caption, staged
  progress); `publishMediaNote` on both publishers; kind-22 round-trip
  contract test (frame decodes with imeta and parses back through
  MediaMetadata). iOS BlossomUploader mirrors the Android flow with the
  same hash-mismatch blocking rule.

- 2026-08-27 — CAP-001/002 foundation: camera screens on both apps
  (CameraX Recorder / AVCaptureMovieFileOutput) feeding the tested publish
  pipeline; Create tab Record/Import fast paths now action; shared
  MediaPublishViewModel on Android unifies Home import + Create flows;
  iOS camera hands takes to the import sheet state. Permission denied
  states truthful; import path always available.

- 2026-08-27 — SOC-005 partial: `NotificationExtractor` (shared, 4 new
  tests) + `NotificationRepository` (Android) / `InboxStore` (iOS); `#p`
  tagged subscriptions over kinds 1/7/6/9735; kind-badged inbox rows with
  author + time; identity-gated with truthful empty/connecting states.
  Contract test: signed reply → REPLY notification with author + summary.

- 2026-08-27 — SOC-004: `SearchRepository` (Android) / `SearchStore` (iOS)
  with NIP-50 filters + npub resolution through the bridge; Discover
  reworked with real search + topic chips on both apps. `escape` made
  public in the codec (protocol surface used by adapters). Contract test:
  search REQ issued + result fans in verified; npub query issues the
  targeted profile REQ and resolves.

- 2026-08-27 — Author profiles (SOC-001/004): `AuthorRepository` (Android) /
  `AuthorStore` (iOS) with targeted profile+notes REQs through the bridge;
  profile sheets with follow/unfollow inline; author taps on video
  captions on both apps.

- 2026-09-01 — Author Bitz page performance (web parity): `authorRequest`
  now splits filters (profile limit-1, media kinds deep 60, kind-1 shallow
  150 — Nostr `limit` is per relay per filter), and both stores settle
  pages on ALL-RELAY EOSE with a 4 s deadline + CLOSE per page instead of
  a blind 3 s sleep; subscriptions no longer leak across pages.

- 2026-08-27 — ID-008 partial: `composeProfileMetadata` (kind 0, 3 new
  tests: JSON escaping + round-trip through ProfileMetadata, minimal
  empty profile, field validation) + edit sheets on both apps; profile
  publish through the receipt machine with settle states.

- 2026-08-27 — SOC-003 repost display: `RepostParser` (embedded event
  extraction + verification, 3 new tests) + `FeedNote.repostedBy`;
  kind 6 in feed subscriptions; ↻ "Reposted" attribution on both apps.

- 2026-08-27 — SOC-001 moderation: `composeReport` (NIP-56 kind 1984,
  3 tests) + bridge + publisher on both apps; `MuteStore` (Android) and
  UserDefaults-backed mute set (iOS) filtering all feed windows; more
  options dialogs (mute/unmute + report reasons) on both platforms.

- 2026-08-27 — CAP-003 partial (take preview): `VideoPreviewScreen` on
  both apps between camera capture and the publish pipeline; looping
  playback with Use/Retake; bytes unchanged on Use.

- 2026-08-27 — CAP-004 trim: start/end sliders on the take preview on
  both apps; Media3 Transformer clipping export (Android) /
  AVAssetExportSession timeRange export (iOS); fallback to original on
  export failure. Quick-edit path complete: record → preview → trim →
  publish.

## 1. Delivery model

Build vertical slices, not two disconnected UI projects followed by a late integration. Every phase ends with the same user journey working on real iOS and Android devices against local/staging Nostr and media infrastructure.

Recommended core team for credible parallel delivery:

- 2 iOS engineers;
- 2 Android engineers;
- 1 Kotlin Multiplatform/shared-business-core engineer;
- 2 C++/GPU/media engineers;
- 2 backend/media infrastructure engineers;
- 1 product designer with native/accessibility experience;
- 1 QA automation/performance engineer;
- shared product, security, SRE/DevOps and moderation/legal support.

A smaller team can build the product, but scope and calendar must shrink rather than hiding work. With the above team, a strong public V1 is plausibly a 12-18 month program; the advanced editor alone is a multi-quarter product. This is a planning range, not a commitment. Re-estimate after Phases 0, 2 and 5 using measured velocity and device results.

## 2. Priority and ownership labels

- P0: required for the first interoperable creator alpha or its safety.
- P1: required for private/public beta quality.
- P2: Studio differentiation after the reliable core loop.
- P3: later ecosystem/economy work requiring product evidence.

Owners:

- IOS, AND: native platform teams.
- SBC: Kotlin Multiplatform BusinessCore.
- MED: shared C++/GPU/media.
- BE: backend/indexer/worker.
- INF: infrastructure/SRE.
- QA: quality/performance automation.
- PD: product/design/content.
- SEC: security/privacy.

## 3. Phases and exit gates

| Phase                     | Outcome                                                     | Approximate sequence | Exit gate                                                                    |
| ------------------------- | ----------------------------------------------------------- | -------------------: | ---------------------------------------------------------------------------- |
| 0. Contracts and spikes   | Risks are measured before product code scales               |            4-6 weeks | BusinessCore iOS interop, media spike, event fixtures and threat model pass. |
| 1. Native foundation      | Both apps launch, persist accounts and read verified events |            6-8 weeks | Signed fixture feeds render offline/online with no key leakage.              |
| 2. Feed and playback      | TikTok-class vertical reader foundation                     |            6-8 weeks | Three-slot playback meets device budgets and fallback works.                 |
| 3. Capture and quick edit | Creator can make a correct local output                     |           8-12 weeks | Camera -> trim/text -> MP4/poster passes conformance.                        |
| 4. Publish vertical slice | Output becomes interoperable kind-22 media                  |            6-8 weeks | External clients can read it; kill/retry does not duplicate or lose work.    |
| 5. Social beta            | Discovery, interaction, safety and zaps                     |           8-12 weeks | Private beta scenario and moderation/store requirements pass.                |
| 6. MEM Studio             | Differentiating multi-track meme editor                     |          12-20 weeks | Preview/export parity and crash-safe projects pass on both platforms.        |
| 7. Public V1 hardening    | Performance, accessibility and operations                   |           6-10 weeks | SLO, store, threat, restore and release gates pass.                          |
| 8. V1.x/V2                | Draw/Record, batch, marketplace, live                       |      evidence-driven | Each feature gets its own safety/reliability gate.                           |

Phases overlap by discipline after their dependencies stabilize. Do not start marketplace/live while publish recovery or player stability is below gate.

## 4. Release journeys

### Creator-alpha journey

1. Create/import/connect identity.
2. Configure or discover relays and Blossom server.
3. Watch verified kind 21/22/34235/34236 events.
4. Record/import a portrait clip.
5. Trim, add text, select cover and caption.
6. Render, hash, upload, verify, sign and publish kind 22.
7. See relay acknowledgements and the published post in the feed.
8. Kill the app at every stage and successfully resume.

### Public-V1 journey

The creator-alpha journey plus follow, like, NIP-22 comment, repost, bookmark, report/mute, search/discover, zap, notifications, captions/accessibility, data saver, poor-network playback, multi-account isolation and creator analytics.

## 5. Task ledger

### EPIC GOV — product, contracts and governance

| ID      | Pri | Owner  | Task and acceptance                                                                                                                                                     |
| ------- | --: | ------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GOV-001 |  P0 | PD     | Freeze V1 product vocabulary, tab map and creator journey; every route/screen has a named owner and release tier.                                                       |
| GOV-002 |  P0 | PD/ENG | Approve the ADR set: native shells, KMP BusinessCore, C++ MediaCore boundary, kind-22 default, NIP-22 comments, Blossom, local-first private state and modular backend. |
| GOV-003 |  P0 | ENG    | Create repository skeleton from `mobile-architecture.md`; iOS, Android, BusinessCore, MediaCore and contract CI all build from a clean checkout.                        |
| GOV-004 |  P0 | ENG    | Define JSON Schema/OpenAPI/event fixture review rules and compatibility version policy.                                                                                 |
| GOV-005 |  P0 | SEC    | Create data inventory and classification for keys, DMs, drafts, public events, media, wallet, analytics and diagnostics.                                                |
| GOV-006 |  P0 | PD/SEC | Complete age/content, app-store, copyright/license, zap/payment and regional privacy review for V1 scope.                                                               |
| GOV-007 |  P1 | PD     | Define feature flag lifecycle, owner, default, metrics, kill switch and deletion date.                                                                                  |
| GOV-008 |  P1 | ENG    | Maintain `web-parity-audit.md` as web/native behavior changes; every changed surface stays marked Port, Redesign, Later or Reject.                                      |

### EPIC SBC — Kotlin Multiplatform shared business core

| ID      | Pri | Owner       | Task and acceptance                                                                                                                      |
| ------- | --: | ----------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| SBC-001 |  P0 | SBC/IOS/AND | Scaffold common, Android and iOS targets; Android artifact and release-mode XCFramework build from a clean checkout.                     |
| SBC-002 |  P0 | SBC/IOS     | Establish the Swift facade, suspend/async and Flow/AsyncSequence bridge with cancellation, error and leak tests.                         |
| SBC-003 |  P0 | SBC         | Add value types for pubkey, event ID/kind/ref, relay URL, media hash, capability, cursor and stable domain error.                        |
| SBC-004 |  P0 | SBC         | Add injected clock, random ID, hash and dispatcher/test scheduler ports; all reducers/use cases are deterministic.                       |
| SBC-005 |  P0 | SBC/BE      | Implement canonical event serialization, structural validation and NIP-19/21 plus media/comment codecs from shared fixtures.             |
| SBC-006 |  P0 | SBC/SEC     | Complete crypto spike and select audited shared or narrow platform verification; valid/invalid vectors agree on iOS, Android and server. |
| SBC-007 |  P0 | SBC         | Implement replaceable/addressable head, deletion and interaction-target rules with property/golden tests.                                |
| SBC-008 |  P0 | SBC/IOS/AND | Define signer/NIP-46/NIP-55 ports and state machines; common state never contains secret key bytes.                                      |
| SBC-009 |  P0 | SBC/IOS/AND | Define relay gateway and native adapter contract for subscribe cancellation, dedupe, outbox routing and publish receipts.                |
| SBC-010 |  P0 | SBC/IOS/AND | Define event/project/job repository transactions and run one shared adapter suite against iOS SQLite and Android Room.                   |
| SBC-011 |  P1 | SBC         | Port feed candidate normalization, scoring signals, diversity, penalties and explanation model from fixtures.                            |
| SBC-012 |  P1 | SBC         | Implement follow/mute/block/bookmark/reaction/repost/comment/zap optimistic and reconciliation reducers.                                 |
| SBC-013 |  P1 | SBC/SEC     | Implement moderation ordering, content reveal/report decisions, analytics allowlist and redaction helpers.                               |
| SBC-014 |  P0 | SBC/MED     | Implement project semantics, normalization/migration, preflight, edit invalidation and render-snapshot contract.                         |
| SBC-015 |  P0 | SBC/IOS/AND | Implement durable publish reducer/effects for render, hash, upload, verify, sign, relay ACK and reconciliation.                          |
| SBC-016 |  P1 | SBC         | Implement template, sound, remix, license, provenance and value-split validation with hostile size/depth fixtures.                       |
| SBC-017 |  P0 | AND         | Integrate BusinessCore into Android ViewModels while keeping scope, navigation, permission and UI state native.                          |
| SBC-018 |  P0 | IOS         | Integrate BusinessCore through Swift feature stores while keeping task lifetime, navigation and UI state native.                         |
| SBC-019 |  P0 | SBC/QA      | Add common, Android and iOS framework test lanes, public API dumps, binary compatibility and exact artifact provenance.                  |
| SBC-020 |  P1 | SBC/QA/SEC  | Meet parsing/ranking/startup budgets and pass dependency, license, redaction, fuzz and threat review gates.                              |

### EPIC PRO — protocol contracts and interoperability

| ID      | Pri | Owner       | Task and acceptance                                                                                                             |
| ------- | --: | ----------- | ------------------------------------------------------------------------------------------------------------------------------- |
| PRO-001 |  P0 | SBC/BE      | Check in valid/invalid NIP-01 event and signature vectors; BusinessCore and server implementations agree.                       |
| PRO-002 |  P0 | SBC/BE      | Implement typed `EventRef` for regular/addressable events with round-trip and malformed-input tests.                            |
| PRO-003 |  P0 | SBC/BE      | Port kind 20/21/22/34235/34236 parsing fixtures, `imeta` metadata and legacy kind-1 video compatibility.                        |
| PRO-004 |  P0 | SBC/BE      | Build kind-22 golden encoder with title, alt, content warning, tags, poster, hash, duration and fallbacks.                      |
| PRO-005 |  P0 | SBC/BE      | Implement NIP-22 top-level/nested video comment fixtures with correct uppercase/lowercase tags and K/k.                         |
| PRO-006 |  P0 | SBC         | Implement reactions and generic media repost target codecs; external test client reads each event.                              |
| PRO-007 |  P0 | SBC/IOS/AND | Implement shared strict NIP-19/NIP-21 parser and native universal/deep-link routing with confirmation for sensitive operations. |
| PRO-008 |  P1 | SBC/BE      | Implement replacement/deletion resolution and relay-observation provenance; rebuild tests agree.                                |
| PRO-009 |  P1 | SBC/BE      | Define/version BitOS remix, template, sound and value-edge schema with byte caps and hostile fixtures.                          |
| PRO-010 |  P1 | QA          | Maintain interoperability suite against at least two independent relays, Blossom servers and external Nostr clients.            |

### EPIC ID — identity, signing and account isolation

| ID     | Pri | Owner       | Task and acceptance                                                                                            |
| ------ | --: | ----------- | -------------------------------------------------------------------------------------------------------------- |
| ID-001 |  P0 | SBC/IOS/AND | Define `IdentitySigner` and deterministic test signer; feature code cannot access raw secret bytes.            |
| ID-002 |  P0 | IOS         | Local signer in Keychain with access control, biometric policy, backup gate and redaction tests.               |
| ID-003 |  P0 | AND         | Local signer protected by Android Keystore envelope/device credential with backup gate and redaction tests.    |
| ID-004 |  P0 | IOS/AND     | Create/import/QR account flow validates formats and visibly confirms pubkey before destructive replacement.    |
| ID-005 |  P0 | IOS/AND     | Multi-account database/files/job isolation; switching under active playback/upload cannot cross data.          |
| ID-006 |  P1 | IOS/AND     | NIP-46 connect, permission request, auth challenge, relay switch and logout cleanup.                           |
| ID-007 |  P1 | AND         | NIP-55 discovery/sign/encrypt/decrypt with signer rejection/absence/timeout states.                            |
| ID-008 |  P1 | IOS/AND     | Profile metadata and NIP-65 relay-list editor with optimistic state reconciled by verified relay echo.         |
| ID-009 |  P1 | SEC/QA      | Identity threat review, jailbroken/rooted-device posture decision and backup/restore/account-loss test script. |

### EPIC DAT — local persistence and recovery

| ID      | Pri | Owner   | Task and acceptance                                                                                  |
| ------- | --: | ------- | ---------------------------------------------------------------------------------------------------- |
| DAT-001 |  P0 | IOS     | Implement account-scoped SQLite-backed schema and deterministic migrations.                          |
| DAT-002 |  P0 | AND     | Implement Room schema and deterministic migrations equivalent to iOS contracts.                      |
| DAT-003 |  P0 | IOS/AND | Verified event store, replaceable heads, deduplication, relay receipts and bounded feed windows.     |
| DAT-004 |  P0 | IOS/AND | Content-addressed asset catalog, atomic project revisions, refcount and recovery grace period.       |
| DAT-005 |  P0 | IOS/AND | Durable job checkpoint model with idempotency keys, input/output hashes, attempts and stable errors. |
| DAT-006 |  P1 | IOS/AND | Cache budget/eviction classes for poster, stream, offline save, proxy and project-owned asset.       |
| DAT-007 |  P1 | QA      | Migration corpus from every released schema plus corruption/storage-full/process-kill cases.         |
| DAT-008 |  P1 | SEC     | OS backup/file-protection policy proves keys, DMs and unpublished assets follow user promises.       |

### EPIC REL — relay networking

| ID      | Pri | Owner   | Task and acceptance                                                                                          |
| ------- | --: | ------- | ------------------------------------------------------------------------------------------------------------ |
| REL-001 |  P0 | IOS/AND | WebSocket relay pool with lifecycle, backoff/jitter, cancellation, subscription caps and event verification. |
| REL-002 |  P0 | IOS/AND | Read/write relay roles, health/latency test and human-readable connection diagnostics.                       |
| REL-003 |  P0 | IOS/AND | Publish fan-out records every `OK`, timeout and rejection; one ACK is success with partial-state UI.         |
| REL-004 |  P0 | IOS/AND | Feed pagination overlap/dedup handles relay reordering and reconnect without visible duplicates.             |
| REL-005 |  P1 | IOS/AND | NIP-65 outbox routing for author reads and user writes with safe fallback discovery relays.                  |
| REL-006 |  P1 | IOS/AND | Direct-relay degraded Following/Latest mode when API/candidate service is unavailable.                       |
| REL-007 |  P1 | QA/SEC  | Flood, oversized event, malformed JSON, slow relay, clock skew and subscription leak tests.                  |

### EPIC FED — feed, ranking and playback

| ID      | Pri | Owner       | Task and acceptance                                                                                     |
| ------- | --: | ----------- | ------------------------------------------------------------------------------------------------------- |
| FED-001 |  P0 | IOS         | SwiftUI vertical pager and three-slot AVPlayer coordinator with stale-callback protection.              |
| FED-002 |  P0 | AND         | Compose vertical pager and three-slot ExoPlayer coordinator with stale-callback protection.             |
| FED-003 |  P0 | IOS/AND     | Poster, playback controls, caption/alt/content-warning UI and interaction action rail.                  |
| FED-004 |  P0 | IOS/AND     | Rendition/mirror selection uses viewport/network/cache/capability and deterministic fallback order.     |
| FED-005 |  P0 | IOS/AND     | Player cache is bounded under 100-item swipes, rotation/backgrounding and low memory.                   |
| FED-006 |  P1 | IOS/AND     | For You/Following/Latest surfaces with independent cursors and state restoration.                       |
| FED-007 |  P1 | SBC/IOS/AND | BusinessCore ranking/diversity pipeline integrated with native “Why this video?” presentation.          |
| FED-008 |  P1 | IOS/AND     | Not interested, mute author/topic, reset interests, data saver and Wi-Fi prefetch controls.             |
| FED-009 |  P1 | QA          | Device/network/codec matrix meets first-frame, dropped-frame, memory and battery budgets.               |
| FED-010 |  P1 | IOS/AND     | VoiceOver/TalkBack actions expose every gesture action; reduced motion and caption defaults pass audit. |

### EPIC CAP — camera and import

| ID      | Pri | Owner   | Task and acceptance                                                                                    |
| ------- | --: | ------- | ------------------------------------------------------------------------------------------------------ |
| CAP-001 |  P0 | IOS     | AVFoundation capture service off main thread with preview, lens, focus, exposure, zoom, torch and mic. |
| CAP-002 |  P0 | AND     | CameraX capture service with equivalent capability-driven controls and lifecycle recovery.             |
| CAP-003 |  P0 | IOS/AND | Segment record/pause/resume/delete/retake; every finished segment persists before next action.         |
| CAP-004 |  P0 | IOS/AND | Countdown, duration, speed mode and audio route/meter with truthful unsupported states.                |
| CAP-005 |  P0 | IOS/AND | Photos/MediaStore/files/share import copies or security-scopes assets into the catalog safely.         |
| CAP-006 |  P0 | IOS/AND | Permission UX requests on action and handles deny, limited library, device loss and interruption.      |
| CAP-007 |  P1 | IOS/AND | Orientation/color/HDR metadata normalization and device capability report.                             |
| CAP-008 |  P2 | IOS/AND | Dual camera/PIP spike and gated implementation only for passing device tiers.                          |
| CAP-009 |  P0 | QA      | Long record, call/audio interruption, background, low storage, thermal and process kill matrix.        |

### EPIC MED — shared editor and export core

| ID      | Pri | Owner       | Task and acceptance                                                                            |
| ------- | --: | ----------- | ---------------------------------------------------------------------------------------------- |
| MED-001 |  P0 | MED         | Define stable C ABI, ownership/thread/cancel/error rules and ABI version test.                 |
| MED-002 |  P0 | MED         | Parse/validate `com.bitos.studio.project` with caps, migrations and fuzz tests.                |
| MED-003 |  P0 | MED         | Timeline time mapping, clip trim/split/reorder/rate and deterministic frame/sample scheduling. |
| MED-004 |  P0 | MED         | Scene transforms, crop/fit/fill, opacity, z-order, text and sticker commands.                  |
| MED-005 |  P0 | MED/IOS     | Metal backend interoperates with AV pixel buffers and survives context/background changes.     |
| MED-006 |  P0 | MED/AND     | Vulkan backend plus tested GLES fallback interoperates with MediaCodec surfaces.               |
| MED-007 |  P0 | MED         | CPU reference renderer for bounded golden-test feature set.                                    |
| MED-008 |  P0 | MED         | Audio graph schedules source, music, voice and cues with gain/fade/duck automation.            |
| MED-009 |  P0 | IOS/AND/MED | Simple native export and complex shared export emit MP4 H.264/AAC with same project semantics. |
| MED-010 |  P0 | MED         | Shared font assets/shaping/fallback and text layout conformance across platforms.              |
| MED-011 |  P1 | MED         | Filter/effect graph with bounded built-in IDs/parameters and capability fallback report.       |
| MED-012 |  P1 | MED         | Drawing stroke static/replay/hold renderer with simplification and eraser composition.         |
| MED-013 |  P1 | MED         | GIF/animated sticker bounded decode and deterministic frame selection.                         |
| MED-014 |  P0 | QA/MED      | Sanitizers, fuzzers, leak/stress, pixel/SSIM and A/V sync golden lanes in CI.                  |
| MED-015 |  P1 | SEC         | Native parser/ABI/template/effect threat review and third-party license/codec audit.           |

### EPIC EDT — project library, quick editor and MEM Studio

| ID      | Pri | Owner   | Task and acceptance                                                                          |
| ------- | --: | ------- | -------------------------------------------------------------------------------------------- |
| EDT-001 |  P0 | IOS/AND | Unified project library with status, poster, search/sort, archive/delete and storage usage.  |
| EDT-002 |  P0 | IOS/AND | Autosave and visible save failure; kill/relaunch loses no committed operation.               |
| EDT-003 |  P0 | IOS/AND | Quick editor trim/reorder/crop/cover/source audio/text/look/preflight.                       |
| EDT-004 |  P0 | IOS/AND | Command undo/redo coalesces drags/sliders and restores after project reload where supported. |
| EDT-005 |  P1 | IOS/AND | Expert timeline with clips, layers, playhead, zoom, snapping, lock/hide and inspector.       |
| EDT-006 |  P1 | IOS/AND | Text/caption editor with timing, styles, motion and accessibility-safe editing controls.     |
| EDT-007 |  P1 | IOS/AND | Audio lanes with waveform, SFX, custom sound, voice-over, gain/fades/ducking.                |
| EDT-008 |  P1 | IOS/AND | Image/GIF/sticker/SVG-raster layers with crop/motion/timing and hostile-asset handling.      |
| EDT-009 |  P1 | IOS/AND | Speed, zoom, frame effects, transitions and preview/export capability report.                |
| EDT-010 |  P1 | IOS/AND | Review/preflight lists blockers/warnings and focuses the exact repair control.               |
| EDT-011 |  P1 | IOS/AND | Relink, duplicate, schema migration and missing-capability flows preserve original revision. |
| EDT-012 |  P2 | IOS/AND | Project history checkpoints, brand presets and output/proxy estimator.                       |
| EDT-013 |  P0 | QA      | Keyboard/switch-control/stylus/touch, screen reader and 320-equivalent narrow layout audit.  |

### EPIC MEM — meme, template, sound and remix system

| ID      | Pri | Owner      | Task and acceptance                                                                               |
| ------- | --: | ---------- | ------------------------------------------------------------------------------------------------- |
| MEM-001 |  P1 | IOS/AND    | Import web `com.bitos.bitz.meme` v1 fixtures into native projects without visual/timing loss.     |
| MEM-002 |  P1 | IOS/AND    | Classic/free caption, look, sticker, SFX and timed effect quick paths.                            |
| MEM-003 |  P1 | IOS/AND    | Sound page/library, license/provenance display, preview and “Use sound.”                          |
| MEM-004 |  P1 | IOS/AND/BE | Template publish/read/library with capability, license, preview and hostile-schema limits.        |
| MEM-005 |  P1 | IOS/AND/BE | Remix/create flow preserves root, parent, asset provenance and attribution.                       |
| MEM-006 |  P2 | IOS/AND    | Mild/Funny/Chaos suggestions are opt-in, reviewable and undoable; no silent project mutation.     |
| MEM-007 |  P2 | IOS/AND    | Caption-to-cue/beat plan and live sound pad with deterministic preview/export.                    |
| MEM-008 |  P2 | IOS/AND    | Draw surface, brush inspector, vector persistence and stroke undo/redo.                           |
| MEM-009 |  P2 | IOS/AND    | Performance recording shared clock for drawing, mic, camera and cue taps; take review/recovery.   |
| MEM-010 |  P2 | IOS/AND    | Persistent batch model, bulk apply/validate/queue with storage and concurrency guardrails.        |
| MEM-011 |  P1 | SEC/PD     | License/attribution policy and evidence model for every distributed sound/template/sticker asset. |

### EPIC PUB — upload and publishing

| ID      | Pri | Owner       | Task and acceptance                                                                                     |
| ------- | --: | ----------- | ------------------------------------------------------------------------------------------------------- |
| PUB-001 |  P0 | SBC/IOS/AND | Integrate durable BusinessCore validate/render/hash/upload/verify/sign/publish/reconcile state machine. |
| PUB-002 |  P0 | IOS/AND     | SHA-256 streaming hash, file size/MIME/probe and descriptor validation before signer request.           |
| PUB-003 |  P0 | IOS         | File-backed background URLSession upload reconciles after suspend/termination/relaunch.                 |
| PUB-004 |  P0 | AND         | WorkManager/foreground user-visible upload reconciles after process death/reboot policy.                |
| PUB-005 |  P0 | IOS/AND/BE  | Blossom auth/upload/retrieve/delete client with BUD compatibility fixtures.                             |
| PUB-006 |  P0 | IOS/AND     | Poster/output upload and remote verification; hash mismatch is blocking/security-visible.               |
| PUB-007 |  P0 | IOS/AND     | Build unsigned kind 22 only from ready descriptor; signer rejection preserves ready output.             |
| PUB-008 |  P0 | IOS/AND     | Relay fan-out, partial acknowledgment, retry without resigning and echo reconciliation.                 |
| PUB-009 |  P1 | IOS/AND     | Mirror/fallback policy and BUD-03 server list management with per-host health.                          |
| PUB-010 |  P1 | IOS/AND/BE  | Server-assisted job API and result verification, gated independently from device render.                |
| PUB-011 |  P0 | QA          | Kill app/network/server/signer at every transition; no duplicate render/upload/event and no lost draft. |
| PUB-012 |  P0 | QA          | Published fixtures play in BitOS web plus at least two external Nostr clients/media players.            |

### EPIC SOC — social, discover, messaging and wallet

| ID      | Pri | Owner       | Task and acceptance                                                                                             |
| ------- | --: | ----------- | --------------------------------------------------------------------------------------------------------------- |
| SOC-001 |  P1 | IOS/AND     | Follow, profile, followers/following, mute, block and bookmark with verified reconciliation.                    |
| SOC-002 |  P1 | SBC/IOS/AND | NIP-22 comments/replies UI, shared reducer, pagination, author context, report and optimistic failure recovery. |
| SOC-003 |  P1 | SBC/IOS/AND | Shared reaction/repost reconciliation plus native quote/share and accurate partial aggregate labels.            |
| SOC-004 |  P1 | IOS/AND/BE  | Search/discover creators, videos, hashtags, sounds/templates with direct NIP-50 fallback.                       |
| SOC-005 |  P1 | IOS/AND     | Notification inbox filters and preference state; invalid/deleted source events disappear correctly.             |
| SOC-006 |  P1 | IOS/AND     | NIP-17/44/59 DMs, local decrypt/search, message requests and opaque push routing.                               |
| SOC-007 |  P1 | IOS/AND     | NIP-47 NWC connection with minimum capabilities, visible approval and secure disconnect.                        |
| SOC-008 |  P1 | IOS/AND     | NIP-57 zap request/payment/receipt validation and pending/failure/duplicate states.                             |
| SOC-009 |  P2 | IOS/AND     | Non-atomic value split execution/receipt UX truthfully reports each recipient.                                  |
| SOC-010 |  P2 | SEC         | Separate threat reviews before group signaling, voice/video call or private media message release.              |

### EPIC BE — API, indexer, ranking and media services

| ID     | Pri | Owner | Task and acceptance                                                                                       |
| ------ | --: | ----- | --------------------------------------------------------------------------------------------------------- |
| BE-001 |  P0 | BE    | Local stack for relay, Blossom, object store, Postgres, Valkey, queue, API, indexer and worker.           |
| BE-002 |  P0 | BE    | Indexer verifies/deduplicates events, stores relay observations/cursors and rebuilds projections.         |
| BE-003 |  P0 | BE    | PostgreSQL migrations for raw events, heads, profiles, media, interactions, remix and jobs.               |
| BE-004 |  P0 | BE    | Blossom-compatible service uses immutable hash keys, scoped auth, CORS/range and safe delete policy.      |
| BE-005 |  P0 | BE    | Sandboxed probe/transcode/poster worker is idempotent by input hash + recipe and emits exact descriptors. |
| BE-006 |  P1 | BE    | Feed candidate API with stable cursors, signed events, relay hints and explanation fields.                |
| BE-007 |  P1 | BE    | Search V1 over creators/caption/hashtag/sound/template with pagination and index lag signal.              |
| BE-008 |  P1 | BE    | Aggregate counters retain provenance/coverage and never imply an exact global total.                      |
| BE-009 |  P1 | BE    | Notification fan-out honors preferences, block/mute/deletion and sends opaque APNs/FCM data.              |
| BE-010 |  P1 | BE    | Analytics batch is consent-gated, idempotent, coarse, retention-bounded and schema validated.             |
| BE-011 |  P1 | BE    | Moderation projection, case/audit/appeal model and operator access controls.                              |
| BE-012 |  P1 | QA/BE | Full projection rebuild, duplicate delivery, poison event, cursor and dead-letter integration tests.      |

### EPIC OPS — infrastructure, security and operations

| ID      | Pri | Owner | Task and acceptance                                                                                       |
| ------- | --: | ----- | --------------------------------------------------------------------------------------------------------- |
| OPS-001 |  P0 | INF   | Terraform/OpenTofu local/staging/production modules with isolated credentials, networks, buckets and DBs. |
| OPS-002 |  P0 | INF   | CI uses short-lived workload identity, protected environments, provenance and immutable artifacts.        |
| OPS-003 |  P0 | INF   | Managed Postgres PITR, object policy/versioning, queue DLQ and quarterly restore drill.                   |
| OPS-004 |  P0 | INF   | OpenTelemetry correlation across API/indexer/job/media without content/secret logging.                    |
| OPS-005 |  P0 | INF   | Dashboards/alerts for API, relay lag, queue, worker, object, hash mismatch, push and DB saturation.       |
| OPS-006 |  P0 | SEC   | Secret scanning, dependency/SBOM/license, container/native static analysis and rotation runbook.          |
| OPS-007 |  P1 | INF   | CDN immutable/range behavior, origin protection, purge/takedown and cost/bandwidth alerts.                |
| OPS-008 |  P1 | INF   | SLO/error-budget and incident severity/on-call/communication policy exercised in staging game day.        |
| OPS-009 |  P1 | SEC   | External mobile/API/media penetration test and remediation before public V1.                              |
| OPS-010 |  P1 | INF   | Regional recovery/projection rebuild and service kill-switch drill meets declared RTO/RPO.                |

### EPIC QAR — quality, accessibility and release

| ID      | Pri | Owner  | Task and acceptance                                                                                         |
| ------- | --: | ------ | ----------------------------------------------------------------------------------------------------------- |
| QAR-001 |  P0 | QA     | Test pyramid and fixture ownership documented; flaky-test quarantine has owner and expiry.                  |
| QAR-002 |  P0 | QA     | Real-device lanes cover minimum, median and flagship iOS/Android plus codec-vendor diversity.               |
| QAR-003 |  P0 | QA     | Creator-alpha E2E runs nightly with local signer, NIP-46 and Android NIP-55 variants.                       |
| QAR-004 |  P0 | QA     | Media corpus covers rotation, VFR, HDR, no audio, corrupt/truncated, odd rates and hostile limits.          |
| QAR-005 |  P1 | QA     | Network profiles: offline, loss, latency, captive, relay outage, mirror outage and bandwidth change.        |
| QAR-006 |  P1 | QA     | Accessibility audit includes VoiceOver, TalkBack, Dynamic Type/font scale, contrast and reduced motion.     |
| QAR-007 |  P1 | QA     | Macrobenchmark/MetricKit regression dashboard and release-blocking memory/frame/startup thresholds.         |
| QAR-008 |  P1 | QA     | Battery/thermal/storage 30-minute feed and editor/export soak on reference devices.                         |
| QAR-009 |  P1 | QA/PD  | Localized strings, plural/date/number, RTL, long text and caption-language test pass.                       |
| QAR-010 |  P1 | SEC/PD | Privacy labels/data safety forms, terms, report/block/delete and support/appeal flows approved.             |
| QAR-011 |  P1 | ENG    | Phased rollout, crash/ANR guardrails, rollback/kill switches and prior-version server compatibility tested. |
| QAR-012 |  P1 | QA     | Public V1 release candidate completes a seven-day soak without open release-blocking defects.               |

## 6. Later epics deliberately outside V1

Do not pull these into the critical path without removing equivalent work:

- LIVE: live capture, ingest, low-latency playback, chat/moderation and clip extraction.
- MKT: paid template/sound marketplace, receipts, licensing, refunds and tax/legal operations.
- COL: collaborative projects, conflict resolution, encrypted asset sync and roles.
- AIX: AI captions/translation/meme generation/dubbing with consent, provenance and model operations.
- ALG: public algorithm providers/marketplace and safe execution/verification model.
- COM: shop video, affiliate graph and live commerce.
- P2P: nearby/offline media share and delay-tolerant relay synchronization.

Each later epic requires a separate product spec, threat model, protocol profile, operational owner and exit gate.

## 7. Cross-platform acceptance matrix

Every shared behavior task must prove:

| Contract       | BusinessCore                       | iOS adapter/UI        | Android adapter/UI     | MediaCore            | Backend/Web                       |
| -------------- | ---------------------------------- | --------------------- | ---------------------- | -------------------- | --------------------------------- |
| Nostr event    | Canonical encode/decode/validation | Framework contract    | Direct module contract | N/A                  | Verify/project fixture            |
| Project schema | Normalize/migrate/preflight        | Load/save adapter     | Load/save adapter      | Parse/evaluate       | Web import/export where supported |
| Render effect  | Capability/render request          | Preview/export bridge | Preview/export bridge  | Golden/reference     | Web may degrade explicitly        |
| Publish job    | Reducer/effect decisions           | Background/recovery   | Background/recovery    | Deterministic output | Descriptor/idempotency            |
| Safety rule    | Shared decision ordering           | Native presentation   | Native presentation    | Parser/resource cap  | Projection/operator policy        |

No platform is allowed to silently reinterpret a shared field. If perfect feature parity is impossible, the project declares a capability and the UI offers a deterministic fallback before publish.

## 8. Definition of done

A task is Done only when:

1. Acceptance criteria pass on its declared real-device/service matrix.
2. Unit/contract/integration/UI tests exist at the right boundary.
3. Offline, cancellation, retry, permission denial, empty/error and app-kill behavior is handled.
4. Accessibility and localization behavior is reviewed.
5. Logging/analytics contains only allowlisted fields with redaction tests.
6. Performance/memory/storage/battery impact is measured for media hot paths.
7. Protocol/schema changes include fixtures, compatibility note and migration.
8. Service changes include dashboard, alert, runbook, rollback and capacity/cost impact.
9. Security/privacy review is complete for a changed trust boundary.
10. Documentation and feature/capability matrix are updated.
11. Shared behavior exists once in BusinessCore; both platform adapter suites and the public API compatibility check pass.

## 9. Program risk register

| Risk                                                | Early signal                                    | Mitigation                                                                          |
| --------------------------------------------------- | ----------------------------------------------- | ----------------------------------------------------------------------------------- |
| BusinessCore becomes a cross-platform app framework | UI/lifecycle/platform objects enter common code | Enforce ports/adapters and native feature-store ownership in architecture review.   |
| iOS KMP interop or memory regression                | Boxing, cancellation leaks or launch slowdown   | Swift facade, batched boundaries, release-mode device benchmarks and rollback gate. |
| Shared editor becomes a framework rewrite           | UI/network logic enters C++                     | Enforce C ABI boundary and native ownership in review.                              |
| Preview/export differ                               | Golden A/V timing drifts                        | One timeline model, CPU oracle and device conformance fixtures.                     |
| Android codec/driver fragmentation                  | Device-specific export/player failures          | Media3 first, capability tiers, vendor matrix and server fallback.                  |
| iOS background render expectation                   | Jobs fail after suspension                      | Foreground render checkpoint; background file upload only.                          |
| Key compromise                                      | Secret appears in memory/log/crash              | Signer interface, secure storage, late unlock and automated redaction tests.        |
| Draft/source loss                                   | Kill/storage-full test fails                    | Atomic revisions, refcounted assets and stage-by-stage recovery suite.              |
| Nostr draft changes                                 | Public fixture stops parsing                    | Isolated codecs, read broad/write gated and current-spec review each release.       |
| Media host lock-in/outage                           | URLs fail outside BitOS                         | Hash identity, Blossom contract, mirrors and standard MP4 master.                   |
| Scope explosion                                     | Live/market work starts before publish gate     | Phase exit gates and explicit V1 non-goals.                                         |
| Moderation/app-store block                          | Missing report/age/legal operations             | Safety/legal work begins Phase 0, not release week.                                 |
| Infrastructure cost spike                           | Egress/transcode per publish rises              | Per-job cost telemetry, immutable caching, recipe limits and budgets.               |
| Team cannot support two apps                        | Parity backlog grows                            | Contract fixtures, paired feature ownership and reduce feature scope.               |

## 10. First 30 implementation days

Week 1:

- approve ADRs and V1 scope;
- create native, BusinessCore, MediaCore, contracts and service modules/CI with code ownership;
- import protocol/media fixtures from the web app;
- define project schema v1, BusinessCore ports and C ABI draft;
- stand up the local relay/Blossom/Postgres/object stack.

Week 2:

- scaffold native navigation/design tokens/account database;
- produce the first BusinessCore Android artifact and iOS XCFramework;
- implement shared event verification and kind-22 parsing, then call it from Swift and Android;
- build C++ project parser/timeline test harness;
- start local signer threat model and secure-storage spike.

Week 3:

- read-only direct-relay feed with static posters;
- native one-video player on both platforms;
- integrate native relay/database adapters with BusinessCore feed models;
- C++ one-video + text render spike through Metal/Vulkan/GLES;
- indexer ingests/verifies fixture relays.

Week 4:

- measure player/render spike on the device matrix;
- export the same 10-second project on both platforms and compare goldens;
- run BusinessCore public API, async cancellation, memory and adapter contract gates;
- prove one local key and one external/remote signer flow;
- review results and re-estimate Phases 1-4 before scaling the codebase.
