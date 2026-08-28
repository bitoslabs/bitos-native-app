# bitOS — Nostr on Flutter: Full Feature, UX/UI, Routes & Data Model Reference

> **Superseded as implementation authority** by
> [`native/app-unified-feature-spec.md`](./native/app-unified-feature-spec.md)
> (merged web + Flutter single source; track work in
> [`native/native-ui-build-tracker.md`](./native/native-ui-build-tracker.md)).
> This file is preserved as the Flutter-era audit record and mobile-UX
> reference. Progress tracking for native iOS/Android happens ONLY in the
> tracker, never here.

> Single-source reference for the bitOS Flutter client (`app_bitos_space`): every
> feature surface, UX/UI pattern, route page, data model, and the design-system
> tokens actually implemented in code.
>
> Generated from a full audit of `lib/` (Aug 2025). Where
> `docs/guidelines/DESIGN_SYSTEM.md` differs (e.g. legacy purple brand), the
> **code in `lib/app/core/theme/` is authoritative** — this document reflects code.

---

## 1. System Overview

| Item | Value |
|:--|:--|
| Product | **bitOS — Nostr Business OS** — Social, Bitcoin & Business on Nostr |
| Package | `app_bitos_space` v1.0.0+1 |
| Framework | Flutter (Dart SDK ^3.12.0), Android + iOS |
| State management | **GetX** (controllers, bindings, `Obx` reactive UI, DI) |
| Navigation | GetX named routes, route table 1:1 with the web app (bitos-svelte) |
| Protocol | Nostr — hand-rolled relay client/pool, Schnorr (bip340), NIP-01..96 |
| Crypto | pointycastle, bip340, bech32 (NIP-19), custom NIP-44 v2 |
| Local storage | `shared_preferences` (prefs/state) + `flutter_secure_storage` (keys) |
| Networking | `web_socket_channel` (relays), `http` (NIP-96/Blossom, LNURL) |
| Media | video_player, cached_network_image, image_picker, file_picker, just_audio, record, video_thumbnail, qr_flutter, flutter_svg, flutter_webrtc (DM calls) |
| i18n | GetX translations: **English (en_US)** + **Lao (lo_LA)** |
| Architecture | Layered: `core/` (engine & services) → `modules/` (feature pages) → `shared/` (widgets/utils) → `routes/` |

### Layer map

```
lib/
├── main.dart                      # bootstrap → DI init → MaterialApp
└── app/
    ├── core/
    │   ├── nostr/                 # Nostr engine: events, filters, relay pool,
    │   │   │                      #   subscriptions, signing, validation, PoW,
    │   │   │                      #   publishing, caching, classification
    │   │   ├── nips/              # nip04, nip05, nip17, nip19, nip44, nip57
    │   │   └── relay/             # relay_client, relay_pool, relay_service
    │   ├── auth/                  # AccountManager (multi-account registry)
    │   ├── crypto/                # KeyManager (keygen/derive)
    │   ├── storage/               # SecureStorageService (keychain)
    │   ├── media/                 # media_upload_service (Blossom/NIP-96),
    │   │                          #   video_poster_service
    │   ├── algorithm/             # AlgorithmPreferences + InteractionProfile
    │   │                          #   (explainable feed ranking signals)
    │   ├── settings/              # privacy_notification_settings,
    │   │                          #   media_provider_prefs
    │   ├── theme/                 # design tokens: colors, typography, spacing,
    │   │                          #   radius, shadows, icons, app_theme
    │   ├── services/              # app_preferences_service, theme_controller
    │   ├── navigation/            # app_route_observer (surface visibility)
    │   ├── constants/             # storage_keys
    │   └── utils/                 # context_extensions
    ├── modules/                   # feature screens (see §3 Route Pages)
    ├── shared/
    │   ├── widgets/               # reusable components (see §7 Design System)
    │   ├── utils/                 # mention parser, media utils, text_safe
    │   └── menus/                 # AppMenu / AppMenuItem / bottom-sheet menu
    ├── routes/                    # app_routes.dart + app_pages.dart
    ├── i18n/                      # translations.dart + locales (en, lo)
    └── di/injection.dart          # initCoreServices() service registry
```

---

## 2. Service Registry (DI — `di/injection.dart`)

Registered at boot (before first interactive route); permanent singletons marked ★:

| Service | Purpose |
|:--|:--|
| ★ `AccountManager` | Multi-account registry; secrets in encrypted keychain |
| `AppPreferencesService` | SharedPreferences wrapper |
| `SecureStorageService` | Encrypted key/value (nsec, keys) |
| `ThemeController` | Theme mode / accent / font scale |
| `RelayService` (+ `RelayPool`, `RelayClient`) | WebSocket relay connections, subscriptions, publishing |
| `ContactListService` | Following graph (kind 3 / NIP-02) |
| `BookmarkService` | Bookmarks (NIP-51 kind 30003) |
| `MuteService` | Muted pubkeys & words (NIP-51) |
| `NoteActionsService` | Shared like/repost/reply/zap/delete with optimistic state |
| `EventCacheService` | Event + profile-metadata cache |
| ★ `SentZapsStore` | Local sent-zap ledger (kind 9735 mirror) |
| ★ `PrivacyNotificationSettings` | Per-type notification mutes/privacy toggles |
| ★ `MediaProviderPrefs` | Default media upload provider |
| ★ `AlgorithmPreferences` | Feed ranking signal weights |
| ★ `InteractionProfileService` | Long-term affinity/interests/dismissed memory |
| ★ `PowPrefs` | Last-used NIP-13 difficulty |
| `MediaUploadService` | Nostr-authorized Blossom uploads |
| `Nip05Verifier` | NIP-05 DNS identity verification |
| `OriginNoteService` | Batched origin-note previews for notifications |

---

## 3. Route Pages (`lib/app/routes/`) — Full Detail

Route table mirrors the web app (bitos-svelte) 1:1 — see `docs/PARITY_MAP.md`.
Initial route: `/auth` (entry gate that bridges to boot/home based on persisted identity).
Every page follows GetX MVVM: **View (`*_view.dart`) + Controller (`*_controller.dart`) + Binding (`*_binding.dart`)**.

### 3.0 Route summary

| # | Route | View | Controller | Params/Args | In shell? |
|:--|:--|:--|:--|:--|:--|
| 1 | `/auth` | `AuthView` | `AuthController` | — | — |
| 2 | `/welcome` | `OnboardingView` | `OnboardingController` | — | — |
| 3 | `/onboarding` | ↳ alias → `OnboardingView` | 〃 | — | — |
| 4 | `/home` | `HomeView` (shell) | `HomeController` | — | shell host |
| 5 | `/discover` | `ExploreView` | `ExploreController` | — | pushed (feed search · More hub) |
| 6 | `/messages` | `DmListView` | `DmController` | `?to=` peer (web parity) | tab 2 |
| 7 | `/chat` | `ChatView` | `DmController` (shared) | `arguments: peerPubkey` | — |
| 8 | `/notifications` | `NotificationsView` | `NotificationsController` | — | tab 3 |
| 9 | `/more` | `MoreView` | `MoreController` | — | — |
| 10 | `/bitz` | `BitzView` | `BitzController` | — | tab 1 (`inShell: true`) |
| 11 | `/bits` | ↳ alias → `BitzView` | 〃 | — | — |
| 12 | `/communities` | `CommunitiesView` | `CommunitiesController` | `?relay=&id=` invite prefill | — |
| 13 | `/zaps` | `ZapsView` | `ZapsController` | — | — |
| 14 | `/bookmarks` | `BookmarksView` | `BookmarksController` (inline) | — | — |
| 15 | `/create` | `CreateView` | `CreateController` | — | — |
| 16 | `/meme` | `MemeView` → `MemePublishPage` | `MemeController` | — | — |
| 17 | `/profile` | `ProfileView` | `ProfileController` | — | tab 4 “You” |
| 18 | `/profile/:pubkey` | `ProfileView` (tag: `userProfileTag`) | `ProfileController(tag:)` | hex **or** npub | — |
| 19 | `/note/:id` | `ThreadView` | `ThreadController` | hex id, `note1`/`nevent1`, NIP-33 `kind:pubkey:d` (`naddr1`) | — |
| 20 | `/settings` | `SettingsView` | `SettingsController` | — | — |
| 21 | `/settings/:section` | `SettingsSectionPage` | `SettingsController` (shared) | section key | — |
| 22 | `/about` | `AboutPage` | `SupportController` | — | — |
| 23 | `/privacy` | `PrivacyPage` | — | — | — |
| 24 | `/terms` | `TermsPage` | — | — | — |

