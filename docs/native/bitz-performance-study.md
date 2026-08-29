# Bitz Short-Video Performance Study — Why the Web Feed Is Fast, and the Native Implementation Plan

Status: reference + implementation plan (feeds APP-007 / FED epic)
Progress: **§4.1 delivered** — shared `BitzTimelinePolicy` (+ walk bounds consts), `BitzSort`
(trending/zapped), `MediaMetadata` fallback-mirror + rendition-ladder parsing with
`selectRendition` (§4.5), settings schema **v5** (`trending`/`zapped` wires), bridge
`bitzTabSortIds`/`mediaPickRenditionUrl`/walk getters; **§4.3 player triggers delivered**;
**§4.5 delivered both platforms** — player pick → mirror → lower-rendition failover chains
(Android `VideoPlayerPool`+`MediaSources`, iOS `PlayerPool`); **5-tab pills live both
platforms** (§2.9); signed protocol fixture `valid-kind22-rendition-ladder` (§4.1 fixture
rule). Remaining from §4: relay-layer progressive queries (§4.2 repository seam), cold-start
cache snapshot + tab session (§4.4), §4.7 acceptance runs.
Date: 2026-08-29
Web app audited at: `../../bitos-nostr-web` (SvelteKit, `src/routes/bitz/+page.svelte` ~2,300 lines)
Native destination: `apps/ios/BitOS/Features/Bitz/`, `apps/android/.../ui/bitz/`, shared rules in `shared/business-core/.../feed/Bitz.kt`

---

## 1. Executive summary — why `/bitz` feels fast on the web

The web Bitz page is fast for one reason above all: **the expensive work is
never on the scroll path**. Every scroll/visibility tick does O(1)
bookkeeping, all I/O is either (a) done before the first pixel, (b) triggered
early by distance thresholds, or (c) progressive (first relay paints, slow
relays merge in later). Concretely, eight techniques:

| # | Technique | Where (web) | Effect |
|---|---|---|---|
| 1 | **Warm socket pool** — one `SimplePool` for app lifetime, sockets pre-warmed from the layout | `src/lib/nostr/pool.ts` via `ensureConnected()` | Zero handshake cost at page open; queries reuse live sockets |
| 2 | **Progressive multi-relay fan-out** — parallel query to all relays; primary relays paint first, secondaries dedupe-merge in the background | `queryParallelProgressive()` | First content paints at the *fastest* relay's EOSE, not the slowest |
| 3 | **Hard `maxWait` budget** — `4,000 ms` cap on every pagination batch | `REELS_PAGE_MAX_WAIT_MS` | A dead relay can never stall the backwards walk |
| 4 | **Split kind filters** — media kinds `[20,21,22,34235,34236]` limit 400 vs kind-1 limit 120, in ONE parallel round trip | `REEL_MEDIA_KINDS` + comment | Nostr `limit` is per-relay-per-filter; deep-querying dedicated video kinds maximizes renderable yield per request |
| 5 | **Distance-triggered three-layer pipeline** — render window (+5 tiles), relay prefetch (≤6 unrendered buffered), grid reveal (18/page) | `handleReelScroll()` | New content is always requested ~2–3 viewport-heights *before* the user reaches the end |
| 6 | **Backward `until` walk with fresh-count budget** — `until: oldest - 1`, stop when 18 NEW media items land or cursor stalls; max 6 batches | `loadMoreReels()` | One or two round-trips cover a full page; no gaps, no re-fetch of known ids |
| 7 | **Instant-paint caches** — in-memory `bitzSession` (survives route switches, 60 s refresh) + localStorage snapshot (10 reels / 15 min TTL) | `bitzSession`, `bitos:reels-cache:v3` | Cold loads render the grid *before* any network; returning to the tab is instant with scroll position restored |
| 8 | **Renderer discipline** — IntersectionObserver election (thresholds `.25/.5/.6/.75/.9`), only the elected reel plays, DOM windowing (initial 5, batch 5), non-reactive element registries | `createVisibilityObserver`, `reelVideos: Map` | One `play()`, bounded DOM, no reactive churn per visibility tick |

Two things the web deliberately does **not** do (myths to dispel):

