# Performance Baselines — Capture Runbook (Phase 0)

This runbook operationalizes the measurement gate in
[`native-performance.md`](./native-performance.md) §2 and Phase 0 of
[`performance-audit-and-plan.md`](./performance-audit-and-plan.md). No
performance claim merges without a row of the baseline table below captured on
a **physical device**. Simulator/emulator captures are diagnostic only.

## 1. Embedded instrumentation (already shipped)

Both platforms emit the same three interval names, so one capture vocabulary
maps to both:

| Interval | What it covers | Owner |
|---|---|---|
| `relay.decode` | One relay frame through decode + SHA-256 ID + BIP-340 verify (ingest stage) | iOS `Perf` signpost in `FeedStore.ingest`; Android `PerfTrace` section in `FeedRepository`/`NotificationRepository` collectors |
| `feed.publish` | One coalesced UI projection (window snapshot → filter → rank → thread assembly) | iOS `FeedStore.publishState`; Android `FeedRepository.publishState` |
| `poster.decode` | One poster download+decode at rendered size (cache misses) | iOS `PosterImagePipeline`; Android observes via Coil worker threads (decode is internal to Coil) |

iOS subsystem: `space.bitos.app`, category `perf` (`PerfSignposts.swift`).
Android sections are `android.os.Trace` atrace sections (`PerfTrace.kt`).
Payloads carry numeric counts only — never content, keys or URLs.

## 2. Environment record (required with EVERY capture)

Record these next to each table row; a number without them is not a baseline:

- Device model + OS version (low: 2–3 GB Android / iPhone SE class; median: current mid-range)
- Build: commit SHA, debug/release, R8/minified for Android
- Relay set (default 4 or the configured set), network type
- Cache state: cold (fresh install or cache cleared) vs warm
- Test data description (live relays vs recorded burst)

## 3. Baseline table (fill on first Phase 0 run)

| # | Journey / budget (from native-performance.md §2) | iOS low | iOS median | Android low | Android median |
|---|---|---|---|---|---|
| 1 | Warm top-level tab switch to first complete frame — p95 ≤ 100 ms | | | | |
| 2 | Cached cold launch to interactive shell — median ≤ 1.5 s / p95 ≤ 2.5 s | | | | |
| 3 | Cached Home content visible after shell mounts — median ≤ 500 ms | | | | |
| 4 | Home/Bitz scroll or pager janky frames < 5%, zero frozen | | | | |
| 5 | Main-thread stall during steady scrolling — no task ≥ 100 ms | | | | |
| 6 | Verified event accepted → visible UI state — p95 ≤ 100 ms excl. network (`relay.decode` + `feed.publish` chain) | | | | |

Store filled rows as PR updates to this file.

## 4. iOS capture procedure

1. Release build on device: `xcodebuild build -scheme BitOS -configuration Release -destination 'platform=iOS,id=<UDID>'`.
2. **Signpost + hang capture**: Instruments → "Time Profiler" template, add the **os_signpost** instrument, filter subsystem `space.bitos.app`. Record: cold launch → Home scroll (30 s) → Bitz swipe (30 s) → tab switches.
3. **Hitch/jank**: Instruments → "Animation Hitches" template, same journey. SwiftUI instrument for body/recomposition cost on the worst row.
4. Export the trace and note the file next to the table row. MetricKit hitch
   payloads from internal TestFlight builds are the production follow-up
   (not required for the first baseline).

Budget mapping: row 6 = `relay.decode` and `feed.publish` histograms; row 5 =
main-thread Time Profiler samples ≥ 100 ms during scroll; rows 1–3 from the
launch trace timestamps.

## 5. Android capture procedure

1. Release build: `./gradlew :apps:android:assembleRelease` (R8 as configured); install on device.
2. **Perfetto with app sections**:
   ```bash
   adb shell perfetto -o /data/misc/perfetto-traces/feed.pftrace \
     -t 60s sched freq idle am wm gfx view binder_driver hal dalvik app bionic
   ```
   (or use studio Profiler → CPU → System Trace). `relay.decode`/`feed.publish`
   appear as atrace sections in the app process track.
3. **Jank**: `adb shell dumpsys gfxinfo <pkg> framestats` after each scroll
   journey — Janky frame %, 95th percentile; `dumpsys gfxinfo <pkg> reset`
   between journeys. Frozen-frame count from launch in `gfxinfo`.
4. Baseline Profiles + a Macrobenchmark module (startup, scrollHome, swipeBitz,
   loadMore) are the follow-up once these first numbers exist — do not gate the
   first baseline on them.

## 6. Rules

- A PR that exceeds a budget row must include: trace, cause, owning layer,
  fix or approved trade-off (native-performance.md §2).
- Compare like for like: same device, same relay set, same cache state.
- Re-run a row before/after any performance PR — before numbers may come from
  this file, after numbers must be captured on the PR's build.
