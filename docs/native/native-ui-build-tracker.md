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
| APP-001 | Boot, Auth & Identity §3.1 | W1 | ◐ | ◐ | ✅ | create/import/backup gate exist; app icon + native launch + branded boot splash live (legacy parity); missing: switcher overlay polish, masked key echo, guest entry |
| APP-002 | Onboarding carousel §3.2 | W1 | ☐ | ☐ | n/a | 4 pages + dots + skip |
| APP-003 | App shell §3.3 | W1 | ◐ | ◐ | n/a | tabs+retention done; badges, lazy mount audit, re-tap-to-top remain |
| APP-004 | Home feed surface §3.4 | W1 | ◐ | ◐ | ✅ | filter menu + tabs + guest banner + new-notes pill + FAB shipped; wordmark/apps-grid + empty-retry remain |
| APP-005 | NoteCard + rich renderer §3.5 | W1 | ◐ | ◐ | ✅ | NIP-27 tokenizer + rich body + media/lightbox + NIP-36 cover shipped on text cards; polls/clamping/compact variant remain |
| APP-006 | Stories §3.6 | W3 | ☐ | ☐ | ☐ | NIP-38/40 models + composer |
| APP-007 | Bitz reels §3.7 | W1-W2 | ◐ | ◐ | ◐ | player+rail live; explore grid, tabs, search overlay, comments sheet remain |
| APP-008 | Note composer §3.8 | W1 | ◐ | ◐ | ◐ | publish+media live; mention field, GIF/poll/PoW/CW toolbar remain |
| APP-009 | Thread §3.9 | W1 | ◐ | ◐ | ◐ | comments exist; X-style threading, naddr resolution, live deltas, reply-bar options remain |
| APP-010 | Discover §3.10 | W1 | ◐ | ◐ | ✅ | search+chips live; results tabs, trending grid, image viewer remain |
| APP-011 | Messages/DMs §3.11 | W2 | ☐ | ☐ | ☐ | NIP-17/44 chat first; calls/groups W3 |
| APP-012 | Notifications §3.12 | W1 | ◐ | ◐ | ✅ | full surface incl. zap sats+sender, deep links, visible-mark-read, per-type mutes, shell badge (both); media strips, search row, cursor/blocked-filter remain |
| APP-013 | Profile §3.13 | W1 | ◐ | ◐ | ✅ | view+edit live; hero glass, stats sheets, tabs, completion card remain |
| APP-014 | Zaps wallet §3.14 | W1 | ◐ | ◐ | ◐ | LNURL dialog live; ledger page, LUD-21 poll, tier presets remain; NWC W4 |
| APP-015 | Bookmarks §3.15 | W1 | ◐ | ◐ | ✅ | optimistic toggle+publish live; list page remains |
| APP-016 | Communities §3.16 | W3 | ☐ | ☐ | ☐ | NIP-29 wire + UI |
| APP-017 | More / You hub §3.17 | W1 | ☐ | ☐ | n/a | hero+QR+tiles+switcher |
| APP-018 | Settings hub + sections §3.18 | W1-W2 | ◐ | ◐ | ✅ | shared settings contract v2 + native stores; all 12 catalog sections live both platforms (account+profile editor, lightning, privacy, notifications, appearance+accent palette, algorithm, security nsec reveal, media+playback rate, language, relays view+health, help FAQ, about); remain: relays CRUD + NIP-65, theme/font/accent application (APP-023), i18n strings (APP-024), ranking weights (W2) |
| APP-019 | Studio §3.19 | W4 | ◐ | ◐ | ◐ | camera/trim/publish live (CAP/PUB); editor W4 |
| APP-020 | Static pages §3.20 | W1 | ☐ | ☐ | n/a | about/privacy/terms |
| APP-021 | Trending sounds §3.21 | W3 | ☐ | ☐ | ☐ | needs shared-sounds (kind 30078) |
| APP-022 | Component library §4 | W1 | ◐ | ◐ | n/a | avatar/menu/zap sheet + hex geometry (web .hex-clip parity) + BootSplashScreen live; GIF/poll/pickers, PowCard remain |
| APP-023 | Tokens & theming §2 | W1 | ◐ | ◐ | n/a | dark tokens live; light mode, accents, font scale remain |
| APP-024 | i18n en/lo §7 | W1 | ☐ | ☐ | n/a | string tables + wiring |

---

## Detail checklists

### APP-001 — Boot, Auth & Identity (spec §3.1)