Helpers: `Routes.note(id)`, `Routes.profileOf(pubkey)` (hex/npub),
`Routes.settingsSection(section)`. Legacy aliases kept working: `/onboarding`,
`/thread` → `/note/:id`, `/reels` → `/bitz`, `/settings/appearance|profile|security|relays`
→ section dispatcher.

---

### 3.1 `/auth` — Auth & Identity (`modules/auth`)

**Files**: `auth_view.dart` (530 L) · `auth_controller.dart` · `auth_binding.dart` ·
services: `KeyManager`, `SecureStorageService`, `AccountManager`, `Nip19`.

**Page anatomy** — two states, no app bar:
- **`_AuthLoadingScreen`** — branded boot splash bridge while persisted identity
  + onboarding state are read (no progress-circle flash between branded screens).
- **`_LoginView`** — rocket icon (72 px, primary), app name (`displayLarge`),
  tagline; **key import field** (nsec/npub, paste + focus handling, short-key
  masking `head…tail`), generate-vs-import actions, account switcher entry
  (`AccountSwitchOverlay`).
- **`_BackupView`** — post-generate backup screen: warning card, **npub + nsec
  reveal/copy rows** (`_CopyableKey`, copy→check), identity **QR**
  (`BrandQrCode`, "scan QR backup"), continue → `/home`.

**Controller state**: `importError`, hex privkey, `_npub`/`_nsec` (secure-storage
keys `nostr_npub`/`nostr_nsec`). **Flows**: generate → backup → Home; import →
Home; existing session auto-detected on boot.

### 3.2 `/welcome` — Onboarding (`modules/onboarding`)

**Files**: `onboarding_view.dart` (621 L) · `onboarding_controller.dart` (35 L).

**4-page `PageView` carousel** (swipe + buttons):
1. `_WelcomePage` — hero welcome.
2. `_KeysIdentityPage` — `_KeyVisual`: keys = your identity (no email/password).
3. `_DecentralizedPage` — `_RelayVisual`: relays fan-out diagram.
4. `_ZapConnectPage` — Lightning/zap value flow.

**Chrome**: `_StepCounter`, `_PageIndicator` (dots bound to `currentPage` Rx),
`_BottomControls` (Next/Back/Skip). Skip/finish → `Routes.AUTH` (or Home if
identity exists). `hasOnboarded` persisted. Reusable pieces:
`_OnboardingPageBase`, `_GlowingIcon`, `_FeatureChip`.

### 3.3 `/home` — App Shell (`modules/home`)

**Files**: `home_view.dart` · `home_controller.dart` (120 L).

**Shell anatomy** — `Scaffold` + `IndexedStack` (state retention) + bottom nav:
- **5 tabs**: `FeedView` (/) · `BitzView(inShell: true)` · `DmListView` ·
  `NotificationsView` · `ProfileView` (You = own profile).
- **Bottom nav bar**: surface bg, 0.5 px top border, `_NavItem` icon+label row
  (Home · Bitz-play · Chats · Activity · You hex-avatar) — **unread badges**
  (DMs = Σ conversation unread; Activity = `unreadCount`), cap "9+".
- **Lazy tab loading**: `loadedTabs` set — a tab builds on first selection
  (cold-start work stays on Home); selected tabs stay alive.
- **Behavior**: re-tap Home → `FeedController.triggerScrollToTop()`; selecting
  tab 1 toggles `BitzController.setSurfaceVisible()` (videos never autoplay
  offstage); tab bindings registered eagerly in `onInit` (+ `StoriesController`
  `fenix: true` so the rail is live from shell start).
- **"You" tab identity**: hex avatar + display name from persisted profile
  (`_loadIdentity`).

### 3.4 `/home` tab 0 — Feed (`modules/feed`)

**Files**: `feed_view.dart` (~710 L) · `feed_controller.dart` (827 L) ·
`feed_binding.dart`.

**Anatomy** (top → bottom):
- **App bar**: wordmark logo asset (light/dark) leading; **centered
  content-filter trigger** → `AppMenu.showAt` (screen-clamped):
  All · Original · Replies · Media · Liked · Mine (with `_MenuCheck` marks);
  search action → `/discover`; apps-grid action → `/more`.
- **`_HomeFeedTabs`** (sticky): **For you** (sparkles) · **Following** (users),
  `_UnderlineTab` indicator + live note counts.
- **List header** (`_buildHeader`): `StoriesBar` (or `_GuestBanner` when
  signed out) — scrolls *with* the feed (not pinned) to keep timeline height.
- **`_FeedListBody`**: `NoteCard` list, pull-to-refresh (`RefreshIndicator`),
  pagination, skeleton/empty/relay-error + retry states, stale-while-revalidate
  hydration (cached events first, fresh notes lead).
- **`_NewNotesPill`**: live "↑ N new notes" reveal pill (+
  `_PendingAuthorAvatarStack`) — tap to splice new notes.
- **New Note extended FAB** (bottom-floating, web mobile compose parity) → `/create`.

**Controller**: feed mode Rx, filter Rx, event list, scroll-to-top, empty-feed
auto-retry timer (2 s backoff, ≤ max attempts, gated on relay connectivity),
NIP-27 entity rendering, sensitive covers, show more/less.

### 3.5 `/discover` — Explore (`modules/explore`)

**Files**: `explore_view.dart` (1233 L) · `explore_controller.dart` · binding.

**Anatomy**:
- **App bar**: title + Bitz action (play icon → `/bitz`).
- **`_SearchBar`** — input with debounce → `_SearchResults`.
- **`_HashtagChips`** — trending hashtags (tap → `_HashtagResults`).
- **`_TrendingGrid`** — mosaic media grid, load-more via `scrollController`;
  `_ExploreSkeletonGrid` (18 tiles) while loading; `ErrorRetryWidget` + empty state.
- **`_SearchResults`** — `_SearchTab`s: **Posts** (`_PostResults` → note cards →
  `/note/:id`; `_SearchVideoPreview` inline play; `_SearchImageRow`) ·
  **People** (`_UserResults` rows → `/profile/:pubkey`, NIP-05 badges, follow) ·
  **Hashtags** (`_HashtagResults`).
- **`_ExploreImageViewerScreen`** — fullscreen zoomable image viewer with
  `_ExploreImageTopBar` (back, author → profile, `_ExploreImageFollowButton`),
  `_ExploreImageBottomBar` (actions → open note `/note/:id`).

### 3.6 `/bitz` — Bitz Reels (`modules/bitz`) — tab 1

**Files**: `bitz_view.dart` (4302 L — largest surface) · `bitz_controller.dart` ·
`bitz_binding.dart`; `RouteAware` host keeps `isSurfaceVisible` in sync so
videos never autoplay offstage (shell tab **or** standalone `/bits` route).

**Anatomy** (top → bottom):
- **`_BitsTopBar`** glass header: `_BitsTabs` glass pills — **Explore ·
  Following · For you** (mode persisted; re-tap = back-to-top);
  `_ReelsHeaderActions`: `_HeaderIconButton` search · refresh.
