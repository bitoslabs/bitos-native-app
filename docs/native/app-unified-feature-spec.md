# BitOS Unified App Feature Spec — Single Source for Native iOS + Android

> **What this file is:** the ONE merged reference of every feature surface, UI
> field, widget, state, behavior and data source of the BitOS app — union of
> the legacy web and Flutter client feature audits (merged 2026-08-28; the
> two source docs were removed from `docs/` afterwards and live in git
> history), plus the design tokens (`docs/DESIGN_SYSTEM.md`). It exists so
> the native SwiftUI + Compose implementation can run at mass-production
> speed: open a surface section, implement every checklist item top-to-bottom,
> mark it done in [`native-ui-build-tracker.md`](./native-ui-build-tracker.md).
>
> **Superseded inputs.** The per-platform feature audits are fully merged
> here; when they disagreed, this file's *Native decision* column won. Engineering rules live in `docs/engineering/`; protocol/backend
> ownership in the other `docs/native/` files; the task ledger in
> `delivery-plan.md` (epics SBC/PRO/ID/DAT/REL/FED/CAP/MED/EDT/MEM/PUB/SOC…)
> covers infrastructure. This spec adds the **APP epic** (all user-facing UI
> surfaces) tracked in `native-ui-build-tracker.md`.
>
> Sources of truth: old Flutter app `../bitos-nostr-flutter/lib/` (mobile UX
> patterns, tokens, components), old web app (studio suite, wallet, groups),
> current native apps `apps/ios` + `apps/android` (already-built foundation).

---

## 0. How to use this document

1. Every surface has an **APP-xxx** tracker ID. Progress is recorded only in
   `native-ui-build-tracker.md` — never edit this spec to mark status.
2. Each surface lists its **anatomy** as an implementation checklist
   (top→bottom, left→right = build order). "Fields" = inputs, "widgets" =
   every visible control. States = loading/empty/error/success variants.
3. Platform deltas from the two legacy apps are marked **[W]** (web-only
   feature) or **[F]** (flutter-only). Unmarked = both had it. Conflicts are
   resolved once in the *Native decision* and never re-litigated.
4. Native tier per surface: **V1** (alpha-blocking), **V1.x** (beta),
   **V2** (post-V1 differentiation) — aligned with `delivery-plan.md` phases
   and `web-parity-audit.md` decisions (e.g. WebLN rejected, communities and
   full studio deferred, keys never in plain storage).
5. Architecture invariants while implementing (from `AGENTS.md`):
   SwiftUI/Compose stay native; deterministic rules go in
   `shared/business-core`; views never touch relays/DB/signers directly —
   only through feature stores/view-models.

---

## 1. Product map and navigation

### 1.1 Native tab shell (authority: user decision 2026-08-28 — legacy parity)

Six tabs — **supersedes** the 5-tab map in `product-system.md` §1:

| Tab | Surface | Absorbs (legacy routes) |
|:--|:--|:--|
| **Home** | Scrolling notes card list (For you/Following, filter, pill, FAB) | web `/` notes feed, flutter `/home` notes mode |
| **Bitz** | Full-screen vertical video reels pager | web `/bitz`, flutter `/bitz` video mode |
| **Discover** | Search + trending | web/flutter `/discover` |
| **Chats** | DMs (W2 placeholder) | web `/messages`, flutter `/messages`, `/chat` |
| **Activity** | Notifications | web/flutter `/notifications` |
| **You** | Profile + hub entries (Settings/More push here) | web `/profile`, `/more`, `/zaps`, `/settings`; flutter `/profile`, `/more` |

Create/Studio entry points: Home composer FAB, Bitz header record/import,
and the You hub (APP-017). Settings (APP-018) pushes from You. The feed
store is shared between Home and Bitz (one verified stream, client-side
video/notes surface split).

Pushed (non-tab) destinations: onboarding/auth gate, note thread, user
profile, composer, editor/publish pages, settings sections, static pages,
image/lightbox viewers, call panel (overlay), zap dialog (sheet).

### 1.2 Complete destination map (union of all legacy routes)

| # | Legacy route(s) | Surface (spec §) | Native destination | Auth | Tier |
|:--|:--|:--|:--|:--|:--|
| 1 | `/auth` | Boot & Auth §3.1 | Pre-shell gate + account switcher overlay | public | V1 |
| 2 | `/welcome`, `/onboarding` | Onboarding §3.2 | First-launch carousel | public | V1 |
| 3 | shell | App shell §3.3 | Root TabView/NavHost | — | V1 (done) |
| 4 | `/` (notes mode) | Home feed §3.4 | Home notes surface / mode | public (actions gated) | V1 |
| 5 | `/bitz` | Bitz player §3.7 | Home video default + explore grid + search | public | V1 |
| 6 | `/create` | Composer §3.8 | Pushed composer + Home FAB | actions gated | V1 |
| 7 | `/note/:id` | Thread §3.9 | Pushed thread | public | V1 |
| 8 | `/discover` | Discover §3.10 | Discover tab | public | V1 |
| 9 | `/messages`, `/chat` | Messages §3.11 | Inbox → Messages | protected | V1 (DMs), calls V1.x |
| 10 | `/notifications` | Notifications §3.12 | Inbox → Activity | protected | V1 |
| 11 | `/profile`, `/profile/:pubkey` | Profile §3.13 | Profile tab + pushed | public | V1 |
| 12 | `/zaps` | Zaps wallet §3.14 | Pushed from Profile/More | protected | V1 |
| 13 | `/bookmarks` | Bookmarks §3.15 | Pushed | protected | V1 |
| 14 | `/communities` | Communities §3.16 | Pushed (More hub entry) | protected | V1.x |
| 15 | `/more` | More/You hub §3.17 | Profile tab overflow + hub | public | V1 |
| 16 | `/settings`, `/settings/:section` | Settings §3.18 | Pushed | protected | V1 |
| 17 | `/studio`, `/studio/create`, `/meme` | Studio §3.19 | Create tab + full-bleed editor | public, publish gated | V1 quick, V2 full |
| 18 | `/more/sounds` | Trending sounds §3.21 | Pushed from More/Discover | public | V1.x |
| 19 | `/about`, `/privacy`, `/terms` | Static §3.20 | Pushed | public | V1 |
| 20 | `/pulse` | — | **Reference only** (design showcase; do not port) | — | rejected |

Deep links to support: `nostr:` entities (npub/note/nevent/naddr/nprofile),
`lightning:` invoices, nostr: web links → threads/profiles.

---

## 2. Design system (merged tokens — authoritative: `docs/DESIGN_SYSTEM.md`)

> Native decision: **Bitcoin orange `#F7931A` is the brand** (Flutter code +
> DESIGN_SYSTEM.md are authoritative; web's electric-blue "Pulse" is legacy
> and NOT ported). Both native apps already carry these tokens
> (`BitOSTheme.swift` / `ui/theme/Theme.kt`) — extend, don't fork.

### 2.1 Color tokens (dark = default, light = secondary)

| Token | Hex | SwiftUI (BitOSTheme) | Compose | Use |
|:--|:--|:--|:--|:--|
| background | `0xFF0A0A0F` | `background` | `Background` | app canvas (OLED near-black) |
| surface | `0xFF12121A` | `surface` | `Surface` | cards, sheets, nav bars |
| surfaceElevated | `0xFF1A1A26` | `surfaceElevated` | `SurfaceElevated` | elevated cards, modals |
| surfaceOverlay | `0xFF22222E` | `surfaceOverlay` | `SurfaceOverlay` | dropdowns, tooltips, menus |
| primary | `0xFFF7931A` | `accent` | `Accent` | brand, links, focus, CTA |
| primaryLight / primaryDark | `0xFFF9A84B` / `0xFFD4790F` | — | — | hover / pressed states |
| primaryGlow | `#F7931A @20%` | — | — | glow shadows |
| accent (cyan) | `0xFF06B6D4` | `cyan` | — | secondary accent |
| accentWarm | `0xFFF59E0B` | `warning` | — | zaps/lightning amber |
| accentPink | `0xFFEC4899` | `like` | — | likes |
| textPrimary / Secondary / Tertiary | `0xFFF8F8FF` / `0xFF9CA3AF` / `0xFF6B7280` | `textPrimary…` | `TextPrimary…` | text hierarchy |
| textLink | `0xFFF7931A` | — | — | in-content links |
| success / warning / error / info | `0xFF10B981` / `0xFFF59E0B` / `0xFFEF4444` / `0xFF3B82F6` | ✓ | ✓ | semantic |
| like / repost / zap / reply / bookmark | pink / green / amber / blue / orange | ✓ | ✓ | action-icon colors |
| border / borderFocused / divider | `0xFF2A2A3A` / `0xFFF7931A` / `0xFF1F1F2E` | ✓ | ✓ | lines |
| Light theme | bg `0xFFFAFAFC`, surface white, elevated `0xFFF5F5F7`, overlay `0xFFEEEEF0`, text `0xFF111827`/`0xFF6B7280`/`0xFF9CA3AF`, border `0xFFE5E7EB`, divider `0xFFF3F4F6` | — | — | mode flip |

Gradients: `gradientPrimary` orange→amber TL→BR (brand), `gradientZap`
amber→orange (zap surfaces), `gradientDark` transparent→80% black top→bottom
(media scrims), 6 story background gradients (§3.6).

### 2.2 Typography — Inter (+ JetBrains Mono for keys/npub/sats)

| Style | Size/w/height | Notes |
|:--|:--|:--|
| displayLarge | 32 / w700 / 1.2 / ls −0.5 | onboarding heroes, auth title |
| headlineLarge | 24 / w700 / 1.3 | page titles |
| headlineMedium | 20 / w600 / 1.35 | section headers |
| headlineSmall | 18 / w600 / 1.4 | card titles |
| bodyLarge/Medium/Small | 16/14/12 / w400 / 1.5 | note body / rows / meta |
| labelLarge/Medium/Small | 14 w600 / 12 w500 / 10 w500 | buttons / chips / timestamps |
| menuItem | 13 / w600 | popover rows |
| mono | 13 / w400 / ls 0.5 | npub/nsec/ids/sats |

SwiftUI: custom `Font` set; Compose: `Typography` override. Respect system
font scaling to 200%.

