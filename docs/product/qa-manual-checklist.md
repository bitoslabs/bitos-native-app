# Manual QA Checklist — UI flows automation cannot reach

> System pickers, camera handoffs, dialogs, cross-screen seeding and
> hardware codecs are only provable on device. The automated suites
> (`make check`, `make android-test`, `make native-test`) cover shared
> rules, codecs and adapters; THIS checklist is the manual pass for the
> flow layer. Run the relevant section before release or after touching
> its flow; record the run in the log at the bottom.
>
> (History: this started 2026-09-07 after the Create-hub import
> unification — see `docs/product/ux-ui-flows.md` §6 for the flow
> contracts each section verifies. `QA.md` remains the platform-decision
> record, unrelated to this file.)

## How to run

- Android: `make build-android-apk` → install the debug APK on a device
  or emulator with a gallery that has videos (incl. one >256 MB).
- iOS: `make build-ios` (needs a runnable simulator destination).
- Each row is pass/fail; a FAIL blocks release of that flow with a note
  in the log.

## S1 — Studio "Import media" → editor (Android + iOS)

| # | Step | Expected |
|---|---|---|
| 1 | Create hub → "Import media" | System video picker opens (video-only) |
| 2 | Pick a normal video | Brief "Preparing your video…" state, then the meme editor opens in VIDEO mode with the clip on the timeline; >60 s sources show the cut notice |
| 3 | Cancel the picker | Hub unchanged; nothing seeded |
| 4 | Pick a corrupt/unreadable file | "Couldn't open that video" dialog: "This video could not be read. Try another file."; hub untouched |
| 5 | Pick a >256 MB video | Dialog: "…larger than 256 MB. Trim it first, then try again." |
| 6 | Enter the editor with no source | Editor empty state still offers its own media-tray import (image/GIF/video) |

## S2 — Camera → one editor pipeline (Android + iOS)

| # | Step | Expected |
|---|---|---|
| 1 | Create → Bitz → record → preview → **Use** | Editor opens in VIDEO mode seeded with that take (no publish sheet) |
| 2 | Record screen → "Import from library instead" | Same picker as S1 → editor (Android immediate; iOS opens right after the camera screen dismisses) |
| 3 | Record screen → **MEM** | Editor seeded with ALL takes as separate timeline clips (unchanged M5 behavior) |
| 4 | Deny camera permission → "Import from library instead" | Same picker → editor path works without the camera |
| 5 | Publish from the editor | Post details → render → upload → sign machine; note appears in the feed |

## S3 — Feed app-bar (no direct-publish entry; removed 2026-09-07)

| # | Step | Expected |
|---|---|---|
| 1 | Home app-bar | Filter/search/camera/hub icons only — NO photo "Import and publish" icon on either platform |
| 2 | App-bar camera icon | Create hub opens; "Import media" routes through the studio editor (S1 path) |
| 3 | Bitz rail header | NO import entry (both platforms) |

## S4 — Create hub layout & drafts

| # | Step | Expected |
|---|---|---|
| 1 | Open the Create hub | "Start something new" tiles sit directly under their header, above Templates (Android fixed 2026-09-07; iOS parity) |
| 2 | Continue creating → Resume | Editor returns to the exact persisted state |
| 2a | Resume a saved video draft | Timeline and video preview load and play immediately; switching modes is not required |
| 3 | Draft delete (✕) | Slot disappears, list reorders |
| 4 | Templates / Shared templates card | Editor opens seeded with the template |

## S5 — Editor export regression (run after any export-pipeline change)

| # | Step | Expected |
|---|---|---|
| 1 | Video meme export → save to device | File plays with per-clip volume, looks and SFX cues baked |
| 2 | Publish a meme | Receipt-machine stages render (never a silent hang); CW/alt/imeta ride the note |
| 2a | After a successful publish, start a new draft and open Post details | New draft shows the normal idle preflight, not the prior attempt's `Done`; `Done` appears only after a relay acceptance |
| 3 | Kill the app mid-render → relaunch | Draft intact; no partial artifacts published |

## S6 — "Use this sound" loop (Android + iOS; run after any sound-path change)