- **Explore mode** — `_ExploreGrid` video-first 3-col 9:16 tiles
  (`_ExploreTile` + `_ExploreTileFooter` counts; 24 + 18/load-more;
  `_ExploreLoadingTile`; sensitive covers; tap → snap player jump).
- **Player mode** — `PageView` snap feed of `_ReelItem`s (9:16 `_ReelsPlayer`):
  - Overlay: author row (hex avatar → profile, name, follow), caption
    (`CommentContent` rich body), `_BottomOverlay` action rail — like (+count),
    zap (→ `ZapDialog`, optimistic), comment (→ comments sheet), share, ⋯ menu
    (mute author, copy id, raw JSON…).
  - `_VideoControlBar`: tap-to-pause, `_BitsProgressTrack` scrubber with
    `_SeekHintOverlay` + `_FastForwardPill` (±10 s), `_CompactVideoButton`s,
    `_HairlineProgress`; autoplay gating + mute state prefs.
  - Interactions: double-tap like, horizontal swipe → next/prev handling
    (`_handleBitsSwipe` velocity), sensitive-content reveal gate.
- **`_BitsSearchOverlay`** (full-screen): `_SearchField` — instant local matches
  (caption/content/author) + debounced 400 ms NIP-50 relay search (stale-token
  cancel), `_SearchResultsGrid` deduped 9:16 tiles (2/3/4 cols), states:
  `_SearchIdleState` / `_SearchSearchingState` / `_SearchNoResultsState`;
  `_CloseSearchButton`; tap splices into feed + jumps snap player.
- **Comments sheet** (`AppBottomSheetMenu`-hosted): X-style two-level threading
  (top-level + flattened children; orphans/cycles → top-level), hex avatars,
  Like (+count) · Zap (+sats, optimistic) · Reply rows; composer = GIF picker ·
  media-URL attach · gallery upload · NIP-13 PoW + `AttachmentPreviewRow`
  64×64 tiles; `_ReelLoadingPage` + `_BitsEmptyState` (mode-aware, Following
  hint + Discover CTA).
- Record entry (camera capture → composer) via `image_picker`.

### 3.7 `/messages` — DM List (`modules/dm`) — tab 2

**Files**: `dm_view.dart` (`DmListView` + `ChatView`, 899 L) · `dm_controller.dart` ·
`dm_protocol.dart` · `call_service.dart` · `call_panel.dart` · binding.

**`DmListView` anatomy**: app bar "Messages"; body = loading spinner →
conversation rows (`_ConversationRow`: hex avatar, name, last-message preview,
timestamp, unread badge) → tap `Routes.CHAT` with `arguments: peerPubkey`;
empty state (chat outline icon 64 px + CTA).

### 3.8 `/chat` — 1:1 Conversation (`modules/dm`)

**`ChatView` anatomy** (`_ChatViewState`, peer pubkey via arguments):
- **App bar**: peer hex avatar + name (tap → `/profile/:pubkey`), `_DmVerifiedBadge`
  (NIP-05), actions: voice call · video call (→ `CallService` signaling) · ⋯ menu.
- **Message list**: `_MessageBubble` (sent/received alignment) + `_MessageBody`
  (text, `MessageMedia` image/video/file detection, markdown-image extraction,
  inline `VideoPlayerWidget`, link taps via url_launcher, encrypted indicator).
- **`_ChatInputBar`**: text field, attach (image picker), send — NIP-17 gift-wrap
  (kind 1059/13/14, NIP-44 v2) with NIP-04 fallback.
- **`CallWatcher`** wraps list + chat: mounts **`CallPanel`** whenever
  `CallService` has an active/ringing call — incoming ring UI (accept/decline,
  phone-style ring feedback), outgoing ringing state, video surfaces, controls
  (mute / camera / end), WebRTC (`flutter_webrtc`) with Nostr `CallSignal`
  signaling (offer/answer/ice/end/log/state), `CallOutcome` ended/missed/declined.

### 3.9 `/notifications` — Activity (`modules/notifications`) — tab 3

**Files**: `notifications_view.dart` (1644 L) · `notifications_controller.dart` ·
`notification_sections.dart` · `OriginNoteService`.

**Anatomy** (top → bottom):
- **App bar** + **`_PrimaryTabs`**: **All · Unread · Mentions · Replies** with
  `_countText` badges; **`_MobileSearchRow`** + `_SearchField` (expandable,
  name/content search).
- **`_ActivityChips`**: Zaps · Likes · Reposts · Follows filter chips.
- **Body states**: `_LoadingState` · `_RelayErrorState` (retry) · `_EmptyState`.
- **`_NotificationsBody`** — day sections (`notification_sections.dart`:
  Today/Yesterday/weekday, iOS-style grouping: "A and N others liked…";
  follows never aggregate) → **`_NotificationRow`**:
  - `_AvatarsBadge` (hex avatar stacks + type badge), `_TitleLine` (zap sats
    from bolt11 msat; 9734 sender from description tag), timestamp.
  - Unread accent stripe + dot; tap → `/note/:id` or `/profile/:pubkey`.
  - **`_OriginNotePreview`**: batched `OriginNoteService` (≤100-id relay batches,
    8 s missing → "Note unavailable"; `_OriginNoteSkeleton`), corner icon,
    author·time, 2-line excerpt (media stripped, nostr:→@name),
    `_OriginThumb` 44 px (video play glyph, sensitive cover w/ reveal).
  - **`_NotificationMedia`**: up to 4 tiles 2-col 16:9 + "+N" overflow;
    image → lightbox, video → fullscreen.
  - Per-row ⋯ menu: mark read · profile · copy note/profile id · **raw event
    JSON dialog** · mute type; `_TypeMeta` styling per kind.
- **`_LoadMoreButton`** (until pagination end-of-results).

**Controller**: read-cursor persistence, mark-visible-read-on-open (1.4 s),
mark-all/per-item, per-type mutes (persisted, badge-excluded), blocked-author
filtering, cached + live actor kind-0 metadata, `unreadCount` for shell badge.

### 3.10 `/more` — You Hub (`modules/more`)

**Files**: `more_view.dart` (515 L) · `more_controller.dart` · binding.

**Anatomy**:
- **Guests**: `_GuestCard` (freedom-first sign-in) → `/auth`.
- **Signed in**: **`_ProfileHero`** — avatar, name, npub chip, identity QR
  (`BrandQrCode`), `_StatTile` row (notes/following/followers/zaps).
- **`_TileGroup`s** (web mobile `/more` parity):
  - **Explore**: Discover (`/discover`) · Meme Studio (`/meme`) · Communities
    (`/communities`) · Lightning (`/settings/lightning`).
  - **Library**: Zaps (`/zaps`) · Bookmarks (`/bookmarks`).
  - **Account**: Profile (`/profile`) · Settings (`/settings`).
- **`_AccountSwitchRow`** → `AccountSwitcherSheet` (multi-account).
- **`_MetaRow`s**: About (`/about`) · Privacy (`/privacy`) · Terms (`/terms`).

### 3.11 `/communities` — Communities (`modules/communities`)

**Files**: `communities_view.dart` (218 L) · `communities_controller.dart` · binding.

**Anatomy**: app bar + joined public-rooms list (`_CommunitiesBody`, hex-avatar
rows, relay/id captions) + FAB **join dialog** — relay URL + group id fields,
prefilled from invite deep links (`?relay=&id=`). ⚠️ UI + local join only;
**NIP-29 wire protocol TODO**.

### 3.12 `/zaps` — Zap Wallet (`modules/zaps`)

**Files**: `zaps_view.dart` (402 L) · `zaps_controller.dart` (model `ZapEntry`) · binding.

