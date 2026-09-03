# Meme Studio M5 — Multi-clip timeline, layered sources & take-native camera handoff

Analysis + feature spec + task breakdown for the next video-editor wave.
Continues the `MST-xxx` numbering from `meme-studio-plan.md` (last used
MST-049). Status: **ANDROID SESSION-SCOPE V1 + WIRE PERSISTENCE + PER-CLIP
AUDIO SHIPPED (2026-09-03)** — multi-clip timeline model + layered dock
tracks (`vdo N` / `image N` lanes), per-clip trim/remove/reorder, picker
clip insert, playlist stage preview with a timeline clock (playhead
synced to clipping-window-relative positions), multi-clip Transformer
export with the whole-project look burned per clip, take-native camera
handoff (TakeMerger removed from the CAP→MEM path), MST-050/051 core
(`MemeClip` + additive wire v2 incl. per-clip `vol`) with multi-clip
autosave/resume, and the suite dock's dead "Sound · soon" chip replaced
by a working per-clip Volume/mute sheet (mute exact in export; fractional
gain preview-only until media3 exposes a public audio-gain hook).
**Per-clip look (grade overrides per clip, "none" inherits the project
grade) and Split-at-playhead (≥200 ms sides, unique minted clip ids)
also shipped. **MST-052 bridge seams shipped: `memeTimelineSyncClips` +
`memeTimelineDurationMs` (bounded clip sync + shared duration math,
common-tested incl. hostile rows and guards).** **iOS parity (MST-062)
shipped 2026-09-03**: `EditorClip` timeline model in `MemeEditorStore`
(wire sync through the bridge seams, multi-clip autosave/resume),
composition-based stage (windows + speed scaling + per-clip volume mix),
layered suite tracks (`vdo N` / `image N` lanes) + Clips/Volume/Trim
sheets + Split chip, take-native camera handoff (merge removed), and
composition-then-burn export/publish with the tail-clip size ladder.
Verification: `BusinessCore.xcframework` regenerated + all app sources
typecheck clean under Swift 6 strict concurrency; full simulator build
blocked on this machine (no iOS simulator runtime installed — disk
space). The working tree's uncommitted `CreateView.swift` /
`MemeVideoExportIos.swift` WIP compile errors (pre-existing this wave)
were also repaired — the whole app source now typechecks clean. **Close-out
follow-up shipped the same day: per-clip look burn on iOS** — each
graded clip renders through the shared 4×5 matrix (`memeLookMatrix`
seam → `CIColorMatrix`) before timeline composition; a single clip with
an active look routes through the composer too (this is the first
project-look burn for iOS video exports at all). **Android stage grade
preview shipped too** — the whole-project look rides the video stage as
a hardware-layer color filter (same shared matrix; per-clip overrides
stay export-exact). **Multi-take handoff fix:** the camera handoff now
probes all takes off-thread and appends them in one frame, and the
stage re-attaches the player via `AndroidView.update` — previously a
multi-take entry left a RELEASED player on the surface (video never
rendered). **Progress UX (2026-09-03):** clip imports (camera-take
handoff + picker insert) show a full-screen determinate "Preparing
clips… X of Y" overlay while sources probe/stage; the timeline ruler
was re-anchored to the lane start (label column excluded) so the
playhead, segments, cue ticks, tap-seek and the 00:00 readout all share
one zero point; multi-clip timelines repeat end-to-end
(`REPEAT_MODE_ALL` — `REPEAT_MODE_ONE` never advanced past clip 1); the
audio lane segments per clip (muted = gap); and mass production gained
determinate progress on both machines (render % + count on preview
generation, overall processed/percent + per-event note on the publish
queue).

## 1. Problem analysis (what exists today and why it blocks the ask)

### 1.1 Camera takes are merged away before the editor ever sees them
`CameraScreen` records takes 1..N into a session strip
(`MAX_PENDING_TAKES`). On "open in Studio", `openMemeWithAllTakes()`
calls `TakeMerger.merge()` (Media3 Transformer concat) and hands the
editor **one merged ByteArray**. Consequences:

- Per-take trim, speed, look, SFX timing and overlay windows are
  impossible — everything lands on the merged blob.
- The merge itself is a loss point: a failed merge silently degrades to
  the newest take only ("Could not combine takes…"), and a merged result
  over `MemeVideoExport.MAX_SOURCE_BYTES` refuses the handoff outright.
- A re-encode happens before editing (quality loss + time) even when the
  user only wanted take 2 trimmed.

### 1.2 The editor's video model is single-clip by construction
- `MemeProject` (shared wire) has **whole-project** `trimStartMs` /
  `trimEndMs` / `speed` / `lookId`; `maxAssets(VIDEO) = 1 + 6` where the
  clip is always `assets[0]` with id `v1`.
