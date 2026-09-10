# MemeEditor UX/UI audit and implementation plan

Date: 2026-09-05. Status: **audited; MUX-01 + MUX-02 shipped (both platforms, 2026-09-05)**.

The priority is to make finishing work predictable: preserve a draft, export one meme, then reuse that design to export many variants. Improve those paths before adding more editing tools.

## Scope and evidence

Reviewed the current working tree, including existing uncommitted work, for native iOS and Android editing, local export, batch setup, review and publishing. This is a source-based usability audit, not a simulator walkthrough or user study. Findings about rendered contrast, clipping, effective hit areas, performance and screen-reader behavior require device verification. No usability scores or measured completion times are claimed.

Implementation references (line numbers are the audit snapshot; use symbols after edits):

- [iOS editor](../../apps/ios/BitOS/Features/Create/MemeEditorView.swift): `chrome`, `runAutosave`, `statusLine`, `exportActiveAssetToPhotos`, `MemeExportState`, discard dialog.
- [Android editor](../../apps/android/src/main/kotlin/space/bitos/app/ui/create/meme/MemeEditorScreen.kt): `saveToDevice`, `ExportFullScreen`, `SuiteDock`, discard dialog.
- [iOS Studio](../../apps/ios/BitOS/Features/Create/CreateView.swift): `MassSetupView`, `MassReviewView`, `MassVariantTile`, `MassBatchFlow`.
- [Android Studio](../../apps/android/src/main/kotlin/space/bitos/app/ui/create/CreateScreen.kt): `MassSetup`, `MassReview`, `MassPublish`, `MassBatchUi`.
- [Batch contract](../../shared/business-core/src/commonMain/kotlin/space/bitos/core/studio/MassBatch.kt) and [batch rules](../../shared/business-core/src/commonMain/kotlin/space/bitos/core/studio/MassBatchRules.kt).
- Product authority: [UX flows](ux-ui-flows.md), [mass production](studio-mass-production.md). Architecture authority: [engineering architecture](../engineering/architecture.md). Existing delivery context: [Meme Studio](../native/meme-studio-plan.md), [multi-clip plan](../native/meme-studio-multiclip-plan.md).

Keep the existing strengths: native UI, undo/redo foundations, basic/expert separation, multi-clip editing, local draft storage, typed batch validation, deterministic variant rules and approval invalidation. Batch creation, CSV import, contact sheets and progress already exist; extend them.

## Findings ranked by user impact

P0 = trust or loss risk; P1 = completion blocker or substantial friction; P2 = discoverability and efficiency. Observed behavior is distinguished from a proposed design or an unverified risk.