| # | Step | Expected |
|---|---|---|
| 1 | Editor (video mode) → Sound tool → "♪ Pick sound from a video…" → pick a library video | "Extracting the sound…" then the attached row (label · duration · volume · Remove) |
| 2 | Play/scrub the timeline | The soundtrack is audible in preview, glued to the clock; Mute/slider/remove work live |
| 3 | Export → save to device | The saved file plays with clip audio + soundtrack (+ cues) mixed |
| 4 | Publish | Receipt stages render; the note carries `sound`/`p`/`attribution` tags (raw event view) with the uploaded m4a's URL + sha256 |
| 5 | Close the editor mid-session → Continue creating → Resume | The soundtrack rehydrates (row + audible); an undecodable restore strips the row with a named notice — never a silent export |
| 6 | Any video bitz → rail **Sound** | Editor opens in video mode with the sound attached and NO clip; provenance (source note + author) shows in the wire row |
| 7 | Publish a borrowed-sound meme | Feed/Bitz card shows the ♪ chip; the sound/p/attribution credit the source |
| 8 | More → Trending sounds | The published borrow ranks (uses count, 3-day half-life); ▶ streams the artifact (one row at a time) and the waveform bars appear after the first preview (accent-tinted while playing); empty state explains the loop when none |
| 9 | Trending row → "Use in Studio" | Hash-verified re-attach: editor seeds with the sound, publish stamps the EXISTING URL (no re-upload; a hash mismatch loads nothing and says so) |
| 10 | Soundtrack shorter than the timeline → enable "Loop to fill" | Preview repeats the sound to the end; the exported file's mix loops identically; "plays once" restores silence after one pass |
| 11 | Select a timed overlay (visibility window) → open the Sound effects sheet | The "Cue anchor · selected overlay window" row appears; ＋ places the cue at the window start/end per the toggle instead of the playhead |
| 12 | Corrupt cases: pick a video with no audio / undecodable file | Named failures ("No readable audio track…" / "could not be decoded…"), session usable |

## S7 — Blank canvases + cross-format export (Android + iOS; run after any blank-canvas / GIF-path change)

Covers `meme-blank-canvas-crossmode-plan.md` waves 1–4: blank sessions in all three
modes, the kinetic-GIF path and video→GIF conversion.

| # | Step | Expected |
|---|---|---|
| 1 | IMAGE empty state → "Start blank canvas" → add text + stickers → export | PNG at the canvas ratio/background; overlays + image layers burned (no white-rectangle export before content — buttons stay disabled) |
| 2 | VIDEO empty state → "Start blank canvas" → 9:16 · color · 10 s → add text + a GIF layer + an SFX cue → export | MP4 plays the canvas with overlays, the animated layer and the cue mixed; timeline shows the `canvas` segment; blank session with no content refuses with the named notice |
| 3 | Blank video → Canvas chip → change bg / duration / ratio | Source regenerates in place; overlays/cues keep timeline positions; shorter duration clamps the window (nothing silently dropped) |
| 4 | Blank video → kill app → relaunch → resume | Timeline restores (`canvas` label + clip bytes); edits survive; Canvas chip still offered |
| 5 | GIF empty state → "Start blank canvas" → 2 s · 15 fps → add text → give it POP fx + a visibility window | Stage animates the overlay on the loop clock; exported GIF loops with the SAME motion (WYSIWYG) |
| 6 | Blank GIF → Duration chip → change loop/fps → export | Loop re-times; windows stay proportional; blank with no content refuses with the named notice |
| 7 | Blank GIF → kill app → relaunch | Loop resumes from the wire (`canvas.sec`/`fps`); preview animates again |
| 8 | Picked GIF with a POP/shake overlay → export | Overlay animates in the exported GIF (timed plan per frame); no-animation GIFs are byte-stable as before |
| 9 | Any video (blank or picked, ≥12 s) → Export → Format GIF | Saved GIF: ~10 fps, silent, covers the FIRST 10 s (outcome says "· first 10 s"), overlays + fx motion + layers burned once (no double-paint); oversize auto-downscales ("Saved at a smaller size") |
| 10 | Video→GIF with layers/looks/trim/speed set | Conversion honors per-clip trims + speed + grades + image/GIF layers exactly like the MP4 burn |
| 11 | Video→GIF export → kill app mid-render → relaunch → Export sheet → Retry | Retry reuses the rendered artifact (durable `gif` job), never re-renders |
| 12 | Publish each mode's blank session | VIDEO publishes MP4 (kind 22/21, sound mixed); GIF publishes kind-20; IMAGE publishes PNG — and a content-less blank never reaches Post details (Next stays disabled) |

### Perf gates (device; MST-086)

| Gate | Budget |
|---|---|
| Blank-video synthesis (10 s clip) | ≤ 300 ms on a mid-range device; file ≤ ~100 KiB |
| Video→GIF sampling (10 s timeline) | ≤ ~10 s wall time; peak frames ≤ 100 × 480 px long edge (≤ ~60 MiB) |
| Blank-GIF export (3 s × 15 fps) | ≤ 60 frames held; ladder ≤ 3 downscales / 8 MB — outcome copy says which |
| Derived bounds | Common-tested in `MemeCanvasAndSfxTemplatesTest.derivedGifTimingClampsIdentically` (single-sourced clamps) |

## Run log

| Date | Sections | Device / OS | Result | Notes |
|---|---|---|---|---|
| | | | | |