- **No media-byte prefetch ahead of the playhead.** Reel videos mount with
  `preload="metadata"` and only the active one plays. There is no
  `createObjectURL` warm-up, no `<link rel=preload>`, no second warming
  `<video>`. The word "prefetch" only refers to *relay data*. (Native already
  does better here with the 3-slot player pool.)
- **No posters.** `imeta thumb` is published but never consumed; the grid tile
  video element is its own thumbnail (`preload="none"` → `metadata` at
  300 px rootMargin).

---

## 2. The data pipeline in detail

### 2.1 Initial load — `loadReels()`

```text
t0   sockets already warm (layout pre-warm)
t0   fire discovery-relay query + queryParallelProgressive(primary ∥ secondary)
     filters (ONE round trip, two filters in parallel):
       { kinds: [20,21,22,34235,34236], limit: 400 }   ← deep: ~100% renderable
       { kinds: [1], limit: 120 }                       ← shallow text window
t1   primary EOSE → paint: latestAddressableEvents → sort desc → dedupe
       (eventRefKey; addressable dedupe by coordinate, newest wins, tie →
        lexicographically smaller id) → toReelNote (pure, no I/O)
t2   secondary EOSE → background dedupe-merge, re-render
t3   SECOND round trip (queryPrimaryFirst, sequential): engagement counts
       { kinds:[7,6,16,9735,1111,1018,1], '#e': reelIds, limit: 500 }
       → applyActivityToNotes (zap totals, like counts for rail + ranking)
```

Why it feels instant: (1) the cache/session paints at t−1 (below), (2) the
first *network* paint happens at the fastest relay's EOSE with rendering
already possible (engagement numbers arrive later and patch in), (3) ranking
runs over the already-shown set — tabs (Following / Trending / Most-zapped)
are **derived sorts of the same loaded list, zero extra fetches**.

### 2.2 Pagination — `loadMoreReels()` (the "load more" heart)

```ts
guards: loading || loadingMoreReels || !hasMoreReels || !oldestReelEventCreatedAt → return
for (batch < MAX_REEL_QUERY_BATCHES(6) && foundMedia < REELS_MEDIA_PAGE_SIZE(18)) {
    filters = [
      { kinds: REEL_MEDIA_KINDS, limit: 60, until: oldestReelEventCreatedAt - 1 },
      { kinds: [1],              limit: 150, until: oldestReelEventCreatedAt - 1 }
    ]
    events = await queryUrls(relays.orderedReadUrls, filters, { maxWait: 4000 })
    if (!events.length) { exhausted = true; break }
    fresh = events.filter(e => !knownIds[e.id])          // only NEW ids count
    foundMedia += fresh.filter(e => toReelNote(e) != null).length
    if (fresh.length) updateReelWindow(mergeEvents(fresh), { append: true })  // paint per batch
    oldestInBatch = min(created_at)
    cursorAdvanced = oldestInBatch < oldestReelEventCreatedAt
    oldestReelEventCreatedAt = min(oldestInBatch, current)  // monotonic backward
    if (!cursorAdvanced && !fresh.length) { exhausted = true; break }  // relay ignoring until
}
if (exhausted) hasMoreReels = false
```

Key invariants worth porting exactly:

- **`until` cursor is monotonic backward only** — progressive appends can
  never move it forward (`oldest = min(oldestInBatch, current)`).
- **Only fresh ids count toward the page budget** — relays love re-sending
  known events; counting them would silently shrink pages.
- **Paint per batch** — each batch's fresh events merge in as they land; the
  user never waits for the full 6-batch walk.
- **Cursor-stall detection** (`!cursorAdvanced && !fresh.length`) terminates
  relays that ignore `until`.
- Batch sizing comment from the source: the *old* 10-event batches "made
  pagination feel dead: dozens of sequential requests before anything showed" —
  batches must be large enough that 1–2 round trips cover a page.

### 2.3 The three-layer scroll pipeline (`handleReelScroll`)

```text
remaining = scrollHeight - scrollTop - clientHeight
remaining < 2 × clientHeight  → renderMoreReels()            DOM window +5
remaining < 3 × clientHeight  → ensureMoreReelsBuffered()    relay fetch when
                                 (reels.length - renderedReelCount) ≤ 6
```

Grid (Explore) equivalents: 24 initial tiles, +18 per reveal
(`EXPLORE_INITIAL_VISIBLE`/`EXPLORE_PAGE_SIZE`); pinned-to-bottom loads chain
another round immediately (the pinned scroll fires no further scroll events).

