# BitOS Native Product System

## 1. Product surfaces

The native app has five primary tabs:

| Tab      | Purpose                                                  | Default destination  |
| -------- | -------------------------------------------------------- | -------------------- |
| Home     | Immersive short-video feed                               | For You / Following  |
| Discover | Search, topics, sounds, templates and creators           | Personalized explore |
| Create   | Camera, import, quick MEM and project library            | Capture chooser      |
| Inbox    | Notifications, comments, zaps and messages               | Activity             |
| Profile  | Identity, published media, saved items and creator tools | Own profile          |

Studio is not a separate social app. Create opens its fast paths; the project library and advanced editor form the deeper Studio experience.

## 2. Audience and modes

- Viewer: can start without a key, browse public events and build a local preference profile.
- Participant: imports, creates or connects a Nostr identity to follow, react, comment, repost, bookmark, report and message.
- Creator: records, edits, publishes, remixes, manages projects and sees privacy-safe analytics.
- Studio creator: uses multi-track editing, reusable templates, sounds, batch creation, brand presets and durable production jobs.
- Curator/moderator: builds lists, reports content, publishes moderation decisions and optionally participates in the value graph.

Anonymous browsing must not silently create an identity. Actions requiring a signature explain why and then offer local key creation, secure import or remote signer connection.

## 3. Feature inventory

### 3.1 Onboarding, identity and account

- Browse-first onboarding with a short explanation of Nostr, relays, Blossom and zaps.
- Create key, import `nsec`/hex, scan QR, connect NIP-46 remote signer and use Android NIP-55 signer.
- Display and copy `npub`, NIP-05 identity, QR and share link.
- Mandatory key-backup education before the first irreversible local-key publish.
- Multiple accounts with isolated database, cache, upload jobs, drafts and wallet state.
- Biometric/device-credential gate for local signing, configurable per sensitive action.
- Account migration/export, remote-signer disconnect and secure local logout.
- Profile metadata, picture/banner, about, website, Lightning address and relay list editing.
- Starter packs for follows, topics and relay configuration.

### 3.2 Home and playback

- Full-screen vertical pager with one active player, warm next/previous players and bounded cache.
- For You, Following and Latest feeds; optional local/community algorithm presets.
- Instant poster, first-frame target, adaptive rendition selection and fallback mirrors.
- Tap pause/play, press-and-hold 2x, double-tap reaction, horizontal seek, volume/mute and captions.
- Expandable caption, hashtags, mentions, sound, content warning, author and provenance.
- Like, comment, generic repost, quote, bookmark, share, zap, report, mute and “not interested.”
- “Why this video?” score explanation and feed controls.
- Continue-watching position for long videos; short videos restart according to preference.
- Accessibility: captions, VoiceOver/TalkBack action rotor/order, reduced motion and no gesture-only actions.
- Network-aware quality, data saver, Wi-Fi prefetch and offline saved media.

### 3.3 Discover and search

- Search creators, exact `npub`/NIP-05, captions, hashtags, notes, sounds, templates and communities.
- Trending and recent topics with transparent time window and region/language controls.
- Sound pages showing playable preview, license, creator, usage count and “Use sound.”
- Template marketplace/library with category, capability compatibility, license, price and provenance.
- Creator and community recommendations based on local interests and web-of-trust signals.
- Search history remains local unless the user opts into sync.
- Search results can degrade from BitOS index to direct NIP-50 relay search.

### 3.4 Capture

- Camera-first vertical recording with 15/30/60 second targets and configurable longer drafts.
- Front/back/available lens switching, flash/torch, focus, exposure, zoom and grid.
- Hands-free countdown, pause/resume segments, speed capture and clip deletion/retake.
- Microphone level, external audio route status and truthful Bluetooth latency warning.
- Optional dual-camera/PIP only on supported devices after capability checks.
- Import from Photos/MediaStore/files/share extension and receive remote media through a safe downloader.
- Permission requests occur only after an explicit action and every denial has a usable fallback.
- Recording segments persist immediately so app interruption does not erase completed takes.

### 3.5 Project library

- Unified Bitz and MEM projects with title, poster, duration, aspect, updated time and status.
- Draft, ready, rendering, uploading, awaiting signature, publishing, published, failed and archived states.
- Search, sort, filter, duplicate, archive, restore, delete and storage usage.
- Autosave with visible Saved/Saving/Failed state and crash-safe atomic revisions.
- Relink missing source assets without losing edit instructions.
- Project compatibility report when opened on a device missing required capabilities.
- Queue card with durable step progress, cancellation, retry and safe resume.
- Optional encrypted cloud backup comes later and is never implicit.

### 3.6 Quick Bitz editor

