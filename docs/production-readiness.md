# Production-readiness review — POS backend, admin app, migrations 0001–0009

Status: **NO-GO** (updated 2026-11-01, after the view security assessment and the
dev rebuild).
Scope: migrations `0001`–`0009`, RLS policies, **all six database views**, RPC
grants, triggers, the canonical POS schema, payment/reconciliation integrity,
PII and audit-log retention, and the deployment/rollback/recovery procedure.

No production change has been made or is authorized by this document. Claims are
either reproduced from the repository or observed on a real Supabase project by a
read-only probe or a live REST test. Anything unverified is marked **OPEN**.

## 1. Release checklist

| # | Area | Required evidence | Status |
| --- | --- | --- | --- |
| 1 | Database | Clean 0001–0009 replay on an empty project | **PASS** (2026-11-01) |
| 2 | Database | Production catalog matches the reference fingerprint | **OPEN** — no authorized production access; do not assume parity |
| 3 | Database | Dev reconciled with the committed chain | **PASS** (2026-11-01 rebuild, §3) |
| 4 | Database | All six views use `security_invoker`, grant only to `authenticated`, refuse anon at REST | **PASS** (§5) |
| 5 | Security | RLS on every client-reachable table; anon denied at REST | **PASS** (43/43 E2E + catalog probe) |
| 6 | Security | RPC EXECUTE limited to `authenticated`; anon denied | **PASS** |
| 7 | Security | SECURITY DEFINER functions pinned to `search_path = ""` | **PASS** — 12/12 |
| 8 | Security | Secret material absent from the repo, Git history and client bundle | **PASS** (§4.2) |
| 9 | Security | Legacy anon/service JWT keys retired after client migration | **OPEN** — dated plan in §4.3 |
| 10 | Payments | Only server/DB moves payment state; clients cannot confirm | **PASS** |
| 11 | Payments | Reconciliation idempotent; mismatches refused without mutation | **PASS** |
| 12 | Backend | Ambiguous/unfiltered writes refused; errors fail closed | **PASS** |
| 13 | Backend | Sync ingest idempotent and order-safe | **PASS** (Kotlin suite) |
| 14 | Infrastructure | Backup exists **and a restore has been rehearsed** | **PARTIAL** — dev data backed up and restored into a rebuilt schema (§6); no production-grade backup/restore drill yet |
| 15 | Infrastructure | TLS termination + HSTS for the Kotlin server | **OPEN** — Ktor runs plain |
| 16 | Deployment | Rollback script per migration, or documented forward recovery | **PARTIAL** — 0006/0007 have no rollback; forward-recovery procedure drafted (§6) |
| 17 | Deployment | PII and audit-log retention decisions recorded | **OPEN** — classified in §4.4–4.5 |
| 18 | Deployment | Named release owner, change window, on-call, sign-off | **OPEN** |

## 2. Reference fingerprint (verified, reproduced on two projects)

| Object class | Expected after 0009 |
| --- | --- |
| Base tables (`public`) | 30 |
| Views | 6 — all `security_invoker=true`, owned by `postgres` |
| Policies | 20 — 19 `TO authenticated`, `profiles_self_read` `TO public` (own row only) |
| Functions | 12 — all `search_path=""` |
| Triggers | 5 |
| Migration history | `0000 baseline_schema`, `0001`–`0009` in order |
| Scheduled jobs | none in this repo's migrations |

Measured with `server/tools/ProbeState` (§7) on the disposable verify project and,
after the rebuild, on the dev project: identical numbers on both.

## 3. Environment parity

| Environment | Observed state | Gap |
| --- | --- | --- |
| Verify (`numwzjogaanfhlrfgrqk`) | baseline + 0001–0009; 30 tables, 20 policies, 12 functions, 5 triggers | none — reference |
| Development (`uemdrtrzlvhdtonccczt`) | **rebuilt 2026-11-01** from the committed baseline + 0001–0009; same fingerprint; history `0000`–`0009`; data restored from a verified backup | none |
| Production | **unknown** | must be measured before any deployment |

**Dev history before the rebuild (recorded verbatim, because "stale" understated
it).** The table held six rows with these values:

| version | name | statements | statements_hash | executed_at |
| --- | --- | --- | --- | --- |
| `0004` | `rpc_search_path_hardening` | `[]` | NULL | 2026-10-28 19:01:47 |
| `0001` | `admin_foundation` | `[]` | NULL | 2026-10-30 22:47:39 |
| `0002` | `product_management` | `[]` | NULL | 2026-10-30 22:47:39 |
| `0003` | `dashboard_aggregation_and_authz` | `[]` | NULL | 2026-10-30 22:47:39 |
| `0008_` | `authorize admin policies.rollback` | `"applied by SqlMix"` | NULL | 2026-10-31 15:54:54 |
| `0009_` | `phase47 faults.rollback` | `"applied by SqlMix"` | NULL | 2026-10-31 15:54:55 |

Three independent defects: **0004 ran a day before 0001–0003** (out of order);
**no `0005` row** although 0005's tables existed; and the **0008/0009 rollback
files were recorded as migrations** by an ad-hoc tool (`statements = "applied by
SqlMix"`), against version strings that are not valid versions (`0008_`). The
history could not be repaired meaningfully, so dev was rebuilt from the committed
sources instead of patched.

**Rebuild procedure actually used** (repeatable, no external tooling):
1. `create schema dev_backup` + `create table dev_backup.<t> as select * from public.<t>`
   for every public table — an in-project, FK-free backup.
2. Verified the backup row-for-row against `public` (customers 16, users 5,
   profiles 42, sales 5, payments 5).
3. `drop schema public cascade`, recreated `public` with Supabase's default
   privileges, replayed `schema.sql` (recorded `0000`) then `0001`–`0009` in
   order, writing one history row per file.
4. Restored from `dev_backup` with a dependency-ordered `INSERT ... SELECT`
   using each backup table's own column list (new columns such as
   `payments.blocked` take their defaults), then verified the same five counts.

`dev_backup` is left in place as the safety copy; it can be dropped once the
restored environment is confirmed good. It is not exposed through the Data API
(it is not in the exposed schemas) and is reachable only by the postgres role.

## 4. Security findings

Verified good:
- Anon reaches zero rows on `payments`, `customers`, `faults`, `approval_queues`,
  `profiles`, `sales`; `anon` EXECUTE on `is_admin()`, `is_supervisor()`,
  `process_approval_queues()` and the reconcile RPC is denied.
- Plain admins cannot post, escalate, deny or close faults; their attempts update
  **zero rows** (asserted via `Prefer: return=representation`).
- The fault state machine rejects illegal transitions and reasonless denials in a
  trigger, so REST, RPC and SQL callers hit the same guard.
- Payment blocking is only moved by the sanctioned SECURITY DEFINER trigger.
- All 12 public functions pin `search_path = ""`.
- All six views are `security_invoker` with no `anon` grant (§5).

### 4.1 `profiles_self_read` — precise semantics

`{public}` in `pg_policies.roles` means "applies to every role", including
`anon`; it does not by itself grant anything. The policy's predicate is
`auth.uid() = id`, and two layers must both pass:

1. **Grants** are checked before RLS. Supabase's default privileges give `anon`
   the table grant on `public.profiles` (measured: `has_table_privilege('anon',
   'public.profiles', 'SELECT')` is true), so the request reaches RLS.
2. **RLS** then evaluates `auth.uid() = id`. For `anon`, `auth.uid()` is NULL, so
   no row can ever match.

Result, verified at REST and in the embedded replay: an anonymous read of
`profiles` returns **zero rows** (not an error), and asigned-in cashier sees exactly their own row and no other profile. Retargeting this policy to
`authenticated` would be cosmetic — the predicate already makes it safe — but the
grant is what keeps the fail-closed behavior from depending on RLS alone. The
REST negative suite covers the anonymous profile read explicitly.

### 4.2 Service-role key — classification corrected

A plaintext `.env` on a developer machine is the documented, supported way to
hold server-only secrets; it is not a finding by itself. The finding is only if
the material escapes its intended boundary. Checks run:

| Check | Result |
| --- | --- |
| `.env` excluded from Git | **yes** — `.gitignore:8` and `admin-mobile/.gitignore:34` |
| Any env file ever committed | **no** — `git log --all -- '*.env' '.env*'` is empty |
| JWT material in tracked files | **none** — `git grep` for the JWT prefix returns nothing |
| `sb_secret_…` in tracked files | **none** |
| Service-role references in the mobile app | **none** — only `EXPO_PUBLIC_SUPABASE_ANON_KEY`/publishable key ship |

Conclusion: **not exposed, no rotation required today**. Requirement before
release: supply the key through a managed secret store in the deployed
environment and rotate immediately if any artefact containing it is shared.

### 4.3 Legacy JWT keys — dated remediation