### 2.4 Visibility election & playback sync

```text
IntersectionObserver(root: reelScroller, thresholds: [0.25, 0.5, 0.6, 0.75, 0.9])
  → reelVisibility: Map<reelId, ratio>            (non-reactive)
  → updateActiveReel(): highest ratio wins; fallback = first rendered
  → syncActivePlayback():
       every non-active video: mute + pause (position kept → resumes on return)
       active video: apply persisted mute, play(); on autoplay rejection →
                     force mute, retry, else show tap-to-play overlay
```

- Mute persists in `localStorage 'bitos:reel-muted'`, default muted.
- Reels resume where paused (loop, never reset); grid hover previews DO reset.
- Visibility ratios are also folded into ranking as a *dwell proxy*, but from
  a **snapshotted copy** so re-ranking never fires per visibility tick (that
  would jitter the snap scroll).

### 2.5 Cold start & tab-return caches

| Layer | Scope | Contents | Bound |
|---|---|---|---|
| `bitzSession` (module state, non-reactive) | route switches | reels, mode, `exploreScrollTop`, `activeReelIndex`, `oldestReelEventCreatedAt`, `hasMoreReels`, `lastRefreshedAt` | app-tab lifetime; background refresh only if > 60 s old |
| `bitos:reels-cache:v3` (localStorage) | cold start | `{ savedAt, reels: newest 10 }` | TTL 15 min, best-effort write, never written in author mode |

Order on mount: session hydrate → cache hydrate (sets `loading = false`
synchronously → grid paints **before** network) → background `loadReels()` →
`ensureMoreReelsBuffered()`. Scroll position restored after `tick()` + rAF
(player: `activeReelIndex × clientHeight`; explore: `exploreScrollTop`).

### 2.6 Rendition & failover policy

```ts
targetHeight = max(screen.height, innerHeight, 640)
selectRendition(media, targetHeight):
    cap = targetHeight × 1.25                    // DPR headroom
    fitting = renditions.filter(r => r.height > 0 && r.height <= cap)
    if empty → smallest available (browser downscales)
    else      → tallest fitting rendition
no renditions → primary mediaUrl
```

- Renditions come from `imeta fallbackrendition variant <url> <dim> <bitrate>`,
  sorted high→low. Same-URL variants are treated as **mirrors** and pushed to
  the fallback chain instead.
- The fallback chain (`MediaPlayer.fallbackSrcs`) is **walked only on load
  error** (`tryNextCandidate()`), independent of rendition choice.
- Static pick (screen-height based), not adaptive ABR.

### 2.7 Grid tile cheapness

Video tiles: `preload="none"` + `lazyVideoMetadata` action — an
IntersectionObserver with `rootMargin: 300px` upgrades to `metadata` near the
viewport (fetches just the moov atom for the duration badge); hover = unmuted
preview, leave = pause + reset. Picture tiles: plain `loading="lazy"` `<img>`.
On a long grid this skips hundreds of off-screen header requests.

### 2.8 Other loans worth keeping

- **Kill-switch**: `bitzPageActive` — every async continuation checks it
  before writing state after unmount (native equivalent: structured
  concurrency cancellation in the store task).
- **Profiles only for the rendered window**, batched one-filter-per-pubkey,
  inflight-deduped, 12 h staleness, capped (300 profiles / 300k chars).
- **Search**: 400 ms debounce, `maxWait: 4000`, local instant matches merged
  local-first with id-dedupe (already shared as `BitzSearch`).
- **Explore paging math** already shared: `BitzExplore.INITIAL_PAGE = 24`,
  `MORE_PAGE = 18` — web parity confirmed.

### 2.9 Tab system — five surfaces, one window

The web mounts five tabs: **Explore · Following · For you · Trending ·
Most zapped**. Only the first two change *what is fetched*; the last three
are orderings of the same loaded window:

| Tab | Data source | Ordering |
|---|---|---|
| Explore | same window, 3-col grid | grid paging (`BitzExplore` 24+18) |
| Following | `follows` filter on relays | chronological |
| For you | global media window | chronological (arrival; native adds ranking) |
| Trending | **same window, zero fetch** | `counts × 0.5^(ageHours / 72)` desc — engagement = reactions + reposts + zaps |
| Most zapped | **same window, zero fetch** | zap sats desc, newest as tiebreak |

