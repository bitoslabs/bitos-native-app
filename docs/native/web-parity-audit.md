# Web-to-Native Parity Audit

Audit date: 2026-08-27  
Web source: sibling `../bitos-nostr-web` (at audit time `../bitos-nostr`), commit `fd4296dd5e42b5ed8224b47fde5654d538dbcfe3`

> The web repo's own `docs/SYSTEM.md` (v0.6.3, 2026-08) is the living web
> feature reference — re-check it before starting each later-wave surface
> (communities, wallet/NWC, studio, trending sounds), because the web app
> has shipped features since this audit.

This audit records what the native plan took from the current web implementation. “Port” means preserve user-visible behavior/protocol, not copy Svelte/TypeScript code. “Redesign” means preserve the product intent with native security, lifecycle and media architecture. “Later” means it is outside public V1. “Reject” means the web mechanism must not become the native implementation.

## Route and surface map

| Web route/surface                          | Observed behavior                                                   | Native decision | Native destination                                                       |
| ------------------------------------------ | ------------------------------------------------------------------- | --------------- | ------------------------------------------------------------------------ |
| `/`                                        | Kind-1 feed, stories, composer, zaps, ranking explanation           | Port/merge      | Home supplementary notes surface; short video is native Home default.    |
| `/bitz`                                    | Immersive media feed, search, comments, zaps, remix, splits         | Port/redesign   | Home For You/Following player and media detail sheets.                   |
| `/discover`                                | People/media/topic discovery with algorithm/WoT input               | Port            | Discover tab.                                                            |
| `/bookmarks`                               | Locally managed saved notes                                         | Port/extend     | Profile Saved with event sync strategy explicitly selected.              |
| `/communities`                             | NIP-29 group UI                                                     | Later           | Discover/Inbox community surface after protocol/product review.          |
| `/messages`                                | DMs, groups, media, custom call signaling                           | Split           | Port secure NIP-17 DMs; gate groups/calls behind threat/protocol review. |
| `/notifications`                           | Activity types, origin preview, media, PoW                          | Port            | Inbox Activity with native push routing.                                 |
| `/note/[id]`                               | Note context/detail                                                 | Port            | Universal-link detail destination.                                       |
| `/profile`, `/profile/[pubkey]`            | Profile metadata, stats and Bitz grid                               | Port            | Profile tab and creator public profile.                                  |
| `/settings` and sections                   | Account, appearance, algorithm, media, privacy, security, Lightning | Port/redesign   | Native Settings; remove browser-only provider/key handling.              |
| `/studio`                                  | Project-oriented Studio home                                        | Port/redesign   | Create tab project library.                                              |
| `/studio/create`                           | Bitz/MEM editor, mobile shell, remix/sound handoff                  | Port/redesign   | Native Quick Bitz and MEM editors.                                       |
| `/more/sounds`                             | Trending sound ranking and Use Sound handoff                        | Port            | Discover Sound pages/library.                                            |
| `/zaps`                                    | NWC wallet, ledger, requests and receipts                           | Port/redesign   | Inbox/Profile wallet and zap ledger with native NWC.                     |
| `/pulse`                                   | Premium UI concept/mock surface                                     | Reference only  | Use as visual/product research, not a feature source.                    |
| `/welcome`, `/about`, `/privacy`, `/terms` | Onboarding and public policy pages                                  | Port content    | Native onboarding/settings plus hosted legal URLs.                       |

## Protocol and state map

| Web module                                 | Valuable contract/behavior                                                         | Native action                                                                         |
| ------------------------------------------ | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| `nostr/bitz-codec.ts`                      | Kind 20/21/22/34235/34236 parsing, address refs, `imeta`, fallbacks/renditions     | Convert tests/fixtures first, then implement the typed BusinessCore codec.            |
| `nostr/event-ref.ts`                       | Regular/addressable stable reference abstraction                                   | Preserve as shared contract fixture and native value type.                            |
| `nostr/pool.ts`, relay stores              | Read/write pools, progressive queries and health                                   | Rebuild with native websocket/lifecycle/outbox routing.                               |
| feed/comments/notifications/profile stores | Projection and optimistic interaction behavior                                     | Port domain cases, replace Svelte/local storage with database repositories.           |
| `nostr/pow.ts`                             | Optional client proof-of-work and display                                          | Preserve as optional bounded worker; never block UI thread.                           |
| `nostr/nwc.ts`, `webln.ts`, wallet/zaps    | NWC, WebLN and zap flows                                                           | Port NWC/zap validation; reject WebLN as native architecture.                         |
| identity store                             | Create/import/multi-account convenience                                            | Redesign: Keychain/Keystore, signer abstraction, NIP-46/NIP-55, no raw local storage. |
| messaging protocol                         | DM/group/call payload and lifecycle behavior                                       | Port only standardized secure DM behavior; custom group/call formats need review.     |
| algorithm pipeline                         | recency, affinity, engagement, novelty, topics, WoT, zaps, diversity and penalties | Port pure scoring concepts and fixtures; keep detailed behavior local by default.     |
| privacy/content classification             | sensitive media, protocol payload and consent handling                             | Port policies as typed native moderation/privacy services.                            |

