# Development Workflow and Quality Gates

## 1. Work item shape

Every production task states:

- user outcome and non-goals;
- owning layer and platforms;
- protocol/schema impact;
- security/privacy classification;
- offline, cancellation and recovery behavior;
- accessibility/localization behavior;
- metrics and performance budget;
- acceptance tests and rollout/kill switch.

Use task IDs from `docs/native/delivery-plan.md`. Split a task when it cannot be reviewed, tested or rolled back independently.

## 2. Vertical-slice order

1. Write or update the shared contract/fixture.
2. Implement pure BusinessCore rule and tests.
3. Define native ports and focused fake adapters.
4. Implement iOS and Android adapters.
5. Implement native UI states and accessibility.
6. Add backend behavior only when the feature needs derived assistance.
7. Add integration/E2E, performance and recovery tests.
8. Update architecture, UX and runbook documentation.

Media features insert MediaCore model/golden work between steps 2 and 3.

## 3. Branch and commit policy

- Branch from an up-to-date main branch using `feature/ID-description`, `fix/ID-description` or `infra/ID-description`.
- Keep commits reviewable and buildable where practical.
- Never mix toolchain upgrades with product behavior.
- Never rewrite or discard unrelated user work.
- Generated artifacts are committed only when the build/release requires them and their provenance is documented.

## 4. Pull request requirements

The PR description includes outcome, screenshots/video for UI, architecture boundary, changed contracts, tests/devices, failure/recovery scenarios, privacy changes, migration, rollout, rollback, flags and known follow-ups.

Required review ownership:

- BusinessCore: shared-core and one native consumer reviewer.
- MediaCore: media owner and affected platform reviewer.
- Protocol: Nostr owner and fixture/interoperability reviewer.
- Security boundary: security reviewer.
- Schema/migration: data owner and rollback reviewer.
- UX flow: product/design and accessibility reviewer.

## 5. Local gates

Run `make check`. If a target is unavailable, run every available lane and state the omitted lane explicitly. CI remains authoritative.

| Change | Required check |
|---|---|
| Business rule | Common test and affected native adapter contract |
| Nostr | Golden fixtures and external-shaped fixture |
| iOS UI | Build/test and VoiceOver/Dynamic Type state |
| Android UI | Unit/instrumented test and TalkBack/font scale state |
| Media | C++ tests and both-platform preview/export conformance |
| Service | Typecheck/unit/integration and telemetry/error contract |
| Infrastructure | Format/validate/plan and security/cost review |
| Migration | Upgrade from every supported version and rollback/forward recovery |

## 6. Feature flags

Flags are for controlled rollout and emergency disablement, not permanent branches. Every flag has an owner, task/ADR, default per environment, exposure metric, kill-switch behavior, removal condition and date.

Never use a server flag to remove access to a user’s local drafts, identity export or direct-relay core reader.

## 7. Release flow

```text
merge -> immutable artifacts -> staging migrations/deploy
      -> protocol/media smoke -> native internal builds
      -> security/performance/accessibility gates
      -> phased store rollout -> observe -> expand
      -> remove compatibility/flag after support window
```

Release candidates pin BusinessCore, MediaCore, schemas, service images and infrastructure revision. Servers support the current mobile release and at least one prior supported contract version.

## 8. Incident and hotfix flow

1. Protect users/data first with the narrowest kill switch.
2. Preserve evidence without collecting secrets/content.
3. Classify protocol, key, media, data-loss, availability or privacy impact.
4. Patch on a release branch with regression test.
5. Roll forward when safe; roll back only if migrations/artifacts permit it.
6. Publish an internal timeline and follow-up owners.
7. Add the missing test/runbook/alert; do not stop at the code fix.

## 9. Definition of done

Use the complete definition in `docs/native/delivery-plan.md`. “Works on my device,” mock-only UI, or a happy-path API response is not Done.

