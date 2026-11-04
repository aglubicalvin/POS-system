# Business Store POS

Offline-first point-of-sale for a Ghanaian retail store: a **cashier till** that
keeps selling without a network, an **authoritative Kotlin server** on PostgreSQL,
and an **administrator mobile app** that reads and manages the business through
Supabase with database-enforced authorization.

## Key features

- **Offline checkout** — local SQLite transaction; the sale is queued and synced
  later. Totals are computed in the domain layer, never in the UI.
- **Deterministic sync** — per-device sequence numbers, idempotent event ids,
  buffered gaps, and server-side repricing that flags client discrepancies.
- **Mobile money** — initiation, HMAC-signed provider webhook, and idempotent
  admin reconciliation that refuses reference/amount mismatches.
- **Customers, payroll, hire purchase** — staff-number-based customers, monthly
  deductions, contracts with installment schedules and balances.
- **Catalog management** — price, category and media edits with a price audit
  trail; admin-only, enforced in SQL.
- **Faults and approvals** — supervisor-only lifecycle with a payment-blocking
  side effect and an approval queue.
- **Row-level security everywhere** — anonymous requests reach nothing; six views
  are `security_invoker`; all `SECURITY DEFINER` functions pin `search_path = ""`.

## Technology stack

| Part | Stack |
| --- | --- |
| Till | TypeScript, `node:sqlite`, plain HTML/CSS |
| Server | Kotlin 2.4, Ktor 3.6, PostgreSQL 18, HikariCP, kotlinx.serialization |
| Database | PostgreSQL (Supabase) + 9 versioned migrations |
| Admin app | Expo SDK 58, React Native 0.88, expo-router, Supabase JS |
| Tests | Vitest, JUnit 5, zonky embedded PostgreSQL |

## Folder structure

```
src/                till domain, SQLite, sync, UI
server/             Kotlin/Ktor server + canonical schema.sql + tools
supabase/migrations migrations 0001–0009 and rollbacks
admin-mobile/       Expo admin app
scripts/            demo, till, contract emit, Supabase E2E
tests/              Vitest suites
contract/           generated cross-language event fixtures
docs/               audit, readiness, roadmap, compliance
```

## Setup

See `SETUP.md` for full instructions. Short version:

```bash
# till + scripts
npm install
npm test                 # 109 passed / 1 skipped
npm run typecheck
npm run demo             # domain walkthrough
npm run till             # cashier UI on http://localhost:3000

# server (needs PostgreSQL)
cd server && ./gradlew test
./gradlew run            # listens on :8080

# admin app
cd admin-mobile && npm install && npm start
```

## Running in development and production

- **Development:** local PostgreSQL or a Supabase dev project; server via
  `./gradlew run`; till via `npm run till`; admin app via Expo.
- **Production:** server jar behind a TLS-terminating proxy, managed PostgreSQL,
  migrations applied in order with a verified backup. Production is currently
  **NO-GO** — see `docs/production-readiness.md`.

## Environment variables

| Variable | Used by | Notes |
| --- | --- | --- |
| `POS_DATABASE_URL`, `POS_DATABASE_USER`, `POS_DATABASE_PASSWORD` | server | PostgreSQL connection |
| `POS_PORT` | server | default 8080 |
| `POS_ENROLLMENT_SECRET` | server | gates device registration; unset ⇒ unauthenticated (warns) |
| `MOMO_WEBHOOK_SECRET`, `MOMO_PROVIDER` | server | webhook HMAC; unset ⇒ 503 |
| `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_ANON_KEY`, `SUPABASE_DB_URL` | scripts only | never in the app bundle |
| `EXPO_PUBLIC_SUPABASE_URL`, `EXPO_PUBLIC_SUPABASE_ANON_KEY` | admin app | public values only |

`.env` files are git-ignored; use a secret store in deployed environments.

## Database setup

Provisioning and startup are **separate** operations, by design.

**Normal startup never writes schema.** `Application.main` connects, runs a
read-only check (`SchemaVerifier.requireProvisioned`) for the
`supabase_migrations` schema, the `schema_migrations` table and baseline version
`0000`, and exits with `STARTUP_FAILED` if they are absent. It never creates a
schema, creates a table, applies DDL, records migration history or runs a
migration — starting the server cannot change the database.

**Provisioning is explicit and separately confirmed.** There are two strictly
separate paths â€” a remote/Supabase one and a loopback-only local one â€” and both
end in the same transactional baseline apply. The remote one is:

**Remote / Supabase** (`provisionDatabase`) requires a Supabase pooler host
(`aws-<n>-<region>.pooler.supabase.com`), port 5432 or 6543, `sslmode=require`,
and a pooler login whose project suffix matches `POS_EXPECTED_TARGET`. It is the
only task that writes to a remote database.

