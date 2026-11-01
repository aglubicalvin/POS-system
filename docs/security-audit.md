# Phase 4.3 security audit — customers, payroll, hire purchase, MoMo

Audit date: 2026-10-30. Scope: everything Phase 4.3 added to the admin
surface and the mobile-money workflow. Every claim below is backed by an
automated check named in Evidence, not by code reading alone.

## Threat areas and where they are enforced

| Area | Decision | Enforced by |
| --- | --- | --- |
| Customer data | `customers` is admin-only (RLS `for all` on `is_admin()`); the admin app has no read path for a non-admin and anon sees nothing | migration `0005` policies; `Phase43WorkflowTest > RLS hides protected rows…`, `> anonymous clients cannot reach the admin surface` |
| Payroll amounts | `payroll_deductions` admin-only; month/status indexes; a client cannot create deductions outside the RPC path | migration `0005` policies + `0006` indexes; same RLS tests; `admin` mobile writes go through authenticated RPCs only |
| Hire-purchase integrity | contract + sale + full installment plan are written inside ONE security-definer RPC; deposit payment, deductions and schedule are consistent; paying an installment is locked (`for update`) and idempotent by idempotency key | `0006` functions; `Phase43WorkflowTest > hire purchase writes one consistent sale…`, `> paying installments reduces the balance once and never twice`, `> an installment larger than the remaining balance is refused`, `> a refused contract leaves no partial records` |
| MoMo initiation | only the server (device-token API + provider webhook) creates/moves MoMo payments; the admin mobile app cannot confirm payments, only reconcile them | Kotlin `Routes.kt` routing; `MobileMoneyApiTest` (6 tests) |
| MoMo webhook | HMAC signature over the exact raw body with a constant-time compare; disabled (HTTP 503) without a configured secret; replay is a no-op; forged signature, wrong amount or unknown id cannot move anything | `MobileMoneyApiTest > a signed callback confirms once and a replay is a no-op`, `> a forged signature or a wrong amount cannot change the payment`, `> the webhook stays disabled without a configured secret` |
| MoMo reconciliation | `admin_reconcile_mobile_money_payment` never mutates status/amount/provider_ref; refuses pending payments, mismatched amounts and mismatched references; is idempotent on `reconciled_at` | migration `0007`; `Phase43WorkflowTest > reconciliation matches a confirmed payment and is idempotent`, `> reconciliation refuses mismatches without touching the payment`; E2E `scripts/e2e-supabase.ts` repeats the lifecycle over REST |
| Till-side MoMo | checkout leaves the attempt `pending`; initiation is refused (HTTP 503) without a provider; re-initiation never re-contacts the provider; ONLY a provider verdict transitions pending → confirmed/failed; a failed verdict is audited and synced home | `src/domain/momo.ts`; `tests/momo-till.test.ts` (7 tests) |
| Payment forgery by clients | authenticated sessions have no INSERT/UPDATE on `payments`; admin RPCs are the only write paths | `Phase43WorkflowTest > authenticated clients cannot write financial tables directly`; E2E `admin cannot insert/edit payment rows` |
| `is_admin()` hardening | SECURITY DEFINER, empty `search_path`, fully qualified names, EXECUTE revoked from `public`/`anon` | migration `0003`+`0004`; `AdminMigrationTest > is_admin recognizes only active admins`, `> is_admin is executable by authenticated only`, `> admin rpcs are pinned to an empty search_path…` |
| Device enrollment | `POS_ENROLLMENT_SECRET` gates device registration; unset secret prints a startup WARNING and is the documented operational responsibility | `Application.kt`; Kotlin test suites exercise both paths |
| Migration safety | 0005–0007 apply in order over the canonical schema; canonical POS tables only GAIN columns (never lose constraints); admin objects tested against the migrations that create them, not assumptions | `AdminMigrationTest` (5 tests, embedded Postgres + Supabase shim), `Phase43WorkflowTest > migrations 0005 to 0007 create every object they advertise`, `> migrations only add columns to the canonical POS tables`, `tests/schema.test.ts` |

## Policy role alignment — migration 0008 (applied and tested on the dev project)

Audit date: 2026-10-31. Status: **applied to the development/test project**
(`uemdrtrzlvhdtonccczt`, verified from `pg_policies` before and after) and
**drafted for production, not applied**. The production deployment boundary is
lower in this document.

**What changed.** 15 RLS policies that gate on `is_admin()` were written with
the implicit `TO public` role list, but `is_admin()` EXECUTE is revoked from
`anon`/`public` (migrations 0003/0004). Anonymous callers therefore hit
`42501 permission denied for function is_admin` instead of a clean RLS
denial — fail-closed either way, but the error channel was inconsistent with
the function's grant surface. Migration `0008_authorize_admin_policies.sql`
retargets all 15 policies `TO authenticated`:

- 0001: `profiles_admin_read`, `sales_admin_read`, `sale_items_read`,
  `payments_read`, `products_read`, `stock_on_hand_admin_read`,
  `approvals_read`, `audit_admin_read`
- 0002: `categories_read_all`, `categories_admin_all`, `price_audit_admin_read`
- 0005: `customers_admin_all`, `payroll_deductions_admin_all`,
  `hp_contracts_admin_all`, `hp_payments_admin_all`

**What did NOT change.** Every `USING` / `WITH CHECK` expression is preserved
verbatim (`alter policy ... to` only rewrites the role list). `is_admin()`
itself is untouched: SECURITY DEFINER, empty `search_path`, EXECUTE still
limited to `authenticated` + `service_role`.

**Anonymous access.** Still denied. The function grant unchanged plus the
retarget means anon requests now fail on the policy role match rather than on
function privileges — the denial direction is identical. Evidence: the E2E
anonymous probes (`{anon: true}` REST reads on `payments`, the forge attempt
with a cashier JWT) returned permission denials / zero rows before and after
the retarget, and the run exits 0.

**Exception (intentionally excluded).** `profiles_self_read` on `profiles`
(`USING (auth.uid() = id)`, no `is_admin()` call) keeps `TO public` so any
signed-in user can still read exactly their own row. Verified from
`pg_policies` after the apply: `{public}`.

**Rollback.** `0008_authorize_admin_policies.rollback.sql` restores all 15
policies to `TO public` with one `ALTER POLICY` per policy. Because 0008 did
not touch expressions, nothing else needs restating.

**E2E verification of 0008** (`npm run e2e:supabase`, exit 0): admin sign-in,
service-role seeding, reconciliation (refuse wrong reference / match /
idempotent / refuse amount mismatch), payment-forgery refusal — all pass.
At the time of the 0008 run the fault-lifecycle block reported SKIP; that gap
is closed by migration 0009 (next section).

## Test evidence

- Kotlin: `gradlew check` — 72 tests, 0 failures (`CleanReplayMigrationTest` 6,
  `AdminMigrationTest` 5, `Phase43WorkflowTest` 17, `MobileMoneyApiTest` 6, plus
  the pre-existing suites), after the clean replay and rollback additions.
- TypeScript till/mobile domain: `npx vitest run` — 108 passed, 1 skipped,
  including `tests/momo-till.test.ts` (7 new) and `tests/ui.test.ts`.
- admin-mobile: `npx vitest run` — 29 passed, including the Phase 4.3 screens'
  pure-module tests.
- Live project (optional, env-gated): `npm run e2e:supabase` — runs the same
  authz + reconciliation lifecycle over real Supabase REST, then deletes
  everything it created. Refuses to run without explicitly set credentials so
  production is never touched by accident. Latest run (after 0008 + 0009 on
  the dev project): 35 checks, all pass, exit 0; no SKIP.
- `npm run typecheck` and `npm run test` after the 0009 apply: typecheck exit 0;
  vitest 109 passed / 1 skipped (15 files), exit 0.
- Kotlin (`gradlew check`) and admin-mobile suites were **not re-run** for
  0008/0009; the figures above for those suites predate both migrations.

## Phase 4.7 faults — migration 0009 (applied and tested on the dev project)

Audit date: 2026-10-31. Status: **applied to the development/test project**
(`uemdrtrzlvhdtonccczt`) and **not applied to production**. Files:
`supabase/migrations/0009_phase47_faults.sql` and
`0009_phase47_faults.rollback.sql`.

**Design source.** The schema was derived from the contract already encoded
in the E2E fault-lifecycle block (fields, statuses, side effects,
permissions), not invented. That audit also found four missing prerequisites,
all added by 0009: the `faults` table, the `approval_queues` table, the
`payments.blocked` column, and a supervisor concept (both E2E actors carry
`profiles.role = 'admin'`, and the role CHECK only allows
`cashier|manager|admin`, so a separate flag was required).