**Anatomy**:
- **Stat tiles** (`_StatTile`): totals for sent/received sats + counts.
- **`TabBar`**: **All · Received · Sent** (`zaps.tab.*`) → `_LedgerList` of
  `_ZapRow`s — direction icon (⚡ in/out), sats, peer (hex avatar →
  `/profile/:pubkey`), timestamp, comment excerpt, note link → `/note/:id`
  (when `entry.noteId != null`), loading/empty states.
- **Data**: kind 9735 receipts (received) + `SentZapsStore` local ledger (sent);
  `ZapDirection`, bolt11 msat→sats parsing.
- Send flow lives in the shared **`ZapDialog`** (§7.7): lud16 resolve → 4 tiers
  ⚡💜🔥🚀 + custom + 200-char comment + anonymous toggle → BOLT-11 QR + expiry
  countdown + `lightning:` deep link + copy → 9735 receipt watch + **LUD-21
  verify polling** (3 s) → auto-close; 9734 relays tag = recipient NIP-65
  read relays (≤6) + own (≤3), deduped cap 8.

### 3.13 `/bookmarks` — Bookmarks (`modules/bookmarks`)

**Files**: `bookmarks_view.dart` (207 L, controller declared inline) ·
`BookmarkService` (NIP-51 kind 30003) · `RelayService` re-fetch.

**Anatomy**: app bar + saved-note list (`NoteCard` compact rows) — tap →
`/note/:id`; remove/unbookmark action; empty state; live relay re-fetch on open.

### 3.14 `/create` — Composer (`modules/create`)

**Files**: `create_view.dart` (962 L) · `create_controller.dart` · binding.

**Anatomy** (top → bottom):
- **App bar**: title + **Publish** `TextButton` (disabled until `canPublish`;
  18 px spinner while `isPublishing`).
- **`_ComposerAuthorHeader`**: hex avatar + name.
- **Hint** "Post a note…" multiline field with `_MentionField` (@autocomplete →
  `nostr:npub…` + p-tags).
- **`_ImagePreviewGrid`** (`_ImageThumbnail` removable tiles) +
  `AttachmentPreviewRow` (64×64 image/GIF/video tiles, GIF badge, ✕).
- **`_ContentWarningField`** (NIP-36).
- **`_ComposerToolbar`** (`_ToolbarButton`s): image attach · media-URL attach ·
  **GIF picker** (`GifPickerSheet`: Giphy trending + 350 ms debounced search +
  Recent, 24 h cache) · **poll** (`PollComposerSheet`: 2–6 `poll_option` choices,
  ≤280/≤80 chars) · **PoW** (`PowCard` 0–30-bit slider + hash viz + isolate
  mining, `PowPrefs`) · hashtag · 32-emoji quick picker.
- **`_CharCounter`**: 4,000/16,000-char progress.
- **`_PublishedState`**: success confirmation → thread.

### 3.15 `/meme` — Meme Studio (`modules/meme`)

**Files**: `meme_view.dart` (398 L) + `meme_publish_page.dart` + `meme_controller.dart`
+ `editor/` (filters, palette) + `widgets/` (stage, text sheet, editor chrome) +
`services/` (asset picker, audio preview, export, local export, media import,
voice record, publish) + `models/` (§5.6). `WidgetsBindingObserver` for app
lifecycle (tray recovery after mode switch).

**Editor anatomy**:
- **Top chrome**: ✕ exit · **mode pill** (`MemeMode` Image/GIF/Video) · undo · **Post**.
- **`_buildCanvas` → `MemeStage`**: media + draggable/scalable/rotatable
  `MemeTextOverlay`s; `_EmptyCanvas` mode-aware CTA (emoji + label per mode)
  when no media.
- **`_buildPanel`** (bottom sheet host `MemePanelHost`): `panels_media.dart`
  (tray, multi-import via `MemeMediaImportService`) · `panels_text.dart`
  (`MemeTextSheet`, fonts, palette, outline/shadow via `meme_text_painting`).
- **`quick_tool_rail.dart`** side rail · `editor_timeline.dart` (video:
  `MemeSfxCue`s, audio preview, voice-over `record`, soundtrack import) ·
  `editor_bottom_bar.dart` (mode switcher, undo, export →
  `MemeLocalExportService`).
- **`MemePublishPage`**: `_PublishHeader` + preview (`_PreviewCaptionRow`),
  `_TagSection`, `_SettingsSection` (visibility/cover `publish_cover_strip` ·
  `publish_settings`), publish → `MemePublishService` (kind-1 note w/ media,
  caption, tags).

### 3.16 `/profile` & `/profile/:pubkey` — Profile (`modules/profile`) — tab 4

**Files**: `profile_view.dart` (2513 L) · `profile_controller.dart` · binding
(own = default tag; `:pubkey` = `userProfileTag` — resolves **hex or npub**).

**Anatomy** (CustomScrollView, edge-to-edge — no app bar):
- **`_ProfileCover`** (responsive 160/200 px): banner image / `_DefaultCover`
  (brand gradient + `_HexCoverPatternPainter`) + scrim + `_ProfileAvatarHero`
  (large hex avatar, ⚡ chip when lud16).
- **Floating glass controls**: `_GlassIconButton` back · share · Edit cover ·
  settings; `_GlassPillButton`s.
- **`_ProfileInfo`**: display name + `_DmVerifiedBadge` (NIP-05) + `_NpubChip`
  (copy→check) + `_ProfileChip`s (website, lud16) · **`_ProfileActions`** —
  own: Edit-profile pill + ⋯; other: Follow/Following · Message (→ `/chat`) ·
  Zap (→ `ZapDialog`) + ⋯ (mute/block/share/copy) · **`_FollowStats`**
  (`_StatItem`s → `_FollowSheet` follower/following lists with `_UserRow`s +
  `_FollowTabView`).
- **`_AboutSection`**: expandable bio, `_InfoChip` metadata rows.
- **`_ProfileCompletionCard`**: score bar + Finish → profile editor (own).
- **`_ProfileTabBar`** (sticky `SliverPersistentHeaderDelegate`): **Notes ·
  Replies · Bitz · Reposts** (`_ProfileTabContent`):
  - Notes: `_NotesTab` cards; Bitz: `_BitzTab` video tiles; Media/`_PostGrid` +
    `_MediaTile` grid + list toggle.
  - Replies: `_RepliesTab` + `_ReplyContextStrip` (origin note) +
    `_ShowMoreReplies`.
  - Reposts: `_RepostsTab` (`_RepostItem`; `_UnavailableRepostTile` when the
    referenced event is gone).
  - `_TabLoading` / `_TabEmpty` per tab; `_GuestProfile` signed-out state.

### 3.17 `/note/:id` — Thread (`modules/thread`)

**Files**: `thread_view.dart` (1074 L) · `thread_controller.dart` · binding.

**Root resolution**: event arg, hex id, `note1`/`nevent1`, NIP-33 coordinate
`kind:pubkey:d` via `naddr1` (→ newest `#d` version; relay hints ∩ readable
relays).

**Anatomy**:
- **`_RootPostCard`**: author (hex avatar → profile), `_ThreadPostContent`
  (NIP-27 entities, media grid, inline video), **`_ThreadActionRow`**
  (`_ThreadAction`s: reply count · like +count · zap +sats (optimistic) ·
  repost · share · ⋯ raw JSON/delete).
- **Comments**: `_CommentRow`s — **X-style threading** (top-level + flattened
  descendants behind a left border; NIP-10 reply/root markers; legacy
  root-only accepted; cycle-guarded) + `_CommentActionButton` rows
  (Like +count · Zap +sats · Reply).