The quick editor is optimized for one publish in under a minute:

- trim, reorder or delete capture segments;
- crop/rotate/fit/fill and choose cover frame;
- source volume, mute, fade and one sound track;
- caption, hashtags, mentions, content warning and alt text;
- one or more text overlays with timing and simple entry effects;
- looks/filters with intensity;
- automatic preflight, output estimate and one-tap publish;
- “Open in MEM” to duplicate into the expert editor without destructive conversion.

### 3.7 MEM expert editor

The expert editor uses a time-based, nondestructive project:

- multi-clip main track with trim, split, reorder, duplicate, replace, freeze and reverse;
- transform, crop, rotate, opacity, blend and background/canvas controls;
- video, image, GIF, SVG-safe raster, sticker, shape, text and caption layers;
- timing windows, snapping, lock/hide, grouping, z-order and command-based undo/redo;
- speed ramps, zoom windows, frame effects and transitions;
- source audio, music, voice-over and multiple SFX cue lanes;
- waveform, gain, mute, trim, fades, ducking and peak/clipping warning;
- manual captions, speech-to-text import, word timing, styling and WebVTT export;
- filters, safe built-in effects and deterministic presets;
- proxy preview, resolution/frame-rate output policy, poster and final preflight;
- project history checkpoints and duplicate-before-migration when required.

V1 limits must be explicit rather than pretending the editor is unlimited. Recommended initial caps are 60-second public short-video output, 10 video clips, 24 visual layers, 4 audio lanes and 1080x1920 at 30 fps. Capability-tested devices may unlock 4K/60 later.

### 3.8 Meme Studio differentiators

- Classic top/bottom text and free-position timed captions.
- Meme looks, pop/fade/shake/spin caption motion, zoom/speed/frame FX windows.
- Emoji/sticker packs, Bitz Buddy figures, safe SVG rasterization and GIF layers.
- Sound pad with comedy SFX, custom sounds and cue-synced captions.
- Smart templates that suggest slots and timing without modifying the project until accepted.
- Mild/Funny/Chaos suggestion modes; suggestions are opt-in and locally reviewable.
- Image layout, crop, motion and timeline controls.
- Remix chain viewer with parent/root attribution and “Use this MEM.”
- Shareable templates and sounds with explicit license and creator provenance.
- Batch sources, per-item caption overrides and a bounded production queue after single-project reliability.

### 3.9 Draw & Record

- Draw over blank canvas, image, video, screen recording or camera PIP.
- Pen, marker, eraser, color, width, opacity, smoothing and stylus pressure.
- Vector strokes with normalized points and monotonic timestamps.
- Static, replay and hold drawing behavior with timeline windows.
- Performance recording using one clock for strokes, microphone, camera and live SFX taps.
- Separate editable take assets; keep/retry/discard without destroying the last good take.
- Drawing/audio preview and export parity tests.
- No raw camera or microphone upload before explicit final publish/export.

### 3.10 Publish and media

- Durable stages: validate -> render -> hash -> upload -> verify -> build -> sign -> relay publish -> reconcile.
- Primary output: MP4 with H.264/AVC + AAC for broad compatibility; preserve orientation and color metadata.
- Poster plus optional lower-resolution renditions; HLS is a playback optimization, never the only published representation.
- Blossom server selection and multi-home fallback.
- NIP-71 kind 22 by default, optional addressable kind 34236 when edit/migration semantics are intentionally needed.
- Publish progress identifies local render, network upload, signer approval and relay acknowledgements separately.
- A partially acknowledged event is retained and can be republished to missing write relays without resigning.
- Share sheet/deep link after success; draft and output retention choices are explicit.

### 3.11 Social, graph and community

- Follow/unfollow, followers/following, lists, mutes and bookmarks.
- NIP-22 comments for media, including nested replies; NIP-10 only for kind-1 note threads.
- Reactions, generic repost, quote reference and mention notifications.
- Stories as expiring media after core Bitz reliability.
- Communities with moderator lists, approval view and community feed.
- Remix DAG: original, parent, reused sound, template and attribution edges.
- Creator profiles: Bitz grid, MEM grid, notes, likes if public, zaps and activity.
- Share to universal link, NIP-19 link/QR or system share sheet.

### 3.12 Inbox, messaging and calls

- Activity filters for all, comments, mentions, follows, reactions, reposts, zaps and system notices.
- NIP-17 private direct messages using NIP-44 payload encryption and gift wrapping.
- Conversation search over locally decrypted content only.
- Image/video/file messages through user-approved media upload with privacy warning.
- Message requests, block/mute/report and disappearing-local-cache controls.
- Group and call features remain gated until their custom signaling/security model receives a dedicated threat review; web custom URI payloads must not be copied blindly.