| Area | Decision | Enforced by |
| --- | --- | --- |
| Supervisor authorization | `profiles.is_supervisor` (default `false`, no RLS write path — set only via privileged SQL / service connection, like `role`); `public.is_supervisor()` is SECURITY DEFINER, `search_path = ''`, true only for an active `admin` with the flag; EXECUTE revoked from `public`/`anon`, granted to `authenticated`, `service_role` | migration `0009`; E2E supervisor-vs-plain-admin checks |
| Fault management | `faults` (text PK `fault_id`; FKs to `sales` (cascade), `payments` (set null), `auth.users`; `issue_summary` 1–500 chars; `priority` ∈ low/medium/high; `status` ∈ reported/with_approvals/posted/denied/closed; indexes on status, sale_id, payment_id) | migration `0009` |
| Lifecycle / transition guard | BEFORE INSERT/UPDATE trigger `faults_guard_transition`: inserts start as `reported` (or `posted`); allowed moves are reported→with_approvals\|posted, with_approvals→posted\|denied, denied→closed, closed→reported; anything else raises `illegal fault transition`; `denied` requires a non-empty `denial_reason` | migration `0009`; E2E lifecycle checks |
| Payment protection | BEFORE INSERT/UPDATE trigger `faults_sync_payment_block` computes `payment_blocked` (true `posted` + `is_defect`, always false for `denied`) and mirrors it to `payments.blocked`. SECURITY DEFINER, `search_path = ''`: payments have no authenticated write path, so this trigger is the only sanctioned mover of `blocked` | migration `0009`; E2E `payment flips back to not-blocked after fault denial`, `linked payment is blocked while the fault is posted` |
| Approval workflow | Escalation to `with_approvals` inserts an `approval_queues` row for `reported_by_user_id` (AFTER UPDATE trigger, runs as invoker). `process_approval_queues(p_user_id)` is SECURITY DEFINER, `search_path = ''`, and raises unless `p_user_id = auth.uid()` — a caller can only process their own queue; EXECUTE revoked from `public`/`anon` | migration `0009`; E2E `supervisor can trigger the approval-queue processor`, `supervisor sees their approval queue entry` |
| RLS | `faults_admin_read` (SELECT, `is_admin()`), `faults_supervisor_all` (ALL, `is_supervisor()` in USING and WITH CHECK), `approval_queues_admin_read` (SELECT, `is_admin()`), `approval_queues_supervisor_insert` (INSERT, `is_supervisor() and user_id = auth.uid()`). **All four explicitly `TO authenticated`**, consistent with 0008 | migration `0009`; live `pg_policies` check on the dev project |

**Security findings (verified by the E2E run unless noted).**

1. **Ordinary administrators cannot perform supervisor-only operations.** A
   plain admin's fault POST is refused, and plain-admin escalate/deny PATCHes
   change nothing (PostgREST returns an error or an empty result set because
   RLS filters the row — both are accepted as fail-closed by the checks).
2. **Illegal lifecycle transitions are rejected** by the guard trigger. The
   E2E run exercises the *legal* path end to end; a negative test that
   attempts an illegal move (e.g. reported→closed) over REST does **not yet
   exist** — the guarantee currently rests on the trigger code.
3. **Denial requires a reason** — enforced by the guard trigger; the E2E deny
   step supplies one. As with (2), no negative REST test for a reasonless
   denial exists yet.
4. **Payment blocking goes only through the sanctioned SECURITY DEFINER
   trigger.** During dev testing the first version ran as invoker and the
   payments UPDATE was silently filtered by RLS (no error, no effect); it was
   corrected to SECURITY DEFINER and the block/unblock checks now pass.
5. **Approval processing is restricted to the owning user** via the
   `p_user_id = auth.uid()` check. E2E covers the positive case only.
6. **Every RLS surface on the fault/approval/supervisor side is `TO authenticated`.**
   The four new 0009 policies are all `TO authenticated`, matching the posture
   migration 0008 already set for the 15 is_admin()-based policies. A single
   fail-closed posture against `anon` covers the whole side, and the dev
   project's `pg_policies` probe confirmed that before and after the apply.

**Anonymous access.** Fail-closed on the whole reviewed surface: the two new
0009 RPCs and `is_supervisor()` have EXECUTE revoked from `anon`, and every
0009 RLS policy is `TO authenticated`. The E2E run currently tests anon denial
only against the pre-existing tables; a dedicated anonymous probe against
`faults` and `approval_queues` is a documented gap (not yet exercised), but
the `TO authenticated` posture makes the denial a guarantee rather than a tested
one.

**Rollback.** `0009_phase47_faults.rollback.sql` drops, in reverse order, the
three fault triggers and their functions, both fault policies, `faults`,
`process_approval_queues`, the approval-queue policies and table,
`is_supervisor()`, `profiles.is_supervisor` and `payments.blocked`. The
rollback has **not been executed** against any project. Dropping
`payments.blocked` discards block state, so rolling back with posted defects
outstanding unblocks those payments.

