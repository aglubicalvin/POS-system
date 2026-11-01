# Production-readiness review — POS backend, admin app, migrations 0001–0009

Status: **IN PROGRESS — verdict NO-GO** (2026-11-01).
Scope: migrations `0001`–`0009`, RLS policies, RPC grants, triggers, the canonical
POS schema, the admin mobile app's protected reads, payment/reconciliation
integrity, and the deployment/rollback procedure.

No production change has been made or is authorized by this document. Every
claim below is either (a) reproduced from the repository, or (b) observed on the
disposable verify project `numwzjogaanfhlrfgrqk` (London) by a read-only probe.
Claims that could not be verified are marked **OPEN**, not assumed.

## 1. Release checklist

| # | Area | Required evidence | Status |
| --- | --- | --- | --- |
| 1 | Database | Clean replay of 0001–0009 on an empty project, history matching the repo | **PASS** (2026-11-01, verify project) |
| 2 | Database | Production catalog matches the verified fingerprint | **OPEN** — no authorized production access; do not assume parity |
| 3 | Database | Dev project reconciled with the committed chain | **FAIL** — dev is at 0001–0005 (see §3) |
| 4 | Security | RLS on every client-reachable table; anon denied at REST | **PASS** (43/43 E2E, catalog probe) |
| 5 | Security | RPC EXECUTE grants limited to `authenticated`; anon denied | **PASS** |
| 6 | Security | SECURITY DEFINER functions pinned to `search_path = ""` | **PASS** — all 12 |
| 7 | Security | Secrets never in the repo or the app bundle; rotation plan | **OPEN** — `.env` holds a service-role key on dev machines |
| 8 | Security | Legacy JWT anon/service keys disabled after client migration | **OPEN** — both legacy and new keys are enabled on the verify project |
| 9 | Payments | Only the server/DB moves payment state; clients cannot confirm | **PASS** — direct payment edits refused at REST; reconcile RPC is admin-gated |
| 10 | Payments | Reconciliation idempotent, mismatch refused without mutation | **PASS** (E2E + `Phase43WorkflowTest`) |
| 11 | Backend | Unfiltered/ambiguous writes refused; errors fail closed | **PASS** — PostgREST `21000` observed, guards enforce transitions |
| 12 | Backend | Concurrency/idempotency for sync ingest | **PASS** (Kotlin suite: idempotency claim, event ordering) |
| 13 | Infrastructure | Backups + PITR enabled **and restore rehearsed** | **OPEN** — not verifiable from the repo |
| 14 | Infrastructure | TLS termination + HSTS for the Kotlin server | **OPEN** — Ktor runs plain (roadmap) |
| 15 | Deployment | Rollback script exists for every migration | **FAIL** — 0006, 0007 have no rollback (§5) |
| 16 | Deployment | Rollbacks exercised, then reapplied | **PASS** for 0008/0009 (embedded PG); **OPEN** for 0001–0007 |
| 17 | Deployment | Named release owner, change window, on-call, go/no-go sign-off | **OPEN** |

## 2. Verified reference fingerprint (disposable project)

Produced by a clean replay (`drop schema public cascade` → baseline → 0001–0009,
no manual intervention) and by `docs/security-audit.md`'s REST E2E run.

| Object class | Expected after 0009 |
| --- | --- |
| Base tables (`public`) | 30 |
| Views | 6 |
| Policies | 20 — 19 `TO authenticated`, `profiles_self_read` `TO public` (own row only) |
| Functions | 12 — all with `search_path=""` |
| Triggers | 5 (`faults_*`, payment block sync) |
| Rows with RLS enabled | all client-reachable tables |
| Migration history | `0000 baseline_schema`, `0001`–`0009` in order |
| Scheduled jobs | none in this repo's migrations |

`server/tools/ProbeState.java` reproduces these counts read-only (see §6).

## 3. Environment parity

| Environment | Observed state | Gap |
| --- | --- | --- |
| Verify (`numwzjogaanfhlrfgrqk`) | baseline + 0001–0009, 30 tables, 20 policies, 12 functions, 5 triggers | none — reference |
| Development (`uemdrtrzlvhdtonccczt`) | 28 tables, 16 policies (15 auth + 1 public), 8 functions, **0 triggers**, 6 history rows | **stale at 0005**: no `faults`, no `approval_queues`, no `payments.blocked`, none of the 0006/0007/0009 objects |

Dev history is also **internally inconsistent**, not just behind: the recorded
rows are `0001`–`0004` plus two malformed entries
(`0008_ authorize admin policies.rollback`, `0009_ phase47 faults.rollback`).
There is no `0005` row even though its tables (`customers`,
`hire_purchase_contracts`, `hire_purchase_payments`, `payroll_deductions`) exist,
so 0005 was applied without bookkeeping and the 0008/0009 rollback files were
recorded as if they were migrations. Dev cannot be treated as evidence of a
successful chain; rebuild it from the committed files instead of patching the
history table.
| Production | **unknown** | must be measured before any deployment |

