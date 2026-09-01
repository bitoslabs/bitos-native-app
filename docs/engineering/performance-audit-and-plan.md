# Performance & UX Audit — Feed, Bitz Video, Full System (2026-02)

> **Implementation status (2026-08 follow-up, Phase 1–5 slices):**
>
> | Plan item | Status |
> |---|---|
> | R11 window field-loss fix + contract tests (Kotlin bridge + Swift) | ✅ done |
> | Verify-once outcome cache (shared codec, both platforms, all 9 stores) | ✅ done — `NostrEventCodec.verifySignature` |
> | `FeedAggregator` incremental ordering (no per-event re-sort) | ✅ done |
> | iOS ingest off the main actor (`FeedStore` detached decode task) | ✅ done |
> | Publish coalescing ~150 ms (iOS `FeedStore.schedulePublish`, Android `FeedRepository.requestPublish`) | ✅ done |
> | Batched persistence (iOS `EventStore.insertBatch` + WAL, Android `SqliteEventCache.upsertAll`) | ✅ done |
> | R9 keepalive: iOS `RelayPool` 25 s client pings, failed ping → reconnect path | ✅ done |
> | R8 posters: ImageIO off-main decode at rendered size; no flash-to-nil on scroll (Home + Bitz) | ✅ done |
> | PlayerPool reconciliation: settled-id lookup once, direct adjacent indices, no full-window id dictionary allocation (iOS); bounded id slots (Android) | ✅ done |
> | R10 single KMP client in `AppEnvironment` (no duplicate framework init) | ✅ done |
> | Android WAL (`SqliteEventCache.onConfigure`) + LazyColumn `contentType` | ✅ done |
> | U1 splash hold — already resolved: `BootSplashScreen` is disabled at app entry (fast-access decision 2026-08-28); plan correction | ✅ n/a |
> | Phase 0 instrumentation shipped: iOS `Perf` signposts (`relay.decode`,
> `feed.publish`, `poster.decode`) + Android `PerfTrace` sections; capture
> runbook at [`performance-baselines.md`](./performance-baselines.md) | ✅ done |
> | Phase 0 first device captures (fill the baseline table) | ⬜ open — needs physical devices |
> | Bitz `videos` derivation: `FeedStore.videoNotes` derived once per
> publication (no per-body 200-note filter) | ✅ done |
> | UX U2 skeletons: Home rows + Bitz grid tiles on both platforms (reduce-
> motion honored by the existing skeleton components — U10 for skeletons) | ✅ done |
> | UX U7 "You're all caught up" end-of-timeline boundary (Home list + Bitz
> grid, both platforms) | ✅ done |
> | UX U9 data saver / video quality: shared pick rule (AUTO/HIGH/LOW,
> ≥360p floor) wired into BOTH players; setting picker already existed —
> now it actually changes playback (slot rebuild on change) | ✅ done |
> | R10 tab-gated shell: iOS tabs compose on FIRST selection, then stay
> alive (exact state preservation; hidden resources already released by
> disappear handlers) — cold launch pays one tab's body cost, not five | ✅ done |
> | MetricKit field diagnostics (hang/launch/crash counts, redacted) wired
> at launch — closes the loop once internal builds ship | ✅ done |
> | UX U8 duration affordance: tile duration badge (shared formatter,
> both platforms, hidden while unknown) | ✅ done |
> | R13 identity cold-start & signing (2026-09): iOS init is registry-only
> (zero Keychain/crypto before first frame) with async epoch-guarded restore;
> derives & signs off-main on both platforms; session-cached signing secret
> (active slot only); derive-free confirm; active-slot-first DM reveal/unwrap;
> reactive import advance replacing sync preview checks | ✅ done |
> | Android shell state isolation: distinct scalar badge flows at the shell; feed profiles collected only by Chats/zap consumers; author state only by its active overlay | ✅ done |
> | Bitz pager projection: author-window dependency fixed on Android; empty-splice steady state reuses the existing bounded video list on both platforms | ✅ done |
> | Android AUTO rendition bucket uses logical display height instead of a fixed 1920 target (High/Low semantics unchanged) | ✅ done |
> | UX U2–U10 checklist | ✅ closed (U10 = splash already off + skeletons honor reduce-motion) |
> | First device captures (fill the baseline table) | ⬜ open — needs physical devices |
>
> Verification: `:shared:business-core:macosArm64Test` (445 tests),
> `:apps:android:testDebugUnitTest` (75 tests) + `assembleDebug`, iOS target
> type-checked under Swift 6 / strict concurrency (no simulator runtime on
> this machine — run `BitOSTests` on a simulator before merging). Known
> flake to watch: `NotePublisherTest.surfacesRejectionReasons` failed once
> under forced full-suite rerun under load; passes isolated and in normal
> full runs — same transient-size-vs-stable-state fragility class as the
> notification test fixed in Phase 1.

