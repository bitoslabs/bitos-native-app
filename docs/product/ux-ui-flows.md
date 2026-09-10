# Native UX/UI System and End-to-End Flows

## 1. Experience principles

1. Watch immediately; explain Nostr progressively.
2. Ask for identity, signer, camera, microphone or wallet only at the action that needs it.
3. Creation is local and reversible until the user signs.
4. Progress names the real stage: rendering, uploading, awaiting signer or relay publishing.
   A small Bitz video names its BitOS upload and Blossom replica; a large
   video names the single BitOS upload. Signing remains unavailable until the
   required destination(s) have hash-verified.
5. Failure never destroys a usable draft/output.
6. Protocol power is visible but not jargon-heavy; advanced relay/Blossom details live one level deeper.
7. Every gesture has a visible/accessibility alternative.
8. The editor reveals complexity progressively: Quick Bitz first, MEM expert tools when requested.

## 2. Primary navigation

```text
Home       For You / Following / Latest
Discover   Search / creators / topics / sounds / templates
           (topics + hashtag results carry Follow/Following toggles backed
           by the account's NIP-51 interest set — kind 30015, d=interest —
           synced from relays newest-head-wins, published on every toggle,
           web `hashtag-follows` parity)
Create     Camera / import / Quick MEM / project library
Inbox      Activity / zaps / messages
Profile    Identity / posts / saved / Creator Studio / settings
```

The tab-dock center ＋ opens the Create sheet picker (prototype
`openCreateSheet` parity) with the native creation entries — New note (full
page composer) · New Bitz (capture hub) · New story (kind-30315 composer).
The note composer page keeps its action toolbar pinned to the bottom of the
form, riding just above the open keyboard on both platforms (Android
`imePadding`, SwiftUI keyboard avoidance) — the keyboard never covers the
toolbar or the post action.

The central Create action may receive stronger visual treatment, but it remains a real tab with a stable accessibility label and predictable back behavior.

## 3. Global UI states

Every network/data surface designs these states before implementation:

| State             | Required behavior                                                                          |
| ----------------- | ------------------------------------------------------------------------------------------ |
| Loading           | Skeleton/progress appropriate to expected duration; no indefinite spinner without context. |
| Empty             | Explain why and offer one primary next action.                                             |
| Content           | Preserve scroll/playback/focus when returning.                                             |
| Partial           | Render verified available data and label missing aggregate/media.                          |
| Offline           | Use cached/local content; show queued operations and retry trigger.                        |
| Error             | Human message, stable recovery action and diagnostics code where useful.                   |
| Permission denied | Explain capability and offer import/settings/fallback.                                     |
| Cancelled         | Preserve prior stable state; cancellation is not a failure.                                |

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
- The signed-out You tab reuses the identity-onboarding flow behind one "Add identity" action (method → import/backup → verify); it never hosts a second, divergent import form.
- Never show/copy an `nsec` casually; backup is a deliberate protected flow.
- Remote/external signer rejection returns to the pending action without data loss.
- Multi-account switch shows the active identity before signing or paying.

**Implementation status (2026-08):** the launch path is implemented on both
platforms as the identity onboarding flow (docs/ui/app-01-onboarding-identity.html;
shared copy contract `IdentityOnboardingContent`, replacing the APP-002
carousel): Welcome → Add identity → Import/Backup gate → npub confirmation,
exiting into the feed either as guest (Browse now) or with the confirmed
identity. NIP-46 and NIP-55 render as visible "Soon" method cards (the
signer port already reserves them); tapping explains they arrive in a future
build — never a dead link, never a hidden option.

### 4.1 Secret-key login field rules (ID-004, shared `KeyImportForm`)

The import field is one deterministic rule shared by Compose and SwiftUI
(`shared/business-core/identity/KeyImportForm.kt`): live feedback, submit
gate and rejection copy are identical on both platforms, and hex64 secret
keys are accepted alongside `nsec`.

| #    | Given / When / Then                                                                                                                                                                                                                                                                                                                                                                                                                              |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| KF-1 | The field is masked by default; a show/hide toggle reveals the value so a pasted 63-character key can be verified, and a one-tap paste affordance appears while the field is empty.                                                                                                                                                                                                                                                              |
| KF-2 | Keyboard settings never mangle a key: no autocapitalization, no autocorrection, ASCII-capable/password keyboard; the keyboard's Done/continue action submits when the key is valid.                                                                                                                                                                                                                                                              |
| KF-3 | Feedback is live and specific before submit: npub-instead-of-nsec, too short/too long, internal whitespace, wrong prefix, invalid checksum — and a green "valid key" state; a submit error clears as soon as the text changes.                                                                                                                                                                                                                   |
| KF-4 | The review/submit action is the primary action of its surface and is enabled only when the input resolves to a usable secret (nsec or 64-hex).                                                                                                                                                                                                                                                                                                   |
| KF-5 | Every control (field, paste, reveal, copy) carries an accessibility label; state is never communicated by color alone (icon + text).                                                                                                                                                                                                                                                                                                             |
| KF-6 | A READY input previews the derived identity live before submit: the shared rule returns the derived x-only pubkey and npub, and the surface renders a hex avatar + monospace npub + copy chip (onboarding import step, More-hub add-account sheet).                                                                                                                                                                                              |
| KF-7 | The More-hub add-account surface is a bottom sheet on both platforms (never a centered dialog: it hosts a keyboard form opened from the switcher sheet), renders the shared v2 copy verbatim (`IdentityOnboardingContent` add-account + nsec-help vectors), previews the derived identity (KF-6), and carries a collapsible "What's an nsec?" help — what the key is, where to export it, npub-vs-nsec, sealed-on-device — collapsed by default. |
| KF-8 | Add-account action hierarchy: one filled primary "Review key" gated on the shared READY rule, an outlined "Create new key" alternative, and a quiet Cancel — no two actions of equal weight.                                                                                                                                                                                                                                                     |

