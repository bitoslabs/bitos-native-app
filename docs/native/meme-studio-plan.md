# Meme Studio — Image & Video Editor: System Plan (APP-019 / EDT / MEM)

Status: proposed · Owner: native (both platforms) · Spec:
`app-unified-feature-spec.md` §3.19 · Quick MEM editor V1 · Epics: EDT-/MEM-
tables in `delivery-plan.md` · Ship target: V1 in 4 milestones (M0–M3), V1.x
polish (M4). Follow-on wave: **M5 multi-clip timeline / take-native
camera handoff / picker layer expansion — see
`meme-studio-multiclip-plan.md` (MST-050..063).**

This plan turns §3.19 into a concrete, phased build. It follows the repo
architecture: deterministic rules and schemas in `shared/business-core`
(common-tested), all UI/rendering native (SwiftUI / Compose), versioned
size-bounded schemas, and a publish machine that never signs before media
is uploaded and hash-verified.

**Reference implementation.** The web client
(`/Users/mts/Desktop/bitos/bitos-nostr-web`) already ships the full studio:
a ~40-module pure engine in `src/lib/meme/`, a desktop editor
(`src/lib/components/bitz/MemeStudio.svelte`), a mobile editor
(`src/lib/components/studio/mobile/StudioMobileEditor.svelte`), and wire
docs under its `docs/source/` (`meme-remix.md` — authoritative remix wire,
`studio-mobile-ux.md` — mobile shell, `templete/tp-*.md` — template/SFX/
mascot packs). Everything in this plan is grounded in that code: pure logic
and constants port into `shared/business-core`; canvas/WebAudio/
MediaRecorder/WebCodecs concerns become native adapters; the data models are
the cross-platform contract. **When this doc and the web code disagree, the
web code wins** — file a plan fix instead of inventing new constants.

---

## 1. Scope

**In scope (V1)**

- Quick MEM editor with three modes: **Image → GIF → Video** (in that ship order).
- Stage with draggable / pinch-scalable / rotatable **text overlays** + emoji stickers.
- Text sheet: 4 semantic font slots (impact/sans/serif/mono), palette, size, outline/shadow.
- Media tray: multi-import (image/video per mode), asset switching, media-URL import.
- Undo (coalesced drags/sliders per EDT-004), mode switcher, save-to-device export.
- **Publish page**: preview, caption, tags, visibility, cover strip (video), CW + alt text;
  publish machine (render → verify → upload → sign(+pow) → publish).
- Project autosave + recovery: ≤6 continue-creating slots (EDT-001/002), crash-safe.
- Hub completion: enable the dead "Quick MEM" row; disabled states for Sound/Remix
  (until APP-021/MEM-003); design-system icons everywhere.

**Out of scope V1, sequenced in M4/V2 from the proven web feature set
(tracked in EDT/MEM tables — do not pull forward)**

- Meme wire document (`com.bitos.bitz.meme` v1) interop codec + web fixtures
  (MEM-001) — small and pure, pulled into M1 (see MST-019).
- Sound-effects pack: 31 synthesized SFX recipes + cue model (MEM-002/007, EDT-007).
- Looks (8 color grades), per-overlay fx, frame fx/zoom/speed tracks (EDT-009).
- Layers suite: image layers, SVG/Iconify, NIP-30 emoji packs, Bitz Buddy /
  Bitzverse mascots, drawing pen (MEM-008, EDT-008).
- Expert timeline / clip windows / caption-sync beats (EDT-005/006).
- Template system: built-in packs, local templates, shared kind-30078
  marketplace with zap-priced unlocks (MEM-004), template publish.
- Remix lineage on publish + RemixChainDialog (MEM-005).
- Batch queue, AI suggestions (local DSP only — MEM-006), value splits,
  sounds marketplace + trending (APP-021, MEM-003), story destination.

---

## 2. UX design (both platforms, same information architecture)

UX reference: web `docs/source/studio-mobile-ux.md` and
`StudioMobileEditor.svelte` (the CapCut-style mobile shell web already
validated). Native mirrors its anatomy: fixed top bar (exit · mode switcher ·
undo · next), glass floating stage tools, **bottom-sheet panels with
"back dismisses the sheet, not the editor"** history semantics, and URL-less
equivalents of its panel ids (`meme, text, sticker, trim, look, publish,
audio, drafts`).

UI concept source: the Honeycomb mockups in `docs/ui/` (gallery at
`index.html`; its `README.md` maps every screen to the spec). They are
design references, not code — tokens (`docs/DESIGN_SYSTEM.md`: Bitcoin-orange
primary, hex-plate motif, Space Grotesk / Inter / JetBrains Mono for ids and
hashes) map onto the existing Solar/AppIcons tokens, and views stay native.
Studio-relevant pages: `app-15` (studio home, quick MEM, V2 suite, batch
setup, review contact sheet, publish machine), `app-04` (camera, quick Bitz
editor, post details, review preflight), `app-05` (publish machine states
incl. hash mismatch and relaunch).

### 2.1 Entry points

| Entry | Today | Becomes |
| --- | --- | --- |
| Home app-bar camera (Android) / photo icon (iOS) | iOS opens import sheet directly; no Create route | **Both**: camera icon → Create hub (parity). iOS keeps a long-press → direct import shortcut. |
| Create hub "Quick MEM" | Dead row | Opens editor with mode picker (Image default). |
| Bitz header camera | Record Bitz | Unchanged (kind-22 pipeline). |
| Trending sounds row (APP-021) | Absent | V1.x: "Use sound" seeds a video project (`soundSeed`). |

Dead hub rows (Use a sound / Remix) get a disabled state + "Soon" chip in M0 so
taps stop reading as broken. Cross-screen handoff uses a one-shot payload
(`{tab, resumeSlotId?, template?, soundSeed?, remix?}`) — web
`studio-handoff.svelte.ts` is the model; native passes it through the hub
router, never via globals.

### 2.2 Editor screen (shared layout contract)

```
┌──────────────────────────────────────┐
│ ✕(discard-confirm)  [Image ▾]  ↶  Next │  top chrome: exit · mode pill · undo · Next
│                                      │
│            STAGE (canvas)            │  media + overlays; gesture target
│      [text overlay: DRAG ME]         │  — drag: pan · pinch: scale · two-finger twist: rotate
│                                      │  — selected overlay: dashed bounds + delete handle
│                                      │
│ ┌──────────────────────────────────┐ │
│ │ tray: [asset1][asset2][+ URL] [+]│ │  media tray (multi-import, switch active asset)
│ └──────────────────────────────────┘ │
│ [Tt text] [☺ sticker] [↶ undo] [💾]  │  bottom bar: add text · sticker · undo · save
└──────────────────────────────────────┘
```

- **Empty-canvas CTA** per mode: "Pick an image" / "Pick frames" / "Pick a clip".
- **Text sheet** (modal): text input, font slot pills, palette swatches, size slider,
  outline width + shadow toggles. Live preview on stage behind the sheet.
- **Discard confirm** on ✕ when the project has unsaved edits (legacy parity).
- **GIF mode**: tray holds frames (ordered, reorderable by long-press drag in M2);
  stage previews the looping sequence; frame delay control (min 20 ms, bounded).
- **Video mode (M3)**: stage plays the clip; overlays are positioned at a scrub
  position and persist for the whole clip in V1 (per-overlay timing windows are
  the web model — see §4.2 — and arrive with the wire codec); trim controls
  reuse the existing VideoPreviewScreen sliders.

### 2.3 Publish page (shared flow contract)

```
[preview]  caption (≤ caption caps, counter)
           tags (≤24, derived + editable)
           settings: visibility · cover strip (video) ·
                     sensitive (NIP-36 CW) · alt text
           [Publish]   ·  [Save to device]
```

- Publish machine (shared states): `render → hash-verify upload (Blossom) →
  sign (+optional PoW) → relay publish → reconcile`. Media **always** uploads and
  verifies before signing (safety rule; reuses `BlossomUploader` + `NotePublisher`).
- Destinations and kinds: see §4.4 (picture memes are **kind 20**, video
  **kind 22/21 by orientation**, kind 1 only as the explicit Note destination —
  web parity, `nostr-infrastructure.md` §kinds).

---

## 3. Reference data model (web truth, ported verbatim)

The web keeps **two distinct models**; native must keep the same split
instead of overloading one schema:

| Model | Web source | Lives | Purpose |
| --- | --- | --- | --- |
| **Local project store** (editor working state) | draft/slot stores (`meme-drafts.ts`, `meme-slots.svelte.ts`) | native only | Fast autosave/restore. Shape may differ per platform; already shipped as `MemeProject.kt` v1 (M0). |
| **Meme wire document** `com.bitos.bitz.meme` v1 | `src/lib/meme/schema.ts` | shared business-core, cross-platform | The interop contract: remix `meme` tag payloads, shared templates, cross-device project exchange (MEM-001 fixtures). Must match web byte-for-byte in semantics. |

