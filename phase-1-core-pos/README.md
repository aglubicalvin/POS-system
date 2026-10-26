# Phase 1 — Core Offline-First POS (vertical slice)

Phase 1 is the foundation everything else stacks on. **The code for this phase
is not duplicated here** — it is the single codebase at the repository root:
`src/` (device/till), `server/` (backend), `tests/`, `contract/`.

## Scope

- **Offline-first TypeScript till** (`src/`): local SQLite store, complete
  domain — money (integer pesewas), stock ledger, checkout, payments,
  shifts, approvals, users, devices, receipts, outbox.
- **Kotlin authoritative server** (`server/`, Ktor + PostgreSQL): every event
  from a till is repriced from its own catalog and applied exactly once via
  `EventApplier`; canonical PostgreSQL DDL in
  `server/src/main/resources/db/schema.sql`.
- **Cross-language contract** (`contract/events.json`, generated from real
  client code) pinned by tests on both sides: TypeScript suits
  (`tests/`, 95 tests) and Kotlin tests (34) against real embedded PostgreSQL.
- **Sync in both directions**: device→server outbox drain; server→device
  `GET /sync/pull` with per-source cursors and `contract/pull.json`.

## How to verify

```bash
npm install && npm test        # 95 TS tests
npm run typecheck
npm run demo                   # full offline lifecycle demo
cd server && ./gradlew test    # 34 Kotlin tests, real PostgreSQL
```
