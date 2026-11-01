# TESTING — Testing and Quality Assurance

## 1. Strategy

Three layers, each with a different job:

| Layer | Tool | Proves |
| --- | --- | --- |
| Domain / pure logic | Vitest (TS) | money, tax, checkout, stock, permissions, sync ordering |
| Server behaviour | JUnit 5 + **real** embedded PostgreSQL | SQL semantics, transactions, idempotency, RLS/grants, migrations |
| End-to-end | `scripts/e2e-supabase.ts` against a real Supabase project | the actual PostgREST + GoTrue + RLS path the app uses |

Structural checks (`tests/schema.test.ts`) pin the dialect: the SQLite DDL and the
canonical PostgreSQL DDL must define the same tables/columns/indexes.

## 2. Environments

| Environment | Use |
| --- | --- |
| Local (no DB) | domain, UI helpers, schema comparison |
| Embedded PostgreSQL (zonky 18.6) | server integration + migration execution, disposable |
| Verify project (`business-store-verify-…`) | clean replay, E2E, view/authorization probes, disposable |
| Dev project | human-facing dev environment, rebuilt to the same fingerprint |
| Production | **no tests run**; read-only baseline only, and not yet authorized |

## 3. Tools and frameworks

Vitest 5, TypeScript 7 (`tsc --noEmit`), JUnit 5, zonky embedded PostgreSQL,
Gradle 9.7.1, Supabase CLI 2.122, Node 26 (with `--experimental-strip-types` for
the E2E script).

## 4. Unit tests (till + admin app)

- Root: **15 files, 109 passed, 1 skipped** — money, checkout, inventory, stock,
  sync, ordering, catalog producer, receipts, UI, schema parity, MoMo till flow.
- `admin-mobile`: **29 passed** — summary formatting, product management, auth gate
  (pure modules; screens are not rendered in tests).

## 5. API and integration tests (Kotlin, embedded PostgreSQL)

| Suite | Focus |
| --- | --- |
| `HttpServerIntegrationTest` | routes, auth, status codes |
| `SyncApiTest` / `PullApiTest` | batch idempotency, ordering, cursors |
| `EventApplierTest` | event application, rollback, repricing |
| `WireContractTest` | cross-language event fixture |
| `MoneyTest` | integer arithmetic, rounding |
| `Phase43WorkflowTest` (17) | customers, payroll, hire purchase, reconciliation |
| `MobileMoneyApiTest` (6) | initiate/webhook/HMAC/idempotency |
| `AdminMigrationTest` (5) | dashboard roll-up, `is_admin()`, RPC search_path, grants |
| `CleanReplayMigrationTest` (6) | 0001–0009 replay, rollbacks, anon denial |
| `ToolchainSmokeTest` | build sanity |

Run: `cd server && ./gradlew test` (11 classes, 0 failures on the last full run).

## 6. End-to-end tests

`npm run e2e:supabase` — **43/43 pass, exit 0** against the clean replay. It
self-seeds auth users, profiles, a cashier, a customer, a sale and a pending
payment (then confirms it), and deletes everything.

Covered: admin sign-in, service-role seeding, anon denial (tables, faults,
approval queues), direct payment-write refusal, reconciliation (match, idempotent
retry, wrong reference, amount mismatch), payment forgery refusal, fault lifecycle
(post → escalate → deny → close → reopen → repost), payment block/unblock, approval
queue processing, plain-admin refusal on post/escalate/deny, illegal transition
rejection, reasonless denial rejection, cross-user queue processing refusal.

## 7. View security tests (2026-11-01)

Six views × three observers = **18/18 pass** with seeded data: anon refused (no
grant), authenticated cashier sees 0 rows (invoker RLS), admin sees the row.

## 8. Database tests and integrity checks