### 2.3 Geometry

- **Spacing (4-pt grid):** xs 4 · sm 8 · md 12 · base 16 · lg 20 · xl 24 ·
  xxl 32 · xxxl 48; semantic: screen 24 · card 16 · sectionGap 32 ·
  listItem 8 · iconText 8 · avatarGap 12.
- **Radius:** xs 4 (chips) · sm 8 (buttons/inputs) · md 12 (cards) · lg 16
  (sheets) · xl 20 (modals) · full 999 (avatars/pills).
- **Shadows:** cardGlow (primary 5% blur 20 offset (0,4)) · elevatedGlow
  (10% blur 30 (0,8)) · menuPop (dark 0/16/48 @50%) · bottomNavShadow
  (black 30% blur 20 (0,−4)). Dark mode uses glow, not gray shadows.
- **Avatars:** xs 24 · sm 32 · md 40 · lg 56 · xl 80 · xxl 120.
- **Touch targets ≥ 44 pt (iOS) / 48 dp (Android).**

### 2.4 Motion

Durations: instant 100 / fast 200 / normal 300 / slow 500 / emphasis 800 ms.
Curves: standard easeInOutCubic · enter easeOutCubic · exit easeInCubic ·
bounce elasticOut · decelerate. Required animations: like scale-bounce 300
elasticOut (+haptic) · repost 360° rotate 400 · zap bolt descend+shake 500 ·
double-tap floating heart 800 · FAB scale+rotate 200 easeOutBack · sheet
slide+fade 300 easeOutCubic · page hero transitions 300. Respect
`accessibilityReduceMotion` / `Settings.Global.ANIMATOR_DURATION_SCALE` and
`prefers-reduced-motion` behavior parity.

### 2.5 Signature language

- **Icon system (AppIcons tokens — APP-022):** views never reference an
  icon library at call sites. Semantic tokens (`AppIcons.*`) live in one
  file per platform (`DesignSystem/AppIcons.swift`, `ui/theme/AppIcons.kt`),
  porting the Flutter `app_icons.dart` token architecture. Backing = native
  **SF Symbols / Material Rounded** (free RTL mirroring, Dynamic Type,
  platform conventions, zero bundle). Legacy provenance is kept in token
  comments: Flutter used **Solar** (`solar_icons` Bold/Outline + ~6 Material
  fallbacks), web used **Lucide** — a later swap to bundled Solar vectors
  changes only the token files, never call sites.

- **Hex identity:** hexagonal avatars/clips everywhere (sovereign-ID motif),
  `HexMark` brand badge, gradient hex rings = unseen stories, PoW segmented
  pills. Kind-0 `picture` metadata overlays the identicon only for bounded
  HTTPS non-SVG images; the hex remains the loading/error fallback. SwiftUI:
  custom `Shape`; Compose: `clipShape(HexShape)`.
- **Glass surfaces:** blur-20 frosted floating controls over media (Bitz top
  bar, profile hero controls, glass pill tabs).
- **Skeletons** for every list/grid; shimmer; matched empty states with
  icon + title + subtitle + CTA; `ErrorRetryWidget` pattern.
- **Copy→check affordance** for every key/id chip (npub, nsec, invoice,
  note id): tap copies, icon flips to check for 1.5 s.

### 2.6 Accessibility (hard requirements)

48×48 targets · contrast 4.5:1 body / 3:1 large · semantic labels on all
icons/images · full VoiceOver/TalkBack ordering incl. custom actions for
feed gestures (no gesture-only actions) · reduced-motion support · font
scale to 200% without clipping (font-scale-safe clamping in cards).

---

## 3. Feature surfaces (implementation units)

### 3.1 APP-001 — Boot, Auth & Identity (V1)

**Destination:** pre-shell gate; account switcher = global overlay. **Data:**
IdentityStore/SecureKeyStore (Keychain / Keystore), multi-account registry,
NIP-19 codecs (shared core).

Anatomy — **Boot splash** (legacy Flutter parity, implemented 2026-08-28):
- [x] **App icon**: iOS `Assets.xcassets/AppIcon` (1024pt bolt mark, copied
      from the legacy Runner appiconset); Android adaptive icon
      (`mipmap-anydpi-v26` foreground + monochrome at 16% inset over
      `#0A0A0F`) + legacy `mipmap-*` rasters — identical assets to the old
      app.
- [x] **Native launch screen**: iOS `UILaunchScreen` (`SplashBackground`
      color `#F4F7FB`/dark `#0A0A0F` + `SplashLogo` bolt/wordmark, light &
      dark); Android `Theme.BitOS.Launch` — pre-12 `launch_background`
      layer-list (bg + centered logo, `-night` variants), Android 12+
      `windowSplashScreen*` (vector bolt `bitos_splash_mark` + wordmark
      branding); `MainActivity` swaps to `Theme.BitOS` before Compose
      mounts (legacy NormalTheme parity). 2026-08-31: wordmark assets on
      both platforms regenerate from the official masters
      (`docs/logo-black.png` / `logo-white.png`, geometry-normalized);
      launch is fully themed light/dark again (black wordmark on
      `#F4F7FB`, white on `#0A0A0F`) after the APP-023 dark-only interim.
- [x] **In-app boot screen** (`BootSplashScreen` SwiftUI/Compose port of the
      legacy Flutter widget): bolt in the shared flat-top hex avatar
      (breathing 1±2%/2 s, rotating sweep-gradient orbit stroke, orange
      glow), official wordmark, 9 PoW segments sweeping (delay i·0.12 s, lit
      45% of the 2 s loop), mono status pill; entrance fade+scale 450 ms
      easeOutCubic, hold 900 ms, fade-out 300 ms, then shell mounts. Bolt is
      drawn as bezier Path/vector-drawable (never bundled/executed SVG —
      Safety rule).
- [ ] persists while persisted identity + onboarding state load; never
      flashes a bare spinner between branded screens.

Anatomy — **Login** (no app bar):
- [ ] App icon/rocket 72 pt in `primary` + app name `displayLarge` + tagline
- [ ] Key import field: paste support, autofocus, nsec/npub/hex accept,
      masked echo `head…tail` for short keys, inline validation errors
- [ ] **Generate new identity** action (primary) and **Import** action
- [ ] Account switcher entry (if saved accounts exist) → overlay/sheet with
      hex avatars, names, active check, Add account, per-account remove
- [ ] Guest browse entry (continues to shell read-only; guest banner later)

Anatomy — **Backup reveal** (post-generate, blocking before first publish):
- [ ] Warning card ("cannot be recovered — back this up") `error`-tinted
- [ ] `_CopyableKey` rows: npub + nsec, reveal/copy each (copy→check)
- [ ] Identity QR (BrandedQrCode) "scan QR backup"
- [ ] Confirm-backed-up acknowledgment → continue to Home
- [ ] [W] nsec reveal also confirm-gated (re-tap to reveal) in settings

Behaviors: existing session auto-detected on boot → straight to shell;
import validates → decodes (npub accepted but publish-disabled until nsec);
replace-existing-key flow shows visible npub of BOTH old and new key before
destructive switch; secrets ONLY in Keychain/Keystore (never plain files,
never logs — Safety rule).

### 3.2 APP-002 — Onboarding carousel (V1)

- [ ] 4-page swipeable `PageView`/`TabView` carousel: ① Welcome hero
      ② Keys = identity (key visual; no email/password) ③ Decentralized
      relays (fan-out diagram visual) ④ Zap/Lightning value flow
- [ ] Step counter (n/4), page dots bound to page, Next/Back/Skip controls
- [ ] Reusable: page base, glowing icon, feature chips
- [ ] Skip/finish → Auth (or Home if identity exists); `hasOnboarded`
      persisted; shown only pre-identity

### 3.3 APP-003 — App shell (V1 — foundation built; gaps below)

- [ ] 5 tabs Home · Discover · Create · Inbox · Profile with state
      retention (IndexedStack semantics: never rebuild a visited tab)
- [ ] **Lazy tab mounting** — first selection builds the tab; selected tabs
      stay alive (cold-start work stays on tab 0)
- [ ] Tab icons + labels; active tint `primary`; Profile tab = own hex
      avatar (identity-aware)
- [ ] **Unread badges**: Inbox = DMs Σunread + Activity unreadCount, cap
      "9+"; privacy-gated (§3.18 privacy settings suppress amounts/badges)
- [ ] Re-tap current tab → scroll-to-top (Home: pager to first item)
- [ ] Surface-visibility routing: videos never autoplay offstage (tab
      switch/page hide pauses players) — continue current `RouteAware` work
- [ ] [W] relay throughput strip (connected/total + pulse) as a slim status
      affordance on Home app bar (web NetworkBar) — V1.x
- [ ] Global overlays always mounted: call panel (§3.11), toast host,
      account switcher, confirm dialog host

### 3.4 APP-004 — Home feed surface (V1)

**Modes:** For you (ranked) · Following (reverse-chron) · [W] Latest tab ·
[W] hashtag deep view `?tag=`. Video-first vertical pager (built); notes
surface = cards list.

Anatomy:
- [ ] App bar: wordmark logo (light/dark variants) leading
- [ ] Centered content-filter trigger → popover menu (screen-clamped):
      All · Original · Replies · Media · Liked · Mine — checkmarks,
      multi-select on web / single-select mobile → keep single+All [F]
- [ ] Search action → Discover tab; apps-grid action → More hub
- [ ] Sticky mode tabs: ✨ For you · 👥 Following — underline indicator;
      no note-count label (user decision 2026-08-30)
- [ ] [W] pinned followed-hashtag chips (NIP-51 interest set) after tabs
- [ ] StoriesBar (§3.6) as list header scrolling WITH content (not pinned);
      GuestBanner when signed out ("browsing as guest" + sign-in CTA)
- [ ] [W] active-filter banner: `#tag or Filter,Filter · N notes` + relay
      merge status pill ("Primary relay · merging others…" / "Merged" /
      "Live timeline") + Clear
- [ ] Note card list (§3.5): pull-to-refresh, pagination (infinite scroll +
      explicit load-more), skeleton rows, empty states (no notes yet / no
      follows yet + Discover CTA / no filter match + Show all CTA),
      relay-error state + retry, stale-while-revalidate (cached first)
