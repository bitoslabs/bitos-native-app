# BitOS Nostr — Algorithm

Implementation of the toggleable, **client-side** ranking system across
**Feed**, **Reels**, and **Discover**. Built on the existing `bitos-svelte`
stack (SvelteKit 2, Svelte 5 runes, `nostr-tools`), following the same
singleton-store pattern already used by `identity.svelte.ts` /
`relays.svelte.ts` / `theme/preferences.svelte.ts`.

> Status: **shipped.** See `src/lib/algorithm/` for the library and
> `src/routes/settings/algorithm` for the UI.

---

## 1. Design principles

1. **Off means chronological, not hidden.** A surface's master switch off →
   strict reverse-chronological, never an empty state.
2. **Two toggle levels.** Surface-level master switch + per-signal toggles
   inside each surface (disable "zaps" without killing the whole feed).
3. **Client-side only.** All scoring runs in the browser from events already
   fetched via `SimplePool`. No telemetry leaves the device.
4. **Stateless pipeline, stateful preferences.** Scoring is a pure function of
   `(candidates, config, ctx) → ranked notes`. All persisted state lives in one
   preferences store.

---

## 2. File structure

```
src/lib/algorithm/
├── types.ts                    # AlgorithmPreferences, SurfaceConfig, ScoringContext, SignalFn…
├── definitions.ts              # signal catalog (label/description/icon) for the UI
├── preferences.svelte.ts       # persisted toggle/weight/freshness state (runes singleton)
├── presets-helpers.ts          # shared `signals()` + `clamp01()` builders
├── presets.ts                  # Latest / Balanced / Trending / Trusted presets + matcher
├── registry.ts                 # id → scoring fn map (+ safe `resolveSignal`)
├── signals/
│   ├── recency.ts              # exponential time-decay (half-life from global freshness)
│   ├── engagement.ts           # reactions + reposts, log-normalized (+ dwell proxy for reels)
│   ├── zaps.ts                 # NIP-57 sats, log-scaled, read from note.zapTotalSats
│   ├── affinity.ts             # historical interaction rate with this author
│   ├── wot.ts                  # web-of-trust distance gate (0 / ≤2 / other)
│   └── novelty.ts              # soft diversity nudge (hard guarantee is diversity.ts)
├── diversity.ts                # post-scoring author-clustering pass (requeue, never drop)
├── context.ts                  # builds ScoringContext + lazy/cached WoT second-hop + affinity
├── pipeline.ts                 # rankNotes / rankNotesWithBreakdown orchestrators (generic)
├── index.ts                    # public barrel
└── algorithm.test.ts           # vitest: off=chronological, scoring, diversity, affinity
```

UI: `src/lib/components/settings/AlgorithmSettings.svelte`, surfaced as a new
**Settings → Algorithm** tab (`src/lib/settings/sections.ts`).

---

## 3. Core types (excerpt)

```typescript
export type SurfaceId = 'feed' | 'reels' | 'discover';

export interface SignalState { enabled: boolean; weight: number; }   // 0–1
export interface SurfaceConfig {
  enabled: boolean;                // master switch — off = chronological
  diversityEnabled: boolean;
  signals: Record<string, SignalState>;
}
export interface ScoringContext {
  now: number;
  followingSet: Set<string>;
  me?: string;
  affinity: Map<string, number>;   // pubkey → 0–1
  wotSet: Set<string>;             // lazily populated, TTL-cached second-hop
  recentAuthors: Set<string>;      // novelty input
  dwell?: Map<string, number>;     // reels watch-time proxy
  recencyHalfLifeSeconds: number;  // global freshness control
}
export type SignalFn = (note: FeedNote, ctx: ScoringContext) => number;  // → 0–1
```

---

## 4. Defaults per surface

| Surface | Dominant signals | Rationale |
|---|---|---|
| **Feed** | recency 0.35, affinity 0.25, topics 0.15, engagement 0.10, WoT 0.10, novelty 0.05 | Quality-first personalization that stays close to chronological |
| **Reels** | engagement 0.45, zaps 0.30, recency 0.15, affinity 0.10 (off) | Discovery-oriented; dwell folded into engagement |
| **Discover** | engagement 0.40, wot 0.30 (quality gate), zaps 0.20, recency 0.10 | WoT is a floor, not a popularity boost |

