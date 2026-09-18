# Mobile image & video editor — reference UX/UI specification

Date: 2026-09-18. Status: proposed implementation reference.

## Purpose

The supplied reference screens show a familiar mobile editor: a focused
portrait preview, a clip timeline, and task-specific bottom sheets for trim,
music, captions, crop/aspect ratio, look adjustment, and filters. This
document converts that visual reference into an implementable BitOS experience
for image, GIF, and video projects.

It is a UX specification, not a request to copy the reference product's
branding, icons, copy, media, or exact layout. BitOS keeps its native
SwiftUI/Compose UI, design tokens, existing editor engine, Nostr safety gates,
and the shared editor contracts described in
[`../native/meme-studio-ux-redesign-plan.md`](../native/meme-studio-ux-redesign-plan.md).

## Product decisions

| Reference pattern | BitOS decision |
| --- | --- |
| `Save` in the editor header | Use **Next** as the one primary action (review and publish). Put **Save a copy** in a labelled secondary action. Draft saving remains automatic and is never confused with file export. |
| Separate Editor, Filter, Adjust, and Aspect-ratio tools | Retain the capabilities, but expose each once: **Look** owns filter + adjustment; **Crop** owns ratio + rotation. |
| A full-screen alternate editor for clip actions | Use the existing **Timeline workspace**, entered through More → Timeline. It has an explicit **Back to editor** action. |
| Small icon-only controls | All controls have visible labels where space permits, action-based accessibility labels, and at least 48 dp/pt targets. |
| Apply/cancel controls in every sheet | Every editing sheet is a reversible draft transaction: **Cancel** restores the entry value; **Apply** commits one undoable editor command. |
| Online music catalogue | A music source is usable only when its licensing, preview, availability, and export/publish permissions are known. Local audio remains available offline. |

The existing shared contracts remain authoritative:

- `MemeTools`: one native tool catalogue, labels, order, and availability.
- `EditorSurfaces`: one Back/close hierarchy.
- `EditorNotices`: typed transient, error, and Undo feedback.
- `BusinessCore`: validation, caps, deterministic project rules, versioned
  wire contracts.
- Native adapters and MediaCore: picker permissions, playback, rendering,
  audio processing, file destinations, and background work.

## Experience goals

1. A first-time creator can import, add text, improve the look, and either
   save a file or start posting without learning timeline vocabulary.
2. An experienced creator can select a clip, trim/split/reorder it, change
   audio, and return to the same playhead and selection.
3. Every preview is honest: it identifies approximation where preview cannot
   match the native renderer exactly.
4. No destructive or long-running action loses a usable project, rendered
   artifact, or selection without a recovery path.
5. Image, GIF, and video use the same shell; mode-specific actions appear only
   when meaningful.

## Screen anatomy

### Editor shell

```text
┌───────────────────────────────────────────────────────┐
│ Close   [Image / GIF / Video]             Undo Redo Next│
│ Draft saved · project title                            │
│                                                       │
│                    stage preview                      │
│        selected content has boundary + handles         │
│                                                       │
│ Play/Pause   00:04 / 00:15                 Save a copy │
│ [selected item actions, only when something selected] │
│ Media   Text   Sticker   Sound*   Look      More       │
└───────────────────────────────────────────────────────┘
* Sound is available only in Video mode.
```

- The stage uses the largest aspect-fit area possible. It never crops the
  displayed media merely to fill chrome.
- The project mode control is a mode switch only if the current project can
  change modes safely; otherwise it states the current mode without implying a
  destructive conversion.
- `Close` resolves through `EditorSurfaces`: first dismiss text entry/sheet/
  panel, then asks to leave only when needed. A confirmed close flushes the
  current durable revision before returning to the project library.
- Draft status is truthful (`Saving…`, `Saved`, `Could not save — Retry`), not
  inferred from a dispatched write.
- Undo and redo are in one persistent location, reflect enabled state, and
  include accessible names such as “Undo crop adjustment”.
- `Next` starts the existing review-and-publish path. On first use it explains:
  “Publish posts to Nostr. Save a copy creates a file on this device.”

### Tool hierarchy

The reference's bottom toolbar maps to the shared BitOS catalogue rather than
adding a second toolbar.