- [ ] **New-notes pill**: floats immediately below the mode tabs, up-to-4
      author avatars plus a count centered in its circular badge; tap reveals
      (prepend) and returns the notes list to its top so the new cards show
- [ ] [W] ZapLiveStrip live zap ticker under tabs — V1.x
- [ ] [W] ranked banner "Ranked for you · preset" + Tune → settings
- [ ] [W] per-card rank chip (top signal) → RankExplainer sheet (per-signal
      score bars) — V1.x (needs FED-007)
- [ ] New Note extended FAB (bottom-floating) → composer §3.8
- [ ] Empty-feed auto-retry (2 s backoff, capped, relay-connectivity-gated)

Data: kind 1 (+20/21/22/34235/34236 video kinds — already subscribed),
reactions/reposts/zap totals folded into cards, protocol-note classifier
(hide machine traffic unless pref), followed hashtags merged, discovery
relays opt-in (For you only), dedup by id, newest-first capped window.
Kind-0 author metadata is batched (≤48): query the primary connected relay
first, then after 900 ms query only unresolved authors on fallback relays.
The default primary is `wss://nostr-01.yakihonne.com`; it is first in the
native default relay order and default NIP-65 write fan-out order.

### 3.5 APP-005 — NoteCard & rich content renderer (V1) — shared component

- [ ] Author row: username-initial hex avatar (→profile) · Lightning badge
      when `lud16` is available · display name (+fallback) · NIP-05 claim
      marker · time-ago · ⋯ menu (mute author · block · report · copy
      id · [W] raw JSON) · repost attribution ("↻ Reposted by X" when kind 6)
- [ ] Rich body — **NIP-27 entity rendering**: npub/nprofile/note/nevent/
      naddr, bare + `nostr:`-prefixed + `@`-prefixed mentions; TLV relay/
      author hints; invalid entities render inert as text; hashtags tappable
      (→ hashtag view); links tappable (in-app for nostr, external via
      system browser)
- [ ] Media grid ≤9 (image/video): tiles, GIF badge, inline video player
      for single video, tap → lightbox gallery (zoomable, swipe, save/share)
- [ ] Sensitive-content cover: NIP-36 content-warning + [W] imeta `is-ocw`
      classification → gray cover + "Sensitive content" + reveal button
      (per-session reveal)
- [ ] Font-scale-safe clamping: >N lines → "Show more"/"Show less"
- [ ] Action bar: reply (count) · repost · like (+count, animated §2.4) ·
      zap (+sats optimistic) · bookmark · share · (order per DESIGN_SYSTEM
      8.2) — optimistic state via shared NoteActionsService-equivalent,
      reconciled by relay echo
- [ ] Poll rendering [W+F]: options with live bars, my-vote state, voter
      avatars (cap 100), closed state (§3.8 composer pair)
- [ ] Compact feed variant (bookmarks/search results)

### 3.6 APP-006 — Stories system (V1.x — full spec ported from Flutter)

**Bar** (Home header): create-story card (own, first) · own + following
order · gradient hex ring = unseen, muted ring = seen (persisted per
author) · [W] "Public stories" discovery row + load-more · ≤12
slides/author · kind-5 deletions honored · tap → viewer; own card →
composer.

**Viewer**: full-screen, tap right/left = next/prev slide, tap-through
progression bar, per-slide like (kind 7 on slide id), author header →
profile, auto-advance, close.

**Composer sheet**:
- [ ] Live 9:16 preview: image + caption scrim OR gradient + bold text
- [ ] Slide-queue strip: numbered thumbs, order badges, per-thumb remove ✕,
      dashed "+" add slot, tap-to-preview, chevron paging
- [ ] Sources: camera capture · gallery · GIF picker · media-URL attach
- [ ] Caption field: @mention autocomplete (→ `nostr:npub…` + p-tags) ·
      hashtags · 32-emoji palette · 6 gradient backgrounds (`background` tag)
- [ ] NIP-92 imeta tags · NIP-13 PoW card before signing
- [ ] **Mass publish**: multi-select up to 10 → each its own slide (caption/
      mentions on first), "Posting n of N" progress + per-slide status strip
- [ ] Publish: Blossom BUD-02 upload at publish → sign kind 30315 with
      NIP-40 24 h expiration → never sign before upload+hash-verify

### 3.7 APP-007 — Bitz reels surface (V1)

**Top chrome** (glass, floats over media): wordmark (tap = back to Explore)
· glass pill tabs **Explore · Following · For you** (+ [W] Trending · Most
zapped — V1.x) — mode persisted, re-tap = back-to-top · header icon
buttons: search · refresh.

**Explore mode**: 3-col 9:16 video-first grid — 24 initial + 18/load-more,
tile = cover + duration + author + zap/like counts, sensitive covers,
skeleton tiles while loading, sensitive-cover reveal, tap → snap player
jumped to that reel. A visible footer reveals the next 18 loaded tiles,
loads an older relay page when the local window is exhausted, and reports
loading/exhausted state. [W] masonry ordering keeps ranked order for pictures.
Older-page events bypass the live-arrival hold, so a viewer at the end of the
snap pager immediately receives the next page and its player pool preloads
the adjacent next/previous videos.

The V1 relay query follows the legacy app's bounded hybrid strategy: native
NIP-71 kinds 21 and 22 are requested together, while kind-1 media-link notes
use a separate fallback filter. Kind 23 is not a Bitz kind; NIP-23 long-form
content uses kind 30023 and stays outside the reels feed.

**Player mode** — vertical snap `PageView` of 9:16 items:
- [ ] Media: full-bleed, multi-rendition fallback (imeta `fallback`/
      `fallbackrendition` deduped hi-q first), autoplay gated by real
      surface visibility (RouteAware — built), mute memory persisted,
      poster/cover while loading, batched rendering (5 visible + prefetch
      threshold — built via player pools), GIF-as-image reels
- [ ] Overlay — author row: hex avatar (→profile) + name + follow chip
- [ ] Caption: rich `CommentContent` body (NIP-27) + hashtags
- [ ] Action rail: like (+count, double-tap heart anywhere) · zap (→ Zap
      Dialog §3.14, optimistic sats) · comment (→ comments sheet) · share ·
      ⋯ menu (mute author · copy id · raw JSON · report · [W] delete own ·
      [W] view toggle crop/fill)
- [ ] Video controls: tap-to-pause/play, scrubber with seek-hint overlay,
      ±10 s fast-forward pills, compact mute button, hairline progress
- [x] Gestures: double-tap like; horizontal swipe = next/prev handling —
      left swipe cycles Explore → Following → For you; the final left swipe
      on For you opens the settled reel's creator profile sheet (TikTok /
      legacy Flutter parity); right swipe steps back; the gesture is
      suppressed while any sheet or the search overlay is up (both
      platforms, player + explore grid)
- [ ] Sensitive-content reveal gate per reel
- [ ] [W] chips: PoW badge · remix lineage ("remixed from…" → chain
      dialog) · sound chip (primary SFX cue → use-in-studio) · value-split
      chip — V1.x/V2
- [ ] [W] author mode `?author=<npub>`: one creator's reels + back-to-
      profile bar (entry from profile Bitz tab + note deep-links) — V1.x
- [ ] Empty states per mode (Following hint + Discover CTA); loading page
- [ ] Record entry (camera capture → composer §3.8/§3.19)

**Search overlay** (full-screen glass): search field · instant local matches
(caption/content/author) + debounced 400 ms NIP-50 relay search with
stale-token cancel · results grid deduped 9:16 tiles (2/3/4 cols
responsive) · states: idle / searching / no-results · close button · tap
splices into feed + jumps player.

**Comments sheet**: X-style two-level threading (top-level + flattened
children; orphans/cycles → top-level) · hex avatars · per-comment rows:
Like (+count) · Zap (+sats, optimistic) · Reply · composer: text + GIF
picker · media-URL attach · gallery upload · NIP-13 PoW + 64×64 attachment
preview tiles · rich bodies via shared CommentContent.

### 3.8 APP-008 — Note composer (V1)

- [ ] App bar: title + **Publish** text button (disabled until `canPublish`;
      18 pt spinner while publishing)
- [ ] Author header: hex avatar + name
- [ ] Multiline "Post a note…" hint field; **mention field** @autocomplete
      (profile search → `nostr:npub…` + p-tags)
- [ ] Image preview grid: removable thumbnails
- [ ] AttachmentPreviewRow: 64×64 tiles, image/GIF/video, GIF badge, ✕
- [ ] Content-warning field (NIP-36 `content-warning` tag)
- [ ] Toolbar buttons: image attach (picker) · media-URL attach · **GIF
      picker** (trending + debounced 350 ms search + Recent tab, 24 h
      cache) · **poll composer** (2–6 `poll_option` choices, ≤280 content/
      ≤80 option chars, live preview) · **PoW** (PowCard: 0–30-bit slider +
      hash visualization + background mining via isolate/Task, last
      difficulty remembered) · hashtag · 32-emoji quick picker
- [ ] Char counter: 4,000 / 16,000-char progress (limit state)
- [ ] Published state: success confirmation → thread of the new note
- [ ] Draft persistence (survive process death); discard confirm
- [ ] Media flow: picker → Blossom upload (staged progress per file) →
      hash-verified → imeta tags → sign+publish only after upload OK
      (Safety rule); failed-upload retry per attachment

### 3.9 APP-009 — Note thread (V1)

**Root resolution:** event object · hex id · `note1`/`nevent1` · NIP-33
coordinate `kind:pubkey:d` via `naddr1` (newest `#d` version; relay hints ∩
readable relays). [W] media-event links deep-link into Bitz author mode
unless explicit `from` source.

- [ ] Root post card: author (→profile) · full rich content (§3.5) ·
      action row: reply count · like +count · zap +sats (optimistic) ·
      repost · share · ⋯ (raw JSON dialog · delete own kind-5 confirm)
- [ ] **X-style threading**: top-level comments + flattened descendants
      behind a left border; NIP-10 reply/root markers; legacy root-only
      accepted; cycle-guarded
- [ ] Comment rows: author · rich body · action row Like(+count) · Zap
      (+sats) · Reply (opens reply bar with context quote)
