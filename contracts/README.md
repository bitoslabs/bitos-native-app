# Shared contracts

Contracts are versioned interoperability assets, not implementation details.

- `project-schema/`: persisted Studio documents accepted by BusinessCore and MediaCore adapters.
- `nostr/`: signed/unsigned golden event fixtures and BitOS extension profiles.
- `api/`: derived backend API contract. API objects never replace signed Nostr events.

Changes require compatibility notes, fixtures and all consumer checks.