## Media and publish map

| Web module                                         | Observed value                                                            | Native decision                                                                                                         |
| -------------------------------------------------- | ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `media/publish-machine.ts`                         | Render -> verify -> sign -> publish ordering and terminal states          | Extend into a durable validate/render/hash/upload/verify/sign/publish/reconcile machine.                                |
| `media/uploaders.ts`                               | Blossom, Cloudinary, S3 and server providers; upload classification/retry | Blossom is native default; managed server fallback is product configuration, not user-entered cloud secrets by default. |
| `video-probe`, `video-trim`, `video-output-policy` | Duration, bounds, output and rendition policy                             | Preserve fixtures/policy intent; implement with AVFoundation/Media3/native probes.                                      |
| browser video cut/export                           | Functional web fallback                                                   | Reject as native core; replace Canvas/MediaRecorder paths with platform codecs and shared compositor.                   |
| `MediaPlayer.svelte`                               | Poster, fallback, feed playback UX                                        | Port behavior into bounded AVPlayer/ExoPlayer coordinators.                                                             |

## Studio and Meme map

| Web capability                                             | Native decision                                                                       |
| ---------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| Meme schema v1, normalized overlays and timed captions     | Support import; migrate to richer `com.bitos.studio.project` model.                   |
| Text, image/GIF/SVG, stickers, crop, looks and motion      | Port into scene/layer graph with hostile-input limits.                                |
| Clip split/reorder, trim, speed, zoom and frame FX         | Port into deterministic timeline shared core.                                         |
| SFX recipes, cue tracks, custom/shared/trending sounds     | Port with native audio graph, licenses and provenance.                                |
| Caption-to-cue/beat sync                                   | Port after basic captions/audio are stable.                                           |
| Templates, marketplace categories/prices and Nostr sharing | Port free/shareable templates in V1; paid marketplace is later.                       |
| Remix schema, rights and chain dialog                      | Port provenance/lineage; clearly separate protocol attribution from legal permission. |
| Split tags/value model                                     | Port validation/display; payment execution must disclose non-atomic behavior.         |
| AI consent/smart template/suggestion helpers               | Later beta, opt-in and reviewable; no silent upload or edit.                          |
| Studio handoff and mobile editor shell                     | Preserve fast handoff intent, redesign as native navigation/project state.            |
| Browser project repository/autosave/queue plans            | Implement natively before batch/pro UI promises.                                      |
| Draw & Record plan                                         | P2/V1.x after shared clocks, project durability and render parity are proven.         |

## Explicit rejects

- Storing an `nsec` in `localStorage`, preferences or a plain database field.
- Browser Canvas, MediaRecorder, Web Audio or IndexedDB as the native editor abstraction.
- WebLN as a native wallet integration.
- Copying custom call/group message URI payloads without a protocol and security review.
- Treating Cloudinary/S3 URLs or the BitOS API database as canonical media/post identity.
- Shipping remote SVG, JavaScript, shader or effect code from untrusted events.
- Reproducing desktop sidebars or browser modal patterns where native navigation/accessibility has a better convention.

## Contract assets to extract before feature implementation

1. Generated and public-shaped Bitz media event fixtures.
2. Kind-22 build/parse, addressable replacement and fallback/rendition tests.
3. Publish-machine failure-order tests.
4. Meme schema, overlay, caption, clip, cue, speed, zoom, FX and drawing fixtures.
5. Template/sound/remix/license/split fixtures.
6. Ranking signal and diversity fixtures.
7. NWC/zap and secure-DM protocol vectors that match current NIPs.

Typescript implementation code is a reference, not a shared runtime dependency. Extract expected input/output fixtures rather than mechanically translating framework state stores.
