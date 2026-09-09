# Meme Studio — Blank canvas for GIF/VIDEO + video→GIF export

Analysis + feature spec + task breakdown. Continues `MST-xxx` numbering
(meme-studio-plan.md / meme-studio-multiclip-plan.md; last used MST-062+
on the iOS-parity wave). Status: **COMPLETE (2026-09-08, both platforms; MST-070..087 all shipped).** Wave 1 (MST-070/071/072/073): blank VIDEO end-to-end. Wave 2 (MST-074/075/076): sound/layers QA + resume contract. Wave 3 (MST-077/078/079/080): kinetic GIF (timed-plan exports, blank GIF sessions, wire timing). Wave 4 (MST-081/082/083): video→GIF export (Format row; raw-window sampling feeds the timed-plan GIF encoder; ≤10 fps/first 10 s/8 MB ladder; durable `gif` job; publish stays MP4 — MST-084 declined for V1). Wave 5 (MST-085/086/087): QA matrix S7 in `qa-manual-checklist.md` (blank×3 modes × MP4/GIF/PNG × resume × publish + device perf gates); derived GIF timing single-sourced in shared `MemeCanvas` with common tests (`derivedGifTimingClampsIdentically`); doc cross-links swept. Deviations: Extend lives in the Canvas sheet; canvas spec is session-only prefill; iOS sheet chain split for type-check linearity; iOS mirrors the derived timing math as literals (values common-tested via the Android/shared side). Image-mode blank canvas, the selection rail, GIF browsing and the
image-stack export fix are already shipped (see `native-ui-build-tracker.md`
2026-09-08 entries) and are the foundation this plan builds on.

## 1. User stories

| # | Story | Modes |
|----|-------|-------|
| U1 | "I want to make a meme from NOTHING — no photo. Just a colored canvas, my text and stickers." | IMAGE ✅ shipped · GIF · VIDEO |
| U2 | "I want a blank VIDEO: pick a duration, add text/stickers/images/GIF-layers that animate, add sound (SFX), export MP4." | VIDEO |
| U3 | "I want a blank GIF: a short kinetic loop made of animated text/stickers over a canvas." | GIF |
| U4 | "I have a video meme (blank or not) and want to EXPORT IT AS A GIF" for platforms that don't take video. | VIDEO → GIF |
| U5 | "My blank draft survives kill/relaunch like every other draft." | all |

## 2. Current-state analysis

### What already works (reuse inventory)

| Capability | Where | Blank-video ready? |
|---|---|---|
| Canvas ratio/bg rules (additive wire `canvas` object) | shared `MemeCanvas` | ✅ wire exists; VIDEO never writes it |
| Blank IMAGE stage + PNG render/export/publish/variations | `renderBlank` / `renderBlankPngData` | ✅ shipped 2026-09-08 |
| Per-overlay motion fx + visibility windows | shared `MemeFxRules.transformAt(overlay, atMs)` (pop/fade/wave/spin) | ✅ video stage + video export burn it; **GIF exporter draws IDENTITY** |
| Animated GIF layers (reels) | `gifReels` decode + per-frame burn in video export; stage `gifFrameAt` | ✅ works wherever a clip timeline exists |
| SFX cue timeline (synth buckets: funny/impact/system/money/transitions) | `addSfxCue(sfx, atMs)` + `sfxMixTimeline(project, durationMs)` mixed into the MP4 export | ✅ needs a duration source; chip already appears when `videoMode && hasVideo` |
| Multi-clip timeline (split/trim/reorder/volume/look/speed) | M5 wave, both platforms | ✅ if the blank source is A REAL CLIP, everything inherits |
| GIF size ladder (≤3 halving steps, 8 MB cap) | shared `GifExportPlan` + `MemeGifExport` / `MemeGifExportIos` | ✅ |
| Draft slots (assets copy-in incl. `mem:` clip bytes, wire, poster) | `MemeProjectStore` | ✅ blank-video clip rides `mem:` like camera takes |
| Mode switch confirm, undo, coalescing | `MemeEditorState` / store | ✅ |

### What is missing (the gap list)