- The Android session holds one `videoBytes`/`videoProbe` pair; the
  timeline dock paints one full-width "video" lane.
- Trim/speed/look/split act on the whole clip; there is no clip list, no
  reorder, no per-clip anything, no insert-between-clips.

### 1.3 Layer insert is image-only and GIFs freeze
The V2 layer picker accepts images only; a picked GIF paints its **first
frame** (V1 semantics, disclosed in the Layers sheet). Video insert is
not supported at all. "Layer add via picker" therefore means three
different things that all need first-class handling: IMAGE overlay
(exists), **animated GIF** (preview + export animate), and **video
insert** (becomes a timeline clip, not an overlay).

### 1.4 What must NOT change (repo invariants that shape the design)
- Nostr events and hashes stay canonical; the export bytes are still
  rendered once, then uploaded and hash-verified before any signing.
- Business rules live in `shared/business-core`, media pipelines in the
  native apps; neither core imports the other.
- Wire schemas stay versioned and size-bounded; every persisted job
  (autosave, export) stays idempotent/recoverable.

## 2. Target system — feature list (what "done" means)

**F1 — Clip timeline model.** A VIDEO project owns an ordered clip list
(`clips[]`), each clip = `{id, assetRef, inMs, outMs, speed, lookId,
volume, muted}`. Caps: `MAX_CLIPS = 8`, per-clip window ≥ 200 ms, total
timeline ≤ 120 s, source set ≤ `MAX_SOURCE_BYTES` summed. Split = one
clip becoming two windows over the same source. Reorder/duplicate/delete
are list ops.

**F2 — Take-native camera handoff (merge removed).** "Edit in Studio"
passes the take list; each take becomes one clip (pre-trimmed to the
first-60-s window by the existing cut rules, flagged in the timeline).
`TakeMerger` leaves the CAP→MEM happy path entirely — retained only as a
"merge into one clip" *explicit* action inside the editor if wanted at
all (default: not offered in V1).

