# SETUP — Setup and Installation

This document is a single source of truth for environment configuration, and it is
the first page a real operator, a reviewer, or an automated agent reads before
touching a staging target.

## 1. Prerequisites

| Tool | Version used | Needed for |
| --- | --- | --- |
| Node.js | 24+ (tested on 26.x) | till, scripts, Vitest; `node:sqlite` requires ≥ 22.5, 24+ recommended |
| npm | bundled with Node | dependencies |
| JDK | 21 (Temurin) | Kotlin server; `jvmTarget = JVM_21` |
| Gradle | wrapper 9.7.1 (checked in) | no local install needed |
| Kotlin | 2.4.21 (via Gradle plugin) | no local install needed |
| PostgreSQL | 18.6 | production/dev DB; tests download their own embedded binaries |
| Supabase CLI | 2.122+ | migrations, keys, project queries |
| Expo Go / SDK | Expo 58 | running the admin app |
| Docker Desktop | optional | only for `supabase db dump` (pg_dump runs in a container) |

The Kotlin test suite uses **zonky embedded PostgreSQL** (real PostgreSQL
binaries, no Docker), so `./gradlew test` works on a clean machine.

## 2. Recommended development environment

- **VS Code** with: ESLint, Prettier, TypeScript/JavaScript, Kotlin Language,
  Gradle for Java, Expo Tools, SQLTools (PostgreSQL), Markdown All in One.
- Windows: run commands from **Git Bash** (the repo assumes POSIX syntax).
- Recommended settings: format on save, `files.eol: "\n"` (CRLF has already broken
  SQL replay once — see `DEBUGGING.md`).

## 3. Installation

```bash
git clone <repo> && cd "Business Store"
npm install                    # till + scripts + vitest
cd admin-mobile && npm install # Expo app (separate package)
cd ../server && ./gradlew assemble   # pulls Kotlin/Ktor deps
```

## 4. Repository layout

```text
src/ server/ supabase/ admin-mobile/ scripts/ tests/ contract/ docs/
```

## 5. Environment variables

### Server

| Variable | Required | Default | Notes |
| --- | --- | --- | --- |
| `POS_LOCAL_DEV` | No | — | Locale-only flag; refuses to be left on when the process looks hosted. |
| `POS_DATABASE_URL` | Yes (unless `POS_LOCAL_DEV=yes`) | — | Production/dev JDBC URL; must be `verify-full` when it is a pooler. |
| `POS_DATABASE_USER` | Yes | — | `postgres.<project-ref>` for a project target; never printed. |
| `POS_DATABASE_PASSWORD` | Yes | — | Secret; never logged, never passed on the command line. |

There is no default database target. `POS_DATABASE_URL` is required unless
`POS_LOCAL_DEV=yes` is set, in which case the localhost defaults apply.
`POS_LOCAL_DEV` is refused when the process looks hosted, so a deployed service
can never quietly use a localhost database. A hosted deployment must set
`POS_DATABASE_URL` and `POS_DATABASE_USER` explicitly and fails closed otherwise.

### Staging environment contract (mandatory before the first staging identity probe)

The staging connection is **never inferred from a project reference, never
hardcoded, and never weaker than the four independent values below**. Omitting
any one of them refuses the connection before anything is run. This replaces the
old hardcoded `aws-0-eu-west-2.pooler.supabase.com` default in
`server/tools/probe-readonly.ps1`, and the old `CHECK_URL`-only contract in
`server/tools/ProbeState.java`.

| Variable | Required | Accepted value | Purpose |
| --- | --- | --- | --- |
| `POS_STAGING_PROJECT_REF` | Yes | 20-char `[a-z0-9]{20}` | Human-confirmed staging project reference. Project identity is still established by the administrator username suffix `postgres.<ref>` compared against this value; the pooler hostname is shared by every project in a region and identifies only the provider. |
| `POS_STAGING_POOLER_HOST` | Yes | `aws-<n>-<region>.pooler.supabase.com` (exactly one) | The shared pooler hostname, the only value consumed by both connection paths. It is validated against the same contract in the Kotlin module, in `ProbeState.java`, and in PowerShell. **Region must be confirmed by a human against the staging project's Supabase dashboard; it is never inferred from the project reference or from any previously used endpoint.** |
| `POS_STAGING_DB_ROOT_CERT` | Yes | readable PEM file path | Staged root certificate material. |
| `POS_STAGING_DB_ROOT_CERT_SHA256` | Yes | 64-hex (SHA-256) | **Independently supplied** fingerprint of that certificate's DER encoding. Obtained from a source other than the file itself (release notes, certificate record, provisioner output); never computed by trusting the file. |

