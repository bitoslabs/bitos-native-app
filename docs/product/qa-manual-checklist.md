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

| #   | Step                            | Expected                                                                                                                                       |
| --- | ------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Create hub → "Import media"     | System video picker opens (video-only)                                                                                                         |
| 2   | Pick a normal video             | Brief "Preparing your video…" state, then the meme editor opens in VIDEO mode with the clip on the timeline; >60 s sources show the cut notice |
| 3   | Cancel the picker               | Hub unchanged; nothing seeded                                                                                                                  |
| 4   | Pick a corrupt/unreadable file  | "Couldn't open that video" dialog: "This video could not be read. Try another file."; hub untouched                                            |
| 5   | Pick a >256 MB video            | Dialog: "…larger than 256 MB. Trim it first, then try again."                                                                                  |
| 6   | Enter the editor with no source | Editor empty state still offers its own media-tray import (image/GIF/video)                                                                    |

## S2 — Camera → one editor pipeline (Android + iOS)

| #   | Step                                                   | Expected                                                                                          |
| --- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------------- |
| 1   | Create → Bitz → record → preview → **Use**             | Editor opens in VIDEO mode seeded with that take (no publish sheet)                               |
| 2   | Record screen → "Import from library instead"          | Same picker as S1 → editor (Android immediate; iOS opens right after the camera screen dismisses) |
| 3   | Record screen → **MEM**                                | Editor seeded with ALL takes as separate timeline clips (unchanged M5 behavior)                   |
| 4   | Deny camera permission → "Import from library instead" | Same picker → editor path works without the camera                                                |
| 5   | Publish from the editor                                | Post details → render → upload → sign machine; note appears in the feed                           |

## S3 — Feed app-bar (no direct-publish entry; removed 2026-09-07)

| #   | Step                | Expected                                                                                    |
| --- | ------------------- | ------------------------------------------------------------------------------------------- |
| 1   | Home app-bar        | Filter/search/camera/hub icons only — NO photo "Import and publish" icon on either platform |
| 2   | App-bar camera icon | Create hub opens; "Import media" routes through the studio editor (S1 path)                 |
| 3   | Bitz rail header    | NO import entry (both platforms)                                                            |

## S4 — Create hub layout & drafts

| #   | Step                              | Expected                                                                                                            |
| --- | --------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| 1   | Open the Create hub               | "Start something new" tiles sit directly under their header, above Templates (Android fixed 2026-09-07; iOS parity) |
| 2   | Continue creating → Resume        | Editor returns to the exact persisted state                                                                         |
| 2a  | Resume a saved video draft        | Timeline and video preview load and play immediately; switching modes is not required                               |
| 3   | Draft delete (✕)                  | Slot disappears, list reorders                                                                                      |
| 4   | Templates / Shared templates card | Editor opens seeded with the template                                                                               |

## S5 — Editor export regression (run after any export-pipeline change)

| #   | Step                                                                | Expected                                                                                                                |
| --- | ------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| 1   | Video meme export → save to device                                  | File plays with per-clip volume, looks and SFX cues baked                                                               |
| 2   | Publish a meme                                                      | Receipt-machine stages render (never a silent hang); CW/alt/imeta ride the note                                         |
| 2a  | After a successful publish, start a new draft and open Post details | New draft shows the normal idle preflight, not the prior attempt's `Done`; `Done` appears only after a relay acceptance |
| 3   | Kill the app mid-render → relaunch                                  | Draft intact; no partial artifacts published                                                                            |

## S6 — "Use this sound" loop (Android + iOS; run after any sound-path change)