### 4.2 Creation confirmation + one-time backup gate

| #     | Given / When / Then                                                                                                                                                                                                                                                      |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| CB-1  | Confirming any key shows the derived npub (monospace, truncating middle), a copy-npub action, and copy that matches the case: new key, import, or active-account switch ("stays sealed", never "overwritten").                                                           |
| CB-2  | For a freshly generated key the confirm gate is the backup moment: a "Secret key (backup)" section offers an explicit reveal (never automatic) with monospace nsec, copy action and a never-share warning; the confirm label acknowledges the backup ("I saved my key"). |
| CB-2b | The backup confirm stays disabled until the user checks an explicit acknowledgment ("I saved my key somewhere safe and understand it can't be recovered.") — on the onboarding backup screen and in the shared confirm dialog/sheet alike.                               |
| CB-3  | Imported keys are not offered a backup reveal (the user already holds the key) — the gate asks them to verify the npub instead.                                                                                                                                          |
| CB-4  | The secret crosses to the view only inside the preview transaction; it is never logged, persisted, or shown outside the confirm gate.                                                                                                                                    |

### 4.2a Sign-out & account-removal confirmation gates

| #    | Given / When / Then                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| ---- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| DA-1 | Every destructive account action — removing a saved account (Settings → Account, "Accounts on this device") and removing the active key (Settings → Security, danger zone) — confirms through a modal dialog (Compose `AlertDialog` / SwiftUI `confirmationDialog`); an in-place confirm-button swap never gates a wipe, because the confirming tap lands on the same pixel that triggered the request.                                                                                                                                                                                                                      |
| DA-2 | The removal dialog names the account (display name, falling back to "account") and states the consequence and the boundary: that account's sealed key is wiped on this device, an nsec backup is the only way to restore it here, and themes and feed preferences survive. When other saved accounts remain, the dialog names the account it will switch to; removing the ACTIVE account auto-switches to the next sealed account instead of dropping to browse — the signed-out shell has no switcher, so dropping would strand the remaining accounts. Only when no other account remains does removal sign out to browse. |
| DA-3 | Sign out is presented as reversible: its dialog says the account stays sealed on this device and can be switched back to anytime — never copy that claims the key is removed (sign-out clears only the active pointer; key removal lives solely in the Security danger zone).                                                                                                                                                                                                                                                                                                                                                |
| DA-4 | The confirm action carries the destructive role/color and Cancel dismisses without touching sealed storage.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |

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

| #     | Given / When / Then                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AC-1  | Given a signed-in account, when the profile tab opens, then the cover renders edge-to-edge with the brand gradient + hex pattern fallback, keeps a full 160pt of visible height under the status bar (legacy banner:avatar ratio), and the hex avatar overlaps the banner bottom by half, with drop shadow — no tint over the photo.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| AC-1a | The You surface requests the active account's kind-0 head independently of the notes feed both at account activation and when the tab enters; it visibly labels the bounded request as "Refreshing from relays" until a verified profile/contact response or timeout. A quiet account therefore renders its published identity. Tapping Following opens the canonical newest-kind-3 contact list and requests missing/current kind-0 metadata for its rows, with a visible "Loading profile details from relays" state. The last verified kind-3 head is retained in the bounded event cache and restored before relay responses. An account-scoped, bounded optimistic following projection also survives a close immediately after a Follow action; a newer verified head replaces either projection. Home, Bitz and You therefore agree during cold start. Followers remain an explanatory surface with no count, because relays cannot enumerate them reliably without inventing stale follows. |
| AC-2  | When the Notes·Replies·Bitz·Reposts rail is scrolled to the top, it pins under the status bar with the page background (no contrasting surface block) and keeps content masked behind it.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| AC-3  | When the camera chip is tapped, a source sheet offers "Take photo" (hidden when no camera) and "Choose from library"; after a photo is chosen, the chip shows a spinner ("Uploading") until the Blossom upload resolves, then the avatar preview shows the uploaded image.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| AC-3b | The editor opens as a full page with a back chevron in the header and a full-width Save pill (spinner while publishing); back cancels without publishing.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| AC-4  | If an upload fails, an error message appears, the previous image stays, and Save remains available for the remaining fields.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| AC-5  | Save is disabled while any image upload is in flight (nothing is signed before media is uploaded and hash-verified) and while publishing; on success a "Profile published ✓" confirmation shows and the profile hero reflects the new kind-0 once the relay echo lands.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| AC-6  | Portrait photos taken with the device camera crop to the same framing the user saw in the picker (EXIF-orientation normalized before crop) on both platforms.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| AC-7  | Bio is capped at 300 characters with a live counter; all eight kind-0 fields (username, display name, bio, avatar, banner, website, NIP-05, lightning) are editable.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| AC-8  | Every control has an accessibility label (camera chip, change-banner pill, tab rail, glass controls), and the npub chip announces copy state.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |

