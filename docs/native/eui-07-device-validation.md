# EUI-07 device validation runbook

Date prepared: 2026-09-20. Status: **ready to execute — requires physical
devices**. Source-level gates for EUI-01..06 are green (shared tests,
Android unit tests, iOS Swift 6 type-check); this runbook is what converts
"implemented" into "validated" for the reference spec
[`docs/product/mobile-video-image-editor-reference-spec.md`](../product/mobile-video-image-editor-reference-spec.md).

Runs AFTER (or together with)
[`mux-10-device-validation.md`](./mux-10-device-validation.md) — the
storage/kill/recovery rows there are not repeated here except where the
reference spec names them itself.

## Preconditions

Same as MUX-10, plus:
- Camera roll with: 1 portrait IMAGE (tall), 1 landscape image, 1 GIF,
  1 video ≥ 30 s, 1 SHORT video (< 5 s) for Replace clamping.
- Side-load two caption files: `good.srt` (10 well-formed cues, one pair
  overlapping in time), `junk.txt` (prose, no cues), and one `big.srt`
  > 48 cues. A > 512 KB caption file for the bounded-read check.
- Screen reader available (VoiceOver / TalkBack); large-text and
  reduced-motion settings reachable; RTL language (e.g. Arabic) installed
  on the Android device.

Mark each row **Pass / Fail / Partial** with a one-line note. Fails on
any truthfulness row (marked ⊥) are release blockers: a lying state is
worse than a missing feature.

## A. The reference spec's non-negotiable scenarios

| # | Spec scenario | Steps | Required result | Notes / deferrals |
| --- | --- | --- | --- | --- |
| A1 | Portrait → 9:16, crop + rotate, reopen, export | **Image variant (shipped)**: portrait image → Crop → 9:16 → rotate right ×1 → zoom 1.5 → Apply → ✕ (draft saves) → reopen → Save a copy (PNG). **GIF variant**: same on a GIF → export GIF. | Reopened draft shows the SAME crop state; exported framing/rotation matches the stage preview exactly; no draft loss. | Spec's literal VIDEO crop is **deferred by design** (video re-frame = re-encode; tool absent in video mode — confirm the tool is NOT offered there, which is itself a pass). |
| A2 | Clip 2 of 3: trim + split, undo/redo | 3-clip timeline → select clip 2 → Trim → Split → note duration readout → undo → redo. | Only clip 2 changes; time readout and output duration consistent before/after; redo restores exactly; clip 1/3 untouched. | |
| A3 | Mute a clip + shorter soundtrack Loop-to-fill | Clip → Mute → Sound → attach a shorter track → toggle Loop to fill → preview → export → replay the file. | Loop caption states real durations ("Track X s < video Y s — repeats to fill"); preview AND exported audio follow the same rule (loop fills; play-once ends in silence). ⊥ | |
| A4 | Grade + exposure, Cancel, Apply | Look → pick a grade → Exposure +30 → Fade +20 → **Cancel** → re-open Look → **Apply** → undo. | Cancel leaves stage + wire untouched; Apply lands the whole set as ONE undo step (single undo restores the pre-Look state); ⊥ | Spec's vignette is not offered (matrix pipeline can't render it) — verify it's ABSENT, which is the honest pass. |
| A5 | Screen reader + large text | Run section C below. | Every control discoverable/usable; see C rows. | |
| A6 | Save a copy → cancel during render → relaunch → retry | Export → Cancel mid-render → kill app → relaunch → Save a copy again. | Project intact; no partial file in Photos; retry succeeds; no duplicate artifact. ⊥ | Overlaps MUX-10 A-runs; repeat only if render pipeline changed since. |
| A7 | Next → review → publish bytes hash-verified | Publish with network throttled; watch the phase line. | Upload + hash-verify phase precedes any signing request; failure at upload leaves NO signed event; recovery queue row honest. ⊥ | Covered deeper by MUX-10 A7; here confirm the editor entry shows the same machine. |

## B. Slice-by-slice checks

### B1 — Shell (EUI-01)

- iOS: on-screen Redo beside Undo, both reflect enabled state; Android
  already had the pair.
- Header reads **Next** (primary) and **Save a copy** (text, not an icon)
  on both platforms; first Next shows the explainer naming "Save a copy"
  (word-for-word the shared copy), second Next does not.
- Draft status honest: spinner during save, failure keeps the editor open
  with Retry (⊥ — covered by MUX-10 A1).