**F3 — Picker-layer expansion.** The insert picker accepts image / GIF /
video: image → IMAGE overlay (as today); GIF → animated overlay (preview
loops source delays; export composites frames); video → new timeline
clip at the playhead position. Format probing and honest fallbacks (e.g.
codec the exporter can't cut) surface as inline status, never dead taps.

**F4 — Track model.** Timeline lanes: **clips** (N segments), **overlays
** (visibility windows, unchanged), **audio** (per-clip original audio
with volume/mute; future sound seed), **SFX** (global cue ticks,
unchanged). One global playhead; total duration = Σ(window ÷ speed).

**F5 — Export pipeline.** Render = per-clip transcode (window + speed +
look) → concat → overlay/fx/drawing burn on the global timeline → audio
mix (clip audio + SFX). Deterministic, single output artifact, then the
unchanged verify-upload-sign publish machine.

**F6 — Wire v2 + migration.** `clips[]` replaces the whole-project
trim/speed/look for VIDEO; IMAGE/GIF modes unchanged. Decoder accepts v1
(single-`v1` projects migrate to a one-clip list, whole-project fields
collapse into that clip) and v2; encoder writes v2 only for multi-clip,
v1-compatible output when the project is single-clip unchanged (keeps
old readers working). Fixtures for both directions.

**F7 — Session & autosave.** Clip sources persist as `v1..vN` slot
assets; autosave stores the v2 wire; resume restores the full clip list
(posters per clip for the timeline). LRU slot eviction unchanged.

**F8 — Timeline UX.** Suite dock: clip lane shows real segment widths,
tap-to-select, drag-to-reorder, "+" insert between clips, split marker
at the playhead; a per-clip sheet (Trim · Speed · Look · Volume ·
Duplicate · Delete). The basic editor keeps the single-clip mental model
when there IS only one clip — M5 must not complicate the 1-take path.

## 3. Placement (architecture rules)

| Concern | Home |
| --- | --- |
| Clip list validation, caps, duration math, split/reorder rules, wire v2 + migration, fixtures | `shared/business-core` `studio/MemeTimelineRules.kt` + `MemeProject.kt` (pure, common-tested) |
| Bridge seams (`memeTimeline*`) + versioned contract | `BusinessCoreBridge` + native adapter-contract tests |
| Transcode/concat/mix, stage playback (playlist), GIF frame animation | Native per platform (Media3 / AVFoundation); no shared UI |
| Timeline UI, clip sheet, picker expansion, autosave plumbing | Native screens |

## 4. Task breakdown (implementation order)

Wave M5-A — shared foundation (no UI)
1. **MST-050** `MemeClip` model + `MemeTimelineRules` (caps, window/speed
   math, split-at-ms, reorder validity, duplicate id minting). Common
   tests: caps, degenerate windows, reorder identity, split boundary.
2. **MST-051** Wire v2 codec + v1⇄v2 migration in `MemeProjectContract`
   (+ `kind-22-unsigned.json`-style fixtures: v1 read → clip list,
   v2 round-trip, hostile clip fields clamped). Repo rule: protocol
   change ships fixtures in the same change.
3. **MST-052** Bridge seams (`memeTimelineValidate`, `memeTimelineWire`)
   + Android `BusinessCoreClient` / iOS `BusinessCoreClient` adapter
   contract tests.

Wave M5-B — camera & session (Android first)
4. **MST-053** Camera handoff rework: `onOpenMeme` takes
   `List<Pair<ByteArray, mime>>`; strip copy; per-take probe + first-60-s
   cut flag; delete `TakeMerger` from the happy path. Tests: handoff
   preserves order; over-cap take refused with reason.
5. **MST-054** Editor session: `clips` list replaces `videoBytes` scalar
   (single-clip projects compile to the exact old behavior); stage
   playback becomes an ExoPlayer playlist of clip windows; probe cache
   per source. iOS: `AVComposition` equivalent.
6. **MST-055** Autosave/slots multi-source (`v1..vN` asset refs, per-clip
   posters) + resume. Tests: kill/resume mid-edit restores clip list.

Wave M5-C — timeline UX
7. **MST-056** Suite dock clip lane: segments from `clips[]`, widths =
   window share, playhead over concatenated time; tap-select; drag
   reorder (with validity feedback); insert "+" between clips.
8. **MST-057** Clip sheet: per-clip Trim / Speed / Look / Volume+Mute /
   Duplicate / Delete as undoable commands through `MemeEditorState`
   (command pattern already there). Split-at-playhead on the selected
   clip.
9. **MST-058** Picker expansion (F3): video insert → clip at playhead;
   animated GIF overlay preview + export path (frame schedule reuses
   `GifFrameSource` decode ladder); honest fallbacks for unsupported
   codecs.

Wave M5-D — export & publish
10. **MST-059** Export pipeline F5: per-clip Transformer passes → concat
    → overlay burn → audio mix; progress phases surfaced in the
    full-screen export experience; idempotent temp-file hygiene.
11. **MST-060** Multi-clip size ladder: `MemeVideoCutRules` extension —
    over-cap exports trim the **tail clip's window** first, then drop
    tail clips (user-visible reason each step); publish duration =
    Σ(window ÷ speed); cover capture across the concatenated timeline.
12. **MST-061** Publish page preflight shows clip count + per-clip
    warnings (muted, cut, look-applied) so what-you-review =
    what-signs.

Wave M5-E — parity & closeout
13. **MST-062** iOS parity for every M5-B/C/D surface (same seams, same
    fixtures; SwiftUI timeline UI matching the Android one).
14. **MST-063** Docs: update `meme-studio-plan.md` index, this file's
    status, `app-unified-feature-spec.md` video-mode section, tracker;
    perf pass on the stage playlist (no per-frame allocation) + golden
    export tests per platform.

## 5. Acceptance gates (summary)

- Record takes 1,2,3 → Studio shows **three clips**, no merge re-encode;
  trim/speed/look on take 2 only; export reflects it (golden diff).
- Insert a video from the picker between clips 1 and 2; timeline order
  and total duration are correct; autosave/kill/resume keeps everything.
- v1 slots and v1 nostr payloads still open (migration tests green);
  single-clip projects still encode v1-compatible wire.
- Over-cap publish cuts the tail with a visible reason; nothing signs
  before upload verification (existing gate, unchanged).
- Android + iOS adapter-contract tests green on the new seams.

## 6. Risks / open decisions

- **ExoPlayer playlist vs single composition:** playlist preview is
  cheap but per-clip speed/look preview in the stage needs
  `PlaybackParameters` per item (speed ok; look preview per clip may be
  approximated on the stage and exact only in export — decide at
  MST-054).
- **GIF export animation** is the most expensive item in F3; if it
  slips, shipping video-insert + static GIF overlay is an acceptable
  M5 cut line (disclosed in the Layers sheet as today).
- **Take buffer memory:** N take ByteArrays in the camera session
  already bounded by `MAX_PENDING_TAKES` + publish cap; the editor
  holding N sources must stream from slot files rather than heap bytes
  (MST-055 must move video sources to file-backed refs, not
  `ByteArray`).
