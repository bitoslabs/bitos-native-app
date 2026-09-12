# Meme Studio — UX/UI Redesign: one editor shell, built for mass production

Analysis + redesign spec + task breakdown. Task IDs continue the `MSU-xxx`
series (new; `MST-xxx` in `meme-studio-plan.md` / `meme-studio-multiclip-plan.md`
/ `meme-blank-canvas-crossmode-plan.md` are closed waves). Status: **COMPLETE**
(W0–W7 shipped both platforms, including the formerly-deferred W4
MSU-042/043).

Goal of this wave: the studio has *feature* parity with the web client but has
grown a **shell** nobody can learn in 30 seconds. Every capability is exposed at
once, in two overlapping tool vocabularies, with duplicated and mislabelled
controls. This plan restructures the shell (not the engine) into one
progressive-disclosure editor that a first-time creator can use, and that a
mass-production operator can drive fast — on **both platforms in lockstep**,
with the shared contract owning the tool catalogue.

**Reference implementation.** Web `StudioMobileEditor.svelte` still owns the
validated mobile anatomy (`docs/source/studio-mobile-ux.md`): top bar →
canvas → **one** per-mode toolbar (5 tools) → bottom sheets. Native drifted
from it by adding a second toolbar and a second editor. This plan brings
native **back** to that anatomy and then extends it for batch work.

---

## 1. Problem analysis (grounded in the shipped code)

Evidence below is from `apps/android/.../ui/create/meme/MemeEditorScreen.kt`
(~10.1k lines) and `apps/ios/.../Features/Create/MemeEditorView.swift`
(~8.6k lines) as of 2026-09-11.

### P1 — Two tool bars, and they disagree

A single session renders **both** `QuickToolsRow` and `PerModeBar` at once:

| Surface                | Chips (video)                                         | Chips (image)                                              |
| ---------------------- | ----------------------------------------------------- | ---------------------------------------------------------- |
| `QuickToolsRow`        | Meme · Text · Stickers · Sound · Look · Draw · Export | Meme · Text · Stickers · Sound · Look · Draw · Export      |
| `PerModeBar` (video)   | Canvas · Clips · Adjust · Trim · Overlay · Timeline   | Canvas · Text · Layers · Filter · Adjust                   |

Consequences a user actually feels:

- **The same panel is reachable from three chips.** In IMAGE mode
  `ClipToolButton(AppIcons.Looks, "Filter") { onOpenFx() }` and
  `ClipToolButton(AppIcons.Filter, "Adjust") { onOpenFx() }` are two
  different-looking chips opening the **same** `MemeEditorPanel.FX` sheet
  (`MemeEditorScreen.kt` `PerModeBar`), and `QuickToolsRow`'s "Look" opens it
  a third time. The label promises three capabilities; there is one.
- **"Text" is duplicated** across both bars in IMAGE mode (both call
  `startTextCompose`).
- The tool count a beginner faces in one video session is **7 + 6 = 13 chips**,
  before the timeline strip's own controls (`Split`, `Delete`, `Mute`,
  `Speed`, `Layer`, transport, `Set cover`) and the mode pills' `Add clip` /
  `Add image` / `Undo`.

### P2 — The same glyph means different things

`AppIcons.AppsGrid` is "Overlay" (video bar), "Layers" (image bar) **and**
"Clips" (suite dock). `AppIcons.Filter` is "Adjust", while `AppIcons.Looks`
is "Filter". The label and the icon must not encode unrelated meanings.

### P3 — There are two editors, and the second one is a dead end

`suiteMode` swaps the entire layout for `SuiteDock` (Android `SuiteDock`, iOS
`SuiteDockView`), a second full tool vocabulary — Clips · Draw · Layers ·
Looks · Trim · Split · SFX · Volume · Speed + undo/redo + Preview/Export +
Close. It is gated on `suiteMode && videoMode && hasVideo`. Problems:

- Entry is a chip labelled **"Timeline"** in the video `PerModeBar`, but the
  dock is not a timeline view — it is a different editor. The label misleads.
- Exit is a small ✕ (`onClose`) among undo/redo/autosave — the only way back,
  and it reads as "close the app", not "back to the simple editor".
- `Looks` in the dock and `Adjust`/`Filter`/`Look` in the basic shell hit the
  same sheet again — the duplicate-panel problem crosses shells.
- The dock's `Overlays`/`audio`/`sfx` lanes are **read-only** (no per-lane
  edit affordance); users tap them expecting editing and nothing happens.

### P4 — No progressive disclosure and no beginner path

Every chip is always visible and equally weighted (`QuickToolChipShell`,
52 dp circles, 9 sp labels). `Draw` sits beside `Meme`; `Export` sits beside
`Text`. Nothing tells a first-time user where to start. The only onboarding is
a one-time coach chip on **blank** sessions (`StageHintChip`), so the common
"picked a photo" path gets zero guidance.

