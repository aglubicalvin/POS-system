# PLAN — Implementation Plan

Status legend: **done**, **partial**, **blocked** (needs a decision or access).

## Phase 1 — Core POS and authoritative server (done)

| Task | Deliverable | Status |
| --- | --- | --- |
| Money, tax, discount domain | `src/domain/money.ts`, checkout rules, integer pesewas | done |
| SQLite schema + outbox | `src/db/schema.ts`, ordered sync primitives | done |
| Kotlin server + PostgreSQL schema | Ktor routes, `EventApplier`, canonical `schema.sql` | done |
| Sync both directions | `/sync`, `/sync/pull`, per-source cursors, contract fixture | done |
| Till first slice | PIN login, catalog, cart, cash checkout, receipt | done |

## Phase 2 — Payments, roles, shifts (done)

| Task | Deliverable | Status |
| --- | --- | --- |
| Roles and approvals | approval persistence, discount thresholds | done |
| Shifts and cash reconciliation | shift open/close, variance | done |
| Payments state machine | pending/confirmed/failed, idempotency keys | done |
| Mobile-money adapter + HMAC webhook | initiate, webhook, status; unsigned refused | done (no live credentials) |

## Phase 3 — Sales, reporting, inventory (done)

| Task | Deliverable | Status |
| --- | --- | --- |
| Stock ledger + on-hand cache | movements, adjustments, review queue | done |
| Daily sales and discrepancy reports | `/reports/*` | done |
| Receipts (text layout) | `renderReceipt`, on-screen detailed receipt | done |

## Phase 4.1 — Admin mobile app (done)

Auth gate, dashboard, RLS-backed reads, SecureStore sessions.

## Phase 4.2 — Product and price management (done)

Migrations 0002/0004, price/category RPCs, audit trail, catalog view, admin screens.

## Phase 4.3 — Customers, payroll, hire purchase (done)

Migration 0005 (+0006/0007 workflows), contracts/installments, payroll deductions,
reconciliation RPC, admin screens, Kotlin `Phase43WorkflowTest` (17 tests).

## Phase 4.4–4.6 — Verification and hardening (done)

| Task | Deliverable | Status |
| --- | --- | --- |
| Migration replay on real PostgreSQL | `AdminMigrationTest` (5), harness reads committed SQL | done |
| Schema invariance guard | `tests/schema.test.ts` compares canonical DDL | done |
| Policy hardening | migration 0008 retargets 15 policies to `authenticated` | done (dev + verify only) |
| Deployment gating docs | `docs/security-audit.md`, `docs/production-readiness.md` | done |

## Phase 4.7 — Fault lifecycle (done)

Migration 0009: `faults`, `approval_queues`, `is_supervisor()`,
`payments.blocked`, transition + blocking triggers, supervisor-only RLS; E2E block
un-skipped and green.

## Phase 4.8 — Clean replay and recovery evidence (partial)

| Task | Deliverable | Status |
| --- | --- | --- |
| Clean 0001–0009 replay on an empty project | verify project, history `0000`–`0009` | done |
| Rollback proofs | 0008/0009 in `CleanReplayMigrationTest` | done |
| Dev rebuild from committed sources with data preserved | `dev_backup` + restore, checksums | done |
| Production-sized backup/restore drill | needs `pg_dump` (Docker) or a restore-capable target | **blocked** — Docker daemon unavailable; dev-scale restore only |
| Six-view security assessment | REST negative tests, 18/18 | done |
| Secret-handling audit | git history + bundle scan | done |

## Phase 5 — Production readiness (blocked)

| Gate | Owner | Blocking issue |
| --- | --- | --- |
| Production parity baseline | release owner | no authorized read-only access yet |
| Production-sized restore rehearsal with RTO/RPO | release owner | tooling/target + measured timings |
| 0006/0007 forward-recovery sign-off | migration owner | procedure drafted, not rehearsed |
| Legacy JWT key retirement (by 2026-11-30, hard stop 2026-12-31) | release owner | client migration, then disable |
| PII retention decision (`customers.phone`) | product owner | policy unwritten |
| Audit-log retention/archival | product owner | policy unwritten |
| TLS/HSTS termination | infrastructure | not implemented (Ktor plain) |

## Dependencies between tasks

`0001` needs the canonical schema → `0002`/`0004` need `is_admin()` → `0008`
retargets policies from `0001` → `0009` needs `0007`'s payment shape and `0006`'s
tables. The admin app depends on all of them. Release gates depend on the dev
rebuild (done) and on production access (not yet granted).

## Estimated effort (remaining)

| Item | Estimate |
| --- | --- |
| Production read-only baseline + diff | 0.5 day (needs access) |
| Production-sized backup/restore drill with RTO/RPO | 1 day |
| 0006/0007 rollbacks **or** forward-recovery rehearsal sign-off | 1–2 days |
| Legacy key migration + verification | 0.5 day |
| PII + audit retention policy documents | 0.5 day |
| TLS/HSTS + reverse proxy config | 0.5 day |

## Development sequence (next)

1. Close the recovery gate (drill + timings) — highest value, no product change.
2. Obtain the production baseline; diff against the reference fingerprint.
3. Write the two retention policies; apply legacy-key migration.
4. Then, and only then, resume feature work (till voids/refunds, MoMo screens,
   ESC/POS encoder).

## Milestones and deliverables

| Milestone | Deliverable | Status |
| --- | --- | --- |
| M1 Offline POS | sell offline, sync, server authority | done |
| M2 Admin surface | RLS-backed app, migrations 0001–0009 | done |
| M3 Verified migrations | clean replay + rollbacks + fingerprint | done |
| M4 Verified security | 43/43 E2E, view assessment, secret audit | done |
| M5 Recoverable | production-scale restore with measured RTO/RPO | **open** |
| M6 Parity | production matches reference | **open** |
| M7 Release | all gates closed, GO recorded in readiness doc | **open** |