| #   | Step                                                                                   | Expected                                                                                                                                                                                                                         |
| --- | -------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Editor (video mode) → Sound tool → "♪ Pick sound from a video…" → pick a library video | "Extracting the sound…" then the attached row (label · duration · volume · Remove)                                                                                                                                               |
| 2   | Play/scrub the timeline                                                                | The soundtrack is audible in preview, glued to the clock; Mute/slider/remove work live                                                                                                                                           |
| 3   | Export → save to device                                                                | The saved file plays with clip audio + soundtrack (+ cues) mixed                                                                                                                                                                 |
| 4   | Publish                                                                                | Receipt stages render; the note carries `sound`/`p`/`attribution` tags (raw event view) with the uploaded m4a's URL + sha256                                                                                                     |
| 5   | Close the editor mid-session → Continue creating → Resume                              | The soundtrack rehydrates (row + audible); an undecodable restore strips the row with a named notice — never a silent export                                                                                                     |
| 6   | Any video bitz → rail **Sound**                                                        | Editor opens in video mode with the sound attached and NO clip; provenance (source note + author) shows in the wire row                                                                                                          |
| 7   | Publish a borrowed-sound meme                                                          | Feed/Bitz card shows the ♪ chip; the sound/p/attribution credit the source                                                                                                                                                       |
| 8   | More → Trending sounds                                                                 | The published borrow ranks (uses count, 3-day half-life); ▶ streams the artifact (one row at a time) and the waveform bars appear after the first preview (accent-tinted while playing); empty state explains the loop when none |
| 9   | Trending row → "Use in Studio"                                                         | Hash-verified re-attach: editor seeds with the sound, publish stamps the EXISTING URL (no re-upload; a hash mismatch loads nothing and says so)                                                                                  |
| 10  | Soundtrack shorter than the timeline → enable "Loop to fill"                           | Preview repeats the sound to the end; the exported file's mix loops identically; "plays once" restores silence after one pass                                                                                                    |
| 11  | Select a timed overlay (visibility window) → open the Sound effects sheet              | The "Cue anchor · selected overlay window" row appears; ＋ places the cue at the window start/end per the toggle instead of the playhead                                                                                         |
| 12  | Corrupt cases: pick a video with no audio / undecodable file                           | Named failures ("No readable audio track…" / "could not be decoded…"), session usable                                                                                                                                            |

## S7 — Blank canvases + cross-format export (Android + iOS; run after any blank-canvas / GIF-path change)

Covers `meme-blank-canvas-crossmode-plan.md` waves 1–4: blank sessions in all three
modes, the kinetic-GIF path and video→GIF conversion.

| #   | Step                                                                                                          | Expected                                                                                                                                                                                           |
| --- | ------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | IMAGE empty state → "Start blank canvas" → add text + stickers → export                                       | PNG at the canvas ratio/background; overlays + image layers burned (no white-rectangle export before content — buttons stay disabled)                                                              |
| 2   | VIDEO empty state → "Start blank canvas" → 9:16 · color · 10 s → add text + a GIF layer + an SFX cue → export | MP4 plays the canvas with overlays, the animated layer and the cue mixed; timeline shows the `canvas` segment; blank session with no content refuses with the named notice                         |
| 3   | Blank video → Canvas chip → change bg / duration / ratio                                                      | Source regenerates in place; overlays/cues keep timeline positions; shorter duration clamps the window (nothing silently dropped)                                                                  |
| 4   | Blank video → kill app → relaunch → resume                                                                    | Timeline restores (`canvas` label + clip bytes); edits survive; Canvas chip still offered                                                                                                          |
| 5   | GIF empty state → "Start blank canvas" → 2 s · 15 fps → add text → give it POP fx + a visibility window       | Stage animates the overlay on the loop clock; exported GIF loops with the SAME motion (WYSIWYG)                                                                                                    |
| 6   | Blank GIF → Duration chip → change loop/fps → export                                                          | Loop re-times; windows stay proportional; blank with no content refuses with the named notice                                                                                                      |
| 7   | Blank GIF → kill app → relaunch                                                                               | Loop resumes from the wire (`canvas.sec`/`fps`); preview animates again                                                                                                                            |
| 8   | Picked GIF with a POP/shake overlay → export                                                                  | Overlay animates in the exported GIF (timed plan per frame); no-animation GIFs are byte-stable as before                                                                                           |
| 9   | Any video (blank or picked, ≥12 s) → Export → Format GIF                                                      | Saved GIF: ~10 fps, silent, covers the FIRST 10 s (outcome says "· first 10 s"), overlays + fx motion + layers burned once (no double-paint); oversize auto-downscales ("Saved at a smaller size") |
| 10  | Video→GIF with layers/looks/trim/speed set                                                                    | Conversion honors per-clip trims + speed + grades + image/GIF layers exactly like the MP4 burn                                                                                                     |
| 11  | Video→GIF export → kill app mid-render → relaunch → Export sheet → Retry                                      | Retry reuses the rendered artifact (durable `gif` job), never re-renders                                                                                                                           |
| 12  | Publish each mode's blank session                                                                             | VIDEO publishes MP4 (kind 22/21, sound mixed); GIF publishes kind-20; IMAGE publishes PNG — and a content-less blank never reaches Post details (Next stays disabled)                              |

### Perf gates (device; MST-086)

