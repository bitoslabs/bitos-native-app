# ADR 0001: Remote signer boundary (NIP-46 / NIP-55) and registry schema v2

- Status: proposed
- Date: 2026-09-17
- Owners: SBC / IOS / AND / SEC

## Context

BitOS must let users bring an existing Nostr identity without disclosing
its secret key (docs/native/identity-nip46-nip55-plan.md). Two optional
protocols cover this: NIP-46 (relay-connected bunker, iOS + Android) and
NIP-55 (Android signer app). The previous `IdentitySigner.sign(message32)`
port cannot express either: both approve EVENT JSON, may return a fully
signed event, and carry denial/timeout/malformed outcomes a raw signature
cannot. The account registry (schema v1) also had no notion of signer
kind, forcing sign-method inference from secret-store presence — exactly
what the plan forbids. Introducing this changes a persistent schema, a
security boundary and a protocol-support decision, so it needs an ADR
before the flags ever ship on.

## Decision

1. **Typed event-approval contract (SBC-008a).** Feature code signs
   through `EventApprovalSigner.approveEvent(SignEventRequest):
   SignEventResult`. The request carries the canonical bounded unsigned
   event plus account scope; the result is typed (Signed/Denied/TimedOut/
   Unavailable/Rejected/Cancelled/Invalid) with stable redacted outcome
   codes. An independent `SignedEventValidator` gate (exact payload
   match, canonical id recomputation, BIP-340 verify) runs before any
   returned event may reach the publisher — for every signer kind,
   including local keys.
2. **Signer session boundary.** BusinessCore owns the pure protocol
   (NIP-46 URI codec, JSON-RPC envelopes, NIP-44 wrap, request-correlation
   reducer, validation); native code owns sockets, intents, secure
   storage, CSPRNG and lifecycle. NIP-46 traffic runs on a dedicated
   relay lane (own sockets, ≤5 wss signer relays, deadline-driven, alive
   only inside a request window) — never the feed `RelayPool` budget.
   Secrets (account keys, NIP-46 transport keys) cross shared code only
   as function arguments and live exclusively in Keystore/Keychain.
3. **Registry schema v2 (SBC-008c).** Rows gain `signerKind` plus public
   per-kind metadata (bunker pubkey + relay set + label; NIP-55 pinned
   package). v1 rows decode as LOCAL_KEY; unknown kinds and corrupt
   remote metadata degrade a row to READ_ONLY — never to LOCAL_KEY, never
   to another account's signer. No secret ever enters the registry.
4. **Dark launch.** `identity_nip46_enabled` / `identity_nip55_enabled`
   (default off; internal builds only) gate NEW setup and NEW requests.
   Blocking never deletes accounts, drafts, identity export or relay
   access. Removal requires two independent implementations per protocol
   to pass the plan's phased rollout guardrails.

## Alternatives considered

- **Extend `sign(message32)` with response side-channels** — rejected:
  every caller must re-derive trust; typed outcomes cannot be faked
  through a nullable hex string.
- **Infer signing method from secret-slot presence** — rejected: plan
  explicitly forbids it; a wiped slot would silently change account type.
- **Reuse the feed relay pool for signer traffic** — rejected: signer
  lanes have different sensitivity, bounded lifetime and explicit
  cleanup; a hostile bunker relay must not consume feed capacity.
- **Do NIP-04/NIP-44 app encryption in the same slice** — deferred
  (ID-007d): separate data classification and UX, not a prerequisite for
  event signing.

## Consequences

- All publish/upload paths on both platforms now share one signing seam;
  local-key signing gains the same tamper gate as remote signers.
- iOS bridges the reducer through String-only state tokens; both platforms
  drive the SAME tested state machine.
- Setup flows persist nothing until the user confirms the pubkey the
  signer itself returned (ephemeral setup transport key on iOS; in-memory
  setup key on Android).
- Follow-ups: camera QR scanning for bunker links, Content Resolver
  pre-authorization (ID-007c), NIP-44 DM operations (ID-007d), real-signer
  interoperability gates (ID-009).

## Rollout and rollback

Internal builds flip the flags; a kill-switch flip blocks new setup and
new requests immediately without touching stored state. Registry v2
decodes v1 wires transparently; older builds reading a v2 wire yield an
empty registry (bounded, non-destructive — no cross-version signer
guessing). Telemetry may carry only the allowlisted aggregate fields
(signer kind, outcome code, duration bucket, connection outcome).