Audit scope: slow load, jerky/hanging UI rendering, feed ingestion, Bitz video
pager/grid, startup, shared BusinessCore hot paths, relay transport, and UX/UI
friction — iOS (SwiftUI), Android (Compose), shared `business-core`, media
pipeline. Every finding cites the owning file. This document is the working
plan; budgets live in [`native-performance.md`](./native-performance.md).

Severity: **P0** = causes visible hangs / multi-second stalls today;
**P1** = major cost under burst or scroll; **P2** = steady-state waste or UX
friction; **P3** = polish.

---

## 1. Executive summary — ranked root causes

| # | Root cause | Layer | Severity | Effect users feel |
|---|---|---|---|---|
| R1 | Every relay frame is decoded, ID-hashed and **Schnorr-verified 9× per event** (9 independent store collectors on each platform) | iOS + Android + shared | **P0** | Cold feed load takes seconds; UI freezes while events burst in |
| R2 | On iOS all of that verification **runs on the @MainActor** (`FeedStore.absorb` inherits main isolation) | iOS | **P0** | Frozen frames, jerky scroll, "hang" on feed open |
| R3 | `publishState()` re-derives the **entire UI projection per single event** (no coalescing): full window snapshot → filter → rank → thread assembly | iOS + Android | **P0** | O(events × window) CPU during bursts; StateFlow conflation hides it on Android but ingestion slows to a crawl |
| R4 | Pure-Kotlin BIP-340 (`SchnorrVerification` + custom `Fp256` bigint, 2 scalar mults/event) with **no batch/parallel strategy and no cheap pre-filter** | shared | **P0/P1** | Multiplies R1×R2; ~1–10 ms per verification × 9 collectors × N events |
| R5 | `FeedAggregator.snapshot()` cache is invalidated by every insert, then re-sorted (O(n log n)) inside `absorbNote`'s hold-check **and** `publishState` | shared | **P1** | O(n² log n)-ish during initial EOSE burst of 200+ events |
| R6 | Persistence is **one implicit transaction per event** (per-event `Task.detached`/`scope.launch`, statement compiled per insert on Android), no WAL, no batching | iOS + Android | **P1** | Disk churn during bursts; slow cold-start hydrate; battery |
| R7 | iOS bridge seam does **full Kotlin↔Swift struct conversion of the whole window** on every `snapshot()` and JSON-string round-trips for ranking (`rankedForYou`) | iOS | **P1** | Per-publish allocations of 200+ notes × 2 windows; string-built JSON incl. entire follow set |
| R8 | `PosterImage` (Home inline video poster) decodes at **full-screen × scale** regardless of rendered size; poster views flash `nil` on scroll before cache hit | iOS | **P1** | Scroll jank + memory spikes (a 1290×2796 decode for a ~300 pt card) |
| R9 | iOS `RelayPool` has **no client-initiated WebSocket ping** (Android OkHttp has 25 s) — idle sockets silently die, recovery rides the 2 s health poll + backoff | iOS | **P1** | "Feed is slow/empty" after backgrounding: wait for reconnect + re-REQ |
| R10 | Startup: `TabView` composes **all 5 heavy tabs** at first frame; `AppEnvironment` opens SQLite + initializes the KMP framework twice synchronously; splash enforces a 0.9 s brand hold | iOS | **P2** | Perceived cold-launch slowness before any data work |
| R13 | Identity cold-start & signing: `IdentityStore.init` performs a **synchronous Keychain read + secp256k1 derive + npub encode on the MainActor before the first frame** (~51–62 ms derive + keychain I/O, every launch); signing re-reads Keychain and signs (~100–117 ms) on main; Android `identityFor` scalar-multiply also ran on the main thread; `confirmPreview` derived twice | iOS + Android | **P1** | Multi-100 ms stall at every launch and on every key action; wrong-slot DM reveal/sign paths after account switch |
| R11 | **Correctness bug found during audit:** `SharedFeedWindow.snapshot()` drops `threadRootId/threadParentId/pollOptions/remixOfEventId/remixOfPubkey/license/fallbackUrls/renditionSpecs` — every note that passes through the window loses thread anchors, polls, remix and rendition-ladder data | iOS | **P0 (bug)** | Polls/remix/thread UI broken for windowed notes; Bitz rendition failover degraded |