| User goal | Primary route | Detail surface |
| --- | --- | --- |
| Replace/add media | Media | Native picker; video inserts as a timeline clip, image/GIF can be a layer where supported |
| Add/edit words | Text | Text composer and selected-text actions |
| Add visual decoration | Sticker | Sticker/layer sheet |
| Add captions | More → Captions | Caption language/style/timing sheet |
| Choose soundtrack or clip audio | Sound (Video) | Music/audio sheet |
| Improve image appearance | Look | Grade, Adjust, and Motion sections |
| Set aspect ratio, crop, rotate | More → Crop | Crop sheet |
| Edit clip timing | select clip → actions, or More → Timeline | Timeline workspace |
| Save a local rendered file | header secondary action | Save a copy sheet |
| Create variations | More → Batch | Existing immutable batch-base flow |

`More` is a labelled overflow, not a miscellaneous icon. It lists only tools
valid for the current mode and selection. It must not repeat a visible primary
tool.

## Reference interactions

### 1. Playback and timeline

For video, the reference’s thumbnail strip becomes the editable Timeline
workspace, not a second editor.

```text
Editor → More → Timeline
  → select a clip/layer/audio lane
  → make a command (Trim, Split, Speed, Volume, Reorder, Delete)
  → preview at the same playhead
  → Back to editor (selection and scroll position retained)
```

- Play/pause has a visible state, current time, total duration, and an
  accessibility action. Playback loops only when the user enabled looping.
- The playhead begins at zero at the first editable lane; ruler, clip edges,
  time readout, and tap-to-seek use the same timeline coordinate system.
- Clip thumbnails are aspect-fit enough to identify content. Do not make a
  thumbnail tap both inspect and approve/select an unrelated record.
- A selected clip has a high-contrast outline and handles. The selected state
  is also announced in text, for example, “Clip 2 of 3, 4.2 seconds”.
- Touch actions have alternatives: `Previous/Next clip`, trim start/end
  fields, split-at-playhead button, and keyboard/switch-access actions.
- The `+` insertion affordance is labelled **Add media after clip 2** (or
  equivalent), never a bare plus with unknown insertion position.

#### Clip selection actions

Video clip selection exposes the following contextual actions, in this order:

1. **Trim** — adjusts only that clip's in/out window; display start/end and
   resulting duration. Enforce the shared minimum window.
2. **Split** — enabled only when the playhead produces two valid pieces. The
   result is immediately undoable.
3. **Speed** — show named presets and an exact multiplier; update resulting
   timeline duration before Apply.
4. **Volume** — mute toggle plus a labelled gain control. State explicitly
   whether preview and export support fractional gain equally.
5. **Look** — `None (inherit project look)` plus project-independent choices.
6. **Duplicate**, **Replace**, and **Delete** — Delete provides an Undo notice;
   Replace retains project timing/settings only where that is unambiguous.

Reorder is direct manipulation with a textual alternative. Moving a clip must
not silently change another clip's trim, volume, or look.

### 2. Look: filters and adjustments

The reference separates Filter and Adjust. BitOS combines them in the single
**Look** sheet to prevent duplicate entry points.

```text
Look
  Grade       None · saved/custom looks · curated looks
  Adjust      Tone | Colour | Details
  Motion      only where a supported mode/renderer exposes it
  Reset       reset current section / reset all (confirmation for all)
  Cancel                                      Apply
```

#### Adjustment taxonomy

| Section | Controls |
| --- | --- |
| Tone | Exposure, brightness/lightness, contrast, highlights, shadows |
| Colour | Saturation, vibrance, warmth/temperature, tint, fade |
| Details | Sharpness, grain, vignette |

- Selecting a control reveals one labelled slider and its numeric value/range.
  Screen-reader users can increment/decrement it without dragging.
- A setting starts at its actual inherited/current value. `0` or `Default` is
  visible when unchanged; do not make an active orange accent the only signal.
- Grade previews use the real source thumbnail or a clearly labelled,
  deterministic preview. Do not use unrelated stock imagery as a filter
  preview.
- Grade intensity is available only when the renderer supports it. Otherwise,
  selecting a grade applies its documented fixed profile.
- `Reset current setting` is a small secondary action; `Reset all adjustments`
  requires confirmation only if it discards multiple non-default values.
- For video, project-level look is the default. Per-clip look is selected from
  clip actions and visibly says whether it inherits or overrides.

### 3. Crop and aspect ratio

Crop is one surface that combines the reference's ratio picker and direct
pinch/rotate treatment.

