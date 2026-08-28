# BitOS Nostr Web — Full System Feature, UX/UI, Route & Data Model Reference

> **Superseded as implementation authority** by
> [`native/app-unified-feature-spec.md`](./native/app-unified-feature-spec.md)
> (merged web + Flutter single source; track work in
> [`native/native-ui-build-tracker.md`](./native/native-ui-build-tracker.md)).
> This file is preserved as the web-era audit record. Native decisions may
> reject web mechanisms — see [`native/web-parity-audit.md`](./native/web-parity-audit.md).

> **App:** `bitos-nostr` v0.6.x — a local-first Nostr social client (feed, chat, reels, meme studio, zaps)
> **Stack:** SvelteKit 2 · Svelte 5 (runes, `ssr = false`) · Tailwind CSS v4 · `nostr-tools` · Vitest + Playwright · PWA service worker
> **Brand:** "Pulse" design system — electric-blue primary, hexagon identity language, Space Grotesk + JetBrains Mono
> Sources of truth: `src/routes/**`, `src/lib/**`, `src/app.css`, `src/lib/theme/colors.ts`

---

## 1. System Overview

BitOS is a **browser-only Nostr client** fusing three lineages:

| Source | Contribution |
| --- | --- |
| `bitdigo/notes/web` (BitOS Notes) | Visual language, settings UX, relay model (read/write flags + health), appearance/account settings |
| `school-erp-svelte` | Component pattern: Svelte 5 runes, `tailwind-variants` buttons, offline Lucide registry, `--ui-*` surface tokens, desktop-rail + mobile-tab shell |
| Nostr protocol | NIP-01/02/04/17/18/19/29/30/38/40/51/56/57/65/68/71/78, NIP-13 PoW, NIP-47 NWC, WebLN |

**Core principles**

- **Local-first identity** — keys live in `localStorage`, never leave the device (sign on-device only).
- **No backend dependency** — all social data flows relay↔browser via a `SimplePool` singleton (`lib/nostr/pool.ts`); optional server fallbacks only for media upload + icon search.
- **Outbox durability (PUB-012)** — signed events are kept in a local outbox until `minAcks` relays ACK the *same* event id; retries re-send, never re-sign.
- **Zero custom-kind rendering** — creative output (memes) is burned into pixels; metadata rides in versioned, tolerant-parse tags.
- **Guest-first browsing** — Home, Bitz, Discover work logged out; auth-gated surfaces render locked.

---

## 2. Complete Route Map

| Route | Page | Auth | Layout | Description |
| --- | --- | --- | --- | --- |
| `/` | Home Feed | Public (composer needs login) | 3-col shell + right rail | Live kind-1 timeline, For you / Following, stories bar, filters, zap strip, algorithm ranking |
| `/welcome` | Onboarding | Public | PublicShell | Intro → create key (reveal nsec backup) / import nsec |
| `/messages` | Chats & DMs | **Protected** | Full-width multi-pane | NIP-17/NIP-04 DMs, NIP-29 group chats, WebRTC voice/video calls |
| `/notifications` | Notifications | **Protected** | Shell | like/comment/repost/follow/mention/zap activity feed with filters |
| `/bitz` | Bitz Reels | Public | Full-width immersive | Short-video/image reels (NIP-71/68), 5 tabs, author mode, remix, comments |
| `/studio` | Studio Home | Public | Shell | Create hub: resume slots, drafts, saved meme templates, batch queue |
| `/studio/create` | Create (full-bleed editor) | Public (publish needs login) | **Breakout** (no container clamp, hides tab bar) | Tabbed launcher: Meme Studio + Bitz composer |
| `/discover` | Discover | Public | Shell + right rail | Search everything: notes, media, people, tags; trending rails |
| `/communities` | Communities | **Protected** | Shell | NIP-29 group list, browse/join, group chat UI |
| `/zaps` | Zaps Ledger | **Protected** | Shell | All/Received/Sent zap activity, wallet connect, deposit/withdraw invoices |
| `/bookmarks` | Saved | **Protected** | Shell | Locally-stored bookmarked notes |
| `/profile` | My Profile | **Protected** | Shell | Own ProfileView |
| `/profile/[pubkey]` | User Profile | Public | Shell | ProfileView for `npub1…` or hex param |
| `/note/[id]` | Note Detail | Public | Shell | Note thread + replies + hydrated comments; video notes deep-link into Bitz player |
| `/settings` | Settings index (mobile) | **Protected** | Full-width multi-pane | iOS-style grouped index; desktop shows Account section |
| `/settings/[section]` | Settings section | **Protected** | Full-width multi-pane | 11 sections (see §5.8) |
| `/more` | You / More | Public | Shell | Account card, wallet tile, Explore/Library/Account tiles, account switcher, legal rows, QR identity |
| `/more/sounds` | Trending Sounds | Public | Shell | Most-used meme SFX; preview + "use in studio" |
| `/pulse` | Premium UI Showcase | Public | Full-bleed (own viewport) | Design-system demo / spec page (`+page.server.ts`) |
| `/about` `/privacy` `/terms` | Legal/About | Public | PublicShell | Static docs |
| **API** `POST/GET /api/media/upload` | Server endpoint | — | — | Cloudinary fallback (signed/unsigned), folder `bitos/nostr/<pubkey>/<purpose>` |
| **API** `GET /api/media/proxy` | Server endpoint | — | — | SSRF-hardened https media proxy (max 3 redirects) |
| **API** `GET /api/icons/search` | Server endpoint | — | — | Iconify search relay (48 icons, cached 1h/24h) |

**Auth model** (`lib/auth/access.ts`): protected prefixes `/messages /notifications /bookmarks /settings /zaps /profile` render `AuthRequired` with per-route copy. Standalone public routes (`/about /privacy /terms /welcome`) use `PublicShell`. Right rail hidden on `/messages /settings /bitz /studio/create` (multi-pane routes own their columns); `/studio/create` is a *breakout* route (full-bleed).

**Nav surfaces**

- Desktop **NavRail** (≥lg): Home, Chats 🔒(badge), Notifications 🔒(badge), Bitz, Create, Discover, Communities 🔒(badge), Zaps 🔒, Bookmarks 🔒, Profile 🔒, Settings 🔒 + "New Note" CTA + account card / guest login card.
- Mobile **MobileTabBar** (<lg): Home, Bitz, Discover, Chats(badge), Activity(badge), You.
- **NetworkBar**: top relay throughput strip (connected/total, checking pulse).
- **Right rail** (≥xl on content routes): NetworkPulse/Stats, Trending now, People you might like, relay widget.

---

## 3. Feature List by Product Area

### 3.1 Identity & Onboarding (`/welcome`)
- Three-step flow: **intro** (freedom-first pitch) → **create** (generate keypair, reveal + copy `nsec`, confirm-backed) → **import** (`nsec1…` or hex, validated).
- Multi-account: `identity.accounts` list, Account Switcher dialog + boot-style switch overlay, per-account profile cache invalidation, settings auto-restore on switch.
- Account actions: reveal/copy npub/nsec, edit metadata (kind 0), avatar/banner upload via media provider, logout, QR code of npub (`/more`).