Secondary findings (image pipeline, profile fan-out, UX friction, missing
benchmarks) are detailed per-layer below.

---

## 2. Shared BusinessCore (KMP) findings

### 2.1 Verification cost model (R4) — `crypto/SchnorrVerification.kt`
- `verify` performs `liftX` + **two full 256-bit scalar multiplications**
  (`Secp256k1.multiply`) in hand-rolled fixed-window bigint
  (`Fp256`/`Secp256k1`), plus a SHA-256 tagged hash. On mid-range ARM this is
  realistically 1–10 ms per event.
- Call volume today: 9 collectors/platform × every EVENT frame
  (feed kinds, reactions, zap receipts, profiles…) — see R1. A 300-event
  initial burst ⇒ up to **2,700 verifications** on iOS, ~all on the main actor.
- The architecture rule "verify before projection" is correct; the *fan-out*
  violates it in practice: verification should happen **once per event per
  process**, not once per store.

Fix direction (keeps the rule, changes the owner):
1. Introduce a single **`VerifiedEventBus` / relay-ingest stage owned by the
   relay transport layer**: decode + hash-check + verify **once**, then
   multicast `VerifiedEvent`s (already carrying subscription id) to stores.
   Stores keep pure policy logic only.
2. Add cheap gates before field math (many relays send garbage bursts):
   length bound exists; add pubkey/sig hex-shape pre-checks (already partly in
   `parseHexBytes`) and a small **LRU of recently-verified (id, pubkey) pairs**
   so the 9 collectors deduplicate naturally, and duplicate relay frames from
   the 4-relay fan-out (very common: same event from 3 relays) verify once.
3. Optional next step (bigger): move verification to a workers dispatcher with
   parallelism = cores, streaming results in arrival order into the bus.
   Pure-Kotlin stays (no native crypto dependency) — parallel + once-per-event
   is already a ~20× improvement; revisit secp256k1-zkp bindings only if
   profiling still shows verification after (1)+(2).

### 2.2 FeedAggregator re-sorting (R5) — `feed/FeedAggregator.kt`
- `insert()` nulls `sortedCache`; `absorbNote` calls `snapshot()` (hold
  check) before every insert and `publishState` calls it again after ⇒ the
  200-item window is **fully re-sorted per event**, twice.
- `trimIfNeeded()` does an O(n) min-scan per insert when full (steady state).
- Fix: replace `LinkedHashMap` + sort-on-read with a **sorted structure keyed
  by (createdAt, id)** (e.g. maintain an insertion index/arraylist with binary
  insert — 200 items ⇒ ~8 comparisons per insert) and make `snapshot()` return
  the backing order O(1). Keep `prepend(visibleOrder:)` semantics for stable
  live-arrival ordering.

### 2.3 Bridge/JSON ranking seam (R7, iOS-specific manifestation)
- `FeedStore.rankedForYou` builds JSON **strings** for the whole window, the
  entire `followingAuthors` set, zap counts, reply counts, dismissed/muted sets
  on **every** `publishState`, then parses ids back out of the bridge.
- `BusinessCoreBridge.Note` ↔ Swift `FeedNote` conversion copies ~24 fields ×
  window size × (2 windows + bookmarked lookup) per publish.
- Fix: rank **inside** shared core (Android already calls `FeedRanking.rank`
  with typed objects). Expose one typed `rankWindow(notes, ctx)` entry the iOS
  client calls once per coalesced publish; or keep the JSON seam but invoke it
  only on coalesced ticks (see 3.1). Long-term: generate the Swift mirror of
  `FeedNote` once in the ingest stage and never round-trip through the bridge
  for ranking.

