# Native UI Build Tracker — APP Epic Task Ledger

> **What this is:** the progress tracker for implementing every UI surface,
> field and widget of the unified feature spec
> ([`app-unified-feature-spec.md`](./app-unified-feature-spec.md)) on native
> iOS (SwiftUI) and Android (Compose). This is the working document you
> update **in the same change** as the code it describes (AGENTS.md rule).
>
> Relationship to [`delivery-plan.md`](./delivery-plan.md): that ledger owns
> infrastructure/protocol epics (SBC, PRO, ID, DAT, REL, FED, CAP, MED, EDT,
> MEM, PUB, SOC, BE, OPS, QAR, GOV). This ledger owns the **APP epic** —
> user-facing surfaces — and cross-references infra tasks where a surface
> depends on one.

---

## How to track

- Status marks: `☐` not started · `◐` partial · `✅` done (meets spec §
  checklist + DoD below) · `⏸` deferred to stated wave/tier.
- Per surface: one row per platform (iOS = SwiftUI, AND = Compose), plus
  SHARED (business-core rules/parsers) and TEST (contract/adapter tests)
  columns in the detail checklists.
- The per-surface **checklists are copied from the spec §3.x anatomy** and
  broken into shippable increments. Check an item only when BOTH platforms
  have it and tests cover it; per-platform gaps live in the Notes column of
  the master table.
