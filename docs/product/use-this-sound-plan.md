# "Use this sound" — sound sourcing from any video (TikTok loop)

> Status: **Wave A shipped** (shared contract, common-tested). Waves B–D
> planned. Companion tracker: `docs/native/native-ui-build-tracker.md`
> (MST-050); wire contract: `MemeSoundtrack`/`MemeSoundRules`
> (`shared/business-core`, `space.bitos.core.studio`).

## 1. Problem / opportunity

TikTok's core creation loop is "hear a sound → use it → your video feeds
the sound's virality". BitOS today has per-clip volume and synth SFX cues
only — no imported audio. The existing roadmap sources sounds from a
licensed marketplace (MST-047 kind-30078, APP-021). The cheaper, more
native bootstrap: **every existing bitz already carries an audio track**.
Let creators borrow it, with provenance Nostr can verify and credit.

## 2. UX (two entries, one pipeline)

1. **From any bitz** — feed card ⋯ menu, video player sheet and the remix
   flow gain **"Use this sound"**: download the source video (the M4b
   remix download machinery), extract the audio track, open the meme
   editor in VIDEO mode with the soundtrack attached (no clip yet — the
   creator adds theirs; TikTok parity: the sound IS the seed).
2. **Inside the editor** — the Sound tool gains a **"Pick sound from a
   video…"** row: photo-library video picker (and later "from my Bitz…")
   → extract → attach. One soundtrack per project: attach replaces
   attach; remove restores original clip audio.

Editor semantics (VIDEO mode only):

- Soundtrack row: label ("Original sound · author"), source chip
  (♪ + author), volume 0–2, remove. Original-clip audio keeps its
  per-clip volume — both mix at preview and export.
- Playhead sync: the soundtrack starts at `offsetMs`, plays from its
  `startMs` in-point, ends at its `durationMs`; scrub/seek moves both.
- Sound-cued images (MST-041) and synth SFX cues keep baking on top.

## 3. Provenance & protocol (the Nostr-native twist)

Every publish that borrows a sound stamps (built by `MemeSoundRules`,
exposed via bridge `memeSoundTagsFor`):

```text
["sound", <blossom-url>, <sha256-64hex>, <source-event-id>?]
["p", <source-author-pubkey>]                — only for a bitz source
["attribution", "sound of <label>"]          — ≤140 chars, web parity
```

- **Never before upload:** the URL exists only after the hash-verified
  Blossom upload; `tagsFor` stamps NOTHING for an un-uploaded soundtrack
  (repo safety rule: never sign before media is uploaded and verified).
- **Credit is automatic**, not typed by hand — the source note id and
  author ride the project wire (`sourceNoteId`/`sourceAuthorPubkey`).
- **Trending falls out for free:** counting `sound` tags over the feed
  window IS the usage rank (APP-021 bootstrap before any marketplace).
- **License policy** stays advisory like remix: restrictive source
  licenses (`RemixRules.ASK_REQUIRED_LICENSES`) ask for confirmation,
  never hide the action.

## 4. Wire contract (shipped in Wave A)

Additive v1-compatible project row `"sound"` (old readers ignore it;
junk degrades to "no soundtrack", never a failed decode):

```json
{"url":"https://…/audio.mp4","sha256":"<64hex>","ms":12000,
 "start":1000,"vol":0.8,"offset":500,"src":"<event-id>","author":"<pubkey>",
 "label":"Original sound · author"}
```

Bounds (MemeSoundRules): duration ≤ 60 s (`MemeVideoCutRules.MAX_CLIP_MS`),
volume 0–2, url ≤ 2048, label ≤ 80, in-point inside the audio, sha256
must be 64-hex canonical. Read side: `MemeSoundRules.sourceOf(tags)` is
the one seam the feed chip, re-attach and trending all use.

## 5. Waves (each shippable, leaves the app consistent)

| Wave | Scope | Definition of done |
| --- | --- | --- |
| **A — contract (shipped)** | `MemeSoundtrack` + codec row + `MemeSoundRules` (normalize/tagsFor/sourceOf) + bridge `memeSoundTagsFor` + common tests (round-trip, hostile, bounds, tag battery) | all common tests green; old wires decode unchanged |
| **B — editor attach + mixdown (both platforms shipped; iOS 2026-09-08)** | Sound tool "♪ Pick sound from a video…" → passthrough extraction (Android `MemeVideoSound`: extractor→muxer m4a ≤ 60 s + MediaCodec PCM decode; iOS `MemeVideoSoundIos`: AVAssetExportSession AppleM4A + AVAssetReader float PCM at the bed rate) → attach/replace/remove + volume (shared `SetSoundtrack` command on the iOS wire seam); dual-player preview glued to the stage clock; export mixes cues + soundtrack into ONE PCM bed (`MemeSoundMix`, common-tested) riding the existing second-sequence path (Media3 sequence / `memeAudioBedWavBase64` bridge seam); the m4a persists with the slot (asset `sound`) and rehydrates on resume (undecodable → row stripped + named notice, never a silent export); publish uploads the m4a hash-verified BEFORE signing, verifies sha vs the wire, then stamps sound/p/attribution with the real URL | attach → caption → export round-trip on device; common bed/command/seam tests + state tests green; nothing stamps pre-upload |
| **C — "Use this sound" from a bitz (both platforms shipped 2026-09-08)** | Bitz rail **Sound** action (video notes only) → bounded download (remix parity) → passthrough extraction → editor seeds VIDEO mode with the soundtrack and NO clip (TikTok's sound-first loop; the creator adds their own); `sourceNoteId`/`author` ride the wire → publish stamps sound/p/attribution with "Original sound · <author>"; notes carrying a `sound` tag render a ♪ chip in the Bitz meta row (Android `FeedNote.soundOf`; iOS via the bridge-note projection `soundUrl`/`soundSourceEventId`/`soundAuthorPubkey` → the Swift mirror) | end-to-end on device; provenance survives round-trip |
| **D — trending sounds (both platforms shipped 2026-09-08)** | More → **Trending sounds** rail over the LIVE feed window (APP-021
  bootstrap — no marketplace): shared `MemeSoundTrending.rank` (3-day
  half-life, dedup by url+sha, deterministic order) fed by `sound` tags
  (`FeedNote.soundOf` + `soundSha256` through the bridge note projection);
  "Use in Studio" re-attaches by URL through the Wave C editor path —
  hash-verified download, no extraction, pre-filled URL → publish stamps
  the existing artifact WITHOUT re-uploading | rank deterministic from fixtures; re-attach = Wave C path |

Out of scope V1: looping, per-overlay sound windows, voice-over record,
licensed marketplace (MST-047 stays separate), zap-splits to sound
authors (needs invoice + preimage verification — future).

## 6. Risks / notes

- **Extraction cost:** video download + audio transcode is seconds-scale;
  progress states must name the stage (UX §3). Passthrough extract where
  the container allows (m4a copy) before any transcode.
- **Sync drift in preview:** dual-player sync is ±tens of ms — acceptable
  for preview; the EXPORT mixdown is sample-accurate (single mix).
- **Wire growth:** one bounded object; MAX_WIRE_LENGTH unchanged headroom.
- **Cross-core rule:** extraction/mixdown is media-core (native) work;
  timing/bounds/tags are business-core (shared) — never the other way
  around (AGENTS.md).
