# ARCHITECTURE — System Architecture

## 1. Stack (with reasoning)

| Layer | Choice | Why |
| --- | --- | --- |
| Till domain | TypeScript, pure functions (`src/domain/*`) | Money, tax, discounts and stock must be testable without a UI or a database |
| Till storage | `node:sqlite` (Node ≥ 24) | Zero-install, synchronous, transactional; a WASM/OPFS binding is the Android path |
| Till UI | Plain HTML/CSS + one ES module (`src/ui/static`) | No build step, no framework churn, works offline from disk |
| Sync | Outbox + ordered per-device `seq`, idempotency keys | Exactly the semantics needed to make retries safe on flaky networks |
| Server | Kotlin 2.4 + Ktor 3.6 on JVM 21 | Typed DTOs, coroutine-free request scope, one deployable jar |
| Server DB | PostgreSQL 18 (`org.postgresql` + HikariCP) | Strong constraints, transactional event application, JSONB payloads |
| Backend platform | Supabase (Postgres + Auth + PostgREST + RLS) | The admin app gets an API with **database-enforced** authorization for free |
| Admin app | Expo SDK 58 / React Native 0.88 / expo-router | One codebase for Android + iOS + web; SecureStore for sessions |
| Tests | Vitest (TS), JUnit 5 + zonky embedded PostgreSQL (Kotlin) | Real PostgreSQL semantics without Docker |

## 2. Frontend architecture

**Till.** `index.html` is two views (login, sell). `app.js` wires events to
domain calls only — it never computes totals, discounts or tax. Sessions live in
memory for the shift; the device token is stored by the device layer.

**Admin mobile.** Expo Router with a guarded group:

```
admin-mobile/src/
  app/login.tsx            public
  app/(admin)/_layout.tsx  auth gate on every navigation
  app/(admin)/dashboard.tsx products.tsx customers.tsx
  app/(admin)/hire-purchase.tsx inventory.tsx payments.tsx pos.tsx
  context/AuthContext.tsx  session + profile (role/is_active) gate
  lib/supabase.ts          single client, SecureStore adapter, anon key only
  lib/queries.ts           dashboard read model
  lib/phase43.ts, product-management.ts, admin-products.ts, summary.ts
```

Screens call `lib/*` helpers; helpers call Supabase. No screen contains SQL or a
service key.

## 3. Data flow

**Sale (offline-first).**

```
cashier tap → domain.checkout() → SQLite transaction
   (sale, sale_items, payment, stock_movement, stock_on_hand, outbox event)
→ outbox pending → POST /sync (device bearer token) when online
→ EventApplier: claim event id → apply in one transaction → ack
→ GET /sync/pull on other tills (per-source cursors)
```

**Admin read.** Supabase JS → PostgREST → RLS policies → `is_admin()` →
rows. Views are `security_invoker` so they inherit exactly those policies.

**Admin write (catalog/price).** RPC (`admin_update_product_price`) runs
`SECURITY DEFINER` with `search_path = ""`, checks `is_admin()`, updates the row
and writes `product_price_audit` in the same transaction.

**Reconciliation.** Admin RPC matches a confirmed payment by provider reference +
amount, sets `reconciled_at`, is idempotent on retry, and refuses mismatches
without touching the row.

**Fault side effect.** Escalating a fault inserts an approval-queue row; posting a
defect sets `payments.blocked = true` via a `SECURITY DEFINER` trigger (the only
sanctioned writer of that flag), denial clears it.

## 4. Folder structure

```
.
├── src/                     # till: domain, db, sync, ui, scripts entry
│   ├── domain/              # money, checkout, stock, inventory, sales, users, …
│   ├── db/                  # SQLite schema + connection/migrations
│   ├── sync/                # outbox, ordering, apply, client, pull
│   └── ui/static/           # index.html, app.css, app.js
├── server/                  # Kotlin/Ktor
│   ├── src/main/kotlin/com/businessstore/pos/{api,db,domain,payments,reports,security,sync}
│   ├── src/main/resources/db/schema.sql   # canonical PostgreSQL DDL
│   ├── src/test/kotlin/…    # unit + embedded-PostgreSQL integration tests
│   └── tools/               # ApplyMigration, ProbeState, Check/Verify scripts
├── supabase/migrations/     # 0001–0009 + rollback scripts
├── admin-mobile/            # Expo app
├── scripts/                 # demo, till, contract emit, Supabase E2E
├── contract/                # generated cross-language event fixtures
├── tests/                   # Vitest suites
└── docs/                    # security audit, readiness, roadmap, this set
```