### 3.2 Home Feed (`/`)
- **Modes:** `For you` (algorithm-ranked) and `Following` (follow graph + followed hashtags, reverse-chron).
- **Live timeline:** subscribes kind-1 notes from configured relays; reactions (kind 7), reposts, zap totals folded into cards; newest-first capped window.
- **New-note promotion:** newly arriving notes are collected into a "Show N new notes" affordance instead of jumping the list; stable visible window keeps read position.
- **Filters** (multi-select popover): All / Originals / Replies / Media / Liked / Mine.
- **Protocol-note hiding:** `isProtocolPayload` classifier hides machine traffic unless "Show protocol notes" pref.
- **Followed hashtags (NIP-51):** kind-30015 interest set → queried + merged into both modes.
- **Discovery (opt-in):** curated `DISCOVERY_RELAY_URLS` queried parallel-progressive for extra candidates (`source: 'discovery'`).
- **Hashtag deep view:** `/?tag=<t>` switches to relay-searched hashtag feed.
- **Stories bar:** NIP-38 24h stories (own + following), ring states, viewer, composer (text-on-gradient + image carousel), story views tracking.
- **Composer:** text + emoji picker + GIF picker + image/video attachments (media providers), **polls** (`poll_option` tags, vote kind 1018, live results + voters), **mentions** (@ autocomplete via profile search, NIP-27 links), NIP-13 **Proof-of-Work** difficulty selector (web-worker mining, live progress), ⌘/Ctrl+Enter send, draft persistence.
- **PostCard actions:** react ❤️ (+ custom emoji), reply (thread), repost (kind 6/16), zap (dialog), comment refresh, copy id, share, report (kind 1984), media lightbox gallery, sensitive-media overlay (NIP-92-based classification), author menus (mute/block), bookmark.
- **Rank explainer:** per-note score breakdown sheet ("Why am I seeing this?") — signal-by-signal score bars.
- **ZapLiveStrip:** live zap ticker across the feed.

### 3.3 Chats / Messages (`/messages`)
- **DMs:** NIP-17 gift-wrap (kinds 1059/13/14) preferred, **NIP-04 fallback** — decrypt on-device, newest protocol wins per conversation.
- **Conversations list:** grouped, unread cursors, day dividers, search, block-list aware; new-chat dialog (paste npub; deep-links `?to=<npub|hex>&answer=<callId>&group=<id>`).
- **Composer:** text, emoji, image attachments (E2E-encrypted upload flow), voice notes, delivery state (`pending/sent/failed`).
- **WebRTC calls (voice + video):** signaling over DMs (`bitos://call-signal?` URI: offer/answer/ice/end/log), ringtone + incoming-call overlay app-wide, browser Notification, expiry (90s), call admission (1 active call), quality HUD (RTT, loss, ICE state, transport), device pickers (persisted), PiP, fullscreen, minimized "restore", screen-share/self-view drag, raise-hand for groups.
- **Group chats (NIP-29):** dedicated group relay, kind-9 messages `h` tagged, replies (10), admin controls 9000–9007, join/leave 9021/9022, metadata/members/admins 39000–39002; group rename, invites (share link), unsend (own message delete request).
- **Privacy gating:** DM unread badge + call alerts suppressed when notification privacy off.

### 3.4 Bitz — Short Reels (`/bitz`)
- **Sources (codec):** kinds 20 (NIP-68 picture), 21/22 (NIP-71 video), 34235/34236 (addressable video, dedupe by `kind:pubkey:d`), `imeta`-first media parsing with fallback renditions (`fallbackrendition`), url/x/dim/duration/thumb/alt.
- **Tabs:** Explore (grid) · Following · For you (ranked) · Trending · Most zapped.
- **Player:** snap-scroll vertical feed, autoplay w/ mute memory, batched rendering (5 + prefetch threshold), cached reel list (`bitos:reels-cache:v3`), dwell-time capture for ranking.
- **Author mode:** `/bitz?author=<npub>` — one creator's reels, back-to-profile bar, all actions intact.
- **Actions rail:** like, comments panel (kind 1111 + 1 replies), zap dialog, share, **Remix** (handoff to Meme Studio), report, report-sent delete for own.
- **Reel extras:** PoW badge, multi-rendition fallback, GIF-as-image reels, sensitive overlay, web-share target inbox (`share-inbox`).
- **Explore grid:** 24 initial + 18/page masonry-style tiles; pictures keep ranked order.

### 3.5 Studio & Meme Studio
- **Studio Home (`/studio`):** "Start something new" (Meme / Bitz / templates), continue-creating slots (resume), work-in-progress drafts, saved templates, tips + batch tip cards.
- **Create (`/studio/create`):** tabbed launcher (meme | bitz), desktop `MemeStudio`, mobile `StudioMobileEditor` (sheet-based), share-target handoff (`studio-handoff` store), remix payload consumption.
- **Meme Studio (creator suite — 40+ components):**
  - **Sources:** drop-zone (image/GIF/video), device files, paste URL, Giphy, **media source library** (recently-used URLs), remote-media fetch, expert clip panel (video-clips.ts), trim panel, frame strip.
  - **Stage:** zoom steps (0.6–1.5×), artboards (source/9:16/16:9/1:1/4:5/custom), drawing surface (pen tool), stage media controls.
  - **Layers:** text overlays (12 max, 300 chars, fonts impact/sans/serif/mono, palette, sizes 3–22%, stroke/shadow), **image layers** (drag/scale/rotate, crop dialog, motion presets from `layer-motion`), sticker picker (Bitz Buddy + Bitzverse props + Iconify search + Nostr emoji packs kind 30030), SVG icon layer picker.
  - **Timing:** timeline dock (multi-track), zoom punches, speed ramps, frame-FX windows, SFX cue list (16 cues, custom uploads, suggestions, waveform), caption sync, shared-sounds picker (NIP-78 marketplace sounds), look picker (color grades), buddy picker, FX picker.
  - **Templates:** local templates, template dialog, **Template Marketplace** (kind-30078 shared templates with zap-price unlock ledger, categories), shared template publish.
  - **Batch queue:** meme batch production queue bar.
  - **Export:** auto/image/GIF/video output formats (gif-encode, video output policy + probe + trim + cut), render pipeline (`export-pipeline`), download.
  - **Publish machine (PUB-011):** `render → verify → sign(+pow) → publish → done` explicit state machine; destinations: **bitz** (NIP-71), **story** (NIP-38+40), **note** (kind 1 w/ imeta); PoW mining phase; publish options (content warning, alt text, hashtags).
  - **Remix chain:** `["remix", id, relay]` tag + `meme` tag JSON payload (portable schema `com.bitos.bitz.meme` v1); RemixChainDialog shows lineage.
  - **AI assists (opt-in consent):** caption suggestions, smart templates, zoom suggestions — `lib/ai/*`, consent stored per-device.
  - **Splits:** lightning address revenue splits on publish.

### 3.6 Discover (`/discover`)
- Universal search (notes/people/tags/media) across configured + discovery relays with progressive-parallel querying; caches (`bitos:discover-cache`, search cache).
- **Tabs:** Notes · Media (gallery + lightbox + video) · People (grid, follow buttons, WoT badges) · Tags (trending rails, follow hashtags).
- Trending content rails, most-zapped, WoT-gated suggestions, sensitive-media overlays, debounced query with cancellation.

