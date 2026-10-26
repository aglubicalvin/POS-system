# Administrator Mobile App (Expo / React Native / TypeScript)

Phase 4.1 foundation of the dedicated administrator app for iOS and Android.
It talks to the SAME Supabase project (PostgreSQL, auth, RLS) that backs the
web cashier POS and the Kotlin POS server — no separate database, no
client-side business rules.

## What works today (Phase 4.1)

- Administrator sign-in with password, and the TOTP second factor when the
  profile row requires it (`mfa_required`).
- Server-verified authorization: the app only unlocks after the database (via
  RLS + `is_admin()`) returns a profile proving `role = 'admin'`. Non-admin
  sessions are signed out immediately and every screen re-runs the gate.
- Session tokens stored in the device keystore/keychain (`expo-secure-store`),
  restored and re-verified on cold start.
- Business overview dashboard: today / this-week / this-month revenue, gross
  profit, discounts, tax, refunds and sales counts, plus a low-stock list.
  Pull-to-refresh shows the data timestamp.
- Backend migration `supabase/migrations/0001_admin_foundation.sql`:
  `profiles` table, admin RLS policies on the canonical POS tables, and the
  `dashboard_sales_daily` / `low_stock_overview` views.

Later phases (4.2+) add: product/category/price management, serial/IMEI
lookup, approval workflows, cashier account management, audit-log viewer,
push notifications, biometric app-unlock, and device/session revocation.

## Local development

```bash
cd admin-mobile
npm install

# configure your Supabase project (public client values only)
copy .env.example .env      # then edit both EXPO_PUBLIC_ values

npm start                    # Expo dev server
npm run ios                  # iOS Simulator
npm run android              # Android Emulator
npm run typecheck            # tsc --noEmit
npm test                     # vitest: auth gate + sales-summary logic
```

Create the admin account: add the auth user in the Supabase dashboard (or
CLI), then run the commented seed block at the bottom of
`0001_admin_foundation.sql` once with your email, and delete the block.

To require MFA for that administrator: `update profiles set mfa_required =
true where role = 'admin';` and register a TOTP factor in-app or via
`supabase.auth.mfa.enroll`.

## Environments

| Environment | Config source                                                   |
|-------------|------------------------------------------------------------------|
| Local dev   | `admin-mobile/.env` (`EXPO_PUBLIC_*`, never committed)           |
| Test        | CI env vars with the test project URL + anon key                 |
| Production  | EAS build secrets or `app.config` env injection at build time    |

The anon key is a public key; embedding it is expected. NEVER embed a
Supabase service-role key, database password or payment-provider secret in
the mobile bundle.

## Where the security actually lives

- `<Business Store>/supabase/migrations/0001_admin_foundation.sql` — RLS
  policies and the `is_admin()` helper. Unauthorized users cannot read the
  sales tables or the dashboard views, even calling the REST API directly.
- `src/lib/auth-gate.ts` — the pure gate every screen consumes
  (`evaluateAdminAccess`); unit-tested in `tests/auth-gate.test.ts`.
- `src/context/AuthContext.tsx` — the state machine: password → profile
  fetch → (optional) MFA → authorized, denying and signing out anything else.

## Tests

`npm test` runs the pure-logic suites (auth gate, sales-window aggregation,
money formatting) in node. Backend/RLS behavior is asserted against real
PostgreSQL in the Kotlin server's test suite wherever behavior belongs to
the server.