### 3.1 Meme wire document `com.bitos.bitz.meme` v1 (authoritative)

```
{ "schema": "com.bitos.bitz.meme", "version": 1,
  "overlays": [ { "id", "text", "x", "y", "size", "color", "font",
                  "caps", "stroke", "bar", "startMs?", "endMs?", "fx?" } ],
  "sfxCues"?: [ { "id", "sfx", "atMs", "gain", "lane?", "soundId?" } ],
  "caption"?, "mediaKind"?: "image"|"video", "lookId"?,
  "createdAt", "updatedAt" }
```

Field contract with web constants (`schema.ts`):

- **Overlay** ≤ `MAX_OVERLAYS = 12`; text ≤ 300 chars, multiline, plain
  (never HTML/markup); x/y = center, normalized 0–1; **size = fraction of
  stage height, clamp 0.03–0.22, default 0.09**; `color` = hex string from
  the fixed 7-color set `#ffffff #000000 #fde047 #f97316 #22d3ee #a3e635
  #f472b6`; `font` ∈ impact/sans/serif/mono; booleans `caps` (default true),
  `stroke` (classic outline, default true), `bar` (contrast pill, default
  false); optional visibility window `startMs/endMs`, half-open `[start,end)`
  in media ms — nonsense windows normalize to "always visible"; optional
  burned-in motion `fx` ∈ none/pop/fade/shake/spin (entry 380 ms, loop
  900 ms, `fx.ts`).
- **sfxCues** ≤ 16; `sfx` = one of the 31 synth recipe ids or `"custom"`
  (then `soundId` required, ≤64 chars); `atMs` integer ≥ 0 media ms;
  `gain` 0–1 (default 1); `lane` 0–3 (organizational only).
- **caption** ≤ 1000 chars; `mediaKind` image|video; `lookId` = one of the
  8 grade ids (`look.ts`: none/mono/noir/sepia/vhs/deepfry/dream/invert).
- **Parse rule (every reader):** coerce/clamp/drop, never throw. Unknown
  schema ids and `version > 1` are rejected; unknown fields ignored; junk
  overlay rows dropped; `updatedAt` always re-stamped on load.

Editor-only state that rides **stores, not the wire document** on web
(native slots may mirror it locally): image layers (≤6, ≤8 MB, height
fraction 0.05–0.9, rotate ±180°, opacity 0.05–1, motion presets),
drawing groups (≤16 groups × 100 strokes × 1500 pts), zoom/fx/speed
windows (≤16 each, ≤4000 ms per window), trim + rate, destination
selection. The wire document is deliberately minimal; timed extras enter
it only via template schema v2 (§4.6).

### 3.2 Local project store (shipped, M0) + required extension

`shared/business-core/.../studio/MemeProject.kt` v1 is the native working
wire: `{v, mode, assets[id,kind,delay], overlays[id,kind,text,font,size px
@1080,colorIndex,outline px,shadow,x,y,scale,rot], trim, frameDelay, cw,
alt, tags}` with bounds 48 overlays / 200 chars / 24 tags / 64 KB wire /
frame delay 20–1000 ms. This stays the autosave format.

To reach MEM-001 ("import web fixtures without visual/timing loss") the
local store needs **backward-compatible optional fields**, added in M1:

| Local addition | Why |
| --- | --- |
| `caps: Boolean?`, `bar: Boolean?` on overlays | web defaults true/false; lossless round-trip |
| `startMs/endMs` visibility windows | timing survives GIF/video projects |
| `fx: MemeOverlayFx?` | pop/fade/shake/spin port |
| `MAX_TEXT_LENGTH` 200 → 300 | web parity (meme captions ≤300) |
| `lookId: String?`, `caption`, `sfxCues` (later milestones) | wire fidelity as features land |

Conversion rules local ⇄ wire (pure functions in shared core, common-tested):

- **size**: wire fraction × 1080 reference → local px (0.09 → 97 px);
  px ÷ 1080 → fraction. Local MIN 12 px is below web min 32 px — clamp to
  the tighter bound on export.
- **color**: wire hex ⇄ nearest local `MemeRules.PALETTE` index.
  Open decision (§9): adopt the web 7-hex set as indices 0–6 (recommended,
  keeps exports visually identical to web) or keep the 16-color local
  palette with nearest-match. Until decided, nearest-match.
- **stroke ⇄ outline**: web `stroke=true` ↔ local `outline > 0` (use the
  outline width; web has no width — platforms default per style).
- **unknown wire fields**: preserved verbatim in a `passthrough` map on
  import so a native round-trip never destroys future-web data.

### 3.3 Remix lineage wire (web `docs/source/meme-remix.md` is authoritative)

- Tags on publish: `["remix", <root/parent event id>, ≤3 relay hints]` +
  `["meme", <compact wire document ≤700 chars>]` + `["p", <source author>]`.
  Compact payload keys `v/o/c/l/g/z/f/s`; overlay decode cap 12; depth cap
  32 with cycle guard run **pre-sign and re-checked post-sign**
  (`wouldCycle`); legacy `["bitz:edge","remix","event:<id>","1"]` form read
  but never written; 5 license codes ride `["license", code]` + optional
  `["attribution", ≤140 chars]`. Stories and notes deliberately carry **no**
  remix lineage.
- Degradation ladder when the payload would exceed 700 chars:
  layout+cues → layout → media-only. Never truncate silently.

### 3.4 Publish destinations & imeta (web truth table)

| Content | Kind | Notes |
| --- | --- | --- |
| Picture meme (image or animated GIF, no sound cues) | **20** (NIP-68) | imeta `image/gif` for GIFs. Matches `nostr-infrastructure.md` ("kind 20 … MEM image output") and web. |
| Video meme, sound-cued image (exports as video) | **22** portrait / **21** landscape | orientation from render target `height ≥ width` |
| Explicit "Note" destination | 1 | caption + attachment URLs + imeta |
| Story (V2) | 30315 (NIP-38 + NIP-40 expiry) | sound-on-static exports as video |

Common rules (web `bitz-codec.ts` / `feed.svelte.ts`): imeta carries
`url m size dim thumb fallback x duration(bitdp 3) bitrate`; a JPEG **poster
frame is uploaded once separately** and referenced as `thumb`; NIP-31
`["alt", caption ≤200]`; NIP-36 `["content-warning", reason]`; caption caps
meme SOFT 300 / HARD 1000 (note-only text 4,000/16,000); optional NIP-13
PoW; `validateBitzMedia` pre-sign (https-only in prod, 64-hex hash, `WxH`
dim, duration > 0) and post-sign cycle re-check for remix. Upload path is
Blossom BUD-02/11: kind-24242 auth (`t/upload`, `x` sha256, expiration
now+60 s, server), `PUT /upload`, **returned hash must equal local sha256
before anything is signed**.

### 3.5 Templates & sounds (kind 30078, M4/V2)

- Template event: kind **30078**, `d = "com.bitos.bitz:template:<id>"`,
  content `{schema:"com.bitos.bitz.template", version:2, label ≤40, icon
  (8-id allowlist), overlays[...], sfxCues?, zoomWindows?, fxWindows?,
  speedWindows?, imageLayers?, price_sats?, category?}` — v1 events
  (overlays only) parse as free templates; `price_sats` tiers
  **{0, 21, 100, 500}**, junk guard 1,000,000; 10 fixed categories; needs
  ≥ 1 valid overlay. Local templates ≤ 24. Zap unlock = NIP-57 zap
  (e-tag = template event) + local unlock ledger ≤ 500 entries.
- Sound event: kind **30078**, `d = "com.bitos.bitz:sound:<id>"`, tags
  `url / x(sha256) / license / attribution ≤140 / t ≤10×40 / image ≤2048 / p`,
  content `label ≤40, durationSec ≤15, mime ≤64 (default audio/webm),
  description ≤500`. Ingest only `CC0-1.0 / CC-BY-4.0 / CC-BY-NC-4.0`,
  sha256-verify bytes. Local library ≤ 30 sounds, ≤ 8 MB, ≤ 15 s.
- Trending sounds: derived, not stored — count `meme`-tag synth ids over
  recent kind 20/21/22 events, **3-day half-life** score (APP-021).

### 3.6 Synth SFX pack (M4)

31 recipes are **pure note/oscillator data** (`sfx.ts`) — no audio assets,
no licensing. Port the table into shared core (`SfxRecipes`), render via
platform synth (AVAudioEngine / AAudio) at 44.1 kHz stereo, master gain 0.5,
+250 ms tail, 12 ms attack envelope; custom sounds peak-normalized mono,
nearest-sample resample, 15 % edge fade. Cue timing (data) is the contract;
timbre may differ per platform (goldens assert cue schedule, not waveform).

