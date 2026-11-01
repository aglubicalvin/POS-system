# DEBUGGING — Debugging and Troubleshooting

## 1. Workflow

**Identify → Isolate → Fix → Verify.** Identify the failing layer (till domain,
sync/server, database/RLS, admin app, tooling), reproduce with the smallest command
that shows it, fix the cause, then re-run only the affected check and report the
result. Never "fix" by weakening a test, RLS policy, CHECK constraint or
`search_path` pin.

## 2. Issues actually encountered in this project

### 2.1 Migration SQL corrupted by quote-doubling
**Symptom:** PostgreSQL rejects `''''cashier''''`; comments show `app''''s`.
**Cause:** an ad-hoc replay tool escaped every apostrophe (`'` → `''`) — twice —
and stripped `$$` delimiters, directly in the source files.
**Fix:** restore the committed files (`git checkout -- supabase/migrations`); never
transform migration SQL before execution. Guards now fail loudly on `''''` in both
`ApplyMigration.java` and the Kotlin harness.
**Lesson:** loaders execute SQL verbatim; only line endings may be normalised.

### 2.2 `:server:test` is not a valid Gradle task
**Symptom:** `Task ':server:test' not found`.
**Cause:** single-module Gradle project (`settings.gradle.kts` has no subprojects).
**Fix:** run from `server/`: `./gradlew test --tests com.businessstore.pos.AdminMigrationTest`.

### 2.3 Tests "pass" from cache
**Symptom:** `:test FROM-CACHE` after changing nothing relevant.
**Cause:** Gradle build cache hit.
**Fix:** `./gradlew test --rerun-tasks` when you need a real full run; check
`server/build/test-results/test/*.xml` for counts.

### 2.4 `prepared statement "S_1" already exists`
**Symptom:** probe/apply fails on a pooled connection (Supabase port 6543).
**Cause:** transaction pooler reuses backend sessions; server-side prepared
statement names collide.
**Fix:** append `?prepareThreshold=0` (ProbeState does this automatically).

### 2.5 `UPDATE requires a WHERE clause` (`21000`)
**Symptom:** E2E fault PATCH fails.
**Cause:** PostgREST refuses an unfiltered update.
**Fix:** `PATCH /rest/v1/faults?fault_id=eq.<id>`; never PATCH a bare table.

### 2.6 `Invalid API key` on authenticated requests
**Symptom:** 401 even with a valid user JWT.
**Cause:** the user JWT was sent in the `apikey` header.
**Fix:** `apikey` = anon (or service) key; `Authorization: Bearer <user jwt>`.

### 2.7 An "anonymous" probe returned data
**Symptom:** anon read of views returned rows.
**Cause:** a JS default parameter (`token = SERVICE_KEY`) turned an anonymous call
into a privileged one.
**Fix:** pass the anon key explicitly; the view probe now does. Real result after
the fix: anon is refused on all six views.

### 2.8 `relation "sales" does not exist` when applying 0001
**Symptom:** clean project fails on the first migration.
**Cause:** 0001 assumes the canonical POS tables.
**Fix:** apply `server/src/main/resources/db/schema.sql` first (recorded `0000`).

### 2.9 `supabase db dump` fails on the Docker pipe
**Symptom:** `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`.
**Cause:** Docker Desktop not running; the CLI shells out to `pg_dump` in a container.
**Fix:** start Docker, or use SQL-level backups (`create table backup.x as select * …`)
and restore via `INSERT … SELECT`.

### 2.10 Admin sees nothing / `[]` from PostgREST
**Cause:** no `profiles` row, `role <> 'admin'`, or `is_active = false` → `is_admin()`
false → RLS filters everything.
**Fix:** fix the profile. Do not relax the policy.

### 2.11 Fault transition rejected unexpectedly
**Cause:** the transition trigger only allows
`reported → with_approvals → posted|denied → closed → reported`, and denial
requires `denial_reason`.
**Fix:** follow the state machine; read the raised message (SQLSTATE `P0001`).

### 2.12 Dev migration history nonsense
**Symptom:** rows like `version='0008_'`, `name='… .rollback'`,
`statements='"applied by SqlMix"'`, and `0004` before `0001`.
**Fix:** rebuild from committed sources; never patch history rows.

