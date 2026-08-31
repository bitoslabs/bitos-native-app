# Native Performance and Maintainability Guide

## 1. Purpose

This guide defines how BitOS keeps Home, Bitz and top-level navigation fast on
iOS and Android without weakening the repository architecture. It complements
[`clean-code.md`](./clean-code.md); that document remains the authority for
SOLID, naming, error handling and general code quality.

Performance work follows one rule: measure a user-visible problem, fix the
smallest owning layer and verify that behavior did not move into the wrong
layer. A shorter benchmark is not a success if it introduces duplicated
business rules, unbounded caches or unsafe resource ownership.

## 2. Initial performance budgets

Measure release builds on a low supported device and a median device. Record
device, OS, build revision, relay set, cache state and test data with every
result. Simulator/emulator measurements are diagnostic only.

| Journey | Initial budget |
|---|---:|
| Warm top-level tab switch to first complete frame | p95 ≤ 100 ms |
| Cached cold launch to interactive shell | median ≤ 1.5 s; p95 ≤ 2.5 s |
| Cached Home content visible after shell mounts | median ≤ 500 ms |
| Home/Bitz scroll or pager janky frames | < 5%; zero frozen frames |
| Main-thread stall during steady scrolling | no task ≥ 100 ms |
| Verified event accepted to visible UI state | p95 ≤ 100 ms, excluding network |
| Hidden feed video players | zero playing; resources released |

These are regression budgets, not claims about current production devices.
Tighten them after a repeatable baseline exists. A PR that exceeds a budget
must include a trace, cause, owner and explicit follow-up or approved tradeoff.

## 3. Ownership and lifecycle

Long-lived work belongs to the narrowest stable owner:

- Every relay socket runs a **client-initiated keepalive ping** (25 s,
  matching OkHttp) so idle connections are not silently dropped; a failed
  ping routes through the normal reconnect path (audit R9). Answering relay
  pings alone is not sufficient — URLSession never sends its own.

- The process composition root constructs relay pools, repositories and stores
  once. Android starts process work from `Application.onCreate`, never an
  `Application` constructor/`init` block. iOS constructs it in
  `AppEnvironment`.
- The shell owns data shared by multiple top-level destinations. Home and Bitz
  share one verified feed store and must not stop/restart its relay subscription
  when the user switches tabs.
- A feature store or ViewModel owns screen state and cancellable feature work.
- A visible feed surface owns its player pool, network-path monitor and other
  presentation resources. It releases or pauses them when hidden.
- Views/composables render state and emit intents. They never open databases,
  query relays, sign events or construct long-lived clients.

Starting and stopping the same shared subscription from two sibling views is a
lifecycle bug. Keeping every tab fully rendered behind the selected tab is a
resource bug. Preserve saveable UI state while disposing expensive hidden
resources.

## 4. Feed data path

The intended path is:

```text
relay/cache -> verify and decode (off the main actor) -> bounded feed window
            -> feature store -> coalesced immutable UI state
            -> lazy native list/pager -> visible rows
```

Rules:

- Hydrate the bounded cache before waiting for relays.
- Verify event ID and signature before projection or display. Verification
  is **once per (id, pubkey, signature) per process**: the shared codec's
  outcome cache dedupes the store fan-out and the four-relay redelivery, so
  the Schnorr pair runs once, not once per collector (audit R1).
- Decode and verify in the ingest stage BEFORE the main-actor/UI-thread hop
  (iOS `FeedStore` ingest task; Android repository on `Dispatchers.Default`).
  Never move the trust gate into the view or skip it for cached events.
- Decode, normalize, rank and filter outside the view body/composable.
- **Coalesce burst publication**: absorption mutates state per frame; the
  full UI projection (window snapshot, filter, rank, thread assembly)
  publishes once per coalescing window (~150 ms), not once per event.
  User-intent paths (reveal, filter, timeline, follow, bookmark) still
  publish synchronously. Tests must await stable state predicates, never
  transient intermediate sizes (StateFlow/@Observable conflation may skip
  them — see `NotificationRepositoryTest.readCursor…`).
- **Persist in batches**: verified events buffer and flush as ONE
  transaction per coalesced tick or ≥64 events (WAL + synchronous=NORMAL
  on iOS; one transaction per batch on Android). The cache is rebuildable;
  a lost batch only costs a re-fetch.
- Bound every feed window, pending queue, metadata queue, profile cache and
  player pool. Document the bound beside its owner.
- The shared `FeedAggregator` maintains its canonical order incrementally
  (binary-insert by (created_at desc, id)); never reintroduce a per-event
  full re-sort on the read path.