| Check | Result |
| --- | --- |
| Clean 0001–0009 replay on empty project | pass, history `0000`–`0009` |
| Rollbacks 0008/0009 then reapply | pass (`CleanReplayMigrationTest`) |
| Unvalidated foreign keys | 0 of 60 |
| Tables without PK | 0 |
| Orphan rows (profiles/sales/payments) | 0 |
| MD5 checksums live vs backup | match |
| Sequences | 0 (all keys supplied or `gen_random_uuid()`) |
| `search_path` pinned | 12/12 functions |
| Policy roles | 19 `authenticated` + 1 own-row `public` |

## 9. Recovery validation (partial, blocker recorded)

- Dev-scale: full table copies taken in-project, `public` dropped and rebuilt from
  the committed baseline + 0001–0009, data restored, counts and checksums
  reproduced. This proves the restore **procedure**.
- Production-scale: `supabase db dump` requires Docker; the daemon was not running
  (`npipe:////./pipe/dockerDesktopLinuxEngine` unavailable), so no physical dump
  was produced, no durations were measured, and no RTO/RPO is claimed.
- Consequence: recovery capability remains **partially demonstrated** and is a
  release blocker.

## 10. UI/UX, responsive and accessibility testing

- Keyboard/touch flows exercised manually in the till UI; `aria-label`,
  `role="alert"`, `role="status"` present on the critical controls.
- Responsive behaviour relies on the CSS grid rule
  (`minmax(150px, 1fr)`) and was inspected at phone and tablet widths.
- **No automated a11y audit** (axe/Lighthouse) and no screen-reader pass yet.

## 11. Performance and load testing

Not performed. Checkout is local, so the critical path has no network; the server
paths most likely to need load tests are `POST /sync` (batch size, body cap) and
`GET /sync/pull` (cursor scans). Neither has a benchmark or a rate limit.

## 12. Security testing

- Authorization negatives at REST (43 checks), view assessment (18 checks),
  SQL-level grant/`search_path` tests, secret-handling audit, and
  `CleanReplayMigrationTest`'s anon denial.
- **No OWASP ZAP scan, no dependency audit run, no pen test.** These remain open.

## 13. Test data and seed data

`seedServer()` (Kotlin), `tests/helpers.ts` (Vitest), and the E2E script's
seeded fixtures. All E2E data is deleted at the end (verified: 0 rows in every
public table and 0 auth users after the run).

## 14. Feature-specific cases (representative)

| Feature | Case | Expected |
| --- | --- | --- |
| Checkout | discount above ceiling | refused / approval required |
| Sync | duplicate `eventId` | no-op, `duplicate` |
| Sync | gap in `seq` | buffered, applied after fill |
| Payments | client tries to confirm | refused |
| Reconcile | wrong amount | error, row untouched |
| Faults | plain admin escalates | 0 rows updated |
| Faults | deny without reason | error, state unchanged |
| Hire purchase | pay installment | balance reduced by exactly the installment |
| Payroll | deduction for a month | summary totals include it |

## 15. Bug reporting and severity

Use: steps → expected → actual → environment (project ref, migration level, commit)
→ evidence (HTTP status/body, SQL error code, log lines).

| Severity | Meaning |
| --- | --- |
| S1 | money incorrect, auth bypass, data loss |
| S2 | core flow broken (checkout, sync, login) |
| S3 | degraded feature, wrong UI state |
| S4 | cosmetic/docs |

## 16. CI/CD testing

No CI pipeline is committed. The intended gate sequence is:
`npx tsc --noEmit` → `npx vitest run` → `admin-mobile: vitest` →
`cd server && ./gradlew test` → `npm run e2e:supabase` (test project only). A CI
job should also run `git diff --check` and the migration replay test.

## 17. Running tests locally

```bash
npx tsc --noEmit
npx vitest run
cd admin-mobile && npx vitest run
cd ../server && ./gradlew test
cd .. && npm run e2e:supabase     # requires a test project's credentials
```

## 18. Coverage and gaps

- No coverage tooling is configured; no thresholds are enforced.
- Untested: React Native screens (only pure modules), the till UI in a real
  browser, ESC/POS output, provider sandbox, load, OWASP, production restore.
- Known flakiness: none recorded; embedded PostgreSQL startup is the slowest step
  (~30 s once per JVM).