- **`_ThreadReplyBar`**: text field + removable media chips + `_OptionButton`s
  (GIF picker · media-URL attach · gallery upload · NIP-13 PoW → `PowCard`) —
  publishes `feed.reply` wire format (root+reply markers + participant p-tags).
- Live deltas: reactions/zaps/reposts for root **and** replies (kind 9735
  bolt11 parsing).

### 3.18 `/settings` — Settings Hub (`modules/settings`)

**Files**: `settings_view.dart` (351 L) · `settings_controller.dart` · binding.

**Anatomy**: **`_AccountHero`** (web `group: 'hero'` parity — large profile row:
  avatar, name, npub, edit → `/settings/account` · sign-in/out → `/auth`) +
**`_sections`** list (mirrors web keys/labels/icons/tints 1:1) grouped like the
web mobile index:

| Group | Sections (key · tint) |
|:--|:--|
| preferences | `lightning` ⚡ FF9500 · `privacy` 5856D6 · `notifications` FF3B30 · `appearance` FF2D92 · `algorithm` BF5AF2 |
| content | `security` FF9500 · `media` 34C759 · `language` 5AC8FA · `relays` 5AC8FA |
| support | `help` 32ADE6 · `about` 8E8E93 |

Tap → `Routes.settingsSection(key)`.

### 3.19 `/settings/:section` — Section Pages (`modules/settings/pages/`)

**Files**: `settings_section_page.dart` (1128 L) dispatcher + per-section
pages (`profile_page`, `appearance_page`, `security_page`, `relay_page`,
`algorithm_page`); shared chrome `_SectionScaffold` + `_SettingsCard` +
`_SettingsSwitch` / `_SwitchRow` / `_IconSwitchRow` / `_SettingsChoiceTile` /
`_PermissionRow` / `_ProviderTile` / `_HelpCard`.

| Section key | Page contents |
|:--|:--|
| `account`, `profile` | `profile_page` — display name, username, about, picture, banner, website, NIP-05, lud16 → kind-0 publish; avatar/cover pickers (`ImageCropEditor`, Blossom upload) |
| `appearance` | `appearance_page` — theme mode (dark/light/system), accent, font size, font family, compact mode |
| `security` | `security_page` — key export (nsec reveal + copy), app-lock, sign-out, danger zone |
| `relays` | `relay_page` — relay CRUD, read/write toggles, `RelayStatusDot` live states, NIP-65 relay-list publish |
| `algorithm` | `algorithm_page` — `AlgorithmPreferences` signal weights per surface, reset |
| `lightning` | `_LightningSection` — default zap amount, wallet → `/zaps` |
| `privacy` | `_PrivacySection` + `_BlockedUsersCard` — blocked pubkeys manage, per-type notification mutes (`PrivacyNotificationSettings`) |
| `notifications` | `_NotificationsSection` — master toggle, sound, haptics |
| `media` | `_MediaSection` — autoplay, video quality, playback rate, default provider (`_ProviderTile`) |
| `language` | `_LanguageSection` — English / Lao (GetX i18n) |
| `help` | `_HelpSection` / `_HelpCard` |
| `about` | version + `/about` link |

### 3.20 `/about`, `/privacy`, `/terms` — Static (`modules/static`)

**Files**: `static_pages.dart` (561 L) · `support_controller.dart` ·
`support_widget.dart`.
- **`AboutPage`**: `_AboutHero` (brand + `_HeroCtas`: Get Started →
  `Get.offAllNamed(HOME)`), `_FeatureCard` grid, `_OpenSourceCard`,
  **Support/donate** section (`SupportController`), Contributors, Nostr
  explainer, `_LegalFooter` (Terms · Privacy links).
- **`PrivacyPage`** — full web text, 12 sections (`_SectionCard`s).
- **`TermsPage`** — full web text, 11 sections.

---

## 4. Feature List (by module, with UX/UI behavior)

### 4.1 Auth & Identity (`modules/auth`)
- Boot splash bridge while persisted identity/onboarding state loads (no progress-circle flash).
- **Generate** keypair → nsec/npub reveal with copy, secure-storage write.
- **Import** nsec or npub (`Nip19` decode → hex).
- Multi-account: saved-account registry, **account switcher overlay** (hex avatars), permanent in-memory, secrets in encrypted keychain.
- Guest mode → guest banner in feed; freedom-first sign-in card in `/more`.

### 4.2 Home Shell & Feed (`modules/home`, `modules/feed`)
- Bottom nav: 5 items, IndexedStack state retention, unread badges capped “9+”, hex-avatar “You” tab.
- **Home surface (web `/` parity)**:
  - Wordmark logo (light/dark variants) top-left; search action; apps-grid action → `/more`.
  - Centered content-filter menu (`AppMenu.showAt`, screen-clamped): All / Original / Replies / Media / Liked / Mine.
  - Sticky **For you · Following** tabs with underline indicator + note counts.
  - Pull-to-refresh (system `RefreshIndicator`).
  - Guest banner (signed-out), live **“↑ N new notes”** reveal pill.
  - Floating bottom **New Note** extended FAB.
  - Web-matching empty states; skeleton loading.
- **NoteCard feed** (see §7 components): rich body rendering — NIP-27 mention/entity rendering (npub/nprofile/note/nevent/naddr, bare, `nostr:`- and `@`-prefixed; TLV relay/author hints; invalid entities inert), hashtags, links, media grid (lightbox), inline video, sensitive-content covers, font-scale-safe show more/less.
- **Stories rail** (header, scrolls with feed): see §4.11.

### 4.3 Discover / Explore (`modules/explore`)
- Search bar + **trending hashtag chips**.
- **Trending mosaic grid** (Instagram-explore style), load-more, skeleton grid, error/retry + empty states.
- **Global search results**: users (NIP-05), hashtags, notes.
- Bitz entry action (play icon → `/bitz`).

### 4.4 Bitz — Reels (`modules/bitz`)
- Full-screen vertical snap-scroll 9:16 video feed with autoplay gated by real surface visibility (RouteAware — never plays offstage).
- Overlay: author (hex avatar, name, follow), caption (rich content), action rail (like + count, zap (NIP-57 dialog, optimistic), comment, share, ⋯ menu), double-tap interactions, swipe gestures.
- **Bits tabs**: glass pills Explore · Following · For you; Explore = video-first 3-col grid (24 + 18/load-more, sensitive covers, tap → player jump); mode persisted; re-tap = back-to-top; mode-aware empty states.
- **Bits search**: glass header actions (search · refresh), full-screen overlay — instant local matches (caption/author) + debounced 400 ms NIP-50 relay search (stale-token cancelled), deduped 9:16 tile grid (2/3/4 cols responsive), idle/searching/no-results states.
- **Comments sheet** = X-style two-level threading (top-level + flattened children; orphans/cycles fall to top-level), hex avatars, Like (+count) · Zap (+sats) · Reply rows; composer with GIF picker · media-URL attach · gallery upload · NIP-13 PoW + 64×64 attachment preview tiles (`AttachmentPreviewRow`); rich comment bodies via shared `CommentContent`.
- Record/create entry (camera capture → composer).

### 4.5 Messages / DMs (`modules/dm`)
- Conversation list (avatar, name, preview, unread), empty state.
- **Encrypted chat**: NIP-17 (gift wrap + seal, NIP-44 v2) with NIP-04 fallback.
- Media in DMs: image/video/file detection (`MessageMedia`), inline player + lightbox, markdown-image extraction.
- **Voice & video calls** (WebRTC over Nostr signaling): `CallSignal` messages (offer/answer/ice/end/log/state), call panel UI, missed/declined/ended outcomes, ICE servers config.
- NIP-05 badges on peers; url_launcher for external links.