```bash
cd server
PROVISION_CONFIRM=yes \
POS_DATABASE_URL='jdbc:postgresql://<host>:<port>/postgres' \
POS_DATABASE_USER='postgres.<project-ref>' \
POS_DATABASE_PASS='<secret, never logged>' \
POS_EXPECTED_TARGET='<same 20-char project-ref>' \
./gradlew provisionDatabase
```

The run validates everything **before opening a connection**, then:

1. rejects any `PROVISION_CONFIRM` other than the exact string `yes`;
2. rejects a missing, malformed or credential-bearing `POS_DATABASE_URL`
   (there is no default target, and the database must be `postgres`);
3. requires `POS_EXPECTED_TARGET`, because a pooler hostname is shared by every
   project in a region and identifies nothing on its own;
4. requires the pooler username `postgres.<project-ref>` to *match* that
   expected target — the only per-project signal a JDBC URL carries;
5. connects, confirms `current_database()` is `postgres`, applies the baseline
   and records version `0000` **in one transaction**, rolls back on any failure,
   and re-reads the baseline before reporting success.

Re-running is safe: an already-provisioned database reports
`already_provisioned` and nothing is duplicated.

**Local PostgreSQL** (`provisionLocalDatabase`) is the developer-laptop path and
never touches a remote database:

```bash
cd server
LOCAL_PROVISION_CONFIRM=yes \
POS_LOCAL_DATABASE_URL='jdbc:postgresql://localhost:5432/business_store' \
POS_LOCAL_DATABASE_USER='business_store' \
./gradlew provisionLocalDatabase
```

It requires `LOCAL_PROVISION_CONFIRM=yes` (exactly), accepts only loopback hosts
(`localhost`, `127.0.0.1`, `::1`), refuses any remote host before opening a
connection, and does not need a Supabase project reference. Two different
confirmation variables keep the paths from being used interchangeably.

After connecting, the remote path re-reads the **session's own**
`current_user`, `current_database()` and `inet_server_addr()/port` and only then
reports `database_identity=confirmed` â€” a matching username in the configuration
is not treated as proof of the connected project.

Verify the baseline exists:

```sql
select version, name from supabase_migrations.schema_migrations order by version;  -- expect 0000
```

Then start the server normally with `POS_DATABASE_URL` / `POS_DATABASE_USER` /
`POS_DATABASE_PASSWORD`.

### Applying migrations 0001–0009

The admin surface is migrations `0001`–`0009` and is a **separate, deliberate
step**. `provisionDatabase` never applies them:

```bash
supabase db query --linked --file supabase/migrations/0001_admin_foundation.sql
# … in filename order through 0009
```

Verify with `server/tools/ProbeState.java` (read-only) and
`CleanReplayMigrationTest` / `AdminMigrationTest`.

**Staging has not been provisioned and no migration has been applied there.**
The read-only identity probe confirmed connectivity and a read-only transaction
only; staging still reports `migration_history: not_initialized`. Provisioning
staging requires separate explicit authorization.
## API documentation summary

- Device API: `/health`, `/devices`, `/sync`, `/sync/pull`, `/reports/*`,
  `/payments/mobile-money/*` — see `API.md`.
- Admin API: Supabase PostgREST tables/views plus RPCs (`is_admin`,
  `admin_update_product_price`, `admin_reconcile_mobile_money_payment`,
  `admin_create_hire_purchase_contract`, …).

## AI agents overview

There are **no runtime agents**. Development-time agent conventions, guardrails
and handoff rules live in `AGENTS.md`; MCP integration is documented (and absent)
in `MCP.md`.

## Usage instructions

1. Register the till (`POST /devices`) and store its token securely.
2. Cashier logs in with a PIN bound to that device.
3. Add products, choose a payment method, checkout, show the receipt.
4. Admin signs in to the mobile app to review the dashboard, manage catalog,
   customers, hire purchase and MoMo reconciliation.

## Example workflows

- **Offline sale → sync:** sell without network → outbox pending → `POST /sync`
  when online → other tills pull the sale on next `/sync/pull`.
- **Catalog change:** admin edits price → RPC writes row + audit → tills pull the
  upserted product.
- **MoMo reconciliation:** admin pastes provider reference + amount → RPC matches
  or refuses; retry is a no-op.

## Contributing

- Keep money in integer pesewas and business rules in `src/domain` or SQL.
- New database changes need a migration **and** its rollback plus a test.
- Do not weaken RLS, checks or tests to make a suite pass.
- Run the narrowest covering check before finishing; report what did not run.

## Future improvements and roadmap

Till UI completeness (voids/refunds, MoMo screens, inventory screens), Capacitor
packaging with an Android SQLite binding, ESC/POS byte encoding, live payment
provider credentials, MFA and server-side login, TLS/HSTS, GRA E-VAT, load and
OWASP testing, then a single-shop pilot. Details in `docs/roadmap.md` and
`PLAN.md`.

## License

No license file exists in this repository; all rights are reserved by the owner.
Add a license before distributing.