```text
Crop
  Ratio: Original · 1:1 · 4:5 · 3:4 · 9:16 · 16:9
  Actions: rotate left · rotate right · flip horizontal
  Canvas: drag to position; pinch to zoom; accessible position/scale fields
  Cancel                                              Apply
```

- The crop boundary uses a high-contrast edge and optional safe-area guides.
  A short coach mark appears only on first crop interaction: “Pinch to zoom;
  drag to reposition.”
- Ratio options show both label and shape; **Original** states the source
  ratio where space permits.
- Choosing a ratio adjusts the frame but does not irreversibly rasterize the
  asset. Returning to Original restores the uncropped frame unless the user
  also changed position/scale deliberately.
- Rotation, mirroring, and crop are undoable project commands. Video crop
  applies consistently across the whole project unless a future per-clip crop
  capability is explicitly implemented.
- The crop sheet discloses when output will contain padding rather than crop
  media, and why.

### 4. Music and audio

The reference has Online and Local tabs, preview playback, add/download
affordances, and track duration. BitOS uses those principles with honest
availability states.

```text
Sound (Video only)
  For this video: original audio / soundtrack / sound effects
  Browse: Local · Licensed catalogue (when configured)
  Track row: artwork, title, creator, duration, preview, availability, Add
  Selected track: trim/loop-to-fill, volume, attribution/provenance
```

- Local opens the native audio picker and immediately validates readable audio,
  duration, bytes, and supported format before adding it.
- Catalogue rows clearly distinguish **Preview**, **Download**, **Add**, and
  **Added**. A download is never represented as an active soundtrack.
- Starting a new preview stops the prior one. Preview state is announced and
  remains tied to its row after scrolling.
- The sheet is useful while offline: local selection works; unavailable remote
  rows explain that they require a connection rather than showing dead plus
  buttons.
- If soundtrack duration is shorter than the video, offer **Play once** and
  **Loop to fill**; preview and export follow the same selected rule.
- Audio provenance and licensing metadata are treated as bounded project data;
  publish displays attribution where required. A failed/invalid source does
  not remove the prior working soundtrack.

### 5. Captions and text

Captions are a dedicated capability, not hidden behind a generic text plus.

- **Add captions** supports: generate from speech where available, import
  timed captions, and add manually. Generation never claims completion until
  actual transcription results exist.
- The first caption workflow asks for language only when it can change output;
  it offers manual editing and a clear retry/error state.
- Caption style includes readable presets, text size, contrast background or
  outline, position, and safe-area preview. Style controls meet contrast in
  both app themes.
- Selecting a caption opens text edit and timing actions; the timeline shows
  caption spans. Keyboard editing keeps the active caption and Done control
  visible.
- Captions are distinct from post alt text and content warnings. The publish
  flow continues to request those metadata items separately.

### 6. Save a copy, review, and exit

`Save a copy` follows the existing typed export state machine and is always
available from the editor header/overflow without entering Timeline.

| Stage | Required UI |
| --- | --- |
| Preflight | source thumbnail, format, dimensions, duration, destination, profile, known adjustments, and a correction for every blocked issue |
| Queued/rendering | Cancel plus actual progress when available; otherwise a named stage without invented percent |
| Saving | target destination and recovery action if the destination fails after render |
| Complete | destination-specific result, actual output details, View/Share/Done |
| Complete with adjustment | success treatment plus what changed; never an error colour/state |
| Failed | plain cause, stable Retry/Choose destination/Free space action, preserved draft/artifact when possible |
| Cancelled | “Save cancelled. Your draft is saved.” only after persistence is confirmed |

On attempting to exit:

1. Dismiss the most local editor surface first.
2. Flush the current draft revision.
3. If it succeeds, return to the project library; do not show a misleading
   discard prompt for work that is safely saved.
4. If it fails, keep the editor open and offer **Retry** or **Keep editing**.
5. Deleting a draft is a separate, deliberate action that names the project.

## State and feedback requirements

Every editor panel implements loading, empty, content, offline/partial,
error, permission-denied, and cancelled states. Examples:

| Situation | Required response |
| --- | --- |
| No media | Mode-specific empty state with one import/start-blank action and supported media explanation |
| Permission denied | Explain the needed Photos/Media/Microphone capability and offer Settings or a safe fallback |
| Unsupported/corrupt media | Name the rejected item/reason, preserve the existing project, offer another selection |
| No local music | Explain how to import audio; do not show an empty unexplained black list |
| Renderer cannot preview exactly | Show the nearest supported preview and a concise “Export may differ” explanation with the cause |
| Undoable change | One notice with the changed item and Undo; repeated same notice does not restart its timer |
| Persistent error | Error notice remains until dismissed/recovered; it has a relevant action and never exposes private media or secrets |