### 4.3 Author profile surfaces & internal routing (UX-010)

User stories:

1. **Peek, then commit** — tapping any author (feed card, Bitz caption, comment
   row, Discover creator/people row, notification actor, mention) opens the
   profile-overview bottom sheet, mock-parity top-to-bottom: banner with the
   close circle on it, hex avatar with a white **View Profile** pill beside
   it, name + NIP-05 check + npub-copy chip, about, info chips, truthful
   stats row, the **Zap + Follow** two-button quick-action row, and a
   two-line **Latest Note** preview card above the notes list.
2. **Full page, same language as "You"** — "View full profile" routes to the
   in-app full-page profile (never an external web link): back header + ⋯
   menu, full-bleed cover with hex-pattern fallback, hex avatar hero
   floating on a background hex plate (the mock's border), identity block,
   Zap + Follow pills, stats row, collapsible about, and a pinned
   Notes · Replies · Bitz tab rail over a dedicated author REQ.
3. **Zap the person, not just a note** — Zap on profile surfaces is a NIP-57
   profile zap: the kind-9734 request carries only the recipient `p` tag (no
   `e` tag); paid detection rides the LUD-21 verify settle and the record
   lands in the sent ledger without a target note.
4. **Comments lead to profiles** — inside the comment sheet, every root/comment
   avatar and name opens the author sheet, so a conversation can be explored
   without leaving the thread.
5. **Notes page five at a time** — the author REQ loads the first five notes
   with the profile; older pages page backward (`until` = oldest loaded
   note, notes-only filter, limit coerced 1..100) when the reader reaches
   the loaded edge. A short page marks the history exhausted; dedupe stays
   by verified event id.
6. **Note cards are X-style detail entries** — every note card (sheet notes
   list, page Notes/Replies tabs) renders inline media (up to three square
   images or one 16:9 video tile), carries a like · repost · zap action row
   (real shared-core publishes: kind-7 reaction, kind-6 repost, NIP-57 note
   zap), and opens the note's thread sheet on tap; Bitz grid tiles open the
   shared reels player scoped to the author (web `/bitz?author=` parity)
   with the NIP-92 imeta `thumb` as the cover — rendered instantly by the
   image loader, never the video URL — plus a locale-free imeta-duration
   badge in the tile corner (web ProfileBitzGrid parity). Opening an image uses a dark, full-screen lightbox that fits
   the complete image inside the viewport (never crops it); multi-image posts
   show a position label and previous/next controls, disabled at either end.
   The shared attachment extractor supports both bare links and Markdown
   links; an allowlisted image/video extension in either the path or a
   `format`/`fm`/`ext` query value is an inline attachment (for example,
   X's `?format=jpg`). The Markdown wrapper never appears as body text.
   Animated `.gif` attachments play inline in note/comment media tiles and
   the lightbox — Android renders tiles through the shared Coil loader
   (platform GIF decoder registered app-wide), iOS through the DesignSystem
   `GifDecoder` frame player behind `RemoteImageView` (the same decoder
   story slides use); a static first frame is never shown for a GIF.
7. **Own notes are deletable (NIP-09)** — the shared core composes bounded
   kind-5 deletions (`composeDeletion`, ≤50 targets, hex-validated); the
   thread sheet offers Delete on the root card and reply rows for the
   signed-in author's own notes with a confirmation, publishing the kind-5
   and hiding the row locally (the root deletes close the sheet). Unlikes
   use the same path: the feed stores remember MY kind-7 event id per liked
   note (web `myEventId` parity) and an unlike publishes its kind-5
   deletion.
8. **Profile moderation** — the full page's ⋯ menu adds Mute/Unmute (local
   mute store) and Report user (kind-1984 with a reason prompt, p-tag only)
   for other accounts, matching the web ProfileActionMenu set. The ⋯ menu
   renders as the shared AppMenu popover on both platforms — rounded pill
   rows with a press fill, web MenuItem parity (the iOS system Menu and the
   Android raw DropdownMenu are not used here).
9. **Diagnostic copies** — the thread sheet's raw-note dialog offers Copy
   note ID · Copy author npub · Copy note text alongside the raw fields
   (web PostCard menu parity).

Acceptance criteria:

| #    | Given / When / Then                                                                                                                                                                                                                                                                                                                                                                                                                      |
| ---- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PR-1 | Tapping "View Profile" (any surface, either platform) opens the in-app full profile page; no `njump.me`/browser intent is ever triggered by a profile action. Copy-link remains available explicitly in the page's ⋯ menu.                                                                                                                                                                                                               |
| PR-2 | The full page renders the author's live kind-0 (banner, avatar, name, NIP-05, about, website, lud16) from a dedicated author REQ (`kinds:[0,1,21,22]`); stats only show counts the REQ can truthfully produce (Posts/Replies/Bitz, Notes/Bitz on the sheet) — follower counts are never invented. Notes load five per page and page backward from the oldest loaded note; a page returning fewer than five new notes disables load-more. |
| PR-3 | Zap is shown only when the author publishes a lud16; disabled/hidden otherwise with an explanatory label. Follow mirrors the shared contact-list state (optimistic flip + kind-3 publish when signed in).                                                                                                                                                                                                                                |
| PR-4 | Back from the full page returns to the originating surface (the sheet stays dismissed on Android; iOS fullScreenCover dismisses to the caller); the system back gesture is honored on Android via BackHandler.                                                                                                                                                                                                                           |
| PR-5 | Deep links (`nostr:`/`njump` author targets) land on the profile sheet first (peek), preserving the sheet → page hierarchy.                                                                                                                                                                                                                                                                                                              |
| PR-6 | Every avatar/name tap target carries an "Open … profile" accessibility label; Zap/Follow announce their state.                                                                                                                                                                                                                                                                                                                           |

Comment bottom-sheet anatomy (both platforms):

- header "Comments N" + close;
- root card (author row tappable → profile sheet; like · replies · zap+sats ·
  repost · bookmark · raw ⋯ with Copy note ID · Copy author npub · Copy note
  text · Open attachment (through the external-link confirm gate) · Copy
  attachment URL; Delete on own notes);
- **NIP-22 auto-switch (ADR-003, web `feed.comment` parity)**: a non-kind-1
  root (video/picture Bitz) publishes kind-1111 comments — uppercase `E/K/P`
  root tags + lowercase `e/k` parent tags (parent = the answered comment, or
  the target for top-level) — while kind-1 roots keep NIP-10 replies; PoW
  rides BOTH paths (NIP-13 nonce tag appended last over the byte-matching
  comment/reply tags) with the difficulty selector inline under the options
  row (composer parity, never a nested sheet); thread REQs query BOTH `#e`
  and `#E` (case-sensitive relay filters) with kinds `[1,1111,7,6,9735]`, and
  kind-1111 events project into the open thread only, never feed windows;
- threaded replies behind depth rails, per-reply Like (+count) · Zap (+sats) ·
  Reply, reply avatars/names tappable → profile sheet; the interaction shape
  is capped at two levels (root comment → one reply), matching the web flow;
  older/deeper remote replies remain visible flattened at the second level.
- Origin card per surface: card-list surfaces (Home, Discover, Bookmarks,
  Profile, Inbox) always show the thread root card for context — imeta videos
  ride `FeedNote.video` (not content links), so those cards preview the poster
  - play glyph (`VideoPreviewTile`, aspect from the imeta dimensions, tap →
    fullscreen player). The immersive Bitz pager HIDES the origin card — the
    video already plays behind the sheet (TikTok-style: header, comments and
    composer only).
- identity-gated composer (sub-reply targeting chip, gallery · GIF · URL ·
  PoW options, pill input + circular send), keyboard never covers the bar.

## 5. Home feed flow

```text
Home -> poster/first frame -> autoplay visible Bitz
     -> the stories rail is the list's FIRST item (web parity: it scrolls away
        with the feed; it always starts with a Create story card, followed by
        the active account's story (when present), followed stories newest-first,
        a Public stories marker, and a bounded (20-event) relay-wide
        public-story sample; re-tap Home scrolls back to it)
     -> story tiles frame the hex avatar in a matching HEX ring (web
        `story-ring-frame hex-clip`: gradient = unseen, muted = seen),
        preview the note text on gradient covers, and show the video poster
        + play badge for video slides
     -> opening a tile shows the web-parity 9:16 viewer: white-on-white/30
        progress bars, header with hex-ringed avatar + relative time +
        pause/close, left-third / right-two-thirds tap zones, slide counter
        pill
     -> slides render every web media shape: image carousels (NIP-92 imeta
        urls + bare links, ≤6, dots + per-image 5 s segments), animated
        GIFs, video slides (controls-free muted player, measured/imeta
        duration capped 60 s, advance on end), sensitive media blurred
        until tapped (content-warning tag), and text-only slides centered
        on their gradient (7 s; background tag or `#hex>to>#hex` token)
     -> the viewer's engagement lane (web `stories` parity): double-tap or
        heart = kind-7 ❤️ like with e/p/a target tags (kind-5 delete on
        unlike), Reply = kind-1 NIP-10 reply (marker tags), DM = prefilled
        private message through the DM pipeline, Zap = NIP-57 LNURL flow
        targeting the slide id, own-story delete (kind-5 e-tag), live
        likes/zaps/views/replies counts + activity sheet (kinds 7/9735 by
        #e, kind 1 by #e/#a; latest reaction per pubkey, zap receipts
        deduped by event id); view receipts stay private by default
     -> Create story opens the dedicated story composer (web `StoryComposer`
        parity; reachable BOTH from the rail's Create story card and the
        shell Create sheet's New story row, prototype `#/story-compose`):
        9:16 preview carrying the "24 h · kind-30315" expiry chip, ≤280-char
        caption, six gradient backgrounds
        for text-only slides (published as the `background` CSS token),
        ≤6 gallery images each hash-verified-uploaded via Blossom BEFORE
        anything references it, ONE video per slide (video-mime NIP-92
        imeta with the measured `duration` and a generated poster frame
        as `thumb` — the instant rail/viewer preview while the video
        streams; the video replaces the photo
        carousel — web parity — so the photo/GIF entries wait while a
        video is attached), GIF picks (already-public URLs join the
        same imeta carousel, no upload) and emoji inserts into the
        caption via the note-composer action row (photo · video · GIF ·
        emoji · PoW); the viewers show a buffering spinner over the
        poster and fall back to "Video unavailable" on a dead source,
        and Android tunes its player for fast starts (~1 s playback
        buffer, explicit MIME — Blossom hash URLs carry no extension),
        optional NIP-13 PoW mined over the EXACT kind-30315
        template (the session fixes the `d` tag and its `created_at`;
        expiration derives from that timestamp; the nonce tag is appended
        last, byte-matching the miner; any edit voids the nonce and
        publish falls back to the unmined path), alt text (NIP-92 on the
        first imeta) and a
        sensitive flag; publishing is a kind-30315 with a unique `d`
        (bitos-story-<ts>-<rand>), 24h expiration and image URLs mirrored
        into the content — no image, and the story doubles as a 24h status
        note
     -> poster prefers explicit publisher hints (NIP-92 imeta `thumb` — the
        standard cover our composer and the web BitzComposer publish, rendered
        by the web notification media as the video poster — then top-level
        preview/image tag, then imeta preview/image value) before derived
        image attachments
     -> cached notes render immediately; relay head fills one bounded snapshot
     -> after EOSE (or a short deadline), live notes merge at top or buffer while reading older posts
     -> return to top or pull to refresh to merge the bounded buffer; no pending-count control is shown
     -> reselect active Home: scroll to top, then refresh if already there
     -> vertical swipe changes active player lease
     -> ten buffered Bitz remaining starts that mode's older-video query
     -> parallel relays merge by event id until EOSE; oldest time becomes next cursor
     -> each completed EOSE batch (or its short deadline) appends as ONE update —
        mid-page frames never surface one card at a time
     -> the final page remains visible until the next verified video appends
     -> tap pause; double tap react; hold speed
     -> caption/sound/author/provenance sheets
     -> comment / repost / bookmark / zap / share / report
```

The head subscription retains a newest-event watermark and reconnects from a
one-second overlap (event-id de-duplication resolves the boundary). This
forward lane is independent from the per-timeline older cursor, so scrolling
back from an opening snapshot cannot lose notes published in the meantime.

**Bitz discovery/query standard** (web `docs/SYSTEM.md` parity): the Bitz
feed, pagination, author playback and NIP-50 search discover media ONLY
through the standard NIP-68/NIP-71 kinds below — never by querying kind `1`
text notes and inferring video from a URL in the note body. This keeps the
Bitz grid media-specific and interoperable with video clients:

| Content                  | Nostr kind | Standard | Notes                                                    |
| ------------------------ | ---------: | -------- | -------------------------------------------------------- |
| Picture                  |       `20` | NIP-68   | Image media post.                                        |
| Normal video             |       `21` | NIP-71   | Usually landscape/long-form.                             |
| Short video              |       `22` | NIP-71   | Portrait/reels-style; BitOS's normal publish target.     |
| Addressable normal video |    `34235` | NIP-71   | Requires a `d` tag; newest event per coordinate is used. |
| Addressable short video  |    `34236` | NIP-71   | Requires a `d` tag; newest event per coordinate is used. |

Native keeps ONE shared Home+Bitz subscription (architecture split), so the
combined head REQ keeps Home's shallow kind-`1` window and the repost/profile
heads riding alongside the deep media filter (`BitzQuery`, shared
`business-core`); Bitz pagination (`BitzTimelinePolicy.batchFilters`) and
Bitz NIP-50 search (`bitzSearchRequest` / `SearchScope.BITZ_MEDIA`) stay
media-kinds-only. Video metadata belongs in an `imeta` tag (`url`, `m`,
`dim`, `x`, preview `image`, `fallback`, recommended `duration`); `content`
is the description/caption. The codec may still render a legacy kind-`1`
video event passed in from elsewhere, but Bitz never discovers one by
querying kind `1`. The Explore grid's first paint is ONE batch at the page
EOSE (or the snapshot deadline); late slow-relay frames stay batched on the
trailing flush tick instead of dripping in one tile at a time.

UI anatomy:

- full-bleed media respecting safe areas;
- full-screen playback uses aspect-fit/contain: landscape, square and portrait
  originals retain their complete frame, with black letterbox space instead of crop;
- top mode switch with clear selected state;
- right action rail with labels available to assistive technology;
- bottom author, caption, sound and progress. Hashtags render ONCE, inline
  in the caption body (NIP-27 rich tokens, accent + tappable) — no separate
  tag row under the body. `FeedNote.hashtags` is parsed from the caption text
  itself, so a tag row re-listed every tag verbatim on the Bitz player, the
  feed video card and the note detail (Android and iOS alike);
- visible full-viewport blurred content-warning gate before playback; media and
  audio begin only after the reader explicitly reveals it; the decision sits in
  a centered glass panel with a clear “Show video” action;
- NIP-36 `content-warning` remains canonical; legacy posts also gate on a
  case-insensitive sensitive `t` tag or standalone caption hashtag (`#nsfw`,
  `#porn`, `#nudity`, and the shared conservative vocabulary);
- one active audio player; warm next/previous players only;
- relative time is `now`/seconds while a card is visible, then updates only on
  minute boundaries; the feed store is never republished for a clock tick;
- For You and Following paginate independently; exhausting one mode never
  disables loading in the other;
- Explore always reads and paginates the global For You lane, even after the
  user has visited Following; each mode therefore keeps the correct cursor;
- Refreshing Explore reopens that shared relay lane, but it does not replace
  the native For You pager snapshot from the reader's last For You visit;
  returning to For You preserves its visible session. An explicit For You
  refresh remains the reader's opt-in to refresh that session;
- Recognized external video links render as image-only provider cards; opening
  one follows the external-link confirmation gate. No provider iframe or
  WebView executes inside BitOS;
- The raw-event action always responds: it shows the canonical event JSON when
  retained locally (with a copy action), or explains that the bounded cache no
  longer holds the event;
- Explore discovers media only through the standard NIP-68/NIP-71 kinds
  (20/21/22/34235/34236) queried deep — the first paint fills the 24-tile
  grid, not a six-tile sliver (see the Bitz discovery/query standard above);
- Explore reveals ten more tiles per local page and begins relay pagination ten
  tiles before the loaded edge; Nostr uses the oldest event time as `until`
  because relay offsets are not portable;
- Explore prefetches a bounded adjacent poster window so newly revealed rows do
  not wait on full-size image decoding;
- video quality is deterministic across native apps: Auto chooses a rendition
  for the logical laid-out display height (so dense screens do not force an
  unnecessarily heavy decode), High chooses the tallest published rung, and
  Low chooses the shortest rung at or above 360p (or the shortest available);
- polls (APP-008, web `Poll.svelte`/`votePoll` parity): a kind-1 note with
  `poll_option` tags renders as option rows; votes lazy-load once per poll
  (one-shot kind-1018 `#e` REQ, ≤200 voters) and tapping an option publishes
  a kind-1018 (`e` + `response <index>` tags, empty content) with an
  optimistic local flip; tallies use the shared latest-vote-per-pubkey rule
  (changing your vote moves it); after voting, rows show proportional bars,
  percentages, counts and the total, with your choice highlighted.
- local ranking signals (web interaction-profile parity): every card's ⋯
  menu offers **Not interested** (hide + demote author + demote topics),
  **Hide this note**, **Show less/more from \<author\>** and **Show
  less/more about #tag**. Dismissed notes never surface on any window (the
  shared ranker's one intentional drop); author demotions multiply the
  score by 0.25 and topic demotions by 0.5 (stackable, deterministic); all
  three sets are device-local, bounded and persisted — never published.
- raw event viewer (web "View raw event JSON" parity): the shared feed
  card's ⋯ menu (home, profile and author surfaces) offers **View raw event
  JSON**, opening the note's canonical NIP-01 event object
  (`id`/`pubkey`/`created_at`/`kind`/`tags`/`content`/`sig`) in a
  monospace sheet. The object is rebuilt once in the shared codec from the
  verified event — exactly the bytes the event ID commits to — never the
  `["EVENT", …]` relay frame wrapper; repost cards serve the embedded
  original's object (the card displays the original). Feed stores keep a
  bounded recent-events map for the viewer; ingest never pays the
  encoding, the sheet serializes on open.

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

**One publish path from the studio hub (2026-09-07):** the Create hub's
"Import media" action, the camera's used take and the camera's "Import
from library instead" ALL route into the Quick Bitz editor — a picked
library video seeds VIDEO mode as its own timeline clip (same CAP→MEM
seeding as a camera take; cut rules applied). The standalone "New video"
publish sheet (raw video → caption → kind-22 with no editing) is gone
from BOTH surfaces — removed from the studio Create hub on 2026-09-07
and from the feed/Home app-bar the same day (the photo-icon
"Import and publish a video" quick import and its sheet were deleted);
the studio editor is the single publish path. Rejections are named and
non-destructive
(unreadable pick, source over the 256 MB timeline cap — name the reason
and the fix, keep the hub untouched), and a brief "Preparing your
video…" stage covers the byte read before the editor opens.

**The sound loop (2026-09-08, MST-050 A–D shipped both platforms; plan
`use-this-sound-plan.md`):** any video's audio can become a project's
ONE soundtrack — picked in the editor's Sound tool ("♪ Pick sound from
a video…"), borrowed from any video bitz (rail **Sound** action: the
editor seeds sound-first, NO clip — TikTok's loop), or re-attached from
the **Trending sounds** rail (More hub: counting `sound` tags over the
feed window with a 3-day half-life — APP-021 bootstrap, no
marketplace). Preview plays it glued to the timeline clock; export
mixes clip audio + soundtrack + synth cues into one bed; drafts persist
the m4a and rehydrate on resume (undecodable → row stripped with a
named notice, never a silent export). Publishing uploads the m4a
hash-verified BEFORE signing and stamps `sound` + `p` + `attribution`
credit for the source author; re-attached artifacts stamp the existing
URL without re-uploading. Published borrows render a ♪ chip on the
card.

Native implementation (2026-09-05, prototype `#/create-edit` → `#/create-details`
→ `#/create-review` parity): the editor screen follows the prototype layout —
"Editor" header with draft save and the "Next" publish entry inline with it
(the bottom stack stays tool-only; a slim notice replaces the bottom button
while no source exists), VIDEO/GIF/IMAGE pills with source-add buttons
(frame/image/clip per mode, Solar icons) beside undo, circular quick tools
(Meme · Text · Stickers · Sound · Effects) opening native BOTTOM SHEETS
(panel contents per the prototype; sheets are the platform idiom and keep
the canvas visible above; each sheet shows exactly one title — the sheet
header — and one content inset, so panel bodies drop their own titles and
padding),
a single compact clip timeline with Split/Delete/Mute/Speed/Layer in video mode
(clip and layer insertion stays in the expanded Timeline workspace, avoiding a
second source strip in the basic editor) — timeline clip edits (split, delete,
move, mute/volume, trim, per-clip look, picker-added clips) are UNDOABLE and
interleave correctly with overlay/style edits through one undo affordance
(seeding a session from the camera is not an undo step), and a
per-mode bottom bar. Layer stacking is uniform across modes: paint order is
the overlay list order and adding an image always lands ON TOP — video-mode
inserts and image mode's multi-pick (first pick = background, later picks =
draggable image layers) use the same IMAGE overlay binding. The Layers sheet
(video Overlay tool, image-mode Layers tool) manages the WHOLE stack: every
text/sticker/image row, top-first (row 1 paints in front), with select,
delete, insert and ±1 stack moves (the shared `reorder` command — clamped,
one undo step per move; arrows stay reachable without drag gestures per the
accessibility rule). Post details
collects caption, explicit t-tags (≤ 8), the cover row (video, with an Edit
action that drops back onto the editor stage's Set cover), the audience row,
the Zap settings switch (DEFAULT OFF — viewers can zap this post; OFF stamps
the shared advisory `["bitz:zaps", "off"]` tag and BitOS feed cards hide the
zap action on that note — advisory like `license`, and NOT pay-per-view:
zap-gated unlocking needs invoice + preimage verification and stays future
work), the
Allow-remix switch (one view of the `license` tag: off is exactly Nostr-only; on restores
the last remixable chip), content warning (toggle only — the fixed
"Sensitive content" reason rides the NIP-36 cover, no free-text input), and
the license chips
(CC0 / CC-BY / Nostr-only — rides the `license` tag). Remix lineage is NEVER
typed by hand: the Bitz rail's "Remix" opens the meme editor with the source
attached (M4b handoff — the source media downloads into the session, its
`meme` layout clones with fresh ids, lineage rides the draft) and, when the
note has no loadable media, falls back to the note composer seeded with the
attribution tags. A remix source riding the draft previews read-only —
source + author short refs plus the keep-it-remixable license note (the
chips default to CC-BY, the web studio's remix default) — and publish
stamps the machine `remix` (+ ≤3 relay hints merged from the source tag and
the write relays) + `meme` + `p` + `license` + `attribution` ("remix of
<label>", ≤140) tags via the shared `RemixRules`/`memeRemixTagsFor` seams,
with the imeta carrying web-parity `duration` (ms precision) and `bitrate`.
Its preview/caption row
and tag entry use the compact, flat
prototype treatment; caption input enforces the displayed 300-character limit.
Recently used hashtags are reused in one tap: a shared bounded ledger
(`RecentHashtags`, newest-first, ≤ 64, recorded when a note or meme publish
is initiated) feeds "Recent" chip rows under the note composer's field and
the meme details tag input — chips the post already carries drop out.
Preflight shows the real checklist (media, tags, license,
audience, relays, signer) and previews the caption with the SAME shared
NIP-27 rich renderer the feed cards use (hashtags/mentions highlighted
exactly as they will post) and runs the existing render → hash-verified upload →
sign machine with phase feedback. Zap-scope tags, splits, PoW and schedule
remain wave-4 work (see `docs/native/meme-studio-plan.md`): the details form
carries an explicit wave-4 note where the prototype mocks those controls
instead of faking them. The video expert dock stays behind the per-mode
bar's Timeline slot — progressive disclosure per rule 8.

The editor uses one native range-control treatment throughout: an orange
cursor and completed rail over a neutral remaining rail, with a 40dp touch
target. Playback has one scrubber; the clip ruler separately shows selection
and one playhead. A clip's speed belongs to that clip and is displayed on its
segment, so no project-wide speed progress is implied.

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
- Reopening a video draft restores its persisted timeline before the player
  binds; it must play without requiring a mode switch. A completed attempt is
  scoped to that attempt only: opening Post details for a new draft begins
  idle, and `Done` appears only after a relay receipt confirms publication.

Native implementation (2026-09-05, prototype `#/publishing` + `#/queue`
parity): after Preflight's "Sign & publish", a full publish-machine screen
runs the 8 REAL stages as a stepper — render → hash → upload → verify →
build → sign → relay → confirm — with a circular progress ring, per-attempt
job chip and stage details reporting facts (accepted relay hosts).
Transitions fire at actual pipeline checkpoints (uploader hashing/PUT/
verify; note built/signed/relayed; receipt-machine terminal result) —
never timers. The ring is the at-a-glance liveness answer (2026-09-08):
its percent counts COMPLETED stages only (checkpoints, never simulated), a
rotating sweep arc plus a spinner on the current row keeps the screen
visibly alive inside long single stages (video encode, large upload), and
the caption names the position — `Step 3 of 8 · Upload to Blossom`,
`Stalled at step 3 of 8` on failure, `All 8 stages complete` with a
check on success. Where a stage can report a REAL intra-stage fraction
(2026-09-08, both platforms), it absorbs into the same percent and the
row: render follows the encoder's own progress API (media3 Transformer
poll / AVAssetExportSession.progress), upload follows socket bytes
(OkHttp body-write counter / URLSession didSendBodyData, dual-leg
BitOS+Blossom stitched 75/25), and the row's detail line shows byte
truth (`12.4 / 60.0 MB`) under a thin sub-bar. Hash, verify, build,
sign, relay and confirm stay checkpoint-only (they are instant or
nondeterministic-by-design) — the sweep arc, never a fake percent,
covers them.
Failure marks the stalled stage with Retry/Later; success shows the event
id + confirmed relay count. Publish attempts are DURABLE jobs: a bounded
ledger persists inputs + stage at every checkpoint, and the Recovery queue
(from the machine's failure/success blocks) offers verify-integrity
(stored-bytes SHA-256 vs the recorded digest), retry-from-media (upload
idempotent by hash; refused once an event id exists — that note may
already be live) and confirmed discard.

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
- The zap wallet (APP-014: local sent ledger merged with verified kind-9735 receipts) is one surface with three entry points on both platforms — the You page ⋯ menu, the You page "Sats zapped" stat pill, and the More hub Account group "Zap wallet" tile beside Profile. Hubs never render disabled placeholders for surfaces that exist.
- Non-atomic split payments list each recipient result; never show a single success if some failed.
- Report collects reason, optional details and block/mute follow-up without exposing reporter identity publicly.
- Secure messages keep notification payload generic and fetch/decrypt only inside the app.

### 11.1 Inbox activity surface (APP-012, mock 06 `scr-activity` parity)

The Activity tab renders verified events targeting the account as full-width
bordered rows (no cards), matching `docs/ui/app-06-inbox-activity-messages.html`
on both platforms:

- Header "Inbox" + ⋯ menu: mark all read, search, per-type mutes (filters).
- One chip row — All (orange) / ⚡ Zaps / ♥ Likes / Follows / Mentions —
  replaces the old tab+chip pair; Mentions covers mentions and replies.
- Row taxonomy: aggregated zap rows (avatar stack, amber summed sats, bolt),
  like rows with a "+N" hex plate and heart, repost rows with the repeat
  glyph, follow rows with a working **Follow back** pill (optimistic kind-3
  publish; "Following ✓" when already followed), mention/reply quote cards
  with @handles highlighted in brand orange, and dimmed zap-out rows from
  the local APP-014 sent-zap ledger with a PAID chip. Every actor avatar is
  40 pt/dp across all row types (mention/reply rows match the aggregated
  rows). In mention/reply rows the quote card indents to the name line
  (avatar 40 + gap 10 = 50 pt/dp) so the content lines up under the username.
- Day sections Today / Yesterday / Earlier (one collapsed "Earlier"), a 3 dp
  left accent stripe marks unread rows, and the footer restates the
  verified-events promise ("no invented counts, no engagement theater").
- Row taps deep-link to the thread sheet (verified origin) or author sheet and
  mark the row read; long-press keeps mark-read / view-profile / copy-note-id
  / raw-JSON / mute-this-type; visible-mark-read stays 1.4 s and blocked or
  muted authors never render.
- Web `notifications.svelte` functional parity: pagination ("Load older
  notifications" pages history with `until` REQs until "End of relay
  results"), an offline card with Reconnect when the head REQ never answers,
  zap receipts in their own relay filter (never crowded out), kind-16 generic
  reposts, negative reactions never notify, quote-mentions deep-link to the
  quoted note, zap targets prefer the receipt's second `e` tag, titles name up
  to two actors ("A, B and 3 others"), and mention/reply cards render the
  note's own cleaned excerpt plus a media strip behind the NIP-36 cover.
- Media links never surface as row text (shared extractor rule): notification
  summaries strip links and nostr entities via the NIP-27 tokenizer, so a
  media-only note renders its excerpt empty with the media strip carrying the
  row. Compact zap/like/repost and zap-out rows show a "Media" stand-in quote
  when the verified origin has media but no text, and row taps pass the
  origin's media URLs into the thread sheet so the media grid renders there
  instead of raw link text.
- NIP-22 kind-1111 comments (feed cards, bitz videos) notify the commented
  post's author like replies do: the inbox REQ adds kind 1111 to the `#p`
  participant filter and a dedicated uppercase `#P` root-author filter
  (strict NIP-22 clients may carry no lowercase `p` naming the author), and
  the shared extractor classifies them as reply rows whose deep link is the
  `E` root tag (the commented post), with the lowercase `e` parent as
  fallback. Own comments never notify.

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

Interactive HTML mockups for the flows in this document live in `docs/ui/`
(start at `docs/ui/index.html`). They render the state matrices above —
loading/empty/offline/error/permission variants sit next to each happy path —
and use the token set from `docs/DESIGN_SYSTEM.md`.