## 3. Common frontend issues

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Till shows login after refresh | session kept in memory for the shift | log in again; device token persists |
| Cart totals look wrong | UI computing totals | totals must come from `domain.checkout` |
| Checkout button stays disabled | empty cart or shift not open | open a shift, add items |
| Receipt dialog empty | sale rejected (see error line) | read `#checkout-error` |
| Admin app blank | missing `EXPO_PUBLIC_*` env | create `admin-mobile/.env` |
| Admin app bounces to login | gate: profile inactive/not admin | check `profiles` |
| Pull-to-refresh does nothing | request failed silently | error text above the cards shows the message |

## 4. Backend and database issues

| Symptom | Cause | Diagnosis |
| --- | --- | --- |
| 401 on `/sync` | missing/expired token | `Authorization: Bearer <device token>` |
| 403 on `/sync` | event `deviceId` ≠ token's device | one till cannot write for another |
| 422 unknown product | event arrived before catalog sync | resend after pull; event was not claimed |
| Duplicate sales | client resent without idempotency key | `event_id` must be stable per event |
| Stock drift | movement written without cache update | rebuild from `stock_movements` |
| Dashboard inflated | roll-up fan-out per line item | fixed in 0003; verify view definition |

Useful SQL:

```sql
select * from supabase_migrations.schema_migrations order by version;
select tablename, policyname, roles, cmd from pg_policies where schemaname='public' order by 1,2;
select p.proname, p.proconfig from pg_proc p join pg_namespace n on n.oid=p.pronamespace
  where n.nspname='public' order by 1;
select event_id, status from outbox where status <> 'acked' order by created_at;
```

## 5. Authentication issues

| Symptom | Cause | Fix |
| --- | --- | --- |
| `permission denied for function` | `SECURITY DEFINER` without EXECUTE grant | grant to `authenticated` |
| anon gets rows where it shouldn't | policy `TO public` + permissive predicate | retarget `TO authenticated` |
| Webhook 503 | `MOMO_WEBHOOK_SECRET` unset | set it; never accept unsigned callbacks |
| PIN lockout | repeated failures | re-enable the user / reset attempts |

## 6. Reading logs

- Server: `slf4j-simple` to stdout; unhandled errors log a stack trace and return a
  generic 500. Webhook rejections log the reason (signature/secret/state).
- Embedded PostgreSQL tests print server logs through zonky — `ERROR:` lines are
  expected for negative tests (e.g. `only administrators may update selling prices`).
- Supabase: Dashboard → Logs (API/Postgres). PostgREST errors carry SQLSTATE codes;
  `42501` = permission denied, `23514` = check violation, `P0001` = raised exception.

## 7. Tools

| Tool | Use |
| --- | --- |
| `server/tools/ProbeState.java` | Read-only fingerprint (tables, policies, functions, triggers, history) |
| `server/tools/CheckSchema/Verify0001–0003` | Targeted object checks on a project |
| Supabase CLI (`db query`, `projects api-keys`) | SQL and keys without a DB password |
| Browser devtools | Till network/console; storage inspection |
| `npm run demo` | Reproduce domain behaviour without UI or DB |
| `git diff --check` | Line-ending/whitespace problems before a commit |

## 8. Environment-specific debugging

- **Local:** embedded PostgreSQL is disposable — delete the temp cluster if it
  starts slowly; re-run with `--rerun-tasks`.
- **Verify project:** safe to reset (drop `public`, replay migrations); it holds no
  user data.
- **Dev project:** contains data; take table copies into `dev_backup` before any
  reset, verify counts, then rebuild.
- **Production:** read-only. Do not run migrations, DDL or write probes.

## 9. External API debugging

- Provider sandbox: check the callback URL, the exact raw body used for HMAC, and
  clock skew; a mismatched body (re-serialised JSON) fails signature checks.
- Supabase Auth: verify the project URL/anon key pair; sessions in SecureStore can
  be cleared by reinstalling the app.

## 10. Performance debugging

- Checkout: measure locally; any network call on the sell path is a bug.
- `/sync`: inspect batch size and per-event application time; look for missing
  indexes or repeated catalog lookups.
- Reports: `EXPLAIN (ANALYZE, BUFFERS)` the view; the daily roll-up must not fan
  out per line item.

## 11. Preventing recurrence

1. Guard rails in code (fail-fast on corrupted SQL, `PROBE_CONFIRM`, read-only
   transactions) beat checklists.
2. Every fix gets a test that fails without it.
3. Keep one authoritative artifact per fact: `schema.sql` for canonical DDL,
   migrations for the admin surface, `contract/events.json` for the wire format.
4. Record environment surprises in this file, not in chat.