Rules the native port must keep:

- **View-only sorts.** Trending/Most-zapped never issue a relay request;
  they re-rank the verified window client-side as tallies patch in. Rankings
  therefore track live engagement (a zap landing mid-session re-orders).
- **One scroll memory per tab**, not per surface — the pager index is
  restored on tab return from the in-memory session (§2.5); persistence
  beyond the run is explicitly not required.
- **Half-life 72 h** on trending decay: fresh-but-small beats old-but-big
  after ~3 half-lives, matching the web constant exactly.
- Native realization (delivered): shared `BitzSort.trending/zapped` +
  settings v5 modes; pills and 5-step swipe order on both platforms;
  engagement rows cross the iOS bridge as JSON
  (`bitzTabSortIds`, mirroring `algorithmRankIds`).

---

## 3. Native gap analysis (what exists vs what the web proves)

Already in place natively (verified in code):

- Three-slot player pools keyed by verified id — `PlayerPool.swift`
  (AVQueuePlayer + AVPlayerLooper), `VideoPlayerPool.kt` (Media3 ExoPlayer) —
  only the settled page plays; this is *ahead* of the web (real adjacent-slot
  preloading, not just `preload="metadata"`).
- Snap pagers with visibility-driven election; mute memory persisted
  (`bitos_video_muted`); autoplay policy settings; double-tap like.
- Shared explore paging (`BitzExplore` 24+18), search merge (`BitzSearch`),
  share text, duration labels.
- Relay pool actor with backoff; bounded event cache (200 hydrate / 500 prune).
- Shared `BitzQuery.initialFilters()/olderFilters(until)` already splits
  NIP-71 `[21,22]` from kind-1 and carries `until`.

Native gaps this plan closes (tracked →):

| Gap | Web behavior to port | Tracker |
|---|---|---|
| G1 ✅ Query depth & kind coverage | kinds `[20,21,22,34235,34236]` now in `feedKinds`/`reelMediaKinds` + `BitzTimelinePolicy.initialFilters()` | Delivered (shared, tested) |
| G2 ✅ Multi-batch backward walk policy | `BitzTimelinePolicy` (fresh budget 18, 6 batches, monotonic `until`, stall detect) — repository seam adoption pending | Policy shared+tested; repo walk pending (REL) |
| G3 ◐ Per-batch `maxWait` 4 s | `PAGE_MAX_WAIT_MS` shared const; relay-layer enforcement pending | REL |
| G4 ◐ Progressive paint (primary-first, secondary merge) | First relay paints; slow ones merge in | REL |
| G5 ✅ Three-layer distance triggers on the pager | Android pager + iOS `onChange(topId)` now fire load-older at the shared `PREFETCH_BUFFER_THRESHOLD` distance; store-side render batching rides the pools | APP-007 delivered |
| G6 ○ Cold-start snapshot + tab-session with position restore | 10 reels / 15 min TTL; session index + scroll top; 60 s refresh gate | FED pending |
| G7 ✅ Rendition selection + mirror failover on the read path | `selectRendition` (screen ×1.25 static pick) + pick→mirrors→renditions chain on item error, BOTH players; imeta parsing bounded | FED-004 delivered |
| G8 ○ `imeta` poster/thumbnail use | Grid thumbs should use `thumb` when present | APP-007 polish pending |
| G9 ◐ Engagement second-pass patch-in | Tallies already patch in place on both platforms; the kinds `[7,6,16,9735,1111,1018]` `#e`-batched second pass is not yet issued per page | FED partial |

**Tab-system gap (closed):** the web's 5-tab cycle (Explore · Following ·
For you · Trending · Most zapped) vs the native 3-tab bar → delivered as
view-only sorts (§2.9) through shared `BitzSort` + settings v5, both platforms.

Deliberate **non-goals** (web behaviors not to port): localStorage JSON blobs
(native has the versioned event cache — DAT-001..003), DOM-style render
windowing on the pager (native pagers already compose lazily; only the player
pool needs bounding — enforce `native-performance.md` §8).

---

## 4. Native implementation plan

### 4.1 Shared BusinessCore — pagination & query policy (one place, both apps)