## 5. Database interaction

- Server: plain SQL with explicit transactions (no ORM) because the idempotency
  claim and stock upsert depend on exact `ON CONFLICT` semantics.
- `Schema.apply` runs the canonical DDL at startup and records `0000`.
- Migrations 0001–0009 layer the admin surface on top; each file is applied in
  one transaction and recorded in `supabase_migrations.schema_migrations`.
- Sync writes go through `EventApplier` only; every event is claimed by unique
  `event_id` before its effects are applied, so replays are no-ops.

## 6. Authentication and authorization

| Actor | Mechanism |
| --- | --- |
| Till device | Bearer token issued by `POST /devices`, hashed at rest (`device_tokens`), optional `X-Enrollment-Secret` |
| Cashier (till) | PIN bound to the device, Argon2id hash, lockout counter |
| Admin (mobile) | Supabase Auth session, `profiles.role = admin` **and** `is_active` required by `is_admin()` |
| Supervisor | additionally `profiles.is_supervisor` and `is_supervisor()` |
| Provider webhook | HMAC signature over the exact body; unset secret ⇒ 503 (never accept unsigned) |

All table access is RLS-gated; anon holds no useful access (fail-closed),
verified at REST.

## 7. API architecture

Device-facing REST (Ktor): `/health`, `/devices`, `/sync`, `/sync/pull`,
`/reports/{daily,stock,review-queue,discrepancies}`,
`/payments/mobile-money/{initiate,webhook,{id}}`. Admin-facing API is Supabase
PostgREST + five RPCs (see `API.md`).

## 8. External services

Supabase (hosted PostgreSQL, Auth, PostgREST), a mobile-money provider via an
adapter (no live credentials yet), Expo/EAS for builds. Nothing else is wired up.

## 9. AI architecture

None. The product contains no model calls, no agents and no MCP servers at
runtime. AI assistance is limited to development tooling (see `AGENTS.md`).

## 10. Environment configuration

| Variable | Consumer | Purpose |
| --- | --- | --- |
| `POS_DATABASE_URL/USER/PASSWORD` | server | PostgreSQL connection |
| `POS_PORT` | server | Listen port (8080) |
| `POS_ENROLLMENT_SECRET` | server | Gates `POST /devices`; unset ⇒ unauthenticated (warns) |
| `MOMO_WEBHOOK_SECRET`, `MOMO_PROVIDER` | server | HMAC secret + provider label |
| `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_ANON_KEY`, `SUPABASE_DB_URL` | scripts | E2E/administration only, never the app bundle |
| `EXPO_PUBLIC_SUPABASE_URL`, `EXPO_PUBLIC_SUPABASE_ANON_KEY` | admin app | Public config; anon key only |

## 11. Error handling

- Server maps domain exceptions to status codes: 400 validation, 401 auth/signature,
  403 foreign device, 404 payment, 409 payment conflict, 422 unknown product /
  invalid payload (event **not** claimed, so a corrected payload can be retried),
  503 webhook disabled, 500 unhandled (logged, generic body).
- Till: checkout failures surface inline; the sale is not written when the domain
  rejects it, and a failed event application never leaves partial rows (single
  transaction).
- Admin app: errors render as text next to the action; data already on screen is
  retained.

## 12. Security boundaries

1. **Till ↔ server**: device token + device-id binding on every event.
2. **Admin ↔ Supabase**: JWT + RLS; the service-role key never leaves the server
   or CI.
3. **Provider ↔ server**: HMAC over the raw body, constant-time compare.
4. **Database ↔ client**: clients never write canonical tables; only RPCs and
   sanctioned triggers mutate protected state.
5. **Secrets**: env/secret store only; `.env` is git-ignored and never in a bundle.

## 13. Testing and deployment

- Unit/domain: Vitest (109 passing, 1 skipped).
- Server: JUnit 5 over embedded PostgreSQL 18.6 — HTTP integration, sync, pull,
  contracts, events, money, Phase 4.3 workflows, admin migrations, clean replay
  with rollbacks.
- Admin app: Vitest over pure modules (summary, product management, auth gate).
- E2E: `scripts/e2e-supabase.ts` — 43 checks against a real Supabase project,
  self-seeding and self-cleaning.
- Deployment: `gradlew installDist`/jar for the server behind a reverse proxy
  (TLS is deployment work), migrations applied by `supabase db query --file` or
  the JDBC tool; production is currently **NO-GO** pending the gates in
  `docs/production-readiness.md`.