- [ ] Reply bar: text field + removable media chips + options (GIF picker ·
      media-URL attach · gallery upload · NIP-13 PoW) + send; publishes
      root+reply markers + participant p-tags
- [ ] Live deltas: reactions/zaps/reposts for root AND replies (9735 bolt11
      msat parsing)
- [x] States: loading ("Loading note from relays…") · invalid id · not
      found ("Your relays did not return this event") · contextual back
- [ ] [W] comment refresh control

### 3.10 APP-010 — Discover (V1)

- [ ] App bar: title + Bitz action (play icon → Bitz surface)
- [ ] Search bar with debounce (+clear); [W] progressive-parallel relay
      query merge with primary-first results
- [ ] Trending hashtag chips (tap → hashtag results)
- [ ] Trending mosaic grid (Instagram-explore style): media tiles,
      skeleton grid (18 tiles), load-more via scroll, error+retry, empty
- [ ] Results tabs: **Posts** (note cards → thread; inline video preview;
      image rows) · **People** (rows/grid: avatar, name, NIP-05 badge,
      follow/unfollow, [W] WoT badge) · **Hashtags** (result list, [W]
      follow toggles) · [W] **Media** (gallery + lightbox + immersive
      viewer with prev/next) — Media tab V1.x
- [ ] [W] below-tabs when no query: latest-notes stream · trending content
      rails · most-zapped highlights
- [ ] Fullscreen zoomable image viewer: top bar (back · author →profile ·
      follow) · bottom bar (actions → open note `/note/:id`)
- [ ] npub/nprofile query → resolve profile directly
- [ ] Search cache + cancel on new query

### 3.11 APP-011 — Messages / DMs (V1 DMs; calls + groups V1.x)

**List** (Inbox → Messages):
- [x] App bar "Messages" (+ [W] subtitle: N unread · N encrypted · N groups)
- [ ] Search (name/content) + [W] pill tabs All / Unread (badge) / Groups
- [x] Conversation rows: hex avatar · name · last-message preview (media/
      voice/call glyphs) · time-ago · unread badge · delivery ticks (mine)
      (preview generic per NIP-17 — `DmPresentation.previewLine`)
- [x] New-chat dialog: paste npub/nprofile · picks from following ·
      recents; deep links `?to=<npub>&answer=<callId>` (npub/hex paste;
      following picks V1.x)
- [x] Message requests section: "N waiting — accepting never reveals you
      read them" (`DmPresentation.isAccepted`; decline-wins)
- [x] Empty state (chat icon + CTA)

**Chat**:
- [x] Header: peer avatar + name (→profile) · NIP-05 verified badge ·
      voice-call · video-call actions · ⋯ menu (details) (avatar+name→profile
      · ⚡ zap chip; calls/menu V1.x)
- [x] Message list: sent/received bubbles · day dividers · [W] reactions ·
      reply-quotes · media attachments (image/video/file detection, inline
      player, lightbox) · voice notes inline play · encrypted indicator ·
      delivery state (pending/sent/failed + retry) · [W] call-log entries
      rendered inline (bubbles + encrypted indicator + delivery
      pending→delivered via relay OK; media/reactions V1.x)
- [x] Input bar: text field (autogrow) · attach image · attach file · emoji ·
      send; attachment previews with remove; upload progress rings
      (text + send; attachments V1.x)
- [x] Encryption: NIP-17 gift-wrap (1059/13/14, NIP-44 v2) preferred,
      NIP-04 fallback; decrypt on-device; newest protocol wins per convo
      (NIP-17 only; NIP-04 fallback not implemented)
- [x] Read model: per-peer read cursors (bounded 512, persisted) · unread
      counts + shell Chats badge (unread + requests, "9+" cap) ·
      opening a conversation marks read (`DmPresentation.nextCursor`, never
      rewinds)
- [ ] [W] Details dialog: peer npub copy + QR · block/unblock · mute ·
      clear conversation (local)

**Calls** [W+F] (V1.x — needs SOC-010 threat review): WebRTC voice/video
over DM signaling (`CallSignal`: offer/answer/ice/end/log/state URI lines)
· incoming ringer (accept / answer-with-video / decline, 90 s expiry,
app-wide overlay) · outgoing ringing + pre-call network heads-up · active
panel: video surfaces, draggable self-PiP, mute/camera/switch/screen-share,
quality HUD (RTT/loss/ICE state), elapsed, fullscreen, minimize→restore
pill, end · outcomes ended/missed/declined · one-active-call admission ·
group calls (raise-hand, participant grid) V2.

**Groups** [W] (V1.x, NIP-29): joined list + browse public · group chat
(kind-9/10, h-tag) · members/admins panel · admin controls (rename 9000s,
add/remove, permissions) · join/leave 9021/9022 · invites share link ·
unsend own · unread feeds tab badge. (Flutter shell had UI-only join;
wire protocol per NIP-29.)

### 3.12 APP-012 — Notifications / Activity (V1)

- [x] Primary tabs: **All · Unread · Mentions · Replies** (+counts) — [W]
      extended filters Mention/Zap/Like/Repost/Follow/Comment as chips
- [x] Activity chips: Zaps · Likes · Reposts · Follows
- [ ] Expandable search row (name/content)
- [x] Day sections (Today/Yesterday/weekday) + iOS-style grouping: "A and
      N others liked your note" (follows never aggregate)
- [x] Row: avatar stack + type badge · title line (zap sats from bolt11
      msat; 9734 sender from description tag) · timestamp · unread dot ·
      tap → thread or profile (accent stripe pending)
- [ ] **OriginNotePreview**: batched fetch (≤100-id relay batches; 8 s
      missing → "Note unavailable") — corner icon · author·time · 2-line
      excerpt (media stripped, nostr:→@name) · 44 px thumb (video play
      glyph; sensitive cover w/ reveal)
- [ ] Notification media strip: ≤4 tiles, 2-col 16:9, "+N" overflow;
      image→lightbox, video→fullscreen
- [ ] Per-row ⋯ menu: mark read · profile · copy note/profile id · raw
      event JSON dialog · mute type
- [ ] Load more until end; states: loading / relay-error+retry / empty /
      empty-filter
- [ ] Read model: cursor persistence · mark-visible-read-on-open (1.4 s) ·
      mark-all · per-type mutes (persisted, badge-excluded) · blocked-author
      filtering · unreadCount feeds shell badge · [W] privacy gate hides
      zap amounts when enabled
- [ ] [W] video mentions deep-link into Bitz author mode

### 3.13 APP-013 — Profile (V1)

**Own** (Profile tab) and **:pubkey** (pushed; hex or npub param resolved).

- [ ] Edge-to-edge hero (no app bar): banner (160/200 px responsive) or
      brand gradient + hex-pattern default + scrim; large hex avatar
      overlapping, ⚡ chip when lud16; tap banner → lightbox
- [ ] Floating glass controls: back · share · Edit cover · settings (own)
- [ ] Info: display name · NIP-05 badge · npub copy chip (copy→check) ·
      website chip · lud16 chip · identity QR (own / via More)
- [ ] Actions — own: **Edit profile** pill + ⋯ ; other: **Follow/
      Following** · Message (→chat) · Zap (→dialog) + ⋯ (mute/block/report/
      copy pubkey/share)
- [ ] Follow stats row (tappable → sheets): notes · following · followers ·
      zaps; follower/following sheet lists with user rows + tabs
- [ ] About section: expandable bio, metadata chips
- [ ] Profile-completion card (own): score bar + Finish → editor
- [ ] Sticky tab bar (scroll-aware): **Notes · Replies · Bitz · Reposts**
      (+ [W] Media gallery + activity heatmap · Liked (10030) · Pinned
      (10001) · Zaps history — V1.x)
- [ ] Tab content: cards list / video tiles grid / repost items (with
      "unavailable" tile when source gone) / per-tab loading + empty
- [ ] Guest state (signed-out own profile): sign-in card + CTA
- [ ] [W] scroll-reveal sticky mini identity

### 3.14 APP-014 — Zaps & wallet (V1 ledger + dialog; NWC V1.x)

**Ledger page**:
- [ ] Stat tiles: total sent sats · received sats · counts (+ [W] avg zap)
- [ ] Tabs All · Received · Sent (+counts) → ledger rows: direction icon
      (⚡ in/out) · sats · peer (avatar →profile) · time-ago · comment
      excerpt · note link →thread · [W] truncated invoice id + receipt
      event viewer
- [ ] Loading/empty states; [W] export activity
- [ ] Data: received = authoritative kind 9735 (`#p=me`, sender recovered
      from 9734 description); sent = local ledger mirror
- [ ] [W] Wallet hero (V1.x): NWC (NIP-47) connect flow with capability
      display + balance "spendable now" + deposit/withdraw invoice modals
      (QR-first). **WebLN rejected on native** (parity audit).

**ZapDialog (shared send flow, NIP-57)**:
- [ ] lud16/lud06 resolve (LNURL pay request) → 4 amount tiers ⚡💜🔥🚀 +
      custom input + 200-char comment + anonymous toggle
- [ ] BOLT-11 invoice: branded QR + live expiry countdown + `lightning:`
      deep-link button + copy
- [ ] Confirmation: 9735 receipt watch + LUD-21 `verify` polling (3 s until
      settled/expiry — first signal wins) → auto-close; late receipt
      upgrades label "Zap confirmed"
- [ ] 9734 relays tag = recipient NIP-65 read relays (≤6) + own (≤3),
      deduped cap 8; failure safeguards + retry

### 3.15 APP-015 — Bookmarks (V1)

- [ ] Saved-notes list (NoteCard compact rows), newest-saved first; tap →
      thread; unbookmark/remove action; empty state; live relay re-fetch on
      open; NIP-51 kind 30003 bookmark sets (publish + reconcile).

### 3.16 APP-016 — Communities (V1.x)

- [ ] Joined public-rooms list (hex-avatar rows, relay/id captions, unread)
- [ ] Join dialog: relay URL + group id fields, prefilled from invite deep
      links (`?relay=&id=`)
- [ ] Group view: messages (kind-9) · members/admins · rename (admin) ·
      share invite link · leave confirm · unsend own
- [ ] Full NIP-29 wire protocol (9000–9007 admin, 9021/9022 join/leave,
      39000–39002 metadata) — see §3.11 groups note