---

## 4. Architecture (repo-rule compliant split)

### 4.1 `shared/business-core`, package `space.bitos.core.studio`

Shipped in M0: `MemeProject` + contract (local store), `MemeRules`
(palette, clamps, overlay CRUD, hit-test, `MemeCommand` undo model),
`StudioPublishContract` (`Draft → Rendering → Uploading → ReadyToSign →
Signing → Published/Failed`, verify-before-sign enforced structurally),
`MemeCommandCodec`, bridge surface. Common tests green
(`commonTest/.../studio/`).

Planned additions (each versioned + size-bounded, common-tested):

| Type | Milestone | Purpose |
| --- | --- | --- |
| `MemeWireDocument` + `MemeWireCodec` | M1 (MST-019) | §3.1 schema with exact web constants; tolerant parse; passthrough map; local ⇄ wire converters |
| `MemeFxRules` | M4 | per-overlay fx ids + deterministic transforms (380/900 ms, easeOutBack, shake amps) |
| `MemeTrackRules` | M4 | zoom/fx/speed window types + clamps (≤16 windows, ≤4000 ms, rate 0.5–2, factor ≤4, ≥100 ms gaps, smoothstep 140 ms); speed time-mapping (trapezoid) |
| `StickerCatalog` | M1 | 6 packs × 8 emoji, recents ≤16, default size 0.18, emoji-only unicode test |
| `SfxRecipes` + `SfxCueRules` | M4 | 31 recipe tables, cue windowing (`atMs < durationMs`), cue-track duration `max(1,(last+500)/1000)` s |
| `TemplateContract` | M4 | template schema v2 parse/build, price tiers, categories, unlock-ledger model |
| `SharedSoundContract` | M4 | kind-30078 sound parse, license gate, sha256 expectation |
| `RemixContract` | M4 | compact payload codec, depth/cycle guards, license/attribution tags, degradation ladder |

### 4.2 Native layer (per platform; no shared UI)

| Concern | Android | iOS |
| --- | --- | --- |
| Stage rendering | Compose `Canvas` + `GraphicsLayer`; overlays as `Text` with transform cache | SwiftUI `Canvas`/`ZStack`; overlays as `Text` with `scaleEffect/rotationEffect` |
| Gesture | `detectDragGestures` + `detectTransformGestures` | `MagnificationGesture` + `RotationGesture` + `DragGesture` sequencing |
| Image raster | `GraphicsLayer.toImage()` (API 34+) fallback `Picture → Bitmap` at 1080 long edge | `UIGraphicsImageRenderer` of the stage layer |
| GIF decode/encode | frame decode; vendored pure GIF89a encoder (256-color median cut, NETSCAPE2.0 loop, delay floor 20 ms) — port of web `gif-encode.ts`, no remote code | `CGImageSource` decode; `CGImageDestination` GIF with per-frame delays |
| Video export | Media3 Transformer `OverlayEffect` (bitmap sequence from overlay params), 30 fps / ~6 Mbps target | `AVMutableVideoComposition` + CALayer mirroring stage params |
| Audio (M4) | `AAudio`/`AudioTrack` synth from `SfxRecipes`; cue mixdown into export track | `AVAudioEngine` synth; `AVMutableComposition` mixdown |
| Catalog / project store | `MemeProjectStore` (files under `filesDir/studio/`, assets copied in — CAP-005 rule) | Application Support/studio/, same layout |
| Recovery | autosave debounced ~500 ms after each command (web draft debounce); ≤6 slots LRU, poster thumbnails ≤192 KB JPEG, no TTL, explicit delete only | same slot contract |

Export parity numbers (web): canvas long edge 1080; video capture 30 fps,
6 Mbps; GIF export ≤ **360 frames**, cadence falls back to 12 fps, stills =
1 frame @ 100 ms; encode 256 colors/frame. Size guard: GIF ≤ 8 MB →
deterministic downscale ladder.

Adapter-contract tests (per repo rules): store round-trip, raster
determinism — text metrics differ per platform, so goldens are
**per-platform** fixture hashes, not cross-platform — GIF frame
count/delay assertions, video export duration/track smoke, SFX cue-schedule
golden (timing, not waveform).

### 4.3 Bridge surface (only where shared logic must be reached from Swift)

Existing: `memeProjectNormalize / memeApplyCommand / memeHitTest`. Add with
MST-019: `memeWireEncode/Decode`, `memeWireToLocal`, `localToMemeWire` —
same pattern as `composerDraftEncode/Decode`.

---

## 5. Milestones & task breakdown

### M0 — Foundations + hub completion — **SHIPPED** ✅

`MemeProject` v1 + codec + hostile-clamp tests · `MemeRules` + tests ·
`StudioPublishContract` + tests · hub rows honest (Quick MEM enabled →
editor when M1 lands; Sound/Remix "Soon" chips; Solar/AppIcons tokens; iOS
Home camera → hub) · bridge surface. Details + file pointers:
`native-ui-build-tracker.md` §APP-019.

### M1 — Image editor end-to-end (~1.5–2 weeks)

| ID | Task | Platform | Acceptance |
| --- | --- | --- | --- |
| MST-010 | Editor shell: top chrome (discard confirm, mode pill locked to Image, undo, Next), empty-canvas CTA | both | matches §2.2; discard confirm only with edits |
| MST-011 | Media tray: multi-pick images (≤9, ComposerRules.MAX_IMAGES parity), asset switching, URL import (http/s validation, ≤200 MB cap, type gate — web `source-fetch.ts` parity) | both | hostile/oversize asset rejected with visible error |
| MST-012 | Stage: render active asset; add text overlay; drag/pinch/rotate with z hit-test; delete handle | both | gesture smoke on device matrix; overlay never leaves canvas bounds |
| MST-013 | Text sheet: font slot pills, palette, size, outline/shadow; live stage preview | both | pure style mapping from `MemeRules` (no per-platform defaults) |
| MST-014 | Emoji stickers: `StickerCatalog` (6×8) as sticker kind; recents ≤16 | both | sticker overlays behave like text overlays |
| MST-015 | Undo: command stack w/ drag coalescing (300 ms), redo omitted V1 | both | EDT-004: reload restores last autosave; undo restores geometry |
| MST-016 | Raster export at canvas resolution (1080 long edge) → device save (MediaStore / PhotoLibrary) | both | golden-hash determinism test per platform |
| MST-017 | Publish: picture memes → **kind 20** with imeta (new `composeMediaPicture` composer path; `publishMediaNote` stays the kind-22 path); CW + alt + NIP-31 alt + optional PoW | both | publish reconciles in feed; signed only after upload hash verify (test) |
| MST-018 | Autosave + ≤6 recovery slots (thumbnails, explicit deletes); hub "Continue creating" row | both | kill/relaunch loses no committed edit (EDT-002) |
| MST-019 | **Meme wire document** `com.bitos.bitz.meme` v1 codec + local ⇄ wire converters + local-store extension fields (§3.2) + fixtures ported from web `schema.test.ts` | shared+iOS | MEM-001 fixture battery green on both platforms; passthrough preserved |

#### M1 execution note (2026-09-02) — mockup → component mapping, wave order

UI references: `docs/ui/app-15-studio-mass-production.html` **Quick MEM**
screen (`scr-quick`) is the authoritative layout; `app-04` Post
details/Review sheets belong to MST-017. Web truth for behavior:
`StudioMobileEditor.svelte` + `studio-mobile-ux.md` (back dismisses the
sheet, never the editor) and `src/lib/meme/*` for constants. Waves —
each ships a reviewable, honest increment:

| Wave | Tasks | Deliverable |
| --- | --- | --- |
| 1 (editing core) | MST-010..015 | Full editing loop: import ≤9 → text/sticker overlays (drag/pinch/twist, z hit-test, delete) → text sheet → coalesced undo. No export/publish/persistence yet — editor stays session-only and the hub row lands with it. |
| 2 (interop gate) | MST-019 | Wire codec + converters + fixtures, BEFORE the first native publish (§9 gate). **SHIPPED 2026-09-02**: `MemeWireDocument.kt` — `MemeWire` constants (web `schema.ts` verbatim incl. the 31 SFX ids), `MemeWireCodec` tolerant decode/encode/normalize with passthrough preservation, `MemeWireConvert` local ⇄ wire (size ⇄ px, nearest-match colors, stroke ⇄ outline, sticker detection, local-only scale/rot/shadow riding overlay passthrough); local store grew `caps/bar/startMs/endMs/fx` + text cap 300; bridge `memeWireNormalize/memeWireToLocal/localToMemeWire`; contracts fixture `contracts/meme/wire-document-v1.json` + `MemeWireCodecTest` battery (web `schema.test.ts` ported branch-for-branch). |
| 3 (export) | MST-016 | 1080 raster → device save; per-platform goldens. **SHIPPED 2026-09-02**: `MemeExportRules` (web `render.ts` parity — `targetSize` port: long-edge cap 1080, no upscale, evened dims; `exportPlan` = target-px draw commands incl. the `caps` uppercase transform, min-10-px font floor, outline ×2 stroke scale and the local `scale` honored; geometry goldens common-tested). Android `MemeRaster` (android.graphics renderer + MediaStore `Pictures/BitOS` PNG saver, minSdk 29) and iOS `MemeRaster` (UIGraphicsImageRenderer over the bridge envelope + PHPhotoLibrary add-only save) both consume it; Save button + status in the editor tools row both platforms. Font reference corrected to canvas HEIGHT (web `paintOverlay`: `px = size × referenceHeight`) — stage previews re-based to height/1080 on both platforms so stage ⇄ export stay WYSIWYG. Pixel-hash goldens need a device rasterizer (no Robolectric/instrumented infra in this repo) — the plan-geometry goldens run in common tests; bitmap hashing rides the manual QA matrix. |
| 4 (publish) | MST-017 | kind-20 picture path + CW/alt/imeta; verify-before-sign test. **SHIPPED 2026-09-02**: shared `NoteComposer.composeMemePictureNote` (web `feed.postBitz` picture parity — tag order t-tags → `alt` (explicit else caption ≤200) → `imeta` url/m/x/size/dim → `content-warning` last; caption hard cap 1000) + bridge `composeMemePictureEventId`/`memePicturePublishMessage`; Android `NotePublisher.publishMemePictureNote` + `MediaPublishViewModel.publishMemePicture` (render → hash-verified Blossom upload → kind-20) + publish sheet; iOS `NotePublisher.publishMemePictureNote` + publish sheet. Repo test `publishesKind20PictureMemeWithWebTagOrder` asserts the signed frame (kind, tags, imeta) + relay-OK receipt; protocol fixture `contracts/nostr/kind-20-unsigned.json`. PoW path intentionally deferred (rides the composer PoW lane when memes grow it). |
| 5 (durability) | MST-018 | Autosave slots + hub "Continue creating". **SHIPPED 2026-09-02** — M1 COMPLETE: shared `MemeSlots` (slot wire codec `MemeSlotDocument {v,id,updatedAt,assets[id,file,aspect],project}`, index codec, `MemeSlotRules` LRU ≤6/front-bump/evict-returns, label + relative-time; hostile files/paths degrade to empty, traversal refs rejected); Android `MemeProjectStore` (`filesDir/studio/slots/<id>/`, idempotent asset copy-in, ~256 px poster, JVM round-trip test via injected bitmap edges) + editor autosave (500 ms debounce after every committed edit, kill/relaunch restores exactly; publish DONE and discard-confirm clear the slot) + hub rows (poster/label/time/Resume/✕); iOS `MemeProjectStore` (Application Support/studio, same layout) + same editor wiring + `SlotRow`s. Also fixed a wave-1 latent UI bug: the Android editor state was plain Kotlin (drags/adds never recomposed) — it now carries a Compose `revision`. |

Component mapping (both platforms, same semantics):

| Mockup element (app-15 `scr-quick`) | Component | Task |
| --- | --- | --- |
| Top bar `✕` + mode chips + `↩` (+ `Post` in wave 4) | `MemeEditorScreen` (Compose) / `MemeEditorView` (SwiftUI); ✕ → discard confirm only with edits; GIF/Video chips render disabled until M2/M3 | MST-010 |
| Media tray `[asset][asset][＋]`, active tile accent border | `MemeEditorState.assets` + tray row; `PickMultipleVisualMedia(9)` / `PhotosPicker` ≤9 (ComposerRules parity); tap switches active; URL import wave 4+ | MST-011 |
| Stage, dashed overlay bounds + handles, "drag · scale · rotate" | Tap selects via shared `MemeRules.hitTest`; drag/pinch/twist manipulate the selection; ✕ chip deletes selection; clamps live in shared `apply` | MST-012 |
| Text style card (font pills, swatches, effect chip) | Text sheet: field, 4 font-slot pills, `MemeRules.PALETTE` swatches (bridge `memePalette`), size + outline sliders, shadow toggle; live via `MemeCommand` | MST-013 |
| (sticker path) | Shared `StickerCatalog` (web `stickers.ts` port: 6 packs × 8, recents ≤16) + sticker sheet | MST-014 |
| `↩` undo | `MemeEditorState` command stack: gestures push ONE net `UpdateOverlay` at gesture end; discrete bursts coalesce via `MemeRules.coalesce` (300 ms); bounded `(project-before, command)` entries restore on undo | MST-015 |

State rules (both platforms): editor state holds `MemeProject` + bounded
undo stack; asset ids are session-local (`a1…a9`) mapped to platform
image refs; `MemeProjectStore` persistence arrives with MST-018. All
mutations flow through `MemeRules.apply` so clamping/caps/hit-test stay
single-sourced; platform code only maps gestures → commands and commands
→ pixels (Android consumes shared core directly, iOS through the bridge
seams per §4.3).

### M2 — GIF mode (~1 week)