### 3.7 Notifications (`/notifications`)
- Aggregates kind 7 reactions, replies/comments (1/1111), reposts (6/16), follows (3), mentions (#p), zap receipts (9735) addressed to me.
- **Filters:** All / Unread / Mention / Zap / Like / Repost / Follow / Comment (with counts), search, mark-all-read, per-type mute (`bitos:muted-notifications`), media previews, deep links into note/threads, video mentions deep-link into Bitz author mode.

### 3.8 Zaps & Wallet (`/zaps`, wallet store)
- Unified ledger: **Received** (authoritative, `#p=me` kind 9735, sender recovered from 9734 description) + **Sent** (local record `bitos:sent-zaps`) + **All** tabs.
- **Wallets:** WebLN (Alby etc.), NWC (NIP-47 custom URI, persisted, capability negotiation), LNURL from profile `lud16/lud06`, balance display, spendable-now indicator.
- Zap dialog per note/comment/reel: amount presets + custom, comment, anonymous option, invoice QR (fallback), receipt confirmation, expiry parser for BOLT11 (minimal parser).
- Deposit/withdraw invoice modals; zap failure safe-guards.

### 3.9 Profile (`/profile/[pubkey]`)
- Banner + avatar, NIP-05 verification badge, Lightning badge, follow/unfollow (kind 3), stats row (notes / following / followers / zaps — tappable), action menu (mute, block, report, copy id, share), edit mode for own profile.
- **Tabs:** Posts · Replies · Media (gallery) · Liked (kind 10030 read) · Reposts · Pinned (NIP-51 10001) · Zaps (history) · **Bitz** (author reels grid → author mode).
- Activity heatmap, ProfileBitzGrid, scroll-reveal sticky mini identity.

### 3.10 Communities (`/communities`)
- NIP-29 group list (joined + browse public listing), join by id, group detail: messages, members/admins panel, unread badges (feed into title + nav), admin: rename/metadata, kick/add, permissions; invites with share links; leave confirm.

### 3.11 Feed Algorithm (user-owned ranking)
- **Surfaces:** `feed`, `reels`, `discover` — each independently enabled/configurable.
- **Signals:** Recency (half-life decay), Engagement (reactions/reposts/replies), Zaps (sats-weighted), Affinity (interaction profile `bitos:algorithm-interaction-profile`), Topics (#hashtag engagement), **WoT** (follow-graph distance via `getWotSet`), internal Novelty + Diversity (penalties for seen-content/author floods).
- **Presets:** Latest / Balanced (default) / Trending / Trusted + Custom; freshness presets Live 1h / Balanced 6h / Relaxed 24h / Chill 3d.
- Stable-window re-ranking (debounced), per-note score breakdown → RankExplainer, settings-sync backup (kind 30078).

### 3.12 Moderation & Privacy
- **Mute list** (`bitos:muted-pubkeys` + NIP-51 sync), **Block list** (blocks DMs/calls/mentions), both flushed on relay change; **followed hashtags** NIP-51.
- Sensitive media overlays (imeta `is-ocw`/content-warning + heuristic), NIP-56 reports (1984), per-type notification mutes, privacy gates for DM badges/call alerts, media privacy rules (`lib/media/privacy.ts`), SSRF-hardened proxy, NIP-65 relay-list publishing (debounced, signature-guarded).

### 3.13 Platform / PWA
- Service worker (cache-first static assets, unregister in dev), manifest + icons + mask, theme-color light/dark, no-flash theme boot script, static boot splash with fade-out, tab-title unread badge `(n) BitOS`, Web Share target (share-inbox), keyboard shortcuts, reduced-motion support, PWA installable.

---

## 4. Page-by-Page Full Detail

### 4.0 App Shell (`+layout.svelte`)

**Boot sequence:** static splash in `app.html` (hex badge + wordmark, dark-mode logo preloaded) → `BootSplash` twin → app mounts → splash fades (400ms). Then preferences/media/wallet/blocks/mutes/hashtags/privacy/relays/identity/algorithm stores `load()`, service worker registers (prod only, unregistered in dev), relay-ACK observer + 15s outbox drain interval start.

**Reactive wiring:**
- Identity change → stop all subscriptions; on account switch also clear caches, reset algorithm prefs + interaction profile, flush moderation lists, reload feed prefs/call settings/relays/wallet, restart contacts/stories/feed/dms/notifications/nip29, NIP-51 sync (mutes/blocks/hashtags), auto-restore synced settings once per pubkey.
- Relay list change → reconnect, restart feed + all subs, re-merge moderation lists, debounced (4s) NIP-65 kind-10002 republish guarded by signature `bitos:nip65-published-sig`.
- Follow list change → stories subscription refresh (stories = me + follows).
- Tab title badge = notifications + DMs (privacy-gated) + community unreads.
- Call-signaling watcher: parses `bitos://call-signal?` DM lines anywhere in the app, 90s expiry, dedup by message id, toast + browser Notification + IncomingCallOverlay, tap → `/messages?to=..&answer=..`.

**Layout matrix:**

| Condition | Result |
| --- | --- |
| `!identity.ready` | BootSplash only |
| `/pulse` | children own full viewport (showcase) |
| standalone public route | PublicShell wrapper |
| protected route + no identity | `AuthRequired` card (per-route copy from `authMessageForPath`) |
| `/studio/create` | breakout: full-bleed main, no bottom padding reserve, tab bar hidden |
| `/messages /settings /bitz /studio/create` | right rail hidden (multi-pane owns columns) |
| `/` `/discover` (guest incl.) | right rail visible at xl |

Global overlays always mounted: `IncomingCallOverlay`, `Toaster`, `AccountSwitcherDialog`, `ConfirmDialog`, `AccountSwitcherOverlay` + popover layer (click-away/ESC).

---

### 4.1 `/` — Home Feed (1,145 lines)

**Purpose:** the live kind-1 timeline with optional algorithm ranking, stories, composer, and hashtag deep views.

**Query params:** `?tag=<hashtag>` (relay hashtag feed), `#composer` hash (focus composer via nav CTA / `bitos:focus-composer` event).

**Structure (top → bottom):**
1. **Sticky tab bar** (blurred canvas): `✨ For you` · `👥 Following` · separator · pinned followed hashtags (# chips) · right controls: refresh/new-notes button (contextual: shows count → reveals), search icon → `/discover` (hidden ≥xl where rail has search), filter popover (sliders icon, active-state highlight).
2. **Filter popover** (multi-select, checkmarks): All · Originals · Replies · Media · Liked · Mine. Selecting shows an **active-filter banner**: `#tag or Filter,Filter · N notes` + relay merge status pill (`Primary relay · merging others…` / `Merged relay results` / `Live timeline`) + Follow/Unfollow tag (tag view) + Clear.
3. **StoriesBar** (auth only) — rings, own "add", seen/unseen states, viewer.
4. **Composer** (auth) / **guest banner** ("Browsing BitOS as a guest" + CTA → `/welcome`).
5. **New-notes pill** (sticky top): "N new notes" with up-to-4 stacked author avatars + author-name summary; click = reveal (pause + prepend), never auto-jump.
6. **Newly-revealed section**: highlighted "New posts · just now" block above the ranked list with "Continue to For you" dismiss.
7. **ZapLiveStrip** — live zap ticker.
8. **Ranked banner** (algorithm on): "Ranked for you · <preset label>" + Tune → `/settings/algorithm`.
9. **PostCard list** — windowed rendering: 15 initial, +12 per batch; hairline dividers; each card carries an optional **rank tag chip** (top signal: label + colored icon) and "explain" → `RankExplainer` sheet (per-signal score bars).
10. **Pagination:** "Show more posts (n next)" → when rendered window exhausted: "Load more posts" / spinner "Loading older notes" / "End of relay results". Infinite scroll triggers: <1,200px → render more, <900px → load older from relays.

**States:** loading (ping dot + "Fetching notes from relays…" + 4 skeletons) · empty feed ("No notes yet — be the first") · empty filtered (context-aware: "Nothing from your follows yet" + Explore Discover CTA, or "No matching notes" + Show all CTA).

**Data sources:** `feed` store (subscription), followed-tag query (NIP-51 tags, ≤16, both modes merged), discovery relays (opt-in pref, For-you only), hashtag relay feed (250ms debounce, primary-first then merge). Dedup by id; protocol-payload notes hidden unless pref. Ranking: only For-you + no tag view; stable visible window; debounced re-rank on activity arrival.

---

### 4.2 `/welcome` — Onboarding (Onboarding.svelte)

**Flow states:** `intro` → `create` | `import`.
- **Intro:** brand pitch (freedom-first), hex brand mark, two CTAs.
- **Create:** generate keypair on-device; reveal screen shows `nsec` + hex with copy buttons + warning "back this up — it cannot be recovered"; confirm → login → `/`.
- **Import:** paste `nsec1…` or 64-hex; validation + decode; error states; login → `/`.

**Model:** `identity.importSecret()` persists `bitos:identity`, multi-account list `bitos:accounts` updated, relays re-seeded.

---

### 4.3 `/messages` — Chats & Calls (5,373 lines)

**Query params:** `?to=<npub|hex>` (open/scroll convo), `?text=` (pre-seed draft), `?answer=<callId>` (auto-answer incoming), `?group=<id>` (group context).

**Left pane (aside):**
- Header: "Messages" display title + subtitle `N unread · N encrypted · N groups`; help button ("Messages help" dialog: shortcuts, encryption explainer, call FAQ); square-pen FAB (New chat).
- Search input (filters conversations by name/content) + pill tabs **All / Unread (badge) / Groups** (lock icon, tooltip "Private groups — end-to-end encrypted, BitOS to BitOS").
- Conversation rows: hex squircle initials for groups (online dot) / squircle Avatar for DMs; name, time-ago, last-message preview (media/voice/call glyphs), unread badge, delivery ticks (mine), blocked/hidden handling; group member-count chip.

**Right pane (chat canvas, hidden on mobile until selected):**
- Thread header: back (mobile), avatar+name (→ profile), online/protocol indicator, **Start voice call** / **Start video call**, conversation details (…) → details dialog.
- Message list: day dividers, chat bubbles (mine vs theirs), reactions, reply-quotes, media attachments (tap → lightbox), **voice notes** with inline play, delivery state marks (pending/sent/failed + retry), NIP-17/NIP-04 protocol note per conversation, call-log entries rendered inline (offer/answer/ice/end → timeline events with join buttons), group sender names/avatars.
- Composer row: attach image · attach file · emoji · text input (autogrow, ⌘⏎ send) · send; attachment previews with remove; upload progress rings; failed-upload retry.
- **Details dialog:** peer identity (npub copy, QR), block/unblock, mute, clear conversation (local), for groups: member list with roles, **add members**, **rename group**, leave group, share invite link.
- **New chat dialog:** paste npub/nprofile; picks from following list; recent conversations.
- **Rename group dialog** (admin).

**Call system (WebRTC over DM signaling `bitos://call-signal?callId&type=offer|answer|ice|end|log&kind=voice|video&from=&groupId=`):**
- Outgoing: admission check (one active call) → pre-call network heads-up (RTT/loss estimate) → calling modal.
- Incoming: modal/ringer with **Answer / Answer with video / Decline**, ringtone (`calls/ringtone.ts`), 90s expiry, "restored" flow after refresh.
- Active call dialog: remote video (contain/cover fit toggle), draggable self-view PiP, **toggle mic / toggle camera / switch to video / screen-share**, quality HUD (state pill: incoming/outgoing/connected/reconnecting; RTT ms, loss %, ICE state, transport), elapsed timer, fullscreen, **minimize** → floating "Restore call" pill with live dot, End call.
- Group calls: participant grid, **raise-hand** with indicators, per-participant mute state; device pickers (mic/camera/speaker, persisted per account `messages-call-devices:`), shortcut hint dismissible.
- Keyboard shortcut hint overlay (first visit).

**Data:** `dms` store (NIP-17 preferred send, NIP-04 fallback; decrypt on device; `bitos:dm-conversations`, removed-list tombstones), `nip29` groups store, `groupSync` (group events over DMs for private groups), `call-admission` + `call-lifecycle` guards.

---

### 4.4 `/notifications` — Activity (935 lines)

**Header (PageHeader "Notifications"):** search input, filter chips with live counts (**All · Unread · Mention · Zap · Like · Repost · Follow · Comment**), "Mark all read" button, live region announcing fresh activity for screen readers.

**Rows:** avatar+name, action verb + target preview (note snippet / media thumbnail via NotificationMedia / zap amount badge), time-ago, unread accent stripe + dot; zap rows show sats; mention rows show snippet; folded zap groups ("+n more zaps on this note"); row overflow menu (mute this type, mute author, block, report); video mentions link into `/bitz?author=`; click → note thread (or thread comment anchor).

**States:** loading skeletons, empty ("No activity yet"), empty-filter ("No zap notifications" etc.), search-empty. Read state persisted (`bitos:read-notifications` + `notification-read-state`), per-type mutes (`bitos:muted-notifications`), privacy gate hides zap amounts when enabled.

---

### 4.5 `/bitz` — Short Reels (2,563 lines)

**Query params:** `?author=<npub>` (author mode), `?focus=<id>` / hash link (jump to reel), `?from=` handoff context.

**Floating top chrome** (no PageHeader by design): wordmark (tap = back to Explore) or **author-mode back-to-profile bar** (avatar + name + Follow); view counter; tabs **Explore · Following · For you · Trending · Most zapped** (floating dark pills over media); search icon (BitzSearch: instant local matches + NIP-50 relay search, store-driven); refresh.

**Explore tab:** video-first masonry grid (24 + 18/page), tile = cover + duration + author + zap/like counts; "Open bitz by <name>" labels; tap → player focused on that reel.

**Player (snap-scroll):**
- **Media canvas:** full-bleed mobile / rounded card desktop; multi-rendition fallback (imeta); sensitive overlay gate; auto-hiding player bar (progress + play/mute) on desktop; global mute-memory (`bitos:reel-muted`) and view-mode (grid/player `bitos:reel-view-mode`).
- **Meta block:** author (→ profile), caption (RichText, NIP-27), hashtags; **chips:** rank "Why this bitz" (algorithm parity), **remix lineage** ("remixed from …" → RemixChainDialog), **sound chip** (primary SFX cue → use in studio / see more), **advisory rights badge** (provenance), **value-split chip** (declared splits graph), PoW badge.
- **Action rail (right edge, TikTok-style):** Remix (first, primary creation action) · like ❤️ (count) · comments (count → BitzCommentsPanel sheet: kind-1111/1 thread, reply, zap comment, refresh) · zap (NoteZapDialog) · share (Web Share/copy link) · lineage chain icon (if remixed) · overflow menu (⋯: whole-video vs crop-to-fill view toggle, View raw note dialog (JSON), Report (NIP-56), Delete own (kind-5, confirm)).
- **Desktop nav cluster:** prev/next bitz buttons.
- Batched rendering (5 visible + 5 batch, prefetch threshold 6); dwell-time tracking feeds reels ranking; caches to `bitos:reels-cache:v3`.

**Dialogs:** Raw note (JSON viewer), Report, Delete, RemixChainDialog (bounded ancestry walk of `remix` tags), NostrEventPreview.

**Author mode:** single creator's reels, same actions; back-bar; entry from profile Bitz tab and note deep-links.

---

### 4.6 `/studio` — Studio Home (StudioHome.svelte)

Sections: **Continue creating** (hero card = newest WIP slot: one-tap resume into exact meme state) · **Start something new** (cards: *Bitz* → opens BitzComposer; *Meme* → MemeStudio; template shortcuts) · **Resume work in progress** (up to **6 slots** — `meme-slots` store, each with label/destination chip bitz|story|note, delete) · **Saved meme templates** grid (use/delete) · **Tips** + **Batch tip** cards (production guidance). Routes creators *into* `/studio/create` via `studio-handoff` store (tab, template, resumeSlotId, remix, soundSeed).

### 4.7 `/studio/create` — Full-Page Editor (222 lines)

URL-driven tabs `?tab=meme|bitz` (deep-linkable from QR/share/home composer/Bitz remix). One surface at a time, **no dialogs**: `MemeStudio` (desktop) / `StudioMobileEditor` (sheet-based, <lg) or `BitzComposer`. ESC/back returns to `/studio` (never feed). Handles: `studioHandoff.take()` (one-shot: remix payload, template seed, resume slot, sound seed), **PWA share-target pickup** (`?shared=1` → drain inbox file → meme tab → strip flag). Breakout layout: full main column, mobile tab bar hidden.

### 4.8 Meme Studio (component suite)

Desktop 3-zone: **sources rail | stage | inspector** + **timeline dock** (footer). Mobile: sheets (StudioSheet) with same state (StudioMobileEditor).

- **Inputs (MemeStudioInputs / DropZone / SourceLibrary):** drop image/GIF/video; device picker; paste URL (remote-media fetch); Giphy search; expert clip panel; **media library** (recently-loaded URLs, one-tap re-pick).
- **Stage:** zoom 0.6–1.5× (persisted), artboard switcher (source/9:16/16:9/1:1/4:5/custom — persisted), drawing surface (pen), stage media controls (play/scrub).
- **Layers:** text overlays (add/edit/drag; fonts, palette, size, stroke, shadow, ≤12/300 chars); image layers (transform, crop dialog, ambient **motion** presets); sticker pickers: **Bitz Buddy** (10 emotions), **Bitzverse** props (8), Iconify search (`/api/icons/search`), **Nostr emoji packs** (kind 30030); SVG icon layers.
- **Inspector (MemeInspectorPanel + tool cards):** per-layer props; **look picker** (color grades); FX picker; speed picker; trim panel; VideoFrameStrip scrubbing.
- **Timeline dock:** multi-track (captions / image layers / SFX cues / zoom punches / speed ramps / FX windows); quick actions; CueWaveform.
- **Sound:** SFX catalog + custom upload (≤16 cues), suggestions (context-aware), shared-sounds marketplace picker (kind-30078), sound dialog; cross-track cue-mix.
- **Templates:** local template dialog; **Template Marketplace** (browse categories, zap-price unlock with local ledger `bitos:template-unlocks`, buy = zap creator, use-after-unlock); publish own template (shared-templates kind 30078).
- **Batch:** MemeBatchQueueBar — queue multiple renders for mass production.
- **AI assists (explicit consent `bitos:meme-ai-consent`):** caption suggestions, smart templates, zoom suggestions.
- **Export & publish:** format picker (auto/image/GIF/video), Render & download; publish stepper phases `idle → rendering → uploading → mining (PoW) → publishing` with live progress; **PublishOptions:** destination (bitz NIP-71 / story NIP-38+40 / note kind-1), caption + hashtags, alt text, content warning, splits (lightning revenue shares); discard-confirm dialog (MemeDiscardDialog); crash/refresh recovery (draft banner).
- **Remix intake:** RemixHandoff payload (media, overlays, cues, look, layers, zoom/fx/speed windows) seeds stage on arrival.

### 4.9 BitzComposer (inline studio)

9:16 WYSIWYG stage, caption overlay mirroring player placement, drag-drop picker empty state, upload progress/error overlays with retry, media meta chips, **trim draft** (in/out points), **cover frame** capture (scrub + poster), hashtag suggestions one-tap, publish → same render→verify→sign(+pow)→publish machine, discard confirmation, PUB-010 recovery banner.

---

### 4.10 `/discover` — Search & Explore (1,817 lines)

**Header (PageHeader "Discover"):** refresh button; results live-region announcement. **Search bar:** "Search creators, hashtags, images, or videos…" + clear button; debounced query; progressive-parallel relay query (configured + discovery relays) with primary-first merge.

**Tabs with counts:** **Notes** (PostCard list) · **Media** (masonry gallery → immersive media viewer: top bar w/ close, prev/next, caption + mobile actions, full MediaPlayer with seek/buffered/speed/volume) · **People** (grid cards: avatar, name, nip05 badge, follow/unfollow, WoT badge) · **Tags** (trending rails; follow toggles → NIP-51).

Below tabs when no query: **Latest notes from relays** section (reverse-chron stream), trending content rails, most-zapped highlights. Sensitive-media overlays; cached (`bitos:discover-cache`, `bitos:discover-search-cache`); WoT-gated "People you might like"; algorithm prefs apply to discover surface ranking (opt-in).

---

### 4.11 `/communities` — NIP-29 Groups (Nip29Groups.svelte, 42-line route)

**List view:** joined groups (unread badges, relay dot), browse public listing (name/members/description), join-by-id dialog. **Group view:** header (back, name, members & admins panel, rename (admin), connected dot), messages (kind-9/10), member chips, admin controls (add/remove members, permissions), **share invite link**, unsend own message (confirm), leave group (confirm). Sync via `nip29` + `groupSync` stores; unread counts feed nav badge + title.

---

### 4.12 `/zaps` — Zap Ledger & Wallet (826 lines)

**Wallet hero:** connection state card — WebLN/NWC connect flow, balance ("sats · spendable now"), disconnect; **Deposit / Withdraw modals**: QR-first invoice (details secondary), amount + memo, expiry handling, wallet-settled confirmation auto-close; LNURL fallback links.

**Stats row (StatTiles):** total received / total sent / zaps received count / average zap. **Tabs:** **All Activity · Received · Sent** (counts). **Ledger:** rows with in/out icons, sats, sender→recipient, memo, time-ago, truncated invoice id, per-row actions (view receipt event, open note). **Export activity** button. Sent zaps recorded locally (`bitos:sent-zaps`) on in-app payment; received authoritative from 9735 receipts with 9734 sender recovery.

---

### 4.13 `/bookmarks` — Saved Notes (88 lines)

PageHeader "Bookmarks / Notes you saved for later". List of PostCards (newest-saved first) from `bitos:bookmarked-notes`; unsave from card menu; empty state; per-account via account-cache clearing.

---

### 4.14 `/profile` & `/profile/[pubkey]` — ProfileView

Param resolution: `npub1…` or 64-hex; `/profile` uses active identity (else "No identity loaded" + Get started CTA).

**Layout:** banner (tap → lightbox) → overlapping hex avatar → name + nip05 ✓ + lightning badge + lud16 chip → bio/website (RichText) → action row (**Follow/Unfollow/Your profile → Edit**, message, zap, action menu: mute/block/report/copy pubkey/share profile link) → **stats grid** (tappable: Posts / Following / Followers / Zaps) → **sticky tab bar** with scroll-reveal mini identity.

**Tabs (with counts):** **Posts · Replies · Reposts · Bitz · Media · Zaps** + overflow: **Liked · Pinned** (pinned = NIP-51 10001). Lazy loads: Zaps history and Bitz grid load on first open. Media tab = MediaGallery (+ ActivityHeatmap). ProfileBitzGrid tiles → `/bitz?author=`.

---

### 4.15 `/note/[id]` — Note Detail (260 lines)

Resolves `nevent/note` id or `naddr` coordinate (`kind:pubkey:d` for addressable). **Bitz redirect:** plain note links to NIP-68/71 media events (no explicit `from`/`returnTo` source) swap into `/bitz?author=…` (replaceState — no redirect loop); explicit sources keep note view.

Header shows truncated id/coordinate + contextual back (`?from=bitz|discover`, `?returnTo=` sanitized). Body: PostCard + **comments thread** (replies kind-1 `#e` + comments kind-1111, hydrated via secondary query with reaction/zap folding, refresh), reply composer. States: Invalid note / Loading spinner ("Loading note from relays…") / Note not found ("Your relays did not return this event").

---

### 4.16 `/settings` + `/settings/[section]` — Settings

**Mobile `/settings`:** iOS index — grouped tinted icon tiles (hero Account; preferences Lightning/Privacy/Notifications/Appearance/Algorithm; content Security & Relay/Media & Uploads/Language & Region; support Help & Support/About). **Desktop:** left nav rail (all sections + logout) + content pane. `/settings/[section]` reuses the same page (section from param, validated → fallback account).

**Sections:**
- **Account:** profile editor (name, display name, about, avatar/banner **upload via provider** or URL, website, nip05, lud16) → publishes kind 0; account card with npub/nsec reveal + copy (nsec confirm-gated); add-account (→ onboarding); switch-account list; logout (confirm).
- **Lightning (LightningSettings):** WebLN enable, NWC URI connect (capabilities display), wallet prefs (default zap wallet, amounts presets), disconnect.
- **Privacy (PrivacyNotificationSettings):** DM/mention/zap notification gates, per-type mutes, read-receipt behavior, media privacy (auto-load rules), sensitive-media default.
- **Notifications:** per-type toggles + mutes, browser Notification permission request, sound toggles.
- **Appearance (AppearanceSettings):** light/dark/system + watcher, **17 accents**, 5 neutrals, font-size scale, compact mode; live preview.
- **Algorithm (AlgorithmSettings):** per-surface (Feed/Bitz/Discover) enable toggles, presets (Latest/Balanced/Trending/Trusted/Custom), freshness presets (Live 1h/Balanced 6h/Relaxed 24h/Chill 3d), per-signal weight sliders (Recency/Engagement/Zaps/Affinity/Topics/WoT) with weight-total readout, relay-discovery toggles, interaction-profile reset, settings-sync backup/restore (kind 30078).
- **Security & Relay (SecuritySettings):** relay manager — add/remove URL, **read/write/primary flags**, latency test + status dots, recommended relay list; NIP-65 auto-publish indicator; key security info; event outbox viewer (pending ACKs).
- **Media & Uploads (MediaSettings):** provider config — **Blossom** (free default `blossom.nostr.build`), **Cloudinary** (key/secret or preset), **S3** (endpoint/credentials), **Server fallback** (auto when unconfigured); default provider picker, upload purpose paths, per-provider test upload.
- **Language & Region:** UI language selector, date/number format preview.
- **Help & Support (SupportSettings):** FAQ, keyboard shortcuts, SupportWidget (LNURL support zap), ContributorsWidget, links.
- **About:** version (synced w/ package.json), links, licenses, tech credits.

---

### 4.17 `/more` — You / More (574 lines)

**Logged in:** identity card (avatar, name, npub, nip05 ✓) → Security shortcut → **wallet tile** (live WebLN balance "spendable now" or "Connect a Lightning wallet" CTA → `/zaps`). **Tile grids:** *Explore* (Communities · Bitz · Trending sounds) · *Your library* (Zaps · Saved) · *Account* (Profile · Settings). **Accounts on this device:** switch list + add. **QR dialog:** npub QR + copy + share. **Meta rows:** About / Privacy / Terms.

**Guest:** freedom-first sign-in card + visible guest tiles (Bitz, Trending sounds) + login CTA → `/welcome`.

Staggered `.rise` entrance animation (disabled under reduced-motion).

---

### 4.18 `/more/sounds` — Trending Sounds (186 lines)

PageHeader "Trending sounds" with back-to-More. Ranked list of most-used meme SFX (aggregated from shared sounds + usage): play/pause preview per row (waveform), "Use in the Meme Studio" button → `studio-handoff` soundSeed → `/studio/create?tab=meme`.

---

### 4.19 `/pulse` — Premium UI Showcase (140 lines + page.server.ts)

Full-bleed design-system demonstration page (owns viewport; bypasses shell). Uses PremiumShell/PremiumSidebar + premium views (HomeView, ExploreView, MessagesView, NotificationsView, ProfileView, RelaysView, ZapsView, SettingsView, BookmarksView) — the reference implementation of the premium layer.

---

### 4.20 `/about` · `/privacy` · `/terms` — Static Docs (PublicShell)

About: mission, stack, credits, links. Privacy/Terms: legal copy incl. relay logging disclosure, media provider terms, Lightning wallet caveats; DocToc sidebar navigation.

---

### 4.21 API Endpoints

- **`GET/POST /api/media/upload`** — GET: `{enabled}` config probe. POST multipart (file, pubkey, purpose∈{note,story,message,profile,test}); 100MB cap; Cloudinary signed (API key+secret) or unsigned (preset); folders `<folder>/nostr/<pubkey>/<purpose>`; returns `{url, provider:'server'}`.
- **`GET /api/media/proxy?url=`** — https-only, SSRF blocklist (localhost/private/link-local/CGNAT/ULA ranges), ≤3 redirects, streams remote media.
- **`GET /api/icons/search?q=`** — Iconify relay (≤80 chars, 48 icons), cache `public,max-age=3600, s-maxage=86400`, 502 → `{icons:[]}` graceful.

---

## 5. Data Model

### 5.1 Nostr kinds used (`lib/nostr/types.ts` `NOSTR_KINDS`)

| Kind | NIP | Use |
| --- | --- | --- |
| 0 | 01 | Profile metadata |
| 1 | 01 | Text notes (+ inline polls via `poll_option`, close via `endsAt`) |
| 3 | 02 | Contact/follow list |
| 4 | 04 | Legacy DM (fallback) |
| 5 | 01 | Delete |
| 6 / 16 | 18 | Repost / generic repost |
| 7 | 25 | Reactions |
| 9 / 10 | 29 | Group chat message / reply |
| 13 / 14 / 1059 | 17/59 | Seal / private DM / gift wrap |
| 20 | 68 | Picture post (image reels) |
| 21 / 22 | 71 | Video / short-form video |
| 1984 | 56 | Reports |
| 30311 | 53 | Live activity (zap compat) |
| 30315 | 38 | User status → 24h stories (+ NIP-40 expiration) |
| 30015 | 51 | Interest set — followed hashtags |
| 30030 | 30 | Custom emoji packs |
| 10001 | 51 | Pinned notes |
| 10002 | 65 | Relay list metadata (auto-published, debounced) |
| 1018 | — | Poll response |
| 1068 | — | Poll definition (alt) |
| 1111 | 22 | Comments (non-kind-1 replies) |
| 9735 / 9734 | 57 | Zap receipt / request |
| 9000–9007 | 29 | Admin controls (add/remove user, metadata, permissions) |
| 9021 / 9022 | 29 | Join / leave group |
| 39000–39002 | 29 | Group metadata / members / admins (addressable) |
| 30078 | 78 | App data: shared sounds (`com.bitos.bitz:sound:*`), shared templates (`com.bitos.bitz:template:*`), settings-sync backup, template marketplace |
| 34235 / 34236 | 71 | Addressable video / short video (`kind:pubkey:d` dedupe) |

### 5.2 Core domain types (`lib/nostr/types.ts`)

- `Profile` — pubkey, name, display_name, about, picture, banner, website, nip05, lud06/16.
- `FeedNote` — id, pubkey, content, createdAt, pow?, tags, replyTo?, reactions[] (emoji/count/byMe/myEventId), repostCount, zapCount, zapTotalSats, source (`configured|discovery|followed-tag`), raw Event?, poll?.
- `PollData / PollOption / PollVoter` — options, votes map, total, myVote, closedAt, voters (cap 100).
- `NotificationItem` — type (`like|comment|repost|follow|mention|zap`), pubkey, targetId/Kind, content, read, amountSats?.
- `DirectMessage / Conversation` — protocol (`nip04|nip17`), delivery (`pending|sent|failed`), peer, readCursor, unread.
- `RelayRecord` — url, read/write/primary/writePrimary flags, status (`unknown|connecting|ok|fail`), latency, checkedAt.
- `Identity` — sk/pk hex, npub, nsec, profile; multi-account `AccountSummary`.
- `RepostTarget` — NIP-18 normalization incl. embedded event + `a` coordinate for addressable.

### 5.3 Meme project schema (`lib/meme/schema.ts`, `com.bitos.bitz.meme` v1)

- `MemeProject` — namespaced schema id + integer version; unknown fields ignored; **ms integer time units**; **normalized 0–1 coordinates** (restores on any media).
- `MemeTextOverlay` (≤12 overlays, ≤300 chars, size 0.03–0.22, 4 fonts, palette), `MemeSfxCue` (≤16, catalog + `custom`), `MemeImageOverlay` (x/y/size/rotate/motionId/startMs/endMs), zoom/speed/fx `Window` tracks, look (color grade) id.
- Published output = standard Nostr media event (NIP-68/71/38) — meme burned into pixels; remix payload in `meme` tag JSON + `remix` tag chain.

### 5.4 Bitz media extraction (`lib/nostr/bitz-codec.ts`)

`imeta` tag is authoritative: `url` (primary), `fallback` mirrors, `fallbackrendition` variants (deduped, hi-q first), `x` sha-256, `dim` WxH, `duration`, `m` mime, `thumb`, `alt`; addressable dedupe via `event-ref` keys.

### 5.5 Relay & delivery layer

- `pool.ts` — SimplePool singleton; typed multi-filter subscribe (1 sub per OR'd filter), `queryPrimaryFirst` / `queryParallelProgressive`, per-relay ACK observer.
- **Event outbox (PUB-012)** — localStorage ring of signed events + per-relay ACK outcomes; graduate at `DEFAULT_MIN_ACKS`; 15s drain re-sends same bytes.
- NIP-65 publish guard: signature of effective relay list vs `bitos:nip65-published-sig`, 4s debounce.

### 5.6 Local persistence (localStorage keys, prefix `bitos:`)

`identity`, `accounts`, `prefs`, `relays`, `relay-telemetry`, `relay-kind-support`, `profiles-cache`, `dm-conversations`, `dm-removed`, `nip29-groups`, `nip29-messages`, `message-groups`, `group-controls`, `processed-group-controls`, `groups`, `bookmarked-notes`, `saved-notes` (legacy), `muted-pubkeys`, `blocked-pubkeys`, `moderation-synced-at`, `followed-hashtags`, `seen-stories`, `story-views`, `read-notifications`, `notification-read-state`, `muted-notifications`, `sent-zaps`, `nwc-wallets`, `media`, `media-settings`, `media-library`, `draft`, `meme-slots`, `meme-sounds`, `meme-templates`, `meme-stage-zoom`, `meme-artboard`, `meme-ai-consent`, `emoji-packs`, `template-unlocks`, `reels-cache` (v3) + legacy v2, `reel-muted`, `reel-view-mode`, `discover-cache`, `discover-search-cache`, `trending-rail-cache`, `trending-tags`, `live-throughput`, `pow-prefs`, `feed-preferences`, `privacy-notification-settings`, `algorithm-preferences`, `algorithm-interaction-profile`, `event-outbox`, `nip65-published-sig`, `settings-auto-restore-account`, `call-settings`, `studio-handoff`, `gif-picker`, `focus-composer`.

### 5.7 Server env (optional fallbacks)

`BITOS_CLOUDINARY_CLOUD_NAME` + (`BITOS_CLOUDINARY_UPLOAD_PRESET` **or** `BITOS_CLOUDINARY_API_KEY/SECRET`), `BITOS_CLOUDINARY_FOLDER` (default `bitos`) → folders `bitos/nostr/<pubkey>/{note|story|message|profile|test}`; upload cap 100 MB; purposes validated.

### 5.8 Settings sections (`lib/settings/sections.ts`)

`account` (hero) · `lightning` · `privacy` · `notifications` · `appearance` · `algorithm` · `security` (Security & Relay) · `media` (Media & Uploads) · `language` · `help` · `about` — each with iOS tint + icon; mobile groups: preferences / content / support.

---

## 6. Design System ("Pulse" + premium BitOS layer)

Defined in `src/app.css` (2,300 lines), `src/lib/theme/colors.ts`, `src/lib/theme/preferences.svelte.ts`.

### 6.1 Color

- **Primary** = electric blue scale `--color-primary-50…950`, signature `#2F95F6` (500); `--color-brand-500` mirrors it.
- **Accents:** mint `--color-accent-300…600` (`#55D69A` — online/success/voice) and coral `--color-warm-400…600` (`#FF755F` — warnings, alerts, unread badges, "add story").
- **Neutrals:** cool blue-gray `--color-neutral-50…950` (ink = 800/900).
- **User accents (runtime-rewritable):** 17 presets incl. Lightning amber `#FFB627`, Signal pink, Relay green, Ice blue, Purple, Orange, Pulse Blue, Mint, Coral, Violet, Amber, Rose, Cyan, Emerald, Fuchsia, Lime (+ neutral variants: slate/gray/zinc/stone/bluegray). Selected accent rewrites `--color-primary-*` at runtime.
- **Semantic aliases:** `--color-ink/-soft/-faint`, `--color-cream(-dark)` map onto flipping surface tokens so ex-ui utilities (`text-ink`, `bg-cream`) work in both modes.

### 6.2 Surface tokens (light ⇄ dark)

Base layer defines per-mode: `--ui-bg`, `--ui-bg-muted`, `--ui-bg-accented`, `--ui-bg-inset`, `--surface-bg`, `--ui-text{,-muted,-dimmed,-toned,-highlighted}`, `--ui-border{,-muted,-strong}`, `--interactive-hover-bg`, `--glow-primary`. Dark mode is **class-based** (`@custom-variant dark`), toggled by preferences on `<html>` with a pre-paint no-flash script in `app.html`. Every component primitive is token-driven — the premium layer flips cleanly.

### 6.3 Typography

- **Display + body:** Space Grotesk (400–700); **mono:** JetBrains Mono (keys, npub, sats, stats). System-font fallback stack.
- `--font-display` for headings (extrabold, tight tracking on big titles), `--font-mono` for all key/amount/code surfaces.

### 6.4 Geometry & layout

- Radii scale: `--ui-radius-sm 0.625rem / md 0.75rem / lg 0.875rem / xl 1.125rem`, `--ui-radius` default lg — the "Pulse rounded feel".
- Container: `--ui-container: 80rem`; `.page-container` 680px centered (+ `--feed` wide variant); shell grid: rail 260px · main flex · right rail 340px (xl).
- Mobile: `h-dvh` viewport, `env(safe-area-inset-*)` paddings, bottom tab bar 4.25rem reserve.

### 6.5 Signature brand language

- **Hexagon system:** `.hex-clip` flat-top hexagon (clip-path + border-radius fallback) — avatars (`.hex-clip` on Avatar frames), HexMark brand badge, HexIcon tiles, PoW segments (`.pow-bar`), nav active state (gradient hex glow behind icon), mask asset `/mask.svg` for squircle avatars (`.mask-squircle`).
- **PoW/hash identity:** PowBadge, PowCard, PowId, HashViz — difficulty visualized as segmented pills.
- **Bitz Buddy + Bitzverse:** 10 mascot emotions (`/static/bitz-buddy/*.svg`) and 8 world props (`/static/bitzverse/*.svg`) as sticker layers.
- **Premium chrome:** film-grain overlay (`.grain`), NetworkBar throughput strip, ambient orbs, glow shadows (`--glow-primary`), LivePill, Sparkline stats.

### 6.6 Component primitives (`lib/components/ui/`)

Avatar (squircle/hex frame, lightning ring, NIP-05 tick), Button (tailwind-variants), Input, Textarea, Badge, LivePill, SegmentedTabs, Toggle, Popover (+ global popover store, esc/outside close), Dialog, Slideover, ConfirmDialog (+ confirms store), Toaster (+ toasts store), Menu items/divider, Icon (offline Lucide registry via `lib/icons.ts`), QrCode, ImageLightbox, ReportDialog, StatTile, WidgetCard, PageHeader, RelayDot, Sparkline, BootSplash, AccountIdentityBadges, AccountSwitcherDialog/Overlay, HexAvatar/HexIcon/HexMark, HashViz, Pow*.

### 6.7 View primitives (`@layer components` in app.css)

`.post-card` (timeline row: canvas-matched bg, hairline bottom border, no shadow — consistent across Home/Discover/Bookmarks/Profile/note), `.story-ring` (unread vs viewed gradient rings), chat bubbles, chat list rows, nav rows, tabs, toggles, reels chrome, masonry, settings nav, `premium-widget-header`, `.rise` staggered entrance (respects `prefers-reduced-motion`).

### 6.8 Theming behavior

Modes: light / dark / **system** (with watcher); persisted `bitos:prefs`; applied pre-paint; `theme-color` meta flips; font-size scaling; accent + neutral swaps; `mode-watcher` integration. Settings sync backs up prefs (kind 30078) and auto-restores once per account on login/switch.

---

## 7. Testing & Quality

- **Unit/component:** Vitest + browser mode (`*.test.ts`, ~70 suites: codec, algorithm, meme schema/export, uploaders, calls, privacy, outbox…).
- **E2E:** Playwright (`test:e2e`).
- **Static:** `svelte-check`, ESLint + Prettier (svelte + tailwind sorting), `check:client-tags` client-tag audit.
- **CI:** `.github/workflows/release.yml`; fixtures under `fixtures/protocol/` for wire-shape regression.

## 8. Directory Cheat-Sheet

```
src/
├─ routes/            # pages per §2 + api/{icons/search, media/{proxy,upload}}
├─ lib/
│  ├─ components/     # ui/, shell/, feed/, bitz/ (meme studio), studio/, groups/,
│  │                  # calls/, media/, profile/, premium/, settings/, support/, public/, auth/
│  ├─ nostr/          # pool, identity, relays, feed, dms, groups, stories, notifications,
│  │                  # contacts, profiles, wallet, nwc, webln, zaps, pow(+worker), bitz-codec,
│  │                  # event-ref, comments, origin-notes, nip65, reports, outbox helpers
│  ├─ meme/           # schema, render, export pipeline, gif-encode, layers, tracks, sounds,
│  │                  # templates/marketplace, remix, emoji-packs, trending, splits
│  ├─ algorithm/      # signals/, presets, pipeline, penalties, diversity, interaction profile
│  ├─ media/          # uploaders (blossom/cloudinary/s3/server), publish-machine, video*, privacy
│  ├─ messages/       # call-admission, call-lifecycle, protocol
│  ├─ stores/         # 40+ reactive stores (see §5.6 keys)
│  ├─ theme/          # colors (accents/neutrals), preferences
│  ├─ ai/             # consent, suggest, smart-templates, extract
│  ├─ settings/       # sections registry
│  └─ utils/          # format, imeta, nip27, mentions, sensitive-media, verification, cn …
├─ app.css            # Pulse design system (§6)
├─ app.html           # no-flash boot, splash, PWA meta
└─ service-worker.ts  # cache-first static shell
```