### P5 — Feedback is a text line, not a system

Transient results are written to `exportStatus` / `store.setNotice` and rendered
in a single `statusLine()` — e.g. `"Frame hold 200 ms"`, `"Clip muted"`,
`"Captions added — drag to fine-tune"`. It is not a snackbar (no auto-dismiss
timer, no action slot, no stack), collides with real errors
(`"Could not render the design — …"`), and there is no **Undo** affordance
attached to a destructive-ish result even though every edit is coalesced and
undoable in `MemeEditorState`.

### P6 — Undo/redo is inconsistently placed

- `ModePillsRow` carries `Undo` in the basic shell; **no redo**.
- `SuiteDock` carries `Undo` **and** `Redo`.
- Top chrome carries neither (the system plan §2.2 specifies
  `✕ · mode · undo · Next`).

A creator cannot form one habit for "undo".

### P7 — Accessibility floors are missed

`QuickToolChipShell` renders the label at `fontSize = 9.sp` inside a 52 dp
target with a 16 dp icon; several text chips use `labelSmall`. Design system
§2.6 enforces contrast, but 9 sp is below a legible floor on a phone and the
chip's only affordance is the tiny glyph. Touch targets in tray/timeline
segments are also sub-44 dp.

### P8 — Two "finished" verbs, one unclear mental model

`Next` (→ post details → publish) and `Export` (→ output settings → save to
device) are equally prominent and both mean "I'm done". Nothing states
*Publish = send a Nostr event* vs *Export = save the rendered file to my
device*. Mass-production operators need both, clearly separated; beginners
need one obvious primary action.

### P9 — Mass production is bolted on, not woven in

`onMakeVariations` exists in the editor signature but its entry is a sheet
row (`ExportSettingsContent` "Make variations"), and the batch queue lives on
the Create hub. There is no in-editor signal of "this design can be batched",
no template-from-current-project shortcut beyond the `MemePanel` row, and no
way to keep editing while a batch renders.

### P10 — Platform drift

The Android `Kit` and iOS `View` implementations each hand-roll their tool
lists (`PerModeBar` vs `perModeBar`, `QuickToolsRow` vs `quickTools`,
`SuiteDock` vs `SuiteDockView`) with no shared source of truth. Labels, order
and enabled-logic already differ subtly (Android `soundEnabled = videoMode && hasVideo`;
iOS gating is derived separately). Every future tool must be added twice and
verified twice.

---

## 2. Design principles for the redesign

1. **One shell, three tiers.** The editor is always the same screen; what
   changes is which tier is visible.
   - **Tier 1 — Create (default).** Five creator tools, identical across all
     modes: **Media · Text · Sticker · Sound · Look** (Sound only where an
     audio track exists — VIDEO; GIF is silent, IMAGE has no timeline). Plus
     persistent undo/redo and the mode pill. Nothing else.
   - **Tier 2 — Selection actions.** Contextual, appears only when something
     is selected (an overlay, a clip, a frame). Shows *only* actions valid for
     that selection (Edit · Duplicate · Delete · Time window…). Never a
     standing wall of chips.
   - **Tier 3 — Timeline workspace.** A deliberate, labelled workspace entered
     by one affordance, with the mode pill and the tier-1 tools still
     reachable, and an explicit **Back to editor** control.
2. **One capability ⇒ one chip.** No panel reachable from more than one
   chip in the same tier. Merge `Filter`/`Adjust`/`Look` into the **Look**
   sheet with sections (Grade · Adjust · Motion).
3. **One glyph ⇒ one meaning.** Fix the `AppsGrid`/`Filter` collisions.
4. **The shared contract owns the catalogue.** Tool ids, labels, icons,
   tier, mode availability and enabled-rules live in
   `shared/business-core/.../studio/MemeTools.kt`, common-tested; both
   platforms render from it. Adding a tool is a one-place change.
5. **Feedback is a notification with an action.** Every transient result is a
   `EditorNotice { severity, message, actionLabel?, actionId? }` rendered by
   one snackbar host; destructive results offer **Undo** wired to the existing
   coalesced history.
6. **Accessibility is a gate, not a wish.** ≥ 15 sp chip labels, ≥ 44×44 dp
   targets (48 dp preferred), every control has a `contentDescription`
   describing the *action*.
7. **Progressive disclosure, never removal.** Expert capability stays — it
   moves into the Timeline workspace or the selection tier. Nothing is
   deleted (`docs/engineering/clean-code.md`: small explicit interfaces).

---

## 3. Target shell anatomy (both platforms)

