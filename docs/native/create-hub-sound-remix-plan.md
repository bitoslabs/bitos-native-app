# Create Hub: Start with Sound and Remix a Bitz

## Status and decision

Planned implementation slice for `MEM-003` and `MEM-005`. It resolves a
Create-hub reachability gap without changing the already-shipped sound or
remix publish machinery.

The Create hub currently presents **Use a sound** and **Remix** as disabled
“Soon” quick actions. That status is now stale for sound: shared sounds,
trending sounds, local sound-library attachment, source-video extraction,
hash verification and automatic credit already exist on both platforms.
Remix is also implemented from a Bitz card, but has no source-selection path
inside Create; enabling it as a blank project action would make its
provenance promise untrue.

**Decision:** replace the two disabled rows with enabled, source-first entry
points:

- **Start with a sound** opens a sound source picker and then creates an empty
  VIDEO project with a verified soundtrack, never a media clip.
- **Remix a Bitz** opens a source picker. It creates a remix only after the
  selected source event has been fetched, verified, and accepted by the
  existing remix handoff.

The feature reuses `MemeSoundSeed`, `MemeRemixSeed`, existing media download,
the editor's one-shot seed paths, `MemeSoundRules`, `RemixRules`, and the
existing durable studio slots. It does not introduce a backend, new Nostr
event kind, a second editor, or a generic global handoff store.

## Product outcome

A creator on the Create tab can intentionally begin in either of two
creator-native ways:

```text
Create
  -> Start with a sound
       -> choose a verified sound source
       -> Studio video project: sound attached, no clip
       -> add/record/import a clip -> publish with automatic sound credit

  -> Remix a Bitz
       -> choose a verified public Bitz source
       -> Studio project: source media + cloned layout + read-only lineage
       -> edit -> publish with automatic remix attribution
```

The Bitz-player **Sound** and **Remix** actions remain the fastest contextual
entries. The Create-hub entries are discovery/start paths, not replacements.

## UX/UI specification

### Create hub placement and copy

Keep the existing Create hierarchy:

1. Start something new: Bitz, Meme, Batch.
2. Templates and Shared sounds rails.
3. Continue creating.
4. **Create with** quick actions: Import media, Start with a sound, Remix a
   Bitz.

The two enabled quick-action rows use the same height, icon plate, chevron,
touch target and VoiceOver/TalkBack behavior as Import media. Remove the
dimmed state and “Soon” chip.

| Entry | Title | Subtitle | Supporting copy | Destination |
| --- | --- | --- | --- | --- |
| Sound | Start with a sound | Sound sources | Add your video after you choose a sound. | Sound source picker |
| Remix | Remix a Bitz | Credit included | Start from a Bitz; the original creator is credited automatically. | Remix source picker |

Do not call a template “a remix,” and do not offer a disabled action when a
usable first step exists. A source is required for both entry points.

### Sound source picker

Present a native full-screen route on compact phones and a large sheet on
regular-width layouts. It has one title, **Start with a sound**, a close
button, and these ordered sections:

1. **Shared sounds** — the existing verified kind-30078 rail; cards show
   label, duration, license, creator credit and a preview control.
2. **Trending on BitOS** — the existing deterministic trending projection;
   rows show the same source/usage context already provided by Trending
   sounds. It is a link to the existing full Trending sounds surface, not a
   second ranking implementation.
3. **My sound library** — existing locally saved, hash-verified sounds. It
   works offline and identifies its local-only state where no public source is
   available.
4. **Pick sound from a video** — opens the existing native library-video
   picker; the editor's existing extraction path owns extraction, named
   progress, bounds and failure copy.

Selecting a shared, trending or local item dispatches exactly one
`MemeSoundSeed`; selecting a video enters the existing extraction handoff.
The picker dismisses only after the editor has accepted the seed. Back or
Cancel preserves the Create hub unchanged.

The sound-first editor opens in VIDEO mode with an empty canvas. Its empty
state becomes: **“Your sound is ready. Add a video to begin.”** Primary action:
**Add video**. Secondary action: **Change sound**. The attached soundtrack
row remains the existing source of truth for label, volume, loop and remove.

### Remix source picker

Present a native full-screen route on compact phones and a large sheet on
regular-width layouts. It has title **Remix a Bitz** and starts with two
explicit choices:

1. **Paste or scan a Bitz link** — primary. Accept only a bounded `nostr:`
   note/nevent target or a supported BitOS/Nostr URL that resolves to one.
2. **Your recent Bitz** — a bounded, account-scoped, verified event list;
   no server-side search or invented popularity ranking.

The first release deliberately excludes broad creator/media search. It is a
separate discovery feature and should not block the dependable link + own-work
path. The existing Bitz rail stays available for remixing any item encountered
while browsing.

After a source is chosen, show a short preflight—not a separate confirmation
maze—with:

- source poster, creator, event short ID and media type;
- current license/rights signal and attribution preview;
- a clear warning/confirmation for `RemixRules.ASK_REQUIRED_LICENSES`;
- a named loading state while a missing source is fetched from relays;
- primary **Open remix** and quiet **Cancel**.

Only an ID- and signature-verified event with a loadable, allowed media URL
can open the editor. The editor follows the existing behavior: downloads the
source into the session, clones the bounded `meme` layout with fresh IDs,
marks source/author/license read-only, and preserves lineage for the publish
flow. If an event is valid but has no supported media, offer **Create credited
post** through the existing seeded note-composer fallback; never pretend that
a blank studio project is a remix.

### Required states