### 2.4 FeedNote/window data loss (R11) — iOS `SharedFeedWindow.snapshot()`
- The Kotlin `Note` carries thread anchors, poll options, remix source,
  license, fallback ladder — the Swift mapping stops at `contentWarning`.
- Fix: map every field (mechanical), and add an iOS unit test asserting a
  `FeedNote` survives an insert→snapshot round trip field-for-field
  (adapter-contract suite per architecture.md §11).

---

## 3. iOS findings

### 3.1 FeedStore main-actor hot path (R1, R2, R3) — `Platform/FeedStore.swift`
- `collectTask` inherits `@MainActor` isolation; `absorb(frame)` runs
  `client.decodeVerifiedEvent` (JSON + schnorr in KMP), `feedNote(from:)`
  bridge conversion, `richTokens` + `JSONSerialization` mention parsing, and
  `publishState()` per frame — all on the main thread. The code comment even
  anticipates this ("if this ever shows in instruments…").
- `publishState()` per event also re-runs `rankedForYou`, thread assembly
  cache checks, bookmark projection, poll tallies.
- Fixes (ordered):
  1. **Coalesce**: buffer absorbed events; publish on a 100–250 ms tick (or
     first-frame + debounce) using `Task.sleep` drain. One publish per tick
     max. Keep an immediate publish for user-intent paths (reveal, filter
     change, mute).
  2. **Offload ingest**: verify/decode in the pool's receive stream (a
     background task inside `RelayPool` or a dedicated ingest actor) *before*
     hopping to the store; the store receives `VerifiedEvent` values. This
     single change moves ~95% of burst CPU off the main actor.
  3. **Share verification**: with a process-wide `VerifiedEventBus` (2.1),
     the other 8 stores stop verifying entirely — Inbox, DM, Stories,
     Search, Author, ProfileLookup, HashtagFollows, NotePublisher all
     currently run `decodeVerifiedEvent` on main.
  4. `richTokens` mention parsing: parse the token JSON **once** in the
     ingest stage (it already parses content) instead of re-parsing a JSON
     string per note per store.
- `enqueueProfile` uses `profileQueue.contains` (O(n) array scan) — use an
  ordered set; minor at 48-batch scale but free to fix.

### 3.2 RelayPool keepalive (R9) — `Platform/RelayPool.swift`
- No `sendPing` loop. URLSession answers server pings, but most relays expect
  client pings; idle sockets die silently and the app only notices via receive
  error → reconnect backoff → re-REQ. After backgrounding, the feed appears
  "slow" while sockets churn.
- Fix: 30 s `sendPing` timer per connection (cancel on state change), match
  OkHttp's 25 s; also nil the socket on `urlSession(_:didOpenWithProtocol:)`
  bookkeeping and cap in-flight sends with a small queue so CLOSE/REQ bursts
  can't reorder (send callback ordering is not guaranteed today).

### 3.3 Persistence (R6) — `Platform/Persistence/EventStore.swift` + `persist()`
- `persist()` per event: bridge `tagsToJson` on main + `Task.detached` insert
  ⇒ one transaction per event.
- Fix: batch-buffer verified events in the ingest stage and flush
  **every ~1 s / 64 events inside one transaction** on a utility task; enable
  `PRAGMA journal_mode=WAL` + `synchronous=NORMAL` in the DDL bootstrap
  (shared contract so Android matches); add the missing `created_at DESC`
  index if absent from `EventStoreContract`.
- `hydrateFromCache` decodes 200 rows then absorbs them one-by-one with a
  `publishState()` per profile event (`absorbProfile`→`publishState`) — absorb
  the whole batch, publish once.

### 3.4 Media pipeline (R8) — `Features/Home/HomeView.swift` (`PosterImage`), `PosterImagePipeline`
- `PosterImage` decodes at `max(screen) × scale` regardless of card size.
  Fix: cap by the row's actual layout size (`geo.size * scale` from the
  already-present GeometryReader), and prefer `video.width/height` from imeta
  to compute a fit.
- `BitzPosterImage`/`PosterImage` set `image = nil` at task start ⇒ posters
  flash to background on every pager scroll even when cached. Fix: only clear
  when the URL actually changed and keep the last image until replacement.