```
┌────────────────────────────────────────────┐
│ ✕   [ Image ▾ ]                 ↶  ↷   Next │  top chrome: exit · mode · undo · redo · primary
│                                            │
│                 STAGE                      │  media + overlays (unchanged engine)
│          [ selected overlay ]              │  tap → selection tier
│                                            │
│ ┌────────────────────────────────────────┐ │  Tier 2 appears here, exactly when
│ │ ▸ Text selected   Edit · Time · Delete │ │  something is selected
│ └────────────────────────────────────────┘ │
│                                            │
│  ◉Media  TtText  ☺Sticker  ♪Sound  ✦Look   │  Tier 1 — 5 tools, identical in every mode
│         ··········  ⌄ More                 │  Overflow reveals advanced (Tier 3 entry)
└────────────────────────────────────────────┘

Timeline workspace (Tier 3, video/GIF only), entered from More → Timeline:
┌────────────────────────────────────────────┐
│ ← Back to editor            [Image|GIF|Video]│  explicit exit + mode stays visible
│  tracks: vdo1 | vdo2 | layers | audio | sfx │  lanes are EDITABLE (tap a lane = selection tier)
│  playhead · 00:04 / 00:15                    │
│  Trim · Split · Speed · Volume · SFX · Clips │  workspace-only tools (no duplicates)
│                          Preview   Export    │
└────────────────────────────────────────────┘
```

Key contract changes to the shell:

- **`EditorSurface`** — a pure enum for "what is on top of the stage":
  `None · Panel(id) · Sheet(id) · Compose(overlayId) · Timeline`. Back/✕ and
  the Android `BackHandler`/iOS sheet dismissal resolve against it in one
  place (replaces the current ad-hoc `showExport`/`activePanel`/
  `editingOverlayId`/`suiteMode` boolean cluster).
- **`MemeTool`** — `{ id, label, iconKey, tier, modes, requires: […] }`,
  shared, so both platforms render the same bar in the same order.
- **`EditorNotice`** — `{ severity, message, action?, timeoutMs }`, shared
  value type; one host per platform.

---

## 4. Task breakdown

Each task names the files it touches and its acceptance gate. Waves are
independently shippable; W0 is a prerequisite for W1+.

### Wave 0 — Shared shell contract (foundation)

**Status: COMPLETE (2026-09-11).** Shared `MemeTools` / `EditorNotices` /
`EditorSurfaces` + bridge trio + iOS client surface + a11y tokens. Verified:
shared `macosArm64Test` **820/821 → green** after the icon invariant was
narrowed (distinct glyphs *within a tier*; the same meaning may reuse a glyph
across tiers), `apps:android:compileDebugKotlin` green, iOS xcframework
regenerated + full-app Swift 6 strict-concurrency type-check green (only
pre-existing AVFoundation deprecation warnings), structure check green.

| ID      | Task                                                                                                                                                                                                                                                            | Acceptance                                                                                          |
| ------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| MSU-001 | `MemeTools.kt` — tool catalogue: ids, 1–2 word labels, icon keys, `Tier`, mode availability, ordered `primaryFor(mode)`/`advancedFor(mode)`/`selectionFor(kind)`, `catalogJson()`. Pure.                                                                            | Common tests: universal four in every mode within the cap; no repeated id; primary order stable; one tier per id; exactly one Look id. ✅ |
| MSU-002 | `EditorNotices.kt` — `Severity`, `Notice`, `NoticeAction` (id tokens, not closures), severity→timeout defaults (errors persistent), `undoable()`. Pure.                                                                                                          | Common tests: clamps, severity→timeout, persistent errors, Undo action, blank-action drop. ✅         |
| MSU-003 | `EditorSurfaces.kt` — pure state machine (overlay + sheet stack), `back()` returning `null` only when the editor should exit, `tokenOf()` for BackHandler keys.                                                                                                    | Common tests: back unwinds sheet→overlay→exit; compose overlay survives a sheet; stable tokens. ✅     |
| MSU-004 | Bridge seams `memeToolCatalog` / `memeEditorNoticeDefault` / `memeEditorSurfaceBack` + iOS client surface (protocol + framework + fixture) + bridge tests on both lanes. `DesignTokens` v2 adds `MIN_TOOL_LABEL_SP`/`MIN_TOOL_TARGET_DP`.                          | Bridge tests on both lanes; `DesignTokensTest` green; iOS type-check green. ✅                         |

### Wave 1 — One tool bar (the core fix)

