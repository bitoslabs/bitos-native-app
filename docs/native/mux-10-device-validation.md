# MUX-10 device validation runbook

Date prepared: 2026-09-05. Status: **ready to execute — requires physical devices**.

This is the executable form of the validation gates in
[`docs/product/meme-editor-ux-ui-audit-and-plan.md`](../product/meme-editor-ux-ui-audit-and-plan.md)
§ "Validation and release gates". Everything it exercises shipped in
MUX-01…09 (see the audit's delivery checklist). Source-level gates are
green; this runbook is what converts "implemented" into "validated".

## Preconditions

- Fresh build of both apps from the current tree installed on real devices:
  - iOS: one compact phone (e.g. SE/minis class) and one tablet or large phone.
  - Android: one compact phone and one tablet; TalkBack available.
- A test identity imported (Profile tab) for publish scenarios; ALSO run the
  export scenarios signed out (they must not require an identity).
- Network control reachable (airplane mode) for failure scenarios.
- Camera roll with: 1 portrait image, 1 landscape image, 1 GIF, 1 video ≥30 s.
- Recording method for evidence: screen recording per scenario, note the
  device + OS version + text size setting for each session.

## A. Functional and recovery scenarios

Mark each **Pass / Fail / Partial** with a one-line note. A Fail on any
zero-data-loss row (A1, A2, A7) is a release blocker.

| # | Scenario | Steps | Required result | Exercises |
| --- | --- | --- | --- | --- |
| A1 | Save fails (full storage) | Fill storage near capacity → edit → tap ✕ | Draft stays open; NO "saved" claim; dialog offers Retry save / Keep editing / Delete draft | MUX-01 |
| A2 | Close during save, reopen | Edit → ✕ mid-save-acknowledgement → kill app → reopen Create | Latest acknowledged revision restores completely; no missing source references | MUX-01 |
| A3 | Adjusted GIF export | Make a GIF large enough to downscale → Export sheet → Export | Reads "Saved at a smaller size (downscaled ×N)" as SUCCESS (green); dims correct; animation retained | MUX-02/04 |
| A4 | Multi-clip MP4 export | 3-clip timeline > 64 MB (or cap note visible) → Export | Disclosure of trim ladder up front; output correct dims/duration/orientation | MUX-04 |
| A5 | Permission denied after render | Deny Photos add permission → Export | Rendered artifact survives; Export sheet's "Recovered exports" offers Retry save (no re-render); alternative guidance shown | MUX-05 |
| A6 | Edit while export runs | Start export → immediately edit the draft | Export uses the accepted snapshot; new edits intact and separate | MUX-04/05 |
| A7 | Kill during publish | Airplane mode → publish → kill app at upload stage → relaunch | Publishing → Recovery queue lists the job at its stage; Verify integrity passes; Retry after going online completes; no duplicate signed event | #/queue machine |
| A8 | Mixed batch | Design with 3 captions → Make variations → CSV with 8 ready / 1 warn / 1 blocked | Confirmation dialog shows exact scope; blocked row can't be selected/approved; warning row approvable | MUX-06/07/08 |
| A9 | Override one approved variant | Approve variant #3 → edit its row value in Setup | Only #3 becomes unapproved; siblings untouched | MUX-08 |
| A10 | Signed-out bulk export | Sign out → batch of 5 → select all ready → Export selected | 5 saved to Photos; retry-failed path re-runs failures only; successes never duplicated | MUX-09 |
| A11 | CSV edge cases | Import CSVs: wrong headers; quoted commas; Unicode; 101 rows | Preview dialog names every problem BEFORE import; 101-row file refused with the split instruction; ≤100 imports cleanly | MUX-07 |
| A12 | Existing large batch | Load a pre-MUX-07 200-row batch document | Loads with all 200 rows; no truncation | MUX-07 |

## B. Accessibility

| # | Check | Required result |
| --- | --- | --- |
| B1 | VoiceOver / TalkBack through editor → Post details → Preflight → Publishing | Every control has a label; selection row reads "Nudge left", "Approve variant 3" style labels; stage changes announced without reading every row |
| B2 | Manipulation without gestures | Select a caption → use the contextual row (nudge/resize/rotate) exclusively → done | Full caption placement possible with zero drag/pinch |
| B3 | Text at 200% | Walk editor + review + batch setup at max text size | No clipped primary action; status lines wrap; approval/selection controls still tappable |
| B4 | Hit targets | Measure (Accessibility scanner or ruler on recording) the new controls: selection checks, approve buttons, clip tools, retry buttons | ≥48 dp / 44 pt effective bounds |
| B5 | Keyboard visible | Add text in Post details + batch recipe fields | Focused field AND Done reachable; drafts commit on Done |

## C. Usability spot-tasks (formative, 5–8 participants when possible)

1. Create + caption + export one image — unassisted within ~2 min?
2. Close and reopen the draft — found it without help?
3. Make 10 variations from the editor design — did anyone almost publish?
4. Fix an invalid row — discovered the problem via preview/notes without help?
5. Export 3 selected variants signed out.
6. Recover a failed save (storage/permission induced).

Record assisted vs unassisted, wrong turns (especially draft/export/post
confusion), and time excluding render/import waits — the audit's proposed
targets apply (≥4/5 beginners on task 1; everyone reopens a draft; ≥7/8 on
task 3 without accidental publish; zero draft loss — zero data loss is a
hard gate).

## Results conventions

- Record per-row: `Pass` / `Fail` / `Partial` + device + build + one line of
  evidence (timestamp in the screen recording).
- A1, A2, A7 (zero-data-loss) and B4 (hit targets) failures block release;
  everything else files as tracked follow-ups.
- Loop failures back: UX-01…14 IDs and MUX task IDs in the audit doc stay
  the reference — file issues against them, not new vocabularies.
- Optional telemetry note: stage durations, counts, stable error codes and
  retry outcomes only — never captions, CSV cells, unpublished media or
  identity secrets.