- `PosterImagePipeline.image` decodes on a MainActor-inherited task;
  `UIImage(data:)` construction is fine but move the whole path to a
  background actor and hop back with the decoded thumbnail.
- Player: `PlayerPool.update` uses `notes.firstIndex(where:)` + repeated
  `notes.first(where:)` (O(n) per reconciliation, n = window) — pass the
  index (pager already knows it, as Android does) or a dict.

### 3.5 Startup & shell (R10) — `App/RootView.swift`, `App/AppEnvironment.swift`, `BootSplash.swift`
- `TabView` eagerly composes Home+Bitz+Chats+Activity+You bodies on first
  frame. Fix: gate tab content on selection (`if destination == .home` under
  the TabView, or a custom tab bar) while keeping `@State` via
  `.id`-preserved containers; measure with the launch instrument first.
- `AppEnvironment.init` constructs `FrameworkBusinessCoreClient()` **twice**
  (businessCore + defaultEventStore) ⇒ duplicate KMP bridge init; open the
  event store asynchronously (it's already optional) and reuse one client.
- `BootSplashTiming.minDisplay = 0.9 s` enforces an artificial brand hold on
  every launch. Recommend: hold only on first-ever launch (pair with
  onboarding), otherwise drop to ~0.3 s total.
- **(2026-09, done)** R13 identity leg: `IdentityStore.init` used to do the
  Keychain read + secp256k1 derive + npub encode synchronously on the
  MainActor at every launch. Now init is registry-only — the active registry
  row becomes a provisional account (pubkey/npub are public projections
  already persisted, so the signed-in shell renders immediately) and
  `RootView`'s `.task` calls `IdentityStore.restoreActiveSession()`, which
  loads the secret on the actor, derives off-main via a `nonisolated static`
  helper (fresh `BusinessCoreBridge` inside — the KMP class is not Sendable),
  then replaces the provisional account (epoch-guarded against concurrent
  account switches) or drops a dead pointer. Signing (`signLocally`) uses a
  session-cached secret (memory only) — no per-sign Keychain read — and
  hops the ~100 ms `signDetached` off-main through the same helper pattern;
  the Keychain fallback resolves the ACTIVE slot only, never the legacy slot
  (wrong-key signing guard). `confirmPreview` no longer derives (preview
  carries `pubkeyHex`); `switchTo` keeps its signature but task-wraps the
  derive. Android mirrors it: `identityFor` is now `suspend` on
  `Dispatchers.Default`, previews build inside `viewModelScope.launch` with
  `busy` gating, import
  advance is reactive (`LaunchedEffect(state.preview)`), and `revealNsec`
  resolves the active slot first (previously legacy-only — wrong key after
  a switch).

### 3.6 Bitz view specifics — `Features/Bitz/BitzView.swift`
- `videos = environment.feedStore.notes.filter { $0.video != nil }` and
  `playerNotes` are computed properties evaluated on **every body access**;
  during live-arrival publishes this re-filters the 200-note window per frame.
  Fix: derive once per `notes` change (`let videos = ...` captured in a
  stored/@State value updated in `onChange(of: store.notes)` or memoized via
  the coalesced publish tick).
- `environment.feedStore.richTokens(for:)` (bridge + potential JSON parse)
  runs inside `pagerPage` body — move to the ingest stage result stored on
  the note presentation model.
- Pagination edge-trigger logic (`onAppear index >= count - threshold`) is
  duplicated in 3 surfaces (Home list, Home pager, Bitz pager, Bitz grid) —
  consolidate into one shared trigger helper to keep behavior identical when
  the walk policy changes (already shared via bridge constants; the trigger
  plumbing should be too).

---

## 4. Android findings

### 4.1 What's already right
- Verification runs on `Dispatchers.Default` (off main); StateFlow conflation
  protects recomposition; `collectAsStateWithLifecycle`, stable `key = note.id`,
  `derivedStateOf` for near-end, `VerticalPager.settledPage` drives the pool;
  OkHttp pings every 25 s; players bounded to 3 slots with binding publication.
  The guide in `native-performance.md` is largely followed — the remaining
  costs are CPU/battery (ingestion speed), not frame drops.

### 4.2 Ingestion CPU (R1, R3, R5) — `data/feed/FeedRepository.kt`
- Same 9-collector re-verification as iOS (see 2.1): adopt the shared
  `VerifiedEventBus`.
- `publishState()` per event: full window snapshot + `FeedRanking.rank` +
  full `FeedUiState.copy` (maps/sets copied) on every absorbed event. With
  conflation the UI survives, but a 300-event burst does 300 full projections
  and blocks the collector — **ingestion throughput** drops and pending-batch
  drains lag (visible as slow "load more" completion).
- Fix: same coalescing as iOS (sample(100–250 ms) the publish; the collector
  mutates buffers only), then one `publishState` per tick.
- `tallyTargetFor` scans all open threads per kind-7/9735 event — build a
  reverse set of tracked ids (`tallyTargets ∪ thread ids`) once per thread
  window change.

### 4.3 Persistence (R6) — `data/db/SqliteEventCache.kt`
- `upsertVerified` compiles the INSERT per call and runs one transaction per
  event. Fix: `beginTransaction/endTransaction` batch flush (mirror of the
  iOS batcher, same cadence constants via shared contract), WAL is the
  platform default but ensure the shared DDL sets it explicitly.

### 4.4 Compose polish (P2/P3)
- `itemsIndexed(notes, key=…)` lacks `contentType = "note"` — add for reuse.
- `FeedNoteCard` receives ~30 parameters incl. freshly-allocated lambdas per
  row; several (`resolveMentionName`, demotion lookups) can be bound once
  above the list. Verify with recomposition counts after the ingest fixes;
  don't annotate stability without a trace.
- Add **Baseline Profiles** for startup + feed + Bitz journeys and a
  Macrobenchmark module (none exists today) — required by the guide's own
  validation rule.

---

## 5. Relay/network & product-relevant behavior

1. **Duplicate frames**: 4 default relays × same events — the verified-id LRU
   (2.1) plus `knownNoteIds` handle this, but only *after* paying verification.
   Hashing the event id happens before verify in decode — the LRU should key
   on id and short-circuit before field math.
2. **REQ fan-out**: every REQ broadcasts to all sockets including read-only
   nostr.band for writes? Verify `writeUrls` routing is used by NotePublisher
   on iOS too (Android `sendTo` exists; iOS has `broadcast(_:to:)` — audit
   call sites once during the bus refactor).
3. **EOSE aggregation** already exists for head + older pages — good. Keep.
4. **Relay UX**: with R9 fixed, surface a subtle "reconnecting" state instead
   of a spinner-only empty state; `RelayHealth.isLive` exists in state.

---

## 6. UX/UI improvement list (beyond raw performance)

| # | Surface | Issue | Fix |
|---|---|---|---|
| U1 | Boot | 0.9 s forced brand hold every launch | First-launch only; ≤0.3 s otherwise |
| U2 | Home/Bitz cold open | Blank + spinner until relay burst completes (seconds on bad relays) | Cache-first render is built (DAT-003) — ensure window hydrate publishes **before** relay wait (it does; verify after ingest refactor), add skeleton rows matching card layout |
| U3 | Bitz pager | Poster flash-to-black on scroll; first frame of video late | Keep last poster visible under video until first frame renders (`AVPlayerLayer.isReadyForDisplay` / PlayerView `onRenderedFirstFrame`) |
| U4 | Feed | Live arrivals can disturb a reader's anchor | Buffer only while away from the top; auto-merge at top/refresh with no pending-count control; reconnect from the durable head watermark |
| U5 | Bitz grid | Loading tile appears/disappears per walk batch | Show persistent end-of-list spinner while `isLoadingOlder` lane active (state already exists) |
| U6 | Profiles | Anonymous npub rows for up to ~1.15 s after burst (250 ms debounce + 900 ms fallback) | Render npub fallback immediately (already) + cache top-N author metadata locally (extend EventStore kinds beyond kind-0/1/21/22 today? kind-0 is persisted — ensure hydrate fills `profiles` map on cold start, currently only feed kinds + profiles are absorbed: verify order) |
| U7 | Pagination | "Load more" silently does nothing when lane exhausted (two empty pages) | Footer state: "You're all caught up" when `noMoreOlder` |
| U8 | Video controls | Long-press 2× has hint but no scrubber affordance on grid tiles | Add tap-and-hold ripple + progress bar on tiles (parity with player) |
| U9 | Settings | Media autoplay policy exists; no data-saver toggle for renditions | Add "Data saver" → pick lowest rendition ≥360p (shared `mediaPickRenditionUrl` already parameterized by target height) |
| U10 | Accessibility | Reduce-motion not honored by pager snap animations | Gate `withAnimation`/skeleton shimmer on `accessibilityReduceMotion` |

---

## 7. Improvement plan (phased, with acceptance criteria)

Each phase lands behind the measurement step before it; budgets are the
tables in `native-performance.md` §2. No phase weakens the layer rules in
`architecture.md` — every fix names its owning layer.

### Phase 0 — Measurement harness (blocking for later claims)
- iOS: `os_signpost` intervals for `relay.decode`, `relay.verify`,
  `feed.publishState`, `poster.decode`; MetricKit hitch diagnostics in internal
  builds; Instruments launch template saved in repo.
- Android: Macrobenchmark module (startup, scroll Home, swipe Bitz, load-more),
  Perfetto custom sections matching the signposts above; Baseline Profile
  generated for the three journeys.
- Device matrix: 1 low (2 GB Android / iPhone SE class), 1 median. Record
  relay set + cache state per `native-performance.md`.
- **Exit**: baseline numbers committed to `docs/engineering/` for all six
  budget rows before any P0 fix merges.

### Phase 1 — Kill the main-thread stalls (P0, iOS-first)
1. iOS ingest offload: decode+verify in `RelayPool` receive stream → hop
   `VerifiedEvent` to stores. *(Owner: iOS platform; shared codec unchanged.)*
2. Publish coalescing (iOS `FeedStore`, Android `FeedRepository`): 150 ms
   tick drain; immediate publish for user intents.
3. Fix R11 field-loss bug + adapter-contract test.
4. **Exit**: hitch ratio <5% / zero frozen frames during cold EOSE burst
   (budget row 4); `relay.verify` signpost shows 0 ms main-thread samples.

### Phase 2 — Verify-once bus (P0 cross-platform)
1. `VerifiedEventBus` in shared core: id-LRU dedupe + verified multicast with
   subscription id; native adapters subscribe all 9 stores.
2. Parallel verify on `Dispatchers.Default` / Swift concurrency pool.
3. **Exit**: verifications per relay event = 1 (signpost/trace count);
   burst ingest ≥ 5× faster than Phase-0 baseline.

### Phase 3 — Window & persistence (P1)
1. `FeedAggregator` ordered-insert index; O(1) snapshot.
2. Batched transactional event-store writes (shared cadence constants, WAL,
   index check) on both platforms; hydrate absorbs batch → single publish.
3. iOS ranking via typed shared entry (drop per-publish JSON string build);
  snapshot conversion once per coalesced publish, not per store call.
4. **Exit**: "event accepted → visible" p95 ≤ 100 ms (budget row 6) on median
   device with 300-event burst; cold hydrate → first content ≤ 500 ms cached
   (budget row 3).

### Phase 4 — Media & scroll quality (P1/P2)
1. Poster decode at rendered size everywhere; no flash-to-nil on scroll.
2. Player reconciliation by index/dict (both platforms); first-frame poster
   crossfade (U3).
3. Bitz `videos`/`playerNotes` derived once per publish; row work audit
   (richTokens precomputed at ingest).
4. **Exit**: Home/Bitz scroll janky frames <5% at grid-decode sizes (budget
   row 4); poster memory bounded by existing 96/48 MiB limits.

### Phase 5 — Transport & startup (P1/P2)
1. iOS ping keepalive + send queue; reconnect UX state (R9, U-reconnect).
2. Tab-gated shell composition; single KMP client; async store open;
   splash policy U1.
3. Android Baseline Profiles wired to CI artifact.
4. **Exit**: cached cold launch p95 ≤ 2.5 s (row 2); no relay zombie state
   after 60 s idle + background/foreground cycle.

### Phase 6 — UX polish & regression gates (P2/P3)
1. U2–U10 checklist items, each with before/after capture.
2. Perf CI: Macrobenchmark regression job + iOS XCTest measure blocks for
   `FeedAggregator` and the batch writer; alert on budget breach.
3. Update `native-performance.md` budgets where tightened; ADR only if the
   bus changes a platform boundary (it does not — it is a shared-core service
   consumed through the existing bridge seam; if the seam shape changes,
   write the ADR then).

### Sequencing note
Phase 1.1 and 2.1 overlap deliberately: the iOS offload can land first with
per-store verification intact, then the bus removes the duplication. Do not
batch Phase 3 with Phase 2 — the window/persistence changes are independently
revertible and each needs its own before/after trace.

---

## 8. Runtime smoke checklist (device session)

Quick manual pass alongside the baseline captures — each item maps to a
change in this program that could only be type-checked, not run:

- [ ] **Deferred tabs (R10):** cold launch → only Home composes; first tap
  on Bitz/Chats/Activity/You renders each surface correctly; switching back
  and forth preserves scroll position and sheet state.
- [ ] **Cold feed load:** no frozen frames while the initial relay burst
  lands; notes appear progressively (≤150 ms publication cadence).
- [ ] **Video quality (U9):** Settings → Video quality → Low while a video
  plays → the visible video re-prepares at a lower rung (brief blip is
  expected); High restores. Verify on a kind-22 note with a rendition
  ladder (imeta `fallbackrendition`).
- [ ] **Poster crossfade (U3/R8):** swipe the Bitz pager quickly — posters
  never flash to black; memory stays bounded (debug gauge).
- [ ] **Caught-up footers (U7):** scroll Home to exhaustion (or a thin
  relay) → "You're all caught up" appears; same on the Bitz grid.
- [ ] **Duration badges (U8):** Bitz grid tiles show `m:ss` when imeta
  duration exists; no badge when absent.
- [ ] **Skeletons (U2):** cold Home/Bitz with empty cache → skeleton rows /
  tiles (not spinners); honor Reduce Motion (static, no shimmer).
- [ ] **Keepalive (R9):** leave the app idle >60 s, background/foreground →
  feed is live without a visible reconnect stall.
- [ ] **Data loss regression (R11):** open a poll note and a remix note from
  the feed — poll options render, remix chain opens (these fields used to
  vanish through the window round trip).

## 9. Findings index (file map)

| Concern | File |
|---|---|
| Main-actor absorb/verify | `apps/ios/BitOS/Platform/FeedStore.swift` (`absorb`, `collectTask`) |
| Per-event publishState | same + `apps/android/.../data/feed/FeedRepository.kt` (`publishState`) |
| 9× re-verification | `apps/ios/BitOS/Platform/*/…Store.swift` (9 files), `apps/android/.../data/**` (9 files) |
| Schnorr cost | `shared/business-core/.../crypto/SchnorrVerification.kt`, `Secp256k1.kt`, `Fp256.kt` |
| Window re-sort | `shared/business-core/.../feed/FeedAggregator.kt` |
| iOS field-loss bug | `apps/ios/BitOS/Platform/Business/BusinessCoreClient.swift` (`SharedFeedWindow.snapshot`) |
| iOS JSON ranking | `FeedStore.rankedForYou` |
| Event cache writes | `apps/ios/.../Persistence/EventStore.swift`, `apps/android/.../data/db/SqliteEventCache.kt` |
| No WS ping (iOS) | `apps/ios/BitOS/Platform/RelayPool.swift` |
| Poster decode size/flash | `apps/ios/.../Features/Home/HomeView.swift` (`PosterImage`), `Features/Bitz/BitzView.swift` (`BitzPosterImage`), `Platform/Media/PosterImagePipeline.swift` |
| Startup composition | `apps/ios/BitOS/App/RootView.swift`, `AppEnvironment.swift`, `DesignSystem/BootSplash.swift` |
| Bitz re-filter per body | `apps/ios/.../Features/Bitz/BitzView.swift` (`videos`, `playerNotes`) |
| Player O(n) lookups | `apps/ios/.../Playback/PlayerPool.swift` (`update`) |
| Compose polish | `apps/android/.../ui/feed/FeedScreen.kt` (contentType), `ui/components/FeedNoteCard.kt` |