Extend `space.bitos.core.feed` with a `BitzTimelinePolicy` (pure, fixture-tested)
that supersedes the constants inside `BitzQuery`:

```kotlin
object BitzTimelinePolicy {
    // Kinds: [20, 21, 22, 34235, 34236] for media; kind 1 shallow fallback.
    const val MEDIA_INITIAL_LIMIT = 80      // tune to 400-web parity after load tests
    const val TEXT_INITIAL_LIMIT = 120
    const val MEDIA_PAGE_LIMIT = 60
    const val TEXT_PAGE_LIMIT = 150
    const val PAGE_FRESH_MEDIA_TARGET = 18  // stop when 18 NEW media events land
    const val MAX_QUERY_BATCHES = 6
    const val PAGE_MAX_WAIT_MS = 4_000L
    const val PREFETCH_BUFFER_THRESHOLD = 6 // fetch when unrendered-buffered ≤ 6
    const val RENDER_BATCH = 5

    fun olderFilters(until: Long): List<String>          // until = oldest - 1
    fun shouldContinue(foundFreshMedia: Int, batch: Int): Boolean
    fun cursorAdvanced(oldestInBatch: Long, current: Long): Boolean
}
```

Rules to encode (tests = the invariants in §2.2):

1. Cursor monotonic backward: `oldest = min(oldestInBatch, current)`.
2. Only fresh ids (not already in the window) count toward
   `PAGE_FRESH_MEDIA_TARGET`.
3. Stop conditions: target reached, `batch == MAX_QUERY_BATCHES`, empty batch
   (exhausted), or `!cursorAdvanced && fresh.isEmpty` (relay ignoring `until`).
4. Addressable dedupe by coordinate (newest `created_at`, tie → smaller id) —
   reuse the existing shared normalizer; the walk must not regress it.

Fixtures: extend `contracts/nostr/fixtures/` with a pagination vector —
overlapping pages, an `until`-ignoring relay, a batch mixing known+fresh ids,
and an addressable replacement pair straddling the cursor. Protocol change ⇒
fixtures (AGENTS.md).

### 4.2 Relay layer — progressive queries + maxWait (REL)

Both relay pools already own sockets; add two query modes at the repository
seam (not in views):

- `queryParallelProgressive(filters)`: emit results as **each relay's EOSE**
  lands (first-relay paint), then dedupe-merge later arrivals into the store.
  Dedupe key = verified event id (addressable → coordinate).
- Per-batch deadline: cancel/ignore stragglers after `PAGE_MAX_WAIT_MS`
  (4 s); initial load may use a longer deadline. Timeouts cancel only the
  subscription, never the socket.
- Keep sockets warm: no per-page connect/disconnect; the pools' capped
  jittered reconnects stay the only lifecycle policy.

Android: `FeedRepository` gains a paginated Bitz walk (kinds per policy)
emitting incremental `List<FeedNote>` snapshots (immutable, `@Immutable`).
iOS: `FeedStore`/`BusinessCoreClient` seam gains the same walk; incremental
appends must not re-project the whole window per batch.

### 4.3 Store / ViewModel — three-layer pipeline (APP-007)

Pager position drives distance triggers exactly like the web:

```text
remaining pages to end < 2  → grow player window by RENDER_BATCH (5)
remaining pages to end < 3  → if (loaded - renderedVisibleWindow) ≤ 6 → fetch older
```

- Compose: `snapshotFlow { pagerState.currentPage }` /
  `derivedStateOf { pagerState.currentPage }` vs total pages → thresholds;
  never in `LaunchedEffect( Unit )` polling loops.
- SwiftUI: `.onChange(of: topId)` already exists — compute distance from the
  index and fire the same two triggers; keep polling only for the existing
  scrubber tick (500 ms) which is already scoped to `topId`.