| Gate                               | Budget                                                                                                     |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| Blank-video synthesis (10 s clip)  | ≤ 300 ms on a mid-range device; file ≤ ~100 KiB                                                            |
| Video→GIF sampling (10 s timeline) | ≤ ~10 s wall time; peak frames ≤ 100 × 480 px long edge (≤ ~60 MiB)                                        |
| Blank-GIF export (3 s × 15 fps)    | ≤ 60 frames held; ladder ≤ 3 downscales / 8 MB — outcome copy says which                                   |
| Derived bounds                     | Common-tested in `MemeCanvasAndSfxTemplatesTest.derivedGifTimingClampsIdentically` (single-sourced clamps) |

## S8 — Editor shell (Android + iOS; run after any shell/catalogue change)

Covers `meme-studio-ux-redesign-plan.md` waves W0–W6 (MSU-000..063): the
shared `MemeTools` catalogue, the single tool bar, the timeline workspace,
onboarding, the notice host, publish-vs-export clarity and mass production.
The shared invariants (tier membership, glyph uniqueness within a tier, label
/target floors) are enforced by `MemeToolsTest` / `DesignTokensTest` in
`make check`; this section proves the RENDERED shell on device.

### S8.1 — Tool bar & tier visibility per mode

| #   | Step                                                             | Expected                                                                                                                  |
| --- | ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| 1   | Open the editor in IMAGE, GIF and VIDEO mode                     | Exactly ONE tool bar; no second chip row anywhere in the session                                                         |
| 2   | Read the primary bar in each mode                                | Media · Text · Sticker · Look in all three; **Sound** appears ONLY in VIDEO (GIF is silent, IMAGE has no timeline)        |
| 3   | Count the primary tiles                                         | ≤ 5 tiles + one **More** tile (catalogue `MAX_PRIMARY_TOOLS`)                                                             |
| 4   | Open More in each mode                                           | The advanced list matches the mode (Trim/Speed/Volume/Clips only in VIDEO; GIFs/Duration only in GIF; Canvas/Layers/Draw everywhere); Batch + Shortcuts present |
| 5   | Select an overlay, then a clip, then a GIF frame                 | The contextual "Selected item" block appears with the right actions; with nothing selected it is absent                    |
| 6   | Compare Android and iOS                                          | Same ids, same labels, same order, same icons for the same mode (both render from the one catalogue)                       |
| 7   | Tap any tile with a hardware/desktop keyboard attached           | Labels are legible (≥12 sp labels) and every tile's touch target is ≥48 dp/pt                                              |

### S8.2 — One capability, one chip

| #   | Step                                                        | Expected                                                                              |
| --- | ----------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| 1   | Open Look, then grade and adjust inside it                  | ONE Look sheet with all sections — no separate Filter / Adjust / Look chips            |
| 2   | Open More → Draw and the More → Layers row                  | Each capability is reachable from exactly one visible affordance                       |
| 3   | Open the timeline workspace and read its dock               | True timeline tools only (Trim · Split · Speed · Volume · Clips · Add · SFX) — no Look, Layers, Draw, undo or redo |
| 4   | Check the top chrome                                        | Exactly one undo and one redo affordance app-wide; disabled states correct             |

### S8.3 — Timeline workspace round-trip

