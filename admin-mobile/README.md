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
npm run expo:compat          # blocking Expo SDK baseline check (offline)
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

## Expo SDK compatibility baseline

`expo-compat-baseline.json` records the package versions that the installed
Expo SDK considers compatible (`node_modules/expo/bundledNativeModules.json`),
anchored to the exact `expo` version in `package.json`. It is committed,
reviewed like source code, and it is the only input to the blocking
compatibility check.

Why it exists: `expo-doctor` asks `api.expo.dev` for its expectations at run
time. Expo publishes new patches most days, so the same commit can be green
today and red tomorrow without a line changing. The baseline makes "are our
dependencies compatible with SDK 58?" a property of the repository instead of
a property of the calendar.

What `npm run expo:compat` verifies (offline, no network):

- The installed `expo` matches the baseline anchor, so the SDK matrix cannot
  change silently underneath the repository.
- The installed `expo/bundledNativeModules.json` still contains the committed
  ranges; drift there means the baseline must be regenerated.
- Every package declared in `package.json` is either covered by the matrix or
  recorded under `notInSdkMatrix` with a reason.
- Declared ranges are a subset of the SDK range, so any version an install
  could pick stays inside the SDK contract (`^58.0.32` fails against
  `~58.0.32`, even though both resolve to 58.0.32 today).
- Installed versions satisfy both the declared range and the SDK range, and a
  declared package missing from `node_modules` fails explicitly.
- Incomplete validation is a failure, never a silent pass: missing
  `node_modules`, an unreadable baseline or malformed JSON all exit non-zero.

CI runs this check as a blocking step. `expo-doctor` still runs in the same
job, pinned to a known version, but with `continue-on-error: true`: a red
advisory step means upstream published something worth looking at, and it
must not be the thing that decides whether a pull request is mergeable.

### Updating the baseline

Only when you intentionally move Expo versions:

```bash
cd admin-mobile
npm install                     # or: npx expo install <packages>
npm run expo:baseline:generate  # rewrites expo-compat-baseline.json
git diff expo-compat-baseline.json
```

Review every changed range in that diff before committing - that diff is the
contract CI enforces. The generator refuses to run in CI, and the baseline
must never be hand-edited to make a check pass. If the checker reports "SDK
metadata drift", the installed `expo` changed: regenerate, review, and commit
the baseline together with the dependency change.

### What "compatible" does and does not prove

- Passing the baseline check and the export proves the JavaScript side
  bundles and that declared dependencies match the SDK's own matrix.
- `npx expo export --platform android` produces a Hermes bundle; it does not
  compile native code.
- `npx expo prebuild` generates a native project; it does not prove that
  native code compiles.
- A real Android APK/AAB needs a Gradle build against the Android SDK, NDK and
  CMake. A real iOS build needs macOS with Xcode. Neither is produced on
  Windows, and no CI job in this repository produces them today, so nothing
  here is evidence of native build readiness.