**Progress 2026-09-02 (wave M2a): SHARED GIF ENGINE + ANDROID GIF MODE.**
Shared, common-tested: `GifEncoder.kt` (pure GIF89a encoder — verbatim
port of web `gif-encode.ts`: same LZW cadence, same median-cut over a
5-bit histogram, local color tables, NETSCAPE2.0 loop, 2 cs delay floor;
deterministic — identical inputs → identical bytes), `GifDecoder.kt`
(pure decoder — port of web `gif.ts`'s fallback parser: GCE/disposal
2+3, interlace, transparency, sub-2cs→100 ms heuristic; tolerant — junk
→ null), `GifExportPlan.kt` (planner port: source cadence, 20 ms floor
collapse, ≤360 guard, still→single-100 ms frame, pin-trims-only; plus
the ≤8 MB → halve-long-edge ladder ≤3 steps) — 16 common tests incl. the
**encoder⇄decoder round-trip golden** (MST-022's smoke test, in common).
ANDROID: `GifFrameSource` (decode via the SHARED decoder — no platform
API is frame-accurate; ≤60 frames, stills→100 ms), `MemeGifExport`
(planner → per-frame raster w/ overlays burned in → shared encoder →
size ladder), `MemeRaster.renderFrameRgba` + `saveMediaFile`; editor:
GIF chip live (switch with confirm; overlays survive), frame tray with
long-press-drag reorder + uniform-delay slider (20–1000 ms), looping
stage preview at per-frame holds, Save → `.gif` MediaStore (ladder/cap
status surfaced), Publish → kind-20 `m image/gif` through the same
verify-before-sign machine (VM takes mimeType); GIF frames persist in
continuation slots (PNG per frame via a mem: opener; resume restores
the tray, holds collapse to uniform 100 ms in V1). Wave M2b (2026-09-02): iOS GIF MODE DONE — M2 COMPLETE. Bridge seams
`memeGifPlan`/`memeGifLadderCanvas` (shared planner drives iOS timing +
ladder; + common test). `GifFrameSourceIos` (CGImageSource decode with
per-frame delays incl. UnclampedDelayTime, cumulative compositing —
disposal-2 partial-frame ghosting is an accepted V1 caveat, documented;
sub-2cs → 100 ms). `MemeGifExportIos` (shared plan steps → per-frame
UIGraphicsImageRenderer composition with overlays → CGImageDestination
GIF89a with per-step delays → 8 MB halve ladder ≤3 steps). Editor: GIF
chip live (confirm-on-media; overlays survive via wire transplant),
frame tray ≤60 with onDrag/onDrop reorder + delay slider (20–1000 ms),
looping stage preview, Save → `.gif` Photos, Publish → kind-20
`m image/gif` (publish path parameterized by mime), frames persist in
slots + resume (holds collapse to uniform 100 ms). Fixture
`contracts/nostr/kind-20-gif-unsigned.json`. Remaining: on-device QA.

| ID | Task | Platform | Acceptance |
| --- | --- | --- | --- |
| MST-020 | Frame tray: decode image/GIF into bounded frames (≤60, delay ≥20 ms; web sub-2cs heuristic → 10cs), reorder | both | frame-count + delay assertions |
| MST-021 | Stage preview loops frames; overlays persist across frames (V1 semantics) | both | — |
| MST-022 | GIF export: per-frame raster + vendored encoder (≤360 frames, 12 fps fallback cadence, 256-color); size guard ≤8 MB → downscale ladder | both | golden smoke test; oversize falls back deterministically |
| MST-023 | Publish: **kind 20** + imeta `image/gif` | both | feed renders GIF tile |

### M3 — Video mode (~1.5–2 weeks)

**Progress 2026-09-02 (wave M3a): shared video rules + ANDROID video mode.**
SHARED: `NoteComposer.composeMemeVideoNote` (kind 22 portrait / 21
landscape from the EXPORTED frame orientation — web `postBitz` parity;
tag order matches the picture path; imeta gains `duration` seconds) +
`BlossomTest` orientation battery + bridge pair
`composeMemeVideoEventId`/`memeVideoPublishMessage`. ANDROID: video chip
live (confirm switch like GIF); pick (VideoOnly, ≤60 s via
MediaMetadataRetriever probe, ≤64 MB read bound) hands through the
EXISTING `VideoPreviewScreen` trim screen (MST-030 reuse) whose "use"
output feeds the stage; `VideoStage` = ExoPlayer surface (data-uri
transport) with overlays composed on top at any playhead + play/pause +
scrub (MST-031 V1: overlays burn across the whole clip); `MemeVideoExport`
= full-frame transparent overlay bitmap (the shared `MemeExportRules`
geometry — WYSIWYG) burned via Media3 Transformer `OverlayEffect`
(`BitmapOverlay` static, one pass) with a completion poll + 120 s
deadline; Save → `Movies/BitOS` MP4; Publish → kind 22/21 with
duration+dim imeta through the unchanged verify-before-sign machine
(`publishMemeVideo` VM lane + `NotePublisher.publishMemeVideoNote`);
clips persist in slots (`mem:v1`) and resume re-probes. Wave M3b (2026-09-02): iOS VIDEO MODE DONE. `MemeVideoExportIos`
(AVAsset probe — rotation-aware upright dims via preferredTransform;
AVMutableComposition + AVMutableVideoComposition at the upright render
size with ONE full-frame CALayer from the shared envelope (WYSIWYG) via
AVVideoCompositionCoreAnimationTool; AVAssetExportSession HighestQuality
→ MP4 bytes, temp files cleaned). `VideoStageIos` (AVKit VideoPlayer +
overlays at any playhead + play/pause + scrub; tap-through selection).
Editor: Video chip live (confirm switch; overlays survive), pick
(.videos) → probe ≤60 s → the EXISTING `VideoPreviewScreen` trim handoff
→ stage; Save → Photos video (add-only); Publish → exported MP4 →
hash-verified upload (video/mp4) → `NotePublisher.publishMemeVideoNote`
(kind 22/21 + duration imeta via the M3a bridge pair); clips persist in
slots (raw data asset `v1`) and resume re-probes. Wave M3c (2026-09-02): MST-032 DONE — **M3 code-complete**. Shared:
`UploadedMedia.thumbUrl` (validated https/loopback, ≤2048) rides as
imeta `thumb` through every composer; the video bridge seams grew the
param; `BlossomTest` covers thumb-rides + policy reject; fixture
`kind-22-unsigned.json` carries a thumb. Android: `captureCoverJpeg`
(MediaMetadataRetriever at the playhead, closest-sync, JPEG 85) →
`MediaPublishViewModel.uploadMemeCover` (separate hash-verified upload) →
"Set cover / Cover ✓" chip in the video scrub row; publish passes the
URL. iOS: `captureCoverJpeg` (AVAssetImageGenerator, upright transform,
≤1080) → BlossomUploader image/jpeg → the same stage chip +
`publishMemeVideoNote(thumbUrl:)`. Cover pick is session-only in V1
(the project wire has no thumb field — re-pick after relaunch; noted).
MST-035 QA stays a MANUAL gate: long-clip bound, call interruption,
backgrounding, low storage, thermal, process-kill mid-export (CAP-009
reuse) — enumerated in the tracker; no simulator runtimes on the
current machine, so device QA is pending.

| ID | Task | Platform | Acceptance |
| --- | --- | --- | --- |
| MST-030 | Video tray: pick clip (auto-cut to 60 s with "over the size limit" message — never rejected) or Record Bitz handoff; reuse VideoPreviewScreen trim | both | trim output feeds editor unchanged |
| *(2026-09-02 cut-policy revision)* | Long clips CUT, not rejected: shared `MemeVideoCutRules` (pick-time 60 s cap + proportional size ladder, floor 5 s, ≤3 retries, creator-facing "over the size limit — trimmed to Ns"); both exporters now honor the project trim window (Android Media3 `ClippingConfiguration`, iOS AVComposition range); publish ladders re-render until the export fits; kind-22 `duration` imeta carries the CUT duration. Android probes picker `content://` sources through a resolver file descriptor, accepts local editor inputs up to 256 MiB, and plays preview/editor clips from temporary file URIs (never Base64 data URIs); the 64 MiB Blossom bound remains a final-export/upload rule. | both | `MemeVideoCutTest` green; both apps compile |
| MST-031 | Stage: player surface + overlay positioning at scrub position; overlays burn across full clip (V1) | both | overlay geometry persists over scrub/rotate |
| MST-032 | Cover-frame capture (scrub + set poster) → separate poster upload → imeta `thumb` + `preview` (feeds FeedNote poster hints) | both | cover appears in feed card + Bitz |
| MST-033 | Export: overlay burn-in via Transformer / AVVideoComposition, 30 fps / 6 Mbps target; progress + cancel | both | export duration/track test; cancel is clean |
| MST-034 | Publish: **kind 22 portrait / 21 landscape** (orientation rule §3.4) with imeta duration/dims via media path | both | appears in Bitz grid with poster |
| MST-035 | QA matrix: long-clip, call interruption, background, low storage, thermal, process-kill mid-export (CAP-009 reuse) | QA | no stranded in-flight states; recovery banner on relaunch |

### M4 — V1.x polish: studio home, sound, templates, remix

**M4 UI concept — Honeycomb mockups → waves.** Authoritative screens:
`docs/ui/app-15-studio-mass-production.html` (studio home, V2 suite, batch
setup, review, publish machine), publish states from
`docs/ui/app-05-publish-recovery.html`, batch domain rules from
`docs/product/studio-mass-production.md` (recipe immutable → edits fork a
new version; typed slots; approvals bind content+metadata hashes). M1–M3
already follow `scr-quick`; the waves below ship the remaining mockup
surface in reviewable increments:

| Wave | Tasks | Mockup contract | Deliverable |
| --- | --- | --- | --- |
| 1 | MST-040 | `scr-home` | Studio home (You-hub route): "Start something new" 3-tile grid — Bitz → camera route, Meme → quick editor, Batch → "Soon" chip until wave 5; continue-creating rows (≈11:14 poster, title, relative time + editor chip, **Resume**, ✕ with confirm); templates rail seeded from the first built-in pack — marketplace cards render the ⚡sats chip but stay locked until wave 3; batch-queue progress card only appears once wave 5 exists. |
| 2 | MST-041, 043, 044 | `scr-quick` tools + `scr-suite` tool chips | In-editor additions: SFX button → sound sheet (buckets funny/impact/system/money/transitions, cue list ≤16, cue markers on the scrub row, "cue 3/16 · id" readout); Looks picker (8 grades, unknown → none); per-overlay fx picker + start/end visibility windows on the GIF/video stage. |
| 3 | MST-045, 046, 047 | `scr-home` templates rail + APP-021 | Templates: detail/apply sheet (typed slot filling from a template), marketplace sheet (10 categories, price tiers {0,21,100,500}, zap-unlock via NIP-57 + local ledger, own templates always unlocked), publish-your-own (same kind-30078 flow). Sounds: shared-sound ingest (license + sha256 gate), local library ≤30, "Use sound" handoff from the APP-021 trending page. |
| 4 | MST-042 | `scr-pub` remix row | Remix: publish page gains the lineage chips row (`root · satoshi_v → v2 · llady → v3 · you`); RemixChainDialog lists every bounded ancestor; a would-cycle attempt surfaces as a review blocker — never a silent drop; license + attribution ride the advanced options. |
| 5 | MST-048, 049 | `scr-batch` + `scr-review` + `scr-pub` | Batch: setup sheet with typed slot rows (`name · short_text`, `sats · number`, `bg · color`, `img · asset`), CSV import + folder picker, validity chips (`96 valid / 3 warnings / 1 blocked`) with per-row reasons (overflow = warning, coercion = warned, unreadable asset = blocker); blocked rows are excluded automatically; header shows `recipe v3 🔒` — edits fork v4. Review contact sheet: 3-col variant grid, **stable row-id ordering** (never reshuffles mid-review), per-tile duration/warning/approval/render state, long-press multi-select, "Approve N…" confirms exact count + exclusions; variant edits create overrides and invalidate that variant's approvals. Publish runs "Sign & publish N approved" — signing is per event, never batched blindly. AI suggestions (MST-049) enter as an opt-in "Suggest" chip in the text/sound sheets; results are a reviewable list applied as ordinary commands. |

**Wave 5 progress (2026-09-02): mass production V1 SHIPPED (MST-048; MST-049
AI suggestions still pending).** Per product decision the flow lives INSIDE
each platform's Create hub — no separate mass screens/files. Shared core
(common-tested, `studio/MassBatch.kt` + `MassBatchRules.kt`, 12-test battery
+ contract fixtures `contracts/mass/batch-v1{,-hostile}.json` pinned verbatim
in tests): batch wire v1 (`MassBatchDocument`: recipe + typed slots
[short_text/long_text/number/color/image·video·audio_asset/timestamp/enum]
+ rows + per-variant render/approval/publish states, ≤200 rows/12 slots/
256 KB, lenient decode), mockup severity contract (missing-required +
unreadable asset = BLOCKER auto-excluded; "1k" coercion + text overflow +
invalid enum/color/timestamp = WARN reviewable), `{slot}`/`{i}` placeholder
resolution into variants (image_asset swaps the declared project asset),
grouped-number display, sanitized `{i}` naming, sha256 content+poster
approval binding (any edit invalidates silently), recipe fork-on-edit
(published history survives), stable per-event publish queue (skips
published/publishing; crash recovery resets orphaned PUBLISHING), bounded
RFC-4180 CSV import with typed header mapping. Android: integrated into
`CreateScreen.kt` (scr-home "Start something new" tile grid: Bitz/Meme/
Batch + embedded `MassBatchFiles` store at `filesDir/studio/mass/` +
`MassBatchUi` controller; every mutation persists; previews render
sequentially via `MemeRaster` → poster+hash; publish queue renders →
hash-verified Blossom upload → kind-20 per event through
`publishMemePicture(onResult:)`; setup/review/publish phases mirror
scr-batch/scr-review/scr-pub). iOS: same integration in `CreateView.swift`
via four bridge seams (`massBatchNew/Op/Plan/ImportCsv` — op-dispatcher
keeps the surface small; plan carries resolved projectJson + assetMap per
row for rendering) + `MassBatchFiles`/`MassBatchFlow` at Application
Support/studio/mass; publish reuses `BlossomUploader` +
`publishMemePictureNote` sequentially. V1 bounds honestly documented:
batch publishes are image memes (the master/row-asset pipeline), covers
and video/GIF batch modes ride later waves; Android store IO has no JVM
round-trip test (the shared codec carries that contract) — the file layer
is thin temp+rename writes. iOS Swift type-check still pending a simulator
runtime on the dev machine (same gate as M3c; syntax-parse + API-signature
audit done).

**Wave 2 progress (2026-09-02): LOOKS SHIPPED (MST-043).** Shared
`studio/MemeLooks.kt` — the 8 web grades (`look.ts` verbatim CSS chains:
none/mono/noir/sepia/vhs/deepfry/dream/invert) parsed into filter ops and
composed by a CSS-filter-spec math engine into ONE 4×5 color matrix
(row-major, Android `ColorMatrix` / iOS `CIColorMatrix` layout; chain order
left-to-right like CSS; unknown ids degrade to none; `blur` rides as data —
V1 rasterizers burn the matrix only, the 1.2 px dream softness is a
documented cosmetic gap). `lookId` rides the project wire (`"look"`,
written only when set — old wires byte-identical) and `SetLook` is an
undoable `MemeCommand` (`{"op":"look"}`). Android: `MemeRaster` burns the
matrix into the MEDIA before overlays (web `ctx.filter` parity — captions
never graded), the stage preview shows the same `ColorFilter` (WYSIWYG),
and a ✨ tool opens the 8-look sheet. iOS: bridge seams `memeLooks` +
`memeLookMatrix` (all three client layers), `MemeRaster.applyLook` via
CIColorMatrix in the export path, stage preview through a per-asset grade
cache, and the same ✨ sheet. ALSO: the scr-home **batch-queue bar** landed
on both Create hubs (newest batch's `variants · published · awaiting
approval` + progress fill, taps into the batch flow). Tests: `MemeLooksTest`
(reference pixels vs the CSS spec: grayscale luminance, sepia matrix,
invert math, chain order, wire round-trip, SetLook through the codec) +
bridge seam battery. Remaining in wave 2: MST-041 (SFX pack + sound seed)
and MST-044 (per-overlay fx transforms + timed windows on stage).

**Prototype `#/publishing` machine (2026-09-05, BOTH platforms):** the
publish flow's third screen runs the 8-stage stepper over REAL pipeline
checkpoints (uploader hashing→PUT→verify callbacks; note BUILT/SIGNED/
RELAYED; receipt-machine terminal result feeding confirm + accepted relay
hosts). Job chip, progress fraction, stalled-stage failure with Retry/
Later (idempotent), success with event id + confirm count + "View on
Bitz". iOS store: `publishStep`/`publishJobId`/`publishEventId`; Android
`MemePublishUiState`: stage/jobId/eventId/confirmedRelayHosts/terminal +
`markMemeRenderStarted()`. All instrumentation params default no-op.

**Prototype FULL create-flow parity (2026-09-05): editor reskin +
Post details + Preflight, BOTH platforms.** The editor screen now mirrors
`#/create-edit` — "Editor" header with draft save, mode pills + undo, quick
chips with INLINE panels, compact clip timeline (Split/Delete/Mute/Speed/
Layer on real clip ops), per-mode bottom bar, "Next · post details". The
publish path mirrors `#/create-details` (caption, ≤8 t-tag pills, cover/
audience/zap/remix rows, CW, remix source, license chips CC0/CC-BY/
Nostr-only riding the real `license` tag) and `#/create-review` (live
checklist + phase stepper over the existing render → upload → verify →
sign machine; done returns to the feed). Shared: video event seams grew an
additive `extraTagsJson` (TagsCodec-decoded, default empty = byte-identical
ids) so t-tags + license ride kind 21/22 too — picture paths already had
them (`MemePublishScreen` retired for `MemePostFlowScreen`/`MemePostFlowView`).
Zap-scope/splits/PoW/schedule remain wave-4 (MST-050s) and say so in-UI.

**Prototype `#/create-edit` parity pass (2026-09-05): ADJUST +
meme generator + canvas meta chips + labeled tools, BOTH platforms.**
Shared: `MemeAdjust` (bri/con/sat multipliers; prototype slider bounds
0.4–1.6/0.4–1.6/0–2, NaN→1; default ⇒ field null) composes ON TOP of a
look preset into the SAME one 4×5 matrix (`MemeLooks.adjustedMatrixFor` —
pixel order look → brightness → contrast → saturation), rides the wire as
the additive `"adjust":{"bri","con","sat"}` key (default never serializes),
and lands as the undoable `SetAdjust` command (`{"op":"adjust"}`) with
slider-burst coalescing in `MemeRules.coalesce`; bridge seam
`memeAdjustMatrix(lookId,bri,con,sat)` (defaults byte-equal
`memeLookMatrix`). `MemeAdjustTest` (8 tests) pins matrix goldens, wire
round-trip/hostile rows, codec, coalescing and the seam. Native: the ✨
Looks sheet gains the Adjust section (three labeled % sliders + reset;
video keeps it project-wide per prototype semantics); burn-in composes
through Android `MemeRaster`/media3 `RgbMatrix` + stage `ColorFilter`, iOS
`memeAdjustMatrix` through `MemeRaster`/`gradeClip`/`composeClips` +
grade-cached stage (single-clip video pass-through now composes when only
adjust is set); classic **meme generator** (prototype hot "Meme" tool):
TOP/BOTTOM sheet with Impact/Comic/Modern slot pills → positioned pair
(y 0.16/0.84, caps, size 64, outline 3) landing as ONE undo step
(`addMemeCaptions` both states); **canvas meta chips** (image
`WxH · ratio`, gif `N frames · delay`, video `mm:ss · N clips`) bottom-
leading on both stages; the quick tool row is now labeled (Meme first,
icon + caption, scrollable). Fixes riding along: iOS `ToolIconButton`
double `accessibilityLabel` (every tool read "Save to Photos") and the
Image mode chip staying lit in video mode; Android picker bounds now
retain pixel size (meta chip source). iOS seam/store battery:
`testMemeAdjustSeamAndEditingLoop`; Android: `MemeAdjustTest`-mirroring
`MemeEditorStateTest` cases (coalesced adjust + caption pair).

**Mockup UX-parity pass (2026-09-02, `app-15` scr-quick + `app-04`
scr-review):** both editors' selection chrome now matches the Honeycomb
reference — dashed accent bounds plus orange corner dots with a dark rim,
and the stage shows the top-left "drag · scale · rotate" chip whenever an
overlay is selected (Android `OverlayNode`/`StageHintChip`, iOS
`OverlayUiView`/`CornerDot`). The publish sheet gained the scr-review
preflight checklist (caption+tags ✓ with the kind-20/22-21 label, alt-text
row with "defaults to caption", CW row with gated/off) above Sign & publish;
the "nothing signs before hash-verify" footer already existed. Tray active
tile accent + caption counter/tags/CW were already at parity. iOS
type-check still gated on a simulator runtime (syntax-parse + API audit).

**Wave 2 progress (2026-09-02, cont.): SFX DATA + PREVIEW SHIPPED
(MST-041 core).** Shared `studio/SfxSynth.kt`: the 31 web recipes
(`sfx.ts`) ported verbatim as oscillator/note tables across the 5
Meme-Pack buckets, a deterministic pure-Kotlin PCM renderer with the exact
WebAudio envelope math (12 ms linear attack, exponential decay to −80 dB,
linear frequency ramps, master 0.5, clip-guarded), a 16-bit little-endian
PCM + WAV writer, and `MemeSfxCue` riding the project wire
(`"sfx"` ≤16, gain-clamped, unknown ids drop) with `AddSfxCue`/`RemoveSfxCue`
as undoable commands — `SfxSynthTest` (catalog/buckets verbatim spots,
determinism, envelope shape, WAV container, wire clamps, command caps).
Android: 🔊 tool in video mode → SFX sheet (bucket chips, tap = AudioTrack
preview from shared PCM, "＋ cue" schedules at the tracked playhead, cue
list with preview/delete) and accent cue markers on the video scrub row.
Bridge seams `memeSfxCatalog` + `memeSfxWavBase64` ready for the Swift
sheet. **MST-041 follow-up (same day): iOS SHEET + CUE BURN-IN SHIPPED.** The
iOS 🔊 tool opens the same sheet (buckets from `memeSfxCatalog`, previews
via `AVAudioPlayer` on `memeSfxWavBase64` WAV bytes, "＋ cue" at the
tracked playhead, cue list with preview/delete) with accent markers on the
scrub row. Export burn-in landed too: shared `renderCueTrack` mixes the
schedule over the export window (deterministic, common-tested; out-of-range
cues drop) and Android feeds the mixed WAV as a second Media3
`EditedMediaItemSequence` (Transformer mixes sequences — original clip
audio + SFX). **Still open in MST-041**: the iOS export audio track (WAV
as a second composition audio track — the seam data is ready), the
"sound-cued images export as video" slice, and the APP-021 trending
sound-seed handoff.

**Wave 2 progress (2026-09-02, cont.): FX RULES + STAGE APPLICATION
SHIPPED (MST-044 core).** Shared `studio/MemeFxRules.kt` — the web `fx.ts`
math verbatim (entry 380 ms: pop = easeOutBack overshoot, fade = linear;
loops 900 ms: shake = the exact sin·cos wave at dx ±0.008 / dy ≤0.004
canvas fractions, spin = 2π/loop) + half-open `[start,end)` visibility
windows; `UpdateOverlay` grew `fx`/`clearFx`/`startMs`/`endMs`/`clearEndMs`
(command + codec + coalescing; nonsense windows degrade to always-visible,
matching the wire parser). `MemeFxRulesTest` pins the entry math (pop
zero→overshoot→settle, fade ramp), loop periodicity/bounds, window edges
and the command round-trips. Both VIDEO stages apply it live: out-of-window
overlays hide and fx transforms track the playhead (Android `OverlayNode`
graphicsLayer, iOS `OverlayUiView` position/scale/rotation/opacity via a
`MemeFxBridge` display shim — NOTE: the Swift shim mirrors the shared math
and should become a `memeFxTransformAt` bridge seam next pass; the rules
tests stay the contract). **MST-044 follow-up (same day): FX PICKER SHIPPED (Android).** The text
sheet gains motion chips (Pop/Fade/Shake/Spin/None → `updateStyle(fx…,
clearFx)`) and, in video mode, Start/End second fields for the visibility
window (`clearEndMs` when end ≤ 0). **MST-044 close-out (same day): iOS PICKER SHIPPED — WAVE 2 COMPLETE at
its committed scope.** `MemeOverlayUi` carries `fx`/`startMs`/`endMs`
(parsed from the wire) and the iOS text sheet gains the same motion chips
(Pop/Fade/Shake/Spin/none via `clearFx`) plus Start/End second fields in
video mode (`clearEndMs` on end ≤ 0) — the fields-dict rides the extended
command codec unchanged. Remaining (tracked for V1.x): fx burn-in to
exported video (animated overlay layers — CAAnimation on iOS, animated
overlay on Media3), GIF-stage time application, and replacing the iOS
`MemeFxBridge` mirror with a `memeFxTransformAt` bridge seam.

Publish-machine UI contract (from `app-05`; binds every meme publish,
batch included): the stepper names ARE the pipeline — Checking project →
Rendering → Securing media hash → Uploading → Verifying media → Waiting
for signature → Publishing to relays → Done — mapping 1:1 onto
`StudioPublishContract` states. Percentages are real (frames/total,
bytes/total) and only one final render runs per device at a time. Upload
pause/retry resumes from the last verified chunk; a network drop pauses
cleanly. **A hash mismatch is a hard stop — never auto-retried**: show
expected/received hashes, "Open project — it's safe", and never destroy
usable work. Partial relay success is success; retry only the failed
relays, never re-publish accepted ones. An external-signer wait offers
"Open signer / Cancel wait" and the job stays queued. Kill/relaunch
reopens the queue at the exact durable stage; idempotency keys ensure
duplicate retries never duplicate uploads or signed events.

Composer-adjacent note (`app-04`, V1.x Bitz composer rows, not M4): the
camera's MEM button hands the finished take to this editor, and the
permission-denied state always offers "Import from library instead" —
capture both in the composer plan when it starts.

| ID | Task | Platform | Acceptance |
| --- | --- | --- | --- |
| MST-040 | Studio home: continue-creating slots w/ poster+label+delete (≤6, no TTL); templates grid seeded from a first built-in pack | both | EDT-001 slots ≤6, one-tap resume into exact state |
| *(2026-09-02 done)* | Templates rail SHIPPED: shared `MemeTemplates` (6-template built-in pack — Classic, Zap Receipt, Fee Market, Hold, Number Go Up, Bitcoin Sticker; apply = fresh-id overlay clone keeping mode/assets/grade; common-tested) + bridge seams `memeTemplates`/`memeApplyTemplate`; horizontal rail on both Create hubs taps straight into a seeded editor. Slots were MST-018; marketplace cards stay wave 3. | both | test green; both hubs compile |
| MST-041 | Sound seed: APP-021 "Use in Studio" hands `soundSeed` into a video project; `SfxRecipes` + cue model (§3.6); sound-cued images export as video | both | MEM-002/003 preview + attribution visible; cue-schedule golden green |
| MST-042 | Remix: full §3.3 wire — `remix`/`meme`/`p` tags, ≤700-char compact payload, depth 32 + cycle guard pre/post-sign, licenses; RemixChainDialog | both | MEM-005 provenance survives round-trip; web fixtures green |
| *(2026-09-02 done — publish wiring)* | Remix lineage SHIPPED into both publish sheets: an optional "Remix of" field (note1/event id) + author npub, a `source · … → you` chips row (scr-pub), and on publish the tags ride via the tested `memeRemixTagsFor` seam (compact ≤700 payload + ladder, `p` attribution) — Android passes `remixTagsJson` to both lanes (picture + video); the Kotlin picture composer seams grew a defaulted `extraTagsJson` (bridge test pins the id change + frame tags); iOS `publishMemePictureNote` carries `remixTagsJson` through both composer seams. Chain-dialog browsing + the target-picker "Remix" entry remain V1.x (feed-side rail already renders chains). | both | `memePictureSeamAcceptsRemixExtraTags` green; both apps compile |
| MST-043 | Looks: 8 grade ids (`lookId`), CSS-filter-equivalent per platform; unknown id → none | both | grade visibly applies to raster + video paths |
| MST-044 | Per-overlay fx + timed overlays: `MemeFxRules` + visibility windows on GIF/video stage | both | fx timing deterministic vs fixtures (EDT-006 slice) |
| MST-045 | Shared templates: kind-30078 v2 parse/apply/publish (§3.5); local templates ≤24; hostile-schema battery | both | MEM-004 read/publish; v1 events degrade to free templates |
| *(2026-09-03 — relay fetch live on BOTH platforms)* | Android + iOS `SharedTemplateStore` + hub "Shared templates" rails (verified kind-30078 frames → bridge summary, newest-wins ≤24, ⚡price chips) → seeded editors via the apply seams; iOS file registered in project.pbxproj. | both | suite green; compiles/parse |
| *(2026-09-02 — read path done)* | `MemeTemplateContract` + tests + seams `memeSharedTemplateSummary`/`memeApplySharedTemplate` shipped (hostile battery: foreign d-tag/schema/version, empty overlays, junk price→free, icon/category fallbacks, hostile overlay coords clamped via the wire parser, converter-crash guards per MEM-004). Remaining for MST-045: relay fetch → hub rail section, publish-your-own write path; MST-046 zap unlock ledger. | shared | battery green |
| MST-046 | Template marketplace: categories, price tiers {0,21,100,500}, zap-unlock ledger ≤500 | both/BE | unlock survives restart; own templates always unlocked |
| MST-047 | Shared sounds: kind-30078 ingest (license gate + sha256) + local library (≤30/8 MB/15 s) + trending rank (3-day half-life) | both | MEM-003; unlicensed or hash-mismatch rejected |
| MST-048 | Batch queue: multi-pick → per-item captions → sequential publish, crash-safe advance | both | kill mid-queue resumes at correct index |
| MST-049 | AI suggestions (MEM-006): local DSP beat/peak detection, Mild/Funny/Chaos, ≤6 overlays/cues, opt-in + reviewable + `["ai","bitz-suggested"]` provenance only on explicit confirm | both | no silent project mutation; provenance tag only when toggled |

V2 (full suite, EDT-005..009 / MEM-007..009): expert timeline + clip
windows, drawing pen + replay, caption-beat sync, value splits, story
destination (30315), zoom/speed editor UI, NIP-30 emoji packs / SVG /
mascot layers. *(2026-09-03 — first scr-suite slice shipped early:
video-mode source insert as IMAGE layers [image/GIF, ≤6, wire-local] +
the V2 suite dock shell [track lanes, playhead, tool chips, redo] on
both platforms; see tracker APP-019. The full timeline engine — clip
windows, zoom/speed tracks, draw, sound-seed — stays sequenced here.)*
*(2026-09-06 — stack management shipped: shared `ReorderOverlay` command
+ `reorder` wire op; the Layers sheet became the whole-stack manager on
both platforms — every overlay kind, top-first, ±1 moves, select/delete/
insert — and image mode got the Layers tool, so added images stack on top
uniformly with video inserts and any layer's paint position is editable
and undoable.)*

---

## 6. Web-parity constants (port bible)

| Constant | Value | Web source |
| --- | --- | --- |
| Wire schema id / version | `com.bitos.bitz.meme` / 1 | `schema.ts:19` |
| Overlays max / chars max | 12 / 300 | `schema.ts:23-27` |
| Overlay size (fraction of stage height) | 0.03–0.22, default 0.09 | `schema.ts:25-26` |
| Palette | 7 hex: `#ffffff #000000 #fde047 #f97316 #22d3ee #a3e635 #f472b6` | `schema.ts:32-40` |
| Caption cap | 1000 | `schema.ts:260` |
| sfxCues max / lanes / gain | 16 / 0–3 / 0–1 (default 1) | `schema.ts:224` |
| SFX recipes / master gain / rate | 31 / 0.5 / 44.1 kHz stereo | `sfx.ts` |
| Overlay fx entry / loop | 380 ms / 900 ms | `fx.ts:40-42` |
| Frame fx vocabulary / windows | 10 ids / ≤16 × ≤4000 ms, intensity 0.05–1 (def 0.7) | `fx-track.ts` |
| Zoom windows | ≤16 × ≤4000 ms, factor 1.001–4, ease 140 ms | `zoom-track.ts` |
| Speed windows | ≤16 × ≤4000 ms, rate 0.5–2 | `speed-track.ts` |
| Image layers | ≤6, ≤8 MB, height 0.05–0.9, opacity 0.05–1, rot ±180° | `image-overlay.ts` |
| Drawing | ≤16 groups × 100 strokes × 1500 pts (12 000/project) | `drawing.ts` |
| Stickers | 6 packs × 8, recents ≤16, default size 0.18 | `stickers.ts` |
| GIF export | ≤360 frames, 12 fps fallback, 256 colors, delay floor 20 ms | `gif-export.ts`, `gif-encode.ts` |
| Video export | 30 fps, 6 Mbps, long edge 1080 | `render.ts`, `export-support.ts` |
| Source URL import | ≤200 MB, type-gated | `source-fetch.ts` |
| Remix payload / depth / relays / attribution | ≤700 chars / 32 / ≤3 / ≤140 chars | `remix.ts` |
| Template price tiers / cap | {0, 21, 100, 500} / 1 000 000 junk guard | `template-marketplace.ts` |
| Sounds (shared/library) | ≤15 s, licenses CC0/CC-BY/CC-BY-NC, sha256 verify; library ≤30 × ≤8 MB | `shared-sounds.ts` |
| Trending half-life | 3 days over kind 20/21/22 `meme` tags | `trending.ts` |
| Draft / slots / templates / unlocks | 3.5 MB · ≤6 slots (thumb ≤192 KB) · ≤24 · ≤500 | `meme-drafts.ts`, `meme-slots.svelte.ts` |
| AI suggestions | ≤6 overlays/cues, ≤3 smart templates, opt-in only | `lib/ai/suggest.ts` |

---

## 7. Test & delivery gates (repo rules)

- **Protocol changes need fixtures**: kind-20 picture-meme + `image/gif`
  fixture, kind-22 video fixture, `com.bitos.bitz.meme` v1 payload fixtures
  (ported from web `schema.test.ts` incl. hostile clamps), remix
  `meme`-tag fixtures (from web `remix.test.ts`, incl. degradation ladder +
  cycle cases), template v2 + shared-sound fixtures — under `contracts/`.
- **Shared business changes need common tests**: codec/rules/contract
  batteries; wire ⇄ local converters get a cross-model round-trip battery
  (MST-019).
- **Media changes need deterministic/golden or timing tests**: per-platform
  raster goldens, GIF frame/delay assertions, video export duration/track
  smoke, SFX cue-schedule golden.
- Every background job (render, export, upload, autosave) is durable,
  idempotent, cancellable and recoverable — kill/relaunch must never strand
  state.
- Docs updated in the same change: this plan's milestone checkboxes +
  tracker `APP-019` checklist + delivery-plan EDT/MEM rows touched per PR.
- **Fixture drift guard**: when web `schema.ts` / `remix.ts` change, port
  the new fixtures in the same native PR that consumes them (tracked via
  MEM-001).

## 8. Risks & open decisions

| Risk / decision | Mitigation |
| --- | --- |
| **Dual-schema drift** (local store vs web wire document) | Single converter module in shared core + cross-model round-trip battery + web fixtures (MST-019); local store never parses the wire directly. |
| Overlay burn-in parity between Media3 and AVFoundation (font metrics differ) | V1 accepts per-platform text raster differences; goldens are per-platform. Geometry is canvas-normalized so cross-platform project files stay loadable. |
| Palette semantics (16-color local vs 7-hex web) | Nearest-match converter now; decision to converge on the web 7-hex set as indices 0–6 recommended before M4 exports ship (§3.2). |
| GIF encoder on Android (no platform encoder) | Vendored pure frame-sequence encoder (port of web `gif-encode.ts`, no remote code); size ladder keeps outputs bounded. |
| Impact font licensing | Ship 4 semantic slots mapped to licensed/platform fonts; visual "impact" slot uses the boldest available system condensed face. Swap-in of a bundled font later touches only the slot mapping. |
| SFX synthesis parity (WebAudio vs AVAudioEngine/AAudio) | Recipes are data; the contract is the cue schedule + gain envelope, asserted by timing goldens — timbre may differ. |
| Publish-kind mismatch with older plan text (image → kind 1) | Resolved to web parity: pictures → kind 20 (`nostr-infrastructure.md` already commits to kind-20 writes); kind 1 remains an explicit Note destination. Spec §3.19 line updated in the same doc change. |
| Large exports on low-end devices | Resolution ladder + bounded frames (≤360) ; export on background dispatcher with progress + cancel; preflight storage check. |
| Unlisted visibility | V1 ships Public + "Save to device"; true unlisted (no relay broadcast) only if product confirms. |

## 9. What ships when (summary)

M0 hub/schema foundations (shipped) → M1 image editor end-to-end (the meme
studio core: import → caption → publish as kind 20, plus the web wire codec
and fixtures) → M2 GIF → M3 video with cover frames and orientation-based
kind 21/22 → M4 studio home, synth sound pack, templates/marketplace,
remix lineage, looks/fx, batch, opt-in AI. Each milestone is independently
shippable and leaves the app in a consistent state; M1's MST-019 is the
gate that keeps native projects and web payloads interoperable from the
first publish onward.