The verify project issues both the legacy `anon`/`service_role` JWTs and the new
`sb_publishable_…`/`sb_secret_…` keys. Supabase is deprecating the legacy pair by
the **end of 2026**.

- **Target: admin app and tooling fully on the new keys by 2026-11-30.**
- **Hard deadline: legacy keys disabled before 2026-12-31** (before the platform
  deprecation removes them on its own schedule).
- Owner: release owner. Evidence required: an E2E run against the verify project
  using only `sb_publishable`/`sb_secret`, then the legacy pair disabled.

### 4.4 Phone-number PII — classification: **production blocker until decided**

`customers.phone` is stored in clear and has no minimization. Necessary data:
phone + staff number where payroll deduction or MoMo matching requires it.
Access: admin-only through RLS (`customers_admin_all`), never reachable by anon
(verified). Not written to logs by the E2E or the server's structured logging.
Retention and masking (e.g. last 4 digits for display) are **undecided**, which
is why this blocks GO rather than being accepted silently.

### 4.5 Audit-log retention — classification: **production blocker until decided**

`audit_log` is hash-chained and append-only by design; only admins can read it and
no client can write it. Undecided: retention period, archival destination, and
whether older rows leave the database. Blocker for GO because a hash chain without
a stated retention policy cannot be evidenced to an auditor.

## 5. Database views — security assessment

Views are the classic RLS blind spot: a default view runs with its **owner's**
permissions (`postgres`, which bypasses RLS), so a view can silently re-expose
rows the base-table policies hide. All six views in this schema are therefore
assessed individually. Evidence: catalog queries on the clean replay plus a
seeded REST probe (one product with stock, one customer, one sale with a line
item, one hire-purchase contract, an admin profile and a cashier profile).

| View | Security mode | Owner | Grants | Underlying tables | Anon REST | Cashier REST | Admin REST |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `admin_product_catalog` | `security_invoker=true` | postgres | `authenticated`, `service_role` | products, product_categories, stock_on_hand | refused | 0 rows | row returned |
| `admin_inventory_overview` | `security_invoker=true` | postgres | `authenticated`, `service_role` | products, stock_on_hand | refused | 0 rows | row returned |
| `customer_purchase_summary` | `security_invoker=true` | postgres | `authenticated`, `service_role` | customers, sales, payroll_deductions | refused | 0 rows | row returned |
| `dashboard_sales_daily` | `security_invoker=true` | postgres | `authenticated`, `service_role` | sales, sale_items, products | refused | 0 rows | row returned |
| `hire_purchase_overview` | `security_invoker=true` | postgres | `authenticated`, `service_role` | hire_purchase_contracts, customers, products | refused | 0 rows | row returned |
| `low_stock_overview` | `security_invoker=true` | postgres | `authenticated`, `service_role` | products, stock_on_hand | refused | 0 rows | row returned |

All six are exposed through the Supabase Data API (they live in `public`), so each
one was tested over REST rather than assumed safe. Results: **18/18 checks pass** —
every view refuses `anon` (no grant at all, `anon` is absent from the grantee
list), returns zero rows to an authenticated cashier whose base-table RLS hides
the data, and returns the seeded row to an authenticated admin. Because the views
are `security_invoker`, the cashier's zero-row result is the base-table policies
being inherited rather than a separate view-only rule — exactly the property that
makes the `security_invoker` flag worth verifying.

Ongoing requirement: any future view must set `security_invoker = true`, revoke
`anon`, and be added to this table with a REST negative test.

## 5a. Development reconstruction validation (2026-11-01)

Closing checks on the rebuilt dev project, run read-only:

| Check | Result |
| --- | --- |
| Sequences | **0** — every key is client-supplied text or `gen_random_uuid()`; nothing to reset |
| Foreign keys | 60, **0 unvalidated** |
| Tables without a primary key | **0** |
| Orphan rows (`profiles`→`auth.users`, `sales.cashier_id`, `payments.sale_id`) | **0 / 0 / 0** |
| MD5 checksums, live vs `dev_backup` (customers, sales, profiles, payments) | **match** |
| CHECK / UNIQUE constraints | 111 / 68 |
| Fingerprint | 30 tables, 6 views, 20 policies, 12 functions, 5 triggers |

**Recovery drill blocker (honest status).** A production-style physical backup was
attempted with `supabase db dump --db-url`; it shells out to `pg_dump` inside a
container and failed because the Docker daemon is not running
(`npipe:////./pipe/dockerDesktopLinuxEngine`). No dump was produced, so **no
durations, database size or RTO/RPO are claimed**. What is demonstrated is the
logical restore: full table copies, schema rebuild from the committed migrations,
and a verified restore with matching counts and checksums (dev scale). The
production-sized drill remains a release blocker; it needs either Docker running
or a restore-capable target project.

