# Native UX/UI System and End-to-End Flows

## 1. Experience principles

1. Watch immediately; explain Nostr progressively.
2. Ask for identity, signer, camera, microphone or wallet only at the action that needs it.
3. Creation is local and reversible until the user signs.
4. Progress names the real stage: rendering, uploading, awaiting signer or relay publishing.
5. Failure never destroys a usable draft/output.
6. Protocol power is visible but not jargon-heavy; advanced relay/Blossom details live one level deeper.
7. Every gesture has a visible/accessibility alternative.
8. The editor reveals complexity progressively: Quick Bitz first, MEM expert tools when requested.

## 2. Primary navigation

```text
Home       For You / Following / Latest
Discover   Search / creators / topics / sounds / templates
Create     Camera / import / Quick MEM / project library
Inbox      Activity / zaps / messages
Profile    Identity / posts / saved / Creator Studio / settings
```

The central Create action may receive stronger visual treatment, but it remains a real tab with a stable accessibility label and predictable back behavior.

## 3. Global UI states

Every network/data surface designs these states before implementation:

| State | Required behavior |
|---|---|
| Loading | Skeleton/progress appropriate to expected duration; no indefinite spinner without context. |
| Empty | Explain why and offer one primary next action. |
| Content | Preserve scroll/playback/focus when returning. |
| Partial | Render verified available data and label missing aggregate/media. |
| Offline | Use cached/local content; show queued operations and retry trigger. |
| Error | Human message, stable recovery action and diagnostics code where useful. |
| Permission denied | Explain capability and offer import/settings/fallback. |
| Cancelled | Preserve prior stable state; cancellation is not a failure. |

## 4. Browse and identity flow

```text
Launch
  -> short value proposition
  -> Browse now OR Add identity
  -> Home feed

First signed action
  -> explain signature and ownership
  -> Create key / Import / NIP-46 / Android NIP-55
  -> confirmation/backup gate where required
  -> return to the exact pending action
```

- Do not create an identity silently.
- Never show/copy an `nsec` casually; backup is a deliberate protected flow.
- Remote/external signer rejection returns to the pending action without data loss.
- Multi-account switch shows the active identity before signing or paying.

### 4.1 Secret-key login field rules (ID-004, shared `KeyImportForm`)

The import field is one deterministic rule shared by Compose and SwiftUI
(`shared/business-core/identity/KeyImportForm.kt`): live feedback, submit
gate and rejection copy are identical on both platforms, and hex64 secret
keys are accepted alongside `nsec`.

| # | Given / When / Then |
|---|---|
| KF-1 | The field is masked by default; a show/hide toggle reveals the value so a pasted 63-character key can be verified, and a one-tap paste affordance appears while the field is empty. |
| KF-2 | Keyboard settings never mangle a key: no autocapitalization, no autocorrection, ASCII-capable/password keyboard; the keyboard's Done/continue action submits when the key is valid. |
| KF-3 | Feedback is live and specific before submit: npub-instead-of-nsec, too short/too long, internal whitespace, wrong prefix, invalid checksum — and a green "valid key" state; a submit error clears as soon as the text changes. |
| KF-4 | The review/submit action is the primary action of its surface and is enabled only when the input resolves to a usable secret (nsec or 64-hex). |
| KF-5 | Every control (field, paste, reveal, copy) carries an accessibility label; state is never communicated by color alone (icon + text). |

### 4.2 Creation confirmation + one-time backup gate

| # | Given / When / Then |
|---|---|
| CB-1 | Confirming any key shows the derived npub (monospace, truncating middle), a copy-npub action, and copy that matches the case: new key, import, or active-account switch ("stays sealed", never "overwritten"). |
| CB-2 | For a freshly generated key the confirm gate is the backup moment: a "Secret key (backup)" section offers an explicit reveal (never automatic) with monospace nsec, copy action and a never-share warning; the confirm label acknowledges the backup ("I saved my key"). |
| CB-3 | Imported keys are not offered a backup reveal (the user already holds the key) — the gate asks them to verify the npub instead. |
| CB-4 | The secret crosses to the view only inside the preview transaction; it is never logged, persisted, or shown outside the confirm gate. |


### 4.1 Profile view & edit flow (APP-013, legacy-Flutter parity)

User stories:

1. **Recognize my identity** — as a signed-in user, my profile page shows the
   brand hero (cover + hex avatar straddling the banner edge), my name,
   verified badge, npub chip and ⚡ chip — same visual language as the old app.
2. **Edit everything in one place** — "Edit profile" opens the editor with a
   live header preview (banner + "Change banner" pill, avatar + camera chip)
   that shows exactly what my profile will look like once saved.
3. **Change my photo in familiar steps** — tapping the camera chip (avatar)
   or "Change banner" (cover) opens a source sheet ("Take photo" on device
   camera / "Choose from library"), then the shot is center-cropped to the
   published size (512×512 avatar / 1500×500 banner), uploaded to Blossom and
   the preview updates immediately. No URL knowledge required; raw URL fields
   remain for power users. The editor is a full page with a back button (not a
   bottom sheet) — legacy `Routes.PROFILE_EDIT` parity.
4. **Never lose work** — fields keep my draft if the sheet stays open; upload
   or publish failures surface a human message and keep every entered value.

Acceptance criteria:

| # | Given / When / Then |
|---|---|
| AC-1 | Given a signed-in account, when the profile tab opens, then the cover renders edge-to-edge with the brand gradient + hex pattern fallback, keeps a full 160pt of visible height under the status bar (legacy banner:avatar ratio), and the hex avatar overlaps the banner bottom by half, with drop shadow — no tint over the photo. |
| AC-2 | When the Notes·Replies·Bitz·Reposts rail is scrolled to the top, it pins under the status bar with the page background (no contrasting surface block) and keeps content masked behind it. |
| AC-3 | When the camera chip is tapped, a source sheet offers "Take photo" (hidden when no camera) and "Choose from library"; after a photo is chosen, the chip shows a spinner ("Uploading") until the Blossom upload resolves, then the avatar preview shows the uploaded image. |
| AC-3b | The editor opens as a full page with a back chevron in the header and a full-width Save pill (spinner while publishing); back cancels without publishing. |
| AC-4 | If an upload fails, an error message appears, the previous image stays, and Save remains available for the remaining fields. |
| AC-5 | Save is disabled while any image upload is in flight (nothing is signed before media is uploaded and hash-verified) and while publishing; on success a "Profile published ✓" confirmation shows and the profile hero reflects the new kind-0 once the relay echo lands. |
| AC-6 | Portrait photos taken with the device camera crop to the same framing the user saw in the picker (EXIF-orientation normalized before crop) on both platforms. |
| AC-7 | Bio is capped at 300 characters with a live counter; all eight kind-0 fields (username, display name, bio, avatar, banner, website, NIP-05, lightning) are editable. |
| AC-8 | Every control has an accessibility label (camera chip, change-banner pill, tab rail, glass controls), and the npub chip announces copy state. |

## 5. Home feed flow

```text
Home -> poster/first frame -> autoplay visible Bitz
     -> vertical swipe changes active player lease
     -> ten buffered Bitz remaining starts that mode's older-video query
     -> parallel relays merge by event id until EOSE; oldest time becomes next cursor
     -> the final page remains visible until the next verified video appends
     -> tap pause; double tap react; hold speed
     -> caption/sound/author/provenance sheets
     -> comment / repost / bookmark / zap / share / report
```

UI anatomy:

- full-bleed media respecting safe areas;
- top mode switch with clear selected state;
- right action rail with labels available to assistive technology;
- bottom author, caption, tags, sound and progress;
- visible content-warning gate before playback;
- one active audio player; warm next/previous players only;
- For You and Following paginate independently; exhausting one mode never
  disables loading in the other;
- Explore reveals ten more tiles per local page and begins relay pagination ten
  tiles before the loaded edge; Nostr uses the oldest event time as `until`
  because relay offsets are not portable;
- Explore prefetches a bounded adjacent poster window so newly revealed rows do
  not wait on full-size image decoding;
- data-saver/quality indication only when it helps the user.

## 6. Quick create flow

```text
Create
  -> Record / Import / Quick MEM
  -> capture or choose source
  -> Quick Bitz editor
       trim/reorder
       cover
       sound/volume
       text/look
  -> Post details
       caption/tags/mentions
       alt/content warning
       relay/media choices (advanced)
  -> Review/preflight
  -> Render/upload/verify
  -> Sign
  -> Relay publish/receipt
  -> View post / Share / Keep editing copy
```

Back behavior:

- capture back asks only if unsaved segments would be lost;
- editor edits autosave, so ordinary back returns to project/library;
- leaving during render/upload offers Continue in background or Cancel safely;
- awaiting signer can be dismissed and resumed from the queue.

## 7. Camera UX

- Permission requested from Record, not launch.
- Viewfinder controls: close, flash, lens, speed, timer, duration and settings.
- Record control provides color, animation, haptic and accessible state.
- Segment strip shows completed takes with delete/undo.
- Persistent microphone route/level and capture indicator.
- Interruption explains what segment was saved.
- Low storage/thermal state blocks safely before corruption.

## 8. MEM expert editor flow

```text
Project -> Stage + timeline
  -> Add: media/text/caption/sticker/draw/sound
  -> select layer/clip
  -> contextual inspector
  -> preview/scrub
  -> undo/redo + autosave
  -> Review
  -> fix blockers
  -> Export/Publish queue
```

Tablet layout:

```text
+------------+-------------------------------+------------------+
| Tool rail  | Stage                         | Inspector        |
| Add/Text   | safe-area guides              | selected object  |
| Draw/Sound | preview controls              | timing/style     |
+------------+-------------------------------+------------------+
| Tracks: video / overlays / drawing / captions / audio / SFX   |
+---------------------------------------------------------------+
| Undo Redo | project status | Preview | Review | Export/Publish |
+---------------------------------------------------------------+
```

Phone layout:

```text
Top: Back | project name/save | Review
Middle: sticky stage/preview
Bottom: timeline then contextual tool bar
Sheets: add browser and detailed inspector
```

The stage stays visible while editing core properties. Advanced sheets never hide an active microphone/camera state.

## 9. Publish and recovery UX

```text
Checking project -> Rendering -> Securing media hash -> Uploading
-> Verifying media -> Waiting for signature -> Publishing to relays -> Done
```

- Show determinate percentage only when real; otherwise show stage activity.
- Upload shows bytes and network pause/retry.
- Signer wait identifies the selected signer and offers Open signer/Cancel wait.
- Partial relay success is success with a details/retry action.
- Hash mismatch is a blocking integrity error, never an automatic retry loop.
- Relaunch opens the queue card at the exact durable stage.

## 10. Remix and Use Sound

```text
Bitz or Sound page -> Use this / Remix
  -> show source creator, license, attribution, rights signal
  -> Quick Bitz or MEM
  -> preserve parent/root/sound/template edges
  -> review attribution/value recipients
  -> publish
```

Attribution does not claim legal permission. Unavailable/unsupported assets offer replace, continue without, or cancel.

## 11. Social and wallet flows

- Comments open as a bottom sheet on phone and side panel on tablet; keyboard never covers composer/post action.
- A zap opens amount/message/recipient review, then native NWC approval. Pending, paid and failed are distinct.
- Non-atomic split payments list each recipient result; never show a single success if some failed.
- Report collects reason, optional details and block/mute follow-up without exposing reporter identity publicly.
- Secure messages keep notification payload generic and fetch/decrypt only inside the app.

## 12. Visual system

- Brand accent: BitOS yellow `#FFCC00`; do not use it for destructive/warning semantics.
- Dark creator surfaces are default for media accuracy; light/system themes remain supported outside the editor.
- Use semantic tokens: background, surface, elevated, primary text, secondary text, separator, success, warning, danger and focus.
- Minimum target: 44x44 pt iOS, 48x48 dp Android.
- Respect safe areas, camera cutouts, gesture insets and keyboard/IME.
- Motion communicates hierarchy/progress and honors Reduce Motion.
- Haptics confirm capture, snap, destructive confirmation and publish completion; never fire continuously during scrub.
- Typography supports Dynamic Type/Android font scaling without clipping critical actions.

## 13. Accessibility gates

- Logical reading/focus order independent of visual z-order.
- Player actions exposed as accessibility actions.
- Timeline clips have spoken label, start/end/duration and move/trim actions.
- Color is never the only state indicator.
- Captions and alt text are first-class publish controls.
- Screen-reader, switch/keyboard, large-text, reduced-motion and contrast checks are required for release journeys.

## 14. UX acceptance artifact

Every feature PR includes its state matrix, primary/secondary/destructive actions, back behavior, permission moment, offline/recovery behavior, accessibility labels/actions and analytics consent impact. A happy-path mock is not a complete flow.
