# Infrastructure scaffold

## Local

`docker compose -f infra/compose.yaml up --build` starts PostgreSQL, Valkey, an S3-compatible object store, and the API/indexer/worker scaffolds.

Optional profiles:

- `--profile nostr` starts a disposable local relay. The default image tag is for local convenience only; CI/production must replace `NOSTR_RELAY_IMAGE` with a reviewed immutable digest.
- `--profile blossom` expects a reviewed Blossom server checkout at `vendor/blossom-server` or `BLOSSOM_SERVER_CONTEXT`. Pin its release/commit before building.

Copy `infra/.env.example` to `infra/.env` and replace every local credential. Never reuse local values in staging or production.

## Production

`terraform/` intentionally begins with provider-neutral platform inputs and module boundaries. Cloud/provider resources are added only after the reference deployment ADR is approved. Production requirements are defined in `docs/native/nostr-infrastructure.md`.

No production deployment may use mutable container tags, public database/cache ports, default credentials, or a filesystem-only media origin.