### 4.6 Notifications / Activity (`modules/notifications`)
- **Primary tabs**: All · Unread · Mentions · Replies (with counts).
- **Activity chips**: Zaps · Likes · Reposts · Follows.
- Search (name/content); day sections (Today/Yesterday/weekday) with iOS-style grouped rows (“A and N others liked…”, follows never aggregate).
- Unread accent stripe + dot; zap sats (bolt11 msat→sats; 9734 sender from description tag).
- Read-cursor persistence + mark-visible-read-on-open (1.4 s) + mark-all/per-item.
- Per-type mutes (persisted, excluded from badge); blocked-author filtering; live + cached actor kind-0 metadata.
- Per-row ⋯ menu: mark read · profile · copy note/profile id · **raw event JSON dialog** · mute type.
- **Row previews**: origin-note context lines via batched `OriginNoteService` (≤100-id relay batches, 8 s missing → “Note unavailable”) — corner icon, author·time, 2-line excerpt (media stripped, nostr:→@name), 44 px thumbnail (video play glyph, sensitive cover w/ reveal).
- Media strips for single rows: up to 4 tiles 2-col 16:9 + “+N” overflow; image→lightbox, video→fullscreen.

### 4.7 Threads (`modules/thread`)
- Root resolution: event arg, hex id, `note1`/`nevent1`, NIP-33 coordinate via `naddr1`.
- X-style comment threading: top-level comments + flattened descendants behind a left border; NIP-10 reply/root markers; legacy root-only replies; cycle-guarded.
- Comment action row: **Like (+count) · Zap (+sats, NIP-57 dialog, optimistic) · Reply**.
- Live reactions/zaps/reposts for root AND replies (kind 9735 bolt11 parsing).
- Reply bar: removable media chips, GIF picker · media-URL attach · NIP-13 PoW; `feed.reply` wire format (root+reply markers + participant p-tags).

### 4.8 Profiles (`modules/profile`)
- **Edge-to-edge hero** (responsive 160/200 px): banner / brand gradient + hex overlay + scrim; floating glass controls (back · share · Edit cover · settings).
- FB/IG action bar: **Edit profile** pill + ⋯ menu (own) / Follow · Message · Zap + ⋯ (others).
- npub copy chip (copy→check); **identity QR** (`BrandQrCode`); NIP-05 badge; Lightning pill + avatar ⚡ chip when lud16.
- Expandable bio; profile tabs (Notes · Replies · Media — see `profile_tabs_test`); grid/list views; empty states.
- Profile-completion card (score bar + Finish → editor).
- Mute/block/share/copy actions via menus.

### 4.9 Create / Composer (`modules/create`, also thread & bitz composers)
- Author hex-avatar header, “Post a note…” hint.
- Attach: image picker, media-URL attach, **GIF picker** (Giphy trending + 350 ms debounced search + Recent tab, 24 h cache).
- **Poll composer**: kind 1 + `poll_option` tags, 2–6 choices, ≤280/≤80 char limits.
- **NIP-13 PoW**: `PowCard` 0–30-bit slider + hash visualization + background-isolate mining; last difficulty remembered (`PowPrefs`).
- Content warning (NIP-36), hashtag, 32-emoji quick picker, 4,000/16,000-char counter.
- Publishing spinner; disabled-until-valid publish button.

### 4.10 Zaps (`modules/zaps`, `shared/widgets/zap_dialog.dart`)
- **Wallet page**: stat tiles + segmented All · Received · Sent ledger (kind 9735 receipts; Sent via local `SentZapsStore`).
- **Send flow (NIP-57)**: lud16 resolve → 4 amount tiers ⚡💜🔥🚀 + custom + 200-char comment + anonymous toggle → BOLT-11 invoice with **QR + live expiry countdown** + `lightning:` deep link + copy → 9735 receipt confirmation + auto-close.
- 9734 `relays` tag = recipient's NIP-65 read relays (≤6) + own (≤3), deduped, cap 8.
- **Auto-close on payment**: 9735 receipt watch + **LUD-21 verify polling** (3 s until settled/expiry) — first signal wins; late receipt upgrades label “Zap confirmed”. (No WebLN/NWC wallet-connect yet.)

### 4.11 Stories (`modules/stories`)
- **StoriesBar**: NIP-38 kind 30315 + 24 h NIP-40 expiration; Create-story card; own → following order; “Public stories” discovery + Load more; gradient hex-ring unseen / muted ring seen (persisted); kind-5 deletions honored; ≤12/author.
- **Viewer**: full-screen, tap-through progression, like per slide (kind 7 on slide id).
- **Composer sheet**: live 9:16 preview (image + caption scrim or gradient + bold text), numbered slide-queue strip (order badges, per-thumb remove ✕, dashed “+”, tap-to-preview), chevron paging.
- Full toolkit: camera/gallery (Blossom BUD-02 upload at publish), GIF picker, media-URL attach, **@mention autocomplete** (→ `nostr:npub…` + p-tags), hashtags, **NIP-92 imeta**, **NIP-13 PoW** before signing, 32-emoji palette, 6 gradient backgrounds (`background` tag).
- **Mass publish**: multi-select up to 10 → each its own slide (caption/mentions on first), “Posting n of N” progress + per-slide status strip.

### 4.12 Bookmarks (`modules/bookmarks`)
- Saved-notes list from NIP-51 bookmark sets; live relay re-fetch; unbookmark/remove; tap → thread.

### 4.13 Communities (`modules/communities`)
- Joined public rooms list; join dialog (relay + group id); prefilled invite deep links (`?relay=&id=`). ⚠️ UI + local join only; NIP-29 wire protocol TODO.

### 4.14 Meme Studio (`modules/meme`) — Bitz creator tooling
- **Editor modes**: Image / GIF / Video switcher (`MemeMode`).
- **Stage** (`meme_stage.dart`): canvas with drag/scale/rotate text overlays.
- **Text system**: fonts (`MemeFont`), palette, outline/shadow/fill painting (`meme_text_painting.dart`), text sheet.
- **Canvas control**: aspect (`MemeCanvasAspect`), fit, background, resolution enums; non-destructive framing.
- **Timeline** (video): per-overlay SFX cues (`MemeSfxCue` + presets), audio preview, voice-over recording (`record`), soundtrack import.
- **Media tray**: multi-file import (image/video/audio/file), asset picker service, active-asset switching.
- **Editor chrome**: top bar, bottom bar (mode switch, undo, export), quick-tool rail, panel host (media/text panels).
- **Export**: raster/video export service + local save; **publish flow** (`meme_publish_page.dart`): cover strip, settings, publish to Nostr as note.

### 4.15 More — “You” hub (`modules/more`)
- Profile hero with **identity QR**, stats, grouped quick tiles (→ Bitz, Communities, Zaps, Bookmarks, …), legal/meta rows, settings entry.
- Guests: freedom-first sign-in card.
- **Account switcher sheet**.

### 4.16 Settings (`modules/settings`)
- Hub mirrors web sections (same keys/labels/icons/tints): **hero Account · preferences · content · support** groups.
- Pages: `appearance` (theme mode, accent, font size/family, compact mode), `profile` (display name, username, about, picture, banner, website, NIP-05, lud16 → kind 0), `security` (key export, lock), `relay` (add/remove/read-write toggles, status dots, NIP-65 relay-list metadata), `algorithm` (feed ranking signal weights/surface config).
- Persisted prefs: notifications, sound, haptics, language (en/lo), media autoplay, video quality & playback rate, default zap amount, feed options (timeline, media preview, show reactions, protocol notes).