Never rely on colour, thumbnail selection, a tiny handle, or a gesture alone to
communicate state or make an action possible.

## Native implementation boundaries

| Concern | Implementation owner |
| --- | --- |
| Native layout, sheets, system picker, player, gestures, focus, accessibility, permission UI | SwiftUI / Compose feature UI |
| Panel routing and transient screen state | Native editor store/view model using shared `EditorSurfaces` and `EditorNotices` |
| Tool ids/order/availability; project validation; values and bounded schemas | `shared/business-core` |
| Timeline math, clip validity, split/reorder commands, aspect/crop and look command validation | shared business contract and versioned adapter boundary |
| Decode, audio preview/mix, export, render progress | native MediaCore adapters (AVFoundation / Media3 as appropriate) |
| Durable draft/export persistence, destination I/O, cancellation/recovery | native repositories and durable job adapters |

Views emit typed user intents only. They never access a database, a native
renderer handle, a picker result, a signer, or a media uploader directly.

New shared product rules need common tests and native adapter-contract tests.
Media output changes need deterministic/timing or golden tests. A render,
export, or upload job must be durable, cancellable, idempotent, and recoverable
after process death; BitOS never signs before the media has uploaded and its
hash has been verified.

## Delivery slices and acceptance gates

This document adds no parallel editor. Build small native slices against the
existing one-shell and timeline plans.

| Slice | Scope | Acceptance gate |
| --- | --- | --- |
| EUI-01 | Shell/chrome alignment: truthful draft state; Save a copy naming; persistent undo/redo; tool routing audit | A user can identify draft save, device export, and Nostr publish without entering Timeline; failed draft save never exits or reports success. |
| EUI-02 | Look sheet consolidation and accessible adjustment controls | Filter and adjustment are reachable only through Look; every changed setting previews, applies, resets, and undoes correctly on both platforms. |
| EUI-03 | Crop sheet | Each supported ratio/crop/rotation is non-destructive, survives draft restore, exports as previewed, and is operable without pinch gestures. |
| EUI-04 | Timeline selection/actions polish | Selecting, splitting, trimming, reordering, speed, mute/volume, replacement, and deletion are unambiguous; Back returns to editor preserving context. |
| EUI-05 | Sound browser/local audio states | Local/offline/remote availability, preview, attribution, loop, and export mixing match the selected state; no false download/add states. |
| EUI-06 | Captions workflow | Manual/import/generated paths have honest states; captions remain editable and readable at large text sizes; exported timing is deterministic. |
| EUI-07 | Device QA and recovery | Run the editor portions of the product QA checklist on iOS and Android, including screen reader, large text, reduced motion, RTL, cancellation, storage full, app kill during render, and relaunch recovery. |

## Non-negotiable QA scenarios

1. Import a portrait video, choose 9:16, crop and rotate it, close/reopen, and
   export: framing matches the applied crop and no draft is lost.
2. Select clip 2 of 3, trim and split it, then undo/redo: only clip 2 changes;
   time readout and output duration remain consistent.
3. Mute a clip, add a shorter soundtrack with Loop to fill, export and replay:
   both preview and output follow the exact audio rules.
4. Apply a grade plus exposure and vignette, cancel, then Apply: Cancel changes
   nothing; Apply creates one undoable command with the expected output.
5. Turn on a screen reader and large text: header, playback, timeline, crop,
   sliders, Cancel/Apply, and every tool remain discoverable and usable.
6. Start Save a copy, cancel during render, relaunch, and retry: the project is
   intact and no successful file is duplicated.
7. Start Next, complete review, then publish: the exact rendered bytes are
   uploaded and hash-verified before any signing request.

## Related documentation

- [UX/UI flows](ux-ui-flows.md)
- [Meme editor UX/UI audit and implementation plan](meme-editor-ux-ui-audit-and-plan.md)
- [Meme Studio shell redesign](../native/meme-studio-ux-redesign-plan.md)
- [Multi-clip timeline plan](../native/meme-studio-multiclip-plan.md)
- [Meme Studio system plan](../native/meme-studio-plan.md)
- [Design system](../DESIGN_SYSTEM.md)
- [Testing strategy](../engineering/testing.md)