### 3.13 Lightning and creator value

- NIP-57 zap request/receipt validation and clear pending/paid/failed states.
- Nostr Wallet Connect for native wallet operations with strict capability requests.
- Custom zap amounts, recent presets and fiat estimate when available.
- Creator, remix parent, sound/template creator and curator split proposal shown before payment.
- V1 may execute separate recipient payments; UI must never label non-atomic transfers as an atomic split.
- Zap ledger, receipts, export and privacy controls.
- Paid templates/subscriptions/shop/live commerce are later legal/product tracks.

### 3.14 Creator Studio and analytics

- Published/draft/processing inventory with health, relay reach and media availability.
- Aggregate views, qualified watch time, completion, replay, engagement and zap totals.
- Per-post retention curve and traffic source only from consented, privacy-bounded telemetry.
- No fabricated exact global counts when only partial relay/indexer data exists.
- Project/template/sound reuse and remix lineage metrics.
- Caption, thumbnail and accessibility completeness checks.
- Exportable diagnostics separate from audience analytics.

### 3.15 Settings

- Account, security, signer and backup.
- Read/write relays, NIP-65 discovery, latency/health and per-relay errors.
- Blossom servers, upload defaults and mirror health.
- Wallet/NWC permissions and disconnect.
- Feed algorithm preset, signal weights, blocked topics and reset local interests.
- Playback quality, autoplay, data saver, cache size and offline downloads.
- Appearance, app icon, language, captions and accessibility.
- Privacy, diagnostics consent, AI consent and content controls.
- Notifications, quiet hours and per-event preferences.
- About, protocol support matrix, licenses, terms and open-source acknowledgements.

## 4. Feed and recommendation model

Candidate sources are kept separate from scoring:

1. Following authors from their advertised write relays.
2. Recent video events from selected discovery relays.
3. Web-of-trust expansion from followed/curated accounts.
4. Topics, sounds and templates matching the device-local interest vector.
5. Optional BitOS candidate API based on indexed public events.

The local scoring pipeline can combine recency, follow/author affinity, topic match, web-of-trust, verified zap value, engagement quality, novelty and negative feedback. It then applies hard filters, safety policy, deduplication, author/topic diversity and exploration.

Raw watch history and a per-event behavioral log stay local by default. Opt-in analytics upload uses coarse, batched measurements and never includes an `nsec`, draft contents, private messages or raw media.

## 5. Safety and moderation

- Verify event signatures and reject malformed/oversized tags before any rich rendering.
- Respect mute lists, reports, content warnings and community moderation choices.
- Provide local keyword/topic filters and user-controlled sensitive-content reveal.
- Server risk signals are advisory and explainable; clients retain local policy.
- Child safety, illegal-content handling and app-store reporting/blocking flows require jurisdictional legal review before public launch.
- Sandboxed media parsing/transcoding with size, duration, frame, resolution and decompression-bomb limits.
- Unicode spoofing, hostile URLs, SVG/script, archive and metadata sanitization.
- Rate limits combine IP/device abuse defenses with Nostr pubkey reputation and optional proof of work; none is the sole trust mechanism.
- Appeals and moderator audit logs exist for BitOS-operated surfaces.

## 6. Offline and degraded behavior

| Condition                      | Required experience                                                                   |
| ------------------------------ | ------------------------------------------------------------------------------------- |
| No network                     | Open drafts, cached feed metadata, saved media and local editor; queue network work.  |
| BitOS API unavailable          | Following/Latest query relays directly; local ranking replaces For You API.           |
| One relay unavailable          | Continue through pool; show health and partial publish acknowledgements.              |
| Primary media host unavailable | Try declared fallback/mirrors, then Blossom hash/server discovery.                    |
| Signer unavailable             | Preserve ready output and resume at awaiting-signature state.                         |
| App killed during upload       | OS transfer or persisted retry resumes without rerendering unchanged output.          |
| Storage low                    | Stop before destructive failure, estimate needed bytes and offer targeted cleanup.    |
| Thermal/memory pressure        | Reduce preview resolution/cache, pause prefetch and keep the current project durable. |

## 7. Product release tiers

- Foundation alpha: identity, relays, read-only feed, playback, local database and diagnostics.
- Creator alpha: native camera, quick editor, durable publish and external-client interoperability.
- Private beta: social actions, discover, moderation, zaps and solid offline/recovery behavior.
- Studio beta: MEM editor, sounds, captions, templates, remix and project queue.
- Public V1: hardened performance, accessibility, analytics, operations and store compliance.
- V1.x: Draw & Record, batch studio, addressable media migration and multi-home automation.
- V2: live, marketplace/economy, collaborative workflows and safely specified open extensions.