### 3.17 APP-017 — More / "You" hub (V1)

- [ ] Guest: freedom-first sign-in card (→auth)
- [ ] Signed-in **profile hero**: avatar · name · npub chip · identity QR ·
      stat tiles (notes/following/followers/zaps)
- [ ] [W] wallet tile: live balance "spendable now" or connect CTA → zaps
- [ ] Tile groups (web-mobile parity): **Explore** (Discover · Meme Studio ·
      Communities · Lightning) · **Library** (Zaps · Bookmarks · [W]
      Trending sounds) · **Account** (Profile · Settings)
- [ ] Account-switch row → AccountSwitcherSheet (multi-account)
- [ ] Meta rows: About · Privacy · Terms
- [ ] [W] staggered rise entrance (reduced-motion aware)

### 3.18 APP-018 — Settings hub + sections (V1)

**Shared business core (implemented 2026-08-28)**:
`space.bitos.core.settings` — `SettingsContract` (versioned schema v1,
legacy `bitos_*` key names, size-bounded values), typed option enums
(theme/font/language/timeline/autoplay/quality/date-format) with safe
fallback parsing, `SettingsCodec` (kv ⇄ typed snapshot), `SettingsRules`
(write validation/canonicalization, clear-cache protected keys, cache-size
formatting, `shortNpub`), and the deterministic section catalog (web mobile
index order). iOS executes it through `BusinessCoreBridge` (settings
functions + `SettingsSnapshotResult`); Android links the module directly.
Adapters: `Platform/Settings/SettingsStore.swift` (UserDefaults) and
`data/settings/SettingsStore.kt` (SharedPreferences) — both normalize every
write through the shared rules. Common tests: `SettingsTest`; adapter
contract tests: `SettingsStoreTests.swift` / `SettingsStoreTest.kt`.

**Hub** (implemented, legacy `SettingsView` parity): account hero + grouped
hex icon tiles (iOS index style):

| Group | Sections (key · tint) |
|:--|:--|
| preferences | `lightning` ⚡ FF9500 · `privacy` 5856D6 · `notifications` FF3B30 · `appearance` FF2D92 · `algorithm` BF5AF2 |
| content | `security` FF9500 · `media` 34C759 · `language` 5AC8FA · `relays` 5AC8FA |
| support | `help` 32ADE6 · `about` 8E8E93 |

**Section pages** (shared chrome: scaffold + settings cards + switches +
choice tiles + permission rows):

| Key | Contents |
|:--|:--|
| `account`/`profile` | kind-0 editor: display name, username, about, picture, banner (pickers + crop + Blossom upload), website, NIP-05, lud16 → publish — profile fields live in ProfileEdit (You tab); Account page here: identity + copy npub + settings cache size + clear cache |
| `appearance` | theme mode (dark/light/system) · accent palette · font size · font family · compact mode · live preview — live: theme/accent/font/compact apply app-wide from the shared settings contract (APP-023); font family + live preview remain |
| `security` | nsec reveal (confirm-gated) + copy · app-lock (biometric) · sign-out · danger zone |
| `relays` | relay CRUD (add/edit/remove) · read/write toggles · live status dots + latency · recommended list · NIP-65 relay-list publish indicator · [W] event outbox viewer (pending ACKs) |
| `algorithm` | per-surface enable (feed/bitz/discover) · presets Latest/Balanced/Trending/Trusted/Custom · freshness Live 1h/Balanced 6h/Relaxed 24h/Chill 3d · per-signal weight sliders (Recency/Engagement/Zaps/Affinity/Topics/WoT) + total readout · interaction-profile reset · [W] settings-sync backup/restore (kind 30078) — live: full ranking controls (shared `AlgorithmContract` + `FeedRanking.rank` drives the For-You window on both platforms; off = chronological, Following always chronological; Topics/WoT rows render but contribute 0 until their data feeds land) + timeline Latest/Trending · media previews · reactions · protocol notes · default zap amount |
| `lightning` | default zap amount · wallet → zaps page |
| `privacy` | per-type notification mutes · DM/mention/zap gates · read-receipt behavior · blocked users manage · media auto-load rules · sensitive-media default — live: media auto-load · protocol notes · sensitive-media default (drives the NIP-36 cover on both platforms) · blocked users manage (kind-10004 head view + unblock publish; blocked set filters feeds AND the inbox) |
| `notifications` | master toggle · sound · haptics · per-type toggles — live: master/sound/haptics + per-type toggles (all six kinds, same muted-kinds store as the inbox header) |
| `media` | autoplay · video quality · playback rate · default upload provider (`_ProviderTile`: Blossom default; [W] Cloudinary/S3/server fallback config) — live: autoplay/quality/playback rate + honest Blossom provider row |
| `language` | English / Lao (+ system) — live: en/lo picker + date format + timezone display |
| `help` | FAQ · shortcuts · support/donate widget · contributors — live: FAQ (shared `AppFacts` single source) · support/contribute card (donate tiers render when the project LUD-16 is configured) · links; shortcuts + contributors pending |
| `about` | version · links · `/about` — live: brand card (legacy copy), honest supported-NIP chips, links, schema rows |

### 3.19 APP-019 — Studio & Meme Studio (V1 quick editor; V2 full suite)

**Record screen** (camera capture, reference `docs/ui/app-app-04-create-camera-editor.html`):
- [x] Full-bleed preview + rule-of-thirds grid toggle
- [x] Top bar: ✕ close (discard confirm when takes pending) · torch · grid ·
      off/3s/10s self-timer
- [x] REC badge: blinking dot + mono elapsed clock
- [x] Right-edge lens rail .5×/1×/2× (clamped to device zoom bounds)
- [x] Takes strip: poster-frame thumbnails · per-take ✕ · dashed "+" ·
      "N takes · MM:SS" · tap → trim preview (Retake discards, Use hands
      off to the Quick Bitz editor — video mode seeded with the take as a
      timeline clip; caption/publish flow through the editor's Post
      details, the ONE studio publish path 2026-09-07)
- [x] Review tools: start/end trim plus an explicit **Mirror** toggle. The
      preview flips immediately; choosing “Use edited video” performs one
      native export pass, so the published file (not merely its preview) is
      left-to-right mirrored. Source takes stay unchanged until that choice.
- [x] Capture-time Mirror: the top-bar mirror control previews the next take
      flipped before recording. That preference is stored per take and opens
      in review; it never silently changes an earlier take. The take strip
      scrolls horizontally at its bottom anchor, and review supplies “Back
      to takes” without deleting the selected take.