### B2 — Look sheet (EUI-02)

- Tone | Colour tabs; control chips carry icons; the changed chip shows
  "•"; ONE slider for the selected control with a visible value.
- Slider drags preview live on the stage in ALL modes (image, GIF frame,
  and **video player** — iOS video preview included); Cancel reverts the
  stage; Apply = one undo step.
- Reset one control = tap; Reset all with ≥ 2 changed controls asks first.
- Grade thumbnails render the CURRENT media (not stock imagery) ⊥; text
  pills appear only where no frame exists (video per-clip look).

### B3 — Crop sheet (EUI-03)

- Ratio chips show shapes; **Original** shows the media's shape; 3:4
  present; picking Original CLEARS the crop.
- Rotate left/right + Flip + Zoom/Position sliders preview live; the
  sliders are the no-pinch path (drag slider with screen reader).
- Legacy letterbox project (pre-crop draft) shows the padding disclosure
  and keeps letterboxing until a crop is applied ⊥.
- Draft restore: apply a crop → close → reopen (A1 flow).

### B4 — Timeline Replace (EUI-04)

- Replace appears in clip selection (spec order … Duplicate, Replace,
  Delete) and the iOS strip row; video mode only.
- Replace with a SHORTER video → notice says "window shortened to fit the
  shorter clip" and the window is clamped ⊥; longer/equal → "trim, volume
  and look kept".
- Undo after replace restores the OLD media AND old window (not new media
  with old window) ⊥ — delete the highest-numbered clip, then replace,
  then undo twice (archive-id regression).

### B5 — Sound rows (EUI-05)

- Each shared-sound row: preview ▶ (one at a time — starting a second
  stops the first ⊥), state chip Add / Downloading… / Added.
- Airplane mode → Add a shared sound → row shows "Needs a connection to
  load" hint and Add remains tappable ⊥; library rows still preview and
  attach offline.
- A row that IS the current soundtrack reads **Added**; downloading never
  shows as the soundtrack ⊥.
- Loop copy honest per A3.

### B6 — Timed captions (EUI-06)

- Import `good.srt` → notice states imported count; overlapping pair
  staggered to the second row; select a caption → Timing action edits the
  window; timeline overlay lanes show the spans.
- Import `junk.txt` → "No timed cues found — nothing was added" ⊥.
- Import > 48-cue file → truncation named with the cap; `big.srt`
  (over 512 KB) rejected without hanging ⊥.
- Add caption at playhead → selected, 2 s window, editable; at playhead
  exactly 2 s the notice must NOT claim "Caption limit reached" ⊥
  (regression from the review round).
- Speech generation absent everywhere (no lying button) ⊥.

## C. Accessibility matrix (new surfaces)

Rows marked ⊥ are truthfulness checks. Devices: VoiceOver (iOS) +
TalkBack (Android); large text = largest OS setting; reduced motion on;
RTL on Android.

| Surface | Expected under screen reader | Large text | RTL |
| --- | --- | --- | --- |
| Header | "Close editor", "Undo", "Redo", "Next — review and publish to Nostr", "Save a copy — save a rendered file to this device" | Labels may truncate; every action still tappable | Icons mirror; Next/Save order reads correctly |
| Look sliders | Label + value announced; adjustable action increments/decrements (VoiceOver rotor / TalkBack volume) ⊥ | Value column stays visible; row may grow | Slider direction follows layout |
| Crop sheet | Shape chips labelled "Aspect ratio N:N"; sliders as above; Original clears crop when announced | Chips wrap or scroll; nothing clipped to zero | Rotate/Flip labels unmirrored (semantic, not directional) |
| Timeline | "Clip 2 of 3, 4.2 seconds" (iOS) / "Clip 2, selected, 4 seconds" (Android) ⊥; Replace announced | Strip remains scrollable | Playhead + scrub direction mirrored consistently ⊥ |
| Sound rows | "Preview X" / "Stop previewing X"; state chip announces Add/Downloading/Added ⊥ | Two-line rows grow; no overlap | Preview icon not directionally ambiguous |
| Captions | Import/Add-at-playhead labelled; notices announce counts | Panel fields grow | — |

## D. What this runbook does NOT cover

- Video crop/rotate export (deferred — encoder-transform verification is
  its own gate before the tool ships).
- Speech-to-text caption generation (no engine exists).
- Vignette/grain/sharpness adjustments (matrix pipeline cannot render
  them honestly).
- Trending-sounds screen rows (kept their shipped treatment).