- Append a dated entry to the **Progress log** for every work session
  (mirror of `delivery-plan.md` style: what shipped, what was found/fixed,
  what's next).

## Definition of done (per checklist item)

1. Matches spec anatomy/behavior on real iOS and Android builds.
2. States handled: loading (skeleton), empty, error+retry, success.
3. Offline/cancellation/kill-app behavior truthful (no fake success).
4. Accessibility: labels, 48-target, font scale, reduced motion.
5. Strings via i18n keys (en/lo), no hardcoded copy.
6. Views access data only through feature stores/view-models — never
   relays/DB/signers directly (AGENTS.md).
7. Adapter/contract or UI test added at the right boundary.
8. Tracker + (if behavior changed) spec updated in the same change.

---

## Master status table

Wave legend per spec §8. W0 foundation is prior work.

| ID | Surface (spec §) | Wave | iOS | Android | SHARED | Notes / next increment |
|:--|:--|:--|:--|:--|:--|:--|
| APP-001 | Boot, Auth & Identity §3.1 | W1 | ◐ | ◐ | ✅ | create/import/backup gate exist; app icon + native launch live; branded boot splash (animated gradient border, no spin) disabled at app entry — native system splash → shell directly for fast access (user decision 2026-08-28), component retained (APP-022); missing: switcher overlay polish, masked key echo, guest entry |
| APP-002 | Onboarding carousel §3.2 | W1 | ✅ | ✅ | ✅ | 4-page carousel live both platforms over shared `OnboardingContent` (legacy copy verbatim, icon tokens); animated dots + step counter + Next/Get Started/Skip; `hasOnboarded` first-launch gate |
| APP-003 | App shell §3.3 | W1 | ◐ | ◐ | n/a | tabs+retention done; re-tap-to-top/refresh on Home+Bitz shipped (X pattern: scroll-to-top, at-top → refresh); badges, lazy mount audit remain |
| APP-004 | Home feed surface §3.4 | W1 | ✅ | ✅ | ✅ | V1 complete: wordmark/apps-grid header, underline tabs (Following note-count label removed 2026-08-30), X-style reveal pill, infinite-scroll pagination (until-REQ, watchdog exhaust), empty/auto-retry (2 s capped, connectivity-gated), relay-error + retry, filter-mismatch Show-all CTA; W2 banner/chips/ZapLiveStrip remain |
| APP-005 | NoteCard + rich renderer §3.5 | W1 | ◐ | ◐ | ✅ | NIP-27 tokenizer + rich body + media/lightbox + NIP-36 cover shipped on text cards; polls/clamping/compact variant remain |
| APP-006 | Stories §3.6 | W3 | ◐ | ◐ | ✅ | bar + full-screen viewer + seen persistence live BOTH platforms (2026-08-29) over shared kind-30315 rules (TTL/expiration, ≤12/author, d-tag replace; 7 common tests); create card + own-first ordering, composer + mass publish remain |
| APP-007 | Bitz reels §3.7 | W1-W2 | ◐ | ◐ | ✅ | dedicated Bitz surface BOTH platforms (glass bar + persisted mode pills, explore grid, player controls, double-tap like, sensitive gate, search overlay, real Share) + delivered: rendition+mirror failover chain, 3-tab set (Explore/Following/For you — legacy Flutter parity; W2 trending/zapped REMOVED by user decision, wires migrate to default), the Flutter-parity load-more walk (fresh-playable budget, duplicate pages auto-continue, per-tab windows) and the legacy-Flutter Explore tab UX (caption+author+likes tile scrim, blurred sensitive tiles, trailing spinner tile, footer buttons REMOVED); remain: comments-sheet upgrade (iOS tree render = audit T10), record entry (T4), PoW/remix/sound/split chips, dwell ranking |
| APP-008 | Note composer §3.8 | W1 | ✅ | ✅ | ✅ | legacy-parity full-page composer on BOTH platforms over shared `ComposerRules` (counter/inserts/mention+rewrite/tag derivation, caret-accurate @-autocomplete: cursor-tracking iOS field + focus/query-gated Android panel, bare `@` lists all candidates) + POLL sheet (shared `PollContract`: kind-1 + `poll_option` tags, 2–6/280/80; compose + tolerant parse + card display; voting/bars await a vote-format decision) + GIF picker (shared `GifPickerContract`: Giphy trending/350 ms search/Recent ≤12/24 h cache/Load more) with real media thumbnails, web-parity Solar toolbar order (image · video · URL · GIF · poll · PoW · CW · hashtag · emoji), tinted error banner and selection-aware Android field; PoW gated while picks pending |
| APP-009 | Thread §3.9 | W1 | ◐ | ◐ | ✅ | remainder: [W] comment refresh control; zap-live chip on root card rides APP-004 W2 |
| APP-010 | Discover §3.10 | W1 | ◐ | ◐ | ✅ | search+chips live; results tabs, trending grid, image viewer remain |
| APP-011 | Messages/DMs §3.11 | W2 | ◐ | ◐ | ✅ | NIP-17/44 E2EE DMs end-to-end BOTH platforms (2026-08-28): conversation list + encrypted chat bubbles + send over shared `Nip44` v2 + `SecureDmComposer` (gift-wrap w/ throwaway key); 2026-08-30 parity wave: mock-complete UX both platforms — generic NIP-17 previews + unread dots, message-requests section (decline-wins), read cursors (bounded 512) + Chats shell badge (unread+requests, "9+"), delivery ticks (relay OK), encryption banner + E2EE header line, new-chat by npub, chat ⚡ zap chip (→ author zap pipeline); attachments/reactions/calls/groups + NIP-04 fallback remain |
| APP-012 | Notifications §3.12 | W1 | ✅ | ✅ | ✅ | full surface incl. zap sats+sender, deep links, visible-mark-read, per-type mutes, shell badge, media strips + NIP-36 cover, search row, read cursor + blocked-author filtering (both); [W] video-mention deep-links + zap privacy gate remain |
| APP-013 | Profile §3.13 | W1 | ◐ | ◐ | ✅ | own-profile page at legacy-Flutter parity (2026-08-29): edge-to-edge hero (gradient + hex pattern + scrims, avatar glow band), glass controls, identity block (cyan verified, npub copy chip, chips), Edit pill + ⋯ menu, completion card w/ progress bar, stats (K/M), about chips, pinned tabs w/ real content (cards/strips/3-col grid/empty states); view+edit live; follower sheets, banner lightbox, other-user action row remain |
| APP-014 | Zaps wallet §3.14 | W1 | ✅ | ✅ | ✅ | full zap flow on both platforms: legacy-parity sheet (emoji tiers, custom+comment+anonymous, QR invoice w/ live countdown + open-wallet, paid auto-close) + EXACT request-id paid matching (embedded 9734 canonical id through the client gate) + sent-zap ledger page (local records, merge w/ verified received, stat tiles + tabs) on both platforms; 2026-08-29 LNURL wire fix (invoicable again): bare 9734 object in the `nostr` param (was a relay frame servers reject), bech32 `lnurl` param, LUD-16 local-part case preserved + percent-encoded, provider `reason`/`errors` surfaced, weak `nostrPubkey` degrades to plain pay (fixture lnurl-pay-v1.json); LUD-21 verify poll remains; NWC W4 |
| APP-015 | Bookmarks §3.15 | W1 | ✅ | ✅ | ✅ | toggle + page live both platforms (More → Library → Saved); follow-up: by-id fill repo test |
| APP-016 | Communities §3.16 | W3 | ☐ | ☐ | ☐ | NIP-29 wire + UI |
| APP-017 | More / You hub §3.17 | W1 | ◐ | ◐ | n/a | V1 hub shipped both platforms, opened from the feed apps-grid: profile hero (npub copy + identity-QR dialog) + multi-account switch row + Following/Relays stat tiles + live tile groups (Discover, Lightning, Profile, Zap wallet APP-014, Settings, Saved APP-015) + About/Privacy/Terms meta rows; remains: Communities/Meme tiles (W3/W4) |
| APP-018 | Settings hub + sections §3.18 | W1-W2 | ✅ | ✅ | ✅ | shared settings contract v2 + algorithm contract + native stores; all 12 catalog sections live both platforms incl. full relays manager (CRUD, roles, status dots, NIP-65 publish) and the ranking algorithm (presets/freshness/signal weights driving the live For-You order); remains in later waves: privacy gates + blocked manage (W2/APP-012), i18n strings (APP-024) |
| APP-019 | Studio §3.19 | W4 | ✅ | ✅ | ✅ | **M1 COMPLETE (2026-09-02)**: image editor end-to-end both platforms — editing core (tray/gestures/text+sticker/coalesced undo), wire codec `com.bitos.bitz.meme` v1 (interop gate), 1080 raster export → Photos, kind-20 publish (CW/alt/imeta, verify-before-sign), autosave + ≤6 continuation slots; camera/trim/publish live (CAP/PUB); remain: GIF mode (M2), video mode (M3), V2 suite |
| APP-020 | Static pages §3.20 | W1 | ✅ | ✅ | ✅ | About/Privacy(11§)/Terms(10§) live both platforms — full legacy copy in shared `StaticPagesContent` (4 common tests lock it verbatim); More hub meta rows route to the overlay screens |
| APP-021 | Trending sounds §3.21 | W3 | ☐ | ☐ | ☐ | needs shared-sounds (kind 30078) |
| APP-022 | Component library §4 | W1 | ◐ | ◐ | n/a | avatar/menu/zap sheet + hex geometry (web .hex-clip parity) + BootSplashScreen component retained (not mounted at entry — fast-access decision 2026-08-28); GIF/poll/pickers, PowCard remain |
| APP-023 | Tokens & theming §2 | W1 | ✅ | ✅ | n/a | dark + light tokens live; theme (light/dark/system), accent palette, font scale and compact mode now APPLY app-wide from the shared settings contract (Android: `BitOSTheme(darkTheme, accentColorHex)` + dynamic `BitOSColors` palette + system-bar contrast sync; iOS: `SettingsStore` syncs `BitOSTheme.modeOverride`/`accentOverride` live and the shell/sheets drive `preferredColorScheme`); launch chain (system splash + boot splash) stays dark-branded (dark-only art); reduced-motion/high-contrast toggles remain (§2.6) |
| APP-024 | i18n en/lo §7 | W1 | ☐ | ☐ | n/a | string tables + wiring |

---

## Detail checklists

### APP-001 — Boot, Auth & Identity (spec §3.1)

- [x] Boot splash: branded, no spinner flash between branded screens; hex border = animated gradient (anchored hue ripple orange↔yellow, deliberately not the legacy rotating sweep/spin) — `BootSplashTiming.borderWave/borderStopWave` contract + tests both platforms; DISABLED at app entry 2026-08-28 (native system splash → shell directly, fast access) — component retained (APP-022), surface 5/92 inset fixed (port regression)
- [ ] Login: icon+name+tagline block
- [ ] Import field: paste, autofocus, nsec/npub/hex, `head…tail` masking, inline errors
- [ ] Generate / Import actions; guest browse entry
- [ ] Backup reveal: warning card, npub/nsec copy→check rows, identity QR, confirm-backed → Home
- [ ] Account switcher overlay: hex avatars, active check, add/remove
- [ ] Existing-session auto-boot; destructive-switch shows old+new npub
- [ ] SHARED: multi-account registry, NIP-19 decode (✅ done), secrets confined to Keychain/Keystore (✅ done)
- [ ] TEST: import validation vectors; switcher isolation

### APP-002 — Onboarding (spec §3.2)

- [x] 4-page carousel (welcome / keys / decentralized / zap) — shared `OnboardingContent` contract (legacy copy verbatim, 3 common tests); icon medallions (icon-token through AppIcons), page-rise animation (iOS)
- [x] Step counter ("n of 4"), animated dot indicator (24↔8, spring), Next → Get Started, Skip → shell (both platforms)
- [x] `hasOnboarded` persistence (SharedPreferences `bitos_onboarding` / UserDefaults `bitos_has_onboarded`) — first-launch gate before the shell; skip → shell (auth is the You tab in this shell)

### APP-003 — App shell (spec §3.3)

- [x] 5 tabs + state retention (iOS TabView/Android NavHost — W0)
- [ ] Lazy tab mounting audit (first-select build, stay alive)
- [ ] Inbox unread badge (DMs + activity, "9+" cap, privacy-gated)
- [ ] Profile tab = own hex avatar, identity-aware
- [x] Re-tap-to-top on Home + Bitz (X pattern: re-tap scrolls to top; at-top re-tap refreshes — feedRetapTick through both shells); player pause on tab hide (extends existing surface-visibility work)
- [x] Shell performance/lifecycle: Android preserves per-tab scroll/pager state while releasing hidden players; iOS owns the shared Home/Bitz feed subscription at RootView so tab switches do not reconnect relays; Android process wiring starts from Application.onCreate (never constructor init). Runtime audit also fixed iOS Wi-Fi monitor lifetime, Zap bridge recursion, Blossom expiration interop, and hidden-media duplicate branching
- [ ] Global overlay hosts: call panel placeholder, toast, confirm dialog, account switcher

### APP-004 — Home feed surface (spec §3.4)

- [x] Vertical video pager + notes list + pull-to-refresh + pagination (W0)
- [x] Filter menu popover: All/Original/Replies/Media/Liked/Mine (checks) — AppMenu both platforms; rules in shared core `FeedFilters` (7 common tests) + bridge `feedFilterMatches` for iOS; web content-classification parity (2026-08): `ContentClassification` narrows protocol payloads to serialized `channel:__roster` hex blobs (the broad JSON-prefix heuristic is retired), `isMachineTag` filters `udal-*` bot coordination tags out of `SearchResults.hashtags`, and the persisted `showProtocolNotes` opt-in now actually drives the feed windows on both platforms (was a dead setting).
- [x] Mode tabs For you / Following — sticky row under the app bar: icon + label, Following live ALL-window count, accent underline indicator, hairline divider (both platforms); Following displays real notes now (identity → contacts-resolving → notes)
- [x] App bar: wordmark leading (iOS asset w/ light+dark variants; Android `bitos_branding` density PNGs) + search action → Discover + apps-grid action → You hub (APP-017 More hub re-routes later)
- [x] GuestBanner signed-out (StoriesBar mount point lands W3)
- [x] New-notes pill: floating capsule immediately below For You/Following, overlapping ≤4 avatar stack plus count centered in a circular badge and ↑ icon; tap reveals and returns the notes list to top, hold-while-scrolled (list first-row / pager page-0 visibility drives it), at-top auto-flush; dedup via known-ids (repo contract test: hold/reveal/redelivery)
- [x] New Note extended FAB → composer
- [x] Infinite-scroll pagination: near-end triggers one `until`-REQ (oldest window note, limit 40, sub `bitos-older-N`), footer spinner while in flight, 8 s watchdog marks exhausted (`noMoreOlder`) until refresh — Android `FeedRepository.loadOlder` + contract test; iOS `FeedStore.loadOlder` + bridge `olderFeedRequest`. Full-window root-cause fix (2026-09, UX U7 "You're all caught up" dead-end): a window at the 200 cap is NOT timeline exhaustion — `loadOlder` no longer flips `noMoreOlder` merely because the window is full (cold-start hydration alone could strand the feed); older pages now EXTEND the bounded window backward via shared `FeedAggregator.insertOlder` (head eviction — retention follows the reader; bridge `FeedWindow.insertOlder` for iOS) instead of tail-evicting their own inserts; walk freshness is lane-window-scoped (`knownBefore` = the active lane window + pending notes, not the ever-growing global id set, so notes evicted by the cap and re-fetched count as FRESH progress on the next walk)
- [x] Empty/auto-retry + relay-error + retry: shared `EmptyFeedRetry` policy (2 s exponential, 30 s cap, saturating counter, connectivity-gated re-REQ — 5 common tests) drives both stores; empty states split relay-error ("Can't reach relays" + Retry now), filter-mismatch ("Show all" CTA), no-notes-yet (auto-retry hint + Retry now); `retryNow()` resets the backoff
- [ ] W2: active-filter banner + relay merge pill; pinned hashtag chips; ZapLiveStrip; ranked banner + rank chips
- [x] TEST: filter windows (common), reveal-pill ordering + dedup on merge, empty-feed auto-retry (resubscribe-while-empty + stop-on-arrival), older-page until-REQ + in-flight dedupe + full-window cold-start walk (Android repo suite — 21 green; shared lanes green incl. 3 `insertOlder` window-eviction tests; iOS `BusinessCoreClientTests.testInsertOlderExtendsAFullWindowBackward`)

### APP-005 — NoteCard & rich renderer (spec §3.5)

- [x] Author row, action bar (like/repost/zap/bookmark/report/mute), repost attribution (W0)
- [x] NIP-27 entity tokens: npub/nprofile/note/nevent/naddr (bare/nostr:/@-prefixed, TLV type-0 hex, invalid inert) — shared `Nip27` tokenizer + 9 common tests (JVM+macOS); bridge `richTokens` JSON for iOS
- [x] Hashtag + link tap targets (profiles/hashtags route to callbacks; external links styled — in-app routing lands with APP-009 threads)
- [x] Media grid ≤9 image tiles + zoomable fullscreen lightbox (video notes own the pager)
- [x] Sensitive cover (NIP-36 tag/label forms on FeedNote) with per-session reveal (imeta is-ocw pending NIP-92 parsing)
- [x] Font-scale-safe Show more/less — line-based 8-line clamp (font scaling can't break it): Android `RichText` gained `maxLines` + `onOverflow` (`hasVisualOverflow`) with a Show more/less TextButton; iOS `ExpandableRichText` measures clamped vs natural height (hidden fixedSize twin + PreferenceKeys, probe stops once offered) — compact card lists only, full-screen pages never clamp
- [ ] Poll display: bars, my vote, voters, closed
- [x] Animated LikeButton (scale-bounce + haptic) per §2.4 — `AnimatedLikeIcon` (Android components: Animatable spring ≈ elasticOut, 300 ms, LongPress haptic on like-only, Solar heart Bold/Linear, `minimumInteractiveComponentSize` 48 dp) + iOS `LikeTapIcon` (spring 0.3/0.35 + light impact on like-only); adopted in both compact card action rows
- [ ] Compact variant (bookmarks/search) — 2026-09-04: iOS Discover search reuses the full card instead (home `NoteCardRow` → shared `DesignSystem/FeedNoteCard.swift`, now also the note-card home for `ExpandableRichText`/`LikeTapIcon`/`PollOptionsView`/`RelativeTimeText`); Android Discover search reuses the shared `FeedNoteCard` via `HomeViewModel` (same day)
- [x] TEST: tokenizer contract (entities/links/hashtags/inert/merge/JSON shape) + NIP-36 projection — shared suites green

### APP-006 — Stories (spec §3.6) — W3

- [x] Bar: rings (unseen gradient hex / seen muted) + avatar + label; kind-5 deletions honored in the repo — Android live; create card + own-first ordering remain
- [ ] Viewer: tap-through, progress, per-slide like, auto-advance
- [ ] Composer: 9:16 preview, slide queue strip, sources, caption w/ mentions, gradients, imeta, PoW
- [ ] Mass publish ≤10 + progress strip; Blossom upload before sign
- [x] SHARED: `Stories` — kind-30315 slide parse (TTL/expiration, future-guard, image/gradient/PoW), insertion rules (same-id, d-tag replaceable, ≤12/author), author grouping; seen-set persistence (Android `StorySeenPrefs`) — 7 common tests
- [ ] TEST: TTL expiry, batch publish idempotency

### APP-007 — Bitz reels (spec §3.7)

- [x] Snap player + author row + action rail + autoplay gating + player pools (W0)
- [x] Glass top bar: wordmark, pill tabs (Explore/Following/For you, persisted, re-tap=top), search + refresh — new dedicated surface per platform (Android `ui/bitz/BitzScreen.kt`, iOS `Features/Bitz/BitzView.swift`), mode persisted through settings schema v4 `bitos_bitz_mode` (shared `BitzModeSetting`); pills drive the same shared feed window (Following/For-you select the repo timeline)
- [x] Explore grid = LEGACY FLUTTER UX (user decision 2026-08-29, `_ExploreGrid`/`_ExploreTile` parity): 3-col 9:16, 4 dp gutters, top pad 76 under the pills, rounded-8 tiles with press scale/dim (Android), tile footer = caption (2-line, `stripMediaUrls` parity) · author identity row (hex avatar + ⚡ badge + ✓ NIP-05, taps open the profile) · like count — zap/duration badges REMOVED; sensitive tiles = blurred poster + dim + eye-off "Sensitive" (tap opens the player, reveal gate lives there); edge reveals next 18 tiles + warms the relay page; ONE trailing spinner tile while the walk runs (footer buttons REMOVED); loading = centered spinner (12-tile skeleton removed), empty = `_BitsEmptyState` (rounded icon box + title + hint) — both pull-to-refresh; 24+18 reveal bounds still shared `BitzExplore`
- [x] Legacy-compatible bounded query: separate native NIP-71 `[21,22]` (limit 16) and kind-1 media fallback (limit 48) filters for initial/older pages; kind 21 is accepted, verified and projected on both platforms; protocol fixture `contracts/nostr/kind-21-unsigned.json`
- [x] Video controls: scrubber + seek-hint overlay, ±10 s pills, mute memory (persisted `bitos_video_muted`, DEFAULT muted — legacy web polite autoplay parity), hairline progress (slider track); pools gained seek/position/mute APIs (list-based reconciliation fixes the W0 filtered-neighbor bug)
- [x] Double-tap like (heart burst) ; tap pause; vertical swipe = pager
- [x] Search overlay: full-screen glass, instant local matches + NIP-50 through the repo's 400 ms debounce, id-deduped local-first merge (shared `BitzSearch.results` — blank query stays idle even on stray relay echoes), idle/searching/none states, tap splices into the player (≤8) + jumps
- [ ] Comments sheet: two-level threading, like/zap/reply rows, composer (GIF/URL/gallery/PoW + previews) — existing CommentSheet still opens (Android renders the shared tree; iOS flat → audit T10)
- [x] ⋯ menu: mute/copy note ID/report (raw JSON + delete-own remain W2); Share is REAL now — deterministic `NoteShare.text` through the system sheet (fixed the audit 9.4-3 no-op)
- [x] Mode-aware empty states (skeleton grid, relay-empty retry, Following-needs-identity); record entry → camera remains (audit T4 re-wires the capture pipeline)
- [x] Tabs = THREE (user decision, legacy Flutter parity): Explore · Following · For you pills, swipe cycle + final left-swipe creator shortcut on For you; W2 `trending`/`zapped` REMOVED (shared `BitzSort` + bridge `bitzTabSortIds` deleted; persisted v5 wires `trending`/`zapped` parse back to the default — Android `BitzTabsContractTest` + iOS `SettingsStoreTests` lock the migration)
- [x] Load-more root-cause fix (Flutter `_fetchReels` parity, shared `BitzTimelinePolicy` walk): ONE load-more = bounded backwards `until`-walk with a FRESH-PLAYABLE budget (18) over ≤6 batches × 4 s — pages of duplicates/text auto-continue instead of exhausting (the old watchdog marked the feed `noMoreOlder` on the first duplicate page = "load more does nothing"); FOLLOWING walks its OWN window (the global cursor was never its boundary — root cause on that tab); Explore grid reveals the next 18 tiles at the edge AND warms the relay page when the reveal catches the window (old iOS edge condition `videos.count <= visibleCount` never fired while tiles stayed hidden); triggers = settle within 2 pages of the active tab's end (Android `BitzTabsContractTest.walkContinuesWhileFreshMatchesRemainUnderBudget`; repo test asserts policy filters `kinds [20,21,22,34235,34236] limit 60` + `[1] limit 150`, cursor oldest−1)
- [x] Rendition + mirror failover (FED-004): imeta `fallback` mirrors + `fallbackrendition variant` ladder parsed on the shared read path (bounded ≤8 each, hostile fields dropped), static pick = tallest fitting `screen × 1.25` (smallest on overshoot, primary when no ladder), player chains walk pick → mirrors → renditions ONLY on item error (Android ExoPlayer `onPlayerError`; iOS `AVPlayerItemFailedToPlayToEndTime` observer); bridge carries `Note.fallbackUrls`/`renditionSpecs` (`url|height|bitrate` rows) + `mediaPickRenditionUrl`; protocol fixture `valid-kind22-rendition-ladder` (signed, nostr-tools)
- [ ] W2/W3: PoW + remix + sound + split chips; author mode; dwell ranking feed
- [ ] TEST: search cancel; comment tree guard — shared BitzTest covers merge/bounds/duration/share + walk-policy/sort/entry tests; MediaMetadataTest covers mirrors/ladder/hostile-drops; SettingsTest v5 keys

### APP-008 — Composer (spec §3.8)

- [x] Text publish + staged media upload + progress + receipts (W0)
- [x] Publish button state (disabled/spinner) + published state (success → back to feed / new post) — Android composer page; shared `ComposerRules` is the rule source
- [x] Author header (avatar + display name + “Now”) — Android page (profile from the feed store, npub fallback)
- [x] Mention field @autocomplete → nostr:npub rewrite at publish (≤6 candidates, display-name filter; tracked picks rewrite via `ComposerRules.rewriteMentions`; p-tags derive from the rewritten content) — Android page; common tests
- [x] Image grid removable ≤4 (gallery picks + validated image URLs, 96dp thumbs w/ ✕) — Android page; picks upload hash-verified through Blossom BEFORE signing (legacy order)
- [x] Content-warning field (NIP-36 tag + optional reason) — Android page
- [x] Toolbar (legacy order: image · URL · GIF · poll · PoW · CW · hashtag · emoji): image, media-URL, hashtag insert, emoji 32 palette, PoW (badge), CW toggle, POLL sheet (2–6 choices ≤80, question ≤280, live counters; publishes kind-1 + `poll_option` tags + hashtag t-tags through the tags path — both platforms) and GIF picker (Giphy trending + 350 ms debounced search + Recent ≤12 + 24 h cache + Load more + "Powered by Giphy" footer; pick embeds the full-res URL as a plain image; shared `GifPickerContract` rules + both-platform sheets)
- [x] PoW (PowCard: 0–30 slider, hash viz, chunked background mining + cancel/retry) — now tags-aware (template byte-matches the published event incl. derived tags); last-difficulty memory (`PowPrefs`) still pending
- [x] Char counter 4,000/16,000 — shared `ComposerRules.counterState` (grouped label, target switch at soft limit, near/over states); Android ring UI (web parity)
- [x] Draft persistence + discard confirm — shared `ComposerDraftContract` (versioned v1 wire: text ≤16k, ≤4 remote URLs, CW ≤120, ≤8 tracked mentions, pow 0–30; encode clamps, lenient decode → null on corrupt/oversize — 4 common tests incl. hostile clamp + round-trip); adapters: Android `ComposerDraftStore` (SharedPreferences `bitos_composer_draft`, autosave on change, restore on open, clear on publish/new post, BackHandler + close with "Discard draft?" confirm) / iOS UserDefaults via bridge `composerDraftEncode/Decode` (same behaviors, confirmationDialog); local picks are deliberately NOT persisted (ephemeral URIs)
- [x] TEST: poll wire format (PollTest 5) + draft round-trip (ComposerDraftTest 4); PoW nonces verify (existing)
- [x] iOS composer page (full-screen from FAB) + bridge surface — `ComposerScreen` (fullScreenCover; pbxproj registered) with the full parity set; bridge carries `composerCounter/insertHashtag/insertEmoji/mentionQuery/mentionSuggestions/isComposingMention/rewriteMentions/composeContent/deriveTags/emojis` + tags-aware `composeTextNoteWithTagsEventId`/`textNoteWithTagsPublishMessage`/`mineTextNotePowWithTags`/`powTextNoteWithTags*`; NotePublisher grew `publishNote(tags:)`/`publishPowNote(tags:)`/`minePowChunkWithTags`; mention detection at the REAL caret via `ComposerTextEditor` (cursor-tracking UITextView wrapper: UTF-16 caret offsets matching `ComposerRules` indices, focus reporting, programmatic edit token for toolbar inserts / mention picks / draft restore with legacy refocus behavior)

### APP-009 — Thread (spec §3.9)

- [x] Root fetch + comments list + reply publish (W0)
- [x] Root resolution: shared `EventRefs` parses note1/nevent1/naddr1 (± `nostr:`, TLV author + ≤4 relay hints, lenient-but-strict → null on invalid) + `requestFilter` (by id / newest NIP-33 `#d` version); Discover search treats a ref query as a root fetch and opens the thread sheet on arrival (both platforms)
- [x] X-style threading: shared `ThreadAssembly` — NIP-10 root/parent anchors (`rootAndParent`: markers win, legacy first/last positional), chronological children, depth cap 8 flattening, cycle guard (visited-set; unreachable cycles surface as orphans), missing-parent replies surface at top level flagged, window ≤200 — `FeedNote` carries `threadRootId`/`threadParentId` from the projection; Android `threads` state + indented rows w/ conversation rail + orphan note; iOS `ThreadDisplayItem` via bridge `threadItemsJson` (stable JSON shape) + same rendering
- [x] Root action row full (reply count, like+count, repost+count, zap "N · 21K" sats, ⋯ raw-note dialog) — live via shared `NoteTally`; 2026-08-30: ⋯ menu completed both platforms — Share (shared `NoteShare` copy through `NoteShare.text`/bridge `noteShareText` → ACTION_SEND chooser / `ShareSheet`) + Copy link (`https://njump.me/<id>`); delete-own kind-5 shipped with the raw dialog
- [x] Live deltas: shared `NoteTally`/`NoteTallies` (kind-7/6/9735 merge + summed zap msat, ≤32 bounded window; 3 common tests) — thread REQ widened to kinds [1,7,6,9735]; root card AND reply rows show live counts on both platforms — replies render inline reactions/zaps+sats deltas
- [x] Reply bar: media chips, GIF/URL/gallery/PoW options, participant p-tags — legacy `_ThreadReplyBar` parity on BOTH platforms: shared `NoteComposer.replyTags` (ALWAYS both NIP-10 markers `['e',root,'','root']+['e',target,'','reply']`; participant p-tags = target author + target's p-tags, 64-hex, deduped, ≤16; content entities/hashtags via `ComposerRules.deriveTags` merged per kind — 4 common tests + bridge `replyTagsJson` contract test) and the reply rides the tags-aware note path (`publishNoteWith`/`publishPowNoteWith` Android, `publishNote(tags:)`/`publishPowNote(tags:)` iOS) with PoW mined over the byte-exact template; bar UI = reply-to chip on sub-replies (rows get a Reply action that retargets), `AttachmentPreviewRow` (new shared component both platforms, spec §4: 64dp tiles, cover thumbnails, video play surface, bottom-left GIF badge, always-visible ✕), options row gallery · GIF · URL · PoW ("N bits" label), pill input (radius 20, 1–4 lines) + circular send with spinner; gallery picks upload hash-verified through Blossom BEFORE the URL joins the reply (legacy order); GIFs embed through the APP-008 picker; clear-on-ACK only (legacy clears on success)
- [x] States: loading/invalid/not-found + contextual back — 2026-08-30: shared `ThreadOpen.classify` (bech32 + bare 64-hex fallback) + `ThreadOpenCopy` (locked wording both platforms via bridge `threadOpen*` getters); Android `RefOpenPlate` dialog in FeedScreen + iOS `RefOpenPlate` alert in HomeView: Invalid = no REQ issued, Loading polls 3 s (20×150 ms) → thread sheet, NotFound offers Retry + Add relay (applies nevent TLV hints through the platform relay manager); ref search path unchanged
- [x] TEST: `ThreadAssemblyTest` (10: markers, positional forms, chronological order, cycle×2 fixtures, orphan surfacing, depth cap, window bound) + `EventRefTest` (5: note/nevent/naddr round-trips built with the internal TLV encoder, invalid → null, request filters); shared lanes 230/230

### APP-010 — Discover (spec §3.10)

- [x] Search (NIP-50) + topic chips + npub resolve (W0); 2026-09-04: iOS search bar moved onto the design-system `BitosSearchField` (shared `BitosTextField.swift`, medium = 44 pt touch target, magnifier lead, inline clear, focused accent border, search keyboard) + Cancel affordance; Bitz search overlay adopts the same field; Android parity (same day): Discover + Bitz search overlays moved onto the shared `BitosTextField` with an inline clear affordance; hashtag recall: `#tag` queries broadcast the standard NIP-01 `#t` filter (`SearchResults.queryTag`/`tagRequest` + bridge `searchTagRequest`, both platforms) — NIP-50 leaves `#` undefined in search strings, and the `#t` filter is indexed by every conforming relay, so tag recall no longer depends on NIP-50 support; matching stays local + verified via `SearchResults.matches`. Later 2026-09-04: Android idle page rebuilt to the prototype `#/discover` home — recent-search chips (settled-query recording, cap 6, Clear), Trending on Nostr card (hairline-divided rows, flame for the hot head, shared hashtag fan-in over the live window), Creators-to-follow rail (people fan-in minus me/followed, orange Follow chip through the feed VM) and the Explore Bitz 3×3 mosaic (standard NIP-68/71 media from the shared window, sensitive tiles excluded, ▶ live-tally badge, tile → shared player author mode); iOS home still ships the topic-chip list
- [x] Results tabs: Posts / People / Hashtags — shared `SearchResults` fan-in (3 common tests); Android tab row + People rows (Follow/Following via feed VM) + Hashtags rows (tap → re-search); iOS mirror via bridge JSON. 2026-09-04: iOS Posts results render the shared home card (`NoteCardRow` promoted to `DesignSystem/FeedNoteCard.swift` as `FeedNoteCard`; like/comment/repost/zap/bookmark/poll/mention/external-link wired to the shared `FeedStore`, ⋯ menu + note-ref open stay home-only) and the people/hashtag fan-in rows are memoized per data change (`.task(id:)` on a result/profile signature) so typing no longer re-runs the bridge JSON per render. Android parity (same day): Posts results render the shared `FeedNoteCard` (like/comment/repost/zap/bookmark/polls/ranking signals wired via `HomeViewModel`, zap sheet + thread sheet hosted in the tab; hosts without a feed VM keep the plain `SearchCard`). Later 2026-09-04: Android Posts tab is home-parity chrome — full-bleed cards with hairline dividers between (not floating inset cards); the shell params dropped at the SearchResults seam (sensitive default, zap preset, external-link host) now thread through; Hashtags tab picks re-run the search pipeline (was a dead no-op) and the literal `${hit.tag}` label rendering bug is fixed
- [ ] Trending mosaic grid + skeleton 18 + load-more + error/empty — 2026-09-04: Android Discover home ships the 3×3 Explore Bitz mosaic first paint (real NIP-68/71 window tiles, poster loader + tally badge, tap → shared player); skeleton-18, scroll load-more and error/retry states remain
- [ ] User rows: NIP-05 badge, follow/unfollow
- [ ] Fullscreen image viewer (zoom, author bar, follow, open-note bar)
- [ ] Debounce + cancel; search cache
- [ ] W2: Media tab + gallery; latest-notes + trending rails; WoT badge
- [ ] TEST: search cancel semantics; tabbed result fan-in

### APP-011 — Messages/DMs (spec §3.11) — W2 (DMs), W3 (calls/groups)

- [x] Conversation list: rows (avatar/preview/time), empty + connecting states — shared `DmGrouping` (4 tests); 2026-08-30: generic NIP-17 previews ("New message" — never plaintext outside the chat), unread dots, NIP-17 privacy banner; search, tabs All/Unread remain
- [x] Message requests section: "N waiting — accepting never reveals you read them" — shared `DmPresentation.isAccepted` (decline wins; sending auto-accepts) + accept/decline actions; unread from non-accepted peers counts via request badge, not conversation unread
- [x] Read model: per-peer read cursors persisted bounded 512 (SharedPreferences / UserDefaults), opening a conversation marks read via shared `DmPresentation.nextCursor` (never rewinds); unreadCount feeds the Chats shell badge ("9+" cap) both platforms with shell-level account wiring
- [x] New-chat dialog: paste npub, following picks, recents; `?to=` deep link (npub/hex paste with validation via shared npub codec — "Not a valid npub or hex pubkey."; following picks + `?to=` deep link remain)
- [x] Chat: header (avatar + short pubkey/name → profile, back), encrypted bubbles (sent right/accent, received left/surface, timestamps), BitosPlainTextField input + Send — NIP-44 v2 + NIP-17 end-to-end; 2026-08-30: "End-to-end encrypted · NIP-17" header line, encryption banner ("decrypt only in this app — never in a push notification"), ⚡ Zap chip → author zap pipeline, delivery ticks (`sending…` → `· delivered` via relay-OK absorption); day dividers, NIP-05 remain
- [x] SHARED: NIP-44 v2 (`Nip44` — ECDH conv key + HKDF + pure-Kotlin ChaCha20 + power-of-two padding + HMAC-SHA256, 8 tests) + NIP-17 gift-wrap (`SecureDmComposer` — rumor→seal→wrap with throwaway key + randomized timestamps, 5 tests) + presentation rules (`DmPresentation` — unread/preview/acceptance/cursor, 5 tests; bridge `dmUnreadCount`/`dmPreviewLine`/`dmIsAccepted`/`dmNextCursor` locked by bridge contract tests) so SwiftUI and Compose derive identical state — 323/323 shared lanes green; NIP-04 fallback + protocol negotiation follow
- [x] TEST: encrypt/decrypt vectors (NIP-44/ECDH fixtures); DmPresentation common suite + bridge contract tests; delivery state machine (relay-OK absorption covered by repo/store behavior; Android `DmRepositoryTest` cursor/acceptance round-trips via prefs port)
- [ ] Message body: media detection (image/video/file), inline player, lightbox, links, encrypted indicator
- [ ] Input bar: autogrow, attach image/file, emoji, send, previews + progress
- [ ] W3 calls (post SOC-010 review): ringer, panel, quality HUD, outcomes
- [ ] W3 groups NIP-29: list/join/messages/members/admin/leave

### APP-012 — Notifications (spec §3.12)

- [x] #p subscription rows with kind badge + author + time (W0)
- [x] Primary tabs All/Unread/Mentions/Replies (+counts) + activity chips Zaps/Likes/Reposts/Follows — client-side filters via shared `NotificationFilters` (3 common tests; bridge predicates for iOS)
- [x] Day sections (Today/Yesterday/weekday from shared UTC epoch-day) + iOS-style grouping "pk… and N others liked your note" — shared `NotificationSections` (6 common tests; follows/replies/mentions never aggregate; actor cap 4, ≤14-day window; stable group JSON locked by bridge test)
- [x] SHARED additions: `FOLLOW` kind (kind-3 extraction + republish dedupe per author), `OriginNotes` bounded preview projection (URL-stripped excerpt ≤160, first-URL thumb)
- [x] OriginNotePreview: batched ids REQ ≤100 (codec `encodeIdsRequest`), 8 s missing → "Note unavailable", verified-frame fill with excerpt + author·time + video glyph (Android repo contract test; iOS via bridge `originNoteFromFrame`)
- [x] Unread dot + mark read / mark-all (read ids persisted — SharedPreferences `bitos_notifications` / UserDefaults `bitos_notification_read_ids`, bounded 500) + per-row ⋯ menu: mark read · copy note id · raw event JSON dialog (AppMenu on both)
- [x] States: loading / empty / empty-filter (relay-error+retry pending)
- [x] Expandable search row (name/content) — shared `NotificationFilters.queryMatches` (bounded 64, case-insensitive, author display name participates; common test); UI applies the predicate at the list seam (Android InboxScreen, iOS `InboxStore.sections` via bridge `notificationQueryMatches`)
- [x] Row: zap sats from bolt11 msat + verified 9734 sender (shared `Bolt11` HRP parse + `ZapReceipt.senderPubkey` via the client verified gate; tests incl. tampered/mistargeted descriptions) — title lines show "⚡ 21 sats" / "2 zaps · 1,234 sats"; tap → thread sheet (origin full content) or author sheet on both platforms; unread accent stripe (dot shipped)
- [x] Media strip ≤4×16:9 (distinct image/video URLs, FeedNote classification parity — common test); OriginNotePreview thumb + NIP-36 sensitive cover with per-row reveal + lightbox on both platforms; `Nip36` shared rule now dedupes card + preview flagging
- [x] Visible-mark-read-on-open (1.4 s; late arrivals restart the timer) + per-type mutes (persisted `muted_kinds`, evicted + badge-excluded; mute menu on the Activity header) + unreadCount → shell badge ("9+" cap: Android BadgedBox / iOS .badge) with shell-level account wiring so the inbox subscribes from app start
- [x] Read model remainder: cursor persistence (`NotificationFilters.isRead` — explicit ids ∪ created-at ≤ cursor, marking advances the cursor so relay redelivery never re-rings; persisted `cursor_seconds` in SharedPreferences / UserDefaults; common + repo-contract tests) + blocked-author filtering (shared `BlockList` kind-10004 parser, bounded ≤500, newest verified head wins, rows evicted + arrivals filtered + badge-safe; repo contract test with signed 10004 fixture)
- [ ] [W] video mentions deep-link into Bitz author mode; privacy gate hides zap amounts
- [x] TEST: grouping/aggregation/filters/origin-projection common suites; Android `NotificationRepositoryTest` (signed in-test frames: extraction + follow-dedupe + raw frames, read-state round-trip through the prefs port, origin batch + timeout + tampered-frame reject, +block-list evict/filter, +cursor redelivery/reload) — 7 green; iOS `NotificationBridgeRuleTests` lock the media-strip keys + cursor/query predicates

### APP-013 — Profile (spec §3.13)

- [x] Own profile view + kind-0 edit publish (W0); author sheet for others
- [x] Edge-to-edge hero (2026-08-29, legacy `profile_view` parity both platforms): banner 160 + `115° primary-400→700` gradient default + hex-tile overlay (also over banner images), from/via/to-black scrims, zero-gap identity band w/ overlapping hex avatar (drop shadow + primary glow), ⚡ chip; banner lightbox remains
- [x] Glass floating controls (2026-08-29): share (copies `njump.me/<npub>`), edit-cover pill (camera icon), settings; 36pt circle black/30 + blur (iOS material), pill black/30; back button n/a on the tab
- [x] Info block (2026-08-29): display name 24 w800 ("Anonymous" fallback), cyan verified badge, @username, npub mono chip (copy → green check 1.8 s), ⚡ Lightning chip, NIP-05/website/lud16 info chips (radius 8, tint@10%) + hairline divider; QR via ⋯ menu
- [x] Action row — own (2026-08-29): Edit-profile pill (primary, pen) + 44-pt outlined ⋯ menu (Settings · Zap wallet · Copy profile link · Copy npub · Show profile QR · Copy lightning); other-user Follow·Message·Zap+⋯ remains on the author sheet
- [x] Stats row (2026-08-29): Posts/Following/Bitz space-evenly, 1.2K/1.2M counts; follower/following sheets remain
- [x] About expandable (2026-08-29): 5-line clamp, Show more at 240 chars
- [x] Completion card (2026-08-29): sparkles tile, "{score}% · N steps to go", primary→cyan 8-pt progress bar, missing-field pill chips, Finish pill
- [x] Sticky tabs (2026-08-29): pinned 48-pt rail (page-background — no surface block, underline indicator, outline/15 divider) — Notes/Replies (replying-to strip)/Bitz (3-col grid, video scrim+play)/Reposts (reposted-by header) with per-tab empty states; profile note cards (author row, 6-line clamp + show more, media row/video tile, hairline dividers)
  - 2026-08-30 refactor: profile note cards replaced by the shared `FeedNoteCard` (`apps/android/.../ui/components/FeedNoteCard.kt`) — the exact home-feed card (NIP-27 rich body, media lightbox, polls, like·comment·repost·zap·bookmark, ⋯ mute/report menu). Own + author profile pages now render notes identically to the home feed; the bespoke `ProfileNoteCard` was removed.
  - 2026-08-30 web-stats parity (both platforms): own-profile stats row gains "Sats zapped" (tap → zap wallet) from the new shared `AuthorZaps` rule (local sent records + verified received 9735 receipts — never unverifiable relay totals) via bridge `authorZapsSummary`; own profile gains a 5th "Zaps" tab (merged ledger entries, newest first, ⚡ empty state) reusing the wallet ledger row (Android `ZapLedgerRow` / iOS `ZapLedgerRowView` + shared `ZapsView.decodeLedgerRows`). Author profile adds "Zapped by you" stat + "Zaps" tab (4th) from local `SentZapLedger` records filtered by `recipientPubkey == authorPubkey`.
  - 2026-08-31 web Bitz-tab parity (both platforms): profile Bitz-tab tiles now open the shared reels player scoped to that author (web `ProfileBitzGrid` → `/bitz?author=<npub>#bitz=<id>` parity) — same player page with the full action rail, deep-linked at the tapped tile (chronological order so the tapped video is first), author window paged by the profile's own REQ (page 5, `until` cursor). Author chrome replaces the mode rail with a back-to-profile bar (creator name opens the full profile); mode switching, side-swipe, new-note splicing and global-session freshness are all bypassed in author mode. Android: `BitzScreen(authorPubkey, initialNoteId, onExitAuthorMode)` hosted as a `BackHandler`-guarded overlay in `BitOSApp.kt` (`authorBitzTarget`), reusing the app-wide `authorRepository`. iOS: `BitzView` gains the same params + private `AuthorStore`; hosted via `.fullScreenCover(item: $bitzPlayerTarget)` from both `ProfileView` and `AuthorProfilePage` (`BitzPlayerTarget`). In author mode an inner author tap exits the player and opens the full profile page (the shared author repo/sheet would be re-scoped and wipe the window).
  - 2026-09-04 fix: author-profile header no longer pads the status bar itself — the shell Scaffold's `contentWindowInsets` already applies it, so the extra `statusBarsPadding()` doubled the gap between the status bar and the back/title row (same fix applied to the Bitz author-mode back bar, `BitzScreen.kt` `BitzAuthorBar`).
- [x] Guest state (browse-first panels); npub/hex param resolution for the author sheet
- [x] Editor as a legacy page + step flow (2026-08-29): full-screen page with back header + full-width Save pill (not a bottom sheet); source sheet step ("Take photo" via UIImagePickerController / TakePicturePreview, or library picker) → center-crop (EXIF-normalized on iOS) → Blossom upload → live preview header (`_ProfileHeaderPreview` parity: banner 120 + change pill, hex avatar 88 + 28-pt camera chip); Save gated while an upload is in flight (sign only after upload+hash); full 8-field kind-0 form retained
- [ ] W2: Media gallery + heatmap, Liked/Pinned/Zaps tabs, mini identity; banner lightbox; follower/following sheets; other-user action row parity (Follow·Message·Zap + mute/block/report)
- [ ] TEST: param resolution; stats reconcile

### APP-014 — Zaps wallet (spec §3.14)

- [x] LNURL resolve + invoice + kind-9735 counting (W0)
- [ ] ZapDialog: 4 tiers ⚡💜🔥🚀 + custom, comment ≤200, anonymous toggle
- [ ] Invoice: branded QR + expiry countdown + lightning: link + copy
- [ ] Receipt watch + LUD-21 verify poll (3 s) → auto-close; late "confirmed" upgrade
- [ ] 9734 relays tag policy (recipient NIP-65 ≤6 + own ≤3, cap 8)
- [ ] Ledger page: stat tiles, All/Received/Sent tabs, rows (direction/sats/peer/comment/note link), states
- [ ] W4: NWC connect + balance + deposit/withdraw modals (WebLN rejected)
- [ ] TEST: bolt11 msat parse; verify-poll settle; sent-ledger record

### APP-015 — Bookmarks (spec §3.15)

- [x] NIP-51 30003 compose/publish/reconcile + optimistic toggle (W0)
- [x] Page (2026-08-29, both platforms): saved list as compact rows
      (avatar + name/NIP-05 + time + 2-line excerpt), newest-saved first
      (reversed save order, window notes + by-id refetch merge), unbookmark
      action per row (existing optimistic toggle + 30003 publish), empty
      state, "Loading N saved notes…" pending row, relay re-fetch on open
      (≤100 ids REQ — Android hand-built filter / iOS shared
      `eventsByIdsRequest`); entry = More hub Library "Saved" tile (was the
      disabled coming-soon row); row tap → thread sheet. Follow-up: repo
      contract test for the by-id fill path (reuses the verified absorb
      path; BookmarkList rules already covered in common tests).

### APP-016 — Communities (spec §3.16) — W3

- [ ] SHARED: NIP-29 wire (kinds 9/10, 9000s, 9021/9022, 39000s)
- [ ] Joined list + join dialog (relay+id, invite prefill)
- [ ] Group view: messages, members/admins, rename, invite link, leave, unsend

### APP-017 — More/You hub (spec §3.17)

- [x] Guest freedom card; profile hero (avatar, name, npub chip, QR dialog, stat tiles) — both platforms
- [x] Tile groups Explore/Library/Account per spec — all tiles live surfaces: Discover, Lightning & Zaps (settings deep-link), Profile, Zap wallet (APP-014 full-screen cover, 2026-08-31), Settings, Saved (APP-015); the dead "coming soon" zap-ledger tile is gone — never dead links
- [x] Account switch row → switcher sheet (branded switch overlay)
- [x] Meta rows About/Privacy/Terms (APP-020 static covers)

### APP-018 — Settings hub + sections (spec §3.18)

- [x] Hub shipped (V1 core): account hero (npub copy→check), preferences (notifications/sound/haptics toggles persisted via UserDefaults / SharedPreferences `bitos*` keys), version row, sign-out with confirm (identity removal)
- [x] SHARED settings contract v2: 18 `bitos_*` keys (incl. accent color, video playback rate; schema 2), typed enums + immutable snapshot, wire codec (corrupt/oversized → defaults, never crash), normalize/isValid, clear-cache protected device globals, cache-size formatting, shortNpub, deterministic 12-section catalog (hero · preferences · content · support) — common tests on both lanes + iOS/Android adapter-contract tests
- [x] All 12 catalog sections live on both platforms: account (identity + profile editor sheet + storage/clear-cache), lightning (default-zap presets + fine stepper), privacy (media auto-load + protocol notes + honest trust-wave gates), notifications, appearance (theme/font/compact + 15-color accent palette), algorithm (timeline + preview/reactions/protocol toggles), security (npub copy + confirm-gated nsec reveal + danger zone), media (autoplay/quality/playback rate), language (+region: date format, tz), relays (configured list + read/write role chips + live connected count), help (static FAQ accordion), about (schema/version rows)
- [x] `relays` manager: shared `RelayListContract` (bounded ≤16, url-unique, ≥1 role; versioned JSON wire; NIP-65 r-tag projection incl. omitted-marker = read+write) + `composeRelayList` — CRUD + per-relay read/write toggles + live status dots + kind-10002 publish to the set's write relays; edits apply to the live pool immediately (add/remove connect/disconnect sockets); persisted managed set boots the pool on cold start (defaults when empty/corrupt); adapter-contract tests both platforms
- [x] Algorithm ranking (origin `algorithm-plan.md` parity): shared `AlgorithmContract` + `FeedRanking.rank` drive the live For-You order on both platforms (presets/freshness/signal weights + mix bar; off = chronological; Following always chronological)
- [x] Privacy v3: sensitive-media default (drives SensitiveCover) + blocked-users manage (kind-10004 head, unblock publishes a new head; blocked set filters feeds + inbox)
- [x] Theme/font/accent application app-wide (APP-023): light/dark/system + accent palette + font scale apply live from the shared settings contract on both platforms; language strings (APP-024) remain

#### APP-018a — Flutter parity audit (source: `~/Desktop/bitos/bitos-nostr-flutter`, audited 2026-08-28)

Field-level gap list from the old app's real code (`settings_controller.dart` 468L,
`settings_section_page.dart` 1128L + 5 page files, `core/settings/*`,
`core/auth/account_manager.dart` 478L, `shared/widgets/account_switch_overlay.dart`,
`modules/static/static_pages.dart`). Ordered by user impact; each row is a
shippable increment. **Native is AHEAD on: NIP-65 relay-list publish (Flutter
relays have no 10002), kind-based notification mutes (6 kinds vs Flutter's 5
push toggles), relays live status dots, zap sats display.**

| # | Old-app feature (source) | Native now | Gap → plan |
|:--|:--|:--|:--|
| 1 | ✅ **Account switcher** (2026-08-28) — SHIPPED: shared `AccountRegistry` (bounded ≤8, secret-free wire) + Android `AccountRegistryStore`/`SecureKeyStore` pubkey-keyed slots + `IdentityViewModel.switchTo/signOut(deactivate)/removeRegisteredAccount` + iOS `IdentityStore` registry + `IdentityKeychain` slots; switcher card in Settings-account (hex avatar, name/npub, active check, Remove w/ confirm); sign-out now deactivates (slots survive) both platforms. Branded switch OVERLAY still pending (V1 swaps state directly) | done | registry + slots + switcher + branded switch OVERLAY shipped (MoreScreen/MoreView AND the Settings account switcher both platforms — every switch rides the brand beat); auth-screen one-tap rows remain |
| 2 | ✅ **Privacy store** (2026-08-28) — SHIPPED: shared `PrivacyPrefsContract` (8 gate fields, per-field tolerant, enums validated; sensitive media stays the settings-contract key, push toggles stay the kind mutes — no duplication) + native stores + Account-privacy card (6 toggles) + Interactions card (message/comment permission pickers) both platforms; enforcement footnotes honest (W2 DMs) | done | — |
| 3 | **Profile editor fields** — picture + banner (image_picker → crop → upload w/ progress + local preview), website URL field; optimistic metadata cache + kind-0 publish | name/display/about/nip05/lud16 only | extend ProfileEditSheet both platforms: website field (trivial), picture/banner pickers (needs CAP media path reuse: pick → Blossom upload → URL into kind-0) |
| 4 | ✅ (2026-08-28) — SHIPPED: `RelayListContract` schema 2 `primary` flag (≤1, write-only, first in fan-out) + ⭐ toggle + add-dialog suggestion chips (primal/damus/nos) both platforms | done | — |
| 5 | ✅ (2026-08-28, Android) `EventCache.clearAllCache` + `FeedRepository.clearEventCache`; clear-cache row wipes both. iOS (2026-08-29): `FeedStore.clearDerivedState()` — profiles/thread views/tallies/bookmark+block projections wiped on clear-cache (live subs re-fetch); persisted cache arrives with DAT-003 | done | persisted iOS cache = DAT-003 |
| 6 | ✅ (2026-08-28, Android) presets 1/5/21/100/500/1000. iOS uses a stepper (equivalent). Ledger link → APP-014 | done | — |
| 7 | ✅ (2026-08-28) — SHIPPED: `diversityEnabled` per surface (AlgorithmContract schema 2) + `FeedRanking.applyDiversity` (author-clustering requeue, ≤2 consecutive, guarded drain, never drops — origin diversity.ts parity, deterministic, tested) + Diverse-authors toggle + Reset-to-preset both platforms; stat tiles + preset icons remain cosmetic | done | cosmetic extras only |
| 8 | **Appearance live preview** — sample widget rendering with the picked accent/theme | honest pending rows | ship with APP-023 theming (preview needs real token application to be meaningful) |
| 9 | **Help cards** — Help center / Contact / Report a problem / Feature request (4 cards) + 4 popular articles | FAQ (7) + support/donate + links | static content, needs real destinations — fold into APP-020 static pages (links currently snackbar-only in old app → do NOT fake) |
| 10 | **AboutPage sections** — hero CTAs (Get Started), feature-cards grid, open-source card, legal footer (Terms/Privacy links) | brand card + NIP chips + schema ✓ | APP-020 static pages (about/privacy/terms full text already specced §3.20) |
| 11 | **Media provider picker** — None / Blossom / Cloudinary tiles + config | Blossom (default) read-only row ✓ | [W] per spec; Cloudinary needs server config UI — keep honest row until provider exists |
| 12 | **Security** — same as native (npub/nsec reveal + danger zone; NO biometric in old app either) | parity ✓ | nothing missing vs old app; app-lock stays spec-[W] |

### APP-019 — Studio (spec §3.19) — W4 quick, V2 full

Full system plan: `docs/native/meme-studio-plan.md` (M0–M4 task IDs MST-xxx
below map to that plan; EDT/MEM epic rows stay the source of truth for the
V2 suite).

- [x] `#/create-edit` layout correction (2026-09-05, BOTH platforms): source inspection against `docs/ui/prototype/js/ui-bitz.js` found that the parity implementation had changed the prototype's 46 px circular quick tools into wide pills and added a second video source strip above the compact timeline. Quick tools are circular again with native 52 dp/pt accessible targets; the basic video editor now has one timeline, while clip/layer insertion remains available in the expanded Timeline workspace. iOS picker presentation moved to the editor root and the Clips sheet now opens the video picker, preserving add-clip after the duplicate strip was removed. Post details adopts the prototype's flat caption/tag fields and 20 px side rhythm, enforces the displayed 300-character caption cap, and removes internal wave-status copy from the product UI. Android uses a distinct draft-save glyph so it is not confused with Export. Verified: Android `:apps:android:compileDebugKotlin` green; full-app iOS Swift 6 strict-concurrency type-check green; structure check green. Physical-device visual comparison remains required.

- [x] Camera capture + preview + trim + media publish (W0, CAP/PUB epics)
- [x] M0 MST-001..005 — shared `studio/` schema v1 + rules + publish contract (common tests), hub enable/disable + Solar tokens, iOS Home camera entry, bridge surface. Done: `MemeProject`/Contract + `MemeCommandCodec` (20+ hostile-clamp/round-trip tests), `MemeRules` (overlay CRUD/hit-test/coalesced undo), `StudioPublishContract` (verify-before-sign enforced structurally); bridge `memeProjectNormalize/memeApplyCommand/memeHitTest`; hub rows honest (Soon chips + Solar/AppIcons tokens, iOS app-bar camera opens the hub). Quick MEM row flips on with M1.
- [◐] M1 MST-010..015 (wave 1, editing core — DONE 2026-09-02) — image editing loop live on BOTH platforms minus export/publish/persistence: Quick MEM row opens the editor (Android `ui/create/meme/MemeEditorScreen` + `MemeEditorState`, iOS `MemeEditorView` + `MemeEditorStore`); shared `StickerCatalog` (web `stickers.ts` port: 6 packs × 8, recents ≤16); multi-pick ≤9 tray (Photo Picker / PhotosPicker) with active-asset switching; stage with tap-select via shared hit-test, one-finger drag / pinch / twist, delete handle + dashed selection bounds (shared `estimateBounds` via bridge `memeBounds`); text sheet (4 font-slot pills, 16-swatch palette, size + outline sliders, shadow toggle — live through `MemeCommand.UpdateOverlay`, which now carries `size`); coalesced undo (a gesture = ONE net step; style bursts merge in a 300 ms window; bounded 60; cancel-gesture restores). Bridge grew `memePalette/memeDefaultOverlay/memeBounds/memeStickerPacks` (+ contract tests both lanes). Deferred within M1: URL import; MST-016 export; MST-017 publish (kind 20); MST-018 autosave slots — session-only editor until MST-018, guarded by the ✕/back discard confirm.
- [x] M1 MST-019 (wave 2, interop gate — DONE 2026-09-02) — meme wire document `com.bitos.bitz.meme` v1: shared `MemeWireDocument.kt` = `MemeWire` constants (web `schema.ts` verbatim: ≤12 overlays / 300 chars / size fraction 0.03–0.22 def 0.09 / 7-hex palette / fx+look+SFX id sets) + `MemeWireCodec` (tolerant decode, canonical encode, `normalize`; foreign schema/version rejected; `updatedAt` re-stamped via injected `nowMs`; **passthrough preservation** — unknown root/overlay/cue fields survive a native round-trip, strictly safer than web's drop) + `MemeWireConvert` (wire ⇄ local: fraction ⇄ 1080-px sizes, nearest-match colors both directions, `stroke` ⇔ `outline>0`, caps/bar defaults ride null-local, emoji-only+stroke-free+no-caps → STICKER, windows + fx carried; local-only scale/rot/shadow/sticker ride overlay passthrough so native wire round-trips are lossless and web parsers still accept the doc); local `MemeOverlay` gained `caps/bar/startMs/endMs/fx` (optional, wire-faithful) and text cap 200→300; bridge `memeWireNormalize/memeWireToLocal/localToMemeWire` (+ iOS client seams and `testMemeWireDocumentSeams`); fixture `contracts/meme/wire-document-v1.json` inlined in `MemeWireCodecTest` (web `schema.test.ts` ported branch-for-branch: defaults, junk-row drops, clamps, truthiness, window clears, count caps, [start,end) visibility, cue clamp/drop, conversions, round-trips). MEM-001 gate open: native can now import/export web payloads ahead of MST-017 publish.
- [x] M1 MST-016 (wave 3, export — DONE 2026-09-02) — raster export → device save on BOTH platforms. SHARED `MemeExportRules`: `outputSize` = web `render.ts targetSize` port (long edge 1080, never upscales, evened dims ≥2, degenerate → 1080×1920) + `exportPlan` = pure target-px draw commands (the `caps` uppercase transform — web `displayText`, min-10-px font floor — web `max(10, size × referenceHeight)`, outline at ×2 stroke scale, local `scale` honored so exports match the edited stage, multiline split) — 5 common golden tests pin exact px. Bridge `memeExportPlan(projectJson, sourceWidth, sourceHeight)` returns the envelope `{"width","height","items":[…]}` so the size math stays single-sourced (+ iOS client seam + `testMemeExportEnvelopeSeam`). ANDROID `ui/create/meme/MemeRaster.kt`: sampled decode → android.graphics off-screen render (font-slot typefaces incl. sans-serif-black, stroke-behind-fill outline, setShadowLayer shadow, per-line centered rotation) → MediaStore PNG at `Pictures/BitOS` (IS_PENDING round-trip, failed writes clean up the row; minSdk 29 = modern API only). IOS `MemeRaster` in MemeEditorView.swift: UIGraphicsImageRenderer(scale 1) over the bridge envelope (NSAttributedString negative-stroke outline + NSShadow) → `PHPhotoLibrary` add-only save (permission already in Info.plist), render on a detached task. Both editors gained a Save tool with busy/status states. WYSIWYG FIX: the font reference is canvas HEIGHT (web truth found in `paintOverlay` — supersedes the plan's earlier "×1080-wide" wording per the doc's own web-wins rule); stage previews re-based to height/1080 on both platforms. Honest gate: bitmap golden-HASH tests need a device rasterizer (no Robolectric/instrumented infra in this repo) — plan geometry is golden-tested in common; pixel hashing rides manual QA.
- [x] M1 MST-017 (wave 4, publish — DONE 2026-09-02) — picture memes publish as kind 20 end-to-end on BOTH platforms. SHARED: `NoteComposer.composeMemePictureNote` — web `feed.postBitz` picture-path parity: caption hashtag t-tags → NIP-31 `alt` (explicit alt wins, else caption's first 200 chars; pushed only when non-empty) → `imeta` (url m x size dim — the §3.4 superset; `x` = the verified Blossom hash) → NIP-36 `content-warning` LAST when sensitive; caption hard-caps at 1000 (meme wire parity; the kind-1 Note destination is a different path). BRIDGE: `composeMemePictureEventId` + `memePicturePublishMessage` (the kind-22 pair's pattern) + bridge test. ANDROID: `NotePublisher.publishMemePictureNote` through the existing receipt machine; `MediaPublishViewModel.publishMemePicture` (render → hash-verified Blossom upload → kind-20, own `MemePublishPhase` lane so the import path is untouched); editor gains a Post button + publish sheet (caption + soft-300 counter, derived hashtag chips, CW toggle+reason, alt field, phases, verify-before-sign footnote). IOS: `NotePublisher.publishMemePictureNote` (sign-locally → `memePicturePublishMessage` → send); editor Post + `MemePublishSheet` (same fields/phases). TESTS: `BlossomTest.composesKind20PictureMemeWithWebTagOrder` (exact tag list, alt fallback/truncation, CW last, 1000 cap, hostile pubkey), bridge pair test, Android repo `publishesKind20PictureMemeWithWebTagOrder` (signed frame decodes as kind 20 with the web tag order + imeta + relay-OK receipt — nothing signs before the hash-verified upload by uploader contract, BlossomUploaderTest). Fixture `contracts/nostr/kind-20-unsigned.json`. PoW on memes intentionally deferred to the composer PoW lane.
- [x] M1 MST-018 (wave 5, durability — DONE 2026-09-02) — **M1 COMPLETE**. Autosave + ≤6 continuation slots on BOTH platforms: SHARED `MemeSlots` — `MemeSlotCodec` (slot file = `{v,id,updatedAt,assets[{id,file,aspect}],project<wire>}`; index file most-recent-first; lenient decode: corrupt/oversized → empty, absolute/traversal asset+poster paths rejected) + `MemeSlotRules` (front-insert, id-bump, LRU eviction returning evicted ids, ≤6 label ≤40 from first overlay text, relative time) + 5 common tests. ANDROID `ui/create/meme/MemeProjectStore` — `filesDir/studio/slots/<id>/` with slot.json + copied-in assets (CAP-005: transient picker URIs never persist; copy idempotent) + poster.jpg (~256 px JPEG ≤192 KB); bitmap/resolver edges injected so the store round-trips on plain JVM (5 store tests: relaunch-restore, idempotent copy, 7-slots-evict-oldest, delete, corrupt-degrade). Editor: debounced 500 ms autosave after every committed edit/asset change; resume seeds project + tray from the slot files; publish DONE and discard-confirm delete the slot; hub "Continue creating" rows (poster, label, time, Resume, ✕). Also FIXED a wave-1 latent bug: `MemeEditorState` was plain Kotlin so drags/adds never recomposed the stage — it now carries a Compose `revision` bumped at every mutation. IOS `MemeProjectStore` (Application Support/studio, same layout + semantics, registered in pbxproj) + the same editor lifecycle (`.task(id:)` debounce keyed on the store revision, onAppear resume seeding, publish/discard clear) + `SlotRow` hub section. Honest gate: process-kill recovery is contract-tested at the store level (relaunch-restore battery); on-device kill/relaunch QA rides the manual matrix.
- [ ] M2 MST-020..023 — GIF mode: frame tray/reorder, loop preview, encoder + size ladder, kind-20 publish
- [x] M2 MST-020..023 (COMPLETE 2026-09-02 — wave M2a shared engine + Android, wave M2b iOS) — SHARED pure GIF engine, common-tested: `GifEncoder` (web `gif-encode.ts` port — LZW cadence, median-cut 5-bit histogram, local tables, NETSCAPE loop, 2cs floor; deterministic bytes), `GifDecoder` (web fallback parser port — disposal 2/3, interlace, transparency, sub-2cs→100ms; junk→null), `GifExportPlan` (cadence/20ms-floor/≤360/pin-trims + 8MB→halve ladder) — 16 tests incl. the encoder⇄decoder round-trip golden. ANDROID: GIF mode live (chip switch w/ confirm, frame tray ≤60 with long-press-drag reorder + delay slider, looping stage preview, Save `.gif` w/ ladder status, publish kind-20 `m image/gif` via the same verify-before-sign machine, frames persist in slots + resume). Wave M2b: iOS GIF mode live (CGImageSource decode w/ cumulative composite — disposal-2 ghosting accepted V1 caveat; CGImageDestination encode; shared planner via bridge `memeGifPlan`/`memeGifLadderCanvas`; chip/tray onDrag-onDrop reorder/delay slider/loop preview/Save/Publish `m image/gif`; frames persist in slots). Fixtures `kind-20-gif-unsigned.json`. REMAINS: on-device QA.
- [◐] M3 (wave M3a — shared rules + Android video DONE 2026-09-02): `composeMemeVideoNote` (kind 22 portrait / 21 landscape, duration imeta; tests + bridge pair); Android: video chip + trim handoff through VideoPreviewScreen + ExoPlayer stage w/ overlays + scrub + Transformer burn-in export (full-frame overlay bitmap, OverlayEffect) + Save-to-Movies + kind-21/22 publish + slot persist/resume. Wave M3b: iOS video mode live — AVAsset probe (rotation-aware), AVComposition+CoreAnimationTool burn-in (full-frame shared-envelope layer), AVKit stage w/ overlays+scrub, trim handoff through VideoPreviewScreen, Save-to-Photos video, kind-21/22 publish via the M3a bridge pair, slot persist/resume (raw data asset). Wave M3c: MST-032 cover capture DONE — UploadedMedia.thumbUrl (imeta `thumb`, validated; tests + kind-22 fixture), capture-at-playhead + separate upload + "Set cover/Cover ✓" chip both platforms, publish carries it; cover is session-only in V1. MST-035 QA = MANUAL PENDING (checklist: 60 s bound, call interruption, background, low storage, thermal, kill mid-export).
- [ ] (old line kept) M3 details: clip/record handoff + trim reuse, overlay burn-in, cover-frame capture, kind-22/21 publish by orientation, QA matrix (CAP-009 reuse)
- [◐] M4 MST-040..049 — Studio home (resume slots, templates), sound seed + synth SFX pack (APP-021), looks/fx, shared templates + marketplace (MEM-004), remix payload (`com.bitos.bitz.meme` v1), shared sounds, batch queue, opt-in AI suggestions
  - [x] Video cut-policy revision (2026-09-02): long clips are CUT with an "over the size limit — trimmed to Ns" message instead of rejected — shared `MemeVideoCutRules` (60 s pick cap + proportional export-size ladder, 5 s floor, ≤3 retries; `MemeVideoCutTest`), trim-honoring exports (Media3 clipping / AVComposition range), publish ladders, cut-accurate kind-22 duration imeta, Android fresh-pick probe fix.
  - [◐] MST-045 shared-template relay fetch LIVE ON BOTH PLATFORMS (2026-09-03): Android `SharedTemplateStore` + hub "Shared templates" rail (⚡sats chips) + seeded editor; iOS `SharedTemplateStore` (@MainActor/@Observable; verified kind-30078 frames → bridge summary, newest-wins ≤24) + CreateView rail + MemeEditorView shared seed via `memeApplySharedTemplate`; new file registered in project.pbxproj. Remaining: publish-your-own write path, zap unlock ledger (MST-046), icon-allowlist sync with web, iOS type-check on a simulator runtime.
  - [◐] MST-045 shared-template RELAY FETCH LIVE ON ANDROID (2026-09-03): `SharedTemplateStore` (kind-30078 REQ limit 200 → signature-verified events → contract summary via the bridge seam; newest-wins per template id, ≤24 rail rows, hostile shapes drop) + hub "Shared templates" section (⚡sats chip on priced cards) → editor seeds through `memeApplySharedTemplate`; iOS client seams landed (fetch/rail next). Remaining: iOS fetch+rail, publish-your-own write path, zap unlock ledger (MST-046), icon-allowlist sync with web.
  - [◐] MST-045 shared-template READ PATH (2026-09-02, wave 3 start): shared `MemeTemplateContract` (kind-30078 `com.bitos.bitz:template:*` parse — v2 fields + v1 degrade-to-free, price tiers {21,100,500}/junk→free, 10 categories, 8-icon allowlist, wire-overlay → local conversion with hostile tolerance incl. converter-crash guards; `MemeTemplateContractTest` battery) + bridge seams `memeSharedTemplateSummary`/`memeApplySharedTemplate` (apply = fresh-id clone). Remaining: relay fetch feeding the hub rail, publish-your-own (write path), zap unlock ledger (MST-046).
  - [◐] MST-042 remix publish wiring (2026-09-02): "Remix of" + author fields + `source → you` chips in both publish sheets; tags via the tested `memeRemixTagsFor` seam (Android both lanes; Kotlin picture composer seams + iOS publisher carry `extraTagsJson`/`remixTagsJson`; bridge test pins the id change). Remaining V1.x: RemixChainDialog from the feed rail, Remix target-picker entry.
  - [x] MST-040 templates rail (2026-09-02, wave 1 complete): shared `MemeTemplates` (6-template built-in pack, apply = fresh-id overlay clone keeping assets/grade; test) + bridge seams `memeTemplates`/`memeApplyTemplate`; horizontal Templates rail on both Create hubs → editor opens seeded. Marketplace/shared kind-30078 cards stay wave 3.
  - [◐] MST-044 fx core (2026-09-02): shared `MemeFxRules` (web fx.ts math: pop/fade entries, shake/spin loops, [start,end) windows; `MemeFxRulesTest`) + `UpdateOverlay` fx/window fields through command+codec+coalescing; both video stages apply transforms + hide out-of-window overlays at the playhead. Both text sheets ship motion chips + Start/End window fields (2026-09-02; iOS via `MemeOverlayUi` fx/window fields — WAVE 2 complete at committed scope). Remaining (V1.x): export burn-in (animated layers), GIF-stage time, iOS `MemeFxBridge` → bridge seam.
  - [◐] MST-041 SFX (2026-09-02): shared `SfxSynth` (31 recipes + WebAudio-envelope PCM renderer + WAV writer + `MemeSfxCue` wire/commands + `renderCueTrack` mixer; `SfxSynthTest`) · Android 🔊 sheet (AudioTrack preview, playhead cues, scrub markers) + cue burn-in via a second Media3 sequence · iOS 🔊 sheet (AVAudioPlayer previews via base64 WAV seam, playhead cues, scrub markers). Remaining: iOS export audio track, sound-on-static image→video, APP-021 seed.
  - [x] Mockup UX-parity pass (2026-09-02, app-15 scr-quick + app-04 scr-review): selection corner dots + stage "drag · scale · rotate" chip both platforms; publish-sheet preflight checklist (kind label, alt, CW) both platforms. iOS visual check pending simulator runtime.
  - [x] Artboard delete + manipulation-icon fixes (2026-09-05, Android, user-reported): (1) on-canvas overlay DELETE was broken by TWO coordinate bugs — the stage gesture layer was a SIBLING filling the letterboxed container instead of living inside the fitted stage box, so tap positions scaled/offset against the overlay math (worst once canvas ratios letterbox), and the drawn delete chip's 24 dp circle was placed with its TOP-LEFT on the tap-tested center (a 12 dp visual/target skew); the gesture layer now sits inside the fitted box in all three stage branches (image/GIF/`VideoStage`) and the chip centers on its hit point — the Layers-sheet delete worked because it never touches stage coordinates; (2) the MUX-03 selection row's icons didn't say what the buttons do — Nudge-left was a BACK arrow, Nudge-down a DOWNLOAD icon, Shrink a TUNE/filter icon and Enlarge a bare plus; new `AppIcons` entries (NudgeLeft/Right/Up/Down chevron+keyboard arrows, ZoomIn/ZoomOut magnifiers — Solar alt-arrow/magnifer semantics) map icon-to-function. iOS already matched (arrows for nudge, magnifyingglass ± for zoom, delete handle centered on the fitted stage).
  - [x] Editor feature wave — action rail, image stack, canvas, sound templates, form polish (2026-09-05, BOTH platforms + shared, user-directed with reference screenshot): (1) ACTION RAIL → REVERTED same day on the user's direction ("no menu action keep back to old Horizontal"): the vertical right-edge rail from the reference screenshot was tried and rolled back to the original HORIZONTAL quick-tools row below the canvas — that layout is the standing design; (2) IMAGE STACK — in IMAGE mode the first pick stays the background, every later pick lands as a draggable IMAGE LAYER overlay (the same binding video-mode inserts use; "Layer added" notice); also fixed the inline video-mode image button to launch the LAYER picker (it was launching the asset picker); (3) CANVAS (new) — shared `MemeCanvas` rules (ratio presets Source/1:1/4:5/9:16/16:9, `#rrggbb` background, letterbox fit) + an additive `canvas` wire key (old wires byte-identical; junk drops on read) + `memeSetCanvas` bridge seam; a Canvas sheet (per-mode bar, image/GIF — video keeps its source frame in V1 and says so) sets both (undoable; Android rides the direct-edit stack); the STAGE letterboxes onto the ratio with the background fill and both PNG rasters re-frame the export the same way (same long-edge budget, media centered, plan re-mapped to the canvas size); iOS picker = native ColorPicker + palette swatches, Android = palette + live `#rrggbb` field; (4) SOUND (web parity from bitos-nostr-web) — shared `SfxSynth.LABELS` (human labels, single source) + `SfxTemplates` (7 staged cue layouts: laugh track, dramatic hit, cash register, awkward, game over, suspense, fast transition — additive, capped at 16, tail-truncated never dropping existing cues) + the catalog seam extended to `{buckets, labels, templates}`; both SFX sheets show LABELS, a search field (query flattens the buckets), and a TEMPLATES row that stages the whole set at the playhead; (5) PUBLISH FORM — caption and remix styling settled over two user-directed passes: the caption input is FLAT (no border, no card padding — the interim card styling was reverted per feedback) and the remix inputs run one size smaller (labelSmall/caption2 with a caption-size label); the settings rows gained consistent vertical padding (the video-only "Cover image" row matched to its siblings). Tests: `MemeCanvasAndSfxTemplatesTest` (wire round-trip, junk-drop + byte-identical old wires, ratio letterbox math, template staging cap/truncation, every template cue resolves + labels cover all recipes).
  - [x] Undoable timeline clip edits (2026-09-05, BOTH platforms): closes the scope gap flagged in the timeline audit — split/delete/move/mute/volume/trim/per-clip look (and picker-added clips) now step back through the SAME undo affordance as overlay/style edits, in true chronological order. Android: `MemeEditorState` gains a clip-edit stack interleaved with the command stack by a global action `seq` (`beginClipsEdit()` called after each op's guards pass and before mutation — refused edits never push); undo pops the NEWEST action of either kind; redo replays ASCENDING (the top with the smaller seq — the first implementation had this inverted and a traced diagnostic caught it); the screen keeps a bounded id→(bytes, probe) archive and re-hydrates `videoClips` from the restored wire (`reconcileClipsFromWire`, full-rebuild-only with the sticky fallback) via `undoEdit`/`redoEdit` wrappers on all three undo/redo buttons. iOS: every clip op pushes the pre-op wire into the existing history; undo/redo restore the wire then rebuild the session clips from its `clips` rows through a bounded `clipArchive` (missing source → sticky `syncWireClips` fallback, never a silent drop); camera seeding passes `undoable: false` on both platforms. Tests: `clipEditsAreUndoableAndInterleaveWithCommands` (three-step interleaved undo, full redo walk, new-edit-clears-redo) alongside the existing `undoDoesNotResurrectAnOlderClipList`.
  - [x] Timeline track functional audit + undo-desync fix (2026-09-05, BOTH platforms, user-requested check): verified the full video-timeline feature set end-to-end on both platforms — strip (proportional segments, per-segment select, mute badges, output durations, playhead, speed note), tools (Split-at-playhead with 200 ms media-side guards / Delete with keep-one guard on the strip / Mute toggle with live per-clip preview / Speed sheet 0.5–2× via the shared clamp / Layers sheet), Clips sheet (select, move, remove, add), Trim + Volume (0–2×) sheets, stage scrub row (play-pause, timeline-time seek, SFX cue markers, set-cover-at-playhead), timeline math (output = window ÷ rate, timeline→media = Δ × rate — consistent store ↔ player ↔ export on both platforms), and persistence (every clip op syncs the wire; resume join regression-tested earlier). ONE real defect found and fixed: undo restored full wire/snapshot state including an OLDER `clips` list (iOS history wires; Android `UndoEntry.projectBefore` snapshots), so undoing across a clip edit left the strip showing splits the wire no longer had — and the next autosave would persist the stale list, silently dropping them (same family as the resume loss). Clip state is now STICKY through undo/redo: iOS re-syncs the session clips after each history swap (`syncWireClips()` in undo/redo); Android preserves the live clips+trim across snapshot restores (`MemeEditorState.undo/redo`). Regression: `undoDoesNotResurrectAnOlderClipList`. Known scope notes: clip edits (split/delete/move/mute) are intentionally NOT undoable in V1 (undo covers overlays/style/look/speed); tapping a strip segment selects (seeking stays on the scrub row); iOS strip ClipTool icons are raw SF symbols, not the AppIcons set.
  - [x] Video stage playback fix + single add affordance (2026-09-05, BOTH platforms, user-reported "cannot play vdo"): (1) Android BLACK-VIDEO root cause — the stage ExoPlayer runs `setVideoEffects` (color grade), whose GL-processed frames cannot render to the default `surface_view`; PlayerView is now inflated from `res/layout/video_stage_player_view.xml` with `app:surface_type="video_decoder_gl_surface_view"` (not constructible programmatically), so graded frames render; the trim screen (no effects) keeps its default surface; (2) wiring bug from the layout pass — Android's inline "Add image layer" button called `launchPicker()`, which in video mode launches the VIDEO picker (a duplicate of Add clip); the inline image button now always means images (frames in GIF via `gifPicker`, background in IMAGE and layers in VIDEO via the `picker` ImageOnly launcher — `loadPicked`/`layerPicker` semantics unchanged); (3) source-add is now a SINGLE affordance per the user's direction: the dashed "+" tiles left the asset tray (image/video layer sources) and the GIF frame tray on both platforms — adding happens only in the mode row beside undo; trays select/reorder what exists.
  - [x] Video resume data-loss audit + fix (2026-09-05, BOTH platforms, user-reported "split video gone after resume"): audited the whole split → autosave → relaunch → resume chain. The persistence layers are sound (new JVM regression `MemeProjectStoreVideoResumeTest`: a split timeline survives save → relaunch → the screen's wire-rows-to-asset-files join, and re-saves keep both halves); the REAL bug was a destructive overwrite loop at rehydration: any clip the resume could not bring back (cold-start file/probe failure — the "first open" case) was dropped SILENTLY, and the next autosave rewrote the slot's asset list from the partial session, erasing the dropped clips' asset rows — after that the timeline was unrecoverable ("auto away" forever). Fixes: (1) rehydration now counts every dropped wire clip (missing file / unreadable bytes / null probe / window collapsed to ≤0 after the probe clamp — itself a new guard against zero-length segments) and shows "N timeline clip(s) could not be restored — the last saved draft is kept; reopen it to retry"; (2) while a video session is incompletely restored, autosave stands down (iOS `saveDraftNow` guard + `.failed` state; Android autosave `resumeDroppedClips` guard + `DraftSaveState.FAILED`) so the last good slot is never overwritten by a partial one — reopen retries the rehydration against the intact slot; (3) the header Next is disabled in that state (posting an irreversible partial video is worse than waiting).
  - [x] Video resume duplication fix (2026-09-05, ANDROID, user-reported "resume auto-splits the video"): the audit above covered the data-loss half; the VISIBLE "auto split" symptom was clip DUPLICATION. `CreateScreen` loaded the resume handle inline in composition (`resume = resumeSlotId?.let { slotStore.loadSlot(it) }`), so every CreateScreen recomposition handed `MemeEditorScreen` a FRESH `SavedSlot`; the editor's own first autosave guarantees one (saves with a new `updatedAtMs`, then bumps `slotsRevision` via `onSlotsChanged`), `SavedSlot` structural equality breaks on that field, so the screen's `remember(resume)` seed block re-ran and appended every wire clip onto the still-populated unkeyed `videoClips` list — the timeline showed each segment twice (a single-clip draft looked auto-split moments after opening), the next `syncWireClips` persisted the duplicate into the wire, and each edit cycle doubled it again (1→2→4→8); GIF drafts doubled their frame tray the same way, and every re-run also rebuilt `MemeEditorState` (undo history + selection dropped mid-session). Fix: (1) the slot now loads once per editor session — `CreateScreen` holds `val resumeSlot = remember(resumeSlotId) { … loadSlot(it) }` and passes that (also removing main-thread disk I/O on every recomposition); (2) defense in depth: both seed branches are idempotent (video clips seed only `if (videoClips.isEmpty())`, GIF frames only `if (gifFrames.isEmpty())`; the IMAGE-layer tray still re-seeds because the `assets` list is itself rebuilt by the block), so a block re-run can never duplicate again. iOS unaffected (`restoreClips` REPLACES the clip list and `seedEditorSession` runs from `onAppear` behind empty-tray guards). Composition-level semantics are not JVM-testable in the current harness — the store-level split-resume regression in `MemeProjectStoreVideoResumeTest` stays the persistence gate.
  - [x] Editor layout pass (2026-09-05, BOTH platforms, user-directed from live screenshots): (1) the "Next" publish entry moved from a bottom CTA into the header inline with draft save (iOS `headerNextButton` compact bordered-prominent; Android header `TextButton`) — the bottom keeps only a slim notice while no source exists or a publish is running, so the bottom stack is tool-only; (2) explicit source-add controls now sit in the mode-pills row beside undo using the Solar set (iOS `AppIcons.photo`/`.video` = SolarGallery/SolarVideoCamera assets; Android `SolarStudioIcon.Gallery`/`.VideoCamera`): video mode gets Add clip + Add image layer, GIF gets Add frames, IMAGE gets Add image — the pick affordance is no longer buried in the tray; (3) bottom-sheet content de-duplication and inset normalization — every tool-panel sheet shows exactly ONE title (the sheet header: iOS `editorPanel`, Android panel-sheet Column) and ONE content inset, so `MemePanelContent`/`TextPanelContent`/`StickerPanelContent`/`SoundPanelContent`/`FxPanelContent` (iOS) and `MemeCaptionSheetContent`/`TextPanelContent`/`StickerSheetContent`/`LooksSheetContent` (Android) dropped their internal titles and outer padding (Android's panel bodies were double-inset: sheet Column base padding + own base padding); the standalone Looks sheet gained its own padded "Color look" header wrapper; iOS panel sheets use horizontal md + top sm + bottom lg.
  - [x] MUX-10 runbook (2026-09-05): `docs/native/mux-10-device-validation.md` — the audit's validation gates as an executable checklist: preconditions (device matrix, signed-out runs, network control), 12 functional/recovery scenarios mapped to the shipped MUX slices (zero-data-loss rows A1/A2/A7 + hit targets B4 marked release blockers), 5 accessibility checks (VoiceOver/TalkBack walkthrough, gesture-free manipulation, 200% text, 48dp targets, keyboard coexistence), the 6 usability spot-tasks with the audit's proposed targets, and results conventions. The device passes themselves are the user's step.
  - [x] MUX-09 from `meme-editor-ux-ui-audit-and-plan.md` (2026-09-05, BOTH platforms): bulk local export closes the offline path (UX-06). Review mode gains export SELECTION — per-tile checks distinct from publish approval, "Select all ready" (blocked-excluded count shown), "Export selected (N)" needing NO identity: each chosen valid variant renders from its resolved project through the same raster previews use and saves to the photo library (iOS `MassBatchFlow.exportSelected` → `MemeRaster.saveToPhotos`; Android `MassBatchUi.exportSelected` → `MemeRaster.savePng` with the recipe's sanitized `resolveVariant().name`). Per-row results: saved rows are skipped on re-run (never duplicated), failures stay visible, "Retry failed (K)" re-runs only failures, finish line "N saved ✓ / N saved · M need attention". MUX-10 (device validation) documented as requiring physical-device passes on the user's side.
  - [x] MUX-08 from `meme-editor-ux-ui-audit-and-plan.md` (2026-09-05, BOTH platforms): review-mode trust. Tile taps open a full variant preview instead of toggling approval — aspect-fit poster (fill/crop no longer hides composition), queued/blocked states explained, per-row validation notes listed; Prev/Next navigation, an in-preview Approve/Unapprove control and "Edit this version" jump into Setup (iOS `MassVariantPreviewView` sheet + `previewingRowId`; Android dialog on `MassBatchUi.previewingRowId`). Approval is now an EXPLICIT per-tile check button (32–48 dp, blocked rows disabled) separate from inspection. "Approve all valid" gained the bounded confirmation (UX-09): count, warnings-included, blocked-excluded and the invalidation reminder before any approval op runs.
  - [x] MUX-07 from `meme-editor-ux-ui-audit-and-plan.md` (2026-09-05, BOTH platforms + shared): CSV preview + limit reconciliation. Shared `MassBatchRules.csvPreview` (dry-run: headers, auto-mapping by slot id/name, missing-required, unknown columns, row count vs cap, ≤3 sample rows) + bridge seam `massBatchCsvPreview`; `MAX_NEW_ROWS = 100` operational cap — imports over it REFUSE with the split-CSV instruction (the old path silently truncated at 200) while `MassBatchCodec` decode keeps 200 for existing documents. Native: the CSV pickers now analyze first and show a confirmation dialog (iOS `confirmationDialog` via the new client seam; Android `AlertDialog` calling the shared object directly) — wrong headers, missing fields and excess rows get actionable feedback before the batch changes. Test: `csvPreviewMapsColumnsAndCapsRowsBeforeImport` (mapping, over-cap refusal, quoted commas, Unicode, 200-row decode compatibility).
  - [x] MUX-06 from `meme-editor-ux-ui-audit-and-plan.md` (2026-09-05, BOTH platforms + shared): "Make variations from this design". Shared `MassBatch.designRecipe(project)` freezes an editor image design — every non-blank TEXT overlay becomes a `{t:<overlayId>}` placeholder carrying a LONG_TEXT slot named after the original caption (blank row value = caption deliberately dropped; existing substitution engine does the work) — and bridge seam `massBatchFromDesign(projectJson, batchId, name, nowMs)` returns a ready document or an honest error (non-image, caption-less, corrupt wire). The editor's Export sheet gains the entry (image + ≥1 caption only): renders the design poster, hands (wire, poster) to the Create hub, which opens the batch setup seeded with the design. iOS: client seam (framework+fixture), `MassBatchFiles.createFromDesign`, `MassBatchFlow.createFromDesign`, editor callback; Android: `MassBatchFiles.createFromDesign` (direct shared-object call), `MassBatchUi.createFromDesign`, editor param wired at the hub. Test: `massBatchFromDesignFreezesAnEditorDesign`.
  - [x] MUX-05 from `meme-editor-ux-ui-audit-and-plan.md` (2026-09-05, BOTH platforms): durable export jobs — persist-then-effect around every export path (PNG/GIF/MP4): the rendered artifact lands in a bounded job ledger (≤10, one media file per job) BEFORE the destination save; finish deletes the artifact, failure keeps it and the Export sheet's "Recovered exports" section offers Retry save (loads the persisted bytes, saves to the destination only — no re-render) and Discard. Crash-mid-save reconciliation is honest: a job found in `saving` on relaunch becomes needsReview with "check Photos/Movies first — the save may have finished", because add-only photo-library saves cannot be queried back (the audit's "tested reconciliation strategy, not an exactly-once claim"). iOS `MemeExportJobStore` + store `retryExportSave`/`discardExportJob`; Android `MemeExportJobStore` (thread-safe — called from the IO render blocks) + `retryExportSave`.
  - [x] MUX-04 from `meme-editor-ux-ui-audit-and-plan.md` (2026-09-05, BOTH platforms): output-settings sheet before export (iOS `ExportSettingsSheet`, Android `ExportSettingsContent`, both bottom sheets). The Export chip opens settings instead of firing the save path: format facts derived from the same rules the exporters run (PNG dims from the asset, GIF frames+timing, MP4 dims+duration), named destination (Photos/Movies), up-front disclosure of the automatic adjustment ladders (GIF downscale, video 64 MB trim) and the honest "size shown after the render" copy (no invented estimates). Single tested profile per mode — no unsupported conversions offered; master unchanged; last failure shown in-sheet with retry via the MUX-02 typed outcome.
  - [x] MUX-03 from `meme-editor-ux-ui-audit-and-plan.md` (2026-09-05, BOTH platforms): accessible overlay manipulation + vocabulary. Selecting any overlay now reveals a contextual control row — nudge left/right/up/down (±0.05 normalized), shrink/enlarge (×0.9/×1.1), rotate −15°/+15°, Edit text (text overlays) and Delete — as the explicit no-gesture alternative the audit asked for (iOS `selectionControls` via `updateStyle` geometry keys with burst coalescing; Android `SelectionControlsRow` + new `MemeEditorState.nudgeOverlay` UpdateOverlay wrapper; 40–48 dp targets, per-control a11y labels). Vocabulary: basic-bar Suite slot renamed Timeline (UX-12), Effects chip/panel renamed Look; iOS status line wraps instead of fixed-height clipping (UX-13). The import → caption → export path remains entirely in the basic editor.
  - [x] MUX-01/02 from `meme-editor-ux-ui-audit-and-plan.md` (2026-09-05, BOTH platforms): draft trust + typed export results. Close now PERSISTS first — leaving with work keeps the draft and returns to the hub; a failed save opens "Could not save the draft" with Retry save / Keep editing and Delete draft as a separate deliberate destructive action (no more ambiguous Discard/Keep dialog). The draft icon shows acknowledged Saving/Saved/Failed — iOS `saveDraftNow()` awaits the slot write before claiming saved (the old detached write + instant "Draft saved" is gone); Android's debounced autosave wraps `slotStore.save` in a runCatching acknowledgement feeding the same icon states. Export results are typed: iOS gains `MemeExportState.savedAdjusted` so a downscaled GIF reads as SUCCESS ("Saved at a smaller size…", green, never error-styled); Android gains `ExportOutcome` (Success/SuccessAdjusted/Failure) with `ExportFullScreen` switching on the type instead of `status.startsWith("Saved")` copy inference. Quick tool relabeled Save → Export (draft vs export vs post vocabulary, UX-05). Remaining MUX-03…10 tracked in the audit doc.
  - [x] Prototype `#/queue` recovery queue (2026-09-05, BOTH platforms): publish attempts are now DURABLE jobs — a bounded ledger (≤20 records, JSON + one media file per job, deleted on done/discard) persists every attempt's inputs + stage at the REAL pipeline checkpoints, so a crash mid-publish never loses the run. The Recovery queue screen (reached from the publishing machine's failure and success blocks) lists non-terminal jobs with the job chip, stage progress, stuck-at line + last error, and three real actions: **Verify integrity** (SHA-256 of the stored bytes vs the recorded digest — missing media says so), **Retry now** (re-runs upload→sign→relay from the STORED media — render skipped, upload idempotent by content hash; disabled once an event id exists, because that note may already be live — verify instead), and **Discard** (confirmed; deletes record + media, Blossom copy stays until GC). iOS: `MemePublishJobStore` + store `resumePublish`; Android: `MemePublishJobStore` + VM `retryMemeJob`/`discardMemeJob`/`verifyMemeJobIntegrity` wired through the flow screen.
  - [x] Tool-panel bottom-sheet + usability pass (2026-09-05, BOTH platforms, user-directed): the editor's tool panels (Meme · Text · Stickers · Sound · Effects) now present as NATIVE bottom sheets instead of inline cards — iOS `.sheet(item:)` with [.medium,.large] detents + drag indicator + Done header; Android `ModalBottomSheet` with a title row. Canvas stays visible above; contents unchanged from the prototype panels. Mass-production UX: batch rows are whole-row tap-to-open with swipe/confirmed delete (was an instant-destructive ✕), empty states on both pickers, recipe fields commit on Done/Generate (Android previously forked the recipe version on EVERY keystroke once rows existed — real bug), placeholder examples (`meme-{i}.png`, `gm {name} — {sats} sats`) + plain-language placeholder help. Friendliness: "Next · post details" explains itself when disabled ("pick a clip, image or frames first").
  - [x] Prototype `#/publishing` machine pass (2026-09-05, BOTH platforms): the publish flow gained the prototype's publish machine — Preflight's "Sign & publish" now advances to a full stepper screen (render → hash → upload → verify → build → sign → relay → confirm) with a progress bar (done stages / 8), per-attempt `job NNNN` chip, factual stage details (mode-specific render format, actual accepted relay hosts) and terminal states: failure marks the stalled stage with Retry now / Later (idempotent re-run; nothing signs before hash-verify), success shows the event id + confirmed relay count + "View on Bitz". Stages are REAL checkpoints, not timers: both Blossom uploaders grew `onStage` (hashing/PUT/verify), both note publishers grew `onStage` (BUILT event-id / SIGNED / RELAYED) on the meme paths, the iOS store tracks `publishStep`/`publishJobId`/`publishEventId`, and Android's `MemePublishUiState` grew `stage`/`jobId`/`eventId`/`confirmedRelayHosts`/`terminal` (a VM watcher resolves the receipt machine's terminal result; the editor marks RENDER as it encodes). All new callback params default to no-op — existing callers byte-identical.
  - [x] Prototype FULL create-flow parity pass (2026-09-05, BOTH platforms): the native editor now matches `#/create-edit` and publish runs the `#/create-details` → `#/create-review` steps. Editor chrome: "Editor" header (✕ + title + draft-save) replacing mode chips/Post; VIDEO/GIF/IMAGE pill switcher + undo below the canvas; quick-tool chips (Meme hot · Text · Stickers · Sound · Effects + Draw/Save extras) opening INLINE panels under the chips (meme TOP/BOTTOM, quick text, sticker grid, SFX summary, looks+adjust — sheets retired from the quick path); compact clip timeline in video mode (proportional segments, selection, playhead, mute marks, whole-timeline speed note) with the prototype clip tools Split/Delete/Mute/Speed/Layer wired to the REAL store ops (split at playhead, remove, volume 0↔1, speed sheet, move-forward); per-mode bottom bar (video Canvas/Adjust/Filter/Overlay→layers/Suite dock · gif Speed=frame-hold cycle/Loop/Crop/Filter/Text · image Ratio/Crop/Filter/Adjust/Background) with honest wave-2 notices on the prototype-mocked slots; primary "Next · post details" replaces the header Post. New publish flow (iOS `MemePostFlowView`, Android `MemePostFlowScreen`): Post details (preview thumb + caption + counter, ≤8 explicit t-tags pills, cover row (video), audience cycle, zap/remix toggles, CW + reason, remix source fields, alt text, license CC0/CC-BY/Nostr-only chips with the shared `license` tag vocabulary) → Preflight (real checklist: media/caption+tags/alt/CW/license/audience/zaps/relays/signer + phase stepper) → existing render → hash-verified upload → sign machine; on success Done returns to the feed. Tag plumbing: explicit t-tags + `license` ride as extra tags on ALL modes (shared `composeMemeVideoEventId`/`memeVideoPublishMessage` grew an additive `extraTagsJson` param — default empty keeps old event ids byte-identical; Android video path already had `extraTags`; iOS picture path merges lineage + extras into one `remixTagsJson`). Splits/PoW/schedule stay documented wave-4 slots in-UI. Tests: `BusinessCoreBridgeTest.memeVideoSeamAcceptsExtraTagsForTagsAndLicense`; Android `testDebugUnitTest` + iOS swift-6 strict type-check green.
  - [x] Post-details defaults tightened (2026-09-06, BOTH platforms): Zap settings now defaults OFF per product call (new posts stamp the advisory `bitz:zaps` off marker until the author flips the switch), and Content warning is a toggle-only row — the free-text "Warning reason" input was removed; the fixed "Sensitive content" default rides the existing NIP-36 cover plumbing (publish + preflight unchanged). Gates: Android 116 tests + iOS swift-6 strict green (one unrelated FeedRepositoryTest timing flake re-ran clean).
  - [x] Zap settings switch — real end-to-end (2026-09-05, BOTH platforms): the post-details zap row is now a Switch (was informational text). OFF stamps the new shared advisory `["bitz:zaps", "off"]` marker (`feed/ZapPolicy` + `FeedNote.zapsDisabled` projection, 3 common tests), publishes ride it through the existing extra-tags plumbing (iOS `MemePostDraft.extraTags`, Android `postExtraTagsJson(+allowZaps)`), and BOTH feed cards (iOS `FeedNoteCard.actionBar` extracted to keep the body under the Swift type-check budget; Android `FeedNoteCard`) hide the zap action on notes carrying it. Preflight gains a Zaps row (on/off). Semantics documented in-UI and in `ZapPolicy`: this is zap permission, NOT pay-zap-to-view — paid unlocking needs invoice + preimage verification and is future work. Side fix: untracked `BitosSlider.kt` carried an invalid `layout.weight` import that broke the first full-module recompile. Gates: shared host tests + Android 116 tests + iOS swift-6 strict all green.
  - [x] Post-details audit vs prototype `#/create-details` (2026-09-05, BOTH platforms): missing prototype rows implemented for real — Cover image gains an "Edit" action (drops the flow back onto the editor stage's Set cover), Zap settings row (informational: viewers zap the profile wallet — no fake off-switch, zap-split tags are wave 4), and an Allow-remix switch that is ONE view of the existing `license` tag (off = exactly `bitz/all-reserved`; on restores the last remixable chip via remembered state — switch and chips stay in sync both ways). Manual "Remix source" / "Source author" inputs REMOVED per product call: lineage is machine-stamped (MST-042 — `remix` + `p` + `meme` tags via shared `RemixRules`/`memeRemixTagsFor` seam), so when a source rides the draft the form previews it READ-ONLY (source + author short refs, keep-it-remixable license note) and nothing is hand-typed; the draft fields remain as the programmatic carrier for the remix handoff. Splits/PoW/schedule: explicit in-form wave-4 note where the prototype mocks them (no fake controls). Gates: Android `testDebugUnitTest` (116 green) + iOS swift-6 strict type-check green.
  - [x] Prototype `#/create-edit` parity pass (2026-09-05, BOTH platforms): editor gaps closed against the interactive prototype's editor screen — ① **Adjust** (FX-panel sliders): shared `MemeAdjust` + `adjustedMatrixFor` (look→bri→con→sat composition into the one 4×5 burn-in matrix) + additive `"adjust"` wire key + undoable/coalesced `SetAdjust` + bridge seam `memeAdjustMatrix`; Looks sheets grow Brightness/Contrast/Saturation % sliders + reset (project-wide in video, per prototype); burn-in composes through raster + video export + stage preview everywhere. ② **Meme generator** (prototype hot tool): TOP/BOTTOM + Impact/Comic/Modern sheet → positioned caps pair (y .16/.84, outline 3) as ONE undo step (`addMemeCaptions` both states). ③ **Canvas meta chips**: `WxH · ratio` / `N frames · delay` / `mm:ss · N clips` bottom-leading both stages (Android picker retains decoded bounds now). ④ **Labeled tool row** (Meme first, icon+caption, scrollable). GIF lane closed end-to-end the same day: the grade now burns into iOS GIF export/publish (frame → `applyLook` before paint) and previews on BOTH GIF stages (iOS `gradedImage` cache / Android stage `ColorFilter`); Android GIF export already graded via `basePaint`. Preview coverage: image + GIF + Android-video stages are WYSIWYG; the iOS VIDEO stage still previews ungraded (pre-existing MST-043 gap — looks never previewed there either; export/publish burn exactly). Fixes: iOS `ToolIconButton` double a11y label (all tools read "Save to Photos"), iOS Image mode chip lit in video mode. Tests: `MemeAdjustTest` (8 common) + iOS `testMemeAdjustSeamAndEditingLoop` + 2 `MemeEditorStateTest` cases.
  - [x] MST-043 looks (2026-09-02, M4 wave 2 start): shared `MemeLooks` (8 web grades → CSS-spec 4×5 color-matrix engine, unknown→none) + `lookId` on the project wire + undoable `SetLook` + bridge seams `memeLooks`/`memeLookMatrix`; Android raster burn-in + WYSIWYG stage filter + ✨ sheet; iOS CIColorMatrix export + grade-cached stage + ✨ sheet; `MemeLooksTest` reference-pixel battery green. Also: scr-home batch-queue bar on both Create hubs (variants · published · awaiting + progress). Remaining wave 2: MST-041 SFX pack, MST-044 fx/timed overlays.
  - [x] MST-048 mass production V1 (2026-09-02): shared `MassBatch`/`MassBatchRules` (typed slots, mockup severity contract, `{slot}`/`{i}` resolution, content+poster approval hashes, recipe fork-on-edit, stable per-event publish queue w/ crash recovery, bounded CSV) + 12 common tests + `contracts/mass/batch-v1{,-hostile}.json` pinned in tests + bridge seams `massBatchNew/Op/Plan/ImportCsv`. Per product decision the flow lives INSIDE the Create hubs (no separate mass screens): Android `CreateScreen.kt` (scr-home "Start something new" tiles: Bitz/Meme/Batch + `MassBatchFiles`/`MassBatchUi` + kind-20 queue via `publishMemePicture(onResult:)`), iOS `CreateView.swift` (`MassBatchFlow` + per-event Blossom upload → `publishMemePictureNote`). iOS type-check pending a simulator runtime (syntax-parse + API-signature audit done); batch V1 = image memes (video/GIF batch + covers ride later waves).
- [ ] W4+/V2: bitz composer; expert timeline/SFX/voice-over (EDT-005..009); full suite (EDT/MEM)

### APP-020 — Static pages (spec §3.20)

- [ ] About / Privacy (12 §) / Terms (11 §) — content from legacy copy

### APP-021 — Trending sounds (spec §3.21) — W3

- [ ] Ranked list + preview + use-in-studio handoff; needs kind-30078 shared sounds

### APP-022 — Component library (spec §4)

- [x] Identicon avatar, zap sheet, comment sheet, more-menus (W0)
- [x] HexAvatar/HexIcon/HexShape system (migrated identicon to hex clip on both platforms; Long-parse seed regression fixed + tested)
- [x] AppMenu popover (screen-clamped showAt, checks/dividers/destructive, tested layout) + AppBottomSheetMenu — both platforms; first adoption: note ⋯ menus (iOS popover at trigger anchor, Android anchored DropdownMenu)
- [x] Icon token layer: AppIcons.* semantic tokens both platforms (backing SF Symbols / Material Rounded; Solar/Lucide provenance in comments — swap-in-place if brand demands)
- [x] PowCard/PowBadge (NIP-13 slider 0–30 + hash viz + chunked background mining + cancel/retry; adopted in both note composers; shared-core Pow with parity tests)
- [ ] AttachmentPreviewRow; MediaGrid; ImageViewer lightbox; BitzVideoCover
- [x] BrandQrCode (2026-08-28): shared pure `QrCode` encoder (byte mode, ECC-M, V1–6, 8 masks w/ penalty; identity payloads) + branded renderer both platforms (white card, gray-900 modules, orange hex-bolt cover — legacy parity) + identity-QR dialogs from the You profile npub row AND the More hub hero (scan-to-follow + copy); bridge `qrMatrix` (bit-packed rows). Invoice QRs use the user's zxing path (payment-grade, long payloads). 5 common tests
- [ ] Nip05Badge; RelayStatusDot
- [ ] AccountSwitchOverlay/Sheet; BootSplash; ErrorRetryWidget; skeletons; empty-state widget
- [ ] GlassContainer; LikeButton animation; AppAvatar size set

### APP-023 — Tokens & theming (spec §2)

- [x] Dark token set on both platforms (W0)
- [ ] Light theme complete; mode switch (dark/light/system) + persistence
- [ ] Accent palette + runtime swap; font size scale; compact mode
- [ ] Motion durations/curves constants + reduced-motion gates
- [ ] TEST: token parity snapshot (iOS/Android values equal)

### APP-024 — i18n (spec §7)

- [ ] String table infra (keys per surface); en complete; lo complete
- [ ] System language default + in-app override (settings `language`)

---

## Progress log

Append newest-first. Format: date — what shipped (IDs), what was found/
fixed, what's next.

- 2026-09-03 — Studio Draw pen wave (APP-019 EDT-008 slice; the scr-suite
  chip row is now 6/7 live — only Sound remains "soon"): **pen strokes on
  the stage, burned under the captions in every export path.** Shared
  (common-tested): `MemeStroke` (normalized polyline, palette color,
  width as a canvas-height fraction), project `drawStrokes` on the local
  wire (`"draw"` rows, budget-capped decode), `MemeCommand`
  AddStroke/RemoveStroke/ClearDrawing + codec ops (stroke-add /
  stroke-del / draw-clear), and web `drawing.ts` bounds enforced in
  apply: ≤1,500 pts/stroke, 12,000 pts/project, over-budget adds evict
  the OLDEST ink. `MemeExportRules.drawingPlan` (canvas-px polylines) +
  envelope `"strokes"` rows. Tests: rules budget/eviction + codec
  round-trip, contract round-trip + hostile flood (budget caps decode),
  plan geometry + envelope. Android: draw mode on the stage (pen tool in
  the tools row AND the suite Draw chip), a capture layer centered on
  the measured media rect (strokes normalize exactly like the export
  canvas — WYSIWYG), pen controls (8 palette dots, 3 widths, undo-
  stroke, clear, done), and `MemeRaster.drawStrokes` wired into image
  render, GIF frames and the per-frame video CanvasOverlay (ink under
  overlays everywhere). iOS: store stroke ops (undoable) + wire-parsed
  strokes, `MemeRaster.paintStrokes`, static ink CALayer under the video
  overlay tree (+ fast path + GIF frames + stills), aspect-fitted
  DrawLayerIos capture overlay with live-stroke preview, and the same
  pen controls. Solar pen icon (`solar_pen_linear`) added to
  SolarStudioIcons. Verification: shared + Android suites green; iOS
  parse green (type-check still gated on a simulator runtime). Device
  QA: finger-drawing smoothness/stroke caps, ink in exported PNG/GIF/
  MP4, budget eviction UX at 12 k points.

- 2026-09-03 — Post-details fields on the media publish sheet (user
  "next step"; reference app-04 scr-details). The kind-22 import path now
  collects what the meme paths already did: caption counter, **alt text**
  (NIP-31; falls back to the caption when left blank — accessibility
  floor, common-tested), and a **content warning** gate + reason
  (Flashing imagery / Sensitive topic / Loud audio → NIP-36
  `content-warning`). Shared: `composeMediaNote` gained defaulted
  `altText`/`contentWarningReason` + caption-hashtag t-tags (fixture
  updated in BlossomTest); bridge `composeMediaNoteEventId`/
  `mediaNotePublishMessage` + both native publishers thread them through
  (defaults keep old call sites source-compatible). Android
  ImportMediaContent + iOS ImportMediaSheet grew the fields; publish
  enables when caption OR alt is present. Also unblocked the tree: fixed
  6 mechanical errors in the concurrent session's in-flight
  MemeEditorView (non-optional bridge return, VideoStageIos arg order,
  `BitOSTheme.text`→`textPrimary`, SuiteTrackLane labels, LooksSheet
  body split, `lookId` nonisolated), restored the dropped `AppIcons.save`
  token, fixed their `charset("ASCII")` test slip, and RELINKED the stale
  universal-sim BusinessCore framework (the suite-port symbols were
  missing from the Sep-2 binary). Verified: shared macosArm64Test green,
  Android compile + publish/create/theme tests green, iOS whole-module
  typecheck green except one remaining expression-budget timeout inside
  SuiteDockView — the region the concurrent session is editing live
  (Trim/Speed chips gained actions minutes ago), deliberately left to
  them.

- 2026-09-03 — Studio Trim + Speed wave (APP-019; two more scr-suite tool
  chips go live — Draw and Sound remain "soon"): **in-suite trim editing
  and whole-clip playback rate (0.5–2×, web speed-track bounds), with
  every timing consequence handled.** Shared (common-tested): project
  `speed` field (video-only wire `"speed"` written only when ≠ 1, clamped
  0.5–2, junk → 1), `MemeCommand.SetSpeed` + codec op `speed`, and
  `SfxSynth.cuesInOutputTimeline` (cue media times ÷ rate — the mix
  lives on the output timeline); the `memeSfxTrackWavBase64` seam grew a
  rate param. Tests: contract speed round-trip/clamps, SetSpeed clamp +
  codec round-trip, cue mapping (2× halves offsets, 0.5× stretches, junk
  → source). Android: `state.setSpeed` (undoable), the exporter applies
  `EditedMediaItem.setSpeed(SpeedProvider)` (Sonic resamples audio+video;
  pitch shifts — V1 accepted and labeled), the timed overlay maps output
  time → media time (× rate + trim start), the cue mix + publish
  durationMs are effective (media ÷ rate), and Trim/Speed sheets (window
  sliders with the effective length said out loud; rate chips with
  output-length preview) replace the dead chips. iOS: store
  `setSpeed`/`setTrim` + wire-parsed speed/trim, exporter
  `scaleTimeRange` on both tracks (keep ÷ rate) with the instruction
  window + SFX insert + keyframe sampler all on the output timeline
  (sampler maps media = output × rate + trim start), publish duration
  effective, and the same Trim/Speed sheets. Remaining V1 caveat
  (documented in the sheets): audio pitch follows the clip. Verification:
  shared + Android suites green; iOS parse green (type-check still gated
  on a simulator runtime). Device QA: 0.5×/2× exports (frame pacing,
  pitch), trim+speed combined, cue timing under rate.

- 2026-09-03 — Studio export WYSIWYG wave (APP-019; closes the MST-044
  export item + the last open MST-041 slice): **overlay timing windows
  and fx now burn into the exported video exactly as the suite timeline
  previews them, and iOS exports carry the SFX cue mix.** Shared:
  `MemeExportRules.paintPlanAt` — the per-frame paint plan (geometry +
  `MemeFxRules.transformAt` + half-open window drop; poster = identity),
  common-tested (window edges, entry/overshoot sampling, image layers);
  bridge seams `memeFxTransformAt` (`scale|rot|dx|dy|alpha` with the
  window folded into alpha; negative time = poster — non-null Long for
  clean Swift interop) and `memeSfxTrackWavBase64` (the deterministic
  cue-track mix as a WAV) with a bridge battery. Android: the exporter's
  static full-frame bitmap is replaced by a Media3 `CanvasOverlay`
  repainting `paintPlanAt` per frame (output presentation time shifted
  back into media time by the trim start — the clipping runs before
  effects), with the raster's draw path gaining the fx frame transform
  (pivot at the moved item center) + alpha. iOS: per-overlay tight
  CALayers (content measured by a new `MemeRaster.contentSize`, anchored
  at the overlay center so transforms pivot like the stage) whose
  transform/opacity keyframes are SAMPLED from the seam at ~30 ms steps
  — deterministic, no Swift easing math; purely static projects keep the
  single flattened layer (fast path unchanged); the SFX mix rides as a
  second composition audio track with an explicit AVMutableAudioMix
  (Android already mixed it via Media3 sequences). ALSO: the iOS
  `MemeFxBridge` mirror of the fx math is DELETED — stage previews and
  export sampling both read `memeFxTransformAt` (the plan's requested
  close-out), and the per-layer path fixes iOS ignoring overlay rotation
  in static export. Verification: shared common tests + Android
  compile/unit tests green; iOS parse green (type-check still gated on a
  simulator runtime). Manual QA (device matrix): animated export
  frame-accuracy, keyframe sample density on long clips, SFX mix levels.

- 2026-09-03 — Settings chip rows now wrap (user report: option chips in
  the Lightning & Zaps / Algorithm & Feed / Language & Region sections
  ran off-screen instead of flowing to a second line). Root cause: every
  pill group sat in a plain non-wrapping container. Android
  `SettingsScreen`: `OptionRow` (theme/text-size/timeline/freshness/
  autoplay/quality/playback-rate/language/date-format/privacy selectors),
  `ZapAmountRow` presets, algorithm surfaces + preset pills, relay
  suggestion chips and the About NIP badges (was a fixed
  `chunked(5)` grid) all render in `FlowRow` — the same
  `ExperimentalLayoutApi` pattern ProfileScreen/AuthorProfileSheet
  already use; chip styling, spacing and selection visuals unchanged.
  iOS `SettingsView`: the algorithm preset pills, relay suggestion chips
  and `FlowChips` (About NIPs — the name promised wrapping but the body
  chunked fixed rows of 5) now use the shared `FlowLayout` from
  ProfileView; the three user-named sections are pickers/steppers on iOS
  and never had the bug, the fix there covers the pill rows that did.
  No shared/business change (view-layer only). Verified: Android
  compileDebugKotlin + shared androidHost & android unit tests green,
  iOS whole-module Swift 6 strict-concurrency typecheck exit 0 (only
  pre-existing FeedStore warnings).

- 2026-09-03 — CAP→MEM now merges ALL takes (user report: "when you
  click MEM all takes should go to the studio"). The handoff no longer
  carries only the newest take: with N ≥ 2 takes the record screen
  concatenates the whole strip (take order) into one clip before seeding
  the editor — Android `TakeMerger` (Media3 Transformer
  `Composition`/`EditedMediaItemSequence`, listener-await, ≤256 MB
  source cap) and iOS `CameraScreen.mergeTakes`
  (`AVMutableComposition` + passthrough export, no re-encode for
  same-codec camera clips). A "Combining N takes…" progress overlay
  covers the transcode; a failed merge falls back to the newest take
  with a hint instead of dead-ending. The "only the newest continues"
  confirm dialog is gone (nothing is discarded anymore); single take
  still passes straight through. Verified: Android compileDebugKotlin +
  create/theme unit tests green (the earlier MemeEditorStateTest
  failure was the concurrent session's in-flight state/test mismatch,
  resolved by them — failures=0 now); iOS: my touched files typecheck
  clean in isolation and the FeedStore.swift main-actor error is fixed
  (dead `InteractionProfileStore()` default-value expression removed —
  Swift 6 evaluates default values nonisolated; the sole call site
  passes it explicitly). The whole-module typecheck is currently red
  only in MemeEditorView.swift, the concurrent session's in-flight
  suite port (coverSet/AppIcons.save/lookId sites), untouched here.
  Also confirmed the Bitz §3.7 record entry exists on both platforms
  (top-bar camera → Create hub) — the new record screen is reachable
  from Bitz, not just Create.

- 2026-09-03 — Studio V2-suite wave (APP-019, pulls forward the first
  `scr-suite` slice of plan V2/EDT-005..009): **video-mode source insert
  + expert timeline dock, both platforms.** Shared core (common-tested):
  `MemeOverlayKind.IMAGE` with an `assetId` reference riding the LOCAL
  project wire only (`"kind":"image"` + `"asset"`; `localToWire` drops
  layers — web `image-overlay.ts` parity, they persist via slots and burn
  into exports); `MemeProjectContract.maxAssets(VIDEO)` 1 → 7 (1 clip +
  `MAX_IMAGE_LAYERS = 6` imported sources); `MemeRules` IMAGE defaults
  (size 192 ≈ 18% stage height, square hit box); `MemeExportRules` plan
  items carry `image`/`asset` geometry (fontSizePx = target height,
  aspect kept by the rasterizer) and the bridge envelope gains
  `"image":true`/`"asset"` rows. Tests: +4 batteries (rules defaults,
  contract round-trip + video cap, wire drops layers but the local store
  keeps them, export plan/envelope geometry). Android: `layerPicker`
  insert (image/GIF → asset + IMAGE overlay; GIFs paint their first
  frame — V1 semantics, labeled in the sheet), `VideoSourceTray` (clip
  tile → replace via trim handoff, layer thumbs, insert tile),
  `OverlayNode` IMAGE branch (AsyncImage, aspect-kept, WYSIWYG with the
  raster), `MemeRaster.drawImageItem` + `MemeVideoExport` asset resolver
  (all three export/publish call sites), layers resume from slots, and
  **`SuiteDock`** (scr-suite): video/overlays/audio/sfx track lanes with
  real data (overlay visibility windows, cue ticks), scrubbable playhead
  via a registered `VideoTransport`, mono time + `cue N/16` readout,
  tool chips (Layers/Looks/SFX live; Draw/Trim/Sound/Speed honest
  "soon"), undo + **redo** (`MemeEditorState` redo branch, symmetric
  snapshots, cleared by any new edit — test), Preview/Export. ALSO
  FIXED: the video stage never passed `MemeFxRules.transformAt` into
  `OverlayNode` (fx was wired but inert on Android) and the video publish
  guard early-returned on `activeAsset == null` (video mode has no
  tray asset — publish button was a no-op). iOS: same feature set —
  store redo history + `addImageOverlay`/`select(id:)`/`layerImages`,
  mode switches drop IMAGE layers (their sources are session-bound),
  video asset cap 6 with `activeAsset` untouched (Looks stays
  image-mode), `OverlayUiView` IMAGE branch, `MemeRaster.paintImage`,
  `MemeVideoExportIos.export(images:)`, `VideoStageIos`
  `layerImages`/`showScrub`/`VideoTransportIos`, `SuiteDockView` +
  `LayersSheetView`, tray insert + layer resume. Known V1 bounds
  (documented in code): video-in-video PiP and animated GIF layers ride
  later waves; layer timing windows render on the timeline but exports
  still burn the full-frame overlay (pre-existing V1 export semantic).
  Android compile + unit tests (state 10/10) + aapt2 green; shared
  common tests green (rules 12 / contract 8 / wire 21 / export 7); iOS
  syntax-parse green (type-check still gated on a simulator runtime).

- 2026-09-03 — CAP→MEM handoff (user follow-up: "MEM drops the takes").
  The record screen's MEM button previously opened the editor empty and
  the session takes died with the camera. Now the latest take flows into
  the meme editor as the video-mode source on both platforms:
  `CameraScreen.onOpenMeme` carries `(bytes, mime) | null`, Create hub
  holds it as `memeSeed`, and `MemeEditorScreen(videoSeed=…)` /
  `MemeEditorView(videoSeed:)` switch a fresh project to video mode and
  feed the take through the exact MST-030 pipeline a picked clip uses
  (trim screen → `setVideoClip`/`videoBytes` + probe + cut-to-60 s) —
  from that moment the editor's MST-018 slot autosave persists it as a
  studio draft (deliberately NOT the OS photo gallery: unpublished media
  stays in the project library). With several takes, a confirm states
  that only the newest continues (single-clip video mode in V1); one
  take continues straight through; zero opens the editor empty. Verified:
  Android compileDebugKotlin green (after the concurrent session's
  MemeEditorScreen suite-dock additions landed), iOS whole-module
  typecheck green except the known in-flight FeedStore.swift. Still
  deferred: multi-take merge into one clip (media-core).

- 2026-09-03 — Record screen rebuilt to the app-04 Honeycomb reference
  (APP-019 / CAP-002 / APP-022 icon layer; user ask). Both camera
  surfaces (`CameraScreen.kt` / `CameraScreen.swift`) now carry the
  reference chrome over the unchanged thin capture contract
  ((bytes, mime) → publish pipeline): rule-of-thirds grid (toggle),
  blinking REC badge with mono elapsed clock, right-edge .5×/1×/2× lens
  rail (CameraX setZoomRatio / videoZoomFactor, clamped to device
  bounds), torch toggle (hidden when no flash unit), off/3s/10s
  self-timer with full-screen countdown, session takes strip (real
  poster-frame thumbnails via MediaMetadataRetriever /
  AVAssetImageGenerator, per-take ✕ delete, dashed "+", "N takes ·
  MM:SS", tap tile → existing trim preview; Retake discards that take,
  Use hands off), 76dp record button with morphing red core,
  gradient Import + MEM side buttons (wired: CreateScreen/CreateView now
  close the camera into the import sheet / expert editor), Flip /
  Edit takes / 3:00-cap bottom row with real enforcement (auto-stop at
  180 s; iOS minFreeDiskSpaceLimit 1.2 GB low-storage reserve), X with
  discard-takes confirm, and the permission-declined layout (plate,
  system-settings deep link, library-instead, retry). Buffers bounded:
  ≤5 pending takes / 96 MB session / per-take Blossom 64 MB cap.
  Icons: vendored 4 new Solar Linear vectors (flashlightOn, stopwatch,
  clockCircle, cameraRotate + camera for Android) — iOS AppIcons capture
  tokens, Android new `SolarCaptureIcon` facade mirroring SolarFeedIcon;
  plain-X close and the 3×3 grid toggle keep native renderers (no Solar
  glyph; icon-system.md convention rule). Mock-only controls deliberately
  not shipped: speed chip, mic meter (no audio track in the pipeline),
  settings gear. Found+fixed: working-tree `project.pbxproj` was corrupt
  (one malformed PBXBuildFile line from in-flight meme work merged two
  entries; SharedTemplateStore 15D missing from Sources) — repaired
  without touching the other session's intent. Verified: Android
  `:apps:android:compileDebugKotlin` green; iOS whole-module swiftc
  typecheck green except the pre-existing in-flight FeedStore.swift
  error (untouched). Next: record → Quick-Bitz-style editor handoff for
  take sequences (multi-take merge needs media-core), audio capture
  decision before any mic meter.

- 2026-09-03 — Studio Solar icon pass (APP-019 / APP-022 icon layer):
  every emoji/text-glyph chrome icon on the studio surfaces now paints a
  reviewed bundled Solar vector, matching the app-15 Honeycomb reference
  and the icon-system policy (tokens per platform, no raw glyphs as
  chrome). Vendored from the official Solar set (Iconify mirror; same
  CC-BY 4.0 source already attributed in THIRD_PARTY_NOTICES.md — no
  license change): iOS imagesets `SolarTextLinear`,
  `SolarSoundwaveLinear`, `SolarUndoLeftRoundLinear`,
  `SolarDangerTriangleLinear`, `SolarMusicNoteLinear`; Android drawables
  `solar_text/soundwave/undo_left_round/danger_triangle/palette/music_
  note/magic_wand_linear` (palette + magic wand port the exact in-repo
  iOS SVGs so both platforms paint identical glyphs). New token
  surfaces: iOS AppIcons gained `textStyle/sticker/looks/sfx/musicNote/
  warningTriangle` (+ `undo` now resolves to Solar instead of SF);
  Android gained `SolarStudioIcons.kt` (Settings/Gallery/VideoCamera/
  Bolt/Text/Sticker/Palette/Soundwave/UndoLeft/DangerTriangle/MusicNote/
  MagicWand) mirroring iOS one-for-one. Call-site swaps both platforms:
  scr-home start tiles (🎥🖼⚙️ → video/gallery/settings), batch-queue
  bar (⚙️ → hex-plate settings glyph — reusing the iOS `HexIcon` design
  system component; Android gained the matching flat-top `HexPlateShape`),
  editor tools row ("Aa/☺/✨/🔊" → Solar text/emoji/palette/soundwave
  circles), delete-handle ✕, SFX sheet ▶/＋ buttons, mass-flow badges
  (recipe 🔒, "image ✓ (replace)", review "!", "published ✓" → icons),
  quick actions music/remix. Mockup-parity additions: template rails now
  render the scr-home gradient covers (honeycomb.css `grad-lightning/
  mine/node` + a story violet — stable by rail position, same ladder on
  both platforms) with the zap-priced sats chip (bolt icon + amber,
  "⚡" text glyph dropped). Sticker-grid emoji and template pack emoji
  stay by design (content, not chrome — the mockup itself uses emoji
  there). Android `compileDebugKotlin` + unit-test compile green; iOS
  syntax-parse green for the three touched files (full type-check still
  gated on a simulator runtime — same as M3c).

- 2026-09-02 — Meme Studio M4 wave a: the remix wire (APP-019 MST-042
  shared core). The READ side already existed (APP-007's `RemixRules` —
  sourceOf incl. legacy bitz:edge, license vocabulary + advisory gate,
  `RemixChain` 32-deep cycle-guarded walk; FeedNote carries
  `remixOfEventId`/`license`), so this wave built the WRITE side:
  (1) SHARED `core/studio/MemeRemix.kt` — the compact `meme` tag codec
  (v/o/c/l keys, round-2 coordinates, defaults omitted: impact/white/
  caps/stroke/bar; tolerant decode with the 12-overlay cap, hostile
  colors → `#ffffff` (web cleanColor — caught by the battery), x/y
  clamped 0..1 (web normalizeOverlay — also caught), cues normalized
  through the MST-019 rules) and the §3.3 degradation ladder:
  layout+cues → layout → media-only, never a raw slice (web truncates
  today; the plan's ladder is the rule). Future web keys (g/z/f/s —
  image layers, zoom/fx/speed tracks) ride VERBATIM through a native
  decode→encode round-trip, so a native remix never drops the author's
  V2 tracks — pinned by a test with a web-shaped payload. (2)
  COMPOSERS: `composeMemePictureNote`/`composeMemeVideoNote` grew
  `extraTags`, spliced BEFORE `alt` per web `postBitz` order. (3)
  BRIDGE: `memeRemixTagsFor(projectJson, sourceEventId, sourcePubkey,
  relaysJson, license, attribution)` — project wire → localToWire →
  ladder → `["remix", id, ≤3 relays]` + `["meme", payload?]` +
  `["p", author]` (+ license/attribution when valid) as TagsCodec JSON
  (+ bridge test: relay cap, all tag shapes, corrupt → ""). (4)
  ANDROID: `publishMemePictureNote`/`publishMemeVideoNote` accept
  `extraTags`; the VM lane parses `remixTagsJson` (TagsCodec) end to
  end. Two test-authoring bugs caught by the battery (the `"o":` false
  positive on the overlays array key; a mis-tuned ladder fixture that
  skipped straight to media-only) — the codec itself needed the two
  parity fixes above. Verified: full shared lane green, full Android
  suite green. NEXT (wave M4b — the UI reach): FeedNote projection
  carries the meme payload; feed/Bitz ⋯ menu gains "Remix meme"
  (license-gated advisory); the editor seeds overlays from a decoded
  payload with the source attached and publishes with the tags; the
  RemixChain dialog (RemixChain.walk already exists).

- 2026-09-02 — Meme Studio M3 wave c: cover frames (APP-019 MST-032) —
  **M3 code-complete** (video memes: trim → stage → cover → burn-in →
  kind-21/22 on both platforms). (1) SHARED: `UploadedMedia.thumbUrl`
  — optional, same HTTPS/loopback policy as the media URL (≤2048),
  emitted as the imeta `thumb` field by `imetaFields()` for EVERY
  composer (picture/video media paths); the video bridge pair grew the
  param (default null keeps old callers valid); `BlossomTest` asserts
  the thumb rides in the video meme imeta AND that an insecure thumb
  rejects the descriptor; fixture `kind-22-unsigned.json` now carries a
  thumb. (2) BOTH PLATFORMS: a "Set cover / Cover ✓" chip in the video
  stage's scrub row captures the frame AT THE PLAYHEAD (Android:
  MediaMetadataRetriever closest-sync, JPEG 85; iOS: AVAssetImageGenerator
  with the upright transform, ≤1080) and uploads it SEPARATELY through
  the hash-verified Blossom path (image/jpeg) — §3.4's "poster uploaded
  once separately" rule — with the publish flow attaching the URL.
  Cover picks are session-only in V1 (the project wire has no thumb
  field; a relaunch re-picks — documented). (3) MST-035 remains the
  manual device gate (no simulator runtimes on this machine): 60 s
  bound, call interruption mid-export, backgrounding, low storage,
  thermal, process-kill mid-export — checklist pinned in this tracker
  row. Verified: full shared lane green (thumb battery), full Android
  suite green, whole-app Swift 6 typecheck clean (relinked). NEXT: M4
  polish per plan order — MST-040 studio home (resume slots exist;
  templates grid), MST-041 sound seed, MST-042 remix lineage.

- 2026-09-02 — Meme Studio M3 wave b: iOS video mode (APP-019
  MST-030/031/033/034 on iOS — video memes now work end to end on BOTH
  platforms). (1) IOS EXPORT (MST-033): `MemeVideoExportIos` — AVAsset
  probe (duration + naturalSize×preferredTransform → rotation-aware
  upright dims), AVMutableComposition (video+audio) + pass-through
  AVMutableVideoComposition at the upright render size whose Core
  Animation tool composites ONE full-frame CALayer rendered from the
  shared `memeExportPlan` envelope (the same geometry the stage paints —
  WYSIWYG), AVAssetExportSession (HighestQuality → MP4) with temp-file
  cleanup; session clip bytes live at a temp URL (AVFoundation needs a
  file). (2) IOS STAGE (MST-031): `VideoStageIos` — AVKit VideoPlayer
  with the meme overlays on top at any playhead, play/pause + scrub
  slider, tap-through hit-test selection. (3) EDITOR: the Video chip
  goes live on iOS (confirm-on-media switch, overlays survive via the
  wire transplant); picking (.videos) loads bounded bytes, probes, caps
  at 60 s, and hands through the EXISTING VideoPreviewScreen trim screen
  whose "use" output feeds the stage (MST-030 reuse — same contract as
  Android, zero new trim code); Save lands the burned MP4 in Photos
  (add-only, video resource); Publish rides the unchanged
  hash-verify-before-sign machine as video/mp4 →
  `NotePublisher.publishMemeVideoNote` (kind 22 portrait / 21 landscape
  + duration imeta, through the M3a bridge pair). (4) SLOTS: the clip
  persists as a raw data asset (`v1`/asset-v1.mp4 — the store's manifest
  grew a dataAssets lane) and resume re-probes it back into the session.
  Fixed during wiring: stale framework slice (bridge pair landed after
  the last relink), and the stage's overlay/delete views needed internal
  visibility from the editor file. Verified: full shared lane green,
  full Android suite green, whole-app Swift 6 typecheck clean
  (relinked), tests parse clean. REMAINS (wave M3c): MST-032 cover-frame
  capture with the separate poster upload (imeta `thumb` — needs a
  `thumb` slot on UploadedMedia + fixture), MST-035 QA matrix
  (long-clip, interruption, low storage, process-kill mid-export).

- 2026-09-02 — Meme Studio M3 wave a: shared video rules + Android video
  mode (APP-019 MST-030/031/033/034 on Android; both-platform kind
  rules). (1) SHARED: `composeMemeVideoNote` — portrait (height ≥ width
  of the EXPORTED frame) publishes kind 22, landscape kind 21 (web
  `postBitz` parity), tag order identical to the picture path (t-tags,
  alt ≤200 fallback, imeta now carrying `duration` seconds, CW last);
  orientation battery in `BlossomTest`; bridge pair
  `composeMemeVideoEventId`/`memeVideoPublishMessage` (iOS consumes with
  M3b). (2) ANDROID EDITOR: the Video chip goes live (confirm-on-media
  mode switch, overlays survive); picking (VideoOnly, probe via
  MediaMetadataRetriever — dims/rotation/duration, ≤60 s bound, ≤64 MB
  read) hands the clip through the EXISTING VideoPreviewScreen trim
  screen whose "use this" output feeds the meme stage unchanged (MST-030
  reuse, zero new trim code); `VideoStage` composes an ExoPlayer surface
  (data-uri transport like the trim screen) with the meme overlays drawn
  on top at any playhead + play/pause + scrub slider (MST-031; V1 burns
  overlays across the full clip). (3) EXPORT (MST-033): the overlay
  composition renders ONCE as a full-frame transparent bitmap using the
  shared MemeExportRules geometry (WYSIWYG with the stage) and burns via
  Media3 Transformer OverlayEffect/BitmapOverlay — one pass, static
  overlay, completion poll with a 120 s deadline; temp files cleaned in
  a finally. Save lands MP4 in Movies/BitOS (new `saveVideoFile`,
  pending-flag round-trip). (4) PUBLISH (MST-034): exported MP4 →
  hash-verified Blossom upload (video/mp4) → kind 22/21 by orientation
  with duration+dim imeta through the unchanged receipt machine
  (`NotePublisher.publishMemeVideoNote` + a `publishMemeVideo` VM lane);
  clips persist in continuation slots (`mem:v1` asset bytes) and resume
  re-probes the saved file. Fixed during wiring: media3 1.10 API surface
  (Effects two-arg, single-arg BitmapOverlay), retriever/MediaItem URI
  forms, branch-type unification in the publish render. Verified: full
  shared lane green, full Android suite green. REMAINS (wave M3b): iOS
  video mode (AVPlayer stage + AVVideoComposition burn-in through the
  new bridge pair), MST-032 cover-frame capture + separate poster
  upload, the MST-035 QA matrix (long-clip, interruption, low storage,
  process-kill mid-export).

- 2026-09-02 — Meme Studio M2 wave b: iOS GIF mode — **M2 COMPLETE**
  (APP-019 MST-020..023; both platforms now do GIF memes end to end).
  (1) BRIDGE: `memeGifPlan(delaysMsJson, pinnedSec)` +
  `memeGifLadderCanvas(w,h,step)` — the SHARED planner (20 ms floor,
  ≤360 guard, clamp 20..1000, halve ladder) drives the iOS encoder so
  timing stays single-sourced across three implementations (+ common
  bridge test incl. hostile holds and the 360-cap flag).
  (2) IOS DECODE: `GifFrameSourceIos` — CGImageSource with per-frame
  delays (kCGImagePropertyGIFUnclampedDelayTime preferred, DelayTime
  fallback), cumulative compositing onto a persistent canvas, holds
  clamped to the 20 ms floor with sub-2cs → 100 ms. Honest V1 caveat:
  CGImageSource does not expose disposal, so disposal-2 partial-frame
  sources can ghost (the Android/web shared decoder handles them
  exactly; most meme GIFs are full-frame).
  (3) IOS ENCODE: `MemeGifExportIos` — shared plan steps → per-frame
  UIGraphicsImageRenderer composition (overlays burned in through the
  existing row painter, now shared) → CGImageDestination GIF89a with
  per-step delays → the 8 MB halve ladder (≤3 steps) re-rendering at
  each candidate canvas. (4) EDITOR: GIF chip live on iOS (confirm when
  media exists; overlays survive the switch via a wire transplant),
  frame tray ≤60 with onDrag/onDrop reorder + delay slider (20–1000 ms,
  0 = source holds), looping stage preview (task keyed on a preview
  tick), Save → `.gif` into Photos (ladder/cap status), Publish →
  kind-20 with imeta `m image/gif` (the publish flow parameterized by
  mime; upload still hash-verifies before anything signs); GIF frames
  persist in continuation slots and re-seed the tray on resume (holds
  collapse to uniform 100 ms in V1). (5) FIXTURE:
  `contracts/nostr/kind-20-gif-unsigned.json`. Fixed during wiring: a
  @ViewBuilder tray-branch brace imbalance and a Task.detached closure
  form (both caught by parse/typecheck). Verified: full shared lane
  green (new bridge test), full Android suite green, whole-app Swift 6
  typecheck clean (framework relinked). Next: M3 video mode
  (MST-030..035) or the M4 polish set per plan order.

- 2026-09-02 — Meme Studio M2 wave a: shared GIF engine + Android GIF
  mode (APP-019 MST-020..023 partial; UI per the docs/ui app-15 layout —
  GIF is the same editor with the mode chip + frame tray). (1) SHARED
  pure GIF engine, all common-tested: `GifEncoder.kt` — verbatim port of
  web `gif-encode.ts` (same LZW code-size cadence + 4096 reset, median
  cut over a 5-bit/channel histogram with pixel-weighted longest-axis
  splits, per-frame LOCAL color tables, NETSCAPE2.0 loop-forever,
  centisecond delays with Math.round parity + 2cs floor); deterministic
  by construction — a golden test pins re-encode byte-equality.
  `GifDecoder.kt` — port of the web fallback parser (GCE delay/disposal/
  transparency, disposal 2 restore-to-background + 3 restore-previous,
  4-pass interlace, LZW inflate with truncated-stream guard, sub-2cs →
  100 ms browser heuristic); tolerant decode — junk/truncated → null.
  `GifExportPlan.kt` — planner port (source-frame cadence tiled under a
  pin, 20 ms floor boundary collapse, ≤360 guard flagged, stills export
  one 100 ms frame) + the ≤8 MB → halve-long-edge ladder (≤3 steps).
  16 common tests incl. the ENCODER⇄DECODER ROUND-TRIP golden (MST-022's
  smoke test runs on every push, no device needed). (2) ANDROID: frame
  decode rides the SHARED decoder (no platform API is frame-accurate for
  GIF — ImageDecoder exposes no per-frame control), ≤60 frames bounded,
  stills land as 100 ms frames; `MemeGifExport` = planner → per-frame
  raster with overlays burned in (new `MemeRaster.renderFrameRgba`) →
  shared encoder → ladder; editor GIF mode: chip switch (confirm when
  the project has media; overlays survive), frame tray with long-press-
  drag reorder + uniform delay slider (20–1000 ms, 0 = source delays),
  looping stage preview at each frame's hold, Save → `.gif` in
  MediaStore via `saveMediaFile` (bytes verbatim; ladder/cap status
  surfaced), Publish → kind-20 with imeta `m image/gif` through the
  unchanged verify-before-sign machine (`publishMemePicture` grew a
  mimeType param); GIF frames persist in continuation slots (PNG bytes
  via a mem: opener; resume restores the tray — per-frame holds collapse
  to uniform 100 ms in V1, documented). (3) Also fixed while wiring:
  GIF-mode nullability in the stage branch and a duplicated-block
  accident from scripted editing (caught by compile, repaired cleanly).
  Verified: full shared lane green (16 new GIF tests), full Android
  suite green. REMAINS (wave M2b): iOS GIF mode — CGImageSource decode
  + CGImageDestination encode (per plan §4.2 the iOS encoder is
  platform; the shared planner still drives timing) + the same tray/
  preview/publish UI; `image/gif` kind-20 fixture; on-device QA of
  reorder gestures + ladder behavior.

- 2026-09-02 — Meme Studio M1 wave 5: autosave + continuation slots —
  **M1 COMPLETE** (APP-019 MST-018, both platforms; the Quick MEM image
  editor is now durable end to end: import → edit → save/publish, crash
  safe). (1) SHARED `core/studio/MemeSlots.kt`: the slot wire (`slot.json`
  = `{v,id,updatedAt,assets[{id,file,aspect}],project<MemeProject wire>}`)
  + the hub index wire, both lenient (corrupt/oversized → empty; absolute
  paths and `..` traversal refs rejected — a broken store never blocks
  creating); `MemeSlotRules` owns the policy: most-recent-first, re-save
  bumps, LRU eviction past 6 RETURNS the evicted ids so stores delete
  their files, hub label (first overlay text ≤40), relative time —
  5 common tests. (2) ANDROID `MemeProjectStore`: `filesDir/studio/
  slots/<id>/` with copied-in assets (CAP-005 — transient picker URIs
  never persist; the copy is idempotent by stable file name), poster.jpg
  (~256 px JPEG, ≤192 KB per the web constants), index.json; bitmap +
  resolver edges are injected interfaces so the whole store round-trips
  on plain JVM against a temp dir — 5 tests: relaunch-restore of the
  exact project, copy idempotency, 7-saves-evict-the-oldest, explicit
  delete, corrupt-degrade + fresh-save-after-corruption. Editor wiring:
  debounced 500 ms autosave after every committed edit/asset change
  (EDT-002), resume seeds the project + tray from slot files, publish
  DONE and discard-confirm clear the slot, hub "Continue creating" rows
  (poster/label/time/Resume/✕, ≤6 badge). (3) FIXED a wave-1 latent
  UI bug: `MemeEditorState` was plain Kotlin — drags, adds and undo
  mutated state nothing observed, so the stage would never have
  redrawn; it now carries a Compose `revision` bumped at every mutation
  point (commit/gesture/undo/selection) — unit tests unchanged + green.
  (4) IOS `MemeProjectStore` (Application Support/studio — same layout
  and semantics, poster via scaled UIGraphicsImageRenderer; pbxproj
  registered) + the same editor lifecycle (`restore()` seeds the wire;
  `.task(id:)` autosave keyed on the @Observable revision; onAppear
  seeds the tray from slot files; publish/discard clear the slot) +
  `SlotRow` hub section. Honest gate: process-kill recovery is proven
  at the store level (fresh store over the same dir restores the exact
  project); on-device kill/relaunch QA rides the manual matrix with the
  rest of MST-035. Verified: full shared lane green, full Android suite
  green (95 tests in the meme packages), whole-app Swift 6 typecheck
  clean. M1 closed: waves 1–5 (editing core, wire codec interop gate,
  export, kind-20 publish, durability) all shipped. Next: M2 GIF mode
  (MST-020..023) or M3 video per the plan order.

- 2026-09-02 — Meme Studio M1 wave 4: kind-20 publish (APP-019 MST-017,
  both platforms; import → caption → **Post** now works end to end).
  (1) SHARED: `NoteComposer.composeMemePictureNote` ports the web
  `feed.postBitz` picture path tag order exactly — caption hashtag
  t-tags (deduped via ComposerRules), NIP-31 `alt` (explicit alt wins,
  else the caption's first 200 chars, only when non-empty), `imeta`
  (url m x size dim — the plan §3.4 superset of the web fields, `x` =
  the verified Blossom hash), then NIP-36 `content-warning` last; the
  caption hard-caps at 1000 (meme wire parity — the kind-1 Note
  destination stays a separate path). (2) BRIDGE: the kind-22 pair's
  pattern — `composeMemePictureEventId` + `memePicturePublishMessage` —
  hostile media (non-https) returns null, never a half-built event.
  (3) ANDROID: `NotePublisher.publishMemePictureNote` rides the existing
  compose→sign→receipt machine; `MediaPublishViewModel.publishMemePicture`
  owns a dedicated `MemePublishPhase` lane (render PNG → hash-verified
  Blossom upload → kind-20) so the import path state is untouched; the
  editor gained a Post button + publish sheet (caption with the soft-300
  counter, derived hashtag chips, CW toggle+reason, alt field, upload/
  publish phases, and the verify-before-sign footnote).
  (4) IOS: `NotePublisher.publishMemePictureNote` (sign-locally →
  frame → send) + `MemePublishSheet` with the same fields and phases;
  the editor wires Post through the store's publish task (render →
  upload → publish, dims from the shared export envelope).
  (5) TESTS + FIXTURE: `BlossomTest.composesKind20PictureMemeWithWebTagOrder`
  pins the exact tag list (alt fallback + 200 truncation, CW last,
  1000-char cap, hostile pubkey → null); bridge pair test; Android repo
  `publishesKind20PictureMemeWithWebTagOrder` asserts the SIGNED frame
  decodes as kind 20 with the web tag order + imeta and lands a relay-OK
  receipt — signing-before-upload-verify is prevented structurally (the
  uploader's hash gate, locked by BlossomUploaderTest). Protocol fixture
  `contracts/nostr/kind-20-unsigned.json`. One wrong assumption caught
  in review: picture memes do NOT reconcile through
  `MediaMetadata.fromEvent` (that's the kind-21/22 video extractor) —
  they ride the imeta/image path; the test asserts accordingly. PoW on
  memes deliberately deferred to the composer PoW lane. Verified: full
  shared lane green, full Android suite green, whole-app Swift 6
  typecheck clean (framework relinked). Next: MST-018 autosave slots +
  "Continue creating" (the last M1 item).

- 2026-09-02 — Meme Studio M1 wave 3: raster export → device save (APP-019
  MST-016, both platforms). (1) SHARED `MemeExportRules`: `outputSize` is
  the web `render.ts targetSize` port (1080 long-edge cap, no upscale,
  evened dims ≥ 2, degenerate source → 1080×1920 portrait) and
  `exportPlan` produces pure target-px draw commands — applying the web
  `displayText` caps transform (default uppercase), the `max(10, size ×
  referenceHeight)` font floor, outline at the ×2 stroke scale and the
  local pinch `scale` so exports match the edited stage; 5 common golden
  tests pin exact pixel values (the geometry half of the golden contract;
  bitmap hashing needs a device rasterizer — none in this repo's JVM or
  instrumented infra, noted in the tracker row). (2) BRIDGE/iOS SEAM:
  `memeExportPlan(projectJson, sourceWidth, sourceHeight)` returns the
  envelope `{"width","height","items":[…]}` so the size math stays
  single-sourced in shared; Swift client protocol/framework/fixture +
  `testMemeExportEnvelopeSeam`. (3) ANDROID `MemeRaster.kt`: sampled
  decode near the long edge → android.graphics off-screen render
  (sans-serif-black for the impact slot, stroke-behind-fill outline,
  setShadowLayer shadow, multiline centered under rotation) → MediaStore
  PNG `Pictures/BitOS` with the IS_PENDING round-trip and failed-write
  cleanup (minSdk 29 = modern API only). (4) IOS `MemeRaster`:
  UIGraphicsImageRenderer(scale 1) painting the envelope rows via
  NSAttributedString (negative strokeWidth = stroke under fill, NSShadow)
  → PHPhotoLibrary add-only save (`NSPhotoLibraryAddUsageDescription`
  already shipped); render runs detached, save is async. (5) EDITORS:
  Save tool button + busy/saved/failed status on both platforms.
  (6) WYSIWYG FIX: the plan doc's "×1080-wide" size wording was wrong per
  the web truth (`paintOverlay`: px = size × canvas HEIGHT) — the doc's
  own web-wins rule applies; stage previews re-based to height/1080 on
  both platforms so preview ⇄ export agree. Verified: full shared lane
  green (macosArm64), full Android unit suite green, whole-app Swift 6
  typecheck clean (framework slice relinked). Next: MST-017 publish page
  (kind-20 + imeta through the verify-before-sign machine), MST-018
  autosave slots.

- 2026-09-02 — Meme Studio M1 wave 2: meme wire document codec (APP-019
  MST-019, MEM-001 interop gate; shared + bridge + iOS seams). (1) SHARED
  `core/studio/MemeWireDocument.kt`: `MemeWire` constants ported verbatim
  from web `schema.ts` (schema id/version, ≤12 overlays, ≤300-char text,
  size fraction 0.03–0.22 default 0.09, 7-hex palette, font/fx/look id
  sets, 31 SFX recipe ids + `custom` sentinel, caption ≤1000, cues ≤16);
  `MemeWireCodec` — tolerant decode (coerce/clamp/drop, never throw;
  foreign schema ids and version ≠ 1 rejected; `updatedAt` re-stamped via
  injected `nowMs` so tests stay deterministic; deterministic generated
  ids `o-n`/`c-n` with in-document collision guards), canonical encode
  and a `normalize` (decode→encode) — with PASSTHROUGH preservation of
  unknown root/overlay/cue fields, one notch safer than web (which drops
  them), so future-web data survives a native round-trip;
  `MemeWireConvert` — wire ⇄ local per plan §3.2: size fraction ⇄ 1080-px
  reference (0.09 → 97 px; the tighter web 0.03 floor wins on export),
  nearest-match color mapping both directions (RGB distance, ties →
  lowest; §8 open decision isolated in two functions), `stroke` ⇔
  `outline > 0` (2 px default), caps/bar defaults ride null-local so
  round-trips never flip a default, visibility windows and fx carried,
  emoji-only + stroke-free + no-caps rows import as STICKER overlays, and
  local-only styling (scale/rot/shadow/sticker) rides overlay passthrough
  — web parsers ignore those fields, native round-trips are lossless.
  (2) LOCAL STORE: `MemeOverlay` gained optional `caps/bar/startMs/endMs/fx`
  (encode-when-set keeps M0 wires loadable; nonsense windows normalize to
  always-visible on decode) and MAX_TEXT_LENGTH 200 → 300 (web parity).
  (3) BRIDGE + iOS: `memeWireNormalize(wireJson, nowMs)` /
  `memeWireToLocal(wireJson)` / `localToMemeWire(projectJson, nowMs)` on
  the bridge, the Swift client protocol/framework/fixture, and
  `testMemeWireDocumentSeams`; Android consumes shared directly.
  (4) FIXTURES: `contracts/meme/wire-document-v1.json` (3-overlay golden:
  classic impact caption, windowed+fx text, rocket sticker) inlined into
  `MemeWireCodecTest`, which ports web `schema.test.ts` branch-for-branch
  (defaults, junk-row drops, position/size clamps, 300-cap truncation,
  color regex with case preservation, `!!value` truthiness incl. the
  `"nope" → true` case, negative/non-finite/inverted window clears,
  count caps, [start,end) visibility semantics incl. open-ended windows,
  cue clamp/drop rules, conversions + native round-trips) — 13 common
  tests. Verified: full shared lane green (macosArm64), full Android
  unit suite green, whole-app Swift 6 typecheck clean (framework
  simulator slice relinked; iOS unit tests still not runnable on this
  machine — the new XCTest parses clean and awaits a runtime). Next:
  MST-016 raster export → device save, MST-017 kind-20 publish, MST-018
  autosave slots.

- 2026-09-02 — Meme Studio M1 wave 1: Quick MEM image editing core
  (APP-019 MST-010..015, both platforms; execution note added to
  `meme-studio-plan.md` §M1 — mockup→component map + wave order, web
  truth respected). (1) SHARED: `StickerCatalog` (web `stickers.ts`
  port, pack-for-pack: 6×8, bounded deduped recents ≤16, emoji-only
  gate); `MemeCommand.UpdateOverlay` gained `size` (the text-sheet size
  slider was otherwise unreachable through the command model) — apply/
  coalesce/codec + `updateClampsStyleSizeBothWays` common test; bridge
  seams `memePalette` (16 hex rows), `memeDefaultOverlay` (add-ready
  overlay JSON), `memeBounds` (selection-chrome bounds), `memeStickerPacks`
  (+ `memePaletteDefaultOverlayAndStickerPacksSeams` bridge test).
  (2) ANDROID: `MemeEditorState` (project + bounded undo stack of
  `(project-before, command)`; gestures apply live and push ONE net
  `UpdateOverlay` at gesture end, style bursts coalesce via
  `MemeRules.coalesce` in 300 ms, cancel-gesture restores — 8 unit
  tests incl. cap/dedup assets, hit-test select, undo bounds) +
  `MemeEditorScreen` (chrome with ✕ discard-confirm · mode chips · undo;
  empty-canvas CTA; stage = single gesture target mapping drag/pinch/
  twist → commands with tap = shared hit-test and a rotation-ignored
  delete handle; tray ≤9 via Photo Picker with active tile; text sheet
  with font-slot pills/palette/size+outline sliders/shadow; sticker
  sheet with pack chips + recents). Quick MEM hub row enabled → editor.
  (3) iOS: client protocol/framework/fixture gained the same studio
  seams; `MemeEditorStore` (@Observable; wire-in/wire-out through the
  client, snapshot history, incremental gesture deltas, 300 ms style
  coalescing) + `MemeEditorView` (same layout; UILabel attributed-text
  outline via negative strokeWidth = web stroke look; MagnifyGesture +
  RotateGesture + DragGesture(minimumDistance: 0) with tap-vs-move
  disambiguation; PhotosPicker ≤9; text/sticker sheets); Quick MEM row
  enabled; AppIcons gained `undo` tokens both platforms. Verified:
  full Android unit suite green; full shared common lane green
  (macosArm64); whole-app Swift 6 typecheck clean (framework simulator
  slice relinked so the typecheck sees the new seams; iOS unit tests
  still not runnable — no simulator runtime on this machine; the new
  `testMemeStudioSeams`/`testMemeEditorStoreEditingLoop` XCTest cases
  parse clean and await the runtime). Next: MST-019 wire codec (interop
  gate), MST-016 export, MST-017 kind-20 publish, MST-018 autosave.

- 2026-09-02 — Meme Studio M0: shared foundations + hub honesty (plan
  `docs/native/meme-studio-plan.md`; APP-019 MST-001..005, both platforms).
  (1) SHARED `core/studio/`: `MemeProject` schema v1 (versioned,
  size-bounded, mode-dependent asset caps 9/60/1, normalized canvas coords
  so projects load on both platforms) + `MemeProjectContract`
  encode/decode with hostile-clamp battery; `MemeRules` — 16-color web
  palette, font slots, deterministic overlay CRUD/hit-test (top-most,
  rotation-ignored estimate), `MemeCommand` undo model with drag
  coalescing (EDT-004); `MemeCommandCodec` JSON wire; 
  `StudioPublishContract` render→upload→verify→sign→publish machine where
  signing is structurally unreachable without a verified upload (safety
  rule enforced by the state machine, not by caller discipline).
  (2) BRIDGE: `memeProjectNormalize/memeApplyCommand/memeHitTest` — the
  Swift editor (M1) works on the wire without leaking Kotlin types;
  undecodable commands are no-ops, corrupt projects normalize to "".
  (3) HUB UX: the five Create-hub rows no longer lie — Quick MEM/Use a
  sound/Remix render dimmed with "Soon" chips instead of dead-looking
  taps; icons moved onto the token layer (Android SolarFeedIcon
  VideoCamera/Gallery/Emoji + new AppIcons MusicNote/Remix; iOS
  AppIcons camera/photo/emoji/sparkles — was raw SF Symbols); the iOS
  Home app bar gains the camera button opening the Create hub
  (Android parity — iOS previously had no Home→Create path).
  Quick MEM activates with M1. Verified: business-core common tests
  green on macosArm64 (956, incl. new studio/bridge suites; the
  iosSimulatorArm64 target cannot run on this machine — no simulator
  runtime); full Android unit-test suite green; whole-app Swift 6
  typecheck clean.

- 2026-09-02 — Comment reply bar: Solar tokens unified with the composer
  + text-insert parity (user request; SOC-002/APP-008, both platforms).
  (1) ICONS: the reply bar's action row used different icons than the
  composer toolbar for identical actions — Android painted Material
  glyphs (PhotoLibrary/Gif/Public/QrCode2) where the composer paints
  Solar assets, and iOS used `globe` for the URL action and `qrCode` for
  PoW where the composer uses `link`/`shieldCheck`. New `SolarFeedIcon`
  entries (Gallery/VideoCamera/LinkCircle/Film/Chart/ShieldCheck/
  EyeClosed/Hashtag/Emoji) now back BOTH the Android composer toolbar
  (was raw `painterResource(R.drawable.solar_*)` — bypassed the token
  layer) and the reply bar; iOS reply bar moved onto the composer's
  `link`/`shieldCheck` tokens. PoW audited: present and gated to the
  kind-1 reply path on both platforms — no functional gap. (2) MISSING
  FEATURES vs the composer: hashtag + quick-emoji inserts were
  composer-only; the reply bar gained both (shared `ComposerRules
  insertHashtag/insertEmoji` / bridge `composerInsert*`; iOS reuses the
  composer's `composerEmojis()` grid sheet). Inserts land at the end of
  the reply — the pill field has no cursor tracking. (3) TEST
  HARDENING: `FakeRelayTransport.sent` (FeedRepository/NotePublisher
  tests) was a plain ArrayList appended by repository coroutines while
  tests iterated it — intermittent ConcurrentModificationException
  observed once in the full suite; now a CopyOnWriteArrayList
  (snapshot iterators). Verified: full Android suite green twice
  consecutively; whole-app Swift 6 typecheck clean.

- 2026-09-02 — Comment thread sheet: rich bodies + media + icon tokens
  (user request; SOC-002/APP-009, both platforms). (1) FUNCTIONAL: the
  comment list rendered plain `Text` — NIP-27 entities in comments were
  inert and attached media (gallery/GIF/URL attachments the reply bar
  itself produces) were invisible. Root card + reply rows now render the
  shared NIP-27 rich body (mentions resolve to @display-name and tap →
  the author profile sheet; external links → the confirm sheet, never an
  unattended browser) with bare media links hidden from the text and
  rendered as `MediaRow`/`MediaGrid` tiles → zoomable lightbox
  (feed-card parity, honoring the media-preview setting on iOS).
  (2) ICON TOKENS: iOS root card's delete was raw `trash` and its ⋯ a
  literal text glyph — now `AppIcons.delete`/`AppIcons.more` (Solar);
  the reply-bar GIF button dropped its text glyph for the composer's
  `AppIcons.gifFilm` Solar token; the orphan indicator moved onto a new
  `AppIcons.branch` token; Android's root-card ⋯ text glyph →
  `AppIcons.More`. Verified: full Android unit-test suite green;
  whole-app Swift 6 typecheck clean.

- 2026-09-02 — Author profile sheet: design-system icons + missing
  moderation actions (user request; APP-005/APP-013, both platforms).
  (1) ICON TOKENS: the sheet bypassed the `AppIcons` facade with raw
  platform icons — Android used `Icons.Rounded.CheckCircle/ContentCopy/
  Language/Photo/PlayCircle` directly and iOS rendered raw SF Symbols
  (`bolt.fill` via `Image(systemName:)`, `globe`, `play.circle.fill`,
  `photo`), skipping the bundled Solar assets. All routed through the
  token layer now: new `CheckCircle`/`PlayCircle`/`Link` tokens on
  Android `AppIcons` and a `playCircle` token on iOS (backed by
  `SolarPlayCircleLinear`); the feed card's NIP-05 badge moved onto the
  same token. (2) FUNCTIONAL PARITY WITH THE FULL PROFILE PAGE: the
  sheet had no ⋯ actions — both platforms gained the banner ⋯ menu with
  copy profile link (njump.me/npub) · copy npub · copy lightning, then
  mute/unmute author + report user (destructive, kind-1984 with reason
  dialog; hidden on your own profile) — identical entry set and
  semantics to the full pages' ProfileActionMenu menus. Verified: full
  Android unit-test suite green; whole-app Swift 6 typecheck clean.

- 2026-09-02 — Mention tap routing + Android mention-name fetch (user
  request; APP-005). (1) MENTION TAP OPENS THE MENTIONED USER (was a
  known simplification in the 2026-08-29 entry): profile-mention taps
  inside note bodies discarded the entity hex and opened the note
  author's profile. Renderers already delivered the hex — the card layer
  dropped it. Both platforms gained an `onOpenMentionProfile` seam:
  Android `FeedNoteCard` → `NotesList`/FeedScreen (author bottom sheet),
  `AuthorProfileScreen` (full page via `onOpenAuthor`), `ProfileScreen`
  own page → `BitOSApp` `authorPageTarget`; iOS `NoteCardRow` +
  pager `TextNotePage`/`FeedPage` → `authorTarget` sheet, and the same
  fix mirrored to `BitzView` (Android Bitz already routed the hex
  correctly). (2) ANDROID MENTION-NAME FETCH (dead code revived): the
  2026-08-29 entry claimed both repositories auto-request mentioned
  pubkeys' kind-0 — iOS `FeedStore` did, but Android
  `FeedRepository.requestMentionProfiles` had zero callers. `absorbNote`
  now tokenizes content through the shared `Nip27` and enqueues PROFILE
  entities (batched ≤48, deduped by `requestedProfiles`). Covered by a
  new adapter test `mentionedProfilesAreRequestedWhenANoteAbsorbs`
  (publish → relay echo → absorb → asserts the batched kind-0 REQ
  carries the mentioned hex). Verified: full Android unit-test suite
  green; whole-app Swift 6 typecheck clean (no simulator runtimes
  installed on this machine — iOS unit tests not run).

- 2026-08-31 — Brand wordmark refresh (user-supplied masters
  `docs/logo-black.png` light / `docs/logo-white.png` dark). Assets:
  regenerated BOTH platforms from the masters with a shared geometry
  contract — canvas 3.396:1, ink box matched across variants (the white
  master ships faint edge bloom that inflated its ink box ~13% and made
  the two renders different sizes; trimmed at alpha ≥16 the letterforms
  are identical). iOS `Wordmark.imageset` now has proper 1x/2x/3x slots
  × light/dark; `SplashLogo` light slots point at the black art (were
  pinned to the dark files) and `SplashBackground` light appearance is
  #F4F7FB — the launch chain is themed light/dark again per spec §Boot
  splash, replacing the APP-023 dark-only interim. Android
  `bitos_branding` + `splash` density ladders regenerated (day black /
  night white); launch theming fixed TWO invisible-logo bugs: day launch
  drew the black wordmark on the #0A0A0F APP-023 floor, and pre-12 night
  launch tiled the light 1×1 `background.png` under the white wordmark —
  now day = #F4F7FB floor under black (values), night = #0A0A0F floor
  under white (values-night + drawable-night), v31 system-splash colors
  resolve day/night through one `@color/splash_background`; dead
  `background.png` + `splash_background_dark` removed. Surfaces: onboarding
  welcome heroes (both platforms) and Settings footers now show the
  official wordmark (theme-aware) instead of / above plain text. Found
  while verifying: the iOS pbxproj from c9a4308 had
  `DesignSystem/AccountSwitchOverlay.swift` in Sources but orphaned from
  its group (broke every CLI build) and `Features/Inbox/ChatsView.swift`
  on disk but missing from Sources — both repaired. Follow-up (user
  report: home logo must be black on light / white on dark): in-app
  wordmarks can NOT ride resource qualifiers — `drawable-night-*` follows
  the SYSTEM uiMode while the shell follows the persisted
  ThemeModeSetting, and the bolt's orange gradient rules out tinting, so
  Android gained `LocalBitOSIsDark` (provided by `BitOSTheme`) + a plain
  (non-qualified) `bitos_branding_dark` ladder (white art) + shared
  `BrandWordmark` composable used by the home header, onboarding hero and
  Settings footer; the night-qualified pair remains for the system-driven
  launch chain. Follow-up fix (user report, light theme on a dark system
  still showed the white wordmark): BOTH branches must use non-qualified
  drawables — the light branch had loaded `bitos_branding`, whose
  `drawable-night-*` resolution follows the SYSTEM uiMode, so
  forced-Light-on-dark-system got the night (white) art; added the plain
  `bitos_branding_light` ladder (black art) and the selector now picks
  `_light`/`_dark`, never the qualified pair. iOS needed no code change (`.preferredColorScheme` drives
  catalog appearance resolution) but both retained BootSplash components
  now pin the dark wordmark art — they force a dark floor, and catalog /
  qualifier resolution would follow the trait/system instead. Verified:
  Android assembleDebug green; full-app Swift 6 typecheck (project flags) 0
  errors. actool cannot run on this machine (no simulator runtimes; fails
  identically on HEAD assets), so catalog compilation itself remains
  unverified until runtimes are installed.

- 2026-08-31 — APP-008 composer page polish (user report, Android).
  Found: the shell Scaffold already pads the system bars, and
  CreateNoteScreen's own Scaffold/TopAppBar re-applied the status-bar
  inset — the double padding was the extra gap above the "Create Post"
  header (title also used `headlineMedium` instead of the M3 top-bar
  `titleLarge`). Fixed by zeroing the page's window insets (shell
  supplies them) + `titleLarge`. The shell tab bar no longer renders
  under the composer page (`bottomBar` gated on `!showCreateNote`).
  Toolbar moved to web Composer order (image · video · URL · GIF ·
  poll · PoW · CW · hashtag · emoji) on bundled Solar Linear drawables
  (icon-system.md path: gallery/video-library/link-circle/film-frame/
  chart/shield-check/eye-closed/hashtag/emoji-funny-circle — PoW drops
  the QR glyph and CW the speaker-off glyph for the web's shield-check
  and eye-off semantics). Video attach is new: `video/*` picker rides
  the existing Blossom upload-before-sign path with its real MIME,
  composer shows a play tile (no decode), and the feed already renders
  bare video links with the fullscreen player. Two follow-up bugs
  found in the same pass: the PoW sheet dismissed itself on open
  (PowCard's initial invalidation fires `onMined(null)`, which the
  page also treated as "close" — now only a completed mine closes it,
  the toolbar badge shows a Mined difficulty only, and any template
  edit voids a completed nonce outside the sheet, matching PowCard's
  one-template rule), and the poll composer rendered as a bare column
  outside any surface — it now presents in a ModalBottomSheet like the
  emoji/PoW/GIF sheets. Follow-up UX pass on the same surface (user
  ask): the PoW panel now rides INLINE under the input field (web
  `showPow` parity — the toolbar toggles it, a restored draft with a
  difficulty reopens it, no bottom sheet; a completed mine no longer
  needs to close anything), and PowCard's state rows weight their text
  so Mine/Retry/Cancel/Clear keep intrinsic width and never wrap; the
  exhausted state gained a warning glyph, the attempt count and the
  stuck target ("No nonce found after 5,000,000 hashes at N bits"),
  with locale-grouped hash counts in the mining row too. No
  shared-core change.

- 2026-08-31 — APP-008 composer parity pass mirrored to iOS (user ask:
  "you implement iOS and Android?"). The iOS page already had the two
  shell-level fixes for free (fullScreenCover hides the tab bar; inline
  nav title), so this pass ports the rest: toolbar now renders through
  `AppIcons.image(for:)` — seven new bundled Solar imagesets
  (hashtag/emoji-funny/eye-closed/chart/film/link-circle/shield-check,
  same official SVGs as Android) replace the raw SF Symbols and the
  text "GIF" glyph, in the web order with the QR→shield-check and
  speaker→eye-closed semantic swaps; video attach rides the existing
  PhotosPicker (a second `.videos` picker) with play tiles for local
  and remote video URLs; the PoW panel moved from a `.sheet` to INLINE
  under the field (toolbar toggle, restored drafts reopen it, `.id`
  keys the card to the mining template per its own contract, template
  edits void a completed nonce — iOS previously had no such void and
  could publish a stale nonce); PollComposerSheet Cancel now dismisses
  instead of tripping the publish validator with an empty question
  (spurious error banner); PowCard's exhausted state matches Android
  (warning glyph, grouped counts, "at N bits", fixedSize buttons).
  Verification note: the strict Swift-6 fallback gate was red on the
  in-flight APP-023 theme work — `BitOSTheme` color statics had landed
  as `static var` (the dynamic `UIColor` closure makes `let`
  behavior-identical) and `modeOverride` needed the set-once-at-launch
  `nonisolated(unsafe)` annotation; both fixed, gate now passes with
  zero errors. A later script run failed in the Gradle step on
  `Blossom.kt` (`ushr`/`and` unresolved, all three iOS Kotlin targets)
  — that file was being edited concurrently during this session and is
  not part of this change.

- 2026-08-31 — APP-017 zap wallet tile (both platforms; closes the last
  live-surface gap on the More hub). The wallet page itself (APP-014
  ZapsScreen/ZapsView) had shipped reachable only from the You page (⋯
  menu item + "Sats zapped" pill) while the More hub still rendered a
  disabled "Coming soon → Zap ledger" placeholder. Both hubs now expose
  a live Zap wallet tile in the Account group, directly under Profile
  (Android MoreScreen `onOpenZaps` → the existing `showZaps` overlay in
  BitOSApp; iOS MoreView `showZaps` fullScreenCover presenting ZapsView)
  and the placeholder section is removed. One wallet surface, three
  entry points (You menu, You stat pill, More tile) — no fork.

- 2026-08-30 — APP-009 thread states + ⋯ menu parity (both platforms;
  mock `app-10-note-thread`, closes the last §3.9 checkbox). SHARED: new
  `ThreadOpen` (classify: bech32 via `EventRefs.parse` + bare 64-char
  lowercase-hex fallback → `EventRef`) and `ThreadOpenCopy` (locked
  plate copy: loading/invalid/not-found titles+bodies, Retry, Add
  relay) — bridge `eventRefParse` now classifies through it (hex ids
  accepted) + 8 new `threadOpen*` getters; 4 common tests (hex accept,
  non-hex/non-bech32 reject, uppercase-hex reject, copy locked).
  ANDROID: `RefOpenPlate` (Loading/Invalid/NotFound) in FeedScreen —
  invalid never issues a REQ, loading polls `refNote` 20×150 ms then
  opens the sheet, not-found offers Retry + Add relay (applies nevent
  TLV hints through the new `relayManager` param). IOS: `RefOpenPlate`
  enum + alert in HomeView (same state machine, copy via `ThreadCopy`
  facade, Add relay through `environment.relayManager`); CommentSheet
  root-card ⋯ menu gains Share (`noteShareText` → `ShareSheet`) and
  Copy link (`njump.me/<id>`) matching Android. Deferred: [W] comment
  refresh control; live zap chip on root card rides APP-004 W2;
  orphan-chip wording ("orphan → top-level" vs current fallback label).
  Verified: shared androidHost green, Android compileDebugKotlin green,
  iOS xcframework rebuilt + SwiftUI typecheck clean.

- 2026-08-30 — APP-011 DM mock-parity wave (both platforms; mock
  `app-06-inbox-activity-messages`). SHARED: new `DmPresentation`
  (unread count, generic NIP-17 preview line, request acceptance with
  decline-wins, monotonic read cursor; 5 common tests) and bridge
  exposure `dmUnreadCount`/`dmPreviewLine`/`dmIsAccepted`/`dmNextCursor`
  (+ bridge contract tests) — iOS now consumes the same rulebook as
  Android instead of mirroring it in Swift (removed the interim
  `DmRules` enum that violated the shared-rules architecture). ANDROID:
  `DmRepository` grew read cursors (SharedPreferences port, bounded
  512), accept/decline requests, delivery tracking (relay-OK absorption
  → `· delivered`, 10 s watcher) and list/request partitioning; DmScreen
  got unread dots, generic previews, message-requests header, new-chat
  npub dialog, NIP-17 banners, ⚡ zap chip (→ ZapContent author zap) and
  profile routing; Chats shell badge (unread + requests, "9+" cap).
  IOS: `DmStore` rewritten around the bridge rules (cursors +
  UserDefaults bounded 512, accept/decline, OK-receipt delivery);
  `DmScreen` rebuilt to parity (avatar/name rows, unread dots, requests
  section, new-chat sheet with npub validation, chat header with E2EE
  line + ⚡ Zap → ZapSheet profile mode + avatar→AuthorProfileSheet,
  encryption banner, delivery suffixes); RootView gains the Chats badge
  and shell-level `dmStore.setAccount`; stale Wave-2 `ChatsView.swift`
  removed. Found+fixed en route: Kotlin Int bridges as Swift Int32
  (`Int(...)` clamp); `interestSetCreatedAt` returns `KotlinLong?`
  (HashtagFollowsStore — unblock via `int64Value`). Validation: shared
  `testAndroidHostTest` green; Android `compileDebugKotlin` green; iOS
  framework rebuilt + SwiftUI type-check clean. Next: attachments/
  reactions/calls/groups remain W2/W3 per checklist.

- 2026-08-29 — Comment sheet legacy-UI completion (user request, both
  platforms; layered on the reply-bar work that landed in f0fba90).
  (1) INTERACTIVE THREAD ACTIONS: every comment row now carries the
  legacy `_CommentActionButton` ghost row — Like (toggles, filled heart,
  " · N" trailing) · Zap (opens the ZapSheet stacked above the thread,
  " · sats" trailing) · Reply (retargets the reply bar) — and the root
  card's action row became the interactive `_ThreadActionRow` (like ·
  replies · zap · repost · bookmark with live tallies; ⋯ raw stays).
  Android wires them through CommentContent's new actions/onLike/
  onRepost/onBookmark/onZap seams; a shared CommentThreadSheet host
  renders comment + stacked zap sheets for Discover/Inbox/Bookmarks
  (Feed/Bitz reuse their existing zap chrome). iOS adds the same rows
  plus an in-sheet ZapSheet via AppEnvironment (like/repost/bookmark
  publish paths mirror HomeView). (2) SOLAR ICONS: new
  `solar_plain_linear` drawable (paper plane, converted from the iOS
  SolarPlainLinear SVG source) + `SolarFeedIcon.Send` — the Android
  reply-bar send now uses the Solar glyph (iOS already had it); all
  comment/thread actions render Solar heart/comment/zap/repost/
  bookmark. (3) LEGACY ROW CHROME: Android reply rows flattened to the
  `thread_view.dart` look — avatar 28 (22 sub-replies), bold display
  name + time, conversation rail for descendants, no per-row cards;
  iOS rows show the bold display name (was bare shortPubkey).
  (4) FOUND+FIXED mid-flight: shared BitzModeSetting grew TRENDING/
  ZAPPED while BitzScreen's mode `when` wasn't updated — branches added
  (no-op until those pills land); the Bitz swipe cycle stays pinned to
  the legacy Explore→Following→For-you order. Verified: Android
  compile + unit tests green, iOS Swift 6 typecheck 0 errors.

- 2026-08-29 — Feed card dividers + mention/note-ref functional (user
  request; APP-005). (1) DIVIDERS: hairline separators between feed cards
  on the Home list (0.5pt, inset past the avatar, none after the last
  item) — both platforms. (2) MENTION NAMES (web parity): profile
  entities (npub/nprofile) in note bodies now render as **@display-name**
  once the author's kind-0 metadata resolves (falls back to the shortened
  raw entity until then); the repositories auto-request mentioned
  pubkeys' profiles when a note arrives (bounded ≤48); renderers gained
  a `resolveMentionName` seam on both platforms. (3) NOTE-REF OPEN
  (web parity): tapping a note1/nevent1/naddr1 entity inside any note
  body now opens that note's thread **in-place** (bottom sheet) instead
  of doing nothing — the repository fetches the head by id or NIP-33
  coordinate (single-id/coordinate REQ), the screen polls the bounded
  side-store (3 s), then shows the thread sheet; non-profile entities
  route through the new `onOpenNoteRef` renderer seam (profile entities
  still open the author sheet). Android: FeedRepository
  `openNoteReference`/`refNote`/`requestMentionProfiles` + FeedScreen
  `refOpenTarget` polling; iOS: FeedStore mirrors + HomeView `.task(id:)`
  polling. Verified: Android compile + 55/55, iOS typecheck 0 errors in
  all touched files (concurrent session's Dm/Discover/ProfileEditSheet
  in flight).

- 2026-08-29 — Own-profile completion parity: restored the legacy
  “Complete your profile” card on iOS and Android. Its count and field list
  derive from the published kind-0 values (display name, bio, picture,
  banner, NIP-05, Lightning address, website), and Finish opens the existing
  functional profile editor rather than presenting inert checklist items.

- 2026-08-29 — Profile metadata relay-fidelity fix: kind-0 `banner` and
  `website` were accepted at publish time but mistakenly discarded by the
  shared projection and iOS bridge. Both values now survive verified relay
  parsing, with a common regression assertion; native profile covers and
  website links can therefore render the metadata users actually publish.

- 2026-08-29 — APP-006 iOS stories parity (bar + viewer; shared rules
  from the previous increment). Bridge (XCFramework rebuilt):
  `storiesRequest` (kinds 30315+5 for account+following), `storyFromFrame`
  (verified frame → slide map through shared `Stories.parseSlide`).
  iOS: `StoriesStore` (subscribe/ingest/seen via UserDefaults
  `bitos_story_seen_ids`, author grouping in the mirror), `StoriesBarView`
  (gradient hex ring unseen / muted seen, avatar + short-pubkey) +
  `StoryViewerView` (full-screen, per-slide progress bars, auto-advance
  5 s, tap left/right, gradient background parsed from `#hex>to>#hex`)
  wired into HomeView's VStack (non-video only) + fullScreenCover viewer;
  AppEnvironment.storiesStore + account wiring. Both registered in
  pbxproj. Also rescued the concurrent session's in-flight breaks:
  RichTextView `resolveMentionName`/`onOpenNoteRef` params,
  NoteCardRow param ordering (optional vars before required lets →
  reversed), notesList type-checker timeout (extracted `noteCardRow`
  helper — § pagerPage fix class), DiscoverView PersonRow picture,
  FeedStore Int32 + Dict.Values.last, NoteComposerTest String.format
  (common Kotlin). Verified: shared androidHost + native 352/352,
  Android compile + 56/56, iOS Swift 6 typecheck 0 errors, structure.

- 2026-08-29 — Own-profile hero completion: the signed-in You surface now
  renders the legacy full-bleed cover without outer shell padding or title
  chrome, keeps the centered lifted hex avatar, and exposes functional Edit
  profile and identity-QR actions. The existing kind-0 editor remains the
  source of truth; visible profile details now include NIP-05 and a safe
  HTTP(S) website link.

- 2026-08-29 — Sheet chrome + Bitz swipe pack (user request, both
  platforms). (1) CLOSE = ICON (user decision 2026-08-29): every bottom
  sheet / modal overlay dismiss affordance labelled "Close" became an
  icon-only ✕ button with a11y label — new shared components
  Android `ui/components/SheetCloseIcon` and iOS `SheetCloseButton`
  (DesignSystem/AppMenu.swift); applied to author-profile, comments,
  zap, composer, import-media, profile-edit, remix-chain, donate sheets
  plus the zap-wallet/saved/static full-screen overlays (iOS toolbar
  cancellation actions included; text Open/Cancel stays in confirm
  dialogs per platform convention). (2) SHEET LAYOUT POLISH: Android
  AuthorProfileContent + CommentContent now size to 85% of the sheet
  (weight-driven lists instead of fixed 360/320 dp) and the comment
  composer rides the keyboard (imePadding). (3) BITZ HORIZONTAL SWIPE
  (TikTok / legacy Flutter `bitz_view.dart` parity): left swipe cycles
  Explore → Following → For you; the FINAL left swipe on For you opens
  the settled reel's creator profile sheet (haptic tick); right swipe
  steps back; active on the player media layer AND the explore grid;
  suppressed while comments/zap/chain/author sheets, remix-ask or the
  search overlay are up. Android: accumulated-drag threshold 72 dp
  (detectHorizontalDragGestures alongside the tap layer — vertical
  pager + scrubber untouched). iOS: simultaneous DragGesture filtered to
  horizontal-dominant (|dx|>60pt, |dx|>1.5·|dy|) so paging, taps and
  long-press 2× still work. (4) FOUND+FIXED: Android Bitz rail rendered
  the Save button TWICE (duplicate block) — removed; iOS project.pbxproj
  never parented BitosTextField/ZapsView/SentZapsStore/ComposerScreen
  into the BitOS group (build input files not found) — group children
  fixed; iOS AppEnvironment default arguments (bootRelayPool/
  defaultEventStore) evaluated nonisolated under Swift 6 — defaults now
  resolve inside the MainActor init body. Verified: Android compile
  (compileDebugKotlin) clean, iOS Swift 6 typecheck 0 errors (full
  xcodebuild blocked locally by actool needing a simulator runtime;
  device-SDK link untested on this machine).

- 2026-08-29 — Media-link + link-safety pack (user request, both
  platforms). (1) HIDE MEDIA LINKS COMPLETED: bare image/video URLs in
  note bodies now disappear from the rendered text (hiddenMediaUrls on
  RichText/RichTextView, filtered against the shared FeedNote.mediaUrls
  projection) while their tiles render below — web parity. (2) BARE VIDEO
  LINKS RENDER: MediaRow/MediaGrid tiles gained video-URL detection
  (mp4/webm/mov/m4v) — play-glyph tile → FULLSCREEN single-file player
  (Android: Dialog + ExoPlayer, released on close; iOS: fullScreenCover +
  AVPlayer via VideoPlayer, paused+nil'd on disappear). (3) POPOVER
  ROUNDING: dropdown rows get rounded containers (Android
  DropdownMenuItem contentPadding + the sheet row's rounded bg; iOS
  AppMenu card rows now carry their own rounded-10 row backgrounds with
  6pt inset — web MenuItem rounded-lg parity). (4) EXTERNAL-LINK CONFIRM
  (user decision): tapping any external link in a feed card or Bitz
  caption opens a bottom sheet ("Open external link?" + mono URL +
  "This link leaves BitOS." + filled Open / Cancel) — the browser opens
  ONLY on explicit confirm; implemented per-surface (FeedScreen,
  BitzVideoPage Android; HomeView, BitzView iOS) via the new
  onOpenExternalLink/onOpenLink renderer seams (null/default still opens
  directly for other call sites); iOS new ExternalLinkConfirmSheet
  (pbxproj 13H/23H). Verified: Android compile + 55/55 (errors at close
  are the concurrent session's in-flight ProfileEditSheet); iOS
  typecheck 0 errors in all touched files.

- 2026-08-29 — APP-006 stories shared core + Android bar/viewer
  (concurrent session active — checked before starting; zero file
  overlap: they own UX polish/Bitz/profile, this owns stories).
  Shared: `Stories` — slide parsing (NIP-38 kind-30315, NIP-40
  `expiration` tag → TTL default 24h, future-dated ±10 min guard,
  image-URL/gradient extraction, PoW nonce), insertion rules (same-id
  newer replaces, parameterized-replaceable `d` tag newer replaces,
  per-author ≤12 slides newest-first), author grouping (expired pruned,
  latest-first) — 7 common tests. Android: `StoriesRepository`
  (subscribe kinds 30315+5 for account+following, verified ingest,
  kind-5 e-tag deletion, seen persistence via SharedPreferences
  `bitos_stories.seen_ids`), `StoriesBar` (gradient hex ring unseen /
  muted ring seen, avatar + short-pubkey label) + `StoryViewer`
  (full-screen, per-slide progress bars, auto-advance 5 s, tap
  left/right = prev/next, gradient background from the `#hex>to>#hex`
  token, header with close) wired as top overlay in the Home surface
  (non-video only); composition-root wiring through
  BitOsApplication→MainActivity→BitOSApp→FeedScreen. iOS stories +
  composer remain (next increment). Verified: shared androidHost +
  native 345/345 (18 grown by concurrent work), Android compile +
  55/55, iOS Swift 6 typecheck 0 errors, structure check.

- 2026-08-29 — Home author identity affordance: avatar and display-name
  targets now both open the native author-profile bottom sheet on iOS and
  Android. Android's sheet now receives its pubkey directly, so it renders a
  loading state immediately and reliably issues the verified kind-0/profile
  request instead of waiting on empty repository state; returned metadata
  fills the name, NIP-05, bio and Lightning address before the note window.

- 2026-08-29 — Feed/Bitz UX fix pack (user report, 5 items). (1) LINKS:
  Bitz captions were PLAIN Text — now the shared NIP-27 rich renderer
  (white tokens, profile entities → author sheet, external links tappable)
  on both platforms; RichText/RichTextView gained a color override
  (video captions render white) + line limit; FOUND+FIXED: Android
  external links were styled but INERT (empty listener) — they now open
  the system browser (iOS already did via .systemAction). External-link
  EMBEDS (og:image cards / YouTube players) remain web-only (iframe-based
  in the legacy client — not portable without a WebView media pipeline;
  tracked as V1.x candidate). (2) ACTION ORDER (user decision): feed card
  + Bitz rail now [like · comment · repost · zap · bookmark] (Remix/Chain
  first on Bitz); both surfaces, both platforms. (3) AUTHOR SHEET CHECK:
  wiring verified functional (open→fetch→follow→notes; iOS onAppear/
  onDisappear lifecycle correct); removed a dead no-op LaunchedEffect in
  Android AuthorProfileContent (comparison result discarded — follow state
  already reads feedState directly). (4) BITZ CLEAN CHROME (user decision
  2026-08-29): "New note" FAB REMOVED (creation = header record + feed
  FAB); the black scrim behind captions REMOVED; the compact controls'
  black strip REMOVED; the top bar background REMOVED (icons float);
  (5) MODE PILLS → BARE TEXT tabs (legacy Flutter TikTok parity): active
  bold opaque white, inactive 70%, no capsule/background/border.
  Verified: Android compile + 55/55, iOS typecheck 0 errors in all
  touched files (concurrent session's DMs/profile-editor still in flight).

- 2026-08-29 — ⋯ overflow menus → legacy-parity bottom sheets (user
  request; APP-005/APP-007). LEGACY AUDIT (web PostCard/Bitz popovers +
  Flutter app_bottom_sheet_menu.dart): web is the item superset (Share ·
  Save · Copy web/nostr link · Copy ID/text/npub · View raw · Not
  interested · show-less author/tags · Hide · Delete/Block/Report);
  Flutter chrome = drag handle + 28° radius + centered bold title +
  48 dp rows with 12° rounded ripple + bare 18 dp icon + red destructive.
  NATIVE (both platforms): AppBottomSheetMenu upgraded to that chrome
  (centered bold title, minHeight-48 rows, rounded-12 press, bare icons,
  destructive tint); the Bitz rail ⋯ and the feed-card ⋯ now open the
  SHEET (title "Bitz actions" / "Post actions") instead of the anchored
  dropdown; the rail trigger is ICON-ONLY (no "More options" label — iOS
  label was already a11y-only); item set = Share (system sheet via
  NoteShare) · Save/Unsave (toggle) · Copy note ID · Copy note text ·
  Copy author npub (npub-encoded) · Mute/Unmute · Report spam/illicit/
  harassment (destructive, confirm-gated by kind-56 flow). FeedScreen's
  dead no-op rail Share button (audit §9.4-3 leftover on the text page)
  removed — Share lives in the sheet. Home's content-filter trigger stays
  a popover (single-select, anchored — spec §3.4). NOT PORTED (tracked):
  Message author (DMs W2 — concurrent session in flight), Copy web/nostr
  link split (no canonical web viewer), View raw note (needs raw-event
  access on the feed path), Not interested / per-tag show-less (needs
  interaction-profile infra), Hide note, Delete own (kind-5), Block from
  menu. Verified: Android compile + 55/55, iOS typecheck 0 errors in all
  touched files (concurrent session's DmStore/ProfileEditSheet in flight).

- 2026-08-29 — T16 ✅ INBOUND DEEP LINKS (spec §1.2; audit §9.6 T17-class
  item). SHARED: new `nostr/DeepLink.kt` — `DeepLinks.classify(uri)` →
  Author (npub + nprofile via a new `EventRefs.tlvPubkey` type-0 reader) /
  Note (note1/nevent1/naddr1 ± `nostr:`, validated by EventRefs before
  routing — hostile ids never reach a relay REQ) / Lightning (invoice URI,
  bounded ≤2 KB); bridge `deepLinkJson` seam; DeepLinkTest (5) green on
  both lanes. ANDROID: manifest gained `nostr` + `lightning`
  BROWSABLE/DEFAULT VIEW intent-filters; MainActivity captures cold-start
  + onNewIntent URIs into a pending state; BitOSApp routes — Author →
  author sheet (AuthorProfileContent over the shell), Note → Discover tab
  with the reference as ref-search (existing head-fetch + thread-sheet
  path reused wholesale), Lightning → display-only branded-QR dialog with
  copy + an honest "BitOS never sees the payment" note. iOS: Info.plist
  CFBundleURLTypes for both schemes; BitOSApp.onOpenURL classifies via the
  bridge; RootView routes identically (AuthorProfileSheet / DiscoverView
  ref-search / new LightningInvoiceSheet with CoreImage QR); pbxproj
  13G/23G registered. Verified: shared androidHost + macosArm64 green at
  my changes, Android compile + 55/55, full-app Swift 6 typecheck 0
  errors. NOTE: the concurrent session landed T12 static pages (iOS
  StaticPagesScreen) in parallel mid-verification and is now adding NIP-44
  crypto (Nip44.kt in flight) — the only Kotlin errors at session close
  are theirs, mid-edit. REMAIN from audit §9.6: T6 tab decision (user),
  T9/T10 thread+discover polish; concurrent session owns T12 + DMs (W2).

- 2026-08-29 — APP-007 ease-of-use pass (legacy Flutter Bitz UX audit →
  gap pack; spec §3.7 + legacy parity). SHARED: `BitzFormat` in Bitz.kt —
  compact rail-count labels (`_formatCount` 1.2K/1.2M parity) + zap total
  sats from summed millisats (`_compactBitSats`, null = hide); bridge
  `bitzFormatCount`/`bitzFormatSats`; +2 common tests, both lanes green.
  BOTH PLATFORMS: (1) RAIL COUNTS — like/repost/comment counts and zap
  sats replace the static labels when > 0 (live tallies + optimistic like;
  iOS gained empty-state handling for the player surface it was missing);
  (2) DOUBLE-TAP THIRDS (legacy parity) — left third −10 s with seek hint,
  center like + heart burst, right third +10 s (no small buttons to hit);
  (3) LONG-PRESS 2× fast-forward with the 2× pill (pool rate-override
  APIs: Android setRateOverride / iOS setRateBoost); (4) EMPTY-STATE CTAs
  — Following-empty gets "Explore Bitz" (filled) + "Refresh Bitz"
  (outlined), generic empty gets a filled Refresh (legacy copy parity);
  (5) EXPLORE-TILE like count next to zap sats. Verified: shared both
  lanes, Android compile + 55/55, iOS typecheck 0 errors in all touched
  files (concurrent session's in-flight StaticPagesScreen errors are
  theirs — T12 static pages landing in parallel). Deliberate deltas from
  legacy: rail order stays web-parity (earlier user decision), muted
  autoplay default stays (earlier user decision), transport bar stays
  always-visible (legacy auto-hides after 3.5 s — noted as follow-up),
  horizontal mode-swipes and caption-expand not ported (tracked).

- 2026-08-29 — T4 RESOLVED (audit §9.4-2): the capture pipeline is
  reachable again on BOTH platforms — the record → trim → export → publish
  flow (CameraScreen + VideoPreviewScreen, CAP/PUB) had been orphaned when
  the composer-FAB rework unmounted CreateScreen/CreateView. ENTRY POINTS:
  Bitz glass top bar gained the spec §3.7 record (camera) button → the
  Create hub; Android's Home app-bar gained the camera icon (iOS parity —
  Android's import was reachable only through the dead hub); CreateScreen
  gained a Done affordance (it had no way out). FLOW FIXES found while
  wiring: Android's camera completion returned to the chooser WITHOUT the
  caption/publish step — captured takes now flow into the import sheet
  (showImport after mediaCaptured); iOS's capturedData was stored but never
  handed to ImportMediaSheet — the sheet gained capturedData/capturedMime
  seeding (opens straight into caption/publish; reset on close). Verified:
  Android full rerun compile + 55/55, full-app Swift 6 typecheck 0 errors.
  Audit §9.6 T1–T5 now all closed except T5's ComposerSheet deletions and
  T6 (tab decision — user). NEXT: T12 static pages, T16 deep links, or
  T10/T9 thread+discover polish.

- 2026-08-29 — APP-015 ✅ COMPLETE (audit T7; no shared-core change — pure
  plumbing over the already-tested NIP-51 toggle). BOTH PLATFORMS: new
  Saved page (Android `ui/bookmarks/BookmarksScreen.kt` overlay from the
  More hub; iOS `Features/Bookmarks/BookmarksView.swift` fullScreenCover
  from MoreView, pbxproj 13F/23F) — compact rows (avatar + name/NIP-05 +
  time + 2-line excerpt), newest-saved first, per-row unbookmark (existing
  optimistic toggle + 30003 publish), empty state, pending-rows spinner,
  row tap → thread sheet, relay re-fetch on OPEN (≤100 ids REQ for saved
  notes outside the feed window; Android FeedRepository `loadBookmarked` +
  `bookmarkedNotes` state merge [side map + window lookup], iOS FeedStore
  mirror via the shared `eventsByIdsRequest`). The More hub "Saved" tile
  moved from disabled coming-soon into a live Library group (spec §3.17
  shape). FOUND+FIXED: pbxproj ID collision — the concurrent session
  allocated my BitzView 13D/23D to their BitosTextField; re-allocated
  BitzView to 13E/23E (the exact orphaning corruption class the delivery
  plan warns about). Verified: Android compile + 55/55, full-app Swift 6
  typecheck 0 errors, pbxproj plutil OK. Follow-up noted in APP-015: repo
  contract test for the by-id fill path. NEXT per audit §9.6: T4
  camera-pipeline re-wiring (capture code is unreachable on both
  platforms), or T12 static pages, or T16 deep links.

- 2026-08-29 — APP-007 Chain sheet shipped (last portable §3.7 item from
  the web audit). SHARED: `RemixChain` in `feed/Remix.kt` — suspend walk
  over the shared `RemixRules.sourceOf` step (cycle-safe visited-set incl.
  self-loops, hex64 gate so hostile ids never reach a relay REQ, ≤32 cap
  with a truncated flag, missing parent = natural end — web `remixChainOf`
  parity); bridge gained `remixSourceOfTags` (packed `id|pubkey` seam for
  the Swift walk). RemixTest +4 chain vectors (ancestry/depths, natural
  end, cycles + self-loop, cap + non-hex rejection) — both lanes green.
  ANDROID: FeedRepository keeps a bounded (48) ancestor side-store fed by
  absorbNote + `loadRemixChain` (per-hop single-id REQ `{"ids":[id]}` +
  3 s await) and `remixAncestorNote` for row tap-through; HomeViewModel
  exposes `remixChainState` (one walk at a time); BitzScreen rail shows
  the web-conditional Chain button (only when remixOfEventId != null) and
  a bottom sheet with the ancestor list (avatar + name + "Direct source"/
  "N steps back" + Source pill, 8-per-Show-more paging, truncated note,
  loading/cycle/empty states; row tap → that ancestor's thread).
  iOS: FeedStore mirrors the walk (threadRootRequestById per hop +
  ancestor side-store + `remixAncestorNote`), BitzView Chain button +
  BitzChainSheet (same states/paging; row tap → CommentSheet). Verified:
  shared androidHost + macosArm64 green, Android compile + 55/55, full-app
  Swift 6 typecheck 0 errors. REMAIN for APP-007: meme-tag layout payload
  on remix publish (studio W4), remix relay hints, hexagon chip styling
  (web `.reel-action` cosmetics).

- 2026-08-28 — APP-007 rail/header follow-up (user decisions: no logo, no
  refresh button; refresh via bottom bar; web-parity rail + Remix). SHARED:
  new `feed/Remix.kt` — `RemixRules` parses/composes the exact legacy-web
  wire (`["remix", <id>, ≤3 relay hints]`, p-tag attribution,
  `["attribution","remix of …"]` ≤140, `bitz:edge` graph form, license set
  CC0/CC-BY/CC-BY-NC/bitz:* with ASK-ONLY gating), `mergeTags`/`mergeTagsJson`
  dedupe (name+param) for seeding composers; FeedNote projects
  remixOfEventId/remixOfPubkey/license; bridge exposes remixRequiresAsk/
  remixTagsJson/remixAttributionTagJson/mergeTagsJson + Note remix fields.
  RemixTest (8) green on both lanes. WEB AUDIT (bitz/+page.svelte +
  meme/remix.ts): rail order = Remix (violet, first) · Chain (conditional)
  · Zap · Like · Comments · Repost · Save — Share lives in the ⋯ overflow;
  licenses never hide the action (advisory confirm); chain walk ≤32
  cycle-safe. BOTH PLATFORMS: top bar slimmed (wordmark + refresh REMOVED
  — pills + search only); rail reordered to the web order with a violet
  Remix button; Share moved into the ⋯ menu; Remix = advisory dialog on
  bitz/all-reserved + bitz/source-permission, then opens the composer
  SEEDED with remix/p/attribution tags (Android CreateNoteScreen gained
  initialText/baseTags params — seeded sessions never touch the user's
  draft; iOS ComposerScreen gained baseTagsJson with the same draft
  protection; publishes merge seed tags through the shared rule).
  ANDROID: long-press on the Home/Bitz bottom-bar items = force refresh
  (re-tap while at top also refreshes — existing tick); iOS re-tap covers
  it via the same-value selection binding (SwiftUI TabView has no
  long-press hook — noted honestly). iOS BitzView body split into
  stage2/stage2b/stage3 after a Swift 6 type-checker timeout (§ pagerPage
  fix class; co-edited live with the concurrent session). Verified:
  shared androidHost + macosArm64 green, Android compile + 55/55, full-app
  Swift 6 typecheck 0 errors. REMAIN: Chain lineage sheet (walk + paged
  ancestors — read model fully portable per the audit), meme-tag layout
  payload on remix publish (needs the studio editor, APP-019 W4), remix
  relay hints (currently empty — valid wire).

- 2026-08-28 — APP-007 Bitz surface completion (spec §3.7 core; shared-core
  first per the vertical-slice order). SHARED (schema 3→4 + rules): new
  `feed/Bitz.kt` — `BitzSearch` (400 ms debounce const, QUERY_MAX 64,
  bounded local matches, local-first id-deduped merge, blank-query idle
  guard), `BitzExplore` (24+18 paging math), `NoteShare` (bounded share
  copy); `MediaMetadata` gained imeta `duration` parse (1..14 400 s) +
  locale-free `formatDuration`; settings contract v4: `bitos_bitz_mode`
  (explore/following/for_you, default for_you) + `bitos_video_muted`
  (default MUTED — user decision, legacy web polite autoplay parity);
  bridge: Note.durationSeconds, snapshot fields, `bitzSearchResults`
  JSON seam, `noteShareText`, `formatDurationSeconds`, and the
  `settingsKeys()` list gained the new keys PLUS the previously-missing
  KEY_SENSITIVE_MEDIA (the audit-flaged gap). Tests: BitzTest (8),
  MediaMetadata duration/format vectors, SettingsTest v4 block —
  androidHost + macosArm64 lanes green. ANDROID: new
  `ui/bitz/BitzScreen.kt` owns the surface (glass top bar + pills +
  search/refresh, explore grid w/ skeletons + sensitive tiles + duration
  badges + zap counts, pager w/ double-tap like burst, scrubber + ±10 s +
  seek hint, compact mute, per-session sensitive gate, full-screen search
  overlay w/ splice-and-jump, copy-note-id menu, REAL Share via
  NoteShare+ACTION_SEND); `VideoPlayerPool` reworked to list-based
  reconciliation (fixes the W0 bug where filtered pagers resolved
  neighbors against the unfiltered window) + mutedProvider/seek/position;
  BitOSApp mounts BitzScreen for the BITZ tab (FeedScreen keeps the text
  Home; its videoOnly path is now unmounted — extraction follow-up).
  iOS: new `Features/Bitz/BitzView.swift` (pbxproj 13D/23D registered)
  mirroring the surface with .ultraThinMaterial glass, ShareLink,
  BitzSearchOverlay fullScreenCover; PlayerPool gained muted param +
  setMuted/seekBy/seekTo/positionMs/durationMs; SettingsStore +
  SettingsState extended (SettingsBitzMode, videoMuted) with adapter +
  bridge-rule tests; BusinessCoreClient maps durationSeconds; RootView
  mounts BitzView AND now passes retapTick to HomeView (audit 9.4-4
  dormant-feature fix) with a same-value selection binding for re-taps.
  Also fixed `scripts/ios-build.sh` typecheck fallback pointing at the
  device slice instead of `ios-arm64_x86_64-simulator` (no-simulator
  machines got a false "no such module BusinessCore"). Folded concurrent
  session's in-flight work (ZapSheet paid-zap wiring, SentZapsStore,
  ZapsView, SupportDonateSheet — one transient OptIn race in
  SettingsScreen resolved). Verified: shared androidHost + macosArm64
  green, Android compile + 55/55 unit tests, full-app Swift 6 typecheck
  0 errors, pbxproj plutil OK. REMAIN (spec §3.7): comments-sheet
  two-level upgrade (iOS tree render = audit T10), record entry → camera
  (T4), imeta fallback renditions + player fallback chain, like-count
  tiles (needs a NoteActivity producer), W2/W3 chips/tabs.

- 2026-08-28 — Full-code parity audit of both native apps vs the unified
  spec and BOTH legacy apps (Flutter GetX app + web SvelteKit app,
  explored read-only). Results recorded as new **spec §9** (audit
  snapshot: route status, per-surface progress, settings field gaps,
  integrity findings, prioritized next-task queue). Score: 4/24 surfaces
  complete, 13 partial, 7 not started; platforms are at parity with each
  other nearly everywhere. Corrections to this ledger found by the audit:
  APP-019 camera/trim/publish is currently UNREACHABLE (CreateScreen
  orphaned on both platforms by the composer-FAB rework; iOS import sheet
  still reachable, Android's is not); APP-003 iOS re-tap-to-top is
  dormant (HomeView.retapTick never passed by RootView); APP-009 iOS
  assembles the thread tree but renders the flat list; Android
  ProfileEditSheet never prefills nip05/lud16 (callers pass empty
  strings); Share buttons on both video rails are no-ops; inbound
  nostr:/lightning: deep links absent on both platforms; Discover renders
  as a sheet/hidden destination, not the sixth tab of spec §1.1 (user
  decision needed). Also logged: which legacy "features" were stubs
  (Flutter communities NIP-29 TODO, dead language picker, stub help
  cards; web toast-only clear-cache, unenforced private-account) so they
  are not blind-ported. No code changed — audit + docs only
  (developer-guide.md also gained a stale-build troubleshooting section
  from today's crash-fix session).

- 2026-08-28 — APP-011 DMs end-to-end (both platforms; completes the
  W2 DM slice). Shared: `DmGrouping` — per-peer conversation grouping
  (sent messages under the recipient, newest-first conversations,
  chronological messages, ≤32 conv × ≤200 msgs) — 4 common tests.
  Bridge: `secureDmRequest` (kind-1059 #p REQ), `secureDmUnwrap` (frame
  → rumor map through the shared NIP-17 unwrap), `secureDmWrapResult`
  (flat map: rumor + wrap fields for iOS, no nested Kotlin event
  interop), `secureDmPublishMessage` (bridge Event → ["EVENT"] frame);
  XCFramework rebuilt. Android: `DmRepository` (subscribe 1059s →
  unwrap via SecureDmComposer → group via DmGrouping; `sendMessage`
  wraps to recipient + self-wrap for own-device sync, optimistic
  append; secret through SecureKeyStore) + `DmScreen` (conversation
  list → chat with encrypted bubbles: sent right/accent, received
  left/surface, chat header, BitosPlainTextField input + Send) wired
  into the Chats tab (BitOsApplication → MainActivity → BitOSApp).
  iOS: `DmStore` mirror through the bridge (unwrap via cached secret,
  publish via Event construction) + `DmScreen` (same conversation list
  → chat parity) wired into the Chats tab (AppEnvironment.dmStore,
  IdentityKeychain secret) — both registered in pbxproj. Also rescued
  the concurrent session's in-flight breaks: ProfileMetadata banner
  mirror, BitzView richJson scope. Verified: shared androidHost + native
  327/327, Android compile + 55/55, iOS Swift 6 typecheck 0 errors,
  structure check.

- 2026-08-28 — APP-011 shared core: NIP-44 v2 + NIP-17 Secure DMs
  (complete crypto layer, user rule: ALL business logic in shared core
  per docs/native/shared-business-core.md). Studied the legacy Flutter
  `nip44_v2.dart` + `nip17_secure_dm.dart` line by line.
  **NIP-44 v2** (`core/crypto/Nip44.kt`): conversation key (ECDH x-only
  via existing Secp256k1.liftX → HKDF-extract SHA-256 salt "nip44-v2"),
  message keys (HKDF-expand 76B → chachaKey32‖chachaNonce12‖hmacKey32),
  pure-Kotlin IETF ChaCha20 (RFC 8439), power-of-two padding (u16BE
  prefix, u32BE ≥64KiB, chunks 32→512), HMAC-SHA256 MAC (const-time
  compare), base64 payload 0x02‖nonce‖ct‖mac — 8 common tests (symmetric
  conversation keys, padding table incl. 65→96 (caught my wrong vector),
  ChaCha20 round-trip, encrypt→decrypt with unicode, tampered-payload
  MAC reject, wrong-key reject, deterministic HKDF). **NIP-17**
  (`core/publish/SecureDmComposer.kt`): rumor(kind 14, unsigned) →
  seal(kind 13, NIP-44 to recipient, sender-signed) → gift wrap(kind
  1059, NIP-44 with throwaway key, timestamp randomized ≤48h so relays
  can't correlate timing) — 5 common tests (full round-trip with
  structural checks incl. throwaway wrap key + wrap p-tag, wrong-key
  silent-null, randomized timestamps within the 48h window, unicode
  content, invalid-key null). Found+fixed: common-Kotlin has no
  `System.currentTimeMillis` → injectable `SecureDmClock`; `rotateLeft`
  is not infix in common → explicit `rotl`; `liftX` takes ULongArray not
  ByteArray. Verified: shared androidHost + native 323/323. Next: the
  native adapters (repo subscribe/persist) + conversation list + chat UI.

- 2026-08-28 — APP-020 static pages shipped (user request: ALL copy
  ported verbatim from the old Flutter app's i18n). Shared:
  `StaticPagesContent` — About (hero, "What is Nostr?", open-source, 6
  feature cards), Privacy (intro + summary + 11 numbered sections),
  Terms (intro + summary + 10 numbered sections) — every string
  extracted from `en_US.dart` and locked by 4 common tests (section
  counts, key phrases verbatim, length bounds); 16 bridge accessors
  (XCFramework rebuilt). Android: `StaticPagesScreen` (page switcher
  About/Privacy/Terms, section cards, summary cards highlighted in the
  primary container) wired as a full-screen overlay from the More hub's
  meta rows (BitOSApp state). iOS: same screen via the bridge
  (HeroCard/SectionCard, pbxproj registered) wired from MoreView's new
  About section (fullScreenCover). Verified: shared androidHost + native
  305/305, Android compile + 55/55, iOS Swift 6 typecheck 0 errors,
  structure check.

- 2026-08-28 — APP-002 onboarding carousel shipped (shared content
  first). Shared: `OnboardingContent` — the four legacy pages (welcome/
  keys/decentralized/zap) as a versioned contract with icon-token names
  the AppIcons facades resolve — 3 common tests (page order, copy
  verbatim, length bounds); bridge `onboardingContent` (XCFramework
  rebuilt). Android: `OnboardingScreen` (HorizontalPager + animated dot
  indicator 24↔8 dp spring, icon medallion, step counter, Next → Get
  Started, Skip) + `OnboardingPrefs` (SharedPreferences
  `bitos_onboarding.has_onboarded`); wired as a first-launch gate in
  BitOSApp before the shell. iOS: `OnboardingScreen` (TabView paging
  style, same dots/counter/CTA, per-page rise-in animation) +
  UserDefaults `bitos_has_onboarded`; RootView gates on it before the
  tabs (pbxproj registered). Verified: shared androidHost + native
  301/301, Android compile + tests green, iOS Swift 6 typecheck 0
  errors, structure check.

- 2026-09-04 — APP-010 Android Discover prototype-parity pass (idle home
  + Posts-tab chrome, no shared-core change). The idle page now follows
  the prototype `#/discover` home instead of the static topic list:
  recent-search chips (query recorded after a 600 ms settle so keystroke
  prefixes never pollute the row, deduped, cap 6, red Clear), Trending on
  Nostr as ONE card with hairline-divided rows (flame icon for the hot
  head, hash for the rest, chevron affordance; ranked by the shared
  `SearchResults.hashtags` fan-in over the live feed window, row tap →
  the same NIP-50 pipeline), a Creators-to-follow rail (people fan-in
  minus self and already-followed; 150 dp tiles, orange Follow/Following
  chip through the feed VM's contact list) and the Explore Bitz 3×3
  mosaic (standard NIP-68/71 media already riding the shared Home+Bitz
  window, content-warning tiles excluded unless revealed by setting,
  explore poster loader + "▶ tally" scrim badge, tile → shared reels
  player in author mode via a new `onOpenBitzPlayer` shell hop).
  Sections hide while their window slice is empty — connecting plate
  until the first verified event, footnote after (no seed data).
  Posts tab: home-parity list chrome — full-bleed shared `FeedNoteCard`s
  with hairline dividers between (not after the last), creator/empty
  chrome keeps its inset. Seam fix: `sensitiveShowByDefault` /
  `defaultZapSats` / `onOpenExternalLink` were declared by
  DiscoverScreen but dropped at the SearchResults call — result cards
  now honor the shell's sensitive default, zap preset and link-confirm
  host. Hashtags tab picks re-run the search (was a no-op `/* router
  hop next */`) and the escaped `"#${'$'}{hit.tag}"` literal (rendered
  as text) is real interpolation again. Icons: `AppIcons.Flame` /
  `Hash` / `ChevronRight` added (material extended). Verified: Android
  compileDebugKotlin + testDebugUnitTest green.

- 2026-08-28 — APP-009 reply-row deltas (closes the live-deltas item
  end-to-end). Both repos now resolve the tally target through ANY
  known note in an open thread window (root or reply — the comment
  window membership check), so kind-7/6/9735 events targeting a reply
  tally for that reply's row, not just the thread head: Android
  `tallyTargetFor(eTaggedIds)` (first-matching e-tag; comment-window
  lookup), iOS `tallyTarget(for:)` (same rule, explicit self capture for
  Swift 6). Reply rows render the live deltas inline — reactions
  (heart+count) and zaps (bolt + "N · 21K" sats from the summed bolt11
  msat) on both platforms, shown only when non-zero (no fake zeros).
  No shared-core change (the NoteTally merge rule already covers per-
  note deltas; only the target-resolution scope widened — repo concern).
  Verified: shared androidHost + native 296/296, Android compile + 55/55
  unit tests, iOS Swift 6 typecheck 0 errors, structure check.

- 2026-08-28 — APP-010 results tabs shipped (Posts · People ·
  Hashtags; shared fan-in first). Shared: `SearchResults` — the pure
  tab projection (People: distinct authors ranked by result count,
  profile names + NIP-05, ≤24; Hashtags: top tags by occurrence,
  case-insensitive, alpha tiebreak, ≤16) — 3 common tests; bridge
  `searchPeopleJson`/`searchHashtagsJson` for iOS (XCFramework rebuilt).
  Android: DiscoverScreen gains the tab row with live counts — People
  tab (avatar, name, NIP-05 badge, "N notes", Follow/Following through
  the feed VM), Hashtags tab (ranked #tag rows, tap → re-search);
  thread-sheet plumbing moved into PostsTab (cleaned a smart-cast trap).
  iOS: DiscoverView mirror — same tab row, PeopleTab rows, HashtagsTab
  (tap re-searches); JSON assembly fixed from a bad `.map { "[$0]" } ??
  "[]"` pattern to explicit array join. Verified: shared androidHost +
  native 296/296, Android compile + 55/55, iOS Swift 6 typecheck
  0 errors, structure check.

- 2026-08-28 — APP-009 live deltas + root action row (shared rule
  first). Shared: `NoteTally`/`NoteTallies` — per-note merge rule for
  kind-7 reactions / kind-6 reposts / 9735 zaps (+ summed msat from the
  receipt bolt11), insertion-order eviction ≤32 targets — 3 common tests
  (the suite caught my own bad eviction assertion before it shipped);
  bridge `bolt11AmountMillisats` + the comments REQ widened to
  kinds [1,7,6,9735] (XCFramework rebuilt). Android: FeedRepository
  tallies live for open thread targets (reactions/reposts hook the
  collector, zap receipts merge msat); CommentSheet gained the ROOT CARD
  — author row + body + the full action row (reply count · like+count ·
  repost+count · zap "N · 21K" · ⋯ raw-note dialog) per spec §3.9.
  iOS: FeedStore mirrors (talliesBuffer + tallyTargets ≤32; zap msat via
  the bridge), CommentSheet gained the same ThreadRootCard (alert-based
  raw dialog). Verified: shared androidHost + native 293/293 (15 grown
  by concurrent work), Android compile + 55/55, iOS Swift 6 typecheck
  0 errors, structure check.

- 2026-08-28 — APP-022 text-field system (legacy Flutter
  `InputDecorationTheme` parity — user request). Studied the old app's
  app_theme.dart: filled surfaceElevated, radius 12, 1dp border (accent
  2dp focused), tertiary 14/w400 hint, lg+md padding. Shipped ONE
  component per platform: Android `BitosTextField`/`BitosPlainTextField`
  (filled+bordered / borderless inline variants; HintText enforces the
  legacy hintStyle) + iOS `BitosField` wrapper (same geometry; SwiftUI's
  TextFieldStyle protocol cannot observe focus, so a view wrapper
  composition). Adopted across the high-traffic surfaces: Discover
  search, composer body + CW + URL + poll fields, reply bar, zap custom
  amount + comment, notification search (iOS). Also rescued the
  concurrent session's in-flight breaks along the way: BitzView
  type-checker timeout (split body → decoratedRoot/stage2/stage2b/
  stage3 — four named stages, the largest chain split so far), duplicate
  remixOf args in bridgeNote (Kotlin ctor order), ComposerScreen
  onRemix/baseTagsJson arg order, AccountSwitchOverlay Color.Stop →
  Gradient.Stop. Verified: shared androidHost + native suites, Android
  compile + 55/55, full-app Swift 6 typecheck 0 errors, structure.

- 2026-08-28 — APP-014 LUD-21 settle polling (closes the legacy zap
  dialog's "first signal wins" parity; classification rule in shared
  core). Shared: `LnurlPay.Invoice` grew the LUD-21 `verify` URL
  (HTTPS-bound, parse + validation) and `LnurlPay.verifySettled(body)`
  (settled ONLY on status=OK && settled=true; corrupt bodies stay
  unsettled — the next poll retries); bridge `lnurlInvoiceVerifyUrl` +
  `lnurlVerifySettled` (XCFramework rebuilt). Android: LnurlPayClient
  returns the Invoice pair + `fetchVerifySettled`; the zap VM starts a
  3 s poll bounded by the bolt11 expiry after invoice creation
  (cancelled on the next round); the sheet's paid watcher now takes the
  FIRST signal of exact request-id receipt / verify settle / count
  fallback (legacy order). iOS: same through the bridge — the poll task
  lives in the ZapSheet (3 s ticks, expiry-bound, cancelled on
  disappear), `ZapUiState` carries verifyUrl/verifySettled and the paid
  watcher includes the settle signal; HTTP stays platform-side per the
  layering rules. Also fixed the concurrent SupportDonateSheet break from
  the Invoice return-type change. Verified: shared androidHost + native
  278/278, Android compile + 55/55, iOS Swift 6 typecheck 0 errors,
  structure check. APP-014 now fully at spec parity except NWC (W4).

- 2026-08-28 — APP-014 iOS zap ledger parity (closes the cross-platform
  slice; rules stayed in shared core). Bridge (XCFramework rebuilt):
  `sentZapsEncode/Decode` (records JSON ↔ versioned wire through the
  shared contract), `zapLedgerEntries` (sent+received merge → entries
  JSON), `zapLedgerTotals` ({received,sent,avg,net}), `embeddedZapRequestId`
  (verified 9735 frame → embedded 9734 canonical id; native-lane local
  4-tuple for the parallel-array parse). iOS: `SentZapsStore` (UserDefaults
  `bitos_sent_zaps`, records through the bridge wire; insert rule round-
  trips the shared contract), `FeedStore` retains verified request ids per
  target (`zapRequestIds`, bounded 16 targets × 8 ids), the ZapSheet paid
  watch upgraded to EXACT request-id matching (captures its own 9734 id at
  signing; unsigned/anonymous keeps the count fallback) and records the
  paid zap through `onPaid` → `AppEnvironment.sentZaps`, NEW `ZapsView`
  (legacy /zaps parity): stat tiles + All/Received/Sent tabs + ledger rows
  (±sats, memo, time), empty + sign-in states — entry = "⚡ Zap wallet"
  row on the You tab (pbxproj registered: SentZapsStore.swift +
  ZapsView.swift). Also unblocked the concurrent session's in-flight
  Android compile (BitzScreen scripted-edit clipboard syntax; SettingsScreen
  duplicate OptIn + missing file OptIn). Verified: shared androidHost +
  native 278/278 (11 grown by concurrent work), Android compile + 55/55,
  iOS Swift 6 typecheck 0 errors, structure check.

- 2026-08-28 — APP-014 sent-zap ledger + exact paid matching (studied the
  legacy `SentZapsStore`/`ZapsView` first; all rules in shared core).
  Shared: `SentZapLedger` — versioned v1 wire ({id,sats,to,at,note?,memo?},
  ≤200 records, ≤98k chars), insert rule (dedupe by 9734 request id,
  newest-first, bounded), lenient decode (corrupt/oversized → empty;
  invalid rows drop), display merge (local sent + parallel-array received
  from the verified 9735 stream, newest first) and totals
  (received/sent/avg/net); `ZapReceipt.embeddedRequestId` — the receipt's
  description 9734 verified through the client gate, returning its
  canonical id (the paid-matching key) — 6 common tests incl. a signed
  9734 fixture (found the fixture needed a real relay URL: the composer
  rejects empty relay lists). Android: `SentZapsStore` adapter
  (SharedPreferences `bitos_sent_zaps`); `FeedRepository` retains verified
  embedded request ids per target (bounded) in state.zapRequestIds; the
  zap sheet's paid detection upgraded from count-based to EXACT
  request-id match (anonymous/unsigned keeps the count fallback) and
  fires `onPaid` → `HomeViewModel.onZapPaid` records into the sent
  ledger (request id, sats, recipient, note, memo); NEW `ZapsScreen`
  (legacy /zaps parity): stat tiles (Received emphasized · Sent · Avg ·
  Net via shared totals) + All/Received/Sent tabs + ledger rows (zap
  glyph, avatar, name/memo, ±sats, time) + empty + sign-in states; entry
  = "⚡ Zap wallet" row on the You tab (BitOSApp overlay, store wired
  through the composition root). iOS ledger + its bridge surface scoped
  as the next increment. Verified: shared androidHost + native 267/267,
  Android compile + 55/55, iOS Swift 6 typecheck 0 errors, structure.

- 2026-08-28 — APP-014 zap sheet rebuilt to legacy parity (studied the
  old Flutter `zap_dialog.dart` line by line; every rule in shared core
  first). Shared: `ZapFormat` (presets 21/100/500/1000, YakiHonne tiers
  ⚡≤50/💜≤250/🔥≤750/🚀, compact K/M sats format) + `Bolt11.expirySeconds`
  (bech32 data-part TLV walk: timestamp + expiry, default 3600 — the
  countdown is now real, not decorative) — 6 common tests (synthetic
  invoices built with the internal bech32 encoder); bridge: `zapEmoji`,
  `zapFormatSats`, `bolt11ExpirySeconds`. Both platforms rebuilt the sheet:
  recipient header (avatar + name + copy-LN-address + close) → amount step
  (4 emoji tiles, custom sats ≤8 digits, comment ≤200, anonymous toggle /
  signed-out note, zap-colored CTA "⚡ Zap 21 sats") → invoice card (QR —
  zxing on Android, CoreImage on iOS; live mm:ss countdown turning amber
  <2 min; Open wallet deep link with copy fallback; short invoice; expired
  → New invoice) → paid (green ✓, amount+name, comment quote, countdown
  bar + 2.4 s auto-close; first signal = a verified 9735 for the note
  landing after the invoice — count-based, request-id match rides the
  ledger work, noted). Signed-out/anonymous skips the 9734 signature.
  Also rescued the concurrent session's new shared `QrCode` (GF(256)
  reduction bug xors 0x11D post-mask producing >255 values — fixed to
  0x1D; common-Kotlin `System.arraycopy` → loop; `toByteArray(Charsets)`
  → `encodeToByteArray`) — their QrCodeTest 5/5; payment QRs deliberately
  stay on zxing/CoreImage until the shared encoder's matrix tests hold.
  Verified: shared androidHost + native 261/261, Android compile + 55/55,
  iOS Swift 6 typecheck 0 errors, structure check.

- 2026-08-28 — APP-008 draft persistence + discard confirm (spec §3.8
  item closed; persisted schema → shared core first per repo rules).
  Shared: `ComposerDraftContract` — versioned v1 wire ({text, urls, cw,
  mentions[{n,u}], pow}), bounds at encode (text ≤16k via ComposerRules,
  ≤4 remote URLs ≤2k each, CW ≤120, ≤8 mentions, pow 0–30), lenient
  decode (corrupt/oversized/wrong-version → null = empty draft;
  over-long fields clamp) — 4 common tests (round-trip, hostile clamp,
  corrupt wire). Local gallery picks are deliberately NOT persisted
  (ephemeral URIs; the draft keeps remote URLs only). Bridge:
  `composerDraftEncode`/`composerDraftDecode` (XCFramework rebuilt).
  Android: `ComposerDraftStore` (SharedPreferences `bitos_composer_
  draft`) wired through MainActivity → BitOSApp → CreateNoteScreen —
  restore once on open, autosave on every field change, clear on
  publish/new post, and both close paths (back + ✕) confirm
  "Discard draft?" when content exists (BackHandler). iOS: same via
  UserDefaults + bridge in ComposerScreen (confirmationDialog on close,
  clear on publish/new post). Verified: shared androidHost + native
  250/250, Android compile + 55/55, iOS Swift 6 typecheck 0 errors,
  structure check. APP-008 remaining: GIF picker (web service),
  mine-at-publish for picks+PoW, vote-format decision for live bars.

- 2026-08-28 — APP-008 poll sheet + card display (shared-core first).
  Studied the legacy Flutter app first: its poll wire is COMPOSE-ONLY —
  kind-1 question + `["poll_option", <index>, <label>]` tags (web
  `feed.postPoll` parity), bounds 2–6 options ≤80 / question ≤280; the
  old app renders nothing and has NO vote wire, so voting/bars stay
  follow-up pending a vote-format decision (NIP-1 1071 is the candidate).
  Shared: `PollContract` (tolerant parse — kind check, ≥2 usable options,
  dedupe-by-index first-wins, sorted, hostile display bound ≤16, label
  trim; `pollTags` validates the compose bounds) + `NoteComposer
  .composePoll` (poll tags + hashtag t-tags via ComposerRules) +
  `FeedNote.poll` projection + bridge (`Note.pollOptions`, iOS
  `composePollTags`) — 5 common tests (parse/sort/dedupe/bound/invalid
  + composer round-trip; FeedNote now carries polls as cards). Both
  platforms: poll button in the composer toolbar → PollComposerSheet
  (question + 2–6 choice fields with live counters, add/remove) →
  publishes through the tags path (`publishNoteWith`/`publishNote`); feed
  cards render the poll (option rows, "Poll · N options" caption —
  honest V1 display, no fake votes). Verified: shared androidHost +
  native 246/246, Android compile + 55/55, iOS Swift 6 typecheck 0
  errors, structure check.

- NEXT SESSION (pick either; both scoped in this tracker):
  - **APP-018a audit rows 1–2** (user-named gaps): multi-account switcher
    (shared registry → native slots → switcher UI + overlay) and the
    14-field privacy store port (unify hideSensitiveMedia on one key).
  - APP-018a rows 4–7 quick pack: relays primary ⭐ + suggestions,
    event-cache wipe on clear-cache, zap presets 1/5, algorithm diversity
    + reset.
  - APP-009 threading remainder: naddr/nevent root resolution, X-style
    flattened descendants, live deltas on replies.
  - APP-010 results tabs (Posts/People/Hashtags) + trending mosaic.

- 2026-08-29 — APP-018a audit rows 1 + 5 closed out. Row 1 remainder: the
  branded switch overlay (shipped earlier in MoreScreen/MoreView) now
  covers the SETTINGS account switcher too — Android `AccountDetail` hoists
  `switchTarget` and rides `AccountSwitchOverlay` (haptics-gated), iOS
  `AccountSection` mirrors it via `.fullScreenCover(item:)` +
  `AccountSwitchOverlayView`; every switch surface now gets the brand beat
  (only the auth-screen one-tap rows remain for row 1). Row 5 iOS
  remainder: `FeedStore.clearDerivedState()` wipes the session's derived
  state (profiles, comment/thread views, tallies + buffers, zap buffers,
  remix ancestry, bookmark/block projections — live subscriptions
  re-fetch; mutes and feed windows stay) and the Settings clear-cache row
  calls it alongside `settings.clearCache()`; the persisted-event-cache
  equivalent arrives with DAT-003. Verified: iOS Swift 6 strict-concurrency
  typecheck exit 0; Android compile green for all touched files (the
  module's one remaining error is concurrent WIP: BitzScreen TRENDING/
  ZAPPED mode branches, in flight by the user at write time).

- 2026-08-29 — APP-009 reply bar, legacy `_ThreadReplyBar` parity (the
  composer stream's direct follow-on; reuses the APP-008 GIF picker).
  Shared: `NoteComposer.replyTags` — ALWAYS both NIP-10 markers
  (`['e',root,'','root']` + `['e',target,'','reply']`, root reply repeats
  the id), participant p-tags (target author + target's p-tags, 64-hex,
  deduped, ≤16 — hostile targets stay bounded), content entities/hashtags
  via `ComposerRules.deriveTags` merged per kind+value against markers and
  participants — 4 common tests (markers, participants+entities, dedupe+
  cap, invalid ids) + bridge `replyTagsJson` contract test (TagsCodec
  wire). Replies now ride the tags-aware note path (Android
  `publishNoteWith`/`publishPowNoteWith`, iOS `publishNote(tags:)`/
  `publishPowNote(tags:)`) with PoW mined over the byte-exact template;
  the old single-marker `publishReply` stays for other callers.
  Both platforms: reply bar in CommentSheet — reply-to chip on sub-replies
  (each reply row gained a Reply action that retargets the bar), NEW
  `AttachmentPreviewRow` shared component (spec §4: 64 dp tiles, cover
  thumbnails, video play surface, bottom-left GIF badge black/65,
  always-visible ✕), options row gallery · GIF · URL · PoW ("N bits"
  label), pill input (radius 20, 1–4 lines) + circular send with spinner;
  gallery picks upload hash-verified through Blossom BEFORE the URL joins
  the reply (legacy order); GIFs embed through the APP-008 picker (≤4
  attachments); the bar clears only on a successful ACK (legacy clears on
  success). Verified: shared androidHost green, Android
  compileDebugKotlin + 55/55 unit tests, iOS XCFramework rebuilt (bridge
  carries replyTagsJson) + Swift 6 strict-concurrency typecheck exit 0.
  Also completed small gaps in concurrent WIP so the lanes stay green:
  Android HomeViewModel `openNoteReference`/`refNote` delegation,
  RichContent mention smart-cast + one shared `isVideoMediaUrl` helper,
  iOS FeedStore ref-open Int32/`last` fix, HomeView argument order.

- 2026-08-29 — APP-008 composer UX-UI pass, old-app parity (studied the
  legacy Flutter `create_view`/`gif_picker_sheet` + web Composer). Shared:
  new `GifPickerContract` — Giphy request building (trending/search,
  RFC 3986 query encoding, pg rating, page 30), lenient response parse
  (preview fallback fixed_height_small → fixed_height → original; original
  → preview for the full URL; malformed entries dropped, never thrown),
  pagination math (returned offset+count; total_count decides hasMore),
  Recent merge (newest first, dedup by id, cap 12) and the versioned v1
  cache wire ({recent, trending{savedAt, items}}, 24 h TTL, 64 KB bound,
  lenient decode → null on corrupt) — 9 common tests + 1 bridge contract
  test (URL shapes, parse fallback/hostile, pagination, merge dedup/cap,
  wire round-trip/clamp, TTL window). Bridge grew `gifPickerUrl/Parse/
  Pagination/MergeRecent/DebounceMs` + `gifCacheEncode/Decode/Fresh`
  (stable item JSON `{"id","url","preview","w","h"}`). Both platforms:
  GIF toolbar button in the legacy order (image · URL · GIF · poll · PoW
  · CW · hashtag · emoji — Meme Studio awaits the studio phase) opening
  the picker sheet (search header w/ spinner, Recent/Trending pills,
  2-col preview grid w/ broken-image fallback, Load more, footer);
  media grid now renders REAL thumbnails (Android: picked Uri → bounds-
  sampled decode, remote URL → existing `RemoteBitmapImage` pipeline;
  iOS: UIImage from picked Data, `AsyncImage` for URLs) instead of
  placeholder icons; publish/upload errors moved to a tinted full-width
  banner above the upload strip (cleared on next edit, legacy parity);
  char counter reserves its width at 0 so the toolbar doesn't shift.
  Android composer field became selection-aware (`BitosPlainTextField`
  TextFieldValue overload): @-mention detection, toolbar inserts and
  mention picks now operate at the real caret (Compose exposes it;
  iOS keeps the documented end-of-text approximation). Verified: shared
  androidHost green, Android compileDebugKotlin + 55/55 unit tests, iOS
  XCFramework rebuilt + Swift 6 strict-concurrency typecheck.

- 2026-08-28 — APP-008 composer page, legacy-parity port (studied the
  old Flutter app `~/Desktop/bitos/bitos-nostr-flutter` `CreateView`/
  `CreateController` line by line; every rule landed in shared core
  first). Shared: new `ComposerRules` — 4,000-soft/16,000-hard counter
  (grouped label + target switch + near/over states, `MAX_NOTE_LENGTH`
  bumped 2,000→16,000, within codec 65,536 bound), cursor-preserving
  hashtag/emoji inserts (leading-space rules), @-mention autocomplete
  (query-at-cursor, ≤6 name-filtered suggestions with npub) + publish-time
  `nostr:npub` rewrite, media content join, and `deriveTags` (distinct
  lowercase hashtags, NIP-27 p/e + NEW naddr `a`-tag via the tokenizer's
  coordinate support, NIP-36 CW tag) — 9 common tests (the suite caught
  two of my own bad expectations: emoji BMP length + a fixture cursor);
  `composeTextNote` gained a tags overload and `composeTextNoteWithPow`
  mines/publishes over baseTags (nonce tag first). Android: NEW
  `CreateNoteScreen` — a full PAGE (FAB routes through BitOSApp, replacing
  the sheet): author header (feed profile), mention field with
  autocomplete overlay + tracked picks, ≤4 image grid (gallery picker +
  validated URL dialog, removable thumbs), CW field, upload-status strip
  (Blossom upload-before-sign, per-file progress), toolbar (image/URL/
  CW/hashtag/emoji-32/PoW-badge w/ active tint + count badges), char
  ring, published state (back to feed / new post); PowCard grew
  `baseTags`; NotePublisher grew `publishNoteWith`/`publishPowNoteWith`.
  Verified: shared androidHost + native 239/239, Android compile + 55/55
  unit tests, structure check. iOS composer page + its bridge surface
  (counter/insert/mention/deriveTags + tags-aware publish) scoped as the
  immediate next increment; GIF picker + poll sheet + draft persistence
  remain. Also merged around the concurrent session's More-hub overlay
  landing in BitOSApp mid-wire.

- 2026-08-28 — APP-009 threading core (spec §3.9; every rule in
  shared/business-core first). Shared core: `EventRefs` (note1/nevent1/
  naddr1 → id or NIP-33 coordinate pointer, TLV author + bounded ≤4 relay
  hints, `nostr:`-prefix tolerant, invalid → null) reusing the Nip27
  bech32/TLV machinery (decoder widened to internal) + `requestFilter`
  (ids / newest `#d` version); `ThreadAssembly` — `rootAndParent` NIP-10
  anchors (explicit markers win, legacy first/last positional, root-only
  replies attach to the head), `assemble` (chronological children,
  flatten at depth 8, visited-set cycle guard with unreachable cycles
  surfaced as orphans, missing-parent replies surfaced at top level,
  window ≤200); `FeedNote` grew `threadRootId`/`threadParentId` from the
  projection; bridge: Note projection carries the anchors + new
  `eventRefParse`, `threadRootRequestById/ByCoordinate`, `threadItemsJson`
  (stable output shape); XCFramework rebuilt. Android: `FeedUiState.threads`
  assembled in publishState; CommentSheet renders indented rows with a
  conversation rail + "Reply above unavailable" orphan note; Discover
  ref-search (SearchRepository branches on `EventRefs.parse`, `isRefSearch`
  state) fetches the head and the result card opens the thread sheet.
  iOS: `ThreadDisplayItem` + `FeedStore.threads` via the bridge JSON with
  an honest flat fallback; CommentSheet render pass is the next increment
  (store + seam landed). 15 new common tests (ThreadAssembly 10 — two
  cycle fixtures + orphan + cap bounds; EventRef 5 — TLV vectors built
  with the internal encoder). Fixed concurrent-session in-flight blockers
  during verification (PrivacyPrefsStore/IdentityStore trailing commas,
  SettingsView toggle arity, IdentityKeychain type-checker timeout).
  Verified: shared androidHost + native macOS 230/230, Android compile +
  55/55 unit tests, full-app Swift 6 typecheck 0 errors, structure check.

- 2026-08-28 — APP-005 V1 closed (last two items; presentation-only so
  natives own it — the shared settings contract already carries the one
  product rule involved). Show more/less: line-based 8-line clamp on the
  compact card bodies both platforms (Android `RichText` maxLines +
  hasVisualOverflow callback; iOS `ExpandableRichText` clamped-vs-natural
  height probes via PreferenceKeys, measurement stops once the toggle is
  offered); full-screen card pages never clamp. §2.4 like motion:
  `AnimatedLikeIcon`/`LikeTapIcon` (≈300 ms damped spring scale-bounce +
  haptic on the like tap only, unlike stays quiet; Solar heart Bold/Linear
  per icon-system.md; Android enforces the 48 dp target) adopted in both
  card action rows. Parity fix found while wiring: the APP-018
  sensitive-media default (`bitos_sensitive_media` cover/show) gated feed
  cards but not the APP-012 origin-preview strips, and the Android feed
  param was never wired from the store — BitOSApp now feeds
  `sensitiveShowByDefault` (settings snapshot) to Home/Bitz/Activity
  surfaces and both strip covers honor it (iOS reads the environment
  store). Merged duplicate wiring a concurrent session added in BitOSApp.
  No shared-core change (clamp/animation are §2 design behavior, the
  setting already exists). Verified: Android compile + 50/50 unit tests ✅
  (3 grown by the concurrent session), full-app Swift 6 typecheck 0
  errors ✅, structure ✅. APP-005 remaining: polls [W+F] + compact variant
  (rides APP-010/015). Next: APP-009 threading remainder or APP-010
  results tabs.

- 2026-08-29 — You-page profile rebuilt to the legacy Flutter
  `_ProfileHeader`/`_ProfileInfo` structure (user-named, both platforms):
  FULL-BLEED 160dp banner cover (uploaded banner renders via
  produceState bitmap / AsyncImage; two-tone brand gradient + top/bottom
  scrims fallback) with floating GLASS controls (blur-style translucent
  circles/pills: Edit cover → editor, Share → copies nostr:npub, Settings);
  avatar HERO (92dp hex, ⚡ badge when lud16) lifted -46dp onto the cover
  edge with drop shadow; CENTERED identity block (24sp bold name + ✓
  NIP-05 verification, @username in accent, npub copy-chip pill, ⚡
  Lightning chip); evenly-spaced stats row (Posts · Following · Bitz);
  collapsible About (Show more/less at 120 chars); boxed tab bar (Notes ·
  Replies · Bitz · Reposts, evenly split) with window-fed content + honest
  empty note. Also fixed the user's concurrent BitzScreen isEmpty()
  property-vs-fun. Verified: Android compile + 55 tests ✅, iOS full-app
  Swift 6 typecheck 0 errors ✅, structure ✅.

- 2026-08-29 — Profile avatar/banner UPLOAD shipped (user-named: upload +
  crop). Shared: `ProfileMediaSpec` contract (avatar 512² center-crop,
  banner 1500×500 3:1 center-crop, 4 MB JPEG bound — test-locked). Both
  platforms: the editor's Avatar/Banner fields now carry a photo-picker
  action (Android `GetContent` + brand trailing-icon button, iOS
  `PhotosPicker`) → EXIF-orient → CENTER-CROP to the field's aspect →
  downscale to spec → JPEG re-encode (quality steps to fit the byte
  budget) → verified Blossom upload (hash-matched receipt) → URL fills
  the field automatically, with per-field "Uploading…" state + honest
  failure line; the URL stays hand-editable. Android prep lives in
  `ProfileImagePrep` (subsample decode + EXIF rotate + crop + scale);
  iOS crops via cgImage.cropping + renderer. Interactive drag-crop
  handles remain APP-013's pickers/crop wave — the default center-crop
  covers the actual legacy default behavior. Adapted to the user's
  STATIC framework switch (typecheck lane now uses the simulator slice).
  Verified: shared 345/345 ×2 ✅, Android compile + 55 tests ✅, iOS
  full-app Swift 6 typecheck 0 errors ✅, structure ✅.

- 2026-08-29 — Profile editor web-form parity (user ask: view UX-UI from
  the web version). Shared: `composeProfileMetadata` + bridge
  publish surfaces gained `banner` + `website` (bounded, HTTPS-gated like
  picture; website accepts http(s)) and `ProfileMetadata` grew the fields
  (lenient decode). Both platforms rebuilt the editor as the web
  settings/+page.svelte form: UPPERCASE 12-bold-muted labels, 2-col grids
  (Username/Display name · Avatar URL/Banner URL · Website/NIP-05), Bio
  textarea with live "N / 300 characters" counter, Lightning address,
  Save-changes (busy spinner) + Cancel; fields prefill from the live
  kind-0 (previously only 4 of 8 fields existed and prefilled nothing).
  Android uses the brand `BitosTextField`; iOS uses labeled rounded-border
  fields. Also completed the user's concurrent edits so the gates pass:
  DmStore wrap/publish plumbing (Bridge.Event type, secureDm* labels,
  rumor fields), BitzView richJson via bridge tokens, ProfileMetadata
  banner/website mirror, GifPickerContract settle (their fix landed).
  Verified: shared 336/336 ×2 lanes ✅, Android compile + 55 tests ✅,
  iOS full-app Swift 6 typecheck 0 errors ✅ (XCFramework rebuilt), pbxproj
  lint ✅, structure ✅.

- 2026-08-29 — Compact mode wired functional (last wireable settings gap
  after the functional pass). Both platforms: NoteCard density now derives
  from the persisted `bitos_compact_mode` — tighter card vertical padding
  (md→4), reduced internal spacing (sm→2) and a smaller hex avatar
  (36→28) when on; the default density is unchanged. Also fixed the
  user's concurrent FeedStore closure capture (Swift 6 `self.` explicit).
  Remaining settings honest-pending is now strictly infra-dependent:
  theme/accent application (APP-023 light tokens), video quality (server
  renditions), sound (assets), language strings (APP-024). Verified:
  Android compile + 55 tests ✅, iOS full-app Swift 6 typecheck 0 errors ✅,
  structure ✅.

- 2026-08-28 — Settings functional-completeness pass (user ask: "check
  completed functional"). Audit finding: five persisted prefs were read
  NOWHERE (fontSize, hapticEnabled, soundEnabled, compactMode,
  videoQuality). Now live (both platforms): **font size applies app-wide**
  — Android wraps the shell in a scaled `LocalDensity` (multiplier over the
  system font scale: small .9 / 1 / large 1.15 / extra_large 1.3), iOS maps
  to `.environment(\.dynamicTypeSize, …)` at the root (xSmall / large /
  xxLarge / accessibility2); **haptics toggle gates real feedback** —
  like-on (Android LongPress haptic at the feed like, iOS
  `.sensoryFeedback(.impact)` gated by the pref) and the account-switch
  completion tick; sound stays honest-pending (no sound assets exist —
  toggling alone would be fake). Remaining honest-pending: theme/accent
  application (APP-023 light tokens), video quality (server renditions),
  compact mode (density variant pending APP-005 compact card), language
  strings (APP-024). Also unblocked concurrent edits: CommentSheet
  imports, BitzScreen LazyColumn/items/heightIn imports, FeedStore
  bolt11AmountMillisats call through the bridge (KotlinLong → Int64),
  CommentSheet BusinessCore import. Functional matrix now: autoplay ✓
  playback-rate ✓ zap-default ✓ media-preview ✓ sensitive ✓ blocked ✓
  algorithm ✓ font-size ✓ haptics ✓ relays ✓ notifications-mutes ✓
  multi-account ✓ QR ✓ donate ✓ contributors ✓. Verified: shared 293 ✅
  (user's new tests), Android compile + 55 tests ✅, iOS full-app Swift 6
  typecheck 0 errors ✅, structure ✅.

- 2026-08-28 — Switch-overlay polish (user feedback: ring didn't spin;
  sheet must close on switch). Spin fix, both platforms: the orbit is now
  TIME-DERIVED and cannot be interrupted — Android rebuilds the
  sweep-gradient stops per frame shifted by the animated angle (hex
  outline stays STATIC, the bright arc orbits inside it — exact legacy
  `_OrbitHexPainter` behavior; earlier the canvas transform rotated shape
  + shader together and read as static), iOS drives the angle from
  `TimelineView(.animation)` wall-clock (withAnimation repeatForever was
  transaction-fragile under the staged state changes). Sheet auto-close:
  tapping a switcher row now closes the bottom sheet immediately (Android
  `showSwitcher=false` at tap; iOS dismisses the switcher sheet then the
  fullScreenCover overlay takes over) — the overlay alone owns the screen
  during the switch. Also unblocked the user's concurrent edits: BitosTextField
  (M3-1.4 compat: prefix/suffix + contentPadding overloads), BitzScreen
  (onRemix arg, remixSeedTags typing, BitzMoreMenu), BitOSApp long-press
  refresh imports, FeedNote bridgeNote remix fields + XCFramework rebuilt
  with the user's remix/poll bridge Note params. Verified: Android compile
  + 55 tests ✅; iOS — my files parse/typecheck clean (overlay 0 parse
  errors; full-app typecheck blocked only by the user's in-flight
  BitzView expression-timeout, theirs to finish).

- 2026-08-28 — Branded full-page account-switch overlay (user-named;
  legacy `AccountSwitcherOverlay` 1:1). Both platforms:
  `AccountSwitchOverlay(View)` — opaque full-screen scrim (0x0A0A0F,
  input-blocked), 128dp breathing hex mark (±2% scale) with the rotating
  amber orbit sweep along the hex ring (sweep-gradient stops 0/8/16%,
  red-tinted on failure), identity HANDOFF (the FROM avatar holds ~450 ms
  then morphs into the target: fade + scale 0.55→1 + slight rotation,
  easeOutBack), staged captions ("Reading the sealed key → Connecting
  relays → Loading profile → Rebuilding your feed") with a determinate
  progress fill, check-badge completion POP (spring), "Now using NAME"
  done beat 700 ms, 1250 ms brand floor, 240 ms fade-out revealing the
  rebound app. Android: infinite transitions (breathe/orbit) +
  AnimatedContent handoff + AnimatedVisibility badge, runs in the More
  composition root = true full page; the switcher row tap now opens the
  overlay which performs switchTo mid-sequence. iOS: fullScreenCover
  (item-based), repeatForever breathe/orbit animations, spring handoff
  transition + badge, sheet tap routed through an onSwitchTarget callback;
  titleText/stageText computed props (nested-string ternaries broke the
  parser). Verified: Android compile + 55 tests ✅, iOS full-app Swift 6
  typecheck 0 errors ✅ (pbxproj: AccountSwitchOverlay registered), lint ✅,
  structure ✅.

- 2026-08-28 — Donate-sats + Contributors widgets (user-named; legacy
  `SupportWidget`/`ContributorsWidget` parity). Shared: AppFacts schema 2
  replaces the placeholder LUD16 with the REAL legacy flow inputs — official
  support npub, 4 web-parity tiers (1k coffee / 5k expert ★recommended /
  21k production / 100k premium) + contributor npub list; bridge wires
  (supportNpub, tiers, recommendedTierSats, contributorNpubs); facts tests
  v2 (npub shape, ascending tiers, exactly-one recommended). Both platforms:
  **ProfileLookupStore** — one-shot multi-pubkey kind-0 lookup (REQ via
  shared codec, newest-verified-per-pubkey, 8 s settle — the old
  SupportController fetch design); **SupportDonateSheet** — npub → profile →
  lud16 → tier tiles + custom → LNURL-pay invoice → QR + copy + open-wallet
  (Android zxing QR + LnurlPayClient; iOS minimal LNURL client + CoreImage
  CIQRCodeGenerator — invoices exceed the shared V1-6 encoder); resolves
  honestly (loading spinner, unreachable-address error, never a fake
  address); **Contributors card** in settings-help — kind-0 rows (avatar,
  display name, npub-copy), live from relays. Also folded the user's
  concurrent BitzExplore edits (gridState hoist, selectMode relocation,
  Replay10/Forward10 icons, ProfileScreen onOpenZaps + profileLookup
  ordering, BitzView PlayerSurface un-privated). Verified: shared 278 ✅
  (incl. user's new Bitz tests), Android compile + 55 tests ✅, iOS full-app
  Swift 6 typecheck 0 errors ✅ (XCFramework rebuilt), pbxproj lint ✅,
  structure ✅.

- 2026-08-28 — Switch-account sheet UX-UI parity (user-named; legacy
  `AccountSwitcherSheet`/`_AccountRow` 1:1). Both platforms: rounded CARD
  rows (active = 6% primary tint + 25% primary border; inactive = surface
  + hairline border), 40px hex avatar with the ⚡ lud16 chip overlay
  (bottom-end, orange circle, hairline ring), bold name + ✓ NIP-05 badge
  + identifier line + monospace short-npub subtitle — all from the
  account's kind-0 when the feed has seen it (honest: hex avatar +
  npub-only for unknown profiles); trailing states exactly like the old
  app: 18px spinner WHILE SWITCHING (busy && !active) / ✓ check when
  active / chevron otherwise; header count chip; footer Add + Manage.
  iOS sheet body split into sub-views after the monolithic VStack timed
  out the Swift 6 type-checker. The one pre-existing flake
  (readCursor… under full-suite parallel load) passed isolated again.
  Verified: shared 267/267 ×2 ✅, Android 55/55 (isolated re-run) ✅,
  iOS typecheck 0 errors ✅, pbxproj lint ✅, structure ✅.

- 2026-08-31 — Add-account nsec UX unified (dialog-vs-sheet audit). The
  More-hub add-account surface was an AlertDialog on Android vs a bottom
  sheet on iOS, with three same-weight text buttons on iOS, an empty
  `confirmButton = {}` slot on Android, and — worst — the KF-6 derived-
  identity preview missing on Android (users committed without seeing
  which account the key controls). Best-version converged on both
  platforms: bottom sheet everywhere (opened from the switcher sheet;
  hosts a keyboard form like every other Compose sheet), new shared v2
  copy vectors in `IdentityOnboardingContent` (SCHEMA_VERSION 2:
  add-account title/subtitle/labels + 4-item collapsible "What's an
  nsec?" help — what it is, where to export it, npub≠nsec,
  sealed-on-device) bridged through `identityOnboardingContent()` and the
  iOS `IdentityOnboardingMirror`, derived-identity card on both, and one
  filled "Review key" gated on READY + outlined "Create new key" + quiet
  Cancel (KF-7/KF-8 in ux-ui-flows). Android extracted to
  `ui/components/AddAccountSheet.kt`; iOS sheet gained ScrollView +
  medium/large detents. Verified: shared androidHost tests ✅ (incl. new
  `addAccountSheetAndNsecHelpCopy`), Android 55+ tests ✅, iOS full-app
  build 0 errors ✅.

- 2026-08-28 — Identity QR shipped (user-named "show QR" gap). Shared
  core: pure `QrCode` encoder (APP-022) — byte mode, ECC level M, versions
  1–6 (identity payloads: npub/nostr: links), GF(256) Reed–Solomon with the
  reduction-order bug caught by tests (mask before xor 0x11D kept bit 8 →
  index 285 AIOOBE), all 8 masks with N1/N2/N4 penalty scoring,
  deterministic; format-area reservation initially ate the (8,6)/(6,8)
  timing modules and the always-dark module sat coordinate-swapped — both
  caught by structural tests, fixed. Bridge `qrMatrix` (bit-packed Long
  rows). UI (legacy BrandQrCode parity, both platforms): white card,
  gray-900 modules, bitcoin-orange hex-bolt center cover; identity-QR
  dialog/sheet from the You profile npub row and the More hub hero
  ("Scan with any Nostr app to follow …" + Copy npub). Profile (You) page
  is single-scroll on both platforms (cover → hero → identity+QR → stats →
  actions → tabs → window content). Invoice QRs: user's zxing payment path
  (long payloads, beyond the shared V1–6 table) — completed their in-flight
  edits (Uri fix, hasIdentity read, imports). Remaining polish for the next
  pass: profile-picture avatars in switcher rows (kind-0 of other accounts
  needs per-account profile fetch), branded switch overlay animation.
  Verified: shared 261/261 ×2 ✅, Android 55/55 ✅, iOS full-app Swift 6
  typecheck 0 errors ✅ (XCFramework rebuilt), pbxproj lint ✅, structure ✅.

- 2026-08-28 — Multi-account visibility fixed + hub account widgets
  (user report: no switch/add on More). Root cause: the hub switch row
  rendered only when the registry was non-empty, and every pre-registry
  account (legacy single-secret) had no row — so nothing showed and Add
  didn't exist anywhere. Fixes (both platforms): (1) legacy backfill —
  boot resolves the active identity and registers its row + seals its
  slot when missing (Android loadExisting, iOS IdentityStore.init);
  (2) More hub now always shows the legacy-parity compact row "Switch
  account · N on this device" → switcher bottom sheet with hex-avatar
  rows (short npub, Active badge, one-tap switch), **+ Add account**
  (dialog/sheet: import nsec w/ error surfacing + create new key →
  shared ConfirmIdentityDialog; extracted from ProfileScreen to
  components) and "Manage accounts" → You tab; (3) confirm-copy updated
  for multi-account (switching never wipes other sealed slots). Also
  completed the user's concurrent ComposerDraftStore wiring
  (CreateNoteScreen param). Verified: Android compile + 55 tests ✅,
  shared 250 androidHost ✅, iOS full-app Swift 6 typecheck 0 errors ✅,
  pbxproj lint ✅, structure ✅.

- 2026-08-28 — Functional settings wired system-wide + APP-018a rows 4+7.
  **Functional settings now drive real behavior** (both platforms):
  media autoplay policy gates the video pools (ALWAYS/NEVER/unmetered-WIFI
  via ConnectivityManager NET_CAPABILITY_NOT_METERED on Android /
  NWPathMonitor isExpensive on iOS — "never" pauses the visible video for
  tap-to-play), playback rate applies to every player (ExoPlayer
  setPlaybackSpeed / AVQueuePlayer defaultRate+rate on each
  reconciliation), the zap sheet opens on the persisted default amount,
  and media-preview off collapses note media to an honest "N attachments"
  caption. **Row 4 relays**: RelayListContract schema 2 `primary` flag
  (≤1, write-only, leads the publish fan-out) + ⭐ toggle per write relay +
  add-dialog suggestion chips (primal/damus/nos, legacy recommended-list
  parity) — Android + iOS. **Row 7 algorithm**: AlgorithmContract schema 2
  `diversityEnabled` + `FeedRanking.applyDiversity` (deterministic
  author-clustering requeue — max 2 consecutive slots per author, guarded
  drain, NEVER drops notes; caught two test-authoring bugs where the
  fixture gave every note a unique author — the engine was right) +
  Diverse-authors toggle + Reset-to-preset both platforms; normalize now
  carries the diversity flag through round-trips (first run dropped it —
  wire test caught it). Bridge: AlgoSurfaceWire/RelayEntryWire gained the
  flags with compat constructors. Verified: shared 246/246 ×2 lanes ✅,
  Android compile + 55 tests ✅, iOS full-app Swift 6 typecheck 0 errors ✅
  (XCFramework rebuilt), pbxproj lint ✅, structure ✅.

- 2026-08-28 — APP-017 More hub V1 + own-profile You page + hub account
  switching (old-app functional parity: apps-grid → More, You tab → my
  profile). Both platforms: (1) **More hub** opened from the feed
  apps-grid action (Android overlay state in BitOSApp + hub-routed
  settings deep-links incl. Lightning section via a new `initialSection`
  param; iOS sheet from RootView with navigationDestination sections) —
  guest card, profile hero with npub copy, **multi-account switch row**
  (tap to switch, active dot — registry + sealed slots from the previous
  session), Following/Relays live stat tiles, tile groups limited to LIVE
  surfaces (Discover, Lightning, Profile, Settings) + honest coming-soon
  tiles (Saved APP-015, Zap ledger APP-014) + About/Privacy meta rows.
  (2) **You tab = own profile page** (legacy profile_view parity): gradient
  cover + overlapping hex avatar, kind-0 display name, tap-to-copy npub
  chip, NIP-05 + about, stats (Following/Notes/Bitz), Edit profile +
  Settings actions, Notes/Replies/Bitz/Reposts tabs fed from the live feed
  window filtered to the active pubkey (window-limited — honest footnote);
  the old single-account panel is gone (switching/removal lives in Settings
  + the hub). Folded in concurrent user edits (DiscoverScreen thread sheet,
  bridge Note thread fields — Swift arg order completed). Verified:
  Android compile + 55 tests ✅, shared 239 androidHost ✅, iOS full-app
  Swift 6 typecheck 0 errors ✅, pbxproj lint ✅ (MoreView registered),
  structure ✅.

- 2026-08-28 — APP-018a rows 1+2+5+6 implemented (the user-named
  account-switch + privacy gaps). SHARED (+7 common tests, 230/230 both
  lanes): `AccountRegistry` (secret-free rows: pubkey/npub/name/addedAt,
  ≤8, hex-validated, pubkey-unique, per-row tolerant JSON wire) and
  `PrivacyPrefsContract` (8 interaction-gate fields ported from the legacy
  store — privateAcc/includeClientTag/activity/readReceipts/
  sensitiveReason/storyShare + message+comment permission enums; sensitive
  media stays the settings-contract key and push toggles stay the kind
  mutes — mapped, never duplicated) + bridge wires for both. Android:
  `SecureKeyStore` pubkey-keyed sealed slots (legacy single-secret kept as
  fallback), `AccountRegistryStore` (active-pointer lifecycle: setActive/
  null clears pointer but keeps rows = sign-out semantics),
  `IdentityViewModel.switchTo` (dead-slot self-heal drops the row),
  `signOut()` (deactivate) vs `removeRegisteredAccount` (destructive slot
  wipe), signer + boot read the ACTIVE slot; Settings-account gains the
  switcher card (hex avatar, short npub, active check, Remove w/ inline
  confirm); privacy section gains Account-privacy (6 toggles) +
  Interactions (2 permission pickers); zap presets 1/5 added; clear-cache
  wipes the event cache too (`EventCache.clearAllCache` port + repository
  seam + test double). iOS: `IdentityKeychain` slot API (attribute-keyed,
  listing, removal), `IdentityStore` registry + switchTo/remove/signOut
  parity + active-slot signing + nsec reveal through the active slot;
  `PrivacyPrefsStore` (@Observable, pbxproj registered) + mirrored privacy
  rows + account switcher card; hub sign-out → deactivate. Folded in the
  user's concurrent edits (DiscoverScreen thread-sheet wiring — completed
  the SearchResults param threading; BusinessCoreClient Note arg order;
  IdentityKeychain typecheck split — added the missing slotPrefix guard).
  Verified: shared 230/230 ×2 ✅, Android compile + 55 tests ✅ (incl.
  AccountRegistryStoreTest lifecycle + caps), iOS full-app Swift 6
  typecheck 0 errors ✅ (XCFramework rebuilt twice mid-concurrent-edits),
  pbxproj lint ✅, structure ✅. Audit rows 3 (profile picture/banner/
  website), 4 (relays primary+suggestions), 7 (algorithm diversity+reset)
  remain — scoped in the APP-018a table.

- 2026-08-28 — APP-018a Flutter parity audit (settings). Read the old
  app's real source at `~/Desktop/bitos/bitos-nostr-flutter` (settings
  controller 468L + 1128L section dispatcher + 5 section pages +
  privacy/notification store + 478L AccountManager + account-switch
  overlay + static pages) and logged the field-level gap table as
  APP-018a (12 rows, ordered by impact, each a shippable increment).
  Headline gaps: multi-account switcher (registry + one-tap auth rows +
  branded switch overlay), the 14-field privacy store (privateAcc,
  includeClientTag, activity, readReceipts, sensitiveReason, storyShare,
  message/comment permission pickers — with a key-unification note:
  hideSensitiveMedia ≡ our sensitiveMedia cover), profile picture/banner
  pickers + website field, relays primary ⭐ + recommended suggestions,
  event-cache wipe on clear-cache, zap presets 1/5, algorithm diversity
  toggle + reset, appearance live preview (APP-023), help cards +
  AboutPage sections (APP-020). Native is AHEAD on NIP-65 relay publish,
  kind-based mutes, live relay dots, zap-sats display. No code changed —
  audit + tracker only; NEXT SESSION updated with the audit picks.

- 2026-08-28 — APP-018 privacy remainder + APP-012 blocked-author
  filtering (business-core first, infra then UI). Shared core: settings
  contract v3 (`bitos_sensitive_media` cover|show — content pref, resets
  with clear-cache), `BlockList.newest` head selection (ties by id), codec
  `encodeBlockListRequest` (hex-validated author, limit 1),
  `NoteComposer.composeBlockList` (bounded ≤500, hex-validated p-tags),
  bridge surface (`blockListPubkeys` verified-frame parse, compose/publish
  message, block REQ, sensitive field on the settings snapshot) — 4 new
  common tests, 208/208 both lanes. Infra: both feed stores subscribe the
  account's kind-10004 head on setAccount (newest verified wins, reset on
  account switch) and filter the blocked set from ALL windows exactly like
  mutes; the inbox filters rows through the same blocked set (Android
  InboxScreen, iOS InboxStore.sections(blocked:)). UI (parity): privacy
  section gains the sensitive-media picker (wired into SensitiveCover on
  both platforms — `show` renders NIP-36 notes directly) and the
  blocked-users manager (avatar + short-npub rows, unblock publishes a new
  10004 head to the write relays via NotePublisher.publishBlockList with
  result line); Android settings key list gained KEY_SENSITIVE_MEDIA (the
  v2 gap class again — caught before shipping this time). Verified:
  shared 208/208 ×2, Android 50/50, iOS full-app Swift 6 typecheck 0
  errors (XCFramework rebuilt; transient noise from concurrent
  InboxView/HomeView edits resolved), pbxproj lint ✅, structure ✅.

- 2026-08-28 — APP-018 algorithm settings live end-to-end (origin
  `algorithm-plan.md` parity — the big `algorithm` gap vs the origin app).
  Shared core: `AlgorithmContract` (3 surfaces × 6 signals with
  enabled+weight, presets Latest/Balanced/Trending/Trusted + CUSTOM
  auto-detect, freshness half-life steps 1h/6h/24h/72h, 5%-grid weight
  normalization with FP-stable integer-cent math — double division
  truncated 0.35→0.30→0.25 across round-trips, caught by the wire test;
  lenient ≤2 KB JSON wire) + `FeedRanking.rank` pure pipeline (off =
  strict chronological, re-normalized live weights, exp-decay recency by
  half-life, log-scaled zaps/engagement, following-based affinity,
  deterministic score/createdAt/id tie-breaks; TOPICS/WoT honest zeros
  until their data feeds land) — 7 new common tests (204/204 both lanes).
  Android: `AlgorithmStore` (prefs `bitos_algo`, 3 adapter tests — 50
  total), `FeedRepository.setAlgorithm` ranks the For-You window (ctx:
  following + zap counts + reply counts; Following always chronological),
  full settings UI parity (freshness pills, surface selector + master
  switch, preset pills + Custom badge, signal rows with sliders + %-of-mix
  readouts, live stacked weight-mix bar), composition root pushes the
  snapshot into the repository from boot. iOS: `AlgorithmStore`
  (@Observable, wire ⇄ state helpers outside the actor, sink → feed store),
  `FeedStore.rankedForYou` through a bridge seam (`algorithmRankIds`:
  minimal note rows JSON in, ordered ids out — engine stays single-source),
  mirrored section UI (pbxproj registered, environment-injected). Also
  folded the user's concurrent edits (preset weight tuning — preset tests
  made tuning-agnostic; NotificationRepository read-cursor work; one
  cursor test flaked under full-suite load, passes isolated). Verified:
  shared 204/204 ×2 ✅, Android 50 tests ✅ (1 pre-existing flake noted),
  iOS full-app Swift 6 typecheck 0 errors ✅ (XCFramework rebuilt),
  pbxproj lint ✅, structure ✅.

- 2026-08-28 — APP-018 settings content parity (help/about legacy data +
  incomplete-widget finishers). Shared core: new `AppFacts` contract
  (versioned) — app identity + legacy About-card copy (tagline, MIT,
  built-on NIP-01), **honest** supported-NIP list (13 live NIPs verified
  against the tracker — the legacy mockup advertised NIPs this platform
  doesn't implement), canonical links (nips repo, nostr.net),
  support/donate config (LUD-16 + 21/100/1000 tiers render only when the
  team provides a real address — no placeholder payment targets),
  contribute note, and the FAQ single-sourced (now 7 entries incl. the
  user's honest "What works today?" — relay editing removed from the
  pending list since the manager just shipped); bridge `appFacts()`/
  `appFactsFaq()`; 3 contract tests (nips sorted/valid, https-only links,
  bounded content). Platforms (parity): `about` rebuilt as the legacy
  brand card (hex bolt, name, version·built-on·license, tagline) + NIP
  chip rows + schema rows; `help` gained the Support-the-project card
  (contribute copy, donate tiers gated on the address constant) + links
  rows opening the system browser (Link on iOS / ACTION_VIEW on Android)
  + FAQ now reads the shared source (was duplicated platform copies);
  `notifications` gained per-type toggles for all six kinds wired to the
  SAME muted-kinds store as the inbox header mutes (inbox/badge eviction
  shared, no second source of truth); `media` gained the honest Uploads
  card (Blossom default, [W] fallbacks noted). Icons per icon-system.md:
  existing tokens only (zap/widget), no new glyphs vendored. Also folded
  in the user's concurrent edits (7-entry FAQ texts, BlockList shared
  model + tests, HexIconTile drawable migration). Found+fixed:
  `HexIconTile` call with the old ImageVector arg after the user's
  drawable migration; NotificationKind Swift mirror needed CaseIterable
  for the per-type list. Verified: shared 197/197 both lanes ✅ (macos
  result dir was stale — forced re-run for true parity), Android compile
  + 47 unit tests ✅, iOS full-app Swift 6 typecheck 0 errors ✅ (rebuilt
  XCFramework), pbxproj lint ✅, structure ✅.

- 2026-08-28 — APP-018 relays manager shipped (the tracked `relays`
  remainder) + Solar icon system adopted + build repairs. Icon system (user
  decision, guide now at `docs/native/icon-system.md`): Solar is the
  product icon language — iOS resolves `AppIcons.image(for:)` to 41 bundled
  Solar imagesets (SF fallback, template SVGs), Android vendors reviewed
  `solar_*` vector drawables + `SolarFeedIcon` for feed actions; feature
  code keeps using semantic tokens. Relays manager: shared core gained
  `RelayListContract` (kind 10002, ≤16 url-unique entries with ≥1 role,
  versioned lenient JSON wire, NIP-65 r-tag projection — marker omitted =
  read+write) + `NoteComposer.composeRelayList` + bridge surface
  (decode/encode/normalize/compose/publish frame) — 6 new common tests
  (185 both lanes now). Android: `RelayPool.add/remove` + `statesFlow`
  (dynamic sockets, reconnect-safe), `RelayManager` store (persists wire in
  `bitos_relays`, applies edits to the live pool, never-empty invariant,
  corrupt store → platform defaults, seeds the pool defensively),
  `NotePublisher.publishRelayList` (write-role fan-out), full manager UI
  (status dots, role-toggle chips, add field with validation errors,
  delete, publish + result line), composition root boots the pool from the
  persisted set. iOS: `RelayManagerStore` + pool actor add/remove/
  relayStates, publisher `send` parameterized by write relays +
  `publishRelayList`, mirrored manager UI (2 s status poll), AppEnvironment
  boots from the persisted set, file registered in pbxproj. New-UI icons
  per the guide: vendored `solar_add_circle_linear` + `solar_trash_linear`
  on Android (ported from the bundled iOS SVGs — same reviewed source,
  notices unchanged). Found+fixed after the icon adoption (cascade-hidden
  by an early RootView error): HomeView missing the `onOpenHub` param its
  call sites pass (added + apps-grid header button resolving
  SolarWidgetLinear — also converts the neighboring search button to the
  resolver), FeedStore redundant `let self` after the weak-self unwrap
  (Swift 6 rejects), SettingsView `RelayURL.value` → `rawValue` ×2 (user
  rename), `ios-build.sh` typecheck fallback missing the XCFramework
  `-F` search path (fixed in-script). Tests: RelayManagerTest (Android,
  5 cases incl. pool lifecycle through fake transports) +
  RelayManagerStoreTests (iOS, 6 cases; registered in pbxproj). Verified:
  shared 185/185 both lanes ✅, Android compile + 45 unit tests ✅, iOS
  full-app Swift 6 typecheck 0 errors ✅, pbxproj plutil lint ✅,
  structure ✅.

- 2026-08-28 — APP-004 finished (Home feed V1 complete; user requests:
  X-parity pill, load-more, re-tap refresh). Shared core: `EmptyFeedRetry`
  pure policy (2 s exponential backoff capped 30 s, saturating attempt
  counter, connectivity-gated re-REQ; 5 common tests) + bridge surface
  (`emptyFeedRetryDelayMs`, `olderFeedRequest`; XCFramework rebuilt).
  Android: `FeedRepository` gained live tab counts (ALL-window per
  timeline), the retry loop (injectable delay for tests), `loadOlder()`
  (one `until`-REQ pinned to the oldest window note, limit 40, in-flight
  guard, 8 s watchdog → `noMoreOlder` until refresh, window cap 200) and
  `retryNow()`; FeedScreen: header keeps wordmark + filter/search/apps-grid,
  tabs gained live counts, the reveal pill became a floating X-style
  accent capsule (overlapping ≤4 avatar stack + ↑ + "Show N notes",
  top slide-in), list/pager near-end triggers + footer spinner, re-tap on
  the ACTIVE shell tab scrolls to top (at-top → refresh; feedRetapTick),
  hold/reveal now driven by the ACTIVE surface (list first row / pager
  page 0 — the old pager-only signal never held on the Home list), and
  the Following tab finally renders notes (identity gate →
  contacts-resolving → states). Empty states split: relay-error + Retry
  now / filter-mismatch + Show all / no-notes-yet + auto-retry hint.
  iOS: `FeedStore` mirrors everything (counts, retry loop, `loadOlder`,
  `retryNow`); `HomeView` reworked to the spec app bar — wordmark leading
  (light/dark asset), centered filter trigger, search/apps-grid/import
  trailing, sticky underline tabs with live counts — and the same pill /
  pagination / re-tap behaviors (ScrollViewReader list, scrollPosition
  pager; body split into named stages after a type-checker timeout,
  same fix class as pagerPage/pagerPosition). RootView intercepts
  re-taps through a custom selection binding. Icons per the new
  `docs/native/icon-system.md` guide: new `arrowUp` token on both
  AppIcons facades (native backing; Solar swap-in later) — everything
  else resolved through `AppIcons.image(for:)` / bundled Solar assets.
  Fixed in-flight breakage found during verification (some from a
  concurrent session's unverified edits): Android FeedScreen header
  rework compile breaks + missing AppIcons imports; iOS AppEnvironment
  missing `import BusinessCore`, RelayManagerStore `entries:` label,
  SettingsView trailing commas + stale `PublishStatusLine(publisher:)`
  call + switch-expression nil typing. Verified: shared androidHost ✅ +
  native macOS 185/185 ✅, Android compile + 45 unit tests ✅ (repo suite
  14 incl. +2 new: empty-feed auto-retry, older-page until-REQ),
  full-app Swift 6 typecheck 0 errors ✅ (rebuilt XCFramework),
  structure ✅. Follow-up noted: Android FeedScreen still carries an
  unreachable `showImportMedia` sheet (import entry moved to Create by
  the concurrent header decision) — remove when Create owns the flow.

- 2026-08-28 — APP-012 finished (read-model remainder; every rule landed in
  shared/business-core first per the infra layering, natives only adapt).
  Shared core: `Nip36` (content-warning tag/label rule, now deduped by
  card + preview), `OriginNotes` grew the media strip (≤4 distinct
  image/video URLs, FeedNote classification parity) + `contentWarning`,
  `NotificationFilters` grew `queryMatches` (bounded 64, case-insensitive,
  author-name participates), `blockedEvicted` and the read-cursor rule
  `isRead` (explicit ids ∪ created-at ≤ cursor), new `BlockList` kind-10004
  parser (hex-validated `p` tags, ≤500, null for other kinds so heads
  survive) — +9 common tests (both lanes green; the strip-order test
  documents the image-then-video grouping, and one draft assertion bug
  was caught red-handed by the suite). Bridge: `originNoteFromFrame`
  carries `mediaUrls`/`contentWarning`; new `blockListRequest`,
  `blockListFromFrame` (verified + author-gated + createdAt for head
  selection), `notificationQueryMatches`, `notificationCursorIsRead`
  (−1 sentinel = no cursor); XCFramework rebuilt. Android:
  `NotificationRepository` subscribes the kind-10004 head (evicts rows,
  filters arrivals, badge-safe), persists `cursor_seconds` through the
  prefs port (interface grew two methods — impl + both test doubles
  updated), and `markRead`/`markAllRead` advance the cursor;
  `InboxScreen` gained the expandable search row (query applied with
  feed-profile display names) and the origin media strip with NIP-36
  cover + lightbox. iOS: `InboxStore` mirrors everything (blocked head,
  cursor in UserDefaults, query through `sections(query:authorNames:)`),
  `InboxView` gained the same search row + strip/cover/lightbox, new
  `NotificationBridgeRuleTests` lock the seam (media-strip keys with an
  in-test signed frame via `signDetached`, cursor + query predicates).
  Android repo suite +2 (block-list evict/filter; cursor redelivery +
  reload-across-instance) — 7 green. Verified: shared androidHost ✅ +
  native macOS 197/197 ✅, Android 47/47 unit tests ✅, full-app Swift 6
  typecheck 0 errors ✅, structure ✅. Next session candidates: APP-009
  threading remainder, APP-010 results tabs, or APP-005 polls/Show-more.
  14:3x WIP). The last session had already shipped the shared contract v2
  (18 keys incl. accent color + playback rate, schema 2) and the complete
  iOS hub (all 12 sections, SettingsStore via bridge, adapter tests);
  this session shipped Android parity + repairs. Android: live security
  (npub copy, confirm-gated nsec reveal through a new
  `IdentityViewModel.revealNsec` — the sealed secret stays behind the
  identity seam; danger-zone remove), lightning (zap presets + fine
  stepper, moved out of algorithm), privacy (auto-load + protocol notes),
  relays (configured list + read/write role chips + live connected count
  from the feed store), help (static FAQ), account edit-profile sheet,
  appearance accent palette (15 colors, iOS parity), media playback-rate
  row; `feedRepository` threaded MainActivity → BitOSApp → ProfileScreen
  → SettingsScreen; ProfileScreen no longer shadows its injected store
  with a context-built one. Found+fixed (interrupted-session damage that
  broke the build): Android `SettingsContractKeys.ALL` missing
  KEY_ACCENT_COLOR/KEY_VIDEO_PLAYBACK_RATE (those settings would silently
  never decode), FeedScreen mangled modifiers
  (`.androidx.compose.foundation.border` ×2) + duplicated `@Composable` +
  BitOSApp calling a stale FeedScreen signature (dropped
  mediaPublishViewModel arg; wired `onOpenDiscover` → Discover tab on
  both feed call sites), AppIcons missing imports for AutoAwesome/People
  (extended set was already a dependency), pbxproj BitOS group carrying a
  duplicated 9-ref children block ("member of multiple groups" warnings —
  deduped, group linted: 42 refs, no dupes, all resolve). Adapter test +1
  (accent/playback round-trip incl. protected-on-clear; first draft
  wrongly asserted enum rejection — the contract self-heals unknown enum
  values to the default, the test now locks that rule). Verified: shared
  macosArm64 + androidHost 179/179 ✅, Android compile + 39 unit tests ✅,
  XCFramework rebuilt (was stale vs the 14:30 contract) + full-app Swift 6
  strict-concurrency typecheck 0 errors ✅, pbxproj lint ✅, structure ✅.

- 2026-08-28 — APP-012 finishers shipped. Shared core: `Bolt11` HRP msat
  parser (BOLT-11 multiplier table; separator = final `1` since bech32
  data excludes it — caught by the vectors; bounds 20..4096 chars, ≤9
  digits, ≤1e15 msat, pico must be whole-msat) + `ZapReceipt.senderPubkey`
  (description tag → embedded 9734 through the client verified gate: ID +
  BIP-340 + p-tag recipient match, tampered/mistargeted attribute
  nothing) + `amountMsat` on NotificationItem, `totalMsat` on groups,
  full bounded `content` on OriginNote (thread roots) — 5 new common
  tests incl. two in-test signed 9734 fixtures. Platforms (parity):
  zap sats in every title line, row tap → thread sheet (CommentSheet with
  the origin note) or author sheet, visible-mark-read-on-open (1.4 s,
  arrivals restart), per-type mutes (persisted `muted_kinds`, evicted +
  badge-excluded, header mute menu), unread badge on the Activity tab
  ("9+" cap) with shell-level account wiring (inbox subscribes from app
  start; setAccount now idempotent on both). Android repo contract tests
  +2 (mute evict/persist/redelivery; zap amount+sender through the
  verified gate — found the hand-built receipt id must be canonical).
  Found+fixed in the 14:08 settings WIP (blocked the build): Android
  `SettingsStore` unresolved `PREFS_NAME` (lives in SettingsContractKeys),
  ProfileScreen→SettingsScreen missing the new `store` param (wired
  through the composition root: BitOsApplication.settingsStore →
  MainActivity → BitOSApp), iOS `AppearanceSection` brace soup from a
  scripted edit (duplicate `header:` + stray Toggle). Verified: shared
  macosArm64 + androidHost ✅ (165 tests), Android compile + 33 unit
  tests ✅, full-app Swift 6 typecheck with rebuilt XCFramework 0 errors
  ✅, structure ✅.

- 2026-08-28 — APP-012 notifications full (work order executed 1–6).
  Shared core: `NotificationSections` (UTC day buckets ≤14 + iOS-style
  aggregation "pk… and N others liked your note" — reactions/reposts/zaps
  collapse per target, follows/replies/mentions never, actors newest-first
  cap 4, oldest-id group identity), `NotificationFilters` (tabs/chips +
  kind-3 republish dedupe rule), `NotificationKind.FOLLOW` + kind-3
  extraction, `OriginNotes` bounded preview projection (NIP-27 tokenizer
  reuse: URL-stripped excerpt ≤160, first-URL thumb), codec
  `encodeIdsRequest` (hex-validated, ≤100/batch) and bridge surface
  (`groupNotificationsJson` stable shape, `originNoteFromFrame`,
  tab/chip predicates) — 13 new common tests across JVM + native macOS.
  Android: `NotificationRepository` grew read-state (prefs port,
  SharedPreferences-backed), raw-frame retention, origin fetch with 8 s
  aging, follow dedupe; `InboxScreen` rebuilt (tabs+counts, chips, day
  sections, avatar-stack grouped rows with unread dots, origin previews,
  ⋯ AppMenu mark-read/copy/raw-JSON dialog); 3-test contract suite with
  in-test signed frames (deterministic signer). iOS: `InboxStore` mirrors
  everything through the bridge (incl. KotlinLong createdAt decode fix —
  the old KotlinInt cast silently zeroed timestamps); `InboxView` rebuilt
  to match (AppMenu popover rows, raw-JSON sheet, RootView signature
  fixed). Found+fixed a pile of pre-existing iOS typecheck breaks that
  parse-only verification had missed: RichContent `Data as UIImage?`,
  FeedStore called `feedFilterMatches` with the Swift note mirror (moved
  to the BusinessCoreClient seam; bridge now takes the `Note` projection
  and gained `contentWarning`, which the Swift mirror also lacked),
  `richTokens(content:)` label, unscoped `FeedState`, RichTextView
  callback arity (×2), a pager expression that timed out the type-checker
  (split into `pagerPage`/`pagerPosition`), ProfileView `.environment`
  and SettingsView `IdentityPreview.bestDisplayName`. Verified: shared
  macosArm64 + androidHost suites ✅, Android compile + 17 unit tests ✅,
  full-app swiftc typecheck (Swift 6 strict concurrency, rebuilt
  XCFramework) 0 errors ✅, structure check ✅. Simulator-run lanes stay
  CI-side (no local destination).

- 2026-08-28 — APP-003 fix (closed the logged shell edge): per-surface
  empty/loading state now derives from the FILTERED tab notes on both
  platforms (iOS `surfaceState` over the video/notes split; Android
  `feedNotes`-based checks) — the Bitz tab correctly shows loading/empty
  while the store holds only text notes, instead of a blank ready pager.
  Verified: iOS parse ✅, Android compile ✅. Next session (full budget):
  APP-012 notifications full OR the `relays` settings manager — both are
  scoped in the tracker; pick either.

- 2026-08-28 — APP-018 V1 shipped: Settings hub on both platforms, entry
  from the You tab (iOS gear toolbar → sheet, Android "You" header →
  full-screen swap with Done). Sections: account hero (npub copy→check),
  persisted preferences (notifications/sound/haptics — `bitos*` keys in
  UserDefaults / `bitos_prefs` SharedPreferences), appearance + language
  as honest pending rows (APP-023/024), version row, sign-out with
  confirmation (removes the key via the identity seam). Icon decision
  re-confirmed with the user: system icons (SF/Material) behind AppIcons
  tokens stay for V1; Solar swap-in-place documented (spec §2.5).
  Found+fixed: `Icons.Rounded.Settings` is an extension import (no
  qualified access), duplicate state/import churn from scripted edits.
  Verified: Android compile ✅, iOS parse ✅ + pbxproj lint ✅, structure ✅.
  Next: relays manager section (`relays` — CRUD/status dots/NIP-65) or
  APP-012 notifications full.

- 2026-08-28 — APP-003 shell change (USER DECISION, legacy parity): the
  bottom bar is now **Home · Bitz · Discover · Chats · Activity · You**
  (6 tabs) — superseding the 5-tab `product-system.md` map; spec §1.1
  updated as the new authority. Home tab = scrolling compact NoteCard
  list (author row + NIP-27 rich body + media tiles + action row +
  sensitive cover — the legacy UX), Bitz tab = the existing video pager;
  one verified feed store feeds both (client-side video/notes split).
  Create/Studio entries stay on the Home FAB + Bitz header (You hub
  APP-017 lands next wave); Settings (APP-018) pushes from You. Chats
  tab ships as an honest placeholder (NIP-17 DMs are W2 — nothing
  fake). Also re-confirmed the icon answer: legacy Flutter = Solar
  behind AppIcons tokens; native = same tokens backed by SF Symbols /
  Material (swap-in-place, spec §2.5). Found+fixed during build:
  automirrored ChatBubble glyph doesn't exist in outlined set;
  qualified-extension `items()` call; FeedScreen `remember` import.
  Verified: Android compile ✅ (main), iOS parse ✅ + pbxproj lint ✅;
  known gap logged: tab-split empty-state edge (video-only tab while
  store holds only text notes shows `ready`-blank until mixed arrival) —
  fix next session with per-surface state derivation.

- 2026-08-28 — APP-005 first increment: NIP-27 rich content end-to-end.
  Shared core: `core/nostr/Nip27.kt` — tokenizer over note bodies
  (npub/nprofile/note/nevent/naddr in bare / `nostr:` / `@` forms, URLs,
  hashtags with whitespace preservation, adjacent-text merging, invalid
  bech32 stays inert) with its own variable-length bech32 + TLV type-0
  walker (the key codec is 32-byte-strict by contract), plus a stable
  bridge JSON shape for iOS; `FeedNote.contentWarning` (NIP-36 tag +
  label forms). 12 new common tests across JVM + native macOS — they
  caught a real bug immediately: my hand-copied charset missed `l`
  (bech32 value 31) so NO entity matched; fixed constant+regex against
  the codec's authoritative string. UI: iOS `RichContent.swift`
  (`RichTextView` via AttributedString + bitos:// OpenURL routing,
  `RemoteImageView` w/ 2048px memory-normalized loads, `MediaGrid` ≤9
  tiles, zoomable `MediaLightbox`, `SensitiveCover`); Android
  `ui/components/RichContent.kt` (`RichText` via LinkAnnotation,
  `MediaRow`, gesture-zoom `MediaLightbox`, `SensitiveCover`). Text cards
  on both platforms now render rich bodies with entity/hashtag taps
  routing to the author sheet, image tiles → lightbox, and NIP-36 notes
  gated behind a per-session reveal. Bridge gained `richTokens`;
  XCFramework rebuilt. Found+fixed during build: Kotlin lambda signature
  mix-up (`detectTransformGestures` zoom is 3rd param) and a nullable-
  callback `let`. Verified: shared suites green both lanes, Android
  compile ✅, iOS typecheck/parse ✅ (UIKit parts parse-only until CI —
  no iOS SDK in sandbox), pbxproj lint ✅. Next: polls + Show more/less +
  animated LikeButton (APP-005 finish), or APP-012 notifications full.

- 2026-08-28 — APP-004 core shipped: content filter + guest banner +
  new-notes pill + FAB on both platforms. Shared core: `FeedFilters`
  (six legacy windows as pure predicates over FeedNote, protocol-payload
  hidden in ALL/ORIGINALS/MEDIA; 7 common tests on JVM + macOS) + bridge
  `feedFilterMatches` for iOS (XCFramework rebuilt). Stores: hold/reveal
  arrival buffer (hold while scrolled — iOS `topId`/pager page 0 drives
  it; reveal prepends; at-top auto-flush; known-id dedup makes relay
  redelivery harmless; pending cap 50) + filter application inside
  publishState (iOS via bridge per-note, Android via shared enum).
  Chrome: iOS header gains filter popover (AppMenu w/ checkmarks) +
  search action → Discover (RootView closures), content gains pill /
  banner / New-note FAB overlay; Android header gains filter dropdown,
  body gains pill / banner / ExtendedFAB, `onOpenProfile` wired through
  BitOSApp. Found+fixed during build: duplicate `companion object` in
  FeedRepository (constants went unresolved — merged into the existing
  one); an accidental broken edit deleted iOS FeedState extension
  (restored); ALL-filter fast-path skipped protocol hiding (removed —
  predicate always runs). Repo contract test: held arrivals wait for
  reveal, redelivery never duplicates, at-top flushes (JVM executor
  still sandbox-blocked — CI runs; shared suites green on both lanes).
  Next: APP-005 NoteCard rich renderer (NIP-27 entities + media grid +
  sensitive covers) or APP-012 notifications full.

- 2026-08-28 — APP-022 icon decision + token layer (answers "same Solar
  as the Flutter app?"): audited legacy — Flutter backed **Solar**
  (`solar_icons` Bold/Outline, ~6 Material fallbacks), web used **Lucide**;
  the durable part was the `AppIcons.*` semantic token layer, not the
  font. Native decision: port the tokens, back them with native SF
  Symbols / Material Rounded (RTL mirroring, Dynamic Type, zero bundle,
  platform conventions per AGENTS.md); Solar stays swap-in-place (only
  the two token files change, never call sites). Shipped:
  `DesignSystem/AppIcons.swift` + `ui/theme/AppIcons.kt` and migrated
  every call site in the components built so far (AppMenu, PowCard,
  FeedScreen rails + ⋯ menus — 16 iOS + 19 Android replacements). Found
  +fixed during migration: my own bad glyph choices (automirrored
  ChatBubble and Hand don't exist → ChatBubble / PanTool; a scripted
  import swap produced `import AppIcons.Pen`). Verified: Android compile
  ✅, iOS typecheck ✅, pbxproj lint ✅. Noted for APP-004 next session:
  Android already carries basic For You/Following tabs + compose/import
  header — remaining gaps are the AppMenu content-filter, guest banner,
  new-notes pill, FAB and iOS-side chrome parity.

- 2026-08-28 — APP-008 PoW + APP-022 third increment: NIP-13 end-to-end.
  Shared core: `core/nostr/Pow.kt` (difficulty counting, committed nonce
  tags, chunked mining with byte-exact canonical serialization split around
  the nonce digits) + `NoteComposer.composeTextNoteWithPow` + bridge
  surface (`mineTextNotePow` / `powTextNoteEventId` /
  `powTextNotePublishMessage`); BusinessCore.xcframework rebuilt. The
  parity test caught a real bug on first run — the chunked serializer
  dropped the tags-array closing bracket; fixed and locked by
  recomputing every mined id through `NostrEventCodec.computeId`
  (7 common tests on JVM + native macOS, incl. chunk-exhaustion/resume
  and bounds). UI: `PowCard`/`PowBadge` on both platforms (slider 0–30,
  segmented hash viz, chunk driver on background dispatcher, cancel /
  retry / exhausted states, nonce+id result display); adopted in both
  note composers — publish path branches to `publishPow(With)` carrying
  the mined nonce + fixed timestamp (a nonce is valid for exactly one
  template; card identity resets on content/target change). Android
  publisher round-trip contract test: frame decodes through the verified
  gate with the committed nonce tag, mined id meets target, signature
  verifies. Verified: androidHost + macosArm64 suites, Android main/test
  compile, iOS swiftc typecheck/parse, pbxproj lint (JVM executor spawn
  still sandbox-blocked — CI runs it). Also noted pre-existing Kotlin
  warning `width?.toInt()` in bridge media path (line ~597) — cleanup
  candidate, untouched here. Next: GifPickerSheet or start APP-004
  (filter menu + mode tabs — AppMenu now unblocks it).

- 2026-08-28 — APP-022 second increment: AppMenu system on both platforms.
  iOS `DesignSystem/AppMenu.swift`: entry model + pure `AppMenuLayout`
  (preferred below-right, shift-left, flip-above, margin clamp — legacy
  `showAt` parity) + popover card (menuPop shadow, menuItem 13/w600,
  44 pt rows, checkmarks, destructive tint) + `.appMenuHost` screen
  modifier + `AppBottomSheetMenu` + `AppMenuAnchorButton` (reports global
  anchor at tap). Android `ui/components/AppMenu.kt`: same entry model +
  `AppMenuDropdown` (native DropdownMenu anchoring/clamping — platform
  decision per parity audit) + `AppBottomSheetMenu`.
  Adoption: note ⋯ menus migrated off system dialogs — iOS HomeView
  confirmationDialog → clamped popover at the trigger (mute + 3 report
  reasons, destructive styling); Android FeedScreen AlertDialog →
  anchored dropdown at the rail (same entries); `onMore` plumbing replaced
  by explicit `isMuted/onMuteToggle/onReport` (SRP, TextNotePage drops
  unused param). Layout contract locked by `AppMenuTests` (6 vectors:
  below-right fit, right-edge shift, bottom flip, flip+top clamp,
  near-full-width leading clamp, size estimation). Verified: Android
  main+test compile ✅, iOS typecheck/parse ✅, pbxproj lint ✅ (test
  execution stays CI-side — sandbox limits noted in previous entry).
  Next APP-022: PowCard/PowBadge (needs NIP-13 compose in shared core
  first), then AttachmentPreviewRow + skeletons as feed surfaces adopt.

- 2026-08-28 — APP-022 first increment: hex identity system shipped on both
  platforms — `HexShape` / `HexIdentity` / `HexAvatar(View)` / `HexIcon`
  (`apps/ios/BitOS/DesignSystem/HexIdentity.swift`,
  `apps/android/.../ui/components/HexIdentity.kt`); `PubkeyAvatar(View)`
  migrated to the hex clip so every existing call site (feed rows,
  comments, inbox, profiles, discover) carries the motif with zero
  call-site churn. Found+fixed: Android identicon seed used
  `toIntOrNull(16)`, null for 8-hex prefixes ≥ 2³¹ (e.g. `ffffffff…`)
  collapsing those pubkeys to hue 0 — now a Long parse; regression tests
  added on both platforms (iOS `HexIdentityTests`, Android
  `HexIdentityTest`). Verified: Android main+test compile (Gradle test
  executor spawn is blocked in this sandbox — JVM suites run on CI/dev
  box; expectations independently verified), iOS `swiftc -typecheck`
  clean (no iOS platform runtime installed here — xcodebuild gates run
  in CI). Next APP-022: AppMenu popover, PowCard.

- 2026-08-29 — APP-008 functional caret mentions + old-app string parity
  (legacy Flutter `create_view`/`create_controller`/`_MentionField` studied
  line-by-line). Shared: additive bridge `composerIsComposingMention`
  (distinguishes "no @query" from a bare `@` — `composerMentionQuery`
  collapses both to ""; the bare `@` must surface the unfiltered candidate
  list, cap 6) + bridge contract test (incl. UTF-16 cursor-offset vectors).
  iOS: new `ComposerTextEditor` (cursor-tracking UITextView wrapper — real
  caret in UTF-16 units matching `ComposerRules` indices, focus reporting,
  growing field `maxLines: null` parity, programmatic edit token with the
  legacy `_textWorker` refocus-after-insert behavior) replaces TextEditor;
  mention detect/insert, hashtag and emoji inserts now operate at the caret
  (previously end-of-text approximation); counter/canPublish switched to
  UTF-16 length (Kotlin parity); candidate panel gains the 20 % outline.
  Android: fixed the always-visible suggestion panel (`mentionQuery ?: ""`
  returned all profiles when not composing) — candidates now gate on focus
  + active query; same outline parity. Both platforms: copy aligned to the
  legacy locale (`Create Post`, `Post`, `Post a note…`, `Posting as you —
  notes are signed with your key`, `Published!`, CW hint `e.g. NSFW,
  Spoiler...`, `Uploaded X of Y`, URL dialog title + invalid-URL message).
  Repaired pre-existing pbxproj corruption that made xcodebuild unable to
  read the project at all (duplicate PBXBuildFile/PBXFileReference IDs for
  DmStore/DmScreen/BitzView/BookmarksView, PBXBuildFile defs squatting on
  the tests target's phase IDs 905/906, malformed stories fileRefs, and
  three build files missing from the app sources phase); audit script
  confirms zero duplicate/dangling IDs and `xcodebuild -list` opens again.

- 2026-08-30 — Dropdown popover rows full web-MenuItem parity (user ask:
  rounded option items like the web version). Web source of truth
  (`bitos-nostr-web` Popover/MenuItem/MenuDivider Svelte): panel =
  rounded-xl p-1.5 + shadow-pop; each row = rounded-lg (8) pill whose
  fill appears on hover (white 6% normal, error tone destructive).
  iOS `AppMenu.swift`: rows now carry a `Radius.sm` press pill
  (`AppMenuItemPressStyle` — press = web hover; destructive keeps the red
  label but its bg only fills on press, web rest state is flat), divider
  inset 12→6 to span the padded content width like web's 1px rule.
  Android `AppMenu.kt`: `AppMenuDropdown` drops Material3
  `DropdownMenuItem` (square edge-to-edge highlight) for custom
  `AppMenuPillRow`s — rounded-8 clip, press fill via interactionSource
  (no ripple; the fill is the feedback), 6 dp inset column, shared
  `AppMenuRowContent` with the sheet row. Author-profile pages migrated
  onto the shared popover (they were the only raw menus left): Android
  `AuthorProfileScreen` raw DropdownMenu → `AppMenuDropdown` (report
  flagged destructive, mute icon flips with state); iOS
  `AuthorProfilePage` system `Menu` → `AppMenuPresentation` popover via
  the ProfileView anchor-button pattern. All other popover call sites
  (Home/Inbox/Bitz/Profile more-menus) inherit the pill styling through
  the shared components. Verified: iOS `swiftc -typecheck` clean for all
  touched files (sole remaining error is the concurrent session's
  in-flight FeedStore.swift, untouched here); Android Kotlin frontend
  clean for all touched files (module build fails only on the concurrent
  session's `TextOverflow.Middle` in SecretKeyField/OnboardingScreen,
  untouched here). No behavior changes — styling + component routing
  only.

- 2026-08-30 — Follow-up (user report): popover WIDTH must auto-fit the
  text labels like the old dropdowns, not render as one fixed card.
  iOS `AppMenuLayout.width` (fixed 248) replaced by
  `width(entries:)` — widest label measured with fixed 13/w600 font
  metrics + row chrome (icon slot, 12×2 row padding, 6×2 card inset),
  clamped to [208, 280] (floor = web Popover `min-w-52`). Keeps the
  clamp math pure/deterministic; AppMenuTests updated (floor assert +
  fit/cap cases; bounds sanity-checked against real SF metrics: 16×M →
  253, 60×M → cap 280). Android dropdown column gains the same
  `defaultMinSize(minWidth = 208.dp)` floor under its intrinsic width
  so short menus stay uniform-width. Android compile green; iOS
  typecheck green for touched files.

- 2026-08-28 — Merged `app-flutter-feature.md` + `app-web-feature.md` +
  `DESIGN_SYSTEM.md` into `app-unified-feature-spec.md` (APP epic, 24
  surfaces, union NIP/kind/model tables, wave order). Created this tracker
  with baseline statuses audited against `apps/ios` + `apps/android` (W0
  foundation: shell, feed pager + players, composer publish, thread
  comments, discover search, inbox rows, profile + edit, zaps LNURL,
  bookmarks NIP-51, camera/import/trim/publish, mute/report/follow/repost).
  No UI code changed in this session — tracker + spec only.

---

## Cross-references

- Spec: `app-unified-feature-spec.md` (surfaces §3, components §4, models §5, NIPs §6, waves §8)
- Infra ledger: `delivery-plan.md` (SBC/PRO/ID/DAT/REL/FED/CAP/MED/EDT/MEM/PUB/SOC/BE/OPS/QAR/GOV)
- Parity decisions: `web-parity-audit.md` (WebLN rejected; communities/calls gated; `/pulse` reference-only)
- Product IA: `product-system.md` (five tabs, audience modes)
- Engineering rules: `../engineering/` (clean code, testing, workflow)