---

## 5. Pipeline (the whole orchestrator in one breath)

```typescript
export function rankNotes<T extends FeedNote>(surface, candidates, ctx): T[] {
  const cfg = algorithmPreferences.config[surface];
  if (!cfg.enabled) return [...candidates].sort(byCreatedAtDesc);  // off = chronological

  const entries = Object.entries(cfg.signals).filter(enabled && weight > 0);
  const total = sum(weights) || 1;                                  // re-normalize
  const scored = candidates.map((note) => {
    let score = 0;
    for (const [id, state] of entries) score += resolveSignal(id)(note, ctx) * state.weight / total;
    return { note, score };
  });
  scored.sort(byScoreDesc);
  return (cfg.diversityEnabled ? applyDiversity(scored) : scored).map((s) => s.note);
}
```

`rankNotesWithBreakdown` is the same but also returns a per-note explanation
(`topSignal` + per-signal contributions) — powers the **"why am I seeing this?"**
chip on feed posts.

---

## 6. Integration points (where the ranking actually runs)

| Surface | File | Behavior |
|---|---|---|
| **Feed** | `src/routes/+page.svelte` | Re-ranks the "For you" timeline (`rankNotesWithBreakdown`) when the master switch is on. "Following", tag, and relay-search views stay chronological. Adds a "Ranked for you · ⟨preset⟩" banner + per-post dominant-signal chip. |
| **Reels** | `src/routes/reels/+page.svelte` | Re-ranks the loaded reels (`rankNotes`), folding the `IntersectionObserver` watch-ratio into the engagement signal as a dwell proxy. Re-ranks only on load/config change (never per visibility tick — would jitter snap scroll). |
| **Discover** | `src/routes/discover/+page.svelte` | Re-ranks the "Active creators" grid by engagement velocity + recency + WoT floor when enabled. Media + tags stay count/chronological. |
| **Boot** | `src/routes/+layout.svelte` | `algorithmPreferences.load()` on mount alongside the other preference stores. |

---

## 7. Premium UX-UI additions (beyond the original plan)

1. **One-tap presets** per surface — Latest / Balanced / Trending / Trusted /
   Custom — with auto-detection of the active preset and a "Custom" badge once
   the user strays.
2. **Live weight-mix bar** — a colored stacked bar under each surface showing
   the real-time contribution of each enabled signal (turning a signal off
   instantly re-balances the others).
3. **Per-signal weight sliders** with live %-of-mix readouts.
4. **Global Freshness control** — a 4-step recency half-life (Live 1h / Balanced
   6h / Relaxed 24h / Chill 3d) that retunes the Recency signal everywhere.
5. **"Why am I seeing this?" trust chip** on ranked feed posts — shows the
   dominant signal (color-coded) without cluttering the card.
6. **"Ranked for you" banner** with a direct Tune → settings shortcut.
7. **Advanced tools** — manual web-of-trust refresh + "Reset everything".
8. **Diversity toggle** ("Avoid author runs") with a clear, always-on-by-default
   requeue algorithm that never drops notes.
9. **100% local-first hero card** with live stats (signals, following count) to
   reinforce the privacy story.

---

## 8. Notes & open questions

- **Zap totals** are read straight from `note.zapTotalSats` (already maintained
  by the feed store), so the zaps signal needs **no extra relay queries**.
- **WoT second-hop** *does* need extra kind-`3` queries. It's fetched lazily in
  a capped batch (≤40 follows) and cached in memory with a 5-minute TTL via
  `context.ts → scheduleWotRefresh`. It never blocks ranking — the first pass
  simply scores unknown authors at WoT `0`; once the cache populates, the next
  re-rank uses it.
- **Affinity** is derived from notes the user has reacted to + bookmarked
  authors, with a ~30-day recency bias so it doesn't fossilize. No extra
  queries.
- **Weights are user-tunable** but ship with the defaults above. Config is
  additive — missing signal keys fall back to registry defaults, so there's no
  migration on future additions.
- Main-feed replies remain discoverable but are scored at 0.65× for direct
  replies and 0.4× for nested replies, so original posts remain primary.
- Engagement uses age-adjusted velocity rather than lifetime totals, preventing
  old viral notes from permanently dominating fresh relevant notes.
