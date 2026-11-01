# AUTHENTICATION — Authentication and Security

There are two independent identity systems: **till devices/cashiers** (owned by the
Kotlin server) and **administrators** (Supabase Auth). They never share tokens.

## 1. Approach

| Actor | Credential | Stored as | Verified by |
| --- | --- | --- | --- |
| Till device | Bearer token from `POST /devices` | `device_tokens.token_hash` (hash only) | `DeviceService.authenticate` |
| Cashier | PIN, bound to a device | Argon2id hash in `users.pin_hash` | till domain (local) |
| Manager/Admin (till) | same PIN + role | `users.role` | domain role checks |
| Administrator (mobile) | Supabase email/password | GoTrue (`auth.users`) | JWT → `is_admin()` |
| Supervisor | admin **and** `profiles.is_supervisor` | `profiles` | `is_supervisor()` |
| Payment provider | HMAC over raw body | `MOMO_WEBHOOK_SECRET` env | `MobileMoneyService` |

## 2. Roles and permissions

`profiles.role ∈ {cashier, manager, admin}`, plus `is_active` and `is_supervisor`.
Effective capabilities:

| Capability | Enforcement point |
| --- | --- |
| Read admin data | RLS policies using `public.is_admin()` |
| Write admin data | RPCs that raise `only administrators may …` |
| Post/escalate/deny/close faults | RLS `faults_supervisor_all` + `is_supervisor()` + transition trigger |
| Block/unblock a payment | `SECURITY DEFINER` trigger only (no client write path) |
| Process approvals | `process_approval_queues(p_user_id)` restricted to the caller |
| Till permissions | `src/domain/permissions.ts` (discount ceilings, overrides) |

An inactive profile is not an admin: `is_admin()` requires `is_active`.

## 3. User registration

- Till users are created by an admin in the till/server flow (hashed PIN; hashed
  password if set). `user.upserted` events carry **hashes only**, never secrets.
- Admin users are created with the Supabase dashboard/Admin API plus a `profiles`
  row. There is **no self-service signup** in the admin app.

## 4. Email verification

Handled by Supabase Auth for admin accounts. The app does not implement a custom
verification screen; unverified accounts cannot receive a role-bearing profile in
practice. Tests create pre-confirmed users (`email_confirm: true`).

## 5. Login flow (admin app)

1. `signInWithPassword` via Supabase JS (anon key + user credentials).
2. Session persisted through the **SecureStore** adapter (`lib/storage.ts`), with
   `autoRefreshToken: true`.
3. `AuthContext` reads `profiles` for the user: `role` and `is_active` must both
   pass to reach `status === 'authorized'`.
4. `(admin)/_layout.tsx` re-checks the gate on **every** navigation; deep links and
   hardware back cannot bypass it.
5. `signOut` clears the session.

## 6. Password reset and social login

- Password reset: available through Supabase's standard flow; the app has no
  custom reset screen yet (email link only).
- Social login (Google/GitHub): **not implemented**. Adding it would require
  provider configuration in Supabase and no server change.

## 7. Token management

- Device tokens: random, returned once, hashed at rest, with `expires_at`;
  `DeviceService.touch` updates `last_seen_at` on each authenticated call.
- Admin JWTs: short-lived access token + refresh token, refreshed automatically;
  persisted only in the device keystore.
- Webhooks: no token; HMAC signature over the exact body, constant-time compare.

## 8. Protected routes and middleware

- Server: every route except `/health`, `/devices` and the webhook calls
  `authenticate(devices)`; missing/invalid token ⇒ `401`. A device may only submit
  events carrying its own `deviceId` (`403` otherwise).
- Admin app: route group gate + `Redirect` to `/login`.
- Database: RLS is the final authority for every table and view.

## 9. Role-based access control

```sql
create or replace function public.is_admin() returns boolean
language sql stable security definer set search_path = "" as $$
  select exists (select 1 from profiles where id = auth.uid() and role = 'admin' and is_active);
$$;

create or replace function public.is_supervisor() returns boolean … is_admin() and is_supervisor …
```

19 of 20 policies target `authenticated`; the one `{public}` policy
(`profiles_self_read`) filters on `auth.uid() = id` and is verified to return zero
rows to anon.

## 10. Profile management and deactivation

- Profile fields: `full_name`, `role`, `is_active`, `mfa_required`, `is_supervisor`.
- Deactivating a profile immediately removes admin capability (`is_admin()` false)
  while preserving audit history. There is no RLS write path for profiles — changes
  are privileged SQL or server-side.
- Account deletion: deleting the `auth.users` row cascades to `profiles`.

## 11. Password/PIN hashing

- PINs: Argon2id (`node:crypto`), OWASP defaults (19 MiB, 2 passes, 1 lane); legacy
  scrypt hashes verify and are upgraded on successful login.
- Passwords: the server stores **no** password hashes; admin passwords live in
  GoTrue. The till never receives an admin password.
- Constants live in one struct so they can be tuned for low-end Android.

## 12. Rate limiting and CSRF

- Device API: **no rate limiting or body-size limit yet** (documented gap). Device
  PIN lockout (`failed_attempts`, `is_active`) covers brute force at the till.
- Admin API: platform-level protections from Supabase.
- CSRF: not applicable to bearer-token APIs; no cookie-authenticated state changes.

## 13. Environment variables

`POS_ENROLLMENT_SECRET`, `MOMO_WEBHOOK_SECRET`, `MOMO_PROVIDER`,
`EXPO_PUBLIC_SUPABASE_URL`, `EXPO_PUBLIC_SUPABASE_ANON_KEY`,
`SUPABASE_SERVICE_ROLE_KEY` (scripts only). See `SETUP.md` §5.

## 14. Authentication testing

| Test | Where |
| --- | --- |
| admin gate redirect | `admin-mobile/tests/auth-gate.test.ts` |
| anon denied on tables/views/faults/queues | E2E negative block (43 checks) |
| plain admin cannot escalate/deny faults | E2E (zero-rows via `return=representation`) |
| `is_admin()` semantics and EXECUTE grants | `AdminMigrationTest` |
| supervisor authorization + transition guard | `CleanReplayMigrationTest` |
| signed webhook accepted, tampered rejected | `MobileMoneyApiTest` |
| PIN lockout and Argon2id upgrade | `tests/*` till domain suite |

## 15. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Admin app bounces to login | profile missing/inactive, or `role` not admin | check `profiles` row |
| `permission denied for function` | `SECURITY DEFINER` missing EXECUTE grant | grant to `authenticated` |
| REST returns `[]` for an admin | JWT user has no active admin profile | fix the profile, not the policy |
| `Invalid API key` | user JWT sent as `apikey` | `apikey` must be anon/service key |
| Webhook 503 | `MOMO_WEBHOOK_SECRET` unset | set it; never accept unsigned callbacks |
| Device 401 after deploy | token expired or rotated | re-register with the enrollment secret |
