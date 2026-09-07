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

## S3 — Feed quick-import (the no-editing fast path)

| # | Step | Expected |
|---|---|---|
| 1 | Home app-bar photo icon | "New video" bottom sheet on BOTH platforms |
| 2 | Pick → caption/alt/CW → Upload & publish | Stage feedback at every step; "Published ✓" |
| 3 | Oversized/unreadable pick | Named failure surfaced; dismiss keeps the feed stable |
| 4 | Bitz rail header | NO import entry (both platforms) |

## S4 — Create hub layout & drafts

| # | Step | Expected |
|---|---|---|
| 1 | Open the Create hub | "Start something new" tiles sit directly under their header, above Templates (Android fixed 2026-09-07; iOS parity) |
| 2 | Continue creating → Resume | Editor returns to the exact persisted state |
| 3 | Draft delete (✕) | Slot disappears, list reorders |
| 4 | Templates / Shared templates card | Editor opens seeded with the template |

## S5 — Editor export regression (run after any export-pipeline change)

| # | Step | Expected |
|---|---|---|
| 1 | Video meme export → save to device | File plays with per-clip volume, looks and SFX cues baked |
| 2 | Publish a meme | Receipt-machine stages render (never a silent hang); CW/alt/imeta ride the note |
| 3 | Kill the app mid-render → relaunch | Draft intact; no partial artifacts published |

## Run log

| Date | Sections | Device / OS | Result | Notes |
|---|---|---|---|---|
| | | | | |