## 6. Deployment, rollback and recovery plan

Preconditions:
1. Verified snapshot/PITR **and** a completed restore rehearsal on a disposable
   copy of production (§ checklist row 14).
2. Read-only `ProbeState` baseline of production, authorized, diffed against §2.
3. Dev on the same fingerprint as verification (done, §3).
4. Freeze window, named release owner, on-call engineer, rollback decision-maker
   (the release owner; the on-call engineer executes).

Apply order — one transaction per file, history row written after each:
`0001 → 0002 → 0003 → 0004 → 0005 → 0006 → 0007 → 0008 → 0009`, after the
canonical `schema.sql` baseline (`0000`). The baseline is NOT applied by server
startup: `Application.main` only *verifies* it is present and refuses to start
otherwise. It is created by the explicit, separately confirmed
`./gradlew provisionDatabase` task (`PROVISION_CONFIRM=yes`, `POS_DATABASE_URL`,
`POS_DATABASE_USER`, `POS_DATABASE_PASS`, `POS_EXPECTED_TARGET`,
`POS_DATABASE_ROOT_CERT`, `POS_DATABASE_ROOT_CERT_SHA256`), which applies
the baseline and records `0000` in one transaction. Order is
not cosmetic: 0001 needs the POS tables, 0002 needs `is_admin()`, 0008 retargets
policies created in 0001, and 0009 needs 0007's `payments` shape plus 0006's
tables.

Post-apply: `ProbeState` diff against §2, then a read-only REST smoke (admin
sign-in, payment read, one fault post/deny on a throwaway row, then cleanup).

Rollback: `0009 → 0008 → …` using the `*.rollback.sql` files. **0006 and 0007
have no rollback scripts.** That is not fatal *provided* forward recovery is
exercised and documented:
- forward recovery for 0006/0007 = restore the pre-deployment snapshot and replay
  0001–0005, then re-apply once the fix ships; there is no partial in-place undo;
- the SQL-level rollback proofs for 0008/0009 already exist in
  `CleanReplayMigrationTest`; extend it with 0006/0007 rollbacks if written;
- rolling back 0001–0005 destroys POS data and is an incident decision, not a
  routine step.

Recovery evidence already obtained (dev rehearsal, 2026-11-01): full table copies
taken in-project, counts cross-checked, `public` dropped and rebuilt from the
committed baseline + 0001–0009, then data restored with all five counts matching
and the fingerprint reproduced. What is still missing is the same drill against a
**production-sized** backup with timings recorded and a restore-time objective.

## 7. Read-only verification tool

`server/tools/ProbeState.java` — catalog audit used for the parity evidence:
- only catalog `SELECT`s; `setReadOnly(true)` inside a read-only transaction, so a
  stray write fails with `25006`;
- requires `CHECK_URL` **and** `PROBE_CONFIRM=yes`; refuses production-looking
  hosts (exit 4);
- prints host/database only — never `CHECK_USER`/`CHECK_PASS`;
- deterministic (all lists ordered; two runs diff clean);
- `prepareThreshold=0` so transaction poolers cannot collide on prepared names.

## 8. GO/NO-GO

**NO-GO.**

Blocking:
1. **Production parity unknown** — needs an authorized read-only `ProbeState`
   baseline, not an assumption.
2. **Recovery only partly demonstrated** — dev-scale backup/restore done; a
   production-sized restore with recorded timings and a recovery objective is not.
3. **0006/0007 lack rollback scripts** — acceptable only once the forward-recovery
   procedure above is rehearsed and signed off.
4. **Legacy JWT keys** still enabled; retirement deadline set (§4.3) but not met.
5. **PII and audit-retention decisions** unwritten (§4.4–4.5).
6. **TLS/HSTS** for the Kotlin server undecided.

Already satisfied: clean 0001–0009 replay on a genuinely empty project; dev
rebuilt to the same fingerprint with its data preserved; six-view security
assessment with REST negative tests; 43/43 REST E2E including authorization and
payment-integrity negatives; `search_path` hardening; secret-handling audit;
rollback proofs for 0008/0009 and a complete dev-scale data restore.

GO becomes possible when every checklist row reads PASS or carries a signed risk
acceptance, and the release owner records the change window and rollback decision
here.