- [ ] Boot splash: branded, no spinner flash between branded screens
- [ ] Login: icon+name+tagline block
- [ ] Import field: paste, autofocus, nsec/npub/hex, `head…tail` masking, inline errors
- [ ] Generate / Import actions; guest browse entry
- [ ] Backup reveal: warning card, npub/nsec copy→check rows, identity QR, confirm-backed → Home
- [ ] Account switcher overlay: hex avatars, active check, add/remove
- [ ] Existing-session auto-boot; destructive-switch shows old+new npub
- [ ] SHARED: multi-account registry, NIP-19 decode (✅ done), secrets confined to Keychain/Keystore (✅ done)
- [ ] TEST: import validation vectors; switcher isolation

### APP-002 — Onboarding (spec §3.2)

- [ ] 4-page carousel (welcome / keys / relays / zap) + visuals
- [ ] Step counter, dots, Next/Back/Skip
- [ ] `hasOnboarded` persistence; skip → auth

### APP-003 — App shell (spec §3.3)

- [x] 5 tabs + state retention (iOS TabView/Android NavHost — W0)
- [ ] Lazy tab mounting audit (first-select build, stay alive)
- [ ] Inbox unread badge (DMs + activity, "9+" cap, privacy-gated)
- [ ] Profile tab = own hex avatar, identity-aware
- [ ] Re-tap-to-top on Home; player pause on tab hide (extends existing surface-visibility work)
- [ ] Global overlay hosts: call panel placeholder, toast, confirm dialog, account switcher

### APP-004 — Home feed surface (spec §3.4)

- [x] Vertical video pager + notes list + pull-to-refresh + pagination (W0)
- [x] Filter menu popover: All/Original/Replies/Media/Liked/Mine (checks) — AppMenu both platforms; rules in shared core `FeedFilters` (7 common tests) + bridge `feedFilterMatches` for iOS
- [x] Mode tabs For you / Following (W0 pills; live counts + underline polish pending)
- [x] Search action → Discover tab (iOS header; wordmark logo + apps-grid action still pending)
- [x] GuestBanner signed-out (StoriesBar mount point lands W3)
- [x] New-notes pill: "↑ N new notes" + avatar stack (≤4), tap-to-reveal, hold-while-scrolled, at-top auto-flush; dedup via known-ids (repo contract test: hold/reveal/redelivery)
- [x] New Note extended FAB → composer
- [ ] Empty/auto-retry (2 s backoff capped), relay-error + retry
- [ ] W2: active-filter banner + relay merge pill; pinned hashtag chips; ZapLiveStrip; ranked banner + rank chips
- [x] TEST: filter windows (common), reveal-pill ordering + dedup on merge (Android repo suite; JVM executor sandbox-blocked — CI runs)

### APP-005 — NoteCard & rich renderer (spec §3.5)

- [x] Author row, action bar (like/repost/zap/bookmark/report/mute), repost attribution (W0)
- [x] NIP-27 entity tokens: npub/nprofile/note/nevent/naddr (bare/nostr:/@-prefixed, TLV type-0 hex, invalid inert) — shared `Nip27` tokenizer + 9 common tests (JVM+macOS); bridge `richTokens` JSON for iOS
- [x] Hashtag + link tap targets (profiles/hashtags route to callbacks; external links styled — in-app routing lands with APP-009 threads)
- [x] Media grid ≤9 image tiles + zoomable fullscreen lightbox (video notes own the pager)
- [x] Sensitive cover (NIP-36 tag/label forms on FeedNote) with per-session reveal (imeta is-ocw pending NIP-92 parsing)
- [ ] Font-scale-safe Show more/less
- [ ] Poll display: bars, my vote, voters, closed
- [ ] Animated LikeButton (scale-bounce + haptic) per §2.4
- [ ] Compact variant (bookmarks/search)
- [x] TEST: tokenizer contract (entities/links/hashtags/inert/merge/JSON shape) + NIP-36 projection — shared suites green

### APP-006 — Stories (spec §3.6) — W3

- [ ] Bar: create card, own-first, rings (unseen gradient hex / seen muted), ≤12/author, deletions
- [ ] Viewer: tap-through, progress, per-slide like, auto-advance
- [ ] Composer: 9:16 preview, slide queue strip, sources, caption w/ mentions, gradients, imeta, PoW
- [ ] Mass publish ≤10 + progress strip; Blossom upload before sign
- [ ] SHARED: 30315+40 compose/parse; seen-set persistence
- [ ] TEST: TTL expiry, batch publish idempotency

### APP-007 — Bitz reels (spec §3.7)

