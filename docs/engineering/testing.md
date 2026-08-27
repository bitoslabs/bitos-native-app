# Testing Strategy

## Test pyramid

1. Pure BusinessCore/MediaCore unit and property tests: fastest and broadest.
2. Native adapter contract tests: database, signer, relay, background upload and media bridges.
3. Feature UI tests: state rendering and user intents.
4. Backend integration tests: real PostgreSQL/queue/object/relay fixtures.
5. Cross-platform E2E: a few critical creator/viewer/recovery journeys.
6. Real-device performance, battery, thermal and codec matrix.

## Non-negotiable suites

- NIP-01 verification; NIP-71 video; NIP-22 comments; replacement/deletion; malformed/oversized events.
- Project migrations and unknown-field/limit behavior.
- Publish transition table with failure/cancel/retry at every stage.
- App kill during record, render, upload, signer wait and relay reconciliation.
- Media inputs: rotated, VFR, HDR, no audio, corrupt, truncated, unusual sample rate and hostile dimensions.
- Account isolation and secret/telemetry redaction.
- Screen readers, large text, reduced motion, RTL and keyboard/switch access.
- Offline, loss, latency, relay outage, primary-media outage and fallback mirror.

## Test data

Fixtures are synthetic or anonymized. Never commit production keys, DMs, private media, wallet secrets or signed authorization events that remain usable. Deterministic test keys are marked fixtures and blocked from production configuration.

## Flake policy

A flaky test is a defect. Quarantine requires an owner, issue, expiry and preserved signal. Do not hide it behind unconditional retries.