### 4.17 Core engine features (`core/`)
- **Relay stack**: `RelayClient` (single WS) → `RelayPool` (fan-out) → `RelayService` (read/write policies, NIP-65 hints); `SubscriptionManager` (id leases, filters); `AppRouteObserver` for surface visibility.
- **Events**: `NostrEvent` model + `serializeForId` + bip340 `EventSigner`; `EventValidator`; `EventPublisher`; `EventCacheService` (events + kind-0 metadata batches).
- **NIPs in code**: 01, 02, 04, 05, 09 (deletion), 10, 13 (PoW w/ isolate mining), 17, 18, 19, 25, 27, 29 (partial), 36, 38, 40, 44 v2, 50 (search), 51, 57, 58 (badges, README), 65, 92 (imeta), 94/96 + Blossom BUD-02 (media).
- **Algorithm**: `AlgorithmPreferences` (signal defs, weights, per-surface config) + `InteractionProfileService` (affinity, interests, dismissed notes, show-less) powering “For you” and post-menu feedback actions.
- **Content classification**: explainable protocol-note detection for kind-1 (feeds `feedShowProtocolNotes` filter).
- **Media**: `MediaUploadService` (Nostr-authorized Blossom, default free nostr.build), `VideoPosterService`, `MediaProviderPrefs`.
- **i18n**: en_US + lo_LA full string tables.

---

## 5. Data Models (`core/nostr` + module models)

### 5.1 `NostrEvent` (wire model — NIP-01)
```dart
class NostrEvent {
  final String id;                 // 32-byte hex (id of serialized array)
  final String pubkey;             // 32-byte hex author
  final int createdAt;             // unix seconds
  final int kind;                  // event kind
  final List<List<String>> tags;   // [["e", id, relay?], ["p", pubkey], ...]
  final String content;
  final String sig;                // bip340 Schnorr (64-byte hex)
  // serializeForId() → jsonEncode([0, pubkey, createdAt, kind, tags, content])
  // fromJson/toJson; equality by id
}
```

### 5.2 `NostrFilter` (subscription filter — NIP-01)
Author/kind/id/#tag/limit/since/until/e-search (NIP-50) fields; used by `SubscriptionManager`.

### 5.3 Kinds used across the app
| Kind | Use |
|:--|:--|
| 0 | Profile metadata (JSON: name, display_name, about, picture, banner, website, nip05, lud16) |
| 1 | Text notes, polls (`poll_option` tags), memes publish |
| 3 | Contact list / following |
| 4 | NIP-04 legacy DM (fallback) |
| 5 | Deletions (stories, notes) |
| 7 | Reactions/likes (also per-story-slide) |
| 16 | Generic repost |
| 1059 | Gift wrap (NIP-17) |
| 13/14 | Seal / DM (NIP-17, NIP-44 v2) |
| 9734/9735 | Zap request / zap receipt (NIP-57) |
| 10002 | Relay list metadata (NIP-65) |
| 30315 | User status / story slides (NIP-38 + NIP-40 expiration) |
| 30003 | Bookmark sets (NIP-51); mute lists |

### 5.4 DM / call models (`modules/dm/dm_protocol.dart`)
```dart
enum CallKind { voice, video }
enum CallSignalType { offer, answer, ice, end, log, state }
enum CallOutcome { ended, missed, declined }
class CallSignal { callId, type, kind, from, groupId?, sdp?, candidate?, duration?, outcome?, state? }
  // encoded/decoded as nostr: URL query lines in message content
enum MessageMediaKind { image, video, file }
class MessageMedia { url, text, kind }   // markdown-image & first-URL extraction
```

### 5.5 Story models (`modules/stories/stories_controller.dart`)
```dart
class StorySlide   // one ephemeral slide (kind 30315), 24 h TTL
class StoryPublishMedia // queued camera/gallery/GIF item → own slide
class StoryAuthor  // batched kind-0 metadata per author
// limits: ≤12 slides/author, ≥10 following before public fallback (≤20),
// metadata batch 100, initial sub limit 20, seen-set persisted key
```

### 5.6 Meme Studio models (`modules/meme/models/`)
```dart
enum MemeMediaKind { none, image, video }
enum MemeMode { image, gif, video }        // GIF rides image pipeline
class MemeProject {                        // publish-time value aggregate
  mediaKind, mediaPath, mediaWidth, mediaHeight,
  overlays: List<MemeTextOverlay>, canvas: MemeCanvas,
  durationMs?, mediaAssets: List<MemeMediaAsset>, activeAssetId?
}
enum MemeCanvasAspect / MemeCanvasFit / MemeCanvasBackground / MemeCanvasResolution
class MemeCanvas          // non-destructive output framing (draft payload w/ enum ids)
enum MemeFont             // editor fonts
class MemeTextOverlay     // drag/scale/rotate text layer + style tokens
enum MemeMediaAssetKind { image, video, audio, file }
class MemeMediaAsset      // tray file entry
class MemeSfxCue          // timed sound cue (+ MemeSfxPresets)
```

### 5.7 Algorithm models (`core/algorithm/`)
```dart
class SignalState / SignalDef   // named ranking signals + weights
class SurfaceConfig             // per-surface (feed/bitz) signal config
class AlgorithmPreferences      // persisted weights (localStorage parity)
class InteractionProfileService // affinity, interests, dismissed, show-less
```

### 5.8 Persistence keys (`core/constants/storage_keys.dart`, `bitos_*`)
themeMode, accentColor, fontSize, fontFamily, notificationsEnabled, soundEnabled, hapticEnabled, compactMode, language, hasOnboarded, feedTimeline, feedMediaPreview, feedShowReactions, feedShowProtocolNotes, relayConfigurations, mediaAutoPlay, videoQuality, defaultZapAmount, videoPlaybackRate — plus `bitos_seen_stories`, `bitos:pow-prefs`, `bitos:privacy-notification-settings`, profile fields, account registry (secure storage: `nostr_nsec`, `nostr_npub`).

---

## 6. UX/UI Patterns

| Pattern | Implementation |
|:--|:--|
| **Shell** | IndexedStack 5 tabs **Home · Bitz · Chats · Activity · You (hex avatar)**, lazy tab mounting, state retention, re-tap-to-top; Discover reachable via feed search action + You hub |
| **Glass surfaces** | Frosted (`ImageFilter.blur`) floating controls on Bitz/profile hero, glass pills (bits tabs, search header) |
| **Hex identity** | `HexAvatar`/`HexIcon`/`HexShape` — hexagonal avatars everywhere (sovereign-ID motif), gradient hex rings for unseen stories |
| **Menus** | `AppMenu` popover (screen-clamped `showAt`, web `--shadow-pop`), `AppMenuItem`, `AppBottomSheetMenu` |
| **Skeletons & states** | Skeleton grids/lists, loading spinners, ErrorRetryWidget, web-matching empty states, relay-error states |
| **Optimistic actions** | NoteActionsService shared like/repost/zap state across feed/bitz/thread |
| **Live updates** | “↑ N new notes” pill, live reaction/zap deltas, badge counts (cap “9+”) |
| **Media UX** | Lightbox image viewer, fullscreen video player, autoplay gated by visibility, sensitive-content covers w/ reveal, 16:9 media tiles, attachment preview chips 64×64 with GIF badge + ✕ |
| **Safety** | Content warnings (NIP-36), mute (pubkey/word/type), block filtering, raw-JSON transparency dialogs |
| **Motion** | Micro-animations, snap paging, swipe gestures, haptics setting, QR with live expiry countdown |
| **Onboarding/guest** | Boot splash → welcome → auth generate/import; guest banner + sign-in cards |
| **Copy affordances** | npub/nsec copy→check chips, share, deep links (`lightning:`, nostr entities) |

---

## 7. Design System (implemented tokens — `core/theme/`)

> ⚠️ `docs/guidelines/DESIGN_SYSTEM.md` still shows the legacy purple brand.
> Code (authoritative) uses **Bitcoin orange** `0xFFF7931A` as primary.

### 7.1 Color (`app_colors.dart`)
**Dark (default) — `AppColors`**