| ID    | Priority | Evidence / observed behavior                                                                                                                                                      | User impact                                                                                                       | Improvement                                                                                                                                            |
| ----- | -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| UX-01 | P0       | Both editor discard dialogs offer Discard / Keep editing. iOS `chrome` routes every nonempty close to that dialog (around 1956); Android equivalent around 1502.                  | A creator cannot clearly leave while keeping work.                                                                | Close to the library after a confirmed draft save; if saving fails, offer Retry / Keep editing. Keep Delete draft as a separate deliberate action.     |
| UX-02 | P0       | iOS `runAutosave` starts a detached write without awaiting its result (around 2803); `chrome` then announces “Draft saved”.                                                       | Confirmation can precede persistence or conceal failure.                                                          | Await durable save acknowledgement for the current revision. Show Saving / Saved / Could not save; flush on explicit close.                            |
| UX-03 | P1       | iOS `exportActiveAssetToPhotos` sets `.failed("Saved (downscaled…)")` for a successful adjusted GIF (around 1170). `statusLine` colors failed states as errors.                   | Successful output appears broken.                                                                                 | Typed success-with-adjustments, with actual dimensions and a visible “View output” action.                                                             |
| UX-04 | P1       | Android `ExportFullScreen` infers success using `status.startsWith("Saved")` and labels other completions “Export finished” (around 5065). iOS has only idle/saving/saved/failed. | Copy controls behavior; errors lack a clear next action, and detailed progress cannot be represented.             | Typed export stages/results and stage-specific recovery; retain artifact references independently of display text.                                     |
| UX-05 | P1       | Basic toolbars label local output “Save”; iOS also has a draft-save icon; expert dock says “Export”; primary basic exit is post details.                                          | Saving a project, creating a file and posting are easily confused.                                                | Use “Save draft”, “Export”, “Post” consistently. Keep export accessible in basic mode.                                                                 |
| UX-06 | P1       | Batch review on both platforms offers “Approve all valid” and “Sign & publish”; no local bulk-output action appears in the reviewed batch surfaces.                               | Offline creators cannot finish a batch as files through this flow.                                                | Add a first-class “Export selected (N)” path, independent of identity and publishing.                                                                  |
| UX-07 | P1       | New batch asks for a master image; `MassBatch.starterProject/defaultRecipe` builds fixed name/sats/bg/img slots.                                                                  | A design already made in MemeEditor is not the visible starting point for a reusable batch.                       | Add “Make variations” from the editor and a slot-definition step over a frozen project snapshot.                                                       |
| UX-08 | P1       | `MassVariantTile` / Android `MassReview` tile taps toggle approval. Thumbnails use fill/crop in a portrait-shaped area.                                                           | Tapping to inspect can approve instead; cropped previews can hide text or composition problems.                   | Tap opens a full, aspect-fit preview. Separate explicit selection and publish-approval controls.                                                       |
| UX-09 | P1       | “Approve all valid” directly invokes approval operations on both platforms.                                                                                                       | Technical validity can be mistaken for creative review; exact scope is unclear before the action.                 | Bounded confirmation with count, exclusions, warnings and account; changes invalidate publish approval.                                                |
| UX-10 | P1       | CSV pickers call import directly; shared rules match headers to known slots. No column-mapping preview appears in setup.                                                          | Users discover mismatched columns only after import.                                                              | Preview rows, map columns, explain missing fields and confirm the import before changing the batch.                                                    |
| UX-11 | P1       | Android local export asks users to keep the screen open. Batch loops live in feature scopes; iOS preview writes and publish loops are in `MassBatchFlow`.                         | A persisted batch document alone does not demonstrate resumable, cancellable effects.                             | Audit each effect boundary and implement durable artifact jobs before promising safe background completion. Kill/relaunch behavior is unverified here. |
| UX-12 | P2       | Expert tools include many chips, “Suite”, and technical limit copy such as “SFX ≤…”.                                                                                              | First-time creators must learn product vocabulary to find ordinary actions.                                       | Basic tools: Text / Stickers / Look / Crop or Trim / More. Rename Suite to “Timeline”; show limits when adding the item.                               |
| UX-13 | P2       | Android review uses three fixed columns; iOS tiles include 9-point badges; editor status uses a fixed-height row.                                                                 | Dense labels and errors may be difficult to read at large text sizes. Effective accessibility remains unmeasured. | Adaptive grid/list, wrapping errors, scalable labels and explicit accessible alternatives to manipulation gestures.                                    |
| UX-14 | P1       | Product mass-production targets say 100 rows; `MassBatch.MAX_ROWS` permits 200.                                                                                                   | Planning, displayed limits and device capacity can disagree.                                                      | Reconcile operational and schema limits before rollout; do not silently truncate existing 200-row drafts.                                              |

## Proposed experience

### 1. Make one meme

```text
Create → Photo / Video / GIF / Template → Editor
  → Export → Output settings → Preflight → Export progress → Output ready
  → Post → Post details → Review → Existing verified publish flow
  → Make variations → Batch setup using this design
Close → Save current revision → Project library
```

Editor layout:

- Top: Back, project title and draft status, Export. Put Post and Make variations in a clearly labeled action menu; preserve direct Post entry for the existing post-first workflow where appropriate.
- Center: largest practical canvas, aspect-fit with selection boundaries. Tap text to edit; expose position, size and rotation controls as alternatives to drag/pinch.
- Bottom: one row of common tools and undo/redo. Selecting an object opens its contextual controls. The keyboard must leave both the selected text and Done reachable.
- Timeline is an optional video workspace. Show “Editing clip 2 of 3” versus “Whole video” on controls with different scopes. Closing Timeline preserves selection and playhead.
- Empty canvas: one clear import action with supported media explained. Unsupported media and capacity errors name the rejected item and retain previous work.
- Draft state is independent of export and publish state. Exporting a snapshot must not mark subsequent edits as exported.

### 2. Export one output

Use one export sheet across basic and timeline workspaces. Show thumbnail, supported format, dimensions, duration when relevant, destination and quality. Derive available profiles from existing export rules and actual encoder support; do not offer unsupported conversions. Keep advanced settings collapsed.

Default to the existing supported output profile for the media mode. Provide plain-language choices only when they map to tested profiles, such as Standard and Smaller file. Label estimates as estimates; show actual bytes after render.

