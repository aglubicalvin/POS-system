# SETUP — Setup and Installation

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

```
src/ server/ supabase/ admin-mobile/ scripts/ tests/ contract/ docs/
```

## 5. Environment variables

**Server** (`server/.env` or the process environment):

```env
POS_DATABASE_URL=jdbc:postgresql://localhost:5432/business_store
POS_DATABASE_USER=business_store
POS_DATABASE_PASSWORD=change-me
POS_PORT=8080
POS_ENROLLMENT_SECRET=change-me        # unset ⇒ device registration is open
MOMO_WEBHOOK_SECRET=change-me          # unset ⇒ webhook returns 503
MOMO_PROVIDER=mtn-momo
```

**Scripts / E2E** (repo-root `.env`, git-ignored):

```env
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_SERVICE_ROLE_KEY=<service role key>   # scripts only — never in the app
SUPABASE_ANON_KEY=<anon key>
SUPABASE_DB_URL=postgresql://postgres.<ref>:<password>@aws-0-<region>.pooler.supabase.com:6543/postgres
```

**Admin app** (`admin-mobile/.env`, git-ignored):

```env
EXPO_PUBLIC_SUPABASE_URL=https://<project-ref>.supabase.co
EXPO_PUBLIC_SUPABASE_ANON_KEY=<anon key>
```

Public values only. The service-role key must never appear here.

## 6. Database setup

Local PostgreSQL:

```bash
createdb business_store
psql business_store -f server/src/main/resources/db/schema.sql   # canonical (0000)
```

Supabase project (dev/verify):

```bash
supabase projects list
supabase db query --project-ref <ref> --linked --file server/src/main/resources/db/schema.sql
for f in 0001_admin_foundation 0002_product_management 0003_dashboard_aggregation_and_authz \
         0004_rpc_search_path_hardening 0005_customers_payroll_hirepurchase \
         0006_phase43_workflows 0007_mobile_money_reconciliation \
         0008_authorize_admin_policies 0009_phase47_faults; do
  supabase db query --project-ref <ref> --linked --file "supabase/migrations/$f.sql"
done
```

The baseline must come first: 0001 references the canonical POS tables. Verify
afterwards:

```bash
cd server/tools && javac ProbeState.java
CHECK_URL=jdbc:postgresql://<host>:5432/postgres CHECK_USER=… CHECK_PASS=… \
PROBE_CONFIRM=yes java -cp .:<postgresql.jar> ProbeState
```

Expected: 30 tables, 6 views, 20 policies, 12 functions, 5 triggers.

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
npx tsc --noEmit && npx vitest run          # 109 passed / 1 skipped
cd admin-mobile && npx vitest run           # 29 passed
cd server && ./gradlew test                 # full Kotlin suite
cd ../ && npm run e2e:supabase              # 43 checks against a test project
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