- [x] Snap player + author row + action rail + autoplay gating + player pools (W0)
- [ ] Glass top bar: wordmark, pill tabs (Explore/Following/For you, persisted, re-tap=top), search + refresh
- [ ] Explore grid: 3-col 9:16, 24+18 paging, tile footers (counts/duration), skeleton tiles, sensitive covers
- [ ] Video controls: scrubber + seek hint, ±10 s pills, mute memory, hairline progress
- [ ] Double-tap like; swipe next/prev; tap pause
- [ ] Search overlay: local instant + NIP-50 debounced 400 ms (stale cancel), dedup grid, idle/searching/none states
- [ ] Comments sheet: two-level threading, like/zap/reply rows, composer (GIF/URL/gallery/PoW + previews)
- [ ] ⋯ menu: mute/copy/raw JSON/report/delete-own
- [ ] Mode-aware empty states; record entry → camera
- [ ] W2/W3: PoW + remix + sound + split chips; author mode; Trending/Most-zapped tabs; dwell ranking feed
- [ ] TEST: rendition fallback order; search cancel; comment tree guard

### APP-008 — Composer (spec §3.8)

- [x] Text publish + staged media upload + progress + receipts (W0)
- [ ] Publish button state (disabled/spinner) + published→thread
- [ ] Author header; hint field
- [ ] Mention field @autocomplete → nostr:npub + p-tags
- [ ] Image grid removable; AttachmentPreviewRow 64×64 (GIF badge, ✕)
- [ ] Content-warning field (NIP-36)
- [ ] Toolbar: image, media-URL, GIF picker (trending/search 350 ms/recent 24 h), poll sheet (2–6/limits), hashtag, emoji 32
- [x] PoW (PowCard: 0–30 slider, hash viz, chunked background mining + cancel/retry) — shipped in both note composers; last-difficulty memory (`PowPrefs`) still pending
- [ ] Char counter 4,000/16,000
- [ ] Draft persistence + discard confirm
- [ ] TEST: poll wire format; PoW nonces verify; draft round-trip

### APP-009 — Thread (spec §3.9)

- [x] Root fetch + comments list + reply publish (W0)
- [ ] Root resolution: note1/nevent1/naddr coordinate + relay hints
- [ ] X-style threading: top-level + flattened descendants, left border, NIP-10 markers, cycle guard
- [ ] Root action row full (reply count, like+z, zap+sats, repost, share, ⋯ raw/delete-own)
- [ ] Live deltas on root AND replies (9735 parsing)
- [ ] Reply bar: media chips, GIF/URL/gallery/PoW options, participant p-tags
- [ ] States: loading/invalid/not-found + contextual back
- [ ] TEST: threading fixture (orphans/cycles); naddr resolution

### APP-010 — Discover (spec §3.10)

- [x] Search (NIP-50) + topic chips + npub resolve (W0)
- [ ] Results tabs: Posts / People / Hashtags (+follow toggles)
- [ ] Trending mosaic grid + skeleton 18 + load-more + error/empty
- [ ] User rows: NIP-05 badge, follow/unfollow
- [ ] Fullscreen image viewer (zoom, author bar, follow, open-note bar)
- [ ] Debounce + cancel; search cache
- [ ] W2: Media tab + gallery; latest-notes + trending rails; WoT badge
- [ ] TEST: search cancel semantics; tabbed result fan-in

### APP-011 — Messages/DMs (spec §3.11) — W2 (DMs), W3 (calls/groups)

- [ ] Conversation list: rows (avatar/preview/time/unread/delivery ticks), search, tabs All/Unread, empty state
- [ ] New-chat dialog: paste npub, following picks, recents; `?to=` deep link
- [ ] Chat: header (avatar/name→profile, NIP-05, call buttons placeholder), bubbles, day dividers, delivery states
- [ ] Message body: media detection (image/video/file), inline player, lightbox, links, encrypted indicator
- [ ] Input bar: autogrow, attach image/file, emoji, send, previews + progress
- [ ] SHARED: NIP-17 gift-wrap + NIP-44 v2 (audited impl) + NIP-04 fallback; per-convo protocol negotiation
- [ ] TEST: encrypt/decrypt vectors; delivery state machine
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
- [ ] Expandable search row (name/content)
- [x] Row: zap sats from bolt11 msat + verified 9734 sender (shared `Bolt11` HRP parse + `ZapReceipt.senderPubkey` via the client verified gate; tests incl. tampered/mistargeted descriptions) — title lines show "⚡ 21 sats" / "2 zaps · 1,234 sats"; tap → thread sheet (origin full content) or author sheet on both platforms; unread accent stripe (dot shipped)
- [ ] Media strip ≤4×16:9 + overflow; OriginNotePreview image thumb + sensitive cover
- [x] Visible-mark-read-on-open (1.4 s; late arrivals restart the timer) + per-type mutes (persisted `muted_kinds`, evicted + badge-excluded; mute menu on the Activity header) + unreadCount → shell badge ("9+" cap: Android BadgedBox / iOS .badge) with shell-level account wiring so the inbox subscribes from app start
- [ ] Read model remainder: cursor persistence, blocked-author filtering
- [ ] [W] video mentions deep-link into Bitz author mode; privacy gate hides zap amounts
- [x] TEST: grouping/aggregation/filters/origin-projection common suites; Android `NotificationRepositoryTest` (signed in-test frames: extraction + follow-dedupe + raw frames, read-state round-trip through the prefs port, origin batch + timeout + tampered-frame reject)

