# Clean Code, SOLID and SRP Rules

## 1. Core standard

Code is clean when another engineer can predict where behavior lives, understand its invariants, test it without the whole system and change one concern without breaking unrelated concerns.

## 2. SOLID for this repository

### Single Responsibility

A type has one reason to change. Separate parsing, policy, orchestration, storage and rendering. A 500-line class is a review signal, not an automatic failure; a 30-line class can still violate SRP.

### Open/Closed

Extend through typed strategies/capabilities where variants are expected: signer, media provider, ranking signal, render backend. Do not build generic plugin systems for hypothetical needs.

### Liskov Substitution

Every adapter must satisfy the shared contract, including cancellation, idempotency and error semantics. A fake that ignores these is not a valid substitute.

### Interface Segregation

Prefer `EventReader`, `EventPublisher`, `IdentitySigner` and `MediaUploader` over a single `NostrClient` with dozens of methods. Consumers depend only on capabilities they use.

### Dependency Inversion

BusinessCore declares ports. Native/infrastructure code implements them. Domain code does not import platform frameworks.

## 3. Naming

- Name by domain intent: `VerifyMediaDescriptor`, not `ProcessData`.
- Commands are verbs; state/models are nouns; booleans read as predicates.
- Include units: `durationUs`, `sizeBytes`, `createdAtSeconds`.
- Use `EventId`, `Pubkey`, `MediaHash`, `Sats`, `BasisPoints`, not interchangeable `String`/`Long`.
- Avoid `Util`, `Helper`, `Manager`, `Common`, `Base` unless the word is the real domain concept.
- Avoid abbreviations except established protocol terms such as NIP, NWC, URL and HTTP.

## 4. Functions

- One abstraction level per function.
- Prefer zero to three parameters; introduce a named request type when parameters form a concept.
- Replace boolean parameters with enums or separate functions.
- Return typed results; do not use sentinel strings/numbers.
- Validate at boundaries, then keep inner code operating on valid types.
- Separate query from command unless an atomic repository operation requires both.
- Make time, randomness and environment injectable in business tests.

## 5. Types and files

- Default to immutable data.
- Keep invariants inside constructors/factories and prevent invalid public states.
- Use sealed variants for finite workflows/errors.
- Expose the smallest visibility; internal/private by default.
- One primary public responsibility per file. Closely related small value types may share a file.
- Composition beats inheritance. No cross-feature base ViewModel/store.

## 6. Comments and documentation

Comment why a constraint exists, its protocol/security reason or a non-obvious tradeoff. Do not narrate obvious syntax. Public boundary APIs document ownership, units, threading, cancellation and error behavior.

TODO format:

```text
TODO(BEZ-1234): Remove legacy kind-1 video parsing after the documented support window.
```

No owner/issue means it is not a durable TODO.

## 7. Error handling

- Never swallow errors.
- Map infrastructure errors once at the adapter boundary.
- Retry only classified transient failures, with bounded backoff/jitter.
- A retry must be idempotent or use an idempotency key.
- Cancellation is normal control flow, not a generic failure toast.
- Preserve the user’s draft/output when signing or publishing fails.

## 8. Security and privacy coding

- Sensitive types implement redacted descriptions.
- Use allowlisted telemetry fields; never “log the whole object.”
- Bound bytes, list counts, string length, nesting, duration, dimensions and decoded pixels before work.
- Verify event/hash/signature at every trust boundary, even if a BitOS server provided it.
- No remote code, JavaScript, shader or raw SVG execution.
- Secrets stay in Keychain/Keystore/external signer and never enter BusinessCore state.

## 9. UI code quality

- Views render immutable UI state and emit user intents.
- Do not perform network/database/signing work in a view body/composable.
- Every asynchronous screen has loading, empty, content, partial, offline and error/retry behavior.
- Keep accessibility labels/actions beside the UI component.
- Extract a component because it has a reusable behavior/responsibility, not just to shorten a file.

## 10. Media code quality

- Timeline time uses integer microseconds; frame rate uses rational values.
- Preview and export consume the same evaluated project model.
- C ABI exposes opaque ownership and explicit lengths; no STL/exceptions across it.
- Every allocation and decoded stream has a budget.
- Do not optimize before measuring real devices, but never design an unbounded cache.

## 11. Review checklist

- Is the responsibility in the correct core/layer?
- Is behavior duplicated across platforms?
- Can invalid state be represented?
- Are units, account scope, schema version and idempotency explicit?
- What happens offline, on cancellation, app kill, storage full and partial relay ACK?
- Can logs/crashes expose secrets or user content?
- Are accessibility and localization preserved?
- Do tests exercise invariants rather than implementation trivia?
- Is the change smaller/simpler than the problem warrants?