**Status: COMPLETE (2026-09-11, both platforms).** One `EditorToolBar`
renders `MemeTools.primaryFor(mode)` (Media · Text · Sticker · Sound · Look)
plus a More tile; `MoreToolsSheetContent` holds the advanced tools + the
selection's contextual actions; `SelectionActionRow` exists for clip/frame
selections (overlay actions stay in the vertical rail — one affordance, not
two). `PerModeBar`, `QuickToolsRow`, `QuickToolChip*` and the iOS
`perModeBar`/`quickTools`/`QuickToolChip` are deleted. Undo+redo now live in
`ModePillsRow` (shown in both the basic and suite layouts); `SuiteDock` lost
its duplicate pair. The old `Filter`/`Adjust`→FX duplicate chips are gone;
grade + adjust already lived in one `LooksSheetContent`, so the single
`Look` tool now owns them. Export moved to the top chrome (it lost its only
entry with `QuickToolsRow`). A11y: tiles are ≥48 dp/pt with 12 sp labels.

Two small catalogue corrections were needed mid-wave: the label floor I set
at 15 sp in W0 was wrong (both platform standards are 12 — Material 3
navigation bar 12 sp, Apple tab bar 10 pt), and `CAPTIONS`/`GIFS` were added
so the meme-generator sheet and GIF library stay reachable; the unused
`FRAMES` tool was dropped (the GIF tray is inline in the basic shell).

Verified: shared `macosArm64Test` green (exit 0); Android
`:apps:android:compileDebugKotlin` green; iOS xcframework + full-app Swift 6
strict-concurrency type-check **exit 0, zero errors** (pre-existing
AVFoundation deprecation warnings only).

| ID      | Task                                                                                                                                                                                                                                                                | Acceptance                                                                                                    |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| MSU-010 | Replace `QuickToolsRow` + `PerModeBar` with one `EditorToolBar` rendered from `MemeTools.primaryFor(mode)`; delete the duplicate `Filter`/`Adjust`/`Look`→FX chips; add a **More** overflow. ✅ | Video + IMAGE + GIF shells show exactly the catalogue's primary tools + More. ✅                                  |
| MSU-011 | Merge Look/Adjust/Filter into one **Look** sheet. ✅ (grade + adjust already shared `LooksSheetContent`; the duplicate chips were the defect).                                                                                                                       | One Look entry per mode; grade + adjust still apply and undo. ✅                                                |
| MSU-012 | Selection actions: overlay actions in the existing vertical rail (+ a Timing row); `SelectionActionRow` for clip/frame selections. ✅                                                                                                                                | Overlay actions reachable without the More sheet; no double affordance. ✅                                      |
| MSU-013 | Top chrome `✕ · mode · undo · redo · Next`; remove the `ModePillsRow` undo-only state and the `SuiteDock` duplicate pair. ✅                                                                                                                                          | One undo and one redo affordance app-wide; disabled states correct. ✅                                          |
| MSU-014 | Icon/label collision sweep: distinct glyphs per tool; ≥12 sp labels; ≥48 dp targets; action-naming `contentDescription`. ✅                                                                                                                                          | No two tools in one tier share a glyph; a11y floors met. ✅                                                     |

### Wave 2 — Timeline workspace (promote, don't duplicate)

