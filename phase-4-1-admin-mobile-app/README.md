# Phase 4.1 — Administrator Mobile App: Foundation

The dedicated Expo / React Native / TypeScript administrator app
(`admin-mobile/`), plus the shared-backend access layer
(`supabase/migrations/0001_admin_foundation.sql`). Same version-controlled
project as the till; same Supabase/PostgreSQL data as the Kotlin server — no
duplicated database, no client-side business rules.

## Scope

- **Secure admin login + logout** with server-verified role: the app only
  unlocks after RLS + `is_admin()` return a profile proving `role='admin'`;
  non-admins are signed out immediately and every screen re-runs the gate
  (`admin-mobile/src/lib/auth-gate.ts`, `src/context/AuthContext.tsx`).
- **MFA-required administrators**: TOTP challenge enforced from the backend
  assurance level (aal2) before access.
- **Secure token storage**: sessions persist to the device keystore/keychain
  via `expo-secure-store` — never AsyncStorage, never plain storage.
- **Business overview dashboard**: today / this-week / this-month revenue,
  gross profit, discounts, tax, refunds, sales counts, low-stock list.
- **RLS layer**: admin read-only policies on the canonical POS tables and
  `dashboard_sales_daily` / `low_stock_overview` views. Unauthorized users
  (even calling the API directly) get nothing.
- **Tests**: 13 pure-logic suites for the gate and summary windows
  (`admin-mobile/tests/`).

## How to verify

```bash
cd admin-mobile
npm install && cp .env.example .env   # set EXPO_PUBLIC_SUPABASE_*
npm test && npm run typecheck
npm start                             # iOS/Android emulator
```

Apply `supabase/migrations/0001_admin_foundation.sql` after the Kotlin
server has created the canonical tables; seed the first admin with the
commented block at the bottom of that file.