### APP-013 — Profile (spec §3.13)

- [x] Own profile view + kind-0 edit publish (W0); author sheet for others
- [ ] Edge-to-edge hero: banner 160/200, gradient default + hex pattern, scrim, overlapping hex avatar + ⚡ chip, banner lightbox
- [ ] Glass floating controls: back/share/edit-cover/settings
- [ ] Info block: name, NIP-05, npub copy chip, website/lud16 chips, QR
- [ ] Action row: own Edit+⋯ / other Follow·Message·Zap+⋯ (mute/block/report/copy/share)
- [ ] Stats row + follower/following sheets (lists + tabs)
- [ ] About expandable; completion card (score bar → editor)
- [ ] Sticky tabs: Notes/Replies/Bitz/Reposts (+loading/empty per tab)
- [ ] Guest state; npub/hex param resolution
- [ ] W2: Media gallery + heatmap, Liked/Pinned/Zaps tabs, mini identity
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
- [ ] Page: saved list (compact cards, newest-saved first), unbookmark, empty, relay re-fetch on open

### APP-016 — Communities (spec §3.16) — W3

- [ ] SHARED: NIP-29 wire (kinds 9/10, 9000s, 9021/9022, 39000s)
- [ ] Joined list + join dialog (relay+id, invite prefill)
- [ ] Group view: messages, members/admins, rename, invite link, leave, unsend

### APP-017 — More/You hub (spec §3.17)

- [ ] Guest freedom card; profile hero (avatar, name, npub chip, QR, stat tiles)
- [ ] Tile groups Explore/Library/Account per spec
- [ ] Account switch row → switcher sheet
- [ ] Meta rows About/Privacy/Terms

### APP-018 — Settings hub + sections (spec §3.18)

- [x] Hub shipped (V1 core): account hero (npub copy→check), preferences (notifications/sound/haptics toggles persisted via UserDefaults / SharedPreferences `bitos*` keys), version row, sign-out with confirm (identity removal)
- [x] SHARED settings contract v2: 18 `bitos_*` keys (incl. accent color, video playback rate; schema 2), typed enums + immutable snapshot, wire codec (corrupt/oversized → defaults, never crash), normalize/isValid, clear-cache protected device globals, cache-size formatting, shortNpub, deterministic 12-section catalog (hero · preferences · content · support) — common tests on both lanes + iOS/Android adapter-contract tests
- [x] All 12 catalog sections live on both platforms: account (identity + profile editor sheet + storage/clear-cache), lightning (default-zap presets + fine stepper), privacy (media auto-load + protocol notes + honest trust-wave gates), notifications, appearance (theme/font/compact + 15-color accent palette), algorithm (timeline + preview/reactions/protocol toggles), security (npub copy + confirm-gated nsec reveal + danger zone), media (autoplay/quality/playback rate), language (+region: date format, tz), relays (configured list + read/write role chips + live connected count), help (static FAQ accordion), about (schema/version rows)
- [ ] `relays` manager remainder: CRUD, per-relay read/write toggles, NIP-65 publish
- [ ] Theme/font/accent application app-wide (APP-023); language strings (APP-024); algorithm ranking weights (W2)

### APP-019 — Studio (spec §3.19) — W4 quick, V2 full

- [x] Camera capture + preview + trim + media publish (W0, CAP/PUB epics)
- [ ] Studio home: start-new cards, ≤6 resume slots, templates grid
- [ ] Quick editor: tray/multi-import, stage + text overlays (drag/scale/rotate), text sheet (fonts/palette/outline/shadow), undo, export local
- [ ] Publish page: preview, tags, cover strip/settings
- [ ] W4+: bitz composer; timeline/SFX/voice-over; V2 full suite (EDT/MEM)

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
- [ ] BrandQrCode; Nip05Badge; RelayStatusDot
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

- NEXT SESSION (pick either; both scoped in this tracker):
  - APP-012 remainder: notification media strips (≤4 + lightbox), search
    row, origin-preview image thumbs + sensitive cover, cursor persistence,
    blocked-author filtering.
  - `relays` settings manager remainder (APP-018 §3.18): CRUD, per-relay
    read/write toggles, NIP-65 publish.

- 2026-08-28 — APP-018 finished (settings V2, resumed from the interrupted
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