- [x] Capture row: Import (library video picker → same editor seeding as
      the hub's "Import media") · 76dp record button with morphing
      red core · MEM (expert editor seeded with ALL takes — N ≥ 2 merge into
      one clip first: Media3 Transformer / AVMutableComposition passthrough,
      progress overlay, failure falls back to the newest take; video mode
      via the MST-030 trim pipeline; the editor's slot autosave keeps it as
      a studio draft, not the OS gallery)
- [x] Bottom row: Flip · Edit takes · 3:00 cap — cap enforced (auto-stop;
      iOS 1.2 GB low-disk reserve)
- [x] Permission-declined state: system-settings deep link · import instead ·
      retry (camera only ever asked here, never at launch)
- [x] Post-details on the publish sheet (reference scr-details): caption
      counter · alt text (NIP-31, caption fallback — required-by-policy
      hint) · content warning + reason (NIP-36) · caption hashtags →
      t-tags — uniform with the meme lanes (shared composeMediaNote,
      common-tested)
- [ ] Speed chip · mic meter — deferred pending audio capture support
- [ ] Multi-take merge into one clip — needs media-core (V1.x)

**Studio home** (Create tab):
- [ ] "Start something new" cards: Bitz (camera) · Meme · templates
- [ ] Continue-creating slots (≤6 WIP, label + destination chip, one-tap
      resume into exact state, delete)
- [ ] Saved templates grid (use/delete) · tips cards
- [ ] [W] batch tip cards · batch queue bar (V2)

**Quick MEM editor (V1 — flutter parity)**:
- [ ] Top chrome: ✕ exit (discard confirm) · mode pill Image/GIF/Video ·
      undo · Post
- [ ] Media tray: multi-import (image/video/audio/file) · asset picker ·
      active-asset switching · media-URL import
- [ ] Stage: media canvas + draggable/scalable/rotatable text overlays ·
      empty-canvas CTA per mode
- [ ] Text sheet: fonts (impact/sans/serif/mono) · palette · size ·
      outline/shadow painting
- [ ] Bottom bar: mode switcher · undo · export (local save)
- [ ] Video timeline: per-overlay SFX cues (+presets) · audio preview ·
      voice-over record · soundtrack import
- [ ] Publish page: preview + caption row · tags section · settings
      (visibility, cover strip, cover frame) → publish picture/GIF memes as
      kind 20 (NIP-68, web parity) · video as kind 22 portrait / 21
      landscape · explicit Note destination → kind 1 (plan §3.4)

**Full studio (V2 — web suite, `delivery-plan.md` EDT/MEM epics)**: layers
(image/sticker/SVG icon/Iconify search/Nostr emoji packs 30030/Bitz Buddy/
Bitzverse) · artboards (source/9:16/16:9/1:1/4:5/custom) + zoom 0.6–1.5× ·
drawing pen · timeline dock multi-track (captions/image layers/SFX cues/
zoom punches/speed ramps/FX windows) · looks (color grades) · trim panel +
frame strip · SFX catalog ≤16 cues + custom uploads + suggestions +
waveform · shared-sounds marketplace (kind 30078) · templates local +
marketplace (zap-price unlock ledger, categories) + publish own · batch
queue · export formats auto/image/GIF/video + render pipeline · **publish
machine** `render → verify → sign(+pow) → publish` with destinations bitz
(NIP-71)/story (NIP-38+40)/note (kind-1) + PoW mining phase + options
(CW/alt/hashtags) + splits (lightning revenue) · remix chain (`remix` tag +
`meme` payload `com.bitos.bitz.meme` v1 + RemixChainDialog) · [W] AI
assists opt-in · crash/refresh recovery.

**Bitz composer (V1.x)**: 9:16 WYSIWYG stage mirroring player placement ·
drag-drop picker empty state · upload progress/error + retry · media meta
chips · trim draft (in/out) · cover-frame capture (scrub + poster) ·
hashtag suggestions one-tap · same publish machine · discard confirm +
recovery banner.

### 3.20 APP-020 — Static pages (V1)

- [ ] About: hero + CTAs (Get Started → Home) · feature cards grid ·
      open-source card · support/donate section · contributors · Nostr
      explainer · legal footer (Terms · Privacy)
- [ ] Privacy: 12 sections (card list) · Terms: 11 sections · [W] DocToc
      sidebar → mobile anchor nav

### 3.21 APP-021 — Trending sounds (V1.x)

- [x] Ranked list of most-used sounds — **bootstrap shipped 2026-09-08**
      (MST-050 Wave D): counting `sound` tags on published memes over the
      live feed window (shared `MemeSoundTrending.rank`: 3-day half-life,
      url+sha dedup, deterministic order) — no marketplace needed; the
      kind-30078 licensed library (MST-047) augments the same rail later
- [x] Per row: play/pause preview + waveform — SHIPPED 2026-09-08
      (both platforms): ▶ streams the ranked artifact in-place (one row
      at a time); the first preview also fetches the waveform (bounded
      download → decode → shared `MemeSoundWaveform.peaks` bar math,
      common-tested) and renders the bars, accent-tinted while playing
- [x] "Use in Studio" → soundSeed handoff → editor (re-attach by URL:
      hash-verified download, no re-upload; publish stamps the existing
      artifact's tags)

---

## 4. Shared component library (APP-022 — build as needed per surface)

| Component | Role | First needed by |
|:--|:--|:--|
| `HexAvatar` / `HexIcon` / `HexShape` | hexagonal identity motif (deterministic gradient fallback from pubkey); geometry = web `.hex-clip` `polygon(25% 6.7%, 75% 6.7%, 100% 50%, 75% 93.3%, 25% 93.3%, 0% 50%)` — regular flat-top hexagon, 6.7% vertical inset, identical on iOS/Android/Flutter/web | everywhere |
| `BootSplashScreen` / `BootSplashTiming` | branded boot splash: hex bolt avatar + orbit sweep + PoW segments + wordmark + status pill (timing parity with web/Flutter) | app launch |
| `NoteCard` (+ compact feed variant) | §3.5 rich note card | feed, search, bookmarks |
| `CommentContent` | tappable-token rich body + media (≤9) for comments/captions | threads, bitz, notifications |
| `AppMenu` / `AppMenuItem` / `AppMenuDivider` / `AppBottomSheetMenu` | screen-clamped popover (`showAt`) + sheet menus | feed app bar, card ⋯, row menus |
| `ZapDialog` | §3.14 send flow (tiers, QR, countdown, receipt) | everywhere zap |
| `PowCard` / `PowBadge` | NIP-13 slider + isolate mining + hash viz / badge | composer, thread reply, stories |
| `MediaGrid` / `AttachmentPreviewRow` (64×64, GIF badge, ✕) / `ImageViewer` (lightbox) / `VideoPlayerWidget` / `BitzVideoCover` | media surfaces | composer, cards, bitz, DMs |
| `GifPickerSheet` | Giphy trending + 350 ms search + Recent, 24 h cache | composer, thread, bitz, stories |
| `PollComposerSheet` | 2–6 options + limits + preview | composer |
| `ImageCropEditor` | avatar/cover crop | settings profile |
| `BrandQrCode` | branded QR (profile id, invoices) | auth backup, more hub, zap |
| `Nip05Badge` / `RelayStatusDot` | identity / relay status | cards, profiles, settings |
| `AccountSwitchOverlay` / `AccountSwitcherSheet` | multi-account UX | auth, more |
| `BootSplash` / `ErrorRetryWidget` / skeleton grid/list / empty-state widget | states | everywhere |
| `GlassContainer` | blur-20 frosted surface | bitz chrome, profile hero |
| `LikeButton` (animated §2.4) / `FeedActionBar` | action row with animations | cards, threads, reels |
| `AppAvatar` (sizes §2.3, online dot, story ring, verified tick) | standard avatar | everywhere |

## 5. Data models (union — shared/business-core authority)

Wire model (already shared): `NostrEvent` (id, pubkey, createdAt, kind,
tags, content, sig — canonical `serializeForId`), `NostrFilter`
(authors/kinds/ids/#tags/limit/since/until/search).

| Model | Fields / notes |
|:--|:--|
| `Profile` | pubkey, name, display_name, about, picture, banner, website, nip05, lud06/16 (kind 0) |
| `FeedNote` | id, pubkey, content, createdAt, pow?, tags, replyTo?, reactions[] (emoji/count/byMe/myEventId), repostCount, zapCount, zapTotalSats, source (configured/discovery/followed-tag), poll?, repostedBy? |
| `PollData/Option/Voter` | options (`poll_option` tags), votes map, total, myVote, closedAt, voters cap 100 (vote kind 1018) |
| `NotificationItem` | type (like/comment/repost/follow/mention/zap), pubkey, targetId/Kind, content, read, amountSats? |
| `Conversation/DirectMessage` | protocol (nip04/nip17), delivery (pending/sent/failed), peer, readCursor, unread |
| `RelayRecord` | url, read/write flags, status (unknown/connecting/ok/fail), latency, checkedAt |
| `Identity/AccountSummary` | sk/pk hex (secret in secure storage only), npub, profile |
| `CallSignal` | callId, type (offer/answer/ice/end/log/state), kind (voice/video), from, groupId?, sdp/candidate/outcome |
| `StorySlide/StoryAuthor` | kind 30315 + NIP-40 24 h TTL; ≤12/author; seen-set persisted |
| `MemeProject` (v1, local store) | native working wire (`studio/MemeProject.kt`): mode, assets, overlays ≤48 (px @1080, scale/rot, colorIndex, outline), trim, frame delay, cw/alt/tags; autosave only |
| `MemeWireDocument` (web `com.bitos.bitz.meme` v1) | interop wire for remix payloads/templates/fixtures (`meme-studio-plan.md` §3.1): mediaKind, overlays ≤12 (≤300 chars, size 0.03–0.22 of stage height, caps/stroke/bar, ms visibility windows, fx), sfxCues ≤16, caption ≤1000, lookId; ms time units; unknown fields ignored |
| `AlgorithmPreferences` | per-surface config, signal defs + weights, presets, freshness |
| `ZapEntry` | direction, sats (bolt11 msat), peer, comment, noteId?, timestamp |

**Nostr kinds (union table — shared core)**

| Kind | NIP | Use |
|:--|:--|:--|
| 0 | 01 | profile metadata |
| 1 | 01 | text notes (+ inline polls, meme publish [F]) |
| 3 | 02 | contact/follow list |
| 4 | 04 | legacy DM (fallback) |
| 5 | 01 | deletion (notes, stories) |
| 6 / 16 | 18 | repost / generic repost |
| 7 | 25 | reactions (+ per-story-slide) |
| 9 / 10 | 29 | group message / reply [W] |
| 13 / 14 / 1059 | 17/59 | seal / DM / gift wrap |
| 20 | 68 | picture post (image reels) |
| 21 / 22 | 71 | video / short-form video |
| 1018 / 1068 | — | poll response / definition |
| 1111 | 22 | comments (non-kind-1 replies) [W] |
| 1984 | 56 | reports |
| 9734 / 9735 | 57 | zap request / receipt |
| 10001 | 51 | pinned notes |
| 10002 | 65 | relay list metadata |
| 30003 | 51 | bookmark sets |
| 30015 | 51 | interest set — followed hashtags |
| 30030 | 30 | custom emoji packs |
| 30078 | 78 | app data (shared sounds/templates, settings sync) |
| 30311 | 53 | live activity (zap compat) |
| 30315 | 38 | user status / stories (+40 expiration) |
| 34235 / 34236 | 71 | addressable video (dedupe `kind:pubkey:d`) |
| 9000–9007 / 9021 / 9022 | 29 | group admin / join / leave |
| 39000–39002 | 29 | group metadata / members / admins |

**imeta codec (bitz)**: `imeta` authoritative — url (primary), fallback
mirrors, fallbackrendition variants (deduped hi-q first), x sha-256, dim
WxH, duration, m mime, thumb, alt; addressable dedupe by event-ref.

## 6. NIP matrix (union; native status column = target)

| NIP | Feature | Legacy status | Native target |
|:--|:--|:--|:--|
| 01 | events/filters/subs | ✅ | ✅ done (shared core) |
| 02 | contact lists | ✅ | ✅ done |
| 04 | legacy DM | ✅ fallback | V1 (fallback) |
| 05 | DNS identity + badge | ✅ | V1 |
| 09 | deletion | ✅ | partial → V1 |
| 10 | reply/root markers | ✅ | ✅ done |
| 13 | PoW (background mining) | ✅ | V1 (never block UI thread) |
| 17 | private DMs gift-wrap | ✅ | V1 |
| 18 | reposts (+embedded) | ✅ | ✅ done |
| 19 | bech32 entities + TLV | ✅ | ✅ done |
| 22 | comments kind 1111 | [W] | V1.x |
| 25 | reactions | ✅ | ✅ done |
| 27 | text note references | ✅ | V1 (renderer) |
| 29 | relay communities | ⚠️ UI-only [F], ✅ wire [W] | V1.x full |
| 30 | emoji packs | [W] | V2 |
| 36 | content warnings | ✅ | V1 |
| 38+40 | stories + expiration | ✅ [F] | V1.x |
| 44 | v2 encryption | ✅ own impl | V1 (shared core, audited impl) |
| 50 | relay search | ✅ | partial → V1 |
| 51 | lists (bookmarks/mutes/pins/interests) | ✅ | bookmarks done; rest V1.x |
| 53 | live activity | [W] | V2 |
| 56 | reports | ✅ | ✅ done |
| 57 | zaps + LUD-21 verify | ✅ | partial → V1 |
| 58 | badges | 📋 | V2 |
| 59 | gift wrap | ✅ | V1 |
| 65 | relay list metadata | ✅ | V1 (settings publish) |
| 68/71 | picture/video posts | ✅ | kind 22 done; 20/21/34235/6 V1 |
| 78 | app data (sounds/templates/sync) | [W] | V2 |
| 92 | imeta | ✅ | ✅ done |
| 94/96 | file metadata / HTTP storage + Blossom BUD-02 | ✅ | ✅ done |

## 7. i18n (APP-024)

Full string tables: **English (en)** + **Lao (lo)** [F parity]; system
language default; all strings localized keys from day one (never hardcode
copy in views); plural/date/number formats; font-scale safe.

## 8. Mass-production implementation order

Waves are tracker milestones (`native-ui-build-tracker.md`):

- **Wave 0 — foundation (DONE)**: shell, verified relay feed + video pager,
  camera/import/trim/publish, follow/like/repost/bookmark/report/mute,
  search, basic inbox + profile + edit, zaps LNURL, author sheets.
- **Wave 1 — V1 alpha UI completion**: APP-001/002 auth+onboarding ·
  APP-004/005 feed surface + NoteCard full · APP-008 composer full ·
  APP-009 thread · APP-010 discover full · APP-012 notifications full ·
  APP-013 profile full · APP-014 zaps ledger+dialog polish · APP-015
  bookmarks · APP-017 more hub · APP-018 settings hub + core sections
  (appearance/security/relays/account/notifications/language/about) ·
  APP-020 static · APP-022 component completion.
- **Wave 2 — social depth**: APP-007 bitz full (search overlay, comments
  sheet, explore grid) · APP-011 DMs NIP-17 + chat UI (+ delivery states) ·
  APP-018 remaining sections (algorithm/privacy/media/lightning) · [W]
  rank chips + explainer · new-notes pill + merge banners.
- **Wave 3 — ephemeral + calls + groups**: APP-006 stories · APP-011 calls
  (post threat review) · APP-016 communities · APP-021 sounds.
- **Wave 4 — studio**: APP-019 quick editor → bitz composer → (V2) full
  timeline suite per EDT/MEM epics.

Every wave ends: both platforms pass their adapter-contract tests, screens
match this spec's checklists, tracker updated same change (AGENTS.md rule).

---

## 9. Native implementation audit (snapshot 2026-08-28)

> **What this section is:** a code-level audit of `apps/ios` + `apps/android`
> as actually shipped on 2026-08-28, checked against this spec and both
> legacy apps (`~/Desktop/bitos/bitos-nostr-flutter` GetX app, 26 routes;
> `~/Desktop/bitos/bitos-nostr-web` SvelteKit app, 21 routes). It records
> per-surface progress, the settings field-level gap list, integrity
> findings (dead code, dormant wiring, spec drift), and the next-task queue
> for everything missing or incomplete. This is a planning snapshot — the
> live ledger remains
> [`native-ui-build-tracker.md`](./native-ui-build-tracker.md).
>
> **Score at snapshot:** of the 24 tracked surfaces — 4 complete (APP-004,
> 008, 012, 018), 13 partial, 7 not started. Both platforms are at parity
> with each other on nearly every surface (the shell, composer, feed,
> inbox, settings catalog all match); the gaps below are almost all
> "both platforms missing X", not platform drift.
>
> 2026-08 DM wave: APP-011 §3.11 moved ☐→◐ (NIP-17 DMs live on both
> platforms; presentation rules shared via `DmPresentation` + bridge).

### 9.1 Route / destination status (spec §1.2 vs shipped code)

| # | Destination (spec §) | Status | Gap summary |
|:--|:--|:--|:--|
| 1 | Auth gate §3.1 | ◐ both | Browse-first; create/import + npub confirm live. Missing: dedicated backup-reveal flow post-generate (warning card, nsec/npub copy rows, identity QR), branded switcher overlay, guest entry as first-class action |
| 2 | Onboarding §3.2 | ☐ both | No carousel, no `hasOnboarded` gate |
| 3 | Shell §3.3 | ◐ both | 5 visible tabs — **Discover is filtered out of the nav bar** (Android `BitOSApp.kt:106` `filterNot`, iOS 5-case enum + Discover as sheet). See drift finding 9.4-1 |
| 4 | Home notes feed §3.4 | ✅ both | W2 items only (banners, hashtag chips, ZapLiveStrip, rank chips) |
| 5 | Bitz §3.7 | ◐ both | Snap player + rail live; explore grid, glass top bar/mode pills, search overlay, comments sheet, video controls (scrubber/±10 s/mute memory) missing |
| 6 | Composer §3.8 | ✅ both | poll voting render-only (decision pending); Meme Studio button awaits studio phase |
| 7 | Thread §3.9 | ◐ both | Sheet-based; tree assembled by shared `ThreadAssembly` but **iOS renders the flat list**; root action row, live deltas, reply-bar options missing |
| 8 | Discover §3.10 | ◐ both | NIP-50 search + chips + npub resolve live; results tabs (Posts/People/Hashtags), trending mosaic, fullscreen image viewer missing. Reached via Home search icon + hub only |
| 9 | Messages/DMs §3.11 | ◐ both | NIP-17 gift-wrap live both platforms: conversation list with generic previews + unread dots + message requests, secure chat (encryption banner, delivery ticks via relay OK), new-chat by npub, chat ⚡ zap chip, Chats shell badge. Missing: search/tabs, attachments, reactions, calls (V1.x), groups, NIP-04 fallback |
| 10 | Notifications §3.12 | ✅ both | No push delivery (FCM/APNs) — later wave |
| 11 | Profile §3.13 | ◐ both | Own page + author sheet live; stats/tabs derive from the **live feed window only** (no dedicated author REQ); no banner/picture editing, no follower counts/sheets, no completion card; Android avatars are identicons (no remote pictures) |
| 12 | Zaps §3.14 | ◐ both | LNURL dialog complete (tiers/comment/QR/expiry/lightning:); ledger page + LUD-21 verify poll + 9734 relays-tag policy missing; NWC absent |
| 13 | Bookmarks §3.15 | ◐ both | Toggle + kind-30003 publish live; saved-list page missing (More tile disabled) |
| 14 | Communities §3.16 | ☐ both | Nothing (legacy Flutter also never wired NIP-29 — see 9.5) |
| 15 | More/You hub §3.17 | ◐ both | Hub live (hero, QR, switcher, tiles, honest coming-soon); wallet tile + communities/sounds tiles pend their surfaces |
| 16 | Settings §3.18 | ✅ both | All 12 sections live; field-level application gaps in 9.3 |
| 17 | Studio §3.19 | ◐ both | **Capture pipeline coded but unreachable** (see 9.4-2); editor absent |
| 18 | Trending sounds §3.21 | ☐ both | Nothing (web is the only legacy source) |
| 19 | Static pages §3.20 | ☐ both | About exists as a settings section only; privacy-policy/terms pages missing; More "About/Privacy" rows just deep-link settings-about |
| 20 | `/pulse` | rejected | Correctly absent |
| — | Deep links (`nostr:`/`lightning:` inbound) | ☐ both | No Android VIEW intent-filters; no iOS `CFBundleURLTypes`/`onOpenURL` |

### 9.2 Per-surface progress (audit view; tracker remains the ledger)

| ID | Surface | Audit status | Corrections vs tracker |
|:--|:--|:--|:--|
| APP-001 | Boot/Auth/Identity | ◐ | as tracked; backup-reveal + overlay remain |
| APP-002 | Onboarding | ☐ | as tracked |
| APP-003 | Shell | ◐ | **iOS re-tap-to-top dormant** — `HomeView.retapTick` never passed by `RootView` (tracker says shipped) |
| APP-004 | Home feed | ✅ | confirmed |
| APP-005 | NoteCard | ◐ | polls render-only; compact variant missing — as tracked |
| APP-006 | Stories | ☐ | confirmed absent |
| APP-007 | Bitz | ◐ | confirmed; add **no-op Share button** on the video rail (both) |
| APP-008 | Composer | ✅ | confirmed; GIF picker absent as tracked |
| APP-009 | Thread | ◐ | **iOS assembles the tree (`ThreadDisplayItem`) but renders flat** — ship the render pass |
| APP-010 | Discover | ◐ | confirmed |
| APP-011 | DMs | ☐ | confirmed placeholder |
| APP-012 | Notifications | ✅ | confirmed; no push (legacy also local-only) |
| APP-013 | Profile | ◐ | new gaps: no website field, no banner/picture pickers, no follower counts, **Android edit sheet never prefills nip05/lud16** |
| APP-014 | Zaps | ◐ | confirmed (dialog ✅, ledger/NWC ☐) |
| APP-015 | Bookmarks | ◐ | confirmed (page ☐) |
| APP-016 | Communities | ☐ | confirmed |
| APP-017 | More hub | ◐ | hub complete for live surfaces |
| APP-018 | Settings | ✅ | sections confirmed; consumption gaps in 9.3 |
| APP-019 | Studio | ◐ | **camera/trim/publish unreachable** — `CreateScreen` orphaned on both platforms by the composer-FAB rework (tracker said "live" from W0) |
| APP-020 | Static pages | ☐ | confirmed |
| APP-021 | Sounds | ☐ | confirmed |
| APP-022 | Components | ◐ | AppMenu/PowCard/hex/QR live; several components exist inline (MediaRow/Lightbox) but aren't factored as the shared library |
| APP-023 | Theming | ✅ | theme (light/dark/system), accent, font scale and compact apply app-wide live from the persisted settings; launch chain stays dark-branded; reduced-motion/high-contrast remain |
| APP-024 | i18n | ☐ | en hard-coded; lo picker exists but no strings |

### 9.3 Settings field-level audit (legacy union vs native)

Native has all 12 hub sections; the gaps are fields and *application*
(fields persisted but never consumed). "F"/"W" = legacy source.

| # | Field / behavior | Legacy | Native today | Action |
|:--|:--|:--|:--|:--|
| 1 | Profile picture picker → crop → upload → kind-0 | F+W | text URLs only (iOS picture field read-only) | APP-018a row 3 — reuse Blossom path |
| 2 | Banner picker | F+W | gradient placeholder only | with row 3 |
| 3 | Website field in profile editor | F+W | missing | trivial add, both platforms |
| 4 | Profile editor prefill | — | Android callers pass `initialNip05=""/initialLud16=""` — never prefills | bug fix |
| 5 | Default zap presets 1/5/21/100/500/1000 | F | ✅ Android chips; iOS stepper only | cosmetic: iOS preset chips |
| 6 | Zap prefs: non-zap reactions, anonymous-by-default, auto-zap-on-follow + amount | W | missing | APP-014 wave |
| 7 | Wallet connect (NWC NIP-47: balance, deposit/withdraw invoices) | W | none (WebLN rejected natively per parity audit) | APP-014 W4 |
| 8 | Theme / accent / font size / compact applied | F+W | live on both platforms (light/dark/system + accent palette + font scale + compact) | APP-023 |
| 9 | Reduced-motion + high-contrast toggles | W | missing (only OS-level respect targeted) | with APP-023 (§2.6) |
| 10 | Algorithm: relay-discovery toggles ×3, smooth ranking, clear learned interests, WoT refresh | F+W | missing (presets/freshness/signals/diversity/reset ✅) | W2 queue |
| 11 | Interaction-profile reset | F | missing | W2 queue |
| 12 | Encrypted settings sync backup/restore | W (NIP-04 app-data) | missing (native plan = kind 30078) | W2 queue |
| 13 | Relay latency + checked-at + "Test all" | W | live status dots + primary ⭐ + suggestions ✅ | add latency/test |
| 14 | Video quality Auto/High/Low | F+W | picker persists; **no pipeline consumer** | wire to player or annotate |
| 15 | Timezone picker (Auto/UTC/PST/EST/JST) | F | read-only "Automatic" row | low: add picker |
| 16 | Date format MDY/DMY/YMD | F+W | persisted; feed timestamps don't consume it | wire into time-ago/format |
| 17 | Sound / haptics toggles | F | persisted; no consumer (haptic exists on like only) | wire with notification service |
| 18 | Language: Lao strings actually applied | F | picker only (Flutter's picker was **dead** — locale pinned en) | APP-024 — must apply, not repeat legacy bug |
| 19 | Media providers Cloudinary/S3 config | W | honest Blossom-only row | deferred [W] — correct per spec |
| 20 | Push notifications (FCM/APNs) | neither legacy had it | none | later wave (APP-012) |
| 21 | Multi-account branded switch overlay | F | sheets (functional) | polish with APP-001 |
| 22 | Clear cache wipes real data | F (web's was a **toast stub**) | Android wipes event cache ✅; iOS EventStore wipe pending | finish iOS |
| 23 | Help cards (4 cards + articles) | F+W (both **snackbar/toast stubs**) | FAQ + support/donate live (ahead) | skip — do not port stubs |

### 9.4 Integrity findings (dead code, dormant wiring, drift)

1. **Tab-count drift:** spec §1.1 (user decision 2026-08-28) = six tabs incl.
   Discover; both apps render five and hide Discover behind the search icon
   + hub. Legacy itself disagreed (Flutter 5 tabs without Discover; web
   mobile bar had Discover). **Decision needed:** restore the sixth tab per
   §1.1 or amend §1.1 to the 5-tab + search-entry model.
2. **Unreachable capture pipeline (both):** `CreateScreen`/`CreateView` is
   never instantiated (Android imports it unused; iOS orphaned), so
   `CameraScreen` + `VideoPreviewScreen` — a complete CameraX/AVCapture
   record → trim → export path — cannot be reached. Android's
   `ImportMediaSheet` is only reachable through that dead screen; iOS
   reaches import via the app-bar camera icon (entry-point inconsistency).
3. **No-op Share buttons** on the video action rail — Android
   `FeedScreen.kt:611`, iOS `HomeView.swift:1132` (empty action).
4. **iOS re-tap-to-top dormant** — `retapTick` implemented but never passed
   by `RootView` (tracker believed shipped; verify + fix).
5. **iOS thread tree computed but unrendered** — `FeedStore.threads`
   produces `ThreadDisplayItem`s; `CommentSheet` still renders the flat
   list.
6. **Android ProfileEditSheet prefill bug** (see 9.3-4).
7. **Android avatars are identicons only** — kind-0 pictures never load
   (iOS has `RemoteImageView`); unify through the media pipeline.
8. **Inbound deep links absent on both platforms** (manifest/Info.plist
   have no URL entries) while spec §1.2 requires `nostr:`/`lightning:`.
9. **Persist-only settings** (both, tracked to their waves): theme,
   accent, font size, compact, video quality, language, date format,
   timezone, sound, haptics.
10. **Orphaned legacy composers** — `ComposerSheet.kt` / `ComposerSheet.swift`
    superseded by the full-page composers; delete after confirming parity
    (drafts/polls/PoW all ported).
11. **Polls render-only** — voting/bars await the vote-format decision
    (NIP-1 kind-1071 candidate); known, do not fake.

### 9.5 Legacy features that were already broken — do not blind-port

- Flutter: NIP-29 communities `TODO(nip29)` (UI-only join); language
  picker dead (locale pinned `en_US`); all help cards + articles are
  snackbar stubs; compact mode has no toggle UI; static-page external
  links copy instead of opening; media provider tiles "coming soon"
  (only server-fallback uploads worked); no FCM/APNs; group calls
  web-only.
- Web: language/region selects decorative (not persisted); help stubs;
  **"Clear cache" is a toast that clears nothing**; "Private account"
  toggle stored but unenforced; video EXIF stripping admitted incomplete;
  `/pulse` is a fake showcase (already rejected by this spec); the old web
  ui-gap audit overstated gaps (it claimed the wallet was missing — NWC
  exists in web code).
- Consequences already taken: native Clear-cache actually wipes (9.3-22),
  native FAQ is real (9.3-23), and APP-024 must *apply* Lao, not just
  persist a picker (9.3-18).

### 9.6 Next-task queue (missing / not complete — build top-down)

Everything below follows the standing rules: deterministic rules land in
`shared/business-core` first with common tests; native adapters get
contract tests; i18n keys not hardcoded copy; views never touch
relays/DB/signers directly.

**Tier P0 — wire what is already built (small, high value):**

- [ ] T1 · APP-003/004 — pass `retapTick` through iOS `RootView`; verify
      Android re-tap path end-to-end.
- [ ] T2 · APP-005/007 — real Share actions (system share sheet) on note
      menus + video rails, both platforms.
- [ ] T3 · APP-013/018 — fix Android ProfileEdit prefill; add website
      field to the editor on both platforms.
- [ ] T4 · APP-019/CAP — re-expose capture: "Record Bitz" entry (Bitz
      header + Create hub row) → CameraScreen → trim → publish; make the
      import-media entry point consistent (Android app-bar icon like iOS).
- [ ] T5 · cleanup — delete orphaned ComposerSheets; refresh stale
      `PendingSection`/`PendingDetail` copy; remove Android's never-true
      `showImportMedia` flag in FeedScreen.
- [ ] T6 · 9.4-1 — tab decision: restore Discover as the sixth tab per
      §1.1 or amend the spec (user decision required).

**Tier P1 — close V1 page gaps (Wave 1 completion):**

- [ ] T7 · APP-015 — Bookmarks page (compact cards, unbookmark, empty,
      re-fetch on open) + enable the More "Saved" tile.
- [ ] T8 · APP-014 — Zap ledger page (stat tiles, All/Received/Sent tabs,
      rows with peer/time/comment/note link) + LUD-21 verify polling +
      9734 relays-tag policy; enable the More "Zap ledger" tile.
- [ ] T9 · APP-010 — Discover results tabs (Posts/People/Hashtags +
      follow toggles), trending mosaic grid, fullscreen image viewer.
- [ ] T10 · APP-009 — thread completion: render the assembled tree on
      iOS; root action row (counts, share, ⋯ raw/delete-own); reply-bar
      options (media chips, PoW); live deltas from kind-9735.
- [ ] T11 · APP-007 — Bitz chrome: glass top bar + Explore/Following/For
      you pills, 3-col explore grid with sensitive covers, search overlay
      (local instant + NIP-50 debounced), comments sheet on the shared
      thread engine, video controls (scrubber, ±10 s, mute memory).
- [ ] T12 · APP-020 — static About/Privacy(12 §)/Terms(11 §) pages
      (copy source: Flutter `modules/static/static_pages.dart`); point the
      More meta rows here.
- [ ] T13 · APP-002 — onboarding carousel (4 pages + dots + skip) and the
      `hasOnboarded` boot gate.
- [ ] T14 · APP-001 — backup-reveal flow post-generate (warning card,
      npub/nsec copy→check rows, identity QR, confirm-backed-up), guest
      entry polish, branded account-switch overlay.
- [ ] T15 · APP-008 — GIF picker (decide the source service first:
      keyed Giphy vs relay-served; trending + search + Recent + 24 h
      cache per §3.8).
- [ ] T16 · APP-013 — profile depth: banner/picture pickers (crop +
      Blossom upload), follower/following counts + sheet lists, stats
      sheets, completion card; Android remote avatars through the media
      pipeline; author pages fed by dedicated author REQs instead of the
      feed window.
- [ ] T17 · deep links — Android intent-filters + iOS URL types:
      `nostr:` npub/note/nevent/naddr → profile/thread/discover,
      `lightning:` → zap invoice sheet.

**Tier P2 — Wave 2 (specced, next major):** APP-011 DM remainder
(attachments, reactions, calls, groups; NIP-04 fallback decision) · APP-023 theming
application (light mode, accent runtime swap, font scale, reduced-motion +
high-contrast) · algorithm extras (relay discovery, smooth ranking, clear
interests, interaction reset) · settings sync (kind 30078) · APP-024 i18n
(lo applied for real) · video-quality consumer · push service (FCM/APNs).
APP-011 V1 (NIP-17 gift-wrap, delivery states, requests, read cursors,
Chats badge) shipped 2026-08.

**Tier P3 — Waves 3–4:** APP-006 stories · APP-011 calls (post SOC-010
threat review) · APP-016 communities (web is the only working NIP-29 wire
reference) · APP-021 trending sounds (web-only legacy surface) · APP-019
quick MEM editor → bitz composer → V2 studio suite · APP-014 NWC wallet.

---

*Merge sources: the Flutter audit and web audit docs (54 KB / 53 KB,
retired from `docs/` after the merge — see git history) and
`docs/DESIGN_SYSTEM.md` (tokens). Legacy apps remain at
`../bitos-nostr-flutter` and the web repo for reference reading only —
never import framework code.*