Both TLS values mirror the provisioning contract:
`POS_DATABASE_ROOT_CERT` / `POS_DATABASE_ROOT_CERT_SHA256`. Public values only;
the file path is never logged and never artifacted.

**How the fingerprint is checked.** The provisioner and both staging paths decode
the PEM into its DER encoding and compare `sha256(DER(cert))` to the independently
supplied value. A mismatch, a missing value, or an unreadable file refuses the
target. There is no filesystem-only check: trusting the file alone would accept
*any* CA with the right name.

**TLS and pooler rules used everywhere:**

| Query / value | Accepted |
| --- | --- |
| `sslmode=verify-full` only | yes |
| `sslmode=prefer|require|allow|disable` | no |
| `sslfactory`, `sslhostnameverifier`, `sslrootcert` injected via URL | no |
| Hardcoded or region-inferred pooler host | no |
| Weaker TLS and `CHECK_URL`-only contract | no (both paths must use the shared contract) |

Exactly one `sslmode` is accepted. A URL may not carry two `sslmode` values, and
`sslfactory`, `sslcert`, `sslkey`, `socketfactory` and similar keys are rejected,
not stripped. A comma-separated multi-host authority is rejected.

### Scripts / E2E

```env
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_SERVICE_ROLE_KEY=<service role key>   # scripts only — never in the app
SUPABASE_ANON_KEY=<anon key>
SUPABASE_DB_URL=postgresql://postgres.<ref>:<password>@<pooler-host>:6543/postgres
```

The pooler host in `SUPABASE_DB_URL` must still be the shared `POS_STAGING_POOLER_HOST`
value — the region must appear there only because the operator confirmed it against
the dashboard, never because a script guessed it.

### Admin app

```env
EXPO_PUBLIC_SUPABASE_URL=https://<project-ref>.supabase.co
EXPO_PUBLIC_SUPABASE_ANON_KEY=<anon key>
```

Public values only. The service-role key must never appear here.

## 6. Database setup

There are two **separate** provisioning paths. Both create the canonical baseline
and record version `0000`; neither applies migrations `0001`-`0009`. Normal server
startup never creates schema — it only verifies the baseline exists and refuses to
start without it, so `psql ... -f schema.sql` on its own is no longer enough
(the tables appear but `0000` is never recorded).

### Local PostgreSQL (loopback only)

```bash
createdb business_store
cd server
LOCAL_PROVISION_CONFIRM=yes \
POS_LOCAL_DATABASE_URL='jdbc:postgresql://localhost:5432/business_store' \
POS_LOCAL_DATABASE_USER='business_store' \
POS_LOCAL_DATABASE_PASS='' \
./gradlew provisionLocalDatabase
```

`provisionLocalDatabase` requires `LOCAL_PROVISION_CONFIRM=yes` (exactly), accepts
only a loopback host (`localhost`, `127.0.0.1`, `::1`) and refuses a remote host
before opening a connection. An empty password is allowed for local trust/peer
auth. It does **not** require a Supabase project reference.

### Supabase project (staging/dev) — separate path

```bash
cd server
PROVISION_CONFIRM=yes \
POS_DATABASE_URL='jdbc:postgresql://aws-<n>-<region>.pooler.supabase.com:5432/postgres?sslmode=verify-full' \
POS_DATABASE_USER='postgres.<project-ref>' \
POS_DATABASE_PASS='<secret, never logged>' \
POS_EXPECTED_TARGET='<same 20-char project-ref>' \
POS_DATABASE_ROOT_CERT='./prod-ca-2021.crt' \
POS_DATABASE_ROOT_CERT_SHA256='<64-hex SHA-256 of that certificate>' \
./gradlew provisionDatabase
```

