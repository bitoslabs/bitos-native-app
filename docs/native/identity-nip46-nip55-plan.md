# NIP-46 and NIP-55 Identity Delivery Plan

## Status

Planned. This document turns the already-reserved ledger work into
implementation slices:

- `SBC-008` — signer, NIP-46 and NIP-55 contracts/state machines;
- `ID-006` — NIP-46 remote signing on iOS and Android;
- `ID-007` — NIP-55 Android signer integration;
- `ID-009` — threat review and recovery validation.

The authoritative protocol references are [NIP-46: Nostr
Connect](https://github.com/nostr-protocol/nips/blob/master/46.md) and
[NIP-55: Android Signer Application](https://github.com/nostr-protocol/nips/blob/master/55.md).
Both are optional/draft protocol support and must remain isolated behind
read/write feature flags until interoperability validation is complete.

## Outcome and scope

Users can add an existing Nostr identity without disclosing its secret key to
BitOS:

1. iOS and Android connect to a NIP-46 bunker/remote signer.
2. Android connects to a NIP-55 signer application.
3. Existing compose, profile, social and upload-authorization operations wait
   for signer approval and publish only a verified signed event.
4. Signer denial, timeout, app backgrounding or relay loss preserves the
   original draft/job and returns to the exact pending action.

Initial release scope is **event signing** and the event signatures needed for
existing Blossom upload authorization. NIP-04/NIP-44 application
encryption/decryption operations are a later DM slice; they are not a
prerequisite for publishing through NIP-55.

Non-goals:

- BitOS does not operate a bunker, custody keys, proxy signer traffic, or add
  an identity backend.
- BusinessCore never owns sockets, platform intents, Keychain/Keystore values,
  or private transport keys.
- Background jobs do not launch an external signer UI. They persist an
  `awaiting signer` state until the user resumes in the foreground.

## Architecture decision

```text
native UI intent
  -> existing BusinessCore composer creates canonical unsigned event
  -> native SignerSession requests approval
       -> local-key signer
       -> NIP-46 relay-connected bunker (iOS / Android)
       -> NIP-55 Android signer app (Android only)
  -> native adapter verifies exact returned signed event
  -> existing relay publisher publishes the verified event
```

### Signer boundary

The current `IdentitySigner.sign(message32)` port is insufficient as the
public protocol boundary: NIP-46 and NIP-55 sign event JSON and may return a
fully signed event. Replace it with a typed event-approval contract, while
keeping raw secret material inaccessible to feature code.

BusinessCore owns:

- `SignEventRequest`: canonical, bounded unsigned event and account scope;
- `SignEventResult`: signed event or a typed outcome;
- signer capability declarations and stable error codes;
- validation that a returned event has the requested pubkey and exactly the
  requested kind/content/tags/created-at before it can enter the publisher.

Native code owns:

- signing UI, cancellation and lifecycle;
- NIP-46 relay transport and NIP-44 transport encryption;
- NIP-55 intents/activity results and package selection;
- Keychain/Keystore persistence of native-only connection secrets;
- platform error mapping to shared stable codes.

The adapter independently re-computes event ID and verifies BIP-340 signature
before dispatching a success result. A signature or event for a changed
payload, different pubkey, or unexpected request ID is rejected and never
published.

### Stored account projection

Persist only a public, versioned account projection. Migrate the registry to a
new schema rather than inferring signing method from secret-store presence.

| Signer kind | Persisted public metadata | Native secret material |
| --- | --- | --- |
| Local key | Existing pubkey/npub and local-key kind | Existing Keychain/Keystore secret slot |
| NIP-46 | User pubkey, bunker pubkey, bounded relay URLs, label and connection version | Client transport private key, sealed by Keychain/Keystore |
| NIP-55 | User pubkey, selected signer package name and protocol version | None held by BitOS |

Never persist or log an `nsec`, bunker connection token, raw authorization
event, returned event payload, signature, DM plaintext, or unpublished media.

## NIP-46 design

Support both user-friendly connection methods:

1. Scan or paste a `bunker://` URI where the signer endpoint is already known.
2. Generate a `nostrconnect://` pairing URI/QR, then wait for bunker approval.

The native `Nip46SignerSession` has a dedicated relay lifecycle. It must not
share feed subscriptions: signer traffic has different sensitivity, bounded
request lifetime and explicit cleanup requirements.

- Validate scheme, hex keys, relay URL policy and byte/count bounds before any
  connection.
- Generate the client transport key natively; store it only after the user has
  confirmed the connection.
- Correlate every request/response with a unique bounded request ID.
- On connection, call `get_public_key` and require it to match the account the
  user confirms before saving the account.
- Permit only the methods required for the active slice. Do not request broad
  persistent permissions by default.
- Close request subscriptions and cancel retry on approval, denial, timeout,
  account switch, logout or account removal.
- Reconnect with capped jitter only while an explicit foreground request is
  waiting. Never maintain an invisible perpetual bunker connection.

## NIP-55 design

NIP-55 is Android-only. The initial slice uses foreground intents, which keep
approval visible to the user.

- Add the `nostrsigner` package-query declaration to the Android manifest.
- Discover installed handlers and invoke `get_public_key` once during account
  setup; store the resulting user pubkey and selected signer package.
- Pin subsequent intent requests with that package name. Do not re-run signer
  discovery or `get_public_key` in normal signing loops.
- Use an Activity Result contract with a request ID; restore a pending request
  through activity recreation/process recovery from durable state.
- Validate the returned signed event against the original `SignEventRequest`
  before publishing.
- Treat app absent, wrong package, user cancellation, refusal, malformed
  response and timeout as distinct recoverable states.

Content Resolver support is a follow-up. It may be used only after a user has
specifically granted remembered permissions for narrow methods/kinds. It must
not be introduced as an invisible background signing path.

## Infrastructure and operations

No BitOS service, database, or custody system is added. Canonical data remains
the signed Nostr event; backend rows remain rebuildable projections.

NIP-46 requires direct client-to-relay transport with these controls:

- `wss` URLs only; apply the existing relay URL validation policy;
- maximum five signer relays, bounded URI/event/request sizes, and one active
  request per correlation ID;
- deadline-based requests, capped exponential backoff with jitter, and
  explicit `CLOSE`/socket teardown;
- a separate connection budget from feed relays so a malicious bunker relay
  cannot consume feed capacity;
- no relay URL, pubkey, event contents, authorization payloads or signatures
  in diagnostics.

Allowlisted telemetry fields are: signer kind, stable outcome code, duration
bucket, connection outcome, app version and feature-flag cohort. Aggregate
only; do not include account identifiers.

Feature flags:

| Flag | Default | Kill-switch behavior | Removal condition |
| --- | --- | --- | --- |
| `identity_nip46_enabled` | Off outside internal builds | Blocks new NIP-46 setup and requests; does not delete accounts/drafts | Two supported bunker implementations pass phased rollout guardrails |
| `identity_nip55_enabled` | Off outside internal Android builds | Blocks new NIP-55 setup and requests; does not delete accounts/drafts | Two signer applications pass phased rollout guardrails |

## Implementation backlog

| Work item | Owner | Dependencies | Definition of done |
| --- | --- | --- | --- |
| `SBC-008a` Typed signer contract | SBC | None | `SignEventRequest`/`Result`, capabilities and stable errors are versioned, bounded and common-tested; no secret can enter shared state. |
| `SBC-008b` NIP-46 pure protocol | SBC | `SBC-008a` | URI parser, request correlation and connection reducer handle valid, malformed, expired, cancelled and relay-switch cases through fixtures. |
| `SBC-008c` Registry v2 | SBC/IOS/AND | `SBC-008a` | Public signer metadata migrates safely; corrupt or unknown rows do not crash or cause cross-account signer use. |
| `ID-006a` NIP-46 transport | IOS/AND | `SBC-008b` | Dedicated cancellable relay session implements connect/get-public-key/sign-event and validates every response. |
| `ID-006b` NIP-46 onboarding | IOS/AND/PD | `ID-006a` | Paste/scan + pairing QR flows show signer/account/relay summary, confirm npub, and retain no unconfirmed connection secret. |
| `ID-006c` Publisher integration | IOS/AND | `ID-006a` | Notes, profile/social operations and upload authorization work with a remote signer; denial sends no relay frame and preserves work. |
| `ID-006d` NIP-46 cleanup | IOS/AND | `ID-006a` | Switch, sign-out, removal, timeout and app termination cancel requests, close subscriptions and wipe client transport keys where required. |
| `ID-007a` NIP-55 setup | AND | `SBC-008a` | Manifest query, discovery, one-time public-key request, explicit package pinning and visible account confirmation work on device. |
| `ID-007b` NIP-55 sign path | AND | `ID-007a` | Activity-result request is rotation/recovery safe; result verification blocks changed/malformed events. |
| `ID-007c` NIP-55 resolver follow-up | AND/SEC | `ID-007b`, threat review | Narrow pre-authorized Content Resolver methods work without foreground UI; no broad default permission is requested. |
| `ID-007d` Encryption follow-up | AND/SBC | DM security epic | NIP-04/NIP-44 operations have their own data classification, encryption tests and UX; not bundled with signing launch. |
| `ID-009` Security and QA gate | SEC/QA | All above | Threat review, redaction, app-kill/recovery, account-isolation and real-signer interoperability gates pass. |

## UX and recovery requirements

- Replace current “Soon” cards only when their feature flag is enabled.
- Explain that the signer approves the action and retains the secret key.
- Before every sign/payment, show the active identity and signer type.
- “Awaiting signer” identifies the selected signer and offers **Open signer** and
  **Cancel wait**.
- Cancellation is not an error and does not discard the user’s draft, upload,
  or durable publish job.
- External signer methods never offer an nsec backup reveal because BitOS does
  not hold the secret.
- Account removal names the account, removes its native signer configuration,
  and never attempts to revoke access remotely without a protocol-supported,
  user-confirmed action.

## Test and release gates

### Fixtures and automated tests

- Protocol fixtures: NIP-46 URI variants, malformed/oversized URLs, relay
  lists, request ID mismatch, stale response, denial, timeout and changed
  returned event.
- Shared tests: intent/event equivalence, exact-payload validation,
  account-scope isolation, schema migration and redacted descriptions.
- Native adapter contracts: cancellation, reconnect caps, app kill while
  awaiting signer, account switch during a request, and no publish after
  refusal.
- Android instrumentation: no signer installed, multiple handlers, package
  pinning, signer cancellation, result delivery after rotation and process
  recreation.
- iOS/Android E2E: local key plus two independent NIP-46 bunkers; Android
  additionally tests two independent NIP-55 signer apps.

### Performance and accessibility

- No identity/signer I/O on the first-frame path.
- Signer wait is cancellable and cannot retain a view, activity or websocket
  after the operation ends.
- VoiceOver/TalkBack announce signer state, active identity, denial and retry
  actions; large text and keyboard/switch navigation are tested.

### Rollout

1. Internal builds with synthetic fixtures and deterministic signer fakes.
2. Dogfood against two independent implementations per supported protocol.
3. Small cohort behind the feature flag, observing only allowlisted aggregate
   outcomes and crash/ANR guardrails.
4. Expand after a seven-day soak with no release-blocking identity, privacy or
   data-loss defect.

Rollback disables new setup and new requests through the flag. It never
silently removes locally saved accounts, drafts, identity export, or direct
relay access.

## Documentation changes required by each implementation PR

- Update `docs/engineering/architecture.md` with the signer session boundary
  and registry schema version.
- Update `docs/product/ux-ui-flows.md` to replace the relevant “Soon” state
  with shipped flows and signer-wait/recovery copy.
- Update `docs/native/nostr-infrastructure.md` with supported NIP-46/NIP-55
  profiles and relay limits.
- Update `docs/native/delivery-plan.md` progress log when each ledger item is
  delivered.
- Add an ADR before shipping because the change introduces a persistent schema,
  security boundary and protocol-support decision.