**E2E.** `npm run e2e:supabase`: 35 checks, all pass, exit 0. The previous
`SKIP fault lifecycle` branch is no longer taken; the guard remains in the
script so projects without 0009 still run cleanly.

**How 0009 reached the dev project (relevant to production).** It was applied
incrementally while testing: the initial file, then `payments.blocked`, then
the `approval_queues_supervisor_insert` policy, then the `TO authenticated`
policy restatement and the SECURITY DEFINER trigger fix. The committed file
includes all of these. The final file **has now been applied in one pass to a
clean database** — see "Clean replay and rollback" below. That replay ran on
embedded PostgreSQL 18.6, not on a Supabase project; the fresh-project apply
is still the last step before production.

## Deployment status

| Migration | Verify project (`numwzjogaanfhlrfgrqk`, clean replay) | Dev project (`uemdrtrzlvhdtonccczt`) | Production |
| --- | --- | --- | --- |
| 0001–0009 | **Applied as one clean replay** (see below) | 0001–0005 only (stale — see below) | **Not applied** |

Correction (2026-11-01): the earlier table recorded 0008/0009 as "applied and
tested" on the development project. A direct catalog probe shows that project
has **no `faults`, no `approval_queues` and no `payments.blocked`** — it stopped
at 0005 (plus a manually retargeted `payments` policy). The 0008/0009 E2E runs
cited earlier were actually executed against the verify project, which had all
nine migrations from a prior session. Treat the dev project as out of sync with
the migration set until it is re-migrated from the committed files.

The consolidated production-readiness review — release checklist, environment
parity, security findings, deployment/rollback plan and GO/NO-GO — lives in
`docs/production-readiness.md` (current verdict: **NO-GO**).

Neither migration is to be deployed to production until a consolidated
production-readiness security review is complete, covering migrations
0001–0009, every RLS policy, RPC EXECUTE grants, SECURITY DEFINER functions
and triggers, and the rollback procedures. The open items that remain closed
below are the fresh-Supabase-project replay and negative REST-level E2E tests
(both now done); the consolidated production-readiness review itself is still
outstanding.

## Clean replay and rollback — `CleanReplayMigrationTest`

Added 2026-10-31. Six tests in `server/src/test/kotlin/.../CleanReplayMigrationTest.kt`
run against **embedded PostgreSQL 18.6**, which is disposable and local: no
Supabase project is created, no credential is used, and neither the development
project nor production is touched. Each test builds its own throwaway database
inside the embedded cluster, installs only the Supabase shim
(`anon`/`authenticated`/`service_role` roles, `auth.users`, `auth.uid()`), bootstraps
the canonical POS schema, and then applies the committed files
`0001_admin_foundation.sql` … `0009_phase47_faults.sql` in filename order — one
`execute` per file, with no repair statements and no manual steps.

What the replay asserts:

| Claim | How it is checked |
| --- | --- |
| 0001–0009 apply in order, no intervention | every file executed as-is on the clean database |
| All 15 admin policies target `authenticated` | `pg_policies.roles::text = '{authenticated}'` for each of the 15 named policies |
| `profiles_self_read` still works | policy stays `{public}`; a signed-in cashier sees exactly their own row and not another's |
| Supervisor authorization | `is_supervisor()` is true for a flagged admin, false for a plain admin, false for a flagged cashier (role still gates it) |
| Fault lifecycle + approval queue | reported → with_approvals inserts one queue row; posted blocks the payment; clearing `is_defect` unblocks; denial requires a reason; illegal transitions raise; `process_approval_queues` processes the caller's queue |
| Payment block/unblock | `payments.blocked` follows `posted && is_defect` and back |
| Anonymous access | `anon` holds table DML grants (as on Supabase) yet reads zero rows from `payments`, `customers`, `faults`, `approval_queues`, `profiles`, `sales`; EXECUTE on `is_supervisor()` and `process_approval_queues()` is denied with `42501` |
| 0009 rollback | `faults`, `approval_queues`, their triggers, `is_supervisor()`, `process_approval_queues()`, `profiles.is_supervisor` and `payments.blocked` are all gone |
| 0008 rollback | all 15 policies are back to `{public}` |
| Reapply | 0008 then 0009 re-applied; 15 policies authenticated again and the block/unblock path works |

One harness note: after the rollbacks, re-applying 0009 recreates `faults`,
which in Supabase would inherit the project's default privileges. The test
replays that grant, otherwise it would fail on a missing GRANT rather than on
migration behaviour.

Run: `cd server && ./gradlew test --tests "com.businessstore.pos.CleanReplayMigrationTest"`
— 6 tests, 0 failures. Full suite: `gradlew check` — 72 tests, 0 failures.