| #   | Step                                                                    | Expected                                                                                     |
| --- | ----------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| 1   | More → Timeline (and the clip selection's "Open timeline")              | The workspace opens; the mode pill stays visible; header says **"Back to editor"**           |
| 2   | Tap each lane (clip, image layer, SFX cue)                              | The tapped lane selects (opens its selection tier) — no lane silently swallows the tap       |
| 3   | Scrub on the ruler strip only                                            | Scrubbing works on the ruler; tapping lanes still selects (the seek surface never covers them) |
| 4   | Press "Back to editor"                                                   | Returns with the same selection + scroll state; no edits lost                                |
| 5   | Zoom the ruler (fit ↔ 1 s) and drag the playhead                          | Zoom is session-local; the playhead readout stays visible; no engine change                  |
| 6   | With a hardware keyboard, use ←/→ (1 s) and ↑/↓ (5 s) and space          | Scrubbing and play/pause respond while the workspace owns the keyboard                       |

### S8.4 — Onboarding & empty states

| #   | Step                                                                       | Expected                                                                                        |
| --- | -------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| 1   | First fresh editor session on a device that has never seen the coach       | The 3-step coach runs (canvas → tools → Next publishes) with Skip / Got it                       |
| 2   | Resume a draft, and separately enter via remix / sound / template / camera | The coach does NOT run on any of these handoffs                                                  |
| 3   | Image / GIF / video empty state                                            | The mode-appropriate guiding empty state with its primary action + a template rail               |
| 4   | Use undo once on a fresh session                                            | A one-time notice names redo ("Undone — redo is beside undo in the top bar")                    |
| 5   | Undo with an empty history; redo with an empty redo stack                   | "Nothing to undo" / "Nothing to redo" — never a dead tap                                         |

### S8.5 — Notice host

| #   | Step                                                          | Expected                                                                                             |
| --- | ------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| 1   | Trigger a transient result (e.g. change a frame hold)         | A notice appears and auto-dismisses; posting the SAME message again does not restart the animation    |
| 2   | Trigger an error (e.g. a failed export)                       | The error notice is persistent — it does not auto-dismiss                                            |
| 3   | Delete an overlay / clip / layer                              | The notice offers **Undo**; tapping it restores the prior state (no confirm dialog for a reversible act) |
| 4   | Post two notices in a row                                     | Newest wins (single slot); the previous one is replaced                                              |

### S8.6 — Publish vs Export clarity

| #   | Step                                                            | Expected                                                                                            |
| --- | --------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| 1   | Look at the editor header                                       | ONE prominent primary (**Next**); Export is a demoted, labelled secondary icon                       |
| 2   | Tap Next the first time                                          | The one-line explainer appears once: "Publish posts to Nostr · Export saves a file to this device." |
| 3   | Open the export sheet                                            | It is titled **"Save a copy"** and saves a rendered FILE to the device                               |
| 4   | Walk the review screen                                           | The review order is stated once (Preview · Caption · Tags · Safety · Publish); verify-before-sign machine unchanged |
| 5   | Publish successfully                                             | Success is a result card: **Posted · View · Share · Make another** (+ Recovery queue); slot cleared as before |
| 6   | Tap Share on the result card                                     | Shares an `njump.me/<eventId>` link (nothing is re-signed or re-uploaded)                            |

### S8.7 — Mass production woven in

| #   | Step                                                                     | Expected                                                                                            |
| --- | ------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------- |
| 1   | More → Batch (an image design with ≥1 caption)                           | Row reads **"Use as batch base"** with an explainer; tapping freezes the design and seeds a batch    |
| 2   | After seeding, stay in / reopen the editor                              | A status strip shows "N of M rendered · View queue"; tapping it opens the newest batch in one step   |
| 3   | Empty-state template rail → "Make N variants"                            | Applies the first template and seeds a batch; caption slots editable in the queue                    |
| 4   | Verify no batch references the project                                   | No status strip is shown (the strip is conditional)                                                  |
| 5   | More → Controls                                                          | Sheet titled **"Controls"** with an **"On screen"** section listing every action's touch affordance (undo, redo, play/pause, select prev/next, save a copy, review & publish, timeline, delete) |
| 6   | Open Controls on a phone with NO hardware keyboard                       | The keyboard section is HIDDEN and the hint invites connecting one; no key glyphs a phone cannot press |
| 7   | Attach a Bluetooth/desktop keyboard → reopen Controls                    | A **"Keyboard (optional)"** section appears listing the key glyphs (⌘/⌥ on iOS, Ctrl/Alt on Android)   |
| 8   | With a hardware/desktop keyboard, use the documented keys                | undo (mod+Z) · redo (mod+Shift+Z) · export (mod+E) · publish (mod+Enter) · timeline (mod+T) · alt+↑/↓ selection · delete all fire; ignored while a sheet/text field owns the keyboard |

### S8.8 — Progress surfaces & confirm audit (MSU-042/043)

| #   | Step                                                                 | Expected                                                                                                          |
| --- | -------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| 1   | Import several camera takes / library clips                          | ONE shared "Preparing clips…" overlay with a determinate `N of M` bar; it clears when staging finishes            |
| 2   | Export a long video                                                  | The render overlay shows the shared title/body and an **indeterminate** spinner — never a fake percentage bar     |
| 3   | Kill the app mid-import / mid-export, relaunch                       | Draft intact; no partial artifact published (durable job machinery unchanged)                                     |
| 4   | Delete an overlay, then a clip, then a layer                         | NO confirm dialog — each posts the Undo notice; Undo restores it                                                   |
| 5   | Switch mode with media loaded                                        | A confirm dialog appears first (irreversible: media clears, overlays stay)                                        |
| 6   | Trigger a failed draft save and dismiss the editor                    | The discard dialog appears (irreversible: "Delete draft") — its copy matches the shared audit                     |
| 7   | Discard camera takes                                                  | A confirm dialog appears before the takes are dropped                                                             |

## Run log

| Date | Sections | Device / OS | Result | Notes |
| ---- | -------- | ----------- | ------ | ----- |
|      |          |             |        |       |
