# Multi-server meme video upload

## Video duration and Nostr event kind

Nostr does not impose a video-duration limit. Native Studio defaults to
**Full** (the whole selected source timeline, bounded by the four-hour project
safety limit), and offers 10 s, 60 s, 3 min and 5 min timeline profiles: the
first two publish NIP-71 kind
`22` (short video) and the longer profiles publish kind `21` (normal video).
This is selected from the creator's publishing profile, rather than exported
orientation. A durable retry derives the same result from the final duration.

The current BitOS endpoint accepts at most 100 MiB. Long profiles never use
the automatic duration-cut ladder: an oversize render fails before signing so
the creator can lower export quality. Supporting 10–60 minute uploads needs a
resumable, large-file BitOS media endpoint and file-backed background render;
that is a hosting/runtime capability, not a Nostr kind limitation.

## Policy

`MemeUploadRouting` is the cross-platform source of truth:

| Rendered video size   | Required verified destinations | Event media URL |
| --------------------- | ------------------------------ | --------------- |
| `< 20 MiB`            | BitOS API and Blossom          | BitOS API       |
| `20 MiB` to `100 MiB` | BitOS API                      | BitOS API       |
| `> 100 MiB`           | Refuse before signing          | —               |

The boundary is byte-based (`20 * 1024 * 1024`), so it is stable across
platforms. Images and GIFs retain their existing Blossom path.

## Verification and publishing

1. Rendered bytes are durably saved in the native publish job.
2. The client computes SHA-256, uploads to BitOS, and verifies the exact
   public object bytes before accepting its URL.
3. For a small video, it uploads the same bytes to Blossom and verifies the
   Blossom descriptor hash. A failure of either required destination blocks
   signing; retries reuse the durable rendered bytes.
4. The BitOS HTTPS URL and verified hash are used for the NIP-71 event.
   Blossom is an availability replica, not a second signed event or a
   competing canonical URL.
5. Only then does the existing receipt machine sign and publish to the
   account's NIP-65 write relays (falling back to the bounded app defaults).
   Each accepted relay receipt is retained; an ambiguous timeout is never
   blindly re-signed or re-published.

## BitOS API contract

The native endpoint is `https://social.bitos.space/api/media/upload` with a
raw `POST`, `Content-Type`, `X-Upload-Filename`, and `X-SHA-256`. It must
return an HTTPS `url` pointing at the immutable uploaded object.

The deployed API does not currently return a verified digest. Native apps
therefore read the returned object and compare its SHA-256 before signing.
This is secure but doubles transfer bandwidth. The web API should next require
a valid Nostr upload authorization and return `{ url, sha256, mimeType,
bytes }`; clients can then compare the server digest without read-back. Do not
relax native verification until that migration is deployed and tested.

## Mass-production operation

Queue immutable render revisions, limit concurrent upload jobs per platform,
and persist destination-level completion before starting the next effect. A
small video is complete only when both destinations verify; a large video is
complete only when BitOS verifies. Relay publishing is a separate effect after
the upload record is durable. Retrying a failed replica must never create a
second signed event.