## Fresh Supabase replay — clean-clone verification (2026-11-01)

Executed on the disposable project `business-store-verify-202610311400`
(`numwzjogaanfhlrfgrqk`, London) via the Supabase CLI Management API
(`supabase db query --linked`) — no database password was shared and **production
and the dev project were never touched**. The project was first reset to a
truly clean state: `drop schema public cascade`, `supabase_migrations` emptied,
and the standard Supabase default privileges (DML for
`anon`/`authenticated`/`service_role` on future tables/sequences/functions)
re-issued so the replay environment matches a brand-new project.

Replay result: the canonical `server/src/main/resources/db/schema.sql` baseline
(recorded as `0000 baseline_schema` — 0001 legitimately depends on the POS
tables), then the committed `0001_admin_foundation.sql` …
`0009_phase47_faults.sql` in order, **all applied without manual intervention**.
An intermediate aborted attempt (0001 run without the baseline) left **zero**
partial objects — every migration is transaction-scoped, so a failed file
rolls back whole.

Post-replay structural verification (direct catalog queries):

| Check | Result |
| --- | --- |
| Application objects | 30 tables, 6 views, 12 functions, 5 triggers |
| Migration history | `0000 baseline_schema` + `0001`–`0009` names matching the repo, in order |
| Policy roles | 20 policies: **19 → `authenticated`**; the only `{public}` policy is `profiles_self_read` (own-row only, intentional) |
| `search_path` security | all 12 public functions pinned to `search_path = ""` (incl. `is_admin`, both admin RPCs, reconcile, `faults_*`, `is_supervisor`, `process_approval_queues`) |
| Phase 4.7 objects | `faults`, `approval_queues`, `payments.blocked`, 5 triggers all present |
| Grants | `authenticated` holds EXECUTE on `is_admin()` and `admin_reconcile_mobile_money_payment(text,text,bigint)`; `anon` is denied EXECUTE on the reconcile RPC; `anon` holds Supabase-standard table grants but RLS default-deny returns zero rows for every protected table |

REST E2E against the clean replay (`scripts/e2e-supabase.ts`, real Supabase
REST + GoTrue + PostgREST): **43/43 checks pass, exit 0**, including the
previously-SKIPped fault lifecycle and every REST-level negative check:
anonymous reads of `faults`/`approval_queues` denied; illegal transition
rejected and left the fault untouched; denial without a reason rejected and
the fault held at `with_approvals`; cross-user approval-queue processing
rejected with the original queue left pending; a plain admin's escalation and
denial attempts update **zero rows** (asserted via `Prefer:
return=representation`); supervisor block/unblock flips observed on the linked
payment through an authenticated read.

Two script defects the clean project surfaced (both fixed in
`scripts/e2e-supabase.ts`):

1. **Unfiltered fault PATCHes** — PostgREST correctly refuses an `UPDATE`
   without a WHERE clause (`21000`). All fault PATCHes now filter on
   `fault_id=eq.<run-id>`.
2. **Wrong observer for payment-flag checks and admin-refusal assertions** —
   anon correctly gets zero payment rows under RLS, so the block/unblock flag
   is read with the supervisor's session; a plain admin's refused PATCH returns
   204 with zero updated rows (not an HTTP 4xx), so the assertion now demands
   an empty representation instead of an error status.

The verify project was left with **zero** application rows and **zero** auth
users after cleanup (the first failed run's three orphan auth users were
deleted via the admin API). Suites after the fix: vitest 109 passed / 1
skipped (15 files), `tsc --noEmit` clean.

## Residual risks (accepted, documented)

1. **Provider credentials** stay with the vendor adapter implementation
   (`MomoProvider` on the till host, `MOMO_WEBHOOK_SECRET`/adapter on the
   Kotlin server). No credential ever ships in the Expo bundle — enforced only
   by convention; review before store submission.
2. **`POS_ENROLLMENT_SECRET` unset** means unauthenticated device registration.
   The server warns loudly; treat it as a bring-your-own-ops requirement.
3. **Phone-number capture** is plain text in `customers.phone`. No PII
   minimization is implemented (no truncation); Ghana data-protection
   obligations are a business decision, not enforced here.
4. **Legacy till payments** for cash/card are confirmed at the till by design
   (the cashier took the money). MoMo is the only server-confirmed method.
   Void/refund flows reusing `payments` remain a roadmap item.
5. **Reconciliation provider statement input** is trusted text from the admin
   app. The RPC validates amount/reference, but the *statement itself* must
   come from a real provider export; nothing verifies the operator's source.