Preflight identifies missing sources, storage, destination permissions and output limits with an actionable correction. Any proposed cut, downscale or loss of animation/audio is visible before acceptance. If a limit is discovered only after render, offer the adjusted result for review before saving or posting; do not mutate the editable master to fit an export.

| State                    | Visible behavior                                                                                   |
| ------------------------ | -------------------------------------------------------------------------------------------------- |
| Checking                 | “Checking media and storage…”; no invented percentage.                                             |
| Queued                   | Queue position and Cancel.                                                                         |
| Rendering                | Real progress when available; otherwise named stage and activity indicator.                        |
| Saving                   | Destination name; preserve rendered artifact if destination fails.                                 |
| Needs attention          | Plain cause and relevant action: Choose destination, Free space, Retry save, or Review adjustment. |
| Complete                 | “Saved to Photos” / “Saved to Files”, actual output details, View / Share / Done.                  |
| Complete with adjustment | Successful result plus explanation such as “Saved at a smaller size”; never styled as failure.     |
| Cancelled                | “Export cancelled. Your draft is saved.” only after draft persistence is confirmed.                |

Use determinate progress only when actual completion is known, consistent with [Apple’s progress indicator guidance](https://developer.apple.com/design/human-interface-guidelines/progress-indicators). Export cancellation and background UI must reflect actual platform guarantees, including “Will resume when you reopen” where needed.

### 3. Produce many variants

```text
Make variations / New batch
  → Design → Variable fields → Rows → Review → Export selected
                                             → Review posting → Publish approved
```

1. **Design:** use the current project snapshot or an image template. Show source name and a preview. Ship image batches first; GIF/video batches are a later renderer capability gate.
2. **Variable fields:** select text/image layers and choose “Changes for each version”. Give each a friendly name, example, required status and bounds. Existing fixed slots remain compatible.
3. **Rows:** manual rows and duplicate-row actions first. CSV import presents a sample table, explicit column-to-field mapping, missing/unknown column feedback and row count. Keep the batch untouched until import confirmation. Read files with byte bounds before parsing.
4. **Review:** aspect-fit contact sheet plus list alternative. Filters: All / Needs fixing / Ready / Exported / Failed. Tap opens full preview with Previous/Next and “Edit this version”. A row override never changes siblings. Recompute affected proof and invalidate affected approvals.
5. **Select:** explicit checkboxes; Select all ready shows count and exclusions. Selection for local export is distinct from approval to publish. Warnings require acknowledgement; blocked rows cannot render.
6. **Export:** choose supported profile and destination once. Preview names such as `campaign_001.png`; validate unsafe names and collisions, offer deterministic suffixes. Queue only the selected immutable revisions. Provide per-item status and Retry failed; never re-export successful items accidentally.
7. **Finish:** “18 saved · 2 need attention”, with Open outputs / Retry failed / Back to batch. An archive is optional later, after native folder/file export works reliably.
8. **Post separately:** show exact variants, account, captions, warnings and exclusions before bounded approval. Reuse verified upload-before-sign gates. Editing content or metadata invalidates approval.

Use simple copy in the main flow: “Design version 2” instead of “recipe v2”; “Make variations” instead of “mass produce”; “Edit this version” instead of “fork”. Technical identifiers belong in diagnostics.

## Implementation sequence

Task IDs are local to this plan and do not claim existing MST tasks are complete. Deliver each native slice on both platforms before marking it done.

| Task   | Scope / files or boundary                                                                 | Depends on                     | Acceptance gate                                                                                                                                        |
| ------ | ----------------------------------------------------------------------------------------- | ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| MUX-01 | Draft close/status in native editor stores and project repositories; UX-01/02             | —                              | Edit → close → reopen preserves newest revision and assets. Failed/cancelled save never announces success or dismisses the draft.                      |
| MUX-02 | Typed export result and artifact references in native stores; UX-03/04/05                 | —                              | Successful adjusted GIF is a success. Failure offers actionable retry. Copy changes cannot change state logic. Labels distinguish draft/export/post.   |
| MUX-03 | Native editor layout and contextual controls; UX-12/13                                    | MUX-01/02                      | Complete import → caption → export without entering Timeline. No clipped primary action at large text sizes.                                           |
| MUX-04 | Output settings, shared preflight rules, native adapter mapping                           | MUX-02                         | Preview profile equals saved profile; unsupported profiles absent; all adjustments reviewed; master unchanged by export.                               |
| MUX-05 | Durable export job contract, native persistence/scheduling/cancellation, renderer adapter | MUX-04                         | Interrupt render or save, relaunch, recover without duplicate accepted output. Retry destination save reuses completed artifact.                       |
| MUX-06 | “Make variations”, immutable design snapshot and slot bindings in shared batch contracts  | MUX-03                         | Current image design creates a batch with matching geometry/assets; recipe edit forks version; old batches load unchanged.                             |
| MUX-07 | Native CSV preview/mapping and shared bounded validation; reconcile limits                | MUX-06                         | Wrong headers, Unicode, quoted commas, missing fields and excess rows get actionable feedback before import; existing large batches remain readable.   |
| MUX-08 | Full variant preview, list/grid selection, row editor and bounded publish approval        | MUX-06                         | Inspecting never approves. Override changes only one row. Changed approved content becomes unapproved.                                                 |
| MUX-09 | Bulk local export, naming rules, output history and Retry failed                          | MUX-05/07/08                   | A signed-out user exports selected valid image variants; failures remain visible and recoverable; successful destinations are not duplicated on retry. |
| MUX-10 | Device accessibility, usability and recovery release validation                           | Each slice; final after MUX-09 | Gates below pass on both platforms; publish regressions checked.                                                                                       |

Recommended first release: MUX-01/02, followed by the focused export sheet and basic-editor changes. Bulk-production release requires MUX-05 through MUX-09; a contact sheet alone is not completion. GIF/video batch output, archives, external connectors and AI assistance are follow-up scope.

## Architecture and recovery requirements

- Keep SwiftUI/Compose views, navigation, permissions, players and scheduling native. Split the large editor/Create files into presentation, feature state, persistence and effect adapters as each slice touches them; avoid an unrelated whole-file rewrite.
- Shared BusinessCore owns deterministic profile validation, slot/row resolution, naming/collision decisions, selection eligibility and job transition rules. Reuse `MassBatchRules` and `MemeExportRules` instead of creating competing policy engines.
- MediaCore owns timeline/scene/render/audio processing. Versioned native adapters connect it to product rules. Existing UIKit/Media3/AVFoundation paths need explicit compatibility adapters and golden coverage during migration; neither core imports the other.
- Views receive state and callbacks; they do not enumerate files or invoke persistence/upload/signing. Move iOS `MassPickView` file reads into its feature-store projection when extracting the batch surface.
- Durable export records include schema version, typed job/batch/variant IDs, scope, immutable input revision/hash, profile, destination reference, phase, attempts and result artifact. Bound all records and collections. Persist permitted protected source files by reference; never embed private media bytes in job JSON or telemetry.
- Persist transitions before effects. Use stable idempotency keys and stale-revision guards; atomically finalize files where supported. Recover interrupted destination writes by reconciling the stored destination/artifact identity before retrying. OS library saves need a tested reconciliation strategy, not a claim of exactly-once delivery based on a filename.
- Cancellation stops unstarted items and cleans up or finalizes the current atomic operation safely. Preserve completed output and draft files; do not interpret cancelled as failed. Bound retry/backoff and surface terminal failures.
- Guest local export requires no signer. Publish approval binds account and reviewed content/metadata; validate it again before signing after upload hash verification. Recover partial relay success separately from local export success.
- Resolve 100-versus-200 row limits explicitly: retain decoding compatibility with 200-row documents, use a tested operational cap for new queued work, and explain chunking if needed. No silent truncation or untested cap increase.

Any schema or platform-boundary change needs an ADR under repository rules, version/migration fixtures and updates to the relevant architecture/flow documents in the same implementation change.

## Validation and release gates

### Functional and recovery

| Scenario                                           | Required result                                                                                                |
| -------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| Save fails due to full storage                     | Draft remains open; no Saved confirmation; retry can recover.                                                  |
| Close during save, then reopen                     | Latest acknowledged revision restores completely; no missing source references.                                |
| PNG, adjusted GIF, multi-clip MP4                  | Correct dimensions/duration/orientation; animation/audio retained as promised; adjustments are disclosed.      |
| Photos permission denied after render              | Offer supported alternative destination or settings; retry save without rerendering.                           |
| Edit while export runs                             | Output uses accepted snapshot; new edits remain intact and clearly separate.                                   |
| Mixed batch: 8 ready, 1 warning, 1 blocked         | Exact scope shown; warning acknowledgement explicit; blocked row excluded with correction action.              |
| Override one approved variant                      | Only affected proof/output becomes stale; publish approval invalidates correctly.                              |
| Kill during render, save, upload and relay publish | Safe recovery at each effect boundary; no duplicate accepted export or signed event; partial success retained. |
| Cancel, offline, low storage, thermal restriction  | Clear state, recoverable draft, bounded work and truthful resume behavior.                                     |
| Existing 200-row batch                             | Loads without losing rows; operational-limit handling is explicit.                                             |

Tests: pure common tests and both native adapter-contract suites for new shared rules; migration/protocol fixtures for changed schemas; deterministic/golden and timing tests for render changes; targeted native lifecycle/integration tests for save acknowledgement, interruption and destination recovery. Run existing relevant suites per [testing strategy](../engineering/testing.md); no need for tests that only assert changed label strings.

### Device UX and accessibility

Validate compact phones and tablets, portrait/landscape, keyboard visible, large text up to 200%, light/dark appearance, reduced motion, VoiceOver and TalkBack. Provide move/resize/reorder actions without precision gestures. Ensure errors remain readable and focus returns to the edited field. Announce meaningful stage changes without reading every percentage update.

Check actual interactive bounds rather than icon size. Android targets should be at least 48 dp square, including custom timeline controls; follow [Android’s accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views). Apply native iOS accessibility sizing and the repository design-system requirements during device validation. Do not treat source inspection as proof of contrast or hit-target compliance.

### Usability study and proposed targets

Run a formative study with 5 first-time creators and 3 frequent creators on representative supported devices. Tasks: create/caption/export one image; close/reopen a draft; make 10 variations; fix an invalid row; export selected variants; recover a failed save. Record assisted versus unassisted completion, wrong turns, draft/export confusion and time excluding render/import waits.

Proposed initial targets, not measured results: at least 4/5 beginners finish the single-image task unassisted within two minutes; all participants can leave and reopen a draft; at least 7/8 finish the 10-variant task without accidentally publishing; no participant loses a draft. Revise timing targets after the baseline study; zero data loss and explicit publishing intent remain release gates.

Optional telemetry records stage durations, counts, stable error codes and retry outcomes only. Never record captions, CSV cell values, unpublished media, raw authorization events or identity secrets.

## Delivery checklist

- [x] MUX-01 shipped (iOS + Android, 2026-09-05): close persists first — leave keeps the draft, failed saves offer Retry save / Keep editing / deliberate Delete draft; header draft icon shows Saving/Saved/Failed from the acknowledged write (iOS `saveDraftNow` awaits the slot write; Android autosave wraps `slotStore.save` with a runCatching acknowledgement).
- [x] MUX-02 shipped (iOS + Android, 2026-09-05): iOS `MemeExportState.savedAdjusted` (adjusted GIF = success, green styling); Android typed `ExportOutcome` (Success/SuccessAdjusted/Failure) feeds `ExportFullScreen` instead of `startsWith("Saved")` copy inference; quick tool relabeled Save → Export.
- [x] MUX-03 shipped (iOS + Android, 2026-09-05): accessible selection controls — selecting any overlay reveals a contextual row (nudge ←/→/↑/↓ · shrink/enlarge · rotate −/+15° · Edit text · Delete) as an explicit alternative to drag/pinch, every action one undoable command; vocabulary per UX-12 (Suite → Timeline in the basic bar, Effects chip → Look); iOS status line wraps instead of clipping at a fixed 16 pt (UX-13). Import → caption → export stays entirely in the basic editor (Timeline never required). **2026-09-10 follow-up:** text editing moved to an Instagram-style compose mode on both platforms — the Text tool drops an empty overlay on the canvas and the keyboard opens on a live field rendered in the overlay slot; a docked bar (font-style pills with live previews, palette dots, outline/background/shadow toggles, More, ✓ Done) sits flush above the keyboard and a slim left-edge vertical slider sets the size; the old full sheet survives as "More styles" (fx, video timing, delete) with the blank-text-on-finish removal contract intact. The toolbar↔keyboard gap found in device screenshots was fixed (Android was double-padding the IME-covered region), and the IG **background highlight** shipped end-to-end (`UpdateOverlay.bar` → export envelope → all raster paths; `caps` now defaults off on editor-added overlays for preview/export parity).
- [x] MUX-04 shipped (iOS + Android, 2026-09-05; privacy/size follow-up 2026-09-06): the Export tool now opens an output-settings sheet instead of firing blindly — it shows EXACTLY the profile the pipeline renders (the single tested profile per mode: PNG at media resolution · GIF with frame count/timing · MP4 with dims/duration), the named destination (Photos/Movies), and discloses automatic adjustments up front (GIF downscale ladder, video 64 MB trim ladder). The sheet shows the exact rendered size in MB after output exists, never a made-up estimate; recoverable artifacts use the same unit. Public output is a fresh render: iOS clears AV export metadata and Android rejects source muxer metadata except playback orientation. The last failure is surfaced in-sheet with retry. Only tested profiles appear; the editable master is untouched by export; preview profile equals saved profile by construction (the sheet derives its facts from the same rules the exporters run).
- [x] MUX-05 shipped (iOS + Android, 2026-09-05): export renders are DURABLE — persist-then-effect: the rendered artifact is written to the job ledger BEFORE the destination save; a failed save retries from that file (never re-renders); a successful save deletes it. A job stuck mid-save on relaunch becomes needsReview with "check Photos first — the save may have finished" (Photos/MediaStore saves are add-only and cannot be reconciled back — the honest strategy is asking, per the plan's reconciliation requirement). Recovered exports surface in the Export settings sheet with Retry save / Discard.
- [x] MUX-06 shipped (iOS + Android + shared, 2026-09-05): "Make variations from this design" in the Export sheet (image designs with ≥1 caption; GIF/video honestly refused with the renderer-roadmap message) renders the design poster, freezes the CURRENT project into the batch recipe via shared `MassBatch.designRecipe` (each caption becomes a `{t:<overlay id>}` placeholder + LONG_TEXT slot named after the original text; a blank row value deliberately drops that caption), and opens the hub's batch setup seeded with it. Bridge seam `massBatchFromDesign` tested in `BusinessCoreBridgeTest` (slot derivation, value substitution, blank-drop semantics, non-image/caption-less/corrupt rejections). Recipe-edit forking and old-batch loading unchanged (existing battery green).
- [x] MUX-07 shipped (iOS + Android + shared, 2026-09-05): CSV import is now analyze-then-confirm — the picker runs a DRY-RUN (`MassBatchRules.csvPreview` / bridge `massBatchCsvPreview`) and a confirmation dialog shows row count, missing required columns ("those rows stay blocked until fixed"), ignored unknown columns, and the over-cap refusal BEFORE anything imports. Limits reconciled (UX-14): a new `MAX_NEW_ROWS = 100` operational cap refuses larger imports with "split the CSV" — the previous behavior silently truncated at 200 — while schema decode keeps MAX_ROWS = 200 so existing large batches stay fully readable (pinned by test). Quoted commas + Unicode pinned in the test battery.
- [x] MUX-08 shipped (iOS + Android, 2026-09-05): inspecting never approves. Variant tiles open a FULL preview (aspect-fit poster, blocked/queued states explained, per-row notes; iOS sheet with Previous/Next + Approve/Unapprove + "Edit this version"; Android dialog with Prev/Approve/Edit/Next) — approval moved to an explicit per-tile check control (blocked rows can't approve). "Approve all valid" now asks a bounded confirmation ("Approve N valid variants? · X with warnings included · Y excluded (blocked) · changed content goes back to unapproved") before running (UX-09). Content-change → approval invalidation remains the existing hash-bound rule.
- [x] MUX-09 shipped (iOS + Android, 2026-09-05): signed-out local batch export works. Review mode gains an EXPORT SELECTION distinct from publish approval — per-tile selection checks (blocked rows can't select), "Select all ready" with the blocked-excluded count, "Export selected (N)" rendering each chosen valid variant from its resolved project (the same render path previews use) straight to Photos/Movies with NO identity required. Per-row results are tracked: successes are never re-exported (skipped on re-run), failures stay visible with "Retry failed (K)" re-running only failures, and a finish line reports "N saved to Photos ✓ / N saved · M need attention". Output names use the recipe's sanitized naming pattern via `resolveVariant().name` (Android) / platform savers (iOS).
- [ ] MUX-10: device accessibility, usability and recovery release validation — the executable runbook lives at `docs/native/mux-10-device-validation.md` (12 functional/recovery scenarios, 5 accessibility checks, 6 usability spot-tasks, results conventions + release blockers). Requires physical-device passes on the user's side; source-level gates for MUX-01–09 are green.
- [ ] New native screens reviewed on devices; accessibility and usability findings recorded.
- [ ] Draft acknowledgement and export recovery gates pass.
- [ ] Signed-out local batch export works end to end.
- [ ] Relevant architecture, UX flows, mass-production specification and native build tracker updated per shipped slice.
- [ ] No planned behavior marked as shipped without implementation and verification.