| State | Sound picker | Remix picker |
| --- | --- | --- |
| Loading | Skeleton rows; existing cached rows remain usable. | Source lookup status names the stage: “Checking this Bitz on relays…”. |
| Empty | Explain no sounds are available; offer Pick sound from a video. | Explain that a link or one of the user's recent Bitz is needed; offer Paste link. |
| Offline | Local sound library remains available; network rails label unavailable. | Recent verified local events remain selectable; a missing remote source names offline and offers retry. |
| Invalid | Never attach a source with a bad URL, hash or decode result. | Name malformed/unsupported link or unverified event; keep paste field and current hub intact. |
| Rights warning | License is displayed, not silently inferred. | Ask-required license requires explicit continue; it never hides the source. |
| Cancel | No project/draft is created. | No project/draft is created; any fetch is cancelled. |

All rows, previews, close actions, pasted-link field, scanning action, rights
warning and primary actions need accessible names. Sound preview must expose
play/pause state and only one preview may play at once. Motion is never the
only indication of download, extraction or selection.

## Contract and architecture

The UI owns navigation and presentation state. It must pass one typed,
one-shot handoff to the native studio coordinator:

```text
StudioHandoff
  = Sound(MemeSoundSeed)
  | ExtractSoundFromVideo(local asset reference)
  | Remix(MemeRemixSeed)
  | CreditedPost(Remix attribution tags)
```

The handoff is consumed once; it is not placed in global mutable state,
navigation arguments that log payloads, or a persisted draft before the editor
accepts it. Both platform routers clear it on completion, cancellation and
account switch.

Shared BusinessCore owns all deterministic rules:

- NIP-19/NIP-21 target parsing and bounds for a pasted remix link;
- event/source eligibility, license policy, relay-hint limits, lineage-cycle
checks, tag construction and tag deduplication;
- soundtrack bounds, source normalization, hash/provenance tag rules and
trending ranking;
- fixtures for accepted and hostile sound/remix inputs.

Native adapters own photo/library access, QR scan permission/UI, verified
relay fetch lifecycle, downloads, audio extraction/decoding, asset storage,
preview players and cancellation. Views never access relays, stores or media
handles directly.

Existing safety rules remain mandatory:

- source URLs and downloaded bytes are bounded and hash-verified where a hash
  is supplied;
- source event ID and signature are verified before creating a remix seed;
- remix cycle/depth checks run before opening and again before signing;
- soundtrack/media uploads hash-verify before `sound`, `remix`, `p`,
  `attribution` or `meme` tags are signed;
- a source license is advisory context, not proof of permission or legal
  advice; attribution is automatic and cannot be edited away.

## Implementation tasks

| Task | Owner | Scope and definition of done |
| --- | --- | --- |
| `MEM-003a` | SBC | Add/use a bounded shared parser for pasted note/nevent/URL targets and fixtures for valid, malformed, oversized and unsupported targets. No UI or relay code in shared core. |
| `MEM-003b` | IOS/AND | Build `SoundSourcePicker` with the four specified sections. Reuse `SharedSoundStore`, trending ranking, local library and existing editor extraction; no duplicate fetch/rank/attach code. |
| `MEM-003c` | IOS/AND | Enable the Create row, dispatch one `MemeSoundSeed`/extraction handoff, and render the sound-first empty editor state. Prove cancel leaves no slot. |
| `MEM-005a` | IOS/AND | Build `RemixSourcePicker`: paste/scan plus bounded account-scoped recent Bitz, explicit verified lookup, license preflight, cancellation and existing credited-post fallback. |
| `MEM-005b` | IOS/AND | Enable the Create row and route verified `MemeRemixSeed` through the existing editor/download/clone/publish path. Do not duplicate `RemixRules` or publish-tag assembly. |
| `MEM-005c` | SBC/IOS/AND | Add common and adapter fixtures for source mismatch, cycle, relay timeout, source disappearing during download, unsupported media and account switch. |
| `EDT-013` | QA/IOS/AND | Device accessibility and narrow-layout audit for both pickers, paste/scan, preview playback, loading/error/retry and large text. |

Implement in that order. `MEM-003b/c` is independently releasable first;
`MEM-005a/b` follows only after the source-target fixtures land.

## Acceptance tests

1. Create → Start with a sound → Shared sound → Studio opens VIDEO mode with
   soundtrack and no clip; Add video leads to the normal editor and publish
   preserves automatic sound provenance without re-uploading a shared source.
2. Create → Start with a sound → local video extracts audio with named progress;
   cancellation/error returns to the source picker or hub without a partial
   project.
3. Create → Remix a Bitz → paste a valid source → verified preflight → Open
   remix creates an editable session with read-only lineage and cloned layout.
4. A restrictive license prompts; cancel produces no draft. A permissive or
   absent license remains visible and does not add an unnecessary confirmation.
5. A malformed link, unverified event, bad URL, oversized download, no-loadable
   media, cycle, relay timeout or account switch publishes nothing and exposes
   a specific recovery action.
6. An unsupported-media source offers the seeded credited-post fallback; its
   tags retain attribution and do not claim a studio remix.
7. Screen reader and keyboard/switch tests identify every selection, preview
   state, warning and error; Android/iOS layouts remain usable at large text.

## Documentation and rollout

- This document is the implementation plan; update
  `docs/product/ux-ui-flows.md` §10 with the canonical flow and acceptance
  criteria.
- Update `docs/native/meme-studio-plan.md` to retire its stale assertion that
  the two hub rows are merely disabled M0 placeholders.
- Record each delivered task in `docs/native/delivery-plan.md` and
  `docs/native/native-ui-build-tracker.md`.
- Feature flags: `studio_sound_source_picker` and `studio_remix_source_picker`
  default off outside internal builds. A kill switch hides the corresponding
  new entry only; it never removes drafts, existing sound/remix actions on
  Bitz cards, local sound-library assets or direct relay access.
- Roll out sound first, then remix after the fixture and real-device relay
  recovery matrix passes. Both paths require iOS and Android parity before
  general availability.
