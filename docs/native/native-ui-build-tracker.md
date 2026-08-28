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
| APP-002 | Onboarding carousel §3.2 | W1 | ☐ | ☐ | n/a | 4 pages + dots + skip |
| APP-003 | App shell §3.3 | W1 | ◐ | ◐ | n/a | tabs+retention done; re-tap-to-top/refresh on Home+Bitz shipped (X pattern: scroll-to-top, at-top → refresh); badges, lazy mount audit remain |
| APP-004 | Home feed surface §3.4 | W1 | ✅ | ✅ | ✅ | V1 complete: wordmark/apps-grid header, underline tabs + live counts, X-style reveal pill, infinite-scroll pagination (until-REQ, watchdog exhaust), empty/auto-retry (2 s capped, connectivity-gated), relay-error + retry, filter-mismatch Show-all CTA; W2 banner/chips/ZapLiveStrip remain |
| APP-005 | NoteCard + rich renderer §3.5 | W1 | ◐ | ◐ | ✅ | NIP-27 tokenizer + rich body + media/lightbox + NIP-36 cover shipped on text cards; polls/clamping/compact variant remain |
| APP-006 | Stories §3.6 | W3 | ☐ | ☐ | ☐ | NIP-38/40 models + composer |
| APP-007 | Bitz reels §3.7 | W1-W2 | ◐ | ◐ | ◐ | player+rail live; explore grid, tabs, search overlay, comments sheet remain |
| APP-008 | Note composer §3.8 | W1 | ✅ | ✅ | ✅ | legacy-parity full-page composer on BOTH platforms over shared `ComposerRules` (counter/inserts/mention+rewrite/tag derivation) + POLL sheet shipped (shared `PollContract`: kind-1 + `poll_option` tags, 2–6/280/80; compose + tolerant parse + card display; voting/bars await a vote-format decision); PoW gated while picks pending; GIF picker + draft persistence remain |
| APP-009 | Thread §3.9 | W1 | ◐ | ◐ | ◐ | comments exist; X-style threading, naddr resolution, live deltas, reply-bar options remain |
| APP-010 | Discover §3.10 | W1 | ◐ | ◐ | ✅ | search+chips live; results tabs, trending grid, image viewer remain |
| APP-011 | Messages/DMs §3.11 | W2 | ☐ | ☐ | ☐ | NIP-17/44 chat first; calls/groups W3 |
| APP-012 | Notifications §3.12 | W1 | ✅ | ✅ | ✅ | full surface incl. zap sats+sender, deep links, visible-mark-read, per-type mutes, shell badge, media strips + NIP-36 cover, search row, read cursor + blocked-author filtering (both); [W] video-mention deep-links + zap privacy gate remain |
| APP-013 | Profile §3.13 | W1 | ◐ | ◐ | ✅ | own-profile page upgraded (hero cover+avatar, name/npub/NIP-05/about, stats, Edit+Settings, Notes/Replies/Bitz/Reposts window tabs) + view+edit live; hero glass, stats sheets, tabs, completion card remain |
| APP-014 | Zaps wallet §3.14 | W1 | ◐ | ◐ | ◐ | LNURL dialog live; ledger page, LUD-21 poll, tier presets remain; NWC W4 |
| APP-015 | Bookmarks §3.15 | W1 | ◐ | ◐ | ✅ | optimistic toggle+publish live; list page remains |
| APP-016 | Communities §3.16 | W3 | ☐ | ☐ | ☐ | NIP-29 wire + UI |
| APP-017 | More / You hub §3.17 | W1 | ◐ | ◐ | n/a | V1 hub shipped both platforms, opened from the feed apps-grid: profile hero + multi-account switch row + Following/Relays stat tiles + tile groups (live surfaces only) + honest coming-soon tiles + meta rows; remains: QR dialog, wallet tile (APP-014), Communities/Meme tiles (W3/W4), bookmark page link (APP-015) |
| APP-018 | Settings hub + sections §3.18 | W1-W2 | ✅ | ✅ | ✅ | shared settings contract v2 + algorithm contract + native stores; all 12 catalog sections live both platforms incl. full relays manager (CRUD, roles, status dots, NIP-65 publish) and the ranking algorithm (presets/freshness/signal weights driving the live For-You order); remains in later waves: privacy gates + blocked manage (W2/APP-012), theme/font/accent application (APP-023), i18n strings (APP-024) |
| APP-019 | Studio §3.19 | W4 | ◐ | ◐ | ◐ | camera/trim/publish live (CAP/PUB); editor W4 |
| APP-020 | Static pages §3.20 | W1 | ☐ | ☐ | n/a | about/privacy/terms |
| APP-021 | Trending sounds §3.21 | W3 | ☐ | ☐ | ☐ | needs shared-sounds (kind 30078) |
| APP-022 | Component library §4 | W1 | ◐ | ◐ | n/a | avatar/menu/zap sheet + hex geometry (web .hex-clip parity) + BootSplashScreen component retained (not mounted at entry — fast-access decision 2026-08-28); GIF/poll/pickers, PowCard remain |
| APP-023 | Tokens & theming §2 | W1 | ◐ | ◐ | n/a | dark tokens live; launch chain (system splash + boot splash) forced dark-only in BOTH system modes to match the dark-only shell — light-mode light-splash→black-shell cut fixed; light mode, accents, font scale remain (restore #F4F7FB splash + light launch art when light tokens land) |
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

- [ ] 4-page carousel (welcome / keys / relays / zap) + visuals
- [ ] Step counter, dots, Next/Back/Skip
- [ ] `hasOnboarded` persistence; skip → auth

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
- [x] Filter menu popover: All/Original/Replies/Media/Liked/Mine (checks) — AppMenu both platforms; rules in shared core `FeedFilters` (7 common tests) + bridge `feedFilterMatches` for iOS
- [x] Mode tabs For you / Following — sticky row under the app bar: icon + label, Following live ALL-window count, accent underline indicator, hairline divider (both platforms); Following displays real notes now (identity → contacts-resolving → notes)
- [x] App bar: wordmark leading (iOS asset w/ light+dark variants; Android `bitos_branding` density PNGs) + search action → Discover + apps-grid action → You hub (APP-017 More hub re-routes later)
- [x] GuestBanner signed-out (StoriesBar mount point lands W3)
- [x] New-notes pill: floating capsule immediately below For You/Following, overlapping ≤4 avatar stack plus count centered in a circular badge and ↑ icon; tap reveals and returns the notes list to top, hold-while-scrolled (list first-row / pager page-0 visibility drives it), at-top auto-flush; dedup via known-ids (repo contract test: hold/reveal/redelivery)
- [x] New Note extended FAB → composer
- [x] Infinite-scroll pagination: near-end triggers one `until`-REQ (oldest window note, limit 40, sub `bitos-older-N`), footer spinner while in flight, 8 s watchdog marks exhausted (`noMoreOlder`) until refresh; window cap 200 — Android `FeedRepository.loadOlder` + contract test; iOS `FeedStore.loadOlder` + bridge `olderFeedRequest`
- [x] Empty/auto-retry + relay-error + retry: shared `EmptyFeedRetry` policy (2 s exponential, 30 s cap, saturating counter, connectivity-gated re-REQ — 5 common tests) drives both stores; empty states split relay-error ("Can't reach relays" + Retry now), filter-mismatch ("Show all" CTA), no-notes-yet (auto-retry hint + Retry now); `retryNow()` resets the backoff
- [ ] W2: active-filter banner + relay merge pill; pinned hashtag chips; ZapLiveStrip; ranked banner + rank chips
- [x] TEST: filter windows (common), reveal-pill ordering + dedup on merge, empty-feed auto-retry (resubscribe-while-empty + stop-on-arrival), older-page until-REQ + in-flight dedupe (Android repo suite — 14 green; shared lanes 185 green)

### APP-005 — NoteCard & rich renderer (spec §3.5)

- [x] Author row, action bar (like/repost/zap/bookmark/report/mute), repost attribution (W0)
- [x] NIP-27 entity tokens: npub/nprofile/note/nevent/naddr (bare/nostr:/@-prefixed, TLV type-0 hex, invalid inert) — shared `Nip27` tokenizer + 9 common tests (JVM+macOS); bridge `richTokens` JSON for iOS
- [x] Hashtag + link tap targets (profiles/hashtags route to callbacks; external links styled — in-app routing lands with APP-009 threads)
- [x] Media grid ≤9 image tiles + zoomable fullscreen lightbox (video notes own the pager)
- [x] Sensitive cover (NIP-36 tag/label forms on FeedNote) with per-session reveal (imeta is-ocw pending NIP-92 parsing)
- [x] Font-scale-safe Show more/less — line-based 8-line clamp (font scaling can't break it): Android `RichText` gained `maxLines` + `onOverflow` (`hasVisualOverflow`) with a Show more/less TextButton; iOS `ExpandableRichText` measures clamped vs natural height (hidden fixedSize twin + PreferenceKeys, probe stops once offered) — compact card lists only, full-screen pages never clamp
- [ ] Poll display: bars, my vote, voters, closed
- [x] Animated LikeButton (scale-bounce + haptic) per §2.4 — `AnimatedLikeIcon` (Android components: Animatable spring ≈ elasticOut, 300 ms, LongPress haptic on like-only, Solar heart Bold/Linear, `minimumInteractiveComponentSize` 48 dp) + iOS `LikeTapIcon` (spring 0.3/0.35 + light impact on like-only); adopted in both compact card action rows
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
- [x] Publish button state (disabled/spinner) + published state (success → back to feed / new post) — Android composer page; shared `ComposerRules` is the rule source
- [x] Author header (avatar + display name + “Now”) — Android page (profile from the feed store, npub fallback)
- [x] Mention field @autocomplete → nostr:npub rewrite at publish (≤6 candidates, display-name filter; tracked picks rewrite via `ComposerRules.rewriteMentions`; p-tags derive from the rewritten content) — Android page; common tests
- [x] Image grid removable ≤4 (gallery picks + validated image URLs, 96dp thumbs w/ ✕) — Android page; picks upload hash-verified through Blossom BEFORE signing (legacy order)
- [x] Content-warning field (NIP-36 tag + optional reason) — Android page
- [x] Toolbar: image, media-URL, hashtag insert, emoji 32 palette, PoW (badge), CW toggle, POLL sheet (2–6 choices ≤80, question ≤280, live counters; publishes kind-1 + `poll_option` tags + hashtag t-tags through the tags path — both platforms) — GIF picker remains (web service)
- [x] PoW (PowCard: 0–30 slider, hash viz, chunked background mining + cancel/retry) — now tags-aware (template byte-matches the published event incl. derived tags); last-difficulty memory (`PowPrefs`) still pending
- [x] Char counter 4,000/16,000 — shared `ComposerRules.counterState` (grouped label, target switch at soft limit, near/over states); Android ring UI (web parity)
- [x] Draft persistence + discard confirm — shared `ComposerDraftContract` (versioned v1 wire: text ≤16k, ≤4 remote URLs, CW ≤120, ≤8 tracked mentions, pow 0–30; encode clamps, lenient decode → null on corrupt/oversize — 4 common tests incl. hostile clamp + round-trip); adapters: Android `ComposerDraftStore` (SharedPreferences `bitos_composer_draft`, autosave on change, restore on open, clear on publish/new post, BackHandler + close with "Discard draft?" confirm) / iOS UserDefaults via bridge `composerDraftEncode/Decode` (same behaviors, confirmationDialog); local picks are deliberately NOT persisted (ephemeral URIs)
- [x] TEST: poll wire format (PollTest 5) + draft round-trip (ComposerDraftTest 4); PoW nonces verify (existing)
- [x] iOS composer page (full-screen from FAB) + bridge surface — `ComposerScreen` (fullScreenCover; pbxproj registered) with the full parity set; bridge carries `composerCounter/insertHashtag/insertEmoji/mentionQuery/mentionSuggestions/rewriteMentions/composeContent/deriveTags/emojis` + tags-aware `composeTextNoteWithTagsEventId`/`textNoteWithTagsPublishMessage`/`mineTextNotePowWithTags`/`powTextNoteWithTags*`; NotePublisher grew `publishNote(tags:)`/`publishPowNote(tags:)`/`minePowChunkWithTags`; mention detection at end-of-text (SwiftUI TextEditor has no public cursor API — documented approximation)

### APP-009 — Thread (spec §3.9)

- [x] Root fetch + comments list + reply publish (W0)
- [x] Root resolution: shared `EventRefs` parses note1/nevent1/naddr1 (± `nostr:`, TLV author + ≤4 relay hints, lenient-but-strict → null on invalid) + `requestFilter` (by id / newest NIP-33 `#d` version); Discover search treats a ref query as a root fetch and opens the thread sheet on arrival (both platforms)
- [x] X-style threading: shared `ThreadAssembly` — NIP-10 root/parent anchors (`rootAndParent`: markers win, legacy first/last positional), chronological children, depth cap 8 flattening, cycle guard (visited-set; unreachable cycles surface as orphans), missing-parent replies surface at top level flagged, window ≤200 — `FeedNote` carries `threadRootId`/`threadParentId` from the projection; Android `threads` state + indented rows w/ conversation rail + orphan note; iOS `ThreadDisplayItem` via bridge `threadItemsJson` (stable JSON shape) + same rendering
- [ ] Root action row full (reply count, like+z, zap+sats, repost, share, ⋯ raw/delete-own)
- [ ] Live deltas on root AND replies (9735 parsing)
- [ ] Reply bar: media chips, GIF/URL/gallery/PoW options, participant p-tags
- [ ] States: loading/invalid/not-found + contextual back (ref search covers invalid: no REQ issued; not-found copy pending)
- [x] TEST: `ThreadAssemblyTest` (10: markers, positional forms, chronological order, cycle×2 fixtures, orphan surfacing, depth cap, window bound) + `EventRefTest` (5: note/nevent/naddr round-trips built with the internal TLV encoder, invalid → null, request filters); shared lanes 230/230

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
- [x] Expandable search row (name/content) — shared `NotificationFilters.queryMatches` (bounded 64, case-insensitive, author display name participates; common test); UI applies the predicate at the list seam (Android InboxScreen, iOS `InboxStore.sections` via bridge `notificationQueryMatches`)
- [x] Row: zap sats from bolt11 msat + verified 9734 sender (shared `Bolt11` HRP parse + `ZapReceipt.senderPubkey` via the client verified gate; tests incl. tampered/mistargeted descriptions) — title lines show "⚡ 21 sats" / "2 zaps · 1,234 sats"; tap → thread sheet (origin full content) or author sheet on both platforms; unread accent stripe (dot shipped)
- [x] Media strip ≤4×16:9 (distinct image/video URLs, FeedNote classification parity — common test); OriginNotePreview thumb + NIP-36 sensitive cover with per-row reveal + lightbox on both platforms; `Nip36` shared rule now dedupes card + preview flagging
- [x] Visible-mark-read-on-open (1.4 s; late arrivals restart the timer) + per-type mutes (persisted `muted_kinds`, evicted + badge-excluded; mute menu on the Activity header) + unreadCount → shell badge ("9+" cap: Android BadgedBox / iOS .badge) with shell-level account wiring so the inbox subscribes from app start
- [x] Read model remainder: cursor persistence (`NotificationFilters.isRead` — explicit ids ∪ created-at ≤ cursor, marking advances the cursor so relay redelivery never re-rings; persisted `cursor_seconds` in SharedPreferences / UserDefaults; common + repo-contract tests) + blocked-author filtering (shared `BlockList` kind-10004 parser, bounded ≤500, newest verified head wins, rows evicted + arrivals filtered + badge-safe; repo contract test with signed 10004 fixture)
- [ ] [W] video mentions deep-link into Bitz author mode; privacy gate hides zap amounts
- [x] TEST: grouping/aggregation/filters/origin-projection common suites; Android `NotificationRepositoryTest` (signed in-test frames: extraction + follow-dedupe + raw frames, read-state round-trip through the prefs port, origin batch + timeout + tampered-frame reject, +block-list evict/filter, +cursor redelivery/reload) — 7 green; iOS `NotificationBridgeRuleTests` lock the media-strip keys + cursor/query predicates

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
- [x] `relays` manager: shared `RelayListContract` (bounded ≤16, url-unique, ≥1 role; versioned JSON wire; NIP-65 r-tag projection incl. omitted-marker = read+write) + `composeRelayList` — CRUD + per-relay read/write toggles + live status dots + kind-10002 publish to the set's write relays; edits apply to the live pool immediately (add/remove connect/disconnect sockets); persisted managed set boots the pool on cold start (defaults when empty/corrupt); adapter-contract tests both platforms
- [x] Algorithm ranking (origin `algorithm-plan.md` parity): shared `AlgorithmContract` + `FeedRanking.rank` drive the live For-You order on both platforms (presets/freshness/signal weights + mix bar; off = chronological; Following always chronological)
- [x] Privacy v3: sensitive-media default (drives SensitiveCover) + blocked-users manage (kind-10004 head, unblock publishes a new head; blocked set filters feeds + inbox)
- [ ] Theme/font/accent application app-wide (APP-023); language strings (APP-024)

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
| 1 | ✅ **Account switcher** (2026-08-28) — SHIPPED: shared `AccountRegistry` (bounded ≤8, secret-free wire) + Android `AccountRegistryStore`/`SecureKeyStore` pubkey-keyed slots + `IdentityViewModel.switchTo/signOut(deactivate)/removeRegisteredAccount` + iOS `IdentityStore` registry + `IdentityKeychain` slots; switcher card in Settings-account (hex avatar, name/npub, active check, Remove w/ confirm); sign-out now deactivates (slots survive) both platforms. Branded switch OVERLAY still pending (V1 swaps state directly) | done | registry + slots + switcher shipped; overlay + auth-screen one-tap rows remain |
| 2 | ✅ **Privacy store** (2026-08-28) — SHIPPED: shared `PrivacyPrefsContract` (8 gate fields, per-field tolerant, enums validated; sensitive media stays the settings-contract key, push toggles stay the kind mutes — no duplication) + native stores + Account-privacy card (6 toggles) + Interactions card (message/comment permission pickers) both platforms; enforcement footnotes honest (W2 DMs) | done | — |
| 3 | **Profile editor fields** — picture + banner (image_picker → crop → upload w/ progress + local preview), website URL field; optimistic metadata cache + kind-0 publish | name/display/about/nip05/lud16 only | extend ProfileEditSheet both platforms: website field (trivial), picture/banner pickers (needs CAP media path reuse: pick → Blossom upload → URL into kind-0) |
| 4 | ✅ (2026-08-28) — SHIPPED: `RelayListContract` schema 2 `primary` flag (≤1, write-only, first in fan-out) + ⭐ toggle + add-dialog suggestion chips (primal/damus/nos) both platforms | done | — |
| 5 | ✅ (2026-08-28, Android) `EventCache.clearAllCache` + `FeedRepository.clearEventCache`; clear-cache row wipes both. iOS EventStore wipe pending | mostly done | iOS row 5 remainder |
| 6 | ✅ (2026-08-28, Android) presets 1/5/21/100/500/1000. iOS uses a stepper (equivalent). Ledger link → APP-014 | done | — |
| 7 | ✅ (2026-08-28) — SHIPPED: `diversityEnabled` per surface (AlgorithmContract schema 2) + `FeedRanking.applyDiversity` (author-clustering requeue, ≤2 consecutive, guarded drain, never drops — origin diversity.ts parity, deterministic, tested) + Diverse-authors toggle + Reset-to-preset both platforms; stat tiles + preset icons remain cosmetic | done | cosmetic extras only |
| 8 | **Appearance live preview** — sample widget rendering with the picked accent/theme | honest pending rows | ship with APP-023 theming (preview needs real token application to be meaningful) |
| 9 | **Help cards** — Help center / Contact / Report a problem / Feature request (4 cards) + 4 popular articles | FAQ (7) + support/donate + links | static content, needs real destinations — fold into APP-020 static pages (links currently snackbar-only in old app → do NOT fake) |
| 10 | **AboutPage sections** — hero CTAs (Get Started), feature-cards grid, open-source card, legal footer (Terms/Privacy links) | brand card + NIP chips + schema ✓ | APP-020 static pages (about/privacy/terms full text already specced §3.20) |
| 11 | **Media provider picker** — None / Blossom / Cloudinary tiles + config | Blossom (default) read-only row ✓ | [W] per spec; Cloudinary needs server config UI — keep honest row until provider exists |
| 12 | **Security** — same as native (npub/nsec reveal + danger zone; NO biometric in old app either) | parity ✓ | nothing missing vs old app; app-lock stays spec-[W] |

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
  - APP-010 results tabs (Posts/People/Hashtags) + trending mosaic.- 2026-08-28 — APP-008 composer page, legacy-parity port (studied the
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