- A note's presentation fields must survive the window round trip
  (insert → snapshot) field-for-field; the bridge/window adapter contract
  tests lock this (audit R11).
- Deduplicate by canonical event ID before publishing state.
- Do not copy or sort the full feed for an individual row.
- Use stable event IDs as lazy-list/pager keys. Never use the current array
  index for relay-backed content.
- Preserve scroll/pager position per top-level tab. Do not retain an active
  player merely to preserve position.

## 5. Profile metadata relay strategy

Metadata enrichment must not block note rendering. Render the verified note
immediately with an npub/hex fallback, then update its presentation when kind-0
metadata arrives.

Use this request order:

1. Read the bounded local profile cache.
2. Deduplicate unresolved author pubkeys into one bounded request (currently at
   most 48 authors).
3. Query the configured primary read relay first.
4. After the short fallback window (currently 900 ms), query only authors still
   unresolved on the remaining read relays.
5. Accept the newest verified kind-0 event per author and cancel obsolete
   fallback work.

Never create one relay subscription per row or query all relays immediately for
every author. Primary-first reduces duplicate frames and UI state churn while
the unresolved-only fallback preserves availability. Record relay provenance
and latency in redacted diagnostics; never log private content or keys.

## 6. Android Compose rules

- Collect observable state with lifecycle awareness.
- Hoist ViewModels and repositories above individual tab compositions.
- Use `rememberSaveableStateHolder` for per-tab scroll/pager state when only the
  selected destination is composed.
- Keep shell subscriptions scalar and distinct (selected tab, theme, badge
  counts). Collect feed/profile/thread payloads inside the active destination
  so a relay burst cannot invalidate the whole `Scaffold` and navigation bar.
- Use `remember`/`derivedStateOf` for derived collections only when their inputs
  are stable and profiling confirms useful work is avoided. Every source read
  by the derivation must be a key/dependency; author-scoped Bitz, for example,
  depends on the author window rather than the global feed window.
- Give `LazyColumn`, lazy grids and pagers stable keys and content types.
- Do not allocate transports, image loaders, formatters or players from a row
  composable.
- Keep coroutine work in `LaunchedEffect` keyed to the smallest real dependency;
  use `DisposableEffect` for symmetric resource cleanup.
- Avoid passing a whole screen state to a row when it needs only a note,
  profile and action state. Narrow parameters reduce unnecessary recomposition.
- Treat stability annotations as contracts. Do not add `@Stable` or
  `@Immutable` to silence tooling when the type is mutable.

Validate with release-profile Macrobenchmark/Perfetto traces and Compose
recomposition diagnostics. Baseline Profiles are added only from representative
startup and navigation journeys.

## 7. iOS SwiftUI rules

- Start a shared feed task once at the shell/store owner, not in both Home and
  Bitz views.
- Use `LazyVStack`/lazy containers for long feeds and stable `Identifiable` event
  IDs for identity.
- Keep expensive filtering, parsing and formatting out of `body`. Store or
  memoize derived presentation only after a trace demonstrates repeated work.
- Scope `.task(id:)` to a real input and ensure its lifetime matches the owned
  work. A task that launches an external monitor and immediately returns does
  not own that monitor.
- Pair every `NWPathMonitor`, notification observer and player allocation with
  explicit cancellation/release.
- Keep `@Observable` state granular. A frequently changing relay-health field
  must not force unrelated feed rows to rebuild.
- Do not use type erasure (`AnyView`) in feed rows unless an Instruments trace
  justifies it.
- Update player reconciliation only for visible identity, media URL, autoplay
  policy or playback-rate changes.

Validate with a release build using Instruments (Time Profiler, SwiftUI,
Allocations and Network) and MetricKit diagnostics from internal builds.

## 8. Video and image resources

- Only the settled/visible Bitz item may play.
- Native video surfaces observe id-to-player slot bindings. A pool may prepare
  after the first UI pass; starting audio without publishing the new surface
  binding is a playback defect.
- Preload at most the documented adjacent slots; the player pool remains
  bounded and evicts deterministically.
- Pause all players when the app backgrounds or the feed destination hides.
- Cancel obsolete image/video requests when rows leave the active window.
- Use decoded image dimensions appropriate to the rendered size. Do not decode
  an original multi-megapixel avatar for a small feed icon. Posters decode
  via ImageIO thumbnailing **off the main actor** at the row's laid-out
  pixel size (iOS `PosterImagePipeline`); a displayed poster is never
  cleared before its replacement has decoded (no flash-to-background while
  scrolling).
