# INTEGRATIONS — External Integrations

Only three external systems are actually integrated today. Everything else is
listed as planned so the boundary between "implemented" and "wanted" stays clear.

## 1. Inventory

| Service | Purpose | State |
| --- | --- | --- |
| Supabase (PostgreSQL + Auth + PostgREST) | Admin data, auth, RLS-enforced API | **integrated** |
| Mobile-money provider (MTN/Telecel via aggregator) | Payment initiation, signed webhook | **adapter + webhook integrated; no live credentials** |
| Expo / EAS | Build and distribute the admin app | **integrated (dev)** |
| GRA E-VAT | Tax reporting | planned, see `docs/ghana-compliance.md` |
| Email/SMS, cloud storage, analytics, OpenAI | none | not used, not planned |

## 2. Supabase

**Purpose.** Hosts the authoritative database for the admin surface, issues admin
JWTs, exposes PostgREST, and enforces authorization with RLS.

**Setup.**
1. Create a project (region closest to the store; London was used for validation).
2. Apply `server/src/main/resources/db/schema.sql` (baseline `0000`), then
   migrations `0001`–`0009` in order.
3. Copy `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY` into the
   repo-root `.env` (scripts only) and `EXPO_PUBLIC_*` into `admin-mobile/.env`.
4. Create admin users in Auth, then insert their `profiles` rows with
   `role='admin'`, `is_active=true` (and `is_supervisor` where needed).

**Official docs.** https://supabase.com/docs (Auth, Row Level Security, REST,
Database functions).

**Environment variables.** `SUPABASE_URL`, `SUPABASE_ANON_KEY`,
`SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_DB_URL`, `EXPO_PUBLIC_SUPABASE_URL`,
`EXPO_PUBLIC_SUPABASE_ANON_KEY`.

**Usage example.**

```ts
const { data } = await supabase
  .from('dashboard_sales_daily')
  .select('day,revenue,gross_profit')
  .order('day', { ascending: false })
  .limit(30);
```

**Project-specific use.** Dashboard roll-ups, product/customer/hire-purchase
reads, RPC writes (price, category, media, reconciliation, contracts,
installments, approval queues), fault lifecycle, E2E seeding/cleanup.

**Quotas and cost.** Free tier: 2 active projects, shared CPU, 500 MB database,
limited egress. Validation used two projects (dev + verify); a third project was
not created, so the recovery drill could not use a fresh target.

**Security.** Service-role key is server/CI only; anon key is public. RLS is the
authorization layer; `security_invoker` views inherit it.

## 3. Mobile money (MTN / Telecel via aggregator)

**Purpose.** Take MoMo payments and reconcile them against provider statements.

**State.** `MobileMoneyService` implements initiate → webhook → status with an
HMAC-signed callback. No provider account is connected; the ledger, idempotency
keys and reconciliation RPC are ready.

**Setup (when a provider is chosen).**
1. Obtain sandbox credentials from the aggregator (API key, merchant id, callback
   secret).
2. Configure the adapter on the till host and the server's
   `MOMO_WEBHOOK_SECRET`/`MOMO_PROVIDER`.
3. Point the provider's callback URL at `POST /payments/mobile-money/webhook`.
4. Run a sandbox transaction end to end; confirm the payment lands as `confirmed`.

**Environment variables.** `MOMO_WEBHOOK_SECRET`, `MOMO_PROVIDER`, plus adapter
credentials held outside the repo.

**Usage.** `POST /payments/mobile-money/initiate` then poll
`GET /payments/mobile-money/{id}`; the webhook confirms. The till never marks a
payment successful on its own.

**Error handling.** `503` when the secret is unset (refuse unsigned callbacks),
`401` bad signature, `409` conflicting terminal state, retries safe because the
webhook is idempotent.

**Cost.** Aggregator fees per transaction; sandbox is free.

**Monitoring.** Server logs webhook rejections; reconciliation mismatches surface
in the admin app. There is no external alerting yet.

## 4. Expo / EAS

**Purpose.** Run and build the admin app.

**Setup.** `cd admin-mobile && npm install && npm start`; `app.json` holds the app
identity. EAS builds are not configured (no `eas.json`).

**Environment.** Only `EXPO_PUBLIC_*` values are embedded.

## 5. Planned: GRA E-VAT

Out of scope. Would require certification details, invoice numbering rules and a
submission client; tax fields (`tax_rate_bp`, `tax_total`) already exist so
reporting can be produced. See `docs/ghana-compliance.md`.

## 6. Not used

OpenAI/LLM APIs, email (no transactional sender), SMS, object storage (images are
URLs), analytics, error tracking (Sentry/LogRocket not installed). Adding any of
these is a new integration and must be recorded here with credentials, quotas,
security review and a failure mode.

## 7. Secrets and access control

- Secrets live in `.env` (git-ignored) locally and in a managed secret store when
  deployed.
- The service-role key never ships to a client; the anon key is safe to ship
  because RLS decides access.
- Rotate on exposure; audit tracked files and Git history (`git grep` for key
  prefixes) before a release.

## 8. Integration testing

`npm run e2e:supabase` exercises the real Supabase REST/Auth path (43 checks,
self-seeding and self-cleaning). The provider path is covered by
`MobileMoneyApiTest` (signed/tampered webhooks, idempotency, conflicts) without
network access.

## 9. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `SKIPPED` from the E2E script | credentials missing | set the four Supabase values |
| `permission denied for table` | RLS policy/role mismatch | inspect `pg_policies`, use an admin profile |
| `Invalid API key` | user JWT in the `apikey` slot | `apikey` = anon key; `Authorization` = user JWT |
| Expo blank screen | env vars missing | create `admin-mobile/.env` |
| Webhook 503 | secret unset | set `MOMO_WEBHOOK_SECRET` |
| Duplicate reconciliation rows | retried without idempotency key | the RPC is idempotent; check the reference used |
