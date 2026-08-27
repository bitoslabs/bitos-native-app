# Studio Mass-Production System

## 1. Product promise

Studio turns one creative recipe into many reviewable, deterministic media outputs without weakening local-first ownership. A creator can make one video or meme, bind a data set, review every variant, render in a durable queue, and publish only explicitly approved outputs.

Mass production is not a hidden server-side content generator. Projects, recipes, asset hashes, approvals, and signing intent remain visible and auditable.

## 2. Information architecture

```text
Studio
├── Projects
├── Templates
├── Assets
├── Batch jobs
└── Exports

Batch job
├── Recipe and version
├── Input rows
├── Per-row overrides
├── Validation report
├── Render queue
├── Review decisions
└── Publish jobs
```

A recipe is immutable after a batch begins. Editing it creates a new version so reruns remain reproducible.

## 3. Core concepts

- **Project:** versioned timeline, scene, audio, canvas, caption, and publish metadata.
- **Template:** reusable project with declared input slots and safe constraints.
- **Recipe:** template version plus output profile, naming rules, and publish defaults.
- **Input row:** typed values for one output, never an unbounded script.
- **Variant:** fully resolved project generated from one row.
- **Batch:** durable collection of variants with aggregate progress.
- **Approval:** an explicit decision tied to variant content and metadata hashes.
- **Publish job:** upload, verify, sign, relay, and reconcile state machine.

## 4. Supported inputs

Initial sources:

- manual rows;
- local CSV with preview and typed column mapping;
- duplicated project variants;
- local media folders selected through OS file pickers.

Later connectors must import through the same bounded input contract. No connector can receive signing keys or publish directly.

Slot types are `short_text`, `long_text`, `number`, `color`, `image_asset`, `video_asset`, `audio_asset`, `timestamp`, and `enum`. Each slot declares required status, size/range, allowed MIME types, overflow behavior, and fallback.

## 5. Creator flow

1. Choose **New project**, **Use template**, or **New batch**.
2. Build and preview a master project.
3. Mark editable fields as typed slots.
4. Select output profile, safe-area policy, file naming, and optional publish defaults.
5. Import rows and map columns to slots.
6. Resolve blocking validation issues before queueing.
7. Review a contact sheet and open any variant in the full editor.
8. Queue low-resolution proofs or final renders.
9. Approve individually or select valid variants and confirm a bounded bulk approval.
10. Export locally, save as drafts, or create publish jobs.
11. Sign each final Nostr event only after its media upload is hash-verified.
12. Reconcile relay acknowledgements and show partial success clearly.

Closing the app never loses accepted work. Reopening resumes from the durable state immediately preceding the interrupted effect.

## 6. Screen behavior

### Batch setup

- Top: recipe version and output profile.
- Center: spreadsheet-like rows with typed cells and per-cell validation.
- Bottom: counts for valid, warning, and blocked rows plus **Generate previews**.
- Mobile compact mode edits one row at a time; tablet uses a split grid and inspector.

### Review

- Contact sheet uses stable ordering and exposes row ID, duration, warnings, approval, and render state.
- Long press/multi-select enables bounded bulk actions.
- Tapping a card opens frame-accurate preview, metadata, diagnostics, and **Edit this variant**.
- Editing a variant creates an override; it never silently mutates the recipe or sibling variants.

### Queue

- Separate lanes for preview, final render, upload, and publish.
- Every item exposes queued/running/paused/retryable/blocked/completed/cancelled.
- Aggregate progress must not conceal individual failures.
- Thermal, storage, battery, background-time, and network constraints are stated in plain language.

## 7. Validation and preflight

Validation runs before preview, before final render, and before publish. Blocking rules include:

- missing or unreadable asset;
- hash mismatch;
- unsupported codec or MIME type;
- slot value outside declared bounds;
- text overflow without an allowed fit policy;
- duration, resolution, or file size over product limits;
- unsafe path, filename collision, or insufficient storage;
- missing caption/alt text where required by policy;
- publish metadata incompatible with the selected Nostr event kind;
- media not uploaded and verified before signing.

Warnings include safe-area risk, loudness risk, low source resolution, excessive render time, and partial relay availability. Warnings require acknowledgement but do not masquerade as errors.

## 8. Durable job model

```text
draft -> validating -> ready -> queued -> rendering -> rendered
      -> blocked                            -> failed_retryable
      -> cancelled                         -> failed_terminal

rendered -> uploading -> verifying -> awaiting_approval -> signing
         -> published_partial -> published
```

Every transition stores:

- job, batch, recipe, and variant IDs;
- account scope;
- input/project/content hash;
- monotonically increasing revision;
- attempt and next-retry time;
- progress and stable error code;
- created/updated timestamps;
- cancellation and approval records.

Workers claim jobs with leases. Effects use idempotency keys. Expired leases are reclaimable. A result for an obsolete revision is discarded.

## 9. Rendering model

- The project contract stores integer microseconds and rational frame/rate values.
- MediaCore resolves a variant into an immutable render plan before encoding.
- Preview and final render share evaluation logic; only output profiles differ.
- A render cache key includes MediaCore ABI, project schema/version, resolved project hash, source asset hashes, font/effect versions, and output profile.
- Device render is the default. Server render, if introduced, consumes the same signed manifest and never receives identity keys.
- Goldens cover representative frames, audio timing, text layout bounds, and export metadata.

## 10. Bulk-action safety

- No one-tap publish-all action from an unreviewed batch.
- Bulk scope always shows exact item count and exclusions.
- Approval binds project, media, caption, tags, event kind, and account hashes.
- Any content or metadata change invalidates the prior approval.
- Signing is just-in-time and per event; private keys never enter render workers or backend queues.
- Cancellation stops unstarted work and safely finishes or cleans up the current atomic operation.
- Destructive cleanup uses a recoverable trash window where platform storage permits.

## 11. Accessibility and internationalization

- Grid actions have list equivalents and do not require drag, hover, color, or precision gestures.
- Progress is announced without flooding assistive technology.
- Error cells expose the field, cause, and correction.
- Text slots support Unicode, bidirectional layout, dynamic type preview, and locale-specific templates.
- Captions, subtitles, alt text, and content warnings are first-class batch fields.

## 12. Operational limits

Limits are remotely configurable but locally enforced from a signed, cached policy. Initial conservative targets:

- 100 input rows per batch;
- 128 assets and 32 tracks per resolved project;
- 5-minute timeline;
- two concurrent previews and one final render per device;
- bounded retry with jitter for network effects;
- storage reserve before render and before download.

Raising a limit requires memory, thermal, recovery, and abuse testing on the oldest supported device tier.

## 13. Definition of done

A mass-production slice is complete only when:

- one recipe generates deterministic variants on iOS and Android;
- invalid rows cannot enter the render queue;
- the app survives kill/relaunch during render, upload, and relay publish;
- per-variant editing does not mutate siblings;
- approvals are invalidated by relevant changes;
- duplicate retries do not duplicate uploads or signed events;
- accessibility paths cover setup, review, correction, and cancellation;
- telemetry reports stage timing and stable failures without private content;
- low-storage, offline, thermal, and partial-relay scenarios are tested.