- Cache by canonical URL plus transformation size, with a byte/count limit.
- Explore warms only the next 12 poster URLs at grid decode size. Moving the
  window cancels obsolete requests; iOS retains at most 96 decoded posters / 48
  MiB and Android delegates to Coil's bounded memory/disk caches.
- Never make autoplay depend on metadata or nonessential enrichment.

Feed pagination is lane-scoped: For You and Following retain separate anchors
and exhaustion state. An older-page walk stays in flight until its bounded walk
finishes, counts only newly playable videos toward the Bitz page budget, and
uses non-video events only to advance the backwards cursor. UI end-of-window
triggers may repeat safely because repositories reject overlapping walks.

The persisted `bitos_video_quality` preference (auto/high/low) owns the
rendition pick through the shared rule (UX U9): `auto` keeps the display-
fitting pick, `high` forces the tallest rung, `low` is the data saver —
shortest rung at or above 360p, else the shortest available. A preference
change releases the bounded player slots so the next reconciliation
re-prepares at the new rung; players never keep a rung the user turned down.
Native adapters pass logical layout height (points on iOS, density-independent
pixels on Android), with the 640 logical-pixel watchability floor, to the shared
AUTO rule; raw physical pixels or a fixed 4K-class bucket over-select
renditions on dense low/mid-tier devices.

Nostr feed pagination is cursor-based, never offset-based. Each backwards REQ
is broadcast to the connected read relays in parallel with one subscription
id. The repository merges that exact subscription's verified events by event
id, waits for EOSE from the request-time relay set (or the hard deadline), then
closes the subscription and sets the next `until` to `oldest created_at - 1`.
Unrelated live arrivals must not influence this cursor. Bitz starts this walk
with ten loaded items remaining and targets ten fresh playable videos, so the
next swipe/grid segment is normally ready before the user reaches the edge.

## 9. State and clean-code rules for fast paths

Fast code is easier to optimize when responsibilities remain explicit:

- Codec: parse and validate protocol input.
- Repository: coordinate cache and relay I/O.
- Store/ViewModel: own lifecycle and publish screen state.
- Ranking/filter policy: pure BusinessCore rule.
- View: render native UI and emit intent.
- Player pool: own a bounded set of native player resources.

Do not create `FeedManager`, `PerformanceHelper` or a global mutable cache.
Prefer focused interfaces such as `ProfileCache`, `RelayReader` and
`PlayerPool`. Keep functions small enough that a trace sample maps to one clear
responsibility, but do not split code into wrappers that add no boundary.

Every optimization must state:

- the measured bottleneck and trace/metric;
- the owner and invariant being preserved;
- memory, battery and network tradeoffs;
- cancellation and stale-result behavior;
- regression coverage or repeatable benchmark.

## 10. Startup and failure diagnostics

- Phase 0 instrumentation is compiled in: iOS `Perf` signposts (subsystem
  `space.bitos.app`) and Android `PerfTrace` atrace sections share the
  interval names `relay.decode`, `feed.publish`, `poster.decode`. Capture
  procedure and the baseline table live in
  [`performance-baselines.md`](./performance-baselines.md).
- Keep `Application`/app initialization synchronous work minimal. Defer cache
  hydration and relay connection to structured background work after dependency
  construction is complete.
- Do not block the main thread on relay connection, metadata, database
  compaction or media preparation.
- Distinguish source/runtime failures from build-tool cache failures. Clear or
  stop the affected compiler daemon before changing application code for a
  cache-registration error.
- Preserve the first exception and its cause chain. Do not catch a launch error
  and show an empty shell without a redacted diagnostic.
- Runtime logs may include event IDs, relay host and typed phase/error codes.
  They must not include `nsec`, wallet secrets, DM plaintext, authorization
  events or unpublished media paths/content.

## 11. Review and verification checklist

- Does switching Home/Bitz reuse the store without reconnecting relays?
- Are hidden players paused/released while scroll position survives?
- Can a metadata timeout delay the note itself? It must not.
- Is any work executed once per row that could be batched once per state update?
- Are list keys stable across insert, pending-note reveal and ranking changes?
- Are collections, retries, caches and subscriptions bounded?
- Does cancellation stop monitors, players and obsolete relay requests?
- Was the change tested with slow relays, missing metadata, offline mode and a
  burst of new events?
- Did Android compile/tests and iOS build/type-check pass?
- Was a release-build trace captured on a physical device for a performance
  claim?
- Were relevant architecture, flow and performance documents updated?