| Gap | Impact |
|---|---|
| **G1** No creation entry for blank GIF/VIDEO (empty state only offers pick/browse) | U2, U3 blocked |
| **G2** No blank-timeline source: VIDEO requires ≥1 probed clip | U2 blocked — the core invention |
| **G3** No duration model for a blank timeline (video duration = clip windows; GIF duration = frames) | U2, U3 |
| **G4** GIF exporter ignores per-frame fx (`renderFrameRgba`/`encode` paint IDENTITY) | U3 (and any future animated-GIF output) |
| **G5** GIF stage preview is a static frame — no fx clock | U3 preview honesty |
| **G6** No video→GIF conversion path or export-format choice | U4 |
| **G7** Canvas chip hidden in VIDEO mode; bg/ratio re-style for blank video undefined | U2 polish |
| **G8** Blank-GIF duration/fps have no wire representation → resume loses them | U5 |
| **G9** Publish path assumes GIF ⇒ gif-mode frames; video→GIF export is save-only in V1 | U4 scope line |

## 3. UX/UI design

### 3.1 Consistent creation entry (all modes)

Empty state grows one shared row (matches the IMAGE blank CTA shipped
2026-09-08):

```
┌─────────────────────────────┐
│      [ Pick …  CTA ]        │   existing pick affordance
│   [ Start blank canvas ]    │   NEW — all three modes
│   [ Browse GIFs ]           │   GIF mode only (shipped)
└─────────────────────────────┘
```

Mental model everywhere: **"the canvas is the media."** A blank session
is not a special editor — same stage, same rail, same tools; only the
background source differs.

### 3.2 The "New blank" sheet (shared component, mode-aware)

| Control | VIDEO | GIF |
|---|---|---|
| Ratio chips | 1:1 · 4:5 · 9:16 · 16:9 (no "Source") | same |
| Background swatches | the CanvasSheet palette (incl. custom hex) | same |
| Duration chips | 3 s · 5 s · 10 s (default 5 s; ladder caps apply) | 1 s · 2 s · 3 s (default 2 s) |
| Frame rate | n/a (clip is 30 fps) | 10 fps · 15 fps (default 10; ≤ 60-frame cap honored: 3 s × 15 fps clamps to 45) |
| Create button | "Create blank video" | "Create blank GIF" |

Feedback on create: the stage immediately shows the colored canvas; a
one-time coach chip ("Add text, stickers, layers or sound — the canvas
is your clip") replaces the empty CTA for ~4 s.

### 3.3 In-editor affordances on a blank session

- **VIDEO**: the timeline strip shows ONE clip labeled `canvas` (chip
  color distinct). Everything existing keeps working because it IS a
  clip: Trim, Split, Speed, per-clip Volume, looks. New rows:
  - Clip sheet gains **"Extend canvas"** (3/5/10 s) when only the blank
    clip remains — regenerates the source longer; overlays/cues keep
    their timeline positions (nothing re-times).
  - **Canvas chip appears in VIDEO mode** while the timeline is
    all-blank: bg re-tint regenerates the source (cheap: no content to
    lose); ratio changes require confirmation (re-frame).
  - **Sound** keeps its existing gating (`videoMode && hasVideo`) — a
    blank video satisfies it; the SFX sheet already mixes cues into the
    export. V1 sound = synth buckets (no user audio import; see §6).
- **GIF**: frame tray is hidden on a blank session (there are no picked
  frames); the per-mode bar's Speed chip becomes **Duration** (reopens
  the duration part of the sheet; re-times the loop, overlay windows
  scale proportionally with a confirm). Stage animates overlays with the
  same fx transforms the export burns (G5 fix) so the preview is honest.

### 3.4 Export sheet — format row (U4)

VIDEO mode gains a FORMAT block above the preset block:

```
Format   (•) MP4 — full quality, sound included
         ( ) GIF — looping, silent, ~10–15 fps, auto-downscaled to fit 8 MB
```