- Fetch, decode-verify-project, and append are cancellable and idempotent
  (AGENTS.md background-job rules): re-entering the trigger must not start a
  second walk (the web's `loading`/`loadingMoreReels` guards).
- Engagement second pass: after a page lands, fetch kinds
  `[7,6,16,9735,1111,1018,1]` `#e = pageIds` limit 500 and patch counts
  in place (`NoteTally` already models this — reuse it).

### 4.4 Cold start + tab session (FED)

Two layers, mirroring web intent with native storage:

1. **Cache snapshot** — reuse the existing SQLite event cache
   (DAT-001..003): on cold start hydrate the newest ≤10 *verified* short-video
   events and paint immediately; then refresh in the background. No new
   storage schema; minutes-freshness bound (web: 15 min TTL) is a read-time
   check, not a persisted clock.
2. **Tab session** — in-memory (per app run) state owned by the feature
   store: mode, pager index, grid scroll offset, backward cursor,
   `hasMore`, `lastRefreshedAt`; restore on re-entering the Bitz tab
   (`refresh only if > 60 s`). Surviving app kills beyond the snapshot is
   explicitly *not* required (web parity).

### 4.5 Rendition selection + mirror failover (FED-004, open in blueprint)

- Parse `imeta fallbackrendition variant` ladders and `fallback` mirrors on
  the read path in BusinessCore (bounded: cap rendition count, reject
  non-`https`, hash/dim regex bounds — the web codec lacks bounds; keep ours).
- `selectRendition(renditions, targetHeight)`: cap = `targetHeight × 1.25`,
  tallest fitting, smallest-on-overshoot — pure shared function + fixtures.
- Players consume `primary = rendition pick`, `fallbacks = mirrors`; swap to
  the next candidate **only on item error** (Android: `Player.Listener
  onPlayerError` → next `MediaItem`; iOS: observe `AVPlayerItem.status ==
  .failed` → replace item). Rendition choice is a static pick — no ABR in V1.
- Grid thumbnails: use `imeta thumb` when present (AsyncImage/decoded-size
  rules per `native-performance.md` §8) — an improvement over web.

### 4.6 Player/pager discipline (already enforced — keep)

Only the settled page plays; adjacent slots prepared at most; pause on
background/hide; cancel obsolete requests when rows leave the window. The
pools' id-keyed lifetimes already deliver the web's promise ("no stale
callback reaches the UI") more strongly than the DOM registry does.

### 4.7 Performance acceptance gates

| Gate | Target |
|---|---|
| Bitz tab cold paint (cache hydrate path) | < 150 ms to first frame of grid |
| First network page (fastest relay EOSE) | paints incrementally; never blocked by slowest relay |
| Pagination trigger → tiles appended | no user-visible stall at normal scroll speed (batches land before edge) |
| Dial-a-dead-relay test | pagination completes ≤ 6 batches × 4 s worst case; UI shows loading state, never deadlock |
| Player slots | ≤ 3, exactly 1 playing, 0 playing when surface hidden |
| Monotonic cursor | fixture: repeated walks never re-yield known ids; addressable newest-wins preserved |

Tests: common `BitzTimelinePolicy` tests (JVM + macOS lanes), relay
adapter-contract test with a fake transport (slow relay, `until`-ignoring
relay, duplicate ids, overlapping pages), platform UI tests for the trigger
thresholds. Update `native-ui-build-tracker.md` APP-007 rows and this file's
status in the same change (AGENTS.md delivery rule).

---

## 5. Porting cheat-sheet (web → native mapping)

| Web | Native equivalent (existing or planned) |
|---|---|
| `SimplePool` singleton + `ensureConnected` | `RelayPool` actor / OkHttp pool (keep warm; REL) |
| `queryParallelProgressive` / `queryPrimaryFirst` | repository query modes (G4) |
| `until` walk + fresh-count + stall detect | `BitzTimelinePolicy` (G1–G3) |
| `handleReelScroll` thresholds 2×/3× | pager distance triggers (G5) |
| `bitos:reels-cache:v3` (10/15 min) | SQLite event cache hydrate path (G6) |
| `bitzSession` + 60 s refresh | feature-store tab session (G6) |
| `createVisibilityObserver` election | pager settled-page + pool reconciliation |
| `syncActivePlayback` mute/retry | pool `setMuted` + autoplay policy settings |
| `selectRendition` ×1.25 + `tryNextCandidate` | shared `selectRendition` + item-error failover (G7) |
| `lazyVideoMetadata` 300px | grid thumb = `imeta thumb`, lazy compose/async image (G8) |
| engagement 2nd pass `#e` patch | `NoteTally` merge per page (G9) |
| `bitzPageActive` kill-switch | structured cancellation in store task |