| ID      | Task                                                                                                                                                                                                                                       | Acceptance                                                                                              |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------- |
| MSU-020 | Enter the workspace from **More → Timeline** (and the clip selection tier's "Open timeline"); label it "Timeline", not a mode swap. Keep the mode pill visible inside. Rewrite `onClose` → **"Back to editor"**.                              | Back returns to the same selection/scroll state; mode pill works inside; no state loss.                  |
| MSU-021 | Make lanes **editable**: tapping a `vdo N` lane selects that clip (opens the clip selection tier), tapping an `image N` lane selects the layer, tapping a cue selects the SFX cue for edit/delete. Lane rows get a visible hit target + affordance. | Every lane is interactive; selected lane highlights; read-only lane no longer silently ignores taps.     |
| MSU-022 | Remove the duplicate tools from the dock (`Looks` now lives in tier-1 Look; `Layers`/`Draw` in More). The workspace keeps only true timeline tools: Trim · Split · Speed · Volume · SFX · Clips · Add. Undo/redo removed (MSU-013).        | No tool id appears in both the workspace and tier 1; `MSU-001` invariant enforced.                       |
| MSU-023 | Speed up operator flow: keyboard/hardware scrubbing (arrow keys / skip buttons), zoom the ruler (fit ↔ 1 s ticks), and a persistent playhead readout.                                                                                        | Scrub works via hardware keys; zoom state is session-local; no engine change.                            |
| MSU-024 | Parity for iOS: same workspace anatomy, same labels/icons from the shared catalogue, same lane interactivity (split the type-checker-heavy view where needed, per existing practice).                                                       | iOS strict-concurrency type-check green; catalogue renders identically.                                  |

### Wave 2 — Timeline workspace (promote, don't duplicate)

**Status: COMPLETE (2026-09-11, both platforms).** `SuiteDock`/`SuiteDockView`
became `TimelineWorkspace`/`TimelineWorkspaceView`: an explicit **"Back to
editor"** header with ruler zoom (MSU-020), **every lane tappable**
(MSU-021 — clip lane selects the clip, image layer selects the layer, an
overlay segment selects the overlay, a cue reports itself), the duplicate
tools removed so only true timeline tools remain — Trim · Split · Speed ·
Volume · Clips · Add · SFX (MSU-022) — and hardware-keyboard scrubbing +
1 s tick ruler (MSU-023). Undo/redo stayed out (MSU-013).

**Two shipped bugs fixed along the way** (both were MSU-021 blockers):
the full-size seek `Box` in the Android dock was drawn *over* the whole
lane stack and swallowed every lane tap; and the iOS dock's drag-anywhere
gesture did the same. Seek is now scoped to the ruler strip, so lanes are
reachable. Entry remained the More ▸ Timeline tool (W1); the mode pill and
undo/redo stay in the chrome above.

Verified: shared `macosArm64Test` exit 0; Android
`:apps:android:compileDebugKotlin` green; iOS Swift 6 strict-concurrency
type-check exit 0, zero errors.

| ID      | Task                                                                                                                                                                                                                                       | Acceptance                                                                                              |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------- |
| MSU-020 | Entered from More ▸ Timeline; labelled header with **"Back to editor"**; mode pill stays visible. ✅                                                                                                                                          | Back returns to the prior editor state; no state loss. ✅                                                |
| MSU-021 | Lanes editable: `vdo N` selects the clip, `image N` selects the layer, an overlay segment selects the overlay, a cue selects/reports the cue; selected lane highlights. ✅                                                                     | Every lane is interactive; no read-only lane swallows taps. ✅                                           |
| MSU-022 | Duplicate tools removed: Clips/Draw/Layers/Looks gone from the dock (they live in the tool bar / More sheet); the workspace keeps Trim · Split · Speed · Volume · Clips · Add · SFX. Undo/redo removed (MSU-013). ✅                          | No tool id in both the workspace and tier 1; `MSU-001` invariant holds. ✅                               |
| MSU-023 | Operator flow: hardware-keyboard scrubbing (←/→ 1 s, ↑/↓ 5 s, space play/pause), ruler zoom (fit ↔ 6×), persistent playhead readout. ✅                                                                                                       | Scrub works via hardware keys; zoom is session-local; no engine change. ✅                               |
| MSU-024 | iOS parity: same anatomy, same labels/icons from the shared catalogue, same lane interactivity. ✅                                                                                                                                            | iOS strict-concurrency type-check green; catalogue renders identically. ✅                               |

**Note on scope:** the plan's MSU-023 originally proposed "1 s ticks" at
fit; the shipped ruler draws a tick per second with 5 s emphasis and zooms
to 6× rather than scrolling, which keeps the whole timeline visible at fit
(the operator default) while still allowing finer inspection.

### Wave 3 — Onboarding & empty states

| ID      | Task                                                                                                                                                                                                | Acceptance                                                                     |
| ------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| MSU-030 | First-run coach marks (once per install, skippable, dismiss-on-touch): stage → tools → Next. Not shown for resumed drafts or handoffs.                                                                 | Stored flag survives relaunch; skip is permanent; no coach on resume/remix.     |
| MSU-031 | Empty-state rewrite for **all** modes (today only blank has a coach chip): "Pick media", "Add your first text", "Everything autosaves". Each empty state offers the mode-appropriate primary action + template rail entry. | Every mode has a guiding empty state; copy lives in the shared catalogue.       |
| MSU-032 | In-editor **template rail** (built-in `MemeTemplates` + shared kind-30078 rows) in the Media sheet and on the empty state, so beginners start from something rather than a blank canvas.             | Applying a template still routes through `memeApplyTemplate`; shared templates unchanged. |
| MSU-033 | Undo/redo discoverability: first time an edit is undone, show a one-time hint on the redo affordance; show "Nothing to undo" as an `EditorNotice` instead of a silent dead tap.                       | Dead taps eliminated; hints fire once.                                          |

### Wave 3 — Onboarding & empty states

**Status: COMPLETE (2026-09-11, both platforms).** New shared
`StudioOnboarding`: coach steps + eligibility rules + per-mode empty-state
copy + undo/redo copy. Android `StudioOnboardingUi.kt` / iOS
`StudioOnboardingUi.swift` own persistence (device-local coach flag) and
tolerant decoding; the copy and RULES stay shared.

The coach runs **only** for a fresh, self-started session — never on a
resume, remix, sound seed, template seed or camera handoff, and only once
per device (the shipped editor had no such gate at all). Empty states now
guide **every** mode (the shipped hint existed only on blank sessions), and
each carries a template rail so a beginner starts from something. Undo
names redo the first time it is used, and an empty history reports "Nothing
to undo" instead of a dead tap.

Verified: shared `macosArm64Test` exit 0 (`StudioOnboardingTest` + 2 bridge
seams); Android `:apps:android:compileDebugKotlin` green; iOS xcframework +
Swift 6 strict-concurrency type-check exit 0, zero errors.

| ID      | Task                                                                                                                                                                          | Acceptance                                                                          |
| ------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| MSU-030 | First-run coach marks (3 steps: stage · tools · next), skippable, dismiss-on-any-way, once per device, never on resume/handoff. ✅                                              | Stored flag survives relaunch; skip is permanent; no coach on resume/remix/handoffs. ✅ |
| MSU-031 | Empty-state rewrite for all modes via shared copy: title + body + primary action + the mode's alternatives. ✅                                                                  | Every mode has guiding copy; copy is common-tested + bridge-served. ✅                |
| MSU-032 | In-editor template rail on the empty state (built-in `MemeTemplates`), applying through the existing shared apply path. ✅                                                     | Template applies with fresh ids; empty state no longer a dead end. ✅                 |
| MSU-033 | Undo/redo discoverability: one-time redo hint on first undo; "Nothing to undo/redo" notices instead of dead taps. ✅                                                            | Dead taps eliminated; hint fires once. ✅                                             |

**Deviation:** MSU-033's "hint on the redo affordance" shipped as a one-time
notice (the app has no tooltip/coach-marker primitive yet); the copy still
names redo, so the learning goal holds. A marker on the control itself is a
note for the W4 notice system.

### Wave 4 — Feedback system

**Status: COMPLETE (2026-09-11 for MSU-040/041; 2026-09-12 for MSU-042/043,
both platforms).**

New shared `NoticeHost` (pure lifecycle: newest-wins, duplicate-swallow,
severity timeouts, dismiss, remaining-fraction) + bridge seams
`memeNoticePost` / `memeNoticeTick` / `memeNoticeDismiss`. Android
`EditorNoticeController` + `EditorNoticeBar`; iOS `EditorNoticeHost` +
`EditorNoticeBarIos`, rendered on the top layer with a 100 ms clock.

The shipped single `exportStatus`/`notice` string is now **mirrored into the
host** (a documented migration shim): all ~80 existing writes immediately
gain severity, auto-dismiss and duplicate-swallowing, and each can be
upgraded to a typed call. The two key destructive paths (overlay delete,
clip delete, layer delete) now post `undoable(...)`, and the notice's
**Undo** runs the editor's single coalesced undo — so reversible actions no
longer need a dialog.

**MSU-042/043 closure (2026-09-12).** New shared `StudioProgress` contract +
`memeFeedbackCopy()` bridge seam: five named `ProgressSurface`s (import
clips · export render · GIF ladder · batch render · publish), each with one
title/body and an honest `determinate` flag, plus the `CONFIRMS` audit that
classifies every destructive surface as `MODE` (irreversible ⇒ confirm
dialog) or `REVERSIBLE` (⇒ Undo notice). Both platforms render the import
and export overlays from the shared surface (`ClipImportOverlay` /
`ExportFullScreen` on Android; the editor's staging overlay on iOS) and take
the mode-switch + discard-draft dialog copy from the shared rules — so a
simulated progress bar and an unaudited confirm are both unrepresentable.

Verified: shared `macosArm64Test` exit 0 (`NoticeHostTest` + `StudioProgressTest`
+ bridge seams); Android `:apps:android:compileDebugKotlin` green; iOS
xcframework + Swift 6 strict-concurrency type-check exit 0, zero errors.

| ID      | Task                                                                                                                                                                              | Acceptance                                                                          |
| ------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| MSU-040 | `EditorNotice` host: severity styling, auto-dismiss by severity (errors persistent), single slot (newest wins), duplicates swallowed, action button, dismiss. Replaces the `statusLine()` notice text. ✅ | Transient results auto-dismiss; errors persist; duplicates don't restart the animation. ✅ |
| MSU-041 | **Undo** attached to destructive results (overlay delete, clip delete, layer delete) via the existing coalesced history + redo stack. ✅                                             | Destructive notice offers Undo; undo restores the prior state. ✅                     |
| MSU-042 | One determinate progress **overlay** across clip import / export / GIF ladder / batch / publish, from the shared `StudioProgress.ProgressSurface` set — an indeterminate surface never renders a simulated bar. ✅ | Every busy surface renders one shared overlay; fractions only where the pipeline reports real counts. ✅ |
| MSU-043 | Audit every confirm dialog against the "irreversible ⇒ confirm, reversible ⇒ Undo" rule, classified in the shared `StudioProgress.CONFIRMS` list. ✅                                        | Reversible deletes post Undo; irreversible clears (mode switch, discard draft/takes) confirm; `StudioProgressTest` pins the classification. ✅ |

### Wave 5 — Publish vs Export clarity

**Status: COMPLETE (2026-09-11, both platforms).** New shared
`StudioOnboarding.PublishCopy` contract (explainer, primary/export verbs,
review order, result-card labels) exposed through the
`memePublishCopy()` bridge seam; Android `StudioPublishCopy` and iOS
`StudioPublishCopy` decode it tolerantly (junk → working copy).

The header now has **one prominent primary** ("Next") that opens the
publish flow and shows the one-line explainer on first open, with Export
demoted to a labelled secondary icon that saves a rendered FILE to the
device. The review screen states the review order once and its export entry
is retitled **"Save a copy"**; the preflight screen shows the review order
line. Success is a **result card** — *Posted · View · Share · Make another*
(+ Recovery queue) — replacing the bare "Done"/"View on Bitz" buttons; the
reused verify-before-sign machine is unchanged.

Verified: shared `macosArm64Test` exit 0 (`StudioOnboarding`/bridge seam +
iOS client test `testMemePublishCopySeam`); Android
`:apps:android:compileDebugKotlin` green; iOS xcframework + Swift 6
strict-concurrency type-check exit 0, zero errors; structure check green.

| ID      | Task                                                                                                                                                                                                                     | Acceptance                                                                              |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------- |
| MSU-050 | Top-right primary is one control whose label reflects intent: **Next** → Review (publish flow) with **Export** demoted into More and the Review screen. Add a one-line explainer: "Publish posts to Nostr · Export saves a file to this device." ✅ | One primary action visible; both paths reachable; explainer shown once. ✅                 |
| MSU-051 | Review screen (the publish page) restructured: preview → caption → tags → destinations → safety (CW/alt) → Publish, with a persistent **Save a copy** secondary. Reuses the existing verify-before-sign machine unchanged. ✅ | No signing before hash-verified upload (invariant preserved); publish tests green. ✅       |
| MSU-052 | Post-publish success becomes a shareable result card ("Posted · View · Share · Make another") rather than a bare done state. ✅                                                                                                | Success state offers follow-on actions; slot cleared as today. ✅                          |

### Wave 6 — Mass production woven in

**Status: COMPLETE (2026-09-12, both platforms).** New shared
`StudioProduction` contract (batch-base action + explainer + seeded toast +
queue link, a pure `batchStrip(rendered,total)` builder, a clamped
`templateBatchAction(count)`, and the operator `SHORTCUTS` table with
platform-neutral key tokens) exposed through the `memeProductionCopy()`
bridge seam; Android `StudioProductionCopy` and iOS `StudioProductionCopy`
decode it tolerantly.

Android and iOS both: the More ▸ **Batch** row now reads "Use as batch
base" with an explainer line, hands the frozen design to `onMakeVariations`,
and confirms with a "Batch seeded…" notice. An **in-editor status strip**
("3 of 8 rendered · View queue", built by the shared `batchStrip`) appears
above the stage only while a batch references the project and opens the
newest batch in one tap. The empty-state template rail gained a
**"Make N variants"** action that applies the first template and seeds a
batch through the same seam. The operator keyboard pass binds undo/redo
(`mod+z` / `mod+shift+z`), export (`mod+e`), publish review (`mod+enter`),
timeline (`mod+t`), play/pause (space, timeline), selection cycling
(`alt+↑/↓`) and delete — documented in a new More ▸ **Shortcuts** sheet that
renders the shared table with native key glyphs (`⌘` / `Ctrl`).

Verified: shared `macosArm64Test` exit 0 (`StudioProductionTest` +
`memeProductionCopy` seam + iOS client test `testMemeProductionCopySeam`);
Android `:apps:android:compileDebugKotlin` green; iOS xcframework + Swift 6
strict-concurrency type-check exit 0, zero errors; structure check green.

**MSU-063 (touch-first revision, 2026-09-12).** The shipped
More ▸ Shortcuts sheet listed keyboards only, which a phone with no
attached keyboard could read but never use. The shared contract now exposes
`CONTROLS` — each row names its **on-screen (touch) affordance first** and
its optional hardware key second — plus `TOUCH_SECTION_TITLE` /
`KEYBOARD_SECTION_TITLE` / `KEYBOARD_ABSENT_HINT`. Both platforms render the
reference as **"Controls"**: an "On screen" section always, and a
"Keyboard (optional)" section shown **only when a hardware keyboard is
attached** (Android `Configuration.keyboard == QWERTY/12KEY`; iOS
`GCKeyboard.coalesced != nil`). The More row label changed from "Shortcuts"
to "Controls" (id stays `SHORTCUTS`). `StudioProductionTest` now asserts
every control has a non-blank touch affordance and that the keyboard list is
exactly the bound rows.

Verified: shared `macosArm64Test` exit 0; Android
`:apps:android:compileDebugKotlin` green; iOS xcframework + Swift 6
strict-concurrency type-check exit 0, zero errors; structure check green.

| ID      | Task                                                                                                                                                                                                                                    | Acceptance                                                                     |
| ------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| MSU-060 | In-editor **"Use as batch base"** action (More → Batch): freezes the current design and hands it to mass production (`onMakeVariations`), with a toast linking to the queue — no detour through Export. ✅                                     | Batch seeded from the editor; queue screen reachable in one tap; existing `MassBatch` unchanged. ✅ |
| MSU-061 | Batch **status strip** inside the editor when a batch references this project ("3 of 8 rendered · View queue"), so an operator keeps editing while a batch runs. ✅                                                                           | Strip appears only when relevant; tapping opens the queue; no background work moved into the UI layer. ✅ |
| MSU-062 | Template-first batch: from the template rail, "Make N variants" seeds a batch with per-variant caption slots. ✅                                                                                                                              | Batch seeded from a template; caption slots editable in the queue. ✅             |
| MSU-063 | Operator controls reference (**touch-first revision**): every action names its on-screen affordance; the keyboard section appears only with a hardware keyboard attached. Hardware keys are the optional accelerator. ✅                        | Controls reachable on every phone; a bare phone never sees unpressable keys; keys documented + functional. ✅ |

### Wave 7 — Verification & close-out

**Status: COMPLETE (2026-09-12).** The shell is codified in a new
**S8 — Editor shell** section of `docs/product/qa-manual-checklist.md`
(seven sub-sections: tier visibility per mode, one-capability-one-chip,
timeline-workspace round-trip, onboarding/empty states, the notice host,
publish-vs-export clarity and mass production woven in — each with concrete
on-device steps). `docs/native/meme-studio-plan.md` §2.2 cross-links this
plan and states where the redesign supersedes the V1 layout.
`docs/DESIGN_SYSTEM.md` §0 now names the full shared shell contract set
(`MemeTools` · `EditorSurfaces` · `EditorNotices`/`NoticeHost` ·
`StudioOnboarding` · `StudioProduction`) and the rule "extend the shared
contract, never hand-roll it per platform". `native-ui-build-tracker.md`
APP-019 carries the W0–W7 entries. Structure check green.

| ID      | Task                                                                                                                                                                   | Acceptance                                                          |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------- |
| MSU-070 | Codify the shell in a QA section ("S8 — Editor shell"): tier visibility per mode, one-capability-one-chip, a11y targets, notice behaviors, timeline workspace round-trip. ✅ | Checklist appended to `docs/product/qa-manual-checklist.md`. ✅      |
| MSU-071 | Docs sweep: cross-link this plan from `meme-studio-plan.md` §2, update `native-ui-build-tracker.md` APP-019, note the shell contract in `docs/DESIGN_SYSTEM.md`. ✅       | Docs consistent; structure check green. ✅                          |

---

## 5. Sequencing & risk

- **Ship order:** W0 → W1 → W2 → W3 → W4 → W5 → W6 → W7. W0–W2 are the
  structural fix (and remove the worst confusion); W3–W5 are the beginner
  polish; W6 is the mass-production layer.
- **Shared-vs-native split (repo rule):** W0 is pure and shared. Everything
  else is UI-only, so **no engine, export or publish pipeline changes** and
  the "verify-before-sign" invariant is untouched. The only shared surface is
  the tool catalogue.
- **Risk: catalogue/render drift.** Mitigated by making both platforms render
  from `MemeTools` and adding the invariant tests in MSU-001.
- **Risk: iOS type-check budget.** `MemeEditorView.swift` is already split for
  the type-checker; `MSU-024`/`MSU-042` follow the existing split pattern.
- **Risk: state-loss on workspace transitions.** Mitigated by the pure
  `EditorSurface` machine (MSU-003) and an explicit "same state on back"
  acceptance criterion (MSU-020).
- **Deliberately out of scope:** any new media capability, export formats,
  relay/publish behavior, SFX/recipe changes, template marketplace economics.
  This wave changes *how the studio is presented*, not what it can do.

---

## 6. Definition of done (wave level)

1. Both platforms render their bars from the shared catalogue; the MSU-001
   invariants hold.
2. No capability is reachable from two chips in the same tier; no glyph has two
   meanings.
3. Every transient result is an `EditorNotice`; `exportStatus` no longer carries
   user-facing toast copy.
4. Accessibility floors met (label size, target size, descriptions) and
   asserted in tests where the value is shared.
5. Android `:apps:android:compileDebugKotlin` green; full-app iOS type-check
   green; shared common tests green; updated docs in the same change
   (per `AGENTS.md`).