- GIF choice reveals the sampling facts line ("~5.0 s · 12 fps · ≤ 8 MB
  — auto") computed from the shared planner; no manual knobs in V1.
- Export button label switches to "Export GIF"; the durable export job
  records `format = "gif"` so Retry reuses the artifact exactly like
  PNG/MP4/GIF jobs today (MUX-05 semantics preserved).
- Publishing stays MP4 in V1 (G9); the GIF is a save-to-device artifact.

### 3.5 Honesty & guardrails (existing principles carried over)

- Caps surface as notices, never silent degradation: frame cap (60),
  GIF 8 MB ladder (existing "Saved at a smaller size" outcome), 64 MB
  publish estimate unchanged.
- Blank session with zero content: Export/Next buttons stay disabled
  exactly like an empty pick session (no "export a white rectangle"
  surprises) — a blank counts as media only when ≥1 overlay, stroke or
  cue exists.
- A11y: every new chip/button gets a label; the creation sheet is a
  standard ModalBottomSheet with detents, like every other tool sheet.

## 4. Architecture decisions

### D1 — Blank VIDEO = a synthesized solid-color MP4 clip

Generate one small H.264 MP4 (canvas bg color, chosen ratio/duration,
30 fps, no audio track) **once at creation** via MediaCodec+MediaMuxer
(Android) / AVAssetWriter (iOS), write to cache, `probe()` it, and feed
it through `appendClip()` like a camera take. Consequences:

- 100% of the M5 machinery works unmodified: timeline UI, trim/split/
  reorder/speed/volume/look, stage preview, export/publish/cover,
  slots autosave (`mem:` bytes), undo-able clip edits.
- SFX mixing works because the export pipeline already mixes
  `sfxMixTimeline` into the composed MP4.
- GIF layers animate on it (`gifReels`) — a blank video + GIF layers is
  already a mini "GIF with sound" if the user wants it.
- Extend/bg-re-tint = regenerate the file (deterministic, same path).
- The synth engine lives in `native/media-core`-adjacent editor code
  (same seam as `MemeVideoExport`), NOT in shared business-core — it is
  platform rendering, not a product rule.

### D2 — Blank GIF = generated frames, no stored frame assets

A blank GIF session pins `canvas` (ratio+bg) and adds a duration/fps
pair; frames are synthesized at preview/export time (solid canvas →
overlays with per-frame `MemeFxRules.transformAt`). Nothing enters
`gifFrames`, so the frame tray, reorder and delay controls stay
picked-GIF features. Resume infers blankness from `mode == GIF &&
canvasRatio != null && gifFrames.isEmpty`.

### D3 — Wire: additive `canvas.sec` (G8)

`{"canvas":{"ratio":…,"bg":…,"sec":…}}` — `sec` written only for blank
GIF (and tolerated on others), clamped by shared rules
(`BLANK_GIF_MIN_MS..MAX_MS`, fps 10/15). Old readers ignore it; new
readers default absent → picked-GIF behavior. Shared rule + fixture
tests required (repo rule: protocol changes need fixtures).

### D4 — Video→GIF = sample the composed timeline

Do NOT re-encode-then-transcode the published MP4. Reuse the video
compositor: render frames at the ladder canvas for t = 0…duration at
the planned fps (uniform sampling per `GifExportPlan`), feed the
existing GIF encoder. Deterministic, honors overlays/layers/fx/looks,
and the 8 MB ladder owns downsizing. Android: MediaCodec decode of the
in-memory composed output OR compositor frame callbacks (prefer the
latter — zero extra pass); iOS: `AVAssetImageGenerator` against the
exported composition (bounded by the same planner).

## 5. Task breakdown (waves, step-by-step)

Wave order is dependency order; each wave ships both platforms + tests
+ tracker entry. IDs continue the MST space.

### Wave 1 — Blank VIDEO foundation (U2 core)

| ID | Task | Platform | Notes / acceptance |
|---|---|---|---|
| MST-070 | Blank-clip synthesizer: `BlankClipSource.create(ratio, bgHex, ms): File` | A+iOS | MediaCodec/Muxer & AVAssetWriter; deterministic, probe-able, ≤ a few hundred KB; golden test (byte-stable given inputs) |
| MST-071 | "New blank" sheet component (ratio · bg · duration) + empty-state wiring for VIDEO | A+iOS | Reuses CanvasSheet palette; Create → synth → `appendClip(undoable=false)`; coach chip |
| MST-072 | Blank-session detection + gating parity | A+iOS | `blankVideoActive` (single `canvas`-labeled clip); Export/Next enable rules per §3.5; timeline chip styling |
| MST-073 | Extend canvas + bg re-tint + Canvas chip in video mode | A+iOS | Regenerate source; overlays/cues keep positions; confirm on ratio change |

### Wave 2 — Sound + layers QA on blank video (U2 completeness)

| ID | Task | Platform | Notes / acceptance |
|---|---|---|---|
| MST-074 | SFX on blank video: verify + fix cue-at-playhead defaults, scrub markers, export burn | A+iOS | Contract test: blank clip + 2 cues → mixed MP4 has 2 audible regions (timing test) |
| MST-075 | Image + animated-GIF layers on blank video: stage/export parity pass | A+iOS | Reel burn per frame at playhead; layers listing; hit-test |
| MST-076 | Blank-video slots: autosave/resume incl. `mem:` clip + canvas facts | A+iOS | Kill/relaunch restores timeline + canvas label; contract test |

### Wave 3 — Kinetic GIF (U3)

| ID | Task | Platform | Notes / acceptance |
|---|---|---|---|
| MST-077 | GIF exporter fx-per-frame: `renderFrameRgba`/`encode` evaluate `MemeFxRules.transformAt(overlay, atMs)` | A+iOS | Golden frames; picked GIFs unaffected (ID entity when no fx) |
| MST-078 | Blank GIF session: creation sheet (ratio · bg · sec · fps), generated preview loop (stage clock), export | A+iOS | ≤60-frame clamp notice; WYSIWYG stage ⇄ export |
| MST-079 | Wire `canvas.sec` + shared clamps + fixtures | shared | Common tests both modes; old-wire compatibility fixtures |
| MST-080 | Blank GIF resume + Duration chip (re-time with confirm) | A+iOS | Windows scale proportionally; undoable |

### Wave 4 — Video → GIF export (U4)

| ID | Task | Platform | Notes / acceptance |
|---|---|---|---|
| MST-081 | Compositor frame sampler → existing GIF ladder | A | Frame-callback path; deterministic fps/duration honoring speed+trims |
| MST-082 | iOS sampler (`AVAssetImageGenerator` on the composition) | iOS | Same planner facts |
| MST-083 | Export-sheet FORMAT row + durable `gif` job from video mode + outcome copy | A+iOS | Retry reuses artifact; "Saved at a smaller size" ladder outcome reused |
| MST-084 | (stretch) Publish video meme as kind-20 GIF | A+iOS | Only if product wants it; otherwise documented save-only |

### Wave 5 — Hardening

| ID | Task | Notes |
|---|---|---|
| MST-085 | Full QA checklist pass (docs/product/qa-manual-checklist.md): blank×3 modes × export×3 formats × resume × publish | Update checklist |
| MST-086 | Perf: synth ≤ 300 ms, GIF sampling bounded by planner; memory ceilings on 60-frame sessions | Timing tests (repo rule: media changes need deterministic/timing tests) |
| MST-087 | Docs sweep: this file, meme-studio-plan.md cross-links, tracker entries per wave | Repo rule |

## 6. Non-goals / future

- **User audio import** (music/voiceover files) — licensing + NIP
  surface; SFX synth buckets remain V1 sound. Tracked separately.
- **Blank video longer than the cut ladder** — 10 s max chips keep the
  synth file trivial; longer durations via Extend repeat generation.
- **GIF export with sound** — impossible by format; the sheet says so.
- **Ratio re-frame for non-blank video** — stays out (V1 rule from
  `MemeCanvas`: re-frame = re-encode).

## 7. Verification strategy (repo rules)

- Shared changes (MST-079): common tests + fixtures, both natives'
  bridge seams covered by adapter-contract tests.
- Media changes (MST-070/077/081/082): golden/timing tests — synth
  determinism, fx golden frames, sampler fps accuracy.
- Every export job stays durable/idempotent/cancellable (MUX-05 rules)
  — the new `gif`-from-video job rides the same `MemeExportJobStore`.
- UI stays native per platform; no cross-core imports (BusinessCore ⇄
  MediaCore boundary respected; synth + samplers are platform media
  code behind the editor's existing seams).