The dev project was previously believed to carry 0008/0009; a direct catalog read
disproves that. Treat dev and production as **separate reconciliation
exercises** — do not infer production state from either test project.

## 4. Security findings

Verified good:
- Anon reaches zero rows on `payments`, `customers`, `faults`, `approval_queues`,
  `profiles`, `sales`; `anon` EXECUTE on `is_admin()`, `is_supervisor()`,
  `process_approval_queues()` and the reconcile RPC is denied.
- Plain admins cannot post, escalate, deny or close faults (supervisor-only);
  attempts update zero rows, asserted through `Prefer: return=representation`.
- Fault state machine rejects illegal transitions and denials without a reason
  at the trigger level, so REST, RPC or SQL callers all hit the same guard.
- Payment blocking is only moved by the sanctioned SECURITY DEFINER trigger.
- All 12 public functions pin `search_path = ""`, so `SECURITY DEFINER` bodies
  cannot be hijacked by a caller-controlled schema.
- `profiles_self_read` is the only `{public}` policy and filters on
  `auth.uid() = id`, so it cannot widen visibility.

Open security items:
1. **Legacy JWT keys enabled.** Both `anon`/`service_role` JWTs and the new
   `sb_publishable`/`sb_secret` keys work on the verify project. Disable legacy
   keys in production once the admin app ships the new ones.
2. **Service-role key on developer machines.** `.env` carries it in plaintext;
   use a secret manager and rotate before launch. The Expo bundle must never
   include it (only `EXPO_PUBLIC_*` values ship — currently anon only).
3. **Phone numbers stored in clear** (`customers.phone`) — a Ghana
   data-protection decision, not an enforcement gap.
4. **Audit trail retention** — `audit_log` is append-only by design; choose and
   document a retention/immutability policy (e.g. export + no UPDATE grants).

## 5. Deployment and rollback plan (draft — not executed)

Preconditions:
1. Snapshot/PITR enabled on production and **a restore rehearsed** on a copy.
2. Read-only probe of production (`ProbeState`, authorized run) recorded and
   diffed against §2; expected starting point is the canonical baseline only.
3. Dev project re-migrated to 0009 (separate exercise) so the first deployment
   is not also the first rehearsal.
4. Freeze window + named release owner + on-call engineer.

Apply order (one transaction per file, history row written after each):
`0001 → 0002 → 0003 → 0004 → 0005 → 0006 → 0007 → 0008 → 0009`
with the canonical `schema.sql` baseline (recorded `0000`) already applied by
the Kotlin server. Never apply a later file out of order; 0001 requires the POS
tables, 0002 requires 0001's `is_admin()`, 0008 retargets policies created by
0001, and 0009 requires 0007's `payments` shape plus 0006's tables.

Post-apply verification: re-run `ProbeState` and diff against §2; then a
read-only REST smoke (admin sign-in, one payment read, one fault post/deny on a
throwaway row, then cleanup).

Rollback: `0009 → 0008 → …` using the `*.rollback.sql` files. **0006 and 0007
have no rollback scripts** — close this gap before deployment (write and test
them in `CleanReplayMigrationTest`). Rolling back 0001–0005 destroys POS data
and must be an incident decision, not a routine step.

## 6. Read-only verification tool

`server/tools/ProbeState.java` — read-only catalog audit:
- Every statement is a `SELECT` over catalogs/views; the connection is
  `setReadOnly(true)` inside a read-only transaction, so a stray write fails
  with `25006` instead of touching data.
- Requires `CHECK_URL` **and** an explicit `PROBE_CONFIRM=yes`; refuses
  production-looking hosts outright (exit 4).
- Prints only the host and database name — never `CHECK_USER`/`CHECK_PASS`.
- Deterministic: all list queries are ordered; two consecutive runs diff clean.
- Uses `prepareThreshold=0` so transaction poolers (port 6543) cannot collide on
  prepared-statement names.

Run:
```
CHECK_URL=jdbc:postgresql://<host>:5432/postgres CHECK_USER=... CHECK_PASS=... \
PROBE_CONFIRM=yes java -cp .:<postgresql.jar> ProbeState
```

## 7. GO/NO-GO

**NO-GO** at this time.

Blocking:
1. Production catalog parity unknown (§3) — requires an authorized read-only
   probe, never an assumption.
2. Rollback scripts missing for 0006 and 0007 (§5).
3. Dev project stale at 0005; deployment should not be rehearsed first in
   production.
4. Secrets handling and legacy key retirement unresolved (§4.1–4.2).
5. Backup/PITR restore not rehearsed; no documented recovery objective.
6. TLS/HSTS for the Kotlin server undecided.

Already satisfied: clean 0001–0009 replay, RLS/authorization negative tests,
payment-integrity checks, `search_path` hardening, rollback proofs for 0008/0009,
and rollback-then-reapply on embedded PostgreSQL.

GO becomes possible when every checklist row in §1 reads PASS or has a signed
risk acceptance, and the release owner records the change window and rollback
decision in this file.