| Token | Value | Use |
|:--|:--|:--|
| `background` | `0xFF0A0A0F` | App near-black (OLED) |
| `surface` | `0xFF12121A` | Cards, sheets, nav bar |
| `surfaceElevated` | `0xFF1A1A26` | Elevated cards, modals |
| `surfaceOverlay` | `0xFF22222E` | Dropdowns, tooltips |
| `primary` | `0xFFF7931A` | **Bitcoin orange** brand |
| `primaryLight` / `primaryDark` | `0xFFF9A84B` / `0xFFD4790F` | Hover / pressed |
| `primaryGlow` | `0x33F7931A` | Glow shadow (20%) |
| `accent` / `accentWarm` / `accentPink` | `0xFF06B6D4` / `0xFFF59E0B` / `0xFFEC4899` | Cyan / amber / pink |
| `textPrimary` / `textSecondary` / `textTertiary` | `0xFFF8F8FF` / `0xFF9CA3AF` / `0xFF6B7280` | Text hierarchy |
| `textLink` | `0xFFF7931A` | Links (brand orange) |
| `success` / `warning` / `error` / `info` | `0xFF10B981` / `0xFFF59E0B` / `0xFFEF4444` / `0xFF3B82F6` | Semantic |
| `like` / `repost` / `zap` / `reply` / `bookmark` | pink / green / amber / blue / orange | Interaction colors |
| `border` / `borderFocused` / `divider` | `0xFF2A2A3A` / `0xFFF7931A` / `0xFF1F1F2E` | Lines |
| `gradientPrimary` | orange→amber TL→BR | Brand gradient |
| `gradientZap` | amber→orange | Zap surfaces |
| `gradientDark` | transparent→80% black top→bottom | Media scrims |

**Light — `AppColorsLight`**: background `0xFFFAFAFC`, surface white, surfaceElevated `0xFFF5F5F7`, surfaceOverlay `0xFFEEEEF0`, text `0xFF111827`/`0xFF6B7280`/`0xFF9CA3AF`, border `0xFFE5E7EB`, divider `0xFFF3F4F6`; brand/semantic shared with dark.

### 7.2 Typography (`app_typography.dart`) — Inter; mono JetBrains Mono
| Style | Size / weight / height |
|:--|:--|
| displayLarge | 32 / w700 / 1.2 / ls −0.5 |
| headlineLarge | 24 / w700 / 1.3 |
| headlineMedium | 20 / w600 / 1.35 |
| headlineSmall | 18 / w600 / 1.4 |
| bodyLarge / Medium / Small | 16 / 14 / 12 · w400 · 1.5 |
| labelLarge / Medium / Small | 14 w600 / 12 w500 / 10 w500 |
| menuItem | 13 / w600 (web MenuItem parity) |
| mono | 13 / w400 / ls 0.5 (keys, npub) |
`textTheme(brightness)` maps onto Material TextTheme with theme-correct text color.

### 7.3 Spacing (`app_spacing.dart`) — 4-pt grid
`xs 4 · sm 8 · md 12 · base 16 · lg 20 · xl 24 · xxl 32 · xxxl 48`
Semantic: `screenPadding 24 · cardPadding 16 · sectionGap 32 · listItemGap 8 · iconTextGap 8 · avatarGap 12`

### 7.4 Radius (`app_radius.dart`)
`xs 4 · sm 8 · md 12 · lg 16 · xl 20 · full 999` (+ `*All`/`circle` BorderRadius presets)

### 7.5 Elevation/Shadows (`app_shadows.dart`)
- `cardGlow` — primary glow blur 20, offset (0,4)
- `elevatedGlow` — primary 10%, blur 30, offset (0,8)
- `menuPop(dark:)` — web `--shadow-pop` parity (dark 0/16/48 50% · light 0/12/40 16%)
- `bottomNavShadow` — black 30%, blur 20, offset (0,−4)

### 7.6 Icons (`app_icons.dart`) — Solar Icons (7000+) via `solar_icons` mapped to `AppIcons.*` tokens; brand assets `assets/icons/` (logo light/dark, lightning bolt SVG, 192/512 icons), splash set.

### 7.7 Shared component library (`shared/widgets/`)
| Component | Role |
|:--|:--|
| `NoteCard` (+ feed variant) | Rich note card: author, NIP-27 entities, media grid, actions |
| `CommentContent` | Web CommentBody parity: tappable tokens + media (≤9) for comments |
| `HexAvatar` / `HexIcon` / `HexShape` | Hexagonal identity motif |
| `AppMenu` / `AppMenuItem` / `AppMenuDivider` / `AppBottomSheetMenu` | Popover & sheet menus |
| `ZapDialog` | NIP-57 send flow (tiers, QR, countdown, receipt) |
| `PowCard` / `PowBadge` | NIP-13 difficulty slider + mining + hash viz / badge |
| `MediaGrid` / `AttachmentPreviewRow` / `ImageViewer` / `VideoPlayerWidget` / `BitzVideoCover` | Media surfaces |
| `GifPickerSheet` | Giphy trending/search/recent |
| `PollComposerSheet` | Poll creation |
| `ImageCropEditor` | Avatar/cover cropping |
| `BrandQrCode` | Branded QR (profile id, invoices) |
| `Nip05Badge` / `RelayStatusDot` | Identity / relay status |
| `AccountSwitchOverlay` / `AccountSwitcherSheet` | Multi-account UX |
| `BootSplash` / `ErrorWidgets` | Boot & error/retry states |

### 7.8 Theming (`app_theme.dart`, `ThemeController`)
Dark-by-default Material 3 themes built from the tokens; light theme via `AppColorsLight`; persisted mode/accent/font-size/font-family; `context_extensions` helpers.

---

## 8. NIP Support Matrix (as implemented)

| NIP | Feature | Status |
|:--|:--|:--|
| 01 | Events, filters, subscriptions | ✅ |
| 02 | Contact lists | ✅ |
| 04 | Legacy encrypted DM | ✅ fallback |
| 05 | DNS identity verification | ✅ (+ badge) |
| 09 | Event deletion | ✅ (stories, notes) |
| 10 | Thread reply/root markers | ✅ |
| 13 | Proof of Work (isolate mining) | ✅ |
| 17 | Private DMs (gift wrap + seal) | ✅ |
| 18 | Reposts | ✅ |
| 19 | bech32 entities (npub/nsec/note/nevent/naddr + TLVs) | ✅ |
| 25 | Reactions | ✅ |
| 27 | Text note references | ✅ |
| 29 | Relay-based communities | ⚠️ UI only, wire TODO |
| 36 | Sensitive content warnings | ✅ |
| 38 | User status (stories) + 40 expiration | ✅ |
| 44 | v2 encryption (own impl) | ✅ |
| 50 | Relay search | ✅ (bits search) |
| 51 | Lists (bookmarks, mutes) | ✅ |
| 57 | Lightning zaps (+ LUD-21 verify) | ✅ |
| 58 | Badges | 📋 planned |
| 65 | Relay list metadata | ✅ |
| 92 | imeta media tags | ✅ |
| 94/96 | File metadata / HTTP storage + Blossom BUD-02 | ✅ |

---

## 9. Quality & Testing

- `test/` mirrors features: DM protocol, NIP-17/44/57, PoW, nip19, event cache/signer, account manager, zap relays & verify polling, content classification, stories (controller/composer/viewer/PoW), meme suite (canvas/codec/overlay/publish/layers), notifications (sections/reactions/origin/cache), thread threading, note card/feed/content repro, profile tabs, zap dialog, crop editor, menus, media/poster services, privacy settings, hex/brand QR widgets.
- `flutter analyze` clean per `analysis_options.yaml`; CI in `.github/`.

---

*Keep this file in sync with `lib/` and `docs/PARITY_MAP.md` when routes, features, models, or tokens change.*