Download the CA from the Dashboard (**Database Settings -> SSL Configuration**); it is
the root the driver pins. The region `<n>`/`<region>` part of the pooler host
is **confirmed by the operator against the staging project's Supabase dashboard**
and is not inferred from the project reference.

The same four-value staging contract (`POS_STAGING_PROJECT_REF`,
`POS_STAGING_POOLER_HOST`, `POS_STAGING_DB_ROOT_CERT`,
`POS_STAGING_DB_ROOT_CERT_SHA256`) replaces the hardcoded pooler hostname and the
weaker-only production protocol for every staging connection that comes after
provisioning: `ProbeState.java`, `probe-readonly.ps1`, and the GitHub workflows.

### What is actually verified

| sslmode | Encrypted | Certificate chain | Hostname | Accepted here |
| --- | --- | --- | --- | --- |
| `disable` / unset / `require` | yes / no | no | no | **no** |
| `verify-ca` | yes | yes | no | **no** |
| `verify-full` + pinned root cert | yes | yes | yes | **yes** |

`require` encrypts but accepts *any* certificate — a man-in-the-middle could
present its own and collect the administrator credential. `verify-ca` checks the
chain but not the hostname. Only `verify-full` verifies both, and pinning the CA
means the chain must come from Supabase rather than any publicly-trusted CA.

The URL cannot be used to undo any of this. `POS_DATABASE_URL` accepts exactly
one query parameter, `sslmode`, and it must be `verify-full`:

- `sslfactory`, `sslfactoryarg`, `sslhostnameverifier`, `sslrootcert`, `sslcert`,
  `sslkey`, `socketfactory` and similar keys are **rejected**, not stripped. In
  particular `sslfactory=org.postgresql.ssl.NonValidatingFactory` would install a
  trust-all TrustManager and silently defeat `verify-full`.
- A second `sslmode` (for example `verify-full&sslmode=require`) is rejected;
  checking only the first occurrence would let the weaker value reach the driver.
- Any other query parameter is rejected rather than ignored.
- A comma-separated multi-host authority is rejected. pgJDBC fails over to the
  next host when the first is unreachable, so `localhost:5432,elsewhere` would
  connect to the second host while appearing to target the first.

The provisioner does not pass the URL to the driver at all: it builds the
connection from the validated host, port and database, then sets `sslmode` and
`sslrootcert` explicitly, leaving pgJDBC's validating default SSL factory in
place. The configured certificate must also be **readable** by the process, not
merely present.

### Identity vs role creation

- **Identity probe** (`ProbeState.java`, `execProbeState`, `probe-staging-identity.yml`):
  read-only audit of an already-provisioned project. It never creates, alters or
  deletes roles, queries no role management API, and never applies migrations.
- **Role creation** (`probe-readonly.ps1` with `-Apply`, `pos_probe_readonly.sql`):
  deliberate step that creates the `pos_probe_readonly` role on the *designated*
  staging project. It is gated by `-Apply`, requires the operator's explicit
  approval, and must never run against the development or the former verification
  project even by name.

Both paths require human approval before they touch a live database. The
provisioner, the probe, and the role-creation step are **separate operations**:
provisioning records the baseline, probing verifies it read-only, and role
creation adds the auditing role.

### Migration runner exit codes (`server/tools/ApplyMigration.java`)

`ApplyMigration` applies ONE file transactionally. The exit code says what was
proven; `migration_status` on stderr says what happened to the data.

| Exit | Meaning | `migration_status` |
| --- | --- | --- |
| 0 | the migration committed. `connection_disposal=none` means the connection was returned cleanly; `ABORTED`, `CLOSED_FALLBACK` or `DISPOSAL_UNCERTAIN` means the commit still stands but the connection could not be reset | `applied` |
| 2 | configuration rejected **before a connection existed**: bad or missing variables, unreadable file, or a file name that is not `<digits>_<name>.sql` | — |
| 3 | connected, but the database/role proves a different target than `MIGRATION_EXPECTED_TARGET`; no migration SQL ran | — |
| 4 | connection failure, migration failure, an already-recorded version, a concurrent version conflict, or history-insert failure | `already_applied`, `version_conflict`, `rolled_back`, `outcome_uncertain` or `error` |

**Exit 4 does not mean the migration was rolled back.** It means the run did
not commit. Read `migration_status`:

- `already_applied` — the version is already in
  `supabase_migrations.schema_migrations`. The runner checks this **after** the
  history bootstrap and **before** the file runs, so the migration SQL was not
  executed and the existing row (including its recorded SQL) is untouched.
  Nothing needed to be undone; do not re-run the file blindly.
- `version_conflict` — two runners started the same version while it was absent,
  and another one recorded it first. This run's history insert affected zero
  rows, so its migration SQL was rolled back and
  `error_category=migration_version_conflict` is printed. The version is applied
  exactly once; the loser's failed run is safe to ignore after confirming the
  winner committed.
- `rolled_back` — the rollback completed; no schema change or history row was
  kept.
- `outcome_uncertain` — the rollback itself failed (also
  `rollback_failed=true`), so the server may still hold the transaction. The
  runner aborts the connection instead of re-enabling autocommit, and
  `connection_disposal` reports whether the abort worked (`ABORTED`), only the
  fallback close worked (`CLOSED_FALLBACK`), or neither did
  (`DISPOSAL_UNCERTAIN`). Inspect the database by hand; never re-run
  automatically.
- `error` — no migration ran (connection or pre-flight failure).

A committed migration whose connection cleanup failed exits 0, never 4, and
prints both `migration_status=applied` and the `connection_disposal` outcome.
Migration status and connection-disposal status are separate: one describes the
DATA, the other the CONNECTION.

A migration is never reported as applied twice. The runner serialises on the
history primary key: the first runner to insert the row commits, and any other
runner with the same version fails after its insert affects zero rows, before it
commits its own schema changes. There is no automatic retry in any outcome.

## 7. API configuration

- Device API base URL is wherever the server runs (`http://localhost:8080`).
- CORS is not configured; the till is served by the server itself.
- No API gateway: register devices with `POST /devices`, store the token securely.

## 8. AI agents and MCP

No agents or MCP servers are used at runtime or required for setup. Conventions
are in `AGENTS.md`; the absence of MCP and how to add one is in `MCP.md`.

## 9. External integrations

- Supabase (database, auth, REST) — required for the admin app.
- Mobile-money provider — adapter exists; live credentials are not required to run
  the system (webhook returns 503 without `MOMO_WEBHOOK_SECRET`).
- Expo/EAS — only for building/distributing the app.

## 10. Running locally

```bash
npm run demo        # domain walkthrough, no server needed
npm run till        # cashier UI (serves src/ui/static)
cd server && ./gradlew run     # :8080
cd admin-mobile && npm start   # Expo dev server
npm run e2e:supabase           # only with a test project's credentials set
```

## 11. Verification

```bash
npx tsc --noEmit && npx vitest run          # root typecheck + unit tests
cd admin-mobile && npx vitest run           # admin-mobile unit tests
cd server && ./gradlew test                 # full Kotlin suite (embedded PG)
cd ../ && npm run e2e:supabase              # only with a test project's credentials set
```

## 12. Common setup issues

| Symptom | Cause | Fix |
| --- | --- | --- |
| `node:sqlite` not found | Node too old | Use Node 24+ |
| `:server:test` task not found | Single-module Gradle project | Run `./gradlew test` from `server/` |
| `relation "sales" does not exist` during 0001 | canonical baseline missing | Apply `schema.sql` first (`0000`) |
| `Invalid API key` on authenticated REST calls | user JWT sent as `apikey` | `apikey` = anon/service key; identity goes in `Authorization: Bearer <user jwt>` |
| `UPDATE requires a WHERE clause` | unfiltered PostgREST PATCH | filter with `?id=eq.…` |
| `prepared statement "S_1" already exists` | transaction pooler reuse | add `prepareThreshold=0` (ProbeState does) |
| `supabase db dump` fails on Docker pipe | Docker Desktop not running | start Docker, or use SQL-level backups |
| Expo app shows blank screen | missing `EXPO_PUBLIC_*` vars | create `admin-mobile/.env` from `.env.example` |
| Gradle build hangs on first run | downloading PG binaries | wait once; later runs are cached |
| `npm run e2e:supabase` prints SKIPPED | credentials absent | set `SUPABASE_URL` + `SUPABASE_SERVICE_ROLE_KEY` (test project only) |
